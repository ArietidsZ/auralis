# Release tooling review — CI reproducibility and third-party licensing

Date: 2026-09-07. Scope: first-clean-checkout CI reproducibility audit and
third-party licensing for the v0.1.0-preview.1 release. Files touched by this
lane: `.github/workflows/ci.yml`, `THIRD_PARTY_NOTICES.md`, `licenses/*`,
this report. The shared model manifests, clients, `scripts/verify`, and the
build scripts under `android/` were reviewed read-only; no shared file was
modified.

## 1. What was actually checked

### 1.1 CI workflow shape (`.github/workflows/ci.yml`)

- Android job order satisfies "source-build JNI before Gradle": the job
  installs NDK 27.3.13750724 via sdkmanager, runs
  `python3 scripts/build_android_native --ndk "$ANDROID_HOME/ndk/27.3.13750724"`,
  and only then `python3 scripts/verify --scope android` (which runs
  contracts, Python-light checks, and Gradle `testDebugUnitTest`,
  `assembleDebug`, `lintDebug`).
- iOS job uses a real Xcode runner (`macos-15`) and a real toolchain chain:
  `scripts/build_ios_native` (source-builds sherpa-onnx + llama.cpp into
  iphoneos/iphonesimulator slices, merges an atomic XCFramework) → unsigned
  `xcodebuild -destination "generic/platform=iOS"` Release device link →
  `scripts/verify --scope ios` (SwiftPM core checks, session host tests,
  simulator build via `simctl` selection, and real `xcodebuild test`).
- No fake-green paths found in the chain: `scripts/verify` reports `blocked`
  (exit 2) instead of skipping when tools or artifacts are missing, and
  `scripts/build_ios_native` exits 2 on machines without the iPhoneOS /
  iPhoneSimulator SDKs (rejects Command Line Tools and Catalyst upfront) —
  both verified by reading the scripts, and the CLT-rejection path was
  exercised on this host during the earlier iOS native lane (see
  `docs/specs/2026-09-05-auralis/reports/ios-native-build.md`).
- Repo hygiene for clean checkout: `android/gradle/wrapper/gradle-wrapper.jar`
  committed; JNI `.so` outputs and `.build/ios-native` are gitignored; SwiftPM
  dependency is pinned by `exactVersion` 1.24.2, so no `Package.resolved` is
  required; `compileSdk`/`targetSdk` 35 matches platforms preinstalled on
  `ubuntu-latest`; the pinned NDK install path matches the script's default
  resolution.
- `ios/DialectInterpreter.xcodeproj/project.pbxproj` inspected read-only:
  AppIcon appiconset assets are present (added by the main reviewer), the
  AuralisNative.xcframework file reference points at `../.build/ios-native/`
  (produced by the first CI step), and fileSystemSynchronizedGroups cover the
  Swift sources. No pbxproj change was needed.

### 1.2 Action pins verified against official repositories

Web search was unavailable in this lane, so every pin was verified directly
against the official GitHub repository with `git ls-remote` (2026-09-07):

| Action | Tag | Commit SHA (ls-remote) | Match in ci.yml |
| --- | --- | --- | --- |
| actions/checkout | v4.2.2 | `11bd71901bbe5b1630ceea73d27597364c9af683` (tag object) | yes |
| actions/setup-java | v4 (currently derefs to v4.9.1) | `cf277c60eb25467037889841efdb72551f06f6c3` | yes |
| gradle/actions (setup-gradle) | v4.4.4 | `748248ddd2a24f49513d8f472f81c3a07d4d50e1` (`v4.4.4^{}`) | yes |
| actions/upload-artifact | v4.6.2 | `ea165f8d65b6e75b540449e92b4886f43607fa02` | yes (newly added, see below) |

### 1.3 Upstream revisions and hashes (read-only audit)

- `scripts/build_ios_native` pins sherpa-onnx
  `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e`, llama.cpp
  `1e411d8f5a1e23525fa3265dfb4bd76265465397`, and SHA-256-pins the official
  Apple ORT 1.24.2 archive (`f7100a99…600b54`); the cached local copy of that
  archive re-hashed to the pinned value in this audit.
- `scripts/build_android_ort_unified` (not this lane's file; reviewed
  read-only) pins the same sherpa-onnx commit, SHA-256-pins its source
  tarball and the Maven ORT AAR, and records a provenance manifest. No defect
  to report.
- `scripts/build_android_native` enforces a clean pinned NDK (27.3.13750724)
  and a clean llama.cpp checkout at the pinned SHA before building.

## 2. Changes made (exclusive files only)

1. `.github/workflows/ci.yml`:
   - Header comments corrected to describe the actual job shape (JNI-before-
     Gradle, real Xcode requirements, blocked-means-red semantics) and to
     record the ls-remote verification of all four action SHAs with date.
   - iOS job `timeout-minutes` raised 60 → 120: the job source-builds three
     CMake slices (sherpa + llama per slice) on 3-vCPU M1 runners and resolves
     ORT twice (native build archive + SwiftPM), so 60 minutes risked a
     spurious timeout failure on the first real run. No check semantics
     changed.
   - Both verify steps now run with `--output verify-report/<job>` and upload
     the JSON report as a workflow artifact (`actions/upload-artifact` v4.6.2,
     SHA verified; `if: always()`; `if-no-files-found: error` so a silent
     report loss cannot masquerade as a green run). The report is written
     before verify exits, so failed runs still publish it.
2. `THIRD_PARTY_NOTICES.md` + `licenses/`: new, see section 3.

## 3. Third-party licensing

Method: every license text is taken from the pinned upstream revision itself
(pinned source tree, vendored header, or official release archive in the
local build cache) — nothing is paraphrased or generated:

- sherpa-onnx: Apache-2.0 text from the pinned `917bed95…` source checkout
  (the same tarball CI builds Android from) → `licenses/sherpa-onnx-LICENSE-Apache-2.0.txt`.
- llama.cpp: MIT text (Copyright 2023-2026 The ggml authors) from the pinned
  `1e411d8f…` checkout → `licenses/llama.cpp-LICENSE-MIT.txt`.
- cpp-httplib: MIT text from the pinned llama.cpp vendor tree (it is part of
  the iOS static-archive closure as `libcpp-httplib.a`) →
  `licenses/cpp-httplib-LICENSE-MIT.txt`.
- nlohmann/json: the pinned header declares v3.12.0 with SPDX identifiers
  only, so the MIT text was fetched from the official nlohmann/json tag
  v3.12.0 → `licenses/nlohmann-json-LICENSE-MIT-v3.12.0.txt`.
- ONNX Runtime: MIT text extracted from the official SHA-256-pinned 1.24.2
  Apple archive (used at build time; the SwiftPM package and Maven AAR ship
  the same upstream MIT license; the AAR embeds no separate notice file) →
  `licenses/onnxruntime-LICENSE-MIT.txt`.
- Vendored sherpa-onnx `c-api.h` in the iOS app target carries Xiaomi's
  Apache-2.0 copyright and is covered by the sherpa-onnx notice.

Not licensed MIT: sherpa-onnx (Apache-2.0) and the HY-MT model weights
(Tencent HY Community License) are the two components where a blanket MIT
label would have been wrong; the notices keep them distinct.

Model weights are not bundled and nothing is downloaded by the app at
runtime. `THIRD_PARTY_NOTICES.md` links upstream terms with their
verification source and date: Qwen3-TTS-12Hz-0.6B-Base (Apache-2.0) and
Qwen3-ASR-0.6B (Apache-2.0) verified via the Hugging Face API; the
AngelSlim HY-MT1.5 HF cards declare no license in metadata, so the notice
points at the Tencent HY Community License text in the Tencent-Hunyuan/HY-MT
repository, including its explicit EU/UK/South-Korea exclusion. The MT
GGUF + STQ remap is a derived artifact of a pinned upstream file; its terms
follow the upstream license, which is why no weight is redistributed. The
identity/eval speech pool (LibriSpeech test-clean subset, CC BY 4.0) is
internal-evaluation only and not shipped.

## 4. CI items NOT run in this review (no local execution claimed)

- No GitHub Actions run has executed yet; the first real run happens on the
  private candidate push by the main reviewer.
- No local Gradle build, unit test, lint, or AVD/emulator execution (Android
  runtime lane belongs to the main reviewer); the Android audit is
  script/param/config-level only.
- No iOS device/simulator xcodebuild execution on this host (it has Command
  Line Tools only, and `scripts/build_ios_native` correctly exits 2 there —
  that rejection path, not the build itself, is what was exercised locally
  in the earlier lane). The XCFramework build and xcodebuild test therefore
  remain unverified until the first CI run.
- No Android NDK CMake compile was re-run; the prior lane's successful NDK
  27.3 configuration evidence (`ios-native-build.md`) is referenced instead.
- Action SHAs were verified with `git ls-remote` against official repos, not
  via the GitHub web UI (web search was unavailable in this lane); the
  references are tag-object/dereferenced-commit SHAs exactly as listed.
- Upstream model license metadata was read from official endpoints on
  2026-09-07; re-verification at release time is recommended since card
  metadata can change.

## 5. Open items for the main reviewer

- First GitHub CI run will be the real test of build_ios_native + xcodebuild
  test on `macos-15`; treat a first-run timeout as a signal to profile slice
  timings, not to weaken checks.
- `scripts/build_android_ort_unified` is outside this lane's file scope; it
  was audited read-only and no defect was found, but any change to it should
  preserve the pinned-hash provenance manifest it emits.
- The license-text files under `licenses/` are verbatim pinned upstream
  texts; if any pin is bumped, re-copy the text from the new revision and
  update the tables in `THIRD_PARTY_NOTICES.md` and this report.
