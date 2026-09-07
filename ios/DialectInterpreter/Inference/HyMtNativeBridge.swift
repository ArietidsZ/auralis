import Foundation
import hymt_core

/// Swift binding via Clang module `hymt_core` (`hymt_core.h`).
/// Search path for the module map: `android/app/src/main/cpp/hymt_jni`
/// (host: swiftc -I that dir; iOS: SWIFT_INCLUDE_PATHS / header map — root).
enum HyMtNative {
    static let pinnedRevision = "1e411d8f5a1e23525fa3265dfb4bd76265465397"

    static func runtimeRevision() -> String {
        let p = hymt_runtime_revision()
        return p != nil ? String(cString: p!) : ""
    }

    enum Status: Int32 {
        case ok = 0
        case invalidArgument = 1
        case aborted = 2
        case failed = 3
    }

    /// Owner-side handle. cancel() and release() share a lock so onCancel
    /// never calls into a pointer the owner already niled. Does not make
    /// hymt_cancel(stale) defined.
    final class OwnedHandle: @unchecked Sendable {
        private let lock = NSLock()
        private let operationLock = NSLock()
        private var ptr: OpaquePointer?

        fileprivate init(_ ptr: OpaquePointer) {
            self.ptr = ptr
        }

        deinit { release() }

        func cancel() {
            lock.lock()
            let held = ptr
            if let held {
                hymt_cancel(held)
            }
            lock.unlock()
        }

        func release() {
            operationLock.lock()
            defer { operationLock.unlock() }
            lock.lock()
            let held = ptr
            ptr = nil
            lock.unlock()
            if let held {
                hymt_release(held)
            }
        }

        fileprivate func withPointer<T>(_ operation: (OpaquePointer) -> T) -> T? {
            operationLock.lock()
            defer { operationLock.unlock() }
            lock.lock()
            let held = ptr
            lock.unlock()
            guard let held else { return nil }
            return operation(held)
        }
    }

    static func load(modelPath: String, errBufferCapacity: Int = 512)
        -> (status: Status, handle: OwnedHandle?, error: String) {
        let errCap = errBufferCapacity
        var err = [CChar](repeating: 0, count: errCap)
        var out: OpaquePointer?
        let rc = modelPath.withCString { path in
            err.withUnsafeMutableBufferPointer { errBuf in
                hymt_load(path, &out, errBuf.baseAddress, errCap)
            }
        }
        let status = Status(rawValue: rc) ?? .failed
        let message = String(cString: err)
        guard status == .ok, let out else {
            return (status, nil, message)
        }
        return (status, OwnedHandle(out), message)
    }

    static func translate(handle: OwnedHandle,
                          text: String,
                          sourceLanguage: String,
                          targetLanguage: String,
                          context: [String],
                          errBufferCapacity: Int = 512)
        -> (status: Status, output: String?, error: String) {
        guard !([text, sourceLanguage, targetLanguage] + context).contains(where: { $0.contains("\0") }) else {
            return (.invalidArgument, nil, "embedded NUL is not supported by the C string protocol")
        }
        var err = [CChar](repeating: 0, count: errBufferCapacity)
        var out: UnsafeMutablePointer<CChar>?
        let ctxBoxes = context.map { CStringBox($0) }
        var ctxPtrs: [UnsafePointer<CChar>?] = []
        ctxPtrs.reserveCapacity(ctxBoxes.count)
        for box in ctxBoxes {
            guard let pointer = box.pointer else {
                return (.invalidArgument, nil, "out of memory duplicating context string")
            }
            ctxPtrs.append(UnsafePointer(pointer))
        }
        let rc = handle.withPointer { raw in withExtendedLifetime(ctxBoxes) {
            text.withCString { textC in
                sourceLanguage.withCString { srcC in
                    targetLanguage.withCString { tgtC in
                        ctxPtrs.withUnsafeBufferPointer { ctxBuf in
                            err.withUnsafeMutableBufferPointer { errBuf in
                                hymt_translate(raw,
                                               textC, srcC, tgtC,
                                               ctxBuf.baseAddress, ctxPtrs.count,
                                               &out, errBuf.baseAddress, errBufferCapacity)
                            }
                        }
                    }
                }
            }
        }} ?? Int32(HYMT_ERR_INVALID.rawValue)
        let status = Status(rawValue: rc) ?? .failed
        let message = String(cString: err)
        guard status == .ok, let outPtr = out else {
            return (status, nil, message)
        }
        let output = String(cString: outPtr)
        hymt_free_string(outPtr)
        return (status, output, message)
    }

    private final class CStringBox {
        fileprivate let pointer: UnsafeMutablePointer<CChar>?

        init(_ string: String) {
            pointer = string.withCString { strdup($0) }
        }

        deinit {
            if let pointer { free(pointer) }
        }
    }
}
