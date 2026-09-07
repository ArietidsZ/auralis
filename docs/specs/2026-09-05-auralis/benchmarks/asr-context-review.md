# ASR context and memory ablation — 2026-09-07

The runner now validates every WAV before model loading and decodes one file at a time. It no longer retains the whole corpus as Python float lists. CLI arguments, report fields, error codes, reference coverage, decoding, and metric formulas are unchanged. All 15 real whole/segmented outputs match the preloading control exactly.

The fixed **1024/384, 45-second energy-boundary policy** improves the two long recordings over the existing 512/192 policy. It is a useful next device-test candidate, with a measured host memory cost. The tested 2048/768 policy permits an 88-second whole recording and fails the experiment's RSS guard. No Android/iOS defaults were changed.

## Fixed experiment

[asr_context_ablation.py](asr_context_ablation.py) runs fresh serial processes with two threads, `greedy_search`, temperature `1e-6`, top-p `0.8`, seed 42, and the original PCM sample rates. sherpa-onnx owns resampling. All 15 official WAVs and complete references are required before running. The corpus contains 297.768691893 seconds and 3,173 reference characters. CER removes whitespace only; punctuation and case remain, matching the central evaluator. No case was removed or shortened to improve a score.

Parameters were declared before inference:

| Policy | `max_total_len` | `max_new_tokens` | Segment when longer than | Segment maximum |
|---|---:|---:|---:|---:|
| Existing baseline | 512 | 192 | 36 s | 20 s |
| Larger candidate | 1024 | 384 | 45 s | 45 s |
| Larger candidate | 2048 | 768 | 95 s | 95 s |

The larger cut points reserve 32 prompt/scaffold tokens and approximately 13 audio tokens/second: `45*13+384+32=1001≤1024`, `95*13+768+32=2035≤2048`. The baseline retains the existing 36-second trigger rather than changing its historical behavior. The experiment uses energy boundaries; Silero VAD was not used.

Context7 returned the [official Qwen3 configuration documentation](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/docs/offline-asr.dox). Token and truncation details were independently checked against [the installed runtime's exact source](https://github.com/k2-fsa/sherpa-onnx/blob/917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc). Its audio-length conversion uses 100 feature frames per chunk and three stride-two reductions, producing 13 tokens for a full chunk. The implementation can truncate audio placeholders, stop on total-context capacity, or exhaust the new-token budget. An output that is neither empty nor `language` is therefore not proof of completeness.

The source SHA256 is `a9d396a4d167612e822fb4125e43286ed9b809b44049516a018acac24a078430`. Runtime: sherpa-onnx 1.13.7, git `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e`, ONNX Runtime 1.28.1. All six model hashes were checked again against the local manifest and saved with every WAV/reference hash in `asr-context-provenance.json` under the persistent experiment directory.

## Complete selected-path results

These processes execute only the chosen policy. Whole-audio diagnostic decoding is excluded so its retained ONNX Runtime allocations do not inflate the selected policy's peak memory.

| Policy | Completed | Macro CER | Corpus CER | Character errors | Host corpus RTF | Actual maxRSS |
|---|---:|---:|---:|---:|---:|---:|
| 512/192, 36/20 s | 15/15 | 0.1348453 | 0.2076899 | 659/3173 | 0.1843502 | 2,498,543,616 bytes |
| 1024/384, 45/45 s | 15/15 | 0.1216833 | 0.1487551 | 472/3173 | 0.2044672 | 2,970,075,136 bytes |
| 2048/768, 95/95 s | 14 outputs + 1 guard failure, using continuation | Not a complete-corpus result | Not a complete-corpus result | Not scored as passing | Not comparable | 4,385,423,360 bytes at killed worker |

The middle candidate removes 187 character errors, a 28.4% reduction on this fixed corpus, while increasing measured selected-path peak RSS by 471,531,520 bytes (449.69 MiB). Thirteen outputs remain byte-for-byte identical. The two changed outputs both improve:

| Case | Duration | 512 CER / segments | 1024 CER / segments |
|---|---:|---:|---:|
| `noise1-en` | 88.193 s | 0.2636193 / 6 | 0.1584220 / 3 |
| `qiqiu1` | 50.964125 s | 0.3398058 / 3 | 0.2475728 / 2 |

The baseline's first noise segment, 0–11 seconds, returns an empty string. That is retained as an empty-output signal, not asserted to be a proven token-limit truncation. The 1024 selected path has no empty/language-only segments or runtime truncation warnings in this corpus. Its other 13 samples retain their existing recognition errors; the improvement does not establish broad ASR accuracy.

Every selected-path case is retained below, including the failed one:

| Case | 512 CER | 1024 CER | 2048 CER |
|---|---:|---:|---:|
| ar1 | 0.034483 | 0.034483 | 0.034483 |
| cantonese | 0.086538 | 0.086538 | 0.086538 |
| codeswitch | 0.267857 | 0.267857 | 0.267857 |
| de | 0 | 0 | 0 |
| es1 | 0.050000 | 0.050000 | 0.050000 |
| f1_noise | 0.077465 | 0.077465 | 0.077465 |
| fast1 | 0.197183 | 0.197183 | 0.197183 |
| fr1 | 0 | 0 | 0 |
| ja1 | 0 | 0 | 0 |
| noise1-en | 0.263619 | 0.158422 | Memory-guard failure |
| noise2 | 0.354839 | 0.354839 | 0.354839 |
| qiqiu1 | 0.339806 | 0.247573 | 0.271845 |
| raokouling | 0.054054 | 0.054054 | 0.054054 |
| rap1 | 0.182081 | 0.182081 | 0.182081 |
| ru1 | 0.114754 | 0.114754 | 0.114754 |

The continuation workers finish all five remaining samples after each guard failure without changing a cut point or token budget. Both larger-budget whole-audio paths give `qiqiu1` CER 0.2718446602 with 184 output tokens, reproducing the earlier 1024 observation. The 1024 bounded path is better at 0.2475728155. Increasing the output budget again does not help this recording, and the 2048 selected path still fails the longest recording's memory constraint.

## Runner memory ablation

| Fresh-process control | Actual maxRSS |
|---|---:|
| Load all 15 WAVs as Python floats, no model | 322,191,360 bytes |
| Load/release each WAV, no model | 158,007,296 bytes |
| Model initialization only, no inference | 1,607,499,776 bytes |
| Old preloading behavior, 512 whole + segmented diagnostics | 4,329,340,928 bytes |
| Sequential WAV behavior, same 512 diagnostics | 3,574,431,744 bytes |

The diagnostic controls produce exactly the same 15 whole outputs and the same 15 selected outputs. Whole-audio macro CER is 0.2276163 and corpus CER is 1963/3173 = 0.6186574; selected CER reproduces the established 659/3173 result. The runner fix removes 754,909,184 bytes from the observed full diagnostic high-water RSS in this paired host run. The WAV-only control separately confirms the Python audio-lifetime effect. Model activations, allocator retention, and the long whole-audio diagnostic account for substantial additional memory; the WAV list is not the sole memory cost.

The initial validation pass still reads each complete PCM payload to preserve fail-before-model-load handling of a later bad WAV. It discards the bytes immediately. Decoding reads and validates the current file again, including a file changed after preflight. This adds a cheap PCM read while eliminating corpus-sized float retention.

Boundary regression tests use real WAV parsing and weak references to verify that the previous audio has been released before loading the next file. Other new tests preserve later-file malformed-input failure, full reference coverage, and revalidation after preflight. The inference boundary is replaced only in these lifecycle tests; all quality and memory results above use the real model. Validation: 27 ASR runner tests and 19 model-task integration tests passed.

## Limits, failures, and retained evidence

All workers have a 240-second deadline and a 4 GiB sampled process-group RSS guard. Initial diagnostic runs polled every 500 ms; selected-path and recovery runs poll every 100 ms. This is a watchdog, **not an OS-enforced allocation ceiling**: a fast allocation can exceed the threshold before termination. The old preloaded baseline briefly reached 4.032 GiB without being caught at a poll. Reported maxRSS comes from `/usr/bin/time -l`, not from the sampled watchdog peak.

Both 1024 and 2048 whole-audio diagnostic runs were killed on `noise1-en`. Those initial failures killed the time wrapper too, so their actual maxRSS is unavailable; the sampled peak is retained as a lower bound in their run logs. Later guard handling keeps the time wrapper alive to collect the killed worker's high-water RSS. Remaining fixed samples are processed in fresh continuation workers with unchanged model parameters; the failed case stays explicitly failed. A smaller successful subset is never reported as the complete 15-case score.

Raw outputs, full references, every segment, debug/truncation logs, per-case CER/RTF, model-load timing, memory snapshots, process commands, and guard failures are retained at:

`$AURALIS_CACHE/asr/experiments/`

- `context-2026-09-07/`: audio-only, model-only, old-preload, and whole/selected diagnostic controls.
- `context-selected-2026-09-07/`: the three selected-only policies.
- `context-recovery-*/`: remaining fixed samples after killed workers; original failures are preserved.
- `asr-context-provenance.json`: six verified model files, complete input hashes, pinned source hash.
- `asr-context-all-results.json` and `asr-context-all-outputs.md`: consolidated results including failures and continuations.

Reproduction:

```sh
$AURALIS_CACHE/asr/venv/bin/python \
  docs/specs/2026-09-05-auralis/benchmarks/asr_context_ablation.py \
  --output $AURALIS_CACHE/asr/experiments/context-2026-09-07

$AURALIS_CACHE/asr/venv/bin/python \
  docs/specs/2026-09-05-auralis/benchmarks/asr_context_ablation.py \
  --selected-only \
  --output $AURALIS_CACHE/asr/experiments/context-selected-2026-09-07
```

## Occam selection

Keep the sequential WAV fix. It preserves observable decoding behavior and removes unnecessary corpus-sized storage without a new abstraction or a changed public interface.

Use **1024/384 with bounded 45-second energy segments** as the next higher-quality candidate for device acceptance. It improves both difficult long recordings and fits this experiment's guard, but requires about 450 MiB more peak host RSS than the 512 selected path. Existing constrained-device defaults should remain unchanged until real Android/iOS memory, latency, and quality limits are declared and met.

Reject **2048/768 with a 95-second limit** as the default candidate under this memory constraint. A token-capacity calculation alone does not bound encoder/prefill working memory. Do not add retries, per-case cut-point tuning, or a more complex policy on the strength of this small set. The set supports a simpler bounded 1024 candidate; it does not prove that all possible 2048 configurations are unusable.

All timing is a development-host observation under concurrent workloads. It does not establish physical-phone RTF, energy use, a latency regression, or leading performance. Full Xcode/device validation and a separately held-out multilingual quality corpus remain outstanding.
