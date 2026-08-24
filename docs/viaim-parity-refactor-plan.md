# Auralis → Viaim 功能平齐重构方案

- 日期：2026-08-24（**联网研究优化版 v2**）
- 研究覆盖：ASR / MT / TTS / 竞品优先级 / Android 采集可行性（五路 fan-out）
- 目标分支：`baseline/auralis-android-overhaul`（`ArietidsZ/cross-dialect-communication`）
- 对标产品：Viaim / 讯飞 iFLYBUDS Pro 3（型号 **XFVI-A09 / A09-1**）+ viaim App（详情页 `viaim.cn/#/details?...h=A09-1`）
- 方法：仓库现有审计（`docs/comprehensive-refactor-plan.md`、`docs/refactor-plan-2026-08-evidence-based.md`）+ Viaim 官方指南 / Play 商店 / 产品页公开能力清单交叉对照
- 本文焦点：**产品能力平齐**，不是再写一份纯工程修 bug 清单（工程 critical 仍作为前置条件吸收）

---

## 0. 一句话结论

**Auralis 与 Viaim 不是同一品类的克隆。**  
Viaim A09 是「蓝牙 AI 录音耳机 + 云端转写/翻译/纪要 Agent」；Auralis 是「手机端侧离线 ASR→MT→TTS 跨方言同声传译 + 声音克隆」。

要做到「功能平齐」，必须先统一口径：

| 平齐口径 | 含义 | 建议 |
|---|---|---|
| **A. 体验平齐** | 用户能完成 Viaim 主路径上的同等任务（录、转、译、听、存、导出、摘要） | **采用（本文默认）** |
| **B. 硬件生态平齐** | 自有耳机、闪录、ANC、骨传导麦、充电盒按键 | 非本仓范围；单列「硬件伴侣」可选里程碑 |
| **C. 云模型平齐** | 78 语 / 145 变体、ChatGPT/Claude/Gemini 纪要、配额分钟 | 与 Auralis「全离线」定位冲突；用 **混合模式** 可选接入 |

> Viaim 官方明确：**不支持面对面双向实时语音互译，也不支持通话双向语音互译**；多数模式里翻译是「屏上文本辅助」，仅「同传听译/Live Translation & Recording」会把译文播到耳机。  
> 这意味着 Auralis 的核心（双向方言语音同传）**不是在追 Viaim，而是在补 Viaim 明确不做的空白**。平齐应理解为「覆盖 Viaim 用户价值面 + 保留 Auralis 差异化」。

---

## 1. Viaim A09 / App 功能清单（对标基线）

来源：viaim 官方 User Guide、Play 商店描述、公开评测与国行配套说明（iFLYBUDS / viaim 同生态）。

### 1.1 硬件侧（A09 / Pro 3）
- 入耳式 AI 录音/翻译耳机，主动降噪，骨传导麦（旗舰定位）
- 蓝牙配对；App 调音效 / 降噪 / 连接管理
- **FlashRecord 闪录**：耳机本机存储（通话约 2h / 现场约 1h），回连 App 再转写
- 部分型号支持充电盒一键闪录（RecDot；A09 以耳机端为主）

### 1.2 录音模式（App 四模式）
1. **通话录音 Call** — 电话 / Zoom / Teams / Meet；Safe Record（来电不打断会议录制）
2. **现场录音 Live** — 面对面访谈、会议
3. **音视频录音 A/V** — YouTube 等播放内容（仅录对方侧；会议应改用 Call）
4. **同传听译 Live Translation & Recording** — 外语讲座：手机靠近声源，**耳机播译文**，App 双语转写

约束：单次 App 录音最长 5h；可分段续录。

### 1.3 转写 / 翻译
- 实时转写 或 录后转写（设置开关）
- 同时最多 **3 个转写语言**
- 翻译：录中 / 录后；原文+译文并排
- 语种：约 **78 语言 / 含方言变体约 145**（国行材料另提：约 32 语 + 12 方言 + 2 民族语、行业术语）
- 说话人分离；语气词/重复过滤；行业词库 / 个人词库
- **明确不做**：面对面双向实时语音互译、通话双向语音互译

### 1.4 AI 后处理与知识库
- Smart Title 自动标题
- Summary 摘要（模板 / 自定义 / 重新生成 / 导出 TXT·MD·DOCX·PDF）
- Action Items 待办
- Mind Map 脑图（JPEG / MD / PDF）
- Vitana 助手：就本条录音问答
- Space / 个人知识库：录音与文档入库检索
- Live Text 分享（链接 / 二维码实时字幕）
- 录音管理：编辑、分享、导出音频+转写
- AES-256 加密存储；配额制转写分钟（海外 600 免费分钟/月等）

### 1.5 账号与商业化
- 登录、订阅 / 应用内购、转写分钟消耗
- 云端大模型（摘要侧可选 ChatGPT / Claude / Gemini）

---

## 2. Auralis 现状能力（`baseline/auralis-android-overhaul`）

### 2.1 已具备（产品骨架）
- Android：Kotlin + Compose + ONNX Runtime；`assembleDebug` / `lintDebug` 可通过
- 管线：ASR → Hy-MT → TTS；协程分阶段 + 有界通道背压
- 22 种中文方言/口音选项 + 多语言源/目标
- 声音档案录制与回放（克隆入口）
- Play Asset Delivery 模型包；全离线设计目标
- UI 四屏骨架：Interpret / Setup / VoiceProfile / Settings
- 仓库已有两份深度重构方案与 verification 延迟目标

### 2.2 相对 Viaim 的缺口（产品层）
| Viaim 能力 | Auralis 现状 | 差距级别 |
|---|---|---|
| 通话 / 会议录音模式 | 仅手机麦实时同传 | **P0 产品缺口** |
| 现场录音存档 + 录后转写 | 无会话录音库 | **P0** |
| 音视频外放采集模式 | 无 | **P1** |
| 同传听译（听译文） | 有 TTS 播报意图，但引擎 critical 未通 | **P0（引擎+模式）** |
| 双向面对面语音互译 | 设计目标有，引擎未通 | **P0（Auralis 主场）** |
| 多语同时转写（≤3） | 单源单目标 | **P1** |
| 说话人分离 | 无 | **P1** |
| 摘要 / 待办 / 脑图 | 无 | **P1（可选云或端侧小模型）** |
| 录音库 / 导出 / 分享 / Live Text | 无 | **P1** |
| 知识库 Space / Vitana | 无 | **P2** |
| 耳机蓝牙 / 闪录 / ANC 设置 | 无硬件 | **P2 硬件伴侣** |
| 78 语云转写 | 22 方言离线栈 | **刻意差异；扩展为混合** |
| 账户 / 配额 | 无 | **P2** |

### 2.3 工程阻断（不平齐之前必须先修）
吸收证据驱动方案 F1–F3 + comprehensive plan C1–C4：

1. **Hy-MT `libhymt_jni.so` 未打包** → 翻译阶段失败，整条管线垮  
2. **TTS 非真自回归 + 全零码静音回退** → 克隆播报不可用  
3. **Qwen3-ASR 无法真流式** → 勿把「1.5s partial」绑死在单模型修补上；采用混合 ASR 或下调目标  
4. **NNAPI 跑不了 INT4** → 「NPU 优先」策略失效，改 CPU/KleidiAI 或骁龙 QNN  
5. **旋转屏 `releaseAll()`、背压静默丢句、FIFO 消息错配** 等会话安全问题  
6. **Auralis 品牌未落地**（仍「方言传译」）

> 没有 1–2，谈不上与 Viaim「听译文」平齐；没有录音库，谈不上与 Viaim「纪要产品」平齐。

---

## 3. 产品定位决策（开工前必须拍板）

### D-Parity · 平齐范围
**已拍板并经联网研究修订（2026-08-24）：Must = 引擎可听 + `face_to_face`（最高）+ `listen_translate` + 全离线 + 声音克隆路径；Records/摘要降为 Should；硬件/Space/脑图/小万 Won't。**

**采用「核心同传 + Viaim 工作流子集」：**

1. **Must（与 Viaim 主路径平齐）**  
   现场听译、通话/会议辅助转写译、录音存档、录后转写译、双语时间线、导出、基础摘要待办  
2. **Should（体验拉齐）**  
   说话人分离、多语并行转写、音视频模式、模板化摘要/脑图、Live Text 分享  
3. **Could（生态）**  
   自有耳机协议、闪录、Space 知识库、Vitana 对话、账号配额  
4. **Won't（本阶段）**  
   复制 Viaim 云分钟商业化；放弃 Auralis 离线方言克隆差异化

### D1–D3（2026-08-24 联网研究拍板）
- **D1 ASR = 混合**：sherpa-onnx **流式 Zipformer/Paraformer 草稿** + **Qwen3-ASR-0.6B int8 离线精修**（方言权威）。Qwen3 官方流式仅 vLLM，端侧 sherpa 无真流式。
- **D2 MT = Q4_K_M**：Phase 1 用 **HY-MT1.5-1.8B Q4_K_M GGUF（~1.13GB）+ mainline llama.cpp JNI**。**不要**把 1.25bit/STQ1_0（PR #22836 未合）当 Phase 1 阻断项。EU/UK/KR 考虑 MADLAD ONNX（Apache-2.0）。
- **D3 TTS = 双轨**：Phase 1 **sherpa-onnx + Piper**（Galaxy S10 RTF≈0.08）保证非静音听译；Phase 2+ **ZipVoice/Pocket** 克隆（许可证审查）；Qwen3-TTS 作远期（M4 CPU RTF 仍 3.8–4.7，无可靠中端 Android 数字前不进主路径）。

### D-Mode · 交互模式命名（建议对标 Viaim 四模式 + Auralis 双向）
| 模式 ID | 用户场景 | Viaim 对应 | Auralis 实现要点 |
|---|---|---|---|
| `listen_translate` | 听外语/方言讲座，耳机/扬声器出译文 | Live Translation & Recording | 单向前端 ASR→MT→TTS；手机麦或外放采集 |
| `face_to_face` | 两人面对面双向方言互译 | **Viaim 不做 → 差异化** | 双通道或轮流 VAD；双向 TTS；UI 左右/上下分栏 |
| `call_assist` | 电话/会议辅助 | Call Recording | 系统音频采集（需权限/厂商限制说明）+ 转写译；**默认文本，可选播报** |
| `meeting_record` | 现场会议存档 | Live Recording | 录音文件 + 实时/事后转写译 + 说话人 |
| `media_caption` | 看视频听播客 | A/V Recordi