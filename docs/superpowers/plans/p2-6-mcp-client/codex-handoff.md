# P2-6 MCP Client Handoff

## 状态

- 当前状态: 已完成并通过自动化验证。
- 计划入口: `docs/superpowers/plans/p2-6-mcp-client/plan.md`
- 依赖: P2-3、P2-4、P2-5 均已完成。
- 提交计划: `feat(p2-6): 接入本地 MCP 客户端`

## 已实现能力

- 后端新增 `babiq.mcp` 配置模型，默认 `enabled=false`，只支持本地 stdio MCP server。
- 后端新增 `McpClientManager`、`McpToolCatalog`、`McpToolAdapter` 和 `SdkMcpClientConnector`，负责连接、刷新、工具发现和工具调用适配。
- MCP 工具统一命名为 `mcp.<serverId>.<toolName>`，并合并进 `ToolRegistry`，调用路径继续经过现有审批、沙箱、工具观测、Spotlighting 和 TurnSummary 统计链路。
- 后端新增 `bq_mcp_servers`、`bq_mcp_tools` 两张表，所有表和字段都有 SQL 中文注释，并写入 `bq_schema_comments`。
- 后端新增 JSON-RPC 方法:
  - `mcp/servers/list`
  - `mcp/tools/list`
  - `mcp/servers/refresh`
- 桌面端新增“本地 MCP”入口，能展示 server 状态、错误信息、工具列表，并支持手动刷新。
- 桌面端不提供任意命令编辑入口，避免用户在 UI 中随手输入命令后被后端执行。

## 依赖与版本决策

- 已核对 Spring AI MCP Client 官方文档、MCP Java SDK 官方文档和 Maven Central。
- `spring-ai-starter-mcp-client` 最新 release 指向 `2.0.0-M7`，属于 milestone，不符合仓库规则。
- `spring-ai-starter-mcp-client:1.1.x` 线与当前 Spring AI Alibaba 传递的 MCP SDK 版本存在冲突风险。
- 最终选择官方 MCP Java SDK 稳定版 `io.modelcontextprotocol.sdk:mcp:1.1.3`，通过 BaBiQ 自己的 `McpClientConnector` 做薄适配。

## 关键边界

- P2-6 不实现 MCP Server。
- 不做远程 MCP、OAuth、插件市场。
- 不允许从 UI 输入任意 MCP server command 后立即执行。
- MCP 连接失败只记录 server 状态和 `lastError`，不能拖垮核心聊天。
- MCP 工具输出继续视为不可信数据，必须走 Spotlighting。

## 验收命令

```powershell
cd backend
.\mvnw.cmd "-Dtest=McpPropertiesTest,McpClientManagerTest,McpToolAdapterTest,McpHandlersTest,McpEndToEndIT,ToolRegistryTest" test
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*McpModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest"
.\gradlew.bat test
```

## 手动验收建议

1. 在 `application.yml` 中配置一个本地 stdio MCP server，并将 `babiq.mcp.enabled` 设置为 `true`。
2. 启动后端，确认 `mcp/servers/list` 返回 `connected` 或明确的 `lastError`。
3. 启动桌面端，进入“本地 MCP”页面查看 server 状态和工具列表。
4. 在聊天中触发一个 MCP 工具调用，确认仍出现审批、工具日志、TurnSummary 工具数。

## 下一步

P2-1 到 P2-6 已全部完成。进入下一阶段前，必须先做 P2 总体验收复盘并编写 P3 或后续阶段的详细计划；不要直接把 P3 能力混进 P2 收口提交。
