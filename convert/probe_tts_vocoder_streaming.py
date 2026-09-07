#!/usr/bin/env python3
"""Prefix/chunk stability of the unchanged vocoder on identical real codes.

Predeclared numerical stability criterion: max abs <= 1e-4 AND RMS <= 1e-5
against full-utterance PCM, before clipping/PCM16 conversion. This is not a
perceptual equivalence or a first-audio latency claim.
"""
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import onnxruntime as ort


def error(a,b):
    delta=a.astype(np.float64)-b.astype(np.float64)
    peak=float(np.abs(delta).max());rms=float(np.sqrt(np.mean(delta*delta)))
    return dict(max_abs=peak,rms=rms,stable=peak<=1e-4 and rms<=1e-5)


def main():
    cache=Path.home()/"Library/Caches/Auralis/tts"
    out=cache/"upstream-fidelity"
    path=cache/"optimization/baseline-full/vocoder-inputs.npz"
    codes=np.load(path)["codes"]
    opts=ort.SessionOptions();opts.intra_op_num_threads=4;opts.inter_op_num_threads=1
    session=ort.InferenceSession(str(cache/"hf/vocoder.onnx"),sess_options=opts,providers=["CPUExecutionProvider"])
    def run(x):
        t=time.perf_counter();wave=session.run(None,{"codes":x})[0].reshape(-1)
        return wave,time.perf_counter()-t
    full,full_s=run(codes);frames=codes.shape[-1];spf=1920
    result=dict(runtime=ort.__version__,threads=4,frames=frames,source=str(path),
                source_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                criterion=dict(max_abs=1e-4,rms=1e-5),full_seconds=full_s,prefixes=[],chunks=[])
    for count in sorted(set([4,8,12,16,20,24,frames])):
        if count>frames:continue
        wave,elapsed=run(codes[:,:,:count])
        row=dict(input_frames=count,seconds=elapsed,whole=error(wave,full[:len(wave)]),crop=[])
        for lookahead in (0,1,2,4,8,12):
            end=(count-lookahead)*spf
            if end>0:row["crop"].append(dict(right_context_frames=lookahead,committable_samples=end,**error(wave[:end],full[:end])))
        result["prefixes"].append(row)
    core_start,core_end=12,16
    for left in (0,2,4,8,12):
        for right in (0,2,4,8):
            start,end=core_start-left,core_end+right
            if end>frames:continue
            wave,elapsed=run(codes[:,:,start:end])
            core=wave[left*spf:(left+core_end-core_start)*spf]
            result["chunks"].append(dict(left_context_frames=left,right_context_frames=right,seconds=elapsed,
                                          **error(core,full[core_start*spf:core_end*spf])))
    np.save(out/"streaming-reference-codes.npy",codes)
    np.save(out/"streaming-full-pcm.npy",full)
    (out/"vocoder-stability.json").write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))


if __name__=="__main__":main()
