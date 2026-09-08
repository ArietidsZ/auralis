# Android TTS 推理质量/效率报告（2026-09-08）

Lane：Android 推理三文件（`TtsEngine.kt` / `TtsApi2Runtime.kt` / `SpeakerEmbeddingExtractor.kt`）+ 对应测试。
基线：`v0.1.0-preview.1` / `7e2cb57927d6290c089dce1c33a72bbc9902b6cc`。
执行环境：本 quality worktree，macOS aarch64 主机，JDK 23，Gradle wrapper（Groovy 3.0.22）。所有微基准为 **JVM（JIT C2）主机数字**，只刻画算法级工作/分配量变化，**不冒充手机或端到端合成提速**（见 README 验收门：主机结果不满足实体设备资格）。

## 候选与基线（开工时清单）

| # | 候选 | 证据来源 | 判定 |
|---|---|---|---|
| 1 | `Qwen3TtsProtocol.sampleFromLogits` 每次 `probs.copyOf().sortedDescending()`（整排序+拷贝）→ 精确 K-th largest 选择 | 代码审查 + 微基准 | **实施** |
| 2 | `sampleGroup0` 每步 `generated.toSet()`（HashSet+装箱）→ seen-mask | 代码审查 + 微基准 | **实施**（随 #1 同函数） |
| 3 | `logMelSpectrogram` 每 mel 带重复计算 `sqrt(re²+im²+1e-9)`（128×513/帧）→ 每 bin 一次；FFT twiddle 每蝶形重算 sin/cos → 同表达式缓存 | 代码审查（root 提示）+ 微基准 | **实施** |
| 4 | `prepareReference` 的 `.also` 仅成功路径释放 reference_encoder（ICL 失败/取消路径滞留 ~190 MB 会话） | 代码审查 | **实施**（TtsApi2Runtime.kt） |
| 5 | `PreparedReference` 声称不可变但公开 FloatArray 字段可外部修改 | 代码审查 | **实施**（TtsApi2Runtime.kt） |
| 6 | `sampleCodePredictor` 调用点 `cpLogits.copyOfRange(...)` 与函数内尾部 arraycopy 重复拷贝 | 代码审查 | **实施**（两处调用点） |
| 7 | `code flattening is group-major for the vocoder` 测试缺 `@Test`，从未运行 | 测试审查 | **实施**（补注解） |
| 8 | `logMelSpectrogram` 短音频（<256 样本）AIOOBE 崩溃（守卫边界错误） | 新增等价测试暴露 | **实施**（见"意外发现"） |
| 9 | BPE 重复工作（encode/bpe 每合成一次、按文本长度） | 审查 | **不做**：每合成仅 1 次、量级随文本长度，无基准证据表明是热点 |
| 10 | 其它 DSP 重复（window/filterbank 每次 logMel 调用重建） | 审查 | **不做**：每次调用仅 1 次，微基准中不构成可见份额 |
| 11 | 采样 weights DoubleArray 跨步复用（scratch 缓冲） | 构思 | **不做**：需跨两个引擎传 scratch、引入有状态接口；exp() 主导的每次调用成本中分配占比未证明值得（见消融） |
| 12 | API1 `generateFrames` 后不释放 code_predictor 会话（与 decode/vocoder 不对称） | 代码审查 | **不改**：cp 会话小、保留可加速重复合成；无内存受害证据，API2 亦全缓存。记录为观察 |

基线 JVM 单测：`AURALIS_TTS_MODEL_DIR=$HOME/Library/Caches/Auralis/tts/api2-release ./gradlew :app:testDebugUnitTest --offline --init-script /private/tmp/auralis-existing-cache.init.gradle` → **146 tests 全绿、0 跳过**（含真实 tokenizer 用例，Qwen3TtsProtocolTest 20/20）。

## 1. 采样阈值选择（TtsEngine.kt — Qwen3TtsProtocol）

### 缺陷
`sampleFromLogits` 在每个采样步执行 `probs.copyOf().sortedDescending()[topK - 1]`：整词表拷贝 + 完整排序 + Kotlin `List<Float>` 装箱视图，只为取第 K 大的值。每合成帧调用 16 次（1× group0 vocab 3072 + 15× code predictor vocab 2048）。

### 修复
新增纯数值单元 `Qwen3TtsProtocol.kthLargestFloat(values, k)`（`TtsEngine.kt:475`）：有界堆选择。第 K 大 = 第 (n−K+1) 小，堆取较小一侧（容量 ≤ (n+1)/2），O(n log min(K, n−K+1))；对值只比较、不运算，与排序参照**位等价**。掩码逻辑（`probs[i] < threshold → -inf`，保留并列）、CDF 原顺序扫描、`random.nextDouble()` 恰好一次的消耗、temperature=0/topK=1 短路全部未动。

同函数内 `generated.toSet()` → `BooleanArray` seen-mask：逐 index 独立施加惩罚，与 HashSet 迭代顺序无关，逐值等价；require 校验消息不变。

### 等价证据（新增测试，Qwen3TtsProtocolTest.kt）
- 冻结基线 oracle：测试内完整复制基线 `sampleGroup0`/`sampleCodePredictor`/`sampleFromLogits`（含 `toSet` 与 `sortedDescending`），标注"故意重复以防两侧同时被改"。
- `group0 sampler is value-identical to frozen baseline across topK and ties`：4 种分布（uniform / 量化重复 / 稀疏有限值+−inf / 全等）× topK {0,1,2,50,51,vocab−1,vocab} × 150 步，两侧共享同 seed RNG 流——任何消耗次数/顺序偏差都会立即失步。逐值相等。
- `code predictor sampler ...`：同矩阵 × 200 步，逐值相等。
- `kth largest matches the sorted multiset reference exactly`：size {1..257 全 K 扫描 + 1024/3072 采样 K 扫描} × 6 类对抗数组（随机、两值、全等、单峰、−inf 混合、±0.0 混合），断言 `kthLargestFloat(a,k)` 与 `a.copyOf().sortedDescending()[k-1]` 按 `floatToRawIntBits` 位精确（混合 ±0.0 时零符号豁免；掩码语义已证等价：`x < -0.0` 与 `x < 0.0` 对所有 x 同值）。
- `kth largest rejects empty arrays and out-of-range orders`。

### 微基准（门控 `AURALIS_PERF_BENCH=1`，`benchmark sampling and mel rework`）
JVM 中位数，warmup=repeats/4+10，400 次（mel 10 次）；两次独立运行（`--rerun`）新实现侧稳定，legacy 侧受 GC/堆状态波动：

| 项 | legacy | new | 比 |
|---|---|---|---|
| sampleCodePredictor [2048, uniform] | 57.5–62.8 µs | 12.8–13.0 µs | 4.4–4.9× |
| sampleCodePredictor [2048, ties] | 20.3–20.5 µs | 11.4–12.2 µs | 1.7–1.8× |
| sampleGroup0 [3072, gen=512] | 78.6–87.9 µs | 62.7–63.8 µs | 1.25–1.38× |
| 仅阈值 [2048, k=50]：copyOf+sort vs select | 40.3–42.8 µs | 3.3–3.4 µs | ~12× |
| logMelSpectrogram [2s 音频, ~189 帧] | 19.8–30.7 ms | 13.25–13.28 ms | 1.5–2.3× |

读法（诚实口径）：
- 采样路径剩余成本由 exp() softmax 主导（select 3.4 µs vs 全程 13 µs）；阈值选择本身 ~12× 但只占全路径一部分。
- 按帧外推：16 次采样/帧，legacy ≈ 0.9–1.0 ms/帧 → new ≈ 0.26–0.28 ms/帧；对每帧 ~100–300 ms 量级的 talker decode + code predictor ONNX 计算是 **<1% 的墙钟改善**——CPU 收益不是本轮主要价值。
- 主要价值是分配消除：每步不再有 copyOf（8–12 KB）+ sortedDescending 装箱 + HashSet。按 2048 帧预算上限的算术估算：16 次采样/帧 × 2048 帧 ≈ 32k 次调用 × ~11 KB/次 ≈ **350–400 MB 堆垃圾/满预算合成**（比例随实际帧数线性下降；普通短语远低于上限）。这是纯算术推算，**非 ART/设备实测**。
- legacy 侧 run-to-run 波动（mel 19.8 vs 30.7 ms）表明单次 JVM 微基准对分配重的代码噪声显著；比较以新实现侧稳定性与隔离腿（threshold-only）为准。

## 2. logMelSpectrogram 前端（TtsEngine.kt — Qwen3TtsProtocol）

### 缺陷
- 幅度：`sqrt(re[k]²+im[k]²+1e-9)` 在 m(128)×k(513) 内循环逐带重算，同一频点每帧重复 128 次。
- FFT：每帧每蝶形块重算 `cos(angle·k)/sin(angle·k)`——n=1024 时每帧求值 **5120 对**（10 级 × 每级 n/size 个块 × 每块 half 个重复计算），其中独特值仅 **1023 个**（每级 half 个，Σhalf=1023）。

### 修复（均位等价）
- 每 bin 每帧算一次 `mag[k]`（**同一表达式**），mel 带循环按原 k 顺序累加 `basis[m·513+k] * mag[k]`——累加顺序与 Float/Double 转换不变，输出位等价。
- `TwiddleTable`（单一 `@Volatile` 不可变 holder + 双检锁）：每 FFT 尺寸一次构建，值用**与内联完全相同的表达式** `cos(-2.0*PI/size * k)` 计算，位等价；并发读一致。

### 位等价证据（新增测试）
- `fft is bit-identical to frozen baseline across sizes`：n ∈ {2,4,8,64,256,1024,4096} 随机复数输入，逐点 delta=0。
- `log mel is bit-identical to frozen baseline frontend`：全零 / DC / 正弦 / 冲激 / 2s 噪声 / 256 样本（恰好一 hop）/ 1023 样本 / 既有 8192 验证信号，`legacy.data.contentEquals(current.data)` 逐位相等。
- 微基准见上表（mel 路径合并实测 1.5–2.3×；幅度提升与 twiddle 缓存无独立消融，不做单项墙钟归因）。

## 3. 意外发现：短音频 AIOOBE（已修，行为变化=崩溃→干净错误）

新增等价测试暴露：`logMelSpectrogram` 原守卫 `audio.size < 2` 边界错误。`pad=384`，`padded.size = audio.size+768`；当 `audio.size < 256`（24 kHz 下 ~10.7 ms）时 `padded.size < nFft`，`frames = 1 + (padded.size-nFft)/hop` 整除截断为 1，帧循环读越界 → `ArrayIndexOutOfBoundsException`。基线对 <10.7 ms 的非静音参考音频是**崩溃**，不是设计错误码。

修复：守卫改为 `audio.size < hop`（数学下界 `nFft - 2*pad = hop`），异常类型与消息不变（`IllegalArgumentException("reference audio too short for mel frontend")`），`log mel rejects audio shorter than one hop cleanly` 锁定 {2,100,255} 拒绝 + 256 边界通过。iOS 同源实现的对齐项见 §7.3。

## 4. TtsApi2Runtime.kt（prepareReference 所有权 / PreparedReference 不可变 / 冗余拷贝）

### 4.1 prepareReference 失败/取消路径释放（`.also` → try/finally）
缺陷：`withContext(Dispatchers.Default) { ... }.also { releaseRole("reference_encoder") }` 只在 withContext 正常返回时释放。ICL 路径（有 referenceText）中任一失败——tokenizer 缺失、`refTokenIds.size < 6`、runReferenceEncoder 图 I/O 与 codes 校验、`computeVocoderWarmState` 帧数/状态校验——或块完成后的取消（withContext 退出时重抛 CancellationException），都会把 ~190 MB 的 reference_encoder 会话滞留到整个引擎 `release()`。
修复（TtsApi2Runtime.kt:213-267）：`try { withContext(...) { ... } } finally { modelManager.releaseRole(SUB_DIR, roles, "reference_encoder") }`。块体逐行未改。
语义核对：
- 成功路径释放的时机/参数/次数与原 `.also` 完全一致（仍在 `mutex.withLock` 内、caller 线程）。
- xvector-only 路径会话从未加载：`OnnxModelManager.releaseRole`（OnnxModelManager.kt:118-124）→ `release`（:140-142）= `sessions.remove(key)?.close()`，未加载时纯 no-op，无引用计数/重复 close 风险（独立验证与实施代理结论一致）。
- `releaseRole` 非挂起函数，取消态协程中也能执行。
- 同文件其余资源路径（input tensor finally close、`Result.use`、`readTalkerState` 校验失败 catch→close→rethrow、`state.close()` 外层 finally）经逐函数核对均完备；全文件 `.also {` 释放模式现为 0 处。

### 4.2 PreparedReference 真不可变
缺陷：KDoc 声称 immutable conditioning package，但 `embedding`/`referenceTokenIds`/`referenceCodes` 是可变数组直通引用；`referenceCodes` 完全无下游校验。
修复（TtsApi2Runtime.kt:140-177）：构造参数收私有快照（构造期 `copyOf()`），公开属性每次读取 `field?.copyOf()`；`referenceText`（String 不可变）直存；`isIcl` 改为构造期求值的存储属性，语义不变；internal constructor 参数名/顺序/类型逐项保持（既有调用面与 androidTest 零改动）。
- 有意偏差：实施提示曾建议"构造时不再额外拷贝"，但与"构造入参后续修改不影响实例"的行为规格互斥；按行为规格采用构造期+读取期双重拷贝，代价为每次 prepareReference（秒级 ONNX 推理）多 ≤24 KB memcpy。
- internal `vocoderWarmState` 别名分析：生产端 `readVocoderState` 从 ORT buffer 拷入新 JVM 数组，快照从不别名 ORT 内存；唯一消费点 `VocoderTurnState.fromSnapshot`（逐数组 copyOf）与 `VocoderWarmState.copy()`；全仓 grep 无其它读取点、无写入点。
- androidTest（TtsApi2RuntimeDeviceTest.kt）核对：全部用例只读 `embedding.size`/`referenceCodes == null`/`isIcl`/`referenceFrames`/`identity`，无直接构造、无对返回数组的写入依赖——防御性拷贝对其严格增强，零改动。

### 4.3 冗余尾部拷贝移除
`sampleCodePredictor(cpLogits.copyOfRange(cpLogits.size - cfg.cpVocab, cpLogits.size), ...)` → 直接传 `cpLogits`（TtsApi2Runtime.kt:583-589）。等价性：函数内部 `System.arraycopy(logitsLast, logitsLast.size - vocab, probs, 0, vocab)` 自取末尾 vocab 个元素，与预切片逐元素相同；采样序列与 RNG 消耗不变。省去每 codebook 步 2048 float 分配（~30k 次/合成）。TtsEngine.kt 的同型调用点由本 lane 同步修改（同一所有者，改动等价）。

## 5. 测试与验证

### 命令（本 worktree，`android/` 目录执行）
```
AURALIS_TTS_MODEL_DIR=$HOME/Library/Caches/Auralis/tts/api2-release \
  ./gradlew :app:testDebugUnitTest --offline \
  --init-script /private/tmp/auralis-existing-cache.init.gradle

# 微基准（门控）：
AURALIS_PERF_BENCH=1 AURALIS_TTS_MODEL_DIR=$HOME/Library/Caches/Auralis/tts/api2-release \
  ./gradlew :app:testDebugUnitTest --rerun \
  --tests "com.dialect.interpreter.inference.Qwen3TtsProtocolTest" \
  --offline --init-script /private/tmp/auralis-existing-cache.init.gradle
```
注意：`/private/tmp/auralis-existing-cache.init.gradle` 仅本地离线缓存访问（强制 offline + 镜像 repo 替换），未提交到仓库。

### 数字
| 时点 | tests | skipped | failures | 备注 |
|---|---|---|---|---|
| 基线（未改源） | 146 | 0 | 0 | 含真实 tokenizer 用例 |
| TtsEngine 改动后 | 155 | 1 | 0 | +8 新测试 +1 修复 @Test；skip = 门控基准 |
| + TtsApi2Runtime 改动后 | 162 | 1 | 0 | +7 PreparedReference JVM 契约测试 |
| + 审查修复（最终） | **163** | 1 | 0 | +1 尾部切片钉测试；±0.0 断言精确化；assertSame(warmState) |

新增测试清单：
- Qwen3TtsProtocolTest：group0 逐值等价（4 分布×7 topK×150 步）、code predictor 逐值等价（同矩阵×200 步）、kthLargestFloat 对排序参照精确匹配（1..257 全 K + 1024/3072 采样 K，6 类对抗数组；位精确、零符号豁免）、padded 尾部切片钉（oracle 对拍 + prefix 不可见性，group0/cp × 2 分布 × 50 步）、kthLargest 拒绝空/越界、FFT 位等价（7 尺寸）、mel 位等价（8 信号）、短音频干净拒绝（{2,100,255} 拒绝 + 256 边界）、门控微基准。
- TtsApi2PreparedReferenceTest（新文件，JVM）：构造入参改写不渗入 / 取出副本改写不影响实例 / 每次读取独立副本（×3 数组字段）、标量与 engineToken/generation 绑定原样（assertSame 钉住过期绑定语义）、isIcl 不可经副本翻转、xvector-only 形态、VocoderWarmState.copy() 独立性。

覆盖边界（如实）：reference_encoder 失败/取消释放没有自动化测试——`OnnxModelManager.sessions` 为 private、无公开可观察面；JVM 侧 OnnxModelManager 需要 Android Context 不可实例化（不可改它），androidTest 侧真实 bundle 全部满足图校验、帧数校验经 JNI 30s 上限不可达（auralis_resample_jni.cpp:39,76），注入故障在真机上不可观测。该路径以 try/finally 重构 + `releaseRole` no-op 语义的源码证据覆盖；不以装饰性用例冒充验证。取消路径的既有覆盖（每帧/每 codebook ensureActive、TtsCancellationTest）未动。

androidTest / 真机（TtsApi2RuntimeDeviceTest、TtsCancellationTest、TtsFrameBudgetTest 等）本轮未运行（README 验收门：主机结果不满足实体设备资格）。采样序列逐位不变 + chunking 一致性测试存在，预期设备行为不变。

### 对抗性审查轮（3 视角独立审查 + 逐项对抗验证，7 代理）

4 个原始发现，3 个经对抗验证成立并已修复：

| 发现 | 严重度 | 处置 |
|---|---|---|
| `kthLargestFloat` 与装箱排序在**混合 ±0.0 输入**下返回的零符号可能不同（堆为 IEEE 比较；文档"bit-identical"措辞过强；测试 delta=0 看不见零符号）。行为零影响：掩码 `x < ±0.0` 对所有 x 同值、exp(±0.0) 相等 | minor | KDoc 改为"逐值相等，唯混合 ±0.0 时零符号可能不同（不改行为）"；oracle 测试改为 `floatToRawIntBits` 位精确断言 + 零符号豁免（`assertSortedThreshold`） |
| **尾部切片语义零覆盖**：生产 CP 图在 g=1 prefill 输出 [1,2,2048]→展平 4096 floats，`sampleCodePredictor` 取尾部 vocab 个；新旧全部测试只传 size==vocab 数组，"头部拷贝"突变可让全套件保持绿色而设备行为改变 | major | 新增 `samplers read the trailing vocab slice of padded logits`：37 元素垃圾前缀 + 尾部分布，oracle 对拍 + padded-vs-tail-only 同 RNG 同 token 双重钉（group0 与 cp 双路径 × 2 分布 × 50 步） |
| `TtsApi2PreparedReferenceTest` 漏掉 `vocoderWarmState` 的直传断言（丢失即每个 ICL turn 硬失败的字段） | major | `scalar and binding fields pass through unchanged` 补 `assertSame(warm, p.vocoderWarmState)` |

审查中被对抗验证否决的原始发现：1 项（equivalence 视角，与 ±0.0 项重复）。验证阶段有 2 个子代理在安全分类器限流下运行，其结论经主 worker 独立复核（±0.0 场景手工推演 [1f,-0f,0f],k=2：堆 sift 后 [−0.0,1.0]，0f>−0.0 为假不替换，返回 −0.0；掩码语义等价成立）。

## 6. 消融与未采纳设计

| 设计 | 处置 | 依据 |
|---|---|---|
| 整排序 → 有界堆选择 | **保留** | 隔离腿 ~12×；等价性经排序参照逐值验证 |
| `generated.toSet()` → BooleanArray seen-mask | **保留** | 与阈值选择同函数、合并实测 1.25–1.38×；逐值等价（无独立消融，不归因单项贡献） |
| mel 幅度提升 | **保留** | 每帧冗余 sqrt 65,664→513 次（128 带 × 513 bin 重复），结构计数；合并实测 1.5–2.3× |
| FFT twiddle 缓存 | **保留** | 每帧 5120 对内联求值→0（1023 个独特值缓存）；同表达式缓存保证位等价；与幅度提升合并实测 |
| 采样 weights DoubleArray 跨步 scratch 复用 | **不做** | 需要跨 API1/API2 两个调用方传入 scratch、引入有状态签名；exp() 主导的剩余成本中其占比未证明；收益上限 ~10 µs/帧（<0.1%） |
| BPE 重复工作整理 | **不做** | 每合成 1 次、随文本长度线性；无基准证据为热点 |
| window/filterbank 每次 logMel 调用重建缓存化 | **不做** | 每调用 1 次，微基准不构成可见份额 |
| API1 code_predictor 会话合成后释放 | **不改** | 与 decode/vocoder 不对称但会话小、保留利于重复合成；无内存受害证据（记录为观察，见候选 #12） |
| 失败实验记录 | 短音频守卫初版测试用例（2 样本）暴露的是基线 AIOOBE 而非新代码问题；首个微基准"复跑"因 Gradle up-to-date 未实际执行，经 `--rerun` 修正（教训：up-to-date 跳过的"复现"数字无效） | — |

## 7. 仍开放的门 / 移交

1. **真机/模拟器验证未做**：设备测试套件（含 api2Case 门：main/chunking/lifecycle/stream/binding）与端到端回转写未运行——需 root 排期与设备。预期采样序列逐位不变。
2. **reference_encoder 释放的设备级观测**：如需自动化，需要 OnnxModelManager 增加测试可观测面（不属于本 lane，需 root 决定）或真机故障注入。
3. **iOS lane 对齐项**：iOS 若有同源 mel 前端实现，其短音频守卫可能存在同型 AIOOBE（见 §3）；属 iOS lane 文件，未改动。
4. **微基准口径**：全部为主机 JVM 数字；ART/设备上的收益（尤其按 2048 帧预算上限估算的 ~350–400 MB/满预算合成分配消除，比例随实际帧数线性下降）是算术推算，未实测。
5. 短音频行为变化（崩溃→干净 IAE，§3）请 root 确认接受；同一输入旧代码崩溃、新代码按既有错误风格拒绝。
6. 语义保留声明：错误码/异常类型与消息、就绪门（manifest apiContractVersion、角色校验）、取消语义（每帧/每 codebook ensureActive、sink 失败即中止）、API1/API2 行为、采样协议（temperature/topK/penalty/seed）全部未动；模型精度、manifest/schema、品牌与 Apache-2.0 许可未触碰。
7. **native talker KV 直接复用未回退**：API2 `TalkerState` 仍持有 `OnnxTensor`（past_keys/past_values 留在 ORT 内存，`getFloatBuffer()` 堆拷贝路径未引入）；本轮只把调用点冗余的 logits 预切片去掉，未触碰 KV 传递结构。
