# iOS 参考 ASR → API2 prepare → 流式播放 联合验收

2026-09-07。播放器独占修复已收口。TtsEngine / Pipeline / Data / Python 只读。实验包仍 draft，非生产/物理性能门。v0.1.0-preview.1 公开发布由 root 按 `release-brief.md` 处理。

## 播放器取消（已修）

旧 `playStream` 在 `withTaskCancellationHandler` 的 `do` 外先 `Task.checkCancellation()`，取消时可能不 join 子 producer；`onCancel` 里异步 `node.stop()` 可能打到下一 session。

现行为：`onCancel` 只 `work.cancel()` + 标记 op；任何失败路径 `await work.result` 再在 **当前** `activeStream === op` 时 `node.stop()`。后续 session 用新 generation。

Host：cancel-after-first-chunk 后 **0.082 s** join，`isPlaying==false`；随后新 session 仍能合成（target 21120 样本）。`gotChunk` 观测为 false（schedule 后、sink 回调前取消），不否定 join。

`isPlaying` 只在首块 schedule/play 前置 true。`release()` 取消 `producerTask`。

## 联合 host（真实 adapter，不造参考文本）

生产 `AsrRecognitionAdapter` + `TtsSynthesisAdapter.load()`：VoiceProfile 16 kHz PCM16 → 已缓存 Qwen ASR 转写 → `prepareReference`。`readiness=true` 仅该实验包。`synthesizeStream` → `playStream` + manual render。无扬声器、无采麦。

| 步骤 | 结果 |
|---|---|
| prepare 121 / 260 | pass（真实 ASR 非空后才 ICL） |
| 121 en / zh target | 67200 / 67200 样本 |
| 260 en / zh target | 69120 / 82560 样本 |
| 取消 join | 0.082 s |
| 核心 35 tests | 35 passed |
| Catalyst typecheck | exit 0 |

Manual render 相对 target **未**逐样本对齐：host 在 `waitUntilIdle` 期间过量 pull，render 文件含长静音/重复，maxAbs 不能当质量分。target WAV 才是本轮可交评分的合成产物。勿用旧 8 条 WAV 代替这些新输出。

## 可公开 hash

播放器：

- `AudioPlayer.swift` SHA-256 `1c1c7925c4cc655629e4196812b73dee96b61c34fba3baa4123bc32b66ec892e`
- `StreamPlaybackOperation.swift` SHA-256 `4625d8ad3800537c4384e1dd5cbe7ad10151c98bd18fe8b3676c0bac52af053a`

本次 16 kHz 档案 + 参考 ASR 后的 **target** PCM16（24 kHz）：

| 文件 | SHA-256 |
|---|---|
| 121-english-target.wav | `888a2701bacd347b9a737b0cd95615aa1c4cea1fd2a41330dfe086b2d7d56b31` |
| 121-chinese-target.wav | `7a3bf3e1d34210f022bc259e332b120f029d00019f10d492c8ad8d2309cc97e8` |
| 260-english-target.wav | `963905837298900800b9e31851a2d47dece119fd213c1fec5d4859c22e36d5a9` |
| 260-chinese-target.wav | `d709c6ca3f231068ca63f1e189984458afee54cd2a1b5fd6494594a58f95112e` |

签名：`playStream(sampleRate:producer: @escaping StreamProducer)`，`StreamProducer = @MainActor (@escaping ChunkSink) async throws -> Void`。

## 剩余

- 本 lane 未对上述 4 条新 WAV 再跑 ASR/SV（交 root 评分）。
- Manual render 捕获需另收紧，不能当 bit-exact 播放证据。
- 无实体机、无 iPhoneSDK 链接；preview 不是物理上限。
- 私有 artifact cache 不入库。

## Root 独立评分

2026-09-07 对上述4条新target WAV逐一SHA核对后重新执行：ASR4/4原始CER/WER=0；held-out六人ECAPA分类4/4正确。own cosine按121-en/121-zh/260-en/260-zh为0.53096/0.32290/0.39732/0.31992；不引用旧8条，不作大样本或真机质量结论。证据为同目录quality/{asr,identity,results}.json，评分源码已冻结。

后续发布复核又用“不pull、不release、2个buffer满+第三块排队、只cancel”复现取消死锁。最终StreamPlaybackOperation在requestCancellation时立即唤醒space/idle waiter，移除无必要DispatchQueue层；AudioPlayer仍join后仅stop当前session。此修复不改变4条target音频。
