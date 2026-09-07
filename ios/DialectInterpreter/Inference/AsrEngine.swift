import Foundation
import AuralisSherpaAsr

/// ASR inference engine — sherpa-onnx Qwen3-ASR-0.6B INT8 (official C API).
///
/// Replaces the handwritten 80-mel / INT4 / greedy-piece path. PCM is passed
/// to sherpa-onnx; feature extraction and the KV-cache decoder live in the
/// maintainer library. Clips longer than 36 s are split so each piece stays
/// under the default KV 512 budget.
actor AsrEngine {
    static let sampleRate = 16000
    private static let maxTotalLen: Int32 = 512
    private static let maxNewTokens: Int32 = 192
    private static let segmentTriggerS = 36.0
    private static let maxSegmentS = 20.0
    private static let packageId = "asr"

    private let modelsDir: URL
    private var handle: OpaquePointer?
    private var packageLease: ModelPackageLease?

    struct TranscriptionResult {
        let text: String
        let language: String
        let confidence: Float?
        let durationMs: Int64
    }

    #if canImport(OnnxRuntimeBindings)
    init(modelManager: OnnxModelManager) {
        self.modelsDir = modelManager.modelsDir
    }
    #endif

    init(modelsDir: URL) { self.modelsDir = modelsDir }

    deinit {
        if let handle { AuralisSherpaAsrDestroy(handle) }
        packageLease?.release()
    }

    func load() async throws {
        try Task.checkCancellation()
        guard handle == nil else { return }
        let lease = try ModelPackageLease(modelsDir: modelsDir, packageId: Self.packageId)
        do {
            let created = try Self.createRecognizer(modelsDir: modelsDir)
            if Task.isCancelled {
                AuralisSherpaAsrDestroy(created)
                throw CancellationError()
            }
            handle = created
            packageLease = lease
        } catch {
            lease.release()
            throw error
        }
    }

    /// The caller holds a package lease while this loads the actual backend.
    static func probe(modelsDir: URL) throws {
        let recognizer = try createRecognizer(modelsDir: modelsDir)
        AuralisSherpaAsrDestroy(recognizer)
    }

    private static func createRecognizer(modelsDir: URL) throws -> OpaquePointer {
        guard AuralisSherpaAvailable() != 0 else {
            throw OrtInferenceFailure.protocolMismatch(
                "sherpa-onnx C API is unavailable")
        }
        let root = modelsDir.appendingPathComponent(Self.packageId, isDirectory: true)
        let conv = root.appendingPathComponent("conv_frontend.onnx")
        let encoder = root.appendingPathComponent("encoder.int8.onnx")
        let decoder = root.appendingPathComponent("decoder.int8.onnx")
        let tokenizer = root.appendingPathComponent("tokenizer", isDirectory: true)
        for url in [conv, encoder, decoder] {
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw OrtInferenceFailure.badInput("ASR model missing: \(url.path)")
            }
        }
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: tokenizer.path, isDirectory: &isDir), isDir.boolValue else {
            throw OrtInferenceFailure.tokenizerUnavailable(tokenizer.path)
        }

        let created = conv.path.withCString { convC in
            encoder.path.withCString { encC in
                decoder.path.withCString { decC in
                    tokenizer.path.withCString { tokC in
                        AuralisSherpaAsrCreate(
                            convC, encC, decC, tokC,
                            2, Self.maxTotalLen, Self.maxNewTokens)
                    }
                }
            }
        }
        guard let created else {
            throw OrtInferenceFailure.protocolMismatch(
                "SherpaOnnxCreateOfflineRecognizer failed for \(root.path)")
        }
        return created
    }

    func transcribe(audioData: [Float], language: String? = nil) async throws -> TranscriptionResult {
        try Task.checkCancellation()
        guard let handle else { throw OrtInferenceFailure.notLoaded("ASR") }
        guard !audioData.isEmpty, audioData.allSatisfy(\.isFinite) else {
            throw OrtInferenceFailure.badInput("ASR requires non-empty finite PCM")
        }
        let t0 = ProcessInfo.processInfo.systemUptime
        let langHint = language.flatMap { Self.normalizeLanguage($0) }
        if let language, !language.isEmpty, language.lowercased() != "auto", langHint == nil {
            throw OrtInferenceFailure.badInput("unsupported ASR language hint: \(language)")
        }
        let pieces = Self.boundSegments(audioData, sampleRate: Self.sampleRate)
        var texts: [String] = []
        var detectedLanguages: Set<String> = []
        for piece in pieces where !piece.isEmpty {
            try Task.checkCancellation()
            let result = try Self.decode(handle: handle, samples: piece, language: langHint)
            try Task.checkCancellation()
            if !result.text.isEmpty {
                texts.append(result.text)
                if let detected = result.language, !detected.isEmpty { detectedLanguages.insert(detected) }
            }
        }
        let joined = texts.joined(separator: " ")
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        let elapsedMs = Int64((ProcessInfo.processInfo.systemUptime - t0) * 1000)
        let detected = detectedLanguages.count == 1 ? detectedLanguages.first : nil
        let lang = detected ?? (detectedLanguages.count > 1 ? "mixed" : "unknown")
        return TranscriptionResult(text: joined, language: lang, confidence: nil, durationMs: elapsedMs)
    }

    func release() async {
        if let handle {
            AuralisSherpaAsrDestroy(handle)
        }
        handle = nil
        packageLease?.release()
        packageLease = nil
    }

    private static func decode(handle: OpaquePointer, samples: [Float], language: String?) throws -> (text: String, language: String?) {
        try samples.withUnsafeBufferPointer { buf in
            guard let base = buf.baseAddress else {
                throw OrtInferenceFailure.badInput("empty audio buffer")
            }
            let textPtr: UnsafeMutablePointer<CChar>?
            var detectedPtr: UnsafeMutablePointer<CChar>?
            defer {
                if let detectedPtr { AuralisSherpaStringFree(detectedPtr) }
            }
            if let language {
                textPtr = language.withCString { langC in
                    AuralisSherpaAsrTranscribe(
                            handle, base, Int32(buf.count), Int32(sampleRate), langC, &detectedPtr)
                }
            } else {
                textPtr = AuralisSherpaAsrTranscribe(
                    handle, base, Int32(buf.count), Int32(sampleRate), nil, &detectedPtr)
            }
            guard let textPtr else {
                throw OrtInferenceFailure.badOutput("sherpa-onnx returned NULL text")
            }
            defer { AuralisSherpaStringFree(textPtr) }
            return (String(cString: textPtr), detectedPtr.map { String(cString: $0) })
        }
    }

    static func boundSegments(_ samples: [Float], sampleRate: Int) -> [[Float]] {
        if samples.isEmpty { return [] }
        let duration = Double(samples.count) / Double(sampleRate)
        if duration <= segmentTriggerS { return [samples] }
        let maxN = max(1, Int(maxSegmentS * Double(sampleRate)))
        let hop = max(1, Int(0.02 * Double(sampleRate)))
        var out: [[Float]] = []
        var pos = 0
        while pos < samples.count {
            let remain = samples.count - pos
            if remain <= maxN {
                out.append(Array(samples[pos..<samples.count]))
                break
            }
            let windowEnd = pos + maxN
            var bestI = windowEnd
            var bestE = Float.greatestFiniteMagnitude
            var i = pos + Int(Double(maxN) * 0.55)
            while i < windowEnd {
                let end = min(samples.count, i + hop)
                var acc: Float = 0
                if end > i {
                    for k in i..<end {
                        let v = samples[k]
                        acc += v * v
                    }
                    acc /= Float(end - i)
                }
                if acc < bestE {
                    bestE = acc
                    bestI = i
                }
                i += hop
            }
            let cut = min(samples.count, max(pos + hop, bestI))
            out.append(Array(samples[pos..<cut]))
            pos = cut
        }
        return out
    }

    /// Qwen's prompt is literally `language <canonical name><asr_text>`.
    /// BCP-47 codes are accepted at our boundary, then mapped to that protocol.
    static func normalizeLanguage(_ language: String) -> String? {
        let names = ["zh": "Chinese", "en": "English", "yue": "Cantonese",
            "ar": "Arabic", "de": "German", "fr": "French", "es": "Spanish",
            "pt": "Portuguese", "id": "Indonesian", "it": "Italian", "ko": "Korean",
            "ru": "Russian", "th": "Thai", "vi": "Vietnamese", "ja": "Japanese",
            "tr": "Turkish", "hi": "Hindi", "ms": "Malay", "nl": "Dutch",
            "sv": "Swedish", "da": "Danish", "fi": "Finnish", "pl": "Polish",
            "cs": "Czech", "fil": "Filipino", "fa": "Persian", "el": "Greek",
            "hu": "Hungarian", "mk": "Macedonian", "ro": "Romanian"]
        let key = language.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return names[key] ?? names.values.first { $0.lowercased() == key }
    }
}

/// Engine-level failures. Distinct from OnnxRuntimeError so call sites can
/// report precise, actionable diagnostics. Kept here because TtsEngine and
/// OnnxModelManager reference this type.
enum OrtInferenceFailure: Error, LocalizedError {
    case notLoaded(String)
    case tokenizerUnavailable(String)
    case badInput(String)
    case badOutput(String)
    case missingOutput(String, expected: [String])
    case protocolMismatch(String)

    var errorDescription: String? {
        switch self {
        case .notLoaded(let what): return "\(what) is not loaded"
        case .tokenizerUnavailable(let path): return "tokenizer unavailable: \(path)"
        case .badInput(let m): return "bad input: \(m)"
        case .badOutput(let m): return "bad output: \(m)"
        case .missingOutput(let what, let expected):
            return "\(what) produced none of the expected outputs; model declared: \(expected)"
        case .protocolMismatch(let m): return "model protocol mismatch: \(m)"
        }
    }
}
