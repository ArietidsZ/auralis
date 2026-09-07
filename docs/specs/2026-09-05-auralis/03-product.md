# 03 产品与 Android

## U01 视觉与导航

显示品牌 Auralis；保留 package/application ID，避免安装与数据迁移。Compose Material 3 原生控件，单 Activity。沿用可构建的 Navigation；本轮不因新版本存在而强制迁移导航框架。

主屏上部深色控制区，下部浅色对话区；深色模式使用完整深色 surface。建议起始 token：深色 `#142128`、浅色 `#F6F8F7`、主要文字 `#17232A`、强调 `#216B62`。这些是本项目设计值，必须验证前景/背景对比度；不由品牌色覆盖平台错误、禁用和焦点语义。

系统字体、本地资源、8dp 间距节奏、48dp 最小点击目标、动态字体与 TalkBack。录音/错误状态同时用文字和图标；尊重系统动画设置。删除运行时 Google Fonts 依赖（若没有其他用途），避免离线首屏依赖下载。

手机保留单列 transcript；宽屏在布局允许时分开控制与 transcript，不新增页面层级。底部录音按钮考虑 edge-to-edge/insets/键盘，长译文与 200% 字体不遮住停止操作。

## U02 首次使用与权限

应用启动不直接请求麦克风。用户点录音时解释用途，再走系统请求。拒绝可重试；永久拒绝给系统设置入口。每次开始复查权限及模型能力，返回前台重新检查。

Setup 显示每个包的下载/校验/runtime 状态、实际字节、失败原因、取消与重试。资源安装中允许离开界面；不因某个错误永久禁用按钮。draft/manifest-only 显示“模型尚未验证”，不显示“就绪”。

开始条件由能力决定：仅有真实 ASR 可选择转写；MT 可用后支持文本传译；TTS 可用后支持语音播放。模式必须用户可见，不能偷偷降级并继续显示“同声传译”。无真实 ASR 时提供安装说明和其它可用界面。

## U03 会话界面

ViewModel 订阅 SessionController.snapshot，并合并设置/安装状态形成单个 InterpretUiState。Compose 使用 collectAsStateWithLifecycle。source/target/voice 选择持久化到 SavedStateHandle 或设置仓库；持久偏好用现有 DataStore。

每条 turn 同时呈现原文、真实译文和处理状态。translatedText=null 表示还没有译文，不以原文补齐。DROPPED、FAILED、CANCELLED 都结束等待动画；失败原因可以局部重试或重新录入。只有播放完成才能显示已播放。

允许复制与清空；默认不把 transcript 持久化。清空时旧任务不能重新插入已清空内容。自动滚动只在用户已接近底部时生效，手动浏览历史不被打断；条目使用稳定 key。

录音中源/目标配置在会话边界切换：停止后应用新配置，或禁用并解释。屏幕旋转保留会话；进后台明确停止/暂停；回到前台不自动重新开麦。处理与监听是不同状态，可同时展示。

## U04 声音档案

录音前明确为自己或获授权声音建立档案。记录创建时间、声音 reference 格式、时长、sample rate、版本、模型兼容信息；UUID 作为 ID。原始声音只留 app 私有存储，禁用备份，日志不包含声音内容或转写。

录制、试听、重录、保存、删除均提供实际结果。播放完毕/失败恢复按钮。只有有效非空参考声音可启用克隆；不传全零 speaker embedding 作为正常默认声音。

WAV 读取解析 RIFF chunks，校验 PCM 编码、通道、sample rate、数据长度与边界；不固定跳过 44 字节。拒绝超大、截断、NaN/Inf 数据。档案路径始终由内部 ID 生成，不信任 metadata 的任意路径。

音频与 metadata 成功后一起可见；失败不留下半档案。兼容读取旧 metadata，成功读取后才迁移。删除校验 ID 并清除对应派生 embedding；错误可重试。

现有私有存储/系统文件保护为默认边界；没有明确威胁模型时不增加自写加密、全局生物识别门或已弃用封装。

## U05 分工接口

lane B 拥有 AppContainer、DialectApp、MainActivity、Android data/UI/资源/Manifest；lane A 拥有 session、inference、audio。B 通过 controller 工厂注入声音 resolver 与模型目录；涉及 Gradle 的依赖由 lane C 写入。

验收必须覆盖：拒绝权限、配置恢复、缺模型、安装失败再试、转写模式、无假译文、终态归约、清空后迟到事件、声音档案异常、无障碍和离线资源。
