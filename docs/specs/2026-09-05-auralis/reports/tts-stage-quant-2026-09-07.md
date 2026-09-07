# 分阶段量化消融（2026-09-07）

**固定的三组都未通过质量门，没有候选交给 Android 晋升。** 共完成 36 个唯一合成样本，全部正常 EOS、finite、clippingRatio=0；这些条件不足以证明质量不下降。全部失败保留，未修改生产 runner/engine、原 benchmark、冻结结果或 manifest。

## 固定比较

| 组 | talker prefill/decode | code predictor | 身份正确 | paired SV 中位变化 | 最差变化 | ASR | 判定 |
|---|---|---|---:|---:|---:|---|---|
| A | FP32 | INT8-b32-a4 | 12/12 | −0.033337 | −0.128708 | 12/12 CER=0 | 中位与最差 SV 均失败 |
| B | FP32 | INT4-b32-a4 | 11/12 | +0.000279 | −0.096558 | 11/12 CER=0；一例 CER=1 | 新身份失败及内容失败 |
| C | INT8-b32-a4 | FP32 | 12/12 | +0.010184 | −0.114511 | 12/12 CER=0 | 最差 SV 失败 |

speaker encoder、vocoder、全部 NPY、tokenizer 均为原 FP32 包。没有按说话人调参数或修改阈值。

- A 最差为 **121-English**：独立 ECAPA own cosine 从 0.548923 降至 0.420215。
- B 新增 **260-English → 672** 匹配失败，两者同为男性；own=0.217477，匹配 margin=−0.000854。即使差距小，也必须保留预设身份门失败，不能用性别一致替代身份保持。
- B **237-Chinese** 目标为“请不要取消明天去上海的火车票。”，ASR 返回“提纲”，raw/normalized CER 均为 1.0。另起独立 ASR 进程复核仍返回“提纲”，结果保存在独立 `B/asr-recheck/`，原报告未覆盖。
- C 最差为 **61-English**：own cosine 从 0.594684 降至 0.480173。

## 基线、门与执行

直接使用 `real-pool/protocol-fixed-sampled/` 冻结的新 FP32 基线，而非旧 greedy 结果。动态导入该目录的冻结 runner，SHA256 为 `4df289d3ab4b15f1163112e00e0a81323e8a2650edbf833dc72b39e5293b44be`。运行前核对基线记录的 runner hash、sampling 与 criteria hash。

输入为原先预定的六位 LibriSpeech test-clean 说话人（121/237/1221/61/260/672，3F+3M），reference/heldout 分离。复用相同 soxr_hq 24 kHz 参考。每组先完成 121 的中英文 smoke，正常 EOS 后才执行余下十例；smoke 样本直接保留在完整结果中，没有重复选择成功结果。

参数固定：ORT 1.24.2、CPU、intra-op 4 / inter-op 1、temperature=0.9、topK=50、repetition penalty=1.05、seed=20260906、384 帧预算。量化模型只复用既有转换产物，未重新量化或调整图。

预设 SV 门保持不变：native reference top1=6/6、generated top1=12/12、无新增身份失败、paired median≥−0.03、paired worst≥−0.10。独立 ECAPA 始终与全部六个 heldout 比较，包括同性别干扰项；三组 native own 分数与冻结基线 **逐值相等**，排除评分前端漂移。

额外的逐例 ASR 非退化条件在任何候选运行前写入 `plan.json`：每例 numeric_normalized_CER 不高于对应 FP32，且不得有缺失/失败。使用原 `eval_tts_optimization.py asr` 的真实 Sherpa Qwen3-ASR 与相同规范化；raw 指标完整保留。A/C 全部 raw CER=0；B 的一例失败在原始和规范化指标中都存在。

## 产物与可复建性

新增脚本：`convert/bench_tts_stage_quant.py`。

全部结果在 `$AURALIS_CACHE/tts/stage-quant/`：

- `plan.json`：运行前固定的三组组合、基线/criteria 哈希和 ASR 门。
- `A|B|C/model/`：与原包分离的 hardlink 组合目录，不复制或改写大权重。
- 各组 `composition.json`：完整文件来源、大小、SHA256、阶段精度及原 conversion.json 哈希；量化图和外部 `.onnx.data` 都核对原转换记录。原 FP32 源 graph hash 也匹配转换来源。生成前再次验证全部组合文件。
- 各组 `results.json`、WAV、`identity.json`、`asr.json`、`decision.json` 和日志：完整 12 例记录，包括 token 序列及全部失败。
- `summary.json`、`paired-cases.csv`：36 例配对明细和三组决定。
- 冻结 runner、实际阶段脚本、原 ASR evaluator、pool manifest、criteria 的副本。
- `decision-self-checks.json`：六项判定控制检查通过，包括精确基线应通过、单例缺失/ASR 退化/身份失败/越过最差门/被指标遮掩的合成失败必须拒绝。它们只检验判定控制流，不作为音质证据。

复现环境：

```sh
PYTHONPATH="$HOME/Library/Caches/Auralis/tts/optimization/ort124" "$HOME/Library/Caches/Auralis/tts/venv/bin/python" convert/bench_tts_stage_quant.py prepare
```

随后对 A/B/C 分别运行 `synth --variant X --smoke`、`synth --variant X`、`evaluate --variant X`。ASR 用 `$HOME/Library/Caches/Auralis/asr/venv/bin/python convert/eval_tts_optimization.py asr .../X/results.json`，最后 `python3 convert/bench_tts_stage_quant.py decision --variant X`。脚本会跳过已有已记录样本；中断中的样本保留失败，不自动重试择优。现有组合或来源哈希不同会拒绝覆盖。

## 范围

这次消融说明：在预定的六人、两句、单个固定 seed 上，把量化限制到 CP 或 talker 阶段仍不能满足所有门。B 的平均/中位声纹表现不能掩盖身份和语义失败，C 的中位提升不能掩盖最差样本下降。保持原 FP32 方案，不挑样本或调阈值晋升。

时间、max RSS、load average 都保留作诊断；运行期间存在其他任务竞争，不作速度、内存 SLO 或设备结论。阈值本身是未校准的筛选门，样本量有限，即使通过也只允许继续候选验证，不能直接修改质量认证。源数据与许可证/作者信息保留在 `pool-manifest.json`。
