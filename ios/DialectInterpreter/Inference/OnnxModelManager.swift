import Foundation
import Combine

// MARK: - ONNX Runtime Wrapper Types
// These types abstract over the onnxruntime-objc API.
// When integrating the real onnxruntime-objc pod/SPM package,
// replace these with actual ORT types.

/// Represents the ONNX Runtime environment.
final class OrtEnvironment {
    static let shared = OrtEnvironment()
    private init() {}
}

/// Represents an ONNX tensor value.
final class OrtValue {
    let data: Any
    let shape: [Int]

    init(floatData: [Float], shape: [Int]) {
        self.data = floatData
        self.shape = shape
    }

    init(int64Data: [Int64], shape: [Int]) {
        self.data = int64Data
        self.shape = shape
    }

    func floatArray() -> [Float]? { data as? [Float] }
    func int64Array() -> [Int64]? { data as? [Int64] }
}

/// Represents an ONNX Runtime inference session.
final class OrtSession {
    let modelPath: String
    let inputNames: [String]
    let outputNames: [String]

    init(env: OrtEnvironment, modelPath: String, options: OrtSessionOptions) throws {
        guard FileManager.default.fileExists(atPath: modelPath) else {
            throw OrtError.modelNotFound(modelPath)
        }
        self.modelPath = modelPath
        // In real implementation, these come from the ONNX model metadata
        self.inputNames = []
        self.outputNames = []
        print("[OrtSession] Loaded model: \(modelPath)")
    }

    func run(inputs: [String: OrtValue]) throws -> [OrtValue] {
        // Placeholder — returns empty outputs
        // Real implementation delegates to onnxruntime-objc ORTSession.run()
        print("[OrtSession] Running inference with \(inputs.count) inputs")
        return []
    }

    func close() {
        print("[OrtSession] Session closed: \(modelPath)")
    }
}

/// Session configuration options.
final class OrtSessionOptions {
    enum OptLevel { case allOpt }
    enum ExecutionProvider { case coreML, cpu }

    var optimizationLevel: OptLevel = .allOpt
    var provider: ExecutionProvider = .cpu
    var intraOpNumThreads: Int = 2

    func useCoreML() {
        provider = .coreML
    }
}

/// ORT errors.
enum OrtError: Error {
    case modelNotFound(String)
    case inferenceError(String)
}

// MARK: - ONNX Model Manager

/// Manages ONNX Runtime environment with CoreML EP (Apple Neural Engine).
///
/// Execution Provider priority:
///  1. CoreML EP → delegates to ANE/GPU
///  2. CPU EP (fallback)
@Observable
final class OnnxModelManager {
    static let modelsDir = "dialect_models"
    static let asrDir = "asr"
    static let ttsDir = "tts"

    enum ExecutionProvider: String {
        case coreML = "CoreML"
        case cpu = "CPU"
    }

    enum ModelStatus {
        case notDownloaded, ready, error
    }

    private let env = OrtEnvironment.shared
    private var sessions: [String: OrtSession] = [:]

    private(set) var selectedProvider: ExecutionProvider = .coreML
    private(set) var asrStatus: ModelStatus = .notDownloaded
    private(set) var ttsStatus: ModelStatus = .notDownloaded

    init() {
        detectBestProvider()
        checkModelStatus()
    }

    private func detectBestProvider() {
        // CoreML EP is always available on iOS
        selectedProvider = .coreML
        print("[OnnxModelManager] CoreML EP selected — ANE acceleration enabled")
        print("[OnnxModelManager]   Device: \(deviceName())")
    }

    private func deviceName() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machine = withUnsafePointer(to: &systemInfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) {
                String(cString: $0)
            }
        }
        return machine
    }

    private func checkModelStatus() {
        let modelsDir = getModelsDir()
        let asrDir = modelsDir.appendingPathComponent(Self.asrDir)
        let ttsDir = modelsDir.appendingPathComponent(Self.ttsDir)

        let fm = FileManager.default

        if fm.fileExists(atPath: asrDir.path),
           let files = try? fm.contentsOfDirectory(atPath: asrDir.path),
           files.contains(where: { $0.hasSuffix(".onnx") }) {
            asrStatus = .ready
        }

        if fm.fileExists(atPath: ttsDir.path),
           let files = try? fm.contentsOfDirectory(atPath: ttsDir.path),
           files.contains(where: { $0.hasSuffix(".onnx") }) {
            ttsStatus = .ready
        }
    }

    /// Create optimized session options targeting CoreML EP.
    func createSessionOptions() -> OrtSessionOptions {
        let options = OrtSessionOptions()
        options.optimizationLevel = .allOpt

        switch selectedProvider {
        case .coreML:
            options.useCoreML()
            options.intraOpNumThreads = 2
            print("[OnnxModelManager] CoreML EP configured")
        case .cpu:
            let cores = ProcessInfo.processInfo.processorCount
            options.intraOpNumThreads = min(cores, 4)
            print("[OnnxModelManager] CPU EP: \(cores) cores, intra=\(min(cores, 4))")
        }

        return options
    }

    /// Load an ONNX model session.
    func loadSession(subDir: String, modelFileName: String) throws -> OrtSession {
        let key = "\(subDir)/\(modelFileName)"
        if let existing = sessions[key] {
            return existing
        }

        let modelFile = getModelsDir()
            .appendingPathComponent(subDir)
            .appendingPathComponent(modelFileName)

        guard FileManager.default.fileExists(atPath: modelFile.path) else {
            throw OrtError.modelNotFound(modelFile.path)
        }

        let fileSize = (try? FileManager.default.attributesOfItem(atPath: modelFile.path)[.size] as? Int) ?? 0
        print("[OnnxModelManager] Loading: \(key) (\(fileSize / 1_000_000)MB)")
        let t0 = CFAbsoluteTimeGetCurrent()

        let session = try OrtSession(env: env, modelPath: modelFile.path, options: createSessionOptions())

        let elapsedMs = Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)
        print("[OnnxModelManager] Loaded in \(elapsedMs)ms")

        sessions[key] = session
        return session
    }

    // MARK: - Tensor Creation

    func createTensor(data: [Float], shape: [Int]) -> OrtValue {
        OrtValue(floatData: data, shape: shape)
    }

    func createLongTensor(data: [Int64], shape: [Int]) -> OrtValue {
        OrtValue(int64Data: data, shape: shape)
    }

    // MARK: - Lifecycle

    func getModelsDir() -> URL {
        let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = documentsDir.appendingPathComponent(Self.modelsDir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func getTotalModelSizeBytes() -> Int64 {
        let dir = getModelsDir()
        guard let enumerator = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey]) else {
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

    func release(key: String) {
        sessions[key]?.close()
        sessions.removeValue(forKey: key)
    }

    func releaseAll() {
        sessions.values.forEach { $0.close() }
        sessions.removeAll()
    }
}
