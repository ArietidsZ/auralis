# 主审第一轮复核

日期：2026-09-05。结论：**需要修改，尚未验收**。各 lane 保留原写入权；本文件记录发现时的状态，修复后由新的验证报告关闭条目。

## 已独立执行

- `python3 scripts/verify --mode fast --output /tmp/auralis-review-fast`：exit 0，4/4 检查通过，25 个 Python 单测。
- 反例：创建空临时目录 `<tmp>/asr`，运行 `python3 convert/validate_models.py --models-dir <tmp> --package asr`：**错误地 exit 0**，报告 0 文件、0 roles 通过。已要求 C 增加回归测试。
- 直接调用 `validate_rel_path`：`asr/./a.onnx` 和含 NUL 的路径均被接受。已要求 C 修正并让三端消费相同负例。
- 官方 ORT v1.24.2 [ort_session.h](https://raw.githubusercontent.com/microsoft/onnxruntime/v1.24.2/objectivec/include/ort_session.h) 明确 outputNames 为 NSSet；Swift 调用应传 Set，并在使用 ORT 类型的文件导入模块。

## 修改请求

| ID | lane | 发现 | 关闭条件 |
|---|---|---|---|
| RV01 | C | Python 模型验证对空 draft bundle 返回成功 | 空文件/空roles/draft/任一未验证字段/未实现runner全部不能返回0；回归通过 |
| RV02 | C/B | Python、Kotlin、Swift 的 manifest 接受规则不一致 | 同一组正负 fixtures 在实际消费方验证；包ID、版本、roles、externalData、revision、hash严格一致 |
| RV03 | C | CI 用 bash 执行 Python scripts/verify | 改为正确解释器；校验可运行工作流与依赖版本描述 |
| RV04 | C | simctl 文本正则不能匹配真实列表 | 使用JSON和可用iOS simulator UDID；用样例测试 |
| RV05 | C | ORT导入/Set签名、dtype/shape回退、actor逃逸session | 按实际SDK签名修复；native句柄操作留在所有者内；不能把解析错误变成空shape |
| RV06 | C | iOS ready仅看存在或允许缺hash/空文件；路径移除package前缀 | 单一严格验证及runtime探测；包路径隔离；流式hash |
| RV07 | C | iOS stop取消后立即清空句柄/释放，TTS加载失败阻断转写 | 启动半失败、停止等待、session/turn ID、取消传播及可见背压测试 |
| RV08 | C/B | 下载/安装更新原子性和manifest位置不一致 | 两文件第二个校验失败保留旧包；崩溃恢复；符号链接拒绝；安装/消费路径一致 |
| RV09 | C/A | 文档混淆模型 source.revision 与 native runtime commit | 分别记录固定来源；不能用HuggingFace模型commit编译llama.cpp |
| RV10 | A | `joinAll(workerJob ?: Job(), ...)` 等待从未完成的占位Job | 只等待真实任务；启动load失败/关闭/stop-during-start可结束 |
| RV11 | A | capture异常重抛到独立SupervisorJob launch | 硬件故障转为状态与安全清理，不产生未处理异常；取消正常传播 |
| RV12 | A/B | app级manager缓存session与controller独占所有权冲突 | controller/探测资源隔离；旧关闭不影响新会话 |
| RV13 | B/A | runtimeProbe恒null、embeddingExtractor未接入 | 有效包可实际探测；真实encoder接入；没有实现不能称已完成 |
| RV14 | B | version marker跳过后续hash允许同体积损坏 | 无法证明内容未变时重验，负例通过 |
| RV15 | B | UI collectAsState且缺后台停止，回调捕获旧UiState | 生命周期感知订阅；后台停录，旋转保留；权限撤销正确处理 |
| RV16 | B | WAV reference无视原始sampleRate硬标16k | 实际重采样或拒绝；大小上限在readBytes前；embedding有限值检查 |
| RV17 | A | fake capture关闭后复用、半双工测试在播放时要求新任务入队 | 修正测试驱动与真实语义一致，实际执行生命周期与背压测试 |

## 执行状态

修改请求已交回 Pi GLM A/B/C。C 在资料检索时报告 operation aborted；后续已排队的修复请求进入执行记录并开始处理，未另建代理或替换模型。等待新的真实检查结果后才变更验收结论。

本轮未执行全量 Gradle：A/B 仍在写入，待稳定后由 C 串行集成。iOS完整构建、模型质量及真机性能仍需各自的环境和产物；这些不能用fast检查代替。
