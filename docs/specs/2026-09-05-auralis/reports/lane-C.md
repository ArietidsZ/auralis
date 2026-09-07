# lane-C 报告 — platform/tooling（shared、convert、scripts、CI、Gradle、iOS）

状态：**实现完成（本 lane 范围）**。产品门全部如实 blocked（无模型产物、无完整 Xcode、无真机），未虚报。

## 1. 交付内容（精确文件）

### 共享契约（specs 01 C01/C02 优先级已执行）

- `shared/schema/model-manifest.v2.schema.json`、`shared/schema/dialect-catalog.v1.schema.json`（JSON Schema 2020-12 文档；规范验证器在 convert）。
- `shared/model-manifests/{asr,mt,tts,index}.json`：v1 混合字段 → **v2 单一字段体系**（`sizeBytes`/`sha256`/`classification`/`externalData`/`roles`/`source`/`runtime`/`capabilities`），全部 `status:"draft"`；TTS 35 文件含哈希/大小/角色/external data 全部保留。
- MT manifest：`source.revision=1bed36c0a8f5a0eddf77987b02ba66d4c268aca2`（HF 官方 commit 页核实 2026-09-05），GGUF 官方 LFS `sha256=93e025c9…60ab`，`runtime.notes` 记录 STQ kernel（PR #22836）约束。sizeBytes 留 null（待真实下载回填，未补造）。
- `shared/dialect-catalog/catalog.json`：ID 不变；每方言新增 `mtLanguage:"Chinese"`（MT 源语言显式建模）。
- `shared/fixtures/`：valid×4、invalid×17（未知 schema/字段冲突/空 hash/空文件集/重复路径/穿越/绝对路径/反斜杠/role 缺失/external data 缺失/占位哈希/占位版本/verified 缺哈希/bad verification/未知键/目录重复 ID/坏 JSON）、v1 迁移 3 例 + v1 invalid×3。

### convert（Python 工具链）

- `convert/manifest_contract.py`（新）：规范验证器 + v1→v2 迁移 + fixture 运行器 + CLI（`check`/`check-fixtures`/`migrate-v1`）。纯标准库。
- `convert/tests/test_manifest_contract.py`（新）：25 个 stdlib unittest（CLI 退出码、schema 规则、迁移规则、路径规则）。
- `convert/fetch_model.py`（新）：固定 revision + files 白名单 + sha256/size 校验 + **整包 staging 安装**（全部文件先入 `dest/.staging/<pkg>-<uuid>` 校验通过才原子切换包目录；旧包先移入 `.trash`，切换失败自动回滚，成功后才删旧包）+ `--asset-pack`（pack manifest 写 `assets/<packageId>/manifest.json`）+ `--update-manifest`。无 revision 拒绝（exit 4）、哈希/大小不匹配 exit 1 且旧包保持原样、空间不足/环境类失败 exit 2、symlink 逃逸拒绝。测试 8 例（无网络，monkeypatch `_download_to`）：第二文件坏哈希保留旧包、全新目录失败保持为空、安装切换失败回滚旧包、成功替换并清理 trash、symlink 逃逸拒绝、无 revision 拒绝。
- `convert/validate_models.py`（重写）：**删除原"吞异常+永远 Validation complete"假通过路径**。真实大小/SHA-256/角色校验；会话加载只算 compat-check；GGUF/STQ MT 返回 unsupported；缺依赖/产物→blocked。退出码 0/1/2/3/4。
- `convert/download_hymt_gguf.py` 删除（被 fetch_model.py 取代，消融：不留两个下载器）。
- `convert/requirements-fetch.txt`（新）+ `requirements.txt` 拆分说明；重型依赖不再阻断 schema/CLI。

### scripts / CI

- `scripts/verify_lib.py`、`scripts/verify`、`scripts/doctor`（新）：04 P02 完整实现。退出码 0/1/2/3/4，多失败优先级 3>1>2，参数错立即 4（覆盖了 argparse 默认 2 的行为）；`--output DIR` 生成 JSON 报告（pass/fail/blocked/skipped + 命令 + 原因 + 时长；skip 不算 pass）；doctor 输出修复命令、不打印凭据。
- `.github/workflows/ci.yml`（新）：Linux（Python+Android gradle 三任务）与 macOS（iOS build+test）分离；actions 锁版本；无模型 PR 不下载权重；设备/模型 runner 未配置则不造假绿灯。

### Gradle（最终集成入口待 A/B 稳定后由我串行执行）

- `android/app/build.gradle.kts`：新增 `syncSharedModelManifests`（`assets/models/*.json`，挂 preBuild）；`preBuild` 同时依赖两个 sync 任务；**按 B4.1 移除 `ui-text-google-fonts`**；测试依赖保持 junit4（A 确认够用，消融掉我先前加的 kotlin-test/coroutines-test）。

### iOS（specs 04 P01）

- `Inference/OrtRuntime.swift`（新）：官方 ORT 真实类型层（`import OnnxRuntimeBindings`；ORTEnv/ORTSession/ORTValue；shape 从运行时读回）。
- `Inference/OnnxModelManager.swift`（重写）：`actor ModelSessionStore` 拥有全部 mutable 推理句柄；`@Observable @MainActor OnnxModelManager` 只承载 UI 状态；honest PackageStatus（draft≠ready）；EP 固定报告 CPU，无 CoreML/ANE 宣称。
- `Inference/TranslationEngine.swift`（新）：协议 + `HyMtTranslationEngine`；无 native runtime 时 `translate` 一律抛 `runtimeUnavailable`，**无 passthrough**。
- `AsrEngine.swift`：真 ORT 调用；删零填充 encoder 回退、空 logits break、`"[N tokens]"` 占位解码、硬编码方言表；tokenizer 缺失→抛错；async 化。
- `TtsEngine.swift`（重写）：按真实 manifest 五角色协议；**删 talker_lm 假接口、零 code 生成、静音回退、零向量 embedding**；无 speaker embedding→抛错（默认克隆关闭）；talker prefill/decode 循环在真实 bundle 存在并验证前显式 protocolMismatch（不盲写假图语义）。
- `PipelineOrchestrator.swift`：插入 MT 阶段（新增 `.translating` 状态、`translationResult`/`translationUnavailable` 事件）；MT 失败→该轮 transcript-only，TTS 只对真实译文执行；删 ASR→TTS 直通与零向量克隆回退；加载改串行（spec 默认单计算 worker）。
- `InterpretViewModel.swift`：删 TTS 完成后 `targetText=sourceText` 的伪译文回填；新增 unavailable 原因字段。
- `ModelRepository.swift`（重写）：v2 manifest 驱动的 Availability（ready 仅当 hash/size/角色全过；draft≠ready）；bundle 提取 manifest 白名单驱动。
- `Audio/AudioRecorder.swift`：AVAudioSession interruption/routeChange 观察者；无权限从"打印后继续"改为抛错。
- UI：ANE/CoreML 宣称删除（InterpretView/SettingsView 显示真实 EP）；方言/语言选择器改用共享 catalog。
- `Data/SharedContracts.swift`（新）：bundle 内共享契约解码。
- `DialectInterpreterTests/SharedContractsTests.swift`（新，Swift Testing）：catalog ID 稳定性、v2 解码、MT revision 固定、draft≠ready、MT 不 passthrough、空输入拒绝。
- `project.pbxproj`：SPM `microsoft/onnxruntime-swift-package-manager` 锁定 `exactVersion 1.24.2`；"Sync shared contracts" script phase（shared/→bundle，input/output paths 显式声明，target 级 `ENABLE_USER_SCRIPT_SANDBOXING=NO`）；新增 unit-test target（TEST_HOST 挂 app）+ scheme Testables。

### 根文件

- `README.md`（重写）：Auralis 定位、共享契约、验证入口/退出码、固定 revision 下载、诚实状态声明。
- `.gitignore`：加 `models/`、`scripts/__pycache__/`、`convert/tests/__pycache__/`。

## 2. 执行过的验证（真实命令与结果）

| 命令 | 结果 |
|---|---|
| `python3 convert/manifest_contract.py check` | exit 0（live 契约全过） |
| `python3 convert/manifest_contract.py check-fixtures` | exit 0（27 用例） |
| `python3 -m unittest discover -s convert/tests` | 25 tests OK |
| `python3 scripts/verify --mode fast` | exit 0（4/4 pass） |

## 2b. 主审复核修复轮的真实检查（最新一轮，覆盖上表对应行）

复核项 #1–#7 修复后的实测（均本机实跑）：

| 检查 | 结果 |
|---|---|
| `python3 -m unittest discover -s convert/tests` | **40 tests OK**（含 manifest_contract 27 fixtures + validate_models 回归 + fetch_model staging 8 例） |
| `python3 -m unittest discover -s scripts/tests` | 5 tests OK |
| `python3 scripts/verify --mode fast` | **5/5 pass**（exit 0） |
| `python3 -m unittest discover -s convert/tests -p test_fetch_model.py` | 8 tests OK：第二文件坏哈希保留旧包、安装失败回滚旧包、symlink 逃逸拒绝、无 revision exit 4、asset-pack manifest 落 `assets/<pkg>/manifest.json` 等 |
| CI `setup-java` SHA | `cf277c60eb25467037889841efdb72551f06f6c3`（主审提供，官方 GitHub API tag ref 核实），“SHA 不可核验”长注释已删 |
| `ort_value.h` v1.24.2 | `shape` 在 `NS_ASSUME_NONNULL_BEGIN` 内为非 optional `NSArray<NSNumber*>*`；`OrtRuntime` 已改为直接 `info.shape.map`，去掉错误的 guard-let |

**未实现 vs 缺产物的区分**（复核要求）：

- 缺产物（如实 blocked，工具链已就绪）：模型文件、完整 Xcode、真机。`verify --scope models` / validate_models / doctor 如实返回 exit 2。
- 未实现（如实标注，需真实 bundle 后才能开发）：TTS/ASR 任务保真 runner（CER/可懂度）、GGUF native runtime 链接（iOS 当前只有 ORT；mt 包 `probeRuntime` 如实返回 `runtimeUnavailable`，不伪装 ready）。
- 本轮已交付实现（非 blocked）：fetch_model 整包 staging（#6）、iOS 统一严格 readiness `PackageVerifier`（#4，单实现，ModelRepository 与 OnnxModelManager 共用；draft/unknown/空 files/缺 pin 永不 ready；分块 SHA-256；包级 staging 安装 + 旧包保护 + 取消保留旧版）、PipelineOrchestrator R01/R02/R03 重写（#5：session/turn 单调 ID、停止次序 stopping→停采集/播放→cancel→join→release→IDLE、启动半失败清理、TTS 失败转 transcript-only、有界队列 2 + DROPPED 可见、CancellationError 不吞）。iOS 侧仍因无完整 Xcode 未编译——CI macos runner 是首个真实验证点。
| `python3 scripts/verify --mode fast --output /tmp/vreport` | JSON 报告生成，exitCode 0 |
| `python3 scripts/verify --scope models --models-dir /tmp/nothing` | exit 2（blocked，如实） |
| `python3 scripts/verify --scope models`（缺参）/ `--mode bogus` | exit 4 |
| `python3 convert/validate_models.py --models-dir /tmp/nothing` | exit 2（blocked） |
| `python3 convert/fetch_model.py --package mt`（本机无 huggingface-hub） | exit 2 + 明确安装指引（未伪造下载） |
| `python3 scripts/doctor` | exit 2：python3/jdk17/android-sdk pass；xcode-full、model-assets **blocked（如实）** |
| `plutil -lint ios/DialectInterpreter.xcodeproj/project.pbxproj` | OK |
| `./gradlew help :app:tasks --offline` + `:app:preBuild --dry-run` | BUILD SUCCESSFUL；preBuild→两个 sync 任务图正确 |

## 3. 未执行 / blocked（不伪装）

- **完整 Xcode 不存在**（xcodebuild 指向 CommandLineTools）：iOS 构建/测试/Swift 测试未在本机执行。pbxproj/scheme/Swift 改动经人工审查 + plutil lint，但**未编译验证**——CI macos runner 或装齐 Xcode 后 `scripts/verify --scope ios` 是首个真实编译验证点。
- **模型产物不存在**（三个 asset pack 空、`models/` 无 bundle）：`verify --scope models`、validate_models 的 compat/task 检查、iOS 真实 ORT 加载、端到端管线全部 blocked。fetch_model.py 已就绪（含官方 sha256 校验链），但按规则不主动下载 GB 级文件。
- **adb 不在 PATH / 无真机**：设备门（05 V02）全部未配置，无性能数字。
- **全量 Gradle 集成**（`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`）：按 handoff 规则等 A/B 文件稳定 + 主审通知后由我串行执行；本轮只做了配置级验证（task 图正确），未编译 A/B 代码。

## 4. 删除的抽象（消融）

- `download_hymt_gguf.py`：与 fetch_model.py 双下载器 → 只留固定 revision 通用入口。
- 未被任何 lane 请求的测试依赖（kotlin-test、coroutines-test）：A 明确 junit4+runBlocking 够用。
- `ui-text-google-fonts`（B4.1 请求 + 离线首屏要求）。
- `TtsEngine.speakerEmbeddingDim` 常量与全部零向量/静音/占位回退路径。
- `validate_models.py` 的 `--no-compat` 旋钮（无调用方）。
- 伪 OrtSession/伪 OrtValue 整层（iOS）。

## 5. 需其它 lane / 主审接入

1. **主审**：A/B 稳定后通知我跑全量 Gradle 集成（唯一负责入口）。
2. **B**：消费 `assets/models/*.json` v2 字段（`syncSharedModelManifests` 已挂 preBuild）；asset pack `manifest.json` 现为 v2（见 interface-C.md §5）。
3. **A**：native runtime 编译时用 mt.json `runtime.runtimeRevision`（llama.cpp pin；核验官方来源前为 null）。`source.revision` 是模型仓库 HF commit SHA，不能当 runtime 依赖用；`fetch_model.py --asset-pack` 可直接为开发构建提供真实 GGUF。
4. **CI**：首次 GitHub 运行将给出 iOS 侧首个真实编译结果；若 SPM 解析超时需调 runner 超时（非代码问题）。
5. TTS/ASR 任务保真 runner（CER/可懂度）仍缺实现，validate_models 如实返回 unsupported——需要真实 bundle 后才能开发。

## 6. 证据说明

- MT revision/sha256 来源：huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF commit 页与 LFS 元数据（2026-09-05 检索）。
- ORT SPM：github.com/microsoft/onnxruntime-swift-package-manager Package.swift（product `onnxruntime`，模块 `OnnxRuntimeBindings`，本机 Context7 不可用，改查官方仓库）。
- 未补造任何 hash/revision/性能数字；所有 blocked 均给出修复命令。
