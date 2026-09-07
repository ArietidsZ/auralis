# Python TTS API2 runner 与中央检查

2026-09-07。root 在 `tts_runner.py` 原生成器中加入 API2 分支：单一 talker 的空 past 预填与后续 decode，直接消费 stacked KV；状态 vocoder 在生成器每4帧回调里执行。API1默认及原显式ICL接口保留，未改变采样数学。API2显式 `--api-contract-version 2`，ICL仍须 `--conditioning-mode icl --reference-text`，encoder固定来自包内，外置encoder覆盖拒绝。

`model_tasks` 已识别 API2 完整图和external-data布局，suite可声明真实referenceText，逐案核对API版本/模式/参考文本/所有图摘要。不能拿API1清单为外部实验ICL图背书。CLI成功仍不晋升模型。

## 真实检查

证据 `~/Library/Caches/Auralis/reports/python-tts-api2/`。独立实验目录使用已冻结talker/encoder/vocoder和旧社区FP32 speaker/CP；CP仅重排到外部存储，完整nodes和initializer dtype/dims/raw bytes逐一相同。来源逐文件写入 `origins.json`。这不是官方单源包；未写shared manifest、未发布。保存方式依据 [ONNX 官方文档](https://github.com/onnx/onnx/blob/main/docs/ExternalData.md)。

ORT 1.24.2，官方121/260参考FLAC，中英4案默认采样：37/38/31/27帧，全EOS。与已评分的组合流式基线（相同输入与后端）相比，PCM最大绝对误差分别6.05e-7、3.62e-6、1.16e-6、1.06e-6，callback输出无重复尾段。

maxFrames=5先回调7680样本（4帧），再因未EOS失败；不写成功WAV、不flush失败尾块。原样保留输出和源码快照。生成墙钟含流式vocoder与sink背压，报告已明确，不把两个重叠计时相加作推理性能。

Python测试最近一次128项通过、6项显式跳过（无相应真实依赖或环境的门控项）；当前版本新增API2约束测试；API1真实CLI回归已补跑：121英文xvector 40帧、ICL 37帧，两个WAV与冻结基线逐字节相同。证据为上述目录的api1-regression/，源码和argv均归档。中央完整官方API2包任务待compiler产物，未用实验目录冒充。
