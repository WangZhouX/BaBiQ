# P3-4 长期记忆异步流水线交接

## 状态

P3-4 当前仅完成详细开发计划，尚未实现代码。

计划入口：

- `docs/superpowers/plans/p3-4-long-term-memory/plan.md`

## 背景

P3-1 到 P3-3A 已完成：

- 当前窗口管理和 `ContextSnapshot`。
- `ContextAssembler` 分层上下文和 `LongTermMemoryReference` 占位。
- 短期压缩、active summary 安装、压缩审计字段、事务边界、乐观锁和启动恢复。

P3-4 要补齐的是长期记忆，不是再做短期压缩。

## 设计结论

- BaBiQ 长期记忆采用 Codex 风格两阶段异步流水线。
- SQLite 是事实源，Markdown 是用户可读镜像。
- Phase 1 用 Spring AI structured output 提取候选。
- Phase 2 首版用受控 Java artifact writer + structured consolidation strategy 归并，不让模型直接写任意文件。
- Spring AI Alibaba ReactAgent 可作为后续归并策略实现，但不能绕过 SQLite 审计和 artifact lifecycle。
- Read path 默认只注入 `memory_summary`，完整 `MEMORY.md` 和 VectorStore/RAG 放到后续阶段。
- P3-4 必须有用户开关、thread mode、secret redaction 和污染模式。

## 主要实现入口

预计新增：

- `backend/src/main/resources/db/migration/V10__long_term_memory_pipeline.sql`
- `backend/src/main/java/com/wzx/babiq/server/memory/`
- `backend/src/main/java/com/wzx/babiq/server/memory/model/`
- `backend/src/main/java/com/wzx/babiq/server/memory/repository/`
- `backend/src/main/java/com/wzx/babiq/server/memory/pipeline/`
- `backend/src/main/java/com/wzx/babiq/server/memory/redaction/`
- `backend/src/main/java/com/wzx/babiq/server/memory/artifact/`

预计修改：

- `backend/src/main/java/com/wzx/babiq/server/context/ContextAssembler.java`
- `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- `backend/src/main/java/com/wzx/babiq/server/context/model/ContextEnvelope.java`
- `backend/src/main/java/com/wzx/babiq/server/context/model/LongTermMemoryReference.java`
- `backend/src/main/java/com/wzx/babiq/server/settings/AppSettingsService.java`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ContextModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsScreen.kt`

## 必须保留的边界

- 不要把长期记忆写成普通 assistant message。
- 不要让 ChatMemory / MessageWindowChatMemory 成为事实源。
- 不要让模型直接写任意文件。
- 不要把完整长期记忆每轮塞进模型。
- 不要把 VectorStore/RAG 提前混进 P3-4。
- 不要只做 UI 开关；后端 AgentLoop 和 ContextAssembler 必须真实按设置变化。

## 验证命令

计划要求的最终验证：

```powershell
cd backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest,MemoryRepositoryTest,MemorySettingsServiceTest,MemoryPollutionServiceTest,MemorySecretRedactorTest,LongTermMemoryPipelineTest,MemoryConsolidationServiceTest,ContextAssemblerLongTermMemoryTest,MemoryHandlersTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*MemoryModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*ComposerContextBarTest"
.\gradlew.bat test
```

如果测试类名在实现中有调整，必须在 handoff 中同步真实命令和结果。

## 下一步

等待用户确认后，按 `plan.md` 从 Task 1 开始实现。实现前必须使用 `superpowers:executing-plans` 和 `superpowers:test-driven-development`。
