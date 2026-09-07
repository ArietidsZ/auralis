#!/usr/bin/env python3
"""Independent ASR and ECAPA evaluation of every saved TTS screening case."""
import argparse
import json
from pathlib import Path


def numeric_equivalents(text):
    """Declared text-format equivalents, not correction of recognition errors."""
    text = text.lower().replace("-", " ")
    for source, target in [("two thousand twenty six", "2026"),
                           ("three hundred twenty five", "325"),
                           ("二零二六", "2026"), ("二〇二六", "2026"),
                           ("两千零二十六", "2026"), ("三百二十五", "325"),
                           ("九月七日", "9月7日")]:
        text = text.replace(source, target)
    return text


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode", choices=["asr", "speaker"])
    p.add_argument("results", type=Path, nargs="+")
    a = p.parse_args()
    cache = Path.home()/"Library/Caches/Auralis"
    if a.mode == "asr":
        import asr_runner as asr
        model = asr.build_recognizer(cache/"models/asr", 2, 512)
        def evaluate(case):
            audio = asr.load_wav(Path(case["audio_path"]))
            output = asr.transcribe_file(model, audio, None, 2, "on")
            text = output["segmented"]["text"]
            return dict(text=text, cer=asr.cer(case["text"],text),
                        wer=asr.wer(case["text"],text),
                        numeric_normalized_cer=asr.cer(numeric_equivalents(case["text"]),numeric_equivalents(text)),
                        numeric_normalized_wer=asr.wer(numeric_equivalents(case["text"]),numeric_equivalents(text)), raw=output)
    else:
        import numpy as np
        import soundfile as sf
        import torch
        from speechbrain.inference.speaker import EncoderClassifier
        torch.set_num_threads(2)
        model = EncoderClassifier.from_hparams(
            source=str(cache/"tts/ecapa"), savedir=str(cache/"tts/ecapa"),
            run_opts={"device":"cpu"})
        def embed(path):
            x, sr = sf.read(path, dtype="float32")
            if x.ndim > 1:
                x = x.mean(axis=1)
            if sr != 16000:
                dst = np.arange(int(len(x)*16000/sr)) * (sr/16000)
                x = np.interp(dst, np.arange(len(x)), x).astype(np.float32)
            with torch.no_grad():
                return model.encode_batch(torch.from_numpy(x)[None]).squeeze().numpy()
        def cos(x,y):
            return float(np.dot(x,y)/(np.linalg.norm(x)*np.linalg.norm(y)))
        refs = {v:embed(cache/f"tts/ref_{v}.wav") for v in ("zh","en")}
        pools = {v:[refs[v]]+[embed(cache/f"tts/eval/sv_{v}_pos{s}.wav") for s in ("","2","3")]
                 for v in ("zh","en")}
        def evaluate(case):
            e = embed(case["audio_path"])
            other = "en" if case["voice"] == "zh" else "zh"
            same = float(np.mean([cos(e,r) for r in pools[case["voice"]]]))
            cross = float(np.mean([cos(e,r) for r in pools[other][1:]]))
            return dict(same=same,cross=cross,margin=same-cross,clusters_with_reference=same>cross)
    for path in a.results:
        source = json.loads(path.read_text())
        output = dict(source=str(path),mode=a.mode,cases=[])
        for case in source["cases"]:
            if case["repeat"] != 0:
                continue
            if case.get("status")=="synthesis_failed":
                output["cases"].append(dict(id=case["id"],status="synthesis_failed",error=case["error"]))
                path.with_name(f"{a.mode}.json").write_text(json.dumps(output,ensure_ascii=False,indent=2))
                continue
            row = dict(id=case["id"],reference=case["text"],voice=case["voice"],**evaluate(case))
            output["cases"].append(row)
            path.with_name(f"{a.mode}.json").write_text(json.dumps(output,ensure_ascii=False,indent=2))
            print(json.dumps({k:v for k,v in row.items() if k!="raw"},ensure_ascii=False),flush=True)


if __name__ == "__main__":
    main()
