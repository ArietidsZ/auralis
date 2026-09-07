#!/usr/bin/env python3
"""Run one official full-model greedy case to diagnose a host protocol failure."""
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import torch
from qwen_tts import Qwen3TTSModel


def main():
    root=Path.home()/"Library/Caches/Auralis/tts/upstream-fidelity"
    hf=Path.home()/".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc"
    torch.set_num_threads(2)
    torch.manual_seed(0)
    model=Qwen3TTSModel.from_pretrained(str(hf),dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    text="请不要取消明天去上海的火车票。"
    prompt=model.create_voice_clone_prompt(ref_audio=str(root/"real-pool/121-reference.flac"),x_vector_only_mode=True)
    voice=model._prompt_items_to_voice_clone_prompt(prompt)
    ids=model._tokenize_texts([model._build_assistant_text(text)])
    original=model.model.talker.generate
    captured={}
    def record(**kwargs):
        output=original(**kwargs)
        captured["sequence"]=output.sequences[0].tolist()
        return output
    model.model.talker.generate=record
    start=time.perf_counter()
    with torch.inference_mode():
        codes,_=model.model.generate(input_ids=ids,languages=["Chinese"],voice_clone_prompt=voice,
            non_streaming_mode=False,max_new_tokens=384,do_sample=False,subtalker_dosample=False,repetition_penalty=1.05)
    seconds=time.perf_counter()-start
    delattr(model.model.talker,"generate")
    sequence=captured["sequence"];eos=model.model.config.talker_config.codec_eos_token_id
    result=dict(text=text,voice="121",hf_revision=hf.name,torch=torch.__version__,dtype="float32",attention="eager",
        max_new_tokens=384,do_sample=False,subtalker_dosample=False,repetition_penalty=1.05,
        prompt_ids=ids[0].tolist(),sequence=sequence,eos_token=eos,terminated=eos in sequence,
        frames=int(codes[0].shape[0]),seconds=seconds,script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    np.save(root/"official-121-chinese-codes.npy",codes[0].cpu().numpy())
    (root/"official-generation-121-chinese.json").write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=="__main__":main()
