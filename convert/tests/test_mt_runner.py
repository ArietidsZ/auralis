#!/usr/bin/env python3
"""CLI/protocol tests for convert/mt_runner.py. Does not load the GGUF."""
from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RUNNER = REPO / "convert" / "mt_runner.py"
_SPEC = importlib.util.spec_from_file_location("mt_runner", RUNNER)
mt_runner = importlib.util.module_from_spec(_SPEC)
sys.modules["mt_runner"] = mt_runner
_SPEC.loader.exec_module(mt_runner)


class MtRunnerCli(unittest.TestCase):
    def test_missing_model_is_blocked_not_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            lib = Path(tmp) / "libhymt_core.dylib"
            lib.write_bytes(b"not-a-dylib")
            proc = subprocess.run(
                [sys.executable, str(RUNNER), "--lib", str(lib),
                 "--model", str(Path(tmp) / "missing.gguf")],
                cwd=str(REPO), capture_output=True, text=True,
            )
            self.assertEqual(proc.returncode, 2)

    def test_missing_lib_is_blocked(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = Path(tmp) / "m.gguf"
            model.write_bytes(b"GGUF")
            proc = subprocess.run(
                [sys.executable, str(RUNNER), "--lib", str(Path(tmp) / "no.dylib"),
                 "--model", str(model)],
                cwd=str(REPO), capture_output=True, text=True,
            )
            self.assertEqual(proc.returncode, 2)

    def test_bad_cases_is_args(self):
        with tempfile.TemporaryDirectory() as tmp:
            cases = Path(tmp) / "cases.json"
            cases.write_text("{}\n", encoding="utf-8")
            lib = Path(tmp) / "libhymt_core.dylib"
            model = Path(tmp) / "m.gguf"
            lib.write_bytes(b"x")
            model.write_bytes(b"GGUF")
            try:
                mt_runner.load_cases(cases)
                self.fail("expected RunnerInputError")
            except mt_runner.RunnerInputError:
                pass

    def test_default_cases_are_bidirectional(self):
        src = {c["sourceLanguage"] for c in mt_runner.DEFAULT_CASES}
        tgt = {c["targetLanguage"] for c in mt_runner.DEFAULT_CASES}
        self.assertTrue(src & {"Chinese", "English", "zh", "Japanese"})
        self.assertTrue(tgt & {"Chinese", "English", "en"})


if __name__ == "__main__":
    unittest.main()
