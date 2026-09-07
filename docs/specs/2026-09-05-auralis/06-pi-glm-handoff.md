# 06 Pi GLM 并行移交

用户已授权设计后移交 Pi GLM 并行实施。使用实际 profile：Pi GLM，provider `pi`，model `ark-coding/glm-5.3-flash`，thinking `max`。最多三个实施 agent 与主审同时运行；不再递归创建代理。

## 写入边界

| lane | 独占写入 | 先交付 | 完成后 |
|---|---|---|---|
| A runtime | Android `audio/`、`inference/`、新增 `session/`、`src/main/cpp/`、对应测试、jniLibs 说明 | SessionController 契约；可信失败/关闭/背压 | native 构建、推理协议、测试与 runtime 报告 |
| B product | Android `data/`、`ui/`、AppContainer、DialectApp、MainActivity、Manifest、res、data/UI 测试 | UI 状态接入与安装状态；A 的工厂需求 | Auralis 四屏、声音档案、无障碍及 product 报告 |
| C platform | `shared/`、`convert/`、`scripts/`、`.github/`、所有 Gradle 文件、`ios/`、根 README/忽略规则 | schema/fixtures、shared 资源接入、测试依赖 | Python/CI/iOS，最后单独运行全量 Gradle 集成检查 |
| 主审 | 本 specs、dispatch.json、最终汇总；其它文件仅 lane 退出后接手 | 冻结接口与证据 | 差异审查、集成缺陷修复与验收 |

各 agent 只写自己的 `reports/lane-A.md`、`reports/lane-B.md` 或 `reports/lane-C.md`，同时可写 `reports/interface-A.md` 等独占接口说明。不要共同修改一个状态文件。

## 最短依赖路径

1. A 立即落地 02 的 session 类型；C 立即落地 manifest schema 与 fixtures；B 可同时重构静态 UI、档案和权限。
2. A/B 在冻结接口上并行工作；涉及构造器/dependency/sourceSets 的请求写各自接口报告并通知主审，由拥有者合入。
3. C 完成公共契约与依赖后处理模型工具和 iOS。所有 lane 只运行不冲突的局部检查；全量 Gradle 由 C 在 A/B 写入完成后串行运行。
4. A/B 完成后主审做行为/差异审查；C 集成后各 lane 修自己的真实失败。跨区修复由主审明确交接所有权。

## 所有 lane 的执行规则

- 先读 README、自己相关规格、05、06 和 evidence；按当前文件修改，不 checkout/reset/stash/pull/commit/clean 用户工作树与索引。
- 不能按旧方案重复增加 Hilt、KMP、模块树、目录迁移、Oboe、通用 DAG 或全局事件总线。
- 官方库/API 需要文档时先用已安装 Context7 resolve→query；不可用则查官方资料并记限制。不能查询凭据或私有配置。
- 尽快开始实施，不停在计划；保留可编译的渐进修改。已有修复必须保留。
- 不允许原文伪译文、零音频成功、空 runtime stub、捕获所有异常后 return success、删除测试以变绿。
- 模型/SDK/设备阻塞只影响相关 gate，继续其它实现；不得伪造下载、固定 commit、hash、实测数字或成功记录。
- 不默认下载多 GB 模型或全量 SDK；先完成固定 revision 的下载/构建入口与轻量验证。有可用本地资产则直接验证。
- 每个实质修改运行对应行为测试；无关文案不加测试。实现后执行消融并删掉无收益抽象。
- 最终在独占报告写精确文件、命令、结果、阻塞及下一步；回复主审可直接验收的摘要。不得把“接口存在”写成真实模型已跑通。

## 执行追踪

真实 agent ID、初次创建结果与模型保存在 [dispatch.json](dispatch.json)。未创建前不填写虚构 ID。验收报告位于 [reports/](reports/)。
