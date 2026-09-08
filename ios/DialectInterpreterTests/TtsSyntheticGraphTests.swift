import Testing
import Foundation
@testable import DialectInterpreter

/// SYNTHETIC fixtures — the two tiny ONNX graphs below are hand-encoded at
/// runtime and are NOT the shipped TTS models. They exist to pin, through the
/// production `OrtInferenceSession`/`OnnxTensor` path, the two behaviors the
/// TTS KV cache relies on:
///
/// 1. `kvfeed.onnx`: `present = Concat(past, x, axis=1)` + `y = ones · present`.
///    The engine feeds each step's `present` ORTValue straight back as the
///    next step's `past` input with no managed copy; these tests hold that
///    feedback loop bit-identical to the materialize-and-rebuild twin that
///    emulates the pre-change engine.
/// 2. `kvfeed2.onnx`: the same Concat plus `y = Identity(present)` and
///    `echo = Identity(past)`, an aliasing-shaped graph used to check output
///    lifetime: a retained output must keep its values while later runs
///    allocate fresh state.
///
/// No pointer-identity or mutation assertions: `tensorData()` snapshot
/// behavior differs by Foundation build (measured 2026-09-08, official 1.24.2
/// artifacts), so value identity is the portable observable.
@MainActor
final class TtsSyntheticGraphTests {

    private let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("tts-synthetic-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Self.writeKvFeedGraph(dir.appendingPathComponent("kvfeed.onnx").path)
        try Self.writeAliasFeedGraph(dir.appendingPathComponent("kvfeed2.onnx").path)
    }

    deinit {
        try? FileManager.default.removeItem(at: dir)
    }

    private func makeSession(model: String) throws -> OrtInferenceSession {
        try OrtInferenceSession(env: OrtInferenceTestSupport.makeEnv(),
                                modelPath: dir.appendingPathComponent(model).path)
    }

    // MARK: KV feedback ownership

    @Test func kvFeedbackIsBitIdenticalToCopyPathTwin() throws {
        let session = try makeSession(model: "kvfeed.onnx")
        let steps = 64
        var seed: UInt64 = 0x9E37_79B9_7F4A_7C15
        func lcgFloat() -> Float {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Float(Int64(bitPattern: seed >> 11) % 1000) / 1000.0
        }
        var columns: [[Float]] = []

        // Zero-copy path: own the output ORTValue and feed it straight back
        // (what the engine now does for talker/CP state).
        var past = try OnnxTensor(floatData: [], shape: [4, 0])
        var zeroCopyPresent: [[Float]] = []
        for step in 0..<steps {
            let x = [lcgFloat(), lcgFloat(), lcgFloat(), lcgFloat()]
            let outputs = try session.run(inputs: [
                "past": past,
                "x": try OnnxTensor(floatData: x, shape: [4, 1]),
            ], outputNames: ["present", "y"])
            guard let present = outputs["present"], let y = outputs["y"] else {
                Issue.record("step \(step): missing present/y output")
                return
            }
            #expect(present.shape == [4, step + 1], "step \(step): shape \(present.shape)")
            let presentValues = try present.floatArray()
            #expect(Self.bitEqual(presentValues, Self.expectedKvFlat(columns: columns + [x])),
                    "step \(step): concat mismatch (zero-copy path)")
            #expect(Self.bitEqual(try y.floatArray(), Self.expectedY(columns: columns + [x])),
                    "step \(step): matmul mismatch (zero-copy path)")
            columns.append(x)
            zeroCopyPresent.append(presentValues)
            past = present
        }

        // Copy-path twin: identical inputs, but each step materializes the
        // output to [Float] and rebuilds the next input (pre-change engine).
        var copyPast = try OnnxTensor(floatData: [], shape: [4, 0])
        for step in 0..<steps {
            let outputs = try session.run(inputs: [
                "past": copyPast,
                "x": try OnnxTensor(floatData: columns[step], shape: [4, 1]),
            ], outputNames: ["present", "y"])
            guard let present = outputs["present"] else {
                Issue.record("copy step \(step): missing present output")
                return
            }
            let materialized = try present.floatArray()
            #expect(Self.bitEqual(materialized, zeroCopyPresent[step]),
                    "step \(step): copy path diverged from zero-copy path")
            copyPast = try OnnxTensor(floatData: materialized, shape: [4, step + 1])
        }
    }

    @Test func inputTensorReuseAcrossRunsIsStableAndNonConsuming() throws {
        let session = try makeSession(model: "kvfeed.onnx")
        let past = try OnnxTensor(floatData: [1, 2, 3, 4], shape: [4, 1])
        let x = try OnnxTensor(floatData: [5, 6, 7, 8], shape: [4, 1])
        var first: [Float]?
        for run in 0..<3 {
            let outputs = try session.run(inputs: ["past": past, "x": x],
                                          outputNames: ["present", "y"])
            guard let present = outputs["present"] else {
                Issue.record("run \(run): missing present output")
                return
            }
            let values = try present.floatArray()
            if let first {
                #expect(Self.bitEqual(first, values), "run \(run) differed on reused inputs")
            } else {
                first = values
            }
        }
        #expect(try past.floatArray() == [1, 2, 3, 4],
                "running the session must not consume or mutate the input tensor")
    }

    @Test func wrappedOutputsReportRuntimeShapeAndDtype() throws {
        let session = try makeSession(model: "kvfeed.onnx")
        let outputs = try session.run(inputs: [
            "past": try OnnxTensor(floatData: [1, -2, 3, -4], shape: [4, 1]),
            "x": try OnnxTensor(floatData: [0.5, -0.5, 2, -2], shape: [4, 1]),
        ], outputNames: ["present", "y"])
        guard let present = outputs["present"], let y = outputs["y"] else {
            Issue.record("missing present/y output")
            return
        }
        #expect(present.shape == [4, 2])
        #expect(present.dtype == .float)
        #expect(y.dtype == .float)
        // present is [4, 2] (past column + x column); y = ones[1,4] · present
        // is [1, 2] holding each column's sum (exact in Float here).
        #expect(y.shape == [1, 2])
        #expect(try y.floatArray() == [-2, 0])
    }

    @Test func withFloatBufferScansRunOutputInPlace() throws {
        let session = try makeSession(model: "kvfeed.onnx")
        let outputs = try session.run(inputs: [
            "past": try OnnxTensor(floatData: [1, -2, 3, -4], shape: [4, 1]),
            "x": try OnnxTensor(floatData: [0.5, -0.5, 2, -2], shape: [4, 1]),
        ], outputNames: ["present"])
        guard let present = outputs["present"] else {
            Issue.record("missing present output")
            return
        }
        var allFinite = true
        let scanned = try present.withFloatBuffer { buffer -> Int in
            for v in buffer where !v.isFinite { allFinite = false }
            return buffer.count
        }
        #expect(scanned == 8, "scanned \(scanned) elements")
        #expect(allFinite)
        // The borrow sees the same values a materializing read would.
        #expect(Self.bitEqual(try present.withFloatBuffer { Array($0) },
                              try present.floatArray()))
    }

    @Test func identityOutputsRetainValuesAcrossRuns() throws {
        let session = try makeSession(model: "kvfeed2.onnx")
        let steps = 64
        var seed: UInt64 = 0x0123_4567_89AB_CDEF
        func lcgFloat() -> Float {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Float(Int64(bitPattern: seed >> 11) % 1000) / 1000.0
        }
        var columns: [[Float]] = []
        var past = try OnnxTensor(floatData: [], shape: [4, 0])
        var retainedY: OnnxTensor?
        var retainedSnapshot: [Float]?
        for step in 0..<steps {
            let x = [lcgFloat(), lcgFloat(), lcgFloat(), lcgFloat()]
            let outputs = try session.run(inputs: [
                "past": past,
                "x": try OnnxTensor(floatData: x, shape: [4, 1]),
            ], outputNames: ["present", "y", "echo"])
            guard let present = outputs["present"], let y = outputs["y"], let echo = outputs["echo"] else {
                Issue.record("step \(step): missing outputs")
                return
            }
            #expect(present.shape == [4, step + 1], "step \(step): shape \(present.shape)")
            let presentValues = try present.floatArray()
            #expect(Self.bitEqual(presentValues, Self.expectedKvFlat(columns: columns + [x])),
                    "step \(step): concat mismatch")
            #expect(Self.bitEqual(try y.floatArray(), presentValues),
                    "step \(step): Identity(present) mismatch")
            #expect(Self.bitEqual(try echo.floatArray(),
                                  Self.expectedKvFlat(columns: columns)),
                    "step \(step): Identity(past) mismatch")
            columns.append(x)
            if step == 0 {
                retainedY = y
                retainedSnapshot = presentValues
            }
            past = present
        }
        // The step-0 output is retained but never fed back; every later run
        // must allocate fresh storage instead of overwriting it.
        guard let retainedY, let retainedSnapshot else {
            Issue.record("retained output was never captured")
            return
        }
        #expect(Self.bitEqual(try retainedY.floatArray(), retainedSnapshot),
                "retained Identity output was overwritten by a later run")
    }

    // MARK: minimal protobuf wire encoding for the synthetic graphs

    private static func varint(_ value: Int) -> [UInt8] {
        var v = UInt64(value), out: [UInt8] = []
        repeat {
            var byte = UInt8(v & 0x7F)
            v >>= 7
            if v != 0 { byte |= 0x80 }
            out.append(byte)
        } while v != 0
        return out
    }

    private static func tag(_ field: Int, _ wire: Int) -> [UInt8] { varint((field << 3) | wire) }

    private static func vint(_ field: Int, _ value: Int) -> [UInt8] { tag(field, 0) + varint(value) }

    private static func bytes(_ field: Int, _ payload: [UInt8]) -> [UInt8] {
        tag(field, 2) + varint(payload.count) + payload
    }

    private static func str(_ field: Int, _ s: String) -> [UInt8] { bytes(field, Array(s.utf8)) }

    private static func tensorProto(_ name: String, dims: [Int], floats: [Float]) -> [UInt8] {
        var out = str(8, name)
        for d in dims { out += vint(1, d) }          // dims (unpacked varints)
        out += vint(2, 1)                            // data_type FLOAT = 1
        var payload: [UInt8] = []
        for f in floats {
            withUnsafeBytes(of: f.bitPattern.littleEndian) { payload.append(contentsOf: $0) }
        }
        out += bytes(4, payload)
        return out
    }

    private static func valueInfo(_ name: String, elemType: Int = 1, shape: [[Int]]? = nil) -> [UInt8] {
        // ValueInfoProto{ name=1, type=2 TypeProto{ tensor_type=1 TensorType{
        //   elem_type=1, shape=2 TensorShapeProto{ dim=1 Dimension{dim_value=1} } } } }
        var tensorType = vint(1, elemType)
        if let shape {
            var shapeProto: [UInt8] = []
            for dim in shape {
                shapeProto += bytes(1, vint(1, dim[0]))
            }
            tensorType += bytes(2, shapeProto)
        }
        let type = bytes(1, tensorType)
        return str(1, name) + bytes(2, type)
    }

    private static func nodeProto(_ name: String, op: String, inputs: [String], outputs: [String],
                                  attrs: [[UInt8]] = []) -> [UInt8] {
        // NodeProto: input=1, output=2, name=3, op_type=4, attribute=5.
        var out = str(3, name) + str(4, op)
        for i in inputs { out += str(1, i) }
        for o in outputs { out += str(2, o) }
        for a in attrs { out += bytes(5, a) }
        return out
    }

    private static func writeKvFeedGraph(_ path: String) throws {
        // inputs: "past" float (dynamic shape), "x" float [4,1]
        // present = Concat(past, x, axis=1); y = MatMul(ones[1,4], present)
        let axisAttr = str(1, "axis") + vint(20, 2) + vint(3, 1)  // type INT, i=1
        let concat = nodeProto("kv_concat", op: "Concat", inputs: ["past", "x"],
                               outputs: ["present"], attrs: [axisAttr])
        let matmul = nodeProto("kv_consume", op: "MatMul", inputs: ["ones", "present"], outputs: ["y"])
        let ones = tensorProto("ones", dims: [1, 4], floats: [1, 1, 1, 1])
        let graph = bytes(1, concat) + bytes(1, matmul) + bytes(5, ones)
            + str(2, "kvfeed")
            + bytes(11, valueInfo("past"))                   // float, shape inferred
            + bytes(11, valueInfo("x", shape: [[4], [1]]))
            + bytes(12, valueInfo("present"))                // dynamic
            + bytes(12, valueInfo("y"))
        let opset = bytes(1, []) + vint(2, 13)  // domain "" (empty string)
        let model = vint(1, 9) + str(2, "auralis-synthetic-kvfeed") + bytes(7, graph) + bytes(8, opset)
        try writeModel(model, to: path)
    }

    private static func writeAliasFeedGraph(_ path: String) throws {
        // present = Concat(past, x, axis=1); y = Identity(present);
        // echo = Identity(past)
        let axisAttr = str(1, "axis") + vint(20, 2) + vint(3, 1)
        let concat = nodeProto("alias_concat", op: "Concat", inputs: ["past", "x"],
                               outputs: ["present"], attrs: [axisAttr])
        let identY = nodeProto("alias_identity_y", op: "Identity", inputs: ["present"], outputs: ["y"])
        let identE = nodeProto("alias_identity_echo", op: "Identity", inputs: ["past"], outputs: ["echo"])
        let graph = bytes(1, concat) + bytes(1, identY) + bytes(1, identE)
            + str(2, "kvfeed2")
            + bytes(11, valueInfo("past"))
            + bytes(11, valueInfo("x", shape: [[4], [1]]))
            + bytes(12, valueInfo("present"))
            + bytes(12, valueInfo("y"))
            + bytes(12, valueInfo("echo"))
        let opset = bytes(1, []) + vint(2, 13)
        let model = vint(1, 9) + str(2, "auralis-synthetic-kvfeed2") + bytes(7, graph) + bytes(8, opset)
        try writeModel(model, to: path)
    }

    private static func writeModel(_ model: [UInt8], to path: String) throws {
        try Data(model).write(to: URL(fileURLWithPath: path))
    }

    // MARK: closed-form expectations

    private static func bitEqual(_ a: [Float], _ b: [Float]) -> Bool {
        guard a.count == b.count else { return false }
        for i in a.indices where a[i].bitPattern != b[i].bitPattern { return false }
        return true
    }

    /// present is row-major [4, N]; column c holds columns[c].
    private static func expectedKvFlat(columns: [[Float]]) -> [Float] {
        var flat = [Float]()
        flat.reserveCapacity(4 * columns.count)
        for row in 0..<4 {
            for col in columns { flat.append(col[row]) }
        }
        return flat
    }

    /// y = ones[1,4] · present[4,N] → per-column sums.
    private static func expectedY(columns: [[Float]]) -> [Float] {
        columns.map { $0.reduce(0, +) }
    }
}
