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
