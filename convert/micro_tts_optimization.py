#!/usr/bin/env python3
"""Exact-input CPU kernel and OrtValue/I/O binding ablation on ORT 1.24.2."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import time

import numpy as np
import onnxruntime as ort


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model",type=Path,required=True)
    p.add_argument("--inputs",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--profile",action="store_true")
    a=p.parse_args()
    assert ort.__version__ == "1.24.2"
    options=ort.SessionOptions()
    options.intra_op_num_threads=4
    options.inter_op_num_threads=1
    options.graph_optimization_level=ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.enable_profiling=a.profile
    options.profile_file_prefix=str(a.output.with_suffix(""))
    session=ort.InferenceSession(str(a.model),sess_options=options,providers=["CPUExecutionProvider"])
    feed=dict(np.load(a.inputs))
    names=[o.name for o in session.get_outputs()]
    reference=session.run(names,feed)
    ov={k:ort.OrtValue.ortvalue_from_numpy(v) for k,v in feed.items()}
    binding=session.io_binding()
    for k,v in ov.items():
        binding.bind_ortvalue_input(k,v)
    for name,value in zip(names,reference):
        binding.bind_ortvalue_output(name,ort.OrtValue.ortvalue_from_shape_and_type(value.shape,value.dtype))
    fns={"numpy":lambda:session.run(names,feed),
         "ortvalue":lambda:session.run_with_ort_values(names,ov),
         "bound":lambda:session.run_with_iobinding(binding)}
    result=dict(model=str(a.model),inputs=str(a.inputs),runtime=ort.__version__,
                load_average=os.getloadavg(),threads=4,input_sha256=hashlib.sha256(a.inputs.read_bytes()).hexdigest(),
                input_bytes=sum(v.nbytes for v in feed.values()),methods={})
    for method,fn in fns.items():
        fn()
        samples=[]
        for _ in range(20):
            t=time.perf_counter()
            fn()
            samples.append(time.perf_counter()-t)
        outputs=fn()
        if method=="ortvalue":
            outputs=[x.numpy() for x in outputs]
        elif method=="bound":
            outputs=binding.copy_outputs_to_cpu()
        result["methods"][method]=dict(median_s=statistics.median(samples),samples_s=samples,
                                       exact=all(np.array_equal(x,y) for x,y in zip(reference,outputs)))
    if a.profile:
        profile=Path(session.end_profiling())
        events=json.loads(profile.read_text())
        cost={}
        for event in events:
            op=event.get("args",{}).get("op_name")
            if op and event.get("cat")=="Node":
                cost[op]=cost.get(op,0)+event.get("dur",0)
        result["profile"]=dict(path=str(profile),operator_microseconds=dict(sorted(cost.items(),key=lambda x:-x[1])))
    a.output.write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))


if __name__=="__main__":
    main()
