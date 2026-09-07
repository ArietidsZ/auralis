# sherpa-onnx JNI and the shared ONNX Runtime

The application uses one official Microsoft ORT **1.24.2** library for native
ASR and Java TTS. Gradle supplies `onnxruntime-android:1.24.2`, including
`libonnxruntime.so` and `libonnxruntime4j_jni.so`. Neither file is duplicated in
`jniLibs`. `SherpaJni` loads `onnxruntime` then `sherpa-onnx-jni` lazily.

`libsherpa-onnx-jni.so` is built from official sherpa-onnx commit
`917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e` with NDK `27.3.13750724`, API 28,
arm64-v8a and static libc++. The build keeps the upstream offline recognizer,
offline stream and common JNI implementation, plus one checked reference-resampling entry. All 14 Kotlin external methods
remain available; its 16 JNI exports include upstream `decodeStreams` and
`hasOption`. TTS, diarization, PortAudio, WebSocket, Python, CLI and
unrelated JNI entry points are excluded.

Rebuild and audit in a separate cache:

```sh
python3 scripts/build_android_ort_unified --ndk <path-to-NDK-27.3.13750724>
```

The output is `~/Library/Caches/Auralis/android-ort-unified/candidate/`.
The script verifies source/Maven hashes, Kotlin entry points, native minimum
API, SONAME, versioned ORT imports and 16-KB ELF alignment. It never copies
artifacts into the app automatically.

| Shipped arm64 library | SHA-256 |
| --- | --- |
| `libsherpa-onnx-jni.so` | `64ea14b1016d96c3dde6ceaeadd534122e026559a422a6436e72f710432d9497` |
| AAR `libonnxruntime.so` | `42f83b3c2370300821942d1f61099689d37bb5e05126fb75849d4cc9dbe356a2` |
| AAR `libonnxruntime4j_jni.so` | `64ea10bc6fe15413d9e189d4ac31397b6432620728995a25dd204c54afeba5ea` |

ASR and Java JNI both import `OrtGetApiBase@VERS_1.24.2` from the same
`libonnxruntime.so`; provider append symbols also resolve at that version.
There is no SONAME rewrite, renamed Runtime, `pickFirst`, or custom loader.
The former `libonnxrtnsher.so` / ORT 1.27.1 plus Java ORT 1.22 packaging has
been replaced. Its previous evidence remains historical in
`docs/specs/2026-09-05-auralis/reports/continuation-android-runtime.md`.

`SpeechRecognizer.release()` is suspending and completes only after in-flight
native work and disposal finish. A coroutine mutex serializes load/decode/
release, and IO dispatch keeps Main responsive. No daemon owns deferred
cleanup. The pipeline awaits disposal before closing the model lease and
entering IDLE; a new start waits on the lifecycle mutex.

The ASR model still reports an empty language in this pinned implementation;
the engine reports `unknown`, and the pipeline uses automatic ASR language
selection. Input PCM carries its true sample rate. Long audio is split into
bounded segments for the 512-token KV budget. Model verification status is
not promoted by changing the runtime.

Native prototype measurements and provenance:
`docs/specs/2026-09-05-auralis/reports/android-ort-unified.md`.
Integrated APK and emulator results:
`docs/specs/2026-09-05-auralis/reports/android-ort-integration.md`.

Reference resampling calls the existing sherpa C API inside this same library:
64 zeros, cutoff = `0.9568718266 * 0.5 * min(inputRate, outputRate)`, flush=1.
It rejects empty/non-finite PCM, rates outside 8–192 kHz, references over 30 s,
and rate pairs whose LCM exceeds INT32_MAX (the pinned core's tick type).
Same-rate input is unchanged after validation; output length is the ceiling
of the sample-count ratio. No second DSP library is packaged. The C API is
linked statically and its unused exports are hidden/removed.
