# BaBiQ P2 任务索引

> 本索引用来把 `p2-master.md` 拆成可执行子任务。
> P1 总体验收已由用户在 2026-05-24 确认通过，P2 正式入口从 `P2-1 SQLite + MyBatis-Plus 持久化底座` 开始。

## 任务总览

| 阶段 | 状态 | 任务 | 文档入口 | 依赖 |
|---|---|---|---|---|
| P2-0 | 已通过 | P1 总体验收和收口记录 | `docs/superpowers/plans/p2-0-final-acceptance/codex-handoff.md` | P1-4 |
| P2-1 | 待确认 | SQLite + MyBatis-Plus 持久化底座 | `docs/superpowers/plans/p2-1-sqlite-persistence/plan.md` | P1 验收通过 |
| P2-2 | 待确认 | 多会话历史和桌面端最近对话 | `docs/superpowers/plans/p2-2-thread-history/plan.md` | P2-1 |
| P2-3 | 待确认 | Provider / API Key / 沙箱 / 审批设置系统 | `docs/superpowers/plans/p2-3-settings-system/plan.md` | P2-1 |
| P2-4 | 待确认 | 持久化后的恢复语义和运行记录 | `docs/superpowers/plans/p2-4-recovery-records/plan.md` | P2-1, P2-2 |
| P2-5 | 待确认 | 基础可观测增强 | `docs/superpowers/plans/p2-5-local-observability/plan.md` | P2-4 |
| P2-6 | 可选，待确认 | MCP Client 最小接入 | `docs/superpowers/plans/p2-6-mcp-client/plan.md` | P2-3, P2-4, P2-5 |

## 执行规则

- 每个阶段的详细 `plan.md` 已全部写出；实现前仍必须由用户逐个确认。
- 每个阶段实现前必须重新核对官方文档和 Maven Central，禁止使用 RC、Beta、EAP。
- 每个阶段实现后必须更新 `AGENTS.md`、`CLAUDE.md`、对应 `codex-handoff.md`，并主动中文 commit，不主动 push。
- P2-1 到 P2-4 是 P2 主线，P2-5 是增强，P2-6 是可选扩展。
