# iOS capture bounds

The released baseline buffered continuous speech until silence. A host reproduction sent 320,000 voiced samples (20 s at 16 kHz): ASR received nothing before silence, then one 364,800-sample utterance (22.8 s including the VAD tail). The required utterance bound is 160,000 samples. See [baseline evidence](capture-boundary-baseline.json).

## Change

`UtteranceSegmenter` emits at most 160,000 samples, including up to 3,200 samples of pre-roll. A single large input can emit several bounded values directly into the existing capacity-two ended-utterance queue. A forced split preserves PCM order and keeps VAD continuity; playback and stop reset partial input. The VAD clock now uses actual sample counts. Non-finite input terminates capture visibly.

`AudioChunkStream` also bounds the earlier microphone callback queue: eight chunks of at most 3,200 samples, or 102,400 bytes of queued PCM payload. On overflow it closes the stream and stops the matching recording. It preserves the contiguous prefix; it does not splice later audio across a gap. A stream belongs to one recording, so a late callback cannot stop or contaminate the next recording.

The pipeline's existing unexpected-stream-end path reports capture failure. Partial, unfinished speech is discarded; complete earlier utterances retain their normal queue semantics. No changes were made to inference sampling, model weights or package readiness.

## Selection experiment

The experiment sends 1,000 synthetic callback chunks while the consumer is stalled. These are observed queue results and derived PCM payload sizes, not process RSS or device throughput.

| Candidate | Retained chunks | PCM payload | Result |
|---|---:|---:|---|
| Unbounded baseline | 1,000 | 12,800,000 B | Memory grows with producer duration |
| Keep newest eight and continue | 8 | 102,400 B | Drops 992 chunks and loses the beginning |
| Keep oldest eight, terminate on overflow | 8 | 102,400 B | Preserves IDs 0–7, rejects ID 8, then ends |

The last candidate is implemented using the native Swift stream. No custom ring-buffer or condition-variable framework is needed. The eight-chunk capacity is an engineering bound, not a value calibrated on physical phones. [Results](capture-buffer-ablation.json).

Swift's pinned source confirms that `bufferingOldest` rejects new input when full, while `bufferingNewest` discards older buffered input: [Swift 6.1.2 AsyncStream](https://github.com/swiftlang/swift/blob/swift-6.1.2-RELEASE/stdlib/public/Concurrency/AsyncStream.swift#L156-L171). The behavior was also executed in the experiment.

## Validation

- Existing 35 host session/playback tests plus 13 new capture regressions: **48 passed, 0 failed**.
- Continuous 20 s speech reaches the actual session-core ASR port as two exact 160,000-sample inputs before silence. This uses a recognition spy, not a real model.
- Tests cover oversized inputs, uneven partitioning, exact PCM order, pre-roll, short pauses, reset, actual-sample VAD timing, invalid PCM, overflow, chunk-length validation, stream lifetime and visible session failure.
- The overflow integration fixture first assumed that two queued slots meant the third send must fail. A waiting iterator legitimately receives one chunk directly. The test now sends four chunks synchronously; production capacity and timing were unchanged.
- Real `AudioRecorder` + `AudioChunkStream` Catalyst typecheck: exit 0, one existing Bluetooth-option deprecation warning.
- Physical microphone/route behavior, whole iOS build and representative ASR quality require subsequent integration/device checks; no such result is claimed here.

Run the host checks from the repository root:

```sh
bash ios/DialectInterpreterTests/SessionCoreTests/run_host_tests.sh
```

Reproduce the buffering-policy experiment:

```sh
swiftc -O -parse-as-library ios/DialectInterpreter/Audio/AudioChunkStream.swift docs/specs/2026-09-08-quality/benchmarks/capture-buffer-ablation.swift -o /tmp/auralis-capture-ablation
/tmp/auralis-capture-ablation
```
