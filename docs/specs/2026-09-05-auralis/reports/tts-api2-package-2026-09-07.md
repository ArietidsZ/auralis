# 官方 API2 FP32 包编译完成（2026-09-07）

从单一官方 Qwen checkpoint 编译出完整可复建 API2 包。recipeId `auralis.qwen3-tts.api2.fp32.v1`；管线脚本 `convert/build_tts_api2_package.py`（recipe SHA256 `2f9b4b8507ed30edb0281b71036008976df496410906983b8390db4a02b2dbc0`，与归档快照逐字节一致，即实际执行脚本）。共享 manifest/schema/客户端未改动；tts_runner.py 未改动；未操作 git。

## 统一 talker 历史缺口已用实际重建关闭

在新目录 `cache/tts/unified-talker-rebuild/` 用快照管线 `convert/bench_tts_unified_talker.py`（SHA `92844a82d75f9d7fed709e8d2286a38c3022609e85efd3989e758c5b07c13051`，工作区与 frozen 候选目录内副本同 hash）从官方 checkpoint 重建：

- probe 6/6 pass（2×10 行 xvector + 4×官方 ICL 98/116 行 NPZ）。
- export-dynamic → validate-dynamic：5/6 pass + 260-Chinese full-hidden mismatch（max_abs 1.28e-4，cos 0.9999999999988）——与原 frozen 记录**逐 case 复现同一历史签名**；该输出是 API2 已移除的未消费数据。
- make-api2 → validate-dynamic --api2：**6/6 consumed_outputs_pass**。
- synth --api2：两语 codes 与 baseline 逐位相等，wave max_abs=0。
- 重建产物 `talker_api2.onnx` = `adc1ae88…`、`.data` = `823a9377…`，与 frozen 候选**逐字节相同**（bit-identical）。原始 a215718 脚本无同 hash 快照的历史事实不变，但它不再是复建路径的依赖。

包内 talker 即取自该重建目录（非旧 hardlink）。

## 包布局与文件

部署包 `cache/tts/api2-package/`（4.33 GB apparent，磁盘 4.0 GB，frozen 图为 hardlink）：

| role | 文件 | bytes | SHA256 |
|---|---|---:|---|
| talker | talker_api2.onnx | 2,310,754 | adc1ae88f8880f09db518fd78476635f70eca21f82cf5ebff18fb3c929e24bd8（重建，bit-identical） |
| talker | talker_api2.onnx.data | 1,774,424,064 | 823a937733f4c412f37f434e2d4e66f8a07d5f3e77da16c2a23dc7165583b24e（重建） |
| code_predictor | code_predictor.onnx | 214,572 | 166eb1d1cbd8a55cba7725b37b29e6ee4ca827bb2d1a98c2022d48acad5c316a（新导出） |
| code_predictor | code_predictor.onnx.data | 440,446,976 | ed9f6d10630abd733e0001c4624c2ce677f6d903124a0f869e0f59318b14fec6（新导出） |
| speaker_encoder | speaker_encoder.onnx | 169,925 | 2248a1b994bb57daab773c640e9c4e328d94fbc3bc144266a67395787ff38716（新导出） |
| speaker_encoder | speaker_encoder.onnx.data | 35,409,920 | 9f6f588deda5ebbcda0a6b010bded2669faf861f402cc5ba5556c58d5c0aeb54（新导出） |
| reference_encoder | reference_encoder.onnx | 461,120 | 4294aacfaf7419f6d8d5e6bac14cbe1b1b2ca9f401ad698a5dfe7f6427eb3ad0（frozen，hash 门） |
| reference_encoder | reference_encoder.onnx.data | 190,069,504 | 528902f29affbf7acca8112335fb3230a3eb0384a8c5dca999f7917b1170b62a（frozen） |
| vocoder | vocoder_streaming.onnx | 409,014 | 3138ede6fb908e72eec4ee5904bd3f158cdcca4241caa7fdd0e5112f0aa38a36（frozen） |
| vocoder | vocoder_streaming.onnx.data | 456,219,776 | 80e961291971c0c3e3aee657f4b4ab40eb7c264f3aae90a6233d68f2b9ca0ba7（frozen） |

支持文件：`tokenizer/{vocab.json,merges.txt}`（官方原件；vocab 与 bundle 逐字节同，merges 仅差被三个运行时解析器跳过的 `#version` 头，六样本编码逐 id 相等）+ `embeddings/`（config.json、speaker_ids.json、text_embedding、talker_codec_embedding、codec_head_weight、text_projection_fc1/fc2 w/b、cp_codec_embedding_0..14）——全部由官方权重/配置重新生成并**逐值等于**已验证 bundle 表（不等即中止）。

Occam 消融记录：`codec_head_weight.npy`（12.6 MB，head 已在 talker 图内部）与 `speaker_ids.json`（base 模型为空 `{}`）确认**无任何引擎/runner 消费**（仅中央 validator 文件清单与 manifest 列名）；为与现 validator 文件集保持一致仍随包提供，manifest2 是否剔除由 root 决定，已在此留证。其余支持文件均为消费必需（text_embedding/fc1/fc2/talker_codec/cp_codec_0..14 被 Python/Kotlin/Swift 三端逐行加载）。

包外（host 侧）`cache/tts/api2-package-evidence/`：package.json（role→文件→I/O 契约，供客户端消费）、assemble/verify/smoke/export-*.json、verify/smoke codes 与 PCM npy、管线脚本归档快照。

## 新导出图与验证

- **speaker_encoder**（opset 18）：`mel_spectrogram float32[1,T,128] → speaker_embedding float32[1,1024]`，官方 BF16→FP32 权重。对社区图四份真实 mel + 短窗 3200 样本 **max_abs=0.0（逐位相同）**。batch 固定 1：社区图声明的动态 batch 在 ORT 1.24.2 下 batch=2 本身即失败，故 batch=1 是真实契约，已在接口注明。
- **code_predictor**（opset 18）：`inputs_embeds [b,T,1024] + generation_steps int64[1] + past k/v [5,b,8,P,128] → logits [b,T,2048] + present k/v`；`logits = RMSNorm(hidden) @ lm_head[generation_steps].T`，rope 位置 = P..P+T-1（与社区图对 past 形状的推导一致）。真实生成中 **1200 次 CP 调用（2 语×40 帧×15 组）全部 argmax 相等**，logits max_abs ≤ 9.2e-5、present ≤ 7.7e-5（float32 算子序噪声，权重逐位同）。generation_steps 仅支持长度 1（唯一被消费的模式；社区图 S>1 路径本身无数学意义）。
- **code continuation**：社区图采样与官方图采样两条路径的完整生成 codes 均与 frozen 121 两语 baseline **逐位相等**（同 seed 20260906、temp .9/topK 50/penalty 1.05/384 帧）。
- **e2e smoke**（ORT 1.24.2 CPU，intra 4/inter 1）：xvector 两语（40 帧，clipping=0，峰值 0.29/0.37）+ ICL 两例（121-zh 38 帧、260-en 31 帧，参考 codes 与 icl/ 记录逐位相等，参考 codes 预热 vocoder 并丢弃 PCM）；流式 vocoder 对 full vocoder maxdiff ≤ 2.8e-6；状态字节恒定 5,193,992、position 记账正确、复位后首块重发一致。
- provenance `build-provenance.json`：schemaVersion 1，recipe 字段（path+SHA，不进 manifest 执行）、upstream/sourceCode revision、精确 toolchain（torch 2.14.0、transformers 4.57.3、tokenizers 0.22.2、numpy 2.5.3、onnx 1.20.1、ORT 1.24.2、Python 3.12.9）、11 个官方 input 文件 hash、36 个 outputs（包内相对路径；role 仅真实角色；externalData=true 标 5 个 .onnx.data；不含 provenance 自身）；outputs 与 package.json 交叉校验一致。管线脚本已可移植（路径由 `__file__`/`$HOME`/`AURALIS_CACHE`/`AURALIS_HF_QWEN3_TTS_SNAPSHOT` 推导，无个人绝对路径）。

## 未完成门（如实）

- 全部证据为 **host CPU**。无实体设备：Android/iOS 真机、Catalyst 链接、设备 RTF/能耗/热稳态均未测；不宣称任何 SLO。
- ICL 仅路径级 smoke；ICL 独立质量集（held-out 声纹增益、可懂度）未在本包上重跑；移动端 resampler 前端对离散 VQ codes 的边界影响未并入本包结论。
- 本包未重跑 ASR 回转（沿用 frozen 基线的 codes 逐位相等作为内容锚）；独立 ECAPA 身份门未对本包 PCM 重新评分。
- 新图未做 ORT 峰值内存/加载时间测量；未做 Android NNAPI/CoreML EP 验证（CPU-only）。
- 流式 vocoder 与参考 encoder 沿用既有实验状态：非生产 manifest 一部分，设备欠载/背压未测。
- code_predictor 的 generation_steps>1 与 speaker batch>1 不受支持（已在 provenance/接口披露）；若未来需要属新协议。
- GitHub 发布：模型缓存（~4.3 GB 包、HF snapshot、社区参考 bundle）不上传；发布仅含源码与小体积证据，provenance/evidence 中的大文件以 hash 引用。发布由 root 操作，本 lane 未触碰 git。

## 复建

```sh
CACHE=${AURALIS_CACHE:-$HOME/Library/Caches/Auralis}
export PYTHONPATH="$CACHE/tts/upstream-fidelity/deps:$CACHE/tts/optimization/ort124:$CACHE/tts/upstream-fidelity/Qwen3-TTS"
PY="$CACHE/tts/venv/bin/python"
# 1) 统一 talker 重建（fresh 目录，六步门全过）
$PY convert/bench_tts_unified_talker.py probe --output-dir "$CACHE/tts/unified-talker-rebuild" \
  --inputs "$CACHE/tts/unified-talker/prefill-121-chinese-inputs.npz" \
  --inputs "$CACHE/tts/unified-talker/prefill-121-english-inputs.npz" \
  --inputs "$CACHE/tts/unified-talker/prefill-260-chinese-inputs.npz" \
  --inputs "$CACHE/tts/unified-talker/prefill-260-english-inputs.npz"
$PY convert/bench_tts_unified_talker.py export-dynamic  --output-dir "$CACHE/tts/unified-talker-rebuild"
$PY convert/bench_tts_unified_talker.py validate-dynamic --output-dir "$CACHE/tts/unified-talker-rebuild"
$PY convert/bench_tts_unified_talker.py make-api2       --output-dir "$CACHE/tts/unified-talker-rebuild"
$PY convert/bench_tts_unified_talker.py validate-dynamic --api2 --output-dir "$CACHE/tts/unified-talker-rebuild"
$PY convert/bench_tts_unified_talker.py synth           --api2 --output-dir "$CACHE/tts/unified-talker-rebuild"
# 2) 包编译（export→assemble→verify→smoke→provenance，任一门失败即中止）
for stage in export-speaker export-cp assemble verify smoke provenance; do
  $PY convert/build_tts_api2_package.py "$stage" || exit 1
done
```

证据：`cache/tts/unified-talker-rebuild/{probe.json,dynamic-validation.json,api2-validation.json,api2-proof.json,api2/results.json}`、`cache/tts/api2-package-evidence/evidence/*.json`、`cache/tts/api2-package/build-provenance.json`。
