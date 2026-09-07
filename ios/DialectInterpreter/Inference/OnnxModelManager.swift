import Foundation
import Observation
import OnnxRuntimeBindings

/// Owns the real ORT environment and all loaded inference sessions.
///
/// It is an `actor` on purpose (spec 04 P01): mutable inference handles must
/// never be touched from the main actor. `OnnxModelManager` is the thin
/// @MainActor observable facade the UI watches; it never touches sessions.
actor ModelSessionStore {
    private var env: ORTEnv?
    private var sessions: [String: OrtInferenceSession] = [:]
    private var packageLeases: [String: ModelPackageLease] = [:]

    /// Resolved models root: production default `<Documents>/dialect_models`,
    /// or an injected path (host verification mirrors TranslationEngine's
    /// modelsRoot injection). Immutable after init; safe to read nonisolated.
    nonisolated let modelsRoot: URL
    /// Intra-op thread count for sessions created by this store (TTS graphs;
    /// ASR/MT use their own runtimes). nil keeps the ORT default.
    nonisolated let intraOpThreads: Int?

    init(modelsRoot overrideDir: URL? = nil, intraOpThreads: Int? = nil) {
        self.modelsRoot = overrideDir ?? ModelStore.defaultDir
        self.intraOpThreads = intraOpThreads
    }

    // MARK: - Environment

    private func environment() throws -> ORTEnv {
        if let env { return env }
        do {
            let created = try ORTEnv(loggingLevel: ORTLoggingLevel.warning)
            env = created
            return created
        } catch {
            throw OnnxRuntimeError.environmentInit("\(error)")
        }
    }

    // MARK: - Session loading & execution (real ORT only)

    /// Load (or fetch a cached) session for a manifest role. Sessions never
    /// escape this actor: callers run graphs through `run(role:...)` so the
    /// mutable inference handles are only touched inside the actor.
    private func loadedSession(forRole role: String, inPackage packageId: String) throws -> OrtInferenceSession {
        let key = "\(packageId)/\(role)"
        if let cached = sessions[key] { return cached }

        if packageLeases[packageId] == nil {
            packageLeases[packageId] = try ModelPackageLease(modelsDir: modelsRoot, packageId: packageId)
        }
        defer {
            if !sessions.keys.contains(where: { $0.hasPrefix("\(packageId)/") }) {
                packageLeases.removeValue(forKey: packageId)?.release()
            }
        }

        let manifest = try SharedContracts.loadManifest(packageId: packageId)
        guard let relativePath = manifest.roles[role] else {
            throw OrtInferenceFailure.protocolMismatch(
                "manifest \(packageId).json has no role '\(role)' (available: \(manifest.roles.keys.sorted()))")
        }
        let modelURL = try modelFileURL(packageId: packageId, manifestPath: relativePath)
        let session = try OrtInferenceSession(env: try environment(), modelPath: modelURL.path,
                                              intraOpNumThreads: intraOpThreads)
        sessions[key] = session
        return session
    }

    /// Execute one graph inside the actor. Returns only immutable tensors.
    func run(role: String, packageId: String, inputs: [String: OnnxTensor]) throws -> [String: OnnxTensor] {
        try Task.checkCancellation()
        let session = try loadedSession(forRole: role, inPackage: packageId)
        try Task.checkCancellation()
        let outputs = try session.run(inputs: inputs, outputNames: Set(session.outputNames))
        try Task.checkCancellation()
        return outputs
    }

    /// Probe the actual package backend without retaining inference handles.
    func probeRuntime(packageId: String) throws {
        let manifest = try SharedContracts.loadManifest(packageId: packageId)
        let lease = try ModelPackageLease(modelsDir: modelsRoot, packageId: packageId)
        defer { lease.release() }
        if manifest.runtimeBackend == "gguf-llama-cpp" {
            guard let role = manifest.roles["translator"] else {
                throw OrtInferenceFailure.protocolMismatch("MT manifest missing translator")
            }
            guard manifest.runtimeRevision == HyMtNative.runtimeRevision() else {
                throw OrtInferenceFailure.protocolMismatch("MT runtime revision differs from manifest")
            }
            let path = try modelFileURL(packageId: packageId, manifestPath: role)
            let result = HyMtNative.load(modelPath: path.path)
            defer { result.handle?.release() }
            guard result.status == .ok, result.handle != nil else {
                throw OrtInferenceFailure.protocolMismatch(result.error)
            }
            return
        }
        if packageId == "asr" {
            // ASR is sherpa-onnx Qwen3, not generic ORT sessions. Tokenizer
            // JSON/merges are not ONNX graphs — loading them via OrtSession
            // is a false protocol check.
            try AsrEngine.probe(modelsDir: modelsRoot)
            return
        }
        // Probes must examine installed files, never reuse a session cached
        // for a previous package version or retain a permanent reader lease.
        if packageId == "tts" {
            guard ["1", "2"].contains(manifest.apiContractVersion) else {
                throw OrtInferenceFailure.protocolMismatch("unsupported TTS API contract")
            }
            let expected: Set<String> = manifest.apiContractVersion == "2"
                ? ["speaker_encoder", "talker", "code_predictor", "reference_encoder", "vocoder"]
                : ["speaker_encoder", "talker_prefill", "talker_decode", "code_predictor", "vocoder"]
            guard Set(manifest.roles.keys) == expected else {
                throw OrtInferenceFailure.protocolMismatch("TTS role set differs from its API contract")
            }
        }
        for role in manifest.roles.keys.sorted() {
            guard let path = manifest.roles[role] else { continue }
            let url = try modelFileURL(packageId: packageId, manifestPath: path)
            try autoreleasepool {
                _ = try OrtInferenceSession(env: environment(), modelPath: url.path)
            }
        }
    }

    /// Immutable protocol metadata for a role's graph (safe to hand out).
    func sessionInfo(role: String, packageId: String) throws -> (inputs: [String], outputs: [String]) {
        try Task.checkCancellation()
        let session = try loadedSession(forRole: role, inPackage: packageId)
        return (session.inputNames, session.outputNames)
    }

    /// Drop ONE cached session (e.g. talker_prefill after its single use —
    /// the graph's 1.7 GB of weights are not needed during the decode loop;
    /// releasing it keeps the synthesis peak within device bounds, matching
    /// the Android engine's serial design). A later run() reloads it.
    func release(role: String, packageId: String) {
        sessions.removeValue(forKey: "\(packageId)/\(role)")
    }

    /// Map a manifest path (`<packageId>/...`) to the on-disk models directory.
    /// Layout: <modelsRoot>/<packageId>/... — the manifest path (which already
    /// begins with the package id) appends verbatim; no prefix stripping that
    /// could collide between packages (e.g. asr/ vs tts/ tokenizer files).
    private func modelFileURL(packageId: String, manifestPath: String) throws -> URL {
        let url = modelsRoot.appendingPathComponent(manifestPath)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw OnnxRuntimeError.modelNotFound(url.path)
        }
        return url
    }

    nonisolated static var modelsDir: URL {
        let dir = ModelStore.defaultDir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func modelsDir() -> URL { Self.modelsDir }
    // MARK: - Lifecycle

    func releaseAll() {
        sessions.removeAll()
        packageLeases.values.forEach { $0.release() }
        packageLeases.removeAll()
    }

    func release(packageId: String) {
        let prefix = "\(packageId)/"
        for key in sessions.keys where key.hasPrefix(prefix) {
            sessions.removeValue(forKey: key)
        }
        packageLeases.removeValue(forKey: packageId)?.release()
    }
}

/// Honest per-package status. There is no "ready" without a present,
/// manifest-matching bundle, and no fabricated loaded state.
enum PackageStatus: Equatable {
    case notInstalled
    case manifestUnavailable(String)
    case filesMissing([String])
    case ready
    case runtimeUnavailable(String)
    case error(String)
}

/// Observable UI-facing status. The actual provider is always CPU until a
/// hardware EP is measured to win; there are no CoreML/ANE claims here.
struct ModelStatusSnapshot: Equatable {
    var asr: PackageStatus = .notInstalled
    var mt: PackageStatus = .notInstalled
    var tts: PackageStatus = .notInstalled
    var executionProvider: String = "CPU"
}

@Observable
@MainActor
final class OnnxModelManager {
    /// Shared inference-handle store (actor). Engines receive this instance;
    /// UI code must not use it directly.
    let sessionStore: ModelSessionStore
    /// Resolved models root of this manager's session store (may be injected
    /// for host verification; production equals OnnxModelManager.modelsDir).
    nonisolated let modelsDir: URL

    private(set) var status: ModelStatusSnapshot = ModelStatusSnapshot()

    nonisolated static let modelsDir: URL = ModelSessionStore.modelsDir

    private let repository: ModelRepository

    init(modelsRoot overrideDir: URL? = nil, intraOpThreads: Int? = nil) {
        let store = ModelSessionStore(modelsRoot: overrideDir, intraOpThreads: intraOpThreads)
        self.sessionStore = store
        self.modelsDir = store.modelsRoot
        // The repository must verify the SAME root the probe reads; otherwise
        // refreshStatuses checks the default directory while injected runs
        // probe the override.
        self.repository = ModelRepository(probe: { packageId in
            try await store.probeRuntime(packageId: packageId)
        }, modelsDir: store.modelsRoot)
    }

    /// Re-evaluate package status through the ONE strict verifier (manifest
    /// rules + hash/size/roles + runtime probe). Draft/unknown/empty/unpinned
    /// can never become ready.
    func refreshStatuses() async {
        var snapshot = ModelStatusSnapshot()
        snapshot.asr = mapState(await repository.availability(for: "asr"))
        snapshot.mt = mapState(await repository.availability(for: "mt"))
        snapshot.tts = mapState(await repository.availability(for: "tts"))
        status = snapshot
    }

    private func mapState(_ state: PackageVerifier.State) -> PackageStatus {
        switch state {
        case .ready: return .ready
        case .draft: return .manifestUnavailable("manifest is draft (not verified); install/verify flow required")
        case .manifestInvalid(let m): return .manifestUnavailable(m)
        case .filesMissing(let m): return .filesMissing(m)
        case .integrityFailed(let m): return .error("integrity: \(m)")
        case .runtimeUnavailable(let m): return .runtimeUnavailable(m)
        case .runtimeProbeFailed(let m): return .error("runtime probe failed: \(m)")
        }
    }
}
