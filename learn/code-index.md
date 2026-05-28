# 🔍 代码反查索引

> 你在 IDEA 里打开一个源文件，想知道哪一章讲过它、或它属于哪个阶段？这里就是答案。
> 索引随章节扩展。**已写章节会有"在哪一章讲过"链接；未写章节会标注"待写章节"和对应阶段**，让你能直接跳到 plan 文档了解设计意图。

---

## 桌面端（Kotlin）

### 状态管理（P1-4）

| 类 / 文件 | 路径 | 章节 / 来源 |
|---|---|---|
| `AppState` | [`desktop/.../state/AppState.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt) | [02-reading-path/12 §1](02-reading-path/12-desktop-state.md#1-appstate--所有界面状态的快照) ✅ |
| `ChatReducer` | [`desktop/.../state/ChatReducer.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt) | [02-reading-path/12 §3](02-reading-path/12-desktop-state.md#3-chatreducer--纯函数归约) ✅ |
| `ChatController` | [`desktop/.../state/ChatController.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt) | [02-reading-path/12 §4](02-reading-path/12-desktop-state.md#4-chatcontroller--协调副作用与状态) ✅ |
| `UiModels.kt`（`ChatMessage`、`AgentEvent`、`PendingApproval` 等）| [`desktop/.../state/UiModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt) | [02-reading-path/12 §2](02-reading-path/12-desktop-state.md#2-uimodels--用-sealed-interface-表达几种可能) ✅ |
| `ChatReducerTest` | [`desktop/.../state/ChatReducerTest.kt`](../desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatReducerTest.kt) | [02-reading-path/12 §6](02-reading-path/12-desktop-state.md#-动手实操) ✅ |

### 协议模型（P1-4 + P2 + P3 累积）

| 文件 | 包含的协议模型 | 引入阶段 |
|---|---|---|
| [`protocol/ProtocolJson.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ProtocolJson.kt) | JSON-RPC envelope、sealed item 多态解析 | P1-4 |
| [`protocol/ThreadModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ThreadModels.kt) | `ThreadItem` sealed + 12 种 item 子类 | P1-4 |
| [`protocol/ApprovalModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ApprovalModels.kt) | 审批请求 / 响应 / 三档反馈 | P1-4 |
| [`protocol/ProviderModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ProviderModels.kt) | Provider 列表 / 选中 / 测试连接 | P1-4 |
| [`protocol/ThreadHistoryModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ThreadHistoryModels.kt) | 多会话列表 / 加载 / 归档 | P2-2 |
| [`protocol/SettingsModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/SettingsModels.kt) | Provider 编辑 / 沙箱模式 / 审批策略 | P2-3 |
| [`protocol/RunRecordModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/RunRecordModels.kt) | 历史 turn / 工具调用 / 审批审计 | P2-4 |
| [`protocol/ObservabilityModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ObservabilityModels.kt) | 本地统计快照 / 工具用量 | P2-5 |
| [`protocol/McpModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/McpModels.kt) | MCP server / 工具列表 / 刷新 | P2-6 |
| [`protocol/ContextModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ContextModels.kt) | 上下文窗口状态 / 快照 / 压缩 | P3-2 / P3-3 |
| [`protocol/MemoryModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/MemoryModels.kt) | 长期记忆设置 / 状态 / 检索 | P3-4 |
| [`protocol/CapabilityModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/CapabilityModels.kt) | 能力目录 / 搜索 / 设置 | P3-5 |
| [`protocol/SkillModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/SkillModels.kt) | Skill 列表 / 加载正文 | P3-5 |

### UI 层（P1-4 + P2 累积）

| 文件 | 用途 | 引入阶段 |
|---|---|---|
| [`ui/chat/ChatScreen.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ChatScreen.kt) | 聊天主区入口 | P1-4 |
| [`ui/chat/MessageList.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/MessageList.kt) | 消息列表 + 流式更新 | P1-4 |
| [`ui/chat/Composer.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/Composer.kt) | 输入框 / Enter 发送 | P1-4 |
| [`ui/chat/ComposerContextBar.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt) | 上下文 chip（项目 / 模式 / 分支 / 权限 / 模型）| P1-4 |
| [`ui/chat/ProviderSelector.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ProviderSelector.kt) | 输入框附近的模型切换 | P1-4 |
| [`ui/approval/ApprovalDialog.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/approval/ApprovalDialog.kt) | 工具审批弹窗（approve/deny/edit/always）| P1-4 / P2-3 |
| [`ui/runtime/RuntimeDetailsPanel.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt) | 右侧运行详情（默认折叠）| P1-4 / P2-4 / P2-5 |
| [`ui/runtime/TurnSummaryBar.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/TurnSummaryBar.kt) | 本轮成本反馈条 | P1-4 |
| [`ui/settings/SettingsPanel.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsPanel.kt) | Provider 编辑 + 沙箱 + 审批 + 记忆 + 能力开关 | P1-4 / P2-3 / P3-4 / P3-5 |

---

## 后端（Java）

### Agent 主链路（P1-3a/3b）

| 类 / 文件 | 路径 | 章节 / 来源 |
|---|---|---|
| `AgentLoop` | [`agent/AgentLoop.java`](../backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java) | 待写章节（P1-3a master plan §M3a）|
| `ReActStrategy` | [`agent/ReActStrategy.java`](../backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java) | 待写章节 |
| `BaBiQTokenUsageHook` | [`hook/BaBiQTokenUsageHook.java`](../backend/src/main/java/com/wzx/babiq/server/hook/BaBiQTokenUsageHook.java) | 待写章节（hook 范例）|
| `BaBiQSandboxInterceptor` | [`interceptor/BaBiQSandboxInterceptor.java`](../backend/src/main/java/com/wzx/babiq/server/interceptor/BaBiQSandboxInterceptor.java) | 待写章节 |
| `PathGuard` / `SandboxPolicy` | [`sandbox/`](../backend/src/main/java/com/wzx/babiq/server/sandbox/) | 待写章节 |

### 工具系统（P1-3a + P3-5）

| 类 / 文件 | 路径 | 阶段 |
|---|---|---|
| `ToolRegistry` | [`tool/ToolRegistry.java`](../backend/src/main/java/com/wzx/babiq/server/tool/ToolRegistry.java) | P1-3a |
| 6 个本地工具 | [`tool/impl/`](../backend/src/main/java/com/wzx/babiq/server/tool/impl/)（`ReadFileTool`、`WriteFileTool`、`ExecShellTool`、`ListDirTool`、`GrepTool`、`ApplyPatchTool`）| P1-3a |
| `ToolSearchTool` | [`tool/impl/ToolSearchTool.java`](../backend/src/main/java/com/wzx/babiq/server/tool/impl/ToolSearchTool.java) | P3-5 |

### 安全 + 可观测（P1-3b）

| 类 / 文件 | 路径 | 阶段 |
|---|---|---|
| `Spotlighter` | [`security/Spotlighter.java`](../backend/src/main/java/com/wzx/babiq/server/security/Spotlighter.java) | P1-3b |
| `SystemPromptSecurityRule` | [`security/SystemPromptSecurityRule.java`](../backend/src/main/java/com/wzx/babiq/server/security/SystemPromptSecurityRule.java) | P1-3b |
| `SpotlightingToolInterceptor` | [`interceptor/SpotlightingToolInterceptor.java`](../backend/src/main/java/com/wzx/babiq/server/interceptor/SpotlightingToolInterceptor.java) | P1-3b |
| `ToolObservationInterceptor` | [`interceptor/ToolObservationInterceptor.java`](../backend/src/main/java/com/wzx/babiq/server/interceptor/ToolObservationInterceptor.java) | P1-3b |
| `TurnSummaryEmitter`、`StructuredTurnLogger` | [`observability/`](../backend/src/main/java/com/wzx/babiq/server/observability/) | P1-3b |
| `BaBiQMetrics` | [`observability/BaBiQMetrics.java`](../backend/src/main/java/com/wzx/babiq/server/observability/BaBiQMetrics.java) | P1-3b |

### 协议层（P1-1 + 历次扩展）

| 类 / 文件 | 路径 | 用途 |
|---|---|---|
| `JsonRpcWebSocketHandler` | [`api/JsonRpcWebSocketHandler.java`](../backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java) | WebSocket 入口 |
| `JsonRpcDispatcher` | [`api/JsonRpcDispatcher.java`](../backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java) | method → handler 路由 |
| JSON-RPC handlers | [`api/method/`](../backend/src/main/java/com/wzx/babiq/server/api/method/)（30+ 个 handler，每个对应一个 JSON-RPC method）| 各阶段累积 |

### 对话状态（P1-1 + P2-2 累积）

| 类 / 文件 | 路径 | 阶段 |
|---|---|---|
| `Thread` / `Turn` / `TurnStatus` | [`conversation/`](../backend/src/main/java/com/wzx/babiq/server/conversation/) | P1-1 |
| `ConversationService` | [`conversation/ConversationService.java`](../backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java) | P1-1 / P2-2 扩展 |
| `ConversationEventRecorder` | [`conversation/ConversationEventRecorder.java`](../backend/src/main/java/com/wzx/babiq/server/conversation/ConversationEventRecorder.java) | P2-2（事件落库）|
| 12 种 `ThreadItem` 子类 | [`conversation/items/`](../backend/src/main/java/com/wzx/babiq/server/conversation/items/) | 各阶段累积 |

### 持久化（P2-1 起）

| 类 / 文件 | 路径 | 阶段 |
|---|---|---|
| Flyway migrations | [`db/migration/V2-V12`](../backend/src/main/resources/db/migration/) | V2=P2-1 / V3=P2-3 / V4=P2-4 / V5=P2-6 / V6=token-only / V7=P3-2 / V8=P3-3 / V9=P3-3a / V10=P3-4 / V11=P3-5 / V12=P3-5a |
| MyBatis-Plus entities | [`persistence/entity/`](../backend/src/main/java/com/wzx/babiq/server/persistence/entity/)（17+ 个 Entity）| 各阶段累积 |
| Mapper 接口 | [`persistence/mapper/`](../backend/src/main/java/com/wzx/babiq/server/persistence/mapper/) | 各阶段累积 |
| SQLite Repository adapter | [`persistence/service/`](../backend/src/main/java/com/wzx/babiq/server/persistence/service/) | 各阶段累积 |
| `SchemaCommentsCoverageTest` | [`persistence/SchemaCommentsCoverageTest`](../backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java) | 校验所有业务字段都有中文注释 |

### Provider / 设置系统（P2-3）

| 类 / 文件 | 路径 |
|---|---|
| `ProviderSettingsService` | [`settings/ProviderSettingsService.java`](../backend/src/main/java/com/wzx/babiq/server/settings/ProviderSettingsService.java) |
| `AppSettingsService` | [`settings/AppSettingsService.java`](../backend/src/main/java/com/wzx/babiq/server/settings/AppSettingsService.java) |
| `LocalKeyStoreSecretStore`（JDK JCEKS 加密 API Key）| [`settings/LocalKeyStoreSecretStore.java`](../backend/src/main/java/com/wzx/babiq/server/settings/LocalKeyStoreSecretStore.java) |
| `ApprovalPolicyService` | [`settings/ApprovalPolicyService.java`](../backend/src/main/java/com/wzx/babiq/server/settings/ApprovalPolicyService.java) |
| `SandboxSettingsService` | [`settings/SandboxSettingsService.java`](../backend/src/main/java/com/wzx/babiq/server/settings/SandboxSettingsService.java) |

### 启动恢复（P2-4）

| 类 / 文件 | 路径 |
|---|---|
| `TurnRecoveryService`（RUNNING/SENDING → INTERRUPTED；WAITING_APPROVAL → EXPIRED）| [`recovery/TurnRecoveryService.java`](../backend/src/main/java/com/wzx/babiq/server/recovery/TurnRecoveryService.java) |
| `RecoveryStartupRunner` | [`recovery/RecoveryStartupRunner.java`](../backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryStartupRunner.java) |
| `RunRecordService` / `BaBiQMetricsSnapshot`（本地可观测）| [`observability/`](../backend/src/main/java/com/wzx/babiq/server/observability/) |

### MCP Client（P2-6）

| 类 / 文件 | 路径 |
|---|---|
| `McpClientManager` | [`mcp/McpClientManager.java`](../backend/src/main/java/com/wzx/babiq/server/mcp/McpClientManager.java) |
| `SdkMcpClientConnector`（封装官方 MCP Java SDK 1.1.3）| [`mcp/SdkMcpClientConnector.java`](../backend/src/main/java/com/wzx/babiq/server/mcp/SdkMcpClientConnector.java) |
| `McpToolCatalog` / `McpToolAdapter` | [`mcp/`](../backend/src/main/java/com/wzx/babiq/server/mcp/) |
| `McpPersistenceService` | [`mcp/McpPersistenceService.java`](../backend/src/main/java/com/wzx/babiq/server/mcp/McpPersistenceService.java) |

### 上下文工程（P3-1 / P3-2 / P3-3 / P3-3a）

| 类 / 文件 | 路径 | 阶段 |
|---|---|---|
| `ContextAssembler`（分层 envelope 组装）| [`context/ContextAssembler.java`](../backend/src/main/java/com/wzx/babiq/server/context/ContextAssembler.java) | P3-1 |
| `CapabilityCatalogAssembler`（能力目录摘要）| [`context/CapabilityCatalogAssembler.java`](../backend/src/main/java/com/wzx/babiq/server/context/CapabilityCatalogAssembler.java) | P3-1 |
| `ContextWindowRuntime`（每轮前调用，生成临时输入）| [`context/runtime/ContextWindowRuntime.java`](../backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java) | P3-2 |
| `ContextualPromptRenderer` | [`context/runtime/ContextualPromptRenderer.java`](../backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextualPromptRenderer.java) | P3-2 |
| `ContextCompactionService`（短期压缩主链路）| [`context/compaction/ContextCompactionService.java`](../backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionService.java) | P3-3 / P3-3a |
| `ContextBudgetPolicy`（1M cap + 75% 阈值 + safety margin）| [`context/compaction/ContextBudgetPolicy.java`](../backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextBudgetPolicy.java) | P3-3 |
| `SpringAiContextCompactionStrategy`（用 ChatClient.entity() 生成摘要）| [`context/compaction/SpringAiContextCompactionStrategy.java`](../backend/src/main/java/com/wzx/babiq/server/context/compaction/SpringAiContextCompactionStrategy.java) | P3-3 |
| `ContextCompactionRecoveryService`（启动恢复 ORPHANED/INTERRUPTED）| [`context/compaction/ContextCompactionRecoveryService.java`](../backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionRecoveryService.java) | P3-3a |
| `ContextManualCompactionService`（用户手动压缩）| [`context/compaction/ContextManualCompactionService.java`](../backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextManualCompactionService.java) | P3-3 |

### 长期记忆（P3-4）

| 类 / 文件 | 路径 |
|---|---|
| `LongTermMemoryPipeline`（Phase 1 + Phase 2 异步流水线）| [`memory/pipeline/LongTermMemoryPipeline.java`](../backend/src/main/java/com/wzx/babiq/server/memory/pipeline/LongTermMemoryPipeline.java) |
| `MemorySecretRedactor`（API key / token / private key 过滤）| [`memory/redaction/MemorySecretRedactor.java`](../backend/src/main/java/com/wzx/babiq/server/memory/redaction/MemorySecretRedactor.java) |
| `MemoryArtifactMirror`（Markdown mirror 文件管理）| [`memory/artifact/MemoryArtifactMirror.java`](../backend/src/main/java/com/wzx/babiq/server/memory/artifact/MemoryArtifactMirror.java) |
| `MemoryPollutionService`（external context / MCP 污染判定）| [`memory/MemoryPollutionService.java`](../backend/src/main/java/com/wzx/babiq/server/memory/MemoryPollutionService.java) |
| `LongTermMemoryReadService`（read path 注入 `memory_summary`）| [`memory/LongTermMemoryReadService.java`](../backend/src/main/java/com/wzx/babiq/server/memory/LongTermMemoryReadService.java) |
| `MemoryConsolidationStrategy` + `SpringAiStructuredMemoryConsolidationStrategy`（Phase 2 归并）| [`memory/pipeline/`](../backend/src/main/java/com/wzx/babiq/server/memory/pipeline/) |
| `MemoryStatusService` | [`memory/MemoryStatusService.java`](../backend/src/main/java/com/wzx/babiq/server/memory/MemoryStatusService.java) |

### 能力装配 + 检索（P3-5 / P3-5a）

| 类 / 文件 | 路径 | 阶段 |
|---|---|---|
| `CapabilityCatalogSyncService`（local + MCP + Skill 同步入目录）| [`capability/CapabilityCatalogSyncService.java`](../backend/src/main/java/com/wzx/babiq/server/capability/CapabilityCatalogSyncService.java) | P3-5 |
| `CapabilityExposurePlanner`（VISIBLE / DEFERRED / DISABLED 决策）| [`capability/CapabilityExposurePlanner.java`](../backend/src/main/java/com/wzx/babiq/server/capability/CapabilityExposurePlanner.java) | P3-5 |
| `LuceneCapabilitySearchService`（Apache Lucene + BM25）| [`capability/LuceneCapabilitySearchService.java`](../backend/src/main/java/com/wzx/babiq/server/capability/LuceneCapabilitySearchService.java) | P3-5a |
| `CapabilityAliasDictionary`（中文别名富化 16 个 token 类）| [`capability/CapabilityAliasDictionary.java`](../backend/src/main/java/com/wzx/babiq/server/capability/CapabilityAliasDictionary.java) | P3-5a 补强 |
| `CapabilityCatalogChangedEvent`（Spring Event，触发 Lucene 索引重建）| [`capability/CapabilityCatalogChangedEvent.java`](../backend/src/main/java/com/wzx/babiq/server/capability/CapabilityCatalogChangedEvent.java) | P3-5a |
| `LongTermMemoryRetrievalService`（lexical 记忆检索增强）| [`memory/retrieval/LongTermMemoryRetrievalService.java`](../backend/src/main/java/com/wzx/babiq/server/memory/retrieval/LongTermMemoryRetrievalService.java) | P3-5 |
| `LocalSkillRegistry` / `SkillContentLoader`（按需 Skill 加载）| [`skill/`](../backend/src/main/java/com/wzx/babiq/server/skill/) | P3-5 |

---

## Flyway Migration 阶段映射

| 文件 | 阶段 | 干什么 |
|---|---|---|
| `V2__create_p2_persistence_tables.sql` | P2-1 | 建 `bq_threads` / `bq_turns` / `bq_items` / `bq_turn_summaries` / `bq_provider_configs` 等业务表 |
| `V3__settings_provider_policy.sql` | P2-3 | Provider 编辑、沙箱、审批策略落库 |
| `V4__recovery_run_records.sql` | P2-4 | `bq_tool_calls` 工具调用记录表 |
| `V5__mcp_client.sql` | P2-6 | `bq_mcp_servers` / `bq_mcp_tools` |
| `V6__turn_summary_token_only.sql` | P2 收口 | TurnSummary 去掉价格字段 |
| `V7__context_window_runtime.sql` | P3-2 | `bq_context_windows` / `bq_context_snapshots` |
| `V8__context_short_term_compaction.sql` | P3-3 | `bq_context_summaries` / `bq_context_compactions` |
| `V9__context_compaction_audit_fields.sql` | P3-3a | 压缩记录补 10 个审计字段 |
| `V10__long_term_memory_pipeline.sql` | P3-4 | `bq_memory_jobs` / `bq_memory_candidates` / `bq_memory_artifacts` / `bq_memory_references` + `bq_threads.memory_mode` |
| `V11__capability_retrieval_control.sql` | P3-5 | `bq_capabilities` / `bq_capability_search_events` / `bq_memory_retrieval_events` |
| `V12__lucene_capability_search_comments.sql` | P3-5a | 刷新搜索策略字段中文说明 |

---

## 如何使用这份索引

1. **在 IDE 里看到一个类，不知道哪个阶段引入的**
   - 搜类名 → 看"引入阶段"列 → 跳 plan 文档了解设计意图

2. **想从一个功能模块全面了解**
   - 找对应的"阶段"分组 → 把整个包里的文件都列出来
   - 配合 `docs/superpowers/plans/pX-...` 的 plan 看为什么这么设计

3. **找到了类，想知道相关测试在哪**
   - 同样路径下找 `src/test/java/com/wzx/babiq/server/<同包>/`
   - 测试类名约定：`<ClassName>Test` 或 `<ClassName>IT`

4. **找 SQL 表是哪一阶段建的**
   - 看 Flyway Migration 阶段映射表
