# Experimental streaming vocoder: fixed interface

2026-09-07。已真实导出、ORT **1.24.2 CPU** 执行通过；还不是出货 manifest 的一部分。不得据此宣称移动端延迟或质量门通过。

## 固定产物

目录：`/Users/arietids/Library/Caches/Auralis/tts/upstream-fidelity/streaming-onnx/`

| 文件 | bytes | SHA-256 |
|---|---:|---|
| `vocoder_streaming.onnx` | 409014 | `3138ede6fb908e72eec4ee5904bd3f158cdcca4241caa7fdd0e5112f0aa38a36` |
| `vocoder_streaming.onnx.data` | 456219776 | `80e961291971c0c3e3aee657f4b4ab40eb7c264f3aae90a6233d68f2b9ca0ba7` |

两文件保持同目录、原文件名。标准 ONNX opset **18**，FP32 权重，无新增 execution provider/custom op。所有 state output 的形状均已标注为下列固定形状。

来源：官方 Qwen3-TTS 源码 `022e286b98fbec7e1e916cb940cdf532cd9f488e`；官方 HF revision `5d83992436eae1d760afd27aff78a71d676296fc`；speech-tokenizer `model.safetensors` SHA=`836b7b357f5ea43e889936a3709af68dfe3751881acefe4ecf0dbd30ba571258`。BF16 checkpoint 转 FP32，与既有 FP32 ONNX vocoder 数值对拍；没有改参数值或量化。

## 一次 step 的真实 I/O

| 名称 | 方向 | dtype | shape |
|---|---|---|---|
| `codes` | input | int64 | `[1,16,F]` |
| `conv_state` | input | float32 | `[135232]` |
| `past_keys` | input | float32 | `[8,1,16,71,64]` |
| `past_values` | input | float32 | `[8,1,16,71,64]` |
| `position` | input | int64 | `[1]` |
| `waveform` | output | float32 | `[1,1,F*1920]` |
| `conv_state_out` | output | float32 | `[135232]` |
| `present_keys` | output | float32 | `[8,1,16,71,64]` |
| `present_values` | output | float32 | `[8,1,16,71,64]` |
| `position_out` | output | int64 | `[1]` |

Batch 固定为 1。`codes` 必须是 group-major，含所有 16 个 codebook 的完整声码帧；沿末轴追加时间帧。只传有效音频 codes，不传 codec EOS。`F>0`；已验证 chunk1/4/8/13 及不足块长的正数尾块。

每个新 utterance 四个 state 全零。`position` 表示已经消费的 codec 帧数，首步是 0，输出=`position+F`。调用端沿用生成器的帧预算，不把位置计数当毫秒/PCM 样本数。

成功 step 后：

1. 全部 `waveform[0,0,:]` 可提交，24 kHz，共 `F*1920` 样本；相当于每 codec 帧 80 ms。
2. `conv_state_out`→下一步 `conv_state`；`present_keys/values`→下一步 `past_keys/values`；`position_out`→下一步 `position`。
3. 保留下一步状态后释放旧状态；不得把输入和输出绑定到同一块内存，除非另有经验证的运行时 alias 协议。当前普通 ORT tensor 路径已验证，不要求 I/O binding。

持久状态 **5,193,992 bytes**：卷积 540,928 + K/V 4,653,056 + position 8。一次推理期间输入/输出可能同时存活，这不是全部运行时峰值内存。Conv 内部 packed layout 见 `validation.json::conv_state_layout`；客户端无需逐层解释。

## 尾部、取消、重置

- 尾块 F 小于常规 chunk 时照常 step。EOF **无额外 PCM**；不调用 F=0，不补零码，不重复历史音频，不 overlap-add。丢弃余下 state 即结束。
- graph 无隐藏跨调用状态。取消时使用对应 runtime 的终止机制；失败 step 的 waveform/state 全部丢弃，不提交部分输出。新句 state 全零、position=0。
- 可复用同一 session；不同 utterance 的 state 不混用。回收模型时释放 session 与所有 state tensor。
- 原官方 full decoder 自身裁掉的转置卷积右尾也不应在 flush 时额外输出。

## 已执行的验证

- 81 帧对官方完整 PyTorch、chunk1/4/8/13：maxdiff≤3.88e−6。
- 最终图 108 帧/8.64 s，越过 72 帧窗口 2.88 s：对既有完整 ONNX maxdiff≤1.38e−6，RMS≤8.19e−8；每步 state 字节恒定、尾块与总长度正确。
- 异步 `RunOptions.terminate` 返回明确终止异常；清零重置后首块 maxdiff约2.3e−9。
- 40 次同 session 取消式丢弃/重置：所有输入输出数组 weakref 均释放，RSS 第5–40次保持约648 MB。
- session wrapper 可释放且 RSS 下降约219 MB；allocator 保留内存另报，未把它解释为全部内存归零。

验证文件：`validation.json`、`long-validation.json`、`cancel-reuse.json`、`lifecycle.json`。长窗由真实 codes 重复构造，仅用于状态边界压力测试。没有真机/TTFA≤1.5 s 结论；不要用首段几乎静音的 PCM 冒充可听首音。

## 冻结代码与重建

`convert/export_tts_streaming_vocoder.py` SHA=`49d01414a421fe2d99a740044ec445ee12d3402c440db399882eb3f1e8f1ec76`；`convert/verify_tts_streaming_onnx.py` SHA=`d935a56b0fb6246afaf58779e6a73881a6ea0ca87590882453bc6a094770e06f`。缓存 `streaming-onnx/frozen/` 保存副本。

```sh
tts_root="$HOME/Library/Caches/Auralis/tts"
tts_python="$tts_root/venv/bin/python"
PYTHONPATH="$tts_root/upstream-fidelity/deps:$tts_root/optimization/ort124:$tts_root/upstream-fidelity/Qwen3-TTS" \
  "$tts_python" "$tts_root/upstream-fidelity/streaming-onnx/frozen/export_tts_streaming_vocoder.py"
PYTHONPATH="$tts_root/optimization/ort124" \
  "$tts_python" "$tts_root/upstream-fidelity/streaming-onnx/frozen/verify_tts_streaming_onnx.py"
```

固定版本：torch2.14.0、transformers4.57.3、accelerate1.12.0、onnx1.20.1、onnxruntime1.24.2。导出使用 `dynamo=False`/opset18；实验脚本可替换同目录候选文件，复建前另存要保留的候选。当前客户端尚未接入，依据以上实际 I/O 设计即可，不需要改动现有 full vocoder 接口作为先决条件。
