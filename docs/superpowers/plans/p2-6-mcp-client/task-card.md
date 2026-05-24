# P2-6 MCP Client 最小接入任务卡

## 状态

可选阶段。只有 P2-1 到 P2-5 稳定后才进入。

## 目标

验证 BaBiQ 能接入本地 stdio MCP server，并将 MCP 工具包装为 BaBiQ tool。

## 依赖

- P2-3 审批和沙箱设置稳定。
- P2-4 运行记录稳定。
- P2-5 基础可观测稳定。

## 必做能力

- 本地 stdio MCP server 配置。
- 拉取 MCP 工具列表。
- MCP 工具调用包装成 BaBiQ tool。
- MCP 工具调用仍走审批、沙箱、日志、TurnSummary。
- 桌面端设置页只展示 MCP server 状态，不做 marketplace。

## 不做

- 不实现 MCP Server。
- 不做远程 MCP。
- 不做 OAuth。
- 不做插件市场。

## 下一步

进入本阶段前先重新评估 P2 主线完成度，再写 `docs/superpowers/plans/p2-6-mcp-client/plan.md`。
