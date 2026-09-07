import SwiftUI

/// Main interpretation screen — minimalistic chat UI with adaptive colors.
///
/// State ownership: the pipeline is a single @Observable instance; the view
/// reads `state`, `amplitude`, `telemetry` and `ttsAvailable` directly from it
/// (Observation tracks them), so no duplicated @State copies can drift.
struct InterpretView: View {
    @Environment(OnnxModelManager.self) private var modelManager
    @State private var viewModel = InterpretViewModel()
    @State private var sourceDialect = "四川话"
    @State private var targetLanguage = "普通话"
    @State private var showDialectSheet = false
    @State private var pipeline: PipelineOrchestrator?
    @State private var preparationTask: Task<Void, Never>?
    @State private var teardownTask: Task<Void, Never>?
    @State private var catalog: SharedContracts.DialectCatalog?

    private var isRunning: Bool {
        guard let pipeline else { return false }
        return pipeline.state != .idle && pipeline.state != .failed
    }

    private var targetLanguageCode: String {
        guard let catalog else { return "Chinese" }
        return catalog.targetLanguages
            .first(where: { $0.displayLabel == targetLanguage })
            .map { $0.mtLanguage ?? $0.asrLanguage ?? $0.id } ?? "Chinese"
    }

    private var selectedSource: SharedContracts.DialectCatalog.Dialect? {
        catalog?.dialects.first { $0.displayLabel == sourceDialect }
    }

    /// The actual, measured execution provider — never an ANE claim.
    private var epName: String {
        modelManager.status.executionProvider
    }

    var body: some View {
        VStack(spacing: 0) {
            if viewModel.messages.isEmpty {
                emptyStateView
            } else {
                chatListView
            }
            bottomBar
        }
        .background(Color.appBg)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { showDialectSheet = true } label: {
                    Image(systemName: "textformat.abc")
                        .foregroundStyle(Color.appAccent)
                }
            }
            ToolbarItem(placement: .principal) {
                VStack(spacing: 1) {
                    Text("方言传译")
                        .font(.appTitleMedium)
                        .foregroundStyle(Color.appText)

                    Text(subtitleText)
                        .font(.appLabelSmall)
                        .foregroundStyle(isRunning ? Color.appLiveGreen : .appTextSecondary)

                    if hasTelemetry {
                        Text(telemetryText)
                            .font(.system(size: 9))
                            .foregroundStyle(Color.appTextTertiary)
                            .lineLimit(1)
                    }
                }
            }
        }
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .sheet(isPresented: $showDialectSheet) {
            DialectSelectorView(sourceDialect: $sourceDialect, targetLanguage: $targetLanguage)
        }
        .onAppear {
            catalog = try? SharedContracts.loadCatalog()
        }
        .task {
            await teardownTask?.value
            guard !Task.isCancelled else { return }
            let availability = modelManager
            let preparation = Task { await availability.refreshStatuses() }
            preparationTask = preparation
            await preparation.value
            guard !Task.isCancelled, !preparation.isCancelled else { return }
            preparationTask = nil
            // Create the pipeline here so its event stream subscription and
            // its lifetime are bound to this view instance.
            let p: PipelineOrchestrator
            if let existing = pipeline {
                p = existing
            } else {
                // Runtime handles belong to this view's session; a delayed
                // release from an older view cannot close a new session's store.
                let sessionModels = OnnxModelManager()
                let recognition = AsrRecognitionAdapter(engine: AsrEngine(modelManager: sessionModels),
                                                         isReady: { availability.status.asr == .ready })
                p = PipelineOrchestrator(
                    asr: recognition,
                    translation: HyMtTranslationAdapter(engine: HyMtTranslationEngine(),
                                                        isReady: { availability.status.mt == .ready }),
                    tts: TtsSynthesisAdapter(engine: TtsEngine(modelManager: sessionModels),
                                             referenceRecognizer: recognition,
                                             isReady: { availability.status.tts == .ready }),
                    audioCapture: AudioCaptureAdapter(recorder: AudioRecorder()),
                    audioPlayback: AudioPlaybackAdapter(player: AudioPlayer())
                )
                pipeline = p
            }
            for await event in p.events {
                viewModel.onPipelineEvent(event)
            }
        }
        .onDisappear {
            let p = pipeline
            let preparation = preparationTask
            let previousTeardown = teardownTask
            preparation?.cancel()
            pipeline = nil
            preparationTask = nil
            // Join preparation too: verifier probes may still be releasing
            // native resources even when SwiftUI has cancelled its task.
            teardownTask = Task {
                await previousTeardown?.value
                await preparation?.value
                await p?.release()
            }
            viewModel.clearConversation()
        }
    }

    // MARK: - Subtitle

    private var subtitleText: String {
        switch pipeline?.state ?? .idle {
        case .idle: return "\(sourceDialect) → \(targetLanguage)"
        case .starting: return "加载模型中…"
        case .failed: return "启动失败，请检查模型"
        case .stopping: return "正在停止…"
        case .listening:
            if let pipeline, !pipeline.ttsAvailable { return "文字传译 · \(epName)" }
            return "监听中 · \(epName)"
        case .recognizing: return "识别中…"
        case .translating: return "翻译中…"
        case .synthesizing: return "合成中…"
        case .playing: return "播放中…"
        }
    }

    private var hasTelemetry: Bool {
        guard let pipeline else { return false }
        return pipeline.telemetry.asrCount > 0 || pipeline.telemetry.ttsCount > 0
    }

    private var telemetryText: String {
        guard let pipeline else { return "" }
        let t = pipeline.telemetry
        return "ASR \(t.lastAsrMs)ms · TTS \(t.lastTtsMs)ms · RTF \(String(format: "%.2f", t.lastRtf))"
    }

    // MARK: - Empty State

    private var emptyStateView: some View {
        VStack(spacing: 20) {
            Spacer()
            FloatingIcon()
            Text("开始说话，翻译将出现在这里")
                .font(.appBodyMedium)
                .foregroundStyle(Color.appTextSecondary.opacity(0.5))
            Text("\(sourceDialect) → \(targetLanguage) · \(epName)")
                .font(.appLabelSmall)
                .foregroundStyle(Color.appTextTertiary)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Chat List

    private var chatListView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(viewModel.messages) { message in
                        ChatBubblePair(message: message)
                            .id(message.id)
                            .transition(.asymmetric(
                                insertion: .scale(scale: 0.95).combined(with: .opacity),
                                removal: .opacity
                            ))
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .onChange(of: viewModel.messages.count) {
                if let last = viewModel.messages.last {
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
        }
    }

    // MARK: - Bottom Bar

    private var bottomBar: some View {
        HStack(spacing: 10) {
            // Dialect label / waveform
            HStack {
                if isRunning {
                    WaveformView(amplitude: pipeline?.amplitude ?? 0, isActive: true)
                        .frame(height: 28)
                } else {
                    Image(systemName: "textformat.abc")
                        .foregroundStyle(Color.appTextSecondary.opacity(0.5))
                        .font(.system(size: 14))
                    Text("\(sourceDialect) → \(targetLanguage)")
                        .font(.appBodyMedium)
                        .foregroundStyle(Color.appTextSecondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(Color.appBg)
            .clipShape(RoundedRectangle(cornerRadius: AppStyle.radiusPill))

            // Mic button with ring
            ZStack {
                // Outer concentric ring
                Circle()
                    .fill(isRunning ? Color.appRecordingRed.opacity(0.12) : Color.clear)
                    .frame(width: 56, height: 56)
                    .scaleEffect(isRunning ? 1 + CGFloat(pipeline?.amplitude ?? 0) * 0.3 : 0.8)
                    .animation(.spring(response: 0.35, dampingFraction: 0.5), value: pipeline?.amplitude ?? 0)
                    .animation(.easeInOut(duration: 0.3), value: isRunning)

                Button(action: togglePipeline) {
                    Image(systemName: isRunning ? "stop.fill" : "mic.fill")
                        .foregroundStyle(isRunning ? Color.white : Color.appOnAccent)
                        .font(.system(size: 18))
                        .frame(width: 48, height: 48)
                        .background(isRunning ? Color.appRecordingRed : .appAccent)
                        .clipShape(Circle())
                        .scaleEffect(isRunning ? 1 + CGFloat(pipeline?.amplitude ?? 0) * 0.2 : 1)
                        .animation(.spring(response: 0.3, dampingFraction: 0.55), value: pipeline?.amplitude ?? 0)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.regularMaterial)
    }

    private func togglePipeline() {
        guard let pipeline else { return }
        if isRunning {
            pipeline.stop()
        } else {
            // User-selected source and detected language are distinct. The
            // pinned Qwen backend may return no detected language at all.
            let source = selectedSource
            pipeline.start(targetLanguage: targetLanguageCode,
                           sourceLanguage: source?.mtLanguage ?? "auto")
        }
    }
}

// MARK: - Floating Icon

private struct FloatingIcon: View {
    @State private var isFloating = false

    var body: some View {
        Image(systemName: "bubble.left.and.bubble.right")
            .font(.system(size: 64))
            .foregroundStyle(Color.appTextTertiary.opacity(0.3))
            .offset(y: isFloating ? -6 : 0)
            .animation(
                .easeInOut(duration: 2.0).repeatForever(autoreverses: true),
                value: isFloating
            )
            .onAppear { isFloating = true }
    }
}

// MARK: - Chat Bubbles

private struct ChatBubblePair: View {
    let message: ChatMessage

    private var timeStr: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        formatter.timeZone = .current
        return formatter.string(from: message.timestamp)
    }

    /// Explicit, visible notice for turns that carry no text (dropped,
    /// cancelled, failed with no transcript) — never silent data loss.
    private var isNoticeOnly: Bool {
        message.sourceText.isEmpty && message.targetText.isEmpty
            && !message.isProcessing && message.translationUnavailableReason != nil
    }

    var body: some View {
        if isNoticeOnly {
            HStack {
                Text(message.translationUnavailableReason ?? "")
                    .font(.appLabelSmall)
                    .foregroundStyle(Color.appTextTertiary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.appBubbleSource.opacity(0.6))
                    .clipShape(Capsule())
            }
            .frame(maxWidth: .infinity)
        } else {
            VStack(spacing: 0) {
                // Source text — left (incoming)
                if !message.sourceText.isEmpty {
                    HStack {
                        VStack(alignment: .trailing, spacing: 3) {
                            Text(message.sourceText)
                                .font(.appBodyLarge)
                                .foregroundStyle(Color.appText)

                            Text(timeStr)
                                .font(.system(size: 10))
                                .foregroundStyle(Color.appTextSecondary)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(Color.appBubbleSource)
                        .clipShape(BubbleShape(isIncoming: true))
                        .shadow(color: .black.opacity(0.04), radius: 2, y: 1)

                        Spacer(minLength: 48)
                    }
                    .padding(.bottom, 3)
                }

                // Target text — right (outgoing)
                if !message.targetText.isEmpty || message.isProcessing {
                    HStack {
                        Spacer(minLength: 48)

                        VStack(alignment: .trailing, spacing: 3) {
                            if message.isProcessing && message.targetText.isEmpty {
                                TypingIndicator()
                            } else {
                                Text(message.targetText)
                                    .font(.appBodyLarge)
                                    .foregroundStyle(Color.appText)
                            }

                            HStack(spacing: 4) {
                                Text(timeStr)
                                    .font(.system(size: 10))
                                    .foregroundStyle(Color.appTextSecondary)

                                if !message.isProcessing {
                                    Image(systemName: "checkmark.circle.fill")
                                        .font(.system(size: 11))
                                        .foregroundStyle(Color.appAccent)
                                }
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(Color.appBubbleTarget)
                        .clipShape(BubbleShape(isIncoming: false))
                        .shadow(color: .black.opacity(0.04), radius: 2, y: 1)
                    }
                    .padding(.top, 3)
                }

                // MT/TTS unavailable pill: transcript stays, reason explicit.
                if let reason = message.translationUnavailableReason,
                   !message.sourceText.isEmpty {
                    HStack {
                        Spacer(minLength: 48)
                        Text(reason)
                            .font(.system(size: 10))
                            .foregroundStyle(Color.appTextTertiary)
                    }
                    .padding(.top, 1)
                }
            }
        }
    }
}

/// Rounded bubble shape.
private struct BubbleShape: Shape {
    let isIncoming: Bool

    func path(in rect: CGRect) -> Path {
        let r: CGFloat = AppStyle.radiusBubble
        let tail: CGFloat = 6

        if isIncoming {
            return Path(roundedRect: rect, cornerRadii: .init(
                topLeading: r, bottomLeading: tail, bottomTrailing: r, topTrailing: r
            ))
        } else {
            return Path(roundedRect: rect, cornerRadii: .init(
                topLeading: r, bottomLeading: r, bottomTrailing: tail, topTrailing: r
            ))
        }
    }
}

/// Smooth wave-scale typing indicator.
private struct TypingIndicator: View {
    @State private var phase: [Bool] = [false, false, false]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Color.appTextSecondary.opacity(0.4))
                    .frame(width: 6, height: 6)
                    .scaleEffect(phase[index] ? 1.1 : 0.6)
                    .animation(
                        .easeInOut(duration: 0.5)
                        .repeatForever(autoreverses: true)
                        .delay(Double(index) * 0.16),
                        value: phase[index]
                    )
            }
        }
        .onAppear {
            for i in 0..<3 { phase[i] = true }
        }
    }
}
