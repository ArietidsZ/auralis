# interface-B（lane B → A / 主审）

更新：2026-09-05 23:0x（lane B 实施中，含对 interface-A.md 代码漂移的记录）。

## B0. 代码漂移记录（A 实现与 interface-A.md 报告的差异，B 按实际代码适配）

1. `AudioPlayer` 构造函数现在需要 `Context`（`AudioPlayer(context)`），报告未写。B 已在 AppContainer/VoiceProfileScreen 传 context。
2. `AudioCapture.start(onChunk, onReady: () -> Unit = {})` 增加了 `onReady` 参数（报告 §3 未写）。trailing-lambda 会绑定到 `onReady`，B 调用处用 `onChunk = {...}` 命名参数。请 A 更新 interface-A.md，避免 C/主审误解。
3. `SpeechSynthesizerResult`（A 的 session 测试引用）在 TtsEngine 中尚未定义时，测试源集无法编译，会阻塞 B 的 `testDebugUnitTest`（测试编译是整体）。A 落地后 B 重跑。

更新：2026-09-05（lane B 开工时）。lane B 只通过 02-runtime.md R02 冻结契约 `com.dialect.interpreter.session.SessionController` 呈现会话，不另造 Controller/Repository。

## B1. AppContainer 会话工厂（B → A 需要）

按 R01/R02，AppContainer 只保留 Context、仓库与会话工厂。已按主审复核第 1/2 项实现（2026-09-05 晚）：

```kotlin
class AppContainer(context: Context) {
    val modelRepository: ModelRepository      // B（runtimeProbe = ModelRuntimeProbe）
    val voiceProfiles: VoiceProfileRepository // B（embeddingExtractor 已接 A 端口）
    val settings: SettingsRepository          // B（DataStore）
    val dialectCatalog: DialectCatalogLoader  // B

    fun createSessionController(): SessionController  // 每个 controller 独立 OnnxModelManager
}
```

1. ~~需要 A 明确构造参数~~ → 已按 interface-A.md §2 落地，参数名/类型与代码一致。
2. voice resolver：B 实现 `VoiceProfileResolver`，内部走 `VoiceProfileRepository.resolveSpeakerEmbedding`，接 A 的 `TtsEngine.loadSpeakerEncoder()` + `extractSpeakerEmbedding()`（专用 manager + 互斥串行，不与 session 共享 sessions）。缺失模型/运行时时返回 null（克隆禁用），不伪造 embedding。
3. `SessionConfig` 语义确认：sourceLanguage=dialect.asrLanguage，targetLanguage=targetLanguages[].asrLanguage，voiceProfileId 原样透传；档案无效时 resolver 返回 null，会话以非克隆继续。
4. **【A 评估项】** `ModelRuntimeProbe` 现以“真实 load+release”作为就绪探测（隔离 manager，逐包串行）。若真机性能数据表明逐包全量 load 太重，请 A 提供轻量 runtime 检查端口；在端口出现前 B 不弱化门禁。

## B2. B 对冻结契约的使用方式

- ViewModel 订阅 `controller.snapshot` + `controller.amplitude`，与设置/安装状态合并为单一 `InterpretUiState`；Compose 用 `collectAsStateWithLifecycle`。
- 清空 transcript：冻结契约无 clear()，B 在 UI 侧维护 `(sessionId, turnId)` 水位过滤已清空 turn，迟到事件不会重新出现。不需要 A 改动。
- 每轮局部重试：冻结契约无 per-turn API。B 先提供"重新录入"（继续会话）与会话级"重试"（stop→start 同配置）。若 A 认为应加 per-turn retry 端点，请在 interface-A.md 提出，不要单方面改契约。
- 生命周期：VM `onCleared()` 同步调用 `controller.close()`（幂等，A 在自有 scope 清理）；后台 ON_STOP 调 `stop()`；回到前台不自动 start。

## B3. B 提供给 A / C

- `VoiceProfileRepository.loadReference(profileId): VoiceReference?`：RIFF 严格校验（V08），拒绝空/全零/NaN。A 克隆一律走此入口。
- `ModelRepository.packageStates: StateFlow<Map<String, PackageInstallState>>`（Missing/Installing/Verifying/Installed/RuntimeUnavailable/Ready/Failed）与 manifest status（draft→"模型尚未验证"）。A 可用于加载前检查。
- Manifest 解析兼容 v1（`size_bytes`/`sizeBytes`，冲突即失败）；schema 正式契约由 C 定，B 消费。

## B4. 对 C 的依赖请求（Gradle，C 合入）

1. ~~删除 `androidx.compose.ui:ui-text-google-fonts`~~ → **已由 C 执行（§5 B4.1）**，感谢。
2. ~~【需要】`androidx.lifecycle:lifecycle-runtime-compose:2.8.7`~~ → **已解决，无需 C 改动**：该 artifact 已作为 `lifecycle-viewmodel-compose:2.8.7` 的传递依赖可用（Gradle cache 可证），`collectAsStateWithLifecycle`/`LocalLifecycleOwner` 均编译通过。可选卫生建议：C 若愿意可显式声明，避免未来依赖树变化时隐式失效；不阻塞。
3. 单测依赖：junit4 够用（B 测试全部纯 JVM）。C §5 说明未引入 coroutines-test/kotlin-test，与当前 Gradle 文件状态不一致（文件里已加），请 C 自行核对取齐。
4. 无 sourceSets 变更请求。

## B6. iOS 会话核心 host 测试挂 CI（请 C 接入）

- `ios/DialectInterpreterTests/SessionCoreTests/run_host_tests.sh`（host `swiftc`，无需 Xcode/iOS SDK）跑会话核心 24 例 + AudioPlayer 真实 AVFoundation type-check。**CI/verify 需显式调用该脚本**：plain harness 不是 XCTest，Xcode test target 编译进这些文件也不会自动运行它们；请在 verify 流程加一步 `bash ios/DialectInterpreterTests/SessionCoreTests/run_host_tests.sh`（exit 0 = 通过）。
- 文件经文件系统同步组自动纳入 test target，无需 pbxproj 改动；脚本仅用临时目录，不写仓库。

## B5. 时序

B 先交付独立 data/UI（不依赖 session 类型），随后接入 `com.dialect.interpreter.session.*`。若 A 契约落地前 B 完成其它工作，仅 4 个文件（AppContainer、InterpretViewModel、AppNavigation、InterpretScreen）等待 A 类型编译验证；此状态会记录在 lane-B.md，不视为完成伪装。
