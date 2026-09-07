# Continuation — Grok ASR lane

2026-09-06. Independent audit of the GLM sherpa-onnx Qwen3-ASR path, long-audio
segmentation, and replacement of the handwritten 80-mel/INT4 engines with
maintainer sherpa-onnx on Android JNI + iOS C API.

`convert/validate_models.py` and shared schema remain root-owned. No new
`runtime.backend` / role types are required: keep `backend=onnx` as the
compatibility mapping; tokenizer stays three file roles whose parent dir is
the sherpa tokenizer argument.

## Versions (unchanged)

| item | value |
|---|---|
| sherpa-onnx | 1.13.7 / commit `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e` |
| host wheel | `sherpa_onnx-1.13.7` + bundled ORT **1.28.1** (`SherpaOnnxGetOnnxruntimeVersionStr`) |
| Android jniLibs | prebuilt v1.13.7, `libonnxruntime.so` **VERS_1.27.1** |
| TTS AAR | `onnxruntime-android:1.22.0` `libonnxruntime.so` **VERS_1.22.0** |
| model | `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` sha256 `393f8a14…ee96` |
| venv | `/private/tmp/auralis-models/asr-venv` |

## 1. Audit of 15 official test_wavs

References in `/private/tmp/auralis-models/asr/report-all.json` (GLM) and this
lane’s `/private/tmp/auralis-models/asr/report-segmented.json` match
`test_wavs/transcript.txt` 15/15. No prediction used as reference. No clip
dropped.

Independent unsegmented rerun, 2 threads, `max_total_len=512` (default):
mean CER **0.2276**, identical to GLM. raokouling CER **0.0541**, decode
**4.11 s** (root’s independent 4.141 s / 0.0541). de/fr1/ja1 CER 0.

WAV: all 15 are mono 16-bit; channel-ignore bug did not affect these numbers.
Runner now downmixes multi-channel by average and **rejects** header/payload
length mismatch (no silent trim). `read_transcript_index` splits on any
whitespace (spaces and tabs).

Exit codes aligned with `validate_models.py`: 0 ok, 1 inference fail, 2
missing dep/artifact, 3 unused here (contract is central), 4 args/malformed
input.

## 2. KV 512 and long audio

Decoder graph input `cache_key_0`: `[batch, max_total_len, 8, 128]`, 28
layers. `max_total_len` is an ONNX **dim_param**, not a baked 512.
sherpa-onnx sets `model_max_len` from config when the dim is dynamic, then
`min(model_max_len, option)`. Default 512 is the production cap.

Unsegmented 51 s / 88 s: sherpa logs
`context_len (1162|677) exceeds max_total_len (512). Truncating
audio_token_len → keep_audio=497` and emits `language`. CER 1.0 / 0.995.
Kept in the report.

Ablation `--max-total-len 1024 --segment off` on qiqiu1: no truncation,
CER **0.2718**, decode 11.2 s. Symbolic dim is real; 1024 is not the mobile
default (KV RAM). 88 s still needs split at 1024.

Production path: Silero VAD (`silero_vad.onnx`) **only to choose time
ranges**. Decode always gets original PCM + native rate so sherpa’s
anti-aliased resampler owns 44.1 kHz. Trigger 36 s (rap1 29 s stays whole);
pieces ≤ 20 s; `max_new_tokens=192`. Energy split if VAD empty.

| clip | dur s | CER unseg | CER seg | nseg | method | trunc | RTF unseg | RTF seg |
|---|---:|---:|---:|---:|---|---|---:|---:|
| ar1 | 5.250 | 0.0345 | 0.0345 | 1 | whole | n | 0.188 | 0.184 |
| cantonese | 16.450 | 0.0865 | 0.0865 | 1 | whole | n | 0.211 | 0.209 |
| codeswitch | 6.230 | 0.2679 | 0.2679 | 1 | whole | n | 0.141 | 0.144 |
| de | 6.720 | 0.0000 | 0.0000 | 1 | whole | n | 0.143 | 0.144 |
| es1 | 5.150 | 0.0500 | 0.0500 | 1 | whole | n | 0.145 | 0.145 |
| f1_noise | 19.020 | 0.0775 | 0.0775 | 1 | whole | n | 0.124 | 0.124 |
| fast1 | 11.380 | 0.1972 | 0.1972 | 1 | whole | n | 0.229 | 0.224 |
| fr1 | 5.996 | 0.0000 | 0.0000 | 1 | whole | n | 0.142 | 0.142 |
| ja1 | 5.080 | 0.0000 | 0.0000 | 1 | whole | n | 0.144 | 0.145 |
| noise1-en | 88.193 | 0.9950 | **0.1922** | 6 | silero-vad | **y** | 0.054 | 0.199 |
| noise2 | 22.833 | 0.3548 | 0.3548 | 1 | whole | n | 0.137 | 0.136 |
| qiqiu1 | 50.964 | 1.0000 | **0.3398** | 3 | silero-vad | **y** | 0.064 | 0.154 |
| raokouling | 20.760 | 0.0541 | 0.0541 | 1 | whole | n | 0.198 | 0.203 |
| rap1 | 28.982 | 0.1821 | 0.1821 | 1 | whole | n | 0.182 | 0.177 |
| ru1 | 4.760 | 0.1148 | 0.1148 | 1 | whole | n | 0.176 | 0.177 |

mean CER unsegmented **0.2276** (truncation visible). mean CER segmented
**0.1301**. JSON: `/private/tmp/auralis-models/asr/report-segmented.json`.

qiqiu1 unseg text: `language`. Segmented recovers the lyric with singing
errors (not silence). noise1-en unseg: `language`. Segmented recovers the
noisy dialogue (CER 0.19); one empty VAD slice 0.67–2.97 s is recorded.

## 3. Android: official sherpa JNI, dual ORT

Measured, not guessed:

- sherpa `libonnxruntime.so`: SONAME `libonnxruntime.so`, `VERS_1.27.1`, 21 684 880 B
- AAR 1.22 `libonnxruntime.so`: SONAME `libonnxruntime.so`, `VERS_1.22.0`, 18 214 224 B
- `libsherpa-onnx-jni.so` NEEDED `libonnxruntime.so`; only ORT undef is `OrtGetApiBase` (exported by both). GetApi is versioned (`requested API version [%u] is not available, only [1, %u]`). Same SONAME ⇒ `pickFirst` would break TTS or ASR.
- Maven has `onnxruntime-android:1.27.0`, not 1.27.1.

Isolation: one-occurrence ELF rewrite `libonnxruntime.so` → `libonnxrtnsher.so`
(17 chars). JNI DT_NEEDED updated. Vendored:

- `android/app/src/main/jniLibs/arm64-v8a/libonnxrtnsher.so`
- `android/app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so`

TTS AAR 1.22 unchanged. No `pickFirst`.

Kotlin wrappers from upstream v1.13.7 (`OfflineRecognizer.kt` + deps) with
lazy `SherpaJni.load()` so JVM unit tests that only touch
`AsrEngine.TranscriptionResult` do not `System.loadLibrary`.
`AsrEngine` implements `SpeechRecognizer`; 80-mel/INT4/greedy-piece path
deleted. `featureDim=128`. Clips >36 s energy-split to 20 s.

`./gradlew :app:testDebugUnitTest` **BUILD SUCCESSFUL**.
`:app:mergeDebugNativeLibs` **SUCCESSFUL** (no duplicate SONAME).

## 4. iOS C API (host + Catalyst object, no iphoneos SDK)

Official `c-api.h` (v1.13.7) vendored under
`ios/DialectInterpreter/Inference/SherpaOnnx/`. Thin wrapper
`auralis_sherpa_asr.c` zero-inits `SherpaOnnxOfflineRecognizerConfig` and
calls `SherpaOnnxCreateOfflineRecognizer` / `AcceptWaveformOffline` /
`DecodeOfflineStream`.

Host link against the 1.13.7 wheel dylib:

```
sherpa-onnx 1.13.7
onnxruntime 1.28.1
git 917bed95
available 1
text Raptorium Bergbau scheint profitierter als Monroe als Reaktion auf die wirtschaftlichen Ausfälle zu sein.
```

Catalyst 26.5: `auralis_sherpa_asr.macabi.o` compiled
(`-target arm64-apple-ios17.0-macabi -isysroot MacOSX26.5.sdk`).
Swift `import AuralisSherpaAsr` typecheck on that target: ok.
`AsrEngine.swift` typecheck against the C module: ok.
Full `OnnxModelManager` typecheck still hits pre-existing
`PackageStatus.runtimeUnavailable` / `State.manifestUnavailable` mismatches
(root/UI).

`iphoneos` SDK: **blocked**. No xcframework for device/sim. Not
`alwaysUnsupported` — host binary and Catalyst `-c` exist; device link is
listed separately.

ASR probe in `OnnxModelManager.probeRuntime`: does **not** load tokenizer
JSON/merges as `OrtInferenceSession`. Only checks conv/encoder/decoder
paths exist.

`OrtInferenceFailure` remains in `AsrEngine.swift` (TTS references it).
`decodeTokens` throw-in-nonthrowing is gone (function deleted).

## 5. Tests

`python3 -m unittest discover -s convert/tests -p 'test_asr_runner.py'`:
**23 OK** (metrics, stereo downmix, payload/header mismatch, tab transcripts,
exit 2 vs 4, bounded split).

## Unfinished

- No Android emulator/device inference (root installing API 35 arm64 emulator;
  functional, not a thermal/perf baseline).
- No iphoneos/iphonesimulator sherpa xcframework (no iPhoneOS.sdk).
- Full-app Catalyst typecheck of UI/ORT stack is root.
- `asr.json` stays **draft**; `source.revision` still null (release asset, not
  a git SHA). Do not mark verified.
- Silero VAD model is host-only (`/private/tmp/auralis-models/asr/silero_vad.onnx`);
  not a manifest role. On-device long-utterance safety is energy split.
- qiqiu1 segmented CER 0.34 is singing error, not KV truncation. noise2 CER
  0.35 kept.

## Notify root

ASR lane is stable for **full Gradle** and **real Catalyst typecheck** of the
new C module + `AsrEngine.swift`. Dual-ORT filenames are in jniLibs; do not
add `pickFirst` on `libonnxruntime.so`. No schema change requested.
