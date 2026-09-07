#!/usr/bin/env python3
"""Execute unmodified official Qwen speaker/prompt code without loading the LLM.

AST selection preserves the official class/function bodies; only unrelated
Transformers model definitions and imports are omitted. Prompt execution stops
at the talker.generate boundary to inspect real official input construction.
"""
import ast
import hashlib
import json
from pathlib import Path
import subprocess
from types import SimpleNamespace, MethodType
from typing import Optional

import librosa
import numpy as np
import soundfile as sf
import torch
from torch import nn
from torch.nn import functional as F
from safetensors import safe_open
from transformers.activations import ACT2FN
from transformers import PretrainedConfig

import tts_runner as tts

CACHE = Path.home()/"Library/Caches/Auralis/tts"
OUT = CACHE/"upstream-fidelity"
REPO = OUT/"Qwen3-TTS"
HF = Path.home()/".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc"


def sha(path):
    h=hashlib.sha256()
    with Path(path).open("rb") as f:
        for b in iter(lambda:f.read(4*1024*1024),b""):
            h.update(b)
    return h.hexdigest()


def compare(a,b):
    a,b=np.asarray(a,dtype=np.float64),np.asarray(b,dtype=np.float64)
    assert a.shape==b.shape,(a.shape,b.shape)
    return dict(shape=list(a.shape),cosine=float(np.dot(a.ravel(),b.ravel())/(np.linalg.norm(a)*np.linalg.norm(b))),
                max_abs=float(np.max(np.abs(a-b))),rms=float(np.sqrt(np.mean((a-b)**2))),
                norm_a=float(np.linalg.norm(a)),norm_b=float(np.linalg.norm(b)))


def selected_definitions():
    source=REPO/"qwen_tts/core/models/modeling_qwen3_tts.py"
    config=REPO/"qwen_tts/core/models/configuration_qwen3_tts.py"
    names={"Res2NetBlock","SqueezeExcitationBlock","AttentiveStatisticsPooling","TimeDelayNetBlock",
           "SqueezeExcitationRes2NetBlock","Qwen3TTSSpeakerEncoder","dynamic_range_compression_torch",
           "mel_spectrogram","Qwen3TTSTalkerResizeMLP"}
    nodes=[n for n in ast.parse(config.read_text()).body if getattr(n,"name",None)=="Qwen3TTSSpeakerEncoderConfig"]
    for n in ast.parse(source.read_text()).body:
        if getattr(n,"name",None) in names:
            nodes.append(n)
        if getattr(n,"name",None)=="Qwen3TTSForConditionalGeneration":
            nodes += [m for m in n.body if getattr(m,"name",None) in ("extract_speaker_embedding","generate_speaker_prompt","generate")]
    module=ast.Module(body=nodes,type_ignores=[])
    env=dict(torch=torch,nn=nn,F=F,Optional=Optional,ACT2FN=ACT2FN,PretrainedConfig=PretrainedConfig,librosa_mel_fn=librosa.filters.mel)
    exec(compile(module,str(source),"exec"),env)
    (OUT/"executed_official_definitions.py").write_text(ast.unparse(module))
    return env,source,config


class Captured(Exception):
    def __init__(self,values):
        self.values=values


class EmbeddingRows:
    def __init__(self,reader,key):
        self.reader,self.key=reader,key
    def __call__(self,ids):
        # Only rows requested by the unchanged official prompt code are loaded.
        shape=tuple(ids.shape)
        rows=[self.reader.get_slice(self.key)[int(i):int(i)+1].float() for i in ids.reshape(-1)]
        return torch.cat(rows).reshape(*shape,-1)


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2)
    tts._import_heavy()
    env,source,config_source=selected_definitions()
    config=json.loads((HF/"config.json").read_text())
    speaker=env["Qwen3TTSSpeakerEncoder"](env["Qwen3TTSSpeakerEncoderConfig"](**config["speaker_encoder_config"])).eval()
    with safe_open(HF/"model.safetensors",framework="pt") as reader:
        weights={k.removeprefix("speaker_encoder."):reader.get_tensor(k).float() for k in reader.keys() if k.startswith("speaker_encoder.")}
        speaker.load_state_dict(weights,strict=True)
        weight_dtypes={str(reader.get_tensor(k).dtype) for k in reader.keys() if k.startswith("speaker_encoder.")}
    opt=tts.ort.SessionOptions();opt.intra_op_num_threads=2
    session=tts.ort.InferenceSession(str(CACHE/"hf/speaker_encoder.onnx"),sess_options=opt,providers=["CPUExecutionProvider"])
    result=dict(official_git_revision=subprocess.check_output(["git","-C",str(REPO),"rev-parse","HEAD"],text=True).strip(),
                official_hf_revision=HF.name,source_sha256=sha(source),config_source_sha256=sha(config_source),
                model_sha256=sha(HF/"model.safetensors"),script_sha256=sha(__file__),runner_sha256=sha(tts.__file__),
                torch=torch.__version__,librosa=librosa.__version__,ort=tts.ort.__version__,
                loaded_speaker_tensors=len(weights),speaker_weight_dtypes=sorted(weight_dtypes),cases=[],prompts=[])
    official_mel=lambda x:env["mel_spectrogram"](torch.from_numpy(x)[None],1024,128,24000,256,1024,0,12000).transpose(1,2)
    refs={}
    for voice in ("zh","en"):
        x,sr=sf.read(CACHE/f"ref_{voice}.wav",dtype="float32");assert sr==24000
        with torch.inference_mode():
            mel=official_mel(x);our=tts.compute_mel(x)
            emb=speaker(mel).numpy();onnx=session.run(None,{"mel_spectrogram":our})[0]
            onnx_same=session.run(None,{"mel_spectrogram":mel.numpy()})[0]
        refs[voice]=emb[0]
        np.savez(OUT/f"native24-{voice}.npz",official_mel=mel.numpy(),runner_mel=our,official_embedding=emb,runner_embedding=onnx)
        result["cases"].append(dict(id=f"native24-{voice}",mel=compare(mel.numpy(),our),
            speaker_end_to_end=compare(emb,onnx),speaker_same_mel=compare(emb,onnx_same)))
        for input_sr in (16000,44100,48000):
            pcm=librosa.resample(x,orig_sr=24000,target_sr=input_sr)
            ideal=librosa.resample(pcm,orig_sr=input_sr,target_sr=24000)
            pos=np.arange(int(len(pcm)*24000/input_sr))/(24000/input_sr)
            left=np.clip(pos.astype(np.int64),0,len(pcm)-1);right=np.clip(left+1,0,len(pcm)-1)
            linear=(pcm[left]*(1-(pos-left))+pcm[right]*(pos-left)).astype(np.float32)
            n=min(len(ideal),len(linear));ideal,linear=ideal[:n],linear[:n]
            with torch.inference_mode():
                a,b=official_mel(ideal),official_mel(linear)
                ea,eb=speaker(a).numpy(),speaker(b).numpy()
            result["cases"].append(dict(id=f"resample-{input_sr}-{voice}",input_samples=len(pcm),
                pcm=compare(ideal,linear),mel=compare(a.numpy(),b.numpy()),speaker_end_to_end=compare(ea,eb)))
    embs=tts.EmbeddingTables(CACHE/"hf");cfg=tts.load_config(CACHE/"hf");tokenizer=tts.load_tokenizer(CACHE/"hf")
    upstream_tokens=json.loads((HF/"tokenizer_config.json").read_text())["added_tokens_decoder"]
    result["special_tokens"]={token:upstream_tokens[str(i)]["content"]==token for token,i in tts.SPECIAL_TOKEN_IDS.items()}
    result["language_ids_equal"]=cfg.language_ids==config["talker_config"]["codec_language_id"]
    result["filterbank"]=compare(librosa.filters.mel(sr=24000,n_fft=1024,n_mels=128,fmin=0,fmax=12000),tts.build_mel_filterbank(24000,1024,128,0,12000))
    with safe_open(HF/"model.safetensors",framework="pt") as reader:
        projection=env["Qwen3TTSTalkerResizeMLP"](2048,2048,1024,"silu",bias=True).eval()
        projection.load_state_dict({k.removeprefix("talker.text_projection."):reader.get_tensor(k).float() for k in reader.keys() if k.startswith("talker.text_projection.")})
        class Talker:
            device=torch.device("cpu");dtype=torch.float32;text_projection=projection
            get_text_embeddings=lambda self:EmbeddingRows(reader,"talker.model.text_embedding.weight")
            get_input_embeddings=lambda self:EmbeddingRows(reader,"talker.model.codec_embedding.weight")
            def generate(self,**kwargs):raise Captured(kwargs)
        harness=SimpleNamespace(config=SimpleNamespace(**{**config,"talker_config":SimpleNamespace(**config["talker_config"])}),talker=Talker())
        harness.generate_speaker_prompt=MethodType(env["generate_speaker_prompt"],harness)
        for text,lang,voice in [("你好，世界。","chinese","zh"),("Hello world.","english","en"),("请不要取消去上海的车票。","chinese","en")]:
            ids=tts.build_prompt_ids(tokenizer,text)
            try:
                env["generate"](harness,input_ids=[torch.tensor([ids])],languages=[lang],voice_clone_prompt=dict(ref_spk_embedding=[torch.from_numpy(refs[voice])],x_vector_only_mode=[True],icl_mode=[False],ref_code=None))
            except Captured as exc:official=exc.values
            class Prefill:
                def run(self,names,feed):raise Captured(feed)
            bundle=SimpleNamespace(sessions={"talker_prefill":Prefill()})
            try:tts.generate_codes(bundle,embs,cfg,ids,refs[voice],lang,top_k=1,seed=0)
            except Captured as exc:
                ours=exc.values
                tb=exc.__traceback__
                while tb.tb_frame.f_code.co_name!="generate_codes":tb=tb.tb_next
                trailing=tb.tb_frame.f_locals["trailing"].copy()
            result["prompts"].append(dict(text=text,language=lang,voice=voice,prefill=compare(official["inputs_embeds"].numpy(),ours["inputs_embeds"]),
                trailing=compare(official["trailing_text_hidden"].numpy()[0],trailing),
                mask_equal=bool(np.array_equal(official["attention_mask"].numpy(),ours["attention_mask"]))))
    (OUT/"upstream-numerics.json").write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=="__main__":main()
