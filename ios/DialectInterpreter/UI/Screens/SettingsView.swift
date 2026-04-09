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
                    Text(modelManager.selectedProvider == .coreML ? "CoreML (ANE)" : "CPU")
                        .foregroundStyle(.appTextSecondary)
                } label: {
                    Label("执行提供者", systemImage: "cpu")
                }

                LabeledContent {
                    Text(deviceName)
                        .foregroundStyle(.appTextSecondary)
                } label: {
                    Label("设备", systemImage: "iphone")
                }

                LabeledContent {
                    Text("CoreML + GraphOpt")
                        .foregroundStyle(.appTextSecondary)
                } label: {
                    Label("优化", systemImage: "bolt.fill")
                }
            }

            Section("模型") {
                LabeledContent {
                    Text("\(modelRepo.getDownloadedSize() / 1_000_000) MB")
                        .foregroundStyle(.appTextSecondary)
                } label: {
                    Label("磁盘占用", systemImage: "internaldrive")
                }

                LabeledContent {
                    Text(modelRepo.areAsrModelsReady() ? "就绪" : "未解压")
                        .foregroundStyle(modelRepo.areAsrModelsReady() ? .appGreen : .appTextSecondary)
                } label: {
                    Label("ASR", systemImage: "ear")
                }

                LabeledContent {
                    Text(modelRepo.areTtsModelsReady() ? "就绪" : "未解压")
                        .foregroundStyle(modelRepo.areTtsModelsReady() ? .appGreen : .appTextSecondary)
                } label: {
                    Label("TTS", systemImage: "waveform.circle")
                }

                LabeledContent {
                    Text("INT4 Block-wise")
                        .foregroundStyle(.appTextSecondary)
                } label: {
                    Label("量化", systemImage: "arrow.down.right.and.arrow.up.left")
                }
            }

            Section("关于") {
                LabeledContent {
                    Text("1.0.0")
                        .foregroundStyle(.appTextSecondary)
                } label: {
                    Label("版本", systemImage: "info.circle")
                }

                LabeledContent {
                    Text("Qwen3-ASR-0.6B")
                        .foregroundStyle(.appTextSecondary)
                } label: {
                    Label("ASR", systemImage: "chevron.left.forwardslash.chevron.right")
                }

                LabeledContent {
                    Text("Qwen3-TTS-12Hz-0.6B")
                        .foregroundStyle(.appTextSecondary)
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
}
