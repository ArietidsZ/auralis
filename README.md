<p align="center">
  <img src="docs/brand/mark.svg" width="96" height="96" alt="Auralis">
</p>

<h1 align="center">Auralis</h1>

<p align="center">Offline dialect speech translation on the device: recognize, translate, speak in a cloned voice.</p>

<p align="center"><strong>Developer Preview</strong> · <code>v0.1.0-preview.1</code> · <a href="README.zh-CN.md">中文</a></p>

Auralis is a native Android and iOS app that runs **ASR → MT → TTS** entirely on-device. It does not send audio or text to a network service. Voice cloning uses a recorded reference you provide, not a cloud profile.

This developer preview includes native clients, source builds, and host/emulator engineering checks. Model packages remain gated while quality and physical-device validation continue.

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
| Physical phones | Not provided for this release. Emulator/host numbers are not device limits. |
| Full Xcode / iOS device link | Requires complete Xcode and iOS SDKs. Missing tools report **blocked**, not pass. |
| Representative quality, energy, thermal, p95 | Not signed off. |
| TTS integer quantization / CP-only BF16 | Failed the declared voice-quality gates; FP32 remains the default. |
| Tested CoreML code-predictor configuration | Slower than CPU, with numerical differences; excluded from defaults. |
| TTS API2 (unified talker, ICL encoder, streaming vocoder) | Default draft manifest; host and emulator engineering checks. API1 remains supported. |
| GPU / Hy-MT2 roadmap | Pending. See the [earlier roadmap](docs/viaim-parity-refactor-plan.md); this preview does not complete those phase-one targets. |

Model weights are provisioned separately through the pinned manifests. TTS API2 is built from the official checkpoint, then imported with `fetch_model.py --local-source`; it is not a download at a fictional upstream ONNX path.

## App readiness

The app will not pretend a model is installed. A package is usable only after:

1. Manifest `status` is `verified` (currently all three are `draft`).
2. Every listed file exists with matching `sizeBytes` and `sha256`.
3. Runtime roles resolve to those files.
4. A real smoke/task check has run.

Missing files show the size that still needs installing. There is no fake download-complete state.

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
python3 convert/fetch_model.py --package asr   # only files listed in the manifest
python3 convert/validate_models.py --models-dir "$AURALIS_CACHE/models" --suite SUITE.json
scripts/verify --scope models --models-dir "$AURALIS_CACHE/models" --suite SUITE.json
```

`validate_models.py` will not exit 0 on a draft manifest or an empty directory.

## Build

**Android** — NDK **27.3.13750724**, JDK 17, SDK 35. Native libraries first, then Gradle. The native script does not download weights.

```bash
python3 scripts/build_android_native --ndk "$ANDROID_NDK_HOME"
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

**iOS** — full Xcode, iPhoneOS and iPhoneSimulator SDKs. Host/Catalyst typecheck is not an iOS link. See [iOS native build](docs/ios-native-build.md).

```bash
python3 scripts/build_ios_native
python3 scripts/verify --scope ios
```

Cache for native checkouts and artifacts: `$AURALIS_CACHE` (defaults as above). Do not commit that directory.

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

Application code: MIT. Models: their upstream licenses. Third-party notices ship with the release assets (filled in by the publisher).

Repository: [ArietidsZ/auralis](https://github.com/ArietidsZ/auralis). Release notes: [v0.1.0-preview.1](docs/releases/v0.1.0-preview.1.md).
