# 主审第二轮复核与限流恢复

2026-09-05 15:26 UTC。C 遇到 `429 AccountRateLimitExceeded`，未创建重复代理；间隔约一分钟后向原 Pi GLM agent 提交一次恢复请求。

## 独立确认

- `python3 scripts/verify --mode fast --output /tmp/auralis-rate-limit-review`：5/5 pass，exit 0。新增了 scripts 单测入口。
- RV01 的精确空 ASR 目录反例：现在 exit 2，清单 draft/空文件均明确 blocked；不再假通过。
- dot segment、NUL、首尾空白路径反例：现在均抛 path-escape。
- CI 两处已经改为 `python3 scripts/verify`；simctl 已使用 JSON 与 UDID，相关测试包含无设备、不可用设备和格式错误。
- ORT 文件已补模块 import，run outputNames 已用 Set，session run 已移入 actor；完整 iOS 编译未执行，这些改动仍需后续验证。
- 从官方 [setup-java v4 ref API](https://api.github.com/repos/actions/setup-java/git/ref/tags/v4) 读取 commit `cf277c60eb25467037889841efdb72551f06f6c3`，已交给 C 固定使用。

## 尚未验收

RV02、RV05 的完整类型/运行时校验、RV06–RV17 按第一轮报告继续处理。尤其 iOS readiness/停止流程与 fetch 包级原子更新仍未完成。Android A/B 的完成报告尚未到达，未启动全量 Gradle 集成。

账号限流是代理执行中断；源码及已通过检查保留。整个重构仍处于实现与复核阶段，不能标完成。
