"""Regression tests for fetch_model.py package-level staging and rollback.

All downloads are stubbed via monkeypatching fetch_model._download_to, so the
tests exercise the staging / verification / atomic-switch logic without
network access.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest import mock

CONVERT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONVERT_DIR))

import fetch_model  # noqa: E402

REV = "0123456789abcdef0123456789abcdef01234567"


def manifest(files: list[dict], package_id: str = "asr") -> dict:
    for f in files:
        f.setdefault("classification", "model")
    return {
        "schemaVersion": "2", "packageId": package_id, "version": "1.0.0", "status": "draft",
        "source": {"repoId": "x/y", "revision": REV, "upstreamModelId": "x/y", "licenseSource": "x"},
        "runtime": {"backend": "gguf-llama-cpp", "apiContractVersion": "1", "quantization": None,
                    "runtimeRevision": None,
                    "targetPlatforms": [{"platform": "android", "minSdk": 29, "abis": ["arm64-v8a"]}],
                    "streaming": False},
        "capabilities": {"modes": ["transcribe"], "languages": [], "verification": {}},
        "files": files, "roles": {files[0]["path"].split("/")[-1].split(".")[0]: files[0]["path"]}
        if files else {},
    }


def write_manifest(repo_root: Path, data: dict, package_id: str = "asr") -> None:
    mdir = repo_root / "shared" / "model-manifests"
    mdir.mkdir(parents=True, exist_ok=True)
    (mdir / f"{package_id}.json").write_text(json.dumps(data), encoding="utf-8")


def sha_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class FetchStagingTests(unittest.TestCase):
    def test_compiled_source_never_downloads_generated_names_or_executes_recipe(self) -> None:
        data = json.loads((CONVERT_DIR.parent / "shared/fixtures/valid/manifest-tts-built-draft.json").read_text())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old = root / "tts"
            old.mkdir()
            (old / "keep").write_bytes(b"old package")
            with mock.patch.object(fetch_model, "_download_to") as download, \
                 mock.patch.object(fetch_model.subprocess, "run") as command:
                self.assertEqual(fetch_model.install(data, root, None), 2)
            download.assert_not_called()
            command.assert_not_called()
            self.assertEqual((old / "keep").read_bytes(), b"old package")
            self.assertFalse((root / ".staging").exists())

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self._tmp.name) / "repo"
        (self.repo / "shared").mkdir(parents=True)
        self.addCleanup(self._tmp.cleanup)
        # point the module at the temp repo layout
        patcher = mock.patch.object(fetch_model, "REPO_ROOT", self.repo)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_fetch(self, dest: Path, package_id: str = "asr") -> int:
        return fetch_model.main(["--package", package_id, "--dest", str(dest)])

    def stub_downloader(self, blobs: dict[str, bytes]) -> None:
        def fake_download(repo_id: str, revision: str, repo_file: str, dest: Path) -> tuple[int, str]:
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(blobs[repo_file])
            return len(blobs[repo_file]), sha_of(blobs[repo_file])
        p = mock.patch.object(fetch_model, "_download_to", fake_download)
        p.start()
        self.addCleanup(p.stop)

    def test_second_file_bad_hash_keeps_old_package(self) -> None:
        """Exact review scenario: two files, the second has a bad hash. The old
        package must remain intact and nothing from the staging may leak in."""
        good_old_a, good_old_b = b"old-a", b"old-b"
        dest = self.repo / "dest"
        pkg = dest / "asr"
        pkg.mkdir(parents=True)
        (pkg / "a.bin").write_bytes(good_old_a)
        (pkg / "b.bin").write_bytes(good_old_b)

        new_a = b"new-a"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": len(new_a), "sha256": sha_of(new_a)},
            {"path": "asr/b.bin", "sizeBytes": 5, "sha256": "f" * 64},  # wrong hash for b"new-b"
        ]))
        self.stub_downloader({"a.bin": new_a, "b.bin": b"new-b"})

        code = self.run_fetch(dest)
        self.assertEqual(code, 1, "hash mismatch must exit 1")
        self.assertEqual((pkg / "a.bin").read_bytes(), good_old_a, "old file a must be untouched")
        self.assertEqual((pkg / "b.bin").read_bytes(), good_old_b, "old file b must be untouched")
        staging = dest / ".staging"
        self.assertEqual(list(staging.iterdir()) if staging.exists() else [], [],
                         "staging must be cleaned up")

    def test_second_file_bad_hash_leaves_fresh_dir_empty(self) -> None:
        dest = self.repo / "dest2"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(b"abc")},
            {"path": "asr/b.bin", "sizeBytes": 3, "sha256": sha_of(b"xyz")},
            {"path": "asr/c.bin", "sizeBytes": 1, "sha256": "0" * 64},
        ]))
        self.stub_downloader({"a.bin": b"abc", "b.bin": b"xyz", "c.bin": b"q"})
        code = self.run_fetch(dest)
        self.assertEqual(code, 1)
        self.assertFalse((dest / "asr").exists(), "no package dir may appear when verification failed")

    def test_pinned_size_mismatch_fails(self) -> None:
        dest = self.repo / "dest3"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 999, "sha256": sha_of(b"abc")},
        ]))
        self.stub_downloader({"a.bin": b"abc"})
        code = self.run_fetch(dest)
        self.assertEqual(code, 1, "pinned size must be verified, not just the hash")
        self.assertFalse((dest / "asr").exists())

    def test_symlink_escape_rejected(self) -> None:
        dest = self.repo / "dest4"
        outside = self.repo / "outside"
        outside.mkdir(parents=True)
        dest.mkdir(parents=True)
        (dest / "asr").symlink_to(outside)
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(b"abc")},
        ]))
        self.stub_downloader({"a.bin": b"abc"})
        code = self.run_fetch(dest)
        self.assertEqual(code, 1, "symlink escape must be refused")
        self.assertEqual(list(outside.iterdir()), [], "nothing may be written through the symlink")

    def test_install_failure_restores_previous_package(self) -> None:
        good_old = b"old-a"
        dest = self.repo / "dest5"
        pkg = dest / "asr"
        pkg.mkdir(parents=True)
        (pkg / "a.bin").write_bytes(good_old)
        new_a = b"new-a"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": len(new_a), "sha256": sha_of(new_a)},
        ]))
        self.stub_downloader({"a.bin": new_a})

        real_replace = __import__("os").replace

        def flaky_replace(src, dst):
            # fail only when installing the staged dir into the package slot
            if Path(dst) == pkg and ".staging" in str(src):
                raise OSError("simulated install failure")
            return real_replace(src, dst)

        with mock.patch.object(fetch_model.os, "replace", flaky_replace):
            code = self.run_fetch(dest)
        self.assertEqual(code, 2, "install failure is an environment error")
        self.assertEqual((pkg / "a.bin").read_bytes(), good_old,
                         "previous package must be restored when the switch fails")

    def test_success_replaces_package(self) -> None:
        dest = self.repo / "dest6"
        pkg = dest / "asr"
        pkg.mkdir(parents=True)
        (pkg / "a.bin").write_bytes(b"old")
        new_a, new_b = b"new-a", b"new-b"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": len(new_a), "sha256": sha_of(new_a)},
            {"path": "asr/b.bin", "sizeBytes": len(new_b), "sha256": sha_of(new_b)},
        ]))
        self.stub_downloader({"a.bin": new_a, "b.bin": new_b})
        code = self.run_fetch(dest)
        self.assertEqual(code, 0)
        self.assertEqual((pkg / "a.bin").read_bytes(), new_a)
        self.assertEqual((pkg / "b.bin").read_bytes(), new_b)
        self.assertFalse((dest / ".staging").exists())
        self.assertFalse((dest / ".trash").exists() and any((dest / ".trash").iterdir()),
                         "trash must be removed after a successful switch")

    def test_no_revision_refused(self) -> None:
        dest = self.repo / "dest7"
        m = manifest([{"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(b"abc")}])
        m["source"]["revision"] = None
        write_manifest(self.repo, m)
        code = self.run_fetch(dest)
        self.assertEqual(code, 4, "no pinned revision => refuse")

    def test_asset_pack_manifest_location(self) -> None:
        dest = self.repo / "dest8"
        new_a = b"abc"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(new_a)},
        ]))
        self.stub_downloader({"a.bin": new_a})
        pack_assets = self.repo / "android" / "asset_pack_asr" / "src" / "main" / "assets"
        with mock.patch.object(fetch_model, "REPO_ROOT", self.repo):
            # fetch_model resolves the pack path from REPO_ROOT; dest arg unused
            code = fetch_model.main(["--package", "asr", "--asset-pack"])
        self.assertEqual(code, 0)
        self.assertEqual((pack_assets / "asr" / "manifest.json").is_file(), True,
                         "pack manifest must be at assets/<packageId>/manifest.json")
        self.assertEqual(json.loads((pack_assets / "asr" / "manifest.json").read_text())["packageId"], "asr")

    def test_size_backfill_uses_measurement_before_directory_move(self) -> None:
        data = b"pinned-content"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": None, "sha256": sha_of(data)},
        ]))
        self.stub_downloader({"a.bin": data})
        dest = self.repo / "sizes"
        code = fetch_model.main(["--package", "asr", "--dest", str(dest), "--update-manifest"])
        self.assertEqual(code, 0)
        shared = json.loads((self.repo / "shared/model-manifests/asr.json").read_text())
        installed = json.loads((dest / "asr/manifest.json").read_text())
        self.assertEqual(shared, installed)
        self.assertEqual(installed["files"][0]["sizeBytes"], len(data))
        self.assertEqual(installed["status"], "draft")

    def test_missing_hash_refused_before_any_download(self) -> None:
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 3, "sha256": None},
        ]))
        with mock.patch.object(fetch_model, "_download_to") as download:
            self.assertEqual(self.run_fetch(self.repo / "unpinned"), 1)
        download.assert_not_called()

    def test_staging_and_lock_symlinks_are_rejected(self) -> None:
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(b"abc")},
        ]))
        self.stub_downloader({"a.bin": b"abc"})
        for name in (".staging", ".trash", ".locks"):
            with self.subTest(name=name):
                dest = self.repo / (name + "-case")
                outside = self.repo / (name + "-outside")
                dest.mkdir()
                outside.mkdir()
                (dest / name).symlink_to(outside, target_is_directory=True)
                self.assertEqual(self.run_fetch(dest), 1)
                self.assertEqual(list(outside.iterdir()), [])

    def test_busy_reader_lease_preserves_old_package(self) -> None:
        import fcntl
        dest = self.repo / "leased"
        (dest / "asr").mkdir(parents=True)
        (dest / "asr/a.bin").write_bytes(b"old")
        (dest / ".locks").mkdir()
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(b"new")},
        ]))
        with (dest / ".locks/asr.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
            self.assertEqual(self.run_fetch(dest), 2)
        self.assertEqual((dest / "asr/a.bin").read_bytes(), b"old")

    def test_interrupted_switch_restores_old_package_before_download(self) -> None:
        dest = self.repo / "interrupted"
        (dest / ".trash/asr-previous").mkdir(parents=True)
        (dest / ".trash/asr-previous/a.bin").write_bytes(b"old")
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(b"new")},
        ]))
        self.stub_downloader({"a.bin": b"bad"})
        self.assertEqual(self.run_fetch(dest), 1)
        self.assertEqual((dest / "asr/a.bin").read_bytes(), b"old")

    def test_local_import_pins_hash_without_promoting_release_source(self) -> None:
        source = self.repo / "source"
        source.mkdir()
        (source / "a.bin").write_bytes(b"abc")
        m = manifest([{"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(b"abc")}])
        m["source"]["revision"] = None
        write_manifest(self.repo, m)
        dest = self.repo / "local"
        with mock.patch.object(fetch_model, "_download_to") as download:
            code = fetch_model.main(["--package", "asr", "--dest", str(dest), "--local-source", str(source)])
        self.assertEqual(code, 0)
        download.assert_not_called()
        self.assertEqual(json.loads((dest / "asr/manifest.json").read_text())["status"], "draft")

    def test_manifest_moves_with_files(self) -> None:
        dest = self.repo / "coherent"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(b"abc")},
        ]))
        self.stub_downloader({"a.bin": b"abc"})
        replace = fetch_model.os.replace
        observed = []

        def inspect_swap(source, target):
            if Path(target) == dest / "asr":
                observed.append(json.loads((Path(source) / "manifest.json").read_text()))
            return replace(source, target)

        with mock.patch.object(fetch_model.os, "replace", inspect_swap):
            self.assertEqual(self.run_fetch(dest), 0)
        self.assertEqual(len(observed), 1)
        self.assertEqual(observed[0]["files"][0]["sha256"], sha_of(b"abc"))

    def test_copy_with_digest_matches_pinned_bytes(self) -> None:
        import file_integrity
        source = self.repo / "single-source.bin"
        source.write_bytes(b"acquired-bytes" * 1000)
        for stream in (False, True):
            with self.subTest(stream=stream):
                target = self.repo / f"copy-{stream}.bin"
                if stream:
                    patcher = mock.patch.object(file_integrity, "_try_clonefile", lambda a, b: False)
                    patcher.start()
                    self.addCleanup(patcher.stop)
                measured = fetch_model.copy_with_digest(source, target)
                self.assertEqual(measured, (len(source.read_bytes()), sha_of(source.read_bytes())))
                self.assertEqual(target.read_bytes(), source.read_bytes())

    def test_clone_does_not_propagate_immutable_source_flags(self) -> None:
        """clonefile copies BSD flags: a staged file carrying immutable or
        append-only flags could not be unlinked, replaced or removed, breaking
        staging cleanup and rollback. Flagged sources must therefore take the
        streaming copy, and the source itself must come out untouched."""
        if sys.platform != "darwin":
            self.skipTest("BSD file flags are darwin-only")
        import stat as stat_module
        for flag_name in ("UF_IMMUTABLE", "UF_APPEND"):
            with self.subTest(flag=flag_name):
                source = self.repo / f"flagged-{flag_name}.bin"
                target = self.repo / f"flagged-{flag_name}-target.bin"
                source.write_bytes(b"flagged-source")
                original = os.stat(source).st_flags
                flag = getattr(stat_module, flag_name)
                if original & (stat_module.SF_IMMUTABLE | stat_module.SF_APPEND):
                    self.skipTest("system flags cannot be toggled safely here")
                try:
                    os.chflags(source, original | flag)
                    measured = fetch_model.copy_with_digest(source, target)
                    self.assertEqual(measured, (len(b"flagged-source"), sha_of(b"flagged-source")))
                    self.assertEqual(target.read_bytes(), b"flagged-source")
                    target.unlink()  # the staged copy must be deletable/replaceable
                    self.assertFalse(target.exists())
                    self.assertEqual(source.read_bytes(), b"flagged-source")
                    self.assertEqual(os.stat(source).st_flags, original | flag)
                finally:
                    os.chflags(source, original)

    def test_transform_input_mismatch_fails_before_script_launch(self) -> None:
        m = {"source": {"transform": {"id": fetch_model.STQ_TRANSFORM, "inputPath": "mt/translator.gguf",
                                      "inputSha256": sha_of(b"audited-upstream-input")}},
             "files": [{"sha256": sha_of(b"derived")}]}
        staged = self.repo / "staged.gguf"
        staged.write_bytes(b"not-the-audited-input")
        with mock.patch.object(fetch_model.subprocess, "run") as command:
            with self.assertRaises(fetch_model.IntegrityError):
                fetch_model.apply_source_transform(m, staged, allow_derived=True,
                                                   staged_digest=sha_of(b"not-the-audited-input"))
        command.assert_not_called()

    def test_derived_bundle_shortcut_skips_script_without_a_reread(self) -> None:
        m = {"source": {"transform": {"id": fetch_model.STQ_TRANSFORM, "inputPath": "mt/translator.gguf",
                                      "inputSha256": sha_of(b"audited-upstream-input")}},
             "files": [{"sha256": sha_of(b"derived")}]}
        staged = self.repo / "staged-derived.gguf"
        staged.write_bytes(b"derived")
        with mock.patch.object(fetch_model.subprocess, "run") as command:
            self.assertIsNone(fetch_model.apply_source_transform(m, staged, allow_derived=True,
                                staged_digest=sha_of(b"derived")))
        command.assert_not_called()

    def test_transform_output_digest_is_returned_for_the_gate(self) -> None:
        m = {"source": {"transform": {"id": fetch_model.STQ_TRANSFORM, "inputPath": "mt/translator.gguf",
                                      "inputSha256": sha_of(b"audited-upstream-input")}},
             "files": [{"sha256": sha_of(b"derived")}]}
        staged = self.repo / "staged-input.gguf"
        staged.write_bytes(b"audited-upstream-input")

        def fake_run(command, **kwargs):
            output = Path(command[3])
            output.write_bytes(b"derived")
            return mock.Mock(returncode=0, stderr="")

        with mock.patch.object(fetch_model.subprocess, "run", fake_run):
            self.assertEqual(fetch_model.apply_source_transform(m, staged, allow_derived=True,
                                staged_digest=sha_of(b"audited-upstream-input")), sha_of(b"derived"))
        self.assertEqual(staged.read_bytes(), b"derived")

    def test_build_provenance_mismatch_is_integrity_failure(self) -> None:
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": 3, "sha256": sha_of(b"abc")},
        ]))
        self.stub_downloader({"a.bin": b"abc"})
        with mock.patch.object(fetch_model, "verify_build_record",
                               side_effect=fetch_model.ContractError("bad-value", "outputs differ")):
            code = self.run_fetch(self.repo / "dest-prov")
        self.assertEqual(code, 1, "provenance mismatch is an integrity failure, like validate_models")
        self.assertFalse((self.repo / "dest-prov/asr").exists())

    def test_update_manifest_backfill_failure_fails_request_but_keeps_install(self) -> None:
        """--update-manifest is an explicit request: if the backfill write
        fails, the request fails (exit 2) while the verified package stays
        installed with its own manifest intact."""
        data = b"installed-content"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": None, "sha256": sha_of(data)},
        ]))
        self.stub_downloader({"a.bin": data})
        dest = self.repo / "dest-backfill"
        real_write_json = fetch_model.write_json
        shared = self.repo / "shared/model-manifests/asr.json"

        def failing_backfill(path, data):
            if path == shared:
                raise OSError("read-only shared dir")
            return real_write_json(path, data)

        with mock.patch.object(fetch_model, "write_json", failing_backfill):
            code = fetch_model.main(["--package", "asr", "--dest", str(dest), "--update-manifest"])
        self.assertEqual(code, 2, "a failed explicit update request must not exit 0")
        self.assertEqual((dest / "asr/a.bin").read_bytes(), data, "installed package stays")
        self.assertTrue((dest / "asr/manifest.json").is_file())
        self.assertIsNone(json.loads(shared.read_text())["files"][0]["sizeBytes"],
                          "the shared manifest must not claim a backfill that never landed")

    def test_update_manifest_noop_when_sizes_already_pinned(self) -> None:
        """With every sizeBytes already pinned, --update-manifest is a no-op:
        the shared manifest is not rewritten and the install exits 0."""
        data = b"pinned-content"
        write_manifest(self.repo, manifest([
            {"path": "asr/a.bin", "sizeBytes": len(data), "sha256": sha_of(data)},
        ]))
        self.stub_downloader({"a.bin": data})
        dest = self.repo / "dest-noop"
        real_write_json = fetch_model.write_json
        shared = self.repo / "shared/model-manifests/asr.json"
        before = shared.read_text()

        def no_shared_write(path, data):
            if path == shared:
                raise AssertionError("backfill write attempted with nothing pending")
            return real_write_json(path, data)

        with mock.patch.object(fetch_model, "write_json", no_shared_write):
            code = fetch_model.main(["--package", "asr", "--dest", str(dest), "--update-manifest"])
        self.assertEqual(code, 0, "nothing to backfill: the explicit request is a no-op")
        self.assertEqual(shared.read_text(), before)


if __name__ == "__main__":
    unittest.main()
