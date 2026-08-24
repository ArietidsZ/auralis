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
**已拍板（2026-08-24，用户跳过确认后按默认执行）：Must = V1–V3 + A1–A3；硬件/Space 后置。**

**采用「核心同传 + Viaim 工作流子集」：**

1. **Must（与 Viaim 主路径平齐）**  
   现场听译、通话/会议辅助转写译、录音存档、录后转写译、双语时间线、导出、基础摘要待办  
2. **Should（体验拉齐）**  
   说话人分离、多语并行转写、音视频模式、模板化摘要/脑图、Live Text 分享  
3. **Could（生态）**  
   自有耳机协议、闪录、Space 知识库、Vitana 对话、账号配额  
4. **Won't（本阶段）**  
   复制 Viaim 云分钟商业化；放弃 Auralis 离线方言克隆差异化

### D1–D3（沿用证据方案，略）
- **ASR**：流式前端（sherpa-onnx 等）+ Qwen3-ASR 精修，或接受交传延迟  
- **MT**：先 1.25-bit vs 2-bit 对照；修 STQ1_0 内核或换 Q4_K_M；AngelSlim License 法务审  
- **TTS**：Track B 非克隆兜底先通听译；Track A Qwen3-TTS 克隆增强

### D-Mode · 交互模式命名（建议对标 Viaim 四模式 + Auralis 双向）
| 模式 ID | 用户场景 | Viaim 对应 | Auralis 实现要点 |
|---|---|---|---|
| `listen_translate` | 听外语/方言讲座，耳机/扬声器出译文 | Live Translation & Recording | 单向前端 ASR→MT→TTS；手机麦或外放采集 |
| `face_to_face` | 两人面对面双向方言互译 | **Viaim 不做 → 差异化** | 双通道或轮流 VAD；双向 TTS；UI 左右/上下分栏 |
| `call_assist` | 电话/会议辅助 | Call Recording | 系统音频采集（需权限/厂商限制说明）+ 转写译；**默认文本，可选播报** |
| `meeting_record` | 现场会议存档 | Live Recording | 录音文件 + 实时/事后转写译 + 说话人 |
| `media_caption` | 看视频听播客 | A/V Recording | 播放设备音频采集 + 字幕时间线 |
| `offline_flash` | 无网先录后处理 | FlashRecord | 无耳机时：本地录音队列；有硬件后再接闪录 |

---

## 4. 目标架构（为平齐而调的一层）

在既有七阶段工程路线之上，增加 **「会话产物」层**：

```
CaptureSources          Pipeline                 SessionArtifact
─────────────           ────────                 ───────────────
Mic / BLE earbud   →    Streaming ASR partial →  TranscriptTrack[]
Call/media audio   →    Utterance finalize    →  TranslationTrack[]
File import        →    MT (+ cache)          →  AudioRecording
                   →    TTS / earbud play     →  Summary / Todos / MindMap
                                              →  ExportPackage
```

### 模块增量（相对现有 `:app` 单模块）
- `:core-capture` — Mic / 系统音频 / 文件导入统一 `AudioSource`
- `:core-pipeline` — 既有编排 + 多模式策略（`PipelineMode`）
- `:core-session` — 会话生命周期、暂停/续录、5h 分段策略
- `:data-library` — 录音库、转写稿、导出、加密存储（对标 Viaim Records）
- `:feature-ai-notes` — 摘要/待办/脑图（端侧小模型或可选云 Provider）
- `:ui-modes` — 四模式入口 + 双语时间线 + Live Text

**原则**：推理引擎仍可全离线；AI Notes 默认端侧可降级，云 Provider 为可选插件，不绑架核心同传。

---

## 5. 分阶段平齐路线图

> 每阶段「平齐验收」用 Viaim 用户故事对照；工程验收沿用 `docs/android-verification.md` 并修订不可达指标。

### Phase 0 · 决策与 PoC（3–5 天）
- 拍板 D-Parity / D1–D3 / 是否做 call 音频采集（Android 版本与厂商差异）
- PoC：流式 ASR 方言准确度（粤/闽/吴/川）
- PoC：Hy-MT 1.25 vs 2-bit；TTS 真机 RTF
- **验收**：三份数字报告 + 模式范围签字

### Phase 1 · 引擎可听可用（阻断解除，约 2–3 周）
对标 Viaim「能听懂、能看见字」的最小闭环。

1. 打包真实 Hy-MT runtime；失败时 **降级 ASR+直通提示**，不拖垮全管线  
2. TTS 可听（先 Track B 兜底，再 Track A 克隆）  
3. 修会话生命周期 / 背压 / messageId  
4. EP 策略改为 CPU/KleidiAI（+ 可选 QNN）  
5. 双语时间线 UI：原文 / 译文 / 播放状态  

**平齐验收**
- [ ] `listen_translate`：说一句方言/外语 → 屏上原文+译文 → 扬声器可听译文  
- [ ] 同句二次命中翻译缓存，延迟下降  
- [ ] 不再出现「静音假成功」

### Phase 2 · 录音库与录后处理（约 2 周）— **对齐 Viaim Records 主路径**
1. 会话自动落盘：音频 + 转写 + 翻译 + 元数据  
2. Records 列表：播放、重命名、删除、搜索  
3. 录后转写 / 录后翻译（设置：实时开/关）  
4. 导出：音频 WAV/M4A + 转写 TXT/MD；摘要后补 DOCX/PDF  
5. AES 加密落盘（对标 AES-256 叙事）  

**平齐验收**
- [ ] 无网完成录制 → 有网或本地模型完成转写译 → 导出分享  
- [ ] 单次会话支持暂停/继续；长会可分段（对标 5h 策略可配置）

### Phase 3 · 模式矩阵（约 2–3 周）— **对齐 Viaim 四模式 + 双向差异化**
1. `meeting_record` 现场模式  
2. `call_assist`（能做则做；做不到的机型给清晰降级说明）  
3. `media_caption` 音视频字幕模式  
4. `face_to_face` **双向互译**（Viaim 缺口，Auralis 主打）  
5. 多语言并行转写（先 2，再冲 3）  
6. 说话人分离（diarization）最小可用  

**平齐验收**
- [ ] 用户从首页一键进入四模式，文案/图标与任务匹配  
- [ ] 面对面双向：双方各说一句，双方都能听到对方语言的语音输出  
- [ ] 同传听译模式：译文默认进耳机路由（有 BT 时），无耳机则外放

### Phase 4 · AI Notes 平齐（约 2 周）— **对齐 Summary / Todos / Mind Map**
1. Smart Title  
2. Summary + 模板 + 重新生成  
3. Action Items  
4. Mind Map（可先 MD 大纲，再图形）  
5. Provider 抽象：`OnDeviceNotes` | `CloudNotes(OpenAI|Claude|Gemini)`  
6. Vitana 级「对本条录音问答」作为可选  

**平齐验收**
- [ ] 一场会议结束后 自动/一键出摘要+待办  
- [ ] 导出格式至少 TXT/MD；DOCX/PDF 随后  
- [ ] 关闭云时仍可离线出「抽取式」简版摘要

### Phase 5 · 协作与高级（约 2 周）
1. Live Text 分享（本地 WebSocket / 短链服务）  
2. 行业/个人词库热词  
3. Space 知识库 MVP（本地向量检索）  
4. Auralis 品牌与 Quiet Premium UI 全面落地  

### Phase 6 · 硬件伴侣（可选，独立里程碑）
仅当有耳机硬件或协议时启动：BLE 控制、闪录同步、ANC/EQ、盒键。  
**在此之前，用「手机麦 + BT 音频路由」模拟 Viaim 听译体验即可宣称软件平齐。**

### Phase 7 · 质量门与 CI（贯穿）
- 修订 verification：同传模式 E2E 3–4.5s；对话 partial 仅在混合 ASR 下卡 1.5s  
- Macrobenchmark + 真机门禁  
- 契约：`shared/dialect-catalog`、`model-manifests` 单一事实源  

---

## 6. 功能平齐对照表（执行看板）

| # | Viaim 用户故事 | Auralis 目标行为 | Phase | 状态假设 |
|---|---|---|---|---|
| V1 | 开会时实时看到字幕 | Interpret 时间线 partial→final | 1 | 引擎阻断中 |
| V2 | 听外语讲座，耳机出声译文 | `listen_translate` + TTS/BT | 1–3 | 阻断中 |
| V3 | 录完导出转写稿 | Records + Export | 2 | 未做 |
| V4 | 通话/会议录音 | `call_assist` | 3 | 未做 |
| V5 | 现场录音 | `meeting_record` | 3 | 未做 |
| V6 | 看视频出字幕翻译 | `media_caption` | 3 | 未做 |
| V7 | 闪录后回传处理 | 本地队列 / 未来 BLE | 2 / 6 | 未做 |
| V8 | 摘要 / 待办 / 脑图 | AI Notes | 4 | 未做 |
| V9 | 说话人分离 | diarization | 3 | 未做 |
| V10 | 多语同时转写 | multi-track ASR | 3 | 未做 |
| V11 | Live Text 分享 | 协作分享 | 5 | 未做 |
| V12 | 知识库问答 | Space + Vitana | 5 | 未做 |
| V13 | 词库提升准确率 | 热词 | 5 | 未做 |
| V14 | 加密与隐私 | EncryptedFile + 明示离线 | 2 | 部分 |
| A1 | **双向方言语音互译** | `face_to_face` | 3 | Viaim 无；必须做 |
| A2 | **全离线可用** | 无网跑通 ASR-MT-TTS | 1 | 差异化必须守住 |
| A3 | **声音克隆** | VoiceProfile → TTS | 1（Track A） | 差异化 |

---

## 7. 与仓库既有方案的关系

| 文档 | 作用 | 本文增量 |
|---|---|---|
| `docs/comprehensive-refactor-plan.md` | 工程审计 + 七阶段地基 | 不重复；Phase1 直接依赖其 C1–C4 |
| `docs/refactor-plan-2026-08-evidence-based.md` | 模型事实纠偏（流式/NNAPI/1.25bit） | 采纳 D1–D4；延迟目标改「同传/交传双模式」 |
| **本文** | **Viaim 产品平齐** | 模式矩阵、录音库、AI Notes、对照表、Won't 清单 |

建议落地文件名：`docs/viaim-parity-refactor-plan.md`（与本文一致）。

---

## 8. 风险与非目标

1. **系统通话录音**在 Android 上权限碎片化严重，V4 可能永远「尽力而为」——产品文案需诚实。  
2. **78 语云转写**与全离线互斥；平齐用「离线方言子集 + 可选云扩展」而非硬凑 78。  
3. **Hy-MT / Qwen 许可**与 Viaim 云分钟商业模式不同，分发前做法务审查。  
4. **不要**在引擎未通时先做品牌动画与脑图——那是 Viaim 表层，不是平齐关键路径。  
5. **不要**声称「已支持面对面双向」若只做了单向听译（Viaim 自己也避免此声称）。

---

## 9. 立即下一步（暂缓）

1. ~~确认 D-Parity~~ → 已按 Must 执行  
2. ~~Phase 1 云端编码~~ → **按用户要求暂不动代码**（方案先放着）  
3. 需要开工时从 Phase 1 引擎诚实失败 + Records 骨架续上  

---

## 10. 成功定义（可对外说的一句话）

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

### AI / 知识库增量（相对正文 §1.4）
- 助手名 **小万**（非 Vitana）：全局 / 单条录音 / 项目内 / 录中；模型可切 **DeepSeek V4 / Qwen3.5 Flash / GLM-4.6**；内置「自省」工具  
- 现场录音另有 **深度洞察**  
- 摘要：场景模板 + 行业模板；改摘要主要通过小万，无独立 regenerate 按钮（重转写会连带重生）  
- **项目 = 个人知识库**：≤50 个；可导入 PDF/docx/txt/md/mp3；回收站 30 天  
- 录音合并：最多 10 条、合计 ≤4h  
- 低质量录音自动折叠  
- **文字直播**：链接/二维码+密码；观众可选手动翻译语种并 **TTS 播译文**；会话结束链接失效  
- Web：https://yun.viaim.cn/  
- 设备：ANC/通透、EQ、手势、语音控制、双设备连接、贴合度、查找设备、OTA；盒端 Qi 无线充 + FlashRecord 键  

### 对 Android 平齐的额外隐含需求
- Android 8.0+；App 内完成 BT 配对（不必先系统配对）  
- 通话通路 vs 媒体通路两套采集  
- 悬浮窗权限（音视频模式）  
- 闪录文件传输与 OTA（硬件伴侣阶段）  
- 手册页**未出现**说话人分离、方言产品定位、加密细节表（隐私政策另链）
