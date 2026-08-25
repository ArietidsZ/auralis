# cross-dialect-communication

当前主方案（2026-08-25，**v3.4**）：

**[Auralis → Viaim 功能平齐重构方案](docs/viaim-parity-refactor-plan.md)**

### 拍板摘要
- **加速**：发版 **GPU**；**NPU 二期**；CPU-only = degraded
- **ASR**：Fun-ASR-Nano-2512（0.8B）↔ Qwen3-ASR-0.6B
- **MT**：Hy-MT2-1.8B。**一期 2bit+GPU（~573MB）**；**目标 1.25bit（~440MB，等 STQ-GPU）**
- **TTS**：Qwen3-TTS
- **Must**：GPU 可听 → `face_to_face` → 听译 → 全离线 → 克隆

工程分支：`baseline/auralis-android-overhaul`
