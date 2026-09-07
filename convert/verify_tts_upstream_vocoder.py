#!/usr/bin/env python3
"""Compare the official full PyTorch speech tokenizer decoder to ONNX PCM."""
import hashlib
import json
from pathlib import Path
import time

import numpy as np
import torch
from qwen_tts import Qwen3TTSTokenizer


def main():
    root=Path.home()/"Library/Caches/Auralis/tts/upstream-fidelity"
    model=Path.home()/".cache/huggingface/hub/models--Qwen--Qwen3-TTS-12Hz-0.6B-Base/snapshots/5d83992436eae1d760afd27aff78a71d676296fc/speech_tokenizer"
    torch.set_num_threads(2)
    start=time.perf_counter()
    tokenizer=Qwen3TTSTokenizer.from_pretrained(str(model),torch_dtype=torch.float32,attn_implementation="eager",local_files_only=True)
    load=time.perf_counter()-start
    codes=np.load(root/"streaming-reference-codes.npy")
    start=time.perf_counter()
    wave,sr=tokenizer.decode([dict(audio_codes=torch.from_numpy(codes[0].T))])
    elapsed=time.perf_counter()-start
    expected=np.load(root/"streaming-full-pcm.npy")
    actual=np.asarray(wave[0]);delta=actual.astype(np.float64)-expected.astype(np.float64)
    result=dict(sample_rate=sr,shape=list(actual.shape),load_s=load,decode_s=elapsed,
                torch=torch.__version__,dtype="float32",attention="eager",
                max_abs=float(np.max(np.abs(delta))),rms=float(np.sqrt(np.mean(delta*delta))),
                cosine=float(np.dot(actual.astype(np.float64),expected.astype(np.float64))/(np.linalg.norm(actual.astype(np.float64))*np.linalg.norm(expected.astype(np.float64)))),
                script_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
    np.save(root/"official-vocoder-pcm.npy",actual)
    (root/"official-vocoder-comparison.json").write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))


if __name__=="__main__":main()
