# Android 工程验收

日期：2026-09-06（Asia/Shanghai）。结论：**Android 工程门通过；native 模型与设备产品门未通过验证**。

## 最终命令与结果

```text
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
BUILD SUCCESSFUL in 6s
53 actionable tasks: 15 executed, 38 up-to-date
```

主审读取 JUnit XML：114 个测试、0 failures、0 errors。Lint XML：0 findings。APK 已生成：`android/app/build/outputs/apk/debug/app-debug.apk`，91,099,339 bytes。构建使用已有缓存，未改变用户原有 git 索引（与初始 binary index patch 比较一致）。

此前取消测试在 Gradle 下失败的原因是 coroutine recovery 添加额外 CancellationException；最终测试沿完整 cause 链确认原始 AbortedException 保留，不依赖固定包装深度。

## JNI 补充验收

主审发现 size-probe 的正常负返回被当作失败，会让任何非空 prompt 无法分词。现已改为按负值分配准确容量，并明确拒绝 INT32_MIN 溢出、二次失败和越界返回。Token piece 也按实际所需字节扩容，带上限。

- 生产 token adapter 的 7 个本机 C++ 契约用例通过，启用 AddressSanitizer / UndefinedBehaviorSanitizer，使用固定 commit 的真实 API 头文件。API 替身只用于缓冲区契约，不作模型推理。
- 更新后的 JNI 通过 `clang++ -std=c++17 -fsyntax-only -Wall -Wextra -Werror`，使用真实 JDK JNI 头及 pin `1e411d8f5a1e23525fa3265dfb4bd76265465397` 的 llama/ggml 头。
- 补上 backend 一次初始化、创建失败的 RAII 清理、模板渲染长度检查；取消标记持续至释放/重新加载，不能被迟到的 translate 清空。
- CMake 必须校验 Git commit，强制静态依赖与 PIC，直接链接上游 targets；删除无法校验 pin 时继续构建的分支。
- native 构建与 host 测试入口见 `android/app/src/main/cpp/hymt_jni/README.md`。

## 未验证内容

没有执行 Android NDK 链接，APK 中没有 `libhymt_jni.so` 和真实模型权重。没有进行真机录音、真实 ASR→MT→TTS、克隆质量、端到端速度或热稳定性检查。共享清单仍为 draft，对应能力不会被虚报为 ready。

上述工程结果不能表述为产品已能完整离线传译或达到最先进性能。iOS 核心代码与完整平台构建也不在本报告的通过范围内，B/C 仍在按第四轮分工处理。

## 消融结果

保留直接 SessionController、单计算 worker、手工 DI、严格共享 JSON；删除 UI 占用布尔值与版本 marker 缓存。模型凭证保护替代异步 UI 推测；文件换包仅保留同卷替换/恢复，5 个真实文件用例验证必要性。Native helper 仅提取有实际契约错误的 tokenizer/byte-buffer 逻辑，未增加推理框架或额外后端。
