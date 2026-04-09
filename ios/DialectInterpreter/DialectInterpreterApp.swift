import SwiftUI

@main
struct DialectInterpreterApp: App {
    @State private var modelManager = OnnxModelManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(modelManager)
            // Removed .preferredColorScheme(.dark) — adaptive to system appearance
        }
    }
}

/// Root content view handling navigation routing.
struct ContentView: View {
    @Environment(OnnxModelManager.self) private var modelManager
    @State private var modelsReady = false

    var body: some View {
        Group {
            if modelsReady {
                MainNavigationView()
            } else {
                ModelDownloadView {
                    withAnimation(.easeInOut(duration: 0.4)) {
                        modelsReady = true
                    }
                }
            }
        }
        .onAppear {
            let repo = ModelRepository()
            modelsReady = repo.areModelsReady()
        }
    }
}

/// Main navigation container after models are ready.
struct MainNavigationView: View {
    @Environment(OnnxModelManager.self) private var modelManager

    var body: some View {
        NavigationStack {
            InterpretView()
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        HStack(spacing: 4) {
                            NavigationLink {
                                VoiceProfileView()
                            } label: {
                                Image(systemName: "waveform.circle")
                                    .foregroundStyle(.appText.opacity(0.7))
                            }
                            NavigationLink {
                                SettingsView()
                            } label: {
                                Image(systemName: "ellipsis")
                                    .foregroundStyle(.appText.opacity(0.7))
                            }
                        }
                    }
                }
        }
    }
}
