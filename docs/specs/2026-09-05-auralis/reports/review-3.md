# 主审第三轮：Android 产品接入

2026-09-05。B 完成后主审接手 B 的集成文件；A 继续独占运行时/音频，C 继续平台与工具链。为避免账号限流，没有重开 B。

## 已核对的交付

读取 Gradle XML：data/UI 子集共 43 个测试、0 失败。该结果对应 B 交付时的文件，不能代表 A 子集或后续集成已经通过。

## 本轮实际修复

- 删除由 ViewModel 异步上报的 sessionOccupied 布尔值：它漏掉 STOPPING，也存在状态通知与换包之间的竞态。
- 增加 `ModelAccessGate`：模型句柄持有读取凭证；安装、删除、恢复/校验持有独占修改凭证。凭证归还幂等，多个读取方全部退出前不能换包。A 在 Pipeline 的模型加载前获取凭证，停止时等任务和句柄释放后归还。
- AppContainer 的 speaker encoder 每次使用独立 manager，持有同一文件占用保护，并在提取结束后关闭全部句柄；不永久缓存旧包的 native session。
- ModelRepository 的 refresh 与 install/delete 串行，恢复失败按包显示错误并保留备份；安装 Job 改为实际受 scope 管理的 lazy job，删除额外 Job 容器。
- 提取 `ModelPackageFiles` 供实际安装和启动恢复调用，使用临时文件验证第二步 rename 失败、回滚失败、崩溃恢复与备份保留。测试无需真实模型或设备。
- 录音按钮在会话进行中始终能发出停止请求，不再被模型探测/安装状态禁用。

## 已执行的检查

使用本机缓存 Kotlin 2.1.0 编译器与 JUnit 4.13.2 单独编译并执行两个真实生产类和测试：

- ModelAccessGateTest：5/5 pass，覆盖多个读取方、竞争写入、跨线程、重复关闭和异常释放。
- ModelPackageFilesTest：5/5 pass，覆盖成功替换、激活失败恢复旧包、两次 rename 之间的崩溃、回滚失败后再恢复、恢复失败保留唯一备份。
- 共 10/10，JUnit exit 0；未借用 fake Android framework。
- `git diff --check` 通过；初始 `git diff --cached --binary` 与基线快照完全一致，未改变用户原有索引。

## 仍待集成

- A 的 acquireModelLease 构造参数及全生命周期归还、native reload/cancel/free 修复、A 子集测试。
- A/B 源码稳定后由 C 串行执行完整 Gradle 单测/assemble/lint；此之前不标工程完成。
- iOS、真实模型和真机门沿用前两轮尚未关闭项。

## 调度与消融

实际 provider discovery 确认 Pi GLM 支持 `low`。A 的过长分析轮次已取消，原 agent 在相同模型下以 low 重启；C 的后续请求也设 low。重复的截断完成通知属于旧轮次，不是验收证据。

新增文件占用门用于解决已发现的竞争，替代不可靠的 UI 布尔状态；未引入 DI、通用资源管理框架或额外 controller 包装。文件替换逻辑由安装与恢复共用，故保留；只在测试中注入 rename 失败。
