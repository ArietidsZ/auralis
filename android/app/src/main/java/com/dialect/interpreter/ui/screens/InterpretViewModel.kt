package com.dialect.interpreter.ui.screens

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dialect.interpreter.inference.PipelineOrchestrator
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InterpretViewModel(
    private val pipeline: PipelineOrchestrator
) : ViewModel() {

    companion object {
        private const val MAX_MESSAGES = 200
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val pipelineState = pipeline.state
    val amplitude = pipeline.amplitude
    val telemetry = pipeline.telemetry

    init {
        viewModelScope.launch {
            pipeline.events.collect { onPipelineEvent(it) }
        }
    }

    fun startSession(sourceLanguage: String, targetLanguage: String) {
        _errorMessage.value = null
        pipeline.start(
            scope = viewModelScope,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
    }

    fun stopSession() {
        viewModelScope.launch { pipeline.stop() }
    }

    private fun onPipelineEvent(event: PipelineOrchestrator.PipelineEvent) {
        when (event) {
            is PipelineOrchestrator.PipelineEvent.AsrResult -> handleAsrResult(event)
            is PipelineOrchestrator.PipelineEvent.MtStarted -> Unit
            is PipelineOrchestrator.PipelineEvent.MtComplete -> handleMtComplete(event)
            is PipelineOrchestrator.PipelineEvent.TtsStarted -> handleTtsStarted(event)
            is PipelineOrchestrator.PipelineEvent.TtsComplete -> handleTtsComplete(event)
            is PipelineOrchestrator.PipelineEvent.UtteranceDropped -> Unit
            is PipelineOrchestrator.PipelineEvent.Error -> _errorMessage.value = event.message
            else -> Unit
        }
    }

    fun clearConversation() {
        _messages.value = emptyList()
        _errorMessage.value = null
    }

    private fun handleAsrResult(event: PipelineOrchestrator.PipelineEvent.AsrResult) {
        if (event.text.isBlank()) return
        _errorMessage.value = null

        // Key the message by the pipeline-minted messageId so updates from MT/TTS
        // always land on the right message, even if earlier utterances were dropped.
        val updated = _messages.value.filterNot { it.id == event.messageId } + ChatMessage(
            id = event.messageId,
            sourceText = event.text,
            targetText = "",
            asrLatencyMs = event.latencyMs,
            isProcessing = true
        )
        _messages.value = updated.takeLast(MAX_MESSAGES)
    }

    private fun handleTtsStarted(event: PipelineOrchestrator.PipelineEvent.TtsStarted) {
        updateMessage(event.messageId) { current ->
            current.copy(targetText = event.text.ifBlank { current.targetText })
        }
    }

    private fun handleMtComplete(event: PipelineOrchestrator.PipelineEvent.MtComplete) {
        updateMessage(event.messageId) {
            it.copy(
                targetText = event.translatedText,
                mtLatencyMs = event.latencyMs,
                mtRuntime = event.runtime
            )
        }
    }

    private fun handleTtsComplete(event: PipelineOrchestrator.PipelineEvent.TtsComplete) {
        updateMessage(event.messageId) { current ->
            current.copy(
                targetText = current.targetText.ifBlank { current.sourceText },
                isProcessing = false,
                ttsLatencyMs = event.inferenceMs,
                playbackLatencyMs = event.playbackMs,
                ttsRtf = event.rtf
            )
        }
    }

    private fun updateMessage(messageId: Long, transform: (ChatMessage) -> ChatMessage) {
        val oldMessages = _messages.value
        val index = oldMessages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val mutable = oldMessages.toMutableList()
        mutable[index] = transform(mutable[index])
        _messages.value = mutable
    }

    override fun onCleared() {
        viewModelScope.launch { pipeline.release() }
        super.onCleared()
    }
}

class InterpretViewModelFactory(
    private val pipelineFactory: () -> PipelineOrchestrator
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return InterpretViewModel(pipelineFactory()) as T
    }
}
