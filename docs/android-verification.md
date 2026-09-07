# Android Verification

## Model Packaging

1. Export ASR and TTS ONNX assets:

```bash
cd convert
python export_asr_onnx.py
python export_tts_onnx.py
```

2. Download Hy-MT GGUF into the Android asset pack:

```bash
python download_hymt_gguf.py
```

3. Package the native Hy-MT runtime:

```text
android/app/src/main/jniLibs/arm64-v8a/libhymt_jni.so
```

The app intentionally fails fast if `libhymt_jni.so` is missing, because live
translation must be produced by AngelSlim/Hy-MT1.5-1.8B-1.25bit rather than a
passthrough placeholder.

## Local Checks

```bash
cd android
bash ./gradlew :app:testDebugUnitTest
bash ./gradlew :app:assembleDebug
bash ./gradlew :app:lintDebug
```

## Runtime Targets

- Mic tap visual response: under 100 ms.
- First partial transcript: p50 under 1.5 s, p95 under 3.5 s on target devices.
- Translation cache hit: no native Hy-MT call for the same source/target/context key.
- TTS real-time factor: average under 1.0 for generated speech.
- Active-session UI: p95 frame time under 16.7 ms on flagship devices, under 33 ms on lower-end devices.

## Manual Smoke Test

1. Install a debug APK with ASR, MT, TTS assets extracted.
2. Open the app and verify setup reports all three model groups as ready.
3. Select a source language or dialect and a different target language.
4. Tap the mic, speak one short phrase, and confirm the timeline shows source text, Hy-MT translation, and TTS completion.
5. Repeat the same phrase and confirm MT latency drops through the translation cache.
