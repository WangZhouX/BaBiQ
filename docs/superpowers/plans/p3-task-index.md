# BaBiQ P3 任务索引

> 本索引用来把 `p3-master.md` 拆成可执行子任务。
> P3 的主题是 Codex 级上下文工程：当前窗口管理、短期记忆/上下文压缩、长期记忆。

## 任务总览

| 阶段 | 状态 | 任务 | 文档入口 | 依赖 |
|---|---|---|---|---|
| P3-0 | 待确认 | P2 总体验收复盘 + Codex/Spring 证据归档 | `docs/superpowers/plans/p3-1-context-memory-platform/codex-handoff.md` | P2 全量完成 |
| P3-1 | 已完成 | 上下文与记忆平台总体设计 + 最小底座 | `docs/superpowers/plans/p3-1-context-memory-platform/plan.md` | `p3-master.md` |
| P3-2 | 已完成 | 当前窗口管理运行时、持久化快照和 Agent 前置接入 | `docs/superpowers/plans/p3-2-context-window-runtime/plan.md` | P3-1 |
| P3-3 | 待计划 | 短期记忆和上下文压缩 | 后续创建 | P3-2 |
| P3-4 | 待计划 | 长期记忆异步流水线 | 后续创建 | P3-3 |
| P3-5 | 待计划 | 按需能力装配、记忆检索增强和桌面控制 | 后续创建 | P3-4 |

## 执行规则

- P3 每个实现子阶段必须先写独立 `plan.md`，并由用户确认后再动代码。
- P3-1 已落地 `ContextAssembler`、`ContextSnapshot` 和 `CapabilityCatalogAssembler` 最小底座，但未接入真实 AgentLoop。
- P3-2 已实现可审计 current window、持久化 context snapshot、Agent 前置接入、`context/status` / `context/snapshot/get` 和桌面上下文指示；不包含自动压缩。
- P3-3 是下一阶段，继续接 short-term compaction，必须能恢复、能审计、能在 UI 看到 `ContextCompactionItem`。
- P3-4 再做长期记忆提取/归并，必须有污染模式、secret redaction 和用户开关。
- P3-5 才考虑按需工具/skill/MCP 装配策略、VectorStore/RAG 检索增强和更完整的桌面记忆管理 UI。
- 任何阶段新增业务表或字段，都必须同步 SQL 中文注释、`bq_schema_comments`、Entity 注释和覆盖测试。
