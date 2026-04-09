# convert 工具链说明

本目录用于模型导出、量化、验证和基准测试。

## 文件说明

- `export_asr_onnx.py`：导出 Qwen3-ASR ONNX，并尝试 INT4 量化
- `export_tts_onnx.py`：导出/下载 Qwen3-TTS ONNX，并做量化处理
- `validate_models.py`：对导出结果做可用性验证
- `benchmark_onnx_runtime.py`：对 ONNX 模型做加载和推理延迟基准

## 环境准备

```bash
pip install -r requirements.txt
```

## 推荐流程

```bash
python export_asr_onnx.py
python export_tts_onnx.py
python validate_models.py
python benchmark_onnx_runtime.py --models-dir ../models --providers CPUExecutionProvider
```

## 基准输出

`benchmark_onnx_runtime.py` 默认输出 `benchmark_results.json`，包含：

- 模型加载耗时（`load_ms`）
- 推理平均耗时（`avg_ms`）
- 推理中位数（`p50_ms`）
- 推理 P95（`p95_ms`）
- 输入 shape 与 dtype

## 注意事项

- 量化/导出依赖上游模型结构；模型版本变化可能导致导出代码需要同步调整。
- 建议每次替换模型后都执行一次 `validate_models.py` + `benchmark_onnx_runtime.py`。
