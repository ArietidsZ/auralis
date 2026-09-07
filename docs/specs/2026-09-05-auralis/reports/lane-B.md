# lane-B 报告（product：data/、ui/、AppContainer、MainActivity、Manifest、res）

状态：**实现完成（工程门通过），产品验证待补（无真机/无真实模型产物）**。
日期：2026-09-05。本报告只覆盖 lane B 独占边界内的文件；audio/inference/session 未修改。

## 1. 实现完成

### 数据层（`data/`）
- **`WavCodec.kt`（新）**：严格 RIFF 解析器/编码器（V08）。遍历真实 chunk 结构（fmt/data/LIST/fact/未知均可），支持 PCM 8/16/24/32-bit 与 WAVE_FORMAT_EXTENSIBLE；校验声道、采样率、byteRate/blockAlign 一致性、声明长度 vs 实际字节、空数据、64MB/120s 上限、NaN/Inf/越界采样；`writeAtomically` 临时文件+fsync+rename。
- **`ModelManifests.kt`（新，严格 v2）**：与 `convert/manifest_contract.py` 逐条对齐（同 stable error codes：unknown-schema / field-conflict / empty-hash / empty-file-set / duplicate-path / path-escape / role-missing / external-data-missing / placeholder-value / revision-not-pinned / missing-required / bad-value / bad-json）。含 source/runtime/capabilities/roles/externalData/classification/全零哈希/40 位 revision pin 全部校验；`ModelPathGuard` 拒绝绝对路径/`..`/空段/反斜杠/控制字符/越界 symlink。
- **`ModelRepository.kt`（重写）**：C03 全流程 — PackageState（Missing/Installing/Verifying/Installed/RuntimeUnavailable/Ready/Failed）；manifest 驱动文件清单（draft 空清单不得安装不得 Ready）；同卷 staging、空间预检、流式复制+取消（逐 chunk ensureActive）、staging 落盘哈希校验、备份→原子换目录→清理（备份父目录 mkdirs、崩溃两步恢复）；**每次 probe 均全量 size+hash 校验（无 marker 缓存）**；manifest.packageId 与安装目标一致性检查；`installMutex` 串行 + `deleteAllModels` 互斥 + 会话占用 guard（`setSessionOccupied`）。PAD 优先、app assets（`assets/models/<pkg>.json`）为开发入口；来源缺失如实 Missing，不模拟下载。
- **`VoiceProfileRepository.kt`（重写）**：UUID v4 ID；v2 JSON 元数据（createdAt/durationMs/sampleRate/channels/format/audioBytes），兼容读 v1 JSON 与旧行格式并迁移；音频与元数据原子写、以 meta+wav 同时存在为可见；失败回滚不留半档案；删除校验 ID 并清除 wav/meta/embedding；`loadReference` 读前 size 预检、非 16kHz 拒绝（不做伪重采样）、非有限采样拒绝、全零拒绝；`resolveSpeakerEmbedding` 走注入的 extractor + `sanitizeEmbedding`（非空/有限/非全零）。
- **`SettingsRepository.kt`（新）**：DataStore 持久化 source/target/voice（spec U03 二选一取设置仓库路线，transcript 不持久化）。

### UI 层
- **`Theme.kt`**：spec U01 token；对比度实测（WCAG AA 全过）：#17232A/#F6F8F7=15.0、#216B62/#F6F8F7=5.9、#FFFFFF/#216B62=6.3、#F6F8F7/#142128=15.4、暗色强调 #63B3A4/#142128=6.7。系统字体、sp 动态字号、8dp 网格、48dp 目标。
- **`InterpretScreen.kt`（重写）**：常暗控制区（Auralis 品牌+模式+阶段文本+图标）/浅色对话区；`collectAsStateWithLifecycle`；权限延迟请求（点击→解释→系统请求→永久拒绝给设置入口）；ON_RESUME 复查权限；**ON_STOP 后台停止但 `isChangingConfigurations` 旋转不停止**；install gate 横幅（未 Ready 不启用麦克风+安装入口，不静默降级）；turn 渲染按冻结契约：`translatedText==null` 永不以原文补齐；终态（COMPLETE/FAILED/DROPPED/CANCELLED）全部结束等待动画；DROPPED 显示"已跳过（队列已满）"、tts 缺失显示"译文完整·未播放"；长按复制；接近底部才自动滚动；稳定 key（sessionId-turnId）；会话中配置改动禁用并提示"停止后生效"；reduce-motion（系统动画关闭）禁用装饰动画。
- **`InterpretViewModel.kt`（重写）**：`controller.snapshot+amplitude` 与设置/安装状态合并为单一 `InterpretUiState`（能力派生：仅转写/文本传译/语音传译，用户可见）；清空水位 `(sessionId, turnId, count)` 过滤迟到事件（纯函数 `visibleTurns`，测试覆盖）；`onCleared` 同步 `controller.close()`（不 launch 已取消 scope、不 runBlocking）；FAILED 会话重试先 stop 再 start；`start` 的 `SessionStartException` 由快照 problem 呈现。
- **`SetupScreen.kt`（新）**：逐包状态/字节（received/expected/占用）/失败原因/取消/重试/刷新；draft 显示"模型尚未验证"。
- **`VoiceProfileScreen.kt`（重写）**：录制前本人/授权勾选（U04）；手动开始/停止（适配 A 新 `AudioCapture.start(onChunk, onReady)`）；时长不足 3s 拒绝；重录；试听完成/失败/停止均恢复按钮；删除确认；元数据展示；snackbar 反馈。
- **`SettingsScreen.kt`**：CPU (ORT 1.22) 如实标注（A 已移除推测性 EP）；逐包状态；模型管理入口。
- **`AppNavigation.kt`**：始终以 Interpret 为起点（Setup 可随时离开，非陷阱）；过渡动画尊重 reduce-motion。
- **`AppContainer.kt` / `ModelRuntimeProbe.kt` / `MainActivity.kt` / `DialectApp.kt`**：见复核整改。

### 主审复核 7 项整改
1. ✅ `ModelRuntimeProbe`：真实隔离 load+release（专用 manager），缺 runtime → false → RuntimeUnavailable；不再常量 null。克隆 extractor 已接 A 的 `TtsEngine.loadSpeakerEncoder()+extractSpeakerEmbedding()`（专用 manager+互斥），失败/缺模型 → null（克隆禁用，非伪造）。
2. ✅ `createSessionController()` 为每个 controller 创建独立 `OnnxModelManager`；probe、embedding 引擎亦各自独立；app 级 session 缓存已删除。
3. ✅ ModelManifests 严格 v2 + C fixtures 一致性测试（18 例含 valid/invalid 全量，跳过 catalog-*）；packageId==pkg 在 probe/install 双侧校验。
4. ✅ 删除 `.verified` marker；probe 每次 size+hash 全校验。
5. ✅ 备份父目录 mkdirs；两步崩溃恢复在 `refresh()`（代码审查级验证，见§4 局限）；install/delete/会话占用互斥。
6. ✅ `collectAsStateWithLifecycle`（经 `lifecycle-viewmodel-compose:2.8.7` 传递依赖可用，interface-B 已更新，无需 C 改动）；ON_STOP/旋转/权限复查见上。
7. ✅ loadReference 拒绝非 16kHz（不伪造重采样）、size 预检、embedding 有限性 gate + 测试。

## 2. 执行过的验证（真实命令/退出码）

- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL（含 A 已落地的 session/inference/audio 主源码）。
- `./gradlew :app:testDebugUnitTest --tests "com.dialect.interpreter.data.*" --tests "com.dialect.interpreter.ui.*"` → **43 tests, 0 failures**：
  - WavCodecTest 13（往返/扩展 chunk/截断/超长/非 PCM/对齐/空数据/NaN/Inf/原子写）
  - ModelManifestsTest 18（**含 C fixtures 一致性套件：valid 3 + invalid 全部声明 code**，及 v2 严格性/路径守卫/symlink）
  - EmbeddingSanitizeTest 2、InterpretViewModelReductionTest 6（终态/清空水位/迟到事件/新会话隔离/无原文补译文）、DialectCatalogTest 4。
- `./gradlew :app:testDebugUnitTest`（全量）：B/C 边界外 **A 的测试当前红**（UtteranceSegmenterTest×3、VoiceActivityDetectorTest×1、HyMtTranslationEngineTest init、PipelineOrchestratorTest 多例超时/断言），属 lane A 独占文件，未由 B 代修。B 子集绿。

## 3. 未执行 / 阻塞（如实区分）

- **缺真实模型产物**：三个 asset pack 无 ONNX/GGUF，shared 清单全 draft → 真实安装/Ready/克隆全链路无法在本机验证（产品门 blocked）。UI/状态机按 draft 如实显示"模型尚未验证/未安装"。
- **缺真机/adb**：权限弹窗、录音、播放、TalkBack、200% 字号、性能门（05 V02）全部 blocked；安装两步崩溃恢复与 PAD 路径仅代码审查级验证，无设备级复测。
- **A 的测试当前失败**（非 B 边界）：修复后 B 将重跑全量单测确认无交叉回归。
- `ModelRuntimeProbe` 成本：逐包全量 load 在真实模型落地后较重；已向 A 提出轻量检查端口评估（interface-B B1.4），未单方面弱化门禁。

## 4. 删除的抽象 / 简化（消融）

- WaveformVisualizer：删除每根柱子的 spring 动画（装饰性），直接跟随真实幅度；仅保留受 reduce-motion 约束的呼吸相位。
- InterpretViewModel：删除与快照 problem 重复的独立 error 流；删除 SavedStateHandle（设置仓库已满足 U03"二选一"）；删除硬编码语言/方言回退（单一 catalog 事实源）。
- ModelRepository：删除 `.verified` marker 哈希缓存（复核后选择"每次校验"更简单基线）；删除硬编码 ASR/MT/TTS 文件清单（manifest 驱动）。
- 删除 `ModelDownloadScreen`（被 SetupScreen 取代）、`createPipeline()`（会话工厂直连 A 构造器，无等价转发层）。
- Manifest/res：移除 INTERNET/ACCESS_NETWORK_STATE/FOREGROUND_SERVICE*（离线+前台会话，无对应实现）；清理 windowTranslucentStatus 与 enableEdgeToEdge 冲突。

## 5. 需其它 lane 接入

- **A**：① 修复 session/audio 测试当前红（B 全量单测回归依赖它）；② 评估轻量 runtime 探测端口（B1.4）；③ interface-A.md 补记 `AudioPlayer(context)` 构造与 `start(onChunk, onReady)` 签名漂移（B0 已代记）。
- **C**：① `fetch_model.py --asset-pack` 输出路径 `assets/{pkg}/manifest.json` 与 B 的 candidate 已兼容；B 走 app assets 时用 `assets/models/<pkg>.json`（C 的 sync task）✓；② 可选：显式声明 `lifecycle-runtime-compose`（现为传递依赖，见 interface-B B4.2）。
- **集成（C 全量 Gradle）**：assembleDebug/lintDebug 由 C 串行执行；B 已保证模块内 Kotlin 编译通过。

## 6. 验收对照（03 U05 + 05 V01/V02/V08/V12）

| 项 | 状态 |
|---|---|
| V01 schema/v1 拒绝/路径/draft 不 Ready | ✅ 单测（与 C fixtures 对齐） |
| V02 安装 hash 失败/取消保留旧版本/external data | ✅ 代码+路径守卫测试；磁盘级验证待真机 |
| V08 WAV 扩展 chunk/截断/路径隔离/原子保存 | ✅ 13 例单测 |
| V12 权限拒绝/设置恢复/200%/TalkBack/离线 | ⚠️ 实现完成；真机验证 blocked |
| 无假译文/终态归约/清空迟到事件 | ✅ UI 子集单测 |
| 缺模型安装失败重试/draft 显示 | ✅ 状态机+Setup 界面（逻辑级） |
| 真机端到端/性能门 | ❌ blocked（无设备/无模型） |
