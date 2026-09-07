# 01 共享数据与模型

## C01 单一事实源

`shared/dialect-catalog/catalog.json` 与 `shared/model-manifests/` 是跨平台输入。Android 生成 assets、iOS 构建复制资源、Python 校验都消费它们。生成目录不手工维护。优先直接 JSON 解码；不增加代码生成框架。

方言 ID 永久稳定。方言展示名、ASR 提示语言、MT 语言、TTS 语言各自建模，不能把方言覆盖等同于目标声音覆盖。保留现有 ID；同一 `ttsLanguageCode=zh` 不能证明能合成四川话。平台 UI 只显示当前包确认支持的能力；未验证组合必须有状态说明。

## C02 清单契约

增加 schema 与纯数据样例。写出统一 `sizeBytes`；读取既有 v1 时可接受 `size_bytes`，若两字段冲突则失败。已有 TTS 外部数据、embedding、tokenizer、roles 的信息必须保留。

每个包必须明确：

- `schemaVersion`、`packageId`、`version`、`status`（`draft` / `verified`）。
- `source`：仓库 ID、固定 revision、上游模型 ID、许可证来源；不保存凭据。
- `runtime`：backend 名称、API/模型契约版本、量化类型、已核验 runtime revision、目标 ABI/OS、流式能力。
- `files`：相对路径、正数 `sizeBytes`、64 位十六进制 SHA-256、role 或 supportAsset 分类；涵盖 ONNX external data。
- `roles`：逻辑角色到文件；所有被引用文件必须属于 files。
- `capabilities`：识别/翻译/合成语言集合、clone、streaming，及这些能力的验证状态。

draft 可以缺实际产物大小/哈希/revision，但必须显式 draft，永远不可使 app ready。verified 不允许缺失、重复、空集合、占位版本或 placeholder 哈希；生产检查要求至少完整 ASR、MT、TTS 包与 native runtime。把 status 改 verified 不能绕过文件校验和模型 smoke gate。

读 v1 是迁移边界，内部结构只保留一个字段体系。新写入统一格式。v1 信息不完整时按 draft 迁移；不能补造字节数、hash 或兼容性。

## C03 安装与激活

包状态至少区分 Missing、Installing、Verifying、Installed、RuntimeUnavailable、Ready、Failed。Installed 只表示完整落盘；Ready 必须同时满足 hash、角色、runtime 与必要的加载探测。

1. 校验路径：拒绝绝对路径、`..`、空段、反斜杠混淆与越出根目录的符号链接。
2. 同包安装串行；复制到与最终包同卷的独立 staging 目录，先检查剩余空间。
3. 流式复制并同时计算 hash；支持取消，进度使用完成字节/总字节。
4. 完整校验后原子切换包目录或 active 指针；不得先删当前有效包。
5. 异常只清理本次 staging，保留旧有效版本；并发会话使用的版本不能被替换释放。
6. 启动探测与安装探测复用一套验证逻辑；missing/invalid manifest 不允许跳过校验。
7. 程序崩溃后恢复/清理遗留 staging；模型内容和临时文件不写入日志。

Android 保留 PAD 与开发用本地资产入口，修复现有提取行为。缺少包时显示需要安装的准确体积，不模拟下载完成。Python fetch 工具用固定 revision、允许的文件列表和原子输出，下载失败以非零码结束。

## C04 模型选择与交付

ASR 默认候选 Qwen3-ASR-0.6B；使用上游 processor/tokenizer/decoder 语义。现有手写 mel、逐字 token 匹配和无 cache 解码不能凭形状正确晋升为 verified。

MT 默认保留 Hy-MT 家族。1.25-bit 包明确依赖 STQ kernel；只有固定 runtime commit 的加载、生成、取消测试通过后启用。2-bit/Q4 是评测候选，不能静默换包或把任意 GGUF 声称为兼容。

TTS 必须同时满足文本 tokenizer、语言 token、speaker conditioning、prefill/decode、code predictor、vocoder 的实际模型协议。现有 manifest-only ONNX 包不能拿旧 `talker_lm.onnx` 接口直接跑。输出空数组、零码占位、重复 logits 或未定义形状均为失败。

新证据：上游 llama.cpp 已有 Qwen3-TTS 工具及 speaker reference 入口。因此评估“MT/TTS 共用已核验 native runtime”与“MT native + TTS ORT”两条路径；若前者对目标小模型、中文、克隆、Android/iOS 均通过且降低体积/复杂度，优先前者。工具示例不等于可用于 app 的 streaming API，也不证明 0.6B 已兼容。

默认克隆关闭；用户选了有效档案且模型支持时才激活。可以提供平台实际可用的离线标准声音作为明确标注的替代选项；不能把系统 TTS 当作声音克隆或跨方言声音。没有可用替代时保持文本输出。

## 必须通过的契约案例

有效样例；v1 迁移；未知 schema；字段冲突；空 hash；空文件集；重复路径；路径穿越；role 缺失；external data 缺失；文件损坏；低磁盘/取消安装；旧版本保留；draft 不可 Ready；真实支持语言与 UI 一致。
