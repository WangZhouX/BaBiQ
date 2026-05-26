# P3-3 短期记忆与上下文压缩交接

## 状态

P3-3 已完成实现，核心目标是让 BaBiQ 在当前窗口压力接近上限时，能像 Codex 一样把旧历史压缩为可审计的 active summary，同时保持原始 `bq_items` 不被改写。

## 已完成

- 后端新增 `context.compaction` 领域：
  - `ContextBudgetPolicy` / `ContextBudgetProperties`
  - `CompactionSourceSelector`
  - `ContextCompactionService`
  - `SpringAiContextCompactionStrategy`
  - `ContextManualCompactionService`
- 新增 SQLite 表：
  - `bq_context_summaries`
  - `bq_context_compactions`
- `ContextWindowRuntime` 已接入 pre-turn 自动压缩：
  - 先按当前模型窗口计算预算。
  - 超过阈值时压缩当前 turn 之前的旧历史。
  - 成功后安装 `active_summary_id`，递增 `window_ordinal`。
  - 再重新装配本轮上下文，确保 current turn 仍是权威输入。
- `ContextAssembler` 已支持 summary 替换：
  - active summary 注入 `short_term_summary`。
  - 被覆盖旧历史标记为 `REPLACED_BY_SUMMARY`。
  - `ContextCompactionItem` 不进入模型语义历史。
- JSON-RPC 已新增/扩展：
  - `context/compact`
  - `context/status` 新增 active summary、压缩次数、最近压缩状态。
- 桌面端已接入：
  - `ContextStatusResult` 新字段。
  - `ThreadItem.ContextCompaction` 序列化。
  - `ChatReducer` 运行事件转换。
  - `ComposerContextBar` 展示 `已压缩 N 次` 或失败状态。

## 设计边界

- 压缩摘要不是普通 assistant message，不会写成用户可误读的聊天回复。
- 原始对话仍以 `bq_items` 为事实源，summary 只是当前窗口的派生视图。
- Spring AI `ChatClient.entity(...)` 用于结构化摘要生成；不使用 `MessageWindowChatMemory` 作为 BaBiQ 事实源。
- Spring AI Alibaba `ContextEditingInterceptor` / `SummarizationHook` 仍是后续可替换策略候选，本阶段没有让它们绕过 BaBiQ 的 SQLite 审计和 active window 安装流程。
- P3-4 长期记忆、VectorStore/RAG、跨会话 memory summary 注入尚未实现。

## 重点文件

- `backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionService.java`
- `backend/src/main/java/com/wzx/babiq/server/context/compaction/SpringAiContextCompactionStrategy.java`
- `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- `backend/src/main/java/com/wzx/babiq/server/context/ContextAssembler.java`
- `backend/src/main/resources/db/migration/V8__context_short_term_compaction.sql`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`

## 验证命令

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextBudgetPolicyTest,CompactionSourceSelectorTest,ContextAssemblerCompactionTest,ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,ContextHandlersTest,SchemaCommentsCoverageTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ContextModelsTest" --tests "*ThreadHistoryModelsTest" --tests "*ComposerContextBarTest" --tests "*ChatControllerTest"
.\gradlew.bat test
```

## 下一步

P3-4 应先写详细计划，再实现长期记忆异步流水线：候选提取、secret redaction、归并 artifact、`memory_summary` 注入和用户可控的记忆开关。
