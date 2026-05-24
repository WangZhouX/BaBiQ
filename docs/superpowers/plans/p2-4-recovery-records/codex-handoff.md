# P2-4 恢复语义和运行记录 Handoff

## 状态

- 当前状态: 已完成实现并通过自动化验收。
- 计划入口: `docs/superpowers/plans/p2-4-recovery-records/plan.md`
- 依赖: P2-1、P2-2、P2-3 已完成。

## 目标

让后端重启、失败、取消、审批过期、工具调用和 TurnSummary 都能在本地持久化记录中被解释和追溯。

## 已完成实现

- 后端新增启动恢复服务：`RUNNING` / `SENDING` 遗留 turn 会收束为 `INTERRUPTED`，`WAITING_APPROVAL` 会收束为 `EXPIRED`。
- 后端新增 `run/turns/list`、`run/turn/get`、`run/recovery/status` JSON-RPC 方法。
- 后端新增 `bq_tool_calls` 工具调用记录表，并为新增表和字段写入 SQL 中文注释与 `bq_schema_comments`。
- 工具调用拦截器会记录 running、completed、failed 状态，审批请求和审批响应会持久化 pending/resolved/expired。
- 桌面端运行详情面板已接入真实运行记录：恢复报告、历史 turn 列表、选中 turn 的工具调用/审批/TurnSummary 均来自后端。

## 关键边界

- P2-4 不恢复正在执行的 ReactAgent checkpoint。
- RUNNING/WAITING_APPROVAL 遗留状态必须在启动时收束。
- 运行详情从持久化数据查询，不再只依赖当前内存事件。

## 验收命令与结果

```powershell
cd backend
.\mvnw.cmd "-Dtest=TurnRecoveryServiceTest,RunRecordServiceTest,ToolCallRecordTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*RunRecordModelsTest"
.\gradlew.bat test
```

截至本次交接，以上命令均已通过。

## 手动验收

1. 强制停止正在运行的后端。
2. 重启后端后，历史 turn 显示 interrupted。
3. 审批等待中重启后，approval 显示 expired。
4. 运行详情可查询历史工具调用、审批结果、失败原因和 TurnSummary。
