import Foundation

protocol TranslationEngine {
    func translate(text: String, sourceLanguage: String, targetLanguage: String) async throws -> String
    var isAvailable: Bool { get async }
}

enum TranslationError: Error, LocalizedError {
    case runtimeUnavailable(String)
    case modelUnavailable(String)
    case backendMismatch(String)
    case emptyInput
    case generationFailed(String)

    var errorDescription: String? {
        switch self {
        case .runtimeUnavailable(let m): return "MT runtime unavailable: \(m)"
        case .modelUnavailable(let m): return "MT model unavailable: \(m)"
        case .backendMismatch(let m): return "MT backend mismatch: \(m)"
        case .emptyInput: return "nothing to translate (empty utterance)"
        case .generationFailed(let m): return "MT generation failed: \(m)"
        }
    }
}

/// Hy-MT engine. Package path is re-resolved under a shared lease on every
/// load so a later install is visible. Cancel is sticky on the native handle
/// until `unload()`; recovery is unload + load. `onCancel` uses OwnedHandle
/// so it cannot call into a niled pointer.
actor HyMtTranslationEngine: TranslationEngine {
    struct Diagnostics: Equatable {
        var revisionPinned: Bool = false
        var nativeRuntimeLinked: Bool = false
        var bundlePresent: Bool = false
        var statusDescription: String
    }

    private(set) var diagnostics: Diagnostics
    private let modelsRoot: URL?
    private let manifestOverride: PackageVerifier.Manifest?
    private var owned: HyMtNative.OwnedHandle?
    private var lease: ModelPackageLease?

    init(modelsRoot: URL? = nil, manifest: PackageVerifier.Manifest? = nil) {
        #if canImport(OnnxRuntimeBindings)
        self.modelsRoot = modelsRoot ?? OnnxModelManager.modelsDir
        #else
        self.modelsRoot = modelsRoot
        #endif
        self.manifestOverride = manifest
        let linked = HyMtNative.runtimeRevision()
        diagnostics = Diagnostics(
            revisionPinned: linked == HyMtNative.pinnedRevision,
            nativeRuntimeLinked: !linked.isEmpty,
            bundlePresent: false,
            statusDescription: "hymt_core native runtime (pinned llama.cpp \(HyMtNative.pinnedRevision))"
        )
    }

    deinit {
        owned?.release()
        owned = nil
        lease?.release()
        lease = nil
    }

    func unload() {
        owned?.release()
        owned = nil
        lease?.release()
        lease = nil
    }

    var isAvailableInternal: Bool {
        get async {
            do {
                try loadIfNeeded()
                return owned != nil
            } catch {
                return false
            }
        }
    }

    nonisolated var isAvailable: Bool {
        get async {
            await self.isAvailableInternal
        }
    }

    private func resolveTranslatorURL() throws -> (url: URL, runtimeRevision: String?) {
        guard let root = modelsRoot else {
            throw TranslationError.modelUnavailable("models root is not configured")
        }
        let manifest: PackageVerifier.Manifest
        do {
            manifest = try manifestOverride ?? SharedContracts.loadManifest(packageId: "mt")
        } catch {
            throw TranslationError.modelUnavailable("manifest unavailable: \(error)")
        }
        guard let role = manifest.roles["translator"] else {
            throw TranslationError.modelUnavailable("mt manifest has no translator role")
        }
        let url = root.appendingPathComponent(role)
        let present = FileManager.default.fileExists(atPath: url.path)
        diagnostics.bundlePresent = present
        guard present else {
            throw TranslationError.modelUnavailable("translator missing at \(url.path)")
        }
        return (url, manifest.runtimeRevision)
    }

    private func loadIfNeeded() throws {
        if owned != nil { return }
        let linked = HyMtNative.runtimeRevision()
        diagnostics.nativeRuntimeLinked = !linked.isEmpty
        guard linked == HyMtNative.pinnedRevision else {
            diagnostics.revisionPinned = false
            throw TranslationError.backendMismatch(
                "linked hymt_core reports revision \(linked), expected \(HyMtNative.pinnedRevision)")
        }
        diagnostics.revisionPinned = true
        guard let root = modelsRoot else {
            throw TranslationError.modelUnavailable("models root is not configured")
        }
        let acquired: ModelPackageLease
        do {
            acquired = try ModelPackageLease(modelsDir: root, packageId: "mt")
        } catch {
            throw TranslationError.runtimeUnavailable("mt package lease unavailable: \(error)")
        }
        do {
            let resolved = try resolveTranslatorURL()
            if let revision = resolved.runtimeRevision, !revision.isEmpty, revision != linked {
                throw TranslationError.backendMismatch("manifest runtimeRevision \(revision) != linked \(linked)")
            }
            let result = HyMtNative.load(modelPath: resolved.url.path)
            guard result.status == .ok, let handle = result.handle else {
                throw TranslationError.runtimeUnavailable(result.error)
            }
            owned = handle
            lease = acquired
        } catch {
            acquired.release()
            throw error
        }
    }

    func translate(text: String, sourceLanguage: String, targetLanguage: String) async throws -> String {
        try Task.checkCancellation()
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw TranslationError.emptyInput
        }
        try loadIfNeeded()
        try Task.checkCancellation()
        guard let box = owned else {
            throw TranslationError.runtimeUnavailable("native Hy-MT handle missing after load")
        }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            let result = HyMtNative.translate(
                handle: box,
                text: text,
                sourceLanguage: sourceLanguage,
                targetLanguage: targetLanguage,
                context: [])
            try Task.checkCancellation()
            switch result.status {
            case .ok:
                return result.output ?? ""
            case .aborted:
                throw CancellationError()
            case .invalidArgument:
                throw TranslationError.generationFailed(result.error)
            case .failed:
                throw TranslationError.generationFailed(result.error)
            }
        } onCancel: {
            box.cancel()
        }
    }
}
