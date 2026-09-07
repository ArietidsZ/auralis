# 原生加速验收

当前FP32/CPU实现是质量与协议基线，不是已达到的性能上限。下一阶段以相同Base模型、真实参考克隆和相同机器做对照；不能把CustomVoice预设音色的速度当成ICL克隆指标。

## 候选与顺序

1. 先试当前ORT的CoreML EP，复用已有图与依赖。root实际查询本机ORT1.24.2，CoreMLExecutionProvider可用；是否接管图、是否变快、是否改变质量都须实际检查。[ORT官方配置文档](https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html)提供MLProgram及硬件选择选项。先用真实CP输入做分图/数值/代价检查，profile必须证明节点确实在CoreML执行；未接管或回退不能当加速成功。
2. MLX Audio和原生Swift/MLX实现列为独立对照，固定代码与权重后运行。它们已提供Qwen3-TTS实现和流式入口，但公开示例多使用CustomVoice。其低延迟声明需在本项目的Base+ICL输入和声纹门上复核。[MLX Audio源码](https://github.com/Blaizzy/mlx-audio)、[Speech Swift源码](https://github.com/soniqo/speech-swift)。不因README数字直接替换生产引擎。
3. 只有实测确认现有运行时无法达到同任务对照时，才增加原生实现。对所有候选保留cold load、准备、warm generation、PCM首能量阈值、完整RTF、峰值内存、真实欠载和取消延迟；同时记录未被加速的算子/跨后端拷贝。

## 对照要求

- 正式样本使用同一官方FP32语义基线；输入参考PCM/转录/目标文本、采样协议、随机种子集合固定。跨随机实现不追逐相同token，而是评价完整输出、参考身份和可懂度。
- 先保持现有六人中英质量门；扩大语料前冻结扩充方案。不能降低既有最差声纹/新增身份错误限制来接纳INT8/INT4/BF16候选。一个算子的低误差不是完整语音质量通过。
- 数值或质量失败时记录全例，不拿更短的异常语音换取RTF收益。速度比较按音频时长归一，warm/cold分列，不把准备时间从端到端结果中删去。
- 生产路径只能在实际实体设备上完成内存、能耗、热稳态与p95门后晋升；host和模拟器结果保持独立。

## 消融

优先对现有图/运行时做最小改变，保留逐帧vocoder和单talker已经验证的效果。若CoreML分割/拷贝代价抵消收益，保留CPU配置并记录原因；若独立MLX显著占优且质量过门，再评估依赖和代码净增加量。删除只改变接口名称、并未改变真实执行时序的“流式”层；此次Swift先全句生成后分块的问题已按该原则修正。

## 首次CoreML消融（2026-09-07）

使用现有CP图与归档的真实CP输入（只是算子探测，非质量语料），ORT1.24.2：

- MLProgram/ALL：721节点中511可被CoreML覆盖，划为52个分区，但MLModel创建执行计划失败（error -14）。CPUOnly相同失败；这排除了仅更换该硬件选项就能修复的方案，具体MIL失败原因未定。
- NeuralNetwork/ALL：46分区、272节点，profile证实真实CoreML执行，未伪装CPU回退。logits与CPU最大绝对差0.00458，KV最大0.01160；没有据此虚称完整语音质量失败或通过。
- 关闭profile、交替两后端24次，同输入CPU中位6.84ms，CoreML NN 20.44ms，后者约2.99倍耗时。并发主机负载下的单算子诊断，不是手机RTF。

结论：当前直接CoreML配置不进入默认运行时。保留CPU，不增加这个会变慢的调度分支。后续若试静态图或MLX，作为不同实现重新过实际质量/性能门。证据 `cache/reports/tts-coreml-cp-probe/`、`tts-coreml-cp-ablations/`，含源码、feed/model摘要、provider profile和逐次时长。
