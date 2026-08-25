# Auralis → Viaim 功能平齐重构方案

- 日期：2026-08-25（**v3.4 · MT 一期 2bit+GPU**）
- 目标分支代码：`baseline/auralis-android-overhaul`
- 对标：Viaim / iFLYBUDS Pro 3（A09-1）+ Timekettle/讯飞翻译耳机「面对面双向」
- 本文焦点：产品平齐 + **当代端侧模型栈**

---

## 0. 一句话结论

Auralis 是 **手机端侧离线方言双向同声传译**（+ 声音克隆），不是 Viaim 纪要耳机的软件复刻。  
Viaim 明确不做面对面双向语音互译；那是 Auralis 的主场。

---

## 1. 用户已拍板的硬约束

| 项 | 决策 |
|---|---|
| 转写 ASR 族 | **Qwen3-ASR 或 Fun-ASR-Nano 0.8B 族**；越小越好 |
| FunASR 型号 | **Fun-ASR-Nano-2512（~0.8B）**；不是 SenseVoice/Paraformer-small |
| Qwen 对照 | **Qwen3-ASR-0.6B**；量化不绑死 |
| **MT** | **Hy-MT2-1.8B**。**一期发版：2bit + GPU**。**目标档：1.25bit（~440MB），等 STQ-GPU 可用再切** |
| 端侧加速 | **发版 GPU；NPU 二期**；CPU-only = degraded |
| 禁止当主推 | Zipformer / Piper / Opus-MT / SenseVoice 冒充 Fun-ASR 0.8B |
| 代码 | 暂可不动；先把方案写对 |
| 平齐口径 | Must = 引擎可听 + **face_to_face 最高** + 听译 + 全离线 + 克隆；纪要后置 |

---

## 2. 技术主路径

### 2.1 D1 — ASR

候选对打：**Fun-ASR-Nano-2512** ↔ **Qwen3-ASR-0.6B**（均 ~0.9–1GB 极致包量级）。发版跑 **手机 GPU**；NPU 二期。

### 2.2 D2 — MT（已锁分期）

**一期发版：`Hy-MT2-1.8B` 官方 2bit GGUF + 手机 GPU**  
**目标档：`Hy-MT2-1.8B` 1.25bit GGUF（~440MB），等 STQ 内核能在 GPU 后端跑再切**

| 项 | 内容 |
|---|---|
| 家族 | Hy-MT2：1.8B / 7B / 30B-A3B（MoE）；33 语 |
| 最小 | **1.8B**（不上 7B/30B 作默认） |
| 一期 | **2bit GGUF**（`tencent/Hy-MT2-1.8B-2bit-GGUF`，约 **573 MiB**）+ **GPU**（llama.cpp Vulkan/等价） |
| 目标 | **1.25bit**（`tencent/Hy-MT2-1.8B-1.25Bit-GGUF`，约 **440 MiB**）；依赖 STQ（[PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836)，截至 2026-08 **未合入主干**；官方示例 `-ngl 0` = CPU） |
| 质量 | 论文 Table 5：**无 1.25bit 分数**；2bit 相对 BF16 已掉（IFMTBench **69.36→58.99**，FLORES XX↔XX **79.21→76.31**）。一期接受 2bit 掉点；1.25bit 上线前必须自测 |
| 禁止 | Opus-MT；用 Q4_K_M（~1.08GB）当 headline（可作 GPU 对照，不当发版主推）；1.25bit 在 CPU 上冒充一期达标 |
| 许可证 | **Apache-2.0**（Hy-MT2；旧 HY 社区地域条款不套过来） |
| 二期加速 | NPU（GenieX/QNN 等） |

### 2.3 D3 — TTS / 克隆

**Qwen3-TTS** 主路径；一期 GPU；NPU 二期；禁 Piper 当推荐。

### 2.4 运行时

- 一期：手机 **GPU**（ORT GPU / Vulkan / OpenCL 等）
- MT 一期：llama.cpp **GPU** 跑 **2bit**；并行跟 STQ-GPU，通了再切 1.25bit
- 二期：QNN / 厂商 NPU
- CPU-only = degraded

---

## 3. Must / Should / Could / Won't

| 优先级 | 内容 |
|---|---|
| **Must** | GPU 可听；`face_to_face`；听译；全离线 ASR→**Hy-MT2-1.8B 2bit（GPU）**→TTS；克隆路径 |
| **Should** | 切到 **1.25bit+STQ-GPU**；NPU 二期；Records；热词；短摘要 |
| **Could** | 通话尽力；字幕；diarization |
| **Won't** | 纪要全家桶平齐；CPU-only/Opus-MT 当卖点 |

---

## 4. 阶段顺序

0. PoC：ASR 双候选 GPU；**Hy-MT2-1.8B 2bit GPU** 短句译质/RTF；同步探 STQ-GPU；Qwen3-TTS GPU  
1. 引擎诚实可听  
2. `face_to_face` MVP  
3. Records  
4. 模式/热词  
5. **MT 切 1.25bit（STQ-GPU 通了才切）**  
6. NPU 二期  
7. 纪要后置  

---

## 5. Android 诚实边界

听译绿 · PlaybackCapture 黄 · 真通话双轨 **红**。

---

## 6. 成功定义

> 无网、两人各说方言：GPU 上 ASR 听懂 → **Hy-MT2-1.8B 2bit（GPU）** 译出 → Qwen3-TTS 出声；面对面双向。**1.25bit 不挡一期发版。** NPU 不挡一期。不把过时栈写成推荐。

---

## 7. 修订记录

- v3.3：MT 锁 Hy-MT2-1.8B + 1.25bit  
- **v3.4：** 用户确认 **一期 2bit+GPU**；**1.25bit 等 STQ-GPU**；许可证改为 Apache-2.0
