# interface-tts-review（TTS 复核 lane 接口变更与依赖）

2026-09-07，TTS 独立复核者（max）。全部变更限于本 lane 独占文件；给主审/其它 lane 的依赖项在此列出。

## 独占文件内的变更

### convert/tts_runner.py
- 新增 `LANGUAGE_ALIASES` / `normalize_language(value)`：BCP-47 短码 + canonical 名 + auto，返回 bundle canonical key。
- `main()`：依赖检查（exit 2）之后、任何模型 IO 之前验证语言，失败 `parser.error` → **exit 4**（毫秒级）。此前 `zh` 会在 11 s 加载后 exit 1。
- `synthesize()`：进入即归一化（哈希/加载前）；成功报告新增 `"language_requested"`（原始请求），`"language"` 恒为 canonical。失败报告 base 不含成功字段（契约不变）。exit 0/1/2/4 契约不变。

### convert/tests/test_tts_runner.py
- 新增：`test_language_normalization`、`test_exit_4_on_unsupported_language_before_model_load`、`test_language_codes_accepted_at_cli_boundary`（依赖缺失环境自动 skip，与既有模式一致）。plain python3 `OK (skipped=4)`；venv `OK`；门控真实 smoke `OK`。

### android/.../inference/TtsEngine.kt（Android lane 请重跑单测）
- **修复 vocoder 码矩阵转置**：generateFrames 的 flat 数组由 frame-major 改为 group-major（`flat[g*frames+f]`）；逻辑提取为纯函数 `Qwen3TtsProtocol.flattenCodesGroupMajor(allCodes, numCodebooks)`。
- 新增单测 2 个（Qwen3TtsProtocolTest.kt）：group-major 布局断言、ragged frame 拒绝。**需要 `:app:testDebugUnitTest`（Gradle 归 GLM）**。

### ios/DialectInterpreter/Inference/{TtsEngine,OrtRuntime,OnnxModelManager}.swift
- `NpyFloat2D` 支持 1-D npy（bundle 的 projection bias 是 `(N,)`——原实现直接抛错，即从未对真实 bundle 执行过）。
- `TtsEngine.synthesize` 追加带默认值参数（默认=参考行为，调用方零改动）：`temperature=0.9, topK=50, repetitionPenalty=1.05, maxFrames=2048, seed=2026_0906`。`SynthesisResult` 追加 `frames/peak/clippingRatio/group0Tokens`（结构体成员追加，源兼容）。
- `generateFrames`：每帧 `Task.checkCancellation()`（真机 stop 的每步取消点）；logits 非有限值抛 `badOutput`（原先静默采样）。
- **内存**：prefill/decode 会话在每次合成内用毕即释放（`ModelSessionStore.release(role:packageId:)` 新增）；峰值 5.29→3.68 GB，代价 +~2.5 s/回合。
- `OrtRuntime.OrtInferenceSession.init` 追加 `intraOpNumThreads: Int?`；显式线程时同时 `setGraphOptimizationLevel(.all)`。nil 保持 ORT 默认（现网默认路径不变；设备门后建议 pin，host 实测 t4 最优）。
- `ModelSessionStore`：`init(modelsRoot:intraOpThreads:)` + `nonisolated let modelsRoot/intraOpThreads` + `release(role:packageId:)`。
- `OnnxModelManager`：`init(modelsRoot:intraOpThreads:)`、实例 `nonisolated let modelsDir`；**repository 绑定 `store.modelsRoot`**（修复 refreshStatuses 查默认目录而 probe 读注入目录的不一致）。静态 `OnnxModelManager.modelsDir` 语义不变（AsrEngine/TranslationEngine 依赖保留）。MT probe 保留主审的 `OwnedHandle.release()` 用法。
- 全源 Catalyst 26.5 typecheck：**exit 0**（修改后复跑）。

## 给主审/其它 lane 的依赖与提示

1. **ORT 1.24.2 持久产物**（/private/tmp 丢失后恢复）：`$AURALIS_CACHE/ios/`，README.md 有完整表。全源 typecheck 头模块路径：`-I $AURALIS_CACHE/ios/ort-headers`（pod archive sha256 与官方 Package.swift 一致；源 tag 1.24.2 = `b7fb7f7d…`）。请勿重复下载。
2. **TTS bundle 35 文件已恢复并逐哈希核对**：`$AURALIS_CACHE/tts/hf`（root 安装的 cache/models/tts 与此同源）。上游 Qwen 参考仓库副本：`tts/upstream-qwen`。
3. **manifest tts.json**：删除重复 `runtimeRevision` 键；verification 四项均 `unverified`，notes 写明升级证据要求（样本量/CER 阈值/独立 SV/公开用例）。`convert/manifest_contract.py::load_manifest_v2` 通过。
4. **中央 model_tasks 对接**：`--language zh` 现在可用；报告含 `language_requested`/`language`；失败 JSON 仍只含错误字段；exit 4 发生在任何重 IO 前。
5. **Android lane**：TtsEngine.kt vocoder 布局修复 + 2 个新单测需重跑测试与设备验证（转置 bug 在 Android 上同样存在）。
6. 已知局限（不因本次修复消失）：host RTF≈2.3–2.7（未达 ≤1）；克隆质量=独立 SV 聚类证据（合成参考池、n=2），非产品级结论；设备门未开；ICL 模式诚实拒绝（bundle 缺 tokenizer12hz_encode）。
