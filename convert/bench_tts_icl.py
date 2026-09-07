#!/usr/bin/env python3
"""Fixed official ICL/xvector comparison; isolated from shipping model contracts."""
import argparse
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import soundfile as sf

ROOT=Path.home()/"Library/Caches/Auralis/tts/icl"
POOL=ROOT.parent/"upstream-fidelity/real-pool"
HF=Path.home()/".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc"


def sha(path):
    h=hashlib.sha256()
    with Path(path).open("rb") as f:
        for b in iter(lambda:f.read(4194304),b""):h.update(b)
    return h.hexdigest()


def synthesize():
    import torch
    from qwen_tts import Qwen3TTSModel
    import tts_runner as runner
    torch.set_num_threads(2)
    ROOT.mkdir(parents=True,exist_ok=True)
    pool=json.loads((POOL/"manifest.json").read_text())
    speakers=[s for s in pool["speakers"] if s["id"] in ("121","260")]
    texts=json.loads((POOL/"predeclared-criteria.json").read_text())["synthesis_texts"]
    plan=dict(speaker_ids=["121","260"],texts=texts,seed=20260906,backend="official PyTorch FP32/eager for BOTH modes",
        sampling=json.loads((HF/"generation_config.json").read_text()),max_new_tokens_override=384,
        non_streaming_mode=False,reference_text="Exact LibriSpeech manifest transcript, no per-speaker edit",
        modes=["xvector","icl"],criteria=dict(no_new_synthesis_or_asr_failure=True,no_new_identity_top1_failure=True,
            paired_median_heldout_cosine_gain_min=.03,paired_worst_heldout_cosine_gain_min=-.03),
        note="Four paired cases are a feature screen, not a production-quality claim. Existing ONNX baseline is secondary context because RNG/runtime differ.")
    (ROOT/"plan.json").write_text(json.dumps(plan,ensure_ascii=False,indent=2))
    model=Qwen3TTSModel.from_pretrained(str(HF),dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    runner._import_heavy();embs=runner.EmbeddingTables(ROOT.parent/"hf");cfg=runner.load_config(ROOT.parent/"hf")
    report=dict(script_sha256=sha(__file__),hf_revision=HF.name,torch=torch.__version__,cases=[],embedding_coverage=[])
    original=model.model.talker.generate
    capture={}
    def record(**kwargs):
        capture["prefill_shape"]=list(kwargs["inputs_embeds"].shape)
        output=original(**kwargs);capture["sequence"]=output.sequences[0].tolist();return output
    model.model.talker.generate=record
    for speaker in speakers:
        prompts={}
        for mode in plan["modes"]:
            start=time.perf_counter()
            prompts[mode]=model.create_voice_clone_prompt(ref_audio=speaker["recordings"]["reference"]["path"],
                ref_text=speaker["recordings"]["reference"]["text"],x_vector_only_mode=mode=="xvector")
            print("prepared",speaker["id"],mode,time.perf_counter()-start,flush=True)
        ref_codes=prompts["icl"][0].ref_code.cpu().numpy()
        np.save(ROOT/f"reference-{speaker['id']}-codes.npy",ref_codes)
        coverage=dict(speaker=speaker["id"],code_shape=list(ref_codes.shape),groups=[])
        for g in range(16):
            ids=torch.from_numpy(ref_codes[:,g])
            official=(model.model.talker.get_input_embeddings()(ids) if g==0 else model.model.talker.code_predictor.get_input_embeddings()[g-1](ids)).detach().cpu().numpy()
            local=np.stack([embs.talker_codec_embedding(int(i)) if g==0 else embs.cp_codec_embedding(g-1,int(i)) for i in ids])
            coverage["groups"].append(dict(group=g,min=int(ids.min()),max=int(ids.max()),max_abs=float(np.max(np.abs(official-local)))))
        # Execute official ICL prompt assembly and compare with the existing NPY tables.
        for language,text in texts.items():
            text_ids=model._tokenize_texts([model._build_assistant_text(text)])[0][:,3:-5]
            ref_ids=model._tokenize_texts([model._build_ref_text(speaker["recordings"]["reference"]["text"])])[0][:,3:-2]
            def projected(i):return embs.project(embs.text_embed(int(i)))
            pad=projected(cfg.tts["tts_pad_token_id"]);eos=projected(cfg.tts["tts_eos_token_id"])
            with torch.inference_mode():
                official_prompt,official_trailing=model.model.generate_icl_prompt(text_ids,ref_ids,torch.from_numpy(ref_codes),torch.from_numpy(pad)[None,None],torch.from_numpy(eos)[None,None],False)
            joined=torch.cat([ref_ids,text_ids],dim=-1).reshape(-1).tolist()
            text_embed=np.stack([projected(i) for i in joined]+[eos])
            code_embed=np.stack([np.stack([embs.talker_codec_embedding(int(row[g])) if g==0 else embs.cp_codec_embedding(g-1,int(row[g])) for g in range(16)]).sum(axis=0) for row in ref_codes])
            code_embed=np.concatenate([embs.talker_codec_embedding(cfg.talker["codec_bos_id"])[None],code_embed])
            if len(text_embed)>len(code_embed):prefix=text_embed[:len(code_embed)]+code_embed;trailing=text_embed[len(code_embed):]
            else:prefix=np.concatenate([text_embed,np.repeat(pad[None],len(code_embed)-len(text_embed),axis=0)])+code_embed;trailing=pad[None]
            coverage.setdefault("prompt_comparison",[]).append(dict(language=language,prefix_shape=list(prefix.shape),prefix_max_abs=float(np.max(np.abs(prefix-official_prompt[0].numpy()))),trailing_max_abs=float(np.max(np.abs(trailing-official_trailing.reshape(-1,1024).numpy())))))
        report["embedding_coverage"].append(coverage)
        for language,text in texts.items():
            for mode in plan["modes"]:
                capture.clear();torch.manual_seed(plan["seed"]);start=time.perf_counter()
                cid=f"{speaker['id']}-{language}-{mode}"
                row=dict(id=cid,voice=speaker["id"],language=language,mode=mode,text=text,repeat=0)
                try:
                    waves,sr=model.generate_voice_clone(text=text,language=language,voice_clone_prompt=prompts[mode],non_streaming_mode=False,max_new_tokens=384)
                    wave=np.asarray(waves[0],dtype=np.float32);terminated=model.model.config.talker_config.codec_eos_token_id in capture["sequence"]
                    path=ROOT/f"{cid}.wav";sf.write(path,wave,sr)
                    row.update(audio_path=str(path),sample_rate=sr,duration_s=len(wave)/sr,finite=bool(np.isfinite(wave).all()),clipping_ratio=float(np.mean(np.abs(wave)>=.999)),eos=terminated,
                               status="success" if terminated else "synthesis_failed",error=None if terminated else "official generation exhausted budget without EOS")
                except Exception as exc:row.update(status="synthesis_failed",error=f"{type(exc).__name__}: {exc}")
                row.update(seconds=time.perf_counter()-start,**capture);report["cases"].append(row)
                (ROOT/"results.json").write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({k:v for k,v in row.items() if k!="sequence"},ensure_ascii=False),flush=True)
    delattr(model.model.talker,"generate")


def score(results_dir=None,baseline_mode="xvector",candidate_mode="icl"):
    import librosa
    import torch
    from speechbrain.inference.speaker import EncoderClassifier
    torch.set_num_threads(2)
    model=EncoderClassifier.from_hparams(source=str(ROOT.parent/"ecapa"),savedir=str(ROOT.parent/"ecapa"),run_opts={"device":"cpu"})
    def embed(path):
        x,sr=sf.read(path,dtype="float32")
        if sr!=16000:x=librosa.resample(x,orig_sr=sr,target_sr=16000)
        with torch.inference_mode():return model.encode_batch(torch.from_numpy(x)[None]).squeeze().numpy()
    pool=json.loads((POOL/"manifest.json").read_text())["speakers"]
    gallery={s["id"]:embed(s["recordings"]["heldout"]["path"]) for s in pool}
    working=Path(results_dir) if results_dir else ROOT
    result=dict(cases=[],paired=[])
    for case in json.loads((working/"results.json").read_text())["cases"]:
        if case["status"]!="success":result["cases"].append(dict(id=case["id"],status=case["status"],error=case["error"]));continue
        x=embed(case["audio_path"]);scores={k:float(np.dot(x,e)/(np.linalg.norm(x)*np.linalg.norm(e))) for k,e in gallery.items()}
        result["cases"].append(dict(id=case["id"],voice=case["voice"],language=case["language"],mode=case["mode"],scores=scores,own=scores[case["voice"]],top1=max(scores,key=scores.get),correct=max(scores,key=scores.get)==case["voice"]))
    for identity in ("121","260"):
        for language in ("english","chinese"):
            rows={x.get("mode"):x for x in result["cases"] if x.get("voice")==identity and x.get("language")==language}
            if baseline_mode in rows and candidate_mode in rows:
                result["paired"].append(dict(voice=identity,language=language,cosine_gain=rows[candidate_mode]["own"]-rows[baseline_mode]["own"],new_identity_failure=rows[baseline_mode]["correct"] and not rows[candidate_mode]["correct"]))
    (working/"identity.json").write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))


def dump_prefill():
    import torch
    from qwen_tts import Qwen3TTSModel
    torch.set_num_threads(2)
    model=Qwen3TTSModel.from_pretrained(str(HF),dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    pool=json.loads((POOL/"manifest.json").read_text())["speakers"]
    texts=json.loads((ROOT/"plan.json").read_text())["texts"]
    pending={}
    class Captured(Exception):pass
    def capture(**kwargs):
        position_ids,rope_delta=model.model.talker.get_rope_index(kwargs["attention_mask"])
        np.savez(ROOT/f"prefill-{pending['id']}.npz",inputs_embeds=kwargs["inputs_embeds"].detach().numpy(),
            attention_mask=kwargs["attention_mask"].numpy(),position_ids=position_ids.numpy(),
            trailing_text_hidden=kwargs["trailing_text_hidden"].detach().numpy(),tts_pad_embed=kwargs["tts_pad_embed"].detach().numpy(),rope_delta=rope_delta.numpy())
        print(pending["id"],list(kwargs["inputs_embeds"].shape),flush=True)
        raise Captured()
    model.model.talker.generate=capture
    for speaker in pool:
        if speaker["id"] not in ("121","260"):continue
        prompt=model.create_voice_clone_prompt(ref_audio=speaker["recordings"]["reference"]["path"],ref_text=speaker["recordings"]["reference"]["text"],x_vector_only_mode=False)
        for language,text in texts.items():
            pending["id"]=f"{speaker['id']}-{language}"
            try:model.generate_voice_clone(text=text,language=language,voice_clone_prompt=prompt,non_streaming_mode=False,max_new_tokens=384)
            except Captured:pass
    delattr(model.model.talker,"generate")


def native_runtime():
    import dataclasses
    import onnxruntime as ort
    import torch
    from qwen_tts import Qwen3TTSModel
    torch.set_num_threads(2)
    out=ROOT/"native-runtime";out.mkdir(exist_ok=True)
    plan=dict(voice="121",texts=json.loads((ROOT/"plan.json").read_text())["texts"],seed=20260906,
        controls="Same native-resampled 24k PCM, same speaker embedding/ref transcript/default sampling; only PyTorch versus ORT reference codes differ",
        criteria=dict(no_new_asr_error=True,no_new_identity_failure=True,each_cosine_drop_limit=.03))
    (out/"plan.json").write_text(json.dumps(plan,ensure_ascii=False,indent=2))
    native=np.load(ROOT.parent/"upstream-fidelity/resampler-evidence-real-121.npz")["pcm_0.9568718266"]
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(ROOT/"reference_encoder.onnx"),sess_options=options,providers=["CPUExecutionProvider"])
    ort_codes=session.run(None,{"pcm":native[None,None]})[0];del session
    model=Qwen3TTSModel.from_pretrained(str(HF),dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    speaker=next(s for s in json.loads((POOL/"manifest.json").read_text())["speakers"] if s["id"]=="121")
    base=model.create_voice_clone_prompt(ref_audio=(native,24000),ref_text=speaker["recordings"]["reference"]["text"],x_vector_only_mode=False)
    candidate=[dataclasses.replace(base[0],ref_code=torch.from_numpy(ort_codes[0].T.copy()))]
    official=base[0].ref_code.numpy().T[None]
    report=dict(script_sha256=sha(__file__),encoder_sha256=sha(ROOT/"reference_encoder.onnx"),
                reference_code_difference=int(np.count_nonzero(ort_codes!=official)),cases=[])
    np.savez(out/"reference-codes.npz",torch=official,onnx=ort_codes)
    original=model.model.talker.generate;capture={}
    def record(**kwargs):
        output=original(**kwargs);capture["sequence"]=output.sequences[0].tolist();return output
    model.model.talker.generate=record
    for language,text in plan["texts"].items():
        for mode,prompt in (("native_torch",base),("native_ort",candidate)):
            torch.manual_seed(plan["seed"]);capture.clear();start=time.perf_counter()
            cid=f"121-{language}-{mode}";row=dict(id=cid,voice="121",language=language,mode=mode,text=text,repeat=0)
            try:
                waves,sr=model.generate_voice_clone(text=text,language=language,voice_clone_prompt=prompt,non_streaming_mode=False,max_new_tokens=384)
                wave=np.asarray(waves[0],dtype=np.float32);eos=model.model.config.talker_config.codec_eos_token_id in capture["sequence"]
                path=out/f"{cid}.wav";sf.write(path,wave,sr)
                row.update(audio_path=str(path),eos=eos,finite=bool(np.isfinite(wave).all()),clipping_ratio=float(np.mean(np.abs(wave)>=.999)),
                           duration_s=len(wave)/sr,status="success" if eos else "synthesis_failed",error=None if eos else "budget without EOS")
            except Exception as exc:row.update(status="synthesis_failed",error=str(exc))
            row.update(seconds=time.perf_counter()-start,**capture);report["cases"].append(row)
            (out/"results.json").write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps({k:v for k,v in row.items() if k!="sequence"},ensure_ascii=False),flush=True)
    delattr(model.model.talker,"generate")


if __name__=="__main__":
    p=argparse.ArgumentParser();p.add_argument("mode",choices=["synthesize","score","dump-prefill","native-runtime"])
    p.add_argument("--results-dir");p.add_argument("--baseline-mode",default="xvector");p.add_argument("--candidate-mode",default="icl");a=p.parse_args()
    if a.mode=="score":score(a.results_dir,a.baseline_mode,a.candidate_mode)
    else:{"synthesize":synthesize,"dump-prefill":dump_prefill,"native-runtime":native_runtime}[a.mode]()
