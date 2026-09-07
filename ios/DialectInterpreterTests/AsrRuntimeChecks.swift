import Foundation

/// Explicit host integration checks over the actual Swift engine + sherpa C
/// API. Invoked by scripts/check_asr_swift; never runs during light unit tests.
enum AsrRuntimeChecks {
    struct Failure: Error { let message: String }

    static func run(modelsRoot: URL, pcmURL: URL, reference: String) async throws -> [String: Any] {
        let data = try Data(contentsOf: pcmURL)
        guard !data.isEmpty, data.count % 4 == 0 else { throw Failure(message: "invalid PCM test input") }
        let audio = data.withUnsafeBytes { buffer in
            (0..<(data.count / 4)).map { buffer.loadUnaligned(fromByteOffset: $0 * 4, as: Float.self) }
        }
        var passed: [String] = []
        func check(_ condition: Bool, _ name: String) throws {
            guard condition else { throw Failure(message: name) }
            passed.append(name)
        }
        let engine = AsrEngine(modelsDir: modelsRoot)
        do {
            try await engine.load()
            do {
                let writer = try ModelPackageLease(modelsDir: modelsRoot, packageId: "asr", exclusive: true)
                writer.release()
                throw Failure(message: "writer acquired lease during live ASR session")
            } catch is Failure { throw Failure(message: "writer acquired lease during live ASR session") }
              catch { passed.append("loaded ASR excludes package replacement") }
            let auto = try await engine.transcribe(audioData: audio)
            try check(auto.text == reference, "automatic German transcript matches official reference")
            try check(auto.language == "unknown", "upstream missing language stays unknown")
            try check(auto.confidence == nil, "no fabricated confidence")
            let forced = try await engine.transcribe(audioData: audio, language: "de")
            let canonical = try await engine.transcribe(audioData: audio, language: "German")
            try check(!forced.text.isEmpty && forced.text == canonical.text,
                      "language code maps to the canonical Qwen protocol")
            try check(forced.language == "unknown", "language hint is not presented as detected language")
            do {
                _ = try await engine.transcribe(audioData: [Float.nan])
                throw Failure(message: "non-finite PCM was accepted")
            } catch OrtInferenceFailure.badInput { passed.append("non-finite PCM rejected") }
            await engine.release()
            await engine.release()
            let writer = try ModelPackageLease(modelsDir: modelsRoot, packageId: "asr", exclusive: true)
            writer.release()
            passed.append("double release is safe and permits replacement")

            let cancelledEngine = AsrEngine(modelsDir: modelsRoot)
            let task = Task {
                while !Task.isCancelled { await Task.yield() }
                try await cancelledEngine.load()
            }
            task.cancel()
            do {
                try await task.value
                throw Failure(message: "cancelled load succeeded")
            } catch is CancellationError { passed.append("already-cancelled load does not acquire a model") }
            await cancelledEngine.release()
            return ["status": "pass", "checks": passed, "transcript": auto.text, "forcedTranscript": forced.text,
                    "language": auto.language, "durationMs": auto.durationMs,
                    "scope": "macOS host execution of the real Swift ASR engine; not iOS device verification"]
        } catch {
            await engine.release()
            throw error
        }
    }
}
