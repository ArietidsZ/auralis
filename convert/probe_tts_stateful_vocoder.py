#!/usr/bin/env python3
"""Experimental PyTorch state-carrying decoder; no shipping graph/interface changes.

Uses official module weights/operators, retaining causal-convolution inputs and
transformer KV. This proves state requirements before designing ONNX tensor I/O.
"""
import json
import math
import gc
from pathlib import Path
from types import MethodType

import numpy as np
import torch
from qwen_tts import Qwen3TTSTokenizer
from qwen_tts.core.tokenizer_12hz.modeling_qwen3_tts_tokenizer_v2 import (
    Qwen3TTSTokenizerV2CausalConvNet, Qwen3TTSTokenizerV2CausalTransConvNet,
)


def main():
    root=Path.home()/"Library/Caches/Auralis/tts/upstream-fidelity"
    model=Path.home()/".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc/speech_tokenizer"
    torch.set_num_threads(2)
    tokenizer=Qwen3TTSTokenizer.from_pretrained(str(model),torch_dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    decoder=tokenizer.model.decoder.eval()
    # Repetition is only a state-boundary/window test, never a voice-quality case.
    codes=torch.from_numpy(np.tile(np.load(root/"streaming-reference-codes.npy"),(1,1,3)))
    with torch.inference_mode():reference=decoder(codes).numpy().reshape(-1)
    states={};descriptions=[]
    for name,module in decoder.named_modules():
        if isinstance(module,Qwen3TTSTokenizerV2CausalConvNet):
            assert module.stride==1,(name,module.stride)
            def conv(self,x,key=name):
                padding=self.padding
                past=states.get(key)
                if past is None:past=x.new_zeros(x.shape[0],x.shape[1],padding)
                joined=torch.cat([past,x],dim=-1)
                if padding:states[key]=joined[...,-padding:].clone()
                return self.conv(joined).contiguous()
            module.forward=MethodType(conv,module)
            descriptions.append(dict(name=name,type="causal_conv",channels=module.conv.in_channels,history=module.padding))
        elif isinstance(module,Qwen3TTSTokenizerV2CausalTransConvNet):
            original=module.forward
            history=math.ceil(module.right_pad/module.conv.stride[0])
            def trans(self,x,key=name,fn=original,history=history):
                if not history:return fn(x)
                past=states.get(key)
                joined=x if past is None else torch.cat([past,x],dim=-1)
                offset=0 if past is None else past.shape[-1]*self.conv.stride[0]
                states[key]=joined[...,-history:].clone()
                return fn(joined)[...,offset:]
            module.forward=MethodType(trans,module)
            descriptions.append(dict(name=name,type="causal_transpose_conv",channels=module.conv.in_channels,history=history))
    transformer=decoder.pre_transformer
    original_transformer=transformer.forward
    def transformer_forward(self,inputs_embeds,**kwargs):
        seen=states.get("seen",0)
        position=torch.arange(seen,seen+inputs_embeds.shape[1],device=inputs_embeds.device)
        output=original_transformer(inputs_embeds=inputs_embeds,past_key_values=states.get("kv"),
                                    use_cache=True,cache_position=position,position_ids=position[None])
        states["seen"]=seen+inputs_embeds.shape[1];states["kv"]=output.past_key_values
        return output
    transformer.forward=MethodType(transformer_forward,transformer)
    result=dict(frames=codes.shape[-1],synthetic_repetition_for_state_test=True,modules=descriptions,
                transformer=dict(layers=8,kv_heads=16,head_dim=64,sliding_window=72),cases=[])
    for chunk in (1,4,8,13):
        states.clear();pieces=[]
        with torch.inference_mode():
            for start in range(0,codes.shape[-1],chunk):pieces.append(decoder(codes[...,start:start+chunk]).numpy().reshape(-1))
        actual=np.concatenate(pieces);delta=actual.astype(np.float64)-reference.astype(np.float64)
        kv=states["kv"]
        kv_shapes=[dict(keys=list(layer.keys.shape),values=list(layer.values.shape)) for layer in kv.layers]
        row=dict(chunk_frames=chunk,samples=len(actual),max_abs=float(np.abs(delta).max()),rms=float(np.sqrt(np.mean(delta**2))),
                 convolution_state_bytes=sum(v.numel()*v.element_size() for v in states.values() if isinstance(v,torch.Tensor)),
                 kv_shapes=kv_shapes,kv_bytes=sum(layer.keys.numel()*layer.keys.element_size()+layer.values.numel()*layer.values.element_size() for layer in kv.layers))
        result["cases"].append(row);print(json.dumps(row),flush=True)
        (root/"stateful-vocoder-proof.json").write_text(json.dumps(result,indent=2))
    # Remove experiment-bound methods before native module/thread-pool teardown.
    for module in decoder.modules():
        if isinstance(module,(Qwen3TTSTokenizerV2CausalConvNet,Qwen3TTSTokenizerV2CausalTransConvNet)):
            delattr(module,"forward")
    delattr(transformer,"forward")
    states.clear()
    gc.collect()


if __name__=="__main__":main()
