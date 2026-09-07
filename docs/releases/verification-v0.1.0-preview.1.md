# Auralis preview verification

This release is an engineering preview. Physical-phone performance, representative translation and voice quality, energy, thermal stability, and accessibility qualification remain open. All shared model packages remain `draft`.

## Executed checks

| Check | Result and scope |
| --- | --- |
| Android unit tests | 146 passed, no failures or skips when the official tokenizer bundle is supplied. Weight-free CI skips that one optional bundle test. |
| Python | 129 conversion tests ran with no failures and six optional-dependency skips; 14 script tests passed. |
| Swift session harness | 35 passed. The queue-overflow regression also passed six consecutive runs after replacing timer assumptions with explicit worker admission. |
| Shared data/contracts | Python, Kotlin and Swift contract checks, including 21 build-provenance fixtures; Swift core executable passed. |
| GitHub CI | Android native source build, unit tests, app assembly and lint; iOS three-slice native framework, unsigned device link, simulator app/tests and host checks passed. Exact final commit/run is recorded in the release's `verification-summary.json`. |
| Android audio | Four unchanged AudioPlayer tests passed. A separate native threshold comparison passed. |
| Android real pipeline | Full, restart and cancellation cases passed with actual ASR, MT, prepared-reference TTS and muted AudioTrack tail completion. Capture used fixture PCM; explicit repetition exercised the 10-second boundary. |
| TTS API2 state | Chunk-size comparison passed: maximum float differences 1.52e-6 for ICL and 1.72e-6 for xvector. Cancellation/sink-failure recovery and voice isolation reproduced baseline WAV hashes. Budget exhaustion remained an error; normal-budget recovery used a recreated runtime. |

The Android runtime evidence comes from an API 35 arm64 emulator with a 192 MiB managed-heap limit, not a physical handset. Test WAVs, voice references and large model caches are not published.

## Ablations and corrections

- **Talker KV copies removed.** A failed 22,134,800-byte allocation matched one `28 × 8 × 193 × 128` float cache plus the ART array header. ORT's `getFloatBuffer()` copied it onto the managed heap before another array copy and recreation of native inputs. The decoder now retains the owning native result until replacement, then closes it. The persistent managed KV pair at that position falls from 44,269,568 bytes to zero. Explicit 10-second runs passed without raising the heap limit; one in-flight sample showed about 42 MiB managed allocation, not a certified peak.
- **Short-audio startup corrected.** On the same AudioTrack, capacity and default startup threshold were 4384 frames. With 2192 real PCM frames, the head remained zero; lowering only the threshold to one advanced the head to 2192. No padding was added. This supersedes earlier reports attributing the short-play failure to the emulator HAL. API 28–30 use the effective-buffer fallback; vendor-specific limits remain unqualified.
- **Unnecessary model overlap removed from the test.** The lifecycle harness originally held two full TTS instances simultaneously. `lmkd` killed it under native-memory pressure. Serial ownership preserved its assertions and passed. This is separate from the production KV-copy correction.
- **One runtime and one API2 talker retained.** ASR and TTS share ORT 1.24.2 on each mobile client. API2 uses one talker for prefill/decode. Two unused support files, totaling about 12.6 MB, were removed from the package.
- **FP32 retained.** Integer quantization and CP-only BF16 failed paired voice-quality gates. The measured CoreML code-predictor configuration was about three times slower than CPU. Those candidates remain outside defaults; this does not characterize every possible CoreML implementation.

The startup behavior is documented in [Android's AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack#getStartThresholdInFrames()). ORT's copy behavior is visible in the [pinned Java implementation](https://github.com/microsoft/onnxruntime/blob/v1.24.2/java/src/main/java/ai/onnxruntime/OnnxTensor.java).

## Quality evidence

The official API2 package's four host ICL smoke cases had exact ASR round trips and correct identities against a six-person held-out gallery. This small sample does not qualify broad quality.

A fresh Android pipeline sample used the actual speaker-260 reference, confirmed by matching raw-PCM hashes. Its generated Chinese speech ranked speaker 260 first in the independent gallery (cosine 0.3182; margin 0.0955). Raw round-trip CER was **0.1944**: the recognizer wrote Chinese number words where the input used Arabic dates, with punctuation differences. The metric was retained unchanged. This single sample is not a production accuracy score.

## Release integrity

The Android APK is signed with the Auralis release certificate, has 16 KiB ZIP/native load alignment, carries the shared manifests and license texts, and excludes model weights. `SHA256SUMS.txt`, `android-artifact.json`, `verification-summary.json`, the public certificate, and third-party notices accompany it. Signing keys, recordings, heap dumps, model weights and local coordination files are excluded from GitHub.
