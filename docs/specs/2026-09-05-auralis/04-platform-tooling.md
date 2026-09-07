# 04 iOS 与工具链

## P01 iOS 原生实现

接入官方 ONNX Runtime Apple 包的实际 ORT 类型，或通过模型对照确认可统一的 native runtime。固定依赖来源/版本，提交项目依赖声明。删除返回空数组的伪 OrtSession；SDK 不存在时必须构建诊断或明确 unavailable，不能创建“已加载”假 session。

增加 TranslationEngine 协议与真实 MT 阶段，遵守 02 的会话/语句 ID、失败语义与关闭顺序。actor 负责会话状态及 mutable 推理句柄，@MainActor 只负责可观察 UI；不能在主 actor 上同步跑模型。取消需等在途推理/音频结束后释放。

使用 AVAudioSession interruptions/routeChange 通知，正确声明和请求录音权限；后台处理与 Android 一致。SwiftUI 各屏接入共享 catalog、包可用性与真实错误；不以 CoreML provider 选择直接声称 ANE 已启用。

缺模型或缺可编译 SDK 时，优先完成真实失败路径、协议、项目依赖与验证入口，保留准确 blocker。iOS 端到端能力未通过真实运行时测试前禁止宣称完成。

## P02 根命令

增加薄的 `scripts/doctor` 与 `scripts/verify`；可使用 Python 标准库调度原生命令。保持现有目录，不写新的构建系统。

- `scripts/doctor`：只检查 JDK、SDK、NDK/CMake（native 请求时）、完整 Xcode、Python、模型目录，输出清楚的修复命令。不打印凭据/私有配置。
- `scripts/verify --mode fast`：共享 schema/fixture、Python 轻量检查与确定性单测，无大模型下载。
- `scripts/verify --scope android`：契约 + `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`。
- `scripts/verify --scope ios`：契约 + xcodebuild 无签名构建/测试；完整 Xcode 缺失是 blocked。
- `scripts/verify --mode full`：平台与工具检查汇总，不因一个缺环境而漏报其他独立结果。
- `scripts/verify --scope models --models-dir PATH`：实际文件/角色/兼容性/任务测试；缺任何要求的数据或 runtime 以 blocked/fail 返回。

退出码：0=所请求检查全部通过；1=代码/测试失败；2=缺必要环境/产物；3=契约无效；4=参数错误。多失败返回优先级 3、1、2、4（参数解析错误立即 4）。JSON 报告写声明的 output 目录，列出 pass/fail/blocked/skipped、命令、原因和时长；跳过不能算已通过。

`bootstrap` 只在实际需要时提供明确安装指引；`clean` 非本轮必要项，不新增危险的宽泛删除入口。

## P03 工具与版本

保留可用工具链组合，统一必要的版本声明。版本升级需解决具体兼容问题，有官方依据并跑构建；不照抄旧方案的“>=最新”清单。优先官方依赖源；已有镜像若本机访问需要则做显式可选配置，不能因猜测删除导致无法构建。

Python 轻量验证依赖与重量级导出依赖分开，避免 import torch 阻断 schema/CLI。CPU/GPU ORT 包互斥，不默认同时安装。下载固定 revision；导出保存 processor、tokenizer、config、外部数据、量化参数和 provenance。

删除“只创建 session 就算验证成功”的路径。任务保真度工具必须运行实际候选模型；若没有实现该 bundle 的 runner，返回 unsupported，不能打印成功。ASR 比较 CER/WER，MT 用固定双语集和人工语义核对，TTS 比较可听结果/长度/有限值/回转写与声音一致性；不能用一个 embedding cosine 阈值代替可懂度。

CI 分开 Linux Android/Python 与 macOS iOS 工作；复用根命令，锁定 action 来源和工具版本。无模型 PR 检查不下载多 GB 权重；设备/模型检查显式触发并使用同一报告格式。没有真机 runner 就标未配置，不造性能绿灯。

lane C 拥有 shared、convert、scripts、CI、所有 Gradle 配置和 iOS。优先发布共享 schema/fixtures 与 Gradle 接入，再做工具与 iOS；所有新增依赖需求通过分工文件交接。
