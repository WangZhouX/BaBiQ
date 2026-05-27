# BaBiQ P3 任务索引

> 本索引用来把 `p3-master.md` 拆成可执行子任务。
> P3 的主题是 Codex 级上下文工程：当前窗口管理、短期记忆/上下文压缩、长期记忆。

## 任务总览

| 阶段 | 状态 | 任务 | 文档入口 | 依赖 |
|---|---|---|---|---|
| P3-0 | 待确认 | P2 总体验收复盘 + Codex/Spring 证据归档 | `docs/superpowers/plans/p3-1-context-memory-platform/codex-handoff.md` | P2 全量完成 |
| P3-1 | 已完成 | 上下文与记忆平台总体设计 + 最小底座 | `docs/superpowers/plans/p3-1-context-memory-platform/plan.md` | `p3-master.md` |
| P3-2 | 已完成 | 当前窗口管理运行时、持久化快照和 Agent 前置接入 | `docs/superpowers/plans/p3-2-context-window-runtime/plan.md` | P3-1 |
| P3-3 | 已完成 | 短期记忆和上下文压缩 | `docs/superpowers/plans/p3-3-short-term-compaction/plan.md` | P3-2 |
| P3-3A | 已完成 | 短期压缩鲁棒性补强：审计字段、事务安装、ordinal 乐观锁和启动恢复 | `docs/superpowers/plans/p3-3a-compaction-hardening/plan.md` | P3-3 |
| P3-4 | 已完成 | 长期记忆异步流水线 | `docs/superpowers/plans/p3-4-long-term-memory/plan.md` | P3-3 |
| P3-5 | 待确认 | 按需能力装配、记忆检索增强和桌面控制 | `docs/superpowers/plans/p3-5-capability-retrieval-control/plan.md` | P3-4 |

## 执行规则

- P3 每个实现子阶段必须先写独立 `plan.md`，并由用户确认后再动代码。
- P3-1 已落地 `ContextAssembler`、`ContextSnapshot` 和 `CapabilityCatalogAssembler` 最小底座，但未接入真实 AgentLoop。
- P3-2 已实现可审计 current window、持久化 context snapshot、Agent 前置接入、`context/status` / `context/snapshot/get` 和桌面上下文指示；不包含自动压缩。
- P3-3 已完成 short-term compaction：自动/手动压缩、summary 落库、active window 替换、`ContextCompactionItem` 事件和上下文 chip 压缩状态已接入；长期记忆仍不在本阶段。
- P3-3A 已完成 P3-3 鲁棒性补强：`bq_context_compactions` 10 个审计字段、压缩安装事务边界、`window_ordinal` 乐观校验、启动恢复服务和关键失败路径测试已补齐。
- P3-4 已完成长期记忆异步流水线：SQLite 事实源、Phase 1 idle 扫描抽取、secret redaction、Phase 2 generation 归并、Markdown mirror、memory summary read path 注入、JSON-RPC 状态/设置入口和桌面最小控制已接入。
- P3-5 计划文档已创建，待用户确认后才允许实现按需工具/skill/MCP 装配策略、VectorStore/RAG 检索增强和更完整的桌面记忆管理 UI。
- 任何阶段新增业务表或字段，都必须同步 SQL 中文注释、`bq_schema_comments`、Entity 注释和覆盖测试。
