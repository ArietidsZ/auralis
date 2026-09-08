import Testing
import Foundation
@testable import DialectInterpreter

/// Regression tests for `OnnxTensor`'s input validation and read-out gates in
/// OrtRuntime.swift. Every rejection pinned here used to be either a trap
/// (`reduce(1, *)` overflows into a crash), a confusing count mismatch, or a
/// silent dtype reinterpretation; the tests hold the named, thrown errors.
///
/// These are the in-repo form of the host verification (official ORT 1.24.2
/// artifacts, 13/13 checks) and use no pointer-identity or mutation
/// assertions: `tensorData()` snapshot behavior is environment-dependent, so
/// only values, shapes and gate messages are portable observables.
@MainActor
struct TtsOnnxTensorTests {

    /// Runs `body`, returns the thrown error's description, or records an
    /// issue and returns "" when the invalid input was accepted.
    private func rejectionError(_ body: () throws -> Void) -> String {
        do {
            try body()
        } catch {
            return String(describing: error)
        }
        Issue.record("invalid input was accepted")
        return ""
    }

    // MARK: shape validation

    @Test func negativeDimensionIsNamedRejectionNotTrap() {
        let error = rejectionError { _ = try OnnxTensor(floatData: [0, 0], shape: [1, -2]) }
        #expect(error.contains("negative dimension"), "unexpected error: \(error)")
    }

    @Test func int64NegativeDimensionIsNamedRejectionNotTrap() {
        let error = rejectionError { _ = try OnnxTensor(int64Data: [0], shape: [2, -1]) }
        #expect(error.contains("negative dimension"), "unexpected error: \(error)")
    }

    @Test func elementCountOverflowIsNamedRejectionNotTrap() {
        // [Int.max, 2, 2] overflowed `reduce(1, *)` and crashed the process;
        // it must be a thrown error instead.
        let error = rejectionError { _ = try OnnxTensor(floatData: [0], shape: [Int.max, 2, 2]) }
        #expect(error.contains("addressable element count"), "unexpected error: \(error)")
    }

    @Test func hugeButFiniteCountFailsAsCountMismatch() {
        // 1<<40 is representable, so this must reach the count-mismatch gate,
        // not the overflow gate and certainly not an allocation.
        let error = rejectionError { _ = try OnnxTensor(floatData: [0], shape: [1 << 40]) }
        #expect(error.contains("element count 1 does not match shape"), "unexpected error: \(error)")
    }

    @Test func floatCountMismatchIsRejected() {
        let error = rejectionError { _ = try OnnxTensor(floatData: [0, 0], shape: [2, 2]) }
        #expect(error.contains("element count 2 does not match shape"), "unexpected error: \(error)")
    }

    @Test func int64CountMismatchIsRejected() {
        let error = rejectionError { _ = try OnnxTensor(int64Data: [1], shape: [2, 1]) }
        #expect(error.contains("element count 1 does not match shape"), "unexpected error: \(error)")
    }

    // MARK: dtype gates

    @Test func floatArrayRefusesInt64Tensor() throws {
        let tensor = try OnnxTensor(int64Data: [1, 2], shape: [2])
        let error = rejectionError { _ = try tensor.floatArray() }
        #expect(error.contains("dtype is int64, not float; refusing to reinterpret"),
                "unexpected error: \(error)")
    }

    @Test func int64ArrayRefusesFloatTensor() throws {
        let tensor = try OnnxTensor(floatData: [1, 2], shape: [2])
        let error = rejectionError { _ = try tensor.int64Array() }
        #expect(error.contains("dtype is float, not int64; refusing to reinterpret"),
                "unexpected error: \(error)")
    }

    @Test func requireFloatMirrorsFloatArrayGate() throws {
        let float = try OnnxTensor(floatData: [1], shape: [1])
        do {
            try float.requireFloat()
        } catch {
            Issue.record("float tensor must pass requireFloat: \(error)")
        }
        let int64 = try OnnxTensor(int64Data: [1], shape: [1])
        let error = rejectionError { try int64.requireFloat() }
        #expect(error.contains("dtype is int64, not float; refusing to reinterpret"),
                "unexpected error: \(error)")
    }

    // MARK: shape semantics

    @Test func emptyShapeIsAScalarTensor() throws {
        // Rank-0 tensors carry exactly one element (verified against ORT
        // 1.24.2); elementCount([]) must accept exactly that.
        let scalar = try OnnxTensor(floatData: [7], shape: [])
        #expect(scalar.shape == [])
        #expect(try scalar.floatArray() == [7.0])
        let int64Scalar = try OnnxTensor(int64Data: [42], shape: [])
        #expect(try int64Scalar.int64Array() == [42])
    }

    @Test func zeroExtentShapeCarriesNoElements() throws {
        // [4, 0] is the TTS empty-past prefill input; it must construct and
        // read back as zero elements, never as a mismatch or a null buffer.
        let empty = try OnnxTensor(floatData: [], shape: [4, 0])
        #expect(empty.shape == [4, 0])
        #expect(try empty.floatArray() == [])
        let scanned = try empty.withFloatBuffer { $0.count }
        #expect(scanned == 0)
    }

    // MARK: no-bridge float borrow

    @Test func withFloatBufferMatchesFloatArray() throws {
        let tensor = try OnnxTensor(floatData: [1, -2, 3, -4], shape: [4])
        let borrowed = try tensor.withFloatBuffer { Array($0) }
        #expect(try borrowed == tensor.floatArray())
        // The body's return value is propagated (production uses it for
        // finiteness scans over the talker/CP state tensors).
        let checksum = try tensor.withFloatBuffer { $0.reduce(0, +) }
        #expect(checksum == Float(1 - 2 + 3 - 4))
    }

    @Test func withFloatBufferRefusesInt64Tensor() throws {
        let tensor = try OnnxTensor(int64Data: [1, 2], shape: [2])
        let error = rejectionError { _ = try tensor.withFloatBuffer { Array($0) } }
        #expect(error.contains("dtype is int64, not float; refusing to reinterpret"),
                "unexpected error: \(error)")
    }
}
