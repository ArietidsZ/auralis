<p align="center">
  <img src="docs/brand/mark.svg" width="96" height="96" alt="Auralis">
</p>

<h1 align="center">Auralis</h1>

<p align="center">端侧离线方言传译：识别、翻译、用参考声音说话。</p>

<p align="center"><strong>Developer Preview</strong> · <code>v0.1.0-preview.1</code> · <a href="README.md">English</a></p>

Auralis 是原生 Android / iOS 应用，在设备上完成 **ASR → MT → TTS**，不把音频或文本送到网络服务。声音克隆只用你提供的参考录音，没有云端声纹库。

开发者预览包含原生客户端、源码构建与主机/模拟器工程验证。模型包仍处于 draft，质量和实体设备验收继续进行。

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
| 实体手机 | 本次未提供。模拟器/主机数字不是设备上限。 |
| 完整 Xcode / iOS 真机链接 | 需要完整 Xcode 与 iOS SDK。缺工具记 **blocked**，不是通过。 |
| 代表性质量、能耗、热、p95 | 待验证。 |
| TTS 整数量化 / 仅 CP 的 BF16 | 未通过既定音色门，默认保留 FP32。 |
| 已测试的 CoreML code predictor | 比 CPU 更慢，并有数值差异，未进入默认配置。 |
| TTS API2（统一 talker、ICL encoder、流式 vocoder） | 已作为默认 draft 清单；主机与模拟器工程检查，保留 API1 兼容。 |
| GPU / Hy-MT2 路线图 | 待完成。[原路线图](docs/viaim-parity-refactor-plan.md)保留；本预览不代表一期目标达成。 |

模型权重单独获取。TTS API2 从官方 checkpoint 构建后，通过 `fetch_model.py --local-source` 导入；清单不会为派生 ONNX 图伪造上游下载地址。

## 应用就绪

应用不会假装模型已装好。一个包可用需要：

1. Manifest `status` 为 `verified`（当前三个都是 `draft`）。
2. 列出的每个文件都在，且 `sizeBytes`、`sha256` 相符。
3. 运行时角色能指到这些文件。
4. 真实 smoke/任务检查已经跑过。

缺文件时显示仍需安装的体积。没有“下载完成”的假状态。

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
python3 convert/fetch_model.py --package asr   # 只取 manifest 列出的文件
python3 convert/validate_models.py --models-dir "$AURALIS_CACHE/models" --suite SUITE.json
scripts/verify --scope models --models-dir "$AURALIS_CACHE/models" --suite SUITE.json
```

`validate_models.py` 在 draft manifest 或空目录上不会以 0 退出。

## 构建

**Android** — NDK **27.3.13750724**、JDK 17、SDK 35。先编原生库，再 Gradle。原生脚本不下载权重。

```bash
python3 scripts/build_android_native --ndk "$ANDROID_NDK_HOME"
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

**iOS** — 完整 Xcode、iPhoneOS 与 iPhoneSimulator SDK。主机/Catalyst 类型检查不等于 iOS 链接。见 [iOS 原生构建](docs/ios-native-build.md)。

```bash
python3 scripts/build_ios_native
python3 scripts/verify --scope ios
```

原生检出与产物缓存：`$AURALIS_CACHE`（默认同上）。不要把该目录提交进 git。

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

应用代码：MIT。模型：各自上游许可。第三方 notice 随发布资产提供（由发布者补全）。

仓库：[ArietidsZ/auralis](https://github.com/ArietidsZ/auralis)。发布说明：[v0.1.0-preview.1](docs/releases/v0.1.0-preview.1.md)。
