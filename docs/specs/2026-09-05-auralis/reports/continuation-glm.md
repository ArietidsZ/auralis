# Continuation — GLM lane：Qwen3-ASR 真实 sherpa-onnx 部署路径

2026-09-06。本轮把 ASR 从"猜 tokenizer/mel/decoder 接口"切换到维护者支持的 sherpa-onnx Qwen3-ASR-0.6B INT8 路径，完成 host 真实推理、可复现 runner、清单回填与移动端部署证据。手机/模拟器设备门仍未做，状态不自动升级。

## 固定版本与环境

| 项 | 值 |
|---|---|
| 宿主 | macOS 27.0 arm64（Apple Silicon 笔记本），非目标移动设备 |
| Python | 3.12.9，venv `/private/tmp/auralis-models/asr-venv`（uv 创建） |
| sherpa-onnx | 1.13.7 官方 wheel（`sherpa_onnx-1.13.7-cp312-cp312-macosx_11_0_arm64`）。`files.pythonhosted.org` 在本网络 TLS 失败，经 `pypi.tuna.tsinghua.edu.cn` 镜像安装同一 wheel |
| 模型包 | 官方 asset `k2-fsa/sherpa-onnx` release tag `asr-models` → `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2`，878,702,423 bytes，sha256 `393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96`，位于 `/private/tmp/auralis-models/asr/`（多 GB 权重不进 Dropbox 源码目录） |
| ONNX 导出源 | 模型包内 README 指明 `Wasser1462/Qwen3-ASR-onnx`（modelscope 镜像 `zengshuishui/Qwen3-ASR-onnx`）；上游权重 `Qwen/Qwen3-ASR-0.6B`（Apache-2.0） |
| Android 预编译 | `sherpa-onnx-v1.13.7-android.tar.bz2`（45,287,000 bytes，tag `v1.13.7` = commit `917bed95c8e5c7c18aa4d69fea42e9ef8ef0a60e`），解包含 4 ABI 的 `libsherpa-onnx-jni.so`/`libonnxruntime.so`/`c-api`/`cxx-api`，位于 `/private/tmp/auralis-models/mobile/jniLibs/` |
| sherpa-onnx repo 无 Maven Central 官方坐标 | `search.maven.org` 仅第三方 repack `com.bihe0832.android:lib-sherpa-onnx`，不可作为维护者支持来源 |

## 真实推理证据（host）

命令（完整可复现，15/16 官方 test_wavs，转写索引来自官方 `transcript.txt`）：

```
/private/tmp/auralis-models/asr-venv/bin/python convert/asr_runner.py \
  --models-dir /private/tmp/auralis-models/asr/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 \
  --audio <每个 test_wavs/*.wav> \
  --transcripts .../test_wavs/transcript.txt \
  --json-report /private/tmp/auralis-models/asr/report-all.json
```

接口为官方 Python API `sherpa_onnx.OfflineRecognizer.from_qwen3_asr(conv_frontend, encoder, decoder, tokenizer, num_threads)`，未从数组形状推断任何结果。逐条结果（2 线程）：

| 音频 | CER | RTF |
|---|---|---|
| de.wav（德语，16kHz） | 0.0 | 0.152 |
| fr1.wav / ja1.wav | 0.0 / 0.0 | 0.158 / 0.156 |
| raokouling.wav（中文绕口令 20.8s） | 0.054 | 0.209 |
| cantonese.wav（粤语） | 0.086 | 0.279 |
| ar1 / es1 / ru1 / f1_noise / rap1 | 0.035–0.182 | 0.13–0.28 |
| fast1 / codeswitch / noise2 | 0.198 / 0.268 / 0.355 | 0.15–0.25 |
| noise1-en（88s 噪声场景剧） | 0.995（截断，见限制） | 0.056 |
| qiqiu1（51s 长歌词） | 1.0（截断，见限制） | 0.065 |

mean CER（含截断条目）= 0.2276；不含两条截断条目 ≈ 0.098。输出文本与 `transcript.txt` 逐字可比，JSON 报告保存在 `/private/tmp/auralis-models/asr/report-all.json`。WER 对中文无意义（无空格分词），以 CER 为准。

## 交付物

1. **`convert/asr_runner.py`**（新增）：CLI `--models-dir --audio(可重复) --reference(可重复)/--transcripts --num-threads --json-report`；模型/tokenizer/sherpa-onnx 缺失、音频缺失、reference 数量不匹配一律 exit 2；C 级 stdout 重定向到 stderr 保证 JSON 干净；CER/WER/RTF 计算纯函数化。无任何 fixture 冒充推理。
2. **`convert/tests/test_asr_runner.py`**（新增，13 用例）：只测度量数学、WAV 解析、CLI 错误路径与 transcript 索引解析；不触碰真实模型。
3. **`shared/model-manifests/asr.json`**（回填）：source 改为 `Wasser1462/Qwen3-ASR-onnx`（export repo，`revision=null` 因该 asset 由 release 固定，非 git SHA；upstream `Qwen/Qwen3-ASR-0.6B`）；6 个文件真实 sizeBytes+sha256；roles conv_frontend/encoder/decoder/tokenizer_vocab/tokenizer_merges/tokenizer_config；runtimeRevision `sherpa-onnx v1.13.7 (tag 917bed95…)`；quantization `int8`；executionProviders 收敛为 `cpu`（无设备证据不声称 nnapi/coreml）。**status 保持 draft**。`convert/manifest_contract.py validate` 通过；`validate_models.py --package asr` integrity/roles PASS（exit 2 仅因 draft 门禁，正确行为）。
4. **`convert/tests/test_validate_models.py`**：`test_empty_bundle_dir_must_not_pass` 原依赖仓库 asr.json 为空 files 列表；改为显式传入空 draft manifest，使该复现用例与本次回填解耦（意图不变：空包绝不通过）。

## 移动端部署方案与接口请求（未接入，需与主审协调）

**现状**：Android `AsrEngine.kt` 与 iOS `AsrEngine.swift` 是手写 mel/FFT/tokenizer/decoder 对假想 INT4 协议的实现。官方包证明其输入假设（80 mel、int4 encoder/decoder、`tokenizer.json` 单文件）与真实 bundle（128 维 conv frontend、int8 encoder+decoder、`vocab.json+merges.txt` BPE 目录、KV-cache LLM decoder 28 层）完全不符，无法被任何真实资产验证。

**已核实的真实移动接口**（来自 v1.13.7 commit `917bed95…` 的源码与本机解包）：

- Android：`libsherpa-onnx-jni.so` 导出 `Java_com_k2fsa_sherpa_onnx_OfflineRecognizer_{newFromFile,newFromAsset,createStream,decode,decodeStreams,getResult,setConfig,delete}`；Kotlin wrapper `sherpa-onnx/kotlin-api/OfflineRecognizer.kt` 含 `qwen3Asr` 配置段（convFrontend/encoder/decoder/tokenizer/maxNewTokens/temperature/topP/seed/hotwords）。需要把 jniLibs 四 ABI `.so` 与对应 Kotlin wrapper 文件引入 `android/app`——**本轮禁改 Gradle**，且必须先与 `OnnxModelManager` 现有 ORT 1.22.0 会话共存（重复 `libonnxruntime.so` 冲突风险）评估后由主审选择：a) vendored jniLibs+Kotlin 文件（无 Gradle 改动也可行，src/main/jniLibs 为默认 sourceSet）；b) 上游建议的 AAR/jar 方式。
- iOS：sherpa-onnx 不发布 iOS xcframework release asset，需从 v1.13.7 源码 `-DSHERPA_ONNX_TARGET_IOS=ON` 构建 `sherpa-onnx.xcframework` 后经 **Package.swift（本轮禁改）** 引入；本机只有 CommandLineTools，无法验证 iOS 构建，此为 blocked 而非通过。
- C API：`sherpa-onnx/c-api/c-api.h` 的 `SherpaOnnxOffline*Config` 系列可作为 Kotlin/Swift 之外的共同语义参照。

**接口请求**（请主审确认后，下一轮即可执行替换）：

1. Android：新增 `SherpaOnnxAsrEngine : SpeechRecognizer`（保持现有 load/transcribe/release 接口与 PipelineOrchestrator 零改动），删除手写 mel/tokenizer 路径；manifest roles 即为文件来源。
2. iOS：同型 `AsrEngine` 替换，经由 xcframework C API；`files`/roles 与 Android 共享同一 asr.json。
3. schema 层面：`backend` 枚举仅 `onnx|gguf-llama-cpp`。本轮不改 schema，asr.json 采用 `backend=onnx` 兼容映射并在 notes 中声明；如主审希望区分，请提供新枚举值（如 `sherpa-onnx`）后再改。

## 消融与取舍

- 官方 sherpa-onnx 路径 vs 手写协议：官方路径用真实 bundle 产出可复核文本；手写路径无任何可验证资产，且当前 AsrEngine 期望的 int4/80-mel/tokenizer.json 在官方包中不存在。结论：保留 `SpeechRecognizer` 边界，删除两端手写 mel/FFT/tokenizer 实现是主线（待依赖接入后执行）；本轮未先删除以免破坏 114 用例的 Android 工程门。
- runner 内自写 CER/WER（~40 行）vs 引入 jiwer 等依赖：文本为中文为主、无分词歧义，标准 Levenshtein 足够，不新增依赖。

## 限制（不声称已完成的部分）

1. **设备门未做**：全部性能/质量数据来自 host CPU（Apple Silicon，2 线程，RTF 0.13–0.28）；Android/iOS 端到端、内存、发热无证据。
2. **长音频截断**：本 INT8 导出 `max_total_len=512`（KV 上限），音频 token 约 8.7/s，>~40s 剪辑触发上游截断逻辑，noise1-en/qiqiu1 输出退化为 "language" 一词。需 VAD 分段（应用侧或 sherpa-onnx VAD）后再识别；已写入 runner 使用限制。
3. `revision=null` + draft：符合契约（draft 永不视为 ready），不能作为 verified 清单使用。
4. PyPI 主站在本网络不可达，wheel 经清华镜像安装（同一文件名/版本）；如需供应链复核可用 `pip download --no-deps sherpa-onnx==1.13.7` 比对哈希。

## 复核命令

```bash
# 测试（与本机 python3.14 等效）
/private/tmp/auralis-models/asr-venv/bin/python -m unittest discover -s convert/tests -p 'test_*.py'   # 40 OK
python3 scripts/verify --mode fast   # 8 pass, 0 fail
# 真实推理
/private/tmp/auralis-models/asr-venv/bin/python convert/asr_runner.py \
  --models-dir /private/tmp/auralis-models/asr/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 \
  --audio /private/tmp/auralis-models/asr/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/test_wavs/de.wav \
  --transcripts /private/tmp/auralis-models/asr/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/test_wavs/transcript.txt \
  --json-report /tmp/asr.json
# 清单完整性
ln -sfn /private/tmp/auralis-models/asr/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25 /private/tmp/auralis-models/check/asr
python3 convert/validate_models.py --models-dir /private/tmp/auralis-models/check --package asr
```
