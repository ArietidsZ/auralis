#!/usr/bin/env python3
"""Real ASR runner for sherpa-onnx Qwen3-ASR-0.6B INT8.

Official path: OfflineRecognizer.from_qwen3_asr (conv_frontend + encoder.int8
+ decoder.int8 + tokenizer dir). Long audio is also decoded unsegmented so
KV-cache truncation is visible, then again with Silero VAD / bounded energy
splits. Fixtures never fake inference.

Exit codes (same as convert/validate_models.py):
  0  success
  1  inference failure
  2  missing dependency / model artifact
  3  contract (not used by this runner)
  4  arguments / malformed input
"""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import struct
import sys
import time
import wave
from dataclasses import dataclass
from pathlib import Path

MODEL_FILES = {
    "conv_frontend": "conv_frontend.onnx",
    "encoder": "encoder.int8.onnx",
    "decoder": "decoder.int8.onnx",
}

# Conv frontend yields ~13 audio tokens/s. Prompt ≈15 tokens. KV default 512
# so unsegmented clips ≳40 s truncate (51 s / 88 s empirically). rap1 at 29 s
# is under budget; do not VAD-split it. After the trigger, pieces ≤20 s leave
# room for max_new_tokens=192 (20*13+15+192 < 512).
SEGMENT_TRIGGER_S = 36.0
MAX_SEGMENT_S = 20.0
VAD_MAX_SPEECH_S = 20.0
MAX_NEW_TOKENS = 192
BOUNDARY_PAD_S = 0.20
VAD_SAMPLE_RATE = 16000


class RunnerError(RuntimeError):
    exit_code = 2


class RunnerEnvError(RunnerError):
    """Missing sherpa-onnx install or model files."""
    exit_code = 2


class RunnerFailError(RunnerError):
    """Recognizer ran and failed."""
    exit_code = 1


class RunnerInputError(RunnerError):
    """CLI arguments or malformed/missing WAV. Not contract (3)."""
    exit_code = 4


@dataclass
class WavAudio:
    sample_rate: int
    samples: list[float]
    channels: int
    downmixed: bool

    @property
    def duration_s(self) -> float:
        if self.sample_rate <= 0:
            return 0.0
        return len(self.samples) / self.sample_rate


@dataclass
class SegmentResult:
    start_s: float
    end_s: float
    text: str
    decode_s: float


@contextlib.contextmanager
def _stdout_to_stderr():
    """Route fd 1 to stderr: sherpa-onnx C++ logs would corrupt JSON."""
    saved = os.dup(1)
    try:
        os.dup2(2, 1)
        yield
    finally:
        os.dup2(saved, 1)
        os.close(saved)


def _read_wav_pcm(path: Path) -> tuple[int, int, int, bytes]:
    """Validate a complete PCM payload without allocating Python float samples."""
    try:
        with wave.open(str(path), "rb") as w:
            nch = w.getnchannels()
            width = w.getsampwidth()
            sr = w.getframerate()
            nframes = w.getnframes()
            raw = w.readframes(nframes)
    except FileNotFoundError as e:
        raise RunnerInputError(f"audio not found: {path}") from e
    except wave.Error as e:
        raise RunnerInputError(f"{path}: malformed WAV ({e})") from e
    except OSError as e:
        raise RunnerInputError(f"{path}: cannot read WAV ({e})") from e

    if width != 2:
        raise RunnerInputError(f"{path}: expected 16-bit PCM, got sampwidth={width}")
    if nch < 1:
        raise RunnerInputError(f"{path}: invalid channel count {nch}")
    if sr < 1:
        raise RunnerInputError(f"{path}: invalid sample rate {sr}")
    if nframes < 1:
        raise RunnerInputError(f"{path}: empty WAV")
    expected = nframes * nch * width
    if len(raw) != expected:
        raise RunnerInputError(
            f"{path}: WAV payload {len(raw)} bytes != header nframes*{nch}*"
            f"{width} ({expected})"
        )
    return sr, nch, nframes, raw


def load_wav(path: Path) -> WavAudio:
    """Load validated 16-bit PCM WAV, averaging multiple channels to mono.

    Missing, malformed, unsupported, or short WAV payloads raise
    RunnerInputError (exit 4), including during the CLI's initial validation.
    """
    sr, nch, nframes, raw = _read_wav_pcm(path)
    unpacked = struct.unpack(f"<{nframes * nch}h", raw)
    n_frames = nframes
    if nch == 1:
        samples = [s / 32768.0 for s in unpacked]
        return WavAudio(sr, samples, 1, False)

    samples = []
    inv = 1.0 / (nch * 32768.0)
    for i in range(n_frames):
        acc = 0
        base = i * nch
        for c in range(nch):
            acc += unpacked[base + c]
        samples.append(acc * inv)
    return WavAudio(sr, samples, nch, True)


def resample_linear_for_vad(samples: list[float], src_sr: int, dst_sr: int) -> list[float]:
    """Cheap resampler used ONLY to get Silero VAD timestamps.

    Decode always feeds the original PCM + native sample rate to sherpa-onnx
    so its anti-aliased resampler owns quality. Do not pass this output into
    OfflineRecognizer.
    """
    if src_sr == dst_sr or not samples:
        return list(samples)
    if src_sr < 1 or dst_sr < 1:
        raise RunnerInputError(f"invalid resample {src_sr} -> {dst_sr}")
    n_out = max(1, int(round(len(samples) * dst_sr / src_sr)))
    if n_out == 1:
        return [samples[0]]
    ratio = src_sr / dst_sr
    last = len(samples) - 1
    out = [0.0] * n_out
    for i in range(n_out):
        x = i * ratio
        j = int(x)
        if j >= last:
            out[i] = samples[last]
            continue
        f = x - j
        out[i] = samples[j] * (1.0 - f) + samples[j + 1] * f
    return out


def edit_distance(a: list[str], b: list[str]) -> int:
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[len(b)]


def cer(reference: str, hypothesis: str) -> float:
    ref = [c for c in reference.strip() if not c.isspace()]
    hyp = [c for c in hypothesis.strip() if not c.isspace()]
    if not ref:
        return 0.0 if not hyp else 1.0
    return edit_distance(ref, hyp) / len(ref)


def wer(reference: str, hypothesis: str) -> float:
    ref = reference.split()
    hyp = hypothesis.split()
    if not ref:
        return 0.0 if not hyp else 1.0
    return edit_distance(ref, hyp) / len(ref)


def read_transcript_index(path: Path) -> dict[str, str]:
    """Parse test_wavs/transcript.txt: filename then text, any whitespace."""
    index: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split(None, 1)
        stem = Path(parts[0]).stem
        index[stem] = parts[1].strip() if len(parts) > 1 else ""
    return index


def looks_truncated(text: str, duration_s: float) -> bool:
    t = text.strip().lower()
    if duration_s >= 40.0 and t in {"", "language"}:
        return True
    return t == "language" and duration_s >= 30.0


def join_segment_texts(texts: list[str]) -> str:
    return " ".join(t.strip() for t in texts if t.strip()).strip()


def _frame_energy(samples: list[float], start: int, hop: int) -> float:
    end = min(len(samples), start + hop)
    if end <= start:
        return 0.0
    acc = 0.0
    for i in range(start, end):
        v = samples[i]
        acc += v * v
    return acc / (end - start)


def bounded_energy_segments(
    samples: list[float],
    sample_rate: int,
    max_s: float = MAX_SEGMENT_S,
) -> list[tuple[int, int]]:
    """Return [start, end) sample ranges, each ≤ max_s, split at low energy."""
    n = len(samples)
    max_n = max(1, int(max_s * sample_rate))
    if n <= max_n:
        return [(0, n)]
    hop = max(1, int(0.02 * sample_rate))
    ranges: list[tuple[int, int]] = []
    pos = 0
    while pos < n:
        remain = n - pos
        if remain <= max_n:
            ranges.append((pos, n))
            break
        window_end = pos + max_n
        search_from = pos + int(max_n * 0.55)
        best_i = window_end
        best_e = float("inf")
        i = search_from
        while i < window_end:
            e = _frame_energy(samples, i, hop)
            if e < best_e:
                best_e = e
                best_i = i
            i += hop
        cut = min(n, max(pos + hop, best_i))
        ranges.append((pos, cut))
        pos = cut
    return ranges or [(0, n)]


def silero_vad_segments(
    samples_16k: list[float],
    vad_model: Path,
    max_speech_s: float = VAD_MAX_SPEECH_S,
    num_threads: int = 1,
) -> list[tuple[int, int]]:
    import sherpa_onnx

    config = sherpa_onnx.VadModelConfig()
    config.sample_rate = VAD_SAMPLE_RATE
    config.num_threads = num_threads
    config.provider = "cpu"
    config.silero_vad.model = str(vad_model)
    config.silero_vad.threshold = 0.3
    config.silero_vad.min_silence_duration = 0.4
    config.silero_vad.min_speech_duration = 0.25
    config.silero_vad.max_speech_duration = max_speech_s
    config.silero_vad.window_size = 512
    window = int(config.silero_vad.window_size)
    vad = sherpa_onnx.VoiceActivityDetector(config, buffer_size_in_seconds=120)
    ranges: list[tuple[int, int]] = []

    def _drain() -> None:
        while not vad.empty():
            seg = vad.front
            start = int(seg.start)
            n = len(seg.samples)
            ranges.append((start, start + n))
            vad.pop()

    with _stdout_to_stderr():
        i = 0
        n = len(samples_16k)
        while i + window <= n:
            vad.accept_waveform(samples_16k[i : i + window])
            _drain()
            i += window
        if i < n:
            tail = list(samples_16k[i:]) + [0.0] * (window - (n - i))
            vad.accept_waveform(tail)
            _drain()
        vad.flush()
        _drain()
    return ranges


def pad_ranges(
    ranges: list[tuple[int, int]], n: int, pad: int
) -> list[tuple[int, int]]:
    out: list[tuple[int, int]] = []
    for a, b in ranges:
        out.append((max(0, a - pad), min(n, b + pad)))
    merged: list[tuple[int, int]] = []
    for a, b in sorted(out):
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return merged


def segment_audio(
    samples: list[float],
    sample_rate: int,
    vad_model: Path | None,
    num_threads: int,
) -> tuple[list[tuple[int, int]], str]:
    """Return sample ranges in `sample_rate` plus the method used."""
    n = len(samples)
    if n == 0:
        return [], "empty"
    duration = n / sample_rate
    if duration <= SEGMENT_TRIGGER_S:
        return [(0, n)], "whole"

    method = "energy"
    ranges_src: list[tuple[int, int]] = []
    if vad_model is not None and vad_model.is_file():
        # VAD timestamps only. Decode uses original PCM (see decode_samples).
        samples_16k = resample_linear_for_vad(samples, sample_rate, VAD_SAMPLE_RATE)
        try:
            ranges_16k = silero_vad_segments(
                samples_16k, vad_model, VAD_MAX_SPEECH_S, num_threads
            )
        except Exception as e:  # VAD failure is not an env/model-missing case
            ranges_16k = []
            method = f"energy (vad-failed: {e})"
        else:
            if ranges_16k:
                method = "silero-vad"
                ratio = sample_rate / VAD_SAMPLE_RATE
                min_len = int(0.3 * sample_rate)
                ranges_src = [
                    (int(a * ratio), min(n, int(b * ratio)))
                    for a, b in ranges_16k
                    if (b - a) * ratio >= min_len
                ]
            else:
                method = "energy (vad-empty)"
    if not ranges_src:
        ranges_src = bounded_energy_segments(samples, sample_rate)
        if method == "whole":
            method = "energy"

    pad = int(BOUNDARY_PAD_S * sample_rate)
    ranges_src = pad_ranges(ranges_src, n, pad)
    # Hard cap: re-split any piece still over the KV budget.
    capped: list[tuple[int, int]] = []
    for a, b in ranges_src:
        piece = samples[a:b]
        for sa, sb in bounded_energy_segments(piece, sample_rate):
            capped.append((a + sa, a + sb))
    if not capped:
        capped = [(0, n)]
        method = method + "+fallback-whole"
    return capped, method


def build_recognizer(models_dir: Path, num_threads: int, max_total_len: int):
    try:
        import sherpa_onnx
    except ImportError as e:
        raise RunnerEnvError(
            "sherpa-onnx is not installed. Create a venv and "
            "`pip install sherpa-onnx==1.13.7`."
        ) from e

    missing = [name for name, fn in MODEL_FILES.items() if not (models_dir / fn).is_file()]
    if not (models_dir / "tokenizer").is_dir():
        missing.append("tokenizer/")
    if missing:
        raise RunnerEnvError(f"{models_dir}: missing {missing}")

    try:
        with _stdout_to_stderr():
            return sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
                conv_frontend=str(models_dir / MODEL_FILES["conv_frontend"]),
                encoder=str(models_dir / MODEL_FILES["encoder"]),
                decoder=str(models_dir / MODEL_FILES["decoder"]),
                tokenizer=str(models_dir / "tokenizer"),
                num_threads=num_threads,
                max_total_len=max_total_len,
                max_new_tokens=MAX_NEW_TOKENS,
                feature_dim=128,
            )
    except RunnerError:
        raise
    except Exception as e:
        raise RunnerFailError(f"{models_dir}: recognizer create failed ({e})") from e


def decode_samples(recognizer, samples: list[float], sample_rate: int) -> tuple[str, float]:
    """Decode original PCM. sherpa-onnx resamples 44.1 kHz etc. itself."""
    t0 = time.perf_counter()
    try:
        with _stdout_to_stderr():
            stream = recognizer.create_stream()
            stream.accept_waveform(sample_rate, samples)
            recognizer.decode_stream(stream)
            text = stream.result.text
    except RunnerError:
        raise
    except Exception as e:
        raise RunnerFailError(f"sherpa-onnx decode failed: {e}") from e
    return text, time.perf_counter() - t0


def transcribe_file(
    recognizer,
    audio: WavAudio,
    vad_model: Path | None,
    num_threads: int,
    segment_mode: str,
) -> dict:
    duration = audio.duration_s
    entry: dict = {
        "sample_rate": audio.sample_rate,
        "channels": audio.channels,
        "downmixed": audio.downmixed,
        "duration_s": round(duration, 3),
        "n_samples": len(audio.samples),
    }

    if segment_mode in ("off", "both"):
        text, decode_s = decode_samples(recognizer, audio.samples, audio.sample_rate)
        entry["unsegmented"] = {
            "text": text,
            "decode_s": round(decode_s, 3),
            "rtf": round(decode_s / duration, 4) if duration > 0 else None,
            "truncated": looks_truncated(text, duration),
        }

    if segment_mode in ("on", "both"):
        task_start = time.perf_counter()
        ranges, method = segment_audio(
            audio.samples, audio.sample_rate, vad_model, num_threads
        )
        segmentation_s = time.perf_counter() - task_start
        segs: list[SegmentResult] = []
        t_all = 0.0
        for a, b in ranges:
            piece = audio.samples[a:b]
            if not piece:
                continue
            text, decode_s = decode_samples(recognizer, piece, audio.sample_rate)
            t_all += decode_s
            segs.append(
                SegmentResult(
                    start_s=a / audio.sample_rate,
                    end_s=b / audio.sample_rate,
                    text=text,
                    decode_s=decode_s,
                )
            )
        joined = join_segment_texts([s.text for s in segs])
        entry["segmented"] = {
            "method": method,
            "n_segments": len(segs),
            "text": joined,
            "decode_s": round(t_all, 3),
            "segmentation_s": round(segmentation_s, 4),
            "total_s": round(time.perf_counter() - task_start, 4),
            "rtf": round((time.perf_counter() - task_start) / duration, 4) if duration > 0 else None,
            "segments": [
                {
                    "start_s": round(s.start_s, 3),
                    "end_s": round(s.end_s, 3),
                    "decode_s": round(s.decode_s, 3),
                    "text": s.text,
                }
                for s in segs
            ],
        }
    return entry


def runtime_versions() -> dict:
    import sherpa_onnx
    return {"sherpa_onnx": sherpa_onnx.version,
            "git_revision": sherpa_onnx.git_sha1,
            "onnxruntime": sherpa_onnx.onnxruntime_version}


class RunnerParser(argparse.ArgumentParser):
    def error(self, message):
        self.print_usage(sys.stderr)
        self.exit(4, f"error: {message}\n")


def _mean(values: list[float]) -> float | None:
    return round(sum(values) / len(values), 4) if values else None


def main(argv: list[str] | None = None) -> int:
    parser = RunnerParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--models-dir",
        type=Path,
        required=True,
        help="Directory with conv_frontend.onnx, encoder.int8.onnx, "
        "decoder.int8.onnx and tokenizer/",
    )
    parser.add_argument("--audio", type=Path, action="append", default=[],
                        help="WAV file to transcribe; repeatable")
    parser.add_argument("--reference", action="append", default=[],
                        help="Reference transcript for the corresponding --audio")
    parser.add_argument("--transcripts", type=Path,
                        help="test_wavs/transcript.txt used as fallback by stem")
    parser.add_argument("--num-threads", type=int, default=2)
    parser.add_argument("--max-total-len", type=int, default=512,
                        help="KV cache cap passed to sherpa-onnx (graph dim is symbolic)")
    parser.add_argument("--segment", choices=("off", "on", "both"), default="both",
                        help="off=unsegmented only; on=VAD/bounded only; both=keep raw + segmented")
    parser.add_argument("--vad-model", type=Path,
                        help="silero_vad.onnx; energy split is used if missing")
    parser.add_argument("--json-report", type=Path, help="Write JSON report here")
    args = parser.parse_args(argv)

    try:
        if args.num_threads < 1 or args.max_total_len < 1:
            raise RunnerInputError("--num-threads and --max-total-len must be positive")
        if not args.audio:
            raise RunnerInputError("no --audio given")
        if args.reference and len(args.reference) != len(args.audio):
            raise RunnerInputError(
                f"--reference count ({len(args.reference)}) != --audio count ({len(args.audio)})")
        if args.transcripts is not None and not args.transcripts.is_file():
            raise RunnerInputError(f"transcripts not found: {args.transcripts}")
        index = read_transcript_index(args.transcripts) if args.transcripts else {}
        if args.transcripts and any(p.stem not in index for p in args.audio):
            raise RunnerInputError("transcripts must include every requested audio sample")

        for audio_path in args.audio:
            if not audio_path.is_file():
                raise RunnerInputError(f"audio not found: {audio_path}")
            # Preserve fail-before-model-load validation for every input, but
            # discard each PCM payload instead of retaining the whole corpus
            # as Python floats. Decode revalidates the file when it is read.
            _read_wav_pcm(audio_path)

        vad_model = args.vad_model
        if vad_model is not None and not vad_model.is_file():
            raise RunnerInputError(f"vad model not found: {vad_model}")
        recognizer = build_recognizer(args.models_dir, args.num_threads, args.max_total_len)

        results = []
        for i, audio_path in enumerate(args.audio):
            entry = transcribe_file(
                recognizer, load_wav(audio_path), vad_model, args.num_threads, args.segment
            )
            entry["audio"] = str(audio_path)
            reference = args.reference[i] if i < len(args.reference) else index.get(audio_path.stem)
            if reference is not None:
                entry["reference"] = reference
                if "unsegmented" in entry:
                    u = entry["unsegmented"]["text"]
                    entry["cer_unsegmented"] = round(cer(reference, u), 4)
                    entry["wer_unsegmented"] = round(wer(reference, u), 4)
                if "segmented" in entry:
                    s = entry["segmented"]["text"]
                    entry["cer_segmented"] = round(cer(reference, s), 4)
                    entry["wer_segmented"] = round(wer(reference, s), 4)
                # Production metric: segmented when present, else unsegmented.
                if "cer_segmented" in entry:
                    entry["cer"] = entry["cer_segmented"]
                    entry["wer"] = entry["wer_segmented"]
                    entry["text"] = entry["segmented"]["text"]
                    entry["rtf"] = entry["segmented"]["rtf"]
                else:
                    entry["cer"] = entry["cer_unsegmented"]
                    entry["wer"] = entry["wer_unsegmented"]
                    entry["text"] = entry["unsegmented"]["text"]
                    entry["rtf"] = entry["unsegmented"]["rtf"]
            results.append(entry)
    except RunnerError as e:
        print(f"error: {e}", file=sys.stderr)
        return e.exit_code

    report = {
        "model_dir": str(args.models_dir),
        "backend": "sherpa-onnx qwen3-asr (official OfflineRecognizer.from_qwen3_asr)",
        "num_threads": args.num_threads,
        "max_total_len": args.max_total_len,
        "segment": args.segment,
        "vad_model": str(vad_model) if vad_model else None,
        "runtime": runtime_versions(),
        "results": results,
        "mean_cer": _mean([r["cer"] for r in results if "cer" in r]),
        "mean_cer_unsegmented": _mean(
            [r["cer_unsegmented"] for r in results if "cer_unsegmented" in r]
        ),
        "mean_cer_segmented": _mean(
            [r["cer_segmented"] for r in results if "cer_segmented" in r]
        ),
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.json_report:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        args.json_report.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
