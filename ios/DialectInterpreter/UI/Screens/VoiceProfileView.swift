import SwiftUI

struct VoiceProfileView: View {
    @State private var profileRepo = VoiceProfileRepository()
    @AppStorage(VoiceProfileRepository.selectionKey) private var selectedID = ""
    @State private var profiles: [VoiceProfileRepository.VoiceProfile] = []
    @State private var errorMessage: String?
    @State private var showRecordSheet = false
    @State private var isRecording = false
    @State private var isSaving = false
    @State private var profileName = ""
    @State private var recordedAudio: [Float]?
    @State private var recordingStarted: TimeInterval = 0
    @State private var recordingTask: Task<Void, Never>?
    @State private var audioRecorder = AudioRecorder()
    @State private var audioPlayer = AudioPlayer()

    var body: some View {
        List {
            Section {
                Button {
                    selectedID = ""
                } label: {
                    Label("仅使用文字", systemImage: selectedID.isEmpty ? "checkmark.circle.fill" : "circle")
                }
                ForEach(profiles) { profile in
                    HStack(spacing: 12) {
                        Button {
                            Task {
                                do { try await profileRepo.selectProfile(id: profile.id) }
                                catch { errorMessage = error.localizedDescription }
                            }
                        } label: {
                            HStack {
                                Image(systemName: selectedID == profile.id ? "checkmark.circle.fill" : "circle")
                                VStack(alignment: .leading) {
                                    Text(profile.name).font(.appBodyLarge)
                                    Text("\(profile.durationMs / 1000) 秒参考录音")
                                        .font(.appBodySmall).foregroundStyle(Color.appTextSecondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        Spacer()
                        Button {
                            Task {
                                do {
                                    let audio = try await profileRepo.loadProfileAudio(profile: profile)
                                    try await audioPlayer.play(audioData: audio, sampleRate: 16000)
                                } catch is CancellationError { }
                                  catch { errorMessage = error.localizedDescription }
                            }
                        } label: { Image(systemName: "play.circle.fill").font(.title2) }
                        .buttonStyle(.plain)
                        .accessibilityLabel("试听\(profile.name)")
                    }
                }
                .onDelete { indices in
                    let ids = indices.map { profiles[$0].id }
                    Task {
                        do {
                            for id in ids { try await profileRepo.deleteProfile(id: id) }
                            try await refresh()
                        } catch { errorMessage = error.localizedDescription }
                    }
                }
            } footer: {
                Text("选择参考声音后，下次会话会用真实声纹编码器准备语音合成。参考录音保存在本机；未选择时保留文字译文。")
            }
            Section {
                Button("录制新档案", systemImage: "mic.fill") { showRecordSheet = true }
            }
        }
        .navigationTitle("声音档案")
        .task {
            do { try await refresh() }
            catch is CancellationError { }
            catch { errorMessage = error.localizedDescription }
        }
        .onDisappear { cancelRecording(); audioPlayer.release() }
        .sheet(isPresented: $showRecordSheet, onDismiss: cancelRecording) { recordingSheet }
        .alert("操作未完成", isPresented: Binding(get: { errorMessage != nil && !showRecordSheet }, set: { if !$0 { errorMessage = nil } })) {
            Button("好", role: .cancel) { errorMessage = nil }
        } message: { Text(errorMessage ?? "") }
    }

    private var recordingSheet: some View {
        NavigationStack {
            Form {
                if let errorMessage {
                    Text(errorMessage).foregroundStyle(Color.red).accessibilityLabel("操作未完成：\(errorMessage)")
                }
                if let audio = recordedAudio {
                    Text("已录制 \(audio.count / 16000) 秒")
                    TextField("声音名称", text: $profileName)
                    Button(isSaving ? "保存中…" : "保存并选择") {
                        isSaving = true
                        Task {
                            defer { isSaving = false }
                            do {
                                _ = try await profileRepo.saveProfile(name: profileName, audioData: audio, select: true)
                                recordedAudio = nil
                                profileName = ""
                                showRecordSheet = false
                                try await refresh()
                            } catch { errorMessage = error.localizedDescription }
                        }
                    }
                    .disabled(isSaving || profileName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                } else {
                    Text("请清晰朗读约 5 秒。录音将用作语音合成参考。")
                    if isRecording {
                        TimelineView(.periodic(from: .now, by: 0.1)) { _ in
                            ProgressView(value: min((ProcessInfo.processInfo.systemUptime - recordingStarted) / 5, 1))
                        }
                    }
                    Button(isRecording ? "取消录音" : recordingTask != nil ? "正在结束录音…" : "开始录音") {
                        if isRecording { cancelRecording() } else { startRecording() }
                    }
                    .disabled(recordingTask != nil && !isRecording)
                }
            }
            .navigationTitle("参考录音")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { cancelRecording(); showRecordSheet = false }
                        .disabled(isSaving)
                }
            }
        }
        .interactiveDismissDisabled(isSaving)
    }

    private func refresh() async throws {
        let updated = try await profileRepo.getProfiles()
        try Task.checkCancellation()
        profiles = updated
    }

    private func cancelRecording() {
        recordingTask?.cancel()
        // The task owns its cleanup. A new recording cannot start before
        // its defer has stopped the old recorder and cleared this handle.
        audioRecorder.stopRecording()
        isRecording = false
    }

    private func startRecording() {
        guard recordingTask == nil else { return }
        isRecording = true
        recordedAudio = nil
        errorMessage = nil
        audioPlayer.release()
        recordingStarted = ProcessInfo.processInfo.systemUptime
        recordingTask = Task {
            defer { isRecording = false; recordingTask = nil }
            do {
                if !audioRecorder.hasPermission {
                    let granted = await audioRecorder.requestPermission()
                    try Task.checkCancellation()
                    guard granted else {
                        throw VoiceProfileRepository.ProfileError.invalid("请允许麦克风权限后录制")
                    }
                }
                try Task.checkCancellation()
                let audio = try await audioRecorder.recordFixedDuration(durationMs: 5000)
                try Task.checkCancellation()
                recordedAudio = audio
            } catch is CancellationError { }
              catch { errorMessage = error.localizedDescription }
        }
    }
}
