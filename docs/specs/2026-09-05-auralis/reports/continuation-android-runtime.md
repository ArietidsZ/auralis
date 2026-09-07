# Continuation — Android ASR runtime integration & acceptance

2026-09-07. Lane: Pi GLM max, independent Android integration acceptance of
the sherpa-onnx Qwen3-ASR path (task list per resume order). Previous lane's
"ORT isolation / segmentation production-ready" claims were **not** accepted;
everything below is re-measured on the dedicated emulator.

## Environment (fixed for all numbers below)

| item | value |
|---|---|
| device | emulator-5580, AVD `Auralis_API35_arm64` (persistent cache `~/Library/Caches/Auralis/android/avd`) |
| image | system-images;android-35;default;arm64-v8a rev2, security patch 2024-09-05 |
| pagesize / RAM / vCPU | 4096 (4 KiB — 16 KiB compliance not exercisable here) / 8,129,464 kB / 4 |
| SDK / NDK | /opt/homebrew/share/android-commandlinetools / 27.3.13750724 |
| model | `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`, archive sha256 `393f8a14…ee96` == shared/model-manifests/asr.json; all 6 manifest files re-hashed OK after extraction **and** again on-device after `adb push` (decoder `4f6885be…`, encoder `60748d3e…`, conv `d22dc442…`) |
| runtime | sherpa-onnx 1.13.7 jniLibs (commit 917bed95), ORT 1.27.1 renamed `libonnxrtnsher.so`; AAR `onnxruntime-android:1.22.0` |

Model staging: `run-as` copy into `files/dialect_models/asr/` (exact
production path; no UI, no draft-status bypass — asr.json stays draft).

## Gradle (serial, this lane only)

| task | result |
|---|---|
| `:app:testDebugUnitTest` | **134 tests, 0 failures** (incl. 3 new AsrLanguageProtocolTest) |
| `:app:assembleDebug` / `:app:assembleDebugAndroidTest` / `:app:assembleRelease` | BUILD SUCCESSFUL (release = R8 minified, unsigned) |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| targeted connected (via `am instrument -e class …`) | **13 test methods, all OK** across 5 fresh-process invocations |

APK sha256 (final binaries):

- `app-debug.apk` `84eb5e070a1424a2d7a2df2a0616c7db81e59256147b7d2119dc2836309c1ec5`
- `app-debug-androidTest.apk` `33849d741a40d027985431e68a49c2c172b94e2fe60fb99f6d8f853f9beb0960`
- `app-release-unsigned.apk` `73ce9b9ae1cecd824240fe6d85c29ecba6e061dec93d1af206fcb466b8070eae`

## 1. Dual-ORT ABI coexistence — both load orders, real inference

Tested in **separate fresh processes** (`am force-stop` between), because
load order is a process-global property:

- `OrtFirstCoexistenceTest` — Java ORT 1.22 first, then sherpa native.
- `AsrFirstCoexistenceTest` — sherpa native first (this process also proves
  the lazy-env fix: the 1.22 AAR is **not** mapped until the test itself
  creates `OrtEnvironment`).

Each test: binding-probe graph execution via the Java API (output
`[11,22,33,44]` = A+B, exact), `SherpaJni.load()`, full real ASR load,
`de.wav` decode, alternation run/release in both directions, then
`/proc/self/maps` inspection. Libraries are loaded directly from `base.apk`
(`extractNativeLibs=false`), so presence is proven by matching each APK
entry's data offset (ELF first PT_LOAD, 4 KiB zipalign-aligned) against maps
file offsets — file-level identity, not `mergeNativeLibs` packaging.

Result (both orders): `libonnxruntime.so` (1.22, Java), `libonnxruntime4j_jni.so`,
`libonnxrtnsher.so` (1.27.1) and `libsherpa-onnx-jni.so` **resident
simultaneously**; `de_cer = 0.0` on device in both orders; Java ORT version
reported `1.22.0` while sherpa decoded correctly — the versioned
`OrtGetApiBase@VERS_1.27.1` verneed in `libsherpa-onnx-jni.so` resolves
against its own NEEDED `libonnxrtnsher.so` (bionic resolves through the
caller's DT_NEEDED closure; the AAR lib is not in sherpa's closure). Had
interposition pulled 1.22, `GetApi(27)` would abort recognizer creation.

- **TTS-bundle real graph**: `TtsGraphCoexistenceTest` ran the real
  `tts/hf/speaker_encoder.onnx` (ECAPA-TDNN, `mel_spectrogram →
  speaker_embedding[1,1024]`, real de.wav → 24 kHz → 128-dim log-mel via
  `Qwen3TtsProtocol`) through Java ORT 1.22 **while the native 1.27.1 held a
  live ASR recognizer**, alternating with real ASR decode and surviving
  `engine.release()`. Embedding finite. A handcrafted 179-byte ONNX was used
  only as the always-available Java-API binding probe, never as TTS quality.
- **Real TTS synthesis quality**: not in this lane (TTS worker owns
  TtsEngine); the speaker-encoder run proves runtime coexistence only.

## 2. Android Kotlin ASR vs host (same WAVs, same references, same CER
normalization — char edit distance, whitespace-stripped)

All decoding on emulator-5580 (ARM64, 4 vCPU, 4 threads
`min(cores,4)`); host numbers from `report-segmented.json` (2 threads,
Silero VAD). These are **device-measured** numbers, not borrowed ones.

| sample | dur s | device CER | host CER (seg) | device segments | device RTF | note |
|---|---:|---:|---:|---:|---:|---|
| de (German) | 6.72 | **0.0** | 0.0 | 1 | 0.140 | exact match |
| fast1 (short Chinese) | 11.38 | **0.2042** | 0.1972 | 1 | 0.241 | 44.1 kHz resampled by sherpa |
| noise2 (Chinese) | 22.83 | **0.3710** | 0.3548 | 1 | 0.130 | whole clip |
| rap1 (29 s KV-margin) | 28.98 | **0.1647** | 0.1821 | 1 | 0.175 | no truncation; hyp_len 359 vs ref 346 |
| noise1-en (long noise) | 88.19 | **0.2023** | 0.1922 (Silero, 6 pcs) | 6 (energy split) | 0.224 | no `language` sentinel, no collapse |

Honest differences: Android uses **native energy split** (no Silero on
device) — for the 88 s noise sample the measured gap to the host's
Silero-based split is +0.010 CER (energy split 0.2023 vs Silero 0.1922), i.e.
the simpler on-device splitter is not a quality cliff on this sample; single
short samples sit within ~0.01–0.02 of host except noise2 (+0.016). All raw
texts are in the on-device JSON reports (paths below). Emulator RTF is
functional evidence only — **not** a phone physical baseline.

KV budget guards: `boundSegments` contract test (88 s → all pieces ≤ 20 s,
sample count preserved, 10 s stays whole, empty rejected); rap1 whole-clip
decode shows no output truncation at 29 s; `maxTotalLen=512` /
`maxNewTokens=192` passed to the wrapper (fields verified against upstream
JNI at v1.13.7).

Cancel/release/UAF: `AsrLifecycleGuardTest` — transcribe-before-load throws
ISE; release is idempotent and reload works; **release during in-flight
decode joins** (no SIGSEGV, no emit after stop; production `stop()` does
`workerJob.cancel()` + `releaseEnginesQuietly()`, which previously could free
the C++ recognizer mid-decode — fixed with a native-lock join in AsrEngine);
3 concurrent transcribes serialize deterministically.

## 3. Runtime readiness & ELF audit

- Android `ModelRuntimeProbe` does a real engine `load()` (full recognizer
  create) + release — on-device tests pin `true` when the bundle is present
  (probe duration > 100 ms, i.e. a real load), `false` when the decoder file
  is missing, `false` for unknown packages. The iOS check-exists shortcut
  remains the main reviewer's item; Android does not have it.
- 16 KiB ELF audit of the final APK: ASR/sherpa libs, both AAR ORT libs and
  androidx libs are 0x4000-aligned. **`libhymt_jni.so` is 0x1000-only —
  flagged to the MT lane (Grok8fa): must be rebuilt with
  `-Wl,-z,max-page-size=16384` before 16 KiB-page devices ship.** Not
  catchable on this 4 KiB-page emulator.
- Load-failure localization: `nativeloader` logcat lines record the exact
  classloader namespace, source APK and caller dex per `.so`
  (`Load …/base.apk!/lib/arm64-v8a/libsherpa-onnx-jni.so using ns clns-7 …
  ok`); missing-model failures surface as explicit `require` messages with
  the missing file list.

## 4. Ablation (keep only what evidence supports)

- **Kept — dual-ORT rename isolation.** Measured coexistence in both orders
  with real inference; memory cost of the second runtime is ~13 MB RSS
  (Java env) + ~6 MB (sherpa libs, file-backed) vs ~1.03 GB for the ASR
  weights. Unifying on one ORT was rejected: no `onnxruntime-android:1.27.1`
  Maven coordinate (1.27.0 exists, version-string mismatch with the
  verneed), and the 1.22 AAR pin belongs to the TTS lane.
- **Kept — lazy `OrtEnvironment` in `OnnxModelManager`.** Was eager at
  construction, mapping the 1.22 AAR even for ASR-only flows (probes, ASR
  sessions). Now created on first Java-ORT session use. Behavior preserved
  (TTS/MT still get the env on `loadSession`).
- **Kept — one native-lock join in AsrEngine.** Replaced the racy
  `release()`; no additional wrapper/abstraction layer added.
- **Removed — language backfill.** `result.lang` is never filled by the
  pinned Qwen3 impl; engine now reports honest `unknown`, hint mapping uses
  canonical full names (`canonicalAsrLanguage`), unmappable hints dropped.
  `LanguageCodes.normalize` untouched for TTS.
- **Rejected — Silero VAD on device.** silero_vad.onnx is not a manifest
  role; the measured energy-split gap on the 88 s sample is +0.010 CER. Not
  worth a new bundled model without more evidence.

## Limitations (unchanged, not upgraded)

- Emulator-only: functional evidence; no thermal/perf/phone claims. RTF
  numbers are emulator RTFs.
- `asr.json` stays **draft**; device results do not promote it.
- Long-audio segmentation quality rests on one 88 s sample; more noisy/long
  samples would strengthen the energy-vs-Silero comparison.
- Release APK is unsigned and untested on-device (install = debug);
  baselineProfiles present.
- TTS synthesis quality/RTF, MT, and iOS remain their own lanes.

## Round 2 — chief-reviewer integration pass (2026-09-07)

### R1. libhymt_jni.so: history vs current state

- **Historical (earlier round, stale binary):** LOAD segment alignment was
  0x1000 — flagged as an MT-lane defect.
- **Current (Grok rebuilt; verified this round, twice):** repo file and the
  APK-embedded copy are byte-identical, sha256
  `4dfa0e6bc7d2a35d1d4dc00c4f65737817803a7d4efb2dee4fe4d58b037b1d4a`, and all
  three LOAD segments are `0x4000`. Every `.so` in app-debug.apk is now
  16 KiB-aligned (`llvm-readelf -l` over `lib/arm64-v8a/*`). The earlier
  finding is closed as historical; no open alignment defects remain in the
  APK.

### R2. MT JNI smoke — real model on the emulator (fresh process)

`MtJniSmokeTest` (new androidTest) against the CURRENT production wiring:
`files/dialect_models/mt/Hy-MT1.5-1.8B-1.25bit-stq43.gguf` +
`OnnxModelManager.HY_MT_GGUF`, `System.loadLibrary("hymt_jni")` from the
re-built APK:

- Device-side gguf sha256 `e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971`
  — equals the derived hash pinned in `shared/model-manifests/mt.json`
  (`source.transform`: input `93e025c9…` → derived `e42935e2…`, runtime pin
  `1e411d8f…`). Push, manifest, and binary agree.
- Real translation: 「今天天气很好，我们一起去公园散步吧。」 →
  "Today, the weather is great. Let's go for a walk in the park together."
  (1.35 s, Hy-MT1.5-1.8B-1.25bit runtime label).
- Cancel path (12×-repeated long input, cancel at ~400 ms): aborted without
  crash; release×2 idempotent; release→reload→translate OK.
- Emulator timing, not a phone baseline. Evidence: `mt_jni_smoke.json`.

### R3. ModelManifestsTest — root cause was test-harness, not parser

- Failing fixture: `transform-input-traversal.json` (canonical code
  `path-escape`) carries a top-level `$expectedError` metadata key. The
  Python runner pops it before validation
  (`convert/manifest_contract.py:663-670`); the root's strict Kotlin reader
  treats it as an unknown top-level key → `bad-value`. Python reported all
  47 fixtures passing — the disagreement was harness convention.
- Fix (this lane, test only): `stripExpectedErrorMeta()` mirrors the Python
  contract — `$expectedError` is stripped before `ModelManifests.parse`, and
  the **declared** code is asserted instead of a hard-coded one, so future
  canonical-code changes flow through automatically. The strict parser
  itself is untouched (real manifests still reject `$expectedError`).
- Result: `testDebugUnitTest` **136 tests, 0 failures** (was 133 pass / 1
  fail).

### R4. Language boundary — auto ASR hint, configured MT source

- New JVM regression `asr hint stays auto while mt keeps configured source
  language`: `FakeRecognizer.capturedLanguages == ["auto"]` and
  `FakeTranslator.capturedSources == ["Chinese"]` after a full turn —
  exactly root's ablation outcome (canonical hints degraded zh/yue).
- `AsrLanguageProtocolTest`: `canonicalAsrLanguage("auto"/"AUTO") == null`
  — auto is a control word, never force-mapped into a prompt.
- No production call-site changes (orchestrator already sends `"auto"`);
  explicit ASR hints remain available for diagnostics.

### R5. Stop/release hardening — Main never blocks, old turns never emit

AsrEngine.release() redesigned (was: join-inside-release on the caller
thread → Main could block for a whole decode):

1. `releasedGate.compareAndSet(false,true)` synchronously — idempotent, and
   immediately rejects new `transcribe` (no emit after stop) even while an
   old decode is still draining.
2. Snapshot of the doomed recognizer via `@Volatile` fields WITHOUT taking
   `nativeLock` (the lock is held for the duration of an in-flight decode;
   acquiring it on Main would block for seconds → ANR).
3. Daemon joiner thread: `synchronized(nativeLock) { doomed.release() }` —
   the join IS the lock acquisition, so the C++ recognizer is freed only
   after the in-flight decode returns (no UAF).
4. Non-Main callers `join()` the joiner for deterministic teardown (probes,
   tests); Main returns immediately. `load()` re-arms the gate under the
   lock, enabling reload after release.
5. Android instrumentation test `AsrMainThreadReleaseTest` (Main looper,
   real engine): releases mid-decode from a Main-handler — returns in
   <300 ms, a post-release transcribe throws ISE, the in-flight decode
   completes without UAF, reload+decode+release cycle passes (6 lifecycle
   tests green).

### R6. Build/integration status & TTS full-graph feasibility

- Gradle (serial): unit 136/136, `compileDebugAndroidTestKotlin`,
  `assembleDebug`, `assembleDebugAndroidTest`, `assembleRelease`,
  `lintDebug` — all green with the TTS lane's current staged sources.
- **One-line compile repair in a TTS-lane file** (reported, not owned):
  `Qwen3TtsProtocolTest.kt` imported `org.junit.Assert.assertFailsWith`,
  which does not exist in JUnit 4 (and `kotlin.test` is not a dependency);
  replaced with the direct JUnit 4.13 equivalent `Assert.assertThrows` at
  the single ragged-frames call site. Observation for the TTS lane: that
  test function currently lacks an `@Test` annotation, so it would not run
  even once compiling — left untouched (TTS lane's active file).
- Final APK hashes (this round): debug `1a2d80c6…fd30c`, release-unsigned
  `d9cdbe68…8eefc`; embedded `libhymt_jni.so` = `4dfa0e6b…` @ 0x4000
  (verified by unzipping the final APK, not just the repo file).
- Minimal affected instrumentation re-run in fresh processes: MT smoke (1),
  Main-thread release + lifecycle (6), ORT-first (1), ASR-first + TTS graph
  (2), quality + readiness (4) — all OK. Quality numbers unchanged
  (de 0.0000 / fast1 0.2042 / noise2 0.3710 / rap1 0.1647 / noise1-en 0.2023).
- **TTS full-graph synthesis on this 8 GB emulator: NOT viable as-is.**
  Static weight math (hash-verified): `talker_prefill.onnx.data` and
  `talker_decode.onnx.data` are byte-identical (1692 MB each, sha256
  `4d8e742a…`), plus vocoder 435 MB, code predictor 420 MB, speaker encoder
  34 MB → 2.58 GB unique / 4.27 GB if ORT maps both .data files separately;
  + ASR ~0.6 GB int8 + MT gguf 462 MB → ≈5.4 GB resident before activations
  and KV caches, on an 8 GB emulator that also runs the OS. Interface gaps
  owned by the TTS lane (not assumed by ASR evidence): (a) external-data
  dedup so prefill/decode share one mapping (−1.65 GB), (b) bounded
  streaming decode, (c) a memory-budget gate in `ModelRuntimeProbe` before
  `synthesize` is allowed. No TTS readiness is claimed from the speaker
  graph test — it only proves ORT coexistence, not synthesis quality.

## Evidence file paths

On-device (pulled via adb, per-run JSON):
`/storage/emulated/0/Android/data/com.dialect.interpreter/files/asr_validation/`:
`ort_first_coexistence.json`, `asr_first_coexistence.json`,
`device_asr_quality.json`, `tts_graph_coexistence.json`,
`memory_ablation.json`, `mt_jni_smoke.json` (round 2). Full logcat retained
on the host for the runs above; lint report
`android/app/build/reports/lint-results-debug.html`.
