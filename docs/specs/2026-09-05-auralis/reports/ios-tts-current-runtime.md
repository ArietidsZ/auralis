# 当前 Swift TTS 默认采样真实执行（2026-09-07）

在重采样及重复惩罚修复后，重新编译当前完整生产 Swift TtsEngine，并在 macOS 上运行真实 ONNX 图。中英文两例正常生成 EOS，真实 ASR 回转写的词内容一致；没有回退旧重复惩罚或调整默认采样策略。

## 构建与输入

- 编译生产 `TtsEngine.swift`、`OrtRuntime.swift`、`OnnxModelManager.swift`、`AsrEngine.swift`、`HyMtNativeBridge.swift` 和全部 Data Swift 源；没有使用旧 hostcopy 或模型 stub。
- 链接现成官方 ORT 1.24.2 macOS 静态 archive、已用 ARC 编译的官方 ObjC bindings、真实 Sherpa C API 和 HyMT dylib。复用已恢复缓存，没有下载模型或运行库。
- 中文参考为先前已记录的 say/Tingting 合成录音，经 `afconvert -f WAVE -d LEF32@16000` 转为 **16 kHz Float32**；英文参考为已记录的 say/Samantha **24 kHz Float32**。未采麦、未读取用户私人声音档案。
- 显式走 host engine 路径，不绕过或修改生产 App 的 readiness 状态门。运行使用 temperature=0.9、topK=50、repetitionPenalty=1.05、seed=20260906，均为现有默认参数；线程数 4。harness 的短句安全上限为 384 帧，生产默认 2048 帧未改。两例均提前产生 EOS，没有碰到该上限。

持久证据：`/Users/arietids/Library/Caches/Auralis/reports/ios-tts-current-runtime/`，包括当前 `bundle/current-tts` 可执行文件、完整编译命令、生产源码快照/hash、harness、参考/输出 WAV、原始日志与 JSON。检查时所有编译输入 Swift 源的 mtime 均早于生成的二进制。

## 真实结果

| 用例 | 参考采样率 | 输出 | 合成墙钟时间 | ASR 回转写 |
|---|---:|---:|---:|---|
| 你好，世界。 | 16 kHz | 23 帧 / 1.84 秒 | 9.54 秒 | 你好，世界。 |
| Hello world, this is a real speech synthesis test. | 24 kHz | 38 帧 / 3.04 秒 | 9.76 秒 | Hello world. This is a real speech synthesis test. |

两例 clippingRatio 均为 0，峰值分别 0.4541 / 0.5991。`results.json` 同时保存完整 group-0 token 序列，没有把“音频非空”当作质量通过条件。

ASR 使用官方 Sherpa Qwen3-ASR INT8，真实 runtime 为 sherpa-onnx 1.13.7、git 917bed95、ORT 1.28.1；与 TTS 的官方 ObjC ORT 1.24.2 分开记录。短音频采用 unsegmented 路径，两例均未截断。

- 中文原始 CER/WER：0 / 0。
- 英文原始 CER/WER：**0.0476 / 0.2222**。区别是逗号变句号、this 变 This，不能把原始指标写成 0。
- 另行以 Unicode casefold、只保留字母/数字归一，中文和英文的参考与回转写都完全相等。归一结果单独保存在 `lexical-checks.json`，没有覆盖原始 `asr.json`。

## 失败与取消路径

- `maxFrames=1`：真实执行后抛出 `badOutput("frame budget exhausted before codec EOS; refusing truncated speech")`，未写出截断语音。这是明确的预算耗尽回归，不是伪造输出。
- `speakerEmbedding=nil`：明确拒绝，无默认/零向量声音回退。
- 长句启动 400 ms 后取消：得到 **CancellationError**，从 cancel() 到返回实测 **1.403 秒**。本轮未对具体 native 阶段做跟踪，不能把此结果表述为立即取消或固定每帧响应时间。
- 完成后显式释放引擎；本轮没有重新做 RSS/内存回收量测。

## 复现与范围

`build-command.json` 是实际完整生产源编译命令，`harness.swift` 是实际执行源；`provenance.json` 含生产源、可执行文件和参考音频 hash。产物中的临时路径保留原样，持久副本以相同文件名保存。重跑持久二进制时传入输出目录、`models`、`ref_zh_16k.wav`、`ref_en_24k.wav` 四个参数；输出目录需预先存在。

这两例说明新正确重复惩罚与最终重采样参数在当前 Swift 默认采样下可真实终止并输出可识别内容。不说明所有参考声音/语言/长文本都已通过，不替代此前独立声纹对拍，也不是 iPhone 真机或完整 iOS 链接/性能证据。未修改默认策略、manifest 或质量验证等级。
