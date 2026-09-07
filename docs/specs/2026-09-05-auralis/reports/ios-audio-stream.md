# iOS AudioPlayer 连续流式 PCM

2026-09-07。独占 `AudioPlayer.swift`、`StreamPlaybackOperation.swift`。未改 TtsEngine / Pipeline / Data / `run_host_tests.sh`。

## 接线签名（给 root）

```swift
typealias ChunkSink = @Sendable ([Float]) async throws -> Void
typealias StreamProducer = @MainActor (@escaping ChunkSink) async throws -> Void

func play(audioData: [Float], sampleRate: Double = AudioPlayer.defaultSampleRate) async throws
func playStream(sampleRate: Double = AudioPlayer.defaultSampleRate,
                producer: @escaping StreamProducer) async throws
```

`StreamProducer` 的 sink 明确 `@escaping`，与 `SpeechProducer = @MainActor (@escaping SpeechChunkSink)` 对齐。`play` 走同一 `playStream`。适配器 `{ sink in try await producer(sink) }` 独立 typecheck 通过。

队列：2 个在飞 buffer，单块最多 `4*1920` 样本，更大输入拆块。sink 只在队列满时等待，不在每块播完后才返回。设备完成类型 `.dataPlayedBack`；manual/offline 用 `.dataRendered`（Apple 头文件写明 `.dataPlayedBack` 仅设备路径）。completion **不**调用 `node.stop()`（`AVAudioPlayerNode.h` 死锁警告）。

`isPlaying`：在**首块** `scheduleBuffer`/`play` 之前置 true，不是 `ensureInitialized` 之后、producer 开始之前。chunk 间隙保持 true，直到 producer 返回且在飞 buffer 完成。`release()` 取消 `producerTask`（覆盖 `Task.sleep` / 取消点），并唤醒全部 waiter。

## 检查

- `ios/DialectInterpreterTests/SessionCoreTests/run_host_tests.sh`：**35 passed, 0 failed**（未改该脚本）。
- Adapter typecheck：`playStream { sink in try await producer(sink) }` 无 error。
- 全源 Catalyst typecheck exit 0；AudioPlayer 仅既有 Bluetooth 弃用警告。
- Host manual rendering（无扬声器、无采麦），`~/Library/Caches/Auralis/reports/ios-audio-stream/results.json`：

| id | 结果 |
|---|---|
| cancel-before-register / backpressure / producer-error / identity / finish-once | pass（非设备状态机） |
| play-empty / play-silent | pass，`isPlaying==false` |
| isPlaying-after-first-schedule-not-during-compute | pass；计算期 `playingDuringCompute=false` |
| manual-render-three-chunks | pass；对齐后 maxAbs **0**（前导 4096 样本静音为 offline mixer 延迟） |
| release-cancels-sleeping-producer | pass；`Task.sleep` 中 `release()` → `CancellationError` |
| recover-new-session | pass；maxAbs **0** |

未接真机/iPhoneSDK，不称生产完成。

## 队列满取消死锁（已闭环）

复现（不 pull、不 `release`）：填满 2 个 buffer 后第三块 `reserveSlot` 排队，只 `play.cancel()`。修前 `requestCancellation` 不 resume waiter，`op-repro` 在 “cancelling” 后 8s 仍无返回。

修复仅 `StreamPlaybackOperation`：`requestCancellation` 原子关准入并立即 resume space/idle；`node.stop` 仍在 MainActor join 后且 `activeStream===op`。删 `DispatchQueue` `resumeLater`，unlock 后直接 `continuation.resume`。

修后：op 第三槽 `CancellationError` **0.00048 s**；player 同场景 **0.00014 s**，`queued=true`，`isPlaying=false`。queued / draining / 取消后注册 / 旧 callback→新 session 均 pass。35/35，Catalyst 0。`AudioPlayer.swift` 未改。旧 4 条 target WAV 未动。
