#!/usr/bin/env python3
"""Explicit real ORT1.24.2 ICL runner verification; not an automatic unit test."""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tts_runner as runner

CACHE = Path.home() / "Library/Caches/Auralis/tts"
ROOT = CACHE / "icl/runner-validation"


def run_case(identity, language, mode="icl"):
    runner._import_heavy()
    np = runner.np
    assert runner.ort.__version__ == "1.24.2", runner.ort.__version__
    manifest = json.loads((CACHE / "upstream-fidelity/real-pool/manifest.json").read_text())
    speaker = next(s for s in manifest["speakers"] if s["id"] == identity)
    text = json.loads((CACHE / "icl/plan.json").read_text())["texts"][language]
    out = ROOT / f"{identity}-{language}-{mode}"
    out.mkdir(parents=True, exist_ok=True)
    proof = {}
    generate = runner.generate_codes
    decode = runner.decode_waveform

    def capture_generate(bundle, embs, cfg, token_ids, embedding, lang, **kwargs):
        original_session = bundle.sessions["talker_prefill"]
        class CapturePrefill:
            def run(self, names, feed):
                np.savez(out / "prefill.npz", **feed)
                if mode == "icl":
                    expected = np.load(CACHE / f"icl/prefill-{identity}-{language}.npz")
                    delta = feed["inputs_embeds"].astype(np.float64) - expected["inputs_embeds"]
                    proof["prefill_max_abs"] = float(np.abs(delta).max())
                    proof["prefill_rms"] = float(np.sqrt(np.mean(delta ** 2)))
                    assert proof["prefill_max_abs"] < 1e-4
                    for key in ("attention_mask", "position_ids"):
                        assert np.array_equal(feed[key], expected[key]), key
                return original_session.run(names, feed)
        bundle.sessions["talker_prefill"] = CapturePrefill()
        frames = []
        def sink(frame):
            frames.append(frame.copy())
            frame[:] = 2047  # Must not mutate the generator's own frame.
        kwargs["on_frame"] = sink
        try:
            gen = generate(bundle, embs, cfg, token_ids, embedding, lang, **kwargs)
        finally:
            bundle.sessions["talker_prefill"] = original_session
        assert np.array_equal(gen.codes, np.stack(frames, axis=1))
        proof["target_callback_copies_independent"] = True
        proof["callback_frames"] = len(frames)
        reference = kwargs.get("reference_codes")
        if reference is not None:
            expected_codes = np.load(CACHE / f"icl/reference-{identity}-codes.npy").T
            proof["reference_codes_equal_official"] = bool(np.array_equal(reference, expected_codes))
            assert proof["reference_codes_equal_official"]
            _, trailing = runner.build_icl_prompt(embs, cfg, token_ids, kwargs["reference_token_ids"], reference)
            expected = np.load(CACHE / f"icl/prefill-{identity}-{language}.npz")
            proof["trailing_max_abs"] = float(np.max(np.abs(trailing - expected["trailing_text_hidden"][0])))
            assert proof["trailing_max_abs"] < 1e-4
        np.savez(out / "codes.npz", generated=gen.codes,
                 reference=reference if reference is not None else np.empty((16, 0), dtype=np.int64))
        return gen

    def capture_decode(session, codes, reference_codes=None):
        all_codes = codes if reference_codes is None else np.concatenate([reference_codes, codes], axis=1)
        full = session.run(None, {"codes": all_codes[None]})[0].reshape(-1)
        result = decode(session, codes, reference_codes)
        trim = 0 if reference_codes is None else reference_codes.shape[1] * 1920
        assert np.array_equal(result, full[trim:])
        proof["vocoder_crop_max_abs"] = float(np.max(np.abs(result - full[trim:])))
        proof["reference_samples_removed"] = trim
        proof["returned_target_samples"] = result.size
        np.save(out / "full-context-pcm.npy", full)
        return result

    runner.generate_codes = capture_generate
    runner.decode_waveform = capture_decode
    argv = ["--model-dir", str(CACHE / "hf"), "--text", text, "--language", language,
            "--reference-wav", speaker["recordings"]["reference"]["path"],
            "--out", str(out / "audio.wav"), "--json-report", str(out / "report.json"),
            "--seed", "20260906", "--threads", "4", "--max-frames", "384", "--conditioning-mode", mode]
    if mode == "icl":
        argv += ["--reference-text", speaker["recordings"]["reference"]["text"],
                 "--reference-encoder", str(CACHE / "icl/reference_encoder.onnx")]
    try:
        status = runner.main(argv)
    finally:
        runner.generate_codes, runner.decode_waveform = generate, decode
    assert status == 0, status
    report = json.loads((out / "report.json").read_text())
    assert report["conditioningMode"] == mode and report["termination"] == "codec_eos"
    assert report["frames"] == proof["callback_frames"]
    assert report["reference_pcm_trimmed_samples"] == proof["reference_samples_removed"]
    assert proof["returned_target_samples"] == report["frames"] * 1920
    if mode == "xvector":
        baseline = CACHE / f"upstream-fidelity/real-pool/protocol-fixed-sampled/fp32/{identity}-{language}.wav"
        actual, sr = runner.sf.read(out / "audio.wav", dtype="float32")
        expected, expected_sr = runner.sf.read(baseline, dtype="float32")
        proof["xvector_equals_frozen_baseline"] = sr == expected_sr and np.array_equal(actual, expected)
        assert proof["xvector_equals_frozen_baseline"]
    (out / "verification.json").write_text(json.dumps(proof, indent=2))
    print(json.dumps(proof), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("identity", choices=("121", "260"))
    parser.add_argument("language", choices=("english", "chinese"))
    parser.add_argument("--mode", choices=("icl", "xvector"), default="icl")
    args = parser.parse_args()
    run_case(args.identity, args.language, args.mode)
