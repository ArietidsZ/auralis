"""Task-gate/process boundary regressions; fake programs never stand in for
model quality. Real runtime evidence is produced by separate explicit suites.
"""
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import model_tasks
import validate_models


class TaskGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.report = validate_models.Report()

    def program(self, source):
        (self.root / "asr_runner.py").write_text(source)
        return patch.object(model_tasks, "CONVERT", self.root)

    def test_exit_zero_without_report_is_failure(self):
        # A report left by an earlier run must not turn a no-op into a pass.
        (self.root / "result.json").write_text('{"results":[]}')
        with self.program("pass"), self.assertRaises(model_tasks.TaskError) as error:
            model_tasks.fresh_run("asr", sys.executable, [], self.root, 5, self.report)
        self.assertEqual(error.exception.status, "fail")

    def test_crashed_runner_does_not_read_success_payload(self):
        code = 'import sys\nfrom pathlib import Path\nPath(sys.argv[-1]).write_text(\'{"results":[]}\')\nsys.exit(1)\n'
        with self.program(code), self.assertRaises(model_tasks.TaskError) as error:
            model_tasks.fresh_run("asr", sys.executable, [], self.root, 5, self.report)
        self.assertEqual(error.exception.status, "fail")

    def test_nonfinite_report_is_rejected(self):
        code = 'import sys\nfrom pathlib import Path\nPath(sys.argv[-1]).write_text(\'{"time":NaN}\')\n'
        with self.program(code), self.assertRaises(model_tasks.TaskError) as error:
            model_tasks.fresh_run("asr", sys.executable, [], self.root, 5, self.report)
        self.assertEqual(error.exception.status, "fail")

    def test_missing_dependency_is_blocked(self):
        with self.program("raise SystemExit(2)"), self.assertRaises(model_tasks.TaskError) as error:
            model_tasks.fresh_run("asr", sys.executable, [], self.root, 5, self.report)
        self.assertEqual(error.exception.status, "blocked")

    def test_omitted_hard_sample_is_failure(self):
        config = {"cases": [{"id": "easy", "audio": "easy.wav"}, {"id": "hard", "audio": "hard.wav"}]}
        with patch.object(model_tasks, "fresh_run", return_value={"results": [{}]}), self.assertRaises(model_tasks.TaskError):
            model_tasks.run_asr(self.report, self.root, config, sys.executable, self.root, 5)

    def test_corpus_cer_is_length_weighted_not_mean(self):
        cases = [{"id": "short", "reference": "a"}, {"id": "long", "reference": "b" * 99}]
        metrics = model_tasks.text_metrics(cases, ["x", "b" * 99])
        self.assertEqual(metrics["corpusCer"], 0.01)
        self.assertEqual(metrics["macroCer"], 0.5)

    def test_quality_needs_predeclared_limits(self):
        model_tasks.quality_gate(self.report, "asr", {}, {"corpusCer": 0}, {"maxCer": ("corpusCer", "max")})
        self.assertEqual(self.report.entries[-1]["status"], "blocked")

    def test_exceeded_quality_limit_fails(self):
        model_tasks.quality_gate(self.report, "asr", {"maxCer": 0.1}, {"corpusCer": 0.2}, {"maxCer": ("corpusCer", "max")})
        self.assertEqual(self.report.entries[-1]["status"], "fail")

    def test_duplicate_case_is_rejected_before_execution(self):
        case = {"id": "same", "audio": "a.wav"}
        path = self.root / "suite.json"
        path.write_text(json.dumps({"schemaVersion": 1, "name": "test", "purpose": "smoke", "asr": {"cases": [case, case]}}))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            model_tasks.load_suite(path)

    def test_bad_hash_prevents_native_execution(self):
        (self.root / "asr").mkdir()
        (self.root / "asr/model.bin").write_bytes(b"bad")
        manifest = {"schemaVersion": "2", "status": "draft", "runtime": {"backend": "onnx"},
                    "source": {"revision": None}, "roles": {"encoder": "asr/model.bin"},
                    "files": [{"path": "asr/model.bin", "sha256": "a" * 64, "sizeBytes": 3}]}
        with patch.object(validate_models, "run_task") as runner:
            self.assertFalse(validate_models.validate_package(self.report, "asr", manifest, self.root))
            runner.assert_not_called()

    def test_package_symlink_cannot_pass_integrity(self):
        outside = self.root / "external"
        outside.mkdir()
        (outside / "file.bin").write_bytes(b"x")
        (self.root / "asr").symlink_to(outside, target_is_directory=True)
        manifest = {"schemaVersion": "2", "status": "draft", "runtime": {"backend": "onnx"},
                    "source": {"revision": None}, "roles": {"encoder": "asr/file.bin"},
                    "files": [{"path": "asr/file.bin", "sha256": model_tasks.file_hash(outside / "file.bin"), "sizeBytes": 1}]}
        with patch.object(validate_models, "run_task") as runner:
            self.assertFalse(validate_models.validate_package(self.report, "asr", manifest, self.root))
            runner.assert_not_called()

    def layout_manifest(self, pkg):
        """Byte fixtures exercise integrity only; no native inference is mocked as quality."""
        path = (model_tasks.CONVERT.parent / "shared/legacy-model-manifests/tts-api1.json" if pkg == "tts"
                else model_tasks.CONVERT.parent / "shared/model-manifests" / f"{pkg}.json")
        manifest = json.loads(path.read_text())
        manifest["status"] = "verified"
        for entry in manifest["files"]:
            path = self.root / entry["path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(entry["path"].encode())
            entry.update(sizeBytes=path.stat().st_size, sha256=model_tasks.file_hash(path))
        return manifest

    def test_current_api2_layout_and_external_files(self):
        manifest = json.loads((model_tasks.CONVERT.parent / "shared/model-manifests/tts.json").read_text())
        self.assertEqual(manifest["runtime"]["apiContractVersion"], "2")
        self.assertEqual(set(manifest["roles"]), {"speaker_encoder", "talker", "code_predictor", "reference_encoder", "vocoder"})
        self.assertIsNone(model_tasks.runner_layout_error("tts", manifest))
        for name in model_tasks.API2_HASHED_FILES:
            altered = dict(manifest, files=[f for f in manifest["files"] if f["path"] != "tts/" + name])
            with self.subTest(missing=name):
                self.assertIsNotNone(model_tasks.runner_layout_error("tts", altered))

    def test_every_native_input_must_be_declared_before_execution(self):
        for pkg in ("asr", "tts"):
            manifest = self.layout_manifest(pkg)
            self.assertIsNone(model_tasks.runner_layout_error(pkg, manifest))
            for omitted in list(manifest["files"]):
                incomplete = dict(manifest, files=[f for f in manifest["files"] if f is not omitted])
                with self.subTest(pkg=pkg, omitted=omitted["path"]), patch.object(validate_models, "run_task") as runner:
                    self.assertFalse(validate_models.validate_package(self.report, pkg, incomplete, self.root))
                    runner.assert_not_called()

    def test_every_native_input_hash_is_checked_before_execution(self):
        for pkg in ("asr", "tts"):
            manifest = self.layout_manifest(pkg)
            for entry in manifest["files"]:
                path = self.root / entry["path"]
                original = path.read_bytes()
                path.write_bytes(b"x" * len(original))
                with self.subTest(pkg=pkg, corrupted=entry["path"]), patch.object(validate_models, "run_task") as runner:
                    self.assertFalse(validate_models.validate_package(self.report, pkg, manifest, self.root))
                    runner.assert_not_called()
                path.write_bytes(original)

    def test_redirected_roles_and_external_data_cannot_invoke_runner(self):
        for pkg in ("asr", "tts"):
            manifest = self.layout_manifest(pkg)
            role = next(iter(manifest["roles"]))
            manifest["roles"][role] = f"{pkg}/unrelated.onnx"
            self.assertIsNotNone(model_tasks.runner_layout_error(pkg, manifest))
        manifest = self.layout_manifest("tts")
        next(f for f in manifest["files"] if f["path"] == "tts/vocoder.onnx")["externalData"] = []
        self.assertIsNotNone(model_tasks.runner_layout_error("tts", manifest))

    def test_malformed_asr_nested_report_is_recorded_as_failure(self):
        suite = {"asr": {"cases": [{"id": "a", "audio": "a.wav", "reference": "a"}]}}
        raw = {"model_dir": str(self.root / "asr"), "results": [{"audio": "a.wav", "segmented": None}]}
        with patch.object(model_tasks, "fresh_run", return_value=raw):
            self.assertFalse(model_tasks.run_task(self.report, "asr", {"runtime": {"backend": "onnx"}},
                self.root, suite, {"asr": sys.executable}, None, self.root, 5))
        self.assertEqual(self.report.entries[-1]["status"], "fail")

    def test_empty_reference_cannot_pass_asr_quality(self):
        suite = {"asr": {"cases": [{"id": "a", "audio": "a.wav", "reference": "  "}],
                         "limits": {"maxCer": 0, "maxRtf": 1}}}
        with patch.object(model_tasks, "run_asr", return_value=([""], {"rtf": 0})):
            self.assertFalse(model_tasks.run_task(self.report, "asr", {"runtime": {"backend": "onnx"}},
                self.root, suite, {"asr": sys.executable}, None, self.root, 5))
        self.assertEqual(self.report.entries[-1]["status"], "blocked")

    def test_artifact_creation_failure_preserves_json_report(self):
        (self.root / ".model-task-runs").write_text("file instead of directory")
        suite = self.root / "suite.json"
        suite.write_text(json.dumps({"schemaVersion": 1, "name": "test", "purpose": "smoke"}))
        output = self.root / "report.json"
        self.assertEqual(validate_models.main(["--models-dir", str(self.root), "--suite", str(suite),
                                              "--json-report", str(output)]), 2)
        self.assertEqual(json.loads(output.read_text())[-1]["status"], "blocked")

    def test_timeout_terminates_descendants_and_retains_log(self):
        marker = self.root / "surviving-child"
        code = ("import os,time\nfrom pathlib import Path\n"
                "if os.fork() == 0:\n"
                f" time.sleep(0.4)\n Path({str(marker)!r}).write_text('leaked')\n"
                "else:\n time.sleep(5)\n")
        with self.program(code), self.assertRaises(model_tasks.TaskError):
            model_tasks.fresh_run("asr", sys.executable, [], self.root, 0.15, self.report)
        time.sleep(0.5)
        self.assertFalse(marker.exists())
        entry = self.report.entries[-1]
        self.assertEqual(entry["status"], "fail")
        self.assertEqual(entry["logSha256"], model_tasks.file_hash(Path(entry["log"])))

    def test_tts_report_hashes_and_request_must_match_before_audio_is_accepted(self):
        manifest = self.layout_manifest("tts")
        case = {"id": "tts", "text": "hello", "language": "English", "referenceWav": "reference.wav"}
        raw = {"status": "success", "finite": True, "model_dir": str(self.root / "tts"),
               "api_contract_version": "1", "conditioningMode": "xvector", "reference_text": None,
               "audio_path": str(self.root / "tts-0.wav"), "reference_wav": case["referenceWav"],
               "text": case["text"], "language_requested": case["language"], "language": "english",
               "model_sha256": {f["path"].removeprefix("tts/"): f["sha256"] for f in manifest["files"]
                                if f["path"].endswith((".onnx", ".onnx.data"))}}
        variants = [dict(raw, model_sha256={}), dict(raw, text="different"), dict(raw, audio_path="elsewhere.wav"),
                    dict(raw, api_contract_version="2"), dict(raw, conditioningMode="icl"),
                    dict(raw, reference_text="invented reference")]
        for bad in variants:
            with patch.object(model_tasks, "fresh_run", return_value=bad), patch.object(model_tasks, "load_wav") as load:
                with self.assertRaises(model_tasks.TaskError):
                    model_tasks.run_tts(self.report, self.root, manifest, {"cases": [case]},
                                        {"tts": sys.executable}, self.root, 5, True)
                load.assert_not_called()
        # A structurally correct report reaches PCM validation; this is not quality evidence.
        with patch.object(model_tasks, "fresh_run", return_value=raw), patch.object(
                model_tasks, "load_wav", side_effect=model_tasks.RunnerError("PCM boundary")) as load:
            with self.assertRaisesRegex(model_tasks.RunnerError, "PCM boundary"):
                model_tasks.run_tts(self.report, self.root, manifest, {"cases": [case]},
                                    {"tts": sys.executable}, self.root, 5, True)
            load.assert_called_once()


if __name__ == "__main__":
    unittest.main()
