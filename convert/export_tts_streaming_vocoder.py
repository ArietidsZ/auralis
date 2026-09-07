#!/usr/bin/env python3
"""Isolated state-tensor export of the official Qwen 12Hz decoder.

Weights and all convolution/MLP operations are unchanged. Transformer cache
updates and masks are expressed as tensors instead of a Python DynamicCache.
"""
import gc
import hashlib
import json
import math
from pathlib import Path
import time

import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn
from qwen_tts import Qwen3TTSTokenizer
from qwen_tts.core.tokenizer_12hz.modeling_qwen3_tts_tokenizer_v2 import (
    Qwen3TTSTokenizerV2CausalConvNet, Qwen3TTSTokenizerV2CausalTransConvNet,
    apply_rotary_pos_emb,
)

ROOT=Path.home()/"Library/Caches/Auralis/tts/upstream-fidelity"
HF=Path.home()/".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc/speech_tokenizer"
OUT=ROOT/"streaming-onnx"


def sha(path):
    h=hashlib.sha256()
    with Path(path).open("rb") as f:
        for b in iter(lambda:f.read(4*1024*1024),b""):h.update(b)
    return h.hexdigest()


class Context:
    pass


class ConvState(nn.Module):
    def __init__(self,module,context,offset):
        super().__init__();self.op=module.conv;self.ctx=context;self.offset=offset
        self.history=module.padding;self.channels=self.op.in_channels
        assert module.stride==1
    def forward(self,x):
        if not self.history:return self.op(x).contiguous()
        n=self.channels*self.history
        past=self.ctx.conv[self.offset:self.offset+n].reshape(1,self.channels,self.history)
        joined=torch.cat([past,x],dim=-1)
        self.ctx.next_conv[self.offset]=joined[...,-self.history:].reshape(-1)
        return self.op(joined).contiguous()


class TransposeState(nn.Module):
    def __init__(self,module,context,offset):
        super().__init__();self.op=module.conv;self.ctx=context;self.offset=offset
        self.stride=self.op.stride[0];self.right_pad=module.right_pad
        self.history=math.ceil(self.right_pad/self.stride);self.channels=self.op.in_channels
    def forward(self,x):
        if self.history:
            n=self.channels*self.history
            past=self.ctx.conv[self.offset:self.offset+n].reshape(1,self.channels,self.history)
            x=torch.cat([past,x],dim=-1)
            self.ctx.next_conv[self.offset]=x[...,-self.history:].reshape(-1)
        wave=self.op(x)
        if self.right_pad:wave=wave[...,:-self.right_pad]
        return wave[...,self.history*self.stride:].contiguous()


class ExplicitTransformer(nn.Module):
    def __init__(self,base,context):
        super().__init__();self.base=base;self.ctx=context
    def forward(self,inputs_embeds):
        x=self.base.input_proj(inputs_embeds)
        frames=x.shape[1]
        position=self.ctx.position
        query_positions=torch.arange(frames,device=x.device)+position[0]
        key_positions=torch.arange(frames+71,device=x.device)+position[0]-71
        allowed=(key_positions[None,:]>=0)&(key_positions[None,:]<=query_positions[:,None])&(key_positions[None,:]>query_positions[:,None]-72)
        mask=torch.where(allowed,x.new_zeros(()),x.new_full((),torch.finfo(torch.float32).min))[None,None]
        freq=query_positions.float()[:,None]*self.base.rotary_emb.inv_freq[None,:]
        emb=torch.cat([freq,freq],dim=-1)[None]
        cos=emb.cos()*self.base.rotary_emb.attention_scaling
        sin=emb.sin()*self.base.rotary_emb.attention_scaling
        next_keys=[];next_values=[]
        for i,layer in enumerate(self.base.layers):
            residual=x;norm=layer.input_layernorm(x);attn=layer.self_attn
            q=attn.q_norm(attn.q_proj(norm).view(1,-1,16,64)).transpose(1,2)
            k=attn.k_norm(attn.k_proj(norm).view(1,-1,16,64)).transpose(1,2)
            v=attn.v_proj(norm).view(1,-1,16,64).transpose(1,2)
            q,k=apply_rotary_pos_emb(q,k,cos,sin)
            k=torch.cat([self.ctx.keys[i],k],dim=2);v=torch.cat([self.ctx.values[i],v],dim=2)
            next_keys.append(k[:,:,-71:,:]);next_values.append(v[:,:,-71:,:])
            weights=torch.softmax(torch.matmul(q,k.transpose(2,3))*attn.scaling+mask,dim=-1)
            hidden=torch.matmul(weights,v).transpose(1,2).contiguous().view(1,-1,1024)
            x=residual+layer.self_attn_layer_scale(attn.o_proj(hidden))
            x=x+layer.mlp_layer_scale(layer.mlp(layer.post_attention_layernorm(x)))
        self.ctx.next_keys=torch.stack(next_keys);self.ctx.next_values=torch.stack(next_values)
        return type("Output",(),{"last_hidden_state":self.base.output_proj(self.base.norm(x))})()


class StreamingVocoder(nn.Module):
    def __init__(self,decoder):
        super().__init__();self.decoder=decoder;self.ctx=Context();self.state_layout=[];offset=0
        originals=list(decoder.named_modules())
        for name,module in originals:
            if isinstance(module,Qwen3TTSTokenizerV2CausalConvNet):
                replacement=ConvState(module,self.ctx,offset)
            elif isinstance(module,Qwen3TTSTokenizerV2CausalTransConvNet):
                replacement=TransposeState(module,self.ctx,offset)
            else:continue
            count=replacement.channels*replacement.history
            self.state_layout.append(dict(name=name,offset=offset,elements=count,shape=[1,replacement.channels,replacement.history],dtype="float32"))
            offset+=count
            parent_name,_,child_name=name.rpartition(".")
            parent=decoder.get_submodule(parent_name) if parent_name else decoder
            setattr(parent,child_name,replacement)
        self.conv_elements=offset
        decoder.pre_transformer=ExplicitTransformer(decoder.pre_transformer,self.ctx)
    def forward(self,codes,conv_state,past_keys,past_values,position):
        self.ctx.conv=conv_state;self.ctx.keys=past_keys;self.ctx.values=past_values;self.ctx.position=position
        self.ctx.next_conv={}
        waveform=self.decoder(codes)
        conv=torch.cat([self.ctx.next_conv[item["offset"]] for item in self.state_layout if item["elements"]])
        return waveform,conv,self.ctx.next_keys,self.ctx.next_values,position+codes.shape[-1]
    def empty(self):
        return (torch.zeros(self.conv_elements),torch.zeros(8,1,16,71,64),torch.zeros(8,1,16,71,64),torch.zeros(1,dtype=torch.int64))
    def clear(self):
        self.ctx.__dict__.clear()


def difference(x,y):
    d=x.astype(np.float64)-y.astype(np.float64)
    return dict(max_abs=float(np.abs(d).max()),rms=float(np.sqrt(np.mean(d*d))))


def main():
    assert ort.__version__=="1.24.2",ort.__version__
    OUT.mkdir(exist_ok=True);torch.set_num_threads(2)
    tokenizer=Qwen3TTSTokenizer.from_pretrained(str(HF),dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    codes=np.tile(np.load(ROOT/"streaming-reference-codes.npy"),(1,1,3))
    decoder=tokenizer.model.decoder.eval()
    with torch.inference_mode():reference=decoder(torch.from_numpy(codes)).numpy().reshape(-1)
    model=StreamingVocoder(decoder).eval()
    result=dict(ort=ort.__version__,torch=torch.__version__,onnx=onnx.__version__,source_model_sha256=sha(HF/"model.safetensors"),
                script_sha256=sha(__file__),conv_state_layout=model.state_layout,conv_elements=model.conv_elements,
                states=dict(conv_state=[model.conv_elements],past_keys=[8,1,16,71,64],past_values=[8,1,16,71,64],position=[1]),
                eager=[],ort_cases=[])
    with torch.inference_mode():
        for chunk in (1,4,8,13):
            state=model.empty();parts=[]
            for start in range(0,codes.shape[-1],chunk):
                output=model(torch.from_numpy(codes[...,start:start+chunk]),*state);parts.append(output[0].numpy().reshape(-1));state=output[1:]
            row=dict(chunk_frames=chunk,**difference(np.concatenate(parts),reference));result["eager"].append(row);print("eager",row,flush=True)
            assert row["max_abs"]<1e-4 and row["rms"]<1e-5
        model.clear()
        target=OUT/"vocoder_streaming.onnx"
        torch.onnx.export(model,(torch.from_numpy(codes[...,:4]),*model.empty()),str(target),
            dynamo=False,opset_version=18,input_names=["codes","conv_state","past_keys","past_values","position"],
            output_names=["waveform","conv_state_out","present_keys","present_values","position_out"],
            dynamic_axes={"codes":{2:"frames"},"waveform":{2:"samples"}},do_constant_folding=True)
        model.clear()
    loaded=onnx.load(str(target))
    fixed_outputs={"conv_state_out":[result["conv_elements"]],"present_keys":[8,1,16,71,64],"present_values":[8,1,16,71,64]}
    for output in loaded.graph.output:
        if output.name in fixed_outputs:
            for dim,value in zip(output.type.tensor_type.shape.dim,fixed_outputs[output.name]):
                dim.ClearField("dim_param");dim.dim_value=value
    data_path=target.with_suffix(".onnx.data")
    if data_path.exists():data_path.unlink()
    onnx.save_model(loaded,str(target),save_as_external_data=True,all_tensors_to_one_file=True,location="vocoder_streaming.onnx.data",size_threshold=1024)
    del loaded,tokenizer,decoder,model;gc.collect()
    options=ort.SessionOptions();options.intra_op_num_threads=4;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(target),sess_options=options,providers=["CPUExecutionProvider"])
    def empty():return [np.zeros(result["conv_elements"],np.float32),np.zeros((8,1,16,71,64),np.float32),np.zeros((8,1,16,71,64),np.float32),np.zeros(1,np.int64)]
    for chunk in (1,4,8,13):
        state=empty();parts=[];start_time=time.perf_counter();per_step=[]
        for start in range(0,codes.shape[-1],chunk):
            count=min(chunk,codes.shape[-1]-start)
            out=session.run(None,dict(zip(["codes","conv_state","past_keys","past_values","position"],[codes[...,start:start+chunk],*state])))
            assert out[0].shape[-1]==count*1920
            parts.append(out[0].reshape(-1));state=out[1:];per_step.append(dict(position=int(state[-1][0]),valid_samples=len(parts[-1]),state_bytes=sum(x.nbytes for x in state)))
        actual=np.concatenate(parts)
        row=dict(chunk_frames=chunk,frames=codes.shape[-1],samples=len(actual),seconds=time.perf_counter()-start_time,
                 final_position=int(state[-1][0]),flush_extra_samples=0,state_bytes=sum(x.nbytes for x in state),**difference(actual,reference))
        result["ort_cases"].append(row);print("ort",row,flush=True)
        np.save(OUT/f"pcm-chunk{chunk}.npy",actual)
        assert row["max_abs"]<1e-4 and row["rms"]<1e-5
    # Cancellation/reset: discard a partially progressed state; fresh state must reproduce frame zero.
    initial=empty();feed=dict(zip(["codes","conv_state","past_keys","past_values","position"],[codes[...,:4],*initial]))
    first=session.run(None,feed)
    cancelled=first[1:];del cancelled,first
    reset=session.run(None,feed)[0].reshape(-1)
    result["cancel_reset"]=difference(reset,reference[:4*1920])
    result["artifacts"]=[dict(path=str(p),bytes=p.stat().st_size,sha256=sha(p)) for p in (target,target.with_suffix(".onnx.data"))]
    result["protocol"]=dict(initial="All states zero; position=0",valid_pcm="Every step commits exactly F*1920 samples at 24 kHz",flush="No extra PCM on EOF; do not call with F=0; final short positive chunk is normal",cancel="Discard states; reset to zero for a new utterance; graph has no hidden mutable state",position="Absolute number of consumed codec frames, int64[1]")
    (OUT/"validation.json").write_text(json.dumps(result,indent=2))
    del session;gc.collect()


if __name__=="__main__":main()
