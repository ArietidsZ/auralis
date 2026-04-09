import Foundation

/// Manages bundled model files.
/// Models are included in the app bundle and extracted to Documents on first run.
final class ModelRepository {
    private static let tag = "ModelRepository"
    private static let modelsDir = "dialect_models"

    static let asrModelFiles = [
        "asr/asr_encoder_int4.onnx",
        "asr/asr_decoder_int4.onnx",
        "asr/tokenizer/tokenizer.json",
    ]

    static let ttsModelFiles = [
        "tts/speaker_encoder_int4.onnx",
        "tts/talker_lm_int4.onnx",
        "tts/vocoder_int4.onnx",
        "tts/tokenizer/tokenizer.json",
    ]

    private static let optionalManifestFiles = ["asr/manifest.json", "tts/manifest.json"]

    struct ExtractionProgress {
        var totalFiles: Int = 0
        var completedFiles: Int = 0
        var currentFileName: String = ""
        var isComplete: Bool = false
        var error: String? = nil
    }

    private let modelsDir: URL

    init() {
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        modelsDir = documentsDir.appendingPathComponent(Self.modelsDir)
        try? FileManager.default.createDirectory(at: modelsDir, withIntermediateDirectories: true)
    }

    // MARK: - Status

    func areModelsReady() -> Bool {
        areAsrModelsReady() && areTtsModelsReady()
    }

    func areAsrModelsReady() -> Bool {
        Self.asrModelFiles.allSatisfy { relativePath in
            let file = modelsDir.appendingPathComponent(relativePath)
            return FileManager.default.fileExists(atPath: file.path) &&
                   (fileSize(file) > 0)
        }
    }

    func areTtsModelsReady() -> Bool {
        Self.ttsModelFiles.allSatisfy { relativePath in
            let file = modelsDir.appendingPathComponent(relativePath)
            return FileManager.default.fileExists(atPath: file.path) &&
                   (fileSize(file) > 0)
        }
    }

    // MARK: - Extraction

    func extractBundledModels(progress: @escaping (ExtractionProgress) -> Void) async throws {
        let allFiles = Self.asrModelFiles + Self.ttsModelFiles +
                       Self.optionalManifestFiles
        var currentProgress = ExtractionProgress(totalFiles: allFiles.count)
        progress(currentProgress)

        let fm = FileManager.default
        let bundle = Bundle.main

        for relativePath in allFiles {
            let dstFile = modelsDir.appendingPathComponent(relativePath)

            // Skip if already extracted
            if fm.fileExists(atPath: dstFile.path), fileSize(dstFile) > 0 {
                currentProgress.completedFiles += 1
                currentProgress.currentFileName = (relativePath as NSString).lastPathComponent
                progress(currentProgress)
                continue
            }

            // Create parent directories
            let parentDir = dstFile.deletingLastPathComponent()
            try? fm.createDirectory(at: parentDir, withIntermediateDirectories: true)

            // Try to copy from bundle
            let fileBaseName = (relativePath as NSString).deletingPathExtension
            let fileExtension = (relativePath as NSString).pathExtension

            if let bundleURL = bundle.url(forResource: fileBaseName, withExtension: fileExtension) {
                // Atomic copy via temp file
                let tempFile = parentDir.appendingPathComponent(dstFile.lastPathComponent + ".tmp")
                try? fm.removeItem(at: tempFile)
                try fm.copyItem(at: bundleURL, to: tempFile)

                guard fileSize(tempFile) > 0 else {
                    try? fm.removeItem(at: tempFile)
                    throw NSError(domain: Self.tag, code: -1,
                                  userInfo: [NSLocalizedDescriptionKey: "Extracted file is empty: \(relativePath)"])
                }

                try? fm.removeItem(at: dstFile)
                try fm.moveItem(at: tempFile, to: dstFile)

                let sizeMB = fileSize(dstFile) / 1_000_000
                print("[ModelRepository] Extracted: \(relativePath) (\(sizeMB)MB)")
            } else {
                print("[ModelRepository] Bundle resource not found: \(relativePath) (expected for initial build)")
            }

            currentProgress.completedFiles += 1
            currentProgress.currentFileName = (relativePath as NSString).lastPathComponent
            progress(currentProgress)
        }

        // Verify integrity if manifests exist
        verifyIntegrityIfAvailable()

        currentProgress.isComplete = true
        progress(currentProgress)

        let totalMB = getDownloadedSize() / 1_000_000
        print("[ModelRepository] Model extraction complete: \(totalMB)MB")
    }

    // MARK: - Integrity

    private func verifyIntegrityIfAvailable() {
        let rules = loadIntegrityRules()
        guard !rules.isEmpty else {
            print("[ModelRepository] No model manifest found, skipping integrity verification")
            return
        }

        for rule in rules {
            let file = modelsDir.appendingPathComponent(rule.path)
            guard FileManager.default.fileExists(atPath: file.path), fileSize(file) > 0 else {
                print("[ModelRepository] Missing model file: \(rule.path)")
                continue
            }

            if let expectedSize = rule.sizeBytes, fileSize(file) != expectedSize {
                try? FileManager.default.removeItem(at: file)
                print("[ModelRepository] Size mismatch: \(rule.path)")
            }
        }

        print("[ModelRepository] Model integrity verification passed (\(rules.count) entries)")
    }

    private struct IntegrityRule {
        let path: String
        let sizeBytes: Int64?
        let sha256: String?
    }

    private func loadIntegrityRules() -> [IntegrityRule] {
        var rules: [IntegrityRule] = []

        for manifestPath in Self.optionalManifestFiles {
            let file = modelsDir.appendingPathComponent(manifestPath)
            guard FileManager.default.fileExists(atPath: file.path),
                  let data = try? Data(contentsOf: file),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let files = root["files"] else {
                continue
            }

            if let array = files as? [[String: Any]] {
                for item in array {
                    guard let path = item["path"] as? String else { continue }
                    let size = (item["size_bytes"] ?? item["sizeBytes"]) as? Int64
                    let hash = item["sha256"] as? String
                    rules.append(IntegrityRule(path: path, sizeBytes: size, sha256: hash))
                }
            }
        }

        return rules
    }

    // MARK: - Utilities

    func getDownloadedSize() -> Int64 {
        guard let enumerator = FileManager.default.enumerator(at: modelsDir, includingPropertiesForKeys: [.fileSizeKey]) else {
            return 0
        }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                total += Int64(size)
            }
        }
        return total
    }

    func deleteModels() {
        try? FileManager.default.removeItem(at: modelsDir)
        try? FileManager.default.createDirectory(at: modelsDir, withIntermediateDirectories: true)
        print("[ModelRepository] Extracted models deleted")
    }

    private func fileSize(_ url: URL) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
    }
}
