#!/usr/bin/env python3
"""Isolated TTS screening; never changes shipping bundles or their manifests.

Run with the isolated ORT 1.24.2 PYTHONPATH. See the paired report for commands.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import resource
import time

import tts_runner as tts

CACHE = Path.home() / "Library/Caches/Auralis/tts"
ROLES = ("speaker_encoder", "talker_prefill", "talker_decode", "code_predictor", "vocoder")
# Defined before any candidate execution. Original six are retained verbatim.
CASES = [
    ("zh1", "你好，世界。", "chinese", "zh"),
    ("zh2", "今天天气不错，我们去公园散步吧。", "chinese", "zh"),
    ("en1", "Hello world, this is a real speech synthesis test.", "english", "en"),
    ("en2", "The quick brown fox jumps over the lazy dog.", "english", "en"),
    ("cross1", "你好，很高兴认识你。", "chinese", "en"),
    ("cross2", "Nice to meet you, this is a cross language test.", "english", "zh"),
    ("zh_number", "今天是九月七日，订单号是二零二六，金额是三百二十五元。", "chinese", "zh"),
    ("en_number", "The order number is two thousand twenty six, and the total is three hundred twenty five dollars.", "english", "en"),
    ("zh_negation", "请不要取消明天去上海的火车票，我不是要去北京。", "chinese", "en"),
    ("en_negation", "Do not cancel my train to Shanghai tomorrow. I am not going to Beijing.", "english", "zh"),
    ("zh_long", "虽然今天下雨，但是张明仍然按时到了上海虹桥站。他先确认了车次和座位，然后给同事发消息，提醒大家不要忘记带身份证。", "chinese", "zh"),
    ("en_long", "Although it was raining, Alice arrived at London Paddington on time. She checked the train number and her seat, then reminded her colleagues not to forget their passports.", "english", "en"),
]


def sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for b in iter(lambda: f.read(4 * 1024 * 1024), b""):
            h.update(b)
    return h.hexdigest()


class TimedSession:
    def __init__(self, session, capture=None, capture_call=0):
        self.session = session
        self.calls = 0
        self.seconds = 0.0
        self.input_bytes = 0
        self.capture = capture
        self.capture_call = capture_call

    def run(self, names, inputs):
        if self.capture and self.calls == self.capture_call:
            tts.np.savez(self.capture, **inputs)
        self.input_bytes += sum(x.nbytes for x in inputs.values())
        start = time.perf_counter()
        out = self.session.run(names, inputs)
        self.seconds += time.perf_counter() - start
        self.calls += 1
        return out


def quantize(args):
    import onnx
    import onnxruntime as ort
    from onnxruntime.quantization.matmul_nbits_quantizer import (
        DefaultWeightOnlyQuantConfig, MatMulNBitsQuantizer,
    )
    from onnxruntime.quantization import QuantFormat
    assert ort.__version__ == "1.24.2", ort.__version__
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    manifest = out / "conversion.json"
    records = json.loads(manifest.read_text())["models"] if manifest.exists() else []
    for role in args.roles.split(","):
        src, dst = args.model / f"{role}.onnx", out / f"{role}.onnx"
        model = onnx.load(str(src))
        before = collections.Counter(n.op_type for n in model.graph.node)
        config = DefaultWeightOnlyQuantConfig(
            block_size=args.block, is_symmetric=True, accuracy_level=args.accuracy,
            quant_format=QuantFormat.QOperator, op_types_to_quantize=("MatMul",),
            bits=args.bits,
        )
        start = time.perf_counter()
        quant = MatMulNBitsQuantizer(model, algo_config=config)
        quant.process()
        quant.model.save_model_to_file(str(dst), use_external_data_format=True)
        after = collections.Counter(n.op_type for n in quant.model.model.graph.node)
        paths = [p for p in sorted(out.glob(f"{role}.onnx*")) if p.is_file()]
        record = dict(role=role, seconds=time.perf_counter()-start,
                      before=dict(before), after=dict(after), source_sha256=sha(src),
                      files=[dict(path=str(p), bytes=p.stat().st_size, sha256=sha(p)) for p in paths])
        records = [r for r in records if r["role"] != role] + [record]
        payload = dict(ort=ort.__version__, onnx=onnx.__version__, bits=args.bits,
                       block=args.block, accuracy=args.accuracy, symmetric=True,
                       ops=["MatMul"], script_sha256=sha(__file__), models=records)
        (out / "conversion.json").write_text(json.dumps(payload, indent=2))
        print(json.dumps(record), flush=True)


def bench(args):
    tts._import_heavy()
    np, ort, sf = tts.np, tts.ort, tts.sf
    assert ort.__version__ == "1.24.2", ort.__version__
    args.output.mkdir(parents=True, exist_ok=True)
    cfg = tts.load_config(args.model)
    embs = tts.EmbeddingTables(args.model)
    tokenizer = tts.load_tokenizer(args.model)
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    if args.no_spin:
        opts.add_session_config_entry("session.intra_op.allow_spinning", "0")
    sessions, loads = {}, {}
    start = time.perf_counter()
    for role in ROLES:
        candidate = args.variant / f"{role}.onnx" if args.variant else None
        path = candidate if candidate and candidate.exists() else args.model / f"{role}.onnx"
        t0 = time.perf_counter()
        sessions[role] = TimedSession(ort.InferenceSession(str(path), sess_options=opts,
                                                      providers=["CPUExecutionProvider"]),
                                     args.output/f"{role}-inputs.npz" if args.capture else None,
                                     7 if role == "code_predictor" else 0)
        loads[role] = dict(seconds=time.perf_counter()-t0, path=str(path),
                           maxrss_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    load_s = time.perf_counter()-start
    bundle = type("Bundle", (), {"sessions": sessions})()
    speakers = {v: tts.extract_speaker_embedding(bundle, CACHE/f"ref_{v}.wav") for v in ("zh", "en")}
    cases = CASES if not args.cases else [c for c in CASES if c[0] in args.cases.split(",")]
    result = dict(runtime=ort.__version__, threads=4, no_spin=args.no_spin, load_seconds=load_s,
                  loads=loads, script_sha256=sha(__file__), runner_sha256=sha(tts.__file__),
                  reference_sha256={v:sha(CACHE/f"ref_{v}.wav") for v in ("zh", "en")},
                  seed=0, top_k=1, temperature=0.9, repetition_penalty=1.05,
                  max_frames=384, cases=[], note="Host screening; load average recorded. No device claim.")
    for repeat in range(args.repeats):
        for cid, text, lang, voice in cases:
            before = {r:(s.calls,s.seconds,s.input_bytes) for r,s in sessions.items()}
            t0 = time.perf_counter()
            load_average = os.getloadavg()
            gen = tts.generate_codes(bundle, embs, cfg, tts.build_prompt_ids(tokenizer,text),
                                     speakers[voice], lang, max_frames=384, top_k=1, seed=0)
            wave = sessions["vocoder"].run(None, {"codes":gen.codes[None]})[0].reshape(-1)
            elapsed = time.perf_counter()-t0
            path = args.output/f"{cid}_r{repeat}.wav"
            finite, peak = bool(np.isfinite(wave).all()), float(np.abs(wave).max())
            sf.write(path, np.clip(wave,-1,1), tts.SAMPLE_RATE)
            phases = {r:dict(calls=s.calls-before[r][0], seconds=s.seconds-before[r][1],
                            input_bytes=s.input_bytes-before[r][2]) for r,s in sessions.items()}
            duration = len(wave)/tts.SAMPLE_RATE
            row = dict(id=cid, repeat=repeat, text=text, language=lang, voice=voice,
                       audio_path=str(path), seconds=elapsed, duration_s=duration, rtf=elapsed/duration,
                       cold_total_s=elapsed+load_s if not result["cases"] else None,
                       frames=gen.frames, eos=gen.frames<384, finite=finite, peak=peak,
                       clipping_ratio=float(np.mean(np.abs(wave)>=0.999)),
                       maxrss_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                       load_average=load_average, phases=phases,
                       group0_tokens=gen.group0_tokens)
            result["cases"].append(row)
            (args.output/"results.json").write_text(json.dumps(result,ensure_ascii=False,indent=2))
            print(json.dumps({k:v for k,v in row.items() if k not in ("group0_tokens", "phases")},ensure_ascii=False),flush=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=["quantize","bench"])
    p.add_argument("--model",type=Path,default=CACHE/"hf")
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--variant",type=Path)
    p.add_argument("--roles",default="talker_prefill,talker_decode,code_predictor")
    p.add_argument("--bits",type=int,default=4)
    p.add_argument("--block",type=int,default=32)
    p.add_argument("--accuracy",type=int,default=1)
    p.add_argument("--cases",default="")
    p.add_argument("--repeats",type=int,default=1)
    p.add_argument("--no-spin",action="store_true")
    p.add_argument("--capture",action="store_true")
    a=p.parse_args()
    (quantize if a.command=="quantize" else bench)(a)


if __name__=="__main__":
    main()
