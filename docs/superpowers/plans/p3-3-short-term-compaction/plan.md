# P3-3 短期记忆与上下文压缩实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:executing-plans` to execute this plan.

**Goal:** 在 P3-2 当前窗口运行时之上，实现 Codex 级短期记忆/上下文压缩：当 thread 的模型可见上下文接近预算时，把旧历史压缩成可审计、可恢复的短期摘要，安装为新的 active window，再继续用当前用户输入驱动 Agent。

**Architecture:** BaBiQ 保留自己的 thread/turn/item 事实源和 SQLite 审计链路，复用 Spring AI 的 `ChatModel`、`ChatClient`、`ChatMemory` 思路和结构化输出能力，评估 Spring AI Alibaba Agent Framework 的 `ContextEditingInterceptor` / `SummarizationHook` 作为压缩策略实现。压缩结果不回写为普通 assistant 对话，只作为 `ShortTermSummary` 层和 `ContextCompactionItem` 事件进入上下文窗口。

**Tech Stack:** Java 21, Spring Boot, Spring AI, Spring AI Alibaba Agent Framework, SQLite, MyBatis-Plus, Flyway, Kotlin Compose Desktop, JSON-RPC 2.0.

---

## 1. 当前证据

### 1.1 BaBiQ 当前实现

- P3-2 已接入 `ContextWindowRuntime`，每轮模型调用前会读取 SQLite 历史、生成 `ContextEnvelope`、保存 `ContextSnapshot`。
- `ContextAssembler` 已有分层优先级规则：`current_turn` 是权威输入，`recent_history`、摘要、记忆和能力目录都只能作为参考。
- `ShortTermSummary` 模型已存在，但 P3-2 传入为 `null`，还没有 summary 持久化、安装和替换逻辑。
- `bq_context_windows.active_summary_id`、`window_ordinal` 已为 P3-3 预留；当前 `window_ordinal` 不会递增。
- `bq_context_snapshots` 已记录 `model_context_window`、`auto_compact_threshold`、估算 token、真实 prompt token、included/excluded item，适合作为压缩触发和审计数据源。
- P3-2 当前自动压缩阈值是 `modelWindow * 0.70`，P3-3 需要升级成统一预算策略。

### 1.2 Codex 源码结论

参考源码位于 `E:\wzx\codex`：

- `codex-rs/app-server/README.md` 提供 `thread/compact/start`，说明 Codex 支持手动压缩，并会产生 `contextCompaction` item。
- `codex-rs/app-server-protocol/src/protocol/v2/config.rs` 定义 `model_context_window`、`model_auto_compact_token_limit`、`compact_prompt`、`pre_compact`、`post_compact`。
- `codex-rs/core/src/session/turn.rs` 在 turn 执行前和执行中检查 token 状态，必要时触发自动压缩。
- `codex-rs/core/src/compact.rs`、`compact_remote.rs`、`compact_remote_v2.rs` 的核心语义是生成 replacement history，而不是只生成一条普通摘要消息。
- `codex-rs/rollout-trace/src/compaction.rs` 会记录 `input_history` 和 `replacement_history`，便于回放和排查。
- `codex-rs/core/src/session/rollout_reconstruction.rs` 在重建上下文时使用 `replacement_history`，压缩点是历史窗口的语义边界。

本阶段应吸收的 Codex 设计点：

- 压缩是当前窗口替换，不是把摘要追加成普通聊天消息。
- 原始历史仍然保存在事实源中，压缩只改变下一轮模型可见窗口。
- 当前用户输入不能被 pre-turn 压缩吃掉；先压缩旧历史，再把本轮输入作为权威层注入。
- 压缩本身必须产生可见、可审计、可恢复的运行事件。
- 自动压缩阈值应来自模型窗口和运行预算，而不是硬编码固定值。

### 1.3 Context7 查证结论

- Spring AI `ChatMemory` / `MessageWindowChatMemory` 提供按 conversation id 维护窗口历史的标准思路，但它更适合消息窗口缓存，不适合作为 BaBiQ 的事实源。BaBiQ 仍以 `bq_items`、`bq_context_windows`、`bq_context_snapshots` 为权威数据。
- Spring AI `ChatClient` 支持 `entity(...)` 和 `BeanOutputConverter` 风格的结构化输出，可用于让压缩模型返回稳定的 Java record，例如 `CompactionSummaryPayload`。
- Spring AI Alibaba Agent Framework 已提供 ReactAgent、Hook、Interceptor、MemorySaver，并有 `ContextEditingInterceptor`、`LargeResultEvictionInterceptor`、`SummarizationHook` 等上下文治理组件。P3-3 可以把它们封装为 `ContextCompactionStrategy` 的候选实现，但不能让它们直接绕过 BaBiQ 的 SQLite 审计和 active window 安装流程。

---

## 2. 设计边界

### 2.1 P3-3 做什么

- 统一上下文预算策略：支持 1M 系统上限、provider/model 上限、输出预留和 75% 自动压缩阈值。
- 支持自动压缩和手动压缩。
- 生成短期摘要并持久化到 SQLite。
- 成功压缩后更新 thread 的 active window：`active_summary_id` 指向新摘要，`window_ordinal` 递增。
- 下一轮上下文装配时注入 active short-term summary，并排除被摘要覆盖的旧历史。
- 产生 `ContextCompactionItem`，让桌面端和运行详情能看到压缩发生过。
- 压缩失败必须可恢复：失败不能污染 active window，也不能阻断普通模型调用。

### 2.2 P3-3 不做什么

- 不做长期记忆提取、归并、VectorStore/RAG 检索；这些属于 P3-4。
- 不做按需 skill/MCP/tool schema 全量装配；这些属于 P3-5。
- 不使用 OpenAI/Codex 专用 `/responses/compact` API；BaBiQ 必须保持 provider-neutral。
- 不把 Spring AI `ChatMemory` 替换为事实源；它只能作为设计参考或局部适配能力。
- 不把压缩摘要伪装成 assistant 普通回答，避免污染对话历史。

---

## 3. 核心设计

### 3.1 上下文窗口预算

新增 `ContextBudgetPolicy`，所有阈值都从这里计算。

默认策略：

| 字段 | 默认值 | 说明 |
|---|---:|---|
| `systemContextWindowCap` | `1_000_000` | BaBiQ 系统级最大窗口，防止 provider 配置异常放大预算。 |
| `autoCompactRatio` | `0.75` | 超过有效输入预算 75% 触发自动压缩。 |
| `warningRatio` | `0.60` | 桌面端展示上下文压力提示，不触发压缩。 |
| `forceCompactRatio` | `0.90` | 极限保护；如果压缩失败且仍超过该值，降级裁剪旧 history。 |
| `reservedOutputMinTokens` | `8_192` | 为回答和工具调用保留的最小输出预算。 |
| `reservedOutputMaxTokens` | `64_000` | 避免 1M 窗口时输出预留过大。 |
| `runtimeSafetyRatio` | `0.05` | 给工具 schema、provider 额外包装和 tokenizer 误差留余量。 |

计算方式：

```text
effective_context_window = min(systemContextWindowCap, providerModelContextWindow)
reserved_output_tokens = clamp(effective_context_window * 0.10, 8192, 64000)
runtime_safety_margin = effective_context_window * 0.05
effective_input_budget = effective_context_window - reserved_output_tokens - runtime_safety_margin
auto_compact_threshold = floor(effective_input_budget * 0.75)
```

说明：

- 用户提出的 1M 最大窗口是合理的，但不能直接把 1M 全部塞给 prompt；必须预留输出和运行余量。
- P3-2 旧快照中的 70% 阈值不需要批量迁移；P3-3 后续快照按新策略写入即可。
- 如果 provider/model 配置窗口小于 1M，以 provider/model 为准。

### 3.2 短期摘要不是普通历史

压缩成功后，BaBiQ 的三类数据分工如下：

| 数据 | 存储位置 | 是否事实源 | 是否进入模型 |
|---|---|---|---|
| 原始 `ThreadItem` | `bq_items` | 是 | 被 active summary 覆盖的旧 item 默认不进入 |
| 压缩摘要 | `bq_context_summaries` | 是，作为上下文派生物 | 作为 `short_term_summary` 中优先级进入 |
| 压缩事件 | `bq_items` 中的 `ContextCompactionItem` + `bq_context_compactions` | 是，作为运行审计 | 不作为聊天语义进入模型 |

这能避免两类污染：

- 摘要不会被 UI 当成 assistant 回答。
- 压缩事件不会被后续模型当成新的业务指令。

### 3.3 active window 替换语义

压缩完成后：

1. `bq_context_summaries` 写入摘要正文、结构化摘要 JSON、覆盖 item 范围和 token 估算。
2. `bq_context_compactions` 写入本次压缩输入快照、触发类型、状态和前后 token。
3. `bq_context_windows.active_summary_id` 更新为新摘要 id。
4. `bq_context_windows.window_ordinal` 递增。
5. 下一次 `ContextWindowRuntime.prepare(...)` 重新读取 active summary。
6. `ContextAssembler` 把 summary 放入 `short_term_summary` 层，同时把被覆盖的旧 item 标记为 `REPLACED_BY_SUMMARY`。

这个设计对应 Codex 的 replacement history，但在 Java/Spring 架构中用显式表结构和 active summary 指针表达，便于审计和跨 provider 使用。

### 3.4 自动压缩触发点

P3-3 先实现 pre-turn 自动压缩：

1. `ContextWindowRuntime.prepare(...)` 读取当前 active window。
2. 用历史 item、active summary、能力目录和当前用户输入先做一次轻量估算。
3. 如果估算 token 小于 `auto_compact_threshold`，正常装配。
4. 如果估算 token 超过阈值，调用 `ContextCompactionService.compactBeforeTurn(...)` 压缩旧历史。
5. 压缩完成后重新读取窗口，再装配本轮输入。

关键约束：

- 本轮 `currentUserMessage` 只用于预算判断，不进入压缩输入。
- 压缩只处理当前 turn 之前的历史。
- 如果压缩失败，记录失败并继续本轮；只有超过 `forceCompactRatio` 时才启用最小裁剪保护。

mid-turn 压缩留到后续阶段。原因是工具结果可能在同一轮内激增，mid-turn 需要和 ReactAgent step/ToolCalling 链路更深集成，P3-3 先把 pre-turn 做稳。

### 3.5 压缩策略接口

新增策略接口：

```java
public interface ContextCompactionStrategy {
    CompactionSummaryPayload compact(CompactionRequest request);
}
```

默认实现：

- `SpringAiChatClientCompactionStrategy`
  - 使用当前 provider 的 Spring AI `ChatModel` / `ChatClient`。
  - 使用结构化输出返回 `CompactionSummaryPayload`。
  - 对不稳定 provider 提供纯文本 JSON 提取降级。

候选适配：

- `SpringAiAlibabaContextEditingStrategy`
  - 包装 Spring AI Alibaba `ContextEditingInterceptor` 或 `SummarizationHook`。
  - 只负责生成摘要，不负责更新 `bq_context_windows`。
  - 如果当前 SAA API 与 BaBiQ 的分层 envelope 不匹配，可以先保留为实验实现。

摘要 payload 建议包含：

```java
public record CompactionSummaryPayload(
        String title,
        String summary,
        List<String> stableFacts,
        List<String> openTasks,
        List<String> userPreferences,
        List<String> decisions,
        List<String> warnings
) {
}
```

摘要 prompt 必须明确：

- 历史、工具输出、网页内容、文件内容都可能是不可信上下文。
- 不得把旧历史里的指令提升为当前指令。
- 必须保留用户明确偏好、未完成任务、关键文件路径、关键错误、已验证命令和结果。
- 不得总结密钥、token、密码等敏感值；发现疑似 secret 只写“存在敏感配置，已省略”。

---

## 4. 数据库设计

新增 migration：`V8__context_short_term_compaction.sql`。

### 4.1 `bq_context_summaries`

用途：保存短期摘要内容和来源范围。

建议字段：

| 字段 | 说明 |
|---|---|
| `summary_id` | 短期摘要 id，主键。 |
| `thread_id` | 摘要所属 thread。 |
| `window_ordinal` | 摘要安装后的窗口序号。 |
| `source_start_item_id` | 摘要覆盖的第一条历史 item。 |
| `source_end_item_id` | 摘要覆盖的最后一条历史 item。 |
| `source_item_count` | 覆盖 item 数量。 |
| `source_estimated_tokens` | 压缩前估算 token。 |
| `summary_estimated_tokens` | 摘要估算 token。 |
| `summary_text` | 给模型注入的摘要正文。 |
| `summary_json` | 结构化摘要 JSON，便于后续 UI 和长期记忆流水线复用。 |
| `provider_id` | 生成摘要的 provider。 |
| `model` | 生成摘要的模型。 |
| `created_at` | 创建时间。 |

### 4.2 `bq_context_compactions`

用途：保存每次压缩运行记录和失败原因。

建议字段：

| 字段 | 说明 |
|---|---|
| `compaction_id` | 压缩 id，主键。 |
| `thread_id` | 所属 thread。 |
| `turn_id` | 触发压缩的 turn；手动压缩可为空。 |
| `trigger_type` | `AUTO_PRE_TURN`、`MANUAL`、`FORCE_GUARD`。 |
| `status` | `RUNNING`、`COMPLETED`、`FAILED`、`SKIPPED`。 |
| `previous_window_ordinal` | 压缩前窗口序号。 |
| `next_window_ordinal` | 压缩成功后的窗口序号。 |
| `input_snapshot_id` | 压缩前上下文快照。 |
| `output_summary_id` | 生成的摘要 id。 |
| `replacement_snapshot_id` | 压缩后下一次装配快照，可为空。 |
| `model_context_window` | 本次使用的模型窗口。 |
| `effective_input_budget` | 本次有效输入预算。 |
| `auto_compact_threshold` | 本次自动压缩阈值。 |
| `estimated_tokens_before` | 压缩前估算 token。 |
| `estimated_tokens_after` | 压缩后估算 token。 |
| `failure_reason` | 失败或跳过原因。 |
| `started_at` | 开始时间。 |
| `completed_at` | 完成时间。 |

所有新增表和字段必须满足项目规则：

- SQL 中每张表和每个字段前写中文 `--` 注释。
- `bq_schema_comments` 同步写入非空中文说明。
- Entity 字段写中文注释。
- `SchemaCommentsCoverageTest` 必须覆盖新增字段。

---

## 5. 后端实现计划

### Task 1: 上下文预算策略

Files:

- `backend/src/main/java/com/wzx/babiq/server/context/budget/ContextBudgetProperties.java`
- `backend/src/main/java/com/wzx/babiq/server/context/budget/ContextBudgetPolicy.java`
- `backend/src/test/java/com/wzx/babiq/server/context/budget/ContextBudgetPolicyTest.java`

Steps:

1. 新增 `@ConfigurationProperties(prefix = "babiq.context.window")`，默认 1M cap、75% auto compact、输出预留和 safety margin。
2. 把 `ContextWindowRuntime` 中的 `AUTO_COMPACT_RATIO = 0.70` 替换为 `ContextBudgetPolicy`。
3. 测试 32k、128k、1M、超过 1M 的 provider 窗口计算。
4. 测试输出预留和 safety margin 不会让有效输入预算小于 0。

### Task 2: 压缩持久化模型

Files:

- `backend/src/main/resources/db/migration/V8__context_short_term_compaction.sql`
- `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSummaryRecord.java`
- `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextCompactionRecord.java`
- `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSummaryRepository.java`
- `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextCompactionRepository.java`
- 对应 MyBatis-Plus Entity / Mapper / Adapter。

Steps:

1. 新增两张表及完整中文注释。
2. 新增 repository 接口，Agent 核心只依赖 repository，不直接依赖 Mapper。
3. 支持按 `summary_id`、`thread_id` 查询 active summary。
4. 支持创建压缩记录、标记完成、标记失败。
5. 更新 `SchemaCommentsCoverageTest`。

### Task 3: 压缩输入选择

Files:

- `backend/src/main/java/com/wzx/babiq/server/context/compaction/CompactionSourceSelector.java`
- `backend/src/test/java/com/wzx/babiq/server/context/compaction/CompactionSourceSelectorTest.java`

Steps:

1. 从 `bq_items` 中选择当前 turn 之前的 user/assistant 完整文本。
2. 跳过 `TurnSummaryItem`、`ContextCompactionItem`、不完整 assistant delta 和空文本。
3. 如果已有 active summary，则只选择 active summary 之后的新历史，避免重复压缩。
4. 当前用户输入只能参与预算判断，不作为待压缩历史。
5. 返回 source item 范围、估算 token、压缩输入文本。

### Task 4: Spring AI 压缩策略

Files:

- `backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionStrategy.java`
- `backend/src/main/java/com/wzx/babiq/server/context/compaction/SpringAiChatClientCompactionStrategy.java`
- `backend/src/main/java/com/wzx/babiq/server/context/compaction/CompactionPromptFactory.java`
- `backend/src/main/java/com/wzx/babiq/server/context/compaction/CompactionSummaryPayload.java`
- `backend/src/test/java/com/wzx/babiq/server/context/compaction/SpringAiChatClientCompactionStrategyTest.java`

Steps:

1. 使用 Spring AI `ChatClient` 构造压缩请求。
2. 优先用 `entity(CompactionSummaryPayload.class)` 或 `BeanOutputConverter` 解析结构化输出。
3. 对不支持结构化输出的 provider，降级为纯文本 JSON 提取。
4. 压缩 prompt 必须包含污染控制、secret 省略和优先级规则。
5. 测试结构化输出成功、纯文本降级、空摘要失败、secret redaction 指令存在。

### Task 5: 压缩服务和事务安装

Files:

- `backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionService.java`
- `backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionResult.java`
- `backend/src/test/java/com/wzx/babiq/server/context/compaction/ContextCompactionServiceTest.java`

Steps:

1. `compactBeforeTurn(...)` 在模型调用前执行自动压缩。
2. 模型压缩调用在数据库事务外执行，避免长事务。
3. 模型返回后，在一个事务中写 summary、完成 compaction、更新 `bq_context_windows`。
4. 更新窗口时必须校验 `previous_window_ordinal`，避免并发 turn 覆盖新窗口。
5. 成功后写入 `ContextCompactionItem`，用于 UI 和运行详情。
6. 失败时只标记 compaction failed，不更新 active summary，不递增 ordinal。

### Task 6: Runtime 自动触发和装配替换

Files:

- `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- `backend/src/main/java/com/wzx/babiq/server/context/ContextAssembler.java`
- `backend/src/main/java/com/wzx/babiq/server/context/model/ContextExclusionReason.java`
- `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeCompactionTest.java`
- `backend/src/test/java/com/wzx/babiq/server/context/ContextAssemblerCompactionTest.java`

Steps:

1. `ContextWindowRuntime.prepare(...)` 先查询预算状态，超过阈值时调用 `ContextCompactionService`。
2. 压缩后重新加载 active window 和 active summary。
3. 把 active summary 传入 `ContextAssemblyInput.shortTermSummary()`。
4. `ContextAssembler` 将 summary 放入 `short_term_summary` 层。
5. 被 summary 覆盖的旧 item 不进入 `recent_history`，snapshot reason 记为 `REPLACED_BY_SUMMARY`。
6. 本轮 `current_turn` 始终保留为权威层。

### Task 7: JSON-RPC 和桌面端

Backend files:

- `backend/src/main/java/com/wzx/babiq/server/api/method/ContextCompactHandler.java`
- `backend/src/main/java/com/wzx/babiq/server/context/ContextStatusService.java`
- `backend/src/test/java/com/wzx/babiq/server/api/method/ContextCompactionHandlersTest.java`

Desktop files:

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ContextModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`

Steps:

1. 新增 `context/compact` 手动压缩接口。
2. `context/status` 返回 active summary、window ordinal、pressure、最近压缩状态。
3. 桌面上下文 chip 展示 `正常`、`接近上限`、`已压缩 N 次`。
4. 运行详情展示 compaction 记录和前后 token。
5. 渲染 `ContextCompactionItem`，但不把摘要正文当作普通 assistant 回复展示。

### Task 8: 恢复和观测

Files:

- `backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionRecoveryService.java`
- `backend/src/test/java/com/wzx/babiq/server/context/compaction/ContextCompactionRecoveryServiceTest.java`

Steps:

1. 启动时扫描 `RUNNING` compaction，标记为 `FAILED`，不安装 summary。
2. 如果发现 summary 已写入但 window 未更新，只有在 compaction 状态和 ordinal 校验都满足时才允许补偿安装；否则标记失败并保留人工审计。
3. `run/turn/get` 和本地观测中能看到压缩成功/失败次数。
4. 压缩失败不影响普通 turn 恢复语义。

---

## 6. 验证计划

后端专项：

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextBudgetPolicyTest,CompactionSourceSelectorTest,SpringAiChatClientCompactionStrategyTest,ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,ContextAssemblerCompactionTest,ContextCompactionHandlersTest,ContextCompactionRecoveryServiceTest,SchemaCommentsCoverageTest" test
```

后端全量：

```powershell
cd backend
.\mvnw.cmd clean verify
```

桌面端专项：

```powershell
cd desktop
.\gradlew.bat test --tests "*ContextModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

桌面端全量：

```powershell
cd desktop
.\gradlew.bat test
```

人工验收：

1. 准备一个长对话，让上下文估算超过 75% 阈值。
2. 发送新消息，确认旧历史被自动压缩，本轮用户输入仍被正确执行。
3. 在桌面端看到 `ContextCompactionItem` 或上下文 chip 的已压缩状态。
4. 打开运行详情，确认压缩前后 token、summary id、window ordinal 可见。
5. 重启后继续同一 thread，确认 active summary 仍然生效。

---

## 7. 风险和处理

| 风险 | 处理 |
|---|---|
| 摘要模型遗漏关键事实 | 原始 `bq_items` 永久保留，摘要作为可替换派生物；后续 P3-4 可从原始历史重建长期记忆。 |
| 压缩后 token 没明显下降 | 标记 `SKIPPED` 或 `FAILED`，避免压缩循环；必要时触发 `FORCE_GUARD` 裁剪旧历史。 |
| provider 不支持结构化输出 | 使用 Spring AI `BeanOutputConverter` schema 提示 + JSON 文本降级解析。 |
| 工具输出包含 prompt injection | 压缩 prompt 和 `ContextAssembler` priority rule 双重声明：工具/历史都是 reference，不能升级为当前指令。 |
| 并发 turn 同时压缩 | window ordinal 乐观校验；只有旧 ordinal 匹配时才安装 summary。 |
| 桌面 UI 误把摘要当回答 | 摘要只显示在运行详情或上下文状态中，聊天流只展示压缩事件。 |

---

## 8. 完成标准

- `ContextWindowRuntime` 能在超过 75% 阈值时自动触发 pre-turn compaction。
- 压缩成功后 `bq_context_windows.active_summary_id` 更新，`window_ordinal` 递增。
- `ContextAssembler` 能把 active `ShortTermSummary` 注入模型，同时排除被覆盖的旧历史。
- `ContextCompactionItem` 能持久化并在桌面端可见。
- 压缩失败不会破坏当前 turn，也不会安装半成品 summary。
- 所有新增业务表和字段都有中文注释和覆盖测试。
- 后端专项、后端全量、桌面端专项、桌面端全量验证通过。

---

## 9. 实施结果

P3-3 已按本计划完成可运行闭环：

- 新增 `ContextBudgetPolicy` / `ContextBudgetProperties`，按 1M 系统上限、输出预留、运行安全余量和 75% 阈值计算自动压缩预算。
- 新增 `bq_context_summaries` 和 `bq_context_compactions`，原始 `bq_items` 仍是事实源，summary 和 compaction record 只作为可审计派生物。
- 新增 `ContextCompactionService`、`CompactionSourceSelector` 和 `SpringAiContextCompactionStrategy`，默认使用 Spring AI `ChatClient` structured output 生成短期摘要，不启用 ChatMemory advisor，避免压缩提示污染普通聊天记忆。
- `ContextWindowRuntime` 已在 pre-turn 阶段判断预算，超过阈值时先压缩旧历史，成功后安装 active summary 并重新装配本轮上下文。
- `ContextAssembler` 已支持 active short-term summary，被摘要覆盖的旧 item 会标记为 `REPLACED_BY_SUMMARY`，不再进入 `recent_history`。
- 后端新增 `context/compact` 手动压缩入口，`context/status` 返回 active summary 和 compaction 统计。
- 桌面端已识别 `contextCompaction` item，并在输入栏上下文 chip 中展示 `已压缩 N 次` 或压缩失败状态。

本阶段刻意没有实现 P3-4 长期记忆异步提取/归并，也没有把 Spring AI Alibaba 的 ContextEditing/Summarization 组件直接接管 BaBiQ 的事实源。它们后续仍应作为 `ContextCompactionStrategy` 或长期记忆流水线的可替换实现，而不是绕过 SQLite 审计链路。
