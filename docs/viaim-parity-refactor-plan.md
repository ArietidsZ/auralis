# Auralis → Viaim 功能平齐重构方案

- 日期：2026-08-24（**v3.3 · MT 锁 Hy-MT2 极致量化**）
- 目标分支代码：`baseline/auralis-android-overhaul`
- 对标：Viaim / iFLYBUDS Pro 3（A09-1）+ Timekettle/讯飞翻译耳机「面对面双向」
- 本文焦点：产品平齐 + **当代端侧模型栈**

---

## 0. 一句话结论

Auralis 是 **手机端侧离线方言双向同声传译**（+ 声音克隆），不是 Viaim 纪要耳机的软件复刻。  
Viaim 明确不做面对面双向语音互译；那是 Auralis 的主场。

---

## 1. 用户已拍板的硬约束（2026-08-24）

| 项 | 决策 |
|---|---|
| 转写 ASR 族 | **Qwen3-ASR 或 Fun-ASR-Nano 0.8B 族**；越小越好 |
| FunASR 型号 | **Fun-ASR-Nano-2512（~0.8B）**；不是 SenseVoice/Paraformer-small |
| Qwen 对照 | **Qwen3-ASR-0.6B**；量化不绑死 |
| **MT** | **Hy-MT2**；**最小型号 Hy-MT2-1.8B**；**极致量化 1.25bit（~440MB）** |
| 端侧加速 | **发版 GPU；NPU 二期**；CPU-only = degraded |
| 禁止当主推 | Zipformer / Piper / Opus-MT / SenseVoice 冒充 Fun-ASR 0.8B |
| 代码 | 暂可不动；先把方案写对 |
| 平齐口径 | Must = 引擎可听 + **face_to_face 最高** + 听译 + 全离线 + 克隆；纪要后置 |

---

## 2. 技术主路径

### 2.1 D1 — ASR

候选对打：**Fun-ASR-Nano-2512** ↔ **Qwen3-ASR-0.6B**（均 ~0.9–1GB 极致包量级）。发版跑 **手机 GPU**；NPU 二期。细节见 v3.2。

### 2.2 D2 — MT（已锁）

**主路径：`Hy-MT2-1.8B` + AngelSlim `1.25bit` GGUF（官方标称约 440MB）**

| 项 | 内容 |
|---|---|
| 家族 | Hy-MT2：1.8B / 7B / 30B-A3B（MoE）；33 语 |
| 最小 | **1.8B**（不上 7B/30B 作默认） |
| 极致量化 | **1.25bit**（`tencent/Hy-MT2-1.8B-1.25bit-GGUF` / AngelSlim 同款） |
| 工程过渡 | 若 STQ/GPU 未就绪：官方 **2bit GGUF**（仍属极致档）；**禁止** Opus-MT 当推荐 |
| 运行时 | llama.cpp 系；**1.25bit 依赖 STQ 内核**（[llama.cpp PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836)，截至 2026-08 **未合入主干** → **必须 vendor/fork**） |
| 一期加速 | 发版要 **GPU**（Vulkan/等价）；PoC 验证 1.25bit 能否在 GPU 后端跑；不行则 2bit+GPU 或 STQ-CPU 仅作临时测质量 |
| 二期 | NPU（GenieX/QNN 等） |
| 许可证 | Tencent HY / Hunyuan 社区条款（注意地域限制，以 LICENSE 为准） |

官方叙述：1.25bit 相对 Hy-MT1.5 的 4bit 端侧路径，体积约 440MB，并称相对 4bit 有约 **1.5×** 加速（Apple A15 等端侧对比语境）。具体 Android GPU RTF **以真机 PoC 为准**。

### 2.3 D3 — TTS / 克隆

**Qwen3-TTS** 主路径；一期 GPU；NPU 二期；禁 Piper 当推荐。

### 2.4 运行时

- 一期：手机 **GPU**（ORT GPU / Vulkan / OpenCL 等）
- MT 特殊：STQ vendor llama.cpp + GPU 后端可行性验证
- 二期：QNN / 厂商 NPU
- CPU-only = degraded

---

## 3. Must / Should / Could / Won't

| 优先级 | 内容 |
|---|---|
| **Must** | GPU 可听；`face_to_face`；听译；全离线 ASR→**Hy-MT2-1.8B 1.25bit（或标明的 2bit 过渡）**→TTS；克隆路径 |
| **Should** | NPU 二期；Records；热词；短摘要 |
| **Could** | 通话尽力；字幕；diarization |
| **Won't** | 纪要全家桶平齐；CPU-only/Opus-MT 当卖点 |

---

## 4. 阶段顺序

0. PoC：ASR 双候选 GPU；**Hy-MT2-1.8B 1.25bit（STQ fork）GPU 可行性** + 短句译质；Qwen3-TTS GPU  
1. 引擎诚实可听  
2. `face_to_face` MVP  
3. Records  
4. 模式/热词  
5. NPU 二期  
6. 纪要后置  

---

## 5. Android 诚实边界

听译绿 · PlaybackCapture 黄 · 真通话双轨 **红**。

---

## 6. 成功定义

> 无网、两人各说方言：GPU 上 ASR 听懂 → **Hy-MT2-1.8B 极致量化**译出 → Qwen3-TTS 出声；面对面双向。NPU 不挡一期。不把过时栈写成推荐。

---

## 7. 修订记录

- v3.2：发版 GPU，NPU 二期  
- **v3.3：** MT 锁 **Hy-MT2-1.8B + 1.25bit**；STQ vendor；2bit 仅工程过渡
