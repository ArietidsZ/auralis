"""Fresh ASR/MT/TTS execution for validate_models; no report-import shortcut.

Native runtimes run in separate processes. The suite contains data and limits,
never executable commands. Quality passes apply only to this named suite on
this host; they do not promote a manifest or certify a physical device.
"""
from __future__ import annotations

import json
import math
import os
import signal
import subprocess
import tempfile
import time
from pathlib import Path

from asr_runner import RunnerError, cer, edit_distance, load_wav
from manifest_contract import ContractError, unique_json_keys
from tts_runner import normalize_language, API2_GRAPHS, API2_HASHED_FILES
# One shared single-pass SHA-256 reader; the file_hash spelling stays for
# scripts/check_asr_swift, which imports it from here.
from fetch_model import sha256_of as file_hash  # noqa: E402

CONVERT = Path(__file__).resolve().parent

# These are the concrete layouts consumed by the current runners, not a
# generic ONNX role contract. Keep this gate independent of optional runtimes.
ASR_FILES = {"conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
             "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json"}
TTS_GRAPHS = {"speaker_encoder", "talker_prefill", "talker_decode", "code_predictor", "vocoder"}
TTS_EXTERNAL = {name + ".onnx.data" for name in TTS_GRAPHS - {"code_predictor"}}
TTS_FILES = ({name + ".onnx" for name in TTS_GRAPHS} | TTS_EXTERNAL |
             {"tokenizer/vocab.json", "tokenizer/merges.txt", "embeddings/config.json",
              "embeddings/speaker_ids.json", "embeddings/codec_head_weight.npy",
              "embeddings/text_embedding.npy", "embeddings/talker_codec_embedding.npy"} |
             {f"embeddings/cp_codec_embedding_{i}.npy" for i in range(15)} |
             {f"embeddings/text_projection_fc{layer}_{kind}.npy"
              for layer in (1, 2) for kind in ("weight", "bias")})


def runner_layout_error(pkg: str, manifest: dict) -> str | None:
    """Reject incomplete or redirected manifests before native loading."""
    if manifest["runtime"]["backend"] != "onnx" or pkg not in ("asr", "tts"):
        return None
    if pkg == "tts" and manifest["runtime"]["apiContractVersion"] not in ("1", "2"):
        return "unsupported TTS runner API contract"
    api2 = pkg == "tts" and manifest["runtime"]["apiContractVersion"] == "2"
    required = ASR_FILES if pkg == "asr" else (set(API2_HASHED_FILES) |
        {name for name in TTS_FILES if not name.endswith((".onnx", ".onnx.data"))
         and name not in ("embeddings/codec_head_weight.npy", "embeddings/speaker_ids.json")} if api2 else TTS_FILES)
    entries = {entry["path"]: entry for entry in manifest["files"]}
    missing = {f"{pkg}/{name}" for name in required} - entries.keys()
    if missing:
        return f"runner inputs absent from manifest: {sorted(missing)}"
    roles = ({"conv_frontend": "conv_frontend.onnx", "encoder": "encoder.int8.onnx",
              "decoder": "decoder.int8.onnx", "tokenizer_vocab": "tokenizer/vocab.json",
              "tokenizer_merges": "tokenizer/merges.txt", "tokenizer_config": "tokenizer/tokenizer_config.json"}
             if pkg == "asr" else API2_GRAPHS if api2 else {name: name + ".onnx" for name in TTS_GRAPHS})
    if any(manifest["roles"].get(role) != f"{pkg}/{name}" for role, name in roles.items()):
        return "manifest roles differ from the fixed runner layout"
    for name in required:
        if name.endswith(".onnx"):
            expected = [f"{pkg}/{name}.data"] if pkg == "tts" and (api2 or name + ".data" in TTS_EXTERNAL) else []
            if sorted(entries[f"{pkg}/{name}"].get("externalData") or []) != expected:
                return f"external data differs from runner layout: {pkg}/{name}"
    return None


class TaskError(Exception):
    def __init__(self, status: str, message: str):
        super().__init__(message)
        self.status = status


def finite_number(value, name: str, minimum: float = 0) -> float:
    if type(value) not in (int, float) or not math.isfinite(value) or value < minimum:
        raise ValueError(f"{name} must be a finite number >= {minimum}")
    return float(value)


def load_suite(path: Path) -> dict:
    suite = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_json_keys)
    if not isinstance(suite, dict) or type(suite.get("schemaVersion")) is not int or suite["schemaVersion"] != 1:
        raise ValueError("suite.schemaVersion must be 1")
    if set(suite) - {"schemaVersion", "name", "purpose", "asr", "mt", "tts"}:
        raise ValueError("unknown suite keys")
    if not isinstance(suite.get("name"), str) or not suite["name"].strip():
        raise ValueError("suite.name is required")
    if suite.get("purpose") not in ("smoke", "benchmark"):
        raise ValueError("suite.purpose must be smoke or benchmark")
    for pkg in ("asr", "mt", "tts"):
        if pkg not in suite:
            continue
        config = suite[pkg]
        if not isinstance(config, dict) or not isinstance(config.get("cases"), list) or not config["cases"]:
            raise ValueError(f"{pkg}.cases must be a non-empty list")
        options = {"asr": {"segment", "numThreads", "maxTotalLen", "vadModel"},
                   "mt": set(), "tts": {"numThreads", "maxFrames"}}[pkg]
        if set(config) - {"cases", "limits"} - options:
            raise ValueError(f"unknown {pkg} options")
        if pkg == "asr" and config.get("segment", "both") not in ("on", "off", "both"):
            raise ValueError("asr.segment must be on/off/both")
        for option in ("numThreads", "maxTotalLen", "maxFrames"):
            if option in config and (type(config[option]) is not int or config[option] < 1):
                raise ValueError(f"{pkg}.{option} must be a positive integer")
        if "vadModel" in config:
            config["vadModel"] = str((path.parent / config["vadModel"]).resolve())
        limits = config.get("limits", {})
        allowed_limits = {"asr": {"maxCer", "maxCaseCer", "maxRtf"},
                          "mt": {"minChrf", "maxLatencyMs"},
                          "tts": {"maxRoundTripCer", "maxRtf"}}[pkg]
        if not isinstance(limits, dict) or set(limits) - allowed_limits:
            raise ValueError(f"unknown {pkg}.limits")
        for key, value in limits.items():
            finite_number(value, f"{pkg}.limits.{key}")
            if key == "minChrf" and value > 100:
                raise ValueError("minChrf must be <= 100")
        ids = set()
        for case in config["cases"]:
            if not isinstance(case, dict) or not isinstance(case.get("id"), str) or not case["id"].strip():
                raise ValueError(f"every {pkg} case needs an id")
            if case["id"] in ids:
                raise ValueError(f"duplicate {pkg} case id: {case['id']}")
            ids.add(case["id"])
            required = {"asr": {"audio"}, "mt": {"text", "sourceLanguage", "targetLanguage"},
                        "tts": {"text", "language", "referenceWav"}}[pkg]
            extra = {"context"} if pkg == "mt" else {"conditioningMode", "referenceText"} if pkg == "tts" else set()
            if set(case) - required - {"id", "reference"} - extra:
                raise ValueError(f"unknown fields in {pkg} case {case['id']}")
            for key in required:
                if not isinstance(case.get(key), str) or not case[key].strip():
                    raise ValueError(f"{pkg}/{case['id']}: {key} must be a non-empty string")
            if "reference" in case and not isinstance(case["reference"], str):
                raise ValueError("reference must be a string")
            if pkg == "tts":
                mode = case.get("conditioningMode", "xvector")
                if mode not in ("xvector", "icl"):
                    raise ValueError("TTS conditioningMode must be xvector/icl")
                if mode == "icl" and (not isinstance(case.get("referenceText"), str) or not case["referenceText"].strip()):
                    raise ValueError("ICL requires actual referenceText")
                if mode == "xvector" and "referenceText" in case:
                    raise ValueError("xvector must not carry referenceText")
            if "context" in case and (not isinstance(case["context"], list)
                                       or any(not isinstance(s, str) for s in case["context"])):
                raise ValueError("MT context must be a list of strings")
            for key in ("audio", "referenceWav"):
                if key in case:
                    case[key] = str((path.parent / case[key]).resolve())
    return suite


def fresh_run(pkg: str, python: str, arguments: list[str], artifacts: Path,
              timeout: float, report) -> dict:
    directory = Path(tempfile.mkdtemp(prefix=pkg + "-", dir=artifacts))
    output, log = directory / "result.json", directory / "runner.log"
    runner = CONVERT / f"{pkg}_runner.py"
    command = [python, str(runner), *arguments, "--json-report", str(output)]
    runner_hash = file_hash(runner)
    started = time.monotonic()
    try:
        with log.open("wb") as stream:
            process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                report.add(f"{pkg}/process", "fail", f"runner exceeded {timeout}s; process group terminated",
                           command=command, elapsedSeconds=time.monotonic() - started,
                           log=str(log), logSha256=file_hash(log), runnerSha256=runner_hash)
                raise TaskError("fail", f"{pkg} runner exceeded {timeout}s; process group terminated")
            except BaseException:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                raise
    except OSError as exc:
        raise TaskError("blocked", f"{pkg} runner cannot start: {exc}") from exc
    elapsed = time.monotonic() - started
    report.add(f"{pkg}/process", "pass" if code == 0 else
               ("blocked" if code == 2 else "argument-error" if code == 4 else "fail"),
               f"fresh runner exit={code}, wall={elapsed:.3f}s", command=command,
               elapsedSeconds=elapsed, log=str(log), logSha256=file_hash(log), runnerSha256=runner_hash)
    if code != 0:
        raise TaskError("blocked" if code == 2 else "argument-error" if code == 4 else "fail",
                        f"{pkg} runner exit={code}; see {log}")
    try:
        if output.stat().st_size > 10 * 1024 * 1024:
            raise ValueError("report exceeds 10 MiB")
        data = json.loads(output.read_text(encoding="utf-8"), object_pairs_hook=unique_json_keys,
                          parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"non-finite {value}")))
        if not isinstance(data, dict):
            raise ValueError("report is not an object")
    except (OSError, ValueError, ContractError) as exc:
        raise TaskError("fail", f"{pkg} exited 0 without a valid fresh report: {exc}") from exc
    report.add(f"{pkg}/evidence", "pass", "fresh task report retained",
               path=str(output), sha256=file_hash(output))
    return data


def text_metrics(cases: list[dict], outputs: list[str]) -> dict:
    char_errors = char_count = word_errors = word_count = 0
    per_case = []
    for case, output in zip(cases, outputs, strict=True):
        ref = case["reference"]
        chars = [c for c in ref if not c.isspace()]
        words = ref.split()
        char_errors += edit_distance(chars, [c for c in output if not c.isspace()])
        char_count += len(chars)
        word_errors += edit_distance(words, output.split())
        word_count += len(words)
        per_case.append({"id": case["id"], "cer": cer(ref, output), "text": output})
    return {"corpusCer": char_errors / max(1, char_count),
            "corpusWer": word_errors / max(1, word_count),
            "macroCer": sum(c["cer"] for c in per_case) / len(per_case),
            "maxCaseCer": max(c["cer"] for c in per_case), "cases": per_case,
            "characterErrors": char_errors, "referenceCharacters": char_count,
            "normalization": "case/punctuation preserved; CER ignores whitespace, WER splits whitespace"}


def quality_gate(report, pkg: str, limits: dict, metrics: dict, comparisons: dict) -> None:
    missing = set(comparisons) - set(limits)
    if missing:
        report.add(f"{pkg}/quality", "blocked", f"predeclared limits missing: {sorted(missing)}", metrics=metrics)
        return
    failures = []
    for limit, (metric, direction) in comparisons.items():
        value = metrics[metric]
        if (direction == "max" and value > limits[limit]) or (direction == "min" and value < limits[limit]):
            failures.append(f"{metric}={value:.4f} outside {limit}={limits[limit]}")
    report.add(f"{pkg}/quality", "fail" if failures else "pass",
               "; ".join(failures) if failures else "all declared suite limits met on this host",
               metrics=metrics, limits=limits)


def run_asr(report, models: Path, config: dict, python: str, artifacts: Path, timeout: float) -> tuple[list[str], dict]:
    cases = config["cases"]
    args = ["--models-dir", str(models / "asr"), "--segment", config.get("segment", "both"),
            "--num-threads", str(config.get("numThreads", 2)),
            "--max-total-len", str(config.get("maxTotalLen", 512))]
    if config.get("vadModel"):
        args += ["--vad-model", config["vadModel"]]
    for case in cases:
        args += ["--audio", case["audio"]]
    if all("reference" in c for c in cases):
        for case in cases:
            args += ["--reference", case["reference"]]
    raw = fresh_run("asr", python, args, artifacts, timeout, report)
    if raw.get("model_dir") != str(models / "asr"):
        raise TaskError("fail", "ASR report model directory differs from requested package")
    rows = raw.get("results")
    if not isinstance(rows, list) or len(rows) != len(cases):
        raise TaskError("fail", "ASR did not return every requested case")
    outputs, duration, inference = [], 0.0, 0.0
    inputs = []
    for case, row in zip(cases, rows, strict=True):
        if not isinstance(row, dict):
            raise TaskError("fail", "ASR result must be an object")
        if Path(row["audio"]).resolve() != Path(case["audio"]).resolve():
            raise TaskError("fail", "ASR report case order/path differs from requested inputs")
        mode = "unsegmented" if config.get("segment", "both") == "off" else "segmented"
        result = row[mode]
        if not isinstance(result, dict):
            raise TaskError("fail", "ASR decode result must be an object")
        if not isinstance(result.get("text"), str):
            raise TaskError("fail", "ASR text must be a string")
        outputs.append(result["text"])
        duration += finite_number(row["duration_s"], "ASR duration", 0.001)
        inference += finite_number(result["total_s"] if "total_s" in result else result["decode_s"], "ASR time")
        inputs.append({"id": case["id"], "path": case["audio"], "sha256": file_hash(Path(case["audio"]))})
    report.add("asr/task", "pass", f"decoded all {len(cases)} requested samples", inputs=inputs, runtime=raw.get("runtime"))
    return outputs, {"rtf": inference / duration, "audioSeconds": duration, "inferenceSeconds": inference}


def run_mt(report, models: Path, manifest: dict, config: dict, python: str,
           library: Path | None, artifacts: Path, timeout: float) -> None:
    if library is None:
        raise TaskError("blocked", "MT execution requires --mt-library PATH")
    cases = config["cases"]
    input_file = artifacts / "mt-cases.json"
    input_file.write_text(json.dumps(cases, ensure_ascii=False), encoding="utf-8")
    raw = fresh_run("mt", python, ["--model", str(models / manifest["roles"]["translator"]),
                    "--lib", str(library), "--cases", str(input_file)], artifacts, timeout, report)
    if raw.get("modelSha256") != next(f["sha256"] for f in manifest["files"] if f["path"] == manifest["roles"]["translator"]):
        raise TaskError("fail", "MT runner model hash differs from the verified input")
    if raw.get("runtimeRevision") != manifest["runtime"].get("runtimeRevision"):
        raise TaskError("fail", "MT runner runtime revision differs from manifest")
    rows = raw.get("cases")
    if not isinstance(rows, list) or len(rows) != len(cases):
        raise TaskError("fail", "MT did not return every requested case")
    outputs, times = [], []
    for case, row in zip(cases, rows, strict=True):
        if not isinstance(row, dict):
            raise TaskError("fail", "MT case result must be an object")
        if row.get("id") != case["id"] or row.get("status") != "ok" or not isinstance(row.get("output"), str) or not row["output"].strip():
            raise TaskError("fail", f"MT case failed or mismatched: {case['id']}")
        outputs.append(row["output"])
        times.append(finite_number(row["latencyMs"], "MT latency"))
    report.add("mt/task", "pass", f"translated all {len(cases)} requested cases",
               runtimeRevision=raw["runtimeRevision"], librarySha256=file_hash(library))
    if not all(c.get("reference", "").strip() for c in cases):
        raise TaskError("blocked", "MT reference translation required for every quality case")
    try:
        from sacrebleu.metrics import CHRF
    except ImportError as exc:
        raise TaskError("blocked", "install sacrebleu==2.5.1 in validator Python for MT quality scoring") from exc
    metric = CHRF(word_order=2)
    score = metric.corpus_score(outputs, [[c["reference"] for c in cases]])
    metrics = {"chrf": score.score, "signature": str(metric.get_signature()),
               "maxLatencyMs": max(times), "cases": [{"id": c["id"], "output": out, "latencyMs": t}
                    for c, out, t in zip(cases, outputs, times, strict=True)]}
    quality_gate(report, "mt", config.get("limits", {}), metrics,
                 {"minChrf": ("chrf", "min"), "maxLatencyMs": ("maxLatencyMs", "max")})


def run_tts(report, models: Path, manifest: dict, config: dict, pythons: dict, artifacts: Path,
            timeout: float, asr_integrity: bool) -> None:
    generated, total_audio, total_time = [], 0.0, 0.0
    for index, case in enumerate(config["cases"]):
        wav = artifacts / f"tts-{index}.wav"
        api = manifest["runtime"]["apiContractVersion"]
        mode = case.get("conditioningMode", "xvector")
        if mode == "icl" and api != "2":
            raise TaskError("blocked", "central ICL verification requires a complete API2 package")
        arguments = ["--model-dir", str(models / "tts"), "--api-contract-version", api,
            "--conditioning-mode", mode,
            "--text", case["text"], "--language", case["language"], "--reference-wav", case["referenceWav"],
            "--out", str(wav), "--threads", str(config.get("numThreads", 2)),
            "--max-frames", str(config.get("maxFrames", 150))]
        if mode == "icl": arguments += ["--reference-text", case["referenceText"]]
        raw = fresh_run("tts", pythons["tts"], arguments, artifacts, timeout, report)
        if raw.get("status") != "success" or raw.get("finite") is not True:
            raise TaskError("fail", f"TTS synthesis failed for {case['id']}")
        for key, expected in (("model_dir", str(models / "tts")), ("audio_path", str(wav)),
                              ("reference_wav", case["referenceWav"]), ("text", case["text"]),
                              ("language_requested", case["language"]),
                              ("language", normalize_language(case["language"])),
                              ("api_contract_version", api), ("conditioningMode", mode),
                              ("reference_text", case.get("referenceText"))):
            if raw.get(key) != expected:
                raise TaskError("fail", f"TTS report {key} differs from requested input")
        hashes = raw.get("model_sha256")
        expected_hashes = {f["path"].removeprefix("tts/"): f["sha256"] for f in manifest["files"]
                           if f["path"].endswith((".onnx", ".onnx.data"))}
        if not isinstance(hashes, dict) or set(hashes) != set(expected_hashes) or any(
                hashes[name] not in (digest, digest + " (hardlink-verified)")
                for name, digest in expected_hashes.items()):
            raise TaskError("fail", "TTS report graph hashes differ from verified inputs")
        audio = load_wav(wav)
        if not audio.samples or not any(abs(x) >= 1e-4 for x in audio.samples):
            raise TaskError("fail", f"TTS returned silent audio for {case['id']}")
        total_audio += audio.duration_s
        # Include cold load and speaker conditioning: decoding-only RTF hides
        # an important cost of this one-process-per-case runner.
        process_entry = next(e for e in reversed(report.entries) if e["id"] == "tts/process")
        total_time += process_entry["elapsedSeconds"]
        generated.append({"id": case["id"], "audio": str(wav), "reference": case["text"]})
        report.add(f"tts/audio/{case['id']}", "pass", "non-silent PCM produced",
                   path=str(wav), sha256=file_hash(wav), referenceSha256=file_hash(Path(case["referenceWav"])),
                   durationSeconds=audio.duration_s)
    report.add("tts/task", "pass", f"synthesized all {len(generated)} requested cases")
    if not asr_integrity:
        raise TaskError("blocked", "round-trip quality requires the ASR package integrity check; select --package all")
    outputs, _ = run_asr(report, models, {"cases": generated, "segment": "on"}, pythons["asr"], artifacts, timeout)
    metrics = text_metrics(generated, outputs)
    metrics.update(rtf=total_time / total_audio, audioSeconds=total_audio,
                   processSeconds=total_time, rtfScope="cold process, including model load and speaker conditioning")
    quality_gate(report, "tts", config.get("limits", {}), metrics,
                 {"maxRoundTripCer": ("corpusCer", "max"), "maxRtf": ("rtf", "max")})
    report.add("tts/clone-identity", "blocked", "ASR round-trip checks intelligibility; independent speaker identity evaluation is still required")


def run_task(report, pkg: str, manifest: dict, models: Path, suite: dict | None,
             pythons: dict, library: Path | None, artifacts: Path, timeout: float) -> bool:
    start = len(report.entries)
    backend = manifest["runtime"]["backend"]
    if backend != ("gguf-llama-cpp" if pkg == "mt" else "onnx"):
        report.add(f"{pkg}/task", "unsupported", f"no {pkg} runner for backend {backend}")
        return False
    if suite is None or pkg not in suite:
        report.add(f"{pkg}/task", "blocked", "provide --suite with explicit task cases; existing reports cannot substitute")
        return False
    try:
        config = suite[pkg]
        if pkg == "asr":
            outputs, metrics = run_asr(report, models, config, pythons[pkg], artifacts, timeout)
            if not all(c.get("reference", "").strip() for c in config["cases"]):
                raise TaskError("blocked", "ASR quality requires a reference for every requested sample")
            metrics.update(text_metrics(config["cases"], outputs))
            comparisons = {"maxCer": ("corpusCer", "max"), "maxRtf": ("rtf", "max")}
            if "maxCaseCer" in config.get("limits", {}):
                comparisons["maxCaseCer"] = ("maxCaseCer", "max")
            quality_gate(report, pkg, config.get("limits", {}), metrics, comparisons)
        elif pkg == "mt":
            run_mt(report, models, manifest, config, pythons[pkg], library, artifacts, timeout)
        else:
            asr_integrity = any(e["id"] == "asr/integrity" and e["status"] == "pass" for e in report.entries)
            run_tts(report, models, manifest, config, pythons, artifacts, timeout, asr_integrity)
    except TaskError as exc:
        report.add(f"{pkg}/task-gate", exc.status, str(exc))
    except (OSError, ValueError, KeyError, TypeError, IndexError, StopIteration, RunnerError) as exc:
        report.add(f"{pkg}/task-gate", "fail", f"invalid task output/input: {exc}")
    return all(e["status"] == "pass" for e in report.entries[start:])
