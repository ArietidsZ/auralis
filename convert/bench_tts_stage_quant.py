#!/usr/bin/env python3
"""Fixed stage-wise quantization ablation against the frozen real-speaker pool.

Uses existing converted graphs; never quantizes, edits source artifacts, or
changes shipping manifests. Timings are diagnostic under concurrent host load.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import resource
import shutil
import sys
import time

CACHE = Path.home() / "Library/Caches/Auralis/tts"
POOL = CACHE / "upstream-fidelity/real-pool"
BASELINE = POOL / "protocol-fixed-sampled"
OUT = CACHE / "stage-quant"
ROLES = ("speaker_encoder", "talker_prefill", "talker_decode", "code_predictor", "vocoder")
PLANS = {
    "A": {"code_predictor": "int8"},
    "B": {"code_predictor": "int4"},
    "C": {"talker_prefill": "int8", "talker_decode": "int8"},
}
SAMPLING = dict(top_k=50, seed=20260906, temperature=0.9, repetition_penalty=1.05, max_frames=384)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    path = Path(path)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temp.replace(path)


def read(path):
    return json.loads(Path(path).read_text())


def frozen_runner():
    source = BASELINE / "tts_runner.py"
    baseline = read(BASELINE / "fp32/results.json")
    if sha(source) != baseline["runner_sha256"] or baseline["sampling"] != SAMPLING:
        raise RuntimeError("frozen baseline runner or sampling does not match")
    if sha(POOL / "predeclared-criteria.json") != baseline["criteria_sha256"]:
        raise RuntimeError("predeclared criteria changed")
    spec = importlib.util.spec_from_file_location("stage_quant_frozen_runner", source)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def prepare():
    frozen_runner()
    OUT.mkdir(parents=True, exist_ok=True)
    plan = dict(plans=PLANS, sampling=SAMPLING, baseline=str(BASELINE),
                criteria_sha256=sha(POOL / "predeclared-criteria.json"),
                baseline_results_sha256=sha(BASELINE / "fp32/results.json"),
                baseline_identity_sha256=sha(BASELINE / "identity.json"),
                baseline_asr_sha256=sha(BASELINE / "fp32/asr.json"),
                asr_gate="Every paired numeric_normalized_cer <= frozen FP32; no missing cases or failures.",
                promotion="Screening candidate only. No manifest promotion or device/SLO claim.")
    if (OUT / "plan.json").exists() and read(OUT / "plan.json") != plan:
        raise RuntimeError("existing stage plan differs; do not overwrite an experiment")
    write_json(OUT / "plan.json", plan)
    hash_cache = {}
    for variant, changes in PLANS.items():
        destination = OUT / variant / "model"
        destination.mkdir(parents=True, exist_ok=True)
        sources = {}
        for path in sorted((CACHE / "hf").rglob("*")):
            relative = path.relative_to(CACHE / "hf")
            if not path.is_file() or any(part.startswith(".") for part in relative.parts):
                continue
            if any(relative.as_posix().startswith(role + ".onnx") for role in changes):
                continue
            sources[relative.as_posix()] = (path.resolve(), None)
        conversions = {}
        for role, quantization in changes.items():
            directory = CACHE / "optimization" / f"{quantization}-b32-a4"
            conversion = read(directory / "conversion.json")
            if (conversion["bits"], conversion["block"], conversion["accuracy"], conversion["ort"]) != (int(quantization[3:]), 32, 4, "1.24.2"):
                raise RuntimeError("unexpected quantization recipe")
            record = next(item for item in conversion["models"] if item["role"] == role)
            if sha(CACHE / "hf" / f"{role}.onnx") != record["source_sha256"]:
                raise RuntimeError("quantization source differs from the FP32 bundle")
            conversions[role] = dict(path=str(directory / "conversion.json"), sha256=sha(directory / "conversion.json"))
            for item in record["files"]:
                name = Path(item["path"]).name
                sources[name] = ((directory / name).resolve(), item["sha256"])
        records = []
        for relative, (source, expected) in sorted(sources.items()):
            key = str(source)
            if key not in hash_cache:
                hash_cache[key] = sha(source)
            digest = hash_cache[key]
            if expected and expected != digest:
                raise RuntimeError("converted artifact hash mismatch: " + str(source))
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                if sha(target) != digest:
                    raise RuntimeError("existing candidate differs: " + str(target))
            else:
                os.link(source, target)
            records.append(dict(path=relative, source=str(source), bytes=source.stat().st_size, sha256=digest))
        composition = dict(variant=variant, role_precision={role: changes.get(role, "fp32") for role in ROLES},
                           files=records, conversions=conversions, plan_sha256=sha(OUT / "plan.json"))
        write_json(OUT / variant / "composition.json", composition)
        print(variant, composition["role_precision"], flush=True)


def synth(variant, smoke):
    import numpy as np
    import soundfile as sf
    tts = frozen_runner()
    tts._import_heavy()
    if tts.ort.__version__ != "1.24.2":
        raise RuntimeError("use the frozen ORT 1.24.2 overlay")
    root = OUT / variant
    composition = read(root / "composition.json")
    model = root / "model"
    for item in composition["files"]:
        path = model / item["path"]
        if not path.is_file() or path.stat().st_size != item["bytes"] or sha(path) != item["sha256"]:
            raise RuntimeError("candidate artifact changed: " + str(path))
    result = dict(variant=variant, runtime=tts.ort.__version__, sampling=SAMPLING,
                  runner_sha256=sha(BASELINE / "tts_runner.py"), script_sha256=sha(__file__),
                  composition_sha256=sha(root / "composition.json"),
                  criteria_sha256=sha(POOL / "predeclared-criteria.json"), cases=[])
    if (root / "results.json").exists():
        previous = read(root / "results.json")
        for key in ("sampling", "runner_sha256", "composition_sha256", "criteria_sha256"):
            if previous[key] != result[key]:
                raise RuntimeError("resume provenance mismatch: " + key)
        result["cases"] = previous["cases"]
        for case in result["cases"]:
            if case.get("status") == "running":
                case.update(status="synthesis_failed", error="prior process interrupted; retained without retry")
    if not smoke:
        warmup = [row for row in result["cases"] if row["voice"] == "121"]
        if len(warmup) != 2 or not all(row.get("eos") and row.get("finite") and row.get("status") == "success" for row in warmup):
            raise RuntimeError("run and pass speaker 121 in both languages before the full pool")
    write_json(root / "results.json", result)
    options = tts.ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    started = time.perf_counter()
    sessions = {role: tts.ort.InferenceSession(str(model / f"{role}.onnx"), sess_options=options,
                                              providers=["CPUExecutionProvider"]) for role in ROLES}
    result["load_seconds"] = time.perf_counter() - started
    bundle = type("Bundle", (), {"sessions": sessions})()
    config, embeddings, tokenizer = tts.load_config(model), tts.EmbeddingTables(model), tts.load_tokenizer(model)
    manifest, criteria = read(POOL / "manifest.json"), read(POOL / "predeclared-criteria.json")
    for speaker in manifest["speakers"]:
        if smoke and speaker["id"] != "121":
            continue
        reference = POOL / f"{speaker['id']}-reference24.wav"
        pcm, rate = sf.read(reference, dtype="float32")
        if rate != 24000 or pcm.ndim != 1 or not np.isfinite(pcm).all():
            raise RuntimeError("frozen reference is not valid mono 24k PCM")
        embedding = tts.extract_speaker_embedding(bundle, reference)
        for language, text in criteria["synthesis_texts"].items():
            cid = f"{speaker['id']}-{language}"
            if any(row["id"] == cid for row in result["cases"]):
                continue
            started = time.perf_counter()
            row = dict(id=cid, voice=speaker["id"], sex=speaker["sex"], language=language,
                       text=text, repeat=0, status="running", reference_sha256=sha(reference),
                       load_average=os.getloadavg())
            result["cases"].append(row)
            write_json(root / "results.json", result)
            try:
                generated = tts.generate_codes(bundle, embeddings, config, tts.build_prompt_ids(tokenizer, text),
                                               embedding, language, max_frames=384, temperature=0.9,
                                               top_k=50, rep_penalty=1.05, seed=20260906)
                waveform = sessions["vocoder"].run(None, {"codes": generated.codes[None]})[0].reshape(-1)
                if not len(waveform) or not np.isfinite(waveform).all() or not np.any(waveform):
                    raise RuntimeError("invalid or silent vocoder output")
                path = root / f"{cid}.wav"
                sf.write(path, np.clip(waveform, -1, 1), 24000)
                row.update(status="success", audio_path=str(path), frames=generated.frames,
                           eos=generated.frames < 384, finite=True,
                           clipping_ratio=float(np.mean(np.abs(waveform) >= .999)),
                           group0_tokens=generated.group0_tokens, duration_s=len(waveform) / 24000)
            except Exception as exc:
                row.update(status="synthesis_failed", error=f"{type(exc).__name__}: {exc}")
            row.update(seconds=time.perf_counter() - started,
                       maxrss_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
            write_json(root / "results.json", result)
            print(json.dumps({key: value for key, value in row.items() if key != "group0_tokens"}, ensure_ascii=False), flush=True)


def evaluate(variant):
    import librosa
    import numpy as np
    import soundfile as sf
    import torch
    from speechbrain.inference.speaker import EncoderClassifier
    torch.set_num_threads(2)
    model = EncoderClassifier.from_hparams(source=str(CACHE / "ecapa"), savedir=str(CACHE / "ecapa"), run_opts={"device": "cpu"})

    def embed(path):
        pcm, rate = sf.read(path, dtype="float32")
        if rate != 16000:
            pcm = librosa.resample(pcm, orig_sr=rate, target_sr=16000)
        with torch.inference_mode():
            return model.encode_batch(torch.from_numpy(pcm)[None]).squeeze().numpy()

    speakers = read(POOL / "manifest.json")["speakers"]
    gallery = {speaker["id"]: embed(speaker["recordings"]["heldout"]["path"]) for speaker in speakers}
    sexes = {speaker["id"]: speaker["sex"] for speaker in speakers}

    def score(path, identity):
        vector = embed(path)
        scores = {key: float(np.dot(vector, value) / (np.linalg.norm(vector) * np.linalg.norm(value))) for key, value in gallery.items()}
        own = scores[identity]
        return dict(identity=identity, scores=scores, own=own, top1=max(scores, key=scores.get),
                    correct=max(scores, key=scores.get) == identity,
                    margin=own - max(value for key, value in scores.items() if key != identity),
                    same_sex_margin=own - max(value for key, value in scores.items() if key != identity and sexes[key] == sexes[identity]))

    root = OUT / variant
    identity = dict(native=[score(speaker["recordings"]["reference"]["path"], speaker["id"]) for speaker in speakers], cases=[])
    for case in read(root / "results.json")["cases"]:
        if case.get("status") != "success":
            row = dict(id=case["id"], identity=case["voice"], correct=False, status=case["status"], error=case.get("error"))
        else:
            row = dict(id=case["id"], language=case["language"], **score(case["audio_path"], case["voice"]))
        identity["cases"].append(row)
        write_json(root / "identity.json", identity)
        print(variant, row["id"], row.get("own"), row.get("correct"), flush=True)


def decision(variant):
    import statistics
    root = OUT / variant
    baseline_sv = {row["id"]: row for row in read(BASELINE / "identity.json")["variants"]["fp32"]}
    baseline_asr = {row["id"]: row for row in read(BASELINE / "fp32/asr.json")["cases"]}
    synthesis = read(root / "results.json")["cases"]
    identity = read(root / "identity.json")
    asr = {row["id"]: row for row in read(root / "asr.json")["cases"]}
    gate = read(POOL / "predeclared-criteria.json")["screening_gate"]
    candidate = {row["id"]: row for row in identity["cases"]}
    complete = set(candidate) == set(baseline_sv) == set(asr) and len(synthesis) == 12
    deltas = {key: row["own"] - baseline_sv[key]["own"] for key, row in candidate.items() if "own" in row and key in baseline_sv}
    new_failures = [key for key, row in candidate.items() if not row["correct"] and baseline_sv[key]["correct"]]
    asr_regressions = [key for key, row in asr.items() if "numeric_normalized_cer" not in row or row["numeric_normalized_cer"] > baseline_asr[key]["numeric_normalized_cer"]]
    checks = dict(complete=complete, every_synthesis_eos=all(row.get("status") == "success" and row.get("eos") and row.get("finite") for row in synthesis),
                  native_top1=sum(row["correct"] for row in identity["native"]) >= gate["native_reference_top1_required"],
                  generated_top1=sum(row["correct"] for row in candidate.values()) >= gate["generated_top1_required"],
                  no_new_identity_failures=len(new_failures) <= gate["candidate_new_identity_failures_allowed"],
                  paired_median=len(deltas) == 12 and statistics.median(deltas.values()) >= gate["paired_median_cosine_delta_min"],
                  paired_worst=len(deltas) == 12 and min(deltas.values()) >= gate["paired_worst_cosine_delta_min"],
                  asr_nonregression=not asr_regressions)
    report = dict(variant=variant, passed=all(checks.values()), checks=checks, paired_deltas=deltas,
                  paired_median=statistics.median(deltas.values()) if deltas else None,
                  paired_worst=min(deltas.values()) if deltas else None,
                  new_identity_failures=new_failures, asr_regressions=asr_regressions,
                  scope="predeclared screening candidate only; no manifest or device promotion")
    write_json(root / "decision.json", report)
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "synth", "evaluate", "decision"])
    parser.add_argument("--variant", choices=PLANS)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    if args.mode != "prepare" and not args.variant:
        parser.error("--variant is required")
    if args.mode == "prepare":
        prepare()
    elif args.mode == "synth":
        synth(args.variant, args.smoke)
    elif args.mode == "evaluate":
        evaluate(args.variant)
    else:
        decision(args.variant)


if __name__ == "__main__":
    main()
