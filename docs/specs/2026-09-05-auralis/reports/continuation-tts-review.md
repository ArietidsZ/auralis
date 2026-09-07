# TTS 独立复核报告（continuation-tts-review）

2026-09-07，TTS 独立复核者（max）。范围：Qwen3-TTS-12Hz-0.6B-Base ONNX bundle 的协议、host 工程、质量代理与性能消融的独立验证。环境曾在 /private/tmp 丢失，全部产物已恢复至持久缓存 `$AURALIS_CACHE/`（tts/ ios/ asr/ mt/），恢复脚本与哈希核对见下。

## 0. 结论（先读）

1. **发现并修复一个三端协议级 bug**：vocoder 输入码矩阵被转置（frame-major 数据填入 `[1,16,T]` group-major 形状）。样本数恰好匹配、无形状错误、音频"非空非静音"——上一 lane 的"真实执行"验证必然漏检此问题。修复前 Swift 6/6 回转写全是退化重复文本；修复后 Swift 与 Python 贪婪解码波形 **cos=1.000000**（max|diff| 3e-6..6e-5，跨 ORT 版本浮点噪声底）。Android 同一 bug 同步修复（`Qwen3TtsProtocol.flattenCodesGroupMajor` + 单测）。此 bug 证明"非空输出=通过"不是验证。
2. **iOS 生产代码已真实编译、链接、执行**（非 typecheck）：官方 ObjC bindings 9 个 .mm（ARC/C++17/SPM_BUILD）+ 官方 pod archive（sha256 与官方 Package.swift 钉死值一致）+ 生产 Swift 源，host macOS 上跑通全部 6 用例 + 取消 + 无 profile 拒绝 + 确定性。
3. **清单修正**：删除重复 `runtimeRevision` 键；`capabilities.verification` 全部回到 `unverified`（原 bench-verified 证据不足）；notes 写明升级所需证据。
4. **tts_runner.py CLI 修正**（中央联调反馈）：`--language` 接受 BCP-47 短码与 canonical 名，进入任何重工作前归一化；不支持的语言 **exit 4（0.08 s，不加载模型）**，不再 11 s 加载后 exit 1；报告新增 `language_requested` 保留原始请求。
5. **内存消融量化并修复**：talker_prefill/decode 的 1.7 GB 权重被两个 session 各自持有一份（硬链接不减少 ORT 内存）。改为每次合成后串行释放 prefill/decode（与 Android 设计一致），峰值 5.29 GB → **3.68 GB**（max RSS），代价 +~2.5 s/回合（prefill+decode 重载）。
6. 质量证据（host）：12/12 回转写精确（zh/en/cross × Python/Swift）；独立 ECAPA 说话人验证 12/12 聚类到参考声音。**清单保持 unverified**：样本量未达本报告自设门槛（每语言 ≥10），设备门未开。

## 1. 恢复与来源（全部可复核）

- **TTS bundle**：HF `elbruno/Qwen3-TTS-12Hz-0.6B-Base-ONNX` @ `6a297d9641354ef0c16e63d329a93a6239bca0a2`（HF API sha 确认），37 文件 5.92 GB 重下至 `$AURALIS_CACHE/tts/hf`；35 个清单文件 **逐一 sha256+size 与 shared/model-manifests/tts.json 匹配（35/35）**；prefill/decode .data 硬链接（manifest 同哈希 4d8e742a…）。
- **上游权威参考**：`Qwen/Qwen3-TTS-12Hz-0.6B-Base` @ `5d83992436eae1d760afd27aff78a71d676296fc`（本地缓存复制到 `tts/upstream-qwen`）。
- **ORT 1.24.2**（`$AURALIS_CACHE/ios/`，见其 README.md）：pod archive sha256 `f7100a99…` = 官方 Package.swift 校验和；ObjC 源 = git tag 1.24.2（`b7fb7f7d…`）；9 个非训练 .mm 以 `clang -x objective-c++ -std=c++17 -fobjc-arc -DSPM_BUILD` 编译（含 assert_arc_enabled.o 证明 ARC 开启）；public headers 模块 `ort-headers/` 供主审全源 typecheck 复用（`-I …/ios/ort-headers`）。
- **Python venv**：Python 3.12，onnxruntime 1.29.0、numpy 2.5.3、tokenizers 0.23.2、soundfile 0.14.0、transformers 5.16.1（清华镜像，files.pythonhosted 不可达）。
- 参考声音为 macOS `say` 重新生成（Tingting zh_CN 24 kHz、Samantha en_US 24 kHz；与丢失前同引擎同格式，文本为本轮固定文本）。

## 2. 协议独立验证（mandate 第 1 项）

全部脚本与判定 JSON 在 `tts/eval/verify_protocol.json`、`verify_protocol.py`。

| 检查 | 结果 | 证据 |
|---|---|---|
| 特殊 token id vs **官方 Qwen tokenizer_config.json** added_tokens_decoder | 10/10 精确一致（151643..151675） | 官方文件即来源；bundle vocab.json 确实不含这些 token（151643 词条上限） |
| bundle vocab/merges vs 上游 Qwen 仓库 | 字节相等 | sha256 |
| bundle embeddings/config.json vs 上游 config.json | talker/cp/tts 全部字段一致；`language_ids` == `talker_config.codec_language_id`（chinese 2055 … russian 2069） | 脚本比对 |
| tokenizer vs **HF transformers 慢路径**（Qwen2Tokenizer，官方 added tokens） | 8/8 语料逐 id 一致（含中英混排/emoji/无标点长句） | tokref 构建 + AutoTokenizer use_fast=False |
| mel 滤波器 vs librosa（slaney, 24 k/1024/256/128/0–12 k） | max abs diff < 1e-5 | librosa.filters.mel |
| mel 前端 vs librosa STFT（reflect pad、周期 hann） | ref_zh/ref_en cos > 0.999 | librosa.stft |
| 图签名（5 图 I/O 名/类型/形状） | 与 manifest notes 断言一致 | ORT session 元数据（见 verify_protocol.json graph_signatures） |
| **speaker embedding 跨运行时** | Python(1.29.0) vs Swift(1.24.2) cos = 1.0000000，max|diff| 1e-6 | 同一参考 wav |
| **prefill 嵌入跨运行时** | prompt ids、10×1024 嵌入、trailing 行逐值一致（~1e-7） | dump-prefill 对拍 |
| **prefill logits step-0** | top-5 (1995/1221/404/1464/279) 值差 ~1e-5 | 双侧对拍 |
| **decode step-0**（合成 next 输入） | top-5 (2149/215/2042/210/678) 一致；present_keys 一致到 1e-6 | 双侧对拍 |
| **贪婪全序列**（top-k=1） | 27 帧 g0 序列逐 token 一致；波形 cos=1.000000 | 修复 vocoder 后 |
| 采样策略差异 | 数学路径（top-k 阈值/温度/max 减除/累积归一）逐行等价；**RNG 不同**（numpy PCG64 vs SplitMix64），种子不可跨运行时互换——已写入两份代码注释 | 代码 diff |

温度采样下 Swift 与 Python 的帧数/内容差异（如 zh1 22 vs 24 帧）完全由 RNG 差异导致，贪婪模式已证协议位等价。

### 发现的 bug（修复均在本 lane 独占文件内）

1. **vocoder 码矩阵转置**（Swift + Kotlin 同源错误）：`runVocoder`/`generateFrames` 以 frame-major 顺序填充 flat 数组，却声明 `[1, NUM_CODEBOOKS, frames]`（group-major）。后果：样本数精确匹配、峰值正常、无异常抛出，音频内容完全错误。修复后贪婪波形 cos=1.000000。Kotlin 侧提取为纯函数 `flattenCodesGroupMajor` 并新增 2 个单测（Gradle 运行归 Android lane）。
2. **NpyFloat2D 拒绝 1-D bias**（Swift）：bundle 的 `text_projection_fc{1,2}_bias.npy` 是 `(N,)` 一维，原实现假设 `(1,N)` 二维直接抛错——说明该引擎从未对真实 bundle 执行过。已支持 1-D/2-D。
3. **logits 非有限值处理分歧**：Python 采样遇 NaN 会抛错，Swift 原实现会静默采样末位 token。已在 Swift 采样前加 `allSatisfy(\.isFinite)` 守卫（badOutput）。
4. **OnnxModelManager repository 与注入根不一致**（主审反馈）：`repository` 未绑定 `store.modelsRoot`，refreshStatuses 查默认目录而 probe 读注入目录。已修：`ModelRepository(probe:…, modelsDir: store.modelsRoot)`。MT probe 的 `OwnedHandle.release()` 用法保留。

## 3. iOS host 真实执行（mandate 第 2 项）

- 编译：生产源（TtsEngine/OrtRuntime/OnnxModelManager + Data/* + AsrEngine + HyMtNativeBridge）+ harness，`swiftc -O -target arm64-apple-macos27.0`，链接官方静态 archive + bindings .a + sherpa + hymt。二进制 `tts/ios-host/bundle/auralis-tts-host`（32.7 MB）。
- 运行结果（修复后，threads=4）：

| 用例 | 语言/声音 | 帧 | 时长 | ASR 回转写 |
|---|---|---|---|---|
| zh1 | zh/Tingting | 22 | 1.76 s | 你好，世界。（精确） |
| zh2 | zh/Tingting | 49 | 3.92 s | 今天天气不错，我们去公园散步吧。（精确） |
| en1 | en/Samantha | 38 | 3.04 s | Hello world. This is a real speech synthesis test.（仅标点） |
| en2 | en/Samantha | 37 | 2.96 s | The quick brown fox jumps over the lazy dog.（精确） |
| cross1 | zh 文本/Samantha | 29 | 2.32 s | 你好，很高兴认识你。（精确） |
| cross2 | en 文本/Tingting | 43 | 3.44 s | Nice to meet you. This is a cross language test.（仅标点） |

- 确定性：同 seed 同进程两次合成逐样本相等 ✓。跨 ORT 版本贪婪模式波形 cos=1.000000 ✓。
- 无 profile：`speakerEmbedding=nil` → badInput 拒绝（bundle `spk_id={}` 无默认声音，不伪造零向量）✓。
- 取消：每帧 `Task.checkCancellation()`；cancel() 后 0.09–1.1 s 抛 CancellationError（1 s 情形=取消落在 prefill 加载段，native load 不可中断，如实报告）。PipelineOrchestrator 已有 `catch is CancellationError` 通路 ✓。
- 终止预算：`maxFrames`（默认 2048 ≈ 163.8 s）✓。异常不吞：图/协议错误全部 thrown ✓。
- 内存：会话逐个探测 speaker +9 MB / prefill +1719 MB / decode +1712 MB / CP +708 MB / vocoder +239 MB；串行释放后峰值 3.68 GB、稳态 ~2.0 GB、release() 后 22 MB。

## 4. 质量代理与性能消融（mandate 第 3 项）

固定：参考声音（say Tingting/Samantha）、文本、seed、threads=4、CPU（M 系列 8P+4E，32 GB）。所有用例与失败原始 JSON 在 `tts/eval/`（python_cases_t4.json、swift_cases*.json、rt2_*.json、speaker_verification.json）。

- **回转写可懂度**：真实 sherpa-onnx Qwen3-ASR（1.13.7，主审恢复的 bundle），segmented 路径，12/12 内容精确（上表 + Python 侧同 6 用例全对）。这不是"先进性"证明，只是可懂度代理。
- **说话人相似度（独立指标）**：speechbrain ECAPA-VoxCeleb（非 bundle 自带 encoder）。基线：两个 say 声音自身同/跨余弦 0.840/0.250（区分度充足）。TTS 输出 12/12 聚类到其参考声音（同 0.47–0.73 vs 跨 0.08–0.39；跨语言用例裕度最小 0.09）。**局限如实声明**：参考池全部是 say 合成音（窄分布）、ECAPA 对合成音是 out-of-domain、n=2 声音——该证据支持"克隆身份在独立验证器下可分"，不支持产品级克隆质量。
- **warm/cold**（Swift host，含每次合成的 prefill/decode 重载）：cold 首回合 5.1 s（1.76 s 音频），warm 5.5–8.0 s。Python 冷进程每用例 8.4–15.6 s（含 1.3 s 哈希+全量会话加载，无释放）。RTF≈2.3–2.7，未达 RTF≤1，与暂停报告结论一致。
- **线程消融**（Python，zh2 固定文本 53 帧）：t1 20.6 s / t2 22.4 s / **t4 11.4 s** / t6 11.9 s / t8 11.2 s。t2 比 t1 慢（小矩阵同步开销），t4 为实测最优；输出与线程数无关（同 seed 同帧同峰值）。Swift 同序：t1 13.4 / t2 12.0 / t4 9.9 s。**保持 4 线程**；Swift 侧新增显式 `setIntraOpNumThreads` + `.all` 优化级别（原 `sessionOptions: nil` 会按全核自旋，与 ASR/MT 并行时过订阅）。
- **明显浪费评估**：
  - 双份 1.7 GB 权重：量化确认（会话逐个 +1719/+1712 MB），硬链接不省 ORT 内存 → 串行释放修复（峰值 5.29→3.68 GB；+2.5 s/回合是内存受限设备上的诚实代价，设备门可复测）。
  - 共享初始化器（C API `AddExternalInitializersFromFilesInMemory`）可免重载，但 ObjC bindings 未暴露，引入第二绑定路径违反 Occam——不做，记录为可选后续。
  - NPY 全量读：无（三端均 mmap/映射 text_embedding 1.24 GB）。
  - 每 token 重建 session：无（会话缓存）。
  - 每次运行 1.3 s 哈希：保留（中央 provenance 要求）。
- **量化**：未动权重/哈希。若未来做，需先给配对证据（同文本 fp32 vs 量化回转写 CER + ECAPA 分数的成对对比），见 manifest notes。

## 5. 中央联调接口修正（主审反馈）

1. **语言归一化**：`normalize_language()`（canonical 名 + BCP-47 短码 + 常见区域子 tags + auto，大小写/下划线不敏感）；`main()` 在依赖检查后、任何模型 IO 前验证，失败走 `parser.error` → **exit 4**（实测 0.08 s）；`synthesize()` 库内也在加载前归一化。报告字段：`language` = canonical，`language_requested` = 原始请求（中央可核对）。测试：normalization 表、exit-4 快速失败、`zh` 被接受（轻量环境下自动 skip，venv 全跑）。
2. 与共享契约一致：`capabilities.languages` = zh/en；Swift `languageKey(for:)` 与 Kotlin `LanguageCodes.normalize` 均已接受短码，三端语义现在统一（canonical 进生成，原始进报告）。
3. **ModelsRoot 注入闭环**：`OnnxModelManager.init(modelsRoot:intraOpThreads:)`；repository 绑定 `store.modelsRoot`（见上）。probeRuntime 的 ASR/MT 分支（主审所修）原样保留，仅 MT handle 释放适配 `OwnedHandle.release()`。
4. **不回归证明**：全源 Catalyst 26.5 `-typecheck` exit 0（本次修改后复跑；仅 Audio 文件既有 deprecation 警告）。Python 套件：轻量 `OK (skipped=4)`、venv `OK`、门控真实 smoke `OK`。

## 6. 门与边界（区分工程验证/质量代理/设备）

- **host 工程验证（已完成）**：协议对拍、图签名、跨运行时位等价、取消/预算/拒绝路径、内存与线程消融、CLI exit 契约。
- **质量代理（host，样本有限）**：12/12 回转写、ECAPA 12/12 聚类。清单 `capabilities.verification` 保持 unverified——升级到 bench-verified 需（见 manifest notes）：每语言 ≥10 样本（含长文本/混排）+ CER 阈值 + 独立 SV 分离超基线 + 全部用例公开；本次证据未达样本量门槛，故不升。
- **设备门（未开）**：真机延迟/内存（3.68 GB 峰值是 host 数字；iPhone jetsam 预算需实测）、热稳定、移动端 ORT 线程 pin、Android Gradle/设备测试（GLM lane；Kotlin vocoder 修复与 2 个新单测需其重跑 `:app:testDebugUnitTest`）。
- 已知残留：ICL 模式不可用（bundle 无 tokenizer12hz_encode，拒绝而非模拟）✓ 诚实；auto 语言仅 host 支持；`position_id_per_seconds=13`（上游）与导出协议 +1/帧不同——导出自洽且已验证，记录不改。

## 7. 产物索引

- 复核脚本：`tts/eval/verify_protocol.py`、`python_cases.py`、`speaker_verify.py`；判定 JSON 同目录。
- ORT 恢复与复用路径：`$AURALIS_CACHE/ios/README.md`（主审 typecheck 用 `…/ios/ort-headers`）。
- Swift host harness：`tts/ios-host/`（bundle 结构 + models-root 注入示例）。
- 接口变更清单：`reports/interface-tts-review.md`。
