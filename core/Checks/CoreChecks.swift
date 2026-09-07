import Foundation
@testable import AuralisCore

/// Minimal check harness: no Testing framework, no macros, no fakes.
final class CheckLog: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var failures: [String] = []
    func record(_ msg: String) { lock.lock(); failures.append(msg); lock.unlock() }
    func containsFailures() -> Bool { lock.lock(); defer { lock.unlock() }; return !failures.isEmpty }
    func dump() -> [String] { lock.lock(); defer { lock.unlock() }; return failures }
}

let log = CheckLog()

func check(_ cond: Bool, _ msg: @autoclosure () -> String) {
    if !cond { log.record(msg()) }
}

func expectThrows(_ what: String, _ body: () throws -> Any) {
    do {
        _ = try body()
        log.record("\(what): expected throw, got success")
    } catch { /* expected */ }
}

// MARK: - Shared fixture consistency

func runFixtureChecks() throws {
    let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // Checks
        .deletingLastPathComponent()   // core
        .deletingLastPathComponent()   // repo root
    let fixtures = repoRoot.appendingPathComponent("shared/fixtures")
    let buildCases = try JSONSerialization.jsonObject(with: Data(contentsOf:
        repoRoot.appendingPathComponent("shared/build-provenance-fixtures.json"))) as! [[String: Any]]
    check(buildCases.count == 21, "expected 21 build provenance cases")
    for item in buildCases {
        let manifest = try PackageVerifier.decodeManifest(
            JSONSerialization.data(withJSONObject: item["manifest"]!), packageId: "tts")
        let record = try JSONSerialization.data(withJSONObject: item["record"]!)
        let expected = item["valid"] as! Bool
        do {
            try PackageVerifier.validateBuildProvenance(manifest, data: record)
            check(expected, "build provenance accepted invalid case: \(item["name"]!)")
        } catch {
            check(!expected, "build provenance rejected valid case: \(item["name"]!) / \(error)")
        }
    }

    func jsonFiles(in dir: URL) throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    // Valid manifest fixtures decode.
    let valid = try jsonFiles(in: fixtures.appendingPathComponent("valid"))
        .filter { $0.lastPathComponent.hasPrefix("manifest-") }
    check(valid.count >= 3, "expected >=3 valid manifest fixtures")
    for url in valid {
        let data = try Data(contentsOf: url)
        let obj = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let manifest = try PackageVerifier.decodeManifest(data, packageId: obj["packageId"] as! String)
        check(manifest.isVerified == (obj["status"] as? String == "verified"),
              "\(url.lastPathComponent): status mirror broken")
    }

    // The catalog is a different contract; the manifest verifier rejects it.
    let catalog = try Data(contentsOf: fixtures.appendingPathComponent("valid/dialect-catalog-valid.json"))
    expectThrows("catalog accepted as manifest") { try PackageVerifier.decodeManifest(catalog, packageId: "asr") }

    // Drafts evaluate as .draft — never ready.
    for name in ["manifest-asr-draft.json", "manifest-mt-draft-pinned.json"] {
        let data = try Data(contentsOf: fixtures.appendingPathComponent("valid/\(name)"))
        let obj = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let manifest = try PackageVerifier.decodeManifest(data, packageId: obj["packageId"] as! String)
        let state = PackageVerifier.evaluateSync(manifest, modelsDir: URL(fileURLWithPath: "/nonexistent"))
        check(state == .draft, "\(name): draft did not evaluate as .draft, got \(state)")
    }

    // Verified fixture: honest missing-files outcome, never manifestInvalid/ready.
    do {
        let data = try Data(contentsOf: fixtures.appendingPathComponent("valid/manifest-tts-verified.json"))
        let manifest = try PackageVerifier.decodeManifest(data, packageId: "tts")
        let state = PackageVerifier.evaluateSync(manifest, modelsDir: URL(fileURLWithPath: "/nonexistent"))
        switch state {
        case .filesMissing, .runtimeUnavailable, .runtimeProbeFailed:
            break
        default:
            log.record("manifest-tts-verified.json evaluated as \(state)")
        }
    }

    // Every invalid manifest fixture is rejected.
    let invalid = try jsonFiles(in: fixtures.appendingPathComponent("invalid"))
    check(invalid.count >= 20, "expected >=20 invalid fixtures")
    for url in invalid where !url.lastPathComponent.contains("catalog") {
        let data = try Data(contentsOf: url)
        do {
            if !url.lastPathComponent.hasSuffix(".invalidjson.json"),
               var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                // Remove fixture annotations before validating. Do not
                // reserialize: that can change 200.0 into integer 200.
                object.removeValue(forKey: "$expectedError")
                try PackageVerifier.validate(object, packageId: object["packageId"] as? String)
            } else {
                _ = try PackageVerifier.decodeManifest(data, packageId: "asr")
            }
            log.record("invalid fixture accepted: \(url.lastPathComponent)")
        } catch { /* expected */ }
    }

    // Cross-package path rejected by evaluate.
    var obj = try JSONSerialization.jsonObject(
        with: Data(contentsOf: fixtures.appendingPathComponent("valid/manifest-asr-draft.json"))) as! [String: Any]
    obj["status"] = "verified"
    obj["source"] = ["repoId": "t/r", "revision": String(repeating: "a", count: 40),
                     "upstreamModelId": "m", "licenseSource": "l"]
    obj["runtime"] = ["backend": "onnx", "apiContractVersion": "1", "streaming": false,
                      "targetPlatforms": [["platform": "ios"]], "runtimeRevision": "v1.24.2"]
    obj["capabilities"] = ["modes": ["transcribe"], "languages": ["zh"],
                           "verification": ["languages": "unverified"]]
    obj["files"] = [["path": "tts/other.onnx", "sizeBytes": 4,
                     "sha256": String(repeating: "a", count: 64), "classification": "model"]]
    obj["roles"] = ["encoder": "tts/other.onnx"]
    do {
        let manifest = try PackageVerifier.decodeManifest(JSONSerialization.data(withJSONObject: obj), packageId: "asr")
        let state = PackageVerifier.evaluateSync(manifest, modelsDir: URL(fileURLWithPath: "/nonexistent"))
        if case .manifestInvalid(let msg) = state, msg.contains("outside package") {} else {
            log.record("cross-package path not rejected, got \(state)")
        }
    }

    // On-disk symlink escape rejected by evaluate.
    let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("ev-\(UUID().uuidString)")
    let models = tmp.appendingPathComponent("models")
    let outside = tmp.appendingPathComponent("outside.bin")
    try FileManager.default.createDirectory(at: models.appendingPathComponent("asr"), withIntermediateDirectories: true)
    try Data([7, 7, 7]).write(to: outside)
    try FileManager.default.createSymbolicLink(at: models.appendingPathComponent("asr/encoder.onnx"),
                                               withDestinationURL: outside)
    defer { try? FileManager.default.removeItem(at: tmp) }
    obj["files"] = [["path": "asr/encoder.onnx", "sizeBytes": 3,
                     "sha256": PackageVerifier.sha256Hex(Data([7, 7, 7])), "classification": "model"]]
    obj["roles"] = ["asr_encoder": "asr/encoder.onnx"]
    let manifest = try PackageVerifier.decodeManifest(JSONSerialization.data(withJSONObject: obj), packageId: "asr")
    let state = PackageVerifier.evaluateSync(manifest, modelsDir: models)
    if case .integrityFailed(let msgs) = state, msgs[0].contains("symlink") {} else {
        log.record("symlink escape not rejected, got \(state)")
    }
}

// MARK: - Staged install

func runInstallChecks() async throws {
    let fm = FileManager.default
    let tempRoot = fm.temporaryDirectory.appendingPathComponent("install-checks-\(UUID().uuidString)")
    let modelsDir = tempRoot.appendingPathComponent("models")
    try fm.createDirectory(at: modelsDir, withIntermediateDirectories: true)
    let repository = ModelRepository(modelsDir: modelsDir)
    defer { try? fm.removeItem(at: tempRoot) }

    // Each check starts from an EMPTY models dir: no cross-check leftovers
    // that could turn a stale state into a false pass or false failure.
    func wipeModels() throws {
        try? fm.removeItem(at: modelsDir)
        try fm.createDirectory(at: modelsDir, withIntermediateDirectories: true)
    }
    func stagingClean() -> Bool {
        let s = (try? fm.contentsOfDirectory(atPath: modelsDir.appendingPathComponent(".staging").path)) ?? []
        let t = (try? fm.contentsOfDirectory(atPath: modelsDir.appendingPathComponent(".trash").path)) ?? []
        return s.isEmpty && t.isEmpty
    }

    func manifestJSON(status: String = "verified", files: [[String: Any]], roles: [String: String]) throws -> Data {
        let object: [String: Any] = [
            "schemaVersion": "2", "packageId": "asr", "version": "1.0.0", "status": status,
            "source": ["repoId": "test/repo",
                       "revision": status == "verified" ? String(repeating: "a", count: 40) : "",
                       "upstreamModelId": "test/model", "licenseSource": "test"],
            "runtime": ["backend": "onnx", "apiContractVersion": "1", "streaming": false,
                        "targetPlatforms": [["platform": "ios"]],
                        "runtimeRevision": status == "verified" ? "v1.24.2" : ""],
            "capabilities": ["modes": ["transcribe"],
                             "languages": status == "verified" ? ["zh"] : [NSNull()],
                             "verification": status == "verified" ? ["languages": "unverified"] : [:]],
            "files": files, "roles": roles,
        ]
        return try JSONSerialization.data(withJSONObject: object)
    }
    func fileEntry(_ path: String, bytes: Data) -> [String: Any] {
        ["path": path, "sizeBytes": bytes.count, "sha256": PackageVerifier.sha256Hex(bytes),
         "classification": "model"]
    }
    func write(_ bytes: Data, to path: String, in dir: URL) throws {
        let url = dir.appendingPathComponent(path)
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try bytes.write(to: url)
    }

    // Success: single prefix layout, readiness re-check passes, staging clean.
    do {
        try write(Data([1]), to: "asr/encoder.onnx", in: modelsDir)
        let newBytes = Data([1, 2, 3, 4])
        let sourceDir = tempRoot.appendingPathComponent("source")
        try write(newBytes, to: "asr/encoder.onnx", in: sourceDir)
        let data = try manifestJSON(files: [fileEntry("asr/encoder.onnx", bytes: newBytes)],
                                    roles: ["encoder": "asr/encoder.onnx"])
        try repository.installPackage(packageId: "asr", manifestData: data, from: sourceDir)
        check(try Data(contentsOf: modelsDir.appendingPathComponent("asr/encoder.onnx")) == newBytes,
              "install success: wrong bytes installed")
        check(!fm.fileExists(atPath: modelsDir.appendingPathComponent("asr/asr").path),
              "install success: double prefix asr/asr created")
        let manifest = try PackageVerifier.decodeManifest(data, packageId: "asr")
        check(PackageVerifier.evaluateSync(manifest, modelsDir: modelsDir) == .ready,
              "install success: readiness re-check not .ready")
        check(stagingClean(), "install success: staging/trash package leftovers")
    }

    do {
        try wipeModels()
        try write(Data([1]), to: "asr/encoder.onnx", in: modelsDir)
        let sourceDir = tempRoot.appendingPathComponent("source2")
        try write(Data([2, 2, 2]), to: "asr/encoder.onnx", in: sourceDir)
        let wrong: [String: Any] = ["path": "asr/encoder.onnx", "sizeBytes": 3,
                                    "sha256": PackageVerifier.sha256Hex(Data([8, 8, 8])),
                                    "classification": "model"]
        let data = try manifestJSON(files: [wrong], roles: ["encoder": "asr/encoder.onnx"])
        expectThrows("bad hash install succeeded") {
            try repository.installPackage(packageId: "asr", manifestData: data, from: sourceDir)
        }
        check(try Data(contentsOf: modelsDir.appendingPathComponent("asr/encoder.onnx")) == Data([1]),
              "bad hash: previous package damaged")
        check(stagingClean(), "bad hash: staging leftovers")
    }

    do {
        try wipeModels()
        try write(Data([1]), to: "asr/encoder.onnx", in: modelsDir)
        let newBytes = Data([1, 2, 3, 4, 5])
        let sourceDir = tempRoot.appendingPathComponent("source3")
        try write(newBytes, to: "asr/encoder.onnx", in: sourceDir)
        let data = try manifestJSON(files: [fileEntry("asr/encoder.onnx", bytes: newBytes)],
                                    roles: ["encoder": "asr/encoder.onnx"])
        let task = Task {
            let result = try? repository.installPackage(packageId: "asr", manifestData: data, from: sourceDir)
            return result
        }
        task.cancel()
        _ = await task.result
        check(try Data(contentsOf: modelsDir.appendingPathComponent("asr/encoder.onnx")) == Data([1]),
              "cancel: previous package damaged")
        check(stagingClean(), "cancel: staging leftovers")
    }

    do {
        try wipeModels()
        let outside = tempRoot.appendingPathComponent("outside.bin")
        try Data([7, 7, 7]).write(to: outside)
        let sourceDir = tempRoot.appendingPathComponent("source4")
        try write(Data([0]), to: "asr/keep", in: sourceDir)
        try fm.createSymbolicLink(at: sourceDir.appendingPathComponent("asr/encoder.onnx"),
                                  withDestinationURL: outside)
        let data = try manifestJSON(files: [fileEntry("asr/encoder.onnx", bytes: Data([7, 7, 7]))],
                                    roles: ["encoder": "asr/encoder.onnx"])
        expectThrows("symlinked source accepted") {
            try repository.installPackage(packageId: "asr", manifestData: data, from: sourceDir)
        }
        check(!fm.fileExists(atPath: modelsDir.appendingPathComponent("asr/encoder.onnx").path),
              "symlinked source: file installed anyway")
        check(stagingClean(), "symlinked source: staging leftovers")
    }

    do {
        try wipeModels()
        let outsideDir = tempRoot.appendingPathComponent("outside-dir")
        try fm.createDirectory(at: outsideDir, withIntermediateDirectories: true)
        try fm.createDirectory(at: modelsDir.appendingPathComponent("asr"), withIntermediateDirectories: true)
        try fm.createSymbolicLink(at: modelsDir.appendingPathComponent("asr/sub"),
                                  withDestinationURL: outsideDir)
        let sourceDir = tempRoot.appendingPathComponent("source5")
        try write(Data([3, 3]), to: "asr/sub/encoder.onnx", in: sourceDir)
        let data = try manifestJSON(files: [fileEntry("asr/sub/encoder.onnx", bytes: Data([3, 3]))],
                                    roles: ["encoder": "asr/sub/encoder.onnx"])
        do {
            try repository.installPackage(packageId: "asr", manifestData: data, from: sourceDir)
        } catch {
            log.record("symlinked destination: install failed unexpectedly: \(error)")
        }
        // The whole package dir was replaced: the old symlink is gone and
        // nothing was ever written through it.
        check((try? fm.destinationOfSymbolicLink(atPath: modelsDir.appendingPathComponent("asr/sub").path)) == nil,
              "symlinked destination: old symlink survived the switch")
        let outsideContents = (try? fm.contentsOfDirectory(atPath: outsideDir.path)) ?? []
        check(outsideContents.isEmpty, "symlinked destination: wrote through symlink")
    }

    do {
        try wipeModels()
        try fm.createDirectory(at: modelsDir.appendingPathComponent(".locks"), withIntermediateDirectories: true)
        let lockPath = modelsDir.appendingPathComponent(".locks/asr.lock").path
        let fd = open(lockPath, O_CREAT | O_RDWR, 0o644)
        check(fd >= 0, "flock: cannot open lock file")
        defer { close(fd) }
        check(flock(fd, LOCK_EX | LOCK_NB) == 0, "flock: cannot acquire lock in test")
        let sourceDir = tempRoot.appendingPathComponent("source6")
        try write(Data([9, 9]), to: "asr/encoder.onnx", in: sourceDir)
        let data = try manifestJSON(files: [fileEntry("asr/encoder.onnx", bytes: Data([9, 9]))],
                                    roles: ["encoder": "asr/encoder.onnx"])
        do {
            try repository.installPackage(packageId: "asr", manifestData: data, from: sourceDir)
            log.record("locked install succeeded (mutex broken)")
        } catch ModelRepository.InstallError.locked { /* expected */ }
    }

    do {
        try wipeModels()
        let firstReader = try ModelPackageLease(modelsDir: modelsDir, packageId: "asr")
        let secondReader = try ModelPackageLease(modelsDir: modelsDir, packageId: "asr")
        expectThrows("replacement while native reader is live") {
            try PackageLock.withLock(modelsDir: modelsDir, packageId: "asr") { () }
        }
        firstReader.release()
        expectThrows("replacement while second reader is live") {
            try PackageLock.withLock(modelsDir: modelsDir, packageId: "asr") { () }
        }
        expectThrows("delete while a reader is live") { try repository.deleteModels() }
        secondReader.release()
        try PackageLock.withLock(modelsDir: modelsDir, packageId: "asr") {
            secondReader.release() // must not close a reused descriptor
            expectThrows("reader admitted during replacement") {
                try ModelPackageLease(modelsDir: modelsDir, packageId: "asr")
            }
        }
    }

    do {
        let trash = modelsDir.appendingPathComponent(".trash/asr-crashed")
        try fm.createDirectory(at: trash, withIntermediateDirectories: true)
        try Data([1]).write(to: trash.appendingPathComponent("encoder.onnx"))
        let staging = modelsDir.appendingPathComponent(".staging/asr-stale")
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        try? fm.removeItem(at: modelsDir.appendingPathComponent("asr"))  // crash lost the live dir
        try repository.recover(packageId: "asr")
        check(try Data(contentsOf: modelsDir.appendingPathComponent("asr/encoder.onnx")) == Data([1]),
              "crash recovery: previous package not restored")
        check(!fm.fileExists(atPath: staging.path), "crash recovery: stale staging not removed")
    }

    do {
        try wipeModels()
        let sourceDir = tempRoot.appendingPathComponent("source7")
        try write(Data([1, 2, 3, 4]), to: "asr/encoder.onnx", in: sourceDir)
        let huge: [String: Any] = ["path": "asr/encoder.onnx", "sizeBytes": 9_000_000_000_000,
                                   "sha256": PackageVerifier.sha256Hex(Data([1, 2, 3, 4])),
                                   "classification": "model"]
        let data = try manifestJSON(files: [huge], roles: ["encoder": "asr/encoder.onnx"])
        do {
            try repository.installPackage(packageId: "asr", manifestData: data, from: sourceDir)
            log.record("huge install succeeded (space check broken)")
        } catch ModelRepository.InstallError.insufficientSpace { /* expected */ }
        check(stagingClean(), "insufficient space: staging package leftovers")
    }
}

// MARK: - Tiny harness helpers

final class Expectation {
    private let semaphore = DispatchSemaphore(value: 0)
    func fulfill() { semaphore.signal() }
    func wait(_ timeout: TimeInterval = 5) { _ = semaphore.wait(timeout: .now() + timeout) }
}

func expectation(_ label: String) -> Expectation { Expectation() }

func wait(_ exp: Expectation, timeout: TimeInterval = 5) { exp.wait(timeout) }
