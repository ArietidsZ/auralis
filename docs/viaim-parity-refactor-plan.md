# Auralis → Viaim 功能平齐重构方案

- 日期：2026-08-24（**v3 · 用户纠偏后**）
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
| 转写 ASR | **仅 Qwen3-ASR 或 FunASR**；**越小越好** |
| 转写默认（跳过确认后） | **Qwen3-ASR-0.6B**（同系列最小；不上 1.7B；**量化不绑死**，由 PoC/你拍板） |
| 更小备选 | FunASR **SenseVoiceSmall**（~250MB q8；方言面窄于 Qwen3） |
| 禁止当主推 | Zipformer / Piper / Opus-MT 等写成「产品推荐」 |
| 代码 | 暂可不动；先把方案写对 |
| 平齐口径 | Must = 引擎可听 + **face_to_face 最高** + 听译 + 全离线 + 克隆路径；纪要全家桶后置 |

---

## 2. 技术主路径（当代栈）

### 2.1 D1 — ASR（已锁）

**主路径：`Qwen3-ASR-0.6B`（sherpa-onnx / 自有 ONNX Runtime）**

- 体积：随量化而变（社区常见 INT8 打包约 **0.9GB**：conv≈42MB + enc≈174MB + dec≈721MB）；**INT8 不是产品决策，只是导出选项之一**
- 能力：官方 **22 种中文方言/口音**；Apache-2.0
- 延迟：端侧以 VAD/分句 +（若导出具备）KV-cache 解码为目标；**不以旧流式 Zipformer 顶替 Qwen3**
- 工程债：把导出、KV-cache、端侧 RTF 做实——这是正业，不是退回 2023 小模型

**体积更敏感时：** FunASR `SenseVoiceSmall` q8（~250MB）作轻量档；方言场景仍回 Qwen3-0.6B。

**明确不写进主路径：** Zipformer / Nemotron / TeleSpeech 当「推荐 ASR」。

### 2.2 D2 — MT

**主路径：HY-MT1.5 家族，优先可商用可落地的当代量化**（Q4_K_M 可运行于 mainline llama.cpp 是工程现实，不是「拥抱落后」）。

- 继续跟进 STQ/1.25bit **仅当**上游内核合入且质量金标通过——作为**更小更先进的量化选项**，不是倒退
- 许可证：Tencent HY Community（注意 EU/UK/KR 限制）
- 自建 JNI；无官方 `libhymt_jni.so` SDK

### 2.3 D3 — TTS / 克隆

**主路径：Qwen3-TTS（Apache-2.0）+ 社区 ONNX 拆分（prefill/decode/code_predictor/vocoder）**，目标是可听译文 + 声音克隆。

- 工程重点：真机 RTF、KV-cache 自回归、去掉静音假成功
- CosyVoice3 / ZipVoice 等可作对照评测，**不得**用 Piper 冒充产品推荐
- 若真机暂时跑不动：允许 **临时工程降级音色**，文档里必须标成 *degraded fallback*，禁止写进「推荐栈」标题

### 2.4 运行时

- ONNX Runtime Android（CPU / 骏龙 QNN 等当代 EP）；不要再写「NNAPI 跑 INT4」当卖点
- 音频：Oboe/低延迟路径；听译 TTS 走 `USAGE_MEDIA` → 普通 BT 耳机（绿）

---

## 3. 产品 Must / Should / Could / Won't（竞品修订后）

| 优先级 | 内容 |
|---|---|
| **Must** | 引擎可听可用；**`face_to_face` 双向方言互译（最高）**；`listen_translate`+双语时间线；全离线 ASR→MT→TTS；声音克隆路径 |
| **Should** | Records 落盘与导出；现场存档；方言热词；离线短摘要 |
| **Could** | 通话「外放+麦」尽力辅助；音视频采集字幕；diarization |
| **Won't** | 脑图/Space/小万级问答；云分钟；78 语云平齐；无硬件假装闪录；Play 应用宣称录 Zoom/微信双边（**红灯**） |

---

## 4. 阶段顺序

0. PoC：Qwen3-0.6B 中端 RTF/RAM；Hy-MT 质量；Qwen3-TTS 真机 RTF  
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

> 无网、两人各说方言/语言：App 用 **Qwen3-ASR-0.6B**（量化待定）听懂，现代 MT 译出，**Qwen3-TTS（或标明的临时降级）** 出声；并支持面对面双向。不宣称平齐 Viaim 纪要全家桶，不把过时小模型写成推荐栈。

---

## 7. 修订记录

- v1：初版 Viaim 平齐  
- v2：五路联网研究（曾误把 Zipformer/Piper 写成主路径）  
- **v3（本版）：** 用户否决过时主推；ASR 锁定 Qwen3/FunASR 且默认 0.6B；**不锁 INT8**；TTS/MT 当代主路径；修复 main 文档损坏
