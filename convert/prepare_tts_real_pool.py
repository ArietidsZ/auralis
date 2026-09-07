#!/usr/bin/env python3
"""Deterministically prepare licensed, unseen real-speaker reference/held-out pairs."""
import hashlib
import io
import json
from pathlib import Path
import tarfile

import soundfile as sf


def main():
    root=Path.home()/"Library/Caches/Auralis/tts/upstream-fidelity"
    archive=root/"test-clean.tar.gz";out=root/"real-pool";out.mkdir(exist_ok=True)
    digest=hashlib.md5(archive.read_bytes()).hexdigest()
    assert digest=="32fa31d27d2e1cad72775fee3f4849a9"
    criteria=dict(selection="First three female and first three male test-clean IDs numerically, independent of audio scores. Reference: first lexicographic 6-12s recording. Held-out: last qualifying recording from another chapter if available, else last distinct recording.",
        synthesis_texts={"english":"Please do not cancel the train to London tomorrow.","chinese":"请不要取消明天去上海的火车票。"},
        comparisons=["FP32","INT8-b32-a4","INT4-b32-a4"],reference_resample="librosa soxr_hq 16k->24k equally for all variants",
        identity="ECAPA held-out cosine ranked against all six identities, including two same-gender alternatives; gender classification is never a pass criterion.",
        screening_gate=dict(native_reference_top1_required=6,generated_top1_required=12,
            candidate_new_identity_failures_allowed=0,paired_median_cosine_delta_min=-.03,paired_worst_cosine_delta_min=-.10),
        limitation="These screening thresholds are declared before output generation, are not calibrated product thresholds, and do not authorize verification promotion.")
    (out/"predeclared-criteria.json").write_text(json.dumps(criteria,ensure_ascii=False,indent=2))
    with tarfile.open(archive) as tar:
        meta=tar.extractfile("LibriSpeech/SPEAKERS.TXT").read().decode()
        (out/"SPEAKERS.TXT").write_text(meta)
        rows=[list(map(str.strip,x.split("|"))) for x in meta.splitlines() if x and not x.startswith(";")]
        selected=[]
        for sex in ("F","M"):
            selected += sorted([r for r in rows if r[1]==sex and r[2]=="test-clean"],key=lambda r:int(r[0]))[:3]
        names=tar.getnames();manifest=[]
        for row in selected:
            prefix=f"LibriSpeech/test-clean/{row[0]}/"
            recordings=[]
            for name in sorted(n for n in names if n.startswith(prefix) and n.endswith(".flac")):
                data=tar.extractfile(name).read();info=sf.info(io.BytesIO(data))
                if 6<=info.duration<=12:recordings.append((name,data,info.duration))
            assert len(recordings)>=2,row
            ref=recordings[0];other=[r for r in recordings[1:] if r[0].split("/")[-2]!=ref[0].split("/")[-2]]
            held=(other or recordings[1:])[-1]
            speaker=dict(id=row[0],sex=row[1],name=row[4],recordings={})
            for role,(name,data,duration) in (("reference",ref),("heldout",held)):
                target=out/f"{row[0]}-{role}.flac";target.write_bytes(data)
                chapter="/".join(name.split("/")[:-1]);stem=Path(name).stem
                transcripts=tar.extractfile(f"{chapter}/{row[0]}-{name.split('/')[-2]}.trans.txt").read().decode()
                text=next(line.split(" ",1)[1] for line in transcripts.splitlines() if line.startswith(stem+" "))
                speaker["recordings"][role]=dict(path=str(target),archive_path=name,duration_s=duration,sample_rate=16000,sha256=hashlib.sha256(data).hexdigest(),text=text)
            manifest.append(speaker)
    (out/"manifest.json").write_text(json.dumps(dict(source="https://www.openslr.org/12/",archive_url="https://www.openslr.org/resources/12/test-clean.tar.gz",license="CC BY 4.0",attribution="LibriSpeech: Vassil Panayotov, Guoguo Chen, Daniel Povey, Sanjeev Khudanpur; recordings from LibriVox; individual reader names retained.",md5=digest,speakers=manifest),ensure_ascii=False,indent=2))
    print([(x["id"],x["sex"]) for x in manifest])


if __name__=="__main__":main()
