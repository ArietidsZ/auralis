# cross-dialect-communication

当前主方案（2026-08-24，**v3**）：

**[Auralis → Viaim 功能平齐重构方案](docs/viaim-parity-refactor-plan.md)**

### 拍板摘要
- **品类**：手机离线方言双向同传（不是 Viaim 纪要克隆）
- **Must**：引擎可听 → `face_to_face` → 听译 → 全离线 → 克隆路径
- **ASR（已锁）**：**Qwen3-ASR-0.6B**（FunASR SenseVoiceSmall 作更小备选）；**量化未锁**；禁止旧栈主推
- **MT**：HY-MT 当代可落地方案（Q4_K_M 可运行；更激进量化跟上游）
- **TTS 主路径**：**Qwen3-TTS**（工程降级必须标明，不得写成推荐）
- **通话双轨录音**：Play 应用基本红灯，勿当卖点

代码实现仍可暂缓。历史稿：`docs/superpowers/` · 工程分支：`baseline/auralis-android-overhaul`
