# Android single-ORT integration — 2026-09-07

The production Android app now packages one official ORT **1.24.2** for
native sherpa ASR and Java TTS. Real ASR, MT, speaker extraction, complete TTS,
and one ASR → MT → TTS chain executed on `emulator-5580`. These are emulator
functional results, not physical-phone latency, thermal, or quality approval.
Model manifests remain draft/unverified.

## Runtime and lifecycle changes

- Gradle pins `onnxruntime-android:1.24.2`; the rebuilt offline-ASR JNI replaces
  the old prebuilt library. `libonnxrtnsher.so` is removed. `SherpaJni` loads
  the official Runtime and sherpa JNI lazily. There is no SONAME rewrite or
  `pickFirst`. Both Java/native clients use the same versioned API symbols.
- `SpeechRecognizer.release()` is now suspending. A fair coroutine mutex
  serializes load, decode and disposal; native disposal runs on IO inside
  `NonCancellable`. Every release caller waits for disposal, including
  repeated concurrent releases. The old Main-dependent daemon semantics are
  removed. Decode checks cancellation/release between bounded segments.
- Pipeline engine teardown runs on IO and is awaited before the model lease
  closes and IDLE is published. Capture failure now publishes STOPPING while
  teardown runs, then FAILED after cleanup. This also fixed a previously
  racy test that treated an early FAILED snapshot as proof of disposal.
- Voice-profile sample rate is validated before duration division. Preview
  loading checks the file-size bound before `readBytes()`. Device tests reject
  rate 0/negative/8 kHz and a sparse file larger than `Int.MAX_VALUE` without
  attempting a giant allocation.
- `ModelManifests` now rejects bare NaN/Infinity and other non-JSON primitives
  before schema validation. A lexical number check preserves valid large
  exponents such as `1e400`; quoted strings remain strings. The updated shared
  fixture suite passes.

## Full TTS: observed failure and minimal repair

The first full-TTS test used a float32 WAV reference. The production PCM-only
reader rejected it correctly. Only the test fixture was converted to PCM16;
its sample rate and source voice stayed unchanged.

This first baseline preceded the final sampling corrections described below.
The next run reached real speaker extraction but exhausted the **192 MiB ART
heap** while parsing the tokenizer. Nineteen NPY matrices smaller than the
old 64-MiB mmap threshold occupied **176,163,200 bytes** of heap copies before
vocabulary parsing. The process RSS high-water mark was only about 1.52 GiB;
this was not evidence that the 8-GB emulator lacked physical RAM for TTS.

The repair changes the existing NPY reader's default mmap threshold to zero.
All read-only 2-D matrices use the existing mapped path; the explicit heap
path remains available for tests. No `largeHeap`, extra loader, weight
quantization, or graph change was added. A regression checks small-table row
parity against the heap path and proves backing-file mapping behavior.

With that change, the complete production speaker encoder, prefill, decode,
code predictor and vocoder executed with the ASR recognizer resident:

| Measurement | Result |
| --- | --- |
| Text / real reference | `你好，世界。` / PCM16 conversion of the shared Tingting reference |
| Generated audio | 49,920 samples, 24 kHz, 2.08 s |
| Synthesis time | 11,002 ms (emulator, including loads) |
| Real ASR roundtrip | `你好，世界。`, normalized CER 0 |
| Process `VmHWM` | 3,772,592 KiB ≈ 3.60 GiB |
| Sampled Native Heap RSS (dumpsys) | 3,335,676 KiB peak |
| Sampled Java Heap RSS (dumpsys) | 99,204 KiB peak |
| ART maximum heap | 201,326,592 bytes, unchanged |
| Java heap used at completion | 27,654,512 bytes |

RSS is resident process memory; ART's heap limit is a managed-allocation
budget. `Native Heap` is dumpsys' category and excludes other file mappings.
The sampled category peaks need not occur at the same instant as `VmHWM`.

TTS also now rejects frame-budget exhaustion without codec EOS. The bounded
constructor parameter accepts 1..2048 frames. A **real graph** test with
`maxFrames=1` reaches the expected explicit failure and returns no successful
audio result; it does not attempt 2048 frames or substitute synthetic logits.
The missing `@Test` on the ragged-codebook regression was restored.

## Earlier ASR → MT → TTS baseline

All three engines stayed in the same process. Each stage consumed the previous
stage's actual output; TTS did not receive an unrelated canned sentence.

1. `de.wav` → ASR, **980 ms**:
   `Raptorium Bergbau scheint profitierter als Monroe als Reaktion auf die wirtschaftlichen Ausfälle zu sein.`
2. That text → Hy-MT German-to-Chinese, **1,852 ms**:
   `根据经济衰退的情况，雷波特里姆矿业公司似乎比门罗公司更盈利。`
3. That exact translation → voice-conditioned TTS, **27,155 ms**:
   **172,800 samples / 24 kHz / 7.2 s**.
4. Generated audio → real ASR, **928 ms**:
   `根据经济衰退的情况，雷波特里姆矿业公司似乎比门罗公司更盈利。`

The roundtrip matched the MT output exactly. This confirms this sample's
intelligibility, not an independent assessment of translation or voice quality.
Process `VmHWM` was **4,809,632 KiB ≈ 4.59 GiB**. Sampled Native Heap RSS peaked
at 3,798,336 KiB and Java Heap RSS at 159,200 KiB. The Java heap limit stayed
192 MiB. The measured synthesis RTF is about 3.77, so real-time speed is not
claimed.

## ASR and teardown checks

Fresh-process Java-first and ASR-first tests both report Java ORT **1.24.2**,
execute real German ASR with CER 0, alternate Java/native work, and continue
Java execution after ASR disposal. APK-offset mapping inspection shows the
one Runtime plus Java JNI and sherpa JNI; the renamed Runtime is absent.
The actual speaker encoder runs between ASR decodes and remains usable after
ASR release.

Three real stress cycles start long-audio decode, request two releases from
Main, verify a Main heartbeat within 300 ms while **both releases remain
pending**, then await disposal and perform a queued reload plus real decode.
No daemon or detached cleanup is involved. Controlled JVM gates verify both
normal stop and capture failure keep the model lease during disposal and
prevent a restart from loading early.

The single-runtime device quality run used the existing references and
bounds without changing thresholds after seeing results:

| Sample | CER | Segments | Emulator RTF |
| --- | ---: | ---: | ---: |
| de | 0 | 1 | 0.1379 |
| fast1 (44.1 kHz) | 0.204225 | 1 | 0.2328 |
| noise2 | 0.370968 | 1 | 0.1396 |
| rap1 | 0.161850 | 1 | 0.1637 |
| noise1-en (88 s) | 0.207890 | 6 | 0.1984 |

These timings are from the integrated emulator run. The final repeated run's
raw report is retained separately; timing variation is expected. Unknown ASR
language remains `unknown`; no user hint is presented as detected metadata.

## Build, packaging and provenance

`testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`,
`assembleRelease` (R8 minification/resource shrinking), and `lintDebug` pass.
**141 JVM tests, zero failures, zero skips**, with the real tokenizer directory
provided. The final artifact/native hashes are in
[android-ort-apk-audit.json](android-ort-apk-audit.json).

Both debug and unsigned minified Release APKs contain exactly six arm64
libraries, including exactly one `libonnxruntime.so`. All ELF LOAD alignments
are at least `0x4000`. Both pass Build-Tools 35 `zipalign -c -P 16 4`.
The final MT binary is the sanitizer-fixed build (`70d04be5…63db5`),
and remains 16-KB aligned. Its real translation/cancel/reload smoke passes.

The emulator has API 35, 8 GB configured RAM, four vCPUs, and **4096-byte pages**.
Actual execution on a 16-KB device and at the API floor was not performed.
The minified Release APK was built/audited but not installed or exercised;
device instrumentation used the debug APK. The AAR's native/API floor is 24,
our JNI's is 28, while model manifest platform gates remain conservative.

Old APKs, libraries and previous device reports are preserved under
`~/Library/Caches/Auralis/android-ort-unified/pre-integration/`. The emulator's
6-GB data partition required removing duplicate push staging and temporarily
removing the backed-up MT GGUF while preparing TTS. MT was then restored and
its derived SHA-256 reverified before the full chain. All 35 TTS files were
hashed on-device. Identical prefill/decode data use a disk-only hard link
created through the dedicated emulator's root staging access after run-as
linking was denied. This did not change SELinux policy or reduce ORT's runtime
memory allocations. ASR, MT and the complete TTS bundle remain installed.

Persistent evidence root: `~/Library/Caches/Auralis/android-ort-unified/`:

- `integration-gradle-final.log`, final test XML in the Gradle reports.
- `device-final-evidence/`: final instrumentation logs, JSON and generated WAVs.
- `device-full-tts-true-memory.json` and `device-FullTranslationChainTest-true-memory.json`:
  RSS/high-water marks plus sampled full dumpsys output.
- `art-heap-oom-before-mmap/`: retained failed ART-heap run.
- `device-tts-file-hashes.json`, `restored-mt.sha256`: model identity checks.
- `run_full_tts.py`: retained device experiment driver.

The earlier [prototype report](android-ort-unified.md) describes the isolated
candidate before this integration. The older dual-ORT and speculative
full-TTS-infeasibility statements in `continuation-android-runtime.md` are
historical and superseded by the measured results above.

## Final protocol, DSP, cleanup and candidate experiments

The final reference resampler is a thin JNI call into the already-linked
sherpa C API: 64 zeros, cutoff ratio 0.9568718266 of the smaller Nyquist,
flush=1. It checks finite/nonempty PCM, 8–192-kHz rates, at most 30 seconds,
and a 64-bit-computed LCM no larger than INT32_MAX before calling the pinned
core. Same-rate input is unchanged after validation; output count uses ceil.
This avoids both the old two-point interpolation and native int32 tick
overflow. Device regressions include the 191999→192000 rejection, NaN on a
same-rate input, oversized references, and nonintegral output lengths.
A 48→16-kHz tone check measured 1-kHz passband RMS 0.70710684 and 14-kHz
stopband RMS 1.2844e-7. No second DSP library or Runtime was added.

Sampling now applies repetition penalty once per distinct generated token,
uses the first argmax at temperature=0 or top-k=1, rejects NaN/+Inf/all-masked
logits and invalid controls, and subtracts the finite maximum before
performing temperature scaling. The default temperature remains 0.9.
Old protocol measurements above are retained as historical evidence.

Cancellation is checked at each frame and each codebook. A real generation
was cancelled after the code-predictor session became active: the first
measured cancel/join was 88 ms, with no audio result. The extended regression
also completes a real next turn on the same engine. Prefill, decode (including
code-predictor load failure), and vocoder load/run are now inside stage
try/finally cleanup. Deliberately mismatched binding graphs test prefill and
vocoder failure: each session is removed and closed, then the same engine
completes a real full synthesis without a controller stop.

The corrected-protocol paired device experiment used the same two texts and
references, fresh processes per precision, and a resident ASR recognizer:

| Precision | Chinese synthesis / audio | English synthesis / audio | Process VmHWM |
| --- | --- | --- | ---: |
| FP32 | 18,007 ms / 3.84 s | 20,940 ms / 3.12 s | 4,009,916 KiB |
| INT8 | 8,191 ms / 3.92 s | 6,297 ms / 3.04 s | 2,732,040 KiB |
| INT4 | 6,742 ms / 4.00 s | 5,727 ms / 2.96 s | 2,491,876 KiB |

All six ASR roundtrips matched these two texts (CER 0). This small synthetic
reference set demonstrates execution and intelligibility only. The separate
six-person quality gate **failed both candidates**: INT8 worst paired speaker
similarity drop was −0.11274 against a −0.10 limit; INT4 had an additional
speaker-identity error and median drop about −0.053 against −0.03. Neither
candidate replaced the production FP32 model or manifest. Even the measured
INT4 synthesis RTFs remain above 1 for these cases.

Candidate graph/data hashes were checked on-device, including the additional
code-predictor data file. Testing used separate ContextWrapper model roots.
Large FP32 files were temporarily removed only after verified host backups,
and the finally block removed candidate directories, restored all 35 original
FP32 files, and verified every hash. MT was also restored and verified. The
transaction record is `quantized-device-transaction.json`; raw paired audio
and JSON are under `device-final-evidence/tts_{fp32,int8,int4}_*`.

## Final artifact and chain checkpoint

The final debug APK is byte-identical to the installed APK. Its SHA-256 is
`c631ad3995ed6b0a8e62ae2c1e2e5bf9c6835d57eed01f65c679d221c0963dbc`;
minified unsigned Release is
`fc05a8a5ce34937f3fbf3a22d552242b6689fbd3d113fca896f5d9c00113ac76`.
Native sherpa+resampler SHA-256 is
`64ea14b1016d96c3dde6ceaeadd534122e026559a422a6436e72f710432d9497`;
MT is `70d04be59d273254c8db45ef7423d9582b56e65257b59b62a632a6a11d663db5`.
Both APKs pass final ELF/ZIP 16-KB checks; the audited JSON includes every
library hash and the instrumentation APK hash.

After the final sampling, DSP, cleanup and MT corrections, the real German
chain was repeated: ASR 905 ms, MT 1,810 ms, TTS 25,991 ms for **182,400 samples
at 24 kHz (7.6 s)**, and ASR roundtrip 769 ms. The Chinese MT output and
roundtrip text remained exactly equal to the sentence above. Final chain
VmHWM was 4,801,576 KiB; sampled Native Heap RSS peaked at 3,798,736 KiB and
Java Heap RSS at 170,688 KiB. This is a measured short-sentence result, not a
claim about longer texts or maximum KV-cache sizes.

The final ASR stress additionally queues an idempotent load and an old decode
before release. The no-op load must not reset the published release gate;
the queued old decode is rejected, both releases join, and the subsequent
fresh load decodes correctly. The final new-MT smoke also verifies real
translation, cancellation, idempotent release and reload. Final two-order
Runtime, speaker graph, ASR quality/long-audio, readiness and resampler tests
all pass against this installed artifact.

The final cancellation/recovery run measured **112 ms** from cancellation to
join, returned no audio for the cancelled turn, and completed a real next
turn on the same engine. Prefill/vocoder controlled failures each closed their
session and recovered without whole-controller teardown. Native ORT calls
and session construction remain non-preemptible; this bound was measured
specifically during code prediction.

Latest chain WAV, JSON and final test reports are in
`~/Library/Caches/Auralis/android-ort-unified/protocol-final-device-evidence/`.
Earlier baselines and the paired candidate measurements remain in
`device-final-evidence/`. Final APKs and the audit are in `final-apks/`.
The original FP32 TTS bundle and MT model are restored; no quantized model
selection or manifest promotion remains on the device.
