#!/usr/bin/env python3
"""Run explicit negative CLI cases with real ORT1.24.2 and real wrong-role assets."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import wave


def main():
    cache=Path.home()/"Library/Caches/Auralis/tts";root=cache/"icl/runner-validation/failures";root.mkdir(exist_ok=True)
    reference=cache/"upstream-fidelity/real-pool/121-reference.flac"
    with wave.open(str(root/"silence.wav"),"wb") as wav:
        wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(24000);wav.writeframes(bytes(24000*2))
    missing_data=root/"missing-data/reference_encoder.onnx";missing_data.parent.mkdir(exist_ok=True)
    shutil.copy2(cache/"icl/reference_encoder.onnx",missing_data)
    cases=[
        ("missing-text",4,"icl",reference,None,cache/"icl/reference_encoder.onnx"),
        ("missing-encoder",2,"icl",reference,"actual transcript",root/"absent.onnx"),
        ("missing-data",2,"icl",reference,"actual transcript",missing_data),
        ("wrong-role",1,"icl",reference,"actual transcript",cache/"hf/speaker_encoder.onnx"),
        ("silent-icl",1,"icl",root/"silence.wav","invalid silence fixture",cache/"icl/reference_encoder.onnx"),
        ("silent-xvector",1,"xvector",root/"silence.wav",None,None),
    ]
    runner=Path(__file__).resolve().parents[1]/"tts_runner.py";results=[]
    for name,expected,mode,ref,text,encoder in cases:
        report=root/f"{name}.json";output=root/f"{name}.wav"
        args=[sys.executable,str(runner),"--model-dir",str(cache/"hf"),"--text","Target speech.","--reference-wav",str(ref),
              "--out",str(output),"--json-report",str(report),"--conditioning-mode",mode,"--threads","2"]
        if text is not None:args += ["--reference-text",text]
        if encoder is not None:args += ["--reference-encoder",str(encoder)]
        proc=subprocess.run(args,capture_output=True,text=True,timeout=90)
        payload=json.loads(report.read_text()) if report.exists() else None
        assert proc.returncode==expected,(name,proc.returncode,proc.stdout,proc.stderr)
        assert not output.exists()
        if payload:
            assert payload["status"]=="error" and "audio_path" not in payload and "duration_s" not in payload
        results.append(dict(case=name,returncode=proc.returncode,payload=payload,stderr=proc.stderr))
        (root/"summary.json").write_text(json.dumps(results,indent=2));print(name,proc.returncode,flush=True)


if __name__=="__main__":main()
