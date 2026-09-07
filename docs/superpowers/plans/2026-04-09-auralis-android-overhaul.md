# Auralis Android Premium Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the Android app into the `Auralis` flagship experience with a quiet-premium interpretation workspace, refreshed setup/support screens, testable runtime ownership, and measurable performance verification.

**Architecture:** Introduce a small interpretation contract layer (`InterpretUiState`, session controller, pipeline adapter) so the ViewModel owns runtime lifecycle while Compose renders stable state. Layer a new brand/theme system and support-screen content components on top of the existing app shell, then finish with Android UI coverage and benchmark-based startup verification.

**Tech Stack:** Kotlin 2.1, Jetpack Compose Material 3, AndroidX Lifecycle/ViewModel/Navigation, Kotlin coroutines + Flow, ONNX Runtime, JUnit4, Compose UI Test, AndroidX Macrobenchmark + Baseline Profile.

---

## Preconditions

The current repository only has the design spec committed. The Android app, iOS app, tooling directory, and older docs are still untracked. Before executing this plan in a dedicated worktree, create a baseline snapshot commit so the worktree contains the actual app code.

```bash
git add .gitignore README.md android convert ios docs/superpowers/plans docs/superpowers/specs/2026-03-18-codebase-quality-foundation-design.md
git commit -m "chore: snapshot current mobile app baseline"
```

Run `git status --short` after that commit and make sure only the new branch-specific work is left unstaged.

## File Structure

### Brand And Theme Files

- Create: `android/app/src/main/java/com/dialect/interpreter/ui/theme/Brand.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/theme/Theme.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Modify: `android/app/src/main/res/drawable/ic_launcher_monochrome.xml`
- Modify: `android/app/src/main/res/values/ic_launcher_colors.xml`

### Interpretation Runtime Files

- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretUiState.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretRuntime.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretSessionController.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/PipelineRuntimeAdapter.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/DialectApp.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretViewModel.kt`

### Flagship Workspace Files

- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretHeader.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretTimeline.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretActionDock.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretWorkspace.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretScreen.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/navigation/AppNavigation.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/components/DialectSelector.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/MainActivity.kt`

### Setup And Support Surface Files

- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/ModelDownloadScreen.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/screens/VoiceProfileContent.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/VoiceProfileScreen.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/screens/SettingsContent.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/SettingsScreen.kt`

### Test Files

- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/test/java/com/dialect/interpreter/ui/interpret/InterpretUiStateTest.kt`
- Create: `android/app/src/test/java/com/dialect/interpreter/ui/interpret/InterpretSessionControllerTest.kt`
- Create: `android/app/src/androidTest/java/com/dialect/interpreter/ui/setup/ModelDownloadScreenTest.kt`
- Create: `android/app/src/androidTest/java/com/dialect/interpreter/ui/interpret/InterpretWorkspaceTest.kt`
- Create: `android/app/src/androidTest/java/com/dialect/interpreter/ui/voice/VoiceProfileContentTest.kt`
- Create: `android/app/src/androidTest/java/com/dialect/interpreter/ui/settings/SettingsContentTest.kt`

### Performance Verification Files

- Modify: `android/build.gradle.kts`
- Modify: `android/settings.gradle.kts`
- Modify: `android/app/build.gradle.kts`
- Create: `android/benchmark/build.gradle.kts`
- Create: `android/benchmark/src/main/AndroidManifest.xml`
- Create: `android/benchmark/src/main/java/com/dialect/interpreter/benchmark/StartupBenchmark.kt`
- Create: `android/benchmark/src/main/java/com/dialect/interpreter/benchmark/BaselineProfileGenerator.kt`
- Modify: `README.md`
- Create: `docs/android-verification.md`

## Task 1: Establish Interpretation Contract And Reducer

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretUiState.kt`
- Test: `android/app/src/test/java/com/dialect/interpreter/ui/interpret/InterpretUiStateTest.kt`

- [ ] **Step 1: Write the failing reducer test**

```kotlin
package com.dialect.interpreter.ui.interpret

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpretUiStateTest {

    @Test
    fun `phase and amplitude mutations update the headline state`() {
        var state = InterpretUiState()

        state = state.reduce(
            InterpretMutation.PhaseChanged(
                phase = SessionPhase.PREPARING,
                statusLine = "Preparing models",
                providerLabel = "NPU"
            )
        )
        state = state.reduce(InterpretMutation.AmplitudeChanged(0.32f))

        assertEquals(SessionPhase.PREPARING, state.phase)
        assertEquals("Preparing models", state.statusLine)
        assertEquals("NPU", state.providerLabel)
        assertEquals(0.32f, state.amplitude)
        assertEquals("Stop session", state.primaryActionLabel)
    }

    @Test
    fun `queueing and resolving a transcript replaces the pending timeline row`() {
        val finalState = InterpretUiState(errorMessage = "Microphone access is required")
            .reduce(InterpretMutation.TranscriptQueued(sourceText = "Nia ho", asrLatencyMs = 84L))
            .reduce(InterpretMutation.TelemetryChanged("NPU • ASR 84 ms • TTS 112 ms"))
            .reduce(
                InterpretMutation.TranscriptResolved(
                    targetText = "Hello",
                    ttsLatencyMs = 112L,
                    playbackLatencyMs = 280L
                )
            )

        assertNull(finalState.errorMessage)
        assertEquals(1, finalState.timeline.size)
        with(finalState.timeline.single()) {
            assertEquals("Nia ho", sourceText)
            assertEquals("Hello", targetText)
            assertEquals(false, isPending)
            assertEquals(84L, asrLatencyMs)
            assertEquals(112L, ttsLatencyMs)
            assertEquals(280L, playbackLatencyMs)
        }
        assertEquals("NPU • ASR 84 ms • TTS 112 ms", finalState.telemetryLine)
    }
}
```

- [ ] **Step 2: Run the unit test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.dialect.interpreter.ui.interpret.InterpretUiStateTest"`
Expected: FAIL because `InterpretUiState`, `SessionPhase`, and `InterpretMutation` do not exist yet.

- [ ] **Step 3: Add the reducer contract and missing unit-test dependencies**

```kotlin
// android/app/build.gradle.kts
dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretUiState.kt
package com.dialect.interpreter.ui.interpret

enum class SessionPhase {
    IDLE,
    PREPARING,
    LISTENING,
    RECOGNIZING,
    SYNTHESIZING,
    PLAYING,
    ERROR,
}

data class TranscriptEntry(
    val id: Long = System.nanoTime(),
    val sourceText: String,
    val targetText: String = "",
    val asrLatencyMs: Long = 0L,
    val ttsLatencyMs: Long = 0L,
    val playbackLatencyMs: Long = 0L,
    val isPending: Boolean = true,
)

data class InterpretUiState(
    val brandName: String = "Auralis",
    val sourceDialect: String = "Sichuanese",
    val targetLanguage: String = "Mandarin",
    val phase: SessionPhase = SessionPhase.IDLE,
    val statusLine: String = "Ready to interpret",
    val telemetryLine: String = "",
    val providerLabel: String = "",
    val amplitude: Float = 0f,
    val timeline: List<TranscriptEntry> = emptyList(),
    val errorMessage: String? = null,
    val primaryActionLabel: String = "Start live interpretation",
) {
    fun reduce(mutation: InterpretMutation): InterpretUiState = when (mutation) {
        is InterpretMutation.SelectionChanged -> copy(
            sourceDialect = mutation.sourceDialect ?: sourceDialect,
            targetLanguage = mutation.targetLanguage ?: targetLanguage,
        )

        is InterpretMutation.PhaseChanged -> copy(
            phase = mutation.phase,
            statusLine = mutation.statusLine,
            providerLabel = mutation.providerLabel ?: providerLabel,
            errorMessage = null,
            primaryActionLabel = if (mutation.phase == SessionPhase.IDLE) {
                "Start live interpretation"
            } else {
                "Stop session"
            },
        )

        is InterpretMutation.AmplitudeChanged -> copy(amplitude = mutation.value)
        is InterpretMutation.TelemetryChanged -> copy(telemetryLine = mutation.value)

        is InterpretMutation.TranscriptQueued -> copy(
            errorMessage = null,
            timeline = timeline + TranscriptEntry(
                sourceText = mutation.sourceText,
                asrLatencyMs = mutation.asrLatencyMs,
            ),
        )

        is InterpretMutation.TranscriptResolved -> {
            val pendingIndex = timeline.indexOfLast { it.isPending }
            if (pendingIndex < 0) return this
            val mutableTimeline = timeline.toMutableList()
            val current = mutableTimeline[pendingIndex]
            mutableTimeline[pendingIndex] = current.copy(
                targetText = mutation.targetText,
                ttsLatencyMs = mutation.ttsLatencyMs,
                playbackLatencyMs = mutation.playbackLatencyMs,
                isPending = false,
            )
            copy(errorMessage = null, timeline = mutableTimeline)
        }

        is InterpretMutation.Error -> copy(
            phase = SessionPhase.ERROR,
            statusLine = "Action required",
            amplitude = 0f,
            errorMessage = mutation.message,
            primaryActionLabel = "Try again",
        )

        InterpretMutation.SessionStopped -> copy(
            phase = SessionPhase.IDLE,
            statusLine = "Ready to interpret",
            amplitude = 0f,
            primaryActionLabel = "Start live interpretation",
        )
    }
}

sealed interface InterpretMutation {
    data class SelectionChanged(
        val sourceDialect: String? = null,
        val targetLanguage: String? = null,
    ) : InterpretMutation

    data class PhaseChanged(
        val phase: SessionPhase,
        val statusLine: String,
        val providerLabel: String? = null,
    ) : InterpretMutation

    data class AmplitudeChanged(val value: Float) : InterpretMutation
    data class TelemetryChanged(val value: String) : InterpretMutation
    data class TranscriptQueued(val sourceText: String, val asrLatencyMs: Long) : InterpretMutation
    data class TranscriptResolved(
        val targetText: String,
        val ttsLatencyMs: Long,
        val playbackLatencyMs: Long,
    ) : InterpretMutation

    data class Error(val message: String) : InterpretMutation
    data object SessionStopped : InterpretMutation
}
```

- [ ] **Step 4: Run the reducer test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.dialect.interpreter.ui.interpret.InterpretUiStateTest"`
Expected: PASS with both reducer assertions succeeding.

- [ ] **Step 5: Commit the interpretation contract**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretUiState.kt android/app/src/test/java/com/dialect/interpreter/ui/interpret/InterpretUiStateTest.kt
git commit -m "feat: add interpretation UI state contract"
```

## Task 2: Move Runtime Ownership Into A Session Controller

**Files:**
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretRuntime.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretSessionController.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/PipelineRuntimeAdapter.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/DialectApp.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretViewModel.kt`
- Test: `android/app/src/test/java/com/dialect/interpreter/ui/interpret/InterpretSessionControllerTest.kt`

- [ ] **Step 1: Write the failing session-controller test**

```kotlin
package com.dialect.interpreter.ui.interpret

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterpretSessionControllerTest {

    @Test
    fun `toggleSession starts runtime when models are ready and permission is granted`() = runTest {
        val runtime = FakeInterpretRuntime()
        val controller = InterpretSessionController(
            modelAvailability = { true },
            runtimeFactory = { runtime },
            externalScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        controller.toggleSession(hasMicPermission = true)
        runtime.events.tryEmit(
            InterpretMutation.PhaseChanged(
                phase = SessionPhase.LISTENING,
                statusLine = "Listening live",
                providerLabel = "NPU",
            )
        )

        advanceUntilIdle()

        assertTrue(runtime.started)
        assertEquals(SessionPhase.LISTENING, controller.state.value.phase)
        assertEquals("NPU", controller.state.value.providerLabel)
    }

    @Test
    fun `toggleSession surfaces a permission error without starting runtime`() = runTest {
        val runtime = FakeInterpretRuntime()
        val controller = InterpretSessionController(
            modelAvailability = { true },
            runtimeFactory = { runtime },
            externalScope = TestScope(StandardTestDispatcher(testScheduler)),
        )

        controller.toggleSession(hasMicPermission = false)
        advanceUntilIdle()

        assertFalse(runtime.started)
        assertEquals(SessionPhase.ERROR, controller.state.value.phase)
        assertEquals("Microphone access is required", controller.state.value.errorMessage)
    }

    private class FakeInterpretRuntime : InterpretRuntime {
        override val events = MutableSharedFlow<InterpretMutation>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        var started = false
            private set

        override fun start(scope: CoroutineScope, targetLanguage: String) {
            started = true
        }

        override fun stop() {
            started = false
        }

        override fun release() {
            started = false
        }
    }
}
```

- [ ] **Step 2: Run the unit test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.dialect.interpreter.ui.interpret.InterpretSessionControllerTest"`
Expected: FAIL because `InterpretRuntime` and `InterpretSessionController` do not exist yet.

- [ ] **Step 3: Introduce the runtime abstraction, controller, and ViewModel wiring**

```kotlin
// android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretRuntime.kt
package com.dialect.interpreter.ui.interpret

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

interface InterpretRuntime {
    val events: Flow<InterpretMutation>
    fun start(scope: CoroutineScope, targetLanguage: String)
    fun stop()
    fun release()
}

fun interface InterpretRuntimeFactory {
    fun create(): InterpretRuntime
}

fun interface ModelAvailability {
    fun areModelsReady(): Boolean
}

// android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretSessionController.kt
package com.dialect.interpreter.ui.interpret

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class InterpretSessionController(
    private val modelAvailability: ModelAvailability,
    private val runtimeFactory: InterpretRuntimeFactory,
    private val externalScope: CoroutineScope,
) {
    private val _state = MutableStateFlow(InterpretUiState())
    val state: StateFlow<InterpretUiState> = _state.asStateFlow()

    private var runtime: InterpretRuntime? = null
    private var runtimeCollection: Job? = null

    fun selectSourceDialect(value: String) {
        _state.update { it.reduce(InterpretMutation.SelectionChanged(sourceDialect = value)) }
    }

    fun selectTargetLanguage(value: String) {
        _state.update { it.reduce(InterpretMutation.SelectionChanged(targetLanguage = value)) }
    }

    fun toggleSession(hasMicPermission: Boolean) {
        if (runtime != null) {
            stopSession()
            return
        }

        if (!hasMicPermission) {
            _state.update { it.reduce(InterpretMutation.Error("Microphone access is required")) }
            return
        }

        if (!modelAvailability.areModelsReady()) {
            _state.update { it.reduce(InterpretMutation.Error("Install offline models before starting live interpretation")) }
            return
        }

        val createdRuntime = runtimeFactory.create()
        runtime = createdRuntime
        _state.update {
            it.reduce(
                InterpretMutation.PhaseChanged(
                    phase = SessionPhase.PREPARING,
                    statusLine = "Preparing models",
                )
            )
        }
        runtimeCollection = createdRuntime.events
            .onEach { mutation -> _state.update { current -> current.reduce(mutation) } }
            .launchIn(externalScope)
        createdRuntime.start(externalScope, _state.value.targetLanguage)
    }

    fun stopSession() {
        runtime?.stop()
        runtimeCollection?.cancel()
        runtime = null
        runtimeCollection = null
        _state.update { it.reduce(InterpretMutation.SessionStopped) }
    }

    fun release() {
        runtime?.release()
        runtimeCollection?.cancel()
        runtime = null
        runtimeCollection = null
    }
}

// android/app/src/main/java/com/dialect/interpreter/ui/interpret/PipelineRuntimeAdapter.kt
package com.dialect.interpreter.ui.interpret

import com.dialect.interpreter.audio.AudioPlayer
import com.dialect.interpreter.audio.AudioRecorder
import com.dialect.interpreter.inference.AsrEngine
import com.dialect.interpreter.inference.OnnxModelManager
import com.dialect.interpreter.inference.PipelineOrchestrator
import com.dialect.interpreter.inference.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

class PipelineRuntimeAdapter(
    modelManager: OnnxModelManager,
) : InterpretRuntime {
    private val pipeline = PipelineOrchestrator(
        asrEngine = AsrEngine(modelManager),
        ttsEngine = TtsEngine(modelManager),
        audioRecorder = AudioRecorder(),
        audioPlayer = AudioPlayer(),
    )
    private val eventBus = MutableSharedFlow<InterpretMutation>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var pendingTargetText: String = ""

    override val events: Flow<InterpretMutation> = eventBus

    override fun start(scope: CoroutineScope, targetLanguage: String) {
        merge(
            pipeline.state.map { state ->
                when (state) {
                    PipelineOrchestrator.PipelineState.IDLE -> InterpretMutation.SessionStopped
                    PipelineOrchestrator.PipelineState.LOADING -> InterpretMutation.PhaseChanged(SessionPhase.PREPARING, "Preparing models")
                    PipelineOrchestrator.PipelineState.LISTENING -> InterpretMutation.PhaseChanged(SessionPhase.LISTENING, "Listening live")
                    PipelineOrchestrator.PipelineState.RECOGNIZING -> InterpretMutation.PhaseChanged(SessionPhase.RECOGNIZING, "Recognizing speech")
                    PipelineOrchestrator.PipelineState.SYNTHESIZING -> InterpretMutation.PhaseChanged(SessionPhase.SYNTHESIZING, "Generating playback")
                    PipelineOrchestrator.PipelineState.PLAYING -> InterpretMutation.PhaseChanged(SessionPhase.PLAYING, "Playing output")
                }
            },
            pipeline.amplitude.map { InterpretMutation.AmplitudeChanged(it) },
            pipeline.telemetry.map {
                InterpretMutation.TelemetryChanged(
                    if (it.asrCount == 0L && it.ttsCount == 0L) {
                        ""
                    } else {
                        "ASR ${it.lastAsrMs} ms • TTS ${it.lastTtsMs} ms • RTF ${"%.2f".format(it.lastRtf)}"
                    }
                )
            },
        ).onEach { eventBus.emit(it) }.launchIn(scope)

        pipeline.events.onEach { event ->
            when (event) {
                is PipelineOrchestrator.PipelineEvent.AsrResult -> {
                    eventBus.emit(InterpretMutation.TranscriptQueued(event.text, event.latencyMs))
                }

                is PipelineOrchestrator.PipelineEvent.TtsStarted -> {
                    pendingTargetText = event.text
                }

                is PipelineOrchestrator.PipelineEvent.TtsComplete -> {
                    eventBus.emit(
                        InterpretMutation.TranscriptResolved(
                            targetText = pendingTargetText,
                            ttsLatencyMs = event.inferenceMs,
                            playbackLatencyMs = event.playbackMs,
                        )
                    )
                }

                is PipelineOrchestrator.PipelineEvent.Error -> {
                    eventBus.emit(InterpretMutation.Error(event.message))
                }

                else -> Unit
            }
        }.launchIn(scope)

        pipeline.start(scope, targetLanguage = targetLanguage)
    }

    override fun stop() = pipeline.stop()
    override fun release() = pipeline.release()
}

// android/app/src/main/java/com/dialect/interpreter/DialectApp.kt
package com.dialect.interpreter

import android.app.Application
import com.dialect.interpreter.inference.OnnxModelManager
import com.dialect.interpreter.ui.interpret.InterpretRuntime
import com.dialect.interpreter.ui.interpret.PipelineRuntimeAdapter

class DialectApp : Application() {

    lateinit var modelManager: OnnxModelManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        modelManager = OnnxModelManager(this)
    }

    fun createInterpretRuntime(): InterpretRuntime = PipelineRuntimeAdapter(modelManager)

    companion object {
        lateinit var instance: DialectApp
            private set
    }
}

// android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretViewModel.kt
package com.dialect.interpreter.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dialect.interpreter.DialectApp
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.ui.interpret.InterpretSessionController
import com.dialect.interpreter.ui.interpret.ModelAvailability
import kotlinx.coroutines.flow.StateFlow

class InterpretViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DialectApp
    private val modelRepository = ModelRepository(application.applicationContext)
    private val controller = InterpretSessionController(
        modelAvailability = ModelAvailability { modelRepository.areModelsReady() },
        runtimeFactory = { app.createInterpretRuntime() },
        externalScope = viewModelScope,
    )

    val uiState: StateFlow<com.dialect.interpreter.ui.interpret.InterpretUiState> = controller.state

    fun selectSourceDialect(value: String) = controller.selectSourceDialect(value)
    fun selectTargetLanguage(value: String) = controller.selectTargetLanguage(value)
    fun toggleSession(hasMicPermission: Boolean) = controller.toggleSession(hasMicPermission)
    fun stopSession() = controller.stopSession()

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}
```

- [ ] **Step 4: Run the session-controller test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.dialect.interpreter.ui.interpret.InterpretSessionControllerTest"`
Expected: PASS with the start and permission-guard cases succeeding.

- [ ] **Step 5: Commit the runtime ownership refactor**

```bash
git add android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretRuntime.kt android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretSessionController.kt android/app/src/main/java/com/dialect/interpreter/ui/interpret/PipelineRuntimeAdapter.kt android/app/src/main/java/com/dialect/interpreter/DialectApp.kt android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretViewModel.kt android/app/src/test/java/com/dialect/interpreter/ui/interpret/InterpretSessionControllerTest.kt
git commit -m "refactor: move interpretation runtime into a session controller"
```

## Task 3: Apply Auralis Branding And Redesign The Setup Screen

**Files:**
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/theme/Brand.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/theme/Theme.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/ModelDownloadScreen.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
- Modify: `android/app/src/main/res/drawable/ic_launcher_monochrome.xml`
- Modify: `android/app/src/main/res/values/ic_launcher_colors.xml`
- Test: `android/app/src/androidTest/java/com/dialect/interpreter/ui/setup/ModelDownloadScreenTest.kt`

- [ ] **Step 1: Write the failing setup-screen instrumentation test**

```kotlin
package com.dialect.interpreter.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.ui.screens.SetupScreenContent
import com.dialect.interpreter.ui.theme.DialectInterpreterTheme
import org.junit.Rule
import org.junit.Test

class ModelDownloadScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setup_content_shows_auralis_brand_and_install_cta() {
        composeRule.setContent {
            DialectInterpreterTheme {
                SetupScreenContent(
                    brandName = "Auralis",
                    tagline = "Speak naturally. Deliver clearly.",
                    progress = ModelRepository.ExtractionProgress(),
                    isExtracting = false,
                    estimatedFootprint = "1.2 GB",
                    onPrimaryAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Auralis").assertIsDisplayed()
        composeRule.onNodeWithText("Prepare offline voice interpretation").assertIsDisplayed()
        composeRule.onNodeWithText("Install on this device").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the instrumentation test to verify it fails**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dialect.interpreter.ui.setup.ModelDownloadScreenTest`
Expected: FAIL because `SetupScreenContent` does not exist and the current setup screen does not use the `Auralis` copy.

- [ ] **Step 3: Add brand tokens, quiet-premium theme updates, setup content, and launcher assets**

```kotlin
// android/app/src/main/java/com/dialect/interpreter/ui/theme/Brand.kt
package com.dialect.interpreter.ui.theme

object Brand {
    const val AppName = "Auralis"
    const val Tagline = "Speak naturally. Deliver clearly."
    const val SetupHeadline = "Prepare offline voice interpretation"
    const val SetupAction = "Install on this device"
    const val SetupCaption = "Offline ASR and voice playback stay on-device after setup completes."
}

// android/app/src/main/java/com/dialect/interpreter/ui/theme/Theme.kt
val AccentLight = Color(0xFF86A7FF)
val AccentDark = Color(0xFFADC1FF)
val BgPrimary = Color(0xFFF6F8FC)
val BgPrimaryDark = Color(0xFF050816)
val BgElevated = Color(0xFFFFFFFF)
val BgElevatedDark = Color(0xFF11182B)
val BgSecondary = Color(0xFFE9EEF9)
val BgSecondaryDark = Color(0xFF1A223A)
val TextPrimary = Color(0xFF111827)
val TextPrimaryDark = Color(0xFFF7F9FF)
val TextSecondary = Color(0xFF6B7280)
val TextTertiary = Color(0xFFA7B0C0)
val TextTertiaryDark = Color(0xFF55617A)
val BubbleSource = Color(0xFFFFFFFF)
val BubbleSourceDark = Color(0xFF172036)
val BubbleTarget = Color(0xFFDCE6FF)
val BubbleTargetDark = Color(0xFF223357)

// android/app/src/main/java/com/dialect/interpreter/ui/screens/ModelDownloadScreen.kt
@Composable
fun SetupScreenContent(
    brandName: String,
    tagline: String,
    progress: ModelRepository.ExtractionProgress,
    isExtracting: Boolean,
    estimatedFootprint: String,
    onPrimaryAction: () -> Unit,
) {
    val accent = AppColors.accent()
    val background = AppColors.bg()
    val text = AppColors.text()
    val secondary = AppColors.textSecondary()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(28.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(brandName, style = MaterialTheme.typography.displaySmall, color = text)
                Text(tagline, style = MaterialTheme.typography.titleMedium, color = secondary)
                Spacer(Modifier.height(12.dp))
                Text(Brand.SetupHeadline, style = MaterialTheme.typography.headlineMedium, color = text)
                Text(Brand.SetupCaption, style = MaterialTheme.typography.bodyMedium, color = secondary)
                Text("Expected footprint • $estimatedFootprint", style = MaterialTheme.typography.labelMedium, color = secondary)
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LinearProgressIndicator(
                    progress = {
                        if (progress.totalFiles == 0) 0f else progress.completedFiles.toFloat() / progress.totalFiles
                    },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp)),
                    color = accent,
                )
                Text(
                    if (progress.currentFileName.isBlank()) "Ready when you are" else progress.currentFileName,
                    color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onPrimaryAction,
                    enabled = !isExtracting,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Text(if (isExtracting) "Installing..." else Brand.SetupAction)
                }
            }
        }
    }
}

@Composable
fun ModelDownloadScreen(
    modelRepository: ModelRepository,
    onDownloadComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress by modelRepository.extractionProgress.collectAsState()
    var isExtracting by remember { mutableStateOf(false) }

    LaunchedEffect(progress.isComplete) {
        if (progress.isComplete) onDownloadComplete()
    }

    SetupScreenContent(
        brandName = Brand.AppName,
        tagline = Brand.Tagline,
        progress = progress,
        isExtracting = isExtracting,
        estimatedFootprint = "1.2 GB",
        onPrimaryAction = {
            isExtracting = true
            scope.launch { modelRepository.extractBundledModels() }
        },
    )
}
```

```xml
<!-- android/app/src/main/res/values/strings.xml -->
<resources>
    <string name="app_name">Auralis</string>
</resources>

<!-- android/app/src/main/res/values/ic_launcher_colors.xml -->
<resources>
    <color name="ic_launcher_background">#0B1120</color>
</resources>

<!-- android/app/src/main/res/drawable/ic_launcher_foreground.xml -->
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="oval">
            <solid android:color="#86A7FF" />
        </shape>
    </item>
    <item android:gravity="center" android:width="60dp" android:height="104dp">
        <shape android:shape="rectangle">
            <corners android:radius="30dp" />
            <solid android:color="#F7F9FF" />
        </shape>
    </item>
</layer-list>

<!-- android/app/src/main/res/drawable/ic_launcher_monochrome.xml -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <corners android:radius="30dp" />
    <solid android:color="#FFFFFF" />
</shape>

<!-- android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>

<!-- android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

Leave `android/app/src/main/AndroidManifest.xml` pointing at `@string/app_name`; the resource change is enough for the launcher label.

- [ ] **Step 4: Run the setup-screen test to verify it passes**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dialect.interpreter.ui.setup.ModelDownloadScreenTest`
Expected: PASS with the `Auralis` setup copy and primary install action visible.

- [ ] **Step 5: Commit the brand foundation and setup redesign**

```bash
git add android/app/src/main/java/com/dialect/interpreter/ui/theme/Brand.kt android/app/src/main/java/com/dialect/interpreter/ui/theme/Theme.kt android/app/src/main/java/com/dialect/interpreter/ui/screens/ModelDownloadScreen.kt android/app/src/androidTest/java/com/dialect/interpreter/ui/setup/ModelDownloadScreenTest.kt android/app/src/main/res/values/strings.xml android/app/src/main/res/values/ic_launcher_colors.xml android/app/src/main/res/drawable/ic_launcher_foreground.xml android/app/src/main/res/drawable/ic_launcher_monochrome.xml android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
git commit -m "feat: introduce Auralis branding and setup experience"
```

## Task 4: Rebuild The Flagship Interpretation Workspace

**Files:**
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretHeader.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretTimeline.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretActionDock.kt`
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretWorkspace.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretScreen.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/navigation/AppNavigation.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/components/DialectSelector.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/MainActivity.kt`
- Test: `android/app/src/androidTest/java/com/dialect/interpreter/ui/interpret/InterpretWorkspaceTest.kt`

- [ ] **Step 1: Write the failing flagship-workspace instrumentation test**

```kotlin
package com.dialect.interpreter.ui.interpret

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dialect.interpreter.ui.theme.DialectInterpreterTheme
import org.junit.Rule
import org.junit.Test

class InterpretWorkspaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idle_workspace_shows_brand_tagline_and_primary_action() {
        composeRule.setContent {
            DialectInterpreterTheme {
                InterpretWorkspace(
                    state = InterpretUiState(
                        statusLine = "Ready to interpret",
                        primaryActionLabel = "Start live interpretation",
                    ),
                    onSourceDialectClick = {},
                    onTargetLanguageClick = {},
                    onSwapClick = {},
                    onMicClick = {},
                    onProfileClick = {},
                    onSettingsClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Auralis").assertIsDisplayed()
        composeRule.onNodeWithText("Speak naturally. Deliver clearly.").assertIsDisplayed()
        composeRule.onNodeWithText("Start live interpretation").assertIsDisplayed()
    }

    @Test
    fun active_workspace_shows_inline_telemetry_and_resolved_timeline() {
        composeRule.setContent {
            DialectInterpreterTheme {
                InterpretWorkspace(
                    state = InterpretUiState(
                        phase = SessionPhase.LISTENING,
                        statusLine = "Listening live",
                        telemetryLine = "NPU • ASR 84 ms • TTS 112 ms",
                        timeline = listOf(
                            TranscriptEntry(
                                sourceText = "Nia ho",
                                targetText = "Hello",
                                asrLatencyMs = 84L,
                                ttsLatencyMs = 112L,
                                playbackLatencyMs = 280L,
                                isPending = false,
                            )
                        ),
                        primaryActionLabel = "Stop session",
                    ),
                    onSourceDialectClick = {},
                    onTargetLanguageClick = {},
                    onSwapClick = {},
                    onMicClick = {},
                    onProfileClick = {},
                    onSettingsClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Listening live").assertIsDisplayed()
        composeRule.onNodeWithText("NPU • ASR 84 ms • TTS 112 ms").assertIsDisplayed()
        composeRule.onNodeWithText("Hello").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the instrumentation test to verify it fails**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dialect.interpreter.ui.interpret.InterpretWorkspaceTest`
Expected: FAIL because `InterpretWorkspace` does not exist and the current screen does not show the new branded layout.

- [ ] **Step 3: Split the workspace into testable sections and move permission prompting to intent time**

```kotlin
// android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretHeader.kt
package com.dialect.interpreter.ui.interpret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RecordVoiceOver
import com.dialect.interpreter.ui.theme.AppColors
import com.dialect.interpreter.ui.theme.Brand

@Composable
fun InterpretHeader(
    state: InterpretUiState,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Brand.AppName, style = MaterialTheme.typography.displaySmall, color = AppColors.text(), fontWeight = FontWeight.Bold)
            Text(Brand.Tagline, style = MaterialTheme.typography.bodyMedium, color = AppColors.textSecondary())
            Text(state.statusLine, style = MaterialTheme.typography.titleMedium, color = AppColors.text())
            if (state.telemetryLine.isNotBlank()) {
                Text(state.telemetryLine, style = MaterialTheme.typography.labelMedium, color = AppColors.textSecondary())
            }
        }
        Row {
            IconButton(onClick = onProfileClick) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = "Voice profile")
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.MoreVert, contentDescription = "Settings")
            }
        }
    }
}

// android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretTimeline.kt
package com.dialect.interpreter.ui.interpret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.ui.theme.AppColors

@Composable
fun InterpretTimeline(state: InterpretUiState, modifier: Modifier = Modifier) {
    if (state.timeline.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your live transcript appears here", style = MaterialTheme.typography.titleMedium, color = AppColors.text())
                Text("Keep the device close and speak naturally.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textSecondary())
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.timeline, key = { it.id }) { row ->
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(24.dp), color = AppColors.bubbleSource()) {
                    Text(row.sourceText, modifier = Modifier.padding(16.dp), color = AppColors.text())
                }
                Surface(shape = RoundedCornerShape(24.dp), color = AppColors.bubbleTarget()) {
                    Text(
                        if (row.isPending) "Generating playback..." else row.targetText,
                        modifier = Modifier.padding(16.dp),
                        color = AppColors.text(),
                    )
                }
            }
        }
    }
}

// android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretActionDock.kt
package com.dialect.interpreter.ui.interpret

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.ui.components.WaveformVisualizer
import com.dialect.interpreter.ui.theme.AppColors

@Composable
fun InterpretActionDock(state: InterpretUiState, onMicClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = AppColors.surface(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WaveformVisualizer(
                amplitude = state.amplitude,
                isActive = state.phase == SessionPhase.LISTENING,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
            Button(onClick = onMicClick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(state.primaryActionLabel, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// android/app/src/main/java/com/dialect/interpreter/ui/interpret/InterpretWorkspace.kt
package com.dialect.interpreter.ui.interpret

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.ui.components.DialectSelector
import com.dialect.interpreter.ui.theme.AppColors

@Composable
fun InterpretWorkspace(
    state: InterpretUiState,
    onSourceDialectClick: () -> Unit,
    onTargetLanguageClick: () -> Unit,
    onSwapClick: () -> Unit,
    onMicClick: () -> Unit,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    Scaffold(
        containerColor = AppColors.bg(),
        bottomBar = { InterpretActionDock(state = state, onMicClick = onMicClick) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.bg())
                .padding(padding),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().background(AppColors.surfaceSecondary()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                InterpretHeader(state = state, onProfileClick = onProfileClick, onSettingsClick = onSettingsClick)
                DialectSelector(
                    sourceDialect = state.sourceDialect,
                    targetLanguage = state.targetLanguage,
                    onSourceChange = { onSourceDialectClick() },
                    onTargetChange = { onTargetLanguageClick() },
                    onSwap = onSwapClick,
                )
            }
            Spacer(Modifier.height(4.dp))
            InterpretTimeline(state = state, modifier = Modifier.weight(1f))
        }
    }
}
```

```kotlin
// android/app/src/main/java/com/dialect/interpreter/ui/components/DialectSelector.kt
@Composable
fun DialectSelector(
    sourceDialect: String,
    targetLanguage: String,
    onSourceChange: () -> Unit,
    onTargetChange: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusCard))
            .background(AppColors.surface())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onSourceChange).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Source", style = MaterialTheme.typography.labelSmall, color = AppColors.textSecondary())
            Text(sourceDialect, style = MaterialTheme.typography.titleMedium, color = AppColors.text())
        }
        FilledIconButton(
            onClick = onSwap,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = AppColors.accent().copy(alpha = 0.14f),
                contentColor = AppColors.accent(),
            ),
        ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = "Swap languages")
        }
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onTargetChange).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Target", style = MaterialTheme.typography.labelSmall, color = AppColors.textSecondary())
            Text(targetLanguage, style = MaterialTheme.typography.titleMedium, color = AppColors.text())
        }
    }
}

// android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretScreen.kt
@Composable
fun InterpretScreen(
    onNavigateToProfile: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: InterpretViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showSourceSheet by remember { mutableStateOf(false) }
    var showTargetSheet by remember { mutableStateOf(false) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
        if (granted) viewModel.toggleSession(hasMicPermission = true)
    }

    InterpretWorkspace(
        state = uiState,
        onSourceDialectClick = { showSourceSheet = true },
        onTargetLanguageClick = { showTargetSheet = true },
        onSwapClick = {
            val currentSource = uiState.sourceDialect
            val currentTarget = uiState.targetLanguage
            viewModel.selectSourceDialect(currentTarget)
            viewModel.selectTargetLanguage(currentSource)
        },
        onMicClick = {
            if (hasMicPermission) {
                viewModel.toggleSession(hasMicPermission = true)
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onProfileClick = onNavigateToProfile,
        onSettingsClick = onNavigateToSettings,
    )

    if (showSourceSheet) {
        SelectionSheet(
            title = "Choose source dialect",
            items = AsrEngine.CHINESE_DIALECTS.map { it.first },
            selectedItem = uiState.sourceDialect,
            onSelect = {
                viewModel.selectSourceDialect(it)
                showSourceSheet = false
            },
            onDismiss = { showSourceSheet = false },
        )
    }

    if (showTargetSheet) {
        SelectionSheet(
            title = "Choose target language",
            items = AsrEngine.SUPPORTED_LANGUAGES.map { it.first },
            selectedItem = uiState.targetLanguage,
            onSelect = {
                viewModel.selectTargetLanguage(it)
                showTargetSheet = false
            },
            onDismiss = { showTargetSheet = false },
        )
    }
}

@Composable
private fun SelectionSheet(
    title: String,
    items: List<String>,
    selectedItem: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.surface()) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
            item {
                Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.text())
                Spacer(Modifier.height(12.dp))
            }
            items(items) { item ->
                FilterChip(
                    selected = item == selectedItem,
                    onClick = { onSelect(item) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    label = { Text(item) },
                )
            }
        }
    }
}

// android/app/src/main/java/com/dialect/interpreter/MainActivity.kt
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
        DialectInterpreterTheme {
            AppNavigation()
        }
    }
}

// android/app/src/main/java/com/dialect/interpreter/ui/navigation/AppNavigation.kt
composable(AppRoutes.INTERPRET) {
    InterpretScreen(
        onNavigateToProfile = { navController.navigate(AppRoutes.VOICE_PROFILE) },
        onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) },
    )
}
```

- [ ] **Step 4: Run the flagship-workspace test to verify it passes**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dialect.interpreter.ui.interpret.InterpretWorkspaceTest`
Expected: PASS with the brand headline, inline telemetry, and transcript timeline assertions all succeeding.

- [ ] **Step 5: Commit the flagship screen rebuild**

```bash
git add android/app/src/main/java/com/dialect/interpreter/ui/interpret android/app/src/main/java/com/dialect/interpreter/ui/screens/InterpretScreen.kt android/app/src/main/java/com/dialect/interpreter/ui/navigation/AppNavigation.kt android/app/src/main/java/com/dialect/interpreter/ui/components/DialectSelector.kt android/app/src/main/java/com/dialect/interpreter/MainActivity.kt android/app/src/androidTest/java/com/dialect/interpreter/ui/interpret/InterpretWorkspaceTest.kt
git commit -m "feat: rebuild the Auralis interpretation workspace"
```

## Task 5: Redesign The Voice Profile Surface

**Files:**
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/screens/VoiceProfileContent.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/VoiceProfileScreen.kt`
- Test: `android/app/src/androidTest/java/com/dialect/interpreter/ui/voice/VoiceProfileContentTest.kt`

- [ ] **Step 1: Write the failing voice-profile instrumentation test**

```kotlin
package com.dialect.interpreter.ui.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dialect.interpreter.ui.screens.VoiceProfileCardModel
import com.dialect.interpreter.ui.screens.VoiceProfileContent
import com.dialect.interpreter.ui.screens.VoiceProfileUiState
import com.dialect.interpreter.ui.theme.DialectInterpreterTheme
import org.junit.Rule
import org.junit.Test

class VoiceProfileContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun empty_state_highlights_the_recording_call_to_action() {
        composeRule.setContent {
            DialectInterpreterTheme {
                VoiceProfileContent(
                    state = VoiceProfileUiState(emptyList()),
                    onRecordClick = {},
                    onPlayClick = {},
                    onDeleteClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Build your voice profile").assertIsDisplayed()
        composeRule.onNodeWithText("Record a new sample").assertIsDisplayed()
    }

    @Test
    fun populated_state_shows_existing_profile_and_preview_action() {
        composeRule.setContent {
            DialectInterpreterTheme {
                VoiceProfileContent(
                    state = VoiceProfileUiState(
                        profiles = listOf(
                            VoiceProfileCardModel(
                                id = "voice-1",
                                name = "Studio Sample",
                                durationLabel = "5 s reference",
                            )
                        )
                    ),
                    onRecordClick = {},
                    onPlayClick = {},
                    onDeleteClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Studio Sample").assertIsDisplayed()
        composeRule.onNodeWithText("Preview").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the voice-profile test to verify it fails**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dialect.interpreter.ui.voice.VoiceProfileContentTest`
Expected: FAIL because `VoiceProfileContent`, `VoiceProfileUiState`, and `VoiceProfileCardModel` do not exist yet.

- [ ] **Step 3: Extract a testable voice-profile content layer and rebuild the screen around it**

```kotlin
// android/app/src/main/java/com/dialect/interpreter/ui/screens/VoiceProfileContent.kt
package com.dialect.interpreter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.ui.theme.AppColors

data class VoiceProfileCardModel(
    val id: String,
    val name: String,
    val durationLabel: String,
)

data class VoiceProfileUiState(
    val profiles: List<VoiceProfileCardModel>,
)

@Composable
fun VoiceProfileContent(
    state: VoiceProfileUiState,
    onRecordClick: () -> Unit,
    onPlayClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Build your voice profile", style = MaterialTheme.typography.headlineMedium, color = AppColors.text())
        Text("Record a short reference clip for richer on-device playback.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textSecondary())
        Button(onClick = onRecordClick, modifier = Modifier.fillMaxWidth()) {
            Text("Record a new sample")
        }

        if (state.profiles.isEmpty()) {
            Text("No saved profiles yet.", color = AppColors.textSecondary())
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.profiles, key = { it.id }) { profile ->
                    Surface(shape = RoundedCornerShape(24.dp), color = AppColors.surface()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium, color = AppColors.text())
                                Text(profile.durationLabel, style = MaterialTheme.typography.bodySmall, color = AppColors.textSecondary())
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { onPlayClick(profile.id) }) {
                                    Text("Preview", color = AppColors.accent())
                                }
                                TextButton(onClick = { onDeleteClick(profile.id) }) {
                                    Text("Delete", color = AppColors.textSecondary())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// android/app/src/main/java/com/dialect/interpreter/ui/screens/VoiceProfileScreen.kt
val state = VoiceProfileUiState(
    profiles = profiles.map {
        VoiceProfileCardModel(
            id = it.id,
            name = it.name,
            durationLabel = "${it.durationMs / 1000} s reference",
        )
    }
)

VoiceProfileContent(
    state = state,
    onRecordClick = { showRecordSheet = true },
    onPlayClick = { profileId ->
        val selected = profiles.first { it.id == profileId }
        scope.launch { audioPlayer.play(profileRepo.loadProfileAudio(selected), 16000) }
    },
    onDeleteClick = { profileId ->
        profileRepo.deleteProfile(profileId)
        profiles = profileRepo.getProfiles()
    },
)
```

- [ ] **Step 4: Run the voice-profile test to verify it passes**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dialect.interpreter.ui.voice.VoiceProfileContentTest`
Expected: PASS with both the empty-state and populated-state assertions succeeding.

- [ ] **Step 5: Commit the voice-profile redesign**

```bash
git add android/app/src/main/java/com/dialect/interpreter/ui/screens/VoiceProfileContent.kt android/app/src/main/java/com/dialect/interpreter/ui/screens/VoiceProfileScreen.kt android/app/src/androidTest/java/com/dialect/interpreter/ui/voice/VoiceProfileContentTest.kt
git commit -m "feat: redesign the voice profile surface"
```

## Task 6: Reframe The Settings Surface Around Device Intelligence

**Files:**
- Create: `android/app/src/main/java/com/dialect/interpreter/ui/screens/SettingsContent.kt`
- Modify: `android/app/src/main/java/com/dialect/interpreter/ui/screens/SettingsScreen.kt`
- Test: `android/app/src/androidTest/java/com/dialect/interpreter/ui/settings/SettingsContentTest.kt`

- [ ] **Step 1: Write the failing settings instrumentation test**

```kotlin
package com.dialect.interpreter.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dialect.interpreter.ui.screens.SettingsContent
import com.dialect.interpreter.ui.screens.SettingsUiState
import com.dialect.interpreter.ui.theme.DialectInterpreterTheme
import org.junit.Rule
import org.junit.Test

class SettingsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settings_surface_groups_device_model_and_build_information() {
        composeRule.setContent {
            DialectInterpreterTheme {
                SettingsContent(
                    state = SettingsUiState(
                        providerLabel = "NNAPI",
                        deviceLabel = "Tensor G4",
                        modelFootprint = "1200 MB",
                        asrState = "Ready",
                        ttsState = "Ready",
                        buildVersion = "1.0.0",
                    )
                )
            }
        }

        composeRule.onNodeWithText("Runtime").assertIsDisplayed()
        composeRule.onNodeWithText("Tensor G4").assertIsDisplayed()
        composeRule.onNodeWithText("1200 MB").assertIsDisplayed()
        composeRule.onNodeWithText("1.0.0").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the settings test to verify it fails**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dialect.interpreter.ui.settings.SettingsContentTest`
Expected: FAIL because `SettingsContent` and `SettingsUiState` do not exist yet.

- [ ] **Step 3: Extract a settings content model and update the screen wrapper**

```kotlin
// android/app/src/main/java/com/dialect/interpreter/ui/screens/SettingsContent.kt
package com.dialect.interpreter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dialect.interpreter.ui.theme.AppColors

data class SettingsUiState(
    val providerLabel: String,
    val deviceLabel: String,
    val modelFootprint: String,
    val asrState: String,
    val ttsState: String,
    val buildVersion: String,
)

@Composable
fun SettingsContent(state: SettingsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text("Device intelligence", style = MaterialTheme.typography.headlineMedium, color = AppColors.text())
        Text("A compact view of the active runtime and offline model state.", style = MaterialTheme.typography.bodyMedium, color = AppColors.textSecondary())
        Surface(shape = RoundedCornerShape(24.dp), color = AppColors.surface()) {
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Runtime", style = MaterialTheme.typography.titleMedium, color = AppColors.text())
                Text("Provider • ${state.providerLabel}", color = AppColors.textSecondary())
                Text("Device • ${state.deviceLabel}", color = AppColors.textSecondary())
                Text("Model footprint • ${state.modelFootprint}", color = AppColors.textSecondary())
                Text("ASR • ${state.asrState}", color = AppColors.textSecondary())
                Text("TTS • ${state.ttsState}", color = AppColors.textSecondary())
                Text("Build • ${state.buildVersion}", color = AppColors.textSecondary())
            }
        }
    }
}

// android/app/src/main/java/com/dialect/interpreter/ui/screens/SettingsScreen.kt
val uiState = SettingsUiState(
    providerLabel = if (epState == OnnxModelManager.ExecutionProvider.NNAPI) "NNAPI" else "CPU",
    deviceLabel = socName.ifBlank { "Unknown device" },
    modelFootprint = "${modelRepo.getDownloadedSize() / 1_000_000} MB",
    asrState = if (modelRepo.areAsrModelsReady()) "Ready" else "Missing",
    ttsState = if (modelRepo.areTtsModelsReady()) "Ready" else "Missing",
    buildVersion = "1.0.0",
)

SettingsContent(state = uiState)
```

- [ ] **Step 4: Run the settings test to verify it passes**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dialect.interpreter.ui.settings.SettingsContentTest`
Expected: PASS with the runtime, model footprint, and build details visible.

- [ ] **Step 5: Commit the settings redesign**

```bash
git add android/app/src/main/java/com/dialect/interpreter/ui/screens/SettingsContent.kt android/app/src/main/java/com/dialect/interpreter/ui/screens/SettingsScreen.kt android/app/src/androidTest/java/com/dialect/interpreter/ui/settings/SettingsContentTest.kt
git commit -m "feat: reframe settings around device intelligence"
```

## Task 7: Add Startup Benchmarks, Baseline Profiles, And Verification Docs

**Files:**
- Modify: `android/build.gradle.kts`
- Modify: `android/settings.gradle.kts`
- Modify: `android/app/build.gradle.kts`
- Create: `android/benchmark/build.gradle.kts`
- Create: `android/benchmark/src/main/AndroidManifest.xml`
- Create: `android/benchmark/src/main/java/com/dialect/interpreter/benchmark/StartupBenchmark.kt`
- Create: `android/benchmark/src/main/java/com/dialect/interpreter/benchmark/BaselineProfileGenerator.kt`
- Modify: `README.md`
- Create: `docs/android-verification.md`

- [ ] **Step 1: Write the failing benchmark module tests first**

```kotlin
// android/benchmark/src/main/java/com/dialect/interpreter/benchmark/StartupBenchmark.kt
package com.dialect.interpreter.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStart() = benchmarkRule.measureRepeated(
        packageName = "com.dialect.interpreter",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
    ) {
        pressHome()
        startActivityAndWait()
    }
}

// android/benchmark/src/main/java/com/dialect/interpreter/benchmark/BaselineProfileGenerator.kt
package com.dialect.interpreter.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineProfileRule.collect(
            packageName = "com.dialect.interpreter",
        ) {
            pressHome()
            startActivityAndWait()
        }
    }
}
```

- [ ] **Step 2: Run the benchmark task to verify it fails**

Run: `cd android && ./gradlew :benchmark:connectedCheck`
Expected: FAIL because the `benchmark` module is not included or configured yet.

- [ ] **Step 3: Wire the benchmark module, baseline profile consumer, and verification docs**

```kotlin
// android/build.gradle.kts
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("com.android.test") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("androidx.baselineprofile") version "1.3.4" apply false
}

// android/settings.gradle.kts
include(":app")
include(":asset_pack_asr")
include(":asset_pack_tts")
include(":benchmark")

// android/app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

baselineProfile {
    automaticGenerationDuringBuild = false
}

// android/benchmark/build.gradle.kts
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.dialect.interpreter.benchmark"
    compileSdk = 35
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
```

```xml
<!-- android/benchmark/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
</manifest>
```

```markdown
<!-- docs/android-verification.md -->
# Android Verification

## Required local checks

1. `cd android && ./gradlew :app:testDebugUnitTest`
2. `cd android && ./gradlew :app:connectedDebugAndroidTest`
3. `cd android && ./gradlew :benchmark:connectedCheck`
4. `cd android && ./gradlew :app:assembleRelease :app:lintDebug`

Run the benchmark step on a physical device or a supported managed device with API 28+.

<!-- README.md -->
## Android verification

The Android app now ships with unit tests, Compose UI tests, and startup benchmarks.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :benchmark:connectedCheck :app:assembleRelease :app:lintDebug
```
```

- [ ] **Step 4: Run the full Android verification stack to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest :benchmark:connectedCheck :app:assembleRelease :app:lintDebug`
Expected: PASS with zero unit-test failures, zero instrumentation-test failures, successful benchmark execution, a release build, and a clean debug lint report.

- [ ] **Step 5: Commit the verification and benchmark work**

```bash
git add android/build.gradle.kts android/settings.gradle.kts android/app/build.gradle.kts android/benchmark README.md docs/android-verification.md
git commit -m "perf: add Android benchmark and verification coverage"
```

## Self-Review

### Spec Coverage

- Product identity and `Auralis` branding: covered by Task 3
- Quiet-premium flagship interpretation workspace: covered by Task 4
- Runtime ownership and session controller boundary: covered by Tasks 1 and 2
- Setup, voice profile, and settings support surfaces: covered by Tasks 3, 5, and 6
- Behavioral, UI, and performance verification: covered by Tasks 1, 2, 3, 4, 5, 6, and 7

### Placeholder Scan

- No `TBD`, `TODO`, or deferred implementation markers remain in the task steps
- Every task includes explicit files, concrete code, exact commands, expected failures, expected passes, and commit commands

### Type Consistency

- `InterpretUiState`, `SessionPhase`, `InterpretMutation`, `InterpretRuntime`, and `InterpretSessionController` are introduced in Tasks 1 and 2 before later tasks depend on them
- `SetupScreenContent`, `VoiceProfileContent`, and `SettingsContent` are introduced in the same tasks that define their tests
- The benchmark module path stays `android/benchmark` consistently through file structure, task steps, and verification commands
