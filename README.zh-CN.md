<p align="center">
  <img src="docs/brand/mark.svg" width="96" height="96" alt="Auralis">
</p>

<h1 align="center">Auralis</h1>

<p align="center">端侧离线方言传译：识别、翻译、用参考声音说话。</p>

<p align="center"><strong>Developer Preview</strong> · <code>v0.1.0-preview.1</code> · <a href="README.md">English</a></p>

Auralis 是原生 Android / iOS 应用，在设备上完成 **ASR → MT → TTS**，不把音频或文本送到网络服务。声音克隆使用你提供的本地参考录音。

[下载 Android 签名预览版](https://github.com/ArietidsZ/auralis/releases/download/v0.1.0-preview.1/Auralis-v0.1.0-preview.1-arm64-v8a.apk) · [发布文件与校验和](https://github.com/ArietidsZ/auralis/releases/tag/v0.1.0-preview.1)

Developer Preview：原生客户端、源码构建、主机/模拟器检查。权重单独配置；模型包保持 `draft`，App 就绪门仍关闭，质量与真机验证尚未完成。

## 本预览已有内容

- 方言与模型包的共享契约（`shared/`），Python、Kotlin、Swift 共用。
- 原生 Android（Kotlin/Compose）与 iOS（SwiftUI）客户端。
- 离线链路：Qwen3-ASR 家族 → AngelSlim Hy-MT → Qwen3-TTS 家族。
- 声音参考存储与克隆。没有参考文本时只走 speaker embedding，应用不会编造转写。
- 移动端 ASR 与 TTS 共用一份官方 ONNX Runtime **1.24.2**。
- 主机与 Android 模拟器上的真实图执行。见 [工程报告](docs/specs/2026-09-05-auralis/reports/)。

## 验证状态

| 项目 | 状态 |
|---|---|
| 共享 ASR/MT/TTS manifest | 仍为 **`draft`**。draft 包不能当作 App 已就绪。 |
| 实体手机 | 本次未提供。主机/模拟器数字只是工程检查。 |
| CI | Android / iOS 原生构建与测试均已通过；运行记录见[发布验收](docs/releases/verification-v0.1.0-preview.1.md)。 |
| 质量、能耗、热、p95 | 未完成。 |
| TTS 整数量化 / 仅 CP 的 BF16 | 未通过既定音色门，默认保留 FP32。 |
| 已测试的 CoreML code predictor | 比 CPU 更慢，并有数值差异，未进入默认配置。 |
| TTS API2（统一 talker、ICL encoder、流式 vocoder） | 已作为默认 draft 清单；主机与模拟器工程检查，保留 API1 兼容。 |
| GPU / Hy-MT2 路线图 | 待完成。[原路线图](docs/viaim-parity-refactor-plan.md)保留；本预览不代表一期目标达成。 |

模型权重单独获取。TTS API2 从官方 checkpoint 构建后，用 `fetch_model.py --package tts --dest DEST --local-source LOCAL_SOURCE` 导入；清单不会为派生 ONNX 图伪造上游下载地址。

## 应用就绪

一个包可用需要：

1. Manifest `status` 为 `verified`（当前三个都是 `draft`）。
2. 列出的每个文件都在，且 `sizeBytes`、`sha256` 相符。
3. 运行时角色能指到这些文件。
4. 真实 smoke/任务检查已经跑过。

缺文件时显示仍需安装的体积。

## 检查（不需要权重）

```bash
scripts/doctor                 # 环境；缺 Xcode/模型记 blocked
scripts/verify --mode fast     # 契约、Python、Swift 核心 — 不下载模型
```

退出码：`0` 通过，`1` 测试/代码失败，`2` 缺环境或产物（blocked），`3` 契约无效，`4` 参数错误。

磁盘上已有模型时：

```bash
export AURALIS_CACHE="${AURALIS_CACHE:-$HOME/Library/Caches/Auralis}"   # macOS 默认
# Linux/Android 主机一般用 $HOME/.cache/Auralis

python3 convert/manifest_contract.py check
# 要用 $AURALIS_CACHE 必须显式 --dest；默认是 <repo>/models
python3 convert/fetch_model.py --package asr --dest "$AURALIS_CACHE/models"
python3 convert/validate_models.py --models-dir "$AURALIS_CACHE/models" --suite SUITE.json
scripts/verify --scope models --models-dir "$AURALIS_CACHE/models" --suite SUITE.json
```

`validate_models.py` 在 draft manifest 或空目录上非 0 退出。`--suite` 可选；`--scope models` 必须带 `--models-dir`。

官方 ASR 测试 WAV、声音参考录音和捕获的张量**不在** git。本地备齐后：

```bash
python3 scripts/prepare_android_test_assets --samples-dir /path/to/samples
```

可选的 code-predictor 捕获张量：

```bash
python3 scripts/prepare_android_test_assets --samples-dir /path/to/samples --cp-inputs-dir /path/to/bf16_cp_inputs
```

报告里的 `$AURALIS_CACHE/...` 是未入库的本地证据。

## 构建

**Android** — NDK **27.3.13750724**、JDK 17、SDK 35。先编原生库，再 Gradle。原生脚本不下载权重。

```bash
python3 scripts/build_android_native --ndk "$ANDROID_NDK_HOME"
( cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug )
```

**iOS** — 完整 Xcode、iPhoneOS 与 iPhoneSimulator SDK。主机/Catalyst 类型检查不等于 iOS 链接。见 [iOS 原生构建](docs/ios-native-build.md)。

```bash
python3 scripts/build_ios_native
python3 scripts/verify --scope ios
```

原生检出与产物在 `$AURALIS_CACHE`。

## TTS API2（实验）

可复建的 FP32 包（统一 talker、参考 encoder、流式 vocoder）记录在：

- [TTS 下一版包](docs/specs/2026-09-05-auralis/08-tts-next-package.md)
- [API2 包编译](docs/specs/2026-09-05-auralis/reports/tts-api2-package-2026-09-07.md)
- [API2 运行时接口](docs/specs/2026-09-05-auralis/reports/interface-tts-api2-runtime.md)

共享 `tts.json` 已选择 API 契约 **2**，状态仍为 draft。原 API1 清单保存在 `shared/legacy-model-manifests/`，用于兼容验证。

## 目录

```text
shared/     方言目录 + v2 manifest（Python、Kotlin、Swift）
convert/    清单校验、固定获取、模型验证
scripts/    doctor、verify、原生构建
android/    Kotlin 客户端
ios/        Swift 客户端
docs/       构建说明、规格、报告
```

## 许可

应用代码：Apache-2.0。模型：各自上游许可。第三方 notice：[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

仓库：[ArietidsZ/auralis](https://github.com/ArietidsZ/auralis)。预定 tag 页：[v0.1.0-preview.1](https://github.com/ArietidsZ/auralis/releases/tag/v0.1.0-preview.1)。说明：[docs/releases/v0.1.0-preview.1.md](docs/releases/v0.1.0-preview.1.md)。
