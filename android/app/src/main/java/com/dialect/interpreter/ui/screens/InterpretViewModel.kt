package com.dialect.interpreter.ui.screens

import androidx.lifecycle.ViewModel
import com.dialect.interpreter.inference.PipelineOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class InterpretViewModel : ViewModel() {

    companion object {
        private const val MAX_MESSAGES = 200
    }

    private val pendingMessageIds = ArrayDeque<Long>()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun onPipelineEvent(event: PipelineOrchestrator.PipelineEvent) {
        when (event) {
            is PipelineOrchestrator.PipelineEvent.AsrResult -> handleAsrResult(event)
            is PipelineOrchestrator.PipelineEvent.TtsStarted -> handleTtsStarted(event)
            is PipelineOrchestrator.PipelineEvent.TtsComplete -> handleTtsComplete(event)
            else -> Unit
        }
    }

    fun clearConversation() {
        pendingMessageIds.clear()
        _messages.value = emptyList()
    }

    private fun handleAsrResult(event: PipelineOrchestrator.PipelineEvent.AsrResult) {
        if (event.text.isBlank()) return

        val message = ChatMessage(
            sourceText = event.text,
            targetText = "",
            asrLatencyMs = event.latencyMs,
            isProcessing = true
        )

        val updated = (_messages.value + message).takeLast(MAX_MESSAGES)
        _messages.value = updated
        pendingMessageIds.addLast(message.id)

        while (pendingMessageIds.size > MAX_MESSAGES) {
            pendingMessageIds.removeFirst()
        }
    }

    private fun handleTtsStarted(event: PipelineOrchestrator.PipelineEvent.TtsStarted) {
        val messageId = pendingMessageIds.firstOrNull() ?: return
        updateMessage(messageId) { it.copy(targetText = event.text) }
    }

    private fun handleTtsComplete(event: PipelineOrchestrator.PipelineEvent.TtsComplete) {
        val messageId = if (pendingMessageIds.isNotEmpty()) {
            pendingMessageIds.removeFirst()
        } else {
            null
        } ?: return

        updateMessage(messageId) { current ->
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
}
