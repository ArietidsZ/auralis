# 真实运行时与验收增补（2026-09-07）

本增补承接 01–05 specs。模型实际执行与质量验收分开；所有包目前仍为 draft，不从主机测试反推设备已验证。

## 可复现来源

HF 文件使用 source.repoId + 完整 source.revision。GitHub release 模型使用 source.archive（HTTPS URL、归档 SHA-256、精确大小、stripPrefix）；release 标签不是 git commit。归档先验整体哈希，再只复制清单声明的普通文件；路径穿越、链接、清单内文件的重复成员被拒绝；清单外普通文件不安装。Python/Kotlin/Swift 对这些字段及重复 JSON 键采用一致规则。

MT 只允许一个已审计转换 source.transform：hymt-stq42-to43-v1。它记录原始文件名与 93e025… 输入哈希；files 记录 e42935… 的运行时产物、精确大小和 translator 角色，runtimeRevision 固定 1e411d8… 。这不是重新量化；完整张量数据不变。程序不执行来自 manifest 的任意命令。获取工具从原始文件实际转换并验证输出后，与 manifest 一起切换；错误保留旧包。

持续证据放在 ~/Library/Caches/Auralis，不能只保留在 /private/tmp。暂停期间临时目录丢失过一次，已按上述哈希重建。

## 统一执行入口

```sh
python3 scripts/verify --scope models --models-dir /path/models \
  --suite /path/suite.json \
  --runner-python asr=/path/asr-venv/bin/python \
  --runner-python tts=/path/tts-venv/bin/python \
  --mt-library /path/libhymt_core.dylib --output /path/reports
```

套件包含 schemaVersion=1、name、purpose（smoke 或 benchmark）及 asr/mt/tts.cases。case id 不得重复，音频/参考声音相对 suite 文件解析。没有接收旧报告代替执行的入口。固定的三个 runner 在独立进程运行，超时清理进程组，所有执行参数、日志、输出和哈希留档。

- ASR case：id、audio、reference。参数 segment（on/off/both）、numThreads、maxTotalLen、可选 vadModel。记录所有样本，同时报告 macro/corpus CER、WER、推理时长及 RTF。原始大小写/标点保留；CER 去空白、WER 按空白切词。缺失参考不能从平均值中排除。
- MT case：id、text、sourceLanguage、targetLanguage、reference、可选 context。记录真实译文、延迟、模型/库指纹；标准 SacreBLEU chrF++ 和签名只作其中一项指标，不能代替否定、数量、专名和上下文语义审查。
- TTS case：id、text、language、referenceWav。参数 numThreads、maxFrames。检查实际非静音 PCM，使用已校验 ASR 包重新转写合成结果。当前单 case 子进程的 RTF 包含冷加载和参考声音处理，明确标识；不能冒充应用热会话性能。可懂度不证明说话人身份一致。

质量限值必须在比较新候选前固定：ASR maxCer/maxRtf（可选 maxCaseCer）、MT minChrf/maxLatencyMs、TTS maxRoundTripCer/maxRtf。没有限值时仍可以执行并报告，但质量门 blocked。当前探索套件未擅自依据得到的分数倒设合格线。

文件门必须覆盖 runner 实际读取的文件：当前 ASR 6 文件、TTS 35 文件及固定角色/外部数据，不能只提供一个无关文件骗过哈希检查。持有包读租约贯穿完整验证，安装器不能在校验与执行之间换包。额外 generic ONNX session 检查仅通过 --compat 启用，不满足任务门。

退出码：0 所选门通过；1 实际失败；2 缺环境/未验证/缺参考或限值；3 契约不合法；4 参数错误。JSON 保留不同状态。draft 永远不能退出 0。

## 已有消融决定

1. Qwen ASR 自动识别优于默认强制语言前缀，在德语、中文绕口令、粤语的成对实测成立。App 默认 auto；用户选定的源语言独立传给 MT。底层不填 language 就显示 unknown，提示语言不充当检测结果。
2. 280 次 MT 输出和 6 次 CLI/native 核对支持保持 greedy、默认不传上下文。官方上下文模板也会泄露历史；官方采样在 STQ 上出现禁止指令反转。Q4_K_M 是进一步评估候选，不能用 14 个病例直接替换或声称领先。详见 benchmarks/mt-quality-review.md。
3. iOS 的 ASR/MT 静态合并库使用单一 ORT 1.24.2 已通过真实主机推理/符号检查。Android 单 ORT 1.24.2 已通过两个加载顺序、真实 ASR、speaker encoder、完整 ASR→MT→TTS 链路，删除第二份 ORT 和隔离改名。各自保留真机/完整 iOS 链接验收门。
4. 原生 MT 删除有 TOCTOU/ABA 风险的全局 live-set；句柄所有者负责取消与释放互斥，操作锁保证真实调用完成后释放；取消保持到释放。Swift 使用真实 Clang 导入，共享 C ABI 声明。
5. iOS 不再把复制结束标为模型就绪；界面消费统一的真实探测状态，支持导入模型目录和部分组件。ASR 就绪后允许转写，缺 MT/TTS 明示降级。取消导入保留原包。字体使用原生 Dynamic Type。

6. TTS 参考音频和采样协议按官方 PyTorch 复核，修正两点插值及按重复次数累计的 token 惩罚。后续量化和流式选择遵循 [07-tts-runtime-optimization.md](07-tts-runtime-optimization.md)，旧协议结果只保留作历史对照。

## 尚未满足的生产门

完整 Xcode 的设备/模拟器构建、实体设备峰值内存/热稳态/能耗/p95、代表性且独立保留的质量集、TTS 独立说话人一致性、权限/路由/无障碍实机验收。Catalyst 类型检查、主机 smoke、模拟器推理各自记录，不能互相替代。
