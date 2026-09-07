# Python ICL runner 复核与证据冻结

2026-09-07。独占范围：只读审查 `convert/tts_runner.py` 与其 tests；未改该文件、未改 sampling/模型、未改 manifest/`model_tasks`/fetch/Kotlin/Swift。`reports/tts-icl-review.md` 是官方 PyTorch 功能筛选，不是本 runner 交付。前一笔记 `tts-runner-icl.md` 未冻结执行源码文件。本文件以 cache 原始 JSON/WAV/NPY 与本轮复跑为准。

结论：**当前源码满足显式 ICL runner 契约；原 ORT1.24.2 四例质量/数值证据可核对；当前源码对那五条 WAV 逐字节复现。不晋升产品质量。** 未发现必须改 runner 的 bug。

## 源码契约（当前文件）

当前 `convert/tts_runner.py` SHA-256=`1ee436353f1bffd3657ec1571bd45be59db275555398e5d4394dd968874cb655`。

- CLI 默认 `--conditioning-mode xvector`；ICL 必须同时给非空 `--reference-text` 与 `--reference-encoder`。xvector 附带任一 ICL 参数 → argparse exit 4。不会推断或伪造转写。
- 缺参数/混传：exit 4（依赖与模型 IO 前）。缺 encoder graph/data 或其它产物：exit 2。错误 role、静音参考、执行失败：exit 1。失败 JSON 无成功音频字段。
- 提前准备：`load_reference_audio`、`encode_reference_codes`、`build_reference_prompt_ids`、`build_icl_prompt`。`generate_codes(..., reference_token_ids, reference_codes)` 两者必须同时有。
- 准备门：finite float32 mono 24 kHz、1024 样本..30 s、peak≥1e-4。DSP `resample_reference_audio` 仍接受零信号。直接 API 的 reference codes 限制 1–375 帧（30 s）。
- 采样未改：默认 temperature=.9、topK=50、penalty=1.05 且 `set(generated)` 每 distinct token 一次。`on_frame` 只送 target 帧的 `frame.copy()`。
- `decode_waveform` 先把 reference+target codes 送完整 vocoder，再裁 `R×1920`，不按原录音样本数裁。官方 `generate_icl_prompt`（本地 Qwen3-TTS `modeling_qwen3_tts.py`，`non_streaming_mode=False`）使用 `ref_ids[:,3:-2]` 与 `text_ids[:,3:-5]`；runner 的 wrapper/slice 与此一致。

## 原始 runner-validation（未覆盖）

目录：`~/Library/Caches/Auralis/tts/icl/runner-validation/`。四例 ICL 报告均为 python3.12.9 + **onnxruntime 1.24.2** + seed 20260906 + `.9/50/1.05`。`results.json` 记录当时 `runner_sha256=15e956c1c82c9030c12b75e439422103201dd2b6c475d999214c34dcf553641e`。该字节文件不在 cache/git；只放松 30 s codes 上限无法复原该 hash。

| 用例 | R | target帧 | 秒 | WAV SHA-256 |
|---|---:|---:|---:|---|
| 121 en ICL | 106 | 37 | 2.96 | `9ceac31bf9e041678d13bfed7021ad915394f302cb45546f3497a4bceef9f3c6` |
| 121 zh ICL | 106 | 38 | 3.04 | `1b24ccb7493553573e5eca7ebfded146baa71f457af71852f7cc5f4ce81f3b8c` |
| 260 en ICL | 88 | 31 | 2.48 | `3f3517d88919505a49387862804130dcc786e11c4298a4969938145e31fad4cb` |
| 260 zh ICL | 88 | 31 | 2.48 | `c6de2e6e9a52126e69c2d0058264df74450cf62743d3eef91269f0d8923b0ac4` |

本轮对已存文件重算（不重跑 ASR/身份/官方 vocoder 图）：

- 各例 `codes.npz` 的 reference 与 `icl/reference-{id}-codes.npy`.T **逐值相同**；当前 `validate_reference_codes` 接受 88/106 帧。
- 捕获 prefill vs 官方 `prefill-{id}-{lang}.npz`：maxdiff=`3.337860107421875e-06`（121）、`2.980e-06`/`2.861e-06`（260）；`attention_mask`/`position_ids` 完全相同。trailing maxdiff=`7.15e-07`（verification.json）。
- `full-context-pcm.npy` 长度 = `(R+T)×1920`。verification 中 runner 返回的 float32 target 与 `full[R*1920:]` maxdiff=`0`。写出的 WAV 为 PCM16，相对该 float 裁剪约 `3.05e-05`（1/32768），不能把 WAV 当成 float 逐样本。
- 已存 `official-context-pcm.npy` vs ONNX full 的 target 裁剪：max `8.452305337414145e-06`（260 en），RMS≤`1.73e-07`；121 裁 203520 不是原录音 203040；260 裁 168960。与 `official-vocoder.json` 一致。该次日志有 `sox: command not found` 与 flash-attn 警告，四例仍写完。
- callback：verification 四例 `target_callback_copies_independent=true`，次数=target 帧。
- 默认 xvector 121 en：WAV SHA=`d6df3f57df35dfc14ab15976f644f2c388d6b7a8b4b0e035294f25e833b27c43`，与冻结 `protocol-fixed-sampled/fp32/121-english.wav` **整文件相同**；PCM16 逐样本相同。
- `asr.json`（schema=`convert/eval_tts_optimization.py asr`）：4/4 正文精确，CER=0。命令行未另存，按输出 schema 还原。
- `identity.json`（schema=`convert/bench_tts_icl.py score`）：gallery 六人 121/237/1221/61/260/672，4/4 top1 正确。`paired=[]` 因为 `results.json` 只有 ICL 例。own cosine：121 en `.59767`、zh `.44662`；260 en `.39989`、zh `.24788`。
- 负例 `failures/summary.json` + `failures.log`：missing-text 4；missing-encoder 2；missing-data 2；wrong-role 1；silent-icl 1；silent-xvector 1。六例都无成功 wav。混传/缺参数的 exit 4 在 unittest，不在该脚本。

Encoder graph/data SHA 仍为 `4294aacf…` / `528902f2…`（461120 / 190069504 bytes）。

## 当前源码绑定（必要复跑）

因执行 hash 与当前文件不同，在不覆盖原目录的前提下，用当前 runner + ORT1.24.2 overlay 重跑 4 ICL + 1 xvector → `runner-validation/current-source-bind/`。五条 WAV SHA 与原证据 **全部相同**（`bind.json` `ALL_EQUAL`）。生成数学在这些用例上等价；未重跑 ASR/身份。

## 测试

本轮实跑，不是沿用旧“27 项”口头结果。

```
env -u PYTHONPATH "$HOME/Library/Caches/Auralis/tts/venv/bin/python" convert/tests/test_tts_runner.py -v
# 3.12.9；Ran 27 tests in 1.420s OK (skipped=1 smoke 环境变量未开)
# 该进程 import 到的是 venv ORT 1.29.0；协议测试不跑真实图

env -u PYTHONPATH /opt/homebrew/bin/python3.14 convert/tests/test_tts_runner.py -v
# 3.14.7 缺 tokenizers/soundfile/librosa/soxr；Ran 16 tests in 0.627s OK (skipped=6)
```

带 `PYTHONPATH=.../ort124` 跑 unittest 会让 `-S` 的 MissingDependency 子进程仍能 import overlay，不是本套件的用法。

## 冻结

`~/Library/Caches/Auralis/tts/icl/runner-validation/freeze/`：

- `source/`：当前 `tts_runner.py`、`test_tts_runner.py`、三个 verify 脚本
- `logs/unittest-venv312.txt`、`unittest-py314.txt`
- `provenance.json`：源码/依赖/命令/产物 SHA
- 绑定输出：`../current-source-bind/bind.json`

真实 ICL 命令形态（verify 入口；原 case 日志只有 JSON 没有 argv）：

```sh
tts_cache="$HOME/Library/Caches/Auralis/tts"
PYTHONPATH="$tts_cache/optimization/ort124" "$tts_cache/venv/bin/python" \
  convert/tests/verify_tts_icl_runtime.py 121 english
```

依赖（overlay）：python 3.12.9、onnxruntime **1.24.2**（文件在 `tts/optimization/ort124/onnxruntime`）、numpy 2.5.3、tokenizers 0.23.2、soundfile 0.14.0、librosa 1.0.0、soxr 1.1.0。venv 默认 ORT 是 1.29.0，不能无 overlay 复现合成。

## 边界（给 root）

- 未接入 API2/中央 validator/移动 engine/streaming sink。参考仍走完整 vocoder，有重复解码成本。
- n=2 说话人、单 seed，不是产品分布；host 证据不是实体机。
- 执行版 `15e956c1…` 文件仍缺失；已用当前源码把五条 WAV 绑死。
- 官方 ICL 质量筛选见 `tts-icl-review.md`，勿与本 runner 交付混写。
