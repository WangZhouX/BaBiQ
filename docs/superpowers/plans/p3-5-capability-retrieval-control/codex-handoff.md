# P3-5 按需能力装配、记忆检索增强和桌面控制交接

## 当前状态

P3-5 已进入实现收口。本阶段保持 Spring AI `1.1.6` 和 Spring AI Alibaba `1.1.2.3` 不升级，采用 BaBiQ 自有 `CapabilitySearchService` / `tool_search` / SQLite 审计链路完成按需能力装配，并把长期记忆检索增强接入 `long_term_memory` 参考层。P3-5a 已在该端口下接入 Spring AI Community `tool-searcher-lucene:1.0.1`，只复用 Lucene 搜索器，不接入 Advisor。

## 必读入口

1. `docs/superpowers/plans/p3-master.md`
2. `docs/superpowers/plans/p3-task-index.md`
3. `docs/superpowers/plans/p3-4-long-term-memory/plan.md`
4. `docs/superpowers/plans/p3-4-long-term-memory/codex-handoff.md`
5. `docs/superpowers/plans/p3-5-capability-retrieval-control/plan.md`
6. `AGENTS.md`

## 已确认事实

- P3-5 暂不需要 Spring AI `1.1.6 -> 1.1.7` 升级，当前主线继续保持 `1.1.6`。
- P3-5a 已确认 `tool-searcher-lucene:1.0.1` 能在当前依赖树下解析，底层引入 `tool-search-tool:1.0.1` 和 Apache Lucene `9.12.3`。
- Spring AI Alibaba 最新 BOM 仍为 `1.1.2.3`；它不等于 Spring AI 全能力集合，也不直接替代 Spring AI。
- Dynamic Tool Search 来自 Spring AI Community，不在 Spring AI core jar 内。
- 当前适配 Spring Boot 3 / Spring AI 1.1.x 的 Dynamic Tool Search 版本线是 `1.0.x`，只能作为候选实现评估；不应为了它升级 Spring AI。
- Spring AI `spring-ai-vector-store:1.1.6` 包含 `SimpleVectorStore`，`spring-ai-advisors-vector-store:1.1.6` 包含 `QuestionAnswerAdvisor` 和 `VectorStoreChatMemoryAdvisor`，记忆检索增强不依赖升级。
- Codex 采用“已注册能力”和“模型可见能力”分离的 deferred tool 设计，BaBiQ P3-5 应按这个方向做，但不能绕过自己的 SQLite 审计、审批和沙箱链路。

## 本阶段实现摘要

- 后端新增 `bq_capabilities`、`bq_capability_search_events`、`bq_memory_retrieval_events`，并同步 SQL 中文注释、`bq_schema_comments`、Entity 注释和覆盖测试。
- 后端新增 `capability` 领域服务：能力扫描、持久化目录、VISIBLE / DEFERRED / DISABLED 暴露策略、按线程最近搜索结果提升 deferred 能力。
- 后端新增 `tool_search` 工具：模型默认只看到搜索入口，命中后下一轮才把对应工具纳入可见 tool callbacks，实际执行仍经过原 `ToolRegistry`、审批、沙箱和运行记录。
- P3-5a 已把 `CapabilitySearchService` 默认实现替换为 `LuceneCapabilitySearchService`，新搜索审计 `strategy=LUCENE`；旧 `FallbackLexicalCapabilitySearchService` 已删除，历史数据库里的旧策略值只作为审计记录保留。
- 后端新增本地 Skill 最小注册表：扫描受控 skill 目录 metadata，只有显式 `skills/get` 才读取 `SKILL.md` 正文。
- 后端新增长期记忆检索增强：按本轮用户输入检索相关 artifact / candidate 引用，按 token 预算注入 `long_term_memory` 参考层，并记录检索审计。
- JSON-RPC 新增 `capability/status`、`capability/search`、`capability/settings/set`、`skills/list`、`skills/get`、`memory/search`，`memory/status` / `memory/settings/set` 增加 `retrievalEnabled`。
- 桌面端新增能力、Skill、记忆检索协议模型；设置页可查看/切换能力暴露策略、控制长期记忆检索开关；输入栏 context chip 展示能力装配状态。

## 执行注意事项

- 先执行 `superpowers:executing-plans`，再开始改代码。
- 生产代码前必须使用 `superpowers:test-driven-development`。
- 完成前必须使用 `superpowers:verification-before-completion`。
- 新增表和字段必须同步 SQL 中文注释、`bq_schema_comments`、Entity 中文字段注释和覆盖测试。
- 新增或修改 Java/Kotlin 生产代码必须补中文教学型注释。
- 用户要求 commit 时使用中文 conventional commit。
- 不要把 `backend/src/main/resources/application.yml` 和 `learn/` 这类既有本地 dirty 项混进 P3-5 提交，除非实施时确认它们属于当前任务。

## 推荐执行顺序

1. 能力搜索依赖边界确认，保持 Spring AI `1.1.6` 不升级。
2. 能力目录持久化底座。
3. 能力扫描和目录装配。
4. 按需工具暴露和 `tool_search`。
5. Skill 最小注册表。
6. 长期记忆检索增强。
7. JSON-RPC 和桌面控制。
8. 文档、规则和最终验证。

## 最终验证命令

```powershell
cd backend
.\mvnw.cmd -q -Dtest="CapabilityHandlersTest,SkillHandlersTest,MemoryHandlersTest,CapabilityExposurePlannerTest,LuceneCapabilitySearchServiceTest,CapabilityCatalogSyncServiceTest,ToolSearchToolTest,LongTermMemoryRetrievalServiceTest,LongTermMemoryReadServiceTest,SchemaCommentsCoverageTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*CapabilityModelsTest" --tests "*SkillModelsTest" --tests "*MemoryModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*ComposerContextBarTest"
.\gradlew.bat test
```
