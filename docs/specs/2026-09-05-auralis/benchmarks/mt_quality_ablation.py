#!/usr/bin/env python3
"""Fixed HY-MT quality ablation; raw outputs preserved, no timing claims.

Run from repository root. Only writes --output. Models/CLI must already exist.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path('/Users/arietids/Library/Caches/Auralis/mt')
CASES = [
    ('museum', '今天下午我们去博物馆参观，好吗？', 'zh', 'en', ['The weather is nice today.'], 'Only museum invitation; no weather claim.'),
    ('unrelated-number', '请在周五之前回复。', 'zh', 'en', ['The invoice total is 847 dollars.'], 'Reply before Friday; no invoice or 847.'),
    ('negation', '我没有把文件发给李明。', 'zh', 'en', ['王芳已经把照片发给了张伟。'], 'Speaker did not send document to Li Ming; no Wang Fang/photo/Zhang Wei.'),
    ('time-negation', 'The meeting starts at 3:00 pm, not 4:00.', 'en', 'zh', ['Yesterday the workshop started at noon.'], 'Meeting at 15:00, not 16:00; no yesterday/noon workshop.'),
    ('name-number', 'Please call Dr. Wang at 138-0013-8000 before Friday.', 'en', 'zh', ['Dr. Li is away until Monday.'], 'Dr Wang phone number and before Friday preserved; no Dr Li/Monday.'),
    ('decimal-unit', '温度从零下5.5摄氏度升到了2摄氏度。', 'zh', 'en', ['昨天的风速是每小时80公里。'], 'From minus 5.5 degrees Celsius to plus 2; no wind/80.'),
    ('unknown-source', '今日は雨が降っています。', 'unknown', 'zh', ['明日は晴れるでしょう。'], 'Chinese today raining; no tomorrow sunny.'),
    ('japanese-source', '今日は雨が降っています。', 'ja', 'zh', ['昨日は雪でした。'], 'Chinese today raining; no yesterday snow.'),
    ('language-codes', '请把门关上。', 'zh', 'en', ['窗户已经关上了。'], 'Close the door; no window assertion.'),
    ('proper-name', 'The train to Zürich leaves from platform 12 at 06:45.', 'en', 'zh', ['The train to Paris has been cancelled.'], 'Zurich platform 12 at 06:45; no Paris cancellation.'),
    ('female-pronoun', 'She will arrive tomorrow.', 'en', 'zh', ['Dr. Chen is my sister.'], 'She arrives tomorrow; no context translated; feminine pronoun retained.'),
    ('bank-sense', 'He sat on the bank.', 'en', 'zh', ['A fisherman walked along the river.'], 'With context: he sat on riverbank, not bank institution; no fisherman sentence.'),
    ('context-time', 'It will be held at the same time.', 'en', 'zh', ['The meeting has been postponed until next Monday.'], 'It held at same time; no invented Monday or extra postponement sentence.'),
    ('unknown-en', 'Do not open package B-17 until Monday.', 'unknown', 'zh', ['Package A-12 was opened yesterday.'], 'Do not open B-17 until Monday; no A-12/yesterday.'),
]

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def prompt(case, use_context):
    _, text, src, tgt, context, _ = case
    target = {'en': '英语', 'zh': '中文'}[tgt]
    if use_context:
        return '\n'.join(context) + '\n参考上面的信息，把下面的文本翻译成' + target + '，注意不需要翻译上文，也不要额外解释：\n' + text + '\n'
    return '将以下文本翻译为' + target + '，注意只需要输出翻译后的结果，不要额外解释：\n\n' + text

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cli', type=Path, default=CACHE / 'build-host/bin/llama-completion')
    parser.add_argument('--lib', type=Path, default=CACHE / 'build-host/libhymt_core.dylib')
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--phase', choices=['all', 'native', 'sampling'], default='all')
    parser.add_argument('--sampling-policy', choices=['controlled', 'card-cli'], default='controlled')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('mt_runner', ROOT / 'convert/mt_runner.py')
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    report = dict(model=str(args.model), modelSha256=sha(args.model), modelBytes=args.model.stat().st_size,
                  cli=str(args.cli), cliSha256=sha(args.cli), lib=str(args.lib), libSha256=sha(args.lib),
                  threads=4, gpuLayers=0, seed=args.seed, phase=args.phase, samplingPolicy=args.sampling_policy,
                  timingUse='Functional quality only; concurrent workloads, not device performance.',
                  cases=CASES, results=[])
    def record(entry):
        report['results'].append(entry)
        (args.output / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(entry, ensure_ascii=False), flush=True)
    if args.phase in ('all', 'native'):
        lib = runner.HyMtLib(args.lib)
        report['runtimeRevision'] = lib.revision()
        handle = lib.load(args.model)
        try:
            for use_context in (False, True):
                for case in CASES:
                    ident, text, src, tgt, context, _ = case
                    rc, out, err = lib.translate(handle, text, src, tgt, context if use_context else [])
                    record(dict(id=ident, variant='native-context' if use_context else 'native-no-context',
                                status=rc, output=out, error=err, prompt=prompt(case, use_context)))
        finally:
            lib.release(handle)
    if args.phase in ('all', 'sampling'):
        # Explicit sampler order mirrors Transformers: repetition, temperature, top-k, top-p.
        # Disable llama.cpp's default min-p; repetition window covers the entire 2048-token context.
        for use_context in (False, True):
            for case in CASES:
                variant = 'sampling-context' if use_context else 'sampling-no-context'
                command = [str(args.cli), '-m', str(args.model), '--jinja', '-st', '-p', prompt(case, use_context),
                           '-n', '256', '-ngl', '0', '-c', '2048', '-b', '512', '-t', '4', '-tb', '4',
                           '--temp', '0.7', '--top-k', '20', '--top-p', '0.6', '--min-p', '0',
                           '--repeat-penalty', '1.05', '--repeat-last-n', '2048',
                           '--samplers', 'penalties;temperature;top_k;top_p', '--seed', str(args.seed),
                           '--no-display-prompt', '--no-warmup', '--simple-io', '--color', 'off']
                if args.sampling_policy == 'card-cli':
                    # Literal model-card sampling flags retain this pinned CLI's other defaults.
                    # This separates official CLI behavior from the explicit controlled sampler arm.
                    for flag in ('--min-p', '--repeat-last-n', '--samplers'):
                        index = command.index(flag)
                        del command[index:index + 2]
                    variant = 'card-cli-' + ('context' if use_context else 'no-context')
                start = time.monotonic()
                proc = subprocess.run(command, capture_output=True, text=True, timeout=180)
                stem = args.output / (variant + '-' + case[0])
                stem.with_suffix('.stdout.txt').write_text(proc.stdout)
                stem.with_suffix('.stderr.txt').write_text(proc.stderr)
                record(dict(id=case[0], variant=variant, status=proc.returncode,
                            output=proc.stdout.replace('[end of text]', '').strip(), rawStdout=proc.stdout,
                            stderrFile=stem.with_suffix('.stderr.txt').name,
                            command=command, durationSeconds=round(time.monotonic() - start, 3)))

if __name__ == '__main__':
    main()
