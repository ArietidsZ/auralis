"""Unit tests for script plumbing: simulator selection from simctl --json."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

from verify_lib import CheckResult, Reporter, pick_ios_simulator  # noqa: E402

SAMPLE = {
    "devices": {
        "com.apple.CoreSimulator.SimRuntime.iOS-16-4": [
            {"lastBootedAt": "2026-01-01T00:00:00Z", "dataPath": "/d1", "deviceTypeIdentifier": "t",
             "state": "Shutdown", "name": "iPhone SE (3rd generation)", "udid": "UDID-SE",
             "isAvailable": True},
            {"state": "Shutdown", "name": "iPhone 14", "udid": "UDID-14", "isAvailable": False},
        ],
        "com.apple.CoreSimulator.SimRuntime.iOS-18-2": [
            {"state": "Shutdown", "name": "iPad Pro (11-inch)", "udid": "UDID-IPAD", "isAvailable": True},
            {"state": "Booted", "name": "iPhone 16 Pro", "udid": "UDID-16PRO", "isAvailable": True},
        ],
    }
}


class PickIosSimulatorTests(unittest.TestCase):
    def test_prefers_newest_runtime_iphone(self) -> None:
        self.assertEqual(pick_ios_simulator(json.dumps(SAMPLE)), "UDID-16PRO")

    def test_skips_unavailable_and_non_iphone(self) -> None:
        text = json.dumps({
            "devices": {
                "com.apple.CoreSimulator.SimRuntime.iOS-18-2": [
                    {"name": "iPad Pro", "udid": "IPAD", "isAvailable": True},
                    {"name": "iPhone 14", "udid": "OFF", "isAvailable": False},
                ],
            }
        })
        self.assertIsNone(pick_ios_simulator(text))

    def test_no_iphone_anywhere(self) -> None:
        text = json.dumps({
            "devices": {"com.apple.CoreSimulator.SimRuntime.iOS-17-5": [
                {"name": "iPad mini", "udid": "IPAD", "isAvailable": True}]}
        })
        self.assertIsNone(pick_ios_simulator(text))

    def test_unknown_runtime_keys_still_pick_by_name(self) -> None:
        text = json.dumps({
            "devices": {"com.apple.CoreSimulator.SimRuntime.Unknown": [
                {"name": "iPhone X", "udid": "X", "isAvailable": True}]}
        })
        self.assertEqual(pick_ios_simulator(text), "X")

    def test_malformed_json_is_none(self) -> None:
        self.assertIsNone(pick_ios_simulator("not json"))
        self.assertIsNone(pick_ios_simulator(json.dumps({"devices": []})))


class ReporterExitTests(unittest.TestCase):
    def test_contract_exit_survives_subprocess_aggregation(self):
        reporter = Reporter()
        result = reporter.run_cmd("schema", [sys.executable, "-c", "raise SystemExit(3)"], contract_errors=True)
        self.assertEqual(result.status, "fail")
        reporter.add(CheckResult("sdk", "blocked"))
        reporter.add(CheckResult("other-test", "fail"))
        self.assertEqual(reporter.aggregate_exit(), 3)

    def test_regular_compile_exit_three_is_not_a_contract_failure(self):
        reporter = Reporter()
        reporter.run_cmd("compiler", [sys.executable, "-c", "raise SystemExit(3)"])
        self.assertEqual(reporter.aggregate_exit(), 1)

    def test_requested_missing_prerequisite_remains_blocked(self):
        reporter = Reporter()
        reporter.run_cmd("model", [sys.executable, "-c", "raise SystemExit(2)"], pass_statuses=("pass", "blocked"))
        self.assertEqual(reporter.aggregate_exit(), 2)

    def test_declared_argument_error_survives_wrapper(self):
        reporter = Reporter()
        reporter.run_cmd("model", [sys.executable, "-c", "raise SystemExit(4)"], contract_errors=True)
        self.assertEqual(reporter.aggregate_exit(), 4)

    def test_compiler_exit_four_remains_build_failure(self):
        reporter = Reporter()
        reporter.run_cmd("compiler", [sys.executable, "-c", "raise SystemExit(4)"])
        self.assertEqual(reporter.aggregate_exit(), 1)


class FailureEvidenceTests(unittest.TestCase):
    def test_timeout_reports_failure_and_preserves_partial_output(self):
        import tempfile
        reporter = Reporter()
        code = "import sys,time; print('partial stdout',flush=True); " \
               "print('partial stderr',file=sys.stderr,flush=True); time.sleep(30)"
        result = reporter.run_cmd("timeout", [sys.executable, "-u", "-c", code], timeout=1)
        self.assertEqual(result.status, "fail")
        self.assertIn("timed out", result.reason)
        with tempfile.TemporaryDirectory() as tmp:
            reporter.write_json(Path(tmp), "timeout")
            log = (Path(tmp) / "logs/timeout.log").read_text()
            self.assertIn("partial stdout", log)
            self.assertIn("partial stderr", log)

    def test_tail_never_lets_stderr_evict_stdout_errors(self):
        from verify_lib import _tail
        stdout = "x" * 400 + "\nerror: the real compiler failure\n"
        stderr = "y" * 700 + "warning: manifest deprecation"
        summary = _tail(stdout, stderr)
        self.assertIn("the real compiler failure", summary)
        self.assertIn("--- stderr ---", summary)
        self.assertIn("manifest deprecation", summary)

    def test_failed_command_writes_full_output_to_evidence_dir(self):
        import tempfile
        from pathlib import Path as P
        reporter = Reporter()
        code = "import sys; sys.stdout.write('OUT-progress\\nOUT-error: boom\\n'); " \
               "sys.stderr.write('WARN-noise\\n' * 200); raise SystemExit(1)"
        reporter.run_cmd("swift-core-build", [sys.executable, "-c", code])
        with tempfile.TemporaryDirectory() as tmp:
            out = P(tmp)
            reporter.write_json(out, "scope-test")
            log = out / "logs" / "swift-core-build.log"
            self.assertTrue(log.is_file())
            text = log.read_text()
            self.assertIn("OUT-error: boom", text)
            self.assertIn("WARN-noise", text)  # full stderr kept, untruncated
            self.assertGreater(len(text), 1200)  # beyond the inline tail limit
            report = json.loads(next((out).glob("verify-scope-test-*.json")).read_text())
            entry = next(r for r in report["results"] if r["id"] == "swift-core-build")
            self.assertEqual(entry["details"]["logFile"], "logs/swift-core-build.log")

    def test_passing_commands_do_not_emit_logs(self):
        import tempfile
        from pathlib import Path as P
        reporter = Reporter()
        reporter.run_cmd("ok", [sys.executable, "-c", "print('fine')"])
        with tempfile.TemporaryDirectory() as tmp:
            out = P(tmp)
            reporter.write_json(out, "scope-test")
            self.assertFalse((out / "logs").exists())


if __name__ == "__main__":
    unittest.main()
