# iOS TTS runtime review — 2026-09-08

Baseline: `v0.1.0-preview.1` (`7e2cb57927d6290c089dce1c33a72bbc9902b6cc`).
Measurements below use macOS arm64 and the official ONNX Runtime 1.24.2 bindings. They do not establish iPhone performance or model readiness.

## Changes retained

- Keep talker, code-predictor and streaming-vocoder KV outputs as `OnnxTensor` values and feed them directly into the next graph call. Preserve shape, dtype, finite-value and cancellation checks. API1 still stacks its separate per-layer prefill outputs once, as required by that graph.
- Reject negative or overflowing input shapes before constructing a tensor. Scalar and zero-extent inputs remain supported.
- Share float validation and reading through `withFloatBuffer`; `floatArray` only adds materialization. Retain both the `ORTValue` and Foundation wrapper throughout every float/int64 borrow.
- Compute mel magnitudes once per FFT bin instead of once per mel band. Reuse frame-local buffers while preserving arithmetic order. Require at least one valid frame; the sole production caller already rejects references shorter than 1,024 samples.

Each KV output is validated before assignment. A later validation failure unwinds the turn; partially replaced local state is never resumed. Prepared-reference warmup state remains an immutable value snapshot. Sampling, precision, model files, public APIs and manifest readiness are unchanged.

## Copy accounting

A **set** below contains both keys and values. The old path materialized a set and reconstructed it as input; the new feedback path performs neither operation. Logical transfer counts describe payload movement, not measured memory bandwidth or allocator traffic.

| State | One set | Removed logical output + input transfer |
|---|---|---|
| Talker `[28,1,8,T,128]` × 2 | `229,376 × T` bytes | `458,752 × T` bytes per step; 88,539,136 bytes at T=193 |
| Code predictor `[5,1,8,T,128]` × 2 | `40,960 × T` bytes | `81,920 × T` bytes per group step |
| Streaming vocoder, conv + KV | 5,193,984 bytes | 10,387,968 bytes per step; normally four audio-code frames per step |

**CP history resets every audio frame.** Its first group consumes two positions, then the remaining groups add one each: present lengths are 2…16. It does not accumulate `15 × audioFrames` positions. Earlier estimates using T=959 were incorrect; at T=16 the removed logical transfer is 1,310,720 bytes per group step.

The host probes also distinguish three read paths:

| Read path | Observed host payload copies |
|---|---|
| Native KV feedback | 0 managed copies; never calls `tensorData()` |
| `withFloatBuffer` | 1 Foundation snapshot |
| Current `floatArray` | Snapshot + Array = 2 |
| Baseline `floatArray` | Snapshot + Data bridge + Array = 3 |

Although ORT's Objective-C source requests a no-copy `NSMutableData` wrapper, the tested Foundation implementation returned an independent snapshot. A separate Objective-C probe reproduced that behavior without Swift bridging. Other platforms may alias the storage, so both owners are retained during borrowing. Tests assert values and lifetimes, not pointer identity. No private binding or extra ownership framework was added.

## Validation

| Check | Result |
|---|---|
| Repository Swift Testing suites | 23 tests / 3 suites pass against real ORT, including the final shared-reader cleanup |
| Synthetic Concat/MatMul graph | 64-step native feedback equals the materialize/rebuild twin bit for bit |
| Synthetic Identity graph | Retained step-zero output remains unchanged through 63 later runs |
| Tensor boundaries | Negative/overflowing dimensions and wrong dtype throw; scalar and empty tensors round-trip |
| Mel regression | LCG, impulse and DC goldens, frame counts and reflection semantics pass |
| Independent mel comparison | 27 input cases, including a 30-second reference and extreme floats, match baseline bit patterns |
| Full-model baseline/candidate comparison | Three serial runs of each binary; all 20 checks per run pass; all eight WAV hashes match across all six runs |
| Full-model final shared-reader check | 20 checks pass; eight WAVs match baseline byte for byte; [source-bound evidence](ios-runtime-evidence.json) |

The synthetic graphs are explicitly synthetic runtime fixtures. Full-model checks use the real API2 talker, reference encoder, code predictor and streaming vocoder, with references 121/260, Chinese/English targets and ICL/xvector modes. Parameters are fixed at `maxFrames=384`, `seed=2026_0906`.

All six baseline/candidate runs agree on encoder codes (R=106/88), eight synthesis frame counts (39/34/39/37/39/34/43/34), sink-error recovery, no-EOS rejection, cancellation and release invalidation. Chunk/full-vocoder maximum difference remains `2.8759241104125977e-06`. Cancellation returned in 0.05–0.11 seconds on this host. Every eight-WAV SHA list has digest `eab0d3022a233b78…`.

The new tests are part of the existing Xcode test target. Host execution does not replace the integrated iOS simulator CI run or physical-device qualification. The scratch host build links macOS 27 native artifacts; its deployment-target linker warnings do not establish macOS 14 compatibility.

## Measurements and ablation

Maximum RSS in the three full-model runs was 5.56/4.93/4.95 GB for baseline and 4.25/4.61/4.73 GB for the KV candidate. These process peaks exceed the initial 3–3.5 GB estimate. Per-synthesis wall time varied from 4.1 to 10.6 seconds, with within-binary variation comparable to differences between candidates; no end-to-end speedup is claimed.

| Candidate | Decision | Evidence or cost |
|---|---|---|
| Native KV feedback | Keep | Full-model WAV equality; eliminates repeated materialization and reconstruction |
| Shared float reader | Keep | Removes duplicate validation/borrow logic; 23 runtime tests pass |
| One magnitude calculation per bin | Keep | Bit-pattern comparison passes; function-level improvement about 1.1–1.2×, less than 1% end to end |
| FFT twiddle cache | Omit | Estimated total benefit too small to justify another retained cache |
| Partial KV conversion | Omit | Keeps two state representations while retaining part of the copy cost |
| Additional owner/retention framework | Omit | Graph lifetime checks pass with ordinary ORTValue ownership and scoped borrowing |
| Private Objective-C/C++ tensor bridge | Omit | The remaining snapshot is used only where values must be inspected; no measured benefit justifies a second binding path |

## Reproduction and attribution

Private evidence root: `$AURALIS_CACHE/reports/ios-runtime-20260908/`. Model weights, reference recordings and generated WAVs are excluded from Git.

- `baseline-src/`: nine files verified against `7e2cb57`; baseline TTS SHA starts `d539cf9b`, ORT wrapper `1ba2ef44`.
- `candidate-src/`: the six-run KV candidate. Binary hashes start `a71ed564` (baseline) and `d46d4202` (candidate). Subsequent first review changed comments only.
- `build-full.sh`: one compilation recipe for both source directories. The frozen harness is `$AURALIS_CACHE/reports/ios-tts-api2/freeze/harness.swift`, SHA starts `aa3bb814`.
- `full/{baseline,candidate}-r{1,2,3}/`: independent results, logs, process peaks and WAV hash lists; `full-run-summary.txt` records six exit-zero results.
- `root-final-src/`, `root-final-source-shas.json`: source snapshot after float-reader consolidation and explicit int64-owner retention. `testrun/root-final-swift-test.log` records 23 passing tests; `full/root-final-r1/` keeps its separate full-model result and binary/source hashes.

The final source needs integrated CI before merging. Physical-device latency, memory and energy gates remain open; shared packages remain draft.
