# Auralis → Viaim 功能平齐重构方案

- 日期：2026-08-24
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
- Vitana / 小万 助手：就本条录音问答
- Space / 项目知识库：录音与文档入库检索
- Live Text / 文字直播（链接 / 二维码实时字幕）
- 录音管理：编辑、分享、导出音频+转写
- AES-256 加密存储；配额制转写分钟（海外 600 免费分钟/月等）

### 1.5 账号与商业化
- 登录、订阅 / 应用内购、转写分钟消耗
- 云端大模型（摘要侧可选 ChatGPT / Claude / Gemini；手册另见 DeepSeek / Qwen / GLM）

> 更细的手册爬取见文末 §11。

---

## 2. Auralis 现状能力（`baseline/auralis-android-overhaul`）

### 2.1 已具备（产品骨架）
- Android：Kotlin + Compose + ONNX Runtime 1.22；`assembleDebug` / `lintDebug` 可通过
- 管线：ASR → Hy-MT → TTS；协程分阶段 + 有界通道背压
- 22 种中文方言/口音选项 + 多语言源/目标
- 声音档案录制与回放（克隆入口；尚未接到实时同传）
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
| 说话人分离 | 无（手册亦未强调） | **P1** |
| 摘要 / 待办 / 脑图 / 小万 | 无 | **P1（可选云或端侧小模型）** |
| 录音库 / 导出 / 分享 / 文字直播 | 无 | **P1** |
| 项目知识库 / Vitana·小万 | 无 | **P2** |
| 耳机蓝牙 / 闪录 / ANC 设置 | 无硬件 | **P2 硬件伴侣** |
| 78 语云转写 | 22 方言离线栈 | **刻意差异；扩展为混合** |
| 账户 / 配额 | 无 | **P2** |

### 2.3 工程阻断（不平齐之前必须先修）
吸收证据驱动方案 F1–F3 + comprehensive plan C1–C4（部分项已在 Aug 6 分支部分修复）：

1. **Hy-MT `libhymt_jni.so` 未打包** → 无 .so 时无法真翻译（现已有 passthrough 降级）  
2. **TTS 非真自回归 + 全零码静音回退** → 克隆播报不可用 / 假成功  
3. **Qwen3-ASR 无法真流式** → 勿把「1.5s partial」绑死在单模型修补上；采用混合 ASR 或下调目标  
4. **NNAPI 跑不了 INT4** → 代码已偏向 CPU；勿再宣称 NPU INT4  
5. **背压静默丢句** 等会话安全问题（旋转屏 release 已部分修复）  
6. **Auralis 品牌未落地**（仍「方言传译」）

> 没有 1–2，谈不上与 Viaim「听译文」平齐；没有录音库，谈不上与 Viaim「纪要产品」平齐。

---

## 3. 产品定位决策

### D-Parity · 平齐范围
**已拍板（2026-08-24，用户跳过确认后按默认执行）：Must = V1–V3 + A1–A3；硬件/Space 后置。**

**采用「核心同传 + Viaim 工作流子集」：**

1. **Must（与 Viaim 主路径平齐）**  
   现场听译、通话/会议辅助转写译、录音存档、录后转写译、双语时间线、导出、基础摘要待办  
2. **Should（体验拉齐）**  
   说话人分离、多语并行转写、音视频模式、模板化摘要/脑图、Live Text 分享  
3. **Could（生态）**  
   自有耳机协议、闪录、Space 知识库、小万对话、账号配额  
4. **Won't（本阶段）**  
   复制 Viaim 云分钟商业化；放弃 Auralis 离线方言克隆差异化

### D1–D3（沿用证据方案，略）
- **ASR**：流式前端（sherpa-onnx 等）+ Qwen3-ASR 精修，或接受交传延迟  
- **MT**：先 1.25-bit vs 2-bit 对照；修 STQ1_0 内核或换 Q4_K_M；AngelSlim License 法务审  
- **TTS**：Track B 非克隆兜底先通听译；Track A Qwen3-TTS 克隆增强

### D-Mode · 交互模式
| 模式 ID | 用户场景 | Viaim 对应 | Auralis 实现要点 |
|---|---|---|---|
| `listen_translate` | 听外语/方言讲座，耳机/扬声器出译文 | 实时翻译 | 单向前端 ASR→MT→TTS |
| `face_to_face` | 两人面对面双向方言互译 | **Viaim 不做 → 差异化** | 双通道或轮流 VAD；双向 TTS |
| `call_assist` | 电话/会议辅助 | 通话录音 | 系统音频采集；默认文本，可选播报 |
| `meeting_record` | 现场会议存档 | 现场录音 | 录音文件 + 实时/事后转写译 |
| `media_caption` | 看视频听播客 | 音视频录音 | 媒体通路 + 悬浮窗字幕 |
| `offline_flash` | 无网先录后处理 | 闪录 | 本地录音队列；硬件后再接闪录 |

---

## 4. 目标架构

```
CaptureSources          Pipeline                 SessionArtifact
─────────────           ────────                 ───────────────
Mic / BLE earbud   →    Streaming ASR partial →  TranscriptTrack[]
Call/media audio   →    Utterance finalize    →  TranslationTrack[]
File import        →    MT (+ cache)          →  AudioRecording
                   →    TTS / earbud play     →  Summary / Todos / MindMap
                                              →  ExportPackage
```

模块增量：`:core-capture` / `:core-pipeline` / `:core-session` / `:data-library` / `:feature-ai-notes` / `:ui-modes`

**原则**：推理引擎仍可全离线；AI Notes 默认端侧可降级，云 Provider 为可选插件。

---

## 5. 分阶段平齐路线图

### Phase 0 · 决策与 PoC（3–5 天）
- PoC：流式 ASR 方言准确度（粤/闽/吴/川）；Hy-MT 1.25 vs 2-bit；TTS 真机 RTF

### Phase 1 · 引擎可听可用（进行中）
1. Hy-MT runtime 打包门禁；失败降级不拖垮管线  
2. TTS 可听（禁静音假成功；Track B 兜底 + Track A 克隆）  
3. 背压 / messageId / 声音档案接入实时会话  
4. 双语时间线；Auralis 命名  
5. Records 薄骨架

### Phase 2 · 录音库与录后处理
会话落盘、Records、录后转写译、导出、加密

### Phase 3 · 模式矩阵
四模式 + `face_to_face` 双向 + 多语并行 + diarization MVP

### Phase 4 · AI Notes
Smart Title / Summary / Todos / Mind Map / 小万可选

### Phase 5 · 协作与高级
文字直播、热词、项目知识库、Quiet Premium UI

### Phase 6 · 硬件伴侣（可选）
BLE、闪录、ANC/EQ、盒键

### Phase 7 · 质量门与 CI（贯穿）

---

## 6. 功能平齐对照表

| # | Viaim 用户故事 | Auralis 目标 | Phase |
|---|---|---|---|
| V1 | 实时字幕 | Interpret 时间线 | 1 |
| V2 | 耳机出声译文 | `listen_translate` | 1–3 |
| V3 | 导出转写稿 | Records + Export | 2 |
| V4–V6 | 通话/现场/音视频 | 模式矩阵 | 3 |
| V7 | 闪录 | 本地队列 / BLE | 2 / 6 |
| V8 | 摘要待办脑图 | AI Notes | 4 |
| V11 | 文字直播 | 协作分享 | 5 |
| V12 | 知识库问答 | 项目 + 小万 | 5 |
| A1 | **双向方言语音互译** | `face_to_face` | 3 |
| A2 | **全离线** | 无网 ASR-MT-TTS | 1 |
| A3 | **声音克隆** | VoiceProfile→TTS | 1 |

---

## 7. 与仓库既有方案的关系

| 文档 | 作用 |
|---|---|
| `docs/comprehensive-refactor-plan.md` | 工程审计 + 七阶段地基 |
| `docs/refactor-plan-2026-08-evidence-based.md` | 模型事实纠偏 |
| **本文** | Viaim 产品平齐 |

---

## 8. 风险与非目标

1. 系统通话录音在 Android 上权限碎片化 — 文案需诚实  
2. 78 语云转写与全离线互斥 — 用离线方言子集 + 可选云扩展  
3. Hy-MT / Qwen 许可需法务审查  
4. 引擎未通时不要先做品牌动画与脑图  
5. 不要在只做了单向听译时声称面对面双向

---

## 9. 立即下一步（进行中）

1. ~~确认 D-Parity~~ → 已按 Must 执行  
2. Phase 1 云端编码进行中（引擎诚实失败 + Records 骨架）  
3. Phase 0 真机 PoC 仍待人工/真机

---

## 10. 成功定义

> **在没有 Viaim 耳机的情况下，Auralis 手机 App 能完成 Viaim 用户最常见的「听懂 · 记下 · 译出 · 导出 · 纪要」闭环，并额外提供 Viaim 不做的「面对面双向方言语音同传」与「全离线」能力。**

---

## 11. 附录：viaim.cn 产品手册增量（2026-08-24 页面爬取）

产品手册页确认型号语境 **A09-1 / iFLYBUDS Pro 3 / 智能体耳机 Pro**（安徽艾德未来）。**不是助听器/方言专用硬件**，而是 AI 会议录音耳机 + 配套 App。

### 录音模式（手册为 5 类）
1. **闪录** — 耳机本机存储；充电盒按键或耳机捏合；约 69min 环境 / 120min 通话（双耳可翻倍）；回连自动下载；可选自动转写（最多 3 语预设）  
2. **通话录音** — 系统电话 + 微信/QQ/飞书/钉钉/企微；网络通话安心录；自动开录；短于 30s 后台片段可自动清理  
3. **现场录音**  
4. **音视频录音** — 媒体通路；Android **悬浮窗** / iOS PiP 显示实时字幕+翻译  
5. **实时翻译** — 手机麦；屏上原文+译文；**译文播到耳机**；语速/扬声器开关

通用：转写语种「自动」或最多 3 个手动语种；翻译目标 1 个；标记 ±10s；录中可问「小万」。

### AI / 知识库增量
- 助手名 **小万**：全局 / 单条录音 / 项目内 / 录中；模型可切 **DeepSeek V4 / Qwen3.5 Flash / GLM-4.6**；内置「自省」  
- 现场录音另有 **深度洞察**  
- 摘要：场景模板 + 行业模板；改摘要主要通过小万  
- **项目 = 个人知识库**：≤50 个；可导入 PDF/docx/txt/md/mp3；回收站 30 天  
- 录音合并：最多 10 条、合计 ≤4h  
- 低质量录音自动折叠  
- **文字直播**：链接/二维码+密码；观众可选手动翻译语种并 **TTS 播译文**  
- Web：https://yun.viaim.cn/  
- 设备：ANC/通透、EQ、手势、语音控制、双设备连接、贴合度、查找设备、OTA；盒端 Qi 无线充 + FlashRecord 键

### 对 Android 平齐的额外隐含需求
- Android 8.0+；App 内完成 BT 配对  
- 通话通路 vs 媒体通路两套采集  
- 悬浮窗权限（音视频模式）  
- 闪录文件传输与 OTA（硬件伴侣阶段）  
- 手册页**未出现**说话人分离、方言产品定位、加密细节表
