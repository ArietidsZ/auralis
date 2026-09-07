# iOS 声音档案复核（2026-09-07）

本轮修复生产代码中的取消、保存与选择、资源释放和模型验证门。未读取用户已有录音，未申请麦克风权限或采集声音，未修改模型、manifest 或 git 索引。

## 修复

- `VoiceProfileRepository.swift`：不同仓库实例共用一个进程内锁，完整串行化保存、读取、选择和删除。保存并选择在同一事务内完成，删除先撤下 metadata 提交标记并清除选中项，再删除音频。读取只接受普通文件，实际读取也限制字节数，继续拒绝路径穿越及目录/文件符号链接。保存检查 PCM16 量化后的静音边界，避免原始 Float 合格但保存后无法加载。保留 UUID 和旧 `profile_<timestamp>` ID、无 version 旧 metadata 的读取能力；没有迁移或删除真实旧录音。
- `VoiceProfileView.swift`：recorder/player 由 `@State` 持有，SwiftUI 重建不再替换实例。sheet 内直接显示错误；普通取消不显示失败。保存并选择成功后先清理草稿，再刷新列表，刷新失败不会留下可以重复保存的已提交草稿。recordingTask 只由旧任务的 defer 清空，取消后不能越过旧清理立即启动新任务。
- `AudioRecorder.swift`：主 actor 拥有 engine、stream 和 UI 状态。每个 tap 捕获所属录音的 continuation，避免在音频线程读取可变实例属性；录音 ID 防止旧 amplitude/通知回调及旧 defer 影响新录音。转换器每个输入 buffer 只供应一次，随后返回 noDataNow。初始化失败抛错并清理；录音中断终止 stream。权限等待使用一次性 continuation 状态，取消可立即结束等待，系统回调稍后到达不会重复 resume，也不会启动已取消录音。
- `PipelinePlatformPorts.swift`：三个生产推理 adapter 必须注入 readiness 闭包。ASR load/transcribe、MT translate、TTS load/synthesize 在对应全局状态不是 ready 时拒绝调用推理。TTS 仍使用选中 ID → 本机 PCM → 真实 speaker encoder；准备失败或取消后清除 embedding 并释放引擎。无选中档案仍返回明确不可用，由既有管线保留文字。
- `InterpretView.swift`：闭包捕获全局 modelManager 实例，session 的 native handle 仍单独持有。移除未跟踪的 onAppear refresh；保存 preparationTask 与 teardownTask，退出时取消并等待准备任务、释放旧管线，下次 task 先等 cleanup，再验证/创建。每个等待后的取消检查阻止离开页面后继续创建管线。

## 实际检查

持久证据目录：`/Users/arietids/Library/Caches/Auralis/reports/ios-voice-review/`。

1. `core-checks` 真实编译并运行，最终 **ALL PASS**，见 `core.log`。声音档案用例覆盖 UUID、保存可见性、持久选择、不信任 audioPath、非 44 字节 WAV 头、截断/错误采样率、非有限数/静音、路径穿越、符号链接、文件系统失败、删除、旧 ID/metadata、PCM16 量化阈值、20 次双实例选择/删除竞争、预先取消保存、拒绝将目录当作音频文件。全部使用专门生成的合成 PCM 和临时目录。
2. Catalyst 26.5 **全 app Swift 源 typecheck exit 0**，使用真实 ORT headers、Sherpa 和 HyMT module maps，见 `catalyst.log`。仅剩 AudioRecorder/AudioPlayer 原有 allowBluetooth 弃用警告；新增的 actor 隔离告警已解决。
3. 从生产 `AudioRecorder.swift` 提取未改逻辑的 PermissionRequest，真实 host 编译运行：**1,000 次注册前取消、1,000 次 callback/cancel 并发均通过**，无重复 resume。见 `PermissionChecks.swift`、`permission.log`。
4. 从生产文件提取三个推理 adapter，配合明确标记的 stub engines 和真实 VoiceProfileRepository，在 host 编译运行通过：五个入口 readiness 拒绝且未调用推理；未选档案拒绝；encoder 失败后释放；选中 PCM 传入提取、结果传入合成。见 `Adapters.swift`、`AdapterChecks.swift`、`adapter.log`。此项只证明门控和控制流，不证明模型推理质量。

核心检查命令：

```sh
CLANG_MODULE_CACHE_PATH=/private/tmp/auralis-voice-review-module-cache SWIFTPM_MODULECACHE_OVERRIDE=/private/tmp/auralis-voice-review-module-cache swift run --disable-sandbox --scratch-path /private/tmp/auralis-voice-review-build core-checks
```

首次 SwiftPM 调用因默认 clang cache 不在可写目录而失败；使用专用 module cache 后真实构建与执行通过，没有将环境失败记录成成功。

## 简化实验

先采用“编码 WAV 后完整解码一遍”验证量化可读性，运行 core-checks 通过。随后删去这次完整解析及 Float 数组分配，只在原有输入校验中检查量化后的峰值是否至少 4 个 PCM16 单位，再运行同一套检查，仍全部通过。选择后者。不同实例的互斥保留一把锁，没有引入 actor 存储层、通用文件事务框架或迁移系统。移除了重复的录音 RMS 更新、空 onTermination 回调和未跟踪的状态刷新任务。

## 文档依据

Context7 的 AVFoundation resolution 只返回第三方项目，没有 Apple 官方匹配，因此改查 Apple 官方资料。转换器可能多次请求输入；一次 tap 只拥有当前 buffer，供应后返回 noDataNow。依据：[TN3136](https://developer.apple.com/documentation/technotes/tn3136-avaudioconverter-performing-sample-rate-conversions)、[AVAudioConverterInputStatus](https://developer.apple.com/documentation/avfaudio/avaudioconverterinputstatus)。

## 尚未验证的范围

- 没有完整 Xcode iPhoneOS/Simulator build、设备录音、系统权限弹窗 UI、音频路由切换、真实 tap 调度或 sheet 呈现测试；typecheck 和提取状态机测试不能替代这些。
- 页面 cleanup 屏障属于同一 SwiftUI 状态实例。完全独立新建页面实例之间不提供全局互斥；没有新增全局资源框架。
- 文件锁保证本进程的仓库事务。未进行跨进程攻击、磁盘填满、第二次 fsync 失败或断电实验；metadata 提交标记和失败回滚代码不等于经过断电耐久验证。
- 合成 PCM 只用于存储/格式/控制流边界，本轮没有新增克隆音色、语音自然度或真机性能证据。
