"""Regression tests for validate_models.py: missing gates can never exit 0.

Includes the exact reproducer reported in review: an empty <tmp>/asr directory
against a draft manifest with an empty files list must NOT pass.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CONVERT_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = CONVERT_DIR.parent
SCRIPT = CONVERT_DIR / "validate_models.py"
REV = "0123456789abcdef0123456789abcdef01234567"


def run(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)


def manifest(status: str, files: list[dict], roles: dict, backend: str = "gguf-llama-cpp") -> dict:
    for f in files:
        f.setdefault("classification", "model")
    return {
        "schemaVersion": "2", "packageId": "asr", "version": "1.0.0", "status": status,
        "source": {"repoId": "x/y", "revision": REV if status == "verified" else None,
                   "upstreamModelId": "x/y", "licenseSource": "x"},
        "runtime": {"backend": backend, "apiContractVersion": "1",
                    "quantization": None, "runtimeRevision": "ort-1.22.0" if status == "verified" else None,
                    "targetPlatforms": [{"platform": "android", "minSdk": 29, "abis": ["arm64-v8a"]}],
                    "streaming": False},
        "capabilities": {"modes": ["transcribe"], "languages": ["zh"] if status == "verified" else [],
                         "verification": {"languages": "unverified"} if status == "verified" else {}},
        "files": files,
        "roles": roles,
    }


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ValidateModelsExitCodeTests(unittest.TestCase):
    def test_empty_bundle_dir_must_not_pass(self) -> None:
        """Exact review reproducer: empty <tmp>/asr + draft manifest, files=[] -> exit 2.

        Passes an explicit empty draft manifest so the reproducer stays pinned
        regardless of how the repo's own asr.json is backfilled.
        """
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "models"
            (models / "asr").mkdir(parents=True)
            m = manifest("draft", [], {})
            mpath = Path(tmp) / "m.json"
            mpath.write_text(json.dumps(m))
            proc = run(["--models-dir", str(models), "--package", "asr",
                        "--manifest", f"asr={mpath}"])
            self.assertEqual(proc.returncode, 2, proc.stdout + proc.stderr)
            self.assertIn("blocked", proc.stdout)

    def test_single_missing_hash_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "models"
            (models / "asr").mkdir(parents=True)
            (models / "asr" / "a.bin").write_bytes(b"x" * 10)
            m = manifest("draft", [{"path": "asr/a.bin", "sizeBytes": 10, "sha256": None}],
                         {"asr": "asr/a.bin"})
            mpath = Path(tmp) / "m.json"
            mpath.write_text(json.dumps(m))
            proc = run(["--models-dir", str(models), "--package", "asr",
                        "--manifest", f"asr={mpath}"])
            self.assertEqual(proc.returncode, 2, proc.stdout)
            self.assertIn("unverified-integrity", proc.stdout)
            self.assertIn("sha256 missing", proc.stdout)

    def test_single_missing_size_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "models"
            (models / "asr").mkdir(parents=True)
            (models / "asr" / "a.bin").write_bytes(b"x" * 10)
            m = manifest("draft", [{"path": "asr/a.bin", "sizeBytes": None, "sha256": "a" * 64}],
                         {"asr": "asr/a.bin"})
            mpath = Path(tmp) / "m.json"
            mpath.write_text(json.dumps(m))
            proc = run(["--models-dir", str(models), "--package", "asr",
                        "--manifest", f"asr={mpath}"])
            self.assertEqual(proc.returncode, 2, proc.stdout)
            self.assertIn("unverified-integrity", proc.stdout)

    def test_verified_bundle_without_runner_is_blocked(self) -> None:
        """Verified + complete integrity + roles still exits 2 while no runner exists."""
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "models"
            (models / "asr").mkdir(parents=True)
            data = b"gguf-fake-payload"
            (models / "asr" / "a.gguf").write_bytes(data)
            m = manifest("verified", [{"path": "asr/a.gguf", "sizeBytes": len(data),
                                       "sha256": sha(models / "asr" / "a.gguf")}],
                         {"asr": "asr/a.gguf"})
            mpath = Path(tmp) / "m.json"
            mpath.write_text(json.dumps(m))
            proc = run(["--models-dir", str(models), "--package", "asr",
                        "--manifest", f"asr={mpath}"])
            self.assertEqual(proc.returncode, 2, proc.stdout)
            self.assertIn("unsupported", proc.stdout)

    def test_hash_mismatch_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "models"
            (models / "asr").mkdir(parents=True)
            (models / "asr" / "a.gguf").write_bytes(b"tampered")
            m = manifest("verified", [{"path": "asr/a.gguf", "sizeBytes": 8, "sha256": "b" * 64}],
                         {"asr": "asr/a.gguf"})
            mpath = Path(tmp) / "m.json"
            mpath.write_text(json.dumps(m))
            proc = run(["--models-dir", str(models), "--package", "asr",
                        "--manifest", f"asr={mpath}"])
            self.assertEqual(proc.returncode, 1, proc.stdout)
            self.assertIn("sha256", proc.stdout)

    def test_missing_file_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models = Path(tmp) / "models"
            (models / "asr").mkdir(parents=True)
            m = manifest("verified", [{"path": "asr/absent.gguf", "sizeBytes": 8, "sha256": "b" * 64}],
                         {"asr": "asr/absent.gguf"})
            mpath = Path(tmp) / "m.json"
            mpath.write_text(json.dumps(m))
            proc = run(["--models-dir", str(models), "--package", "asr",
                        "--manifest", f"asr={mpath}"])
            self.assertEqual(proc.returncode, 1, proc.stdout)

    def test_missing_models_dir_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            proc = run(["--models-dir", str(Path(tmp) / "nope"), "--package", "asr"])
            self.assertEqual(proc.returncode, 2)


if __name__ == "__main__":
    unittest.main()
