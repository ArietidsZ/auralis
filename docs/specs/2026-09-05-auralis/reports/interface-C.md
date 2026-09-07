# interface-C — 共享契约交付（lane C）

状态：第一批交付完成。消费方：A、B。有问题请在各自 interface-?.md 声明，由我合入。

## 1. Schema 与字段体系（v2，唯一字段系统）

- 规范验证器：`convert/manifest_contract.py`（纯标准库，无 torch/onnxruntime 依赖）。
  - `python3 convert/manifest_contract.py check` 校验真实 shared 目录。
  - `python3 convert/manifest_contract.py check-fixtures` 跑 27 个契约用例（当前全过）。
  - `migrate-v1 --input F --output F2` 做 v1→v2。
- JSON Schema 文档：`shared/schema/model-manifest.v2.schema.json`、`shared/schema/dialect-catalog.v1.schema.json`。
- v2 manifest 顶层：`schemaVersion:"2" | packageId(asr/mt/tts) | version | status(draft|verified) | source{repoId,revision,upstreamModelId,licenseSource} | runtime{backend,apiContractVersion,quantization,runtimeRevision,executionProviders,targetPlatforms,streaming,notes} | capabilities{modes,languages,clone,streaming,verification} | files[] | roles{}`
- files 条目键（v2 只允许这五个）：`path | sizeBytes(正整数|null) | sha256(64位小写hex|null) | classification(model|supportAsset) | externalData[]`。
  - 空字符串 sha256 永远非法，未知一律 `null`；全零 64 位哈希视为占位符。
  - `externalData` 指向的路径必须存在于 files（ONNX external data 显式声明）。
  - `size_bytes` 只在 v1 迁移输入里接受；v2 中出现即 bad-value。
- `roles`：逻辑角色→路径；引用文件必须存在于 files 且 classification=model。
- verified 要求：非占位 version、source.revision、runtimeRevision、每个 file 的 sizeBytes+sha256、非空 files/roles/modes/languages/verification。**verified 不绕过运行时文件校验与模型 smoke gate。**
- draft 可以缺大小/哈希/revision，但必须显式 `status:"draft"`，app 永远不得把 draft 当 ready。

## 2. 真实 shared 数据（已迁移，勿再改格式）

- `shared/dialect-catalog/catalog.json`：ID 不变；每个 dialect 新增 `mtLanguage:"Chinese"`（MT 源语言显式建模；缺省语义=asrLanguage）。
- `shared/model-manifests/{asr,mt,tts,index}.json`：全部已迁移为 v2 `draft`。
  - MT：`source.revision=1bed36c0a8f5a0eddf77987b02ba66d4c268aca2`（HF 官方 commit 页核实，2026-09-05），GGUF 官方 LFS sha256=`93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab`（sizeBytes 待真实下载后回填，当前 null）。`runtime.notes` 记录 STQ kernel（PR #22836）约束。
  - TTS：35 个文件全部保留（含 sizeBytes/sha256/roles/externalData 推导/quantization=none）。
- fixtures：`shared/fixtures/{valid,invalid,v1,v1/invalid,v1/expected}/`，覆盖 01 的契约案例（未知 schema、字段冲突、空 hash、空文件集、重复路径、路径穿越/绝对路径/反斜杠、role 缺失、external data 缺失、占位哈希/版本、verified 缺哈希、目录重复 ID、坏 JSON、v1 迁移）。

## 3. 平台资产接入点（Gradle 已改好）

Android（`android/app/build.gradle.kts` 已由我更新）：

- `assets/dialect/catalog.json` ← `shared/dialect-catalog/catalog.json`（原有，保持不变）。
- `assets/models/asr.json`、`assets/models/mt.json`、`assets/models/tts.json`、`assets/models/index.json` ← `shared/model-manifests/`（新增 task `syncSharedModelManifests`，挂在 preBuild）。
- B 消费 manifest 时请用 v2 字段（`sizeBytes`/`sha256`/`roles`）；不要读 v1 字段。index.json 的 `packages` 映射 packageId→文件名。
- 测试依赖保持 junit4（按 B 反馈已消融 kotlin-test / coroutines-test，A 确认 junit4+runBlocking 够用）。还缺什么请写 interface-A/B.md。

iOS：

- 计划以构建期 script phase 复制 `shared/` → bundle `shared/`（`shared/dialect-catalog/catalog.json`、`shared/model-manifests/*.json`），Swift 侧 `Bundle.main.url(forResource:)` 消费；落地后我会更新本文件。

## 4. 对 A/B 的请求与承诺

- A：native runtime 构建需要 llama.cpp pinned revision 时，**不要**从 mt.json `source.revision` 取——`source.revision` 是模型仓库（HF commit）的固定 SHA，不是 llama.cpp 的 commit。runtime 依赖单独固定在 `runtime.runtimeRevision`（核验官方来源前为 null，null 时 A 不得自行臆测值）；`convert/fetch_model.py`（固定 revision + 允许文件列表 + 原子输出 + 失败非零退出）已交付。
- B：安装/校验逻辑请基于 v2 manifest 字段；invalid fixtures 可直接当测试输入参考。

## 5. 对 A/B interface 请求的回复（2026-09-05）

- **B4.1 已执行**：`android/app/build.gradle.kts` 已移除 `androidx.compose.ui:ui-text-google-fonts`。
- **A"无需新测试依赖"已采纳**：未引入 coroutines-test / kotlin-test / Robolectric；`testImplementation` 仅 junit4。需要时写 interface 文件，我加。
- **pack manifest 格式变更通知 B**：`fetch_model.py --asset-pack` 现在把 **v2** manifest 写入 `asset_pack_*/src/main/assets/{pkg}/manifest.json`（不再生成 v1 的 `size_bytes`）。B 的解析器读 v2 字段即可；若保留 v1 兼容读取也无害（真实文件已是 v2，双字段冲突路径不会触发）。
- **A 的 `ModelStatus` 提示已知晓**：Android 侧 honest EP = "CPU (ORT 1.22)"，与我在 iOS 的处理一致（不声称 CoreML/ANE）。
- **共享资产接入点（两 lane 均可用）**：Android `assets/dialect/catalog.json` + `assets/models/{asr,mt,tts,index}.json`；iOS bundle `shared/dialect-catalog/catalog.json` + `shared/model-manifests/*.json`（`SharedContracts.loadCatalog/loadManifest`）。catalog 每个 dialect 新增 `mtLanguage`（默认语义=asrLanguage），ID 不变。

## 6. 本轮补充（复核修复后）

- **revision 语义澄清**：`source.revision` = 模型仓库（HF commit）固定 SHA；`runtime.runtimeRevision` = runtime 依赖（如 llama.cpp）单独 pin，核验官方来源前为 null。A 不得拿 `source.revision` 当 runtime 依赖用，也不得在 null 时臆测值。
- **iOS readiness 统一（`PackageVerifier`，单实现）**：schemaVersion≠"2"/未知 status/空 files/空 roles/缺 size/hash/占位哈希或版本/缺 source.revision（40 位 hex）/缺 runtimeRevision → 永不 ready；draft → `.draft`；文件校验为 `<modelsRoot>/<packageId>/<manifest path>` 逐字路径 + size + 分块 SHA-256；之后必须过真实 runtime probe（onnx = 全 role session 实载；gguf 如实 `runtimeUnavailable`）。`ModelRepository` 与 `OnnxModelManager` 共用此实现，无第二套逻辑。
- **iOS 安装**：包级 staging（复制时逐块算哈希）→ 全量校验 → 原子切换；旧包先移入 `.trash`，切换失败自动回滚，成功后才删；取消保留旧版本。
- **Pipeline 契约（02-runtime R01/R02/R03）**：session/turn 单调 ID；stop 次序 STOPPING→停采集/播放→cancel→join→release→IDLE（幂等）；启动半失败清理（ASR 失败即 FAILED+清理，MT/TTS 失败降级 transcript-only，不阻断转写）；ended-utterance 有界队列（容量 2）溢出 DROPPED 且计数可见；CancellationError 不被通用 catch 吞。
- **未实现 vs 缺产物**：缺产物（模型/Xcode/真机）→ 工具如实 blocked（exit 2）；未实现（保真 runner、GGUF runtime 链接）→ 如实标注 unsupported，不伪装 ready。

## 7. 主审不验收后的真实编译与核验（更新）

主审 `swiftc -typecheck` 抓到的问题已逐项修复，并改为**真实编译 + 真实运行**验证（不再只有 typecheck）：

- 数据层以 SwiftPM 本地包编译（`Package.swift` → target `AuralisCore` = `ios/DialectInterpreter/Data`，仅 Foundation/CryptoKit，无 ORT/UIKit 依赖；路径归属下移到数据层 `ModelStore.defaultDir`，OnnxModelManager 复用）。
- CLT 的 Testing 宏插件服务器不可靠 → 核验用**无宏可执行 harness** `swift run core-checks`（`core/Checks/`），断言直接跑、失败逐条列名。
- `core-checks` 内容：
  - **共享 fixtures 一致性**（用 `shared/fixtures/` 原字节，不造宽松绿灯）：valid manifest×3 全 decode、catalog 拒收、draft fixture evaluate==`.draft`、tts-verified evaluate 不为 manifestInvalid/ready、invalid×20+ 全拒、跨包路径拒、盘上 symlink 逃逸拒。
  - **staged install**（真实文件 I/O + 真实 SHA-256）：单前缀布局断言（`<models>/asr/encoder.onnx`，无 `asr/asr`）+ 安装后 evaluateSync==ready、坏哈希保旧包、取消保旧包、源 symlink 拒、**目的树 symlink 被整包切换替换且不写穿**、flock 互斥拒并发安装、崩溃恢复还原旧包+清 stale staging、空间不足在任何写入前拒绝。
  - 期间修掉的真实缺陷：`isCommitSha` 误用 64 位长度校验、externalData 两遍校验顺序、URL/String 比较、`import CryptoKit` 缺失等。
- **verify 接入**：`scripts/verify` 的 `--mode fast` 与 `--scope ios`（Xcode 判定前）及 `--mode full` 均运行 `swift-core-build`+`swift-core-checks`，并**实际调用** B 的会话 host runner `run_host_tests.sh`（不是放进 Xcode target 就算数）；无 swift 工具链时如实报 blocked（exit 2），绝不静默计 pass。macOS CI 同入口。

## 8. 本机（无完整 Xcode）实测汇总

| 检查 | 结果 |
|---|---|
| `python3 scripts/verify --mode fast` | **8 pass / 0 fail / 0 blocked**（contracts 2 + python light 3 + swift-core-build + swift-core-checks + session-host-tests） |
| `python3 scripts/verify --scope ios`（本机） | 8 pass + ios-xcodebuild **blocked**（CommandLineTools，无 iphonesimulator SDK）——core/host 结果保留 |
| `swift run core-checks` | **ALL PASS**（fixtures 一致性 + staged install 全部断言） |
| B lane host 会话测试 | 21/21 pass（主审独立复跑；B 继续追加播放器错误/取消修复） |

仍然 blocked（如实）：ios-xcodebuild（需完整 Xcode；CI macos runner 为首个真实 iOS 编译点）、模型产物相关全链。
