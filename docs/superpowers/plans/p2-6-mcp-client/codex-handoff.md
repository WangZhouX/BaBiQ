# P2-6 MCP Client Handoff

## 状态

- 当前状态: 可选阶段计划已编写，等待 P2 主线稳定和用户确认。
- 计划入口: `docs/superpowers/plans/p2-6-mcp-client/plan.md`
- 依赖: P2-3、P2-4、P2-5 必须完成。

## 目标

接入本地 stdio MCP server，把 MCP 工具包装成 BaBiQ Tool，并继续走审批、沙箱、Spotlighting、日志和 TurnSummary。

## 关键边界

- P2-6 不实现 MCP Server。
- 不做远程 MCP、OAuth、插件市场。
- 不使用 milestone、RC、Beta、Snapshot 依赖。
- 如果 Spring AI MCP Client 需要升级 Spring AI 小版本，必须先完成兼容性验证。

## 验收命令

```powershell
cd backend
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

## 手动验收

1. 配置一个本地 stdio MCP server。
2. 后端能连接并拉取工具列表。
3. 设置页能展示 MCP server 状态。
4. Agent 调用 MCP 工具时仍触发审批和日志。
5. TurnSummary 统计 MCP 工具调用。
