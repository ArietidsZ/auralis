# Auralis → Viaim 功能平齐重构方案

- 日期：2026-08-24（**v3.1 · 用户纠偏后**）
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
| 端侧加速 | **必须 GPU 及以上；优先 NPU**。纯 CPU（llama.cpp CPU / 仅 XNNPACK）= *degraded fallback*，禁止当产品主推 |
| 禁止当主推 | Zipformer / Piper / Opus-MT / SenseVoice 冒充「Fun-ASR 0.8B」等 |
| 代码 | 暂可不动；先把方案写对 |
| 平齐口径 | Must = 引擎可听 + **face_to_face 最高** + 听译 + 全离线 + 克隆路径；纪要全家桶后置 |

---

## 2. 技术主路径（当代栈）

### 2.1 D1 — ASR

**候选对打（未最终锁死单赢家）：**

| 候选 | 身份 | 极致可装包量级 | 方言口径 | 加速现状（诚实） |
|---|---|---|---|---|
| **Fun-ASR-Nano-2512** | ~0.8B；encoder≈0.2B + Qwen3-0.6B LLM | GGUF enc f16+LLM Q4 ≈ **0.95 GB** | 官方 7 大方言 + 26 口音 | 服务器 CUDA/vLLM 成熟；端侧 GGUF 主打 CPU；桌面有 Vulkan/CUDA 包；**Android NPU 开箱缺口** |
| **Qwen3-ASR-0.6B** | AuT+LLM ASR | sherpa int8 拆分 ≈ **0.94 GB** | 官方 22 种中文方言 | sherpa Android 现成但公开测多为 **CPU**；桌面有 cuda provider；**Android QNN 开箱缺口** |

公开同表（Qwen 报告对 **Fun-ASR-MLT-Nano**，非旗舰 Nano）：Dialog-Chinese Dialects CER 19.41 vs **18.24**（Qwen 0.6B 略优）。旗舰 Nano ↔ Qwen **无**同套方言金标。

**产品加速要求：** 主路径必须证明 **手机 GPU（Vulkan/OpenCL/ORT GPU）可跑**；NPU（骁龙 QNN 等）为优先目标。验收以 GPU/NPU RTF+峰值 RAM 为准，**不以 CPU RTF 冒充达标**。

**明确不写进主路径：** Zipformer / SenseVoiceSmall 当「Fun-ASR 0.8B」；纯 CPU 推理当 headline。

### 2.2 D2 — MT

**主路径：HY-MT1.5 家族，优先可商用可落地的当代量化**（能上 NPU/GPU 的路径优先于纯 CPU）。

- 继续跟进 STQ/1.25bit **仅当**上游内核合入且质量金标通过
- 许可证：Tencent HY Community（注意 EU/UK/KR 限制）
- 自建 JNI；无官方 `libhymt_jni.so` SDK

### 2.3 D3 — TTS / 克隆

**主路径：Qwen3-TTS（Apache-2.0）+ 社区 ONNX/LiteRT 等可 GPU/NPU 编排**，目标是可听译文 + 声音克隆。

- 工程重点：真机 GPU/NPU RTF、KV-cache、去掉静音假成功
- CosyVoice3 等可作对照；**不得**用 Piper 冒充产品推荐
- 临时工程降级必须标 *degraded fallback*

### 2.4 运行时

- **优先：** ONNX Runtime **QNN / GPU EP**、厂商 NPU SDK、Vulkan/OpenCL
- **禁止当主推：** 仅 CPU EP 的端到端链路
- 音频：Oboe/低延迟；听译 TTS → `USAGE_MEDIA` → 普通 BT（绿）

---

## 3. 产品 Must / Should / Could / Won't（竞品修订后）

| 优先级 | 内容 |
|---|---|
| **Must** | 引擎可听可用（**GPU+**）；**`face_to_face` 双向方言互译（最高）**；`listen_translate`+双语时间线；全离线 ASR→MT→TTS；声音克隆路径 |
| **Should** | NPU 加速落地；Records 落盘与导出；现场存档；方言热词；离线短摘要 |
| **Could** | 通话「外放+麦」尽力辅助；音视频采集字幕；diarization |
| **Won't** | 脑图/Space/小万级问答；云分钟；78 语云平齐；无硬件假装闪录；Play 应用宣称录 Zoom/微信双边（**红灯**）；CPU-only 当卖点 |

---

## 4. 阶段顺序

0. PoC：**手机 GPU（优先再试 NPU）** 上跑 Fun-ASR-Nano 0.8B 与 Qwen3-ASR-0.6B 同批方言 wav；RTF/RAM/CER；Hy-MT；Qwen3-TTS  
1. 引擎诚实可听（真 MT + 真 TTS，禁静音假成功）  
2. **`face_to_face` MVP**（先于厚纪要）  
3. 薄 Records / 导出  
4. 模式补全与热词  
5. 纪要/协作后置  

---

## 5. Android 诚实边界

| 模式 | 评级 |
|---|---|
| 听译（麦 + FGS + TTS→BT） | 绿 |
| 音视频 PlaybackCapture | 黄（需授权，部分 App 静音） |
| 真·通话/VoIP 双轨录音 | **红**（勿当卖点） |

---

## 6. 成功定义

> 无网、两人各说方言/语言：App 在 **手机 GPU 及以上（优先 NPU）** 上用 Fun-ASR-Nano 0.8B 或 Qwen3-ASR-0.6B（量化待 PoC）听懂，现代 MT 译出，Qwen3-TTS（或标明的临时降级）出声；并支持面对面双向。不宣称平齐 Viaim 纪要全家桶，不把过时小模型或 CPU-only 写成推荐栈。

---

## 7. 修订记录

- v1：初版 Viaim 平齐  
- v2：五路联网研究（曾误把 Zipformer/Piper 写成主路径）  
- v3：否决过时主推；ASR 族 Qwen3/FunASR；不锁量化  
- **v3.1：** FunASR = **Fun-ASR-Nano 0.8B**（纠正 SenseVoice 跑偏）；端侧加速 **GPU 底线 / NPU 优先**；CPU-only 降为 degraded
