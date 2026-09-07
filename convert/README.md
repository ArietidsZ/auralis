# 模型工具

共享清单是模型来源、文件哈希、角色和运行时约束的唯一入口。所有出货包当前为 draft；工具不会通过运行一次样本自动改成 verified。

- `manifest_contract.py check` / `check-fixtures`：标准库契约检查、三端共享fixtures、v1迁移。
- `fetch_model.py`：固定HF revision或完整哈希的release归档；文件白名单、包锁、staging、回滚。MT的已审计STQ转换显式记录输入和输出；不执行清单提供的任意命令。
- `asr_runner.py`：真实sherpa Qwen ASR，完整WAV预检、逐文件读取、保留分段前后输出、CER/WER/RTF。
- `mt_runner.py`：真实Hy-MT C ABI，检查运行库revision并保留每条译文。
- `tts_runner.py`：真实Qwen TTS图，支持语言码与名称、参考声音、生成波形、模型hash与统计。
- `bench_tts_streaming.py`：用真实生成帧驱动实验状态声码器，与整段PCM对拍并记录各块可用时刻；不播放音频，不改变出货清单。
- `validate_models.py` / `model_tasks.py`：校验实际runner全部输入，再启动新的独立推理进程。旧报告不能替代本次执行。缺质量限值或独立身份验证不计通过。

```sh
python3 convert/manifest_contract.py check
python3 convert/manifest_contract.py check-fixtures
python3 convert/fetch_model.py --package asr
python3 convert/fetch_model.py --package mt
python3 convert/fetch_model.py --package tts
python3 convert/validate_models.py --models-dir models --suite SUITE.json \
  --runner-python asr=/path/asr-venv/bin/python \
  --runner-python tts=/path/tts-venv/bin/python \
  --mt-library /path/libhymt_core.dylib --json-report reports/models.json
```

`--local-source DIR`校验导入现有文件；`--archive FILE`复用带完整来源哈希的归档；`--asset-pack`创建Android开发资产包。`--compat`是可选ONNX会话兼容检查，不等于任务或质量通过。

依赖分开安装：轻量契约/单测只用标准库；HF获取用requirements-fetch.txt；ASR和原始TTS参考环境分别用requirements-asr-runtime.txt、requirements-tts-runtime.txt。MT推理只需已编译原生库，chrF++打分需要在验证器Python中安装sacrebleu 2.5.1。端侧ORT 1.24.2与Python参考环境分开记录，导出/量化环境独立使用。

套件格式、退出码与晋升边界见 [真实运行时规格](../docs/specs/2026-09-05-auralis/06-real-runtime-verification.md)。物理设备性能、长期稳定性和代表性语音质量不能用这些主机smoke替代。
