import SwiftUI
import UniformTypeIdentifiers

/// File installation and runtime readiness are separate states.
struct ModelDownloadView: View {
    @Environment(OnnxModelManager.self) private var modelManager
    let onComplete: () async -> Void

    @State private var isInstalling = false
    @State private var progress = ModelRepository.ExtractionProgress()
    @State private var showImporter = false
    @State private var installTask: Task<Void, Never>?
    @State private var installId: UUID?

    private var fraction: Double {
        progress.totalFiles > 0 ? Double(progress.completedFiles) / Double(progress.totalFiles) : 0
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "square.stack.3d.up")
                    .font(.system(size: 40))
                    .foregroundStyle(Color.appAccent)
                Text("准备语音模型")
                    .font(.appDisplaySmall)
                Text("选择包含模型包的文件夹。安装后会检查文件和运行组件；识别模型就绪后即可开始使用。")
                    .foregroundStyle(Color.appTextSecondary)
                VStack(alignment: .leading, spacing: 10) {
                    statusRow("语音识别", modelManager.status.asr)
                    statusRow("翻译", modelManager.status.mt)
                    statusRow("语音合成", modelManager.status.tts)
                }
                if isInstalling {
                    ProgressView(value: fraction)
                    Text("已处理 \(progress.completedFiles) / \(progress.totalFiles) 个文件")
                        .font(.appLabelMedium)
                    Text(progress.isComplete ? "正在检查模型…" : "正在安装…")
                        .foregroundStyle(Color.appTextSecondary)
                } else if progress.isComplete {
                    Text("文件已安装。尚未就绪的组件需要完成验证或补齐文件。")
                        .foregroundStyle(Color.appTextSecondary)
                }
                if let error = progress.error {
                    Text(error).foregroundStyle(Color.appError)
                }
                Button("从文件夹安装") { showImporter = true }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(Color.appOnAccent)
                    .disabled(isInstalling)
                Button("安装应用附带的模型") { startInstallation(from: nil) }
                    .disabled(isInstalling)
                Text("文件夹可以包含 asr、mt、tts 中的一个或多个模型包。缺少翻译或合成组件时，已就绪的识别组件仍可用于转写。")
                    .font(.appBodySmall)
                    .foregroundStyle(Color.appTextSecondary)
            }
            .padding(24)
            .frame(maxWidth: 600, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .background(Color.appBg)
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.folder]) { result in
            switch result {
            case .success(let url): startInstallation(from: url)
            case .failure(let error): progress.error = "无法读取所选目录：\(error.localizedDescription)"
            }
        }
        .onDisappear {
            installId = nil
            installTask?.cancel()
            installTask = nil
        }
    }

    private func statusRow(_ title: String, _ status: PackageStatus) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(statusLabel(status))
                .foregroundStyle(status == .ready ? Color.appGreen : Color.appTextSecondary)
        }
    }

    private func statusLabel(_ status: PackageStatus) -> String {
        switch status {
        case .ready: return "就绪"
        case .notInstalled: return "未安装"
        case .filesMissing: return "文件不完整"
        case .manifestUnavailable: return "尚未验证"
        case .runtimeUnavailable: return "运行组件不可用"
        case .error: return "校验失败"
        }
    }

    private func startInstallation(from source: URL?) {
        guard !isInstalling else { return }
        let request = UUID()
        installId = request
        isInstalling = true
        progress = ModelRepository.ExtractionProgress()
        let repository = ModelRepository(modelsDir: modelManager.modelsDir)
        installTask = Task {
            let accessed = source?.startAccessingSecurityScopedResource() ?? false
            defer {
                if accessed { source?.stopAccessingSecurityScopedResource() }
                if installId == request { isInstalling = false }
            }
            let update: (ModelRepository.ExtractionProgress) -> Void = { value in
                Task { @MainActor in
                    guard installId == request, progress.error == nil else { return }
                    progress = value
                }
            }
            do {
                if let source {
                    try await repository.installFromDirectory(source, progress: update)
                } else {
                    try await repository.extractBundledModels(progress: update)
                }
                try Task.checkCancellation()
                await onComplete()
            } catch is CancellationError {
                if installId == request { progress.error = "安装已取消，原有模型保留。" }
            } catch {
                guard installId == request else { return }
                progress.error = "安装未完成：\(error.localizedDescription)"
                await onComplete() // Successfully installed earlier packages remain usable.
            }
        }
    }
}
