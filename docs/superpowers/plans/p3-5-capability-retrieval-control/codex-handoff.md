# P3-5 按需能力装配、记忆检索增强和桌面控制交接

## 当前状态

P3-5 尚未实施。本目录当前只包含计划文档，用于用户确认后执行。

## 必读入口

1. `docs/superpowers/plans/p3-master.md`
2. `docs/superpowers/plans/p3-task-index.md`
3. `docs/superpowers/plans/p3-4-long-term-memory/plan.md`
4. `docs/superpowers/plans/p3-4-long-term-memory/codex-handoff.md`
5. `docs/superpowers/plans/p3-5-capability-retrieval-control/plan.md`
6. `AGENTS.md`

## 已确认事实

- Spring AI 最新稳定线可从 `1.1.6` 升到 `1.1.7`，不应升到 `2.0.0-M7`。
- Spring AI Alibaba 最新 BOM 仍为 `1.1.2.3`；它不等于 Spring AI 全能力集合，也不直接替代 Spring AI。
- Dynamic Tool Search 来自 Spring AI Community，不在 Spring AI core jar 内。
- 当前适配 Spring Boot 3 / Spring AI 1.1.x 的 Dynamic Tool Search 版本线是 `1.0.x`，建议使用 `1.0.1`。
- Spring AI `spring-ai-vector-store:1.1.7` 包含 `SimpleVectorStore`，`spring-ai-advisors-vector-store:1.1.7` 包含 `QuestionAnswerAdvisor` 和 `VectorStoreChatMemoryAdvisor`。
- Codex 采用“已注册能力”和“模型可见能力”分离的 deferred tool 设计，BaBiQ P3-5 应按这个方向做，但不能绕过自己的 SQLite 审计、审批和沙箱链路。

## 执行注意事项

- 先执行 `superpowers:executing-plans`，再开始改代码。
- 生产代码前必须使用 `superpowers:test-driven-development`。
- 完成前必须使用 `superpowers:verification-before-completion`。
- 新增表和字段必须同步 SQL 中文注释、`bq_schema_comments`、Entity 中文字段注释和覆盖测试。
- 新增或修改 Java/Kotlin 生产代码必须补中文教学型注释。
- 用户要求 commit 时使用中文 conventional commit。
- 不要把 `backend/src/main/resources/application.yml` 和 `learn/` 这类既有本地 dirty 项混进 P3-5 提交，除非实施时确认它们属于当前任务。

## 推荐执行顺序

1. 依赖升级和兼容性锁定。
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
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest,CapabilityRepositoryTest,CapabilityCatalogSyncServiceTest,CapabilityExposurePlannerTest,CapabilitySearchServiceTest,ToolSearchToolTest,LocalSkillRegistryTest,LongTermMemoryRetrievalServiceTest,ContextAssemblerMemoryRetrievalTest,CapabilityHandlersTest,SkillHandlersTest,MemoryHandlersTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*CapabilityModelsTest" --tests "*SkillModelsTest" --tests "*MemoryModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*ComposerContextBarTest"
.\gradlew.bat test
```
