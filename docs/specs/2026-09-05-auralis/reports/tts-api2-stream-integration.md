# ICL、统一 talker 与流式 vocoder 联调

本轮把三个已经独立执行过的候选组合在同一个真实 Python/ORT 1.24.2 CPU 进程中。使用当前 `tts_runner.py` 的生成器及 `bench_tts_streaming.py`，四例均正常 EOS，完整采样参数保持 temperature .9、topK 50、penalty 1.05、seed 20260906。没有播放音频或修改 shared manifest。

## 状态与音频

单一 API2 talker session 同时承担批量预填和逐步生成。适配器仅转换旧生成器的输出容器；没有创建第二份 talker session。参考 encoder 产生 group-major codes，streaming vocoder 先消费这些参考帧，其 PCM 丢弃。随后每四个 target 帧提交一块，最后仅提交实际尾块，不额外 flush 空帧。

每个成功 step 校验 PCM/state shape、dtype、finite 和 position 增量后才替换 state。参考帧不进入目标 sink。生成器耗尽预算仍由既有 EOS 门返回失败。

| 用例 | reference 帧 | target 帧 | 对同 codes 完整 vocoder 裁剪的 max abs diff |
|---|---:|---:|---:|
| 121 English | 106 | 37 | 1.98e-6 |
| 121 Chinese | 106 | 38 | 2.14e-6 |
| 260 English | 88 | 31 | 3.19e-6 |
| 260 Chinese | 88 | 27 | 1.52e-6 |

参考 codes 四例均与原 ICL runner 一致。前三例全部 16 组目标 codes 也一致；260 中文从原 31 帧变为 27 帧。该变化被保留，没有为追旧随机序列加任何修补；不能从先前短 prefix 的 bit-exact 结果推断所有 ICL 输出不变。

## 独立质量复核

把新 float32 PCM 另存为 PCM16，与原 ICL runner 的四条 PCM16 一起重新执行实际 ASR 和既有六人 held-out ECAPA 评估。评分使用冻结的 `bench_tts_stage_quant.py` 数学及相同 soxr 前端。

- 八条音频原始 CER/WER 均为 0。
- 两组四例身份均正确；没有新增身份错误。
- 121 英/中和 260 英的相似度差约 -5.7e-6、-5.0e-6、-1.1e-5；260 中文从 0.24788 到 0.31709。
- 按运行前写入 plan.json 的非退化规则通过本轮四对筛选。只有两位说话人、单 seed，不是生产质量验收。

原执行源码缺失的历史问题仍按 `tts-icl-runner.md` 记录；这里的对照音频已经由当前 runner 字节级复现。新组合的脚本和 runner 都在每个 case 开始时冻结，源文件及所有图/外部数据摘要进入报告。

## 时间的范围

当前未预热的组合基准包含 lazy talker/CP 创建，生成 RTF 约 2.31–3.14，不能当成热会话或设备结果。另列的准备时间包含 tokenizer、speaker、encoder、streaming 图加载和参考 warm-up；哈希/import 开销不计在这些分项内。

参考 vocoder warm-up 每例约 2 秒，这个重复成本没有必要。客户端将在声音准备阶段保存不可变的参考初始 state，之后每个 turn 使用独立工作副本；本轮脚本尚未以此测量热端到端延迟。首次 20 ms RMS 达到 -40 dBFS 的音频可用时间与离线推算的无欠载启动下界分别记录，都不等于真实扬声器播放测试。

证据：`~/Library/Caches/Auralis/tts/streaming-client/icl-unified/` 的四个 case（命令、源码、codes、float PCM、逐块时间），以及 `evaluation/paired/`（冻结 PCM16、plan、ASR、identity、decision 和评分源码）。仍未接入生产包、UI 播放器或实体设备。
