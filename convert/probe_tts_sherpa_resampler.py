#!/usr/bin/env python3
"""Compare the already shipped sherpa resampler to official soxr_hq input."""
import ctypes as C
import json
from pathlib import Path
import sys
import time

import librosa
import numpy as np
import soundfile as sf
import torch
from safetensors import safe_open

from verify_tts_upstream import CACHE,OUT,HF,selected_definitions,compare,sha


class ResampleOut(C.Structure):
    _fields_=[("samples",C.POINTER(C.c_float)),("n",C.c_int32)]


def main():
    libpath=CACHE.parent/"asr/venv/lib/python3.12/site-packages/sherpa_onnx/lib/libsherpa-onnx-c-api.dylib"
    lib=C.CDLL(str(libpath))
    create=lib.SherpaOnnxCreateLinearResampler;create.argtypes=[C.c_int32,C.c_int32,C.c_float,C.c_int32];create.restype=C.c_void_p
    destroy=lib.SherpaOnnxDestroyLinearResampler;destroy.argtypes=[C.c_void_p];destroy.restype=None
    run=lib.SherpaOnnxLinearResamplerResample;run.argtypes=[C.c_void_p,C.POINTER(C.c_float),C.c_int32,C.c_int32];run.restype=C.POINTER(ResampleOut)
    free=lib.SherpaOnnxLinearResamplerResampleFree;free.argtypes=[C.POINTER(ResampleOut)];free.restype=None
    def resample(pcm,sr,width,cutoff=0.0):
        handle=create(sr,24000,cutoff,width);assert handle
        try:
            pcm=np.ascontiguousarray(pcm,dtype=np.float32)
            chunk=run(handle,pcm.ctypes.data_as(C.POINTER(C.c_float)),len(pcm),1);assert chunk
            try:return np.ctypeslib.as_array(chunk.contents.samples,shape=(chunk.contents.n,)).copy()
            finally:free(chunk)
        finally:destroy(handle)
    if "--fit-impulse" in sys.argv:
        tests=[]
        for sr in (16000,44100,48000):
            x=np.zeros(1001,dtype=np.float32);x[500]=1
            tests.append((sr,x,librosa.resample(x,orig_sr=sr,target_sr=24000)))
        records=[]
        for width in (16,32,64,128):
            for fraction in [milli/1000 for milli in range(900,991,2)]+[0.9568718266]:
                errors=[]
                for sr,x,target in tests:
                    actual=resample(x,sr,width,fraction*.5*min(sr,24000))
                    assert len(actual)==len(target)
                    errors.append(float(np.sum((actual-target)**2)/np.sum(target**2)))
                records.append(dict(num_zeros=width,cutoff_nyquist_fraction=fraction,relative_mse=errors,mean_relative_mse=float(np.mean(errors))))
        records.sort(key=lambda x:x["mean_relative_mse"])
        (OUT/"resampler-impulse-fit.json").write_text(json.dumps(dict(selection="Minimize equally weighted central-impulse relative MSE at 16k, 44.1k and 48k; no speaker output used for selection",records=records),indent=2))
        print(json.dumps(records[:6],indent=2));return
    torch.set_num_threads(2)
    env,_,_=selected_definitions();config=json.loads((HF/"config.json").read_text())
    speaker=env["Qwen3TTSSpeakerEncoder"](env["Qwen3TTSSpeakerEncoderConfig"](**config["speaker_encoder_config"])).eval()
    with safe_open(HF/"model.safetensors",framework="pt") as reader:
        speaker.load_state_dict({k.removeprefix("speaker_encoder."):reader.get_tensor(k).float() for k in reader.keys() if k.startswith("speaker_encoder.")})
    def mel(x):return env["mel_spectrogram"](torch.from_numpy(x)[None],1024,128,24000,256,1024,0,12000).transpose(1,2)
    if "--validate-fit" in sys.argv:
        inputs=[]
        for voice in ("zh","en"):
            x,_=sf.read(CACHE/f"ref_{voice}.wav",dtype="float32")
            for sr in (16000,44100,48000):inputs.append((f"say-{voice}-{sr}",sr,librosa.resample(x,orig_sr=24000,target_sr=sr)))
        pool=json.loads((OUT/"real-pool/manifest.json").read_text())
        for item in pool["speakers"]:
            x,sr=sf.read(item["recordings"]["reference"]["path"],dtype="float32")
            inputs.append((f"real-{item['id']}",sr,x))
        records=[]
        for identity,sr,pcm in inputs:
            target=librosa.resample(pcm,orig_sr=sr,target_sr=24000)
            arrays=dict(input_pcm=pcm,official_pcm=target)
            for fraction in (.99,.95,.956,.9568718266,.957,.96):
                output=resample(pcm,sr,64,fraction*.5*min(sr,24000));n=min(len(output),len(target))
                with torch.inference_mode():
                    a,b=mel(target[:n]),mel(output[:n]);ea,eb=speaker(a).numpy(),speaker(b).numpy()
                records.append(dict(id=identity,input_rate=sr,cutoff_nyquist_fraction=fraction,num_zeros=64,
                    pcm=compare(target[:n],output[:n]),mel=compare(a.numpy(),b.numpy()),embedding=compare(ea,eb)))
                arrays[f"pcm_{fraction}"]=output;arrays[f"embedding_{fraction}"]=eb;arrays["official_embedding"]=ea
            np.savez(OUT/f"resampler-evidence-{identity}.npz",**arrays)
        (OUT/"resampler-cutoff-validation.json").write_text(json.dumps(dict(script_sha256=sha(__file__),num_zeros=64,selection="Independent impulse fit; .95/.96 neighbors and .99 default retained",cases=records),indent=2))
        print(json.dumps(records,indent=2));return
    result=dict(library=str(libpath),library_sha256=sha(libpath),script_sha256=sha(__file__),
                cut_off="0=>0.99*0.5*min(in,out)",widths=[6,16,32,64,128],flush=1,
                librosa=librosa.__version__,soxr=__import__("soxr").__version__,res_type="soxr_hq",cases=[],tones=[],impulses=[])
    for voice in ("zh","en"):
        x,sr=sf.read(CACHE/f"ref_{voice}.wav",dtype="float32");assert sr==24000
        for input_sr in (16000,44100,48000):
            pcm=librosa.resample(x,orig_sr=24000,target_sr=input_sr)
            target=librosa.resample(pcm,orig_sr=input_sr,target_sr=24000)
            for width in result["widths"]:
                start=time.perf_counter();output=resample(pcm,input_sr,width);elapsed=time.perf_counter()-start
                n=min(len(output),len(target))
                with torch.inference_mode():
                    a,b=mel(target[:n]),mel(output[:n]);ea,eb=speaker(a).numpy(),speaker(b).numpy()
                result["cases"].append(dict(voice=voice,input_rate=input_sr,num_zeros=width,seconds=elapsed,
                    target_samples=len(target),output_samples=len(output),pcm=compare(target[:n],output[:n]),
                    interior_pcm=compare(target[240:-240],output[240:-240]),
                    mel=compare(a.numpy(),b.numpy()),embedding=compare(ea,eb)))
    for freq in (1000,15000):
        x=(.2*np.sin(2*np.pi*freq*np.arange(48000)/48000)).astype(np.float32)
        for width in result["widths"]:
            y=resample(x,48000,width)[200:-200]
            gain=20*np.log10(np.sqrt(np.mean(y.astype(np.float64)**2))/(.2/np.sqrt(2)))
            result["tones"].append(dict(input_rate=48000,output_rate=24000,tone_hz=freq,num_zeros=width,gain_db=float(gain)))
    for sr in (16000,44100,48000):
        for position in (0,500,1000):
            x=np.zeros(1001,dtype=np.float32);x[position]=1
            target=librosa.resample(x,orig_sr=sr,target_sr=24000)
            for width in result["widths"]:
                y=resample(x,sr,width);n=min(len(y),len(target))
                result["impulses"].append(dict(input_rate=sr,position=position,num_zeros=width,
                    target_samples=len(target),output_samples=len(y),target_peak=int(np.argmax(np.abs(target))),
                    output_peak=int(np.argmax(np.abs(y))),comparison=compare(target[:n],y[:n])))
    (OUT/"sherpa-resampler-widths.json").write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))


if __name__=="__main__":main()
