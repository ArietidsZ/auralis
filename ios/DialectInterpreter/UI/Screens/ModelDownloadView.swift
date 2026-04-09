import SwiftUI

/// Model extraction screen — hero layout with circular progress ring.
struct ModelDownloadView: View {
    let onComplete: () -> Void

    @State private var isExtracting = false
    @State private var progress = ModelRepository.ExtractionProgress()
    @State private var appeared = false

    private let modelRepo = ModelRepository()

    private var frac: Double {
        progress.totalFiles > 0
            ? Double(progress.completedFiles) / Double(progress.totalFiles)
            : 0
    }

    var body: some View {
        ZStack {
            Color.appBg.ignoresSafeArea()

            VStack(spacing: 24) {
                // Circular progress ring
                ZStack {
                    Circle()
                        .stroke(Color.appAccent.opacity(0.1), lineWidth: 6)
                        .frame(width: 120, height: 120)

                    if isExtracting && !progress.isComplete {
                        Circle()
                            .trim(from: 0, to: frac)
                            .stroke(Color.appAccent, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                            .frame(width: 120, height: 120)
                            .rotationEffect(.degrees(-90))
                            .animation(.easeOut(duration: 0.4), value: frac)
                    }

                    // Center icon
                    Group {
                        if progress.isComplete {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 44))
                                .foregroundStyle(.appGreen)
                                .transition(.scale.combined(with: .opacity))
                        } else if isExtracting {
                            Text("\(Int(frac * 100))%")
                                .font(.appTitleMedium)
                                .foregroundStyle(.appAccent)
                                .fontWeight(.bold)
                                .contentTransition(.numericText())
                        } else {
                            Image(systemName: "square.stack.3d.up")
                                .font(.system(size: 40))
                                .foregroundStyle(.appAccent.opacity(0.6))
                        }
                    }
                    .animation(.spring(response: 0.5, dampingFraction: 0.7), value: progress.isComplete)
                }

                Text(progress.isComplete ? "准备就绪!" : "准备 AI 模型")
                    .font(.appTitleLarge)
                    .foregroundStyle(.appText)

                if !isExtracting {
                    Text("模型已预装在应用中\n首次启动需要解压初始化")
                        .font(.appBodyMedium)
                        .foregroundStyle(.appTextSecondary)
                        .multilineTextAlignment(.center)
                }

                if isExtracting && !progress.isComplete {
                    Text("\(progress.completedFiles)/\(progress.totalFiles)  \(progress.currentFileName)")
                        .font(.appLabelSmall)
                        .foregroundStyle(.appTextSecondary)
                }

                if let error = progress.error {
                    Text(error)
                        .font(.appBodySmall)
                        .foregroundStyle(.appError)
                }

                if !progress.isComplete {
                    Button {
                        startExtraction()
                    } label: {
                        Text(isExtracting ? "解压中…" : "开始")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 15)
                            .background(Color.appAccent)
                            .foregroundStyle(.white)
                            .clipShape(Capsule())
                    }
                    .disabled(isExtracting)
                }
            }
            .padding(48)
            .opacity(appeared ? 1 : 0)
            .offset(y: appeared ? 0 : 12)
            .animation(.easeOut(duration: 0.6).delay(0.15), value: appeared)
        }
        .onAppear { appeared = true }
        .onChange(of: progress.isComplete) {
            if progress.isComplete {
                Task {
                    try? await Task.sleep(for: .milliseconds(500))
                    onComplete()
                }
            }
        }
    }

    private func startExtraction() {
        isExtracting = true
        Task {
            do {
                try await modelRepo.extractBundledModels { newProgress in
                    Task { @MainActor in
                        progress = newProgress
                    }
                }
            } catch {
                await MainActor.run {
                    progress.error = "模型提取失败: \(error.localizedDescription)"
                }
            }
        }
    }
}
