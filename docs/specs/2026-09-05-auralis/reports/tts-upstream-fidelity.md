# TTS 官方实现保真、重采样与状态流式核验

2026-09-07。仅新增独立实验脚本、报告和缓存产物；本 lane 未改出货引擎或 manifest。

## 结论

1. **24 kHz 路径的 log-mel、speaker encoder、speaker 注入及 vocoder 与官方 PyTorch 数值一致。** 原先 n=2 合成参考的声音相似度不足，不能归咎于这些步骤。
2. **发现两个实际协议偏差**：非 24 kHz 参考曾用两点线性插值；重复 token 曾按出现次数累罚，而官方 HF 只罚一次。root 已协调修复。旧量化质量结果只能保留为历史消融，不能晋升。
3. **已验证可复用 sherpa 的最小重采样配置**：64 零点，`cutoff_hz = 0.9568718266 × 0.5 × min(inputRate, 24000)`，最后一块 `flush=1`。比例来自实际加载 libsoxr HQ 通/阻带中点，6 位真实说话人 embedding 对官方最低余弦 .99999911。
4. 正确协议、实际 app 采样下，36 例 ASR 正文均精确，但全模型 INT8/INT4 **均未通过事先设定的声纹非劣筛选**，不能因可懂度通过而晋升。
5. **独立状态流式 ONNX 已导出并在 ORT 1.24.2 真执行**：chunk 1/4/8/13、跨 72 帧窗口、尾块、终止/重置都已核验。输出与原完整 vocoder 的最大误差 <1.4e−6；状态恒定 5,193,992 字节。尚未接入 app，也没有移动端 TTFA≤1.5 s 的证据。

## 上游与环境固定

- 官方源码 [QwenLM/Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS)，git `022e286b98fbec7e1e916cb940cdf532cd9f488e`。
- 官方 HF `Qwen/Qwen3-TTS-12Hz-0.6B-Base`，revision `5d83992436eae1d760afd27aff78a71d676296fc`，直接读取真实 HF cache。旧 `tts/upstream-qwen` 是移动过的相对 symlink 副本，链接失效，未将它当证据。
- speaker 模型只加载官方 `speaker_encoder.*` 共 76 tensors，原 BF16 权重转 FP32。前处理/注入核验执行官方源码 AST 选出的原始函数与类，未改其函数体；完整提取代码保存于 `executed_official_definitions.py`。这样不必为前处理加载整个 LLM。
- 完整官方 vocoder 与诊断性单例生成使用官方 package，Transformers **4.57.3** / accelerate **1.12.0** / PyTorch **2.14.0**，FP32/eager；独立 overlay 未覆盖原环境。
- ONNX 核验始终使用 **ORT 1.24.2 CPU**，不是用 1.29 替代移动版本验证。librosa **1.0.0**、Python-SoXR **1.1.0**、实际 libsoxr **0.1.3-14-ga66f3ee**。
- 缓存根：`~/Library/Caches/Auralis/tts/upstream-fidelity/`。所有源码、权重、输出 SHA-256 另存索引；下文 JSON/NPZ 路径均相对此根。

## 官方前处理与注入数值

官方 [modeling_qwen3_tts.py](https://github.com/QwenLM/Qwen3-TTS/blob/022e286b98fbec7e1e916cb940cdf532cd9f488e/qwen_tts/core/models/modeling_qwen3_tts.py) 的 `mel_spectrogram` / `extract_speaker_embedding`：24 kHz、FFT/window 1024、hop 256、128 mel、0–12000 Hz、Slaney 默认尺度和面积归一化、周期 Hann、左右 reflect pad 384、`center=False`、非归一化单边 STFT。先求 `sqrt(real²+imag²+1e−9)` **magnitude**，再 mel 投影与 `log(max(x,1e−5))`，不是 power mel。

| 同一 24 kHz PCM | 中文参考 | 英文参考 |
|---|---:|---:|
| mel 最大绝对差 | .0005751 | .0006304 |
| 最终 speaker embedding cosine | .999999999999896 | .999999999999871 |
| embedding 最大绝对差 | 2.38e−6 | 2.86e−6 |

滤波器 maxdiff 3.73e−9。mel 极小差异来自 float32 Torch FFT 与 NumPy 高精度 FFT/窗口的数值路径，不应仅凭 mel 最大值判断声音偏差；最终 embedding 已对拍。向量 L2 norm 约 10.74/10.80，官方没有再做单位长度归一化或额外缩放。

执行官方 `generate()` 的真实输入构造，截获进入 talker 前的张量，与 runner 比较中、英、跨语言三例：prefill `[1,10,1024]` maxdiff≤1.32e−6，trailing text maxdiff≤3.04e−6，cosine≈1，attention mask 相同。官方 checkpoint 的 text projection 是 2048→2048→1024/SiLU；speaker 在 codec 前缀中原样插入。10 个特殊 token ID 以及全部 language ID 均与官方文件一致。

完整官方 PyTorch 12Hz speech tokenizer 接受相同 27 帧真实 codes，经官方 `[B,T,16]`→decoder `[B,16,T]` 转置，得到相同 51,840 PCM；与 ONNX maxdiff **2.27e−6**，RMS 1.43e−7，cos **.999999999998365**。这独立验证了修正后的 group-major 布局及 decoder 权重/流程。

证据：`upstream-numerics.json`、`native24-{zh,en}.npz`、`official-vocoder-comparison.json`、`official-vocoder-pcm.npy`。

## 非 24 kHz 重采样偏差与选择

官方 [reference preprocessing](https://github.com/QwenLM/Qwen3-TTS/blob/022e286b98fbec7e1e916cb940cdf532cd9f488e/qwen_tts/inference/qwen3_tts_model.py) 使用 `librosa.resample`，本环境默认 `soxr_hq`。旧三端两点线性插值不等价。同一份 16 kHz PCM 到 24 kHz，旧路径 speaker cosine 仅 .98883/.98852，maxdiff .250/.276；44.1 kHz 也有 .99796–.99866 的差异。这**不会解释旧 24 kHz say 基线**，但会影响实际录音前端。

复用现有 sherpa C API，不新增 DSP 库。宽度消融在默认 `.99×Nyquist` cutoff 下：

| `num_zeros` | 16 kHz 中文 cos | 16 kHz英文 cos | 15 kHz 输入、48→24 kHz alias 抑制 |
|---:|---:|---:|---:|
| 6 | .996779 | .995028 | 45.3 dB |
| 16 | .999283 | .998760 | 63.9 dB |
| 32 | .999878 | .999844 | 89.3 dB |
| 64 | .999882 | .999890 | 另存完整 JSON |
| 128 | .999877 | .999885 | 另存完整 JSON |

64 已进入平台，128 无收益。1 kHz 通带增益误差 <.001 dB；1001 样本在 16/44.1/48 kHz 的首/中/尾冲激，输出长度与峰位置均和 soxr 一致。去掉参考首尾 10 ms 后 PCM 误差没有减少，排除了主要由端点/时延造成的解释。

进一步实读所加载 libsoxr 的公共 `soxr_quality_spec(HQ,0)`：precision=20、linear phase=50、passband_end=.9137436532733066、stopband_begin=1。字段含义和默认 HQ 说明见 [官方 soxr.h](https://github.com/chirlu/soxr/blob/master/src/soxr.h)；构造公式见 [soxr.c](https://github.com/chirlu/soxr/blob/master/src/soxr.c)。**中点 `(passband_end+stopband_begin)/2 = .9568718266`** 是固定参数来源，不是逐说话人调参。

先用三个采样率中心单位冲激比较响应，再用未参与选择的 6 位真实读者验证：

| 固定 64 零点 cutoff/Nyquist | 冲激平均相对 MSE | 6 人最差 embedding cos | 最大 embedding absdiff |
|---:|---:|---:|---:|
| .99（默认） | 2.1944e−2 | .99936073 | .06004 |
| .95 | 1.2119e−3 | .99987366 | .02000 |
| .956（冲激粗网格） | 2.5191e−5 | .99999684 | .00288 |
| **.9568718266（官方中点）** | **5.9255e−6** | **.99999911** | **.00169** |
| .96 | 2.5926e−4 | .99998495 | .00726 |

选择一个 64 零点数和一个官方推导比例，不按输入率或说话人分支；它非常接近官方但不是 bit-exact soxr。64 的宿主参考音频重采样约 7–24 ms，仅是并发观察，不是设备性能保证。

证据：`soxr-native-hq-spec.json`、`sherpa-resampler-{comparison,widths}.json`、`resampler-impulse-fit.json`、`resampler-cutoff-validation.json`、12 份 `resampler-evidence-*.npz`（原 PCM、官方 PCM、全部候选 PCM 与 embedding）。

## 采样协议修正与旧结果的边界

官方 `modeling_qwen3_tts.py:2041,2058` 将 repetition penalty 交给 HF GenerationMixin，没有自定义频次惩罚。固定 Transformers 4.57.3 的 `generation/logits_process.py:402–405` 对原 scores gather、一次罚、scatter，因此每个已出现 token 只罚一次。

实际张量反例：history=`[2,2,2,3,3]`，token 2/3/4 初始 logits=`2/−2/1.8`、penalty=1.05。旧 runner 输出 `1.727675/−2.205/1.8`，argmax=4；官方和修正后为 `1.904762/−2.1/1.8`，argmax=2。**即使 topK=1 也受影响。** `sampling-discrepancy.json` 保存冻结前后源码哈希与实际 HF 输出。

正确协议 greedy 在真实读者 121 的中文句达到 384 帧仍无 EOS。官方完整 FP32/eager PyTorch、同 xvector-only prompt、同 384 token 预算也没有 EOS，并产生长串重复 codes，见 `official-generation-121-chinese.json`。这是拒绝把 greedy 作为部署策略的证据，不能恢复错误惩罚以隐藏失败。官方函数收集的声码帧数为 383，sequence 未含 EOS；本地 runner 明确拒绝预算耗尽的截断音频。

旧 36 例及其旧 runner SHA `91381565f33b41cb222eb642d5cdb60f5b4bf21a7a3316465aa449bf8eb53714` 已冻结在 `real-pool/pre-penalty-fix-provenance.json`；中止的正确协议 greedy 在 `real-pool/protocol-fixed/`，没有删除。下面全部采用修正后的真实 app 采样。

## 六位真实说话人：实际默认采样

[LibriSpeech SLR12](https://www.openslr.org/12/)：CC BY 4.0，16 kHz LibriVox 朗读；保留作者、读者姓名和源录音路径。test-clean 官方 MD5=`32fa31d27d2e1cad72775fee3f4849a9` 已核验。

在任何 TTS/声纹评分前固定：按数值 ID 选前三位女性 121/237/1221、前三位男性 61/260/672；每人第一条 6–12 s 录音作 reference，另一章最后一条合格录音优先作 held-out，否则同章不同录音。全部保留于 `real-pool/manifest.json`。检索图库含全部 6 位身份，包含两个同性交叉负例；不把语种/性别分类当身份保真。

同 6 个 reference、同中英两句，三候选均使用官方 soxr_hq reference24 PCM；temperature=.9、topK=50、penalty=1.05（每 token 一次）、seed=20260906、maxFrames=384。先单人 3 候选验证 EOS，再完整池。冻结 runner SHA：`4df289d3ab4b15f1163112e00e0a81323e8a2650edbf833dc72b39e5293b44be`。

天然 reference→held-out 6/6 排第一，own cos .462–.813、最小相对其它人的 margin .157，证明本图库有区分度。独立 verifier 为 ECAPA-VoxCeleb。它不是 TTS 的 speaker encoder。

| 真实默认采样 | FP32 | 全 INT8 a4 | 全 INT4 a4 |
|---|---:|---:|---:|
| EOS / finite / 零 clipping | 12/12 | 12/12 | 12/12 |
| ASR 正文精确 | 12/12 | 12/12 | 12/12 |
| 六人 held-out 身份 top1 | 12/12 | 12/12 | 11/12 |
| own cosine 均值 | .42354 | .40787 | .37286 |
| 相对 FP32 paired median Δ | — | −.01220 | −.05339 |
| 最差 paired Δ | — | −.11274 | −.14774 |
| 新身份错误 | — | 无 | 260-chinese→237 |

预设筛选门：median Δ≥−.03、worst Δ≥−.10、无新增身份失败；**INT8 因 121-english 最差下降 .11274 未过，INT4 多项未过。** 这些是事先固定的筛选阈值，未校准为产品保证；不能通过重新挑门或只看平均值晋升。单次随机种子仍不足代表采样分布，也未进行盲听/MOS。

证据：`real-pool/protocol-fixed-sampled/` 下冻结 runner/bench、三套全部 WAV、results/asr、`identity.json`。更早错误惩罚 greedy 的较好数值明确不是本表依据。分阶段量化另由 root 分配的独立 lane 审查。

## 状态流式 ONNX

原 `vocoder.onnx` 只有 codes→waveform，没有状态接口。真实 27 帧 codes 的完整 prefixes 与全句对应 PCM maxdiff≤6.3e−7；模型因果，毋需右侧未来码。但独立 chunk 带 8 帧左上下文仍 maxdiff .0418，不能拼接。官方便利函数 `chunked_decode(...left_context_size=25)` 也不能代替本模型逐状态数值验证。

已使用官方卷积/MLP权重，显式保留因果 Conv 输入与 Transformer KV；以固定 tensor mask/cache 替换 Python DynamicCache，导出独立 ONNX。29 个因果 Conv + 6 个转置 Conv 的实际有状态输入打包，1×1/无历史层不创建零长 cache（首版 ORT 拒绝该零长 reshape，已消除）。没有改变 speaker/talker/CP 或原 vocoder。

| 输入 / 对应输出 | dtype | shape | 初始值 |
|---|---|---|---|
| `codes` | int64 | `[1,16,F]` | 完整声码帧；不含 EOS |
| `conv_state` / `conv_state_out` | float32 | `[135232]` | 全零 |
| `past_keys` / `present_keys` | float32 | `[8,1,16,71,64]` | 全零 |
| `past_values` / `present_values` | float32 | `[8,1,16,71,64]` | 全零 |
| `position` / `position_out` | int64 | `[1]` | 0 |
| 输出 `waveform` | float32 | `[1,1,F×1920]` | — |

每次把四个 state 输出作为下次输入；position 是已消费 codec 帧数，不是采样数。KV 始终保存最近 71 帧，mask 用绝对 position 排除初始化填充并维持 72 帧滑窗。Conv packed layout 在候选 `validation.json` 完整公开，调用端可以把它当不透明状态。

- **PCM 有效区**：每次输出的全部 `F×1920` 都可提交，24 kHz，即每 codec 帧 80 ms；没有再剪前部或 overlap-add。
- **尾部/flush**：最后不足正常 chunk 的正数 F 照常调用；EOF 不产生额外 PCM，不向 graph 传 F=0，不用 EOS 或零声码伪造尾部。
- **取消**：终止当前 ORT run，丢弃该次/该句状态；新句全部清零、position=0。图本身不持有跨调用可变状态。
- 第一段 4 帧前缀虽能在约 .09 s 解码，却 RMS≈1.1e−5、几乎静音；未拿静音段冒充首段可听输出。完整 TTS 的移动 TTFA≤1.5 s 和播放不欠载仍须端到端验证。

数值证据：81 帧对官方完整 PyTorch，chunk1/4/8/13 maxdiff≤3.88e−6；**108 帧（8.64 s，超过窗口 2.88 s）**对原完整 ONNX，maxdiff≤1.38e−6、RMS≤8.19e−8。包括 chunk8/13 的尾部 4 帧。当前状态总字节固定 **5,193,992**，不随时长增长。长窗输入是重复真实声码构造的边界压力测试，不当自然语音质量样本。

异步 ORT `RunOptions.terminate` 真返回终止异常，随后清零重置与原始首块匹配。复用一个 session 连续 40 次丢弃状态，全部输入输出数组 weakref 释放；RSS 第 5 次后至第 40 次固定 648,462,336 B。session 销毁后 wrapper 释放且 RSS 下降约 219 MB，但宿主 allocator 仍保留内存；**没有声称 RSS 完全回到底线或移动端没有泄漏**。多次创建 session 的保留现象在 `lifecycle.json` 单列，建议端侧复用 session。

候选：`streaming-onnx/vocoder_streaming.onnx` + `.onnx.data`，ORT1.24.2 CPU 已加载执行；graph SHA `3138ede6fb908e72eec4ee5904bd3f158cdcca4241caa7fdd0e5112f0aa38a36`，data SHA `80e961291971c0c3e3aee657f4b4ab40eb7c264f3aae90a6233d68f2b9ca0ba7`。源官方 speech-tokenizer safetensors SHA `836b7b357f5ea43e889936a3709af68dfe3751881acefe4ecf0dbd30ba571258`。

## 重建与剩余门

`convert/export_tts_streaming_vocoder.py` 执行 eager 对拍、独立图导出和 ORT 核验；`convert/verify_tts_streaming_onnx.py` 独立验证长窗、尾块、取消/重置。环境：

```sh
tts_fidelity="$HOME/Library/Caches/Auralis/tts/upstream-fidelity"
tts_python="$HOME/Library/Caches/Auralis/tts/venv/bin/python"
PYTHONPATH="$tts_fidelity/deps:$HOME/Library/Caches/Auralis/tts/optimization/ort124:$tts_fidelity/Qwen3-TTS" \
  "$tts_python" convert/export_tts_streaming_vocoder.py
PYTHONPATH="$HOME/Library/Caches/Auralis/tts/optimization/ort124" \
  "$tts_python" convert/verify_tts_streaming_onnx.py
```

这里只验证宿主候选。仍须 Android/iOS 官方运行库实跑同图、移动内存和长期取消、端到端首段可听 PCM/持续播放、长自然 codes 与更多参考声音。无需先修改 app 流接口或原 manifest。性能表述不引用 GPU 宣传的 97 ms；官方公共 `generate_voice_clone` 返回完整 wav，也不等于已有可用的公共流式 API。
