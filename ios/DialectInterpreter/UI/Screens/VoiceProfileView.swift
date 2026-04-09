import SwiftUI

/// Voice profile management screen — swipe-to-delete, gradient ring avatar, bottom sheet recording.
struct VoiceProfileView: View {
    @State private var profileRepo = VoiceProfileRepository()
    @State private var profiles: [VoiceProfileRepository.VoiceProfile] = []
    @State private var showRecordSheet = false
    @State private var isRecording = false
    @State private var recordingProgress: Float = 0
    @State private var profileName = ""
    @State private var recordedAudio: [Float]?
    @State private var amplitude: Float = 0

    private let audioRecorder = AudioRecorder()
    private let audioPlayer = AudioPlayer()

    var body: some View {
        ZStack {
            Color.appBg.ignoresSafeArea()

            if profiles.isEmpty {
                emptyState
            } else {
                profileList
            }
        }
        .navigationTitle("声音档案")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.ultraThinMaterial, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showRecordSheet = true } label: {
                    Image(systemName: "mic.badge.plus")
                        .foregroundStyle(.appAccent)
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            // Bottom pinned record button
            Button {
                showRecordSheet = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "mic.fill")
                        .font(.system(size: 15))
                    Text("录制新档案")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(Color.appAccent)
                .foregroundStyle(.white)
                .clipShape(Capsule())
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(.regularMaterial)
        }
        .onAppear { profiles = profileRepo.getProfiles() }
        .onDisappear { audioRecorder.stopRecording(); audioPlayer.release() }
        .sheet(isPresented: $showRecordSheet) {
            recordSheet
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 20) {
            Image(systemName: "waveform.circle")
                .font(.system(size: 64))
                .foregroundStyle(.appTextTertiary.opacity(0.3))
            Text("还没有声音档案")
                .font(.appTitleMedium)
                .foregroundStyle(.appTextSecondary.opacity(0.5))
            Text("录制 3 秒参考音频即可克隆你的声音")
                .font(.appBodySmall)
                .foregroundStyle(.appTextTertiary)
                .multilineTextAlignment(.center)
        }
    }

    // MARK: - Profile List

    private var profileList: some View {
        List {
            ForEach(profiles) { profile in
                HStack(spacing: 12) {
                    // Avatar with ring border
                    ZStack {
                        Circle()
                            .strokeBorder(
                                LinearGradient(
                                    colors: [.appAccent, .appAccent.opacity(0.4)],
                                    startPoint: .topLeading, endPoint: .bottomTrailing
                                ),
                                lineWidth: 2
                            )
                            .frame(width: 48, height: 48)
                        Text(String(profile.name.prefix(1)))
                            .foregroundStyle(.appAccent)
                            .bold()
                            .font(.appTitleMedium)
                    }

                    // Info
                    VStack(alignment: .leading, spacing: 2) {
                        Text(profile.name)
                            .foregroundStyle(.appText)
                            .font(.appBodyLarge)
                            .fontWeight(.medium)
                        Text("\(profile.durationMs / 1000)秒 参考音频")
                            .font(.appBodySmall)
                            .foregroundStyle(.appTextSecondary)
                    }

                    Spacer()

                    // Play button
                    Button {
                        Task {
                            let audio = profileRepo.loadProfileAudio(profile: profile)
                            await audioPlayer.play(audioData: audio, sampleRate: 16000)
                        }
                    } label: {
                        Image(systemName: "play.circle.fill")
                            .font(.system(size: 28))
                            .foregroundStyle(.appAccent)
                    }
                    .buttonStyle(.plain)
                }
                .listRowBackground(Color.appSurface)
            }
            .onDelete { indexSet in
                for index in indexSet {
                    profileRepo.deleteProfile(id: profiles[index].id)
                }
                profiles = profileRepo.getProfiles()
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    // MARK: - Record Sheet

    private var recordSheet: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if recordedAudio == nil {
                    Text("请朗读一段话（至少 3 秒）")
                        .font(.appBodyMedium)
                        .foregroundStyle(.appTextSecondary)
                        .multilineTextAlignment(.center)

                    WaveformView(amplitude: amplitude, isActive: isRecording)
                        .frame(height: 48)

                    if isRecording {
                        ProgressView(value: Double(recordingProgress))
                            .tint(.appRecordingRed)
                        Text("\(Int(recordingProgress * 5))s / 5s")
                            .font(.appLabelSmall)
                            .foregroundStyle(.appRecordingRed)
                    }

                    Button {
                        startRecording()
                    } label: {
                        Text(isRecording ? "录制中…" : "开始录制")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 15)
                            .background(isRecording ? Color.appRecordingRed : .appAccent)
                            .foregroundStyle(.white)
                            .clipShape(Capsule())
                    }
                    .disabled(isRecording)
                } else {
                    TextField("名称（例如：我的声音）", text: $profileName)
                        .textFieldStyle(.roundedBorder)
                        .padding(.horizontal)

                    Button {
                        if !profileName.isEmpty, let audio = recordedAudio {
                            _ = profileRepo.saveProfile(name: profileName, audioData: audio)
                            profiles = profileRepo.getProfiles()
                            showRecordSheet = false
                            recordedAudio = nil
                            profileName = ""
                        }
                    } label: {
                        Text("保存")
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 15)
                            .background(profileName.isEmpty ? Color.appTextTertiary : .appAccent)
                            .foregroundStyle(.white)
                            .clipShape(Capsule())
                    }
                    .disabled(profileName.isEmpty)
                }
            }
            .padding(24)
            .navigationTitle(recordedAudio == nil ? "录制参考音频" : "保存档案")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        if !isRecording {
                            showRecordSheet = false
                            recordedAudio = nil
                            profileName = ""
                        }
                    }
                    .foregroundStyle(.appAccent)
                }
            }
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled(isRecording)
    }

    private func startRecording() {
        Task {
            isRecording = true
            let t0 = CFAbsoluteTimeGetCurrent()

            Task {
                while isRecording {
                    let elapsed = CFAbsoluteTimeGetCurrent() - t0
                    await MainActor.run {
                        recordingProgress = min(Float(elapsed / 5.0), 1.0)
                    }
                    try? await Task.sleep(for: .milliseconds(50))
                }
            }

            do {
                let audio = try await audioRecorder.recordFixedDuration(durationMs: 5000)
                await MainActor.run {
                    recordedAudio = audio
                    isRecording = false
                    recordingProgress = 0
                }
            } catch {
                await MainActor.run {
                    isRecording = false
                    recordingProgress = 0
                }
            }
        }
    }
}
