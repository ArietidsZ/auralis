#!/usr/bin/env python3
"""Numerically compare frozen before/after runner penalties to official HF code."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

import numpy as np
import torch
from transformers.generation.logits_process import RepetitionPenaltyLogitsProcessor


def main():
    root=Path.home()/"Library/Caches/Auralis/tts/upstream-fidelity"
    paths=[root/"real-pool/runner-pre-penalty-fix.py",root/"real-pool/protocol-fixed-sampled/tts_runner.py"]
    history=[2,2,2,3,3];logits=np.zeros(3072,dtype=np.float64);logits[2]=2;logits[3]=-2;logits[4]=1.8
    result=dict(history=history,original=logits[:5].tolist(),runners=[])
    for i,path in enumerate(paths):
        spec=importlib.util.spec_from_file_location(f"frozen_runner_{i}",path);m=importlib.util.module_from_spec(spec);sys.modules[spec.name]=m;spec.loader.exec_module(m)
        m.np=np;m._softmax_sample=lambda scores,*args: scores
        cfg=m.load_config(root.parent/"hf")
        output=m.sample_group0(logits,cfg,.9,1,1.05,history,np.random.default_rng(0))
        result["runners"].append(dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),scores=output[:5].tolist(),argmax=int(output.argmax())))
    official=RepetitionPenaltyLogitsProcessor(1.05)(torch.tensor([history]),torch.from_numpy(logits.copy())[None]).numpy()[0]
    result["official"]=dict(scores=official[:5].tolist(),argmax=int(official.argmax()),transformers=__import__("transformers").__version__)
    assert result["runners"][1]["scores"]==result["official"]["scores"]
    assert result["runners"][0]["argmax"]!=result["official"]["argmax"]
    (root/"sampling-discrepancy.json").write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))


if __name__=="__main__":main()
