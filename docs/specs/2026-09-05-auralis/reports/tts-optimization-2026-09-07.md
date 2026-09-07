# TTS 性能与量化消融（2026-09-07）

结论：**原生 ORT 1.24.2 的 MatMulNBits、accuracy_level=4 确实解决主要矩阵乘成本；仅压缩权重而保留 accuracy_level=1 没有速度收益。** INT4 在协调暂停其它 agent 重计算的窗口中，将两句 warm RTF 从 1.59/1.62 降到 0.76/0.77，进程峰值 RSS 从 5.24 GB 降到 2.04 GB。尚无任何候选通过充分的克隆质量与设备验证，未更改出货引擎、模型清单或 verification 状态。

## 固定约束与证据

- Apple M2 Max，32 GiB，12 核；CPUExecutionProvider；**真实 Python ORT 1.24.2**，不是用 1.29 代替兼容性验证。隔离安装于 `~/Library/Caches/Auralis/tts/optimization/ort124`；ONNX 1.20.1、onnx-ir 1.0.0、NumPy 2.5.3。iOS 静态库/Android ABI、设备内存和热稳定仍须另测。
- 原始 bundle：`elbruno/Qwen3-TTS-12Hz-0.6B-Base-ONNX`，revision `6a297d9641354ef0c16e63d329a93a6239bca0a2`。原始文件未改；所有实验独立于安装目录和共享 manifest。
- 正确 group-major vocoder 输入 `[1,16,T]`；直接复用 `tts_runner.generate_codes`，无协议重写。seed=0、top_k=1、temperature=.9、repetition_penalty=1.05、threads=4、max_frames=384。两参考 WAV 的 SHA-256 和原始模型 SHA-256 存于缓存 `provenance.json`。
- 在运行候选前定义 12 句：原报告全部 6 句，加中英数字、否定/上海/北京、含张明/上海虹桥站和 Alice/London Paddington 的长句；中文/英文各 6 句，两个声音、双向跨语言。列表在 `convert/bench_tts_optimization.py::CASES`。不是每语言 ≥10 的晋升集。
- 每个候选完整保留 12 WAV、group0 tokens、参考文本、帧数、时间、RSS、load average、逐角色调用数/耗时。没有丢弃难句、错误、不同终止长度。
- 计时中的 `cold_total_s` **只表示 session 加载加首句推理**，不包括解释器启动、资产初始化、参考声音编码和 provenance 哈希；不能当用户端完整冷启动延迟。RSS 为 macOS `ru_maxrss` 字节值，报告 GB 为十进制。

## 最小实现

仅转换 talker_prefill、talker_decode、code_predictor 的常量右矩阵 MatMul；不动 speaker encoder、vocoder、embedding、tokenizer。使用官方 `DefaultWeightOnlyQuantConfig` / `MatMulNBitsQuantizer`，QOperator、symmetric=True、block_size=32；分别比较 bits=4/8、accuracy_level=1/4。未添加运行时、C API 桥或图外近似。

每个 talker 图转换 197 个节点，CP 转换 35 个节点。单 talker 图加权重约 1.7 GB → INT4 282 MB / INT8 504 MB；CP **原始磁盘图约 440 MB** → 175 MB / 215 MB（此前报告约 708 MB 是其 session 内存增量，不是文件大小）。INT4 的三个图合计 739 MB；INT8 合计 1.22 GB。未量化的全 bundle 其它资产仍需要存储和内存，不能把这两个数字称为完整包大小。

官方依据：

- [量化 API 与精度损失分析](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html)。
- [MatMulNBits 定义](https://github.com/microsoft/onnxruntime/blob/v1.24.2/docs/ContribOperators.md#commicrosoftmatmulnbits)：accuracy_level=4 允许 INT8 activation 计算，不应把它称为严格的 FP32 activation weight-only 路径。
- [线程与 spinning](https://onnxruntime.ai/docs/performance/tune-performance/threading.html)。
- [OrtValue / I/O binding](https://onnxruntime.ai/docs/api/python/api_summary.html)。上述文档先由安装的 Context7 查询，再用已安装 1.24.2 源码签名与真实执行核实。

## 热路径与消融

最初 zh2 的 48 帧执行 720 次 CP，3.62 s，占总计 7.35 s 的 49%；en1 的 CP 占 58%。talker decode 占约 21%，vocoder 占约 20%。不值得先重构占比极低的 Python 数据组织。

下面微基准复用同一份 **FP32 真合成捕获的输入张量**。CP 取第一帧第八次调用；decode 取第一个 token。每方法 warm 后 20 次中位数；每模型独立 session，4 线程。它不代表长 KV 或完整合成，只隔离 kernel 与封送开销。

| 固定输入 | FP32 / NumPy | INT4 a1 / NumPy | INT4 a4 / NumPy | a4 OrtValue | a4 预分配 I/O binding |
|---|---:|---:|---:|---:|---:|
| CP | 4.559 ms | 5.000 ms | **1.561 ms** | 1.702 ms | 1.584 ms |
| talker decode | 24.351 ms | 25.448 ms | **6.967 ms** | 7.060 ms | 6.976 ms |

三个接口的输出在同一模型下逐元素完全相等。**淘汰 a1 速度方案与新增 I/O binding/持久输出抽象**：前者反慢，后者没有测得收益。此结论限 CPU 已捕获形状，不否定长 KV 或设备特定的进一步测试。

关闭 session intra-op spinning 在两句早期筛选中约改善 9–15%，波形完全相同；该比较受缓存/并发影响，未证明独立收益，故未进入出货设置。共享权重不作“硬链接省 RAM”假设；串行释放与共享 initializer 的另一套绑定未加入本次原型。

## 协调窗口的配对端到端复测

root 停止 Swift 编译/模型推理，Android worker 完成 Gradle 并暂缓 inference；ASR/ECAPA 评估已经结束。用户系统后台仍运行，load average 尚有前序任务余量（约 7.5–8.9），所以称为**agent 重计算协调窗口**，不是整机绝对空载。

| 相同文本/参考/参数 | FP32 warm | INT4 a4 warm |
|---|---:|---:|
| zh2 推理时间 / 音频时长 / RTF | 6.109 s / 3.84 s / 1.591 | 3.333 s / 4.40 s / **0.757** |
| en1 推理时间 / 音频时长 / RTF | 5.304 s / 3.28 s / 1.617 | 2.230 s / 2.88 s / **0.774** |
| 本进程最大 RSS（4 次合成） | 5.237 GB | **2.036 GB** |
| session 加载 + 首句推理 | 10.002 s | 5.379 s |

量化导致生成帧数改变，因此同时提供秒数和 RTF；固定张量微基准用于排除仅由说话速度改变造成的假加速。Python 原型缓存两个 talker session；它的 5.24 GB 基线**不同于** iOS 已串行释放的 3.68 GB 基线，不可直接宣称 iOS 内存降低 61%。

## 完整 12 句质量筛选

| 结果 | FP32 | INT4 a4 | INT8 a4 |
|---|---:|---:|---:|
| 全部 finite / 无 clipping / EOS | 12/12 | 12/12 | 12/12 |
| ASR 转写与 FP32 逐句完全相同 | — | 12/12 | 12/12 |
| ECAPA 同参考平均余弦 | .5624 | **.5066** | .5842 |
| ECAPA 聚类到参考 | 11/12 | 12/12 | 11/12 |
| 最低同参考与异参考分数差 | −.0783 | .0356 | −.0394 |
| 全组最大 RSS | 5.192 GB | 2.272 GB | 3.248 GB |
| 全组总耗时 / 总音频时长 | 123.506 / 67.28 s | 58.946 / 71.28 s | 63.779 / 67.76 s |
| 总 RTF（**并发筛选，非正式比较**） | 1.836 | .827 | .941 |

独立 ASR 是已有 sherpa-onnx Qwen3-ASR，512 上下文、2 线程、segmented；原始转写/CER/WER 都保存。相对输入的差异是标点、大小写、连字符等格式，不是候选新增内容错误；评分原值未抹除。`numeric_normalized_*` 仅应用脚本事先写明的数字格式等价映射，不修正识别错误。

独立 speaker verifier 为缓存的 SpeechBrain ECAPA-VoxCeleb，非 bundle speaker encoder；同参考均值 = 对应参考 WAV 加同声音三条独立文本的四个余弦，异参考均值 = 另一声音三条独立文本的均值。全部参考是 macOS say 合成声、只有 n=2，不能代表真实用户。

**不能只选好看的聚类计数或均值。** INT4 cross1 同参考分数 .537 → .344，虽然聚类仍通过，但声音相似度下降显著；整体均值也下降。FP32 的 en_negation 聚类失败（同 .402、异 .480）。INT8 修复该例，却在 zh_negation 新失败（同 .371、异 .411）。这两个失败全部保留。因此 INT4/INT8 都没有获得质量晋升，不应依赖此小集继续调整直到掩盖失败。

全组性能存在明显并发影响：FP32 最后 en_long 开始时 load average 39.4；INT4 期间约 31–39，实测有 Java Gradle、FileProvider、Dropbox 后台负载。INT8 后续 load average 约 7–8。质量结果可配对，整组延迟不能据此排序为严格性能结论。

## 可复现入口与产物

仓库新增三个独立脚本；中央 runner 未改：

- `convert/bench_tts_optimization.py`：固定语料、官方转换、角色计时和合成。
- `convert/eval_tts_optimization.py`：独立 ASR 与 ECAPA。
- `convert/micro_tts_optimization.py`：同输入 NumPy/OrtValue/预分配 binding 消融。

缓存根 `~/Library/Caches/Auralis/tts/optimization/`：

- `scripts/` 冻结四个源码文件（包含复用的原 runner），`provenance.json` 保存源码、原模型、参考声音哈希。
- `int4-b32-a1/`、`int4-b32-a4/`、`int8-b32-a4/`：独立模型与 `conversion.json`（精确参数、ORT/ONNX 版本、新图/权重 SHA-256）。a1 CP 的早期转换原记录另保存在 `quantize-cp.log`。
- `int4-reproduced/`：使用冻结源码重新转换，三个图及 external data 的 **6/6 SHA-256 与被评估的 INT4 a4 完全相同**。
- `baseline-full/`、`all-int4-a4-full/`、`all-int8-a4-full/`：全部 36 WAV 与 `results.json`、`asr.json`、`speaker.json`。
- `exclusive-fp32/`、`exclusive-int4-b32-a4/` 与 `micro-*.json`：协调窗口完整输出和逐次微基准。
- `baseline/`、`baseline-no-spin/`、`cp-int4-screen/`、`all-int4/`、`all-int4-a4-screen/` 保留早期筛选，未用新结果覆盖。

示例（先设置任务专属路径变量，不覆盖系统变量）：

```sh
tts_opt="$HOME/Library/Caches/Auralis/tts/optimization"
tts_py="$HOME/Library/Caches/Auralis/tts/venv/bin/python"
PYTHONPATH="$tts_opt/ort124" "$tts_py" convert/bench_tts_optimization.py quantize \
  --bits 4 --block 32 --accuracy 4 --output "$tts_opt/new-int4"
PYTHONPATH="$tts_opt/ort124" "$tts_py" convert/bench_tts_optimization.py bench \
  --variant "$tts_opt/new-int4" --output "$tts_opt/new-run"
"$HOME/Library/Caches/Auralis/asr/venv/bin/python" convert/eval_tts_optimization.py \
  asr "$tts_opt/new-run/results.json"
"$tts_py" convert/eval_tts_optimization.py speaker "$tts_opt/new-run/results.json"
```

后续最小选择是复用当前 ORT MatMulNBits 路径，将 INT8 a4 作为较保真、INT4 a4 作为较小内存的**待验证候选**；不引入 I/O binding、第二运行时或为两句得分加特殊规则。下一步需要多真实参考声音、每语言 ≥10 的独立文本、盲听/独立 speaker 非劣验证，以及实际 iOS/Android 内存/实时性/热稳定测试。现有证据只支持“找到真实瓶颈并有可复现收益”，不支持已达到生产级领先质量。
