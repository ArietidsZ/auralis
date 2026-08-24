# cross-dialect-communication

当前主方案（2026-08-24，**v3.1**）：

**[Auralis → Viaim 功能平齐重构方案](docs/viaim-parity-refactor-plan.md)**

### 拍板摘要
- **品类**：手机离线方言双向同传（不是 Viaim 纪要克隆）
- **Must**：引擎可听（**GPU+，优先 NPU**）→ `face_to_face` → 听译 → 全离线 → 克隆路径
- **ASR 候选**：**Fun-ASR-Nano-2512（0.8B）** ↔ **Qwen3-ASR-0.6B**；量化未锁；禁止 SenseVoice/Zipformer 冒充主推
- **加速**：CPU-only = degraded，不当卖点
- **MT**：HY-MT 当代可落地；**TTS**：Qwen3-TTS
- **通话双轨录音**：Play 应用基本红灯，勿当卖点

代码实现仍可暂缓。历史稿：`docs/superpowers/` · 工程分支：`baseline/auralis-android-overhaul`
