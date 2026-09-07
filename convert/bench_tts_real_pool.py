#!/usr/bin/env python3
"""Predeclared six-real-speaker paired synthesis and held-out identity screening."""
import argparse
import gc
import hashlib
import json
import os
from pathlib import Path
import time

import numpy as np
import soundfile as sf

import tts_runner as tts

CACHE=Path.home()/"Library/Caches/Auralis/tts"
POOL=CACHE/"upstream-fidelity/real-pool"
RUNS=POOL
TOP_K=1
SEED=0
SPEAKERS=""


def synth(variant):
    import librosa
    tts._import_heavy()
    root=RUNS/variant;root.mkdir(parents=True,exist_ok=True)
    options=tts.ort.SessionOptions();options.intra_op_num_threads=4;options.inter_op_num_threads=1
    config=tts.load_config(CACHE/"hf");embs=tts.EmbeddingTables(CACHE/"hf");tokenizer=tts.load_tokenizer(CACHE/"hf")
    sessions={}
    for role in ("speaker_encoder","talker_prefill","talker_decode","code_predictor","vocoder"):
        path=CACHE/"optimization"/f"{variant}-b32-a4"/f"{role}.onnx"
        if variant=="fp32" or not path.exists():path=CACHE/"hf"/f"{role}.onnx"
        sessions[role]=tts.ort.InferenceSession(str(path),sess_options=options,providers=["CPUExecutionProvider"])
    bundle=type("Bundle",(),{"sessions":sessions})()
    manifest=json.loads((POOL/"manifest.json").read_text());criteria=json.loads((POOL/"predeclared-criteria.json").read_text())
    result=dict(variant=variant,runtime=tts.ort.__version__,criteria_sha256=hashlib.sha256((POOL/"predeclared-criteria.json").read_bytes()).hexdigest(),
                script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                runner_sha256=hashlib.sha256(Path(tts.__file__).read_bytes()).hexdigest(),
                sampling=dict(top_k=TOP_K,seed=SEED,temperature=.9,repetition_penalty=1.05,max_frames=384),cases=[])
    if (root/"results.json").exists():
        previous=json.loads((root/"results.json").read_text())
        assert previous["runner_sha256"]==result["runner_sha256"]
        assert previous["sampling"]==result["sampling"]
        result["cases"]=previous["cases"]
        result["previous_harness_sha256"]=previous["script_sha256"]
    for speaker in manifest["speakers"]:
        if SPEAKERS and speaker["id"] not in SPEAKERS.split(","):continue
        pcm,sr=sf.read(speaker["recordings"]["reference"]["path"],dtype="float32")
        pcm=librosa.resample(pcm,orig_sr=sr,target_sr=24000)
        ref=POOL/f"{speaker['id']}-reference24.wav"
        if not ref.exists():sf.write(ref,pcm,24000,subtype="FLOAT")
        embedding=tts.extract_speaker_embedding(bundle,ref)
        for lang,text in criteria["synthesis_texts"].items():
            cid=f"{speaker['id']}-{lang}"
            if any(r["id"]==cid for r in result["cases"]):continue
            start=time.perf_counter();load=os.getloadavg()
            try:
                gen=tts.generate_codes(bundle,embs,config,tts.build_prompt_ids(tokenizer,text),embedding,lang,max_frames=384,top_k=TOP_K,seed=SEED)
            except RuntimeError as exc:
                row=dict(id=cid,voice=speaker["id"],sex=speaker["sex"],language=lang,text=text,repeat=0,
                         status="synthesis_failed",error=str(exc),seconds=time.perf_counter()-start,load_average=load)
                result["cases"].append(row);(root/"results.json").write_text(json.dumps(result,ensure_ascii=False,indent=2))
                print(json.dumps(row,ensure_ascii=False),flush=True);continue
            wave=sessions["vocoder"].run(None,{"codes":gen.codes[None]})[0].reshape(-1)
            cid=f"{speaker['id']}-{lang}";path=root/f"{cid}.wav"
            sf.write(path,np.clip(wave,-1,1),24000)
            row=dict(id=cid,voice=speaker["id"],sex=speaker["sex"],language=lang,text=text,repeat=0,audio_path=str(path),
                     frames=gen.frames,eos=gen.frames<384,finite=bool(np.isfinite(wave).all()),
                     clipping_ratio=float(np.mean(np.abs(wave)>=.999)),seconds=time.perf_counter()-start,load_average=load)
            result["cases"].append(row)
            (root/"results.json").write_text(json.dumps(result,ensure_ascii=False,indent=2))
            print(json.dumps(row,ensure_ascii=False),flush=True)


def evaluate():
    import librosa
    import torch
    from speechbrain.inference.speaker import EncoderClassifier
    torch.set_num_threads(2)
    model=EncoderClassifier.from_hparams(source=str(CACHE/"ecapa"),savedir=str(CACHE/"ecapa"),run_opts={"device":"cpu"})
    def embed(path):
        x,sr=sf.read(path,dtype="float32")
        if sr!=16000:x=librosa.resample(x,orig_sr=sr,target_sr=16000)
        with torch.inference_mode():return model.encode_batch(torch.from_numpy(x)[None]).squeeze().numpy()
    manifest=json.loads((POOL/"manifest.json").read_text());speakers=manifest["speakers"]
    gallery={s["id"]:embed(s["recordings"]["heldout"]["path"]) for s in speakers}
    sexes={s["id"]:s["sex"] for s in speakers}
    def score(path,identity):
        v=embed(path)
        scores={key:float(np.dot(v,e)/(np.linalg.norm(v)*np.linalg.norm(e))) for key,e in gallery.items()}
        same_sex=[n for n in scores if n!=identity and sexes[n]==sexes[identity]]
        return dict(identity=identity,scores=scores,own=scores[identity],
                    margin=scores[identity]-max(value for key,value in scores.items() if key!=identity),
                    same_sex_margin=scores[identity]-max(scores[n] for n in same_sex),
                    top1=max(scores,key=scores.get),correct=max(scores,key=scores.get)==identity)
    output=dict(native=[score(s["recordings"]["reference"]["path"],s["id"]) for s in speakers],variants={})
    for variant in ("fp32","int8","int4"):
        path=RUNS/variant/"results.json"
        if not path.exists():continue
        output["variants"][variant]=[]
        for case in json.loads(path.read_text())["cases"]:
            if case.get("status")=="synthesis_failed":
                output["variants"][variant].append(dict(id=case["id"],identity=case["voice"],correct=False,status="synthesis_failed",error=case["error"]))
                continue
            row=dict(id=case["id"],language=case["language"],**score(case["audio_path"],case["voice"]))
            output["variants"][variant].append(row)
            (RUNS/"identity.json").write_text(json.dumps(output,indent=2))
            print(variant,case["id"],row["own"],row["margin"],row["correct"],flush=True)
    baseline={r["id"]:r for r in output["variants"].get("fp32",[])}
    output["paired"]={}
    for variant in ("int8","int4"):
        rows=output["variants"].get(variant,[])
        if rows and len(rows)==len(baseline):
            delta=[r["own"]-baseline[r["id"]]["own"] for r in rows if "own" in r and "own" in baseline[r["id"]]]
            output["paired"][variant]=dict(paired_success_count=len(delta),median_delta=float(np.median(delta)) if delta else None,worst_delta=min(delta) if delta else None,
                new_identity_failures=[r["id"] for r in rows if not r["correct"] and baseline[r["id"]]["correct"]])
    (RUNS/"identity.json").write_text(json.dumps(output,indent=2))


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("mode",choices=["synth","evaluate"]);p.add_argument("--variant",choices=["fp32","int8","int4"],default="fp32");p.add_argument("--experiment",default="");p.add_argument("--top-k",type=int,default=1);p.add_argument("--seed",type=int,default=0);p.add_argument("--speakers",default="");args=p.parse_args()
    if args.experiment:RUNS=POOL/args.experiment
    TOP_K=args.top_k;SEED=args.seed;SPEAKERS=args.speakers
    synth(args.variant) if args.mode=="synth" else evaluate()
