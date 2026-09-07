"""Tests for convert/asr_runner.py.

CLI, WAV loading (including stereo downmix), metric math, transcript index,
bounded segmentation, and error-code distinction. Never invokes real model
inference.
"""

from __future__ import annotations

import importlib.util
import contextlib
import io
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import wave
import weakref
from unittest.mock import patch
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
_SPEC = importlib.util.spec_from_file_location("asr_runner", REPO / "convert" / "asr_runner.py")
asr_runner = importlib.util.module_from_spec(_SPEC)
sys.modules["asr_runner"] = asr_runner
_SPEC.loader.exec_module(asr_runner)


def write_wav(
    path: Path,
    rate: int = 16000,
    frames: list[int] | None = None,
    channels: int = 1,
    sampwidth: int = 2,
) -> Path:
    frames = frames or [0] * rate
    with wave.open(str(path), "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(sampwidth)
        w.setframerate(rate)
        if sampwidth == 2:
            w.writeframes(struct.pack(f"<{len(frames)}h", *frames))
        else:
            w.writeframes(bytes(frames))
    return path


class TestMetrics(unittest.TestCase):
    def test_cer_identical(self):
        assert asr_runner.cer("你好世界", "你好世界") == 0.0

    def test_cer_partial(self):
        assert asr_runner.cer("abcd", "abxy") == 0.5

    def test_cer_ignores_whitespace(self):
        assert asr_runner.cer("你 好 世界", "你好世界") == 0.0

    def test_cer_empty_reference(self):
        assert asr_runner.cer("", "anything") == 1.0

    def test_wer_english(self):
        assert asr_runner.wer("the cat sat", "the cat ran") == 1 / 3

    def test_wer_empty_reference(self):
        assert asr_runner.wer("", "word") == 1.0

    def test_edit_distance_basic(self):
        assert asr_runner.edit_distance(list("kitten"), list("sitting")) == 3
        assert asr_runner.edit_distance([], list("abc")) == 3


class TestWavLoading(unittest.TestCase):
    def test_load_16bit_mono(self):
        with tempfile.TemporaryDirectory() as d:
            p = write_wav(Path(d) / "a.wav", frames=[0, 16384, -16384])
            wav = asr_runner.load_wav(p)
            assert wav.sample_rate == 16000
            assert wav.channels == 1
            assert wav.downmixed is False
            assert wav.samples[1] == 0.5

    def test_stereo_downmix_averages_channels(self):
        with tempfile.TemporaryDirectory() as d:
            # interleaved L,R: 16384, 0 → average 8192 / 32768 = 0.25
            p = write_wav(Path(d) / "st.wav", frames=[16384, 0, 16384, 0], channels=2)
            wav = asr_runner.load_wav(p)
            assert wav.channels == 2
            assert wav.downmixed is True
            assert abs(wav.samples[0] - 0.25) < 1e-6
            assert abs(wav.samples[1] - 0.25) < 1e-6

    def test_rejects_non_16bit(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "a.wav"
            with wave.open(str(p), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(1)
                w.setframerate(16000)
                w.writeframes(b"\x00" * 100)
            try:
                asr_runner.load_wav(p)
            except asr_runner.RunnerInputError as e:
                assert "16-bit" in str(e)
                assert e.exit_code == 4
            else:
                raise AssertionError("expected RunnerInputError")

    def test_rejects_empty(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "empty.wav"
            with wave.open(str(p), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(16000)
                w.writeframes(b"")
            try:
                asr_runner.load_wav(p)
            except asr_runner.RunnerInputError as e:
                assert e.exit_code == 4
            else:
                raise AssertionError("expected RunnerInputError")

    def test_rejects_payload_shorter_than_header(self):
        with tempfile.TemporaryDirectory() as d:
            p = write_wav(Path(d) / "short.wav", frames=[0, 1, 2, 3, 4, 5])
            data = p.read_bytes()
            p.write_bytes(data[:-4])  # drop two PCM frames; header still claims 6
            try:
                asr_runner.load_wav(p)
            except asr_runner.RunnerInputError as e:
                assert e.exit_code == 4
                assert "payload" in str(e) or "malformed" in str(e).lower()
            else:
                raise AssertionError("expected RunnerInputError")

    def test_rejects_malformed_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "bad.wav"
            p.write_bytes(b"not a wav")
            try:
                asr_runner.load_wav(p)
            except asr_runner.RunnerInputError as e:
                assert e.exit_code == 4
            else:
                raise AssertionError("expected RunnerInputError")


class TestResampleAndSegment(unittest.TestCase):
    def test_resample_identity(self):
        s = [0.0, 0.5, -0.5]
        assert asr_runner.resample_linear_for_vad(s, 16000, 16000) == s

    def test_bounded_short_is_whole(self):
        sr = 16000
        samples = [0.0] * (sr * 3)
        ranges = asr_runner.bounded_energy_segments(samples, sr, max_s=20.0)
        assert ranges == [(0, len(samples))]

    def test_bounded_long_splits_under_cap(self):
        sr = 16000
        samples = [0.01] * (sr * 50)
        ranges = asr_runner.bounded_energy_segments(samples, sr, max_s=20.0)
        assert len(ranges) >= 2
        for a, b in ranges:
            assert (b - a) / sr <= 20.0 + 1e-6
        assert ranges[0][0] == 0
        assert ranges[-1][1] == len(samples)

    def test_looks_truncated(self):
        assert asr_runner.looks_truncated("language", 51.0) is True
        assert asr_runner.looks_truncated("hello world", 51.0) is False
        assert asr_runner.looks_truncated("language", 5.0) is False


class TestCli(unittest.TestCase):
    def test_all_inputs_validated_before_model_and_decoded_one_at_a_time(self):
        """Real PCM loading; the inference boundary alone is replaced here."""
        with tempfile.TemporaryDirectory() as d:
            paths = [write_wav(Path(d) / f'{i}.wav', frames=[i, 16384, -16384])
                     for i in range(3)]
            live = []
            loaded = []
            decoded = []
            original_load = asr_runner.load_wav

            def load(path):
                self.assertTrue(all(ref() is None for ref in live),
                                'Previous audio retained when loading the next file')
                audio = original_load(path)
                live.append(weakref.ref(audio))
                loaded.append(path)
                return audio

            def transcribe(recognizer, audio, vad_model, threads, mode):
                self.assertEqual(sum(ref() is not None for ref in live), 1)
                self.assertEqual(audio.samples[1:], [0.5, -0.5])
                decoded.append(audio.samples[0])
                return {'unsegmented': {'text': 'a b', 'rtf': 0.25}}

            output = io.StringIO()
            with patch.object(asr_runner, 'load_wav', new=load), \
                 patch.object(asr_runner, 'build_recognizer', return_value=object()), \
                 patch.object(asr_runner, 'transcribe_file', new=transcribe), \
                 patch.object(asr_runner, 'runtime_versions', return_value={}), \
                 contextlib.redirect_stdout(output):
                code = asr_runner.main(['--models-dir', d, '--segment', 'off'] +
                    [arg for p in paths for arg in ('--audio', str(p), '--reference', 'a c')])
            self.assertEqual(code, 0)
            self.assertEqual(loaded, paths)
            self.assertEqual(decoded, [0, 1 / 32768, 2 / 32768])
            self.assertTrue(all(ref() is None for ref in live))
            report = json.loads(output.getvalue())
            self.assertEqual([r['audio'] for r in report['results']], list(map(str, paths)))
            self.assertEqual([r['cer'] for r in report['results']], [0.5] * 3)
            self.assertEqual([r['wer'] for r in report['results']], [0.5] * 3)
            self.assertEqual(report['mean_cer'], 0.5)

    def test_later_bad_wav_fails_before_any_model_or_decode(self):
        with tempfile.TemporaryDirectory() as d:
            good = write_wav(Path(d) / 'good.wav')
            bad = write_wav(Path(d) / 'bad.wav', frames=[1, 2, 3])
            bad.write_bytes(bad.read_bytes()[:-2])
            with patch.object(asr_runner, 'build_recognizer') as build, \
                 patch.object(asr_runner, 'load_wav') as load, \
                 contextlib.redirect_stderr(io.StringIO()) as errors:
                code = asr_runner.main(['--models-dir', d, '--audio', str(good),
                                        '--audio', str(bad)])
            self.assertEqual(code, 4)
            self.assertIn('payload', errors.getvalue())
            build.assert_not_called()
            load.assert_not_called()

    def test_reference_coverage_is_checked_before_wav_loading(self):
        with tempfile.TemporaryDirectory() as d:
            transcript = Path(d) / 'transcript.txt'
            transcript.write_text('first.wav first\n')
            with patch.object(asr_runner, '_read_wav_pcm') as read, \
                 patch.object(asr_runner, 'build_recognizer') as build, \
                 contextlib.redirect_stderr(io.StringIO()):
                code = asr_runner.main(['--models-dir', d, '--audio', str(Path(d) / 'first.wav'),
                                       '--audio', str(Path(d) / 'missing.wav'),
                                       '--transcripts', str(transcript)])
            self.assertEqual(code, 4)
            read.assert_not_called()
            build.assert_not_called()

    def test_wav_changed_after_preflight_is_revalidated(self):
        with tempfile.TemporaryDirectory() as d:
            wav = write_wav(Path(d) / 'changed.wav', frames=[1, 2, 3])

            def build(*args):
                wav.write_bytes(wav.read_bytes()[:-2])
                return object()

            with patch.object(asr_runner, 'build_recognizer', new=build), \
                 patch.object(asr_runner, 'transcribe_file') as transcribe, \
                 contextlib.redirect_stderr(io.StringIO()) as errors:
                code = asr_runner.main(['--models-dir', d, '--audio', str(wav)])
            self.assertEqual(code, 4)
            self.assertIn('payload', errors.getvalue())
            transcribe.assert_not_called()

    def test_missing_models_dir_is_env_error(self):
        with tempfile.TemporaryDirectory() as d:
            wav = write_wav(Path(d) / "a.wav")
            result = subprocess.run(
                [sys.executable, str(REPO / "convert" / "asr_runner.py"),
                 "--models-dir", "/nonexistent/asr",
                 "--audio", str(wav),
                 "--segment", "off"],
                capture_output=True, text=True, timeout=60,
            )
            assert result.returncode == 2
            assert ("missing" in result.stderr) or ("not installed" in result.stderr)

    def test_no_audio_is_input_error(self):
        result = subprocess.run(
            [sys.executable, str(REPO / "convert" / "asr_runner.py"),
             "--models-dir", "/nonexistent"],
            capture_output=True, text=True, timeout=60,
        )
        assert result.returncode == 4
        assert "no --audio" in result.stderr

    def test_missing_audio_file_is_input_error(self):
        result = subprocess.run(
            [sys.executable, str(REPO / "convert" / "asr_runner.py"),
             "--models-dir", "/nonexistent/asr",
             "--audio", "/tmp/definitely-missing-auralis.wav",
             "--segment", "off"],
            capture_output=True, text=True, timeout=60,
        )
        assert result.returncode == 4
        assert "not found" in result.stderr

    def test_malformed_wav_is_input_error(self):
        with tempfile.TemporaryDirectory() as d:
            bad = Path(d) / "bad.wav"
            bad.write_bytes(b"not a wav")
            result = subprocess.run(
                [sys.executable, str(REPO / "convert" / "asr_runner.py"),
                 "--models-dir", "/nonexistent/asr",
                 "--audio", str(bad),
                 "--segment", "off"],
                capture_output=True, text=True, timeout=60,
            )
            assert result.returncode == 4

    def test_reference_count_mismatch_fails(self):
        result = subprocess.run(
            [sys.executable, str(REPO / "convert" / "asr_runner.py"),
             "--models-dir", "/nonexistent",
             "--audio", "/tmp/x.wav", "--audio", "/tmp/y.wav",
             "--reference", "only one"],
            capture_output=True, text=True, timeout=60,
        )
        assert result.returncode == 4

    def test_transcript_index_parsing(self):
        p = Path(tempfile.mkdtemp()) / "transcript.txt"
        p.write_text(
            "de.wav Raptorium Bergbau.\n\nzh1.wav\t你好。\nar1.wav  arabic\n",
            encoding="utf-8",
        )
        index = asr_runner.read_transcript_index(p)
        assert index == {
            "de": "Raptorium Bergbau.",
            "zh1": "你好。",
            "ar1": "arabic",
        }
