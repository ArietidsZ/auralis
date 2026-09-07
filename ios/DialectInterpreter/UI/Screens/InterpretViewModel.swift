import Foundation
import Observation

/// ViewModel reducing unified, id-bearing pipeline events into chat messages.
///
/// Reduction contract (spec 03/04):
///   - Exactly one message per turn, keyed by the orchestrator's globally
///     monotonic turn id — no FIFO pending queue, so a turn whose translation
///     failed can never receive the next turn's translation.
///   - `translatedText == nil` is never filled with the source text and never
///     replaced by a later turn's translation.
///   - Terminal statuses close the message (no spinner); dropped turns surface
///     an explicit notice — no silent data loss.
///   - clearConversation() installs a turn-id watermark: late events from
///     turns that existed before the clear cannot resurrect messages.
@Observable
final class InterpretViewModel {
    static let maxMessages = 200

    var messages: [ChatMessage] = []
    /// Last orchestrator error/notice, for surfacing in the UI.
    var lastError: String?

    /// Turns with id <= this watermark are ignored (cleared conversation).
    private var clearedUpToTurnId: Int64 = 0

    func onPipelineEvent(_ event: PipelineOrchestrator.PipelineEvent) {
        switch event {
        case .turnUpdated(let turn):
            reduce(turn)
        case .stateChange:
            break  // UI reads the orchestrator's observable state directly
        case .error(let message):
            lastError = message
        }
    }

    func clearConversation() {
        clearedUpToTurnId = messages.map(\.id).max() ?? clearedUpToTurnId
        messages.removeAll()
    }

    // MARK: - Reduction

    private func reduce(_ turn: PipelineOrchestrator.TurnState) {
        guard turn.id > clearedUpToTurnId else { return }

        let message: ChatMessage
        if let index = messages.firstIndex(where: { $0.id == turn.id }) {
            message = ChatMessage(turn: turn, previous: messages[index])
            messages[index] = message
        } else {
            message = ChatMessage(turn: turn, previous: nil)
            messages.append(message)
            if messages.count > Self.maxMessages {
                messages.removeFirst(messages.count - Self.maxMessages)
            }
        }
    }
}

/// Chat message data model. Identity is the pipeline turn id.
struct ChatMessage: Identifiable, Equatable {
    let id: Int64
    var sourceText: String
    var targetText: String
    var timestamp: Date = Date()
    var isProcessing: Bool = false
    var translationUnavailableReason: String?

    init(id: Int64, sourceText: String, targetText: String, isProcessing: Bool = false,
         translationUnavailableReason: String? = nil) {
        self.id = id
        self.sourceText = sourceText
        self.targetText = targetText
        self.isProcessing = isProcessing
        self.translationUnavailableReason = translationUnavailableReason
    }

    /// Reduce a turn update onto the message (new message when previous==nil).
    init(turn: PipelineOrchestrator.TurnState, previous: ChatMessage?) {
        self.id = turn.id
        self.timestamp = previous?.timestamp ?? Date()
        // A nil translatedText is NEVER filled with the source text and never
        // overwritten by a later turn's translation (identity-keyed updates).
        self.sourceText = turn.sourceText ?? previous?.sourceText ?? ""
        self.targetText = turn.translatedText ?? previous?.targetText ?? ""

        switch turn.status {
        case .recognizing, .translating, .synthesizing, .playing:
            isProcessing = true
        case .complete:
            isProcessing = false
        case .failed(let reason):
            isProcessing = false
            translationUnavailableReason = reason
        case .mtUnavailable(let reason):
            isProcessing = false
            translationUnavailableReason = reason
        case .ttsUnavailable:
            isProcessing = false
            // Translation is real; only speech is missing. No reason pill —
            // the message is complete as text.
        case .cancelled:
            isProcessing = false
            translationUnavailableReason = "已取消"
        case .dropped:
            isProcessing = false
            translationUnavailableReason = "语音段过长被丢弃：处理队列已满"
        }
    }
}
