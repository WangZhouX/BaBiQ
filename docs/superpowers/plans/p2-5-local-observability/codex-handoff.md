# P2-5 本地可观测 Handoff

## 状态

- 当前状态: 已实现并通过自动化验证。
- 计划入口: `docs/superpowers/plans/p2-5-local-observability/plan.md`
- 依赖: P2-4 已完成。
- 下一步: 进入 `P2-6 MCP Client 最小接入`。

## 已实现能力

- 后端新增 `LocalObservabilityService`，统计只读取 SQLite 持久化运行记录，不读取 P1 内存计数器。
- 后端新增 `observability/snapshot`、`observability/tools`、`observability/costs` JSON-RPC 方法。
- 后端通过 MyBatis-Plus Mapper 自定义聚合 SQL 统计 turns、tokens、成本、状态分布、provider/model 分布和工具调用分布。
- 桌面端新增 `ObservabilityModels.kt` 协议模型，`AgentClient` 已接入三组 observability JSON-RPC 方法。
- 运行详情面板已增加本地统计区，支持 `7d`、`30d`、`all` 三个范围切换。
- 统计接口失败时只在运行详情统计区展示错误，不污染聊天消息和 turn 状态。

## 关键边界

- P2-5 没有启用 Actuator/Micrometer；原因是当前桌面端只需要 JSON-RPC 本地统计，额外 HTTP endpoint 会扩大暴露面。
- 不接 Langfuse、OpenTelemetry UI、Prometheus/Grafana。
- 不新增数据库表或字段，因此本阶段没有新增 migration；统计复用 P2-1 到 P2-4 已落库的 turn、summary 和 tool call 数据。
- 后续如果要启用 Actuator，必须新增明确计划、测试和本地访问边界。

## 验收命令

已执行并通过：

```powershell
cd backend
.\mvnw.cmd "-Dtest=LocalObservabilityServiceTest" test
.\mvnw.cmd "-Dtest=ObservabilityHandlersTest,LocalObservabilityServiceTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
.\gradlew.bat test --tests "*ObservabilityModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest"
.\gradlew.bat test
```

## 人工验收建议

1. 使用真实 Provider 连续跑几轮任务。
2. 打开右侧运行详情统计。
3. 切换 `7天`、`30天`、`全部`，确认 tokens、成本、失败和工具统计会刷新。
4. 重启后端后再次打开统计，确认数据仍来自历史持久化记录。
