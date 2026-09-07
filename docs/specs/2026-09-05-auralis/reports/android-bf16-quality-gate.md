# Android CP-only BF16 六人配对质量门（2026-09-07）

执行：Android lane worker。接续 `cache/android-bf16-probe/` 与既有 BF16 探针（Bf16MatMulProbeTest / Bf16CodePredictorProbeTest / Bf16TtsProbeTest），不重写。结论先行：**仅 CP 启用 BF16（mlas.enable_gemm_fastmath_arm64_bfloat16=1）未通过事前固定的六人身份门，不予晋升，默认保持关闭**。全部原始证据保留，含失败例。

## 事前固定协议

- 数据：`cache/tts/upstream-fidelity/real-pool/manifest.json`（LibriSpeech test-clean，官方 FLAC 全部 sha256 对 manifest 核验通过）+ `predeclared-criteria.json`（criteria_sha256 `99efde94…`，与既有 screening 一致：median ≥ −0.03、worst ≥ −0.10、新增身份错误 0）。
- 说话人：121/237/1221（女）、61/260/672（男），每人官方参考录音（soxr_hq 16k→24k float32，与既有 host screening 用的 `*-reference24.wav` 同文件），原始字节 .f32 直推设备（无 WAV 编解码二次量化）。
- 文本：criteria 预声明 zh「请不要取消明天去上海的火车票。」/ en "Please do not cancel the train to London tomorrow."。
- 引擎：生产 FP32 图不动（CP sha `44c528d0…` 设备=host 原件）；ORT 1.24.2；seed 20260906（TtsEngine 每次 synthesize 内重建 RNG，配对逐 case 同种子）；temperature 0.9；topK 50；repetition penalty 1.05；maxFrames 384（`TtsEngine(manager, maxFrames = 384)` 构造参数，不改默认 2048）。BF16 仅通过独立创建的 `tts/code_predictor.onnx` session option 注入（沿用 Bf16TtsProbeTest 的 sessions 注入方式），ModelManager 默认/manifest/共享代码零改动。
- 24 条 = 6 人 × {zh,en} × {nativeFP32, CP-BF16}，两种模式各一次独立 `am instrument` 进程（fresh process per precision，同既有成对实验惯例）。测试：`androidTest/.../Bf16TtsQualityGateTest.kt`（arg 门控 `bf16Quality=true`+`bf16Mode`），两模式均 `OK (1 test)`，无跳过。

## 结果

### ASR 回转（设备端 resident sherpa Qwen3-ASR，独立于采样）

24/24 条 CER 0（whitespace-stripped，两模式、两语言全部与预声明文本一致）。逐对 ASR 不劣于 baseline：通过（全部相等）。

### ECAPA held-out 身份（host 复用既有评分工具）

复用 `protocol-fixed-sampled/bench_tts_real_pool.py` 的 evaluate 数学（同一 speechbrain EncoderClassifier、同一 held-out gallery、同一 cosine/own/margin/新增身份失败定义），实验目录 `real-pool/android-native-bf16cp/`（fp32/、bf16cp/ 各 12 WAV + results.json，score_identity.py、identity.json 在内）。

| case | FP32 own | BF16 own | Δ | FP32 top1 正确 | BF16 top1 正确 |
| --- | ---: | ---: | ---: | --- | --- |
| 121-chinese | 0.3263 | 0.2107 | −0.1156 | ✓ | ✓ |
| 121-english | 0.5552 | 0.4094 | **−0.1458** | ✓ | ✓ |
| 1221-chinese | 0.4124 | 0.4283 | +0.0160 | ✓ | ✓ |
| 1221-english | 0.7066 | 0.6860 | −0.0205 | ✓ | ✓ |
| 237-chinese | 0.4215 | 0.4159 | −0.0056 | ✓ | ✓ |
| 237-english | 0.5570 | 0.5627 | +0.0057 | ✓ | ✓ |
| 260-chinese | 0.2574 | 0.1691 | −0.0883 | ✓ | **✗（top1=672）** |
| 260-english | 0.1923 | 0.2466 | +0.0543 | **✗（top1=61）** | ✓ |
| 61-chinese | 0.4170 | 0.2903 | −0.1267 | ✓ | ✓ |
| 61-english | 0.5904 | 0.6086 | +0.0182 | ✓ | ✓ |
| 672-chinese | 0.3045 | 0.3109 | +0.0064 | ✓ | ✓ |
| 672-english | 0.5452 | 0.4497 | −0.0956 | ✓ | ✓ |

- median Δ = **−0.0131**（门 −0.03：过）
- worst Δ = **−0.1458**（121-english；门 −0.10：**不过**）
- 新增身份错误 = **1**（260-chinese；门 0：**不过**）
- 综合：**FAIL**。

基线备注：本次 native FP32 设备基线自身 260-english top1 错（own 仅 0.192）。260 号参考仅 7.04s，是全池最弱身份（两侧 own 都在 0.17–0.26 徘徊），FP32/BF16 的错误分别在 260-english/260-chinese 之间翻转。这不改变门判定（门按配对 Δ 与新增错误计），但说明该门槛附近的两例不应被单独解读为 BF16 专属退化；真正的系统性信号是 121（−0.116/−0.146）与 61-chinese（−0.127）三个明确负向 case。

### 速度 / 内存（同一实验内，仅诊断，非实时结论）

- 全合成耗时（不含 CP 预载）：FP32 median 9,625ms vs BF16 8,642ms（−10.2%）。注意逐 case 帧数不完全相同（logits 变化导致不同采样轨迹，12 例中 6 例帧差 ≥2），该百分比未按帧归一。
- 进程 VmHWM：FP32 4,233,976 KiB vs BF16 4,100,348 KiB，**−133,628 KiB ≈ −130MiB**，与既有 host 级 ~150MiB 结论同向。
- 既有隔离探针（固定真实输入、CP 单 session median 5.002→3.864ms）仍有效；本实验确认该 kernel 级优势在真实链路里确实存在，但换不来身份门通过。

## 结论与边界

1. **CP-only BF16 质量门失败，默认关闭，不晋升**，生产 ModelManager/manifest 未改。三个既有量化候选（INT8/INT4/分阶段）与本次 BF16 在同一事前门下全部失败，门值不被放宽。
2. 证据全部保留：设备原始 WAV/JSON → `cache/android-bf16-probe/quality-mode{0,1}/`；host 评分与配对 Δ → `cache/tts/upstream-fidelity/real-pool/android-native-bf16cp/`；运行日志 `quality-mode{0,1}-run.log`。
3. 模拟器（API35 arm64, 8GB）证据，非实体手机；不冒充物理极限。ASR 为设备端真实模型链路。
4. 若未来重开此实验：门与协议已预声明，可直接复用本测试与 `score_identity.py`，无需新代码；但按 08-tts-next-package.md，量化重开必须相对新的正确 FP32 基线重新过门。

## root 交付项确认

- **新 MT so（`8bb8d117…`，8063800B）最小 smoke/cancel/reload：已完成**。jniLibs 更新（15:02）之后安装的 APK 内嵌 lib 已在设备上抽出核 sha = `8bb8d117…`，`MtJniSmokeTest.nativeMt_load_translate_cancel_reload` 于 15:12 实跑 `OK (1 test)`：真实翻译（"Today, the weather is great. Let's go for a walk in the park together."，1310ms）、长输入取消路径、释放幂等、reload 再译全部通过。证据 `cache/android-bf16-probe/ci-mt-smoke.{json,log}`。旧 final-apks 中 MT `70d04…` 报告原样保留，本次未覆盖。
- **文件恢复**：实验期间唯一临时移除的是设备端 MT gguf（/data 满 99% 导致 androidTest APK 无法安装；事务记录 `quality-mt-removal-transaction.json`）。已 stdin 流式还原并核 sha `e42935e2…` = host 两份原件；CP/talker×2/vocoder/speaker_encoder 五个大文件设备=host 全对；ASR bundle 全程未动。
- **收尾**：设备侧我方临时目录（bf16_quality refs、/data/local/tmp）已清；adbd 已回 non-root（uid=2000 shell）。git 索引未做任何 add/commit/reset/stash/push，新增文件仅测试与报告。

## Occam 消融记录（本次实际取舍）

- 拒绝：设备端内嵌 ECAPA/门判定 → 复用既有 host 评分工具（同一数学，可对拍旧 screening），设备只出数据。
- 拒绝：给 WavCodec 增加 float32 WAV 写路径 → 参考用原始 .f32 直推，输出沿用既有 PCM16 证据格式（与旧 host screening 的 sf.write 默认 subtype 一致）。
- 拒绝：新建 BF16 图/改 manifest/加运行时开关 → 单 session option 注入即可覆盖问题（隔离性由既有探针证明过）。
- 拒绝：逐说话人分进程跑（省内存）→ 单进程 12 例实测无 OOM，VmHWM 记录完整，减少进程数差异变量。
- 未做而明确标注的：合成耗时未按帧归一；260 号弱身份现象只记录不解读为结论。
