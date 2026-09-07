# ICL 质量对照与参考编码器候选

2026-09-07。独立范围：`convert/bench_tts_icl.py`、参考 encoder 导出/诊断脚本、`~/Library/Caches/Auralis/tts/icl/`。未修改生产 engine、manifest、profile 格式或已冻结 streaming 图。

## 结论

**官方 ICL 在固定两位真实读者的小集上有明确收益，现有 embedding 足以表达它；参考 encoder ONNX 已能在 ORT1.24.2 真实运行。** ICL 不能继续仅因 community bundle 缺 encoder 而被视为不可实现。

同时保留两项限制：该质量实验只有 2 人×2 句；encoder 在一个 native PCM 用例存在单帧近邻量化边界差异，不能声称对所有输入 bit-exact。已用该实际 ORT codes 完成对应 ICL 合成：ASR、身份检索没有新增失败，声纹分数下降小于预设 .03，但仍需更大设备质量验收。

## 固定来源和对照

- 官方 Qwen git：`022e286b98fbec7e1e916cb940cdf532cd9f488e`。
- 官方 HF Base 0.6B revision：`5d83992436eae1d760afd27aff78a71d676296fc`；本地已有权重，无新 TTS 模型替换。
- LibriSpeech test-clean，[SLR12 / CC BY 4.0](https://www.openslr.org/12/)，沿用固定池读者 **121、260** 及各自不同录音的 held-out。参考分别 8.46 s/7.04 s；ref_text 使用 manifest 中的原始大写官方转写，没有按声音润色。
- 中英文本固定为 `Please do not cancel the train to London tomorrow.` / `请不要取消明天去上海的火车票。`。
- **两模式均使用官方 PyTorch FP32/eager**，消除 ONNX/PyTorch RNG 与 backend 差异的混淆。torch2.14.0、transformers4.57.3、2线程；每次重置 seed20260906。
- sampling 为官方 generation_config 默认：do_sample=True、temperature=.9、topK50、topP1、penalty1.05；subtalker 同 .9/50/1；仅把非采样安全预算限制为384新token。`non_streaming_mode=False` 采用默认 prompt 组装；公共 API 仍返回整段 wav。
- 在生成前写入 `plan.json`：无新增合成/ASR/身份失败、paired median held-out cosine gain≥.03、最差 gain≥−.03。小集筛选门不是产品验收标准。

## ICL 对 xvector-only 的实际结果

独立 ECAPA-VoxCeleb，检索库始终是原固定六位读者的 held-out，包含同性交叉负例；不把性别分类当身份保真。8/8 合成都自然 EOS、finite、零 clipping，独立 Qwen3-ASR 正文全部精确。

| 读者 / 文本 | 官方 xvector cosine | 官方 ICL cosine | Δ |
|---|---:|---:|---:|
| 121 / en | .54675 | .53218 | −.01458 |
| 121 / zh | .25709 | .35500 | +.09791 |
| 260 / en | .24501 | .44096 | +.19594 |
| 260 / zh | .23416 | .34665 | +.11250 |
| 均值 | .32075 | .41870 | +.09794 |

配对 median gain **+.10520**，最差 **−.01458**。身份 top1：xvector 3/4（260英文被识别为同为男性的672），ICL 4/4。四个 case 全部保留，未选择只报告获益者。

现有 ONNX app-default xvector 结果仅作背景：对应四例 own cosine .54892/.31521/.25736/.18294，均身份正确。它使用另一 RNG/backend，不能把其与官方 ICL 的差值归因于单一功能。上表的同 backend 对照才是 ICL 效果依据。

证据：`results.json`、8个 WAV、`asr.json`、`identity.json`。初始 benchmark 原样源码已按 `results.script_sha256` 恢复并校验于 `frozen/bench_tts_icl_initial.py`，SHA=`8cd765d554bcaf1a9132197d318a0acb163f09a0563ca6965ff10cec2bd2cd8d`。

## 官方 prompt / 既有 NPY 覆盖

直接核验 [官方 generate_icl_prompt](https://github.com/QwenLM/Qwen3-TTS/blob/022e286b98fbec7e1e916cb940cdf532cd9f488e/qwen_tts/core/models/modeling_qwen3_tts.py)：reference text 与 target text 拼接后投影；reference code 每帧16组 embedding 相加，再加 codec BOS；text 不足 code 长度时用 tts_pad 补齐，否则把多余 text 留为 trailing。

参考 codes：121=`[106,16]`，260=`[88,16]`。对每条参考、所有16组、实际使用的每个 token：官方 embedding 与现有 NPY **maxdiff=0**。四个官方 ICL prompt 与 NPY 组装对比 maxdiff≤2.99e−6，trailing 相同。无需新增 text/codec embedding 权重。

完整 prefill 由官方 `generate_voice_clone` 真实构造并在 talker.generate 入口截获：121=`[1,116,1024]`，260=`[1,98,1024]`。`prefill-{121,260}-{english,chinese}.npz` 含 inputs_embeds、attention_mask、官方 get_rope_index 的 position_ids、trailing、tts_pad、rope_delta，已供另一 lane 的动态 talker 核验复用。不是根据 community C# 猜出的输入。

## 参考 encoder ONNX

官方 speech-tokenizer safetensors SHA=`836b7b357f5ea43e889936a3709af68dfe3751881acefe4ecf0dbd30ba571258`。导出 FP32、标准 ONNX opset18，**ORT1.24.2 CPU 实跑**。

| 文件 | bytes | SHA-256 |
|---|---:|---|
| `reference_encoder.onnx` | 461120 | `4294aacfaf7419f6d8d5e6bac14cbe1b1b2ca9f401ad698a5dfe7f6427eb3ad0` |
| `reference_encoder.onnx.data` | 190069504 | `528902f29affbf7acca8112335fb3230a3eb0384a8c5dca999f7917b1170b62a` |

真实接口：

| 名称 | 方向 | 类型 | 形状 / 语义 |
|---|---|---|---|
| `pcm` | input | float32 | `[1,1,N]`，24 kHz mono，有效、未额外批次补零的 PCM |
| `codes` | output | int64 | `[1,16,ceil(N/1920)]`，group-major |

不接受 sample-rate 参数，不替调用者做重采样。batch固定1，离线 reference 编码，没有跨调用 state。采样数 N 与真实有效音频长度一致；使用填充 batch 的调用者不能把填充音频当有效 reference。

最小适配及消融：

1. 官方模型计算32个 quantizer 再取前16；只计算所需16组与原路径精确一致，移除无用计算。
2. 旧 TorchScript exporter 无法跟踪 HF 的 vmap mask构造。通过官方支持的4-D attention_mask 输入提供同一因果 mask，attention/conv/quantizer 模块不改。**全部9个输入的官方 reference 在适配前采集**；适配后 PyTorch codes 9/9 完全相同，含超过250个 encoder transformer frame 的长输入。
3. 导出器重复内嵌46份编码簿 Constant，共96,468,992 bytes。无损合并为16个 initializer、14个纯形状 view，唯一数据33,554,432 bytes；总包253.45 MB→190.53 MB，graph protobuf96.93 MB→.461 MB。原始候选保留于 `before-constant-sharing/`。
4. 显式强制 cdist 的 MM 路径未改变 graph/data SHA 或结果，移除该无益适配；记录于 `cdist-ablation.json`。没有为个别帧加 rounding、距离阈值或强制 token。

ORT 与适配前官方 PyTorch：两条 soxr_hq 真参考、1919/1920/1921/24001/288013样本边界共7例 **全部 codes 精确一致**。新增 native PCM 的260也精确；121存在下面的保留反例。因此总计 **8/9精确**，不能写成9/9 bit-exact。

`encoder-validation.json` 保存完整逐例结果/哈希；`encoder-check-*.npz` 保存双方 code矩阵。所有计算≤2线程；耗时仅诊断，不作设备性能承诺。

## 保留反例：浮点扰动跨过 VQ 边界

同一 native64零点/HQ中点重采样 PCM，121 的第103帧、第4组出现首个 code 分歧，后续残差组连带共9个 code不同：全码一致率99.469%，帧一致率99.057%。

- PyTorch 最邻近1314/1296的距离：4.2871833 / 4.2872109，gap **2.77e−5**。
- ORT 最邻近顺序反转：1296/1314距离4.2870417 / 4.2871661。
- 该帧连续残差 maxdiff **2.11e−4**，距离 maxdiff4.11e−4，足以跨过这个边界。
- 各自残差改用float64求距离仍保留各自最近者，说明单纯提高最后距离精度不是修复。
- debug额外输出后的 codes 与原图完全相同，没有因诊断输出改变结果。

`vq-boundary.json` / `.npz` 保留连续残差、距离和反例；未强行“修到100%”。

随后用同一 native PCM、speaker embedding、官方 transcript、seed/采样，**只替换 PyTorch/实际ORT产生的 reference codes**，完成121的中英 ICL：4/4 EOS、finite、零 clipping、ASR正文精确、六人身份检索正确。

| 同native PCM，仅变encoder backend | PyTorch ref_codes own cos | ORT ref_codes own cos | Δ |
|---|---:|---:|---:|
| 121 en | .50078 | .50063 | −.00015 |
| 121 zh | .39525 | .38740 | −.00785 |

均在这轮事前固定的每例−.03筛选界内，但只支持该保留反例的用户层检查。证据位于 `native-runtime/`，包括实际使用的双方ref_codes、4个WAV、ASR/SV。

**输入前端效应单独报告**：soxr_hq+PyTorch→native PCM+PyTorch 的121 ICL own cosine：英文−.03139、中文+.04025。native PCM与soxr PCM本来就不同；它们各自形成的VQ码约2.1%不同。这不是“同PCM的ORT误差”，也不能被上一表掩盖。native ICL全链路需要独立设备验收。

## 最小补齐方案（尚未修改出货）

1. reference处理时调用新增encoder，得到R帧codes；与准确对应的ref_text、当前speaker embedding及模型revision一起组成ICL提示。现有profile格式未变，这只是新增模式所需数据契约。
2. 复用现有NPY，按官方ICL公式构造较长prefill；复用原talker/CP权重及cache流程。ref_text缺失或不可靠时不能把xvector请求默默标成ICL。
3. 注意布局：encoder输出 `[1,16,R]`；官方prompt helper读取 `[R,16]`。按清晰索引转换，避免再次发生frame/group转置错误。
4. vocoder要先消费reference codes作为左上下文，**不播放reference PCM**，再生成/播放target。完整decoder方案拼接code后裁掉 **R×1920** 样本，不能按原录音的N样本裁（例如8.46 s参考编码后为106帧/8.48 s）。
5. 已有状态流式vocoder可在profile载入时消费reference codes、缓存只读的reference结束状态；每个新请求复制此状态。这可避免重复解码整段reference，但需要独立时延和生命周期验收，不能直接宣称满足首音目标。不能同样盲目缓存完整talker prefill，因为官方ICL对齐中也包含当前target text。

本轮证明的是官方ICL质量信号、既有表覆盖及独立encoder图。尚未实现完整ONNX ICL生成引擎，也未修改app/profile/manifest；需扩大说话人与文本、核对native前端、实际端侧执行和默认采样分布。

## 复建与产物

```sh
tts_cache="$HOME/Library/Caches/Auralis/tts"
tts_python="$tts_cache/venv/bin/python"
tts_path="$tts_cache/upstream-fidelity/deps:$tts_cache/optimization/ort124:$tts_cache/upstream-fidelity/Qwen3-TTS"
OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 VECLIB_MAXIMUM_THREADS=2 PYTHONPATH="$tts_path" \
  "$tts_python" "$tts_cache/icl/frozen/export_tts_icl_encoder.py"
```

固定torch2.14.0/transformers4.57.3/onnx1.20.1/ORT1.24.2。最初质量实验的精确脚本在 `frozen/bench_tts_icl_initial.py`，当前脚本的 `dump-prefill`、`native-runtime` 和 `score` 可复现后续检查；诊断脚本 `diagnose_tts_icl_encoder.py` 只加观察输出。最终源码与数据索引存于 `frozen/`、`evidence-index.json`；旧候选、失败导出日志、反例均保留。
