# Third-Party Notices

The project code in this repository is licensed under the MIT license in
[`LICENSE`](LICENSE) (Copyright (c) 2026 ArietidsZ). This file covers the
third-party components that are compiled into or fetched by Auralis, the
exact revisions used, and the upstream terms for the machine-learning model
weights.

The full license texts are reproduced in the [`licenses/`](licenses/)
directory. Each text was taken from the pinned upstream revision itself
(source tree, vendored header, or official release archive), not rewritten
or paraphrased.

The upstream [ONNX Runtime third-party notices](licenses/onnxruntime-ThirdPartyNotices-1.24.2.txt)
are also included from the official `v1.24.2` source tag, covering its bundled
dependencies in addition to the top-level MIT license.

## Compiled components

| Component | Pinned revision | License | Ships in | License text |
| --- | --- | --- | --- | --- |
| sherpa-onnx | `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e` (1.13.7) | Apache-2.0 | Android `libsherpa-onnx-jni.so` (built from source by `scripts/build_android_ort_unified`); iOS static archive (built from source by `scripts/build_ios_native`); vendored header `ios/DialectInterpreter/Inference/SherpaOnnx/c-api.h` | [`licenses/sherpa-onnx-LICENSE-Apache-2.0.txt`](licenses/sherpa-onnx-LICENSE-Apache-2.0.txt) |
| llama.cpp (with STQ support) | `1e411d8f5a1e23525fa3265dfb4bd76265465397` | MIT | Android `libhymt_jni.so` (built from source); iOS static archive (`libllama*`, `libggml*`) | [`licenses/llama.cpp-LICENSE-MIT.txt`](licenses/llama.cpp-LICENSE-MIT.txt) |
| cpp-httplib (vendored in llama.cpp) | revision vendored at the llama.cpp pin above | MIT | iOS static archive (`libcpp-httplib.a`), linked via `libhymt_core` | [`licenses/cpp-httplib-LICENSE-MIT.txt`](licenses/cpp-httplib-LICENSE-MIT.txt) |
| nlohmann/json (vendored in llama.cpp) | v3.12.0 (declared in the pinned `vendor/nlohmann/json.hpp`) | MIT | Compiled into the llama.cpp `common` sources on both platforms | [`licenses/nlohmann-json-LICENSE-MIT-v3.12.0.txt`](licenses/nlohmann-json-LICENSE-MIT-v3.12.0.txt) |
| ONNX Runtime C API (Apple) | 1.24.2 | MIT | iOS app via the official `onnxruntime-swift-package-manager` SwiftPM package (exactVersion 1.24.2) and, at build time, the official Apple ORT 1.24.2 archive used to build the native XCFramework | [`licenses/onnxruntime-LICENSE-MIT.txt`](licenses/onnxruntime-LICENSE-MIT.txt) (extracted from the official pinned archive) |
| ONNX Runtime Android (AAR) | `com.microsoft.onnxruntime:onnxruntime-android:1.24.2` (Maven Central) | MIT | Android app dependency; the AAR is fetched by Gradle, not committed | same upstream MIT license as above |
| onnxruntime-swift-package-manager | exactVersion 1.24.2 | MIT | iOS SwiftPM package manifest (wraps the official ORT release) | same upstream MIT license as above |

Notes:

- sherpa-onnx is Copyright (c) 2023 Xiaomi Corporation and distributed under
  the Apache License 2.0; the vendored `c-api.h` header carries the same
  license and the project keeps the pinned revision's file unmodified.
- llama.cpp is Copyright (c) 2023-2026 The ggml authors (MIT). The pinned
  revision vendors `cpp-httplib` (MIT) and `nlohmann/json` (MIT); other
  vendored llama.cpp helpers (miniaudio, stb, subprocess) are not part of the
  compiled closure produced by `scripts/build_ios_native` / the Android JNI
  build and therefore carry no distribution obligation here.
- Android native code is built from source during CI (`scripts/build_android_native`
  → `scripts/build_android_ort_unified`) with pinned SHA-256 hashes on the
  sherpa-onnx source archive and the ORT AAR; iOS native code is built from
  source by `scripts/build_ios_native` with pinned git revisions and an
  SHA-256-pinned ORT archive. See the provenance JSON emitted next to each
  build product for the recorded hashes.

## Android native dependencies

The Android sherpa-onnx build also links the following dependencies. Their
revisions come from the pinned source's CMake dependency declarations; the
license files below are copied unchanged from the downloaded source trees.

| Component | Revision | License and upstream text |
| --- | --- | --- |
| KISS FFT | `febd4caeed32e33ad8b2e0bb5ea77542c40f18ec` | BSD-3-Clause; [copyright notice](licenses/kissfft-COPYING.txt) and [license conditions](licenses/kissfft-LICENSE-BSD-3-Clause.txt), read together |
| kaldi-native-fbank | `1.22.3` | [Apache-2.0](licenses/kaldi-native-fbank-LICENSE-1.22.3.txt) |
| kaldi-decoder | `0.3.0` | [Apache-2.0](licenses/kaldi-decoder-LICENSE-0.3.0.txt) |
| kaldifst | `1.8.0` | [Apache-2.0 and upstream legal notice](licenses/kaldifst-LICENSE-1.8.0.txt) |
| simple-sentencepiece | `0.7` | [Apache-2.0](licenses/simple-sentencepiece-LICENSE-0.7.txt) |
| OpenFst | `1.8.5-2026-07-09` | [Upstream copyright and Apache-2.0 notice](licenses/openfst-COPYING-1.8.5-2026-07-09.txt); [full Apache-2.0 text](licenses/sherpa-onnx-LICENSE-Apache-2.0.txt) |

KISS FFT is Copyright (c) 2003-2010 Mark Borgerding. Its upstream license
conditions use placeholder copyright fields; the accompanying `COPYING`
file supplies the actual attribution. OpenFst's copied notice identifies
Copyright 2005-2026 Google LLC.

The build also uses unmodified Eigen `5.0.1` headers, primarily under MPL-2.0.
The included [ONNX Runtime third-party notices](licenses/onnxruntime-ThirdPartyNotices-1.24.2.txt)
contain Eigen notices and the MPL-2.0 text. The source corresponding to this
Android build is available in the [Eigen 5.0.1 source archive](https://gitlab.com/libeigen/eigen/-/archive/5.0.1/eigen-5.0.1.tar.gz),
including its `COPYING.*` files; this version is distinct from the Eigen
version covered by ONNX Runtime's own dependency inventory.

Android native builds use NDK `27.3.13750724` with `c++_static`. LLVM's
Apache-2.0 license with LLVM exceptions covers these compiler-added runtime
portions. The [LLVM licensing policy](https://www.llvm.org/docs/DeveloperPolicy.html#new-llvm-project-license-framework)
explains the exception for automatically included libc++ and compiler
runtimes; the compiler toolchain itself is not distributed with Auralis.

## Android application libraries

The Android app also compiles the following library families. Versions below
are the app's declared versions or the versions selected by its Compose BOM;
transitive modules in each family use their upstream terms. AndroidX,
Kotlin, coroutines, and serialization declare Apache-2.0 in their published
Maven metadata. The [full Apache-2.0 text](licenses/sherpa-onnx-LICENSE-Apache-2.0.txt)
is already included and applies separately to each such component.

| Library family | Version |
| --- | --- |
| AndroidX Compose UI, graphics, animation, foundation, runtime, and Material icons | BOM `2024.12.01`; modules `1.7.6` |
| AndroidX Compose Material3 | `1.3.1` |
| AndroidX Core | `1.15.0` |
| AndroidX Fragment | `1.8.6` |
| AndroidX Lifecycle | `2.8.7` |
| AndroidX Activity | `1.9.3` |
| AndroidX Navigation | `2.8.5` |
| AndroidX DataStore | `1.1.1` |
| Kotlin standard library | `2.1.0` (Kotlin plugin version) |
| kotlinx.coroutines | `1.9.0` |
| kotlinx.serialization | `1.7.3` |

Google Play Asset Delivery (`asset-delivery` and `asset-delivery-ktx`
`2.2.2`, with `core-common` `2.0.3`) is governed by the
[Play Core SDK Terms of Service](https://developer.android.com/guide/playcore#play-core-software-development-kit-terms-of-service)
and referenced Google APIs terms. These Google SDK components are not
licensed under this project's MIT license. Their official AARs supply
license and third-party notice files, preserved here:

- [Asset Delivery 2.2.2 vendor LICENSE](licenses/google-play-asset-delivery-2.2.2-LICENSE.txt)
  and its [original byte-offset index](licenses/google-play-asset-delivery-2.2.2-third_party_licenses.json).
- [Core Common 2.0.3 vendor LICENSE](licenses/google-play-core-common-2.0.3-LICENSE.txt)
  and its [original byte-offset index](licenses/google-play-core-common-2.0.3-third_party_licenses.json).

In both AARs, `LICENSE` and `third_party_licenses.txt` are byte-for-byte
identical, so one copy serves both names. Every one of the 56 indexed
notice blocks supplied by Asset Delivery KTX `2.2.2` is byte-for-byte
identical to the same-named block in the retained Asset Delivery notice.
Its separate text and index are therefore omitted; use the retained index.
Core Common has three distinct blocks, so its complete vendor notice is
retained. These vendor inventories include upstream dependencies and are
preserved as supplied, not presented as a claim that every listed component
survives the app's release optimizer.

[Notice provenance](reports/release-notice-provenance.json) records official
source URLs, source members, SHA-256 hashes, and the byte-block comparison
supporting this deduplication.

## Model weights

No model weights are bundled in this repository or in the first release.
At runtime the app fetches nothing; weights are provisioned on-device
out-of-band by the operator. The upstream models and their declared terms:

| Model | Upstream | Declared license | Verified against |
| --- | --- | --- | --- |
| Qwen3-TTS-12Hz-0.6B-Base (TTS talker / code predictor / vocoder / speaker encoder source) | https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base | Apache-2.0 | Hugging Face model metadata, 2026-09-07 |
| Qwen3-ASR-0.6B (int8 ASR export distributed with sherpa-onnx) | https://huggingface.co/Qwen/Qwen3-ASR-0.6B (export: https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | Hugging Face model metadata, 2026-09-07 |
| HY-MT1.5-1.8B (MT; 1.25-bit GGUF quantization + STQ remap derived from the pinned upstream artifact) | https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF (base: https://huggingface.co/tencent/HY-MT1.5-1.8B, https://github.com/Tencent-Hunyuan/HY-MT) | Tencent HY Community License Agreement (custom terms; **territory excludes the EU, UK and South Korea**). The AngelSlim HF cards declare no license in their metadata. | `License.txt` at the Tencent-Hunyuan/HY-MT GitHub repository, 2026-09-07 |
| Identity/eval speech pool (internal evaluation only; not shipped) | https://www.openslr.org/12 (LibriSpeech test-clean subset) | CC BY 4.0 | recorded in the internal eval pool manifest |

The Tencent HY Community License is linked, not reproduced, because no
HY-MT1.5 weight is redistributed by this project. Anyone provisioning the MT
weights must review and comply with the upstream license themselves,
including its territorial limitations.

No warranty is offered for any third-party component or model. Each is
provided by its authors under its own license and terms.
