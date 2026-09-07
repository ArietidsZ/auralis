# Auralis（跨方言同声传译）全面重构方案 —— 证据驱动版

- 日期：2026-08-05
- 方法：6 维度并行 Tavily 互联网检索（2025–2026 一手资料：HF 模型卡、llama.cpp/sherpa-onnx/ONNX 官方文档、Google 开发者文档、arXiv 论文、GitHub issues）+ 逐项交叉代码审计
- 相对既有方案（`docs/comprehensive-refactor-plan.md`）的增量：**以一手外部证据修正了若干被既有审计视作"既定事实"的底层假设**。这是本方案的核心价值。

> 阅读约定：本方案自底向上编排——先定 **产品与模型选型**（最底层，决定一切上层能否成立），再定 **推理/架构**，然后 **Android / 管线 / UX**，最后 **跨平台 / 验证 / CI**。每一条关键判断都标注了研究证据来源（URL）。

---

## 一、执行摘要：三个必须现在承认的底层事实

外部研究对既有内部审计最重要的贡献，是**推翻了三个被当成"既定事实"的假设**。任何重构若继续按原假设推进，都会在错误的地基上盖楼。

### F1 · Qwen3-ASR 无法在端侧真正流式（这是全案最大的技术事实）
- Qwen3-ASR 的官方"streaming"**仅存在于 vLLM（云/GPU）后端**，transformers 后端不支持；官方不提供 ONNX 导出器（HF 模型卡明确 `qwen-asr` 只支持 transformers + vLLM 后端）。(https://huggingface.co/Qwen/Qwen3-ASR-1.7B)
- sherpa-onnx 对 Qwen3-ASR **只有 offline（非流式）识别器**（`OfflineRecognizer.from_qwen3_asr`），线上/流式路径不覆盖该模型。(https://huggingface.co/thieunv/sherpa-onnx-qwen3-asr-1.7B-int8)
- 2026 论文实测：Qwen3-ASR 在**块式（chunked）流式模式下低于实时**（RTFx≈0.49 on 32 核 CPU），且流式 WER 较批处理近乎翻倍（BSF=1.77，10.45% vs 5.90%），结论"不适合边缘部署"。(https://arxiv.org/abs/2604.14493)
- **推论**：既有方案 C3 的"首句 partial p50<1.5s"目标，**不能靠给当前这套 Qwen3-ASR 加"流式 partial"实现**。要么换一个真正流式的前端 ASR 模型，要么接受"Qwen3-ASR 做高质量离线精修 + 流式模型做低延迟草稿"的混合架构，要么把产品延迟目标回调到 3–4.5s。

### F2 · Hy-MT 1.25-bit 是已确认的上游缺陷，且运行时依赖未合并的 PR
- 1.25-bit 是真实格式（Sherry 3:4 稀疏三元量化，llama-quantize 产物）。(https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF)
- 但它**依赖 llama.cpp 未合并的 PR #22836（STQ1_0 内核）**，且仅 ARM NEON，x86 无内核；模型卡明确要求 checkout `pr-22836-stq_0` 分支构建。(https://github.com/ggml-org/llama.cpp/pull/22836)
- **已确认质量问题**：Tencent/AngelSlim issue #306 报告该模型在 Android 上产出错误输出（zh→en 返回中文、5–10s/句、质量差于 4B），issue **开放、无维护者回应**。(https://github.com/Tencent/AngelSlim/issues/306)
- **推论**：项目当前"native JNI llama-like runtime"若未精确实现 STQ1_0 内核，输出注定是乱码——**这是"mis-behavior"的最可能根因，而非模型质量本身**。必须先用 2-bit（574MB）变体做对照实验隔离根因；若 1.25-bit 换正确内核后仍乱码，则弃用 1.25-bit。
- 另：AngelSlim/Hy-MT 是**自定义 License（非 Apache/MIT）**，商用分发前必须审阅。(https://github.com/tencent/AngelSlim)

### F3 · NNAPI 跑不了 INT4，整套"NPU 优先"策略失效；TTS 无法真流式
- NNAPI **不支持 INT4/MatMulNBits**（其算子表只有 QLinearMatMul/QLinearConv，即 INT8）；2026 官方指引对自定义 ONNX 模型建议 **CPU(MLAS/KleidiAI) 或 QNN EP（仅骁龙）**，NNAPI 仅限遗留 Android 10–14 小模型。(https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html, https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
- **推论**：当前 `OnnxModelManager` 的 `detectBestProvider()` 一律选 NNAPI、`createSessionOptions` 空 `addNnapi`——对 INT4 模型实际下令 NNAPI 帮不上忙、被切分回退 CPU。需改为：**portable 走 ORT CPU（升级 to ≥1.22 拿 KleidiAI INT4 内核 28–51% 提升），骁龙走 QNN EP，NNAPI 仅处理前端小 INT8 算子（mel/conv_frontend）**。
- Qwen3-TTS 官方"streaming"**只是模拟**（`non_streaming_mode=False` 仍处理完整输入，官方明言"true character-by-character streaming input is not yet supported"）；~97ms 首包是 **A100 GPU 实测**，不迁移到手机。(https://qwenlm-qwen3-tts.mintlify.app/guides/streaming)
- **推论**：Qwen3-TTS 的 0.6B 自回归在**中端手机上的真实 RTF 没有任何公开基准**；要真流式输出需自研 KV-cache 单步 decode + CodePredictor + vocoder 编排（社区 ONNX 已拆 `talker_prefill/talker_decode/code_predictor/vocoder`）。(https://huggingface.co/romara-labs/Qwen3-TTS-12Hz-0.6B-Base-ONNX)

---

## 二、产品与战略层（最底层：决定"做什么"）

### 2.1 市场空白与定位（研究支持的差异化）
- 主流竞品几乎全云后端：Google Pixel 翻译（仅 Pixel、架构不透明）、Microsoft Translator Auto mode、iFlytek 讯飞同传、Naver Papago、Timekettle X1（$699 硬件，40 在线/仅 13 离线对）。(https://support.google.com/pixelphone/answer/11209263, https://www.microsoft.com/en-us/translator/blog/2020/11/23/auto-mode-is-now-available-on-android-for-one-on-one-conversations, https://voice-translator-review.com/berts/timekettle-x1)
- **Auralis 的真实空白**：全离线 + 方言原生（22 方言）。Google 用 on-device ASR 本身就让 Recorder 互动 +24%，证明"端侧 + 隐私"是用户买点。(https://android-developers.googleblog.com/2024/08/recorder-app-on-pixel-sees-boost-in-engagement-with-gemini-nano.html)
- **建议**：把"全程离线、语音不出设备"作为一等 UI 卖点（对标 Live Transcribe 的"conversations aren't stored on servers"文案）。(https://www.android.com/accessibility/live-transcribe)

### 2.2 延迟目标要重设：分"同传/交传"双模式
- 人类同传耳-嘴间隔（EVS）约 3–5s；听者容忍 ~4–5s；**1.5s 首 partial 是"对话级"的强目标，但放到同传场景是超额**（IWSLT 定义 AL≤1000ms 低 / ≤2000ms 中）。(https://www.isca-archive.org/interspeech_2013/sridhar13_interspeech.pdf, https://iwslt.org/2021/simultaneous)
- 感知阈值：<3s 透明（5/5），3–5s 中等，>7s 用户觉得"机器坏了"；CAI 系统延迟 >3s 准确率明显下降（3s 98.85% → 4s 93%）。(https://blog.palabra.ai/al-speech-translation/how-real-time-language-translators-reduce-latency-the-technical-reality, https://arxiv.org/abs/2201.02792)
- **建议**：产品区分 **同传模式（目标端到端 3–4.5s，与人类同传一致）** 与 **交传模式（先完整句再翻译，质量优先）**。首 partial 1.5s 仅作为对话场景的增强目标，且只能靠"真流式前端 ASR"实现，不能靠当前 Qwen3-ASR。

### 2.3 双语言 transcript UX（对标 Live Transcribe）
- 词随说随现、主/次语言实时切换、双语对话时动态切换语言；上面是权威参考。(https://www.android.com/accessibility/live-transcribe)
- **建议**：transcript 用**活列表 + 说话人分轮 + 每语言分栏**；partial 标记为 provisional，用**就地改写（streaming rewrite）**而非追加 ghost 文本。
- 无障碍：polite live region + `announceForAccessibility` 播报说话人轮次/新 partial；contentDescription 用 Role 语义；最小字号 12–16sp；可缩放字幕框（2–12 行）。(https://developer.android.com/guide/topics/ui/accessibility/apps)

### 2.4 隐私与合规（声音克隆的隐形地雷）
- **Qwen3-TTS 是 Apache-2.0（可商用）**，3s 零样本克隆、10+ 语言、参考 10–15s 最佳、提供参考转写可把说话人相似度 0.75→0.89；这是正确的克隆选型（对比 XTTS-v2=CPML、F5-TTS=CC-BY-NC，均不可商用）。(https://simonwillison.net/2026/Jan/22/qwen3-tts, https://localaimaster.com/blog/local-ai-voice-clone)
- **合规**：声纹属 GDPR Art9 特殊类别生物特征，需显式同意 + Art17 可删除；**EU AI Act Art50 机器可读合成语音标注 2026-08-02 生效（就是本月）**，最高 4% 全球营收罚款。(https://wcr.legal/can-company-use-voice-face-without-consent-ai)
- **建议**：声音档案一次性本地 enroll，**只保留加密后的 embedding，不落盘原始录音**；用 Keystore 硬件密钥 + Jetpack Security `EncryptedFile`（AES256-GCM）加密，`BiometricPrompt + CryptoObject` 门控解密；落 `setUserAuthenticationRequired(true)`。(https://www.davideagostini.com/android/2026-02-22-android-security-biometrics-keystore)

### 2.5 视觉（Quiet Premium 方向已获研究背书）
- 2025–26 趋势：留白即设计工具、克制 1–2 色 + 中性、字体承担层级、低认知负荷；"暗色顶栏 + 浅色工作区"正是 premium 语音应用范式（对标 ElevenLabs ethereal / Apple Premium Cinematic）。(https://creativepool.com/magazine/features/the-year-in-design-the-biggest-design-trends-of-2025.34112)
- 既有方案的 `BrandedControlDark` token 方向正确，落地即可。

---

## 三、技术底座层：模型栈与推理选型（最底层：决定"能不能做"）

> 这是本方案与既有内部审计最大的分叉点。既有方案假设"在当前模型栈上修 bug"；本方案认为**必须先做模型选型决策**，否则后续所有工作建立在将要被替换的地基上。

### 3.1 决策 D1 · ASR 架构（必须现在定，影响全案）
| 选项 | 说明 | 延迟 | 方言能力 | 落地成本 |
|---|---|---|---|---|
| A. 保持 Qwen3-ASR 单模型（现状） | 无法真流式，仅 VAD 整句/块式 | 首句≥说话时长+800ms+VAD | 22 方言最强 | 低 |
| B. 流式前端（sherpa-onnx Zipformer/Nemotron/SenseVoice）+ Qwen3-ASR 精修 | 流式出 partial<1.5s，Qwen3-ASR 出最终高准确句 | 首 partial <1.5s 可达 | 前端需评估方言覆盖（**前端方言准确度是最大未知**） | 中高 |
| C. 纯流式 transducer（Zipformer/Nemotron） | 无 Qwen3-ASR | 最低 | 方言薄弱，需验证 | 中 |

**研究支撑**：sherpa-onnx 在线 ASR 支持 Zipformer transducer / 流式 Paraformer / Nemotron-streaming（后者 0.6B int4 在 CPU 上 RTFx 7.2、0.56s 延迟、WER 8.2%）；这些带 partial + 时间戳 + endpointing。(https://k2-fsa.github.io/sherpa/onnx, https://arxiv.org/abs/2604.14493)
**建议**：**采用 B（混合）**——用 sherpa-onnx 流式 transducer 出低延迟 partial，Qwen3-ASR 做整句精修与 22 方言识别。**先做前端方言准确度 PoC（粤/闽/吴/川）再全量投入**。若前端方言不可接受，回退 C 并重设产品目标为交传。

### 3.2 决策 D2 · MT 运行时与量化（必须先隔离根因）
- **第一步（隔离根因）**：用 1.25-bit 与 2-bit（574MB）两个变体做对照译测。若 1.25-bit 换正确 STQ1_0 内核后仍乱码 → 弃 1.25-bit，用 2-bit 或 Q4_K_M。
- **运行时**：STQ1_0 仅 PR #22836、仅 ARM NEON、未进 mainline。**必须构建 PR 分支**（`checkout pull/22836/head`）并用该分支产出 JNI .so；接受维护一个 fork。(https://github.com/ggml-org/llama.cpp/pull/22836)
- **质量门**：极端 <2-bit 压缩有质量悬崖，Q4_K_M 是社区公认质量/体积均衡点；MT 需用 BLEU/COMET 在目标方言测试集上校准（当前管线无任何 MT 指标）。(https://medium.com/@michael.hannecke/gguf-optimization-a-technical-deep-dive-for-practitioners-ce84c8987944)
- **许可证**：审阅 AngelSlim 自定义 License 决定能否商用分发权重。

### 3.3 决策 D3 · TTS 架构（最高风险环节）
- Qwen3-TTS 0.6B **自回归在安卓上没有公开基准**，真流式需自研 KV-cache 单步 decode 编排（社区 ONNX 已拆 prefill/decode/code_predictor/vocoder，16 codebook）。(https://huggingface.co/romara-labs/Qwen3-TTS-12Hz-0.6B-Base-ONNX)
- sherpa-onnx 已证明可在安卓端侧跑 Kokoro/Piper/VITS/Matcha（Kokoro 旗舰 RTF≈实时，Piper≈5s/min）。(https://llm-onnx) ，但**都不支持声音克隆**。
- **建议**：**两轨并行**—— Track A：修 Qwen3-TTS 自回归（用社区 ONNX split + KV-cache decode，产出克隆音色）；Track B：若手机 RTF 不达标，用 sherpa-onnx 非克隆 TTS（VITS/Piper）做实时兜底，克隆仅作"可选增强"。**先做 Track B 缩短交付风险**，Track A 作为质量增强。

### 3.4 决策 D4 · 执行提供者（EP）策略
- **弃 NNAPI 作为 INT4 主路径**。策略改为：
  - 通用：ORT **CPU EP + KleidiAI**（升级 onnxruntime-android **1.20.0 → ≥1.22**，INT4 token 生成 +28–51%）。(https://developer.arm.com/community/arm-community-blogs/b/servers-and-cloud-computing-blog/posts/accelerate-llm-inference-with-onnx-runtime-on-arm-neoverse-powered-microsoft-cobalt-100)
  - 骁龙：ORT **QNN EP（HTP，原生 INT8/INT4/INT2）**，需 QDQ/context-binary 编译；仅害骁龙，非骁龙回退 CPU。(https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
  - NNAPI：仅用于前端小 INT8 算子（mel/conv_frontend）。
- **线程**：`SetIntraOpNumThreads` 需实测（XNNPACK 建议 1，回退 CPU 阶段可 2–4），避免过订阅。(https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)
- **注意**：ORT issue #23004 记载 INT4/MatMulNBits 在 arm64 上曾比 INT8 慢 ~10x（KleidiAI 或有缓解但数据是服务器 Arm）；**真机实测 INT4 vs INT8 再定**。(https://github.com/microsoft/onnxruntime/issues/23004)

### 3.5 决策 D5 · KV-cache / 编解码子图
- prefill 计算密集型、decode 内存带宽密集型；KV cache 避免 O(n²)。社区 Qwen3-ASR ONNX 已拆 `decoder_init`（prefill）+ `decoder_step`（decode），Qwen3-TTS 拆 `talker_prefill/talker_decode`。(https://huggingface.co/andrewleech/qwen3-asr-1.7b-onnx)
- **建议**：优先用 **onnxruntime-genai** 的 KV-cache/generation loop（自动发现 KV 布局）而非手写 `Run()` 循环，避免 O(n²) 重算 bug 类。(https://github.com/microsoft/onnxruntime-genai)

---

## 四、Android 架构层

### 4.1 决策 D6 · DI：Hilt（默认）
- Google 官方推荐、编译期校验、单 Activity Compose 首选；Koin 仅当团队速度优先（运行时 service-locator，反射开销）。**除非 KMP 共享 iOS 成为硬需求（Hilt 仅 Android），否则用 Hilt**。(https://developer.android.com/training/dependency-injection/hilt-android)
- 消除 `DialectApp.instance` 全局单例；重对象（ONNX session/pipeline/audio）经 Hilt 依赖注入，生命周期解耦（App 级 vs 会话级）。

### 4.2 决策 D7 · 模块化（Gradle 多模块）
按既有方案拆 `:app / :core-inference / :core-audio / :core-pipeline / :data / :ui-designsystem / :ui-screens / :macrobenchmark`，配 `gradle/libs.versions.toml` 统一版本，移除阿里云镜像（改 `~/.gradle/init.gradle.kts`）。

### 4.3 Compose 性能（2026 最佳实践）
- 依赖 **Strong Skipping Mode**（默认开启），不必全量 `@Stable`；transcript 输出用 **LazyColumn + 稳定唯一 key**（非 Column）；流式 partial 用 `derivedStateOf`。(https://developer.android.com/develop/ui/compose/performance/stability/strongskipping, https://developer.android.com/develop/ui/compose/performance/bestpractices)
- **Edge-to-edge**：targetSdk≥35 强制，`enableEdgeToEdge()` + `Scaffold contentWindowInsets / safeDrawing / systemBars`（勿用 statusBars），LazyColumn 末尾留 contentPadding 避免被手势条遮挡。(https://developer.android.com/develop/ui/views/layout/edge-to-edge)

### 4.4 状态模型（UDF）
- `StateFlow<InterpretUiState>`（sealed，覆盖 spec 全部字段 + 复合"边听边播"状态 + recoverable error）；事件用 Channel（一次性）或 replay=0 SharedFlow；`collectAsStateWithLifecycle()` + `repeatOnLifecycle(STARTED)`。(https://developer.android.com/topic/architecture/recommendations, https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- 会话控制器放 ViewModel（`viewModelScope`）+ SavedStateHandle 兜进程死亡；**旋转屏不释放 ONNX session**（修 `MainActivity.onDestroy` 无条件 `releaseAll()` 的 critical bug）。

### 4.5 音频与前台服务
- **抢延迟用 OBOE**（AAudio 封装，低延迟 + exclusive MMAP + 设备原生采样率）而非 AudioRecord/AudioTrack。(https://developer.android.com/games/sdk/oboe/low-latency-audio)
- 连续同传必须是 **`foregroundServiceType="microphone"` 前台服务**（Android 14+ 强制），且只能前台发起；注意 Android 17（API 37）后台音频收紧、target 37 截止 2027-08。(https://developer.android.com/develop/background-work/services/fgs/service-types, https://developer.android.com/about/versions/17/changes/bg-audio)
- TTS 用 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 让其他音频 duck 而非中断。(https://developer.android.com/media/optimize/audio-focus)

### 4.6 模型交付（Play Asset Delivery）
- 大模型用 **on-demand asset pack**（带下载 UX），小 starter 用 install-time；每包 ≤1.5GB、on-demand 累计 ≤30GB。注意 PAD on-demand 与"安装即离线"的张力（离线纯包需另给非 Play 下载路径）。(https://developer.android.com/guide/playcore/asset-delivery)

---

## 五、实时管线与音频安全层

既有内部审计的管线问题（critical）全部成立，本方案增补研究支持的修复方向：

1. **资源释放安全**：`stop()` 改 suspend + `cancelAndJoin`，等各阶段退出再释放原生资源；共享字段 `@Volatile`/`AtomicBoolean`（TTS 阻塞 `track.write` 时释放 = use-after-free crash）。
2. **真流式 partial**：按 D1 选型落地 `AsrPartial` 事件；VAD 改自适应阈值（当前固定 0.02 RMS 非噪声自适应）。
3. **背压与消息匹配**：`utteranceChannel` 改 SUSPEND/大缓冲（语音不可静默丢），仅 synthesis 保留 DROP_OLDEST；事件带稳定 `messageId` 替代 FIFO 匹配（当前 `InterpretViewModel` 的 `pendingMessageIds` FIFO 与丢弃产生数据错配，被丢消息永久卡 `isProcessing=true`——**已确认**）。丢弃时 emit `UtteranceDropped`。
4. **调度器隔离**：推理阶段用 `Dispatchers.Default.limitedParallelism(1)` 专用调度器，按设备分级，避免 ASR/MT/TTS 三路 + 5 个 ONNX 线程池过订阅。
5. **实时音频纪律**：音频回调内不分配/不 I/O/不阻塞；线程序亲和 + `getExclusiveCores()`；Sustained Performance；推理线程与音频回调分离；冷启动预热模型。(https://github.com/libpd/pd-for-android/issues/66)

---

## 六、跨平台与工具链层

### 6.1 决策 D8 · 跨平台策略（KMP 混合）
- **逻辑/推理编排层用 KMP 共享**（KMP core 2023 年稳定，Google 官方背书）。(https://www.kmpship.app/blog/is-kotlin-multiplatform-production-ready-2026)
- **iOS UI 壳保持原生 SwiftUI**（Compose Multiplatform iOS 用 Skia 渲染非原生控件，不适用于深度 AVFoundation/音频会话集成的 premium 语音应用；Swift Export 仍实验性 via Obj-C bridge）。(https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready, https://volpis.com/blog/is-kotlin-multiplatform-production-ready)

### 6.2 决策 D9 · iOS 推理路径
- **不要假设 ONNX Runtime 在 iOS 能上 ANE**；iOS 无独立 ANE EP，只能经 CoreML EP（算子覆盖有缺口，INT4/动态 shape/KV cache 可能被切回 CPU）。(https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html)
- WhisperKit 用 **Core ML + ANE** 实现 0.46s 流式 + 2.2% WER，证明目标是 iPhone 物理可达的——但路径是 Core ML，不是 ONNX。(https://arxiv.org/abs/2507.10860)
- **建议**：iOS 侧准备 **Core ML/MLProgram 转换工具链**（第二套），而非单一 ONNX 产物；CI 加 ONNX→CoreML 转换 + 兼容性检查。

### 6.3 共享契约（单一事实源）
- 方言目录/模型 manifest 用**单一 JSON Schema（或 Protobuf）**，生成 Kotlin(kotlinx.serialization)/Swift(Codable)/Python(pydantic) 绑定，CI 校验绑定不漂移。当前方言列表在 Kotlin/Swift/catalog.json **三处独立硬编码**且 catalog 无人消费——**已确认**。(https://www.mparticle.com/blog/smartype-generate)

### 6.4 模型工具链（convert/）
- **弃手写 export_asr_onnx.py**，改用社区认证路径：sherpa-onnx `OfflineRecognizer.from_qwen3_asr`（+ thieunv int8 导出）或 andrewleech/qwen3-asr-onnx（FP32 编码器 + INT4 decoder，RTF 0.17 / WER 5.16%）。(https://github.com/andrewleech/qwen3-asr-onnx)
- **弃手写 export_tts_onnx.py**，用社区 ONNX split（talker_prefill/decode/code_predictor/vocoder）。(https://huggingface.co/romara-labs/Qwen3-TTS-12Hz-0.6B-Base-ONNX)
- **修 requirements.txt 版本约束**：`qwen-asr>=0.1.0` 不可满足（当前仅 0.0.x）→ 改 `>=0.0.6`；拆分 onnxruntime / onnxruntime-gpu 冲突。
- **validate_models.py 升级**：当前只做 logit/cosine 相似度、甚至无 `session.run` 比对（**已确认**）。升级为任务级保真度门禁：
  - ASR：WER/CER（jiwer）参考 vs 量化 vs 目标设备。(https://github.com/jitsi/jiwer)
  - MT：BLEU/COMET（目标方言测试集）。(https://github.com/google-research/bleurt)
  - TTS/克隆：Speaker Encoder Cosine Similarity（ECAPA2，>0.8 为良）+ MOS。(https://arxiv.org/abs/2505.17589)
  - 每阶段 RTF 预算（ASR partial / MT per-turn / TTS TTFA / 端到端）在**真机**（非服务器 CPU）上测。

---

## 七、验证 / CI / 质量门层

### 7.1 测试保护网
- 修完 critical 后补：UiState 归约单测、ModelRepository manifest 解析/校验失败路径、VoiceProfile WAV round-trip、PipelineOrchestrator 背压/丢弃 instrumented test。当前仅 1 个单测文件 2 用例（**已确认**）。

### 7.2 Macrobenchmark + Baseline Profile
- 建 `:macrobenchmark` 模块，gate 冷启动（timeToFullDisplayMs）+ 滚动 jank + **实时推理延迟 journey（ASR-partial p50 / TTS chunk latency）**；生成聚焦 Baseline Profile（冷启动 -20~48%，但 profile 过大反伤启动，编译代码 ~10x 大，需验证）。(https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview, https://engineering.fb.com/2025/10/01/android/accelerating-our-android-apps-with-baseline-profiles)

### 7.3 CI（GitHub Actions 单仓流水线）
- (a) Android build + lint + unit test（含 `gradle/actions/setup-gradle` 缓存）；
- (b) 模型产物校验 job（checksum/manifest/provenance + 保真度门禁）；
- (c) on-device gate（Macrobenchmark + Baseline Profile 持续生成并 commit，断言首 partial p50<1.5s / 冷启动阈值）；
- (d) 大模型文件缓存避免每轮重下。
- **先决**：CI 需真机（Pixel 等）跑 Macrobenchmark，模拟器 invalid 延迟数据；需决策 runner 规格（GPU 跑模型导出/保真度）。

---

## 八、分阶段路线图（自底向上）

> 每阶段以可测成功标准收口；测试与质量门贯穿。

### P0 · 决策门（0.5–1 周，无代码）
完成 D1–D9 决策，尤其 **D1（ASR 架构）、D2（MT 量化/运行时）、D3（TTS 轨）**。产出：
- 前端流式 ASR 方言准确度 PoC（粤/闽/吴/川）→ 确定 D1。
- Hy-MT 1.25-bit vs 2-bit 对照译测（隔离 STQ1_0 内核根因）→ 确定 D2。
- Qwen3-TTS 0.6B 在真机 RTF 基准（KV-cache decode）→ 确定 D3。
**成功标准**：三个 PoC 有数字，D1/D2/D3 有明确结论。

### P1 · 地基与契约（L，无依赖）
- Monorepo 规范化（`apps/`、`tooling/`、`shared/`、`scripts/`、`config/`）。
- `libs.versions.toml` 统一版本；移除阿里云镜像；清理死配置。
- shared JSON Schema 单一事实源 + 三端代码生成 + CI 漂移检查。
- 升级 onnxruntime-android → ≥1.22（拿 KleidiAI）。
**成功标准**：`scripts/verify --mode full` 退出码 0；契约被两端消费。

### P2 · Android 架构 / DI（L，依赖 P1）
- 引入 Hilt，消除 `DialectApp.instance`；Split 模块；`InterpretationSessionController` + 统一 `InterpretUiState`；修 `MainActivity.onDestroy` 旋转屏 releaseAll bug；`rememberSaveable`。
**成功标准**：旋转屏不丢会话/不释放 session；UIState 表达复合状态。

### P3 · 推理引擎（XL，依赖 P1+P2，) —— 按 P0 决策实施
- ASR：按 D1（流式前端 + Qwen3-ASR 精修，或回退）；KV-cache decode；mel 前端对齐 feature_extractor/CMVN。
- MT：按 D2（正确 STQ1_0 内核或 2-bit/Q4_K_M）；`HyMtRuntime` load 失败降级 ASR+TTS 直通（不再取消全管线）。
- TTS：按 D3（Track B 兜底 + Track A 克隆增强）；自回归 + 多 codebook + KV-cache；移除全零码静音回退。
- EP：按 D4（CPU/KleidiAI 或 QNN）；`validate_models.py` 升级任务级保真度门禁。
**成功标准**：端到端 ASR→MT→TTS 真机产出可听语音与可读翻译；RTF 可度量且达标。

### P4 · 实时管线与音频（L，依赖 P2+P3）
- 资源释放安全（`cancelAndJoin` @Volatile）；真流式 partial；背压/消息匹配修复；调度器隔离；OBOE 低延迟；mic FGS。
**成功标准**：stop/release 不崩溃；首 partial 阈值达标；无静默丢语音。

### P5 · Auralis 产品 UX（L，依赖 P2+P3+P4）
- 品牌落地；Quiet Premium 视觉（BrandedControlDark）；Setup/权限错误恢复；transcript UX 对标 Live Transcribe；隐私（EncryptedFile + 生物门控 + Art50 标注）；无障碍。
**成功标准**：品牌统一 Auralis；顶暗底亮；错误可恢复；无死组件。

### P6 · iOS 对齐（L，依赖 P1+P3，可与 P5 并行）
- iOS 接真实 ONNX Runtime（替换桩）或按 D9 走 Core ML 转换；补 MT 阶段；消费 shared 契约；XCTest。
**成功标准**：iOS 管线含 MT；从 shared catalog 加载方言；无桩实现。

### P7 · 测试 / CI / 性能闭环（P0，贯穿）
- 测试保护网；macrobenchmark + baseline profile；GitHub Actions 流水线 + on-device gate；voice profile 加密。
**成功标准**：`verify --mode full` 在 CI 通过；延迟目标有自动化测量与回归门禁。

---

## 九、Quick Wins（可立即执行）
1. 升级 `onnxruntime-android` 1.20.0 → ≥1.22（INT4 KleidiAI 提升，零架构改动）。
2. 修 `MainActivity.onDestroy` 无条件 `releaseAll()`（旋转屏 critical bug）。
3. `sourceDialect/targetLanguage` 改 `rememberSaveable`（旋转屏丢选择）。
4. `ModelDownloadScreen` error 分支复位 `isExtracting=false` + 重试按钮（解压失败按钮永久禁用）。
5. 修 `requirements.txt` `qwen-asr>=0.1.0` → `>=0.0.6`。
6. 弃用 `detectBestProvider()` 一律 NNAPI（INT4 无效），改 CPU 优先。
7. 删死组件 `DialectSelector.kt`/`StatusIndicator.kt`；清理 proguard 冗余 keep。
8. 修 `InterpretViewModel` FIFO 消息匹配（改 `messageId` 关联）。

---

## 十、需决策的开放问题
1. **D1 ASR**：前端流式模型方言准确度是否达标？不达标是否接受回退 C + 交传模式？
2. **D2 MT**：1.25-bit 换正确内核后是否仍乱码？是否接受 2-bit/Q4_K_M 的体积？
3. **D3 TTS**：Qwen3-TTS 0.6B 真机 RTF 是否≤1.0？不达标是否接受 sherpa-onnx 非克隆 TTS 兜底？
4. **D6 DI**：KMP 共享 iOS 是否硬需求（决定 Hilt vs Koin）？
5. **D8 跨平台**：iOS 是否走 Core ML 第二工具链（成本高）还是等 ONNX CoreML EP 成熟？
6. **后台连续同传**是否产品需求（决定 mic FGS 与 Android 17 WIU 的交互设计）？
7. **CI runner 规格**：是否买 GPU/真机 runner ？模型导出与保真度门禁需要。
8. **AngelSlim 自定义 License** 是否允许商用分发 MT 权重？
9. **EU 目标**：是否面向 EU 用户（决定 Art50 标注是否本月必须落地）？

---

## 附：本方案关键证据来源索引
- Qwen3-ASR 官方 / vLLM 流式：https://huggingface.co/Qwen/Qwen3-ASR-1.7B
- Qwen3-ASR 端侧仅 offline：https://huggingface.co/thieunv/sherpa-onnx-qwen3-asr-1.7B-int8
- 端侧流式 ASR 论文（RTFx 0.49）：https://arxiv.org/abs/2604.14493
- Hy-MT 1.25-bit + STQ1_0：https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF
- STQ1_0 PR #22836：https://github.com/ggml-org/llama.cpp/pull/22836
- Hy-MT Android bug #306：https://github.com/Tencent/AngelSlim/issues/306
- NNAPI 无 INT4：https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html
- QNN EP：https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- KleidiAI INT4 提升：https://developer.arm.com/community/arm-community-blogs/b/servers-and-cloud-computing-blog/posts/accelerate-llm-inference-with-onnx-runtime-on-arm-neoverse-powered-microsoft-cobalt-100
- INT4 慢于 INT8（#23004）：https://github.com/microsoft/onnxruntime/issues/23004
- Qwen3-TTS 流式"模拟"：https://qwenlm-qwen3-tts.mintlify.app/guides/streaming
- Qwen3-TTS ONNX split：https://huggingface.co/romara-labs/Qwen3-TTS-12Hz-0.6B-Base-ONNX
- sherpa-onnx 在线 ASR / TTS：https://k2-fsa.github.io/sherpa/onnx
- 同传延迟（EVS / 感知）：https://www.isca-archive.org/interspeech_2013/sridhar13_interspeech.pdf 、https://arabout/Paraformer 、https://feri
- WhisperKit（Core ML + ANE 0.46s）：https://arxiv.org/abs/2507.10860
- Hilt 官方推荐：https://developer.android.com/training/dependency-injection/hilt-android
- Compose Strong Skipping：https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- Edge-to-edge：https://developer.android.com/develop/ui/views/layout/edge-to-edge
- OBOE 低延迟：https://developer.android.com/games/sdk/oboe/low-latency-audio
- mic FGS（Android 14/17）：https://developer.android.com/develop/background-work/services/fgs/service-types 、https://developer.android.com/about/versions/17/changes/bg-audio
- PAD：https://developer.android.com/guide/playcore/asset-delivery
- Macrobenchmark / Baseline Profile：https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview 、https://engineering.fb.com/2025/10/01/android/accelerating-our-android-apps-with-baseline-profiles
- KMP 生产级：https://www.kmpship.app/blog/is-kotlin-multiplatform-production-ready-2026
- Compose Multiplatform iOS：https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready
- 声音克隆许可证 / 隐私：https://localaimaster.com/blog/local-ai-voice-clone 、https://wcr.legal/can-company-use-voice-face-without-consent-ai
- Android 加密（Keystore/EncryptedFile）：https://www.davideagostini.com/android/2026-02-22-android-security-biometrics-keystore
- jiwer（WER）：https://github.com/jitsi/jiwer