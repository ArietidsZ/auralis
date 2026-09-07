#!/usr/bin/env python3
"""Compare actual runner target crops with the official decoder on identical codes."""
import json
from pathlib import Path

import numpy as np
import torch
from qwen_tts import Qwen3TTSTokenizer


def main():
    root=Path.home()/"Library/Caches/Auralis/tts/icl/runner-validation"
    hf=Path.home()/".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc/speech_tokenizer"
    torch.set_num_threads(2)
    model=Qwen3TTSTokenizer.from_pretrained(str(hf),dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    rows=[]
    for identity in ("121","260"):
        for language in ("english","chinese"):
            path=root/f"{identity}-{language}-icl";codes=np.load(path/"codes.npz")
            reference_frames=codes["reference"].shape[1]
            all_codes=np.concatenate([codes["reference"],codes["generated"]],axis=1)
            with torch.inference_mode():waves,sr=model.decode([dict(audio_codes=torch.from_numpy(all_codes.T.copy()))])
            official=waves[0];onnx=np.load(path/"full-context-pcm.npy")
            assert sr==24000 and len(official)==len(onnx)==all_codes.shape[1]*1920
            exact_cut=reference_frames*1920
            official_formula_cut=int(reference_frames/all_codes.shape[1]*len(official))
            delta=official[exact_cut:].astype(np.float64)-onnx[exact_cut:].astype(np.float64)
            row=dict(id=f"{identity}-{language}",reference_frames=reference_frames,exact_cut_samples=exact_cut,
                official_formula_cut_samples=official_formula_cut,target_samples=len(delta),
                max_abs=float(np.abs(delta).max()),rms=float(np.sqrt(np.mean(delta*delta))))
            assert row["max_abs"]<1e-4 and row["rms"]<1e-5
            rows.append(row);np.save(path/"official-context-pcm.npy",official)
            (root/"official-vocoder.json").write_text(json.dumps(rows,indent=2));print(row,flush=True)


if __name__=="__main__":main()
