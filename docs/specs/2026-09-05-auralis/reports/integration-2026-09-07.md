# 2026-09-07 集成记录（进行中）

23 小时暂停到期于 02:41 UTC 后恢复；保留原 git 索引和三条独占任务。
临时目录全部丢失，SDK 保留。真实模型与运行环境按来源哈希恢复到
`/Users/arietids/Library/Caches/Auralis`。路径入口：`../runtime-cache.json`。

## 已实跑

- 官方 ASR TAR 878702423 B，SHA-256 `393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96`。
- 新 `source.archive` 契约支持精确归档大小/哈希/目录前缀；Python/Kotlin/Swift 同步；fetch 只提取白名单普通文件，拒绝穿越、链接、重复路径，文件与清单原子切换。六文件已实际安装，draft 不变。
- 官方 wheel 域名 TLS 失败后，使用镜像同版本 wheel；sherpa-onnx 与 sherpa-onnx-core 两个摘要均与官方 PyPI 元数据一致。证据 `asr/wheel-provenance.json`。
- 中央 `validate_models` 实跑全部 15 条官方样本，参考没有遗漏；不传 VAD 时使用明确的 energy 分段。
  未分段 macro CER 0.2276；分段 macro CER 0.1348453；corpus CER 659/3173=0.2076899；corpus WER 0.3489583；分段计算 RTF 0.1778364，297.768秒输入。两种路径及模型加载的进程总时长 91.172秒。不能与独占机器/真机时延直接比较。
  证据 `reports/asr-central-validation.json` 及其记录的本轮新 runner JSON/hash。退出 2：draft 且尚未固定质量晋升限值；不因这次分数反向设置合格线。
- Swift 会话 32/32 通过：显式用户来源独立于缺失的 ASR language 元数据；ASR 默认自动识别；auto 不伪造中文；保留停止/join/release/重启覆盖。
- Python 旧 validator 7 个防假通过回归、新任务边界 11 个、新归档 I/O 5 个通过。

## 复核发现

- 固定 sherpa Qwen 后端剥离 language 前缀但不填 result.lang。真实包的 version/git_sha1/onnxruntime_version 是字符串属性，已实际检查（1.13.7 / 917bed95 / 1.28.1）。
- Swift 无效 fixture 旧测试未移除 $expectedError，且固定传 asr packageId，可能只触发注释/包名错误而掩盖真实字段缺陷。现在按本身 packageId、移除注释直接验证原始值，重复 JSON 键用原始字节单独测。
- MT 第一轮独立复核仍有 live-set 检查与释放之间的 UAF 窗口、cancel 语义漂移、缺文件后永久缓存 nil、ja→zh 错误语言等。已交回修复；第一轮报告不作为最终批准。

## 待验收

中央 MT 派生产物获取与 TTS 真 runner 集成、独立 MT 第二轮、Android 真进程/双 ORT/ELF、TTS Swift 原生执行/性能消融、全源 Catalyst 类型检查、完整 iOS 构建和物理设备指标。host/模拟器不代替生产性能。

## 语言提示消融

本轮官方 de/raokouling/cantonese，逐样本比较 auto、BCP-47、canonical 名称（同一 pinned recognizer/greedy/2线程）。auto CER 0 / 0.05405 / 0.08654；canonical 名称 0.01087 / 0.14865 / 0.09615；BCP-47 0 / 0.08784 / 0.11538。默认自动识别在这三例均不更差，移除 UI 到 ASR 的默认强制提示，只将显式源语言用于 MT。不能从三例推断所有语言，但足以拒绝无收益的默认强制提示。证据 `reports/asr-language-hints.json`。
Swift host 第一轮失败在“强制 German 应与 auto 参考完全相同”的错误前提：实际只是 Raptorium→Rapturium，一字符差异。修正测试为 code→canonical 真实输出一致，默认 auto 仍严格对照原始参考。保留旧失败 log 于 swift-asr/step-2.log，复跑用独立输出目录。

## 后续真实结果

- Swift ASR host 9 项检查通过：官方 de.wav 精确转录、unknown/无 confidence、canonical 别名、NaN拒绝、读租约、取消与重复释放。输出 `reports/swift-asr-2/checks.json`。
- Swift MT 最新调用锁/取消锁与加载租约顺序已真实编译、smoke 全过，记录 `reports/mt-swift-final`。C ABI取消保持到release，无全局live-set。
- 统一 fetch 从原始MT文件实际执行 STQ 转换，随后安装cache/models/mt；不是手工改清单跳过来源。TTS35文件也已经完整hash校验安装cache/models/tts，仍draft。
- Catalyst26.5全源类型检查 exit0（Audio Bluetooth API 两条弃用警告），记录 `reports/catalyst-typecheck-3.log`。后续改用Dynamic Type字体，需最终类型复验。
- iOS Setup原有复制后 modelsReady=true 已删；按真实 ASR readiness路由，新增目录导入，部分组件缺失如实降级；设置页新增管理模型入口。
- Codex原生Astra完成中央门控审查修复：实际6/35输入布局+全量hash，TTS输出与图指纹核对，报告/进程故障门控。任务边界19、validator7、archive5测试通过。全convert109用例通过（2个明确的可选真实产物测试跳过，不作模型验收）。
- MT独立280输出+6个CLI/native核对完成；两个模型的上下文会泄漏，STQ官方随机采样会反转禁止指令。默认greedy/无context已有应用代码支持，继续保留。详见benchmarks/mt-quality-review.md。Q4是较大候选，未宣布生产替换。
- iOS单ORT1.24.2真实主机编译/ASR3样本与合并native单符号通过；XCFramework构建脚本和CI设备链接入口已接。完整Xcode缺失仍exit2，无假产物。

## 中央 all 首轮失败（保留证据）

`verify --scope models` 经当前统一入口实际完成 ASR2例、MT11例，TTS因CLI不接受共享语言码 zh 而exit1；该失败已交TTS owner修统一入口。未把“不支持语言码”改成pass，原报告留在cache/reports/central-all。预期修复：短码与canonical名在重加载前归一，非法参数exit4，保存请求字段用于一致性核验。

## 量化与真实设备集成进展

独立TTS量化在ORT1.24.2实跑：INT4 accuracy_level=4协调窗口RTF从1.59/1.62降至0.76/0.77，常驻双session峰值5.24→2.04GB；INT8候选并发筛选RTF.941/RSS3.248GB。36/36输出有限、零削顶、自然终止，量化ASR回转写均与FP32相同。INT4声音相似度下降，INT8另一个难例分类失败；未晋升质量或修改模型产物清单。正在核对官方PyTorch前端/声纹协议。
Android单ORT1.24.2已在真实APK验证双顺序ASR/TTS speaker图、质量与模型readiness，release API改为显式suspend并真实等待dispose，删除Main线程daemon造成的生命周期缺口。全图TTS首次遇到ART192MiB heap耗尽，原因是小于64MB的NPY表堆分配。已授权复用既有mmap覆盖所有只读NPY，不加largeHeap；正在真实全图与整条模型链测试。详android-ort-unified报告及后续集成报告。
ASR1024/384/45秒候选完整15例corpusCER.1487551，比512.2076899少187字符错，代价约450MiB RSS；2048/95秒一次内存失败不报完整均值。移动默认保持原策略，候选需要设备预算验证。逐文件runner已正式移除整批浮点音频常驻，15例输出完全不变。

## 中央入口复跑

`cache/reports/central-all-2`：ASR2例、MT11例、TTS短句及其ASR回转写均实际执行成功，0 fail；TTS回转写CER/WER均0。总exit2保留draft、未固定限值、独立speaker门。该TTS冷进程10.826秒/1.92秒音频=5.64 RTF，包含每次哈希/模型加载，和优化实验的热会话RTF分别报告。runner源码SHA随每次新执行记录，TTS请求language_requested与canonical同时核对。
`verify` wrapper已修正本项目子工具exit3/4传播，普通编译器同数值仍算build failure；10个script测试及真实无效suite入口exit4通过。

## 结束标记与可重建环境

TTS Python/Swift已修复帧预算耗尽仍返回成功的问题：未codec EOS则拒绝截断音频，Python成功报告记录termination=codec_eos。真实图maxFrames=1返回exit1且未写wav成功产物，证据cache/reports/tts-budget-rejection.json。Android同一修复已交其独占集成lane。
原始已实跑主机依赖版本分别固定在convert/requirements-asr-runtime.txt和requirements-tts-runtime.txt，和端侧ORT1.24.2隔离，不再要求为实际推理安装无关导出工具链。

## 当前协议修复与真实整链结果

Android单ORT当前生产图在同一模拟器进程执行de.wav→ASR→MT中文→TTS真实译文，输出7.2秒。设备和root独立主机回转写均与译文一致（CER/WER 0）；合成27.155秒，VmHWM约4.59GiB。证据cache/android-ort-unified/device-final-evidence/full_translation_chain.json、full_chain_zh.wav及cache/reports/android-chain-independent-asr.json。该结果属于修正重复惩罚之前的完整链路，不能作为之后源码的最终验收。

官方前端审查已定位并替换两点参考插值。Python用官方librosa soxr_hq；移动复用已有sherpa带滤波内核，64零点及HQ通/阻带中点比例0.9568718266。独立六人16k输入的embedding对官方最小cos约.9999991；相同24k PCM的speaker及相同codes的codec PyTorch/ONNX对拍也已通过。没有新增第二DSP运行时。

root进一步发现三端重复token按频次累罚，而官方HF只罚一次。三端现已去重处理，温度0/topK1确定性argmax、非法logits拒绝，原报告与runner副本冻结。新协议greedy在121跨语言短句触发384帧noEOS，失败保留；不回退错误协议。实际默认.9/topK50的首位说话人中英×三候选六例均EOS，完整六人池正在复跑，之前36/36结果是历史对照而非新协议质量结论。

iOS声音档案复核完成：真实core、2000次权限取消状态测试、adapter控制流与全源Catalyst通过；详ios-voice-review.md。新Swift原生重采样/采样小张量检查、Catalyst也通过，详ios-tts-resampler.md；真实Swift全图新协议执行另进行中。

当前verify --mode fast为8检查pass/0fail；报告cache/reports/current-fast-20260907。TTS真实依赖单元19项通过、1项跳过；跳过项需要显式模型参数，独立真实图执行另记，不算该测试通过。

root重新查看MT sanitizer旧日志并实际复现ggml.c:7317空指针加96的UBSan fatal。CMake现用校验过的ggml.c生成构建目录副本，单行替换成uintptr地址计数，原pinned checkout未改、未关sanitizer。正在真实ASan/UBSan重编译复验，尚未替换APK中的MT库。

## 最终现有协议与下一版实验（继续执行）

Android现有协议验收：141 unit、构建/lint、6个so的ELF/ZIP16KB通过；最终MT70d04…库和带resampler的单ORT1.24.2均已实际运行。新协议完整链路是7.6秒PCM（905/1810/25991ms三阶段），设备回转写精确；旧7.2秒结果仅历史。最终APK/音频见android-ort-integration.md及cache/android-ort-unified/protocol-final-device-evidence/。

root额外修复Swift TTS失败后留住大session：同步等待释放prefill/decode/vocoder后才返回错误；原生actor队列在加载前后/运行后检查取消。实际noEOS→cache只剩speaker/CP→同engine再合成23帧成功；取消同样清除大session，本次暖场取消43ms（不与旧不同阶段1.403s直接比速）。输入帧预算/采样参数和speaker向量前置校验，计时改monotonic。真实源码host编译与完整Catalyst都通过；观测仅在测试副本增加只读session列表，没有替代引擎。证据cache/reports/ios-tts-failure-cleanup。

中央central-all-3实际ASR2/MT11/TTS1+回转写0执行失败；TTS原始CER0。另在有SacreBLEU2.5.1的现有环境重跑MT11，chrF++80.5466，保留context泄露失败输出。总exit2仍来自draft/预设质量限值与独立身份门，不能说所有门通过。

所有INT8/INT4整模与三组分阶段量化均未通过事前音色门；B(CP-only INT4)还发生237中文ASR“提纲”/CER1，两次独立识别一致。没有部署这些候选。详tts-stage-quant-2026-09-07.md。

状态streaming vocoder真实ORT长窗误差<=1.38e-6，固定状态5.194MB。root新bench_tts_streaming.py把真实生成帧逐块送入图，22帧/1.76秒与整段vocodermaxdiff1.505e-6。当前高负载冷talker初始化下首阈值块5.30秒、无欠载启动下界5.42秒（不含speaker/stream准备；不是实机播放），不能声称已达到实时。目录cache/tts/streaming-client/zh。

统一动态talker实际消费输出、两语全部codes/wave与原两图一致。第三基线纠正比较：相对当前串行release策略，talker-only峰值1.851→1.889GiB（稍增），不是相对app省46%；主要收益是跨turn复用，消除每turn约1.6秒重加载。一次初始化/保留内存分别报告，详即将冻结的unified报告。

官方ICL功能筛选四对声纹median gain+.1052且ASR精确；encoder8/9codes精确，一处窄VQ边界反例保留，同PCM实际ORTcodes的后续ASR/身份无新增失败。输入resampler影响单列，不把它说成整链bit-exact。ICL runner接入与统一图新包设计仍在进行；shared清单未切换。

root新增scripts/build_android_native和CI源码构建前置，防止C++源码变化后Gradle仍包装陈旧so；目前CLI0/2/4路径及YAML已检查，完整独立输出构建等待CPU窗口，不提前称通过。
