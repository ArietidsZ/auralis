# Python ICL 运行器接入与验证

2026-09-07。`convert/tts_runner.py` 现在支持显式实验 ICL。默认 xvector 行为和采样参数不变；没有修改 shared manifest、API2 model_tasks、移动 engine 或 streaming-vocoder 接口。

## 使用与拒绝规则

```sh
tts_cache="$HOME/Library/Caches/Auralis/tts"
PYTHONPATH="$tts_cache/optimization/ort124" "$tts_cache/venv/bin/python" convert/tts_runner.py \
  --model-dir "$tts_cache/hf" --conditioning-mode icl \
  --reference-wav "$tts_cache/upstream-fidelity/real-pool/121-reference.flac" \
  --reference-text "ALSO A POPULAR CONTRIVANCE WHEREBY LOVE MAKING MAY BE SUSPENDED BUT NOT STOPPED DURING THE PICNIC SEASON" \
  --reference-encoder "$tts_cache/icl/reference_encoder.onnx" \
  --language zh --text "请不要取消明天去上海的火车票。" \
  --seed 20260906 --max-frames 384 --threads 4 \
  --out "$tts_cache/icl/runner-validation/example.wav" \
  --json-report "$tts_cache/icl/runner-validation/example.json"
```

`--conditioning-mode` 默认 `xvector`，另一个合法值为 `icl`。ICL 必须同时提供非空 `--reference-text` 和 `--reference-encoder`；xvector 若带任一 ICL 参数也拒绝，防止参数被悄悄忽略。不会自动推断或伪造转写。

实验 encoder 契约是显式 graph 路径及同目录 `<graph>.data`；两者必须存在并分别哈希。它不是从现有 manifest 自动选择的新出货图。真实 signature 必须为 `pcm: float32[1,1,N]`→`codes: int64[1,16,ceil(N/1920)]`。输出形状、dtype、每个 code 的 `[0,2048)` 范围均检查。

| 情况 | exit |
|---|---:|
| 正常完成 EOS | 0 |
| ICL 缺参数、空转写、xvector带ICL参数、其它非法纯参数 | 4（依赖/模型 IO 前） |
| 依赖、参考文件、encoder graph/data 或 bundle 文件缺失 | 2 |
| 错误 graph role/signature、坏输出、静音参考、预算耗尽等执行失败 | 1 |

参数错误沿用 argparse stderr/exit4 契约；执行错误写 error-shaped JSON，不带成功音频字段。测试未把失败时可能遗留的旧文件当成功输出。

## 可在生成前调用的准备函数

新增实际需要的函数，没有通用框架：

- `load_reference_audio(path)`：读取、downmix、官方 soxr_hq 重采样，返回 owned/contiguous float32 mono 24 kHz PCM。
- `encode_reference_codes(session, audio_24k, cfg)`：检查真实 encoder I/O，执行并返回 owned int64 `[16,R]`。调用者可以在首个 target `on_frame` 前取到它。
- `build_reference_prompt_ids(tokenizer, reference_text)`：官方 reference wrapper。
- `build_icl_prompt(embs, cfg, target_ids, reference_ids, reference_codes)`：返回 ICL body/trailing；`generate_codes` 再加 role/speaker prefix。严格使用 `ref_ids[3:-2]`、`target_ids[3:-5]` 和16组既有NPY。
- `speaker_embedding_from_audio(bundle, audio_24k)`：与 encoder 共用同一份实际 PCM。
- `decode_waveform(session, generated_codes, reference_codes=None)`：完整 vocoder 的 context+target 解码和精确裁剪。

`generate_codes` 在既有 `on_frame` 后追加可选 keyword 参数 `reference_token_ids`、`reference_codes`，必须同时提供；没有改变 penalty、温度、seed 或 EOS 规则。`on_frame` 只包含新生成 target 帧的独立副本；不把 reference 帧发送给 sink。回调仍同步施加 backpressure，异常/取消继续传播；回调产生的是部分输出，不是 EOS 成功标志。

`synthesize` 在 `threads` 后追加 `conditioning_mode="xvector"`、`reference_text=None`、`reference_encoder=None`。encoder 在 talker/CP 加载前执行并释放；不在合成期间无必要地保留额外 encoder session。

## 参考有效性

纯 DSP `resample_reference_audio` 仍可处理零信号，保留滤波/长度测试用途。真正的 speaker/ICL 准备则在图执行前检查：finite、float32 mono、1024样本至30秒、peak≥`1e-4`；直接调用准备函数也不能绕过。不会让 speaker encoder 的非零 bias 把全零录音变成“有效声音”。峰值门与 iOS Profile 存储门一致，不引入 VAD/语音分类模型。

编码后的 reference 矩阵限制在1–375帧（30秒），拒绝非法 shape/dtype、空数据、负数、reserved IDs 和越界值。该上限保护直接调用生成器时的缓存 reference 输入；正常 encoder 路径已有相同音频长度限制。

## 报告字段

成功报告保留全部原字段，新增：

- `conditioningMode`：`xvector` / `icl`。
- `reference_text`：原样输入；xvector 为 null。
- `reference_wav_sha256`：原始参考文件摘要。
- `reference_pcm_sha256`、`reference_samples_24k`：实际重采样后 float32 PCM 字节摘要/样本数，与原文件摘要分开。
- `reference_encoder`、`reference_encoder_sha256`：显式 graph 路径及 graph/data 哈希；xvector 无encoder。
- `reference_frames`、`reference_pcm_trimmed_samples`：R与R×1920；`timing_s.reference_encode` 为encoder加载/执行观察值。

JSON 不包含整段 PCM、embedding 或 codes；验证用 NPY/NPZ 只写独立证据目录。

## 真实 ORT1.24.2 验证

使用固定 LibriSpeech 121/260参考与官方 transcript、中英同两句，temperature=.9、topK50、penalty1.05（每不同 token一次）、seed20260906、maxFrames384。**完整 runner.main/synthesize 路径使用 ORT1.24.2 CPU**，没有用1.29替代。独立验证器仍为 Qwen3-ASR和六人 held-out ECAPA。

| 用例 | 参考帧R | target帧 | 输出秒数 | held-out own cosine | 身份top1 / ASR |
|---|---:|---:|---:|---:|---|
| 121 en | 106 | 37 | 2.96 | .59767 | 正确 / 正文精确 |
| 121 zh | 106 | 38 | 3.04 | .44662 | 正确 / 正文精确 |
| 260 en | 88 | 31 | 2.48 | .39989 | 正确 / 正文精确 |
| 260 zh | 88 | 31 | 2.48 | .24788 | 正确 / 正文精确 |

四例全部 EOS、finite、零 clipping；新JSON与原始输入/参考encoder哈希核对通过。此处仅说明实际运行器验证，不晋升质量状态；n=2和单seed不能代表产品分布。

数值/兼容性证据：

- 对实际官方 ICL full prefill NPZ：maxdiff≤3.34e−6，attention_mask/position_ids完全相同；reference codes逐值相同，trailing maxdiff<1e−4。
- 同一真实 codes 的 `reference+target` ONNX full PCM，runner返回值与 `[R*1920:]` **逐样本完全相同**。121裁203520样本（不是原录音203040），260裁168960样本。
- 再用官方 PyTorch vocoder解码同一完整code矩阵，target裁剪 maxdiff≤8.46e−6，RMS≤1.73e−7；这四例官方比例公式的裁剪位置也与R×1920相同。
- 回调收到后被测试 sink 故意改成2047，最终生成codes仍与回调修改前的owned副本逐值相同；callback次数严格等于target帧数，排除了reference帧混入sink。
- 默认 xvector 的121英文与冻结的修协议/app-default基线 PCM16 **逐样本相同**。
- 实际错误路径：缺转写4；缺encoder2；缺data2；拿真实speaker graph当encoder1；纯静音ICL/xvector均1，且没有成功音频报告/文件。

完整参考波形只存在于独立验证NPY，runner输出WAV仅为target；四例ASR未包含参考转写。不能据此保证生成模型永远不会复述参考文字。

## 检查、证据与边界

- `test_tts_runner.py`：ORT环境27项，1项仅因传统环境变量smoke门未开而skip；另外5次真实runner执行已覆盖一个xvector兼容例及全部4个ICL例。plain Python轻量路径也通过。
- 实际验证入口：`convert/tests/verify_tts_icl_runtime.py`、`verify_tts_icl_official_vocoder.py`、`verify_tts_icl_failures.py`。负例使用真实错误role/缺失文件和明确静音fixture，不拿fixture当模型成功证据。
- 缓存：`~/Library/Caches/Auralis/tts/icl/runner-validation/`；各case有report、verification、真实codes/prefill/full-context PCM；总 `results.json/asr.json/identity.json`、`official-vocoder.json`、`failures/summary.json` 保留所有结果。
- 最后的30秒reference-code上限仅收紧直接API边界；正向推理完成后对已存4份reference矩阵重新检查，生成数学未改。实际执行源码和最终源码分别冻结、记录哈希。

当前实现仍使用完整 vocoder 解码reference+target，存在重复reference解码成本；时间只作并发诊断。未来streaming客户端可用公开的reference准备函数先预热状态vocoder、丢弃reference PCM，但本任务没有新增streaming sink或改移动接口。中央API2与统一talker适配由root另行接入。
