# TTS lane 续作报告（continuation-tts）

2026-09-06。范围：Qwen3-TTS-12Hz-0.6B-Base ONNX bundle 的真实推理落地（host runner → Android/iOS 适配器 → 清单）。所有关键结论附真实命令与输出。

## 1. 固定来源与产物

- repo：`elbruno/Qwen3-TTS-12Hz-0.6B-Base-ONNX`，revision **`6a297d9641354ef0c16e63d329a93a6239bca0a2`**（HF API `sha`），共 39 项（35 文件 + 目录），约 5.9 GB。
- 下载到 `/private/tmp/auralis-models/tts/hf`（Dropbox 源码目录零权重）。`talker_prefill.onnx.data` 与 `talker_decode.onnx.data` sha256 相同（`4d8e742a81ab44ae09bbff72b728caeeaa9c3fb2bd50a0c0f0103d4a0909158b`），decode 用 `ln -f` 硬链接，省 1.7 GB；runner 报告中以 `(hardlink-verified)` 标注。
- 实测哈希与 `shared/model-manifests/tts.json` 逐字节一致（`speaker_encoder.onnx` `c5fd5fce…`、`code_predictor.onnx` `44c528d0…`、`vocoder.onnx.data` `f4cd93d2…` 等）。
- 环境：uv 创建的 **Python 3.12.9** venv（`/private/tmp/auralis-models/venv`），onnxruntime 1.29.0、numpy 2.5.2、tokenizers、soundfile。网络限制：`files.pythonhosted.org` 在当前网络不可达，改用清华镜像；HF 直连+LFS 可用。

## 2. 协议来源（作者实现，非臆测）

作者 C# 参考实现已逐行移植并与其对拍：
- `ElBruno.QwenTTS.Core/Models/LanguageModel.cs`（prefill/decode/code-predictor 循环、采样）
- `ElBruno.QwenTTS.VoiceCloning/`（VoiceClonePipeline、MelSpectrogram、SpeechTokenizer）
- `ElBruno.QwenTTS.Core/Models/{TextTokenizer,EmbeddingStore,Vocoder}.cs`

实际图签名（onnxruntime 实测，非文档假设）：
```
speaker_encoder : mel_spectrogram [B,T,128] f32 -> speaker_embedding [B,1024]
talker_prefill  : inputs_embeds [B,T,1024], attention_mask, position_ids [3,B,T]
                  -> logits [1,1,3072], hidden [1,T,1024], present_key/value_0..27 (28 层位置式)
talker_decode   : inputs_embeds [B,1,1024], attention_mask, position_ids [3,1,1],
                  past_keys/values [28,B,8,T,128] (stacked) -> logits/hidden/present_keys/values
code_predictor  : inputs_embeds, generation_steps [1] i64, past_keys/values [5,B,8,T,128]
                  -> logits [B,S,2048], present_keys/values
vocoder         : codes [B,16,T] i64 -> waveform [1,1,T*1920]
```

关键坑（实测发现并修复）：
1. **特殊 token 不在 vocab.json**（Qwen 放 added tokens，bundle 未带）。作者 C# 用 canonical id 硬编码注册：`<|im_start|>=151644` … `<|audio_pad|>=151675`。首轮实现漏掉 → 中文 6 字被编码成 21 个字节级 token，合成内容错误（ASR 回转写"宅，不舍，那房子里"）。修复后 token 与 HF `Qwen2Tokenizer` 逐位一致（见 §3）。
2. **mel 滤波器的 mel→Hz 回转**：librosa slaney 刻度必须先 Hz→mel 取均匀点再 mel→Hz 回到 Hz 域构三角；Kotlin/Swift 移植初版漏掉回转导致全零滤波（测试抓到）。
3. npy 读取需跳过 minor version 字节；`'shape'`/`'fortran_order'` 字段带引号/裸布尔两种形态都要处理。
4. GPT-2 `bytesToUnicode` 按**字节值**索引（printable 映射到自身），不能按 printable 列表位置索引。

## 3. Host runner：`convert/tts_runner.py`

### 3.1 与参考分词器对拍（真实 bundle，transformers 慢路径）
```
tokenizer: tokref = bundle vocab.json+merges.txt + added_tokens_decoder(canonical ids)
AutoTokenizer.from_pretrained("tokref", use_fast=False)
你好，世界。 / Hello world, this is a real speech synthesis test. / 今晚的月色真美，我们去散步吧！
→ 3/3 match（build_prompt_ids 与 HF 逐 id 一致）
```

### 3.2 mel 前端与 PyTorch+librosa 数值对拍
```
ref_zh.wav: cosine 1.00000012, max abs diff 0.0002985
ref_en.wav: cosine 0.99999976, max abs diff 0.00040245
filterbank max abs diff vs librosa: 3.7e-9 (float32)
```

### 3.3 真实合成 + 真实 ASR 回转写（sherpa-onnx Qwen3-ASR-0.6B INT8，`convert/asr_runner.py`）
| 输入 | 语言 | 参考 wav | 输出 | 回转写 |
|---|---|---|---|---|
| 你好，世界。 | chinese | ref_zh (say-Tingting 24k) | 25 帧 2.0s, peak .48 | **"你好，世界。"**（精确） |
| Hello world, this is a real speech synthesis test. | english | ref_en (say-Samantha) | 41 帧 3.3s, peak .67 | **"Hello world. This is a real speech synthesis test."**（仅标点差） |
| 用英文声音说中文，测试跨语言克隆。 | chinese | ref_en（跨语言克隆） | 49 帧 3.9s | **"用英文声音说中文测试跨语言克隆。"** |
| 你好，世界。（CLI 重构后） | chinese | ref_zh | 2.0s | **"你好，世界。"**（中央新版 asr_runner，segmented+unsegmented 均精确） |

中文 25 帧 wall 5.6s、英文 41 帧 7.2s（4 线程 CPU，M 系列）。

克隆相似度（真实 speaker encoder 自评）：same-voice cos 0.9775/0.9842 > cross-voice 0.95/0.9561；但两个 `say` 参考音自身 cos 0.9666，区分度弱（参考音本身是 TTS 音，声学接近）——只报告不宣称质量。

### 3.4 CLI 契约（按主审要求）
- `--json-report PATH`：成功报告含 `status/text/language/model_dir/reference_wav/audio_path/sample_rate/duration_s/frames/prompt_tokens/peak/rms/clipping_ratio/finite/seed/sampling/timing_s{hash,prefill,generate,vocoder,wall}/runtime_versions/model_sha256(9 项)/generated_at`；失败报告 `status:"error"` + `error_type/error/generated_at`，**不含任何成功字段**。
- 退出码：0 成功；1 执行失败（缺 bundle/图错误/非有限值/静音）；2 缺依赖（onnxruntime/numpy/tokenizers/soundfile，`importlib.util.find_spec` 预检）；4 参数错（自定义 `TtsArgumentParser.error`）。实测：`/nonexistent` → exit 1；缺 `--out` → exit 4；`python -S`（依赖不可见）→ exit 2。
- 顶层 import 仅 stdlib；重依赖在首次执行时懒加载 → 轻量测试环境（无 onnxruntime）不受影响。
- 模型哈希：9 个图/数据文件 sha256 实算入报告（硬链接去重，1.3s）。

## 4. 测试

`convert/tests/test_tts_runner.py`（unittest，importlib 装载 runner，stdlib-only）：
- canonical 特殊 token id 表、依赖缺失清单、CLI 0/1/2/4 退出码、失败报告 schema（禁止成功字段）、硬链接哈希去重、门控全管线 smoke（`AURALIS_TTS_MODEL_DIR`+`AURALIS_TTS_REF_WAV`，独立计时 7.08s 真实执行，校验 finite/frames/峰值/哈希/runtime 版本）。
- `python3 -m unittest discover -s convert/tests -t .`：**plain python3（轻量）81 tests OK (skipped=2)；venv（含依赖）81 tests OK (skipped=1)**。

Android（JVM 单测，`android/app/src/test/.../Qwen3TtsProtocolTest.kt`，17 用例全绿）：
- byte 编码 256 唯一、BPE 最低秩合并、特殊 token 原子性、prompt 模板切片、采样器（EOS 抑制、codec 区间屏蔽、重复惩罚、定种子确定性）、mel 滤波/对数 mel 与 host 常量对拍（eps 2e-4/2e-3）、16k→24k 重采样、prefill 布局（speaker 槽位注入、trailing 行数）、npy 读写往返、语言映射。
- **门控真 bundle 分词测试**（`AURALIS_TTS_MODEL_DIR` 环境变量）：真实 vocab.json+merges.txt 下断言 HF 已验证 id 序列（含中文），0 skipped，真实执行通过。
- `:app:compileDebugKotlin` 通过（offline，JDK20）。

## 5. 移动端适配器（协议已证，随证移植）

### Android（独占范围）
- `inference/TtsEngine.kt`：完整重写。`Qwen3TtsProtocol`（纯函数，无 ORT 依赖）+ `Qwen3TtsEmbeddings`（npy；text_embedding 1.24GB `MappedByteBuffer` mmap）+ `TtsEngine`。串行加载，prefill 会话跑完即 `release`（省 ~1.7GB）；KV shape 校验（[1,8,T,128] 每层 → stacked [28,1,8,T,128]）；vocoder [1,16,T] 输出样本数精确校验；静音/空帧/非有限值全部抛 `ModelProtocol.UnsupportedModelException`。旧 `talker_lm_int4.onnx` 接口与 `tokenizer.json` 字符查找已删除。
- `SpeakerEmbeddingExtractor.kt`：文档对齐（raw 嵌入、16k→24k、mel 前端），实现走新引擎。
- 注意：新协议返回**未 L2 归一化**的 ECAPA 嵌入（与作者/ host 一致）；旧构建持久化的归一化 profile 幅度会缩放条件向量，属迁移边界（见 §7）。

### iOS（独占范围）
- `Inference/TtsEngine.swift`：完整移植（`Qwen3TtsConfig/Qwen3TtsTokenizer/NpyFloat2D/Qwen3TtsEmbeddings/Qwen3TtsProtocol` + 引擎），全部经 `ModelSessionStore` actor 执行，npy 用 `.mappedIfSafe`。
- 主审 Catalyst26.5 全源 typecheck 后，我按日志修复并自跑：
```
swiftc -typecheck -swift-version 5 -target arm64-apple-ios17.0-macabi \
  -sdk /Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk \
  -module-cache-path /private/tmp/auralis-catalyst26-module-cache \
  -F .../System/iOSSupport/System/Library/Frameworks \
  -I .../System/iOSSupport/usr/include -I /private/tmp/auralis-ort-headers \
  -I ios/DialectInterpreter/Inference/SherpaOnnx $(rg --files ios/DialectInterpreter -g '*.swift')
```
**TtsEngine.swift 0 error / 0 warning**（日志为旧版本）。全仓剩余错误仅在 `UI/Components/DialectSelectorView.swift`（appAccent/appText 主题色），非我独占范围，未动。

## 6. 清单（`shared/model-manifests/tts.json`）

- `source.revision` 钉死为 `6a297d96…`；`runtime.runtimeRevision` 记录 host 1.29.0 / mobile 1.22.0+1.24.2。
- `capabilities.modes` 增 `clone`；`languages` 增 `en`；verification：languages/endToEnd/clone=**bench-verified**（host 基准证据），device=**unverified**。
- `status` 保持 **draft**：设备门未过不翻 verified；notes 补充协议事实（图签名、特殊 token、硬链接、legacy talker_lm 不兼容）。`load_manifest_v2` 校验通过，81 测试无回归。

## 7. 未实现与边界（如实）

1. **设备门全部未开**：真机延迟/内存（prefill+decode 各引用 ~1.7GB 权重 + ~160MB 表）/热稳定/克隆质量主观评估未做。Android 已做串行加载+prefill 释放；iOS session store 持有全部会话，内存门未证。
2. **移动端图执行未在真机跑过**：协议代码与 host 逐位对齐且 JVM 单测过，但 Android `.so` 链接、iOS 整机构建由其它门负责。
3. **ICL 模式（ref_text+ref codes）未实现**：bundle 不含 `tokenizer12hz_encode.onnx`，请求时明确拒绝，不模拟。
4. **auto 语言**：host 支持 `auto`；移动端 `languageKeyFor` 只接受显式语言（诚实拒绝），当前管线总是显式传目标语言，无功能损失。
5. **旧 profile 迁移**：`extractSpeakerEmbedding` 从"16k 波形直入 + L2 归一化"改为"重采样 24k + mel + raw 输出"；旧数据若存在会得到不同条件幅度（正确性以新协议为准）。
6. **采样回环风险**：温度 0.9 采样下长文本偶发退化循环可能（作者参考默认参数保留）；tokenizer 修复前的 81s 循环问题已消除。
7. **llama.cpp libmtmd 对照**：按任务要求评估。作者 bundle 六图协议完整且已 host 证毕；切换 llama.cpp 1.7B GGUF 意味着换模型（质量/内存全变）且 0.6B Base 支持未经其上游验证——无收益，消融保留 ONNX 六图路线，不引入第二运行时。
8. 中央 suite 的"有参考样本的 ASR roundtrip/CER"引用本报告 JSON 时：本 lane 只证执行与内容回转写精确，**不主张先进或产品级克隆质量**。

## 8. 协作与文件边界

- ASR 回转写用 `convert/asr_runner.py` 真实 runner（其新旧 schema 均已适配读取）；未改 ASR/MT/validate_models/Gradle。
- `DialectSelectorView.swift` 主题色错误与 PipelinePlatformPorts/Core MT release 归主审；Android 设备侧 Gradle 调度归 GLM03，本 lane 未再动 Gradle。
- 独占改动集：`convert/tts_runner.py`、`convert/tests/test_tts_runner.py`、`android/.../inference/TtsEngine.kt`、`android/.../inference/SpeakerEmbeddingExtractor.kt`、`android/app/src/test/.../Qwen3TtsProtocolTest.kt`、`ios/DialectInterpreter/Inference/TtsEngine.swift`、`shared/model-manifests/tts.json`、本报告。
