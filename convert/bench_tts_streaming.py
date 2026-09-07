#!/usr/bin/env python3
"""Exercise real incremental TTS frames against the experimental stateful vocoder.

This writes numerical/timing evidence, not a production readiness verdict.
No audio is played. All timings belong to this host and process.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import platform
import resource
import shutil
import time
from types import SimpleNamespace

import tts_runner as tts


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model-dir", required=True, type=Path)
    p.add_argument("--streaming-vocoder", required=True, type=Path)
    p.add_argument("--reference-wav", required=True, type=Path)
    p.add_argument("--conditioning-mode", choices=["xvector", "icl"], default="xvector")
    p.add_argument("--reference-text")
    p.add_argument("--reference-encoder", type=Path)
    p.add_argument("--unified-talker", type=Path, help="experimental API2 graph, with adjacent .data")
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--text", default="你好，世界。")
    p.add_argument("--language", default="zh")
    p.add_argument("--chunk-frames", type=int, default=4)
    p.add_argument("--max-frames", type=int, default=384)
    p.add_argument("--threads", type=int, default=4)
    a = p.parse_args()
    if not 1 <= a.chunk_frames <= 13 or not 1 <= a.max_frames <= 2048 or a.threads < 1:
        p.error("chunk frames must be 1..13, frame budget 1..2048, threads positive")
    try:
        tts.validate_conditioning_options(a.conditioning_mode, a.reference_text, a.reference_encoder)
    except ValueError as exc:
        p.error(str(exc))
    a.output.mkdir(parents=True, exist_ok=True)
    report = {"status": "running", "scope": "host numerical/availability experiment",
              "chunks": [], "chunkFrames": a.chunk_frames,
              "text": a.text, "language": a.language,
              "conditioningMode": a.conditioning_mode,
              "talkerMode": "api2-unified" if a.unified_talker else "legacy-serial",
              "sampling": {"temperature": 0.9, "topK": 50, "penalty": 1.05, "seed": 20260906}}

    def persist():
        (a.output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

    try:
        tts._import_heavy()
        np, ort = tts.np, tts.ort
        if ort.__version__ != "1.24.2":
            raise RuntimeError("this candidate comparison requires onnxruntime==1.24.2")
        report["runtime"] = tts._runtime_versions()
        paths = [
            Path(__file__), Path(tts.__file__), a.reference_wav,
            a.streaming_vocoder, Path(str(a.streaming_vocoder) + ".data")]
        for graph in (a.reference_encoder, a.unified_talker):
            if graph is not None:
                paths.extend([graph, Path(str(graph) + ".data")])
        report["hashes"] = {str(path): tts.sha256_file(path) for path in paths}
        source_dir = a.output / "source"
        source_dir.mkdir(exist_ok=True)
        for script in (Path(__file__), Path(tts.__file__)):
            shutil.copy2(script, source_dir / script.name)
        report["modelHashes"] = tts.compute_model_hashes(a.model_dir)
        options = ort.SessionOptions()
        options.intra_op_num_threads = a.threads
        options.inter_op_num_threads = 1
        options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL

        class Graph:
            def __init__(self, role, once=False, path=None, outputs=None):
                self.path = path or a.model_dir / (role + ".onnx")
                self.once, self.session = once, None
                self.outputs = outputs

            def run(self, names, feed):
                if self.session is None:
                    self.session = ort.InferenceSession(str(self.path), sess_options=options,
                                                        providers=["CPUExecutionProvider"])
                try:
                    return self.session.run(names if names is not None else self.outputs, feed)
                finally:
                    if self.once:
                        self.session = None

        prepare_start = time.perf_counter()
        cfg = tts.load_config(a.model_dir)
        tables = tts.EmbeddingTables(a.model_dir)
        tokenizer = tts.load_tokenizer(a.model_dir)
        bundle = SimpleNamespace(sessions={
            "speaker_encoder": Graph("speaker_encoder", once=True),
            "talker_prefill": Graph("talker_prefill", once=True),
            "talker_decode": Graph("talker_decode"),
            "code_predictor": Graph("code_predictor"),
        })
        if a.unified_talker:
            unified = Graph("talker", path=a.unified_talker,
                            outputs=["logits", "last_hidden_state", "present_keys", "present_values"])

            class PrefillView:
                """Only adapts the old generator's output container; one real session."""
                def run(self, names, feed):
                    shape = (cfg.talker["num_hidden_layers"], 1,
                             cfg.talker["num_key_value_heads"], 0, cfg.talker["head_dim"])
                    values = unified.run(None, dict(feed, past_keys=np.zeros(shape, np.float32),
                                                    past_values=np.zeros(shape, np.float32)))
                    length = feed["inputs_embeds"].shape[1]
                    expected_kv = (shape[0], 1, shape[2], length, shape[4])
                    if (values[0].shape != (1, 1, cfg.talker["vocab_size"])
                            or values[1].shape != (1, 1, cfg.talker["hidden_size"])
                            or values[2].shape != expected_kv or values[3].shape != expected_kv):
                        raise RuntimeError("API2 prefill output shape mismatch")
                    return values[:2] + [value[layer] for layer in range(shape[0])
                                         for value in values[2:4]]
            bundle.sessions["talker_prefill"] = PrefillView()
            bundle.sessions["talker_decode"] = unified
        start = time.perf_counter()
        pcm = tts.load_reference_audio(a.reference_wav)
        embedding = tts.speaker_embedding_from_audio(bundle, pcm)
        report["speakerSeconds"] = time.perf_counter() - start
        reference_codes, reference_ids = None, None
        if a.conditioning_mode == "icl":
            start = time.perf_counter()
            encoder = ort.InferenceSession(str(a.reference_encoder), sess_options=options,
                                           providers=["CPUExecutionProvider"])
            reference_codes = tts.encode_reference_codes(encoder, pcm, cfg)
            del encoder
            reference_ids = tts.build_reference_prompt_ids(tokenizer, a.reference_text)
            report["referenceEncodeSeconds"] = time.perf_counter() - start
            report["referenceTextSha256"] = hashlib.sha256(a.reference_text.encode()).hexdigest()
        report["referenceFrames"] = 0 if reference_codes is None else reference_codes.shape[1]
        stream = ort.InferenceSession(str(a.streaming_vocoder), sess_options=options,
                                      providers=["CPUExecutionProvider"])
        states = {
            "conv_state": np.zeros(135232, dtype=np.float32),
            "past_keys": np.zeros((8, 1, 16, 71, 64), dtype=np.float32),
            "past_values": np.zeros((8, 1, 16, 71, 64), dtype=np.float32),
            "position": np.zeros(1, dtype=np.int64),
        }
        output_names = ["waveform", "conv_state_out", "present_keys", "present_values", "position_out"]
        pending, audio = [], []
        produced_frames = 0
        def run_step(codes):
            nonlocal states
            count = codes.shape[1]
            outputs = stream.run(output_names, dict(states, codes=codes[None]))
            wave = outputs[0]
            if wave.shape != (1, 1, count * 1920) or wave.dtype != np.float32:
                raise RuntimeError("unexpected streaming PCM shape/dtype")
            next_states = dict(zip(states, outputs[1:]))
            for name, value in next_states.items():
                if value.shape != states[name].shape or value.dtype != states[name].dtype:
                    raise RuntimeError(f"unexpected state shape/dtype: {name}")
                if not np.isfinite(value).all():
                    raise RuntimeError(f"non-finite state: {name}")
            if int(next_states["position"][0]) != int(states["position"][0]) + count or not np.isfinite(wave).all():
                raise RuntimeError("invalid streaming position or PCM")
            states = next_states
            return wave.reshape(-1)

        # Reference audio supplies causal decoder context, but is never emitted.
        warm_start = time.perf_counter()
        if reference_codes is not None:
            for offset in range(0, reference_codes.shape[1], a.chunk_frames):
                run_step(reference_codes[:, offset:offset + a.chunk_frames])
        report["referenceVocoderSeconds"] = time.perf_counter() - warm_start
        report["preparationSeconds"] = time.perf_counter() - prepare_start
        generation_start = time.perf_counter()

        def flush():
            nonlocal produced_frames
            if not pending:
                return
            count = len(pending)
            step_start = time.perf_counter()
            flat = run_step(np.stack(pending, axis=1))
            elapsed = time.perf_counter() - generation_start
            audio.append(flat)
            report["chunks"].append({
                "startFrame": produced_frames, "frames": count,
                "availableSeconds": elapsed, "vocoderSeconds": time.perf_counter() - step_start,
                "rms": float(np.sqrt(np.mean(flat.astype(np.float64) ** 2))),
                "stateBytes": sum(v.nbytes for v in states.values()),
            })
            produced_frames += count
            pending.clear()

        def accept(frame):
            pending.append(frame)
            if len(pending) == a.chunk_frames:
                flush()

        gen = tts.generate_codes(
            bundle, tables, cfg, tts.build_prompt_ids(tokenizer, a.text), embedding,
            tts.normalize_language(a.language), max_frames=a.max_frames,
            temperature=0.9, top_k=50, rep_penalty=1.05, seed=20260906, on_frame=accept,
            reference_token_ids=reference_ids, reference_codes=reference_codes)
        flush()  # Only commit the tail after successful EOS.
        generation_seconds = time.perf_counter() - generation_start
        if produced_frames != gen.frames:
            raise RuntimeError("stream lost or duplicated codec frames")
        np.savez(a.output / "codes.npz", target=gen.codes,
                 reference=reference_codes if reference_codes is not None else np.zeros((16, 0), np.int64))
        bundle.sessions["talker_decode"].session = None
        bundle.sessions["code_predictor"].session = None
        wave = np.concatenate(audio)
        reference = tts.decode_waveform(Graph("vocoder", once=True), gen.codes, reference_codes)
        if wave.shape != reference.shape:
            raise RuntimeError("incremental PCM length differs from full vocoder target crop")
        delta = wave.astype(np.float64) - reference.astype(np.float64)
        max_diff = float(np.max(np.abs(delta)))
        if max_diff > 1e-5:
            raise RuntimeError(f"incremental PCM differs from full vocoder: maxdiff={max_diff}")
        tts.sf.write(a.output / "streamed.wav", wave, 24000, subtype="FLOAT")
        tts.sf.write(a.output / "full.wav", reference, 24000, subtype="FLOAT")
        # Offline lower bound on startup buffering for uninterrupted playout:
        # every chunk must arrive before its playback start. Not a live policy.
        earliest_start = max(c["availableSeconds"] - c["startFrame"] * 0.08 for c in report["chunks"])
        # An explicit signal threshold, not a subjective claim of audibility.
        first_signal = None
        for c, pcm in zip(report["chunks"], audio):
            for offset in range(0, len(pcm) - 479, 480):
                rms = float(np.sqrt(np.mean(pcm[offset:offset + 480].astype(np.float64) ** 2)))
                if rms >= 0.01:
                    first_signal = {"availableSeconds": c["availableSeconds"],
                                    "fromPreparationStartSeconds": report["preparationSeconds"] + c["availableSeconds"],
                                    "audioOffsetSeconds": c["startFrame"] * 0.08 + offset / 24000,
                                    "criterion": "20 ms RMS >= -40 dBFS"}
                    break
            if first_signal is not None:
                break
        rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        report.update(status="pass", frames=gen.frames, group0=gen.group0_tokens,
                      durationSeconds=len(wave) / 24000,
                      generationSeconds=generation_seconds,
                      generationRtf=generation_seconds / (len(wave) / 24000),
                      maxAbsDifference=max_diff,
                      rmsDifference=float(np.sqrt(np.mean(delta ** 2))),
                      firstSignal=first_signal,
                      minimumNoUnderrunStartupSeconds=max(0, earliest_start),
                      minimumNoUnderrunFromPreparationStartSeconds=report["preparationSeconds"] + max(0, earliest_start),
                      peakRssBytes=rss if platform.system() == "Darwin" else rss * 1024,
                      timingScope="serial graph execution; generation includes lazy talker/CP loading; preparationSeconds separately includes tables/tokenizer/speaker/encoder/stream model and reference context; hash/import overhead excluded")
        persist()
        print(json.dumps({k: v for k, v in report.items() if k not in ("chunks", "hashes", "modelHashes", "group0", "runtime")}, ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        report.update(status="fail", errorType=type(exc).__name__, error=str(exc))
        persist()
        print(json.dumps(report, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
