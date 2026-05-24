# P2-4 持久化后的恢复语义和运行记录任务卡

## 目标

让失败、取消、中断、审批、工具调用、成本和 TurnSummary 都可追溯，并在后端重启后给出明确恢复语义。

## 依赖

- 必须等待 P2-1 和 P2-2 完成。

## 必做能力

- 启动时扫描遗留 `RUNNING`、`WAITING_APPROVAL` turn。
- P2 阶段先将遗留 running turn 标记为 `interrupted` 或 `failed`，不尝试跨进程恢复 ReactAgent checkpoint。
- approval pending/resolved/expired 状态持久化。
- 运行详情页可读取历史工具调用、审批结果、失败原因和 TurnSummary。
- 桌面端 `运行详情` 从真实数据读取，不再只显示当前内存状态。

## 验收

- 执行中强停后端，再启动时不会出现悬挂 running turn。
- 历史 Turn 能看到工具调用、审批和失败原因。
- P1 TurnSummary 成本信息可在历史记录中查询。

## 下一步

在实现前写 `docs/superpowers/plans/p2-4-recovery-records/plan.md` 并等待用户确认。
