#!/usr/bin/env python3
"""Export the official 12Hz reference encoder to an isolated ONNX candidate."""
import hashlib
import json
from pathlib import Path
import time

import librosa
import numpy as np
import onnx
import onnxruntime as ort
import soundfile as sf
import torch
from torch import nn
from qwen_tts import Qwen3TTSTokenizer

ROOT=Path.home()/"Library/Caches/Auralis/tts/icl"
HF=Path.home()/".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc/speech_tokenizer"
POOL=ROOT.parent/"upstream-fidelity/real-pool"


def sha(path):
    h=hashlib.sha256()
    with Path(path).open("rb") as f:
        for b in iter(lambda:f.read(4194304),b""):h.update(b)
    return h.hexdigest()


class Encoder(nn.Module):
    def __init__(self,encoder):
        super().__init__();self.encoder=encoder
    def forward(self,pcm):
        return self.encoder.encode(input_values=pcm,num_quantizers=16,use_streaming=False,return_dict=False)[0]


class ExplicitMaskTransformer(nn.Module):
    """Supply the same full causal mask through the official supported 4-D input.

    This bypasses the HF vmap mask-construction helper, which is not traceable by
    the legacy ONNX exporter. Attention/MLP/normalization modules stay unchanged.
    """
    def __init__(self,base):
        super().__init__();self.base=base
    def forward(self,hidden_states,**kwargs):
        positions=torch.arange(hidden_states.shape[1],device=hidden_states.device)
        allowed=positions[None,:]<=positions[:,None]
        mask=torch.where(allowed,hidden_states.new_zeros(()),hidden_states.new_full((),torch.finfo(torch.float32).min))[None,None]
        return self.base(hidden_states,attention_mask=mask,**kwargs)


def share_codebook_constants(graph):
    """Move repeated immutable codebooks to shared initializers and shape-only views."""
    shared={};views={};aliases={};nodes=[];moved=0;original_bytes=0
    for node in graph.graph.node:
        tensors=[a.t for a in node.attribute if a.type==onnx.AttributeProto.TENSOR]
        if node.op_type!="Constant" or len(tensors)!=1 or len(tensors[0].raw_data)<=1024:
            nodes.append(node);continue
        tensor=tensors[0];key=(tensor.data_type,hashlib.sha256(tensor.raw_data).hexdigest());shape=tuple(tensor.dims)
        moved+=1;original_bytes+=len(tensor.raw_data)
        if key not in shared:
            name=f"shared_codebook_{len(shared)}";copy=onnx.TensorProto();copy.CopyFrom(tensor);copy.name=name
            graph.graph.initializer.append(copy);shared[key]=(name,shape,len(tensor.raw_data));views[(key,shape)]=name
        if (key,shape) not in views:
            name=f"codebook_view_{len(views)}";shape_name=name+"_shape"
            graph.graph.initializer.append(onnx.helper.make_tensor(shape_name,onnx.TensorProto.INT64,[len(shape)],shape))
            nodes.append(onnx.helper.make_node("Reshape",[shared[key][0],shape_name],[name],name=name))
            views[(key,shape)]=name
        aliases[node.output[0]]=views[(key,shape)]
    for node in nodes:
        for i,value in enumerate(node.input):
            if value in aliases:node.input[i]=aliases[value]
    del graph.graph.node[:];graph.graph.node.extend(nodes)
    return dict(moved_constant_nodes=moved,unique_codebooks=len(shared),before_constant_bytes=original_bytes,
                shared_codebook_bytes=sum(v[2] for v in shared.values()),shape_only_views=len(views)-len(shared))


def main():
    assert ort.__version__=="1.24.2"
    torch.set_num_threads(2);ROOT.mkdir(exist_ok=True)
    model=Qwen3TTSTokenizer.from_pretrained(str(HF),dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    encoder=Encoder(model.model.encoder).eval()
    inputs={}
    for identity in ("121","260"):
        x,sr=sf.read(POOL/f"{identity}-reference.flac",dtype="float32")
        inputs[identity]=librosa.resample(x,orig_sr=sr,target_sr=24000)[None,None]
    source=inputs["121"]
    tests=list(inputs.items())+[(f"length-{n}",np.tile(source,(1,1,3))[...,:n]) for n in (1919,1920,1921,24001,288013)]
    for identity in ("121","260"):
        native=np.load(ROOT.parent/"upstream-fidelity"/f"resampler-evidence-real-{identity}.npz")["pcm_0.9568718266"]
        tests.append((f"native-front-{identity}",native[None,None]))
    result=dict(torch=torch.__version__,onnx=onnx.__version__,ort=ort.__version__,source_sha256=sha(HF/"model.safetensors"),
        script_sha256=sha(__file__),export=dict(opset=18,dynamo=False,num_quantizers=16,batch=1,sample_rate=24000),quantizer_ablation=[],cases=[])
    with torch.inference_mode():
        # Independent references are collected BEFORE adapting the mask helper,
        # including the >250-transformer-frame test and native PCM counterexample.
        expected_inputs={identity:encoder(torch.from_numpy(pcm)).numpy() for identity,pcm in tests}
        model.model.encoder.encoder_transformer=ExplicitMaskTransformer(model.model.encoder.encoder_transformer)
        result["mask_ablation"]=[dict(id=name,exact_codes=bool(np.array_equal(expected_inputs[name],encoder(torch.from_numpy(pcm)).numpy()))) for name,pcm in tests]
        assert all(x["exact_codes"] for x in result["mask_ablation"])
        for identity,pcm in inputs.items():
            original=np.load(ROOT/f"reference-{identity}-codes.npy").T[None]
            direct=encoder(torch.from_numpy(pcm)).numpy()
            equal=bool(np.array_equal(original,direct));assert equal and np.array_equal(expected_inputs[identity],direct)
            result["quantizer_ablation"].append(dict(identity=identity,all32_then_first16_equals_only16=equal,shape=list(direct.shape)))
            np.save(ROOT/f"reference-{identity}-pcm24.npy",pcm)
        target=ROOT/"reference_encoder.onnx"
        torch.onnx.export(encoder,(torch.from_numpy(inputs["121"]),),str(target),dynamo=False,opset_version=18,
            input_names=["pcm"],output_names=["codes"],dynamic_axes={"pcm":{2:"samples"},"codes":{2:"frames"}},do_constant_folding=True)
    graph=onnx.load(str(target));result["constant_storage_ablation"]=share_codebook_constants(graph);data=target.with_suffix(".onnx.data")
    graph.graph.output[0].type.tensor_type.shape.dim[0].ClearField("dim_param")
    graph.graph.output[0].type.tensor_type.shape.dim[0].dim_value=1
    if data.exists():data.unlink()
    onnx.save_model(graph,str(target),save_as_external_data=True,all_tensors_to_one_file=True,location=data.name,size_threshold=1024)
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(target),sess_options=options,providers=["CPUExecutionProvider"])
    with torch.inference_mode():
        for name,pcm in tests:
            expected=expected_inputs[name];start=time.perf_counter();actual=session.run(None,{"pcm":pcm})[0];elapsed=time.perf_counter()-start
            assert actual.shape==expected.shape==(1,16,(pcm.shape[-1]+1919)//1920)
            row=dict(id=name,input_samples=pcm.shape[-1],shape=list(actual.shape),exact_codes=bool(np.array_equal(actual,expected)),
                     code_agreement=float(np.mean(actual==expected)),frame_agreement=float(np.mean(np.all(actual==expected,axis=1))),seconds=elapsed)
            if name.startswith("native-front-"):
                identity=name.removeprefix("native-front-");soxr=np.load(ROOT/f"reference-{identity}-codes.npy").T[None]
                row["versus_soxr_input_codes"]=dict(code_agreement=float(np.mean(actual==soxr)),mismatched_codes=int(np.count_nonzero(actual!=soxr)),frame_agreement=float(np.mean(np.all(actual==soxr,axis=1))))
            result["cases"].append(row);np.savez(ROOT/f"encoder-check-{name}.npz",official=expected,onnx=actual)
            print(json.dumps(row),flush=True)
    result["files"]=[dict(path=str(p),bytes=p.stat().st_size,sha256=sha(p)) for p in (target,data)]
    (ROOT/"encoder-validation.json").write_text(json.dumps(result,indent=2))


if __name__=="__main__":main()
