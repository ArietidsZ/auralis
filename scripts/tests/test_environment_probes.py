"""Environment discovery must fail closed and remain bounded without hardware."""
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_lib as lib


class EnvironmentProbeTests(unittest.TestCase):
    def result(self, stdout='', stderr='', code=0):
        return subprocess.CompletedProcess(['tool'], code, stdout, stderr)

    def invoke(self, probe, result):
        with patch.object(lib.shutil, 'which', return_value='/controlled/tool'), \
                patch.object(lib, '_run_owned_command', return_value=result) as run:
            answer = probe()
        self.assertEqual(run.call_args.kwargs['timeout'], lib.PROBE_TIMEOUT_SECONDS)
        return answer

    def test_python_version_tuple_and_malformed_output(self):
        for version, expected in [('Python 3.9.20', False), ('Python 3.10.1', True),
                                  ('Python 4.0.0', True), ('Python 3.13.0rc1', True),
                                  ('', False), ('Python ???', False), ('3', False)]:
            with self.subTest(version=version):
                self.assertEqual(self.invoke(lib.probe_python3, self.result(version))[0], expected)

    def test_all_probes_block_timeout_and_launch_failure(self):
        for probe in [lib.probe_python3, lib.probe_jdk, lib.probe_xcode, lib.probe_ios_simulator]:
            for error in [subprocess.TimeoutExpired('tool', 15), FileNotFoundError('missing'),
                          PermissionError('denied')]:
                with self.subTest(probe=probe.__name__, error=type(error).__name__), \
                     patch.object(lib.shutil, 'which', return_value='/controlled/tool'), \
                     patch.object(lib, '_run_owned_command', side_effect=error):
                    ok, detail, hint = probe()
                    self.assertFalse(ok)
                    self.assertTrue(detail)
                    self.assertTrue(hint)

    def test_nonzero_output_never_reports_success(self):
        for probe, text in [(lib.probe_python3, 'Python 3.12.0'),
                            (lib.probe_jdk, 'java version "17.0.0"'),
                            (lib.probe_xcode, 'iphonesimulator'),
                            (lib.probe_ios_simulator, '{"devices":{}}')]:
            with self.subTest(probe=probe.__name__):
                ok, detail, hint = self.invoke(probe, self.result(text, code=1))
                self.assertFalse(ok)
                self.assertIn('exited 1', detail)

    def test_missing_tools_block_without_starting_process(self):
        with patch.object(lib.shutil, 'which', return_value=None), \
                patch.object(lib, '_run_owned_command') as run:
            for probe in [lib.probe_python3, lib.probe_jdk, lib.probe_xcode, lib.probe_ios_simulator]:
                self.assertFalse(probe()[0])
            run.assert_not_called()

    def test_jdk_stderr_and_xcode_sdk(self):
        self.assertTrue(self.invoke(lib.probe_jdk, self.result(stderr='openjdk version "17.0.1"'))[0])
        self.assertTrue(self.invoke(lib.probe_xcode, self.result('iOS Simulator -sdk iphonesimulator18.0'))[0])
        self.assertFalse(self.invoke(lib.probe_xcode, self.result('macosx15.0'))[0])

    def test_simulator_protocol_errors_are_distinct_from_absence(self):
        for text in ['not json', 'null', '[]', '{"devices":[]}']:
            with self.subTest(text=text):
                ok, detail, _ = self.invoke(lib.probe_ios_simulator, self.result(text))
                self.assertFalse(ok)
                self.assertNotIn('no available', detail)
        self.assertIn('no available', self.invoke(lib.probe_ios_simulator, self.result('{"devices":{}}'))[1])

    def test_simulator_valid_response(self):
        devices = {'devices': {'iOS-18-2': [{'isAvailable': True, 'name': 'iPhone', 'udid': 'ABC'}]}}
        self.assertEqual(self.invoke(lib.probe_ios_simulator, self.result(json.dumps(devices))), (True, 'ABC', ''))

    def test_simulator_rejects_nonstring_and_blank_identifiers(self):
        for value in [123, ['ABC'], {'id': 'ABC'}, '   ', None]:
            with self.subTest(value=value):
                data = {'devices': {'iOS-18': [{'isAvailable': True, 'name': 'iPhone', 'udid': value}]}}
                self.assertFalse(self.invoke(lib.probe_ios_simulator, self.result(json.dumps(data)))[0])

    def test_selection_rejects_nonobject_json(self):
        for text in ['null', '[]', 'true', '42']:
            self.assertIsNone(lib.pick_ios_simulator(text))

    def test_real_owned_probe_timeout_returns_blocker(self):
        with patch.object(lib, 'PROBE_TIMEOUT_SECONDS', 0.05):
            result, error = lib._probe_command([sys.executable, '-c', 'import time; time.sleep(30)'])
        self.assertIsNone(result)
        self.assertIn('timed out', error)

    def test_ios_dispatch_keeps_probe_failure_as_blocked(self):
        import runpy
        namespace = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'verify'),
                                   run_name='auralis_verify_test')
        run_ios = namespace['run_ios']
        reporter = lib.Reporter()
        with patch.dict(run_ios.__globals__, {'probe_xcode': lambda: (True, 'sdk', '')}), \
             patch.object(lib, 'probe_ios_simulator', return_value=(False, 'probe timed out', 'retry')), \
             patch.object(reporter, 'run_cmd', return_value=lib.CheckResult('ios-xcodebuild', 'pass')) as run:
            run_ios(reporter)
        run.assert_called_once()
        self.assertEqual(reporter.results[-1].id, 'ios-swift-tests')
        self.assertEqual(reporter.results[-1].status, 'blocked')
        self.assertIn('probe timed out', reporter.results[-1].reason)

    def test_doctor_preserves_failure_detail_and_remedy(self):
        import runpy
        namespace = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'doctor'),
                                   run_name='auralis_doctor_test')
        result = namespace['_mk']('python', False, 'probe timed out', 'repair installation')
        self.assertEqual(result.status, 'blocked')
        self.assertIn('probe timed out', result.reason)
        self.assertIn('repair installation', result.reason)

    def test_blocked_probe_can_be_serialized(self):
        reporter = lib.Reporter()
        ok, detail, hint = self.invoke(lib.probe_python3, self.result('malformed'))
        reporter.add(lib.CheckResult('python', 'pass' if ok else 'blocked', f'{detail}; {hint}'))
        self.assertEqual(reporter.results[0].status, 'blocked')
        self.assertIn('blocked', json.dumps(lib.asdict(reporter.results[0])))


if __name__ == '__main__':
    unittest.main()
