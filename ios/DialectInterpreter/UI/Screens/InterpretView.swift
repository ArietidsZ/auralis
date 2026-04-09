import SwiftUI

/// Main interpretation screen — minimalistic chat UI with adaptive colors.
struct InterpretView: View {
    @Environment(OnnxModelManager.self) private var modelManager
    @State private var viewModel = InterpretViewModel()
    @State private var sourceDialect = "四川话"
    @State private var targetLanguage = "普通话"
    @State private var showDialectSheet = false
    @State private var pipeline: PipelineOrchestrator?

    @State private var pipelineState: PipelineOrchestrator.PipelineState = .idle
    @State private var amplitude: Float = 0
    @State private var telemetry = PipelineOrchestrator.PipelineTelemetry()

    private var isRunning: Bool { pipelineState != .idle }

    private var targetLanguageCode: String {
        AsrEngine.supportedLanguages
            .first(where: { $0.0 == targetLanguage })
            .map(\.1) ?? "Chinese"
    }

    private var epName: String {
        modelManager.selectedProvider == .coreML ? "ANE" : "CPU"
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
                        .foregroundStyle(.appAccent)
                }
            }
            ToolbarItem(placement: .principal) {
                VStack(spacing: 1) {
                    Text("方言传译")
                        .font(.appTitleMedium)
                        .foregroundStyle(.appText)

                    Text(subtitleText)
                        .font(.appLabelSmall)
                        .foregroundStyle(isRunning ? Color.appLiveGreen : .appTextSecondary)

                    if hasTelemetry {
                        Text(telemetryText)
                            .font(.system(size: 9))
                            .foregroundStyle(.appTextTertiary)
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
            let p = PipelineOrchestrator(
                asrEngine: AsrEngine(modelManager: modelManager),
                ttsEngine: TtsEngine(modelManager: modelManager),
                audioRecorder: AudioRecorder(),
                audioPlayer: AudioPlayer()
            )
            pipeline = p
        }
        .onDisappear {
            pipeline?.release()
            viewModel.clearConversation()
        }
    }

    // MARK: - Subtitle

    private var subtitleText: String {
        switch pipelineState {
        case .idle: return "\(sourceDialect) → \(targetLanguage)"
        case .loading: return "加载模型中…"
        case .listening: return "监听中 · \(epName)"
        case .recognizing: return "识别中…"
        case .synthesizing: return "合成中…"
        case .playing: return "播放中…"
        }
    }

    private var hasTelemetry: Bool {
        telemetry.asrCount > 0 || telemetry.ttsCount > 0
    }

    private var telemetryText: String {
        "ASR \(telemetry.lastAsrMs)ms · TTS \(telemetry.lastTtsMs)ms · RTF \(String(format: "%.2f", telemetry.lastRtf))"
    }

    // MARK: - Empty State

    private var emptyStateView: some View {
        VStack(spacing: 20) {
            Spacer()
            FloatingIcon()
            Text("开始说话，翻译将出现在这里")
                .font(.appBodyMedium)
                .foregroundStyle(.appTextSecondary.opacity(0.5))
            Text("\(sourceDialect) → \(targetLanguage) · \(epName)")
                .font(.appLabelSmall)
                .foregroundStyle(.appTextTertiary)
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
                    WaveformView(amplitude: amplitude, isActive: true)
                        .frame(height: 28)
                } else {
                    Image(systemName: "textformat.abc")
                        .foregroundStyle(.appTextSecondary.opacity(0.5))
                        .font(.system(size: 14))
                    Text("\(sourceDialect) → \(targetLanguage)")
                        .font(.appBodyMedium)
                        .foregroundStyle(.appTextSecondary)
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
                    .scaleEffect(isRunning ? 1 + CGFloat(amplitude) * 0.3 : 0.8)
                    .animation(.spring(response: 0.35, dampingFraction: 0.5), value: amplitude)
                    .animation(.easeInOut(duration: 0.3), value: isRunning)

                Button(action: togglePipeline) {
                    Image(systemName: isRunning ? "stop.fill" : "mic.fill")
                        .foregroundStyle(.white)
                        .font(.system(size: 18))
                        .frame(width: 48, height: 48)
                        .background(isRunning ? Color.appRecordingRed : .appAccent)
                        .clipShape(Circle())
                        .scaleEffect(isRunning ? 1 + CGFloat(amplitude) * 0.2 : 1)
                        .animation(.spring(response: 0.3, dampingFraction: 0.55), value: amplitude)
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
            pipelineState = .idle
            amplitude = 0
        } else {
            pipeline.start(targetLanguage: targetLanguageCode)
            Task {
                for await event in pipeline.events {
                    await MainActor.run {
                        viewModel.onPipelineEvent(event)
                        if case .stateChange(let state) = event {
                            pipelineState = state
                        }
                        amplitude = pipeline.amplitude
                        telemetry = pipeline.telemetry
                    }
                }
            }
        }
    }
}

// MARK: - Floating Icon

private struct FloatingIcon: View {
    @State private var isFloating = false

    var body: some View {
        Image(systemName: "bubble.left.and.bubble.right")
            .font(.system(size: 64))
            .foregroundStyle(.appTextTertiary.opacity(0.3))
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
        return formatter.string(from: message.timestamp)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Source text — left (incoming)
            if !message.sourceText.isEmpty {
                HStack {
                    VStack(alignment: .trailing, spacing: 3) {
                        Text(message.sourceText)
                            .font(.appBodyLarge)
                            .foregroundStyle(.appText)

                        HStack(spacing: 4) {
                            if message.asrLatencyMs > 0 {
                                Text("\(message.asrLatencyMs)ms")
                                    .font(.system(size: 10))
                                    .foregroundStyle(.appTextSecondary)
                            }
                            Text(timeStr)
                                .font(.system(size: 10))
                                .foregroundStyle(.appTextSecondary)
                        }
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
                                .foregroundStyle(.appText)
                        }

                        HStack(spacing: 4) {
                            if message.ttsLatencyMs > 0 {
                                Text("TTS \(message.ttsLatencyMs)ms")
                                    .font(.system(size: 10))
                                    .foregroundStyle(.appTextSecondary)
                            }
                            if message.ttsRtf > 0 {
                                Text("RTF \(String(format: "%.1f", message.ttsRtf))")
                                    .font(.system(size: 10))
                                    .foregroundStyle(.appTextSecondary)
                            }
                            Text(timeStr)
                                .font(.system(size: 10))
                                .foregroundStyle(.appTextSecondary)

                            if !message.isProcessing {
                                Image(systemName: "checkmark.circle.fill")
                                    .font(.system(size: 11))
                                    .foregroundStyle(.appAccent)
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
