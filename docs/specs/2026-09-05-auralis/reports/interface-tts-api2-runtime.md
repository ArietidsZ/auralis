# Interface: TtsEngine 公共 API2 外观（冻结交接）

日期 2026-09-07。本文为 root 并行接线 AppContainer/Pipeline 的接口冻结件。
所有签名已通过 `:app:compileDebugKotlin` 与 `:app:testDebugUnitTest`（含 root 冻结的
ModelManifests/ModelRepository/ModelRuntimeProbe/ModelManifestsTest 中央文件）。

## 1. 公共入口（TtsEngine，现有实例即用）

```kotlin
// TtsEngine.kt（com.dialect.interpreter.inference）
suspend fun prepareReference(
    referenceAudio: FloatArray,
    inputSampleRate: Int = REFERENCE_INPUT_SAMPLE_RATE,   // 16000
    referenceText: String? = null,
): TtsApi2Runtime.PreparedReference

suspend fun synthesizePrepared(
    prepared: TtsApi2Runtime.PreparedReference,
    text: String,
    language: String,
    onAudioChunk: (suspend (chunk: FloatArray) -> Unit)? = null,  // 单参数正 PCM
): SynthesisResult
```

- API1 兼容面不变：`load() / synthesize(text, language, speakerEmbedding) /
  extractSpeakerEmbedding(...) / loadSpeakerEncoder() / release()`。
- 门控：设备 `tts/manifest.json` 存在且 `runtime.apiContractVersion == "2"` 才启用；
  否则 `prepareReference/synthesizePrepared` 抛
  `ModelProtocol.UnsupportedModelException`（无 manifest 文件按 API1 处理，不读 assets）。
- `release()` 同时释放内部 API2 runtime（generation 失效）。

## 2. Sink 契约（root 冻结版，已删除 isLast/零长 marker）

- sink 只收**正长度** PCM（owned copy，已裁剪 [-1,1]，24 kHz）。
- **方法正常返回 = 生成完成（EOS）**；随后 caller 等 tail（`AudioPlayer.playStream`
  自身的 drain 逻辑），无任何冗余结束协议。
- chunk 在逐帧生成环内**即时投递**（默认 4 帧 = 7680 样本）；sink 可挂起背压，
  sink 失败/取消中止该回合且不提交 vocoder 状态。
- 失败回合（预算耗尽无 EOS、取消、sink 失败）可能已投递部分音频；已投递不撤回。
- 结果始终携带完整波形（chunks 拼接），遗留端口可继续编译。

## 3. PreparedReference 绑定

- 绑定产生它的 **engine 实例（engineToken）+ loading generation**；
  `release()` 后旧 prepared 被拒（IllegalArgumentException），跨 engine 实例亦被拒。
- 字段：`embedding`（公开 xvector 值，legacy 可直接用于 `synthesize`）、
  `referenceText/referenceTokenIds/referenceCodes/referenceFrames/identity`、
  internal `vocoderWarmState`（ICL，约 5.2 MB 不可变快照）。
- `referenceText == null` ⇒ 显式 xvector-only；绝不伪造转录。
- 每回合从快照复制工作态；失败/取消不污染快照。

## 4. 设备证据（emulator-5580，均 install 后串行运行，证据已拉回主机）

| case | 结果 | 证据 |
|---|---|---|
| binding | ✅ OK（release→reload 拒绝、跨实例拒绝、同实例 live 通过） | `cache/android-api2/api2-binding3.log`、`api2_evidence/api2_binding.json` |
| stream | ✅ OK：maxFrames=5 无 EOS 回合在失败前已投递 7680 样本 chunk（无 isLast 字段） | `api2-stream.log`、`api2_evidence/api2_stream.json` |
| lifecycle | ✅ OK（无 EOS 失败→恢复、取消、sink 失败不污染快照、状态不跨音色） | `api2-lifecycle4.log`、`api2_evidence/api2_lifecycle.json` |
| chunking | ✅ OK：chunk4 vs whole max_abs_diff 1.52e-6 / 1.72e-6（浮点噪声级） | `api2-chunking4.log`、`api2_evidence/api2_chunking.json` |
| main | ✅ OK：2 说话人 × 中/英 × ICL/xvector 共 8 例，ASR 回转 **CER 全部 = 0**，chunks 8–11/回合（逐环即时投递） | `api2-main3.log`、`api2_evidence/api2_main.json` + 8 WAV |

- clean 全链 Gradle：`compileDebugKotlin + testDebugUnitTest + assembleDebug + assembleDebugAndroidTest`
  ✅ BUILD SUCCESSFUL；单元测试 **142 run / 0 failed / 1 skipped**（含 root 冻结中央文件的 21 例新门）。
- API2 bundle 逐文件 sha256 验证；`code_predictor/speaker_encoder` 与 API1 逐字节相同。
- API1 chunk-invariance 测试曾曝出 AVD 200MB ART 堆限制下双 runtime 表驻留 OOM；
  测试已改为串行开 runtime（接口未动）。若 Pipeline 出现双引擎共存场景，建议后续
  把不可变资源表（config/tokenizer/embeddings）改为按 modelsDir 共享只读缓存。

## 5. 设备终态与空间交易（全部已结清）

- ASR 三文件（963 MB）验收期间临时移除后已恢复，逐哈希验证；
- API1 `talker_prefill/decode/vocoder(.data)` 与 MT gguf（e42935e2…）已恢复并逐哈希验证；
  `talker_decode.onnx.data` 以**符号链接**指向同内容 prefill.data（原硬链接需 adb root，
  本轮不可用；ORT 打开路径等价）——偏差已在交易 JSON `restore_note` 记录；
- API2 专有 bundle 已于验收完成后移除（哈希与主机原件记录在案，可随时重装）；
- 恢复后 API1 真图冒烟 `TtsStageCleanupTest` ✅（`api1-restore-smoke.log`）。

## 6. 未跑门（如实交接，preview 不冒充）

- AudioPlayerStreamTest 第 4 用例（AVD 音频 HAL standby-wedge，详见
  `cache/android-api2/audioplayer-stream-final.log` + AudioTrackHeadDiagnostic 诊断表，3/4 通过；
  独占修复尝试均失败，属环境问题，证据在案）；
- 无物理设备验证；`api_e2e` 端到端门未跑；
- 发布材料/签名/版权审计归 root；本轮未操作 git 索引、未操作 GitHub。
