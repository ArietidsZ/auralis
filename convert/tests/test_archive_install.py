"""Real TAR I/O tests for the content-pinned release acquisition path."""
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_fetch_model import manifest, sha_of, write_manifest
import fetch_model


class _FakeResponse(io.BytesIO):
    """Minimal urlopen() stand-in: a readable byte stream with .url."""
    url = "https://example.com/source.tar.bz2"


class ArchiveInstallTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.repo = Path(tmp.name)
        replacement = patch.object(fetch_model, "REPO_ROOT", self.repo)
        replacement.start()
        self.addCleanup(replacement.stop)

    def make_archive(self, entries):
        archive = self.repo / "source.tar.bz2"
        with tarfile.open(archive, "w:bz2") as target:
            for name, data, kind in entries:
                info = tarfile.TarInfo(name)
                if kind == "link":
                    info.type = tarfile.SYMTYPE
                    info.linkname = "../../outside"
                    target.addfile(info)
                else:
                    info.size = len(data)
                    target.addfile(info, io.BytesIO(data))
        m = manifest([{"path": "asr/model.bin", "sha256": sha_of(b"new"), "sizeBytes": 3}])
        m["source"]["revision"] = None
        m["source"]["archive"] = {"url": "https://example.com/source.tar.bz2", "sha256": sha_of(archive.read_bytes()),
                                  "sizeBytes": archive.stat().st_size, "stripPrefix": "bundle"}
        write_manifest(self.repo, m)
        return archive, m

    def install_archive(self, archive):
        return fetch_model.main(["--package", "asr", "--dest", str(self.repo / "models"), "--archive", str(archive)])

    def test_archive_installs_only_allowlisted_files(self):
        path, _ = self.make_archive([("bundle/model.bin", b"new", "file"), ("bundle/docs.txt", b"docs", "file")])
        self.assertEqual(self.install_archive(path), 0)
        pkg = self.repo / "models/asr"
        self.assertEqual({p.name for p in pkg.iterdir()}, {"model.bin", "manifest.json"})
        self.assertEqual(json.loads((pkg / "manifest.json").read_text())["status"], "draft")

    def test_bad_archive_hash_keeps_previous_package(self):
        path, m = self.make_archive([("bundle/model.bin", b"new", "file")])
        m["source"]["archive"]["sha256"] = "a" * 64
        write_manifest(self.repo, m)
        pkg = self.repo / "models/asr"
        pkg.mkdir(parents=True)
        (pkg / "old.bin").write_bytes(b"old")
        self.assertEqual(self.install_archive(path), 1)
        self.assertEqual((pkg / "old.bin").read_bytes(), b"old")

    def test_archive_traversal_is_rejected(self):
        path, _ = self.make_archive([("bundle/model.bin", b"new", "file"), ("bundle/../../outside", b"bad", "file")])
        self.assertEqual(self.install_archive(path), 1)
        self.assertFalse((self.repo / "models/asr").exists())

    def test_archive_link_is_rejected_even_when_unlisted(self):
        path, _ = self.make_archive([("bundle/model.bin", b"new", "file"), ("bundle/link", b"", "link")])
        self.assertEqual(self.install_archive(path), 1)

    def test_duplicate_archive_member_is_rejected(self):
        path, _ = self.make_archive([("bundle/model.bin", b"new", "file"), ("bundle/model.bin", b"new", "file")])
        self.assertEqual(self.install_archive(path), 1)

    def test_member_hash_mismatch_fails_even_when_archive_hash_matches(self):
        """The archive is byte-identical to its pin, but its member payload
        differs from the manifest file pin: the per-file gate must fail."""
        path, _ = self.make_archive([("bundle/model.bin", b"bad", "file")])
        pkg = self.repo / "models/asr"
        pkg.mkdir(parents=True)
        (pkg / "old.bin").write_bytes(b"old")
        self.assertEqual(self.install_archive(path), 1)
        self.assertEqual((pkg / "old.bin").read_bytes(), b"old", "old package must survive a member mismatch")

    def test_invalid_archive_never_reaches_tarfile_extraction(self):
        """Whole-archive authentication precedes parsing: an archive whose
        bytes differ from the pin is refused before tarfile.open runs, on
        both the local --archive path (pre-hash) and the network path
        (in-flight digest)."""
        path, m = self.make_archive([("bundle/model.bin", b"new", "file")])
        body = bytearray(path.read_bytes())
        body[-1] ^= 0xFF  # same length, different digest: passes the size gate
        tampered = self.repo / "tampered.tar.bz2"
        tampered.write_bytes(bytes(body))
        pkg = self.repo / "models/asr"
        with patch.object(fetch_model.tarfile, "open") as opening:
            self.assertEqual(self.install_archive(tampered), 1)
            write_manifest(self.repo, m)
            response = _FakeResponse(bytes(body))
            with patch.object(fetch_model, "urlopen", return_value=response):
                self.assertEqual(fetch_model.main(
                    ["--package", "asr", "--dest", str(self.repo / "models")]), 1)
        opening.assert_not_called()
        self.assertFalse(pkg.exists())

    def test_local_archive_size_mismatch_is_rejected_before_extraction(self):
        """Local --archive path: the staged archive is size-checked against
        the pin before extraction (extract_archive's own gate, distinct from
        the in-flight download checks)."""
        path, m = self.make_archive([("bundle/model.bin", b"new", "file")])
        m["source"]["archive"]["sizeBytes"] = m["source"]["archive"]["sizeBytes"] + 1000
        write_manifest(self.repo, m)
        self.assertEqual(self.install_archive(path), 1)
        self.assertFalse((self.repo / "models/asr").exists())

    def test_download_size_mismatch_fails_in_flight_before_extraction(self):
        """Network path in-flight size checks: an overlong stream trips the
        running-total guard mid-download, a short one trips the EOF equality
        check; either way nothing is extracted."""
        path, m = self.make_archive([("bundle/model.bin", b"new", "file")])
        body = path.read_bytes()
        pkg = self.repo / "models/asr"
        for label, response_body in (("overlong", body + b"trailing bytes"),
                                     ("short", body[:-8])):
            with self.subTest(label=label):
                write_manifest(self.repo, m)
                response = _FakeResponse(response_body)
                with patch.object(fetch_model, "urlopen", return_value=response):
                    self.assertEqual(fetch_model.main(
                        ["--package", "asr", "--dest", str(self.repo / "models")]), 1)
                self.assertFalse(pkg.exists(),
                                 f"{label} download must not reach extraction")

    def test_downloaded_archive_digest_is_verified_without_a_second_read(self):
        """The download pass hashes the archive; extraction receives that
        digest as verified_digest instead of re-hashing the whole file."""
        path, _ = self.make_archive([("bundle/model.bin", b"new", "file"), ("bundle/docs.txt", b"d", "file")])
        observed = {}
        real_extract = fetch_model.extract_archive

        def spy(manifest, archive_path, staging, **kwargs):
            observed["kwargs"] = kwargs
            return real_extract(manifest, archive_path, staging, **kwargs)

        response = _FakeResponse(path.read_bytes())
        with patch.object(fetch_model, "extract_archive", spy), \
             patch.object(fetch_model, "urlopen", return_value=response):
            self.assertEqual(fetch_model.main(
                ["--package", "asr", "--dest", str(self.repo / "models")]), 0)
        self.assertEqual(observed["kwargs"].get("verified_digest"),
                         fetch_model.sha256_of(path))
