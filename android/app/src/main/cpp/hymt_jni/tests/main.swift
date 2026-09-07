import Foundation

var failures = 0
func check(_ cond: Bool, _ msg: String) {
    if cond {
        print("ok: \(msg)")
    } else {
        print("FAIL: \(msg)")
        failures += 1
    }
}

func makeManifest(modelPath: String) -> PackageVerifier.Manifest {
    let file = PackageVerifier.FileEntry(
        path: modelPath, sizeBytes: nil, sha256: nil,
        classification: "model", externalData: nil)
    return PackageVerifier.Manifest(
        schemaVersion: "2", packageId: "mt", version: "0.0.0", status: "draft",
        sourceRevision: "1bed36c0a8f5a0eddf77987b02ba66d4c268aca2",
        runtimeBackend: "gguf-llama-cpp",
        runtimeRevision: "1e411d8f5a1e23525fa3265dfb4bd76265465397",
        modes: ["translate"], files: [file],
        roles: ["translator": modelPath])
}

func main() async {
    let args = CommandLine.arguments
    guard args.count >= 2 else {
        print("usage: hymt_swift_smoke <model.gguf>")
        exit(2)
    }
    let modelPath = args[1]

    let revision = HyMtNative.runtimeRevision()
    print("runtime revision: \(revision)")
    check(revision == "1e411d8f5a1e23525fa3265dfb4bd76265465397",
          "pinned revision string matches")

    let load = HyMtNative.load(modelPath: modelPath)
    check(load.status == .ok && load.handle != nil, "native load (real GGUF, CPU EP): \(load.error)")
    guard let handle = load.handle else { exit(1) }

    let t1 = HyMtNative.translate(handle: handle,
                                  text: "今天下午我们去博物馆参观，好吗？",
                                  sourceLanguage: "Chinese",
                                  targetLanguage: "English",
                                  context: ["The weather is nice today."])
    print("zh→en: \(t1.output ?? "(\(t1.status): \(t1.error))")")
    check(t1.status == .ok, "zh→en translate returns OK")
    check(t1.output?.isEmpty == false, "zh→en output non-empty")
    check(t1.output != "今天下午我们去博物馆参观，好吗？", "zh→en output differs from input")

    let t2 = HyMtNative.translate(handle: handle,
                                  text: "Hello, how are you?",
                                  sourceLanguage: "English",
                                  targetLanguage: "Chinese",
                                  context: [])
    print("en→zh: \(t2.output ?? "(\(t2.status): \(t2.error))")")
    check(t2.status == .ok && t2.output?.isEmpty == false, "en→zh translate returns OK, non-empty")

    let modelURL = URL(fileURLWithPath: modelPath)
    let engine = HyMtTranslationEngine(
        modelsRoot: modelURL.deletingLastPathComponent(),
        manifest: makeManifest(modelPath: modelURL.lastPathComponent))
    let available = await engine.isAvailable
    check(available, "engine isAvailable with manifest-resolved real model")
    do {
        let out = try await engine.translate(text: "谢谢你的帮助。",
                                             sourceLanguage: "Chinese",
                                             targetLanguage: "English")
        print("engine zh→en: \(out)")
        check(!out.isEmpty && out != "谢谢你的帮助。", "engine translate produces a real translation")
    } catch {
        check(false, "engine translate threw: \(error)")
    }

    let longText = Array(repeating: "这份报告涵盖了一季度销售、市场反馈与供应链的调整情况，请各部门关注重点问题。", count: 8).joined()
    let task = Task { () -> String in
        try await engine.translate(text: longText, sourceLanguage: "Chinese", targetLanguage: "English")
    }
    try? await Task.sleep(nanoseconds: 150_000_000)
    task.cancel()
    do {
        let out = try await task.value
        check(false, "cancelled translate unexpectedly returned \(out.prefix(40))")
    } catch is CancellationError {
        check(true, "mid-flight cancel maps to CancellationError")
    } catch {
        check(false, "cancelled translate threw \(error) instead of CancellationError")
    }

    do {
        _ = try await engine.translate(text: "早上好。",
                                       sourceLanguage: "Chinese",
                                       targetLanguage: "English")
        check(false, "sticky cancel should abort until unload")
    } catch is CancellationError {
        check(true, "sticky cancel until unload")
    } catch {
        check(false, "sticky cancel threw \(error)")
    }

    await engine.unload()
    do {
        let out = try await engine.translate(text: "再见。",
                                             sourceLanguage: "zh",
                                             targetLanguage: "en")
        print("engine after unload/reload: \(out)")
        check(!out.isEmpty, "unload then translate reloads a fresh handle")
    } catch {
        check(false, "engine translate after unload threw: \(error)")
    }

    let cancelled = Task {
        try await engine.translate(text: "你好", sourceLanguage: "Chinese", targetLanguage: "English")
    }
    cancelled.cancel()
    do {
        _ = try await cancelled.value
        check(false, "already-cancelled task must not return success")
    } catch is CancellationError {
        check(true, "already-cancelled task does not load/return success")
    } catch {
        check(false, "already-cancelled threw \(error)")
    }

    handle.cancel()
    let t3 = HyMtNative.translate(handle: handle,
                                  text: "你好",
                                  sourceLanguage: "Chinese",
                                  targetLanguage: "English",
                                  context: [])
    check(t3.status == .aborted, "C-level cancel is sticky")
    let t4 = HyMtNative.translate(handle: handle,
                                  text: "你好",
                                  sourceLanguage: "Chinese",
                                  targetLanguage: "English",
                                  context: [])
    check(t4.status == .aborted, "sticky cancel is not consumed by a failed translate")

    handle.release()
    await engine.unload()
    print(failures == 0 ? "SWIFT SMOKE PASSED" : "SWIFT SMOKE FAILED (\(failures))")
    exit(failures == 0 ? 0 : 1)
}

await main()
