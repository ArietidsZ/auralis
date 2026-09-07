# Auralis 重构规格

日期：2026-09-05。状态：设计冻结，可实施。范围：Android、iOS、模型工具、共享数据、验证。

2026-09-07 接续状态见 [当前集成报告](reports/integration-2026-09-07.md)。[首轮交付报告](reports/final-report.md) 保留为历史。真实 ASR/MT/TTS 已执行，API2、流式播放和来源记录正在集成；实体设备产品门仍未完成。

## 目标与验收边界

把现有离线跨方言语音应用改成输出可信、可恢复、可测量的原生产品。保留 Auralis 品牌、离线处理、方言识别、翻译、声音档案与声音克隆目标。Android 为首个完整交付平台，iOS 同步修复真实性与契约。

“先进”以任务正确率、端到端延迟、内存、耗电、无障碍和维护成本判断。模型论文、桌面速度、编译通过都不能代替手机上的端到端证据。未通过设备门禁的能力显示为未验证，不写成已支持。

本规格替代旧方案中与本次决策冲突的内容；保留旧文件作为历史记录。旧文档标注的“已确认”“已批准”不能代替本次代码核实。

## 重构起点（2026-09-05，历史）

- 分支：`baseline/auralis-android-overhaul`；本地 HEAD `e90977b`，跟踪分支显示落后 3 个提交。本次从当前工作树继续，不拉取、重置、暂存或提交已有改动。
- 初始 staged/unstaged/untracked 状态与 95 个源码文件已保存至 `.superpowers/refactor-2026-09-05/baseline/`。这份本地快照用于区分本次修改与用户既有工作。
- 已有 `AppContainer`、Android shared catalog 消费、messageId、ONNX Runtime 1.22.0 和部分关闭修复；不重复实施旧清单。
- Android `PipelineOrchestrator.kt:361` 在 MT 缺失时生成 `runtime=passthrough` 的“翻译成功”；`InterpretViewModel.kt:114` 又可用原文补译文。必须删除这种成功路径。
- 三个 `DROP_OLDEST` 通道配合 `trySend().isFailure` 统计，不能准确反映被淘汰的旧任务；停止任务的 join 与阻塞录音解除顺序需要重新验证。
- `InterpretViewModel.onCleared()` 向已取消的 `viewModelScope` 发起释放；Activity 结束直接释放共享 ORT sessions，所有权仍不闭合。
- iOS `OrtSession.run()` 返回 `[]`，管线无 MT；ASR/TTS 存在零值回退。
- `shared/model-manifests/` 同时使用空文件集、空哈希、`size_bytes`、`sizeBytes` 和 `manifest-only`。TTS 清单声明 5,919,108,340 字节文件；这是文件体积之和，不能当作峰值 RAM。
- 三个 Android asset pack 中没有 ONNX/GGUF/native 模型产物。真实声音质量和设备速度当前没有验证条件。
- 本机 `xcodebuild` 当前指向 CommandLineTools，未具备完整 Xcode；`adb` 不在 PATH。平台检查必须如实区分缺环境与代码失败。
- 重构前执行 `bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain` 成功（20 秒，52 个任务，47 个 up-to-date）；这是既有工程基线，不是本次实现的验证。

## 已定决策

| 决策 | 执行规则 |
|---|---|
| 原生 UI | Android Compose、iOS SwiftUI；沿用各自生命周期与音频 API |
| 依赖管理 | 保留构造器注入与小型 AppContainer；本轮不引入 Hilt/Koin/KMP |
| 目录 | 保持 `android/`、`ios/`、`convert/`，增加根验证入口；不在并行修改期间搬迁目录 |
| 运行时 | 先使现有边界严格可信；模型与 runtime 逐对验证，禁止根据文件扩展名推断支持 |
| 优化选择 | 量化模型先测 CPU，其他 EP 按模型和设备对照；没有性能收益的加速路径删除 |
| 会话 | 一个明确的会话所有者；采集、计算、播放各自状态，所有任务带会话与语句 ID |
| 失败 | 转写、翻译、播放各自成功/失败；缺 MT 不生成译文，缺 TTS 可保留真实译文 |
| 流式 | 只给实际提供增量结果的适配器标 streaming；完整句推理标交替传译 |
| 共享 | JSON 契约与测试样本共享；不共享推理编排运行时代码 |
| 后台 | 当前实现前台会话；进入后台明确暂停/停止。后台麦克风服务不是本轮依赖 |
| 提交 | 保留原有 git 索引；不自动 push、发布、合并或覆盖既有改动 |

## 阅读顺序

1. [共享数据与模型](01-contracts-models.md)：资产、能力、校验与兼容。
2. [会话、音频与推理](02-runtime.md)：所有权、事件、故障与 native 交付。
3. [产品与 Android](03-product.md)：界面、权限、档案与状态映射。
4. [iOS 与工具链](04-platform-tooling.md)：真实运行时、验证入口与 CI。
5. [验收与消融](05-verification-ablation.md)：功能门、性能门、删除条件。
6. [Pi GLM 执行](06-pi-glm-handoff.md)：三个写入分区、依赖、交付格式。
7. [TTS运行时优化](07-tts-runtime-optimization.md)：实际瓶颈与质量门。
8. [TTS下一版包](08-tts-next-package.md)：单talker、ICL、流式vocoder和跨端接口。
9. [原生加速验收](09-native-acceleration.md)：同任务加速对照与选择规则。
10. [证据](evidence.md)：官方资料与设计消融结果。

## 完成定义

工程门：契约、单测、Android 构建/lint、Python 检查及可执行的 iOS 检查分别报告。没有环境的检查返回 blocked，不伪装 pass。

产品门：真实资产安装、真机 ASR→MT→TTS、目标语种与克隆质量、离线性、生命周期与热稳定性均通过。工程门通过而产品门缺证据时，交付标记为“工程完成，产品验证待补”，不能称作最先进性能已实现。
