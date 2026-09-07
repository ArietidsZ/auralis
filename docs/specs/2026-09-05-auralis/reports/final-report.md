# 重构交付与剩余门禁

2026-09-06。Specs、三个 Pi GLM 的并行实施及主审本机复核已交付。**本机可执行工程检查通过；完整产品与模型运行时交付尚未完成。**

## 最终验收结果

`python3 scripts/verify --mode full`：**12 pass、0 fail、3 blocked，exit 2**。完整机器报告：[verification-final.json](verification-final.json)。阻塞项没有算作通过。

| 范围 | 真实结果 |
|---|---|
| Android | 114 个 JUnit 用例、0 失败；assembleDebug 与 lintDebug 通过 |
| iOS 会话/归约/播放协调 | 28 个本机 Swift 行为用例通过；生产源码参与编译 |
| iOS 数据核心 | SwiftPM 编译与 core-checks 通过；共享正负 fixtures、真实文件安装/回滚/取消/路径检查及运行中读取锁验证 |
| AVFoundation | 真实 macOS AVAudioEngine 播放器类型检查通过；SDK 有两项弃用提示，未以假的 AVFoundation 替代 |
| Python | convert 40 个、scripts 8 个用例通过；共享契约与 fixture 检查通过 |
| Native 适配器 | 7 个 C++ token/buffer 用例通过，启用 ASan/UBSan；JNI 对固定上游头文件的严格语法检查通过 |

APK：`android/app/build/outputs/apk/debug/app-debug.apk`（91,099,339 bytes）。**未包含模型权重和 MT native `.so`**，不应视为可完整传译的发布包。

## 已完成的关键改动

- 统一模型清单与明确的 draft/verified 状态；无缺失校验的 ready、原文伪译文、空语音伪成功。
- Android/iOS 会话与语句身份、有限队列、停止/释放顺序、可恢复失败；去除 FIFO 错配与 UI 重复状态。
- 两端模型读取与换包互斥；运行时句柄按会话隔离，探测使用临时实例；停止中的原生句柄仍受文件占用保护。
- Android Auralis 界面、权限时机、声音档案安全读写、无障碍基础与离线资源。
- iOS 播放完成使用 dataPlayedBack；取消与调度串行，硬件停下后才结束取消，重复回调不会二次恢复 continuation。
- 根验证入口/CI 真正运行 Swift 数据和会话核心；失败构建不执行旧二进制；契约错误保持退出码 3。
- Native JNI 的模板、UTF-8、token sizing、缓存清理、内存/锁释放及固定来源构建已修正。

## 仍未完成或未验证

1. **完整 iOS 应用构建**：本机只有 CommandLineTools，没有完整 Xcode/iOS SDK；SwiftUI、AVAudioSession、ORT 绑定与项目整合仍需原生平台构建。host 核心通过不能代替它。
2. **NDK 链接及 MT runtime 包装交付**：实际 Android SDK 中没有 NDK；JNI 尚未生成并验证 Android `.so`。语法检查不等于链接或模型运行通过。
3. **真实模型协议与保真度 runner**：模型产物未提供；iOS MT native 实现、完整 TTS bundle 执行及 Python 任务保真度 runner 仍有未实现/unsupported 边界，需要针对实际 bundle 继续完成。不能只归因于缺设备。
4. **设备产品门**：已找到 SDK 内 adb，实际 `adb devices -l` 返回 0 个连接；未安装 emulator。真实 ASR→MT→TTS、方言/克隆质量、端到端延迟、内存及热稳定性尚无实测证据。

“最先进性能已达到”没有证据，未作此声明。模型清单维持 draft，缺能力时界面/工具如实报告。

## 交付与维护

三个 Pi GLM 使用 `ark-coding/glm-5.3-flash`；后段实施由 max 调为 low，以减少长时间分析，主审以实际编译和失败反例验收。各 lane 报告是阶段记录，当前结论以本文及最终 JSON 为准。

原有 staged/unstaged 工作已保留；git 索引与初始 binary patch 一致，未提交或推送。源码恢复基线位于 `.superpowers/refactor-2026-09-05/baseline/`。

消融保留了简单的构造器注入、原生 UI、单计算 worker 和共享 JSON；删除无依据的 DI/KMP/目录迁移/额外音频层提案。新增的文件凭证、播放完成协调和 token adapter 均对应已复现的问题并有行为测试；设备性能消融继续属于未完成门禁。
