# 统一 FP32 talker 图消融（2026-09-07）

**真正动态 sequence/past 的单图可替代原两图的计算；当前应用的主要收益是避免每轮重建模型，不是再减少 46% 峰值内存。** 模型仍为原始 FP32。全部工作限于 `convert/bench_tts_unified_talker.py` 和独立缓存，没有修改生产 engine、runner 或 manifest。

## 选择过程

先直接复用原始 decode 图，从空 KV 开始逐 token 预填。10、98、116 行的 logits、末 hidden、56 个 KV 和后续 decode 数值对拍通过；两语所有 codec 和波形也精确相同。但是 98/116 行预填从原图约 0.26/0.30 秒增至 2.53/3.1 秒，约慢十倍。因此保留它作消融证据，不选作全局默认。

随后重导出单一动态图：官方 attention/MLP/norm/rotary/head 模块与 FP32 参数不变，只用显式 tensor KV concat 和 causal mask 替代非张量缓存/掩码构造。trace 输入为 sequence=4、past=3；实际验证覆盖 sequence=10/98/116、past=0 以及带已有 KV 的后续单步。这不是只改 ONNX shape 元数据。

长输入来自官方 Qwen `generate_voice_clone` 到 `talker.generate` 入口的真实 dump：121 的 116 行、260 的 98 行，各含中英文；不是重复拼接的合成长提示。官方 position_ids dump 是整数值的 float32，调用 ONNX 前验证每值为整数并无损转 int64。首次未转换的 ABI 错误保存在 `probe-initial.log/json`。

## 数值与输出

- 使用冻结 runner SHA `4df289d3ab4b15f1163112e00e0a81323e8a2650edbf833dc72b39e5293b44be`；不是之后加入 streaming callback 的生产 runner。
- 对六组输入比较全部 KV、logits、末 hidden 和后续 decode。**实际消费输出 6/6 通过原定 `rtol=atol=1e-4`**；末 hidden 最大绝对差为 3.05e-5 至 4.67e-5。
- 额外检查全部历史 hidden 时，260-Chinese 的 `[1,98,1024]` 有一项严格 allclose 未过：max abs=1.2791e-4、RMS=8.44e-6、cosine=0.9999999999988。它不影响该前端实际消费的末 hidden，且 KV/logits/续 decode 均过。`dynamic-validation.json` 保留 `status=numerical_mismatch` 和独立 `consumed_outputs_pass=true`，没有改容差或抹去结果。
- 同一 121 参考、默认 temperature=.9/topK50/penalty1.05/seed20260906、384 帧预算，中英文原两图和新单图均为 40 帧。**全部 16 组 codec 完全相同，原始 waveform max abs=0**。
- 新图两语及对应基线的真实 ASR 共 4 例均 CER=0。目标文本为预定的 London/上海火车票否定句；未靠音频非空判断质量。

## 当前出货策略的直接比较

当前 Android/iOS 策略为每 turn：prefill 创建→运行→释放，再 decode 创建→运行→释放。补充窗口使用两个新进程，分别执行 **3 轮，每轮 10/98/116 行各一次，共 9 turn**。统一图只创建一个 session，跨 9 turn 复用。创建、计算+KV复制、释放和初始化分别记录。

| 指标（talker-only host） | 当前串行释放 | 新单 session 复用 |
|---|---:|---:|
| 9 turn 实验墙钟 | 21.37 s | 7.35 s，含一次初始化 |
| 模型创建累计 | 17.91 s（18 次） | 5.15 s（1 次） |
| prefill 计算+KV复制累计 | 2.228 s | 1.889 s |
| decode 计算累计 | 0.704 s | 0.226 s |
| 峰值 RSS | 1.851 GiB | 1.889 GiB |
| 测量结束时保留 RSS | 0.668 GiB | 1.889 GiB |
| 新 session 最后显式释放后 | — | 0.696 GiB |

**当前策略下没有峰值节省：新图实测约多 39 MiB。** 收益在于移除反复创建；热轮旧模型创建约 1.63–1.68 s/turn，新复用路径为 0。代价是活跃会话期间保留较多内存，测得约多 1.22 GiB；需在会话结束释放。

按输入长度配对的 turn 中位数（创建成本没有与计算成本混合）：

| 行数 | 当前 turn 总计 | 新复用 turn 总计，不含初始化 | 当前计算+复制 | 新计算+复制 |
|---|---:|---:|---:|---:|
| 10 | 1.833 s | 0.070 s | 0.104 s | 0.064 s |
| 98 | 2.061 s | 0.304 s | 0.328 s | 0.297 s |
| 116 | 2.091 s | 0.344 s | 0.379 s | 0.337 s |

新图一次性 5.15 秒初始化单列，不能把它隐去并称首轮仅需 70 ms。7.35 秒不含最后共享 session 的释放时间；当前每 turn 的释放计入 21.37 秒。raw 文件同时提供 phase 合计和带 RSS 采样开销的墙钟值。

这是 talker 组件级测试，不含 speaker encoder、CP、vocoder、ASR/MT 或全应用开销，不能等同于完整合成提速比例。

## 旧双常驻对照与负载

第一窗口按 baseline→dynamic→dynamic→baseline，新进程顺序运行。旧两 session 常驻峰值 3.709/3.725 GiB，新单图为 2.014/2.012 GiB；约 46% 差额**只相对旧双常驻实验，不能相对当前串行释放策略宣传**。

该窗口热 prefill 中位数：10/98/116 行旧图 41.21/265.94/303.70 ms，新图 39.54/260.59/300.75 ms，排除了 tokenwise 的数量级退化；样本少，低个位数差异不宣称可靠提速。新进程加载波动明显：旧 4.254/2.349 秒、新 3.544/1.407 秒。

两轮窗口均由 root 协调其他 agent 暂停大计算，但整个用户机器并非绝对空闲。第一窗口 CPU idle 约 59–84%，load 约 4.3–4.6；当前串行策略窗口 idle 约 59–82%，load 约 3.7–4.8。`top` 聚合原文、UTC、load 和执行顺序均保留。页缓存未清空，所有“冷”均指新进程/session 创建，不是冷盘或设备 SLO。

## 候选与复建

目录：`/Users/arietids/Library/Caches/Auralis/tts/unified-talker/`。

| 文件 | 字节 | SHA256 |
|---|---:|---|
| talker_unified.onnx | 2,311,577 | ee8b48fb8e40a6bb2afa914cc129128072439680a17f308012e1dc59ae9fd3ed |
| talker_unified.onnx.data | 1,774,424,064 | 823a937733f4c412f37f434e2d4e66f8a07d5f3e77da16c2a23dc7165583b24e |

输入为 `inputs_embeds[1,T,1024]`、`attention_mask[1,P+T]`、`position_ids[3,1,T]`、两个 `past[28,1,8,P,128]`；P 可以是 0。输出为 logits、hidden_states、两个 stacked present KV。导出 metadata 的 logits 前两轴是 symbolic，实际运行始终 `[1,1,3072]`；hidden 是 `[1,T,1024]`。输入 batch 固定 1。

原始证据包括 `probe.json`、`dynamic-export.json`、`dynamic-validation.json`、`dynamic/{results,asr}.json`、两套 codes/WAV、`perf/window.json`、`perf/lifecycle-window.json` 和逐轮 raw JSON/log。脚本执行版本保存在 `script-snapshots/`。

复建用新目录，避免覆盖冻结图：先以 `--output-dir NEW probe` 加四个官方 ICL `--inputs`，然后 `export-dynamic`、`validate-dynamic`、`synth --dynamic`。探测/验证用 ORT 1.24.2 overlay；导出额外使用已缓存官方 Qwen 源与兼容依赖，HF revision `5d83992436eae1d760afd27aff78a71d676296fc`，FP32/eager，opset18，dynamo=False。导出前官方模块包装已与原 prefill 真图比较；图和权重存在时拒绝覆盖。

集成必须真正共享一个 ORT Session，并采用统一 `talker` 身份；将两个旧 role 指向同一个文件仍可能按 role 缓存出两份 session。当前没有做生产集成、Android/iOS 真机峰值、热稳定或全应用 SLO 验证。
