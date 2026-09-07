# 02 会话、音频与推理

## R01 所有权

保留小型手工 DI。AppContainer 只持有应用 Context、仓库与会话管理所需工厂；会话自己拥有引擎句柄和音频资源。禁止 Activity `onDestroy()` 直接释放会话正在用的 ORT session。模型文件可共享，未经同步的 mutable decoder/cache/session 不跨会话共享。

一个会话至多一次 start/stop 转换，使用互斥保护。启动失败必须释放已成功加载的前序资源。停止次序：标记 stopping → 停止/解除阻塞的采集和播放 → 请求 native 取消并取消子任务 → 等待在途 native 调用结束 → 关闭模型/清理本轮队列 → stopped。关闭幂等；不得为避免等待而释放还在使用的指针。

ViewModel 清除时使用独立受控 cleanup scope 或同步发出关闭请求再由会话自己的 scope 完成清理。不能在已经取消的 viewModelScope 发起关键释放，也不允许主线程 runBlocking。录音、安装、推理、读取档案不在 UI 主线程运行。

## R02 Android 接口冻结

运行时 lane A 提供 `com.dialect.interpreter.session` 的下列最小契约；lane B 只通过该契约呈现会话。名字/语义变更必须在接口说明中先同步，不能自行造另一套 Controller/Repository。

```kotlin
data class SessionConfig(
    val sourceLanguage: String,
    val targetLanguage: String,
    val voiceProfileId: String? = null
)
enum class SessionPhase { IDLE, STARTING, ACTIVE, STOPPING, FAILED }
enum class WorkStage { CAPTURE, ASR, MT, TTS, PLAYBACK }
enum class TurnStatus { CAPTURED, RECOGNIZING, TRANSLATING, SYNTHESIZING,
    PLAYING, COMPLETE, FAILED, DROPPED, CANCELLED }
data class SessionProblem(
    val code: String, val stage: WorkStage?, val message: String,
    val recoverable: Boolean
)
data class TranscriptTurn(
    val sessionId: Long, val id: Long,
    val sourceText: String = "", val translatedText: String? = null,
    val status: TurnStatus = TurnStatus.CAPTURED,
    val problem: SessionProblem? = null
)
data class SessionSnapshot(
    val sessionId: Long = 0,
    val phase: SessionPhase = SessionPhase.IDLE,
    val captureActive: Boolean = false,
    val activeStages: Set<WorkStage> = emptySet(),
    val turns: List<TranscriptTurn> = emptyList(),
    val problem: SessionProblem? = null,
    val droppedTurns: Long = 0
)
interface SessionController {
    val snapshot: StateFlow<SessionSnapshot>
    val amplitude: StateFlow<Float>
    suspend fun start(config: SessionConfig)
    suspend fun stop()
    fun close() // request cleanup on owned scope; idempotent
}
```

代码块中的 imports 由实现补齐。可增加默认值字段与内部实现，不改变冻结字段类型。`PipelineOrchestrator` 可以直接实现 SessionController；除非实际测试需要，不再套一个等价委托层。旧事件 API 仅临时兼容，接入完成删除。

voiceProfileId 由注入的 suspend resolver 解析为实际 reference/embedding，不能绕过仓库校验。lane B 与 A 在第一轮明确工厂参数。

## R03 事件与背压

会话 ID 每次启动递增，语句 ID 在采集时生成并贯穿 ASR/MT/TTS/playback。状态只有一个串行写入点或原子 reducer。旧会话事件不能改写新会话；一个 turn 只能有一个终态，所有终态都会结束 UI processing。

持久 UI 事实放在 StateFlow 快照；波形 amplitude 独立且降采样，至多 20–30Hz。只有诊断事件可用易丢的 SharedFlow，不能依靠短暂事件维持最终转写。

采集不阻塞。默认两条已结束语句可排队、每条最长 10 秒；字节/时长同样有界。队列满时显式丢弃新语句并产生 DROPPED 终态与计数，保留已接收的对话顺序。不要继续使用 DROP_OLDEST + trySend 失败计数。

默认一个计算 worker 顺序运行每条 ASR→MT→TTS，采集与播放独立。仅在设备对照实验显示有效时启用阶段重叠；避免多个模型线程池同时耗尽 CPU。普通模式能稳定工作后才提高队列或并行度。

每条语句的 MT/TTS 可恢复失败不取消其他语句。ASR 失败保留带原因的终态。取消异常必须继续抛出；不能被 runCatching/通用 catch 转成模型错误。

MT 缺失：只保留原文并标“翻译不可用”；不得发 MtComplete，不得以 targetLanguage 合成原文。TTS 缺失：保留已有真实译文，明确未播放。ASR 也缺失时阻止开始并给出安装入口。

## R04 音频

使用现有 AudioRecord/AudioTrack 与 AVAudioEngine/AVAudioSession；不先增加 Oboe/C++ 音频层。捕获以真实 sample count 驱动时钟；VAD 保留 200ms 左右 pre-roll，保留句内短停顿，支持静音、短句和长句强制切分。

采集回调不做模型推理/磁盘 I/O；缓冲有上限。播放串行、检查短写/错误、支持立即停止，尾部播放结束后才记 COMPLETE。音频路由变化、耳机拔出、焦点丢失和权限撤销均进入可恢复状态。

扬声器播放默认半双工：播放期间不把合成音重新识别。若启用平台回声消除/耳机场景全双工，必须通过自反馈测试，不能只开一个布尔配置。

## R05 模型执行

每个引擎暴露可注入的最小 port，便于确定性的生命周期/失败测试；不建立通用 inference DAG。ORT Tensor/Result/Options 在所有分支关闭；cache 与 runtime 一起释放；默认一个引擎一个计算并发额度。

ASR/TTS 加载时验证模型输入输出名称、dtype、shape、所需 tokenizer 和缓存角色。缺协议时明确 unsupported。不能以猜测 input 名称、greedy 字符匹配、zero embedding 默认值或零语音掩盖模型不匹配。

native MT 交付包含 C/C++ 源、CMake/NDK 配置、固定上游 revision、构建说明、ABI 与 library load check。load/generate/cancel/free 按一个句柄所有者串行化，释放与取消不能 use-after-free。使用上游模板/tokenizer，截断上下文并按 token budget 限制生成。缓存键包含模型 revision、source、target、文本、上下文；不规范化掉可能改变含义的内容。

当实际模型不可用时，完成可复现构建/下载/验证入口及失败路径，记录准确阻塞项；不把未执行的 native 或任务质量测试标 pass。
