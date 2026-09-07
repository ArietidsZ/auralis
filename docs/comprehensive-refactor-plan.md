# 跨方言传译项目全方位重构方案

- 日期：2026-08-04
- 方法：6 维度并行代码深审 → 逐维度对抗式核实（读真实代码确认/驳回）→ 首席架构师综合
- 范围：Android（Kotlin/Compose/ONNX）、iOS（SwiftUI）、Python 模型工具链（convert/）、仓库与构建地基、跨平台契约、数据/安全/测试/CI
- 审计规模：13 个代理、326 次工具调用、6 维度 65 项发现（64 项确认、1 项驳回）

---

## 一、执行摘要

**现状定性：这是一份"精修原型"，而非"世界级应用"。** 工程骨架（ASR→MT→TTS 管线、并发背压、模型完整性入口、Compose UI 四屏）已搭起，`assembleDebug`/`lintDebug` 可过，但核心产品能力在多个关键环节**实际不可用或结构性不达标**，且两份已批准设计 spec（Phase 1 monorepo 规范化、Phase 2 Auralis premium overhaul）**基本未落地**。

审计揭示 **6 项 critical 缺陷直接阻断核心能力**：

1. **Hy-MT native runtime（`libhymt_jni.so`）未打包** → MT 阶段必抛异常，`coroutineScope` 取消整条流水线，端到端会话无法跑通（`jniLibs/` 仅 README；`NativeHyMtRuntime.kt:17-21`；`PipelineOrchestrator.kt:156-160`）。
2. **TTS talker LM 为单次前向而非自回归**，多 codebook 解析错误并以**全零码回退产出静音**（`TtsEngine.kt:358-363,404-415,380-394`）。
3. **ASR/TTS 全程批处理、无流式 partial** → "首句 partial p50<1.5s" 目标结构性不可达（`AsrEngine.kt:146-163`；`PipelineOrchestrator.kt:191-250`）。
4. **ASR 解码器无 KV cache**，自回归解码 O(n²)，256 步在中端设备极易突破 p95 3.5s（`AsrEngine.kt:426-467`；`export_asr_onnx.py:80-97`）。
5. **iOS 管线为 ASR→TTS 直连，完全缺失 MT**（`PipelineOrchestrator.swift:3,168-185`）。
6. **iOS ONNX Runtime 为桩实现**，`OrtSession.run()` 返回空数组，下游全走零值回退（`OnnxModelManager.swift:51-56`）。

**两份 spec 的共同盲区**：均未覆盖 native runtime 打包、EP/量化策略、流式 ASR、模型保真度校准、内存压力响应、安全加固与 CI 落地——本方案对这些显式补强。

**总策略：地基优先、自下而上七阶段推进**——先固定仓库/契约/构建地基与 DI 骨架，再从最底层修复推理引擎 critical 缺陷与管线并发安全，然后自下而上重塑实时管线与产品 UX，最后 iOS 对齐与质量门闭环。每阶段以可测量成功标准收口；测试与质量门作为贯穿性约束而非末阶段补充。

---

## 二、审计结论概览

| 维度 | 审计 | 确认 | Critical | High | Medium | Low |
|---|---:|---:|---:|---:|---:|---:|
| 产品设计 / 用户体验 | 11 | 11 | 1 | 3 | 5 | 2 |
| Android 架构 / DI / 构建 | 8 | 8 | 0 | 2 | 4 | 2 |
| 端侧推理 / ML 引擎 | 12 | 12 | 2 | 4 | 4 | 2 |
| 实时管线 / 音频 | 13 | 13 | 1 | 3 | 5 | 4 |
| iOS 对齐 / 跨平台契约 / 工具链 | 10 | 9 | 2 | 3 | 2 | 2（+1 驳回）|
| 数据 / 持久化 / 安全 / 测试 / CI | 11 | 11 | 0 | 3 | 5 | 3 |
| **合计** | **65** | **64** | **6** | **18** | **25** | **15** |

> 唯一被驳回的发现：`export_*.py` 依赖 `qwen_asr`/`qwen_tts` 包被初步判定为"不存在的包"，核实发现 PyPI 上确有官方包（`qwen-asr 0.0.6`、`qwen-tts 0.1.1`），故驳回；但版本约束 `qwen-asr>=0.1.0` 不可满足（最新仅 0.0.6），仍属需修正项。

---

## 三、Critical 阻断项（必须在任何 polish 之前处理）

| # | 缺陷 | 证据 | 后果 |
|---|---|---|---|
| C1 | Hy-MT native runtime 未打包 | `jniLibs/` 仅 README；`NativeHyMtRuntime.kt:17-21,63-67`；`PipelineOrchestrator.kt:156-160,177-184` | 跨方言翻译核心能力完全不可用；单点失败拖垮 ASR/TTS |
| C2 | TTS 非自回归 + 全零码静音回退 | `TtsEngine.kt:358-363,380-394,404-415`；`export_tts_onnx.py:37-44,132-148` | 声音克隆产出静音且无任何错误信号 |
| C3 | ASR/TTS 无流式 partial | `AsrEngine.kt:146-163`；`PipelineOrchestrator.kt:42-44,191-250,257-262`；`docs/android-verification.md:41` | 首句 p50<1.5s 结构性不可达 |
| C4 | ASR 解码器无 KV cache（O(n²)） | `AsrEngine.kt:426-467`；`export_asr_onnx.py:80-97` | 256 步解码延迟易破 p95 3.5s |
| C5 | iOS 缺 MT，管线 ASR→TTS 直连 | `PipelineOrchestrator.swift:3,168-185`；`InterpretViewModel.swift:47-51` | iOS 无法实现跨方言翻译 |
| C6 | iOS ONNX Runtime 为桩实现 | `OnnxModelManager.swift:4-7,51-56`；`AsrEngine.swift:360-365`；`TtsEngine.swift:271-280,304-307` | iOS 整条推理链路产出空文本/静音 |

> 另有 1 项 product-critical：**Auralis 品牌完全未落地**（`strings.xml:2` app_name 仍为"方言传译"；全仓交付代码 grep `auralis` 零命中），属 Phase 2 头号成功标准未达成——阻断"premium 产品"目标但不阻断"核心能力"，故单列。

---

## 四、指导原则

1. **地基优先、自下而上**：先固定仓库结构/契约/构建/DI 骨架，再修推理引擎，再重塑管线与 UX；上层重构必须建立在已验证的下层基础之上。
2. **critical 优先于 polish**：6 项 critical 直接阻断核心产品能力，必须在任何视觉/品牌工作之前修复或至少建立降级路径。
3. **契约驱动、单一事实源**：方言目录、模型清单、导出/运行时输入契约必须有且仅有一份 canonical 来源（`shared/`），两端与 tooling 消费而非复制；契约变更先过契约测试再过平台验证。
4. **运行时所有权与生命周期解耦**：重型运行时对象（ONNX session、pipeline、audio）生命周期绑定应用级/会话级而非 Activity 级；旋转屏不得释放 App 级资源。
5. **失败必须显式且可恢复**：任何 silent fallback（全零码静音、空权限静默吞错、manifest 缺失跳过校验、`DROP_OLDEST` 静默丢语音）都是设计缺陷；错误必须成为一等状态并驱动用户可见的恢复 affordance。
6. **测试与质量门是贯穿性约束**：每阶段交付附带该阶段回归测试；CI 与 macrobenchmark 作为独立 phase 闭环，但最小测试保护网应在重构前建立。
7. **spec 方向吸收、盲区补强**：执行两份 spec 方向，但对 native runtime 打包、EP 策略、流式 ASR、模型保真度校准、内存压力响应、安全加固、CI 显式补强。
8. **技术选型必须可验证**：延迟目标必须有自动化测量手段（macrobenchmark + baseline profile），优化是否达标不靠手工感觉。

---

## 五、目标终态架构

### 仓库层（monorepo）
```
apps/android/        Android 客户端（Kotlin/Compose/ONNX Runtime）
apps/ios/            iOS 客户端（SwiftUI/onnxruntime-objc）
tooling/model-convert/  Python 导出/量化/验证/基准
shared/dialect-catalog/    方言目录单一事实源（catalog.json + schema）
shared/model-manifests/    模型清单契约（asr/tts/mt.json + index.json）
scripts/             bootstrap / doctor / verify / clean 根命令
config/              lint / style 工作区级约定
docs/                architecture / runbooks / specs
```

### Android 模块边界（Gradle 多模块）
- `:app` — 应用壳层（MainActivity、Application、Hilt 入口、导航编排），不含业务逻辑
- `:core-inference` — AsrEngine / TtsEngine / TranslationEngine / OnnxModelManager / NativeHyMtRuntime；独立可编译，可被 macrobenchmark 与单测单独依赖
- `:core-audio` — AudioRecorder / AudioPlayer / VoiceActivityDetector
- `:core-pipeline` — PipelineOrchestrator / InterpretationSessionController / InterpretationUiState 归约
- `:data` — ModelRepository / VoiceProfileRepository / SettingsRepository / TranslationCache
- `:ui-designsystem` — Theme、tokens（含 `BrandedControlDark`）、共享 Composable
- `:ui-screens` — Interpret/Setup/VoiceProfile/Settings 屏幕级 Composable
- `:macrobenchmark` — 启动与核心交互基准 + baseline profile 生成

### 运行时所有权（三层解耦）
- **Application 级（`@Singleton`）**：OnnxModelManager（OrtEnvironment + session 缓存）、ModelRepository、SettingsRepository — 经 Hilt 提供，绑定 `ProcessLifecycleOwner`，配置变更不释放。
- **会话级**：`InterpretationSessionController` 持有 PipelineOrchestrator + AudioRecorder + AudioPlayer，绑定 `ProcessLifecycleOwner`（非 Activity）；统一托管 start/stop/warmup/release/事件桥接；旋转屏不重建。
- **ViewModel 级**：仅依赖 controller 的 intent API 与 UiState 流，负责状态归约与 transcript 管理，不直接持有重型对象。

### 数据流
```
用户 intent → ViewModel.accept(intent)
  → SessionController.evaluate(readiness + lifecycle)
  → PipelineOrchestrator 事件流（AsrPartial/AsrResult/MtComplete/TtsComplete/Error/UtteranceDropped）
  → ViewModel.reduce(event)
  → InterpretationUiState（sealed: Idle/Preparing/Ready/Listening/Recognizing/Translating/Synthesizing/Playing/RecoverableError，
     携带 selected source/target + EP label + telemetry summary + amplitude + transcript + action affordance）
  → Composable 按 region 隔离 recomposition（顶部控制区 / transcript 工作区 / 底部 dock）
```

### 跨平台 shared 契约
- `catalog.json` → Android 代码生成（ksp/JSON→Kotlin）或运行时加载替换 `AsrEngine.kt:46-66` 硬编码；iOS 用 Bundle 加载 + Codable 解码替换 `AsrEngine.swift:20-32` 硬编码。
- `model-manifests/*.json` → 两端 ModelRepository 完整性校验 + tooling 产物校验；`files[]` 必须与运行时实际使用的 `_int4` 文件名/量化方式一致。
- 契约测试断言：平台列表条目数 == catalog 条目数；manifest `files[]` 与 asset pack 实际文件一致。

### 模型管线（tooling → 运行时）
```
export_*.py 导出 ONNX（含 KV cache 子图）
  → quantize INT4（CPU 路径）/ QDQ-QInt8 或 FP16（NNAPI 路径）
  → validate_models.py 保真度校验（CER/WER/相似度阈值门禁）
  → 生成 manifest files[]（sha256 + size）
  → 打包 asset pack / jniLibs
  → 运行时 ModelRepository 强制 sha256 校验
  → OnnxModelManager 按 EP 策略加载 session
```

---

## 六、七阶段重构路线图

阶段按"从最底层往上"排序：仓库/契约/构建地基 → 架构/DI → 推理/ML → 实时管线/音频 → 产品/UX → iOS 对齐 → 测试/CI/性能。

### P1 · 仓库 / 契约 / 构建地基（P0，L，无依赖）
**目标**：将 `android/ ios/ convert/` 规范化为 monorepo，建立 `shared` 契约单一事实源，统一版本目录与构建配置，落地质量门脚本。

**WS1 · Monorepo 迁移与构建规范化**
- 【P0】`android/→apps/android/`、`ios/→apps/ios/`、`convert/→tooling/model-convert/`，新增 `docs/architecture|runbooks|specs` 与 `config/lint|style`（`git mv` + 更新各平台相对路径与 README）。
- 【P0】落地 `scripts/bootstrap|doctor|verify|clean`，实现 fast/full/scope 模式与退出码 0-4（当前 `scripts/` 为空）。
- 【P0】新建 `gradle/libs.versions.toml` 统一所有库与插件版本（当前 25 个源文件全在单 `:app`、版本内联）。
- 【P1】阿里云镜像移出 `settings.gradle.kts`，改用 `~/.gradle/init.gradle.kts` 或 CI 环境变量（当前作为第一优先级硬编码，供应链与可复现性风险）。
- 【P2】清理死配置：删除 `secrets-gradle-plugin apply false`、proguard 中无依赖的 okhttp3 keep、冗余 serialization keep。

**WS2 · shared 契约单一事实源**
- 【P0】`catalog.json` 纳入 shipping 根，两端从 catalog 生成/加载替换 `AsrEngine` 硬编码（当前方言列表在 Kotlin/Swift/catalog.json **三处独立硬编码**，catalog 无人消费）。
- 【P0】填充 `asr.json` 的 `files[]`（当前为空）、新增 `mt.json`、统一 `tts.json` 与运行时实际 `_int4` 文件名（当前 tts.json 描述 5 个非量化文件 vs 运行时 3 个 `_int4` 文件不一致）、`index.json` 注册 mt。
- 【P0】manifest 随 asset pack 发布，`verifyIntegrityIfAvailable` 改为强制（无规则即校验失败而非跳过），`checkModelStatus` 复用精确文件清单（当前模型完整性校验在 shipping 构建中**实际为空操作**）。
- 【P1】`extractFromAssetPack` 缺失源文件抛错而非静默 `Log.w` 跳过。

**WS3 · 模块化拆分起步**
- 【P1】按推理/数据/UI 拆分 `:core-inference / :core-audio / :data / :ui-designsystem` 模块。
- 【P2】修正 `requirements.txt`：`qwen-asr>=0.0.6`、拆分 onnxruntime/onnxruntime-gpu 冲突。

**成功标准**：`scripts/verify --mode full` 退出码 0；shared 契约被两端实际消费且无硬编码副本；`libs.versions.toml` 统一版本；构建不依赖阿里云镜像；模型完整性校验在 shipping 构建中真正执行。

---

### P2 · 架构与 DI 骨架（P0，L，依赖 P1）
**目标**：引入 DI 消除 `DialectApp` companion 全局单例，引入 `InterpretationSessionController` 分离运行时所有权与 Activity 生命周期，定义统一 `InterpretationUiState`，建立 `SettingsRepository` 持久化。

**WS1 · DI 框架与全局单例消除**
- 【P0】引入 Hilt 建立应用级依赖图，移除 `DialectApp.companion instance` 单例与所有 `(context as DialectApp)` 强转；`AudioRecorder` 构造注入 `@ApplicationContext Context` 消除对全局单例的隐式读取；`@HiltViewModel InterpretViewModel` 注入 controller。
- 【P0】明确 ONNX session（App 级）与 pipeline（会话级）两层所有权，`MainActivity.onDestroy` 不再无条件 `releaseAll()`（当前每次旋转屏都关闭全部 ONNX session，存活的 ViewModel 仍持有已 close 的 session 引用）。

**WS2 · SessionController 与统一状态模型**
- 【P0】引入 `InterpretationSessionController` 持有 pipeline/recorder/player 生命周期与 start/stop/warmup/release/事件桥接；ViewModel 仅依赖 controller 的 intent API 与 UiState 流。
- 【P0】定义单一 `InterpretationUiState`（sealed）承载 spec 全部字段（setup readiness/idle/preparing/listening/recognizing/synthesizing/playing/recoverable error + selected source/target + EP label + telemetry + amplitude + transcript + action affordance）；`sourceDialect/targetLanguage` 改 `rememberSaveable` 并纳入 UiState（当前用普通 `remember`，旋转屏丢失；全仓零 `rememberSaveable`）。

**WS3 · 设置持久化**
- 【P1】引入 `SettingsRepository`（Preferences DataStore）持久化 source/target 语言、方言、EP 选择、默认 voice profile id（当前 DataStore 依赖已声明却**零使用**，设置项不持久化）。

**成功标准**：`DialectApp.instance` 全局单例移除；重型对象经 DI 提供；SessionController 独立可测；旋转屏不释放 ONNX session 且不丢失语言选择；UiState 能表达"边听边播"复合状态与 recoverable error。

---

### P3 · 端侧推理 / ML 引擎修复（P0，XL，依赖 P1+P2）
**目标**：修复 5 项 critical/high 推理缺陷——Hy-MT native runtime 打包使 MT 可用、TTS 改真正自回归并修复多 codebook、ASR 引入 KV cache 消除 O(n²)、统一 INT4/NNAPI EP 策略、建立模型保真度校准门禁。

**WS1 · Hy-MT native runtime 打包（critical）**
- 【P0】基于 llama.cpp/llama.android 编译 `libhymt_jni.so` 打包至 `jniLibs/arm64-v8a/`，CI 断言 .so 存在；pipeline 在 MT load 失败时降级为 ASR+TTS 直通并 emit 降级事件（不再取消整条流水线）。
- 【P1】新增 `export_hymt_gguf.py` 从基座模型经量化生成 GGUF，使 MT 模型可复现构建（当前仅下载成品，不可复现）。

**WS2 · TTS 自回归与多 codebook 修复（critical）**
- 【P0】talker LM 改为真正自回归 step 循环，导出含 `past_key_values` 的 decode 子图（当前仅一次 `lm.run`，与注释声称的 autoregressive 矛盾）。
- 【P0】加载并调用 `code_predictor` 子图生成多 codebook 码，移除 `decode3dLogits` 单 argmax 广播到 8 codebook 的错误。
- 【P0】移除 `parseSpeechCodes` 全零 fallback，解析失败抛异常而非静音回退。
- 【P1】接入 BPE 子词切分并解析 merges，未知字符映射到 `unk_id`（当前逐字切分 + `char.code+200L` 伪造 token ID）。

**WS3 · ASR KV cache 与特征前端修复（high）**
- 【P0】导出时分离 prefill 与 decode 子图（decode 暴露 `past_key_values_in/out`），AsrEngine 维护 KV cache 每步仅传增量 token。
- 【P1】AsrEngine 解析并应用导出的 `feature_extractor` 配置（normalization/CMVN/mel 段），与 PyTorch 参考做同音频 mel 逐元素数值比对（当前手写 mel 仅 `ln(maxOf(sum,1e-10f))` 无 CMVN，存在 train/serve 失配）。

**WS4 · EP 策略统一与内存压力响应（high）**
- 【P1】为 NNAPI 路径提供 QDQ/QInt8 或 FP16 友好版本（CPU 保留 INT4），启用 `NNAPIFlags`（USE_FP16 + BURST/SUSTAINED），`detectBestProvider` 做实测微基准选 EP（当前 INT4 MatMulNBits 仅 CPU 内核，NNAPI 被切分回退，`addNnapi` 用空 EnumSet，"NPU 优先"实际失效）。
- 【P2】`DialectApp` 注册 `ComponentCallbacks2`，`onTrimMemory` 时释放空闲 session，按 PipelineState 动态加载/卸载引擎（当前全引擎常驻无内存压力响应，低端设备易 OOM）。

**WS5 · 模型保真度校准与缓存策略**
- 【P1】`validate_models.py` 对固定样本跑 PyTorch 与 ONNX INT4 推理并计算 CER/WER 或 logits 余弦相似度设阈值断言（当前 ONNX 段仅 `get_inputs/get_outputs` + 打印，无 `session.run` 无比对，文件头声称"comparing outputs"与实现不符）。
- 【P2】翻译缓存改两级：纯文本+语言对 LRU 快路径 + 含 context 精确缓存慢路径（当前 Key 含完整 context，contextWindow 每轮漂移使命中率趋零）。

**成功标准**：端到端 ASR→MT→TTS 在真机产出可听语音与可读翻译；ASR 解码 256 步不再全序列前向；TTS 产出非静音且 RTF 可度量；NNAPI EP 在支持的设备上实际接管主导 MatMul；validate 产出 CER/WER 数值并通过阈值。

---

### P4 · 实时管线与音频安全（P0，L，依赖 P2+P3）
**目标**：消除 stop/release 并发释放崩溃风险，引入流式 ASR partial 使 p50<1.5s 可达，修复背压静默丢语音与调度器过订阅，降低首帧延迟。

**WS1 · 资源释放安全（critical）**
- 【P0】`stop()` 改 suspend 并 `cancelAndJoin(pipelineJob)` 等待各阶段退出后再释放音频/原生资源，共享可变字段加 `@Volatile` 或锁（当前 `stop()` 先 `cancel()` 紧接着同步释放 AudioTrack/AudioRecord/Hy-MT，ttsStage 仍阻塞在 `track.write` 时释放即 use-after-free 崩溃）。
- 【P1】`HyMtTranslationEngine.isLoaded` 改 `@Volatile` 或 `AtomicBoolean`（当前普通 var，load/release 跨协程数据竞争）。

**WS2 · 流式 ASR partial（critical）**
- 【P0】encoder 对增量 mel 做块编码，decoder 周期性 flush partial 假设经新 `PipelineEvent.AsrPartial` 上报；VAD 改自适应阈值（当前 ASR 仅 VAD 静音后整句解码，首句延迟下界=说话时长+800ms 静音+ASR 推理，p50<1.5s 不可达；VAD 固定 0.02 RMS 非噪声自适应）。
- 【P1】振幅/VAD 与 200ms ASR 分块解耦：按 20-40ms 子块读振幅触发 VAD（当前 200ms 分块使波形可视首帧≈200ms，违反 mic-tap<100ms）。

**WS3 · 背压与消息匹配修复（high）**
- 【P0】`utteranceChannel` 改 SUSPEND 或更大缓冲（语音不可静默丢），仅 `synthesisChannel` 保留 DROP_OLDEST；事件携带稳定 `messageId` 替代 FIFO 匹配；丢弃时 emit `UtteranceDropped`（当前三段均 DROP_OLDEST 静默丢用户语音；InterpretViewModel 用 FIFO `pendingMessageIds` 匹配与丢弃产生数据错配，被丢消息永久卡在 `isProcessing=true`）。
- 【P1】`audioChunks` SharedFlow 改 DROP_OLDEST 或专用 Channel，避免慢消费者反压录音循环丢音频。

**WS4 · 调度器隔离与错误恢复（high）**
- 【P1】为推理阶段用 `Dispatchers.Default.limitedParallelism(1)` 专用调度器，按设备分级动态调整并行度（当前 ASR/MT/TTS 三段同跑 Default，叠加 5 个 ONNX 线程池 + Hy-MT 原生线程池，过订阅使 p95 不降反升）。
- 【P1】`captureStage` 确认录音成功后才置 LISTENING，mic 失败传播为 `PipelineEvent.Error`（当前调用 startRecording 前即置 LISTENING，权限缺失静默停在 LISTENING 不收音无错误）。
- 【P2】每阶段用 SupervisorJob + 限定重试替代 `coroutineScope` 全取消，区分可恢复与致命错误，UI 提供重试入口（当前单阶段失败取消整条管线，用户需手动重启）。
- 【P2】AudioPlayer 用 `minBufferSize` 而非 2 倍，用回调替代 10ms 轮询；修正"流式 vocoder"误导注释。

**成功标准**：stop/release 不触发原生崩溃；用户开口后 1.5s 内可见 partial transcript；utteranceChannel 不再静默丢语音；三阶段不再同时竞争 Default 线程池；mic-tap 波形首帧 <100ms。

---

### P5 · Auralis 产品与 UX 落地（P1，L，依赖 P2+P3+P4，可与 P4 部分并行）
**目标**：落地 Auralis 品牌、Quiet Premium 视觉主轴、Setup/权限错误恢复路径、InterpretScreen 拆分与死代码清理、感知性能与可访问性。

**WS1 · Auralis 品牌与文案落地（critical）**
- 【P1】`app_name` 与顶栏品牌词改 Auralis，导航主标签改英文-first（Interpret/Setup/Voice/Settings），硬编码文案抽离 `strings.xml` 并配置 `localeConfig`（当前 `strings.xml:2` 仍为"方言传译"，全仓交付代码 grep `auralis` 零命中）。

**WS2 · Quiet Premium 视觉与组件拆分**
- 【P1】为 Interpret 顶部控制区引入独立深色 branded surface token（`BrandedControlDark`），与浅色 transcript 工作区形成明暗对比（当前顶栏 `surface.copy(alpha=0.92f)`，浅色模式下与工作区同色，spec"顶暗底亮"未实现）。
- 【P1】按组件职责拆分 InterpretScreen（660 行）：`ChatMessage`→`ui/model`，`ChatBubblePair`/`TypingIndicator`→`ui/components`，`MinimalBottomBar`/`DialectBottomSheet` 各成独立文件。
- 【P2】删除孤立死组件 `DialectSelector.kt`/`StatusIndicator.kt`（零调用方，存在两套并行语言选择实现）。
- 【P2】默认隐藏逐气泡延时徽章，仅 Settings 开启"pro 信号"后显示；telemetry 文本不低于 12.sp（当前每条气泡挂 4 枚 10.sp 徽章 + 顶栏汇总，偏仪表盘化且低于无障碍可读下限）。

**WS3 · 错误恢复、感知性能与可访问性**
- 【P1】`ModelDownloadScreen` error 时复位 `isExtracting` 并提供重试按钮，按 spec 四类失败映射差异化文案；`MainActivity` 改为按需请求权限并展示 rationale，权限拒绝进入 `RecoverableError` 状态（当前解压失败按钮永久禁用；权限拒绝主路径静默吞错、voice profile 路径抛异常，均无 recoverable UI）。
- 【P2】LOADING/PREPARING 期间底部显示"准备中"指示而非波形，引入 Ready 确认态后再切波形（当前加载期间显示静止波形误导为"正在监听"）。
- 【P2】为所有交互/信息图标补 `contentDescription`，波形与状态 pill 加 `semantics`/`liveRegion`，telemetry 不低于 12.sp。
- 【P3】删除无 Service 实现的 `FOREGROUND_SERVICE` 权限，或落地带 `foregroundServiceType=microphone` 的 Service（需产品决策是否要后台连续传译）。

**成功标准**：用户可见品牌统一为 Auralis；顶栏为深色 branded zone 与浅色工作区明暗对比；模型解压失败与权限拒绝有明确重试/恢复 UI；InterpretScreen 拆分为 <200 行主屏 + 独立组件；无死组件。

---

### P6 · iOS 对齐与跨平台契约消费（P1，L，依赖 P1+P3，可与 P5 部分并行）
**目标**：iOS 接入真实 ONNX Runtime 替换桩，引入 TranslationEngine 使管线改为五阶段，消费 shared 契约替换硬编码，建立 iOS 测试目标。使 iOS 从"不可用的桩"变为"与 Android 行为对齐的可用端"。

**WS1 · iOS ONNX Runtime 与 MT 引擎（critical）**
- 【P1】接入 `onnxruntime-objc` 替换 `OrtSession.run()` 返回空数组的桩实现，移除所有零值回退改为抛错。
- 【P1】引入 `TranslationEngine` 协议与 `HyMtRuntime` 抽象，管线改为五阶段（capture→ASR→MT→TTS→playback）。iOS MT 策略需决策：onnxruntime-objc 包装 Hy-MT ONNX 版（需先导出）vs 等 iOS GGUF runtime vs UI 禁用跨语言模式。

**WS2 · shared 契约消费与 iOS 测试**
- 【P1】iOS 从 `catalog.json` 加载方言列表替换 `AsrEngine.swift` 硬编码。
- 【P2】为 iOS 添加 XCTest 目标覆盖核心纯函数，新增跨平台一致性测试断言平台列表与 catalog 一致（当前 iOS 无任何测试目标）。

**成功标准**：iOS `OrtSession.run` 委托到真实 ORT；iOS 管线包含 MT 阶段；iOS 从 shared catalog 加载方言；iOS 有 XCTest 覆盖。

---

### P7 · 测试 / CI / 性能验证闭环（P0，L，依赖 P1+P2，各阶段交付时附带回归测试）
**目标**：建立自动化测试保护网、落地 macrobenchmark 与 baseline profile、建立 CI 流水线、加固 voice profile 生物特征数据安全。

**WS1 · 测试保护网建立（P0 贯穿）**
- 【P0】新增 `InterpretationUiState` 归约单测、`ModelRepository` manifest 解析与校验失败路径单测、`VoiceProfileRepository` WAV round-trip 单测、`PipelineOrchestrator` 背压/丢弃行为 instrumented 测试（当前仅 1 个单测文件 2 个用例）。
- 【P1】Compose UI 测试覆盖 setup-ready/required、idle、listening、transcript、modal、error/retry 状态。

**WS2 · macrobenchmark 与 baseline profile**
- 【P1】新增 `:macrobenchmark` 模块，集成 `androidx.baselineprofile` 生成 `baseline-prof.txt` 并在 release buildType 启用；编写覆盖启动→录音→ASR→MT→TTS 全链路的 Macrobenchmark；把 `docs/android-verification.md` 延迟阈值设为 CI 回归门禁。

**WS3 · CI 流水线与安全加固**
- 【P1】新增 GitHub Actions CI 镜像 `scripts/verify --mode full`（契约测试 + gradle testDebugUnitTest + lintDebug + assembleDebug），PR 上 gating；asset pack/jniLibs 缺失时 fail 而非跳过。
- 【P2】用 `androidx.security:security-crypto` 的 `EncryptedFile`/`MasterKey` 对 voice profile 音频与 meta 加密落盘（当前 `.wav` 明文存储，root 设备或本地备份可获取明文生物特征语音）。
- 【P3】收敛 proguard `-keep` 到真正被反射/JNI 使用的类，移除过粗整包 keep（当前 `-keep class inference.**/audio.**/data.** { *; }` 使 R8 几乎无法裁剪死代码）。

**成功标准**：`scripts/verify --mode full` 在 CI 上通过；macrobenchmark 产出冷启动与交互基准；baseline profile 在 release 启用；延迟目标有自动化测量与回归门禁；voice profile 加密落盘。

---

## 七、Quick Wins（可立即执行，低成本高收益）

1. 删除 `secrets-gradle-plugin apply false`（`build.gradle.kts:5`）与 proguard 中无依赖的 okhttp3 keep（`proguard-rules.pro:11-13`）——零风险纯清理。
2. `ModelDownloadScreen.kt` error 分支复位 `isExtracting=false` 并加重试按钮——当前解压失败按钮永久禁用，一行 `if` 修复首运行关键路径。
3. `MainActivity.kt:36-39` 移除 `onDestroy` 中 `releaseAll()` 或加 `isFinishing` 判断——当前每次旋转屏都关闭全部 ONNX session，critical 生命周期 bug。
4. `InterpretScreen.kt:89-90` `sourceDialect/targetLanguage` 改 `rememberSaveable`——当前旋转屏丢失语言选择，两行改动。
5. 修正 `AudioRecorder.kt:32` 注释"200ms for lower latency"为"200ms for ASR mel accumulation"——方向错误注释误导优化者。
6. 修正 `convert/requirements.txt:3` `qwen-asr>=0.1.0` 为 `>=0.0.6`——当前约束不可满足导致 `pip install` 失败。
7. 删除孤立死组件 `DialectSelector.kt` 与 `StatusIndicator.kt`——零调用方，删除即减认知负担。
8. 将 `settings.gradle.kts` 阿里云镜像移至 `~/.gradle/init.gradle.kts`——恢复构建可复现性。

---

## 八、开放问题（需决策）

1. **DI 框架选型**：Hilt（官方、编译期、与 ViewModel/Application 深度集成但 kapt/ksp 重）vs Koin（轻量、纯 Kotlin、运行时错误）？影响 P2 所有模块。
2. **是否引入 KMP 共享层**：shared 契约各平台独立加载 JSON（简单、零跨平台运行时依赖）vs KMP 生成 Kotlin/Swift 代码（强一致但引入 KMP 工具链）？Phase 1 spec 明确 non-goal 是 shared runtime code，但 catalog 消费方式需决策。
3. **native Hy-MT runtime 来源**：自研基于 llama.cpp 的 JNI 包装（可控但维护成本高）vs 社区 llama.android（成熟但可能不完全适配 1.25bit GGUF）vs 其他 GGUF runtime？是否接受 GGUF 之外格式（如 ONNX MT）以统一 runtime？
4. **iOS MT 策略**：用 onnxruntime-objc 包装 Hy-MT ONNX 版（需先导出，与 Android GGUF 路径分叉）vs 等待 iOS GGUF runtime 成熟 vs iOS 仅做 ASR+TTS 直通并 UI 禁用跨语言模式？
5. **是否上 CI 及 runner 规格**：GitHub Actions 免费层（无 GPU、无法跑模型导出/保真度校验）vs 付费 GPU runner vs 本地 CI？模型导出与保真度门禁需要 GPU。
6. **MT 模型路线**：继续 AngelSlim/Hy-MT1.5-1.8B-1.25bit GGUF vs 换基座/换量化（Q4_K_M GGUF 或 ONNX INT4）？1.25bit 极端量化是否有不可接受的精度损失？需保真度校准数据支撑。
7. **流式 ASR 路径**：重排为真正增量式流式（encoder 块编码 + decoder 周期 flush partial，工作量大但根本解决延迟）vs 折中（缩短 VAD timeout + 固定时间窗触发 partial，工作量小但改善有限）？
8. **模块化拆分粒度**：`:core-inference`/`:data`/`:ui-designsystem` 三模块（快速落地）vs 更细拆分（asr/tts/mt/audio/pipeline 各独立，更独立但构建配置复杂）？
9. **后台连续传译是否为产品需求**：若是，需落地 `foregroundServiceType=microphone` 的 Service（与 SessionController 一并设计）；若否，删除 `FOREGROUND_SERVICE` 权限。
10. **iOS 对齐优先级与时间线**：iOS 作为 P6 紧跟 Android 之后，还是先完成 Android 全部七阶段再启动 iOS？当前 iOS 为桩实现，完全对齐工作量可能相当于重写 iOS 端。

---

## 附：审计证据索引（关键 file:line）

| 维度 | 代表性证据 |
|---|---|
| 产品/UX | `strings.xml:2`；`InterpretScreen.kt:89-90,130,179-181,517-524`；`ModelDownloadScreen.kt:38,179-198`；`Theme.kt:76,153` |
| 架构/DI/构建 | `DialectApp.kt:16,28-31`；`MainActivity.kt:36-39`；`InterpretViewModel.kt:147-162`；`build.gradle.kts:5,68-114`；`settings.gradle.kts:3-15` |
| 推理/ML | `NativeHyMtRuntime.kt:17-21,63-67`；`TtsEngine.kt:358-415,380-394`；`AsrEngine.kt:426-467,203,306-318`；`OnnxModelManager.kt:62-66,99-119`；`validate_models.py:54-128` |
| 管线/音频 | `PipelineOrchestrator.kt:92-103,156-184,191-250,459-475`；`AudioRecorder.kt:32,97-114,120-121`；`AudioPlayer.kt:53,84-89,183-186`；`VoiceActivityDetector.kt:10-11,57-81` |
| iOS/跨平台/工具链 | `PipelineOrchestrator.swift:3,168-185`；`OnnxModelManager.swift:4-7,51-56`；`AsrEngine.swift:20-40`；`shared/.../catalog.json`；`asr.json:16`；`tts.json:23-26`；`download_hymt_gguf.py:22-23,50-58` |
| 数据/安全/测试/CI | `ModelRepository.kt:90-91,240-245,274-306`；`VoiceProfileRepository.kt:39,148-185`；`InterpretViewModel.kt:96-118`；`HyMtTranslationEngine.kt:14`；`android/app/src/test/`（仅 1 文件）；无 `.github/workflows/` |
