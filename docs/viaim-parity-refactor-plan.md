# Auralis → Viaim 功能平齐重构方案

- 日期：2026-08-24（**v3.2 · 加速分期拍板**）
- 目标分支代码：`baseline/auralis-android-overhaul`
- 对标：Viaim / iFLYBUDS Pro 3（A09-1）能力面 + Timekettle/讯飞翻译耳机「面对面双向」赛道
- 本文焦点：产品平齐 + **当代端侧模型栈**（禁止把过时栈写成主路径）

---

## 0. 一句话结论

Auralis 是 **手机端侧离线方言双向同声传译**（+ 声音克隆），不是 Viaim 纪要耳机的软件复刻。  
Viaim 明确不做面对面双向语音互译；那是 Auralis 的主场。

---

## 1. 用户已拍板的硬约束（2026-08-24）

| 项 | 决策 |
|---|---|
| 转写 ASR 族 | **仅 Qwen3-ASR 或 FunASR（指 Fun-ASR-Nano 0.8B 族）**；越小越好 |
| FunASR 具体型号 | **Fun-ASR-Nano-2512（~0.8B）**；**不是** SenseVoiceSmall / Paraformer-small |
| Qwen 对照 | **Qwen3-ASR-0.6B**（不上 1.7B 作默认）；量化位宽**不绑死** |
| 端侧加速 | **发版必须 GPU；NPU = 二期。** 纯 CPU = *degraded fallback*，禁止当产品主推 |
| 禁止当主推 | Zipformer / Piper / Opus-MT / SenseVoice 冒充「Fun-ASR 0.8B」等 |
| 代码 | 暂可不动；先把方案写对 |
| 平齐口径 | Must = 引擎可听 + **face_to_face 最高** + 听译 + 全离线 + 克隆路径；纪要全家桶后置 |

---

## 2. 技术主路径（当代栈）

### 2.1 D1 — ASR

**候选对打（未最终锁死单赢家）：**

| 候选 | 身份 | 极致可装包量级 | 方言口径 | 加速现状（诚实） |
|---|---|---|---|---|
| **Fun-ASR-Nano-2512** | ~0.8B；encoder≈0.2B + Qwen3-0.6B LLM | GGUF enc f16+LLM Q4 ≈ **0.95 GB** | 官方 7 大方言 + 26 口音 | 服务器 CUDA/vLLM 成熟；端侧 GGUF 主打 CPU；桌面有 Vulkan/CUDA；**Android 发版需自接手机 GPU** |
| **Qwen3-ASR-0.6B** | AuT+LLM ASR | sherpa int8 拆分 ≈ **0.94 GB** | 官方 22 种中文方言 | sherpa Android 现成但公开测多为 CPU；发版路径：**ORT/Vulkan/OpenCL 等手机 GPU**；QNN 留二期 |

公开同表（Qwen 报告对 **Fun-ASR-MLT-Nano**）：Dialog-Chinese Dialects CER 19.41 vs **18.24**（Qwen 0.6B 略优）。旗舰 Nano ↔ Qwen **无**同套方言金标。

**产品加速分期（已拍板）：**
- **一期发版门槛：** 手机 **GPU**（Vulkan / OpenCL / ORT GPU EP 等）端到端可跑；验收 RTF/峰值 RAM **以 GPU 为准**，不以 CPU RTF 冒充达标
- **二期：** 骁龙 QNN 等 **NPU**（常见拆法：encoder→HTP，AR decoder→LLM-NPU 运行时）
- CPU-only 仅 degraded

**明确不写进主路径：** Zipformer / SenseVoiceSmall 当「Fun-ASR 0.8B」；纯 CPU 或「未上 GPU 先发版」。

### 2.2 D2 — MT

**主路径：HY-MT1.5 家族**；一期优先可跑在 **GPU** 的落地量化；NPU 译路二期跟进。

- STQ/1.25bit 仅当上游可用且金标通过
- 许可证：Tencent HY Community（注意 EU/UK/KR 限制）
- 自建 JNI

### 2.3 D3 — TTS / 克隆

**主路径：Qwen3-TTS** + 可 GPU 编排（LiteRT/ONNX 等）；NPU 编排二期。

- 工程重点：真机 **GPU** RTF、KV-cache、禁静音假成功
- **不得**用 Piper 冒充产品推荐；临时降级标 *degraded fallback*

### 2.4 运行时

- **一期：** ONNX Runtime **GPU EP** / Vulkan / OpenCL（或等价手机 GPU 后端）
- **二期：** QNN / 厂商 NPU SDK；可拆图
- **禁止当主推：** 仅 CPU EP 的端到端链路
- 音频：Oboe；听译 TTS → `USAGE_MEDIA` → 普通 BT（绿）

---

## 3. 产品 Must / Should / Could / Won't

| 优先级 | 内容 |
|---|---|
| **Must** | 引擎可听可用（**发版 GPU**）；**`face_to_face` 最高**；听译+双语时间线；全离线 ASR→MT→TTS；克隆路径 |
| **Should** | **NPU 二期**；Records/导出；现场存档；方言热词；离线短摘要 |
| **Could** | 通话外放+麦尽力；音视频字幕；diarization |
| **Won't** | 纪要全家桶平齐；云分钟；无硬件闪录；Play 宣称录 Zoom/微信双边；CPU-only 当卖点 |

---

## 4. 阶段顺序

0. PoC：手机 **GPU** 上跑 Fun-ASR-Nano 0.8B ↔ Qwen3-ASR-0.6B 同批方言；RTF/RAM/CER；Hy-MT；Qwen3-TTS  
1. 引擎诚实可听（GPU）  
2. **`face_to_face` MVP**  
3. 薄 Records / 导出  
4. 模式补全与热词  
5. **NPU 二期**（拆图上 QNN 等）  
6. 纪要/协作后置  

---

## 5. Android 诚实边界

| 模式 | 评级 |
|---|---|
| 听译（麦 + FGS + TTS→BT） | 绿 |
| 音视频 PlaybackCapture | 黄 |
| 真·通话/VoIP 双轨录音 | **红** |

---

## 6. 成功定义

> 无网、两人各说方言：App 在 **手机 GPU** 上用 Fun-ASR-Nano 0.8B 或 Qwen3-ASR-0.6B（量化待 PoC）听懂，现代 MT 译出，Qwen3-TTS（或标明临时降级）出声；支持面对面双向。**NPU 不挡一期发版。** 不把过时栈或 CPU-only 写成推荐。

---

## 7. 修订记录

- v3.1：Fun-ASR-Nano 0.8B；GPU 底线 / NPU 优先  
- **v3.2：** 用户确认 **发版先 GPU，NPU 二期**
