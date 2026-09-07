#!/usr/bin/env python3
"""Independent ORT 1.24.2 long-window, cancellation, and lifecycle validation."""
import gc
import hashlib
import json
import os
from pathlib import Path
import threading
import time
import weakref

import numpy as np
import onnxruntime as ort
import psutil


def main():
    assert ort.__version__=="1.24.2"
    cache=Path.home()/"Library/Caches/Auralis/tts";root=cache/"upstream-fidelity/streaming-onnx"
    codes=np.tile(np.load(cache/"upstream-fidelity/streaming-reference-codes.npy"),(1,1,4))
    config=json.loads((root/"validation.json").read_text());count=config["conv_elements"]
    options=ort.SessionOptions();options.intra_op_num_threads=4;options.inter_op_num_threads=1
    full=ort.InferenceSession(str(cache/"hf/vocoder.onnx"),sess_options=options,providers=["CPUExecutionProvider"])
    reference=full.run(None,{"codes":codes})[0].reshape(-1);del full;gc.collect()
    proc=psutil.Process();before=proc.memory_info().rss
    session=ort.InferenceSession(str(root/"vocoder_streaming.onnx"),sess_options=options,providers=["CPUExecutionProvider"])
    def empty():return [np.zeros(count,np.float32),np.zeros((8,1,16,71,64),np.float32),np.zeros((8,1,16,71,64),np.float32),np.zeros(1,np.int64)]
    def feed(x,state):return dict(zip(["codes","conv_state","past_keys","past_values","position"],[x,*state]))
    def compare(x,y):
        d=x.astype(np.float64)-y.astype(np.float64)
        return dict(max_abs=float(np.abs(d).max()),rms=float(np.sqrt(np.mean(d*d))))
    result=dict(runtime=ort.__version__,frames=codes.shape[-1],seconds_of_audio=reference.size/24000,
                frames_past_72_window=codes.shape[-1]-72,load_average=os.getloadavg(),cases=[],rss_before=before)
    for chunk in (1,4,8,13):
        state=empty();parts=[];sizes=[]
        for start in range(0,codes.shape[-1],chunk):
            out=session.run(None,feed(codes[...,start:start+chunk],state))
            expected=min(chunk,codes.shape[-1]-start)*1920
            assert out[0].size==expected
            state=out[1:];parts.append(out[0].reshape(-1));sizes.append(sum(x.nbytes for x in state))
        actual=np.concatenate(parts);comparison=compare(actual,reference)
        assert comparison["max_abs"]<=1e-4 and comparison["rms"]<=1e-5
        assert min(sizes)==max(sizes)==5193992 and int(state[-1][0])==codes.shape[-1]
        result["cases"].append(dict(chunk=chunk,state_bytes=max(sizes),final_position=int(state[-1][0]),
            last_step_valid_samples=parts[-1].size,tail_flush_extra_samples=0,**comparison))
    run_options=ort.RunOptions();started=threading.Event();cancel_result={}
    def worker():
        started.set()
        try:session.run(None,feed(codes,empty()),run_options)
        except Exception as exc:cancel_result["exception"]=str(exc)
        else:cancel_result["completed_before_cancel"]=True
    thread=threading.Thread(target=worker);thread.start();started.wait();time.sleep(.01)
    cancel_start=time.perf_counter();run_options.terminate=True;thread.join(timeout=10)
    assert not thread.is_alive()
    cancel_result["seconds_after_request"]=time.perf_counter()-cancel_start
    assert "terminate" in cancel_result.get("exception","").lower(),cancel_result
    run_options.terminate=False
    reset=session.run(None,feed(codes[...,:4],empty()),run_options)[0].reshape(-1)
    result["cancellation"]=cancel_result;result["reset"]=compare(reset,reference[:4*1920])
    result["rss_live"]=proc.memory_info().rss
    handle=weakref.ref(session)
    del state,parts,out,actual,reset,session;gc.collect()
    result["session_wrapper_released"]=handle() is None
    result["rss_after_release"]=proc.memory_info().rss
    assert result["session_wrapper_released"]
    result["script_sha256"]=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    np.save(root/"long-reference-codes.npy",codes);np.save(root/"long-reference-pcm.npy",reference)
    (root/"long-validation.json").write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))


if __name__=="__main__":main()
