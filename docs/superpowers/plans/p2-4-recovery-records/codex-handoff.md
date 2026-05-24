# P2-4 恢复语义和运行记录 Handoff

## 状态

- 当前状态: 计划已编写，等待用户确认后实现。
- 计划入口: `docs/superpowers/plans/p2-4-recovery-records/plan.md`
- 依赖: P2-1 和 P2-2 必须完成；建议 P2-3 完成后实现。

## 目标

让后端重启、失败、取消、审批过期、工具调用和 TurnSummary 都能在本地持久化记录中被解释和追溯。

## 关键边界

- P2-4 不恢复正在执行的 ReactAgent checkpoint。
- RUNNING/WAITING_APPROVAL 遗留状态必须在启动时收束。
- 运行详情从持久化数据查询，不再只依赖当前内存事件。

## 验收命令

```powershell
cd backend
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

## 手动验收

1. 强制停止正在运行的后端。
2. 重启后端后，历史 turn 显示 interrupted。
3. 审批等待中重启后，approval 显示 expired。
4. 运行详情可查询历史工具调用、审批结果、失败原因和 TurnSummary。
