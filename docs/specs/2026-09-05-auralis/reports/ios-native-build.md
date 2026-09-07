# Native dependency validation — 2026-09-07

Source-level iOS linking is connected. Device and simulator compilation/linking
remain unverified on this machine because it has Command Line Tools and no
iPhoneOS/iPhoneSimulator SDK. CI now has an actual native build and device link
entry point; no CI execution result is claimed here.

Verified:

- Built sherpa-onnx commit `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e` from official
  source against the official Apple ORT **1.24.2** archive on macOS arm64.
  Disabled sherpa TTS, diarization, PortAudio, JNI, WebSocket, binaries and
  examples. Compiled the app's C wrapper and linked a host test to that build.
- Real Qwen3-ASR int8 inference completed on `cantonese.wav`, `f1_noise.wav`
  (English, 44.1 kHz resampled by sherpa), and `noise2.wav` (Mandarin).
  All successful runs reported sherpa 1.13.7 / ORT 1.24.2 and returned text.
  Example Cantonese output starts `今次寻寻觅觅，终于揾到My Princess`.
  Recognition errors remain in noisy audio; this is compatibility evidence,
  not an accuracy acceptance test.
- Upstream `version.cc` hardcodes reported git SHA `de495057` although the
  verified checkout HEAD is `917bed95...`. Artifact provenance uses Git HEAD,
  not that runtime diagnostic string.
- Built the shared MT core and static target from the pinned llama revision
  after turning example builds off. Metal, BLAS, OpenSSL, subprocess support,
  and CPU host-native tuning were disabled; CPU Accelerate remained enabled.
  Real STQ-remapped GGUF `hymt_smoke` passed translation, UTF-8 rejection,
  language codes, sticky cancellation, release and reload checks.
- Merged the same ASR/MT archive closure planned for iOS into a host static
  archive, linked both wrappers with a single ORT 1.24.2 framework, and ran it.
  It reported both pinned runtimes. `nm` found **zero** `OrtGetApiBase`
  definitions in the merged native archive and **one** in the linked host
  executable. The merge's empty-object warnings are upstream static archive
  warnings, not missing required symbols.
- NDK 27.3 arm64-v8a CMake configuration generated successfully and exposes
  both `hymt_core` and `hymt_jni`. No Gradle command was run in this lane.
- The Xcode project passes `plutil -lint`; the build script parses as Python.
  Running `python3 scripts/build_ios_native` here returned **2** and produced
  no XCFramework, as required for a machine without full Xcode.

One attempted ASR test referenced nonexistent `en.wav` and failed explicitly
with `SherpaOnnxReadWave failed`; it was corrected to the supplied
`f1_noise.wav`. This failed invocation is retained in the diagnostic log and
is not counted as a successful model test.

The combined host MT archive used the host compiler's default deployment
target (27.0); its initial link requested macOS 14 and emitted deployment
warnings. The resulting program was executed only on this host. This does
not establish macOS 14 or iOS compatibility. The iOS script explicitly sets
deployment target 17.0 for every slice.

Simplification decisions supported by these experiments:

- One ORT 1.24.2 instance passed real ASR inference and combined native linking,
  so no second ASR Runtime or dynamic isolation layer is needed.
- Removing sherpa TTS/diarization and llama examples preserved required APIs
  and successful real inference. Their libraries are absent from the package.
- Removing Metal and separate BLAS preserved the existing CPU-only MT path.
  No latency or power improvement is claimed; device benchmarking is pending.
- One merged static XCFramework covers the pinned native dependency closure;
  source module maps keep the two C interfaces separate. No additional SPM
  wrapper, generated framework hierarchy, or custom iOS toolchain is added.

Persistent evidence is under
`~/Library/Caches/Auralis/ios/native-validation-2026-09-07/`: configure/build
logs, real inference logs, merged host archive, and host executables. These
are host artifacts, not distributable iOS products. Build instructions and
official source links are in [ios-native-build.md](../../../ios-native-build.md).
