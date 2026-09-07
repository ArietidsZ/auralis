# 证据与设计消融

检索日期：2026-09-05。先按项目要求使用 Context7 官方库解析/查询；Android 查询部分不够聚焦，补查官方页面。以下只把来源明确支持的内容作为事实，设备表现保留为待测。

## 官方资料与影响

| 来源 | 核实内容 | 设计影响 |
|---|---|---|
| [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations) | 小型应用可使用 package 边界；UI 生命周期感知收集，ViewModel 处理状态；domain 层按复杂度引入 | 保留手工 DI/原生 Compose，不强制新增多模块或 use-case 层 |
| [Android state saving](https://developer.android.com/topic/libraries/architecture/saving-states) | ViewModel 与保存状态各解决不同生命周期问题 | 会话与持久偏好分开，Activity 不拥有 native sessions |
| [ONNX Runtime mobile](https://onnxruntime.ai/docs/tutorials/mobile/) | 量化模型从 CPU 开始；EP 性能依赖设备和图，分区可能更慢 | 加速策略以对照实验选择，不宣称默认 NPU/ANE |
| [ORT usability checker](https://onnxruntime.ai/docs/tutorials/mobile/helpers/model-usability-checker.html) | 检查移动模型适用性并比较 CPU/硬件 EP | 为每个实际 bundle 提供兼容性与性能门 |
| [llama.cpp Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md) | 提供 NDK/CMake 原生编译路径 | 交付可复现 native 来源和构建，而非仅外部 .so 占位说明 |
| [llama.h](https://github.com/ggml-org/llama.cpp/blob/master/include/llama.h) | model/context、模板、采样与 CPU abort callback 有实际 API | 取消/释放按调用所有权实现；固定 revision 后核对签名 |
| [Hy-MT 1.25-bit 模型卡](https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF) | 部署说明明确依赖 STQ kernel/PR #22836 | 不从 .gguf 扩展名或自动生成的通用用法推断当前主干兼容 |
| [Qwen3-ASR 官方](https://github.com/QwenLM/Qwen3-ASR) | 提供离线/流式模型与 Transformers/vLLM 推理；方言识别范围并非合成范围 | 保留模型候选；不能把服务端 streaming 示例等同手机 API |
| [Qwen3-TTS 官方](https://github.com/QwenLM/Qwen3-TTS) | Base 克隆使用参考声音，可选 reference transcript；embedding-only 与完整 conditioning 有差异 | 删除全零参考的默认克隆路径；保留明确模式 |
| [llama.cpp TTS](https://github.com/ggml-org/llama.cpp/tree/master/tools/tts) | 当前工具展示 Qwen3-TTS 1.7B Base 及 speaker file | 将共享 MT/TTS native runtime 纳入候选；不推断 0.6B、移动 streaming 或性能已证实 |

以上不构成“选定模型世界第一”的证据。产品性能以 05 的真机测量为准。

## 设计阶段消融：已执行

方法：从旧设计移除每项提案，再沿当前构造器、数据流和写入边界检查是否丢失需求。这是结构性检查，不能解释为运行时速度实验。

| 被移除的设计 | 检查结果 | 本次选择 |
|---|---|---|
| Hilt/Koin 迁移 | 当前 AppContainer 已能构造 model manager、repository、pipeline；单一所有者和可注入 engine port 不依赖 DI 框架 | 不引入；只修所有权与测试边界 |
| KMP 共享编排 | Android/Swift 各有完整原生 API 边界；共用 catalog/manifest 已可避免数据漂移 | 只共享契约与样例 |
| `apps/`/`tooling/` 搬迁 | 不改变输出可信、关闭、模型协议；却会触碰三个 lane 的路径且与已有 dirty index 相交 | 保留现目录 |
| 多个推理 worker 默认重叠 | 现状 stage 事件争写全局状态，DROP_OLDEST 不能保留完整语句账目；单计算 worker 满足正确性需求 | 默认串行计算＋独立采集/播放；测得收益再增加并行 |
| Oboe/第二 ASR/后台 FGS | 无真机延迟证据证明现 API 不足；前台会话与严格退出可由现 API 实现 | 不作为基线依赖 |
| 三语言绑定代码生成器 | 当前数据规模小、平台都有 JSON 解码；统一 schema 与 fixtures 可校验一致性 | 直接消费 JSON |
| 自动 CPU→NPU、CoreML→ANE 标签 | ORT 官方资料要求按图/设备比较，代码中尚无运行证据 | 默认 CPU/明确实际 EP；移除推测性硬件宣传 |
| MT 失败直通、TTS 零值补偿 | 移除后可以保留原文/真实译文并呈现 unavailable；用户可获得准确状态 | 删除假成功，保留可恢复的部分能力 |

实现后的 runtime 消融由各 lane 根据可用设备执行。没有数据时保留上述简单基线并报告 blocked；不补造对照数字。
