# Android single-ORT native prototype — 2026-09-07

Historical isolated-prototype results. Later production integration, the
resampler extension, final artifact hashes and actual device tests are in
[android-ort-integration.md](android-ort-integration.md).

The isolated arm64 JNI candidate compiles and links against Microsoft's
official Android ORT 1.24.2 AAR. ASR and Java JNI resolve the same versioned
Runtime symbols. Device loading, ASR inference, production TTS graphs, and
both clients in one Android process **have not been run** for this candidate.
The current app dependency, Kotlin loader, vendored libraries, and emulator
were not changed. No Gradle command was run.

## Reproduce

```sh
python3 scripts/build_android_ort_unified \
  --ndk /opt/homebrew/share/android-commandlinetools/ndk/27.3.13750724
```

`--cache` selects an isolated output directory; its default is
`~/Library/Caches/Auralis/android-ort-unified`. `--baseline` builds the upstream
non-TTS JNI surface for the ablation comparison. The script downloads pinned
archives, verifies checksums, restores source files from the archive, builds
with NDK 27.3/API 28 and static libc++, strips the output, and records ELF and
Kotlin-entry-point checks. It does not install or package the candidate.
Unrelated absolute symlinks in upstream Go examples are excluded from source
extraction; the required native files are regular files.

## Provenance

| Artifact | Version/revision | SHA-256 |
| --- | --- | --- |
| Official Maven AAR | `com.microsoft.onnxruntime:onnxruntime-android:1.24.2` | `bc461499a735653dff285a6a3477d28b9cfd119a09c7753eaf003426b577f223` |
| Official sherpa source archive | `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e` | `acf539e930283442c4237b7b23a06ebe3bff10cbc00694a4f09a3580e3c10e9e` |
| AAR `onnxruntime_c_api.h` | `ORT_API_VERSION=24` | `9ed0d7054a4e74249467365b25b415d36f51a44a6349e2a994a1812e4723d1e2` |

The official Maven SHA-1 sidecar is
`345c83b483a3aa4c5d74564ce0a92c7684794c9a` and matches the AAR. Its SHA-256
sidecar returned 404, so the SHA-256 above was computed from the artifact
downloaded over HTTPS and pinned in the script. The POM identifies Microsoft,
MIT licensing, version 1.24.2, and the full Android runtime. Sources:
[Maven artifact directory](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.24.2/),
[official source archive](https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e).

The AAR's C API header is byte-for-byte equal to the official Apple ORT 1.24.2
header used in the [iOS compatibility experiment](ios-native-build.md).
That experiment ran real Qwen3-ASR models through the same sherpa source and
ORT version on macOS. It supports version compatibility, but does not replace
Android runtime/model tests.

## Native and ABI checks

| File | DT_SONAME | Runtime imports | Native minimum API | LOAD alignment |
| --- | --- | --- | --- | --- |
| Candidate `libsherpa-onnx-jni.so` | `libsherpa-onnx-jni.so` | `OrtGetApiBase@VERS_1.24.2`, `OrtSessionOptionsAppendExecutionProvider_Nnapi@VERS_1.24.2` | 28 | All `0x4000` |
| AAR `libonnxruntime.so` | `libonnxruntime.so` | Supplies required `@@VERS_1.24.2` definitions | 24 | All `0x4000` |
| AAR `libonnxruntime4j_jni.so` | Absent in the official artifact | `OrtGetApiBase`, CPU and NNAPI provider append functions, all `@VERS_1.24.2` | 24 | All `0x4000` |

The candidate's DT_NEEDED list is `libonnxruntime.so`, `libandroid.so`,
`liblog.so`, `libm.so`, `libdl.so`, and `libc.so`. There is no renamed Runtime,
shared sherpa core, or shared libc++ dependency. All imported ORT symbols have
matching definitions in the single AAR Runtime. The candidate links with
`--no-undefined`, section garbage collection, hidden C++ symbols, and the
upstream JNI export map. ORT's NNAPI provider symbol remains an upstream
dependency; the application's ASR configuration still selects CPU.

The AAR manifest declares minimum SDK 24. ELF Android identification notes
confirm API 24 for both AAR libraries and API 28 for the candidate. Therefore
this native build does not raise the app's existing minimum SDK 28.

The five existing Kotlin API files match the pinned upstream definitions.
Only `OfflineRecognizer` and `OfflineStream` differ in lazy loading; their
fields, constructors, external methods, argument types, and result types are
unchanged. All **13** declared external methods are present among the
candidate's **15** exported JNI functions. The two additional functions are
upstream `decodeStreams` and `hasOption`; no unrelated JNI class is exported.

As a separate read-only Java API check, `javap -public` compared ORT 1.22.0 and
1.24.2 for `OrtEnvironment`, `OrtSession`, `SessionOptions`, `Result`,
`OnnxTensor`, `OnnxTensorLike`, `OnnxValue`, `TensorInfo`, and `NodeInfo`.
No public declarations were removed; five were added. This is API surface
evidence, not proof of identical TTS numerics or runtime behavior.

## Ablation and artifact sizes

Both variants disable sherpa TTS, diarization, C API, Python, examples,
PortAudio, WebSocket, and command-line binaries. The final candidate additionally
sets the upstream JNI target's source list to only `common.cc`,
`offline-recognizer.cc`, and `offline-stream.cc`. The implementation of those
sources is unchanged. The source edit is explicit in the build script and
hashed in its manifest.

The stripped 1.24.2 baseline JNI is **3,709,152 bytes / 117 exports**. The
stripped candidate is **2,600,088 bytes / 15 exports**, saving **1,109,064 bytes
(29.9%)** while retaining all current Kotlin native entry points. Thus the
extra JNI components are excluded. Further surgery into the upstream model
dispatch implementation was avoided; it would increase maintenance cost.

| arm64 native artifact | Current bytes | Candidate bytes |
| --- | ---: | ---: |
| `libsherpa-onnx-jni.so` | 4,761,536 | 2,600,088 |
| isolated `libonnxrtnsher.so` | 21,684,880 | 0 |
| AAR `libonnxruntime.so` | 18,214,224 (1.22.0) | 25,770,888 (1.24.2) |
| AAR `libonnxruntime4j_jni.so` | 100,600 | 111,976 |
| Total | 44,761,240 | 28,482,952 |

Expected reduction for these uncompressed arm64 libraries is **16,278,288
bytes (15.52 MiB / 36.4%)**. This is not a measured APK download size or memory
reduction; compression, other ABIs, packaging, and process allocation differ.

## Exact candidate files

All paths below are relative to the prototype cache.

| File | SHA-256 |
| --- | --- |
| `candidate/libsherpa-onnx-jni.so` | `991e14030ce9e1d6f3ad342d6c383b955463ed46aa6097fe8a2b35a6142776a3` |
| `ort-1.24.2/jni/arm64-v8a/libonnxruntime.so` | `42f83b3c2370300821942d1f61099689d37bb5e05126fb75849d4cc9dbe356a2` |
| `ort-1.24.2/jni/arm64-v8a/libonnxruntime4j_jni.so` | `64ea10bc6fe15413d9e189d4ac31397b6432620728995a25dd204c54afeba5ea` |

The current vendored sherpa hash observed before integration is
`afe266837d47e352a97b7257c6dfda0b1ee9b0f7b34e7e45b9da3a3119744920`.
The removable isolated Runtime hash is
`071d280168fd95d486fbf360a43a3fd262108034b02f968ee31430357bc64005`.
The final [machine-readable manifest](android-ort-unified.json) includes
symbol and dependency evidence. Full configure/build/readelf output remains
in the cache's `baseline/` and `candidate/` directories. Binary hashes are
specific to this toolchain/build; the source and Maven archive pins are the
portable reproducibility inputs.

## Proposed integration, pending coordinated device validation

1. In the exclusive Android integration window, change the Gradle dependency
   from `onnxruntime-android:1.22.0` to exactly `1.24.2`.
2. Replace only `jniLibs/arm64-v8a/libsherpa-onnx-jni.so` with the candidate
   above, and remove `jniLibs/arm64-v8a/libonnxrtnsher.so`.
3. In `SherpaJni.load()`, change `System.loadLibrary("onnxrtnsher")` to
   `System.loadLibrary("onnxruntime")`. Retain the lazy, synchronized load of
   `sherpa-onnx-jni`. The remaining Kotlin API needs no ABI edits.
4. Let the AAR supply both ORT libraries. Do not copy them into `jniLibs` or
   add `pickFirst`. Inspect the resulting APK for exactly one
   `libonnxruntime.so` and no renamed Runtime, and check the hashes above.
5. Under the same coordinated window, run production ASR and every TTS graph
   in both initialization orders (`ASR → TTS`, `TTS → ASR`), repeated release
   and reload, and session cancellation. Include Cantonese, Mandarin, and
   non-16-kHz audio, plus the TTS voice-cloning and default-voice paths.
   Confirm Java reports ORT 1.24.2 and that both clients can run in one process.
6. Check the **final APK's** ELF and ZIP alignment, then run on a real 16-KB
   device/environment and at the supported API floor. The isolated library
   audit cannot establish app-wide 16-KB support. Android documents both
   checks, including `zipalign -v -c -P 16 4 app.apk`, in its
   [16-KB page-size guide](https://developer.android.com/guide/practices/page-sizes).
7. Only after those results should the old dual-Runtime packaging documentation
   be replaced and this candidate treated as the shipping dependency graph.

No device inference, final APK validation, TTS quality comparison, latency,
peak memory, or lower-API runtime result is claimed by this prototype.
