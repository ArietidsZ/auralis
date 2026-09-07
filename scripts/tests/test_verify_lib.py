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


if __name__ == "__main__":
    unittest.main()
