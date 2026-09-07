# lane-A report — runtime lane (session / inference / audio / cpp / jniLibs docs)

Owner: lane A. Status: **code-complete**, Kotlin unit subset 61/61 green.
Date: 2026-09-05.

## 1. Delivered

| Area | Files | Notes |
|---|---|---|
| Session contract | `session/SessionContracts.kt` | Frozen spec-02-R02 types verbatim + `VoiceProfileResolver`, `SessionStartException` |
| Orchestrator | `inference/PipelineOrchestrator.kt` | Direct `SessionController` impl. Atomic reducer, drop-new queue (cap 2 → DROPPED + counter), serial worker, half-duplex playback gate, stop order STOPPING→unblock→native cancel→join→drain→release→IDLE, `modelLease` acquired before first load / returned after all releases on every teardown path |
| Audio | `audio/` (Ports, Recorder, Player, VAD, Segmenter) | VAD: time-constant smoothing (τ≈557 ms ≙ α=0.30 at 200 ms chunks — chunk-size-invariant decay), raw-RMS voiced-extent tracking (decay lag excluded from min-speech), onset/trailing clocks sample-count driven. Segmenter: 200 ms preroll cap, 10 s force split, min-speech 200 ms. Recorder: lint-visible inline `checkSelfPermission` guard on the real construction path + `SecurityException` conversion for mid-path revocation (`hasPermission` port kept for tests) |
| Inference | `inference/` (Asr/Tts/HyMt/Native/Onnx/Cache/Protocol/Extractor) | Ports + strict protocol validation; no fabricated tokens/embeddings; `NativeHyMtRuntime` stateLock serializes load/cancel/release (cancel is a lock-protected native flag write — UAF-safe); release returns to unloaded state (same engine instance can serve a later session); OnnxModelManager: no NNAPI/extension-status claims, session options closed via finally; `TranslationCache` keyed with modelId |
| Native | `src/main/cpp/hymt_jni/` (CMakeLists, hymt_jni.cpp, README) + `jniLibs/README.md` | JNI bridge mirroring `NativeHyMtRuntime` entry points; greedy decode, 256-token budget, abort callback flag; CMake links pinned llama.cpp static libs |

## 2. Verification (real commands, real results)

Full-Gradle unit suite (reviewer environment, directed runs by lane A where noted):

```
./gradlew :app:testDebugUnitTest   → 114 tests, 0 failures
```

Standalone compile+test script `/tmp/laneA-test.sh` (kotlinc-embeddable 2.1.0 +
cached coroutines/junit jars; android.jar for android-35), run in three groups
covering `audio/`, `inference/`, `session/` test sources:

```
JUnit version 4.13.2
............................................................
Time: 0.71
OK (61 tests)
```

61/61 pass, including:
- lease lifecycle: acquire before load (`listOf("acquire")` observed while a
  turn is blocked in translate), release only after the in-flight native call
  returns (`["acquire","translateReturned","release"]`), lease returned on
  failed start (`["acquire","release"]`).
- Hy-MT abort: `translate` surfaces `CancellationException(cause=AbortedException)`;
  explicit `engine.cancel()` reaches the runtime once.
- No-profile semantics: `voiceProfileId == null` → ACTIVE with no session
  problem; MT-only turns COMPLETE with translation; no synthesis attempted.
- Profile requested + invalid → `voice_profile_missing`; profile valid +
  TTS model load failure → `tts_unavailable`; both keep the real translation.
- Empty transcription → `asr_empty` with committed turn (real failure visible).

Main-source compile of all lane-A files is exercised by the same script (it
compiles `src/main/java/com/dialect/interpreter/{audio,inference,session}` plus
the test sources before running). Full-Gradle build remains with the reviewer;
B's report already confirms `assembleDebug` passes with A on the tree.

## 3. Fix history (failures encountered, all resolved)

1. VAD `speechDurationMs` measured smoothing decay (600 ms tail counted as
   speech) → replaced with raw-RMS voiced extent; blips <200 ms now rejected.
2. VAD smoothing was per-chunk-count (chunk-size dependent decay) →
   time-constant smoothing; clock test asserts completion time is chunk-size
   invariant (ratio 0.6–1.4; a count-based clock gives 0.5 and fails).
3. Segmenter preroll test bound was too tight (excluded designed decay/trailing
   tail) → bounded to speech+tail+preroll envelope.
4. Hy-MT cancel test asserted `cancelCalls==1` without ever calling `cancel()`
   → split: abort→CancellationException is translate's job; `cancel()` is an
   explicit stop-path request.
5. Orchestrator treated missing voice profile as a session-wide problem →
   revised to spec-correct semantics (null profile = normal text-only mode);
   per-turn degradation codes only when a profile was requested.
6. `empty transcription` fake threw instead of returning blank → returns
   `TranscriptionResult("")` exercising the `asr_empty` path.
7. `OnnxModelManager.createSessionOptions` leaked → finally-closed.
8. `NativeHyMtRuntime` had a permanent `released` flag → release frees the
   handle and resets; cancel/release share `stateLock` (UAF-safe).
9. Gradle 定向复跑发现 cancel 测试断言 `expected.cause is AbortedException` 失败：
   实际 cause chain（打印定位）为 CancellationException ← CancellationException
   （coroutine stack-trace recovery 追加层）← AbortedException。断言改为沿
   `cause` 链深查 AbortedException（原始原因保留），Gradle 定向跑通过。
10. JNI 返工（主审审核意见逐条落地，仅 A 区文件）：
    ① nativeRelease 在锁作用域外 delete handle（guard 析构不再触碰已释放 mutex）；
    ② 生成循环 token 统一存于 `tokens` 向量（batch 指针跨轮有效），logits 取
    `llama_get_logits_ith(ctx, -1)` 且 null/finite 检查，`token_to_piece` 负返回扩容重试；
    ③ 每次 translate 先 `llama_memory_clear(llama_get_memory(ctx), true)`；
    ④ prompt 用 GGUF 自带 chat template（`llama_model_chat_template` +
    `llama_chat_apply_template`，缺失/不支持即硬错误，不手造模板）；prefill 分批 ≤
    `llama_n_batch`；prompt+256 预算 > n_ctx 直接报错；
    ⑤ Java 字符串经 `String.getBytes("UTF-8")` / `new String(byte[], "UTF-8")` 转
    真实 UTF-8（不再用 modified-UTF8 的 GetStringUTFChars/NewStringUTF），emoji/扩展
    汉字保留；C++ 异常全部捕获后 `ThrowNew`，不穿 JNI 边界；
    ⑥ CMake 改为 `add_subdirectory` 固定 checkout 链接上游 `llama` target，
    configure 期 git 校验 commit == pin。语法检查：host clang++ + JDK23 JNI 头 +
    pinned llama.h 通过（无 Android SDK 依赖）；NDK 编译/真机 smoke 仍 blocked。
    期间修正一处 API 认知：pinned 头无 `llama_vocab_chat_template`，实际为
    `llama_model_chat_template(model, name)`（以真实头文件编译为准）。
9. lintDebug `AudioRecorder.kt:68 MissingPermission` → inline
   `checkSelfPermission` guard on both real construction paths (`start`,
   `recordFixedDuration`), `@SuppressLint` removed, `SecurityException` handled
   as recoverable capture failure. No suppression/baseline used.
   **Not yet re-run by lane A** — final lint pass is the reviewer's Gradle run.

## 4. Native revision — independently verified (partially blocked)

Verified via PR page/commit-list inspection (2026-09-05):

- llama.cpp **PR #22836 (STQ1_0 + ARM NEON vec_dot) is NOT merged upstream**.
- Pinned revision: PR branch commit `1e411d8f5a1e23525fa3265dfb4bd76265465397`
  (branch commits `5503c4b`, `5165daa`, `1e411d8`). Documented in
  `cpp/hymt_jni/README.md` and `jniLibs/README.md`.
- Caveat from the PR/model card: prebuilt HF GGUF may carry an older quant
  type id — re-quantize from BF16 with `llama-quantize` at the pinned revision
  if load fails.
- **Blocked**: no local llama.cpp checkout/NDK build here, so the pinned
  revision has not been compiled nor smoke-tested on device; the `.so` build +
  device gate remains with the reviewer (spec 01 C02).

## 5. Ablation / Occam notes

- No delegate layer over `SessionController` (direct implementation).
- No DROP_OLDEST, no priority queue — single bounded channel + drop-new.
- No coroutines-test dependency; tests use `runBlocking` + real dispatchers.
- No compatibility shims for the removed `events/state/telemetry` API.
- `hasPermission()` port kept (tests use it) even though the production path
  now uses an inline lint-visible guard — minimal, not duplicated logic.
- Voice profile "null mode" handling is one branch each in
  `announceDegradations`/`processTurn`; no separate TextOnlySession class.

## 6. Handoff items for lane B (see also reports/interface-A.md)

- `acquireModelLease = modelRepository::acquireForSession` (already wired in
  AppContainer).
- `resolveSpeakerEmbedding(profileId)` must return a real 256-d embedding or
  null — never a zero vector.
- Per-controller `OnnxModelManager` instances (AppContainer already does this).
- `voiceProfileId == null` renders as normal text-only mode — no warning UI.
- `MainActivity.onDestroy { modelManager.releaseAll() }` must be removed.
