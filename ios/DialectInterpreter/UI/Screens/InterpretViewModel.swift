import SwiftUI

/// ViewModel managing chat messages and pipeline events.
@Observable
final class InterpretViewModel {
    static let maxMessages = 200

    private var pendingMessageIds: [Int64] = []

    var messages: [ChatMessage] = []

    func onPipelineEvent(_ event: PipelineOrchestrator.PipelineEvent) {
        switch event {
        case .asrResult(let text, _, let latencyMs):
            handleAsrResult(text: text, latencyMs: latencyMs)
        case .ttsStarted(let text):
            handleTtsStarted(text: text)
        case .ttsComplete(_, let inferenceMs, let playbackMs, let rtf):
            handleTtsComplete(inferenceMs: inferenceMs, playbackMs: playbackMs, rtf: rtf)
        default:
            break
        }
    }

    func clearConversation() {
        pendingMessageIds.removeAll()
        messages.removeAll()
    }

    private func handleAsrResult(text: String, latencyMs: Int64) {
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return }

        let message = ChatMessage(
            sourceText: text,
            targetText: "",
            asrLatencyMs: latencyMs,
            isProcessing: true
        )

        messages.append(message)
        if messages.count > Self.maxMessages {
            messages.removeFirst(messages.count - Self.maxMessages)
        }
        pendingMessageIds.append(message.id)
    }

    private func handleTtsStarted(text: String) {
        guard let messageId = pendingMessageIds.first,
              let index = messages.firstIndex(where: { $0.id == messageId }) else { return }
        messages[index].targetText = text
    }

    private func handleTtsComplete(inferenceMs: Int64, playbackMs: Int64, rtf: Float) {
        guard !pendingMessageIds.isEmpty else { return }
        let messageId = pendingMessageIds.removeFirst()

        guard let index = messages.firstIndex(where: { $0.id == messageId }) else { return }
        messages[index].isProcessing = false
        messages[index].ttsLatencyMs = inferenceMs
        messages[index].playbackLatencyMs = playbackMs
        messages[index].ttsRtf = rtf
        if messages[index].targetText.isEmpty {
            messages[index].targetText = messages[index].sourceText
        }
    }
}

/// Chat message data model.
struct ChatMessage: Identifiable {
    let id: Int64 = Int64(CFAbsoluteTimeGetCurrent() * 1_000_000_000) + Int64.random(in: 0..<1000)
    var sourceText: String
    var targetText: String
    var timestamp: Date = Date()
    var asrLatencyMs: Int64 = 0
    var ttsLatencyMs: Int64 = 0
    var playbackLatencyMs: Int64 = 0
    var ttsRtf: Float = 0
    var isProcessing: Bool = false
}
