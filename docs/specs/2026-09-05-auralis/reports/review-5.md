# 主审第五轮：完整 Android 测试与 JNI 审查

2026-09-06（Asia/Shanghai）。

## Gradle 实际结果

`bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain`：执行 114 个测试，1 个失败。失败位于 HyMtTranslationEngineTest 的取消异常原因断言；代理独立脚本的 61/61 结果不能覆盖真实 Gradle 行为。APK 构建通过，该组合命令 exit 1。

主审补充修复 AudioRecorder：阻塞 read 不再持有 stop 所需的 recordLock；startRecording/onReady 纳入 try/finally，保证启动中权限撤销或回调异常也释放硬件。随后独立运行 `:app:lintDebug` 成功（exit 0，6 秒）。最终仍须等取消测试修复后重跑完整命令。

## Native 事实与检查

通过官方 [PR API](https://api.github.com/repos/ggml-org/llama.cpp/pulls/22836) 核实：state=open、merged=false、head=`1e411d8f5a1e23525fa3265dfb4bd76265465397`。模型仓库 revision 与此 native runtime revision 继续分开。

主审从该固定 commit 获取真实 llama/ggml 头文件，缓存于 `/private/tmp/auralis-native-review/include`。使用本机 clang++ 与真实 JDK JNI 头执行 `-std=c++17 -fsyntax-only -Wall -Wextra`，当前 JNI 源码语法检查 exit 0。这不是 NDK 链接、模型运行或内存正确性证明。

源码审查发现并交 A 修复：释放时 lock_guard 持有被删除的 mutex；跨循环 batch 指针生命周期；未清除前一请求的 KV；自行发明的语言/上下文特殊 token；未分批 prefill/未约束总 context；token piece 扩容与 UTF-8/JNI 字符串边界；CMake 猜测归档路径。这些不得因语法编译通过而跳过。

## 当前分工

A 收窄到 NativeHyMtRuntime/HyMtTranslationEngine/对应测试、cpp/JNI/CMake 与文档。主审接手其余 Android 集成；B/C 继续第四轮约定的 iOS 会话和数据核心修复。未关闭的产品门仍包括完整 Xcode、NDK `.so`、真实模型与真机。
