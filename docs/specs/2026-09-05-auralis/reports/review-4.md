# 主审第四轮：真实编译与 iOS 分工

2026-09-05。

## Android

主审补充的资源占用与文件恢复已与 A 的 acquireModelLease 接口接入。运行：

`bash ./gradlew :app:testDebugUnitTest --tests 'com.dialect.interpreter.data.*' --tests 'com.dialect.interpreter.ui.*' --offline --console=plain`

真实结果：BUILD SUCCESSFUL，3 秒；主源码和所有测试源码编译通过；data/UI 7 个测试类共 **53 个用例、0 失败**（原 43 + 主审新增 10）。这关闭了 review-3 的 Android 构造参数接入待办；A 的运行时测试另行验收。

另运行 `bash ./gradlew :app:assembleDebug :app:lintDebug --offline --console=plain`：APK 构建通过；lint 1 error / 0 warnings，唯一错误是 AudioRecorder.kt:68 的 MissingPermission。已交 A 在实际录音路径补可识别权限检查与撤销处理，禁止禁用该检查。该组合命令 exit 1，因此 Android 工程门尚未全过。

## 工具链

`python3 scripts/verify --mode fast --output /tmp/auralis-review-c-final` 真实 5/5 pass。下载/校验 Python 测试可运行；不能据此声称 Swift 已通过。

## iOS 未通过项

主审直接运行 `swiftc -typecheck -module-cache-path /tmp/auralis-swift-review-cache ios/DialectInterpreter/Data/PackageVerifier.swift`，exit 1：validatePath 的表达式不能完成类型检查。证明没有完整 Xcode 仍可执行 Foundation/CryptoKit 核心编译检查，不能全部归类为环境阻塞。

此外代码核对仍发现：ModelRepository 的缺失 enum case/导入、URL与String混用、整包安装双前缀路径；manifest 校验与 canonical fixtures 不一致；iOS启动/关闭任务未跟踪、重启竞态、队列取消早于等待注册的竞态，以及UI仍按FIFO关联没有ID的旧事件。这些均已明确交回修复。

## 当前写入分工

- A：Android runtime/audio/session/native 与其测试，继续修复完整子集。
- B（同一 Pi GLM、low）：iOS PipelineOrchestrator、Audio、UI、会话行为测试。Android B 区域已交主审。
- C（同一 Pi GLM、low）：iOS Data、OnnxModelManager/OrtRuntime、工具与项目接入。要求实际运行纯核心 Swift 类型检查/测试，与 B 不写同文件。
- 主审：Android 产品接入、完整 Gradle 集成、review/dispatch。

完整 Xcode/iOS native 构建、真实模型与真机性能仍单列待验证；当前不作全仓工程完成声明。
