"""Tests for convert/tts_runner.py.

CLI contract (exit codes 0/1/2/4), report schema, provenance hashing and
special-token ids. These tests import the runner module with standard library
only — heavy dependencies (onnxruntime/numpy/tokenizers/soundfile) are lazy in
the runner, so this suite passes on machines without them. The full-pipeline
smoke test runs only when a real model bundle and reference wav are provided
via the AURALIS_TTS_MODEL_DIR / AURALIS_TTS_REF_WAV env vars; it is skipped
otherwise and never fakes model inference.
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
_SPEC = importlib.util.spec_from_file_location("tts_runner", REPO / "convert" / "tts_runner.py")
tts_runner = importlib.util.module_from_spec(_SPEC)
sys.modules["tts_runner"] = tts_runner
_SPEC.loader.exec_module(tts_runner)

RUNNER = REPO / "convert" / "tts_runner.py"


class TestSpecialTokens(unittest.TestCase):
    def test_canonical_special_token_ids(self):
        # Verified against HuggingFace Qwen2Tokenizer and the author's C#
        # TextTokenizer.cs — the bundle vocab.json does NOT contain them.
        expected = {
            "<|endoftext|>": 151643,
            "<|im_start|>": 151644,
            "<|im_end|>": 151645,
            "<|audio_start|>": 151669,
            "<|audio_end|>": 151670,
            "<tts_pad>": 151671,
            "<tts_text_bos>": 151672,
            "<tts_text_eod>": 151673,
            "<tts_text_bos_single>": 151674,
            "<|audio_pad|>": 151675,
        }
        self.assertEqual(tts_runner.SPECIAL_TOKEN_IDS, expected)


class TestDependencyGate(unittest.TestCase):
    def test_missing_dependencies_returns_list(self):
        result = tts_runner.missing_dependencies()
        self.assertIsInstance(result, list)
        self.assertTrue(set(result) <= set(tts_runner.REQUIRED_DEPENDENCIES))

    def test_module_import_does_not_load_heavy_deps(self):
        # The whole point of the lazy import: importing the module must not
        # require onnxruntime. (If it were loaded, np would be non-None only
        # after _import_heavy.)
        self.assertIsNone(tts_runner.np)

    def test_cli_exit_2_when_dependencies_missing(self):
        # -S skips site-packages, so the heavy deps cannot import: the CLI must
        # report MissingDependency and exit 2 (not 1, not a success report).
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "r.json"
            result = subprocess.run(
                [sys.executable, "-S", str(RUNNER),
                 "--model-dir", tmp, "--text", "x",
                 "--reference-wav", "x.wav", "--out", str(Path(tmp) / "o.wav"),
                 "--json-report", str(report)],
                capture_output=True, text=True, timeout=120)
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            payload = json.loads(report.read_text())
            self.assertEqual(payload["status"], "error")
            self.assertEqual(payload["error_type"], "MissingDependency")
            self.assertNotIn("audio_path", payload)
            self.assertNotIn("duration_s", payload)


class TestCliContract(unittest.TestCase):
    def test_api2_icl_uses_packaged_encoder_and_keeps_mode_explicit(self):
        base = [sys.executable, "-S", str(RUNNER), "--model-dir", "/missing", "--text", "target",
                "--reference-wav", "ref.wav", "--out", "out.wav", "--api-contract-version", "2"]
        for options, expected in [([], 2), (["--conditioning-mode", "icl"], 4),
                                  (["--conditioning-mode", "icl", "--reference-text", "actual reference"], 2),
                                  (["--reference-encoder", "elsewhere.onnx"], 4),
                                  (["--max-frames", "2049"], 4)]:
            result = subprocess.run(base + options, capture_output=True, text=True, timeout=30)
            self.assertEqual(expected, result.returncode, result.stderr + result.stdout)

    def run_cli(self, *argv: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(RUNNER), *argv],
                              capture_output=True, text=True, timeout=300)

    def test_exit_4_on_missing_required_args(self):
        result = self.run_cli("--model-dir", "/tmp")
        self.assertEqual(result.returncode, 4)

    def test_exit_4_on_unknown_flag(self):
        result = self.run_cli("--model-dir", "/tmp", "--text", "x",
                              "--reference-wav", "x.wav", "--out", "o.wav",
                              "--definitely-not-a-flag")
        self.assertEqual(result.returncode, 4)

    def test_icl_requires_explicit_complete_options_before_dependencies(self):
        base = [sys.executable, "-S", str(RUNNER), "--model-dir", "/missing",
                "--text", "target", "--reference-wav", "ref.wav", "--out", "out.wav"]
        invalid = [
            ["--conditioning-mode", "icl"],
            ["--conditioning-mode", "icl", "--reference-text", "actual transcript"],
            ["--conditioning-mode", "icl", "--reference-encoder", "encoder.onnx"],
            ["--conditioning-mode", "icl", "--reference-text", "  ", "--reference-encoder", "encoder.onnx"],
            ["--reference-text", "must not be ignored"],
            ["--reference-encoder", "encoder.onnx"],
        ]
        for flags in invalid:
            with self.subTest(flags=flags):
                result = subprocess.run(base + flags, capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 4, result.stdout + result.stderr)
        result = subprocess.run(base + ["--conditioning-mode", "icl", "--reference-text", "actual transcript",
                                       "--reference-encoder", "encoder.onnx"], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertIn("MissingDependency", result.stdout)

    def test_missing_icl_graph_or_data_is_exit_2(self):
        if tts_runner.missing_dependencies():
            self.skipTest("runtime unavailable")
        with tempfile.TemporaryDirectory() as tmp:
            graph = Path(tmp) / "reference_encoder.onnx"
            for graph_exists in (False, True):
                if graph_exists:
                    graph.write_bytes(b"not loaded when data is missing")
                result = self.run_cli("--model-dir", tmp, "--text", "target", "--reference-wav", "ref.wav",
                                      "--out", str(Path(tmp) / "out.wav"), "--conditioning-mode", "icl",
                                      "--reference-text", "actual transcript", "--reference-encoder", str(graph))
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertIn("FileNotFoundError", result.stdout)
                self.assertIn("reference encoder artifact", result.stdout)

    def test_language_normalization(self):
        cases = {
            "zh": "chinese", "ZH": "chinese", "zh-CN": "chinese",
            "zh_CN": "chinese", "chinese": "chinese",
            "en": "english", "en-US": "english", "english": "english",
            "fr": "french", "ru-RU": "russian", "auto": "auto",
        }
        for raw, want in cases.items():
            self.assertEqual(tts_runner.normalize_language(raw), want, raw)
        for bad in ("xx", "zz-ZZ", "", "  "):
            with self.assertRaises(ValueError, msg=repr(bad)):
                tts_runner.normalize_language(bad)

    def test_exit_4_on_unsupported_language_before_model_load(self):
        # Contract from the central integration run: an unsupported language
        # must exit 4 fast (no 11 s model load, no exit-1 failure report).
        import time as _time
        t0 = _time.time()
        result = self.run_cli("--model-dir", "/nonexistent-tts-bundle",
                              "--text", "x", "--language", "zh-XY",
                              "--reference-wav", "x.wav", "--out", "o.wav")
        self.assertEqual(result.returncode, 4)
        self.assertLess(_time.time() - t0, 5.0)
        self.assertIn("unsupported language", result.stderr)

    def test_language_codes_accepted_at_cli_boundary(self):
        # 'zh' must pass CLI validation (it fails later on the missing bundle
        # with exit 2, never with 'unsupported language').
        result = self.run_cli("--model-dir", "/nonexistent-tts-bundle",
                              "--text", "x", "--language", "zh",
                              "--reference-wav", "x.wav", "--out", "o.wav")
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("unsupported language", result.stderr)

    def test_exit_2_with_failure_report_on_missing_bundle(self):
        missing = tts_runner.missing_dependencies()
        if missing:
            self.skipTest(f"deps missing ({missing}); covered by exit-2 test")
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "fail.json"
            result = self.run_cli(
                "--model-dir", str(Path(tmp) / "no-such-bundle"),
                "--text", "测试", "--language", "chinese",
                "--reference-wav", str(Path(tmp) / "no-ref.wav"),
                "--out", str(Path(tmp) / "out.wav"),
                "--json-report", str(report))
            self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
            payload = json.loads(report.read_text())
            self.assertEqual(payload["status"], "error")
            # A failure report must not be a success-shaped report.
            for field in ("audio_path", "duration_s", "sample_rate", "peak", "finite"):
                self.assertNotIn(field, payload)
            self.assertIn("error_type", payload)
            self.assertIn("generated_at", payload)

    def test_dependency_error_takes_precedence_over_bundle_errors(self):
        # With deps importable this reaches missing artifacts (exit 2); with
        # deps missing it must be exit 2 — the report distinguishes them.
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "r.json"
            result = self.run_cli(
                "--model-dir", "/nonexistent", "--text", "x",
                "--reference-wav", "x.wav", "--out", str(Path(tmp) / "o.wav"),
                "--json-report", str(report))
            payload = json.loads(report.read_text())
            if tts_runner.missing_dependencies():
                self.assertEqual(result.returncode, 2)
                self.assertEqual(payload["error_type"], "MissingDependency")
            else:
                self.assertEqual(result.returncode, 2)
                self.assertNotEqual(payload.get("error_type"), "MissingDependency")


class TestProvenanceHashes(unittest.TestCase):
    def test_hash_and_hardlink_reuse(self):
        # talker_prefill/decode ship byte-identical external data; when they
        # are hardlinked the runner hashes once and marks the twin.
        with tempfile.TemporaryDirectory() as tmp:
            model_dir = Path(tmp)
            a = model_dir / "talker_prefill.onnx.data"
            b = model_dir / "talker_decode.onnx.data"
            a.write_bytes(b"same-bytes")
            os.link(a, b)
            (model_dir / "vocoder.onnx.data").write_bytes(b"other")
            hashes = tts_runner.compute_model_hashes(model_dir)
            self.assertTrue(hashes["talker_prefill.onnx.data"].startswith("deadbeef")
                            or len(hashes["talker_prefill.onnx.data"]) == 64)
            self.assertIn("(hardlink-verified)", hashes["talker_decode.onnx.data"])
            self.assertEqual(len(hashes["vocoder.onnx.data"]), 64)
            self.assertNotIn("(hardlink-verified)", hashes["vocoder.onnx.data"])

    def test_missing_file_is_marked_not_silently_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            hashes = tts_runner.compute_model_hashes(Path(tmp))
            self.assertEqual(hashes["talker_prefill.onnx"], "MISSING")


class TestReferenceResampling(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if tts_runner.missing_dependencies():
            raise unittest.SkipTest("real numpy/librosa runtime unavailable")
        tts_runner._import_heavy()
        cls.np = tts_runner.np

    def test_rejects_invalid_input_before_resampling(self):
        for rate in (0, -16000, True, 16000.5, 7999, 192001):
            with self.assertRaises(ValueError):
                tts_runner.resample_reference_audio([0.1, 0.2], rate)
        for audio in ([], [float("nan")], [float("inf")], [[0.1, 0.2]]):
            with self.assertRaises(ValueError):
                tts_runner.resample_reference_audio(audio, 16000)
        with self.assertRaises(ValueError):
            tts_runner.resample_reference_audio(self.np.zeros(8000 * 30 + 1), 8000)

    def test_same_rate_preserves_samples_and_noninteger_ratio_length(self):
        audio = self.np.linspace(-0.5, 0.5, 1001, dtype=self.np.float32)
        self.np.testing.assert_array_equal(
            tts_runner.resample_reference_audio(audio, 24000), audio)
        converted = tts_runner.resample_reference_audio(audio, 44100)
        self.assertEqual(len(converted), (1001 * 24000 + 44100 - 1) // 44100)

    def test_downsampling_rejects_alias_without_attenuating_speech_band(self):
        # The old interpolation aliases 15 kHz to 9 kHz. Measure the settled
        # region so startup/filter tails cannot dominate this regression.
        t = self.np.arange(48000, dtype=self.np.float64) / 48000
        high = tts_runner.resample_reference_audio(
            self.np.sin(2 * self.np.pi * 15000 * t), 48000)[1024:-1024]
        low = tts_runner.resample_reference_audio(
            self.np.sin(2 * self.np.pi * 1000 * t), 48000)[1024:-1024]
        high_rms = float(self.np.sqrt(self.np.mean(high.astype(self.np.float64) ** 2)))
        low_rms = float(self.np.sqrt(self.np.mean(low.astype(self.np.float64) ** 2)))
        self.assertLess(high_rms, 1e-4)
        self.assertAlmostEqual(low_rms, 2 ** -0.5, delta=0.001)


class TestIclProtocol(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if tts_runner.missing_dependencies():
            raise unittest.SkipTest("real numpy runtime unavailable")
        tts_runner._import_heavy()
        cls.np = tts_runner.np

    def config(self):
        from types import SimpleNamespace
        return SimpleNamespace(talker={"codec_bos_id": 3}, code_predictor={"vocab_size": 2048},
                               tts={"tts_pad_token_id": 1, "tts_eos_token_id": 2})

    def test_encoder_output_contract_and_owned_copy(self):
        from types import SimpleNamespace
        np = self.np
        source = np.zeros((1, 16, 2), dtype=np.int64)
        session = SimpleNamespace(
            get_inputs=lambda: [SimpleNamespace(name="pcm", type="tensor(float)", shape=[1, 1, "N"])],
            get_outputs=lambda: [SimpleNamespace(name="codes", type="tensor(int64)", shape=[1, 16, "T"])],
            run=lambda names, feed: [source])
        pcm = np.full(1921, .1, dtype=np.float32)
        result = tts_runner.encode_reference_codes(session, pcm, self.config())
        self.assertEqual(result.shape, (16, 2))
        result[:] = 7
        self.assertFalse(source.any())
        bad_outputs = [np.zeros((1, 16, 2), dtype=np.float32), np.zeros((1, 16, 1), dtype=np.int64),
                       np.full((1, 16, 2), -1, dtype=np.int64), np.full((1, 16, 2), 2150, dtype=np.int64)]
        for bad in bad_outputs:
            session.run = lambda names, feed, bad=bad: [bad]
            with self.assertRaises(ValueError):
                tts_runner.encode_reference_codes(session, pcm, self.config())
        session.get_inputs = lambda: [SimpleNamespace(name="mel", type="tensor(float)", shape=[1, 1, "N"])]
        with self.assertRaisesRegex(ValueError, "graph does not match"):
            tts_runner.encode_reference_codes(session, pcm, self.config())

    def test_preparation_rejects_silence_before_model_bias_can_create_a_voice(self):
        np = self.np
        silence = np.zeros(2400, dtype=np.float32)
        np.testing.assert_array_equal(tts_runner.resample_reference_audio(silence, 24000), silence)
        for pcm in (silence, np.full(2400, 1e-5, dtype=np.float32)):
            with self.assertRaisesRegex(ValueError, "silent"):
                tts_runner.speaker_embedding_from_audio(None, pcm)
            with self.assertRaisesRegex(ValueError, "silent"):
                tts_runner.encode_reference_codes(None, pcm, self.config())

    def test_reference_code_shape_dtype_and_reserved_ids(self):
        np = self.np
        for bad in (np.zeros((15, 2), dtype=np.int64), np.zeros((16, 0), dtype=np.int64),
                    np.zeros((16, 376), dtype=np.int64), np.zeros((16, 2), dtype=np.float32),
                    np.full((16, 2), 2048, dtype=np.int64)):
            with self.assertRaises(ValueError):
                tts_runner.validate_reference_codes(bad, self.config())

    def test_official_token_slices_and_both_icl_length_branches(self):
        np = self.np
        class Tables:
            def text_embed(self, token): return np.array([token], dtype=np.float32)
            def project(self, x): return x
            def talker_codec_embedding(self, token): return np.array([10000 + token], dtype=np.float32)
            def cp_codec_embedding(self, group, token): return np.array([100 * group + token], dtype=np.float32)
        target = [99, 98, 97, 10, 11, 12, 96, 95, 94, 93, 92]
        reference = [99, 98, 97, 20, 21, 96, 95]
        prefix, trailing = tts_runner.build_icl_prompt(Tables(), self.config(), target, reference,
                                                     np.zeros((16, 2), dtype=np.int64))
        np.testing.assert_array_equal(prefix[:, 0], [10023, 20521, 20510])
        np.testing.assert_array_equal(trailing[:, 0], [11, 12, 2])
        prefix, trailing = tts_runner.build_icl_prompt(Tables(), self.config(), target, reference,
                                                     np.zeros((16, 8), dtype=np.int64))
        np.testing.assert_array_equal(prefix[:, 0], [10023, 20521, 20510, 20511, 20512, 20502, 20501, 20501, 20501])
        np.testing.assert_array_equal(trailing[:, 0], [1])

    def test_vocoder_drops_reference_frames_not_original_audio_sample_count(self):
        from types import SimpleNamespace
        np = self.np
        reference = np.ones((16, 2), dtype=np.int64)
        generated = np.full((16, 3), 2, dtype=np.int64)
        seen = []
        def run(names, feed):
            seen.append(feed["codes"].copy())
            return [np.arange(feed["codes"].shape[-1] * 1920, dtype=np.float32).reshape(1, 1, -1)]
        output = tts_runner.decode_waveform(SimpleNamespace(run=run), generated, reference)
        np.testing.assert_array_equal(seen[0], np.concatenate([reference, generated], axis=1)[None])
        np.testing.assert_array_equal(output, np.arange(3840, 9600, dtype=np.float32))
        with self.assertRaises(RuntimeError):
            tts_runner.decode_waveform(SimpleNamespace(run=lambda *args: [np.zeros((1, 1, 1))]), generated, reference)


class TestSampling(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if tts_runner.missing_dependencies():
            raise unittest.SkipTest("real numpy runtime unavailable")
        tts_runner._import_heavy()
        cls.np = tts_runner.np

    def test_greedy_is_deterministic_and_does_not_consume_rng(self):
        scores = self.np.array([-self.np.inf, 2.0, 2.0, 1.9])
        # A missing RNG is intentional: greedy must not consult randomness.
        for temperature, top_k in ((0.0, 0), (0.0, 50), (0.9, 1)):
            self.assertEqual(tts_runner._softmax_sample(scores, temperature, top_k, None), 1)

    def test_invalid_logits_fail_instead_of_returning_a_codec_token(self):
        for scores in ([], [float("nan"), 1], [float("inf"), 1], [-float("inf")] * 3):
            with self.assertRaises(ValueError):
                tts_runner._softmax_sample(self.np.array(scores), 0.9, 50, None)
        for temperature, top_k in ((-1.0, 1), (float("nan"), 1), (1.0, -1)):
            with self.assertRaises(ValueError):
                tts_runner._softmax_sample(self.np.array([1., 2.]), temperature, top_k, None)
        token = tts_runner._softmax_sample(self.np.array([2., 1.]), 1e-300, 0,
                                         self.np.random.default_rng(0))
        self.assertEqual(token, 0)

    def test_repetition_penalty_applies_once_per_distinct_token(self):
        from types import SimpleNamespace
        cfg = SimpleNamespace(talker={"vocab_size": 6, "codec_eos_token_id": 5},
                              code_predictor={"vocab_size": 5})
        scores = self.np.array([-10., -10., 2., -2., 1.8, -10.])
        for history in ([2, 3], [2, 2, 2, 3, 3]):
            self.assertEqual(tts_runner.sample_group0(scores, cfg, 0., 0, 1.05, history, None), 2)


class TestFullPipelineSmoke(unittest.TestCase):
    """Real bundle execution — gated, never faked."""

    def setUp(self):
        self.model_dir = os.environ.get("AURALIS_TTS_MODEL_DIR")
        self.ref_wav = os.environ.get("AURALIS_TTS_REF_WAV")
        if not self.model_dir or not self.ref_wav:
            self.skipTest("AURALIS_TTS_MODEL_DIR / AURALIS_TTS_REF_WAV not set")
        if tts_runner.missing_dependencies():
            self.skipTest(f"missing deps: {tts_runner.missing_dependencies()}")

    def test_real_synthesis_report_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_wav = Path(tmp) / "smoke.wav"
            report = Path(tmp) / "smoke.json"
            result = self.run_cli(
                "--model-dir", self.model_dir,
                "--text", "你好，世界。",
                "--language", "chinese",
                "--reference-wav", self.ref_wav,
                "--out", str(out_wav),
                "--json-report", str(report),
                "--seed", "42")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            payload = json.loads(report.read_text())
            self.assertEqual(payload["status"], "success")
            self.assertEqual(payload["sample_rate"], 24000)
            self.assertTrue(payload["finite"])
            self.assertGreater(payload["frames"], 0)
            self.assertGreater(payload["duration_s"], 0.2)
            self.assertGreater(payload["peak"], 0.01)
            self.assertTrue(Path(payload["audio_path"]).exists())
            for graph in ("talker_prefill.onnx", "vocoder.onnx", "code_predictor.onnx"):
                self.assertEqual(len(payload["model_sha256"][graph]), 64)
            self.assertIn("onnxruntime", payload["runtime_versions"])

    def run_cli(self, *argv: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(RUNNER), *argv],
                              capture_output=True, text=True, timeout=1800)


if __name__ == "__main__":
    unittest.main()
