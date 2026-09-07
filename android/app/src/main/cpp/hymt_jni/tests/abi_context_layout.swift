import Foundation

@_silgen_name("hymt_abi_dump_context")
func hymt_abi_dump_context(_ ctx: UnsafePointer<UnsafePointer<CChar>>?, _ n: Int)

let line = "The weather is nice today."
let boxed = strdup(line)!
defer { free(boxed) }

print("Swift MemoryLayout<UnsafePointer<CChar>>.size=\(MemoryLayout<UnsafePointer<CChar>>.size) stride=\(MemoryLayout<UnsafePointer<CChar>>.stride) alignment=\(MemoryLayout<UnsafePointer<CChar>>.alignment)")
print("Swift MemoryLayout<UnsafePointer<CChar>?>.size=\(MemoryLayout<UnsafePointer<CChar>?>.size) stride=\(MemoryLayout<UnsafePointer<CChar>?>.stride) alignment=\(MemoryLayout<UnsafePointer<CChar>?>.alignment)")
print("Swift MemoryLayout<Optional<UnsafeMutablePointer<CChar>>>.size=\(MemoryLayout<UnsafeMutablePointer<CChar>?>.size)")

var nonOptional: [UnsafePointer<CChar>] = [UnsafePointer(boxed)]
nonOptional.withUnsafeBufferPointer { buf in
    print("nonOptional base=\(String(describing: buf.baseAddress)) count=\(buf.count)")
    hymt_abi_dump_context(buf.baseAddress, buf.count)
}

var optionalArr: [UnsafePointer<CChar>?] = [UnsafePointer(boxed)]
optionalArr.withUnsafeBytes { raw in
    let bytes = raw.prefix(16).map { String(format: "%02x", $0) }.joined(separator: " ")
    print("optionalArr raw16=\(bytes)")
}
optionalArr.withUnsafeBufferPointer { buf in
    print("optionalArr base=\(String(describing: buf.baseAddress)) count=\(buf.count) elemSize=\(MemoryLayout<UnsafePointer<CChar>?>.stride)")
    // Call C as if this buffer were const char* const* — this is the experiment.
    buf.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>.self, capacity: buf.count) { rebound in
        hymt_abi_dump_context(rebound, buf.count)
    }
}

print("ABI_LAYOUT_DONE")
