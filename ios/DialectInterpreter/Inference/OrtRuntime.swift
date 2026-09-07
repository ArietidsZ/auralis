import Foundation
import OnnxRuntimeBindings

/// Real ONNX Runtime binding layer. There is no placeholder session here: every
/// call delegates to the official onnxruntime-swift-package-manager ObjC API
/// (`ORTEnv` / `ORTSession` / `ORTValue`) as pinned in project.pbxproj
/// (v1.24.2). Errors are thrown, never silently converted into empty outputs,
/// zero-filled tensors or guessed dtypes.
enum OnnxRuntimeError: Error, LocalizedError {
    case environmentInit(String)
    case modelNotFound(String)
    case sessionInit(String)
    case inputTensor(String)
    case outputTensor(String)
    case inference(String)

    var errorDescription: String? {
        switch self {
        case .environmentInit(let m): return "ORT environment init failed: \(m)"
        case .modelNotFound(let p): return "model file not found: \(p)"
        case .sessionInit(let m): return "session init failed: \(m)"
        case .inputTensor(let m): return "input tensor error: \(m)"
        case .outputTensor(let m): return "output tensor error: \(m)"
        case .inference(let m): return "inference failed: \(m)"
        }
    }
}

/// Explicit tensor element dtype. Output decoding refuses anything else rather
/// than guessing how to reinterpret the bytes.
enum OnnxTensorDtype: Equatable {
    case float
    case int64

    init(from ortType: ORTTensorElementDataType) throws {
        switch ortType {
        case .float: self = .float
        case .int64: self = .int64
        default:
            throw OnnxRuntimeError.outputTensor(
                "unsupported element type \(ortType.rawValue); only float and int64 are handled")
        }
    }
}

/// A typed tensor backed by a real `ORTValue`. Inputs are constructed with an
/// explicit dtype; outputs wrap runtime values whose dtype and shape are read
/// from `tensorTypeAndShapeInfo()` (decode failures throw, they never fall
/// back to empty data).
struct OnnxTensor {
    let value: ORTValue
    let shape: [Int]
    let dtype: OnnxTensorDtype

    init(floatData: [Float], shape: [Int]) throws {
        self.shape = shape
        self.dtype = .float
        let expected = shape.reduce(1, *)
        guard expected == floatData.count else {
            throw OnnxRuntimeError.inputTensor("element count \(floatData.count) does not match shape \(shape)")
        }
        let data = floatData.withUnsafeBufferPointer { Data(buffer: $0) }
        do {
            self.value = try ORTValue(
                tensorData: NSMutableData(data: data),
                elementType: ORTTensorElementDataType.float,
                shape: shape.map { NSNumber(value: $0) }
            )
        } catch {
            throw OnnxRuntimeError.inputTensor("\(error)")
        }
    }

    init(int64Data: [Int64], shape: [Int]) throws {
        self.shape = shape
        self.dtype = .int64
        let expected = shape.reduce(1, *)
        guard expected == int64Data.count else {
            throw OnnxRuntimeError.inputTensor("element count \(int64Data.count) does not match shape \(shape)")
        }
        let data = int64Data.withUnsafeBufferPointer { Data(buffer: $0) }
        do {
            self.value = try ORTValue(
                tensorData: NSMutableData(data: data),
                elementType: ORTTensorElementDataType.int64,
                shape: shape.map { NSNumber(value: $0) }
            )
        } catch {
            throw OnnxRuntimeError.inputTensor("\(error)")
        }
    }

    /// Wrap a runtime-produced output ORTValue. The dtype and shape come from
    /// the runtime; a failure to read them is a thrown error, never an empty
    /// tensor with a guessed layout. (Verified against ort_value.h at tag
    /// v1.24.2: `tensorTypeAndShapeInfoWithError:` is nullable and throws in
    /// Swift; `shape` is a non-optional NSArray<NSNumber*>.
    init(wrapping value: ORTValue) throws {
        self.value = value
        do {
            let info = try value.tensorTypeAndShapeInfo()
            self.shape = info.shape.map { $0.intValue }
            self.dtype = try OnnxTensorDtype(from: info.elementType)
        } catch let error as OnnxRuntimeError {
            throw error
        } catch {
            throw OnnxRuntimeError.outputTensor("tensorTypeAndShapeInfo failed: \(error)")
        }
    }

    private func rawData() throws -> Data {
        do {
            return try value.tensorData() as Data
        } catch {
            throw OnnxRuntimeError.outputTensor("tensorData failed: \(error)")
        }
    }

    func floatArray() throws -> [Float] {
        guard dtype == .float else {
            throw OnnxRuntimeError.outputTensor("dtype is \(dtype), not float; refusing to reinterpret")
        }
        let data = try rawData()
        guard data.count % MemoryLayout<Float>.size == 0 else {
            throw OnnxRuntimeError.outputTensor("byte count \(data.count) is not a multiple of Float")
        }
        return data.withUnsafeBytes { buffer in
            Array(buffer.bindMemory(to: Float.self))
        }
    }

    func int64Array() throws -> [Int64] {
        guard dtype == .int64 else {
            throw OnnxRuntimeError.outputTensor("dtype is \(dtype), not int64; refusing to reinterpret")
        }
        let data = try rawData()
        guard data.count % MemoryLayout<Int64>.size == 0 else {
            throw OnnxRuntimeError.outputTensor("byte count \(data.count) is not a multiple of Int64")
        }
        return data.withUnsafeBytes { buffer in
            Array(buffer.bindMemory(to: Int64.self))
        }
    }
}

/// A real inference session. Instances never escape `ModelSessionStore`: the
/// actor loads, runs and releases them so mutable inference handles are only
/// ever touched from one place. `inputNames`/`outputNames` come from the
/// loaded model metadata; `run` always executes the actual graph.
final class OrtInferenceSession {
    private let session: ORTSession
    let modelPath: String
    let inputNames: [String]
    let outputNames: [String]

    init(env: ORTEnv, modelPath: String, intraOpNumThreads: Int? = nil) throws {
        guard FileManager.default.fileExists(atPath: modelPath) else {
            throw OnnxRuntimeError.modelNotFound(modelPath)
        }
        self.modelPath = modelPath
        do {
            let options: ORTSessionOptions
            if let intraOpNumThreads {
                // Explicit thread budget (mirrors the host runner's
                // intra_op=threads / inter_op=1 / sequential execution). The
                // ORT default spins one intra-op thread per core, which
                // oversubscribes a phone that also runs ASR/MT/audio threads.
                options = try ORTSessionOptions()
                try options.setIntraOpNumThreads(Int32(intraOpNumThreads))
                try options.setGraphOptimizationLevel(.all)
            } else {
                options = try ORTSessionOptions()
                try options.setGraphOptimizationLevel(.all)
            }
            self.session = try ORTSession(env: env, modelPath: modelPath, sessionOptions: options)
            self.inputNames = try session.inputNames()
            self.outputNames = try session.outputNames()
        } catch {
            throw OnnxRuntimeError.sessionInit("\(error)")
        }
    }

    /// Run the graph with the given inputs. `outputNames` maps to the official
    /// ObjC signature `runWithInputs:outputNames:runOptions:error:` where the
    /// parameter is an `NSSet<NSString *>` — a Swift `Set<String>` (verified
    /// against objectivec/include/ort_session.h at tag v1.24.2). Every
    /// requested output is produced and dtype-checked on wrap.
    func run(inputs: [String: OnnxTensor], outputNames: Set<String>) throws -> [String: OnnxTensor] {
        let ortInputs = inputs.mapValues { $0.value }
        do {
            let outputs = try session.run(withInputs: ortInputs, outputNames: outputNames, runOptions: nil)
            var result: [String: OnnxTensor] = [:]
            for (name, value) in outputs {
                result[name] = try OnnxTensor(wrapping: value)
            }
            return result
        } catch let error as OnnxRuntimeError {
            throw error
        } catch {
            throw OnnxRuntimeError.inference("\(error)")
        }
    }
}
