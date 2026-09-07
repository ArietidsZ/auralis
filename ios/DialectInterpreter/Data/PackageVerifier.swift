import Foundation
import CryptoKit
import CoreFoundation

/// Normative-file-location constants for the data layer (Foundation-only, no
/// UI/ORT dependencies). The Inference layer reuses this — the default models
/// directory has exactly one owner.
enum ModelStore {    static let dirName = "dialect_models"

    /// Default on-disk models root: <Documents>/<dirName>. Layout:
    /// <modelsRoot>/<packageId>/<manifest-relative path>.
    static var defaultDir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(dirName, isDirectory: true)
    }
}

/// Strict mirror of the normative manifest validator
/// (`convert/manifest_contract.py::validate_manifest_v2`) plus the single
/// package-readiness implementation shared by `ModelRepository` (install UI)
/// and `OnnxModelManager` (runtime status).
///
/// Decode rules mirrored 1:1 from the Python validator (v2, one field system):
/// unknown top-level/file keys, enum-validated classification/backend/modes,
/// duplicate paths, externalData presence, role→classification=model, the
/// immutable 40-hex `source.revision` rule, and every verified-only gate
/// (non-placeholder version, pinned sizes/hashes, non-empty files/roles/
/// modes/languages/verification). A draft can NEVER evaluate ready.
///
/// Files are verified at <modelsRoot>/<packageId>/<manifest path> with pinned
/// size and chunked SHA-256 (never loading multi-GB files whole). evaluate()
/// additionally rejects paths outside the package (cross-package), traversal
/// (rejected at decode) and on-disk symlink escapes.
enum PackageVerifier {

    // MARK: - State

    enum State: Equatable {
        case ready
        case draft
        case manifestInvalid(String)
        case filesMissing([String])
        case integrityFailed([String])
        case runtimeUnavailable(String)
        case runtimeProbeFailed(String)
    }

    struct FileEntry {
        let path: String
        let sizeBytes: Int?
        let sha256: String?
        let classification: String
        let externalData: [String]?

        var isModel: Bool { classification == "model" }
    }

    // MARK: - Manifest (strict mirror)

    struct Manifest {
        let schemaVersion: String
        let packageId: String
        let version: String
        let status: String
        let sourceRevision: String?
        let runtimeBackend: String
        let runtimeRevision: String?
        var apiContractVersion: String = "1"
        var buildRecipeId: String? = nil
        var buildProvenancePath: String? = nil
        let modes: [String]
        let files: [FileEntry]
        let roles: [String: String]

        var isDraft: Bool { status == "draft" }
        var isVerified: Bool { status == "verified" }
    }

    enum ManifestError: Error, LocalizedError {
        case invalid(String)

        var errorDescription: String? { "manifest invalid: \(self)" }

        static func fail(_ msg: String) throws -> Never { throw ManifestError.invalid(msg) }
    }

    // Normative constants (convert/manifest_contract.py).
    private static let packageIds: Set<String> = ["asr", "mt", "tts"]
    private static let statuses: Set<String> = ["draft", "verified"]
    private static let backends: Set<String> = ["onnx", "gguf-llama-cpp"]
    private static let classifications: Set<String> = ["model", "supportAsset"]
    private static let modes: Set<String> = ["transcribe", "translate", "synthesize", "clone"]
    private static let verificationStates: Set<String> = ["unverified", "bench-verified", "device-verified"]
    private static let platforms: Set<String> = ["android", "ios"]
    private static let topLevelKeys: Set<String> = [
        "schemaVersion", "packageId", "version", "status", "source", "runtime",
        "capabilities", "files", "roles",
    ]
    private static let fileKeys: Set<String> = ["path", "sizeBytes", "sha256", "classification", "externalData"]

    /// The record is data, never an executable install instruction.
    static func verifyBuildRecord(_ manifest: Manifest, packageDir: URL) throws {
        guard let path = manifest.buildProvenancePath else { return }
        let relative = String(path.dropFirst(manifest.packageId.count + 1))
        let url = packageDir.appendingPathComponent(relative)
        guard fileSize(url) <= 1024 * 1024 else { try ManifestError.fail("build record exceeds 1 MiB") }
        try validateBuildProvenance(manifest, data: Data(contentsOf: url))
    }

    static func validateBuildProvenance(_ manifest: Manifest, data: Data) throws {
        guard let recipeId = manifest.buildRecipeId else { return }
        try rejectDuplicateKeys(data)
        guard let record = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            try ManifestError.fail("build record must be an object")
        }
        func requireRecord(_ condition: Bool, _ message: String) throws {
            if !condition { try ManifestError.fail(message) }
        }
        func integer(_ value: Any?) -> Int? {
            guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(),
                  !["d", "f"].contains(String(cString: n.objCType)), n.int64Value > 0 else { return nil }
            return Int(exactly: n.int64Value)
        }
        func validHash(_ value: Any?) -> Bool {
            guard let s = value as? String else { return false }
            return isSha64(s) && s != String(repeating: "0", count: 64)
        }
        try requireRecord(integer(record["schemaVersion"]) == 1 && record["recipeId"] as? String == recipeId,
                          "build schema/recipe differs from manifest")
        let upstream = record["upstream"] as? [String: Any]
        try requireRecord(upstream?["repoId"] as? String == "Qwen/Qwen3-TTS-12Hz-0.6B-Base" &&
                          upstream?["revision"] as? String == manifest.sourceRevision,
                          "build upstream differs from source")
        let source = record["sourceCode"] as? [String: Any]
        try requireRecord(source?["revision"] as? String == "022e286b98fbec7e1e916cb940cdf532cd9f488e",
                          "build source differs from audited recipe")
        guard let recipe = record["recipe"] as? [String: Any], Set(recipe.keys) == Set(["path", "sha256"]),
              let recipePath = recipe["path"] as? String else { try ManifestError.fail("missing recipe identity") }
        try validatePath(recipePath)
        try requireRecord(recipePath.hasSuffix(".py") && validHash(recipe["sha256"]), "invalid recipe identity")
        try requireRecord((record["toolchain"] as? [String: Any])?["onnxruntime"] as? String == "1.24.2",
                          "build ORT must be 1.24.2")
        func entries(_ key: String) throws -> [String: [String: Any]] {
            guard let rows = record[key] as? [[String: Any]], !rows.isEmpty else {
                try ManifestError.fail("build inputs/outputs must be nonempty")
            }
            var result: [String: [String: Any]] = [:]
            for row in rows {
                guard let path = row["path"] as? String else { try ManifestError.fail("missing build file path") }
                try validatePath(path)
                try requireRecord(result[path] == nil, "duplicate build path")
                try requireRecord(integer(row["sizeBytes"]) != nil && validHash(row["sha256"]), "build file needs size/hash")
                result[path] = row
            }
            return result
        }
        _ = try entries("inputs")
        let outputs = try entries("outputs")
        let expected = Dictionary(uniqueKeysWithValues: manifest.files.filter { $0.path != manifest.buildProvenancePath }
            .map { (String($0.path.dropFirst(manifest.packageId.count + 1)), $0) })
        try requireRecord(Set(outputs.keys) == Set(expected.keys), "build outputs differ from manifest files")
        let external = Set(manifest.files.flatMap { $0.externalData ?? [] })
        for (path, row) in outputs {
            guard let target = expected[path] else { try ManifestError.fail("unknown build output") }
            try requireRecord(integer(row["sizeBytes"]) == target.sizeBytes && row["sha256"] as? String == target.sha256,
                              "build output bytes differ from manifest")
            if row["role"] != nil {
                let graph = (row["role"] as? String).flatMap { manifest.roles[$0] }
                let graphEntry = manifest.files.first { $0.path == graph }
                try requireRecord(target.path == graph || (graphEntry?.externalData ?? []).contains(target.path),
                                  "build output role differs from manifest")
            }
            if let flag = row["externalData"] {
                guard let number = flag as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
                    try ManifestError.fail("externalData must be boolean")
                }
            }
            try requireRecord((row["externalData"] as? Bool ?? false) == external.contains(target.path),
                              "build external data differs from manifest")
        }
    }

    // MARK: - Decode

    static func decodeManifest(_ data: Data, packageId: String) throws -> Manifest {
        let obj: Any
        do {
            obj = try JSONSerialization.jsonObject(with: data)
            try rejectDuplicateKeys(data)
        } catch {
            throw ManifestError.invalid("bad JSON: \(error)")
        }
        guard let dict = obj as? [String: Any] else {
            try ManifestError.fail("manifest must be a JSON object")
        }
        try validate(dict, packageId: packageId)
        return manifest(from: dict)
    }

    private static func manifest(from d: [String: Any]) -> Manifest {
        let source = d["source"] as? [String: Any]
        let runtime = d["runtime"] as? [String: Any]
        let caps = d["capabilities"] as? [String: Any]
        let files = (d["files"] as? [[String: Any]] ?? []).map { entry -> FileEntry in
            let ext = entry["externalData"] as? [Any]
            return FileEntry(
                path: entry["path"] as! String,
                sizeBytes: entry["sizeBytes"] as? Int,
                sha256: entry["sha256"] as? String,
                classification: entry["classification"] as? String ?? "model",
                externalData: ext?.compactMap { $0 as? String })
        }
        let rolesDict = (d["roles"] as? [String: Any])?.compactMapValues { $0 as? String } ?? [:]
        return Manifest(
            schemaVersion: d["schemaVersion"] as? String ?? "",
            packageId: d["packageId"] as? String ?? "",
            version: d["version"] as? String ?? "",
            status: d["status"] as? String ?? "",
            sourceRevision: source?["revision"] as? String,
            runtimeBackend: runtime?["backend"] as? String ?? "onnx",
            runtimeRevision: runtime?["runtimeRevision"] as? String,
            apiContractVersion: runtime?["apiContractVersion"] as? String ?? "",
            buildRecipeId: (source?["build"] as? [String: Any])?["recipeId"] as? String,
            buildProvenancePath: (source?["build"] as? [String: Any])?["provenancePath"] as? String,
            modes: caps?["modes"] as? [String] ?? [],
            files: files,
            roles: rolesDict)
    }

    // MARK: - Strict validation (mirror of validate_manifest_v2)

    static func validate(_ d: [String: Any], packageId expectedId: String? = nil) throws {
        let unknownTop = Set(d.keys).subtracting(topLevelKeys)
        if !unknownTop.isEmpty {
            try ManifestError.fail("unknown top-level keys (v2 uses one field system): \(unknownTop.sorted())")
        }

        guard d["schemaVersion"] as? String == "2" else {
            try ManifestError.fail("unsupported schemaVersion \(String(describing: d["schemaVersion"])); expected '2'")
        }
        guard let pkgId = d["packageId"] as? String, packageIds.contains(pkgId) else {
            try ManifestError.fail("packageId must be one of \(packageIds.sorted())")
        }
        if let expectedId, pkgId != expectedId {
            try ManifestError.fail("packageId mismatch: \(pkgId) != \(expectedId)")
        }
        guard let status = d["status"] as? String, statuses.contains(status) else {
            try ManifestError.fail("status must be one of \(statuses.sorted())")
        }
        let isVerified = status == "verified"

        guard let version = d["version"] as? String, !version.isEmpty else {
            try ManifestError.fail("version must be a non-empty string")
        }
        if isVerified && (version == "0.0.0" || version.range(of: "placeholder|todo|tbd", options: .caseInsensitive) != nil) {
            try ManifestError.fail("verified manifest has placeholder version \(version)")
        }

        // source
        guard let source = d["source"] as? [String: Any] else {
            try ManifestError.fail("source must be an object")
        }
        guard Set(source.keys).isSubset(of: Set(["repoId", "revision", "upstreamModelId",
                                                "licenseSource", "archive", "transform", "build"])) else {
            try ManifestError.fail("unknown source fields")
        }
        for key in ["repoId", "upstreamModelId", "licenseSource"] {
            guard (source[key] as? String)?.isEmpty == false else {
                try ManifestError.fail("source.\(key) must be a non-empty string")
            }
        }
        let revisionRaw = source["revision"]
        let revision: String?
        if revisionRaw == nil || revisionRaw is NSNull {
            revision = nil
        } else if let rev = revisionRaw as? String, isCommitSha(rev) {
            revision = rev
        } else {
            try ManifestError.fail("source.revision \(revisionRaw!) is not an immutable full 40-char commit SHA; " +
                "branch names, tags and 'main'/'latest' are moving refs and must not be used")
        }
        if let archive = source["archive"] {
            try validateArchive(archive)
        }
        if isVerified && revision == nil && source["archive"] == nil {
            try ManifestError.fail("verified manifest requires source.revision or pinned archive")
        }

        // runtime
        guard let runtime = d["runtime"] as? [String: Any] else {
            try ManifestError.fail("runtime must be an object")
        }
        guard let backend = runtime["backend"] as? String, backends.contains(backend) else {
            try ManifestError.fail("runtime.backend must be one of \(backends.sorted())")
        }
        guard (runtime["apiContractVersion"] as? String)?.isEmpty == false else {
            try ManifestError.fail("runtime.apiContractVersion must be a non-empty string")
        }
        guard let platformsArr = runtime["targetPlatforms"] as? [[String: Any]], !platformsArr.isEmpty else {
            try ManifestError.fail("runtime.targetPlatforms must be a non-empty array")
        }
        for plat in platformsArr {
            guard let platName = plat["platform"] as? String, platforms.contains(platName) else {
                try ManifestError.fail("targetPlatform entry invalid: \(plat)")
            }
        }
        guard runtime["streaming"] is Bool else {
            try ManifestError.fail("runtime.streaming must be a boolean")
        }
        if isVerified {
            guard let rr = runtime["runtimeRevision"] as? String, !rr.isEmpty else {
                try ManifestError.fail("verified manifest requires runtime.runtimeRevision (the runtime pin — " +
                    "a separate pin from source.revision, which is the model repo commit)")
            }
        }

        // capabilities
        guard let caps = d["capabilities"] as? [String: Any] else {
            try ManifestError.fail("capabilities must be an object")
        }
        guard let modesArr = caps["modes"] as? [String] else {
            try ManifestError.fail("capabilities.modes must be an array")
        }
        for mode in modesArr where !modes.contains(mode) {
            try ManifestError.fail("unknown mode \(mode)")
        }
        if let verification = caps["verification"] as? [String: Any] {
            for state in verification.values {
                guard let s = state as? String, verificationStates.contains(s) else {
                    try ManifestError.fail("verification state must be one of \(verificationStates.sorted()), got \(state)")
                }
            }
        } else if caps["verification"] != nil {
            try ManifestError.fail("capabilities.verification must be an object")
        } else if isVerified {
            try ManifestError.fail("verified manifest requires capabilities.verification states")
        }
        if isVerified {
            guard !modesArr.isEmpty else {
                try ManifestError.fail("verified manifest requires non-empty capabilities.modes")
            }
            guard let langs = caps["languages"] as? [String], !langs.isEmpty else {
                try ManifestError.fail("verified manifest requires non-empty capabilities.languages")
            }
            _ = langs
        }

        // files
        guard let files = d["files"] as? [[String: Any]] else {
            try ManifestError.fail("files must be an array")
        }
        if isVerified && files.isEmpty {
            try ManifestError.fail("verified manifest requires a non-empty files list")
        }
        var seenPaths = Set<String>()
        var entryClass: [String: String] = [:]
        for (index, entry) in files.enumerated() {
            let unknown = Set(entry.keys).subtracting(fileKeys)
            if !unknown.isEmpty {
                try ManifestError.fail("files[\(index)] has unknown keys (v2 uses one field system): \(unknown.sorted())")
            }
            guard let path = entry["path"] as? String else {
                try ManifestError.fail("files[\(index)].path missing")
            }
            try validatePath(path)
            if seenPaths.contains(path) {
                try ManifestError.fail("duplicate file path: \(path)")
            }
            seenPaths.insert(path)

            let size = entry["sizeBytes"]
            if size != nil && !(size is NSNull) {
                guard let s = size as? Int, s > 0 else {
                    try ManifestError.fail("\(path): sizeBytes must be a positive integer or null, got \(String(describing: size))")
                }
                _ = s
            }
            if isVerified && (size == nil || size is NSNull) {
                try ManifestError.fail("\(path): verified manifest requires sizeBytes")
            }

            let sha = entry["sha256"]
            if let shaStr = sha as? String {
                if shaStr.isEmpty {
                    try ManifestError.fail("\(path): sha256 must not be an empty string (use null)")
                }
                if !isSha64(shaStr) {
                    try ManifestError.fail("\(path): sha256 must be 64 lowercase hex chars")
                }
            } else if sha != nil && !(sha is NSNull) {
                try ManifestError.fail("\(path): sha256 must be a string or null")
            }
            if isVerified {
                guard sha is String else {
                    try ManifestError.fail("\(path): verified manifest requires sha256")
                }
                if sha as? String == String(repeating: "0", count: 64) {
                    try ManifestError.fail("\(path): placeholder (all-zero) sha256")
                }
            }

            guard let classification = entry["classification"] as? String, classifications.contains(classification) else {
                try ManifestError.fail("\(path): classification must be one of \(classifications.sorted()), got \(String(describing: entry["classification"]))")
            }
            entryClass[path] = classification

            let ext = entry["externalData"]
            if ext != nil && !(ext is NSNull) && !(ext is [Any]) {
                try ManifestError.fail("\(path): externalData must be an array or null")
            }
        }
        // External-data references are validated AFTER all files are parsed
        // (same two-pass order as the normative validator; .data files may be
        // listed after their owning .onnx entry).
        for entry in files {
            let path = entry["path"] as? String ?? ""
            for extPath in (entry["externalData"] as? [Any])?.compactMap({ $0 as? String }) ?? [] {
                try validatePath(extPath)
                if !seenPaths.contains(extPath) {
                    try ManifestError.fail("\(path): externalData \(extPath) is not present in files")
                }
            }
        }

        // roles
        guard let roles = d["roles"] as? [String: Any] else {
            try ManifestError.fail("roles must be an object")
        }
        for (role, targetAny) in roles {
            guard let target = targetAny as? String else {
                try ManifestError.fail("role targets must be strings")
            }
            try validatePath(target)
            guard seenPaths.contains(target) else {
                try ManifestError.fail("role \(role) references \(target) which is not listed in files")
            }
            if entryClass[target] != "model" {
                try ManifestError.fail("role \(role) must reference a classification=model file, \(target) is \(entryClass[target] ?? "nil")")
            }
        }
        if isVerified && roles.isEmpty {
            try ManifestError.fail("verified manifest requires at least one role")
        }
        if source["build"] != nil {
            guard let build = source["build"] as? [String: Any],
                  Set(build.keys) == Set(["recipeId", "provenancePath"]),
                  build["recipeId"] as? String == "auralis.qwen3-tts.api2.fp32.v1",
                  pkgId == "tts", backend == "onnx", runtime["apiContractVersion"] as? String == "2",
                  source["transform"] == nil, revision != nil else {
                try ManifestError.fail("unsupported build recipe, API, or unpinned upstream revision")
            }
            guard let path = build["provenancePath"] as? String else {
                try ManifestError.fail("build provenance must be a relative path")
            }
            try validatePath(path)
            guard path.hasPrefix(pkgId + "/"), path.hasSuffix(".json"),
                  entryClass[path] == "supportAsset" else {
                try ManifestError.fail("build provenance must be a declared package JSON supportAsset")
            }
        }
        if source["transform"] != nil {
            try validateTransform(source, manifest: d)
        }
    }

    // MARK: - Path rules (mirror of validate_rel_path)

    private static func validateTransform(_ source: [String: Any], manifest: [String: Any]) throws {
        guard let transform = source["transform"] as? [String: Any],
              Set(transform.keys) == Set(["id", "inputPath", "inputSha256"]),
              transform["id"] as? String == "hymt-stq42-to43-v1",
              transform["inputSha256"] as? String == "93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab",
              manifest["packageId"] as? String == "mt", source["archive"] == nil,
              let runtime = manifest["runtime"] as? [String: Any], runtime["backend"] as? String == "gguf-llama-cpp" else {
            try ManifestError.fail("unknown or unaudited source transform")
        }
        guard let inputPath = transform["inputPath"] as? String else {
            try ManifestError.fail("transform.inputPath must be a relative path")
        }
        try validatePath(inputPath)
        guard let files = manifest["files"] as? [[String: Any]], files.count == 1,
              files[0]["sha256"] as? String == "e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971",
              files[0]["sizeBytes"] as? Int == 461860704,
              (manifest["roles"] as? [String: String])?["translator"] == files[0]["path"] as? String,
              runtime["runtimeRevision"] as? String == "1e411d8f5a1e23525fa3265dfb4bd76265465397" else {
            try ManifestError.fail("STQ transform requires its audited output size/hash/role/runtime")
        }
    }

    private static func validateArchive(_ value: Any) throws {
        guard let archive = value as? [String: Any],
              Set(archive.keys) == Set(["url", "sha256", "sizeBytes", "stripPrefix"]) else {
            try ManifestError.fail("archive requires only url/sha256/sizeBytes/stripPrefix")
        }
        guard let rawURL = archive["url"] as? String,
              let url = URLComponents(string: rawURL), url.scheme == "https",
              !(url.host ?? "").isEmpty, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil,
              rawURL.rangeOfCharacter(from: .whitespacesAndNewlines) == nil else {
            try ManifestError.fail("archive.url must be HTTPS without credentials/query/fragment")
        }
        guard let sha = archive["sha256"] as? String, isSha64(sha), sha != String(repeating: "0", count: 64) else {
            try ManifestError.fail("archive.sha256 must be a non-placeholder SHA-256")
        }
        guard let number = archive["sizeBytes"] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue > 0,
              !["f", "d"].contains(String(cString: number.objCType)),
              number.doubleValue == Double(number.int64Value) else {
            try ManifestError.fail("archive.sizeBytes must be positive")
        }
        guard let prefix = archive["stripPrefix"] as? String else {
            try ManifestError.fail("archive.stripPrefix must be a relative path")
        }
        try validatePath(prefix)
    }

    /// JSONSerialization silently keeps one value for repeated object keys.
    /// Scan the already syntax-checked UTF-8 input to reject that ambiguity,
    /// including escaped spellings such as "revision" and "\\u0072evision".
    private static func rejectDuplicateKeys(_ data: Data) throws {
        let bytes = [UInt8](data)
        var objects: [Set<String>] = []
        var i = 0
        while i < bytes.count {
            switch bytes[i] {
            case 123: objects.append([]) // {
            case 125: if !objects.isEmpty { objects.removeLast() }
            case 34:
                let start = i
                i += 1
                while i < bytes.count {
                    if bytes[i] == 92 { i += 2; continue }
                    if bytes[i] == 34 { break }
                    i += 1
                }
                let end = i
                var next = end + 1
                while next < bytes.count && [9, 10, 13, 32].contains(bytes[next]) { next += 1 }
                if next < bytes.count && bytes[next] == 58 && !objects.isEmpty {
                    let key = try JSONDecoder().decode(String.self, from: Data(bytes[start...end]))
                    if !objects[objects.count - 1].insert(key).inserted {
                        try ManifestError.fail("duplicate JSON key: \(key)")
                    }
                }
            default: break
            }
            i += 1
        }
    }

    static func validatePath(_ path: String) throws {
        if path.isEmpty {
            try ManifestError.fail("path must be a non-empty string")
        }
        if path.contains("\0") {
            try ManifestError.fail("path contains a NUL byte")
        }
        if path != path.trimmingCharacters(in: .whitespaces) {
            try ManifestError.fail("path has leading/trailing whitespace: \(path)")
        }
        if path.contains("\\") {
            try ManifestError.fail("path contains backslash (use '/'): \(path)")
        }
        if path.hasPrefix("/") || (path.count > 1 && path[path.index(after: path.startIndex)] == ":") {
            try ManifestError.fail("path is absolute: \(path)")
        }
        for segment in path.split(separator: "/", omittingEmptySubsequences: false) {
            if segment.isEmpty {
                try ManifestError.fail("path has an empty segment: \(path)")
            }
            if segment == "." || segment == ".." {
                try ManifestError.fail("path contains '\(segment)' segment: \(path)")
            }
        }
    }

    private static func isSha64(_ s: String) -> Bool {
        s.count == 64 && isHex(s)
    }

    private static func isHex(_ s: String) -> Bool {
        s.allSatisfy { $0.isHexDigit && ($0.isLetter ? $0.isLowercase : true) }
    }

    static func isCommitSha(_ s: String) -> Bool {
        s.count == 40 && isHex(s)
    }

    // MARK: - Evaluation (the one readiness implementation)

    /// Evaluate one package. `probe` runs the real runtime (session load for
    /// onnx; native-runtime link check for gguf) and must throw when the
    /// runtime cannot run the package. Async so the actor-owned probe can hop.
    static func evaluate(
        packageId: String,
        manifest: Manifest,
        modelsDir: URL,
        probe: ((String) async throws -> Void)? = nil
    ) async -> State {
        let preProbe = evaluateSync(manifest, modelsDir: modelsDir)
        if case .ready = preProbe {
            guard let probe else { return .runtimeUnavailable("runtime probe not wired") }
            do {
                try await probe(packageId)
                return .ready
            } catch PackageRuntimeError.nativeRuntimeMissing {
                return .runtimeUnavailable("native runtime not linked for backend \(manifest.runtimeBackend)")
            } catch {
                return .runtimeProbeFailed("\(error)")
            }
        }
        return preProbe
    }

    /// Synchronous manifest + file-integrity part of `evaluate` (no probe).
    /// Returns `.ready` only when every gate up to the runtime probe passed.
    static func evaluateSync(_ manifest: Manifest, modelsDir: URL) -> State {
        if manifest.isDraft {
            return .draft
        }
        // Cross-package / foreign-layout paths are rejected before any I/O.
        for entry in manifest.files {
            if !entry.path.hasPrefix("\(manifest.packageId)/") {
                return .manifestInvalid("\(entry.path): path is outside package \(manifest.packageId)")
            }
        }
        // File verification: <modelsRoot>/<packageId>/<manifest path>.
        let fm = FileManager.default
        var missing: [String] = []
        var mismatches: [String] = []
        for entry in manifest.files {
            let url = modelsDir.appendingPathComponent(entry.path)
            guard fm.fileExists(atPath: url.path) else {
                missing.append(entry.path)
                continue
            }
            // On-disk symlink escape: a model file must never be a symlink
            // pointing outside the models root.
            let resolved = url.resolvingSymlinksInPath().path
            let root = modelsDir.resolvingSymlinksInPath().path
            if resolved != url.standardizedFileURL.path && !resolved.hasPrefix(root + "/") {
                mismatches.append("\(entry.path): symlink escapes models root")
                continue
            }
            if let size = entry.sizeBytes, fileSize(url) != Int64(size) {
                mismatches.append("\(entry.path): size mismatch")
                continue
            }
            if let sha = entry.sha256, chunkedSha256(url) != sha {
                mismatches.append("\(entry.path): sha256 mismatch")
            }
        }
        if !missing.isEmpty { return .filesMissing(missing) }
        if !mismatches.isEmpty { return .integrityFailed(mismatches) }

        do {
            try verifyBuildRecord(manifest, packageDir: modelsDir.appendingPathComponent(manifest.packageId))
        } catch { return .integrityFailed(["build record invalid: \(error)"]) }
        return .ready  // caller adds the runtime probe on top
    }

    enum PackageRuntimeError: Error, Equatable {
        case nativeRuntimeMissing
    }

    // MARK: - Hashing / helpers

    static func chunkedSha256(_ url: URL, chunkBytes: Int = 1024 * 1024) -> String {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return "" }
        defer { try? handle.close() }
        var digest = SHA256()
        while true {
            let data = handle.readData(ofLength: chunkBytes)
            if data.isEmpty { break }
            digest.update(data: data)
        }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// Hex SHA-256 of an in-memory buffer (tests, small files).
    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func fileSize(_ url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? Int64) ?? 0
    }
}
