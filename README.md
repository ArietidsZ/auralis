# 跨方言声音克隆同声传译 Android App

基于 Qwen3-ASR / AngelSlim Hy-MT / Qwen3-TTS 的端侧离线传译应用。项目包含：

- `android/`：Android 客户端（Kotlin + Compose + ONNX Runtime）
- `convert/`：模型导出、量化、验证、基准工具（Python）

## 当前状态

- Android 端：`assembleDebug` 与 `lintDebug` 均可通过。
- 推理链路：已具备 ASR -> MT -> TTS 端到端管线、并发背压控制、模型完整性校验入口。
- 模型语义质量：已从纯占位升级为 tokenizer 参与的解码/编码流程，但仍建议结合官方 tokenizer/config 持续校准。

## 主要能力

- 22 种中文方言/口音选项 + 多语言源/目标选择
- AngelSlim/Hy-MT1.5-1.8B-1.25bit 机器翻译接入边界
- 声音档案录制与回放
- ONNX Runtime + NNAPI 执行提供者（NPU 优先，CPU 回退）
- Play Asset Delivery 安装时模型包
- 全离线运行（模型落盘后）

## 项目结构

```text
.
├── convert/
│   ├── export_asr_onnx.py
│   ├── export_tts_onnx.py
│   ├── validate_models.py
│   ├── benchmark_onnx_runtime.py
│   └── requirements.txt
└── android/
    ├── app/
    │   └── src/main/java/com/dialect/interpreter/
    │       ├── inference/
    │       ├── audio/
    │       ├── data/
    │       └── ui/
    ├── asset_pack_asr/
    ├── asset_pack_mt/
    ├── asset_pack_tts/
    └── gradlew(.bat)
```

## Android 构建

### 环境要求

- JDK 17
- Android SDK：Platform 35、Build-Tools 34+/35、Platform-Tools
- Windows 下建议设置：`ANDROID_HOME`

### 构建与检查

Windows:

```bat
cd android
gradlew.bat :app:assembleDebug :app:lintDebug
```

Linux/macOS:

```bash
cd android
./gradlew :app:assembleDebug :app:lintDebug
```

输出 APK：`android/app/build/outputs/apk/debug/app-debug.apk`

## 模型导出与验证（开发机）

```bash
cd convert
pip install -r requirements.txt
python export_asr_onnx.py
python export_tts_onnx.py
python download_hymt_gguf.py
python validate_models.py
```

`download_hymt_gguf.py` 会把 `Hy-MT1.5-1.8B-1.25bit.gguf` 写入
`android/asset_pack_mt/src/main/assets/mt/` 并生成 manifest。生产 APK 还需要在
`android/app/src/main/jniLibs/arm64-v8a/` 放入 `libhymt_jni.so`，供
`NativeHyMtRuntime` 调用真实 Hy-MT GGUF runtime。

### ONNX Runtime 基准

```bash
python benchmark_onnx_runtime.py --models-dir ../models --providers CPUExecutionProvider
```

结果输出到：`convert/benchmark_results.json`

## 设计说明（关键实现选择）

- 音频链路：200ms 录音分块 + 能量平滑 VAD + 音频时钟判句
- 并发模型：采集 / ASR / Hy-MT / TTS 分阶段协程 + 有界通道背压
- 稳定性：模型提取采用原子写入，支持 manifest 大小/哈希校验
- 安全默认：禁用备份与明文流量（`allowBackup=false`, `usesCleartextTraffic=false`）

## 已知限制

- 当前 TTS speech code 生成已可解析 ONNX 输出，但仍应按目标模型结构进一步做严格对齐（特别是多 codebook 自回归策略）。
- ASR/TTS 真实效果依赖你提供的导出模型与 tokenizer 版本一致性。
- Hy-MT 翻译阶段需要真实 native GGUF runtime；若未打包 `libhymt_jni.so`，应用会在启动同传时明确提示 runtime 缺失，而不会使用假翻译。
- 若升级到更新 AndroidX（需要 AGP 8.9+ / compileSdk 36），需整体升级 Gradle/AGP 工具链。

## License

- 模型：按对应上游模型许可
- 应用代码：MIT
