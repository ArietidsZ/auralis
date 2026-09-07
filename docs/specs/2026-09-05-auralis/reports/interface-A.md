# interface-A — lane A runtime contract for lanes B/C

Lane A owner: session/, inference/, audio/, src/main/cpp/, jniLibs docs, and tests under
`android/app/src/test/java/com/dialect/interpreter/{session,inference,audio}`.
Status: implemented as described below. Changes after 2026-09-05 will be appended at the bottom.

## 1. Frozen session contract (delivered)

`android/app/src/main/java/com/dialect/interpreter/session/SessionContracts.kt` contains the frozen
types from spec 02 R02 verbatim: `SessionConfig`, `SessionPhase`, `WorkStage`, `TurnStatus`,
`SessionProblem`, `TranscriptTurn`, `SessionSnapshot`, `SessionController`, plus two additions:

- `fun interface VoiceProfileResolver { suspend fun resolve(profileId: String): FloatArray? }`
- `class SessionStartException(val problem: SessionProblem) : IllegalStateException(problem.message)`

B must not create a second Controller/Repository abstraction over these.

## 2. PipelineOrchestrator implements SessionController

`inference/PipelineOrchestrator` directly implements `SessionController` (no delegate layer). New
constructor:

```kotlin
class PipelineOrchestrator(
    private val recognizer: SpeechRecognizer,     // inference/AsrEngine.kt port (AsrEngine implements it)
    private val translator: TranslationEngine,    // inference/TranslationEngine.kt (unchanged class, interface gained cancel())
    private val synthesizer: SpeechSynthesizer,   // inference/TtsEngine.kt port (TtsEngine implements it)
    private val capture: AudioCapture,            // audio/AudioRecorder.kt (implements it)
    private val playback: AudioPlayback,          // audio/AudioPlayer.kt (implements it)
    private val voiceProfiles: VoiceProfileResolver,
    private val limits: RuntimeLimits = RuntimeLimits(),
    private val acquireModelLease: () -> AutoCloseable = { AutoCloseable {} }
) : SessionController
```

`acquireModelLease` acquires the model-store lease (AppContainer passes
`modelRepository::acquireForSession`). Lifecycle: acquired inside `start`'s try
block **before any engine load**; returned only after all engines are released
(on every teardown path: stop, capture failure, start failure). If acquisition
throws, start fails with recoverable `model_store_busy`. Holding the lease
serializes sessions against voice-profile capture so a capture can never read
model files mid-swap.
```

### AppContainer factory for lane B

```kotlin
fun createSessionController(): SessionController = PipelineOrchestrator(
    recognizer = AsrEngine(modelManager),
    translator = HyMtTranslationEngine(
        modelFile = File(modelManager.getModelsDir(), "${OnnxModelManager.MT_DIR}/${OnnxModelManager.HY_MT_GGUF}")
    ),
    synthesizer = TtsEngine(modelManager),
    capture = AudioRecorder(context = context),
    playback = AudioPlayer(),
    voiceProfiles = VoiceProfileResolver { id -> voiceProfileRepository.resolveSpeakerEmbedding(id) }
)
```

- One controller per ViewModel instance (screen entry), like the old `createPipeline()`. The
  orchestrator owns its own `SupervisorJob` scope and all engine handles; it loads models in
  `start()` and releases them in `stop()` (spec R01: engine handles are session-owned; a start→stop
  cycle is one session, session id increments per start).
- **B request 1**: expose `suspend fun resolveSpeakerEmbedding(profileId: String): FloatArray?` on
  the voice profile repository (real extracted embedding for valid non-empty profiles; null
  otherwise). The orchestrator refuses to synthesize without it and never passes a fabricated
  embedding.
- **Voice-profile semantics (revised)**: `SessionConfig.voiceProfileId == null` is the **normal
  text-only mode** — the session starts ACTIVE with no `problem`, MT-only turns complete cleanly
  (`translatedText` set, no audio), and no `tts_unavailable`/`voice_profile_missing` is reported.
  Only a requested-but-unusable profile yields per-turn `voice_profile_missing` (invalid profile)
  or `tts_unavailable` (synthesizer model load failure), each recoverable with the real
  translation kept.
- **Speaker embedding flow**: `TtsEngine.loadSpeakerEncoder()` loads the real
  `SpeakerEmbeddingExtractor` (ONNX wavlm-base-plus-sv); `TtsEngine` validates the profile
  embedding (dimension 256, finite values) and throws `SpeakerVoiceRequiredException` on
  null/invalid — the pipeline degrades that turn, never fabricates tokens or zero vectors.
- **OnnxModelManager isolation**: each `PipelineOrchestrator` must be constructed with its own
  `OnnxModelManager` instance (as AppContainer does inside `createSessionController`) — engines
  hold per-session options/sessions and call `manager.loadSession` concurrently across
  controllers; the manager serializes its own state but session OrtSessions must not be shared.
- **runtimeProbe is a real load**: model status READY is only reported after an actual session
  creation (no extension-inferred READY).

### ViewModel contract for lane B (replaces events/state/telemetry)

- `startSession`: `viewModelScope.launch { try { controller.start(SessionConfig(...)) } catch (e: SessionStartException) { /* surface e.problem */ } }`.
  `start` throws on: already started, closed, RECORD_AUDIO missing, ASR model load failure, mic init
  failure. MT/TTS load failure is NOT fatal — session runs transcript-only with per-turn
  `problem.code` `mt_unavailable` / `tts_unavailable`.
- `stopSession`: `viewModelScope.launch { controller.stop() }`. `stop` suspends through the full
  teardown (unblock capture/playback → cancel native → join → release) and is safe after failure.
- `onCleared`: call `controller.close()` **directly** (non-suspending, idempotent). Never
  `viewModelScope.launch { ... release ... }` — that scope is already cancelled in `onCleared`
  (this was the old leak). Cleanup runs on the orchestrator's own scope.
- Render only from `controller.snapshot` (`collectAsStateWithLifecycle`) and
  `controller.amplitude`. `translatedText == null` ⇒ no translation exists — never substitute
  source text. Turn terminal states are `COMPLETE / FAILED / DROPPED / CANCELLED`; every turn
  reaches exactly one terminal state. `snapshot.droppedTurns` is the queue-overflow counter.
  Waveform amplitude is a separate StateFlow updated at ≤5 Hz.
- The old `PipelineOrchestrator.PipelineEvent`, `PipelineState`, `PipelineTelemetry`, `events`,
  `state`, `telemetry`, `setSpeakerEmbedding` are **removed**. No compatibility shims; rewrite
  InterpretScreen/ViewModel against the snapshot. `InterpretViewModel.onCleared` must stop using
  `pipeline.release()`.
- **B request 2**: remove `MainActivity.onDestroy { modelManager.releaseAll() }`. Activity must not
  release sessions that may be in use (spec R01); per-session engines make it unnecessary.

## 3. Ports lane A owns (fake-friendly)

- `audio/AudioPorts.kt`: `AudioCapture { hasPermission(); start(onChunk: (FloatArray) -> Unit, onReady: () -> Unit = {}); stop(); amplitude }`,
  `AudioPlayback { play(audio: FloatArray, sampleRate: Int); stop(); isPlaying }`.
  `AudioRecorder` guards the real construction path with inline
  `context.checkSelfPermission` (`requireMicPermission`) so `lintDebug`'s MissingPermission
  resolves without suppression, and converts mid-path permission revocation into a
  `SecurityException` (recoverable `capture_failed`).
- `inference/AsrEngine.kt` defines `interface SpeechRecognizer { suspend load(); suspend transcribe(audio, languageHint); release() }`.
- `inference/TtsEngine.kt` defines `interface SpeechSynthesizer { suspend load(); suspend synthesize(text, language, speakerEmbedding: FloatArray?); release() }`.
  `speakerEmbedding == null` ⇒ throws (no zero-vector default); pipeline degrades that turn to
  text-only with `tts_unavailable` / `voice_profile_missing`.
- `TranslationEngine` gained `fun cancel()` (native abort request; safe before/without load).

## 4. Notes for lane C

- No new Gradle dependencies are required by lane A tests (junit4 + coroutines-core already present;
  tests use `runBlocking` and real dispatchers, no coroutines-test needed).
- `OnnxModelManager`: removed the speculative NNAPI `ExecutionProvider`/`selectedProvider` StateFlow
  and the file-extension-based `ModelStatus` READY claims (spec forbids extension-inferred
  support). SettingsScreen/InterpretScreen referenced `selectedProvider` — B must drop those
  references when rewiring screens (the honest EP answer is "CPU (ORT 1.22)" until a device
  comparison says otherwise).
- `android/app/src/main/cpp/hymt_jni/` (CMake + JNI source) is optional to wire via
  `externalNativeBuild`; a prebuilt drop-in into `jniLibs/arm64-v8a/libhymt_jni.so` also works —
  see `jniLibs/README.md`. Neither is required for Kotlin unit tests (the runtime fails fast when
  the library is absent).

## Change log

- 2026-09-05: initial delivery of the contract above.
- 2026-09-05 (rev 2): `acquireModelLease` constructor parameter + lease lifecycle; revised
  voice-profile semantics (null = normal text-only mode); AudioCapture `onReady` in the port;
  AudioRecorder lint-visible permission guard; native revision independently verified (PR #22836
  **not merged** — pinned to PR-branch commit `1e411d8f5a1e23525fa3265dfb4bd76265465397`, see
  `cpp/hymt_jni/README.md`).
