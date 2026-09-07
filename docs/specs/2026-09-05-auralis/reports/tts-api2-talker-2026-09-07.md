# 最终 API2 talker 候选（2026-09-07）

最终图只公开末 hidden，统一角色为 `talker`。旧全历史 hidden 图、数值误差记录及性能报告全部保留。没有更改权重、生产 runner/engine 或 manifest。

## 文件与接口

目录：`/Users/arietids/Library/Caches/Auralis/tts/unified-talker/`。

| 文件 | 字节 | SHA256 |
|---|---:|---|
| talker_api2.onnx | 2,310,754 | adc1ae88f8880f09db518fd78476635f70eca21f82cf5ebff18fb3c929e24bd8 |
| talker_api2.onnx.data | 1,774,424,064 | 823a937733f4c412f37f434e2d4e66f8a07d5f3e77da16c2a23dc7165583b24e |

权重数据与原动态图逐字节相同；缓存中用 hardlink 保存，未复制或改写参数。机器可读接口为 `api2-interface.json`。

| 名称 | dtype | shape |
|---|---|---|
| inputs_embeds | float32 | [1,T,1024] |
| attention_mask | int64 | [1,P+T] |
| position_ids | int64 | [3,1,T] |
| past_keys / past_values | float32 | [28,1,8,P,128] |
| logits | float32 | **[1,1,3072]** |
| last_hidden_state | float32 | **[1,1,1024]** |
| present_keys / present_values | float32 | [28,1,8,P+T,128] |

T≥1，P≥0，batch 固定为 1。初始 KV 是 P=0 的空张量。图同时接受批量 prefill 与已有缓存后的 decode，不提供两个旧 role 的兼容接口。基准中的适配器仅为复用冻结生成循环，不属于最终运行时 API。

## 收窄方式与形状依据

原动态图的 logits MatMul 输入本来就是完整 hidden 的末行 Slice。脚本检查实际算子：轴 1、start=−1、end=INT64_MAX、step=1；codec head 为 FP32 [1024,3072]，输入 batch 固定 1、hidden size=1024。

直接将这个既有 Slice 的输出命名为 `last_hidden_state` 并公开，撤去全历史 hidden 公共输出。logits 和完整 stacked KV 保留；没有增加替代计算、重新训练或量化。固定输出形状由非空序列的末行 Slice 和 MatMul 证明，并经过真实运行验证，不是只改 shape 来掩盖内部常量。

旧完整 hidden 的内部计算没有被改成理想值。260-Chinese 那个历史 hidden 严格 allclose 失败仍在原 `dynamic-validation.json` 和 [前序报告](tts-unified-talker-2026-09-07.md) 中；最终 API 不再返回这部分未消费的数据。

## 实际复验

- ONNX checker 通过。
- 原始两图对照：两份 10 行 xvector，以及官方两人×两语的 98/116 行 ICL 输入，**6/6 实际 API2 输出全部通过 rtol=atol=1e-4**。每份再用完整缓存执行后续 decode，全部通过。
- 每次真实输出均核对 logits=[1,1,3072]、last_hidden_state=[1,1,1024]；KV 保留完整序列。
- 同一 121 参考、默认 .9/topK50/penalty1.05/seed20260906，原图与 API2 中英文各 40 帧：**全部 16 组 codes 完全相同，wave max abs=0**。
- 四份 API2 WAV 与已做真实 ASR 的前序对应 WAV **逐字节 SHA256 相同**；对应 ASR CER 均为 0。通过 `api2/asr-provenance.json` 绑定原始真实报告，没有重复加载 ASR，也没有将推测写成新测量。

证据：`api2-proof.json`、`api2-validation.json`、`api2/{results.json,*-codes.npy,*.wav,asr-provenance.json}`、`api2-interface.json`。

## 复建

脚本：`convert/bench_tts_unified_talker.py`；副本与 hash 保存在候选目录。

1. 按前序报告在新 `--output-dir` 中执行 `probe`、`export-dynamic`、`validate-dynamic`，生成真正动态 sequence/past 的 FP32 原图。输入包含四份官方 ICL NPZ。
2. `make-api2`：验证原图/数据 SHA、检查真实 Slice 与 head，再无损收窄输出并写出接口文件。
3. `validate-dynamic --api2`：比较全部 API2 消费输出和后续 decode。
4. `synth --api2`：按冻结默认参数比较原两图与最终单图的全 codes/wave。

探测/验证采用 ORT 1.24.2 overlay 与既有 TTS venv。新建图需要旧官方 FP32 Torch 模块导出步骤；收窄本身只读写 ONNX protobuf，并复用相同外部权重。已有图或数据文件会被拒绝覆盖。

## 性能与范围

依用户要求，收窄 API2 输出后没有再安排性能窗口。前序动态全 hidden 图的结果不能冒称对最终文件重新测量过。

前序直接对比当前串行释放策略的结论仍需完整引用：**峰值约 1.851→1.889 GiB，没有峰值节省；收益是消除每 turn 约 1.6 秒的模型重建，代价是活跃会话保留更多内存。** 旧双常驻对照的约 46% 内存差只适用于旧策略。完整创建/compute/释放、冷启动波动和实际 CPU/load 见前序报告及 raw 文件。

候选未做生产集成、全应用性能或 Android/iOS 真机验证。运行时必须只创建一个 `talker` session；不应再以两个旧 role 各建一份。
