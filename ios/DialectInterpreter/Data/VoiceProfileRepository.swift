import Foundation

/// Manages voice profiles (reference audio for voice cloning).
/// Voice profiles are stored as WAV files in the app's Documents directory.
final class VoiceProfileRepository {
    private static let tag = "VoiceProfileRepo"
    private static let profilesDir = "voice_profiles"
    private static let sampleRate = 16000

    struct VoiceProfile: Identifiable {
        let id: String
        let name: String
        let createdAt: TimeInterval
        let durationMs: Int64
        let audioPath: String
    }

    private let profilesDir: URL

    init() {
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        profilesDir = documentsDir.appendingPathComponent(Self.profilesDir)
        try? FileManager.default.createDirectory(at: profilesDir, withIntermediateDirectories: true)
    }

    /// Save a voice profile from PCM float data.
    func saveProfile(name: String, audioData: [Float]) -> VoiceProfile {
        let id = "profile_\(Int(Date().timeIntervalSince1970 * 1000))"
        let audioFile = profilesDir.appendingPathComponent("\(id).wav")

        writeWavFile(url: audioFile, audioData: audioData, sampleRate: Self.sampleRate)

        let profile = VoiceProfile(
            id: id,
            name: name,
            createdAt: Date().timeIntervalSince1970,
            durationMs: Int64(audioData.count) * 1000 / Int64(Self.sampleRate),
            audioPath: audioFile.path
        )

        // Save metadata
        let metaFile = profilesDir.appendingPathComponent("\(id).meta")
        writeMetaFile(url: metaFile, profile: profile)

        print("[VoiceProfileRepo] Profile saved: \(name) (\(profile.durationMs)ms)")
        return profile
    }

    /// Get all saved voice profiles.
    func getProfiles() -> [VoiceProfile] {
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: profilesDir, includingPropertiesForKeys: nil) else {
            return []
        }

        return files
            .filter { $0.pathExtension == "wav" }
            .compactMap { wavFile -> VoiceProfile? in
                let id = wavFile.deletingPathExtension().lastPathComponent
                let metaFile = profilesDir.appendingPathComponent("\(id).meta")
                guard fm.fileExists(atPath: metaFile.path) else { return nil }
                return parseMetaFile(id: id, wavFile: wavFile, metaFile: metaFile)
            }
            .sorted { $0.createdAt > $1.createdAt }
    }

    /// Load audio data from a voice profile.
    func loadProfileAudio(profile: VoiceProfile) -> [Float] {
        let url = URL(fileURLWithPath: profile.audioPath)
        return readWavFile(url: url)
    }

    /// Delete a voice profile.
    func deleteProfile(id: String) {
        let fm = FileManager.default
        try? fm.removeItem(at: profilesDir.appendingPathComponent("\(id).wav"))
        try? fm.removeItem(at: profilesDir.appendingPathComponent("\(id).meta"))
        print("[VoiceProfileRepo] Profile deleted: \(id)")
    }

    // MARK: - Metadata

    private func writeMetaFile(url: URL, profile: VoiceProfile) {
        let payload: [String: Any] = [
            "version": 1,
            "id": profile.id,
            "name": profile.name,
            "createdAt": profile.createdAt,
            "durationMs": profile.durationMs,
        ]

        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return }

        let temp = url.deletingLastPathComponent().appendingPathComponent(url.lastPathComponent + ".tmp")
        try? data.write(to: temp)
        try? FileManager.default.removeItem(at: url)
        try? FileManager.default.moveItem(at: temp, to: url)
    }

    private func parseMetaFile(id: String, wavFile: URL, metaFile: URL) -> VoiceProfile? {
        guard let data = try? Data(contentsOf: metaFile),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }

        return VoiceProfile(
            id: id,
            name: json["name"] as? String ?? "Unknown",
            createdAt: json["createdAt"] as? TimeInterval ?? 0,
            durationMs: (json["durationMs"] as? Int64) ?? (json["durationMs"] as? Int).map(Int64.init) ?? 0,
            audioPath: wavFile.path
        )
    }

    // MARK: - WAV I/O

    private func writeWavFile(url: URL, audioData: [Float], sampleRate: Int) {
        let numSamples = audioData.count
        let bitsPerSample = 16
        let numChannels = 1
        let byteRate = sampleRate * numChannels * bitsPerSample / 8
        let blockAlign = numChannels * bitsPerSample / 8
        let dataSize = numSamples * blockAlign

        var buffer = Data(capacity: 44 + dataSize)

        // RIFF header
        buffer.append(contentsOf: "RIFF".utf8)
        buffer.appendLittleEndian(UInt32(36 + dataSize))
        buffer.append(contentsOf: "WAVE".utf8)

        // fmt chunk
        buffer.append(contentsOf: "fmt ".utf8)
        buffer.appendLittleEndian(UInt32(16))
        buffer.appendLittleEndian(UInt16(1)) // PCM
        buffer.appendLittleEndian(UInt16(numChannels))
        buffer.appendLittleEndian(UInt32(sampleRate))
        buffer.appendLittleEndian(UInt32(byteRate))
        buffer.appendLittleEndian(UInt16(blockAlign))
        buffer.appendLittleEndian(UInt16(bitsPerSample))

        // data chunk
        buffer.append(contentsOf: "data".utf8)
        buffer.appendLittleEndian(UInt32(dataSize))

        for sample in audioData {
            let clamped = max(-1, min(1, sample))
            let intSample = Int16(clamped * 32767)
            buffer.appendLittleEndian(intSample)
        }

        try? buffer.write(to: url)
    }

    private func readWavFile(url: URL) -> [Float] {
        guard let data = try? Data(contentsOf: url), data.count > 44 else { return [] }

        let numSamples = (data.count - 44) / 2
        var audioData = [Float](repeating: 0, count: numSamples)

        data.withUnsafeBytes { bytes in
            let base = bytes.baseAddress! + 44
            for i in 0..<numSamples {
                let sample = base.advanced(by: i * 2).assumingMemoryBound(to: Int16.self).pointee
                audioData[i] = Float(sample) / 32768.0
            }
        }

        return audioData
    }
}

// MARK: - Data Helpers

private extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var le = value.littleEndian
        append(UnsafeBufferPointer(start: &le, count: 1))
    }
}
