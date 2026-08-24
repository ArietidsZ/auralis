# cross-dialect-communication

当前主方案（2026-08-24，**联网研究优化版 v2**）：

**[Auralis → Viaim 功能平齐重构方案](docs/viaim-parity-refactor-plan.md)**

### 本轮拍板（摘要）
- **品类**：手机离线方言双向同传（不是 Viaim 纪要耳机克隆）
- **Must**：引擎可听 → `face_to_face` → 听译时间线 → 全离线 → 克隆路径
- **ASR**：流式 Zipformer 草稿 + Qwen3-0.6B 精修
- **MT**：Hy-MT Q4_K_M + mainline llama.cpp（不做 Phase1 死磕 1.25bit）
- **TTS**：Piper 先听得见；克隆后置
- **通话双轨录音**：Play 电话应用基本做不到，勿当卖点

实现仍可暂缓；工程细节见 `baseline/auralis-android-overhaul` 分支。历史设计稿在 `docs/superpowers/`。
