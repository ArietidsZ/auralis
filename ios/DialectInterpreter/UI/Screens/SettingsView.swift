import SwiftUI

/// Settings screen — native iOS inset grouped list.
struct SettingsView: View {
    @Environment(OnnxModelManager.self) private var modelManager

    private let modelRepo = ModelRepository()

    private var deviceName: String {
        var systemInfo = utsname()
        uname(&systemInfo)
        return withUnsafePointer(to: &systemInfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) {
                String(cString: $0)
            }
        }
    }

    var body: some View {
        Form {
            Section("推理引擎") {
                LabeledContent {
                    Text(modelManager.status.executionProvider)
                        .foregroundStyle(Color.appTextSecondary)
                } label: {
                    Label("执行提供者", systemImage: "cpu")
                }

                LabeledContent {
                    Text(deviceName)
                        .foregroundStyle(Color.appTextSecondary)
                } label: {
                    Label("设备", systemImage: "iphone")
                }

                LabeledContent {
                    Text("CPU（默认）")
                        .foregroundStyle(Color.appTextSecondary)
                } label: {
                    Label("优化", systemImage: "bolt.fill")
                }
            }

            Section("模型") {
                NavigationLink("管理模型") {
                    ModelDownloadView { await modelManager.refreshStatuses() }
                }
                LabeledContent {
                    Text("\(modelRepo.getDownloadedSize() / 1_000_000) MB")
                        .foregroundStyle(Color.appTextSecondary)
                } label: {
                    Label("磁盘占用", systemImage: "internaldrive")
                }

                LabeledContent {
                    Text(statusText(modelManager.status.asr))
                        .foregroundStyle(modelManager.status.asr == .ready ? Color.appGreen : Color.appTextSecondary)
                } label: {
                    Label("ASR", systemImage: "ear")
                }

                LabeledContent {
                    Text(statusText(modelManager.status.mt))
                        .foregroundStyle(modelManager.status.mt == .ready ? Color.appGreen : Color.appTextSecondary)
                } label: {
                    Label("翻译", systemImage: "character.book.closed")
                }

                LabeledContent {
                    Text(statusText(modelManager.status.tts))
                        .foregroundStyle(modelManager.status.tts == .ready ? Color.appGreen : Color.appTextSecondary)
                } label: {
                    Label("语音合成", systemImage: "waveform.circle")
                }
            }

            Section("关于") {
                LabeledContent {
                    Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—")
                        .foregroundStyle(Color.appTextSecondary)
                } label: {
                    Label("版本", systemImage: "info.circle")
                }

                LabeledContent {
                    Text("Qwen3-ASR-0.6B")
                        .foregroundStyle(Color.appTextSecondary)
                } label: {
                    Label("ASR", systemImage: "chevron.left.forwardslash.chevron.right")
                }

                LabeledContent {
                    Text("Qwen3-TTS-12Hz-0.6B")
                        .foregroundStyle(Color.appTextSecondary)
                } label: {
                    Label("TTS", systemImage: "chevron.left.forwardslash.chevron.right")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
    }

    private func statusText(_ status: PackageStatus) -> String {
        switch status {
        case .ready: return "就绪"
        case .notInstalled: return "未安装"
        case .manifestUnavailable: return "尚未验证"
        case .filesMissing: return "文件不完整"
        case .runtimeUnavailable: return "运行组件不可用"
        case .error: return "校验失败"
        }
    }
}
