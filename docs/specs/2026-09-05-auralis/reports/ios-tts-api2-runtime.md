# Swift TTS API2 runtime（统一 talker + ICL + 状态 vocoder）

2026-09-07。独占：`ios/DialectInterpreter/Inference/TtsEngine.swift` 与独立 host 检查。未改 Pipeline/UI、中央 Data/manifest/`ModelSessionStore`、Python。shared `tts.json` 仍是 apiContractVersion 1，实验图未晋升。

## 实现（非设备播放）

当前 `TtsEngine.swift` SHA-256=`30a8c895ff913aee45793923a3198239242086824afec15138db5f89ba4bf77c`。

保留 API1 `synthesize(text, language, speakerEmbedding, …)`。API2 仅在注入 `ExperimentalApi2Graphs` 或 shared `apiContractVersion=="2"` 时启用。

公开签名（与 `interface-tts-api2-runtime.md` 对齐；已有方法等价则未另造框架）：

- `prepareReference(referenceAudio:inputSampleRate:referenceText:) throws -> PreparedReference`
  - `referenceText == nil`：xvector，无 codes、无 vocoder warm-up。
  - 非空文本：真实 encoder → group-major codes，并**一次**参考 vocoder 预热；PCM 丢弃。空/空白文本拒绝，不跑 ASR、不造转录。
- `PreparedReference`：引擎创建。外露 `embedding` 兼容旧端口。内部：codes/token IDs、约 5.2 MB **不可变** warm-state、PCM/vocoder 身份。无 profileId 全局 cache。
- `synthesizePrepared(text:language:preparedReference:onAudioChunk:…) throws -> SynthesisResult`
  - 使用调用者给的 snapshot，不按 embedding 数值挑选隐式 reference。
  - `onAudioChunk` 默认 nil：仍返回完整 `SynthesisResult`，Pipeline 可编译。
  - sink 可暂停/抛错；引擎不申请扬声器。

生成：单 `talker` session 同时 prefill（空 past）与 decode；ICL prefix 为官方 `ref[3:-2]` + `target[3:-5]`、16 组 embedding、trailing。状态 vocoder 每 4 个完整 target 帧（正数尾块）交出全部有效 PCM；EOF 不补零。每 turn 拷贝 warm-state 工作副本；失败块不提交；取消/失败不改 prepared 快照。`release()` 丢掉句柄后，旧 prepared 再合成会 `notLoaded`。

实验图（多来源，不是虚构 HF 包）：

| 角色 | 路径 | SHA-256 |
|---|---|---|
| talker | `cache/tts/unified-talker/talker_api2.onnx` | `adc1ae88…` |
| reference_encoder | `cache/tts/icl/reference_encoder.onnx` | `4294aacf…` |
| streaming vocoder | `cache/tts/upstream-fidelity/streaming-onnx/vocoder_streaming.onnx` | `3138ede6…` |

Speaker encoder / code predictor 仍走现有 API1 包角色。采样未改：默认 .9 / topK50 / penalty 1.05，SplitMix64；与 Python numpy PCG64 **不可互换**。

## Host 数值（macOS + ORT 1.24.2，复用 cache/ios，未下载）

证据：`~/Library/Caches/Auralis/reports/ios-tts-api2/`（`results.json`、`runtime.log`、WAV、`bundle/api2-tts`）。输入为已存 soxr 24 kHz PCM，避免把 native 重采样混进 encoder 对拍。

| 检查 | 结果 |
|---|---|
| encoder 121/260 vs `icl/reference-*-codes.npy`.T | 逐值相同；R=106/88 |
| ICL warm-up position | 106 / 88；xvector 无 warm-up |
| 121/260 × 中英 × ICL/xvector 共 8 次 `synthesizePrepared` | 全部 EOS、finite、peak>1e-4、clipping=0 |
| chunk-4 拼接 vs 同次 synth PCM | maxdiff **0** |
| 同 codes 一次 F=T 步 vs chunk-4 | maxdiff **2.88e-6** |
| sink 抛错 / noEOS / 取消 | 失败后同 engine 再合成成功 |
| 两 turn 后 prepared.warmup.position | 仍为 106 |
| `release` 后再 `synthesizePrepared` | `notLoaded("TTS")` |
| 空白 reference text | 拒绝 |

8 次 target 帧（Swift SplitMix64）：ICL 39/34/39/37，xvector 39/34/43/34。与 Python runner 的 37/38/31/31 **不必相同**；长 ICL 浮点微差会改采样路径，未加 heuristic 追旧随机。root 组合 Python 四对 ASR/身份已另报通过，本 host **未跑 ASR/SV**。

Catalyst 全源 typecheck exit 0；仅既有 Bluetooth 弃用警告。完整 Xcode/真机仍 blocked。

## 未接范围

- Pipeline/UI sink 与参考 ASR 文本由 root 接线；不能把本引擎回调说成设备流式播放。
- 未改 shared manifest，未晋升质量门。n=2、单 seed、主机不是实体机。
- API1 默认路径未替换。

## Root 独立复核与实际逐帧交付（2026-09-07）

原交付的 `generateFrames` 先完成全句、之后才分块 vocoder；root 已改为生成循环每 4 帧直接执行状态 vocoder 并调用 sink。剩余正尾块仅在 EOS 后发送。maxFrames=5 的真实图反例先收到 7680 PCM 样本，随后如实报 noEOS，证明回调不再推迟到全句完成。另修 prepared 仅按 vocoder 路径绑定的问题：现在按引擎加载代际绑定，另一 engine、release 后再 load 都拒绝旧快照；准备过程在 await 后复查代际。状态 finite 和 codes 范围检查也补全。

独立真实执行：`cache/reports/ios-tts-api2-progressive/`，23 检查通过；8 条输出 WAV 与上版冻结产物**逐字节相同**。sink 失败、noEOS、取消后同 engine 恢复，旧 prepared 失效检查均已实际运行。本轮并发有负载，耗时不作性能对比。

新评分：`cache/reports/ios-tts-api2/quality/`，8/8 ASR 原始 CER/WER=0；ICL 4/4 held-out 声纹身份正确，xvector 3/4（260 中文归 237）。同 Swift 引擎四对 ICL−xvector 余弦增量 +0.07818/+0.11589/+0.00622/+0.04983，中位 +0.06401，最小 +0.00622，无新增身份错误。小样本成对门通过；n=2、单 seed、host 限制不变。因 8 条 WAV 字节一致，这份评分也对应本次逐帧输出，未把 Python 分数搬来。

并发依据：[Swift 官方迁移指南](https://github.com/swiftlang/swift-migration-guide/blob/main/Guide.docc/DataRaceSafety.md) 明确 await 两侧不保证原子性，因此加载代际必须在挂起后再次确认。
