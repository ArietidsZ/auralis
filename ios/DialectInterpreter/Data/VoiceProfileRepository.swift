import Foundation

/// App-private reference recordings. A metadata file is the commit marker:
/// readers never expose audio until both files have been written successfully.
final class VoiceProfileRepository {
    static let selectionKey = "auralis.selectedVoiceProfileId"
    static let sampleRate = 16000
    static let minimumSamples = sampleRate * 3
    static let maximumSamples = sampleRate * 30
    // Instances share the same default directory and selection preference.
    // Keep each filesystem transaction synchronous under one lock: selecting
    // cannot commit after another instance has deleted its reference.
    private static let storageLock = NSLock()
    private static let maximumBytes = maximumSamples * 2 + 65536

    struct VoiceProfile: Identifiable {
        let id: String
        let name: String
        let createdAt: TimeInterval
        let durationMs: Int64
        let audioPath: String
    }

    enum ProfileError: Error, LocalizedError {
        case invalid(String)
        var errorDescription: String? {
            if case .invalid(let reason) = self { return reason }
            return nil
        }
    }

    private struct Metadata: Codable {
        let version: Int?
        let id: String
        let name: String
        let createdAt: TimeInterval
        let durationMs: Int64
    }

    private let directory: URL
    private let defaults: UserDefaults

    init(directory: URL? = nil, defaults: UserDefaults = .standard) {
        self.directory = directory ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("voice_profiles", isDirectory: true)
        self.defaults = defaults
    }

    var selectedProfileID: String? {
        guard let id = defaults.string(forKey: Self.selectionKey), Self.validID(id) else { return nil }
        return id
    }

    func selectProfile(id: String?) async throws {
        try Self.storageLock.withLock {
            try Task.checkCancellation()
            guard let id else { defaults.removeObject(forKey: Self.selectionKey); return }
            let audio = try readReference(id: id)
            guard audio.count >= Self.minimumSamples else { throw ProfileError.invalid("参考录音至少需要 3 秒") }
            defaults.set(id, forKey: Self.selectionKey)
        }
    }

    func saveProfile(name: String, audioData: [Float], select: Bool = false) async throws -> VoiceProfile {
        try Self.storageLock.withLock {
            let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, name.count <= 80 else { throw ProfileError.invalid("名称需为 1–80 个字") }
            // Four PCM16 units are the first quantized value above decode's
            // silence threshold; validating only Float input can save a
            // reference that immediately fails to load after quantization.
            guard (Self.minimumSamples...Self.maximumSamples).contains(audioData.count),
                  audioData.allSatisfy(\.isFinite),
                  audioData.contains(where: { abs(max(-1, min(1, $0)) * 32767).rounded(.towardZero) >= 4 }) else {
                throw ProfileError.invalid("参考录音需为 3–30 秒有效语音，不能是静音或损坏数据")
            }
            try prepareDirectory()
            let id = UUID().uuidString.lowercased()
            let audioURL = try file(id, "wav"), metadataURL = try file(id, "meta")
            let metadata = Metadata(version: 2, id: id, name: name, createdAt: Date().timeIntervalSince1970,
                                    durationMs: Int64(audioData.count) * 1000 / Int64(Self.sampleRate))
            do {
                try Task.checkCancellation()
                try writeDurably(Self.encode(audioData), to: audioURL)
                try Task.checkCancellation()
                try writeDurably(JSONEncoder().encode(metadata), to: metadataURL)
                if select { defaults.set(id, forKey: Self.selectionKey) }
                return profile(metadata, audioURL)
            } catch {
                try? FileManager.default.removeItem(at: audioURL)
                try? FileManager.default.removeItem(at: metadataURL)
                throw error
            }
        }
    }

    func getProfiles() async throws -> [VoiceProfile] {
        try Self.storageLock.withLock {
            try prepareDirectory()
            let entries = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            var profiles: [VoiceProfile] = []
            for entry in entries where entry.pathExtension == "meta" {
                try Task.checkCancellation()
                let id = entry.deletingPathExtension().lastPathComponent
                guard Self.validID(id), let metadata = try? readMetadata(id),
                      let audio = try? file(id, "wav"), FileManager.default.fileExists(atPath: audio.path) else { continue }
                profiles.append(profile(metadata, audio))
            }
            return profiles.sorted { $0.createdAt > $1.createdAt }
        }
    }

    func loadProfileAudio(profile: VoiceProfile) async throws -> [Float] {
        try await loadReference(id: profile.id) // Never trust a caller-supplied audioPath.
    }

    func loadReference(id: String) async throws -> [Float] {
        try Self.storageLock.withLock { try readReference(id: id) }
    }

    private func readReference(id: String) throws -> [Float] {
        _ = try readMetadata(id)
        let bytes = try readFile(file(id, "wav"), maximum: Self.maximumBytes)
        try Task.checkCancellation()
        return try Self.decode(bytes)
    }

    func deleteProfile(id: String) async throws {
        try Self.storageLock.withLock {
            let metadata = try file(id, "meta"), audio = try file(id, "wav")
            // Removing the commit marker first prevents a half-deleted recording
            // from being advertised as usable if the second removal fails.
            if FileManager.default.fileExists(atPath: metadata.path) { try FileManager.default.removeItem(at: metadata) }
            if selectedProfileID == id { defaults.removeObject(forKey: Self.selectionKey) }
            if FileManager.default.fileExists(atPath: audio.path) { try FileManager.default.removeItem(at: audio) }
        }
    }

    private func profile(_ value: Metadata, _ audio: URL) -> VoiceProfile {
        VoiceProfile(id: value.id, name: value.name, createdAt: value.createdAt,
                     durationMs: value.durationMs, audioPath: audio.path)
    }

    private func readMetadata(_ id: String) throws -> Metadata {
        let url = try file(id, "meta")
        let value = try JSONDecoder().decode(Metadata.self, from: readFile(url, maximum: 16384))
        guard value.id == id, [1, 2].contains(value.version ?? 1), !value.name.isEmpty,
              value.createdAt.isFinite, value.createdAt >= 0, value.durationMs > 0 else {
            throw ProfileError.invalid("声音档案元数据不匹配")
        }
        return value
    }

    private func prepareDirectory() throws {
        if (try? directory.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true {
            throw ProfileError.invalid("声音档案目录不能是符号链接")
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard try directory.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true else {
            throw ProfileError.invalid("声音档案目录无效")
        }
        var url = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }

    private static func validID(_ id: String) -> Bool {
        id.range(of: "^(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|profile_[0-9]{1,16})$", options: .regularExpression) != nil
    }

    private func file(_ id: String, _ ext: String) throws -> URL {
        guard Self.validID(id) else { throw ProfileError.invalid("声音档案 ID 无效") }
        let url = directory.appendingPathComponent("\(id).\(ext)")
        for item in [directory, url] {
            if (try? item.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true {
                throw ProfileError.invalid("声音档案不能是符号链接")
            }
        }
        if FileManager.default.fileExists(atPath: url.path),
           try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile != true {
            throw ProfileError.invalid("声音档案必须是普通文件")
        }
        return url
    }

    private func readFile(_ url: URL, maximum: Int) throws -> Data {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true, let size = values.fileSize, size > 0, size <= maximum else {
            throw ProfileError.invalid("声音档案文件大小或类型无效")
        }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        // Bound the read too, rather than mapping an externally enlarged file.
        let data = try handle.read(upToCount: maximum + 1) ?? Data()
        guard data.count == size, data.count <= maximum else {
            throw ProfileError.invalid("读取时声音档案发生变化")
        }
        return data
    }

    private func writeDurably(_ data: Data, to url: URL) throws {
        try data.write(to: url, options: .atomic)
        let handle = try FileHandle(forWritingTo: url)
        do { try handle.synchronize(); try handle.close() }
        catch { try? handle.close(); throw error }
    }

    private static func encode(_ samples: [Float]) -> Data {
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) {
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
        }
        data.append(contentsOf: "RIFF".utf8); append(UInt32(36 + samples.count * 2))
        data.append(contentsOf: "WAVEfmt ".utf8); append(UInt32(16))
        append(UInt16(1)); append(UInt16(1)); append(UInt32(sampleRate))
        append(UInt32(sampleRate * 2)); append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: "data".utf8); append(UInt32(samples.count * 2))
        for sample in samples { append(Int16(max(-1, min(1, sample)) * 32767)) }
        return data
    }

    static func decode(_ data: Data) throws -> [Float] {
        guard data.count >= 44, data.count <= maximumBytes else { throw ProfileError.invalid("WAV 文件大小无效") }
        func u16(_ i: Int) -> UInt16 { UInt16(data[i]) | UInt16(data[i + 1]) << 8 }
        func u32(_ i: Int) -> UInt32 { UInt32(u16(i)) | UInt32(u16(i + 2)) << 16 }
        func tag(_ i: Int) -> String { String(decoding: data[i..<(i + 4)], as: UTF8.self) }
        guard tag(0) == "RIFF", tag(8) == "WAVE", Int(u32(4)) + 8 == data.count else {
            throw ProfileError.invalid("WAV 头或长度不匹配")
        }
        var offset = 12, hasFormat = false
        var payload: Range<Int>?
        while offset + 8 <= data.count {
            let count = Int(u32(offset + 4)), start = offset + 8
            guard count <= data.count - start else { throw ProfileError.invalid("WAV 数据截断") }
            switch tag(offset) {
            case "fmt ":
                guard !hasFormat, count >= 16, u16(start) == 1, u16(start + 2) == 1,
                      u32(start + 4) == sampleRate, u32(start + 8) == sampleRate * 2,
                      u16(start + 12) == 2, u16(start + 14) == 16 else {
                    throw ProfileError.invalid("参考录音需要 16kHz 单声道 PCM16 格式")
                }
                hasFormat = true
            case "data":
                guard payload == nil, count > 0, count % 2 == 0 else { throw ProfileError.invalid("WAV 音频块无效") }
                payload = start..<(start + count)
            default: break
            }
            offset = start + count + count % 2
        }
        guard hasFormat, let payload, payload.count / 2 <= maximumSamples, offset == data.count else {
            throw ProfileError.invalid("WAV 缺少格式或音频块")
        }
        let audio = stride(from: payload.lowerBound, to: payload.upperBound, by: 2).map {
            Float(Int16(bitPattern: u16($0))) / 32768
        }
        guard audio.contains(where: { abs($0) >= 1e-4 }) else { throw ProfileError.invalid("参考录音不能是静音") }
        return audio
    }
}
