#!/usr/bin/env python3
"""Inspect the preserved native-PCM VQ boundary without altering its decisions."""
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from qwen_tts import Qwen3TTSTokenizer


def main():
    root=Path.home()/"Library/Caches/Auralis/tts/icl"
    hf=Path.home()/".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc/speech_tokenizer"
    torch.set_num_threads(2)
    tokenizer=Qwen3TTSTokenizer.from_pretrained(str(hf),dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    book=tokenizer.model.encoder.quantizer.acoustic_residual_vector_quantizer.layers[3].codebook
    original=book.quantize;captured={}
    def capture(x):
        captured["residual"]=x.detach().numpy().copy();captured["centroids"]=book.embed.detach().numpy().copy()
        captured["distances"]=torch.cdist(x[None].float(),book.embed[None].float(),p=2)[0].detach().numpy()
        return original(x)
    book.quantize=capture
    pcm=np.load(root.parent/"upstream-fidelity/resampler-evidence-real-121.npz")["pcm_0.9568718266"][None,None]
    with torch.inference_mode():tokenizer.model.encoder.encode(torch.from_numpy(pcm),num_quantizers=16,return_dict=True)
    delattr(book,"quantize")
    graph=onnx.load(root/"reference_encoder.onnx",load_external_data=False)
    argmin=[n for n in graph.graph.node if n.op_type=="ArgMin"][4]
    by_output={value:node for node in graph.graph.node for value in node.output}
    distance=argmin.input[0];wire=distance
    while by_output[wire].op_type!="MatMul":wire=by_output[wire].input[0]
    packed=by_output[wire].input[0]
    graph.graph.output.extend([onnx.helper.make_tensor_value_info(distance,onnx.TensorProto.FLOAT,[None,2048]),
                               onnx.helper.make_tensor_value_info(packed,onnx.TensorProto.FLOAT,[1,None,258])])
    path=root/"encoder-distance-debug.onnx";onnx.save(graph,path)
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(path),sess_options=options,providers=["CPUExecutionProvider"])
    codes,distances,packed_value=session.run(None,{"pcm":pcm})
    ort_residual=packed_value[0,:,:256]/-2
    frame=103
    def nearest(d):
        ids=np.argsort(d)[:2]
        return dict(ids=ids.tolist(),distances=d[ids].tolist(),gap=float(d[ids[1]]-d[ids[0]]),gap_in_float32_ulps=float((d[ids[1]]-d[ids[0]])/np.spacing(np.float32(d[ids[0]]))))
    expected_codes=np.load(root/"encoder-check-native-front-121.npz")["onnx"]
    result=dict(frame=frame,group=4,debug_codes_equal_original_graph=bool(np.array_equal(codes,expected_codes)),
        residual_max_abs=float(np.max(np.abs(ort_residual-captured["residual"]))),
        frame_residual_max_abs=float(np.max(np.abs(ort_residual[frame]-captured["residual"][frame]))),
        frame_distance_max_abs=float(np.max(np.abs(distances[frame]-captured["distances"][frame]))),
        torch_nearest=nearest(captured["distances"][frame]),ort_nearest=nearest(distances[frame]))
    for name,residual in (("torch",captured["residual"]),("ort",ort_residual)):
        exact=np.sqrt(np.sum((captured["centroids"].astype(np.float64)-residual[frame].astype(np.float64))**2,axis=1))
        result[name+"_residual_float64_distances"]=nearest(exact)
    np.savez(root/"vq-boundary-evidence.npz",torch_residual=captured["residual"],ort_residual=ort_residual,torch_distances=captured["distances"],ort_distances=distances)
    (root/"vq-boundary.json").write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))


if __name__=="__main__":main()
