# TTS 协议、内存与流式增补（2026-09-07）

承接 06，不重置现有包契约。生产清单保持 draft；量化和流式产物先进入独立候选目录，不能覆盖已校验的原始权重后继续沿用原哈希。

## 已确认的基线

单 ORT 1.24.2 的 Android APK 已在同一模拟器进程执行 ASR→MT→TTS，7.2 秒合成音频经设备及独立主机 ASR 回转写均与输入译文一致。这只验证执行和一条可懂度样本。此前该段合成耗时 27.155 秒、进程 VmHWM 约 4.59 GiB，未通过实时性或实体手机内存门。

Android 的 ART 堆耗尽来自 NPY 小表在堆上累计分配。选择既有只读 mmap 路径覆盖所有表；不提高 largeHeap 掩盖分配问题。硬链接只减少磁盘重复数据，不能宣称减少 ORT 权重的运行时分配。

## 参考声音

- 固定官方 Qwen speaker encoder、前端和 codec 权重来源。24 kHz 相同 PCM 的 mel/embedding 对拍、同 codes 的官方 PyTorch/ONNX waveform 对拍均已通过，原始导出并非本轮声纹差异的来源。
- Python 参考用 `librosa.resample(..., res_type="soxr_hq", fix=True, scale=False)`。保留其版本与输入哈希；24 kHz 输入不重采样。
- 两移动端复用现有 sherpa 的带低通滤波重采样。单一参数：`num_zeros=64`、`cutoff_hz=0.9568718266 * 0.5 * min(in_rate, out_rate)`、最后一块 `flush=1`。这里 LinearResampler 是带滤波内核的名称，不是删除的两点线性插值。
- 比例来自已加载 libsoxr HQ 公共 API 的通带结束 0.9137436533 和阻带开始 1 的中点。先用不含说话人的冲激频响选择，再用六个未参与选择的声音验证。64→128 零点没有收益；不引入按说话人或采样率分支。
- 输入为有限单声道 PCM、8–192 kHz、最长 30 秒；检查原生长度/整数边界。输出长度为 `ceil(N*out/in)`，释放全部原生返回对象；同率输入先检查再原样返回。取消不能变成成功结果。
- sherpa 与 soxr 不宣称逐样本相同。六人 16 kHz 参考的最终声纹余弦最小值约 0.9999991，是前端数值保真证据，不是克隆音质分数。

## Codec token 生成

官方 Qwen 将 repetition_penalty 交给 HF GenerationMixin；HF 按不同 token 各施加一次惩罚。三端对历史去重后处理正负 logits，禁止按出现次数叠乘。用重复 history 改变旧 argmax 的小张量作回归，不能只检查无异常。

`temperature=0` 或 `top_k=1` 使用第一个最大值，不消耗随机数。正温度的 softmax 先减最大值再除温度；拒绝 NaN、正无穷或没有有限候选的 logits，保留正常屏蔽 token 的负无穷。默认采样策略的改变需要单独消融，受控 greedy 量化实验不能冒充当前默认采样质量。

未达到 codec EOS 就耗尽帧预算时返回失败，不能把截断音频标为完成。Android 在每帧和每个 codebook 前检查取消；正在执行的原生调用完成后再释放其会话。iOS 保持对应的取消及释放顺序。

## 量化选择

FP32、INT8 和 INT4 使用相同输入、前端、解码协议及 ORT 1.24.2。每轮在运行前固定样本、候选和门槛，保存实际 runner 副本/哈希。重复惩罚修复前的结果单独归档，修复后全部重跑。

质量同时报告 ASR 可懂度与独立 speaker verifier。六位公开数据集说话人使用不同 reference/held-out 录音，包含同性负例；另报告自然 reference→held-out 的分数，不能将分类通过等同于自然声音相似度充分。所有失败保留。小集通过仅准入 Android 候选实验，不晋升生产。

设备实验使用不同目录或受控换包，核验每个 graph/external data 的摘要，比较真实输出、内存、热/冷时间及取消。每次恢复后重新核验生产产物；不把候选权重留在旧清单名下。只有同一协议下的非退化候选才进入可复建包流程。

## 流式候选

现有整段 vocoder 可数值保留因果 Conv 输入缓存、注意力 KV 与位置计数。独立固定左侧重叠窗口拼接已出现可观测波形差异，拒绝该方案。

先交付实际 ONNX 状态图，在 ORT 1.24.2 验证 chunk 1/4/8/13、跨窗口长输入、最后 flush、输出边界和内存上界。对拍必须包括全部连续 PCM，不能只比较非静音、采样数或 g0 tokens。退出阶段原生错误也计为失败。

图协议确定后再接客户端有界音频队列及播放器。记录第一段真正可听语音的时间和整段实时因子，安静的首批 codec 帧不算可听延迟。提前播放后的失败应保留为部分完成/失败，不能继续宣称完整合成成功。

## 依据与证据

- [librosa 重采样参数](https://librosa.org/doc/0.10.2/generated/librosa.resample.html)，当前实际环境版本另随报告记录。
- [sherpa 重采样 C API](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/docs/resampler.dox)，构建使用仓库固定 revision 的同名 API。
- [HF repetition penalty 实现](https://github.com/huggingface/transformers/blob/v4.57.3/src/transformers/generation/logits_process.py)。Qwen 固定源码及数值对照保存在 `~/Library/Caches/Auralis/tts/upstream-fidelity/`。
- 原始量化与后续修正见 `reports/tts-optimization-2026-09-07.md`；Android 独立回转写见 `~/Library/Caches/Auralis/reports/android-chain-independent-asr.json`。
