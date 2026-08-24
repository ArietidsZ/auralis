# cross-dialect-communication

当前主方案（2026-08-24，**v3.2**）：

**[Auralis → Viaim 功能平齐重构方案](docs/viaim-parity-refactor-plan.md)**

### 拍板摘要
- **品类**：手机离线方言双向同传
- **加速**：**发版 GPU；NPU 二期**；CPU-only = degraded
- **ASR 候选**：Fun-ASR-Nano-2512（0.8B）↔ Qwen3-ASR-0.6B；量化未锁
- **Must**：GPU 可听 → `face_to_face` → 听译 → 全离线 → 克隆
- **MT / TTS**：HY-MT · Qwen3-TTS

代码仍可暂缓。工程分支：`baseline/auralis-android-overhaul`
