#!/usr/bin/env python3
"""Real Hy-MT runner over the hymt_core C ABI (ctypes). No fake generate.

Protocol (for validate_models integration; this file does not edit manifests):

  stdin/CLI:
    --lib PATH          libhymt_core.dylib / libhymt_jni.so
    --model PATH        remapped STQ1_0 GGUF (type id 43)
    --json-report PATH  write the report
    --cases PATH        optional JSON list of {id,text,sourceLanguage,targetLanguage,context?}
    --llama-cli PATH    optional upstream CLI for prompt/output comparison

  C ABI (fixed, hymt_core.h):
    hymt_runtime_revision() -> const char*
    hymt_load(path, **handle, err, err_len) -> int
    hymt_translate(h, text, src, tgt, context, n, **out, err, err_len) -> int
    hymt_cancel / hymt_release / hymt_free_string
    status: 0 OK, 1 INVALID, 2 ABORTED, 3 FAILED

  JSON report:
    schemaVersion, runner, runtimeRevision, modelPath, modelSha256,
    library, nThreads (llama internal, currently 4), sampling="greedy",
    hardware {sysname,machine,cpuCount,loadavg}, interference {otherLlamaPids},
    loadMs, cases[{id,sourceLanguage,targetLanguage,text,context,status,output,latencyMs,err}],
    cliComparison {available, notes, cases?}

  Sampling is greedy argmax (deterministic). Official HY-MT card recommends
  temperature 0.7 / top_k 20 / top_p 0.6 / repetition_penalty 1.05 — not used
  here; do not treat greedy outputs as the card's sampling numbers.

Exit: 0 all cases OK | 1 inference failure | 2 missing lib/model | 4 args.
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import platform
import subprocess
import sys
import time
from pathlib import Path

PINNED = "1e411d8f5a1e23525fa3265dfb4bd76265465397"
HYMT_OK, HYMT_INVALID, HYMT_ABORTED, HYMT_FAILED = 0, 1, 2, 3
STATUS_NAME = {0: "ok", 1: "invalid", 2: "aborted", 3: "failed"}

DEFAULT_LIB = Path(
    os.environ.get(
        "HYMT_LIB",
        "/Users/arietids/Library/Caches/Auralis/mt/build-host/libhymt_core.dylib",
    )
)
DEFAULT_MODEL = Path(
    os.environ.get(
        "HYMT_MODEL",
        "/Users/arietids/Library/Caches/Auralis/mt/models/Hy-MT1.5-1.8B-1.25bit-stq43.gguf",
    )
)
DEFAULT_CASES = [
    {"id": "zh-en-museum", "text": "今天下午我们去博物馆参观，好吗？",
     "sourceLanguage": "Chinese", "targetLanguage": "English",
     "context": ["The weather is nice today."]},
    {"id": "en-zh-hello", "text": "Hello, how are you?",
     "sourceLanguage": "English", "targetLanguage": "Chinese", "context": []},
    {"id": "zh-en-thanks", "text": "谢谢你的帮助。",
     "sourceLanguage": "Chinese", "targetLanguage": "English", "context": []},
    {"id": "en-zh-meeting", "text": "The meeting starts at 3:00 pm, not 4:00.",
     "sourceLanguage": "English", "targetLanguage": "Chinese", "context": []},
    {"id": "zh-en-negation", "text": "我没有把文件发给李明。",
     "sourceLanguage": "Chinese", "targetLanguage": "English", "context": []},
    {"id": "en-zh-name-num", "text": "Please call Dr. Wang at 138-0013-8000 before Friday.",
     "sourceLanguage": "English", "targetLanguage": "Chinese", "context": []},
    {"id": "zh-en-codes", "text": "请把门关上。",
     "sourceLanguage": "zh", "targetLanguage": "en", "context": []},
    {"id": "ja-zh", "text": "今日は雨が降っています。",
     "sourceLanguage": "Japanese", "targetLanguage": "Chinese", "context": []},
    {"id": "unknown-zh", "text": "今日は雨が降っています。",
     "sourceLanguage": "unknown", "targetLanguage": "Chinese", "context": []},
    {"id": "zh-en-long",
     "text": "这份报告涵盖了一季度销售、市场反馈与供应链的调整情况，请各部门关注重点问题。",
     "sourceLanguage": "Chinese", "targetLanguage": "English", "context": []},
    {"id": "en-zh-context", "text": "It will be held at the same time.",
     "sourceLanguage": "English", "targetLanguage": "Chinese",
     "context": ["The meeting has been postponed until next Monday."]},
]


class RunnerError(RuntimeError):
    exit_code = 2


class RunnerInputError(RunnerError):
    exit_code = 4


class RunnerFailError(RunnerError):
    exit_code = 1


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def other_llama_pids() -> list[int]:
    try:
        out = subprocess.check_output(["ps", "-axo", "pid=,command="], text=True)
    except (OSError, subprocess.CalledProcessError):
        return []
    pids = []
    me = os.getpid()
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        pid_s, _, cmd = line.partition(" ")
        try:
            pid = int(pid_s)
        except ValueError:
            continue
        if pid == me:
            continue
        low = cmd.lower()
        if "llama" in low or "hymt" in low:
            pids.append(pid)
    return pids


class HyMtLib:
    def __init__(self, path: Path):
        self.path = path
        self.lib = ctypes.CDLL(str(path))
        self.lib.hymt_runtime_revision.restype = ctypes.c_char_p
        self.lib.hymt_load.argtypes = [
            ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p), ctypes.c_char_p, ctypes.c_size_t
        ]
        self.lib.hymt_load.restype = ctypes.c_int
        self.lib.hymt_translate.argtypes = [
            ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
            ctypes.POINTER(ctypes.c_char_p), ctypes.c_size_t,
            ctypes.POINTER(ctypes.c_char_p), ctypes.c_char_p, ctypes.c_size_t,
        ]
        self.lib.hymt_translate.restype = ctypes.c_int
        self.lib.hymt_release.argtypes = [ctypes.c_void_p]
        self.lib.hymt_free_string.argtypes = [ctypes.c_char_p]

    def revision(self) -> str:
        raw = self.lib.hymt_runtime_revision()
        return raw.decode("utf-8") if raw else ""

    def load(self, model: Path) -> ctypes.c_void_p:
        handle = ctypes.c_void_p()
        err = ctypes.create_string_buffer(512)
        rc = self.lib.hymt_load(str(model).encode("utf-8"), ctypes.byref(handle), err, 512)
        if rc != HYMT_OK or not handle.value:
            raise RunnerFailError(f"hymt_load rc={rc} err={err.value.decode('utf-8', 'replace')}")
        return handle

    def translate(self, handle: ctypes.c_void_p, text: str, src: str, tgt: str,
                  context: list[str]) -> tuple[int, str, str]:
        err = ctypes.create_string_buffer(512)
        out = ctypes.c_char_p()
        encoded_ctx = [c.encode("utf-8") for c in context]
        arr = (ctypes.c_char_p * len(encoded_ctx))(*encoded_ctx) if encoded_ctx else None
        rc = self.lib.hymt_translate(
            handle,
            text.encode("utf-8"), src.encode("utf-8"), tgt.encode("utf-8"),
            arr, len(encoded_ctx),
            ctypes.byref(out), err, 512,
        )
        message = err.value.decode("utf-8", "replace")
        text_out = ""
        if out.value is not None:
            text_out = out.value.decode("utf-8", "replace")
            self.lib.hymt_free_string(out)
        return rc, text_out, message

    def release(self, handle: ctypes.c_void_p) -> None:
        if handle and handle.value:
            self.lib.hymt_release(handle)


def load_cases(path: Path | None) -> list[dict]:
    if path is None:
        return list(DEFAULT_CASES)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RunnerInputError(f"bad --cases: {exc}") from exc
    if not isinstance(data, list) or not data:
        raise RunnerInputError("--cases must be a non-empty JSON list")
    return data


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--lib", type=Path, default=DEFAULT_LIB)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--json-report", type=Path)
    parser.add_argument("--cases", type=Path)
    parser.add_argument("--llama-cli", type=Path)
    args = parser.parse_args(argv)
    try:
        if not args.lib.is_file():
            raise RunnerError(f"native library missing: {args.lib}")
        if not args.model.is_file():
            raise RunnerError(f"model missing: {args.model}")
        cases = load_cases(args.cases)
        lib = HyMtLib(args.lib)
        revision = lib.revision()
        if revision != PINNED:
            raise RunnerFailError(f"runtimeRevision {revision} != pinned {PINNED}")
        model_hash = sha256_file(args.model)
        t0 = time.perf_counter()
        handle = lib.load(args.model)
        load_ms = (time.perf_counter() - t0) * 1000.0
        results = []
        failed = False
        try:
            for case in cases:
                text = case.get("text", "")
                src = case.get("sourceLanguage", "")
                tgt = case.get("targetLanguage", "")
                ctx = case.get("context") or []
                if not isinstance(ctx, list):
                    raise RunnerInputError(f"case {case.get('id')} context must be a list")
                t1 = time.perf_counter()
                rc, output, err = lib.translate(handle, text, src, tgt, ctx)
                latency = (time.perf_counter() - t1) * 1000.0
                entry = {
                    "id": case.get("id"),
                    "text": text,
                    "sourceLanguage": src,
                    "targetLanguage": tgt,
                    "context": ctx,
                    "status": STATUS_NAME.get(rc, str(rc)),
                    "output": output,
                    "err": err,
                    "latencyMs": round(latency, 1),
                }
                if rc != HYMT_OK:
                    failed = True
                results.append(entry)
        finally:
            lib.release(handle)
        try:
            loadavg = os.getloadavg()
        except OSError:
            loadavg = None
        cli = {"available": False, "notes": "llama-cli not invoked"}
        if args.llama_cli is not None:
            if not args.llama_cli.is_file():
                cli = {"available": False, "notes": f"llama-cli missing: {args.llama_cli}"}
            else:
                prompt = (
                    "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n"
                    "今日は雨が降っています。"
                )
                proc = subprocess.run(
                    [str(args.llama_cli), "-m", str(args.model), "--jinja", "-st",
                     "-p", prompt, "-n", "64", "--temp", "0", "--top-k", "1",
                     "-ngl", "0", "-c", "2048", "--no-display-prompt"],
                    capture_output=True, text=True, check=False,
                )
                cli_out = (proc.stdout or "").replace("[end of text]", "").strip()
                native_ja = next((c["output"] for c in results if c["id"] == "ja-zh"), None)
                cli = {
                    "available": True,
                    "path": str(args.llama_cli),
                    "prompt": prompt,
                    "output": cli_out,
                    "nativeJaZh": native_ja,
                    "match": native_ja == cli_out if native_ja is not None else False,
                    "exitCode": proc.returncode,
                    "notes": "greedy --jinja --single-turn; ZH template target=中文",
                }
        report = {
            "schemaVersion": "1",
            "runner": "mt_runner",
            "runtimeRevision": revision,
            "modelPath": str(args.model),
            "modelSha256": model_hash,
            "library": str(args.lib),
            "nThreads": 4,
            "sampling": "greedy",
            "hardware": {
                "sysname": platform.system(),
                "machine": platform.machine(),
                "cpuCount": os.cpu_count(),
                "loadavg": loadavg,
            },
            "interference": {
                "otherLlamaPids": other_llama_pids(),
                "note": "one handle, sequential cases, llama n_threads=4 inside the process",
            },
            "loadMs": round(load_ms, 1),
            "cases": results,
            "cliComparison": cli,
        }
        text = json.dumps(report, ensure_ascii=False, indent=2)
        if args.json_report:
            args.json_report.parent.mkdir(parents=True, exist_ok=True)
            args.json_report.write_text(text + "\n", encoding="utf-8")
        print(text)
        if failed:
            return 1
        return 0
    except RunnerError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return exc.exit_code


if __name__ == "__main__":
    sys.exit(main())
