# continuation-lane-A — 真实运行时交付（MT native host + Android arm64）

2026-09-06。本报告只覆盖本 lane 独占范围的改动：`android/app/src/main/cpp/hymt_jni/**`、
`android/app/src/main/jniLibs/`、`ios/DialectInterpreter/Inference/TranslationEngine.swift`
与新增 native MT 桥接文件。未执行任何 git reset/stash/commit/push；其他 lane 的
staged/unstaged 改动保持原样（见 §9 并行冲突记录）。

## 1. 固定 checkout 与模型（真实下载与校验）

- llama.cpp PR #22836 pinned commit `1e411d8f5a1e23525fa3265dfb4bd76265465397`
  ("ggml-cpu : fix STQ1_0 CI failures")：
  `git fetch --depth 1 origin 1e411d8f5a… && git checkout --detach FETCH_HEAD`，
  落地 `/private/tmp/auralis-runtime-20260906/llama-stq`，`git rev-parse HEAD` 复核一致。
- 模型 `AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF@1bed36c0a8f5a0eddf77987b02ba66d4c268aca2`
  下载到 `/private/tmp/auralis-models/mt/`，461,860,704 字节，
  `shasum -a 256` = `93e025c93cc082e73a3f142b757623a8b9cf541c020a8013ca4ee669556860ab`
  与 mt.json 声明逐字一致。
- NDK 27.3.13750724 (r27d) 由 root 安装至
  `/opt/homebrew/share/android-commandlinetools/ndk/`，`source.properties` 已核。

## 2. 真实兼容问题一：STQ quant-type id 漂移（已修复，非重量化）

首跑真实加载失败：`tensor 'blk.0.attn_k_norm.weight' has offset 203154464,
expected 203277344`。逐层取证：

1. 解析 GGUF 头（v3，354 tensors，32 KV）：只有三种 type id：0(F32)×129、
   14(Q6_K)×1、**42**×224。pinned 枚举 `GGML_TYPE_STQ1_0 = 43`，42 是 `Q2_0`。
2. PR 历史（fetch PR head 后 `git log`）：`5503c4b`(STQ_0) → `5165daa`(更名 STQ1_0)
   → `1e411d8`。三个 revision 的 `block_stq{,_1_0}`（qs[QK_K/8]+sign[QK_K/32]+d，
   42B/256）与 codebook/逆向表**逐字节相同**；且三个 revision 中 STQ 都是 43
   —— 上游主线在此期间插入了 Q1_0=41/Q2_0=42。故 HF 产物出自更早的 fork 枚举
   （缺 Q1_0，STQ=42）。
3. 数据布局验证：按 pinned type sizes 顺序重算 354 个 tensor 偏移，与文件记录
   **0 处不符**；`token_embd`（Q6_K 1680B/行）与总大小亦吻合 → 纯 id 漂移，
   位打包语义未变。
4. 修复 `tools/fix_stq_type_id.py`：校验偏移后仅改写 224 个 4 字节 type-id
   字段（42→43），数据区零改动。
   - 原始 sha256 `93e025c9…`（保留为上游凭证）
   - 修复后 sha256 `e42935e2c143be4c579109ef3a096b0b00796af2527eaa09be76331832d4c971`

## 3. 真实兼容问题二：jinja chat template（已修复）

修完 id 后模型可加载但输出乱码/复读。用 pinned `llama_chat_apply_template`
渲染出的 prompt 为 `…原文<｜hy_User｜>`（用户标记在内容之后、缺
`<｜hy_Assistant｜>`）——legacy 解析器破坏了该 jinja 模板。官方 model card 明确
使用 `--jinja`。改用 pinned checkout 自带的 jinja 引擎
（`common_chat_templates_init/apply`，`llama-common` target）：

- 无 system prompt（官方模板）；ZH↔XX 用中文指令、其余方向用英文指令、
  上下文轮用官方 contextual 模板。
- tokenize `add_special=false`（jinja 产物已含 `<｜hy_begin▁of▁sentence｜>` BOS 标记）。

修复后主审保留的 token sizing/RAII/PIC/取消语义全部保留：`hymt_tokens.h` 仅增加
`add_special` 默认参数（负返回 sizing 逻辑未动）；`CMAKE_POSITION_INDEPENDENT_CODE`
不变；cancel 置位持续到 release，translate 遇置位即 `HYMT_ERR_ABORTED`
（含 prefill 中途 abort——llama_decode 失败时先查 abort 标志再报 FAILED，
该分支由 Swift smoke 真实触发并验证）。

## 4. C ABI（最小接口，JNI 与 Swift 复用）

`hymt_core.{h,cpp}`：`hymt_load / hymt_translate / hymt_cancel / hymt_release /
hymt_free_string / hymt_runtime_revision`。

- 错误码 4 值：`OK / INVALID / ABORTED / FAILED`，err 缓冲写诊断。
- 句柄纪律：load/translate/release 每句柄内部互斥（release 在锁内释放
  model/ctx、出锁后 delete）；cancel 无锁原子置位；release(NULL) 幂等。
- UTF-8：入口逐字节校验，非法输入返回 INVALID，不做"修复"。
- `hymt_jni.cpp` 退化为纯 JNI 转换层（real-UTF8 字节数组路径、异常不过 JNI、
  错误→IllegalStateException，消息与旧版逐字一致，Kotlin `AbortedException`
  映射不受影响）。host 语法检查：
  `clang++ -fsyntax-only -I $JDK23/include{-,/darwin} -I llama-stq/include
  hymt_jni.cpp` → `JNI SHIM SYNTAX OK`。

## 5. 构建（真实产物）

- Host（macOS arm64，Metal off，CPU）：
  `cmake -S cpp/hymt_jni -B build-host -G Ninja -DLLAMA_CPP_DIR=…/llama-stq
  -DHYMT_HOST_BUILD=ON -DGGML_METAL=OFF -DGGML_NATIVE=ON -DCMAKE_BUILD_TYPE=Release`
  → `libhymt_core.dylib`（7,374,400 B）+ `hymt_smoke`。
- Android（NDK r27d，arm64-v8a，minSdk 29）：
  `-DCMAKE_TOOLCHAIN_FILE=…/android.toolchain.cmake -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-29 -DGGML_METAL=OFF -DGGML_NATIVE=OFF`
  → `libhymt_jni.so` strip 后 **8,056,976 B**，
  sha256 `576d5b19bfcca9230117f10b13f96daccf2b8fe6a3b959aedbf71071e137184c`。
  `llvm-readelf`：AArch64 DYN，NEEDED 仅 `liblog/libm/libdl/libc`；
  `llvm-nm -D` 导出 4 个 `Java_com_dialect_interpreter_inference_NativeHyMtRuntime_*`
  符号，与磁盘上（另一 lane 重建中的）`NativeHyMtRuntime.kt`
  `System.loadLibrary("hymt_jni")` 及 external fun 完全对应。
  已拷贝至 `android/app/src/main/jniLibs/arm64-v8a/`，Gradle 自动打包，无项目
  配置改动。
- CMake 对 `LLAMA_BUILD_COMMON`/`LLAMA_BUILD_EXAMPLES` 置 ON（jinja 引擎在
  `llama-common` 内），不构建任何 example/server 二进制。

## 6. 真实 smoke（真实模型，非 fixture）

C smoke（`tests/hymt_smoke.c`，exit 0，SMOKE PASSED）实测输出：

```
zh→en: Let’s go to the museum for a visit this afternoon, okay?
en→zh: 你好，最近怎么样？
invalid UTF-8 rejected with HYMT_ERR_INVALID
null handle rejected
```

Swift host smoke（`tests/run_swift_host_smoke.sh`，用 swiftc 编译 **iOS 侧
`HyMtNativeBridge.swift` + `TranslationEngine.swift`** 与 `main.swift`，链接同一
dylib；SMOKE PASSED）：

```
ok: native load (real GGUF, CPU EP)
zh→en: Let’s go to the museum for a visit this afternoon, okay?
en→zh: 你好，最近怎么样？
ok: engine isAvailable with manifest-resolved real model
engine zh→en: Thank you for your help.
ok: mid-flight cancel maps to CancellationError
ok: translate after cancel returns HYMT_ERR_ABORTED (persists until release)
```

host 延迟（Mac arm64 CPU，4 线程，不含加载）：372/794/252 ms 三句。
iOS always-unavailable MT 已被真实实现替换：`HyMtTranslationEngine` 现在
`isAvailable` 走真实加载探测，translate 经 `withTaskCancellationHandler` 调
native abort（取消→CancellationError；abort 持续到 unload），失败按
`runtimeUnavailable/generationFailed/backendMismatch` 报类型化错误，永不回传
原文。构造函数保留 `init()`（lane B `InterpretView`、lane C 测试不需改动）；
`canImport(OnnxRuntimeBindings)` 守卫生产默认 modelsDir，host smoke 注入
manifest/modelsRoot。

## 7. 并行冲突记录（只读，未干预）

Gradle `:app:compileDebugKotlin --offline` 当前失败（`Unresolved 'TtsEngine'`
等），全部位于其他 lane 正在改写的 `AppContainer/ModelRuntimeProbe/
PipelineOrchestrator/TtsEngine`；git 状态显示 `HyMtTranslationEngine.kt/
NativeHyMtRuntime.kt/TranslationEngine.kt` 有 staged 删除 + 未跟踪重建，属主审
/root 的进行中重构。本 lane 未触碰这些文件、未恢复旧版；JNI 符号已对最新
`NativeHyMtRuntime.kt` 核验一致（§5）。

## 8. 消融（Occam）

- 放弃 dlopen 动态发现：直接链接 + `@_silgen_name`（少一层间接，失败模式相同）。
- 放弃 bridging header（`hymt_bridge.h`）方案：需要 pbxproj 改动；`@_silgen_name`
  零配置，语义以 `hymt_core.h` 为唯一真源。
- 放弃重量化（llama-quantize 重出 BF16→STQ）：布局验证证明纯 id 重映射等价，
  避免额外量化损失与 5 分钟级产物生成。
- 放弃在 host smoke 里编译 OnnxModelManager（主审提供 ORT headers 后验证过，
  传递依赖拖入 ModelRepository 等全量代码）：native 桥接证明不需要它，生产分支
  交由主审 Catalyst 全项目类型检查。
- 保留 4 值错误码而非逐项错误枚举：调用方（JNI/Swift）映射面最小。

## 9. 边界与移交

- `.so` 未上真机：`adb devices` 0 台（final-report 已记录），设备 smoke 仍是
  产品门，本报告的"已验证"仅限 host CPU。
- `GGML_NATIVE=OFF`（Android）为保守通用 armv8-a 代码；dotprod/i8mm 特化属于
  设备性能门实验，未在无设备条件下臆开。
- 加载期日志 `token_embd … cannot be used with preferred buffer type
  CPU_REPACK, using CPU instead` 为 llama.cpp 对该量化组合的正常回退（量化张量
  用非 repack CPU buffer），不影响正确性。
- 修复版 GGUF（sha `e42935e2…`）是本地衍生产物；`shared/model-manifests/mt.json`
  的 sha256 仍是上游 LFS 原件哈希。若 PackageVerifier 要对修复版做文件级校验，
  需要 lane C 决定如何记录（建议：manifest 增补 fixed 文件条目或在
  runtime.notes 记录 remap 流程），本 lane 未改 shared/。
- `mt.json runtime.runtimeRevision` 当前 null；实际使用并验证的 revision 为
  `1e411d8f5a1e23525fa3265dfb4bd76265465397`，是否回填由 lane C 定。

## 10. 主审合入清单（Gradle/Swift 项目配置）

1. iOS target：把 `ios/DialectInterpreter/Inference/HyMtNativeBridge.swift`
   与 `TranslationEngine.swift` 纳入编译（文件系统同步组通常自动覆盖）。
2. iOS target 链接 `libhymt_core`（host 产物在
   `/private/tmp/auralis-runtime-20260906/build-host/`；iOS 静态库需以
   `-DANDROID…` 对应的 iOS SDK 交叉构建，命令同 §5 Android 行，换
   toolchain/SDK，本机无完整 Xcode 未执行）。
3. Android：无配置改动（jniLibs 自动打包）。
