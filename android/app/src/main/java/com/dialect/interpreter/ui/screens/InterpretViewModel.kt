package com.dialect.interpreter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dialect.interpreter.data.DialectCatalogLoader
import com.dialect.interpreter.data.ModelRepository
import com.dialect.interpreter.data.ModelRepository.PackageInstallState
import com.dialect.interpreter.data.ModelRepository.PackageState
import com.dialect.interpreter.data.SettingsRepository
import com.dialect.interpreter.data.VoiceProfileRepository
import com.dialect.interpreter.session.SessionConfig
import com.dialect.interpreter.session.SessionController
import com.dialect.interpreter.session.SessionPhase
import com.dialect.interpreter.session.SessionSnapshot
import com.dialect.interpreter.session.SessionStartException
import com.dialect.interpreter.session.TranscriptTurn
import com.dialect.interpreter.session.TurnStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single UI state for the interpret screen (spec 03 U03): the frozen session
 * snapshot merged with persisted settings, install states and the clear
 * watermark. Rendered directly by Compose — no second controller layer.
 */
data class InterpretUiState(
    val session: SessionSnapshot = SessionSnapshot(),
    val amplitude: Float = 0f,
    val sourceDialectId: String = SettingsRepository.DEFAULT_SOURCE_DIALECT_ID,
    val targetLanguageId: String = SettingsRepository.DEFAULT_TARGET_LANGUAGE_ID,
    val voiceProfileId: String? = null,
    val voiceProfileName: String? = null,
    val installStates: Map<String, PackageInstallState> = emptyMap(),
) {
    val phase: SessionPhase get() = session.phase
    val isSessionActive: Boolean
        get() = phase == SessionPhase.STARTING || phase == SessionPhase.ACTIVE ||
            phase == SessionPhase.STOPPING
    val isBusyInstalling: Boolean
        get() = installStates.values.any {
            it.state == PackageState.INSTALLING || it.state == PackageState.VERIFYING
        }

    /**
     * Capability gating (spec 03 U02): only a *Ready* package counts. Draft or
     * probe-less states honestly keep features off — no silent downgrade.
     */
    val asrReady: Boolean
        get() = installStates[ModelRepository.PACKAGE_ASR]?.state == PackageState.READY
    val mtReady: Boolean
        get() = installStates[ModelRepository.PACKAGE_MT]?.state == PackageState.READY
    val ttsReady: Boolean
        get() = installStates[ModelRepository.PACKAGE_TTS]?.state == PackageState.READY

    /** Human-visible session mode; never claims more than the capability set. */
    val sessionModeLabel: String
        get() = when {
            !asrReady -> "等待安装语音识别模型"
            mtReady && ttsReady -> "语音传译"
            mtReady -> "文本传译"
            else -> "仅转写"
        }
}

class InterpretViewModel(
    private val sessionFactory: () -> SessionController,
    private val settings: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val voiceProfiles: VoiceProfileRepository,
    private val catalogLoader: DialectCatalogLoader,
) : ViewModel() {

    companion object {
        private val TERMINAL_STATUSES = setOf(
            TurnStatus.COMPLETE, TurnStatus.FAILED, TurnStatus.DROPPED, TurnStatus.CANCELLED,
        )

        fun isTerminal(status: TurnStatus): Boolean = status in TERMINAL_STATUSES

        /**
         * Transcript visible after a clear: turns of the watermark session at or
         * below [watermarkTurnId] are hidden, everything else shows. Late events
         * for cleared turns therefore never reappear (spec U03).
         */
        fun visibleTurns(
            turns: List<TranscriptTurn>,
            watermarkSessionId: Long,
            watermarkTurnId: Long,
        ): List<TranscriptTurn> = turns.filterNot { turn ->
            turn.sessionId == watermarkSessionId && turn.id <= watermarkTurnId
        }
    }

    private var controllerRef: SessionController? = null
    private val controller: SessionController
        get() = controllerRef ?: sessionFactory().also { controllerRef = it }

    private data class ClearWatermark(val sessionId: Long, val turnId: Long, val count: Long)

    private val clearWatermark = MutableStateFlow(ClearWatermark(-1L, -1L, 0L))
    private val voiceProfileName = MutableStateFlow<String?>(null)

    val uiState: StateFlow<InterpretUiState> = combine(
        controller.snapshot,
        controller.amplitude,
        settings.settings,
        modelRepository.packageStates,
        combine(clearWatermark, voiceProfileName) { wm, name -> wm to name },
    ) { snapshot, amplitude, userSettings, installStates, (watermark, profileName) ->
        val displaySession = if (watermark.count == 0L) snapshot else snapshot.copy(
            turns = visibleTurns(snapshot.turns, watermark.sessionId, watermark.turnId)
        )
        InterpretUiState(
            session = displaySession,
            amplitude = amplitude,
            sourceDialectId = userSettings.sourceDialectId,
            targetLanguageId = userSettings.targetLanguageId,
            voiceProfileId = userSettings.voiceProfileId,
            voiceProfileName = userSettings.voiceProfileId?.let { profileName },
            installStates = installStates,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InterpretUiState(),
    )

    init {
        viewModelScope.launch { modelRepository.refresh() }
        settings.settings.onEach { userSettings ->
            val name = userSettings.voiceProfileId?.let { id ->
                withContext(Dispatchers.IO) {
                    voiceProfiles.getProfiles().firstOrNull { it.id == id }?.name
                }
            }
            voiceProfileName.value = name
        }.launchIn(viewModelScope)
    }

    // ---------------------------------------------------------------- session

    /**
     * Start a session with the persisted configuration. The screen checks
     * RECORD_AUDIO before calling; capability gaps surface through
     * [SessionStartException] and are rendered from the snapshot problem.
     */
    fun startSession() {
        if (uiState.value.isSessionActive) return
        viewModelScope.launch {
            val current = settings.settings.first()
            val (source, target) = withContext(Dispatchers.IO) {
                val catalog = loadCatalog()
                val source = catalog?.dialects
                    ?.firstOrNull { it.id == current.sourceDialectId }?.asrLanguage ?: "Chinese"
                val target = catalog?.targetLanguages
                    ?.firstOrNull { it.id == current.targetLanguageId }?.asrLanguage ?: "Chinese"
                source to target
            }
            val config = SessionConfig(
                sourceLanguage = source,
                targetLanguage = target,
                voiceProfileId = current.voiceProfileId,
            )
            try {
                // stop() is safe after failure (interface-A): restart from a
                // FAILED session tears the old one down first.
                if (controller.snapshot.value.phase == SessionPhase.FAILED) {
                    controller.stop()
                }
                controller.start(config)
            } catch (e: SessionStartException) {
                // Snapshot already carries phase=FAILED + problem; nothing else to do.
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    fun stopSession() {
        viewModelScope.launch { controller.stop() }
    }

    // --------------------------------------------------------------- settings

    fun setSourceDialect(id: String) {
        viewModelScope.launch { settings.setSourceDialect(id) }
    }

    fun setTargetLanguage(id: String) {
        viewModelScope.launch { settings.setTargetLanguage(id) }
    }

    fun setVoiceProfile(id: String?) {
        viewModelScope.launch { settings.setVoiceProfile(id) }
    }

    /**
     * Clear the transcript. The transcript is intentionally not persisted
     * (spec U03); the watermark keeps late events for cleared turns hidden.
     */
    fun clearConversation() {
        val snapshot = controller.snapshot.value
        val maxTurnId = snapshot.turns.maxOfOrNull { it.id } ?: -1L
        val previous = clearWatermark.value
        clearWatermark.value = ClearWatermark(snapshot.sessionId, maxTurnId, previous.count + 1)
    }

    private fun loadCatalog(): com.dialect.interpreter.data.DialectCatalog? = try {
        catalogLoader.load()
    } catch (e: Exception) {
        null
    }

    override fun onCleared() {
        // Spec 02 R01: synchronous, idempotent close request; cleanup runs on
        // the session's own scope. Never launch release work into the (already
        // cancelled) viewModelScope here, and never runBlocking on main.
        controllerRef?.close()
        super.onCleared()
    }
}

class InterpretViewModelFactory(
    private val sessionFactory: () -> SessionController,
    private val settings: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val voiceProfiles: VoiceProfileRepository,
    private val catalogLoader: DialectCatalogLoader,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(InterpretViewModel::class.java))
        return InterpretViewModel(sessionFactory, settings, modelRepository, voiceProfiles, catalogLoader) as T
    }
}
