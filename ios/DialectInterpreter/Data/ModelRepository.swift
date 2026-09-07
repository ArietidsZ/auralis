import Foundation
import CryptoKit

/// Manages on-disk model availability and installation (data layer,
/// Foundation-only — no UI/ORT dependencies).
///
/// Readiness is delegated to `PackageVerifier` — the ONE strict implementation
/// shared with `OnnxModelManager` (draft/unknown/empty/unpinned can never be
/// ready; files are hash/size verified; a runtime probe must pass).
///
/// Installation is package-level staged:
///   1. strict manifest decode (same rules as readiness),
///   2. per-package advisory lock (flock) for cross-process mutual exclusion,
///   3. crash recovery (stale staging removed; previous package restored
///      from .trash if a crash left the package dir missing),
///   4. copy every file into staging (paths relative to the package dir)
///      while hashing chunk-wise; pinned size + sha256 verified,
///   5. final cancellation check, then an atomic dir switch with rollback:
///      old dir moved to .trash first and restored if the switch fails —
///      never deleted before the new package is in place.
final class ModelRepository {
    static let packages = ["asr", "mt", "tts"]

    struct ExtractionProgress {
        var totalFiles: Int = 0
        var completedFiles: Int = 0
        var currentFileName: String = ""
        var isComplete: Bool = false
        var error: String? = nil
    }

    enum InstallError: Error, LocalizedError {
        case missingFromSource(String)
        case symlinkEscape(String)
        case insufficientSpace(need: Int64, free: Int64)
        case cancelled
        case verificationFailed([String])
        case manifestProblem(String)
        case locked(String)

        var errorDescription: String? {
            switch self {
            case .missingFromSource(let p): return "missing from source: \(p)"
            case .symlinkEscape(let p): return "refusing to write through symlink: \(p)"
            case .insufficientSpace(let need, let free):
                return "insufficient space: need ~\(need) bytes, \(free) free"
            case .cancelled: return "installation cancelled; previous package kept"
            case .verificationFailed(let issues): return "verification failed: \(issues)"
            case .manifestProblem(let m): return "manifest problem: \(m)"
            case .locked(let p): return "another install holds the lock: \(p)"
            }
        }
    }

    private let modelsDir: URL
    private let probe: ((String) async throws -> Void)?

    init(probe: ((String) async throws -> Void)? = nil, modelsDir overrideDir: URL? = nil) {
        modelsDir = overrideDir ?? ModelStore.defaultDir
        self.probe = probe
    }

    // MARK: - Readiness (unified)

    func availability(for packageId: String) async -> PackageVerifier.State {
        let manifest: PackageVerifier.Manifest
        do {
            let data = try SharedContracts.manifestData(packageId: packageId)
            manifest = try PackageVerifier.decodeManifest(data, packageId: packageId)
        } catch {
            // Decode failures are contract-invalid, never "unavailable".
            return .manifestInvalid("\(error)")
        }
        let lease: ModelPackageLease
        do {
            lease = try ModelPackageLease(modelsDir: modelsDir, packageId: packageId)
        } catch {
            return .runtimeUnavailable("model package is being updated: \(error)")
        }
        defer { lease.release() }
        return await PackageVerifier.evaluate(packageId: packageId, manifest: manifest,
                                              modelsDir: modelsDir, probe: probe)
    }

    /// Ready only when EVERY package satisfies every strict gate.
    func areModelsReady() async -> Bool {
        for packageId in Self.packages {
            if await availability(for: packageId) != .ready {
                return false
            }
        }
        return true
    }

    // MARK: - Staged installation

    /// Import a user-selected models root containing asr/, mt/, and/or tts/.
    /// Copying files never promotes a draft or substitutes for runtime probes.
    func installFromDirectory(_ sourceDir: URL, progress: @escaping (ExtractionProgress) -> Void) async throws {
        let available = Self.packages.filter {
            FileManager.default.fileExists(atPath: sourceDir.appendingPathComponent($0).path)
        }
        guard !available.isEmpty else {
            throw InstallError.missingFromSource("请选择包含 asr、mt 或 tts 子文件夹的模型目录")
        }
        let manifests = try available.map { try SharedContracts.loadManifest(packageId: $0) }
        var current = ExtractionProgress(totalFiles: manifests.reduce(0) { $0 + $1.files.count })
        progress(current)
        for manifest in manifests {
            try Task.checkCancellation()
            try install(packageId: manifest.packageId, manifest: manifest, sourceDir: sourceDir,
                        onFileStaged: {
                            current.completedFiles += 1
                            progress(current)
                        })
        }
        current.isComplete = true
        progress(current)
    }

    /// Install from a directory that mirrors the manifest layout
    /// (`<sourceDir>/<packageId>/<relative>`). Used by the extraction flow and
    /// by tests.
    func installPackage(packageId: String, manifestData: Data, from sourceDir: URL) throws {
        let manifest: PackageVerifier.Manifest
        do {
            manifest = try PackageVerifier.decodeManifest(manifestData, packageId: packageId)
        } catch let error as PackageVerifier.ManifestError {
            throw InstallError.manifestProblem("\(error)")
        }
        try install(packageId: packageId, manifest: manifest, sourceDir: sourceDir)
    }

    /// Copy bundle resources (dev builds) for all packages through the same
    /// staged pipeline.
    func extractBundledModels(progress: @escaping (ExtractionProgress) -> Void) async throws {
        var manifests: [String: PackageVerifier.Manifest] = [:]
        var totalFiles = 0
        for packageId in Self.packages {
            let manifest = try SharedContracts.loadManifest(packageId: packageId)
            manifests[packageId] = manifest
            totalFiles += manifest.files.count
        }

        var currentProgress = ExtractionProgress(totalFiles: totalFiles)
        progress(currentProgress)

        for packageId in Self.packages {
            let manifest = manifests[packageId]!
            var stagedBefore = currentProgress.completedFiles
            try install(packageId: packageId, manifest: manifest,
                              sourceDir: Bundle.main.resourceURL ?? Bundle.main.bundleURL,
                              resolve: { path in
                                  let base = (path as NSString).deletingPathExtension
                                  let ext = (path as NSString).pathExtension
                                  return Bundle.main.url(forResource: base, withExtension: ext)
                              },
                              onFileStaged: {
                                  stagedBefore += 1
                                  currentProgress.completedFiles = stagedBefore
                                  progress(currentProgress)
                              })
        }
        currentProgress.isComplete = true
        progress(currentProgress)
    }

    private func install(
        packageId: String,
        manifest: PackageVerifier.Manifest,
        sourceDir: URL,
        resolve: ((String) -> URL?)? = nil,
        onFileStaged: (() -> Void)? = nil
    ) throws {
        guard !manifest.files.isEmpty else {
            throw InstallError.verificationFailed(["manifest declares no files"])
        }

        try PackageLock.withLock(modelsDir: modelsDir, packageId: packageId) {
            // Crash recovery BEFORE touching anything: stale staging from a
            // previous crash is removed; if the crash lost the package dir,
            // the newest .trash copy is restored so the old package stays
            // usable.
            recoverUnlocked(packageId: packageId)

            let fm = FileManager.default
            let packageRoot = modelsDir.appendingPathComponent(packageId)
            // Reject symlink escapes in the destination tree (below modelsDir).
            try checkNoSymlinkEscape(modelsDir, packageRoot)

            // Space check (best effort): required pinned bytes vs free space.
            let required = manifest.files.reduce(Int64(0)) { acc, entry in
                acc + Int64(entry.sizeBytes ?? 0)
            }
            if required > 0,
               let attrs = try? fm.attributesOfFileSystem(forPath: modelsDir.path),
               let free = (attrs[.systemFreeSize] as? NSNumber)?.int64Value,
               free < required {
                throw InstallError.insufficientSpace(need: required, free: free)
            }

            // Staging mirrors ONLY the package-relative part of manifest paths
            // ("a.bin", not "asr/a.bin"): the staged dir is later moved onto
            // <modelsDir>/<packageId>, so the final layout is exactly
            // <modelsDir>/<manifest path> — no double prefix.
            func relativePath(_ manifestPath: String) throws -> String {
                let prefix = "\(packageId)/"
                guard manifestPath.hasPrefix(prefix) else {
                    throw InstallError.verificationFailed(["\(manifestPath): path outside package \(packageId)"])
                }
                return String(manifestPath.dropFirst(prefix.count))
            }

            let staging = modelsDir.appendingPathComponent(".staging/\(packageId)-\(UUID().uuidString)")
            let trash = modelsDir.appendingPathComponent(".trash/\(packageId)-\(UUID().uuidString)")
            try? fm.createDirectory(at: staging, withIntermediateDirectories: true)
            defer { try? fm.removeItem(at: staging) }

            var issues: [String] = []
            for entry in manifest.files {
                if Task.isCancelled { throw InstallError.cancelled }

                let source: URL
                if let resolve {
                    guard let url = resolve(entry.path) else {
                        throw InstallError.missingFromSource(entry.path)
                    }
                    source = url
                } else {
                    source = sourceDir.appendingPathComponent(entry.path)
                }
                // Reject symlink escapes in the source file.
                let sourceDirRoot = sourceDir.resolvingSymlinksInPath().path
                let resolvedSource = source.resolvingSymlinksInPath().path
                if resolvedSource != source.standardizedFileURL.path && !resolvedSource.hasPrefix(sourceDirRoot + "/") {
                    throw InstallError.symlinkEscape(source.path)
                }

                let stagedFile = staging.appendingPathComponent(try relativePath(entry.path))
                try? fm.createDirectory(at: stagedFile.deletingLastPathComponent(), withIntermediateDirectories: true)

                // Chunked copy + hash; never load a multi-GB file into memory.
                guard let inHandle = try? FileHandle(forReadingFrom: source) else {
                    throw InstallError.missingFromSource(entry.path)
                }
                defer { try? inHandle.close() }
                if !fm.fileExists(atPath: stagedFile.path),
                   !fm.createFile(atPath: stagedFile.path, contents: nil) {
                    throw InstallError.verificationFailed(["cannot create staging file: \(entry.path)"])
                }
                guard let outHandle = try? FileHandle(forWritingTo: stagedFile) else {
                    throw InstallError.verificationFailed(["cannot open staging file: \(entry.path)"])
                }
                var digest = SHA256()
                var written: Int64 = 0
                while true {
                    if Task.isCancelled { try? outHandle.close(); throw InstallError.cancelled }
                    let chunk = inHandle.readData(ofLength: 1024 * 1024)
                    if chunk.isEmpty { break }
                    outHandle.write(chunk)
                    digest.update(data: chunk)
                    written += Int64(chunk.count)
                }
                try? outHandle.close()

                let actualSha = digest.finalize().map { String(format: "%02x", $0) }.joined()
                if let expected = entry.sha256, actualSha != expected {
                    issues.append("\(entry.path): sha256 mismatch")
                    continue
                }
                if let expectedSize = entry.sizeBytes, written != Int64(expectedSize) {
                    issues.append("\(entry.path): size \(written) != manifest \(expectedSize)")
                }
                onFileStaged?()
            }
            if !issues.isEmpty {
                // Old package untouched; staging cleaned by defer.
                throw InstallError.verificationFailed(issues)
            }

            try PackageVerifier.verifyBuildRecord(manifest, packageDir: staging)
            // Final cancellation gate: never switch while a cancel is pending.
            if Task.isCancelled { throw InstallError.cancelled }

            // Atomic package-dir switch with rollback: old dir is MOVED aside
            // (not deleted), staging moves in, and only then the old copy is
            // removed. Restore on failure.
            let hadPrevious = fm.fileExists(atPath: packageRoot.path)
            if hadPrevious {
                try? fm.createDirectory(at: trash.deletingLastPathComponent(), withIntermediateDirectories: true)
                _ = try? fm.moveItem(at: packageRoot, to: trash)
            }
            do {
                try fm.createDirectory(at: packageRoot.deletingLastPathComponent(), withIntermediateDirectories: true)
                try fm.moveItem(at: staging, to: packageRoot)
            } catch {
                if hadPrevious && !fm.fileExists(atPath: packageRoot.path) {
                    _ = try? fm.moveItem(at: trash, to: packageRoot)  // restore previous version
                }
                throw error
            }
            if hadPrevious {
                try? fm.removeItem(at: trash)  // previous version dropped only after the new one is in place
            }
        }
    }

    /// Remove stale staging dirs for this package and restore the previous
    /// package from .trash if a crash left the live package dir missing.
    func recover(packageId: String) throws {
        try PackageLock.withLock(modelsDir: modelsDir, packageId: packageId) {
            recoverUnlocked(packageId: packageId)
        }
    }

    private func recoverUnlocked(packageId: String) {
        let fm = FileManager.default
        let packageRoot = modelsDir.appendingPathComponent(packageId)
        let stagingDir = modelsDir.appendingPathComponent(".staging")
        let trashDir = modelsDir.appendingPathComponent(".trash")

        if let entries = try? fm.contentsOfDirectory(atPath: stagingDir.path) {
            for name in entries where name.hasPrefix("\(packageId)-") {
                try? fm.removeItem(at: stagingDir.appendingPathComponent(name))
            }
        }
        if !fm.fileExists(atPath: packageRoot.path),
           let entries = (try? fm.contentsOfDirectory(atPath: trashDir.path))?.filter({ $0.hasPrefix("\(packageId)-") }).sorted(),
           let newest = entries.last {
            try? fm.createDirectory(at: packageRoot.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? fm.moveItem(at: trashDir.appendingPathComponent(newest), to: packageRoot)
        }
    }

    /// Every path component strictly below `root` must not be a symlink;
    /// system-level ancestor symlinks above the models root are fine (macOS
    /// /var → /private/var, tmpdirs, etc.).
    private func checkNoSymlinkEscape(_ root: URL, _ dir: URL) throws {
        let fm = FileManager.default
        let rootPath = root.resolvingSymlinksInPath().standardizedFileURL.path
        var current = dir.standardizedFileURL
        while current.path != rootPath && current.path != "/" {
            if let target = try? fm.destinationOfSymbolicLink(atPath: current.path) {
                throw InstallError.symlinkEscape("\(current.path) -> \(target)")
            }
            current = current.deletingLastPathComponent()
        }
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

    func deleteModels() throws {
        var leases: [ModelPackageLease] = []
        defer { leases.forEach { $0.release() } }
        for packageId in Self.packages {
            leases.append(try ModelPackageLease(modelsDir: modelsDir, packageId: packageId, exclusive: true))
        }
        // Keep lock files in place: deleting them would let a new writer lock
        // a different inode while these leases are still active.
        for name in Self.packages + [".staging", ".trash"] {
            let path = modelsDir.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: path.path) {
                try FileManager.default.removeItem(at: path)
            }
        }
    }
}

/// Cross-process advisory lock (flock) per package so two installs can never
/// interleave their package-dir switch. In-process recursion is not needed —
/// installs are user-triggered one at a time.
final class ModelPackageLease: @unchecked Sendable {
    private let lock = NSLock()
    private var descriptor: Int32 = -1

    init(modelsDir: URL, packageId: String, exclusive: Bool = false) throws {
        guard ModelRepository.packages.contains(packageId) else {
            throw ModelRepository.InstallError.manifestProblem("unknown package: \(packageId)")
        }
        let dir = modelsDir.appendingPathComponent(".locks")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = dir.appendingPathComponent("\(packageId).lock").path
        let fd = open(path, O_CREAT | O_RDWR, 0o600)
        guard fd >= 0 else { throw ModelRepository.InstallError.locked(path) }
        guard flock(fd, (exclusive ? LOCK_EX : LOCK_SH) | LOCK_NB) == 0 else {
            close(fd)
            throw ModelRepository.InstallError.locked(path)
        }
        descriptor = fd
    }

    func release() {
        lock.lock(); defer { lock.unlock() }
        guard descriptor >= 0 else { return }
        flock(descriptor, LOCK_UN)
        close(descriptor)
        descriptor = -1
    }

    deinit { release() }
}

enum PackageLock {
    static func withLock<T>(modelsDir: URL, packageId: String, _ body: () throws -> T) throws -> T {
        let lease = try ModelPackageLease(modelsDir: modelsDir, packageId: packageId, exclusive: true)
        defer { lease.release() }
        return try body()
    }
}
