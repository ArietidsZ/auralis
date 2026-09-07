# iOS native dependencies

Run on macOS with full Xcode, its iPhoneOS and iPhoneSimulator SDKs, Python 3,
CMake 3.22+, Git, and curl:

```sh
python3 scripts/build_ios_native
python3 scripts/verify --scope ios
xcodebuild -project ios/DialectInterpreter.xcodeproj \
  -scheme DialectInterpreter -configuration Release \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

The first command produces `.build/ios-native/AuralisNative.xcframework` for
arm64 devices and arm64/x86_64 simulators. The Xcode app target links that static
XCFramework and compiles `auralis_sherpa_asr.c`. The source module maps expose
`hymt_core` and `AuralisSherpaAsr` to Swift. Host test sources are excluded from
the app target. Re-run the native build after modifying the MT C++ core or
native build settings; it reuses incremental CMake builds.

The default persistent cache is `~/Library/Caches/Auralis`; set `AURALIS_CACHE`
to use another location. `CMAKE_BUILD_PARALLEL_LEVEL` controls compilation
parallelism (default 4). A checkout with the wrong revision or tracked edits
fails rather than being overwritten. Missing Xcode or either iOS SDK exits 2
before downloads or output creation. No host or Catalyst library substitutes
for an iOS slice.

| Dependency | Revision | Linkage |
| --- | --- | --- |
| sherpa-onnx | `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e` (1.13.7) | Static C API, core, and its required speech/tokenizer libraries |
| llama.cpp with STQ | `1e411d8f5a1e23525fa3265dfb4bd76265465397` | Static `hymt_core`, `llama`, common, CPU ggml libraries |
| ONNX Runtime | 1.24.2 | One official SwiftPM `onnxruntime` product, shared by ASR and TTS |

The script verifies Microsoft's Apple archive SHA-256
`f7100a992d2a8135168c8afd831e6a58b465349101982aa58b3e11d36e600b54`, the same checksum
used by the pinned SwiftPM package. sherpa compiles against its headers using
the upstream `SHERPA_ONNXRUNTIME_INCLUDE_DIR` and `SHERPA_ONNXRUNTIME_LIB_DIR`
configuration. The merged native archive does **not** contain ORT. Before
packaging, a symbol check rejects an `_OrtGetApiBase` definition and requires
the ASR and MT entry points. The final app link resolves ORT through SwiftPM.
Do not add the upstream sherpa prebuilt Runtime alongside this product.

The app links libc++ and Accelerate. Native MT currently sets
`n_gpu_layers = 0`; Metal and a separate BLAS backend are disabled while CPU
Accelerate remains enabled. sherpa TTS, diarization, Python, JNI, PortAudio,
WebSocket, examples, and binaries are disabled. llama's chat template common
library remains enabled; its example binaries and OpenSSL are unnecessary.

Native builds use CMake's built-in iOS platform with an explicit SDK,
architecture, deployment target, and static compiler probes. There is no
additional iOS toolchain package or custom dynamic loader. These options are
documented in [CMake's cross-compilation manual](https://cmake.org/cmake/help/latest/manual/cmake-toolchains.7.html#cross-compiling-for-ios-tvos-visionos-or-watchos).
The external Runtime configuration and static archive layout follow
[sherpa's pinned build-ios.sh](https://github.com/k2-fsa/sherpa-onnx/blob/917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e/build-ios.sh)
and [onnxruntime.cmake](https://github.com/k2-fsa/sherpa-onnx/blob/917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e/cmake/onnxruntime.cmake).
The ORT artifact is specified by [Microsoft's 1.24.2 Package.swift](https://github.com/microsoft/onnxruntime-swift-package-manager/blob/1.24.2/Package.swift).

CI builds the native product, links an unsigned Release device app, then runs
the existing iOS build/test verification on a full macOS runner. It downloads
native dependencies, not model weights. A green native package build alone is
not an app-link, simulator-test, model-quality, or device-performance result.

Validation performed on 2026-09-07 is recorded in
[the native build report](specs/2026-09-05-auralis/reports/ios-native-build.md).
