# BaBiQ P2 任务索引

> 本索引用来把 `p2-master.md` 拆成可执行子任务。
> P1 总体验收已由用户在 2026-05-24 确认通过，P2 正式入口从 `P2-1 SQLite + MyBatis-Plus 持久化底座` 开始。

## 任务总览

| 阶段 | 状态 | 任务 | 文档入口 | 依赖 |
|---|---|---|---|---|
| P2-0 | 已通过 | P1 总体验收和收口记录 | `docs/superpowers/plans/p2-0-final-acceptance/codex-handoff.md` | P1-4 |
| P2-1 | 待确认 | SQLite + MyBatis-Plus 持久化底座 | `docs/superpowers/plans/p2-1-sqlite-persistence/plan.md` | P1 验收通过 |
| P2-2 | 待写详细计划 | 多会话历史和桌面端最近对话 | `docs/superpowers/plans/p2-2-thread-history/task-card.md` | P2-1 |
| P2-3 | 待写详细计划 | Provider / API Key / 沙箱 / 审批设置系统 | `docs/superpowers/plans/p2-3-settings-system/task-card.md` | P2-1 |
| P2-4 | 待写详细计划 | 持久化后的恢复语义和运行记录 | `docs/superpowers/plans/p2-4-recovery-records/task-card.md` | P2-1, P2-2 |
| P2-5 | 待写详细计划 | 基础可观测增强 | `docs/superpowers/plans/p2-5-local-observability/task-card.md` | P2-4 |
| P2-6 | 可选，待定 | MCP Client 最小接入 | `docs/superpowers/plans/p2-6-mcp-client/task-card.md` | P2-3, P2-4, P2-5 |

## 执行规则

- 每个阶段必须先写详细 `plan.md`，由用户确认后再实现。
- 每个阶段实现前必须重新核对官方文档和 Maven Central，禁止使用 RC、Beta、EAP。
- 每个阶段实现后必须更新 `AGENTS.md`、`CLAUDE.md`、对应 `codex-handoff.md`，并主动中文 commit，不主动 push。
- P2-1 到 P2-4 是 P2 主线，P2-5 是增强，P2-6 是可选扩展。
