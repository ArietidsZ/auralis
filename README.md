<p align="center">
  <img src="docs/brand/mark.svg" width="96" height="96" alt="Auralis">
</p>

<h1 align="center">Auralis</h1>

<p align="center">Offline dialect speech translation on the device: recognize, translate, speak in a cloned voice.</p>

<p align="center"><strong>Developer Preview</strong> · <code>v0.1.0-preview.1</code> · <a href="README.zh-CN.md">中文</a></p>

Auralis is a native Android and iOS app that runs **ASR → MT → TTS** entirely on-device. It does not send audio or text to a network service. Voice cloning uses a local reference recording you provide.

[Download signed Android preview](https://github.com/ArietidsZ/auralis/releases/download/v0.1.0-preview.1/Auralis-v0.1.0-preview.1-arm64-v8a.apk) · [Release files and checksums](https://github.com/ArietidsZ/auralis/releases/tag/v0.1.0-preview.1)

Developer Preview: native clients, source builds, and host/emulator checks. Provision weights separately. Model packages remain `draft`, keeping the app readiness gate closed until qualification finishes.

## What works in this preview

- Shared contracts for dialects and model packages (`shared/`), consumed by Python, Kotlin, and Swift.
- Native Android (Kotlin/Compose) and iOS (SwiftUI) clients.
- Offline pipeline: Qwen3-ASR family → AngelSlim Hy-MT → Qwen3-TTS family.
- Voice reference storage and cloning paths. Missing or empty reference text stays speaker-embedding-only; the app does not invent a transcript.
- One official ONNX Runtime **1.24.2** on each mobile client for ASR and TTS.
- Host and Android emulator runs of real graphs. See [engineering reports](docs/specs/2026-09-05-auralis/reports/).

## Validation status

| Item | Status |
|---|---|
| Shared ASR/MT/TTS manifests | Still **`draft`**. Draft packages never count as app-ready. |
| Physical phones | Not in this release. Host/emulator numbers are engineering checks only. |
| CI | Android / iOS source builds and tests passed; see [release verification](docs/releases/verification-v0.1.0-preview.1.md). |
| Quality, energy, thermal, p95 | Open. |
| TTS integer quantization / CP-only BF16 | Failed the declared voice-quality gates; FP32 remains the default. |
| Tested CoreML code-predictor configuration | Slower than CPU, with numerical differences; excluded from defaults. |
| TTS API2 (unified talker, ICL encoder, streaming vocoder) | Default draft manifest; host and emulator engineering checks. API1 remains supported. |
| GPU / Hy-MT2 roadmap | Pending. See the [earlier roadmap](docs/viaim-parity-refactor-plan.md); this preview does not complete those phase-one targets. |

Model weights are provisioned separately through the pinned manifests. TTS API2 is built from the official checkpoint, then imported with `fetch_model.py --package tts --dest DEST --local-source LOCAL_SOURCE`; it is not a download at a fictional upstream ONNX path.

## App readiness

A package is usable only after:

1. Manifest `status` is `verified` (currently all three are `draft`).
2. Every listed file exists with matching `sizeBytes` and `sha256`.
3. Runtime roles resolve to those files.
4. A real smoke/task check has run.

Missing files show remaining install size.

## Checks (no weights required)

```bash
scripts/doctor                 # environment; missing Xcode/models are blocked
scripts/verify --mode fast     # contracts, Python, Swift core — no model download
```

Exit codes: `0` pass, `1` test/code failure, `2` missing environment/artifacts (blocked), `3` invalid contract, `4` bad arguments.

With models on disk:

```bash
export AURALIS_CACHE="${AURALIS_CACHE:-$HOME/Library/Caches/Auralis}"   # macOS default
# Linux/Android hosts typically use $HOME/.cache/Auralis

python3 convert/manifest_contract.py check
# --dest is required to use $AURALIS_CACHE; the default is <repo>/models
python3 convert/fetch_model.py --package asr --dest "$AURALIS_CACHE/models"
python3 convert/validate_models.py --models-dir "$AURALIS_CACHE/models" --suite SUITE.json
scripts/verify --scope models --models-dir "$AURALIS_CACHE/models" --suite SUITE.json
```

`validate_models.py` exits non-zero on a draft manifest or empty directory. `--suite` is optional; `--scope models` requires `--models-dir`.

Official ASR test WAVs, voice-reference recordings, and captured tensors are **not** in git. After you have them locally:

```bash
python3 scripts/prepare_android_test_assets --samples-dir /path/to/samples
```

Optional captured code-predictor tensors:

```bash
python3 scripts/prepare_android_test_assets --samples-dir /path/to/samples --cp-inputs-dir /path/to/bf16_cp_inputs
```

Reports may cite `$AURALIS_CACHE/...` as local evidence; those trees are unpublished.

## Build

**Android** — NDK **27.3.13750724**, JDK 17, SDK 35. Native libraries first, then Gradle. The native script does not download weights.

```bash
python3 scripts/build_android_native --ndk "$ANDROID_NDK_HOME"
( cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug )
```

**iOS** — full Xcode, iPhoneOS and iPhoneSimulator SDKs. Host/Catalyst typecheck is not an iOS link. See [iOS native build](docs/ios-native-build.md).

```bash
python3 scripts/build_ios_native
python3 scripts/verify --scope ios
```

Native checkouts and artifacts live under `$AURALIS_CACHE`.

## TTS API2 (experimental)

A rebuildable FP32 package (unified talker, reference encoder, streaming vocoder) is documented in:

- [Next TTS package](docs/specs/2026-09-05-auralis/08-tts-next-package.md)
- [API2 package compile](docs/specs/2026-09-05-auralis/reports/tts-api2-package-2026-09-07.md)
- [API2 runtime interface](docs/specs/2026-09-05-auralis/reports/interface-tts-api2-runtime.md)

Shared `tts.json` selects API contract **2** and remains `draft`. The former API1 manifest is retained under `shared/legacy-model-manifests/` for compatibility checks.

## Layout

```text
shared/     dialect catalog + v2 manifests (Python, Kotlin, Swift)
convert/    manifest check, pinned fetch, model validation
scripts/    doctor, verify, native builds
android/    Kotlin client
ios/        Swift client
docs/       build notes, specs, reports
```

## License

Application code: MIT. Models: their upstream licenses. Third-party notices: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Repository: [ArietidsZ/auralis](https://github.com/ArietidsZ/auralis). Planned tag page: [v0.1.0-preview.1](https://github.com/ArietidsZ/auralis/releases/tag/v0.1.0-preview.1). Notes: [docs/releases/v0.1.0-preview.1.md](docs/releases/v0.1.0-preview.1.md).
