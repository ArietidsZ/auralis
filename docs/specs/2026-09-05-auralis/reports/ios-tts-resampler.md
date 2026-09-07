# iOS TTS 参考重采样与采样协议修复（2026-09-07）

本轮修改 `TtsEngine.swift`、既有 Sherpa module map，并新增两个真实 Swift 检查脚本。没有新增 DSP 依赖，没有采麦或读取用户录音。

## 参考重采样

删除两点插值 `resampleLinear`，改为 `resampleBandlimited`，直接调用已链接的官方 Sherpa C API。虽然 API 名叫 LinearResampler，实际实现包含低通滤波。单一参数冻结为 **64 零点、cutoff = Float(0.9568718266 × 0.5 × min(inputRate, outputRate))、flush = 1**。运算先用 Double、最后转 Float；不按说话人或采样率分支调参，不声称与 soxr 逐样本相同。

helper 先验证取消、8–192 kHz、非空有限 PCM、最多 30 秒和原生 int32 LCM 限制。同率返回原数组，保留 signed zero 等位模式。异率输出按官方 flush 实现的 `ceil(N × outRate / inRate)` 核对长度，并检查指针与全部样本有限性。Free 和 Destroy 通过 defer 覆盖成功、抛错和取消路径。输入校验/重采样先于 speaker encoder 加载；取消在缓存命中前也检查。

`module.modulemap` 仅追加同目录官方 `c-api.h`，复用原有模块。`build_ios_native` 已启用 C_API、合并 c-api/core 静态归档；root 也在其符号门补入四个重采样 API。本轮对现有 `ios/native-validation-2026-09-07/libAuralisNative.a` 执行 nm，确认 Create、Resample、Free、Destroy 都有真实定义。这是既有 native 验证归档的符号证据，不能替代最终 iPhoneOS 链接。

## 采样协议

- temperature=0 或 topK=1：在重复惩罚和 codec/EOS 掩码后，确定性选择第一个 argmax，不消耗随机数。默认 temperature 仍为 0.9。
- 重复惩罚改为遍历 Set(generated)，每个 token 只处理一次；正 logits 除 penalty，负 logits 乘 penalty。
- softmax 用 Double 先减有限最大值再除温度，避免极小正温度的中间溢出。累计概率尾部舍入只回退到有正概率的 token。
- 温度非有限/负数、topK 负数、NaN/+Inf、全被屏蔽、过短 logits、非法历史 token ID 和非法 penalty 明确抛错。正常掩码 -Inf 允许。
- 引擎两个调用点传播 throws；移除引擎与 sampler 重复执行的 EOS 屏蔽。

## 实际结果

持久目录：`/Users/arietids/Library/Caches/Auralis/reports/ios-tts-resampler/`。

1. `scripts/check_tts_resampler_swift` 提取原样生产 helper，真实编译 Swift 并链接官方 `libsherpa-onnx-c-api.dylib` 执行。DSP 没有 stub。库 SHA256 为 `9e26d7ec53650b622adf0e9d4b16863cbc54016e619d89b70213ecec1dc1af91`，完整命令和源码 hash 见 `dsp/provenance.json`。
2. 16/24/44.1/48 kHz 的 1、2、1001、1 秒、30 秒静音长度全部通过；8 kHz/192 kHz 端点通过。1001 样本首/中/尾冲激峰位分别为 `[0,750,1500]`、`[0,500,1000]`、`[0,272,544]`、`[0,250,500]`。1 kHz 通带幅度比约 0.99993；48→24 kHz 的 18 kHz 正弦衰减 **122.67 dB**。同率逐位相同；非法输入、溢出率比、原生非有限输出、预先取消均按预期拒绝。见 `dsp/checks.json`。
3. `scripts/check_tts_sampling_swift` 原样编译生产 sampler 和 PRNG，在小张量上真实执行。greedy 首位 tie、RNG 不消耗、topK=1、EOS/保留 codec 掩码、正/负重复惩罚、最小正温度、16 项非法输入均通过。temperature=0.9 的 20,000 次抽样频次 `[1517,4581,13902]` 落在对应 softmax 分布容差内。config 只是测试数值容器；没有替代采样算法。见 `sampling/checks.log`。
4. 最新全源 Catalyst 26.5 typecheck **exit 0**，使用真实 ORT headers、Sherpa/HyMT module maps，仅剩既有两个 allowBluetooth 弃用警告，见 `catalyst.log`。

复现：

```sh
python3 scripts/check_tts_resampler_swift --library-dir "$HOME/Library/Caches/Auralis/asr/venv/lib/python3.12/site-packages/sherpa_onnx/lib" --output /private/tmp/auralis-tts-resampler-check
python3 scripts/check_tts_sampling_swift --output /private/tmp/auralis-tts-sampling-check
```

## 选择与消融

重复惩罚消融真实编译运行：history `[2,2,2,3,3]`、scores[2]=2、scores[3]=-2、scores[4]=1.8、penalty=1.05；生产每 token 一次得到 argmax **2**，只把 Set(generated) 改回按次数处理即得到 **4**。见 `sampling/ablation.swift` 和 `ablation.log`。

重采样参数由并行官方前端审计选择：32/64/128 零点和 cutoff 邻点对比后采用 64 及 soxr HQ 通带/阻带边缘的中点；root 回传最终六人最低 embedding cosine 0.9999991133、最大绝对差 0.00168991。该声纹结果属于独立前端审计，本脚本只证明原生 DSP 与边界，不冒称重复运行了六人声纹评估。相应独立证据在 `tts/upstream-fidelity/resampler-cutoff-validation.json`。本实现只保留一个 helper、一组参数和已有依赖。

## 官方依据与范围

Sherpa Context7 查询返回官方 [resampler 文档](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/docs/resampler.dox) 与对象所有权规则；实际长度、flush 和资源释放同时核对仓库钉死版本的 c-api.cc/resample.cc。

Transformers Context7 查询未返回相关段落，随后直接核对官方 [RepetitionPenaltyLogitsProcessor 源码](https://github.com/huggingface/transformers/blob/v4.57.3/src/transformers/generation/logits_process.py)：惩罚每 token 最多一次，正负号分别处理。temperature=0 在本应用 API 中明确定义为 greedy。

本报告阶段没有完整 Xcode iPhoneOS/Simulator 构建、设备性能或音色主观评测。后续已完成当前生产 Swift 中英文两例真实合成、ASR 回转写及取消/无 EOS 验证，见 [当前默认采样真实执行报告](ios-tts-current-runtime.md)。静态符号检查、原生 DSP 执行和全源 typecheck 分别记录，没有互相替代。正常 0.9 参数未变，但修正重复惩罚与稳定 softmax 后，不能承诺旧错误实现的采样序列逐位不变。
