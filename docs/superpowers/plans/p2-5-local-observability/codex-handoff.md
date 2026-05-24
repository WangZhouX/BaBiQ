# P2-5 本地可观测 Handoff

## 状态

- 当前状态: 计划已编写，等待用户确认后实现。
- 计划入口: `docs/superpowers/plans/p2-5-local-observability/plan.md`
- 依赖: P2-4 必须完成。

## 目标

基于持久化运行记录提供本地统计，展示 turns、tokens、成本、失败和工具调用分布。

## 关键边界

- 默认通过 JSON-RPC 提供本地统计。
- Actuator/Micrometer 是可选增强，不作为 P2-5 完成的硬依赖。
- 不接 Langfuse、OpenTelemetry UI、Prometheus 部署。

## 验收命令

```powershell
cd backend
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

## 手动验收

1. 运行多轮任务。
2. 打开运行详情统计。
3. 确认统计来自历史记录，重启后仍存在。
4. Provider/model、工具调用、成本和失败分布可见。
