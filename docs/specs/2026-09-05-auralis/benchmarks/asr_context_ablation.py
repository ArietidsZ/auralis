#!/usr/bin/env python3
"""Fixed 15-file ASR context/memory experiment. All outputs go to --output.

The orchestrator launches serial fresh workers under /usr/bin/time -l,
killing a worker process group if it exceeds 240 seconds or 4 GiB RSS.
No mobile settings or production decoder parameters are modified.
"""
from __future__ import annotations
import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import resource
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path('/Users/arietids/Library/Caches/Auralis')
POLICIES = {
    '512': dict(total=512, new=192, trigger=36, cap=20),
    '1024': dict(total=1024, new=384, trigger=45, cap=45),
    '2048': dict(total=2048, new=768, trigger=95, cap=95),
}
CASE_IDS = ['ar1', 'cantonese', 'codeswitch', 'de', 'es1', 'f1_noise', 'fast1',
            'fr1', 'ja1', 'noise1-en', 'noise2', 'qiqiu1', 'raokouling', 'rap1', 'ru1']

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')

def runner():
    spec = importlib.util.spec_from_file_location('asr_runner', ROOT / 'convert/asr_runner.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module

def memory():
    current = subprocess.check_output(['ps', '-o', 'rss=', '-p', str(os.getpid())], text=True)
    # Darwin ru_maxrss is bytes; ps reports KiB. No inferred device memory.
    return dict(currentRssBytes=int(current.strip()) * 1024,
                maxRssBytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)

@contextlib.contextmanager
def capture_native(path):
    sys.stdout.flush(); sys.stderr.flush()
    saved = [os.dup(1), os.dup(2)]
    try:
        with path.open('wb') as f:
            os.dup2(f.fileno(), 1); os.dup2(f.fileno(), 2)
            yield
    finally:
        os.dup2(saved[0], 1); os.dup2(saved[1], 2)
        os.close(saved[0]); os.close(saved[1])

def worker(args):
    asr = runner()
    policy = POLICIES[args.policy]
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    paths = [args.wavs / (name + '.wav') for name in CASE_IDS]
    refs = asr.read_transcript_index(args.wavs / 'transcript.txt')
    if set(refs) != set(CASE_IDS) or {p.stem for p in args.wavs.glob('*.wav')} != set(CASE_IDS):
        raise ValueError('Expected exactly all 15 fixed WAVs and complete references')
    result = dict(policy=policy, mode=args.mode, normalization='strip/remove whitespace only; case and punctuation retained',
                  numThreads=2, sampling='greedy_search; temperature 1e-6; top_p .8; seed42',
                  runnerSha256=sha(ROOT / 'convert/asr_runner.py'), before=memory(), cases=[])
    start = time.perf_counter()
    for path in paths:
        asr._read_wav_pcm(path)
    result['inputValidationSeconds'] = time.perf_counter() - start
    result['afterValidation'] = memory()
    preload = None
    if args.mode in ('preload', 'audio-preload'):
        start = time.perf_counter()
        preload = [asr.load_wav(path) for path in paths]
        result['audioPreloadSeconds'] = time.perf_counter() - start
        result['afterAudioPreload'] = memory()
    if args.mode in ('audio-single', 'audio-preload'):
        for i, path in enumerate(paths):
            audio = preload[i] if preload is not None else asr.load_wav(path)
            result['cases'].append(dict(id=path.stem, durationSeconds=audio.duration_s, memory=memory()))
            del audio
        result['finalMemory'] = memory()
        write(output / 'results.json', result)
        return
    import sherpa_onnx
    start = time.perf_counter()
    with capture_native(output / 'model-load.log'):
        recognizer = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
            conv_frontend=str(args.models / 'conv_frontend.onnx'),
            encoder=str(args.models / 'encoder.int8.onnx'),
            decoder=str(args.models / 'decoder.int8.onnx'), tokenizer=str(args.models / 'tokenizer'),
            num_threads=2, max_total_len=policy['total'], max_new_tokens=policy['new'],
            feature_dim=128, decoding_method='greedy_search', debug=True,
            temperature=1e-6, top_p=.8, seed=42)
    result['modelLoadSeconds'] = time.perf_counter() - start
    result['afterModelLoad'] = memory()
    result['runtime'] = asr.runtime_versions()
    write(output / 'results.json', result)
    if args.mode == 'load-only':
        return
    def decode(samples, sample_rate, name):
        log = output / (name + '.log')
        started = time.perf_counter()
        with capture_native(log):
            stream = recognizer.create_stream()
            stream.accept_waveform(sample_rate, samples)
            recognizer.decode_stream(stream)
            text = stream.result.text
            tokens = list(stream.result.tokens)
        elapsed = time.perf_counter() - started
        logs = log.read_text(errors='replace')
        flags = []
        if re.search(r'Result is truncated|Truncating audio placeholders|truncating the repetition', logs):
            flags.append('runtime-truncation-or-repetition-warning')
        if not text.strip() or text.strip().lower() == 'language':
            flags.append('empty-or-language-only')
        return dict(text=text, tokenCount=len(tokens), decodeSeconds=elapsed,
                    rtf=elapsed/(len(samples)/sample_rate), flags=flags, log=log.name,
                    memory=memory())
    for i, path in enumerate(paths):
        if i < args.start_index:
            continue
        entry = dict(id=path.stem, path=str(path), reference=refs[path.stem])
        try:
            started = time.perf_counter()
            audio = preload[i] if preload is not None else asr.load_wav(path)
            entry.update(loadWavSeconds=time.perf_counter()-started, sampleRate=audio.sample_rate,
                         durationSeconds=audio.duration_s, audioSha256=sha(path))
            started = time.perf_counter()
            if args.policy == '512':
                ranges, method = asr.segment_audio(audio.samples, audio.sample_rate, None, 2)
            elif audio.duration_s <= policy['trigger']:
                ranges, method = [(0, len(audio.samples))], 'whole'
            else:
                ranges = asr.bounded_energy_segments(audio.samples, audio.sample_rate, policy['cap'])
                method = 'energy'
            segmentation_seconds = time.perf_counter() - started
            whole = None
            if args.mode != 'selected' or ranges == [(0, len(audio.samples))]:
                whole = decode(audio.samples, audio.sample_rate, path.stem + '-whole')
                entry['whole'] = whole
            segments = []
            if ranges == [(0, len(audio.samples))]:
                selected = dict(whole, reusedWhole=True, method=method,
                                segmentationSeconds=segmentation_seconds)
            else:
                for j, (a,b) in enumerate(ranges):
                    decoded = decode(audio.samples[a:b], audio.sample_rate, f'{path.stem}-segment{j}')
                    segments.append(dict(decoded, startSeconds=a/audio.sample_rate, endSeconds=b/audio.sample_rate))
                seconds = sum(s['decodeSeconds'] for s in segments)
                selected = dict(text=asr.join_segment_texts([s['text'] for s in segments]),
                                decodeSeconds=seconds, rtf=(seconds+segmentation_seconds)/audio.duration_s,
                                flags=[f for s in segments for f in s['flags']], method=method,
                                reusedWhole=False, segmentationSeconds=segmentation_seconds, segments=segments)
            entry['selected'] = selected
            reference_chars = [c for c in refs[path.stem].strip() if not c.isspace()]
            for item in (x for x in (whole, selected) if x is not None):
                item['characterErrors'] = asr.edit_distance(reference_chars, [c for c in item['text'].strip() if not c.isspace()])
                item['referenceCharacters'] = len(reference_chars)
                item['cer'] = item['characterErrors'] / len(reference_chars)
            del audio
            entry['status'] = 'ok'
        except Exception as exc:
            entry.update(status='error', error=repr(exc))
        result['cases'].append(entry)
        write(output / 'results.json', result)
        print(path.stem, entry['status'], flush=True)
    for arm in ('whole', 'selected'):
        cases = [c for c in result['cases'] if c['status']=='ok' and arm in c]
        if not cases:
            continue
        result[arm+'Summary'] = dict(complete=len(cases)==15, cases=len(cases),
            macroCer=sum(c[arm]['cer'] for c in cases)/len(cases),
            corpusCer=sum(c[arm]['characterErrors'] for c in cases)/sum(c[arm]['referenceCharacters'] for c in cases),
            characterErrors=sum(c[arm]['characterErrors'] for c in cases),
            referenceCharacters=sum(c[arm]['referenceCharacters'] for c in cases),
            decodeSeconds=sum(c[arm]['decodeSeconds'] for c in cases),
            durationSeconds=sum(c['durationSeconds'] for c in cases),
            corpusRtf=sum(c[arm]['decodeSeconds']+c[arm].get('segmentationSeconds',0) for c in cases)/sum(c['durationSeconds'] for c in cases),
            flaggedCases=[c['id'] for c in cases if c[arm]['flags']])
    result['finalMemory'] = memory()
    write(output/'results.json', result)

def orchestrate(args):
    args.output.mkdir(parents=True, exist_ok=True)
    plan = dict(policies=POLICIES, cases=CASE_IDS, maxRssBytes=4*1024**3, timeoutSeconds=240,
                note='Fixed before inference; host timing under concurrent load, not phone performance.',
                casesSha256=sha(args.wavs/'transcript.txt'), runs=[])
    write(args.output/'plan-and-runs.json', plan)
    runs = ([('512','selected'),('1024','selected'),('2048','selected')] if args.selected_only else
            [('512','audio-preload'),('512','audio-single'),('512','load-only'),
             ('512','preload'),('512','single'),('1024','single'),('2048','single')])
    if args.start_index:
        runs = [(args.policy, args.mode)]
    for policy,mode in runs:
        destination = args.output / (policy+'-'+mode)
        destination.mkdir(exist_ok=True)
        command = ['/usr/bin/time','-l',sys.executable,str(Path(__file__).resolve()),'--worker',
                   '--policy',policy,'--mode',mode,'--output',str(destination),
                   '--models',str(args.models),'--wavs',str(args.wavs), '--start-index', str(args.start_index)]
        start=time.monotonic(); peak=0; failure=None
        with (destination/'process.stdout.log').open('wb') as stdout, (destination/'process.stderr.log').open('wb') as stderr:
            p=subprocess.Popen(command,stdout=stdout,stderr=stderr,start_new_session=True)
            while p.poll() is None:
                listing=subprocess.check_output(['ps','-axo','pid=,ppid=,rss='],text=True)
                records=[tuple(map(int,line.split())) for line in listing.splitlines() if line.strip()]
                descendants={p.pid}
                while True:
                    grown=descendants|{pid for pid,parent,rss in records if parent in descendants}
                    if grown==descendants: break
                    descendants=grown
                group_rss=sum(rss*1024 for pid,parent,rss in records if pid in descendants)
                peak=max(peak,group_rss)
                if group_rss>plan['maxRssBytes']: failure='RSS cap exceeded'
                if time.monotonic()-start>plan['timeoutSeconds']: failure='wall timeout exceeded'
                if failure:
                    # Keep /usr/bin/time alive to collect the killed worker's
                    # actual high-water RSS instead of losing that evidence.
                    for pid in descendants - {p.pid}:
                        try:
                            os.kill(pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                    try:
                        p.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        os.killpg(p.pid,signal.SIGKILL)
                    break
                time.sleep(.1)
            code=p.wait()
        log=(destination/'process.stderr.log').read_text(errors='replace')
        match=re.search(r'(\d+)\s+maximum resident set size',log)
        record=dict(policy=policy,mode=mode,command=command,exitCode=code,failure=failure,
                    wallSeconds=time.monotonic()-start,observedProcessGroupPeakBytes=peak,
                    timeMaxRssBytes=int(match.group(1)) if match else None)
        plan['runs'].append(record); write(args.output/'plan-and-runs.json',plan)
        print(json.dumps(record),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--models',type=Path,default=CACHE/'models/asr')
    parser.add_argument('--wavs',type=Path,default=CACHE/'asr/test_wavs')
    parser.add_argument('--worker',action='store_true')
    parser.add_argument('--policy',choices=POLICIES,default='512')
    parser.add_argument('--mode',choices=['single','preload','selected','load-only','audio-single','audio-preload'],default='single')
    parser.add_argument('--selected-only',action='store_true')
    parser.add_argument('--start-index',type=int,default=0,help='Continue remaining fixed cases after a killed worker; never replaces failed cases')
    args=parser.parse_args()
    worker(args) if args.worker else orchestrate(args)
