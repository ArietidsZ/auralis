import Foundation
@testable import AuralisCore

/// Filesystem/format regressions only; these synthetic samples are not
/// evidence of cloning quality or recognized speech.
func runVoiceProfileChecks() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("voice-check-\(UUID().uuidString)")
    let directory = root.appendingPathComponent("profiles")
    let suite = "voice-check-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer {
        try? FileManager.default.removeItem(at: root)
        defaults.removePersistentDomain(forName: suite)
    }
    let repository = VoiceProfileRepository(directory: directory, defaults: defaults)
    let samples = (0..<48000).map { Float(sin(Double($0) * 0.1)) * 0.25 }
    let saved = try await repository.saveProfile(name: "参考录音", audioData: samples)
    check(UUID(uuidString: saved.id) != nil, "profile ID is a UUID")
    let listed = try await repository.getProfiles()
    check(listed.count == 1 && listed[0].id == saved.id, "saved profile becomes visible with both files")
    try await repository.selectProfile(id: saved.id)
    check(repository.selectedProfileID == saved.id, "voice selection persists")
    let forged = VoiceProfileRepository.VoiceProfile(id: saved.id, name: saved.name,
        createdAt: saved.createdAt, durationMs: saved.durationMs, audioPath: "/not-the-profile.wav")
    let decoded = try await repository.loadProfileAudio(profile: forged)
    check(decoded.count == samples.count, "audio comes from validated ID, not arbitrary audioPath")
    let audioURL = directory.appendingPathComponent("\(saved.id).wav")
    let original = try Data(contentsOf: audioURL)

    var withJunk = Data(original.prefix(12))
    withJunk.append(contentsOf: "JUNK".utf8)
    withJunk.append(contentsOf: [3, 0, 0, 0, 1, 2, 3, 0])
    withJunk.append(original.dropFirst(12))
    let riffSize = UInt32(withJunk.count - 8)
    for i in 0..<4 { withJunk[4 + i] = UInt8(truncatingIfNeeded: riffSize >> (8 * i)) }
    check(try VoiceProfileRepository.decode(withJunk).count == samples.count, "WAV metadata is not decoded as audio")
    expectThrows("truncated WAV") { try VoiceProfileRepository.decode(original.dropLast(2)) }
    var wrongRate = original
    wrongRate[24] = 0x44; wrongRate[25] = 0xac // 44100
    expectThrows("wrong reference sample rate") { try VoiceProfileRepository.decode(wrongRate) }

    do {
        var invalid = samples; invalid[100] = .nan
        _ = try await repository.saveProfile(name: "invalid", audioData: invalid)
        log.record("non-finite reference was saved")
    } catch { }
    do {
        _ = try await repository.saveProfile(name: "silence", audioData: Array(repeating: 0, count: 48000))
        log.record("silent reference was saved")
    } catch { }
    let afterRejected = try await repository.getProfiles()
    check(afterRejected.count == 1, "rejected saves do not leave visible half profiles")

    let outside = root.appendingPathComponent("outside.wav")
    try original.write(to: outside)
    do {
        try await repository.deleteProfile(id: "../outside")
        log.record("path-traversal profile ID accepted")
    } catch { }
    check(FileManager.default.fileExists(atPath: outside.path), "invalid deletion did not touch outside file")
    try FileManager.default.removeItem(at: audioURL)
    try FileManager.default.createSymbolicLink(at: audioURL, withDestinationURL: outside)
    do {
        _ = try await repository.loadReference(id: saved.id)
        log.record("reference symlink was followed")
    } catch { }
    try FileManager.default.removeItem(at: audioURL)
    try original.write(to: audioURL)

    let blocked = root.appendingPathComponent("regular-file")
    try Data([1]).write(to: blocked)
    do {
        _ = try await VoiceProfileRepository(directory: blocked, defaults: defaults)
            .saveProfile(name: "must fail", audioData: samples)
        log.record("filesystem failure returned a saved profile")
    } catch { }
    try await repository.deleteProfile(id: saved.id)
    check(repository.selectedProfileID == nil, "deleting selected profile clears selection")
    let remaining = try await repository.getProfiles()
    check(remaining.isEmpty, "deleted profile does not remain visible")

    // Existing timestamp IDs and metadata without a version stay readable.
    let legacyID = "profile_1700000000000"
    try original.write(to: directory.appendingPathComponent("\(legacyID).wav"))
    let legacy: [String: Any] = ["id": legacyID, "name": "旧录音", "createdAt": 1700000000,
                                "durationMs": 3000]
    try JSONSerialization.data(withJSONObject: legacy).write(to: directory.appendingPathComponent("\(legacyID).meta"))
    let legacyAudio = try await repository.loadReference(id: legacyID)
    check(legacyAudio.count == samples.count, "legacy recordings remain readable")
    try await repository.deleteProfile(id: legacyID)

    // Raw input just above the silence threshold can quantize below it.
    do {
        _ = try await repository.saveProfile(name: "too quiet", audioData: Array(repeating: 0.0001, count: 48000))
        log.record("quantized-unusable reference was saved")
    } catch { }
    let afterQuiet = try await repository.getProfiles()
    check(afterQuiet.isEmpty, "quantization rejection leaves no committed profile")

    // Two view/repository instances must not resurrect a deleted selection.
    let second = VoiceProfileRepository(directory: directory, defaults: defaults)
    for _ in 0..<20 {
        let item = try await repository.saveProfile(name: "race", audioData: samples)
        await withTaskGroup(of: Void.self) { group in
            group.addTask { try? await repository.selectProfile(id: item.id) }
            group.addTask { try? await second.deleteProfile(id: item.id) }
        }
        check(repository.selectedProfileID == nil, "concurrent selection and deletion leave no stale selection")
    }

    let cancelled = Task {
        withUnsafeCurrentTask { $0?.cancel() }
        return try await repository.saveProfile(name: "cancelled", audioData: samples)
    }
    do { _ = try await cancelled.value; log.record("cancelled save committed a profile") }
    catch is CancellationError { }
    let afterCancelled = try await repository.getProfiles()
    check(afterCancelled.isEmpty, "cancelled save has no visible metadata marker")

    let invalidID = "profile_123"
    try FileManager.default.createDirectory(at: directory.appendingPathComponent("\(invalidID).wav"), withIntermediateDirectories: true)
    do { try await repository.deleteProfile(id: invalidID); log.record("profile directory accepted as an audio file") }
    catch { }

}
