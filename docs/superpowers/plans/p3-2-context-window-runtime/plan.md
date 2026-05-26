# P3-2 Context Window Runtime Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Status:** 已完成。实现结果和验证证据见 `docs/superpowers/plans/p3-2-context-window-runtime/codex-handoff.md`。本文件保留为 P3-2 的原始实施计划和验收边界。

**Goal:** 实现 BaBiQ 当前窗口管理运行时：每轮 turn 在调用模型前生成并持久化可审计 `ContextSnapshot`，把 P3-1 的分层上下文接入真实 Agent 前置链路，并在桌面端展示本轮上下文窗口状态。

**Architecture:** P3-2 在现有 `AgentLoop -> ReActStrategy -> ReactAgent` 之间插入 `ContextWindowRuntime`。运行时读取 SQLite 历史和 Provider 窗口元数据，调用 P3-1 `ContextAssembler` 生成临时模型输入视图，把快照写入 `bq_context_windows` / `bq_context_snapshots`，再把本轮输入以不污染聊天历史的方式交给 SAA `ReactAgent`。本阶段只做 current window 和快照，不做自动压缩、长期记忆写入或按需工具 schema 装配。

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring AI 1.1.6, Spring AI Alibaba 1.1.2.3, SQLite, MyBatis-Plus, Flyway, Jackson, Kotlin Compose Desktop, kotlinx.serialization, JSON-RPC 2.0.

---

## 1. Current Evidence

### 1.1 BaBiQ 当前代码事实

- `AgentLoop.invoke(...)` 当前会先 `emitItemAdded(UserMessageItem)`，再直接调用 `agent.stream(userText, strategy.buildConfig(...))`。
- `ReActStrategy.buildAgent(...)` 当前统一装配 `ReactAgent`、`SystemPromptSecurityRule.PROMPT`、`ToolCallback[]`、沙箱/HITL/Spotlighting/Token hooks 和 `MemorySaver`。
- `AgentLoopResumeSupport.resumeFromApproval(...)` 当前只负责审批恢复后 `jump_to=tool`，不重新注入用户输入。
- P3-1 已有 `ContextAssembler`、`CapabilityCatalogAssembler`、`ContextAssemblyInput`、`ContextAssemblyResult`、`ContextSnapshot`，但只在单元测试里运行，尚未接入 `AgentLoop`。
- `RunRecordService.getTurn(...)` 当前聚合 turn、item、summary、approval、toolCalls，适合在 P3-2 扩展一个 context snapshot 字段给右侧运行详情。
- 桌面端 `ComposerContextBar` 当前展示目录、沙箱权限和模型；P3-2 可以在这里新增上下文 token/window chip。

### 1.2 Context7 结论

- Spring AI `ChatMemory` / `MessageWindowChatMemory` 可以按 conversation id 维护消息窗口，也支持通过 `ChatClient` advisor 注入短期记忆；但它解决的是“消息窗口记忆”，不解决 BaBiQ 所需的 token budget、included/excluded 快照、ReactAgent 运行审计和跨 provider 降级。
- Spring AI `Prompt` / `Message` / `ToolCallback` 是 provider-portable 抽象。P3-2 应继续把真实工具 schema 留在 `ToolCallback` 通道里，`ContextEnvelope` 只放能力摘要。
- Spring AI Alibaba `ReactAgent` 支持 Hook、Interceptor、`MemorySaver`、HITL、ContextEditingInterceptor、LargeResultEvictionInterceptor 等 Agent Framework 能力。P3-2 先做 BaBiQ 前置上下文运行时；P3-3 再评估 SAA `ContextEditingInterceptor` / `SummarizationHook` 作为压缩策略。

### 1.3 Spring AI / Spring AI Alibaba 复用清单

P3-2 不是绕开 Spring AI / Spring AI Alibaba 自己写一个 Agent。P3-2 的分工是：官方框架承载模型、工具、Agent 图、HITL 和拦截器；BaBiQ 自己维护跨模型上下文策略、持久化快照和桌面协议。

必须复用的 Spring AI 能力：

- `org.springframework.ai.chat.messages.Message`: P3-1 `ContextAssembler` 的标准输出仍然是 Spring AI message，而不是 BaBiQ 私有 prompt 对象。
- `org.springframework.ai.chat.prompt.Prompt`: 如果当前 Spring AI Alibaba `ReactAgent` 版本能安全接收 typed prompt/messages，优先走 typed input，而不是字符串渲染。
- `org.springframework.ai.tool.ToolCallback`: 真实工具 schema 和 function-calling 能力继续走 Spring AI 工具通道，`ContextEnvelope.capability_catalog` 只放摘要，不能替代真实工具注册。
- `ChatModel` / provider adapter: 模型调用仍由现有 `ChatClientFactory` 和 Provider 工厂负责，P3-2 不新建模型客户端。
- `Usage` / response metadata: 真实 prompt/completion token 仍从现有 Spring AI/SAA token usage 链路汇总到 `TurnObservationContext`，再回填 `ContextSnapshot`。

可复用但本阶段不接管主链路的 Spring AI 能力：

- `ChatMemory` / `MessageWindowChatMemory`: 可作为后续辅助组件，但 P3-2 不让它替代 `Thread / Turn / Item` + `ContextSnapshot`，因为它不能表达 included/excluded、window ordinal、审计快照和压缩谱系。
- `VectorStore` / RAG advisor / structured output: 留给 P3-4/P3-5 的长期记忆和检索增强。

必须复用的 Spring AI Alibaba 能力：

- `ReactAgent`: 继续作为工具型 Agent 主执行器，P3-2 只在调用前准备上下文输入。
- `MemorySaver`: 继续保存 SAA Graph/HITL 暂停现场，P3-2 不重写暂停恢复机制。
- `HumanInTheLoopHook`: 审批仍走当前 SAA HITL hook 和 BaBiQ `approval/respond`，上下文运行时不绕过审批。
- `ModelCallLimitHook`、`LargeResultEvictionInterceptor`、`BaBiQSandboxInterceptor`、`SpotlightingToolInterceptor`、`ToolObservationInterceptor`、token hook/interceptor: 继续保留在 `ReActStrategy.buildAgent(...)`，P3-2 不替换这些横切能力。

需要评估但不在 P3-2 落地的 Spring AI Alibaba 能力：

- `ContextEditingInterceptor`: P3-3 短期压缩阶段评估是否作为压缩策略实现之一。
- `SummarizationHook`: P3-3 评估是否用于生成短期摘要，但摘要成功落库、替换 active window 和 UI 可审计事件仍由 BaBiQ 控制。
- 动态工具选择能力: P3-5 再评估，P3-2 只记录能力目录摘要，不做按需工具 schema 装配。

BaBiQ 自己实现的最小必要层：

- `ContextWindowRuntime`: 读取 BaBiQ 历史、Provider 窗口、工作目录和运行策略，决定本轮模型输入视图。
- `ContextSnapshot` 持久化: 记录 included/excluded、token estimate、actual prompt tokens、window ordinal 和能力摘要，供审计、UI 和后续压缩使用。
- JSON-RPC 协议: `context/status`、`context/snapshot/get`、`run/turn/get.contextSnapshot`。
- 桌面展示: 输入栏 context chip 和运行详情快照摘要。

### 1.4 P3-2 不解决什么

- 不实现自动压缩，不生成 `ContextCompactionItem`。
- 不实现长期记忆提取/归并/检索。
- 不做按需 MCP/Skill/tool schema 装配，只记录 P3-1 能力目录摘要。
- 不改变审批和沙箱策略语义；本轮仍使用 `turn/start` 固定的 `AgentRunPolicy` 快照。
- 不把 envelope JSON 或模型可见上下文写入 `bq_items` 当作用户消息。

## 2. Design Decisions

### 2.1 本轮上下文如何注入

P3-2 的关键边界是：`ContextSnapshot` 和 `ContextEnvelope` 是“本轮临时模型输入视图”，不是聊天历史。

实现时必须遵守：

- UI 仍只显示用户真实输入的 `UserMessageItem`。
- `bq_items` 仍只保存协议 item，不保存 envelope 伪消息。
- `ContextSnapshot` 单独落入 `bq_context_snapshots`，供审计和运行详情查看。
- 传给模型的输入必须明确声明层级优先级：`current_turn` 覆盖 `recent_history`、summary、memory、workspace facts 和 capability catalog。
- 如果 SAA `ReactAgent.stream(...)` 在当前版本不能直接接受 `List<Message>` 或 `Prompt`，P3-2 先使用 `ContextualPromptRenderer` 把 `ContextAssemblyResult.messages()` 渲染为一个临时字符串输入；这个字符串只进入本轮模型调用，不进入聊天历史。

### 2.2 为什么不直接使用 Spring AI ChatMemory

Spring AI `MessageWindowChatMemory` 可以成为后续底层组件，但 P3-2 需要自己实现运行时，因为：

- BaBiQ 历史真相源是 SQLite `Thread / Turn / Item`，不是 ChatClient advisor 内存。
- ReactAgent 主链路不是普通 `ChatClient.prompt().advisors(...)`。
- BaBiQ 需要记录 included/excluded、token estimate、model window、工具能力摘要和排除原因。
- 后续 P3-3 压缩必须能安全替换 active context window，这需要 `windowOrdinal` 和 snapshot lineage。

### 2.3 当前窗口状态模型

P3-2 引入两个层次：

- `ContextWindowState`: thread 级当前窗口状态，记录 `windowOrdinal`、模型窗口、阈值、最后快照。
- `ContextSnapshotRecord`: turn 级快照，记录本轮模型可见内容、排除内容、能力摘要、token estimate 和实际 prompt token 回填。

本阶段 `windowOrdinal` 固定从 `0` 开始，只有未来 P3-3 压缩成功后才递增。

## 3. File Structure

### 3.1 Backend New Files

- Create: `backend/src/main/resources/db/migration/V7__context_window_runtime.sql`
  - 新增 `bq_context_windows`、`bq_context_snapshots` 和中文 `bq_schema_comments`。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
  - Agent 调用前的唯一上下文运行时入口。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeInput.java`
  - 封装 thread、turn、provider、cwd、runPolicy 和用户消息。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeResult.java`
  - 返回快照 id、window 状态、模型输入文本和 assembly result。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextualPromptRenderer.java`
  - 当 ReactAgent 不能直接接 Spring AI messages 时，把 messages 渲染为临时模型输入。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextStatusService.java`
  - 聚合当前 thread 的窗口状态，供 JSON-RPC 和 UI 查询。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextWindowRepository.java`
  - 领域仓库接口，隔离 Agent 核心和 MyBatis。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSnapshotRepository.java`
  - 保存和查询 turn 级快照。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextWindowRecord.java`
  - thread 级窗口记录。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSnapshotRecord.java`
  - turn 级快照记录。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/model/ContextStatusResult.java`
  - `context/status` 返回 DTO。
- Create: `backend/src/main/java/com/wzx/babiq/server/context/model/ContextSnapshotDto.java`
  - `run/turn/get` 和 `context/snapshot/get` 共享的快照 DTO。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextWindowEntity.java`
  - `bq_context_windows` MyBatis-Plus entity。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextSnapshotEntity.java`
  - `bq_context_snapshots` MyBatis-Plus entity。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ContextWindowMapper.java`
  - 窗口表 mapper。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ContextSnapshotMapper.java`
  - 快照表 mapper。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextWindowRepository.java`
  - `ContextWindowRepository` 的 SQLite 实现。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextSnapshotRepository.java`
  - `ContextSnapshotRepository` 的 SQLite 实现。
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ContextStatusHandler.java`
  - JSON-RPC `context/status`。
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ContextSnapshotGetHandler.java`
  - JSON-RPC `context/snapshot/get`。

### 3.2 Backend Modified Files

- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
  - 在普通 turn 模型调用前调用 `ContextWindowRuntime.prepare(...)`。
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`
  - 暴露当前 turn 的工具 callbacks 和模型窗口元数据读取辅助，或新增窄方法供 runtime 使用。
- Modify: `backend/src/main/java/com/wzx/babiq/server/observability/RunRecordService.java`
  - `getTurn(...)` 增加 context snapshot DTO。
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/dto/RunTurnDetailResult.java`
  - 新增 `contextSnapshot` 字段。
- Modify: `backend/src/main/java/com/wzx/babiq/server/model/ModelProviderRegistry.java`
  - 如缺失，补一个 provider/model context window 查询方法。
- Modify: `backend/src/main/java/com/wzx/babiq/server/model/ModelProviderConfig.java`
  - 不强改字段；只在 runtime 中读取已有 `contextWindow` 和 `ModelMetadata` fallback。

### 3.3 Desktop New Files

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ContextModels.kt`
  - `ContextStatusResult`、`ContextSnapshotInfo`、`ContextSnapshotItemInfo`。

### 3.4 Desktop Modified Files

- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
  - 新增 `getContextStatus(threadId)` 和 `getContextSnapshot(snapshotId)`。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/RunRecordModels.kt`
  - `RunTurnDetailResult` 增加 `contextSnapshot`。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
  - 新增 `ContextWindowState`，并接入 `RunRecordState` 或 `AppState`。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
  - 增加当前上下文状态字段。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
  - 连接、打开会话、turn 完成后刷新 `context/status`。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`
  - 新增上下文窗口 chip，例如 `上下文 9.5k / 128k`。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`
  - 在选中 turn 详情里展示 snapshot 摘要、included/excluded 计数和 token 估算。

### 3.5 Tests

- Create: `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextualPromptRendererTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/persistence/ContextSnapshotPersistenceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ContextStatusHandlerTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ContextSnapshotGetHandlerTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopContextRuntimeTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`
  - 不一定改代码，但新增 migration 后必须继续通过。
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ContextModelsTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBarTest.kt`

## 4. Data Schema

### 4.1 `bq_context_windows`

Purpose: 每个 thread 的当前窗口运行状态。

Columns:

- `id`: 数据库内部主键。
- `thread_id`: 业务 thread id，唯一。
- `window_ordinal`: 当前窗口序号，P3-2 初始为 0。
- `active_summary_id`: 当前窗口基线摘要 id，P3-2 为空，P3-3 使用。
- `model_context_window`: 当前模型窗口 token 数。
- `auto_compact_threshold`: 自动压缩阈值 token 数，P3-2 只计算不触发。
- `last_snapshot_id`: 最近一次 `ContextSnapshot` id。
- `created_at`: 创建时间。
- `updated_at`: 更新时间。

Indexes:

- `ux_bq_context_windows_thread_id` unique on `thread_id`。
- `idx_bq_context_windows_updated_at` on `updated_at DESC`。

### 4.2 `bq_context_snapshots`

Purpose: 每个 turn 的模型可见上下文快照。

Columns:

- `id`: 数据库内部主键。
- `snapshot_id`: 业务快照 id，例如 `ctxsnap_xxx`，唯一。
- `thread_id`: 所属 thread id。
- `turn_id`: 所属 turn id。
- `phase`: 快照阶段，P3-2 支持 `PRE_MODEL`、`POST_USAGE`、`RESUME_PRE_MODEL`。
- `provider_id`: 本轮 provider id。
- `model`: 本轮模型名。
- `cwd`: 本轮工作目录。
- `window_ordinal`: 所属窗口序号。
- `model_context_window`: 本轮模型窗口。
- `auto_compact_threshold`: 自动压缩阈值。
- `estimated_tokens`: 调用前 token 估算。
- `actual_prompt_tokens`: 调用后真实 prompt tokens，拿不到时为空。
- `included_item_count`: included snapshot item 数量。
- `excluded_item_count`: excluded snapshot item 数量。
- `envelope_json`: 分层 envelope JSON。
- `items_json`: snapshot items JSON 数组。
- `capability_catalog_json`: 能力目录摘要 JSON。
- `input_preview`: 当前用户输入短预览，便于列表排查。
- `created_at`: 创建时间。

Indexes:

- `ux_bq_context_snapshots_snapshot_id` unique on `snapshot_id`。
- `idx_bq_context_snapshots_turn_id_phase` on `(turn_id, phase)`。
- `idx_bq_context_snapshots_thread_created` on `(thread_id, created_at DESC)`。

## 5. Protocol

### 5.1 `context/status`

Request:

```json
{
  "threadId": "thr_xxx"
}
```

Response:

```json
{
  "threadId": "thr_xxx",
  "windowOrdinal": 0,
  "modelContextWindow": 128000,
  "autoCompactThreshold": 89600,
  "lastSnapshotId": "ctxsnap_xxx",
  "lastEstimatedTokens": 9538,
  "lastActualPromptTokens": 8670,
  "usageRatio": 0.0745,
  "status": "ok"
}
```

### 5.2 `context/snapshot/get`

Request:

```json
{
  "snapshotId": "ctxsnap_xxx"
}
```

Response:

```json
{
  "snapshotId": "ctxsnap_xxx",
  "threadId": "thr_xxx",
  "turnId": "turn_xxx",
  "phase": "PRE_MODEL",
  "estimatedTokens": 9538,
  "actualPromptTokens": 8670,
  "includedItemCount": 12,
  "excludedItemCount": 4,
  "modelContextWindow": 128000,
  "items": [
    {
      "sourceId": "item_xxx",
      "sourceType": "THREAD_ITEM",
      "priority": "HIGH",
      "included": true,
      "reason": "recent_history",
      "tokenEstimate": 128
    }
  ]
}
```

### 5.3 `run/turn/get`

P3-2 扩展 `RunTurnDetailResult`：

```json
{
  "turn": {},
  "items": [],
  "summary": {},
  "approvals": [],
  "toolCalls": [],
  "contextSnapshot": {}
}
```

## 6. Implementation Tasks

## Chunk 1: Persistence and DTO Foundation

### Task 1: Add context window migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__context_window_runtime.sql`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`

- [ ] **Step 1: Write migration**

Create `V7__context_window_runtime.sql` with `bq_context_windows` and `bq_context_snapshots`. Every table and column must have Chinese `--` comments and matching `INSERT OR REPLACE INTO bq_schema_comments(...)`.

- [ ] **Step 2: Run schema coverage**

Run:

```powershell
cd backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest" test
```

Expected: PASS. If it fails, fix missing `bq_schema_comments`.

- [ ] **Step 3: Commit**

```powershell
git add backend/src/main/resources/db/migration/V7__context_window_runtime.sql backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java
git commit -m "feat(p3-2): 新增上下文窗口快照表"
```

### Task 2: Add persistence entities and mappers

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextWindowEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextSnapshotEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ContextWindowMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ContextSnapshotMapper.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/ContextSnapshotPersistenceTest.java`

- [ ] **Step 1: Write failing persistence test**

Test should insert a window + snapshot and load them by thread id, turn id and snapshot id.

Expected assertions:

- `findWindow(threadId)` returns `windowOrdinal=0` and `lastSnapshotId`。
- `findLatestSnapshotByTurnId(turnId)` returns `phase=PRE_MODEL`。
- `itemsJson` and `envelopeJson` round-trip without mutation。

- [ ] **Step 2: Run test and verify it fails**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextSnapshotPersistenceTest" test
```

Expected: FAIL because entities/repositories do not exist.

- [ ] **Step 3: Implement entities and mappers**

Each entity field must have Chinese comments explaining database meaning, writer, reader and null semantics.

- [ ] **Step 4: Run test and schema coverage**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextSnapshotPersistenceTest,SchemaCommentsCoverageTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextWindowEntity.java backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextSnapshotEntity.java backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ContextWindowMapper.java backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ContextSnapshotMapper.java backend/src/test/java/com/wzx/babiq/server/persistence/ContextSnapshotPersistenceTest.java
git commit -m "feat(p3-2): 接入上下文快照持久化实体"
```

### Task 3: Add repository adapters

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextWindowRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSnapshotRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextWindowRecord.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSnapshotRecord.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextWindowRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextSnapshotRepository.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/ContextSnapshotPersistenceTest.java`

- [ ] **Step 1: Extend failing test to use repository interfaces**

The test should depend on repository interfaces, not mappers.

- [ ] **Step 2: Implement repository adapters**

Keep Agent/runtime packages depending only on `ContextWindowRepository` and `ContextSnapshotRepository`.

- [ ] **Step 3: Run test**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextSnapshotPersistenceTest" test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/context/repository backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextWindowRepository.java backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextSnapshotRepository.java backend/src/test/java/com/wzx/babiq/server/persistence/ContextSnapshotPersistenceTest.java
git commit -m "feat(p3-2): 建立上下文窗口仓库适配器"
```

## Chunk 2: Runtime Assembly Before Agent Call

### Task 4: Add contextual prompt renderer

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextualPromptRenderer.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextualPromptRendererTest.java`

- [ ] **Step 1: Write failing renderer test**

Test should build a `ContextAssemblyResult` with:

- system context rule
- envelope JSON user message
- final current user message

Expected renderer output:

- includes a clear `Runtime Context` block
- includes a clear `Current User Request` block
- final user request appears exactly once at the end
- renderer output is not equal to any `ThreadItem` payload

- [ ] **Step 2: Implement renderer**

The renderer is a compatibility seam. If later SAA can accept `List<Message>`, this file can become unused or replaced. P3-2 should still use it initially to avoid a risky ReactAgent API jump.

- [ ] **Step 3: Run renderer test**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextualPromptRendererTest" test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextualPromptRenderer.java backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextualPromptRendererTest.java
git commit -m "feat(p3-2): 添加临时上下文输入渲染器"
```

### Task 5: Add ContextWindowRuntime

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeInput.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeResult.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeTest.java`

- [ ] **Step 1: Write failing runtime test**

Use fake history and fake tools. Assert:

- runtime calls P3-1 `ContextAssembler`
- runtime persists `PRE_MODEL` snapshot
- runtime creates or updates thread window
- result has `modelInputText` distinct from raw `userText`
- result keeps `rawUserText` so UI/user item remains unchanged
- `autoCompactThreshold = floor(modelContextWindow * 0.70)` for P3-2

- [ ] **Step 2: Expose minimal strategy helpers**

Add narrow methods to `ReActStrategy` if needed:

```java
public ToolCallback[] currentToolCallbacks() {
    return toolRegistry.allCallbacks();
}
```

Do not let `ContextWindowRuntime` directly depend on `ToolRegistry`.

- [ ] **Step 3: Implement runtime**

Runtime input should include:

- `threadId`
- `turnId`
- `userText`
- `providerId`
- `model`
- `cwd`
- `projectId`
- `AgentRunPolicy`

Runtime should load history from `ConversationRepository.listItems(...)`, decode item payloads into `ThreadItem`, build capability catalog, assemble context, persist snapshot and return rendered model input.

- [ ] **Step 4: Run runtime test**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextWindowRuntimeTest,ContextualPromptRendererTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/context/runtime backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java backend/src/test/java/com/wzx/babiq/server/context/runtime
git commit -m "feat(p3-2): 实现当前窗口运行时"
```

### Task 6: Integrate runtime into AgentLoop

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopContextRuntimeTest.java`

- [ ] **Step 1: Write failing AgentLoop integration test**

Assert:

- `emitter.emitItemAdded(UserMessageItem.of(..., userText))` still receives raw user text.
- `agent.stream(...)` receives runtime `modelInputText`, not raw user text.
- If runtime throws, turn fails through existing `AgentLoopSupport.fail(...)`.
- Existing HITL resume path is unchanged.

- [ ] **Step 2: Modify AgentLoop constructor**

Inject `ContextWindowRuntime`. Keep existing test constructor behavior by making tests use explicit mocks or adding one production constructor only if current tests support Spring injection.

- [ ] **Step 3: Call runtime before model call**

Sequence must be:

1. emit raw user item
2. prepare context runtime
3. build agent
4. stream `runtimeResult.modelInputText()`
5. handle output unchanged

This order keeps user-visible history faithful while model receives the transient context envelope.

- [ ] **Step 4: Run AgentLoop integration tests**

```powershell
cd backend
.\mvnw.cmd "-Dtest=AgentLoopContextRuntimeTest,AgentLoopTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopContextRuntimeTest.java
git commit -m "feat(p3-2): 将上下文运行时接入 AgentLoop"
```

### Task 7: Backfill actual prompt tokens after model usage

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoopOutputHandler.java` or `AgentLoop.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeTest.java`

- [ ] **Step 1: Write failing test**

Given a `TurnObservationContext` with prompt tokens, assert latest snapshot has `actualPromptTokens` after model completion or failure.

- [ ] **Step 2: Add runtime completion hook method**

Example shape:

```java
public void recordUsage(String snapshotId, TurnObservationContext observationContext) {
    // copy prompt token count from observation context if present
}
```

- [ ] **Step 3: Call after model returns**

Call after `AgentStreamConsumer.consume(...)` returns and before `handleOutput(...)` changes turn lifecycle. On failure, still try to record usage if any token count exists.

- [ ] **Step 4: Run tests**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextWindowRuntimeTest,AgentLoopContextRuntimeTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java backend/src/main/java/com/wzx/babiq/server/agent backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeTest.java
git commit -m "feat(p3-2): 回填上下文快照真实 token"
```

## Chunk 3: JSON-RPC and Run Details

### Task 8: Add context DTO and status service

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/context/model/ContextStatusResult.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/model/ContextSnapshotDto.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextStatusService.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextStatusServiceTest.java`

- [ ] **Step 1: Write failing service test**

Assert service returns:

- thread id
- window ordinal
- model context window
- threshold
- latest snapshot id
- estimated/actual prompt tokens
- usage ratio
- `status=ok`, `status=no_snapshot`, or `status=over_threshold`

- [ ] **Step 2: Implement DTO and service**

DTO fields must be stable JSON-friendly records. Avoid returning raw entity classes.

- [ ] **Step 3: Run test**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextStatusServiceTest" test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/context/model backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextStatusService.java backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextStatusServiceTest.java
git commit -m "feat(p3-2): 提供上下文窗口状态服务"
```

### Task 9: Add JSON-RPC handlers

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ContextStatusHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ContextSnapshotGetHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/api/method/ContextStatusHandlerTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/api/method/ContextSnapshotGetHandlerTest.java`

- [ ] **Step 1: Write failing handler tests**

Tests should cover:

- missing `threadId` returns `INVALID_PARAMS`
- missing `snapshotId` returns `INVALID_PARAMS`
- happy path returns DTO from service
- snapshot not found returns `INVALID_PARAMS` with useful message

- [ ] **Step 2: Implement handlers**

Handlers implement `JsonRpcMethodHandler`:

- `context/status`
- `context/snapshot/get`

Registration is automatic via Spring bean list in `JsonRpcDispatcher`.

- [ ] **Step 3: Run handler tests**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextStatusHandlerTest,ContextSnapshotGetHandlerTest" test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/method/ContextStatusHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/ContextSnapshotGetHandler.java backend/src/test/java/com/wzx/babiq/server/api/method/ContextStatusHandlerTest.java backend/src/test/java/com/wzx/babiq/server/api/method/ContextSnapshotGetHandlerTest.java
git commit -m "feat(p3-2): 暴露上下文窗口查询接口"
```

### Task 10: Extend run/turn/get detail

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/dto/RunTurnDetailResult.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/observability/RunRecordService.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/observability/RunRecordServiceTest.java`

- [ ] **Step 1: Write failing run detail test**

Assert `RunRecordService.getTurn(turnId)` includes latest context snapshot DTO when snapshot exists, and null when none exists.

- [ ] **Step 2: Implement DTO extension**

Append field rather than changing existing field names, so desktop decoding remains backward compatible once updated.

- [ ] **Step 3: Run test**

```powershell
cd backend
.\mvnw.cmd "-Dtest=RunRecordServiceTest" test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/dto/RunTurnDetailResult.java backend/src/main/java/com/wzx/babiq/server/observability/RunRecordService.java backend/src/test/java/com/wzx/babiq/server/observability/RunRecordServiceTest.java
git commit -m "feat(p3-2): 在运行详情中返回上下文快照"
```

## Chunk 4: Desktop Protocol and UI

### Task 11: Add desktop context protocol models

**Files:**
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ContextModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/RunRecordModels.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ContextModelsTest.kt`

- [ ] **Step 1: Write failing protocol test**

Decode sample `context/status`, `context/snapshot/get`, and `run/turn/get` with `contextSnapshot`.

- [ ] **Step 2: Implement models**

All public data classes and fields need Chinese KDoc following repo rules.

- [ ] **Step 3: Run protocol tests**

```powershell
cd desktop
.\gradlew.bat test --tests "*ContextModelsTest"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ContextModels.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/RunRecordModels.kt desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ContextModelsTest.kt
git commit -m "feat(p3-2): 添加桌面端上下文协议模型"
```

### Task 12: Add AgentClient context calls

**Files:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`

- [ ] **Step 1: Write failing client tests**

Assert:

- `getContextStatus(threadId)` sends method `context/status`
- `getContextSnapshot(snapshotId)` sends method `context/snapshot/get`
- JSON-RPC error surfaces as `AgentClientException`

- [ ] **Step 2: Implement gateway methods**

Add methods to `AgentGateway` and `AgentClient`.

- [ ] **Step 3: Run tests**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt
git commit -m "feat(p3-2): 接入桌面上下文查询客户端"
```

### Task 13: Add desktop state and controller refresh

**Files:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

- [ ] **Step 1: Write failing controller tests**

Assert:

- after opening a thread, controller loads context status
- after turn completed, controller refreshes context status
- if context status load fails, chat message state is not broken and only context error is recorded

- [ ] **Step 2: Implement state**

Add `ContextWindowUiState` with:

- `loading`
- `error`
- `windowOrdinal`
- `lastSnapshotId`
- `modelContextWindow`
- `lastEstimatedTokens`
- `lastActualPromptTokens`
- `usageRatio`
- `status`

- [ ] **Step 3: Implement controller refresh**

Use existing refresh style:

- `connectOnce`: after `loadThreadHistory`, optionally load current thread context if any
- `openThread`: load selected thread context
- `refreshRunRecordsIfVisible`: also refresh context if thread exists

- [ ] **Step 4: Run tests**

```powershell
cd desktop
.\gradlew.bat test --tests "*ChatControllerTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt
git commit -m "feat(p3-2): 刷新桌面上下文窗口状态"
```

### Task 14: Show context indicators in UI

**Files:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBarTest.kt`

- [ ] **Step 1: Write failing UI tests**

Test label formatting:

- no snapshot: `上下文 --`
- estimated only: `上下文 9.5k / 128k`
- actual prompt tokens present: prefer actual prompt tokens
- over threshold: warning tone

- [ ] **Step 2: Implement composer chip**

Add a non-clickable chip in `ComposerContextBar` after model selector or before model selector. Keep it compact and avoid text overflow.

- [ ] **Step 3: Implement runtime detail display**

In selected turn detail, show:

- snapshot id
- estimated tokens
- actual prompt tokens
- included/excluded counts
- window ordinal

Do not render full `envelopeJson` in UI by default; it may be large and noisy.

- [ ] **Step 4: Run UI tests**

```powershell
cd desktop
.\gradlew.bat test --tests "*ComposerContextBarTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBarTest.kt
git commit -m "feat(p3-2): 展示上下文窗口状态"
```

## Chunk 5: Verification and Docs

### Task 15: Run backend focused verification

**Files:** no code edits expected.

- [ ] **Step 1: Run focused backend tests**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextWindowRuntimeTest,ContextualPromptRendererTest,ContextSnapshotPersistenceTest,ContextStatusServiceTest,ContextStatusHandlerTest,ContextSnapshotGetHandlerTest,AgentLoopContextRuntimeTest,RunRecordServiceTest,SchemaCommentsCoverageTest" test
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Fix any failing test**

Do not weaken tests or add `@Disabled`.

### Task 16: Run desktop focused verification

**Files:** no code edits expected.

- [ ] **Step 1: Run focused desktop tests**

```powershell
cd desktop
.\gradlew.bat test --tests "*ContextModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*ComposerContextBarTest"
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Fix any failing test**

Keep UI labels compact.

### Task 17: Run full verification

**Files:** no code edits expected.

- [ ] **Step 1: Run backend full verification**

```powershell
cd backend
.\mvnw.cmd clean verify
```

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run desktop full verification**

```powershell
cd desktop
.\gradlew.bat test
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Desktop smoke test**

```powershell
cd desktop
.\gradlew.bat run --no-daemon
```

Expected:

- window starts
- context chip visible
- normal message can run
- run details shows context snapshot for completed turn

### Task 18: Update docs and commit final state

**Files:**
- Modify: `docs/superpowers/plans/p3-2-context-window-runtime/codex-handoff.md`
- Modify: `docs/superpowers/plans/p3-task-index.md`
- Modify: `docs/superpowers/plans/p3-master.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update handoff**

Record:

- implementation summary
- new tables and protocols
- verification commands and results
- remaining P3-3 boundary

- [ ] **Step 2: Update project checkpoint docs**

Mark P3-2 completed only after focused and full verification pass.

- [ ] **Step 3: Commit**

```powershell
git add docs/superpowers/plans/p3-2-context-window-runtime docs/superpowers/plans/p3-task-index.md docs/superpowers/plans/p3-master.md AGENTS.md CLAUDE.md
git commit -m "docs(p3-2): 更新当前窗口运行时交接"
```

## 7. Acceptance Criteria

- `ContextWindowRuntime` is called for every normal `turn/start` before model invocation.
- Raw user input remains the only user-visible `UserMessageItem`; envelope JSON is not written to `bq_items`.
- Each normal model call persists at least one `PRE_MODEL` `ContextSnapshot`.
- Latest snapshot can be queried by `context/status`, `context/snapshot/get`, and `run/turn/get`.
- Snapshot records included/excluded item counts, token estimate, model context window, threshold and capability catalog summary.
- Actual prompt tokens are backfilled when `TurnObservationContext` has usage data.
- Desktop composer shows a compact context chip.
- Desktop run details can show context snapshot summary for selected turn.
- P3-2 does not implement automatic compaction or long-term memory.
- All new Java/Kotlin public types and important fields have Chinese teaching comments.
- All new database tables and fields have SQL Chinese comments and `bq_schema_comments`.

## 8. Verification Commands

Focused backend:

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextWindowRuntimeTest,ContextualPromptRendererTest,ContextSnapshotPersistenceTest,ContextStatusServiceTest,ContextStatusHandlerTest,ContextSnapshotGetHandlerTest,AgentLoopContextRuntimeTest,RunRecordServiceTest,SchemaCommentsCoverageTest" test
```

Full backend:

```powershell
cd backend
.\mvnw.cmd clean verify
```

Focused desktop:

```powershell
cd desktop
.\gradlew.bat test --tests "*ContextModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*ComposerContextBarTest"
```

Full desktop:

```powershell
cd desktop
.\gradlew.bat test
```

Smoke:

```powershell
cd desktop
.\gradlew.bat run --no-daemon
```

## 9. Risk Notes

- The main technical risk is SAA `ReactAgent.stream(...)` input shape. The plan intentionally introduces `ContextualPromptRenderer` as a compatibility seam first. If implementation confirms `ReactAgent` can safely accept Spring AI `Prompt` or `List<Message>`, replace renderer with a typed input path in the same seam.
- Context token estimate is approximate in P3-2. Do not use it to drop history yet; P3-3 will implement compaction/drop policy.
- `RESUME_PRE_MODEL` should not be added to approval resume until there is a real second model call before tool execution. P3-2 can store normal `PRE_MODEL` only for the first call and use `POST_USAGE` for token backfill.
- Avoid circular dependencies: `context.runtime` may depend on conversation repositories and strategy helper interfaces, but Agent/persistence packages should not depend on desktop or API DTOs.
- Do not return full `envelopeJson` to desktop by default; it can be large and may contain sensitive local context. Use `context/snapshot/get` for explicit debug details only.
