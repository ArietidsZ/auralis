# hymt_jni — Hy-MT native runtime (C ABI + JNI shim)

Real MT inference for AngelSlim/Hy-MT1.5-1.8B-1.25bit GGUF over a pinned
llama.cpp checkout with the STQ1_0 kernel (PR #22836).

## Files

- `hymt_core.h` / `hymt_core.cpp` — minimal C ABI (load/translate/cancel/
  release/free_string). No JNI or platform types. Used by both the Android JNI
  shim and the iOS/Swift bridge (`ios/DialectInterpreter/Inference/HyMtNativeBridge.swift`).
- `hymt_jni.cpp` — thin JNI shim over `hymt_core`; symbols
  `Java_com_dialect_interpreter_inference_NativeHyMtRuntime_native{Create,Translate,Cancel,Release}`.
- `hymt_tokens.h` — tokenization helpers (negative-return sizing, reviewed).
- `tools/fix_stq_type_id.py` — one-time GGUF compat fix, see below.
- `tests/hymt_smoke.c` — host C smoke (real model).
- `tests/main.swift` + `tests/run_swift_host_smoke.sh` — host Swift smoke
  (real model, exercises the iOS bridge source).

## Pinned runtime

- llama.cpp PR #22836 branch, commit `1e411d8f5a1e23525fa3265dfb4bd76265465397`
  ("ggml-cpu: fix STQ1_0 CI failures"). Fetch:
  `git fetch --depth 1 origin 1e411d8f5a1e23525fa3265dfb4bd76265465397`.
- `hymt_core.cpp` reports the revision via `hymt_runtime_revision()`; the Swift
  engine rejects any other value (`backendMismatch`).

## Host build (macOS arm64, CPU only, Metal off)

```bash
cmake -S android/app/src/main/cpp/hymt_jni -B /private/tmp/auralis-runtime-20260906/build-host \
  -G Ninja -DLLAMA_CPP_DIR=/private/tmp/auralis-runtime-20260906/llama-stq \
  -DHYMT_HOST_BUILD=ON -DGGML_METAL=OFF -DGGML_NATIVE=ON -DCMAKE_BUILD_TYPE=Release
cmake --build /private/tmp/auralis-runtime-20260906/build-host --target hymt_core_dylib hymt_smoke
./build-host/hymt_smoke <model.gguf>
```

## Android build (NDK 27.3.13750724, arm64-v8a)

```bash
NDK r27 defaults to 4KB ELF LOAD alignment. App minSdk is 28, so
ANDROID_PLATFORM must be android-28 (a 29-built .so is not safe on API 28).
Pass ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON; CMakeLists also adds
-Wl,-z,max-page-size=16384. Verify llvm-readelf -lW LOAD Align=0x4000 and
.note.android.ident API word 0x1c.

```bash
NDK=/opt/homebrew/share/android-commandlinetools/ndk/27.3.13750724
cmake -S android/app/src/main/cpp/hymt_jni -B build-android-arm64 -G Ninja \
  -DLLAMA_CPP_DIR=/private/tmp/auralis-runtime-20260906/llama-stq \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DGGML_METAL=OFF -DGGML_NATIVE=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build build-android-arm64 --target hymt_jni
$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip libhymt_jni.so
cp libhymt_jni.so <repo>/android/app/src/main/jniLibs/arm64-v8a/
```

`LLAMA_BUILD_COMMON=ON` + `LLAMA_BUILD_EXAMPLES=ON` are forced by our
CMakeLists because prompt rendering uses upstream's jinja chat-template engine
(`llama-common`). No example/serving binaries are built.

## GGUF quant-id compat (one-time, verified)

The released HF GGUF was written by a pre-merge PR revision whose type enum
placed STQ at id 42; the pinned commit numbers it STQ1_0 = 43 (Q1_0=41,
Q2_0=42 were added upstream in between). The byte layout is identical
(block = 42 B per 256 weights across all PR revisions — verified: 0 offset
mismatches over 354 tensors). `tools/fix_stq_type_id.py` validates offsets and
then rewrites only the 224 4-byte type-id fields:

- original: `93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab`
- fixed:    `e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971`

Independent audit (not from inference): 354 tensors, types `{F32:129, Q6_K:1, id42:224}`;
STQ packed offsets match with 0 mismatches and 0 EOF pad; the same offsets
interpreted as pinned Q2_0 (id 42) mismatch 223 times, first at
`blk.0.attn_k_norm.weight` file 203154464 vs computed 203277344 (the original
loader error). Byte-diff original vs remapped is **224 bytes**, one little-endian
low byte per type field (42→43); data section unchanged. STQ block-tail f16
scales sampled finite and positive. PR commits 5503c4b/5165daa/1e411d8 already
number STQ as 43 — the HF id 42 is an earlier enumerator, not those three
commits. Keep the original hash as the upstream LFS credential; record the
`.stq-remap.json` sidecar as a derived artifact (shared manifest is root-owned).

## Prompt format

Official templates from tencent/HY-MT1.5-1.8B (no system prompt; ZH↔XX uses
the Chinese instruction, others the English one; contextual template when
prior turns exist). Applied with llama.cpp's jinja engine — the legacy
`llama_chat_apply_template` path mangles this template (renders
`<｜hy_User｜>` after the content and drops the assistant marker). Tokenization
uses `add_special=false` because the jinja output already carries the
`<｜hy_begin▁of▁sentence｜>` BOS marker.

## Local graph-size arithmetic fix (2026-09-07)

The pinned upstream computes graph buffer sizes by incrementing an initially null pointer, which fails UBSan. CMake verifies the original `ggml.c` SHA-256 and compiles a build-local copy using `uintptr_t` address arithmetic at that single site. The checkout remains unchanged. The runtime revision API identifies the base commit; the CMake recipe and binary hash identify this local fix. Real-model ASan/UBSan smoke and concurrency tests passed with `halt_on_error=1`; macOS leak detection was unavailable. See `docs/specs/2026-09-05-auralis/reports/mt-graph-size-fix.md` from the repository root for hashes and retained pre-fix failure evidence.
