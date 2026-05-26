# P3-4 长期记忆异步流水线实施计划

> **For agentic workers:** REQUIRED: 实施本计划前先使用 `superpowers:executing-plans`，写代码前使用 `superpowers:test-driven-development`，声称完成前使用 `superpowers:verification-before-completion`。
>
> **状态:** 已实施。代码已按本文完成 P3-4 长期记忆异步流水线，最终验收命令和偏差记录见 `codex-handoff.md`。

**Goal:** 在 P3-1 到 P3-3A 已完成的当前窗口、上下文快照、短期压缩和压缩鲁棒性基础上，实现 Codex 风格的长期记忆异步流水线。BaBiQ 要能在会话结束或 turn 完成后异步提取可复用经验，经过 secret redaction、污染标记和可审计归并后，生成可追溯的长期记忆 artifact，并在下一轮上下文组装时只注入受 token budget 控制的 `memory_summary`。

**Architecture:** P3-4 采用 DB-first + Markdown mirror。SQLite 仍是事实源，保存 job、候选、artifact、引用和污染状态；Markdown 文件只是用户可读镜像。Phase 1 使用 Spring AI structured output 做候选提取，Phase 2 使用受限的 Java 归并服务和可替换的 Spring AI / Spring AI Alibaba 归并策略生成 artifact，ContextAssembler 只读取已归并且未污染的 summary 注入模型。所有写入都经过 BaBiQ repository/application service，不允许模型绕过 SQLite 直接改长期记忆。

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring AI 1.1.6, Spring AI Alibaba 1.1.2.3, SQLite, MyBatis-Plus, Flyway, Jackson structured output, Spring Scheduling, BaBiQ ContextWindowRuntime, BaBiQ AppSettingsService.

---

## 1. 设计证据

### 1.1 Codex 源码结论

本计划参考 `E:\wzx\codex` 的长期记忆实现，抽象出以下稳定设计：

- Codex 长期记忆不是每轮同步写 `MEMORY.md`，而是两阶段异步流水线。
- Phase 1 扫描可处理的 rollout，提取 `raw_memory`、`rollout_summary` 和 `rollout_slug`，并先做 secret redaction。
- Phase 2 有全局归并锁，选择一批 stage1 output 后归并到 memory workspace。
- Phase 2 内部 agent 是受限运行环境：不开网络、不启用普通用户工具、不启用外部 MCP、不允许把普通会话历史反向污染记忆。
- Read path 默认只注入短小的 `memory_summary.md`，完整 memory 文件不直接塞进模型窗口。
- 记忆读取后会记录 usage，外部上下文或 MCP 内容可触发 polluted mode，避免不可信内容沉淀成长期偏好。

BaBiQ 不照搬 Codex 的 Rust 状态机和文件布局，但吸收它的系统边界：异步、可审计、先候选后归并、读路径只注入 summary、可污染隔离。

### 1.2 Spring AI / Spring AI Alibaba 结论

通过 Context7 核对后的复用边界：

- Spring AI `ChatMemory` / `MessageWindowChatMemory` 适合按 conversation id 维护有限消息窗口，但它是消息窗口缓存，不是 BaBiQ 的长期记忆事实源。
- Spring AI `ChatClient.call().entity(...)` 和 structured output 适合作为 Phase 1 记忆候选提取器，把模型输出约束成 Java record。
- Spring AI Advisor、VectorStore、RetrievalAugmentationAdvisor 可以支撑后续 P3-5 的检索增强，但 P3-4 不把 VectorStore/RAG 作为首要实现。
- Spring AI Alibaba Agent Framework 提供 ReactAgent、Hook、Interceptor、MemorySaver 和 context engineering 能力，适合封装为归并策略或后续 agent 化实现。
- BaBiQ 的核心约束是跨 provider 通用平台，因此 Spring AI / SAA 只能作为能力实现层，不能替代 BaBiQ 的 SQLite 审计、记忆开关、污染模式和 artifact 生命周期。

### 1.3 BaBiQ 当前状态

已完成基础：

- `ContextWindowRuntime` 已在 turn 前生成上下文快照，并可触发短期压缩。
- `ContextAssembler` 已输出分层 `ContextEnvelope`，并已有 `LongTermMemoryReference` 占位模型。
- `bq_context_windows`、`bq_context_snapshots`、`bq_context_summaries`、`bq_context_compactions` 已存在。
- `AppSettingsService` 已承载 provider、sandbox、approval 等全局设置，可扩展长期记忆开关。
- 桌面端已有 context chip 和 settings 页面，可继续接入长期记忆状态。

P3-4 实施前缺口：

- 没有长期记忆 job 表、候选表、artifact 表和引用表。
- 没有长期记忆的用户开关、thread 级 memory mode 和污染状态。
- 没有 turn 完成后的异步候选提取。
- 没有 secret redaction。
- 没有 `memory_summary` 注入到 `ContextAssembler`。
- 没有长期记忆状态查询和最小桌面展示。

---

## 2. 范围边界

### 2.1 P3-4 要做

- 新增长期记忆数据库迁移 `V10__long_term_memory_pipeline.sql`。
- 新增 memory 领域模型、repository、persistence adapter 和 application service。
- 新增长期记忆全局开关、生成开关、thread 级 mode 和污染状态。
- turn 完成后只更新 thread 记忆水位；Phase 1 由启动扫描和周期扫描挑选满足 idle 条件的 thread，避免每轮对话都立刻消耗一次模型调用。
- Phase 1 使用 Spring AI structured output，输出受 Java record 约束的候选结果。
- 提取前后执行 secret redaction，保证 API Key、token、Authorization header、URL credential 等不会进入 memory artifact。
- Phase 2 使用 DB lease 和全局单运行约束归并候选，每次归并保留独立 generation job 历史，并生成 `MEMORY.md`、`memory_summary.md`、`raw_memories.md` 和 `rollout_summaries/` 的镜像文件。
- `ContextAssembler` 在下一轮上下文中注入短小的长期记忆 summary，并把注入来源写进 `ContextSnapshot`。
- 新增最小 JSON-RPC：`memory/status`、`memory/settings/set`、`memory/jobs/list`、`memory/artifacts/list`、`memory/consolidate`。
- 桌面端设置页和输入框 context chip 展示长期记忆开启、关闭、暂停或污染状态。

### 2.2 P3-4 不做

- 不做完整 VectorStore / RAG 检索增强，这放到 P3-5。
- 不做复杂桌面记忆编辑器，只提供状态、开关、列表和手动归并入口。
- 不让模型直接写数据库或任意文件。
- 不把长期记忆当作普通 assistant message 写回 `bq_items`。
- 不把完整 `MEMORY.md` 每轮塞进模型，只注入 budget 内的 summary。
- 不实现跨设备同步、云端记忆、团队共享记忆。
- 不把 GPT、DeepSeek、Qwen 任意单一模型特性写成核心边界。

---

## 3. 数据模型

### 3.1 Migration

Create:

- `backend/src/main/resources/db/migration/V10__long_term_memory_pipeline.sql`

要求：

- 每张 `bq_*` 表和每个字段前都有中文 SQL 注释。
- 每张表和每个字段都写入 `bq_schema_comments`。
- 修改已有表也要为新增字段写入中文注释元数据。
- `SchemaCommentsCoverageTest` 必须覆盖所有新增表和字段。

### 3.2 新增表

#### `bq_memory_jobs`

长期记忆异步任务和 lease 状态。

核心字段：

- `job_id`: 任务 id，后端生成。
- `job_type`: `PHASE1_EXTRACT`、`PHASE2_CONSOLIDATE`。
- `job_key`: 去重键，例如 `phase1:{threadId}:{sourceUpdatedAt}` 或 `phase2:{generation}`。
- `generation`: Phase 2 归并代次。Phase 1 可为空；Phase 2 必填，用于保留每次归并历史。
- `thread_id`: 关联 thread。Phase 2 归并 job 可为空。
- `turn_id`: Phase 1 对应 turn。Phase 2 可为空。
- `status`: `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`NO_OUTPUT`、`CANCELLED`、`SKIPPED_POLLUTED`。
- `worker_id`: 当前持有 lease 的 worker。
- `lease_until`: lease 过期时间。
- `retry_count`: 已重试次数。
- `max_retries`: 最大重试次数。
- `input_watermark`: 本次处理输入水位。Phase 1 使用 thread 最新 completed turn 时间；Phase 2 使用候选最大更新时间。
- `error_message`: 最近错误摘要。
- `created_at`、`started_at`、`completed_at`、`updated_at`。

约束：

- `job_key` 唯一，避免重复提取同一 thread 的同一输入水位。
- Phase 2 不使用固定 `phase2:global` 作为历史 job key；`MemoryJobService` 在 `BEGIN IMMEDIATE` 事务中分配下一代 `phase2:{generation}`，同时保证同一时间只有一个 Phase 2 处于 `PENDING` / `RUNNING`。
- `status + lease_until` 建索引，便于 worker 拉取可执行任务。
- `thread_id + turn_id` 建索引，便于运行详情追踪。

#### `bq_memory_candidates`

Phase 1 提取的候选记忆。

核心字段：

- `candidate_id`: 候选 id。
- `thread_id`: 来源 thread。
- `turn_id`: 来源 turn。
- `job_id`: 来源 Phase 1 job。
- `cwd`: 来源工作目录。
- `provider_id`: 提取时使用的 provider。
- `model`: 提取时使用的模型。
- `raw_memory`: 可复用记忆原文，已 redaction。
- `rollout_summary`: 会话摘要，已 redaction。
- `rollout_slug`: 可选文件名 slug。
- `source_item_ids_json`: 来源 item id 列表，JSON 数组。
- `source_snapshot_id`: 来源上下文快照 id。
- `pollution_status`: `CLEAN`、`EXTERNAL_CONTEXT`、`MCP_UNTRUSTED`、`USER_DISABLED`、`SECRET_RISK`。
- `redaction_count`: redaction 命中次数。
- `selected_for_phase2`: 是否已被某次 Phase 2 选择。
- `selected_at`: 被选择时间。
- `usage_count`: read path 或引用计数，初始 0。
- `last_used_at`: 最近引用时间。
- `created_at`、`updated_at`。

约束：

- `thread_id + turn_id + job_id` 建索引。
- `pollution_status + selected_for_phase2` 建索引，便于 Phase 2 选择干净候选。

#### `bq_memory_artifacts`

长期记忆归并产物元数据。

核心字段：

- `artifact_id`: artifact id。
- `artifact_type`: `MEMORY_MD`、`MEMORY_SUMMARY_MD`、`RAW_MEMORIES_MD`、`ROLLOUT_SUMMARY`。
- `artifact_path`: 本地镜像文件相对路径。
- `content_hash`: 文件内容 hash。
- `version`: 递增版本。
- `source_job_id`: 生成 artifact 的 Phase 2 job。
- `candidate_ids_json`: 本版本消耗的候选 id 列表。
- `summary_text`: 对 `MEMORY_SUMMARY_MD` 保存一份 DB 文本副本，供 read path 不读文件也能注入。
- `token_estimate`: artifact 文本估算 token。
- `created_at`、`updated_at`。

约束：

- `artifact_type + version` 建唯一约束。
- `artifact_type + updated_at` 建索引，便于读取最新 summary。

#### `bq_memory_references`

长期记忆注入和归并引用记录。

核心字段：

- `reference_id`: 引用 id。
- `thread_id`: 读取记忆的 thread。
- `turn_id`: 读取记忆的 turn。
- `snapshot_id`: 本次注入对应的 context snapshot。
- `artifact_id`: 注入或引用的 artifact。
- `candidate_id`: 可选候选 id。
- `reference_type`: `SUMMARY_INJECTED`、`PHASE2_SELECTED`、`USER_VIEWED`、`USER_DELETED`。
- `token_estimate`: 本次注入 token 估算。
- `created_at`。

约束：

- `thread_id + turn_id + reference_type` 建索引。
- read path 每次注入都写 `SUMMARY_INJECTED`，方便 UI 追踪模型看过哪些长期记忆。

### 3.3 已有表扩展

#### `bq_threads`

新增字段：

- `memory_mode`: `ENABLED`、`DISABLED`、`PAUSED`、`POLLUTED`。默认 `ENABLED`。
- `memory_polluted_reason`: 污染原因摘要，可为空。
- `memory_polluted_at`: 进入污染模式时间，可为空。

说明：

- 全局 `memory.use` 开关关闭时，read path 不注入长期记忆。
- 全局 `memory.generate` 开关关闭时，Phase 1 不再创建候选。
- thread `memory_mode=DISABLED` 时，该 thread 不生成也不读取长期记忆。
- thread `memory_mode=POLLUTED` 时，默认不生成长期记忆，但仍可读取全局干净 summary；如果用户关闭读取，则完全不注入。

#### `bq_context_snapshots`

如果当前 schema 尚不能表达长期记忆注入来源，需要新增或复用 JSON 字段：

- `long_term_memory_refs_json`: 本轮注入的 artifact/reference id 列表。
- `long_term_memory_token_estimate`: 长期记忆注入 token 估算。

如果已有 `memory_ids` 或类似字段，则优先沿用现有字段，不重复造列。

---

## 4. 后端实现设计

### 4.1 Package layout

Create:

- `backend/src/main/java/com/wzx/babiq/server/memory/model/`
- `backend/src/main/java/com/wzx/babiq/server/memory/repository/`
- `backend/src/main/java/com/wzx/babiq/server/memory/pipeline/`
- `backend/src/main/java/com/wzx/babiq/server/memory/redaction/`
- `backend/src/main/java/com/wzx/babiq/server/memory/artifact/`

Persistence create:

- `backend/src/main/java/com/wzx/babiq/server/persistence/entity/MemoryJobEntity.java`
- `backend/src/main/java/com/wzx/babiq/server/persistence/entity/MemoryCandidateEntity.java`
- `backend/src/main/java/com/wzx/babiq/server/persistence/entity/MemoryArtifactEntity.java`
- `backend/src/main/java/com/wzx/babiq/server/persistence/entity/MemoryReferenceEntity.java`
- matching mapper and SQLite repository adapter classes.

API create:

- `backend/src/main/java/com/wzx/babiq/server/api/method/MemoryStatusHandler.java`
- `MemorySettingsSetHandler.java`
- `MemoryJobsListHandler.java`
- `MemoryArtifactsListHandler.java`
- `MemoryConsolidateHandler.java`

### 4.2 Core classes

#### `LongTermMemoryProperties`

Configuration prefix: `babiq.memory`.

Fields:

- `enabled`: 总开关，默认 `true`。
- `generateEnabled`: 是否生成长期记忆，默认 `true`。
- `readEnabled`: 是否注入长期记忆，默认 `true`。
- `rootDir`: Markdown mirror 根目录，默认 `${user.home}/.babiq/memories`。
- `phase1ScanIntervalMillis`: Phase 1 周期扫描间隔，默认 `3600000`，即 1 小时。
- `phase1MinIdleMillis`: thread 最新 turn 完成后至少空闲多久才允许提取，默认 `300000`，即 5 分钟；生产配置可提高到 Codex 默认的 6 小时级别。
- `phase1MaxThreadsPerScan`: 单次 Phase 1 扫描最多提取多少个 thread，默认 `4`。
- `phase1OnStartup`: 后端启动后是否做一次 Phase 1 扫描，默认 `true`。
- `phase1InputWindowPercent`: Phase 1 输入最多使用模型窗口的比例，默认 `70`。
- `phase1FallbackTokenLimit`: 缺少模型窗口元数据时的 Phase 1 输入兜底 token 上限，默认 `150000`。
- `phase2MaxCandidates`: 单次 Phase 2 选择候选上限，默认 `256`。
- `phase2TriggerOnCandidateCount`: 未归并 `CLEAN` candidate 累计到多少后自动触发 Phase 2，默认 `5`。
- `phase2ScanIntervalMillis`: Phase 2 兜底扫描间隔，默认 `86400000`，即 24 小时。
- `phase2MinIntervalMillis`: 两次 Phase 2 成功或启动之间的最小间隔，默认 `3600000`，即 1 小时。
- `summaryTokenBudget`: read path 注入 summary 预算，默认 `2500`。
- `extractProviderId`: Phase 1 提取使用的 provider，默认当前 active provider。
- `extractModel`: Phase 1 提取使用的模型，默认 provider 默认模型，建议可配置为便宜小模型。
- `consolidationProviderId`: Phase 2 语义归并使用的 provider，默认当前 active provider。
- `consolidationModel`: Phase 2 语义归并使用的模型，默认 provider 默认模型。
- `maxRetries`: job 最大重试，默认 `3`。

#### `MemorySettingsService`

职责：

- 读取 `AppSettingsService` 和 `LongTermMemoryProperties`，合并出运行时有效设置。
- 写入 `memory.generateEnabled`、`memory.readEnabled`、`memory.defaultThreadMode` 等 app setting。
- 提供 thread 级 `MemoryMode` 读取和更新。

设计原因：

- 不把 memory 开关散落在 handler、worker、ContextAssembler 中。
- 全局开关和 thread mode 有清晰优先级，便于 UI 展示真实后端状态。

#### `MemoryJobService`

职责：

- 后端启动或周期扫描时挑选满足 idle 条件的 thread 并创建 `PHASE1_EXTRACT` job。
- worker 拉取待执行任务并写入 lease。
- job 成功、失败、跳过、无输出时统一更新状态。
- Phase 1 成功落库后检查未归并 `CLEAN` candidate 数量，达到阈值时创建下一代 Phase 2 job。
- Phase 2 使用 `generation` 保留历史 job，同时通过事务和运行中状态保证同一时间只有一个归并任务。

关键方法：

```java
int scanAndEnqueuePhase1(Instant now);
Optional<MemoryJob> claimNext(String workerId, Instant now);
Optional<MemoryJob> enqueuePhase2IfThresholdReached(Instant now);
Optional<MemoryJob> enqueuePhase2ForScheduledScan(Instant now);
void markSucceeded(String jobId, Instant now);
void markNoOutput(String jobId, Instant now);
void markFailed(String jobId, String message, Instant now);
```

#### `MemoryStageOneExtractor`

接口：

```java
MemoryStageOneResult extract(MemoryStageOneRequest request);
```

Default implementation:

- `SpringAiMemoryStageOneExtractor`

职责：

- 按 `phase1InputWindowPercent` 和 `phase1FallbackTokenLimit` 计算 Phase 1 输入预算，超长时按时间倒序保留最近 items，再按时间正序提交给模型。
- 读取 thread 的最近 items、turn summary、tool calls、context snapshot。
- 构造短 prompt，要求模型只输出结构化 JSON。
- 使用 Spring AI `ChatClient.call().entity(MemoryStageOnePayload.class)`。
- 如果模型输出为空或无可复用经验，返回 `noOutput`。

结构化输出 record：

```java
record MemoryStageOnePayload(
    boolean hasReusableMemory,
    String rawMemory,
    String rolloutSummary,
    String rolloutSlug,
    List<String> sourceItemIds,
    List<String> confidenceNotes
) {}
```

注意：

- structured output 只约束格式，不保证事实正确。落库前必须重新验证来源 item id 和 redaction。
- 任何模型异常都只影响 memory job，不应让用户 turn 失败。

#### `MemorySecretRedactor`

职责：

- 对 Phase 1 输入和输出做 secret redaction。
- 产出 redaction count 和命中类型。
- redaction 命中次数大于等于 3，或命中 `PRIVATE_KEY`、`URL_CREDENTIAL`、`AUTHORIZATION_HEADER` 等高风险类型时，把 candidate 标记为 `SECRET_RISK`，默认不进入 Phase 2。

首批规则：

- `Authorization: Bearer ...`
- `api_key=...`、`apikey=...`、`access_token=...`
- `sk-...`、`AKIA...` 等常见 key 形态。
- URL credential: `https://user:pass@example.com`。
- Windows / Unix env var 样式：`OPENAI_API_KEY=...`。

实现要求：

- 规则集中放在 `MemorySecretRedactionRule`，不要散落正则。
- redaction 后替换为 `[REDACTED:<type>]`。
- 测试必须覆盖中英文文本、代码块、URL、header 和 env var。

#### `MemoryPollutionService`

职责：

- 决定某个 thread/turn 是否允许生成长期记忆。
- 识别不可信来源：MCP 外部工具返回、大段网页抓取、用户明确要求不要记住、系统检测到 secret risk。
- 设置 thread `memory_mode=POLLUTED` 或 candidate `pollution_status`。

首版规则：

- 如果 turn 中存在 MCP tool result 且 tool metadata 标记为 untrusted，Phase 1 默认跳过。
- 如果用户消息包含“不要记住”“不要保存到记忆”“仅本次对话”等中文/英文指令，Phase 1 跳过。
- 如果 redaction 命中次数大于等于 3，或命中 `PRIVATE_KEY`、`URL_CREDENTIAL`、`AUTHORIZATION_HEADER` 等高风险类型，candidate 标记 `SECRET_RISK`，不进入 Phase 2。

#### `MemoryArtifactMirror`

职责：

- 管理 Markdown mirror 根目录。
- 写入文件前在临时文件中生成内容，再原子替换。
- 写入后计算 `content_hash` 并落库 artifact。

文件布局：

```text
{rootDir}/
  MEMORY.md
  memory_summary.md
  raw_memories.md
  rollout_summaries/
    {yyyyMMdd-HHmmss}-{slug}.md
```

设计要求：

- SQLite 是事实源。文件缺失时可从 DB artifact 恢复。
- 文件写失败时 Phase 2 job 失败并可重试，不修改已成功 artifact 元数据。
- 文件路径必须限制在 `rootDir` 下，防止路径穿越。

#### `MemoryConsolidationStrategy`

接口：

```java
String generateMemorySummary(MemoryConsolidationRequest request);
String generateMemoryHandbook(MemoryConsolidationRequest request);
```

首版默认实现：

- `SpringAiStructuredMemoryConsolidationStrategy`

职责：

- 读取上一版 `MEMORY.md` / `memory_summary.md` 和本批 candidates。
- 使用 Spring AI structured output 分两次生成新的 `memory_summary.md` 和 `MEMORY.md`。
- `raw_memories.md` 由 Java 按 selected candidates 机械拼接，稳定排序，不调用模型。
- `rollout_summaries/` 由 Java 从 candidate 的 `rollout_summary` 直接写入，按 slug 生成文件，不调用模型。
- Java 服务负责写所有文件和落库。

为什么首版不用通用工具型 agent 直接写文件：

- P3-4 的核心是长期记忆事实源和安全边界，不能让模型直接获得任意文件写权限。
- structured output 更容易测试、审计和回滚。
- 机械 artifact 不走模型，可降低成本和输出长度风险。
- 后续如果需要 Codex 式内部 agent，可新增 `SpringAiAlibabaRestrictedAgentMemoryConsolidationStrategy`，但也必须只通过受控 memory artifact writer 写入。

Spring AI Alibaba 复用点：

- 归并策略接口预留 SAA ReactAgent 实现。
- 若使用 ReactAgent，只允许注入单一受控工具 `memory_artifact.writeDraft`，并禁用普通 shell、MCP、网络和用户工具。
- `MemorySaver` 只能保存内部归并 agent 的短期状态，不能作为 BaBiQ 长期记忆事实源。

#### `LongTermMemoryReadService`

职责：

- 在 `ContextAssembler` 组装前读取最新 `MEMORY_SUMMARY_MD` artifact。
- 使用 `ApproximateContextTokenEstimator` 按 `summaryTokenBudget` 估算注入长度，优先按段落边界截断；单段超长时再硬截断并追加省略标记。
- 写入 `bq_memory_references`。
- 返回 `LongTermMemoryReference` 列表给 `ContextEnvelope`。

注入规则：

- `current_turn` 永远优先于长期记忆。
- `long_term_memory` 必须被标记为 `priority=reference`。
- 如果 thread memory mode 是 `DISABLED` 或全局 read disabled，则不注入。
- 如果 thread 是 `POLLUTED`，默认只注入全局 summary，不注入该 thread 新产生的候选。

### 4.3 Pipeline flow

```mermaid
sequenceDiagram
    participant Scan as "Startup / scheduled scan"
    participant Job as "MemoryJobService"
    participant P1 as "Phase1 worker"
    participant Redact as "Secret redactor"
    participant DB as "SQLite"
    participant P2 as "Phase2 consolidator"
    participant Files as "Markdown mirror"
    participant Ctx as "ContextAssembler"

    Scan->>Job: scan idle threads and enqueue Phase1
    Job->>P1: claim PHASE1_EXTRACT
    P1->>Redact: sanitize input/output
    P1->>DB: insert bq_memory_candidates
    P1->>Job: mark succeeded or no_output
    Job->>Job: enqueue Phase2 when threshold reached
    P2->>Job: claim phase2:{generation}
    P2->>DB: select clean unmerged candidates
    P2->>Files: write MEMORY.md and summary
    P2->>DB: insert artifacts and references
    Ctx->>DB: read latest summary artifact
    Ctx->>DB: insert SUMMARY_INJECTED reference
```

---

## 5. JSON-RPC 和桌面端

### 5.1 Backend JSON-RPC

#### `memory/status`

Input:

```json
{
  "threadId": "thr_xxx"
}
```

Output:

```json
{
  "enabled": true,
  "generateEnabled": true,
  "readEnabled": true,
  "threadMode": "ENABLED",
  "latestSummaryVersion": 3,
  "latestSummaryUpdatedAt": "2026-05-27T10:00:00Z",
  "pendingJobs": 1,
  "runningJobs": 0,
  "failedJobs": 0,
  "lastError": null
}
```

#### `memory/settings/set`

Input:

```json
{
  "generateEnabled": true,
  "readEnabled": true,
  "threadMode": "ENABLED"
}
```

Notes:

- 字段均可选，缺省表示不修改。
- 修改 thread mode 时必须传 `threadId`。
- handler 只做 DTO 校验，实际写入交给 `MemorySettingsService`。

#### `memory/jobs/list`

用于运行详情面板展示最近记忆 job。

#### `memory/artifacts/list`

用于设置页或后续记忆管理 UI 展示 artifact 元数据。P3-4 首版只展示元数据和 summary 摘要，不做富编辑。

#### `memory/consolidate`

手动创建下一代 Phase 2 归并 job，用于调试和用户主动归并；正常情况下 Phase 1 候选阈值和每日兜底扫描也会自动触发。

### 5.2 Desktop changes

Modify:

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ContextModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsScreen.kt`

UI behavior:

- 输入框 context chip 增加长期记忆状态：`长期记忆开`、`长期记忆关`、`记忆暂停`、`记忆污染`。
- 点击 chip 打开轻量 popover，显示 summary 版本、最近归并时间、待处理 job 数、最近错误。
- 设置页增加两个开关：`生成长期记忆`、`读取长期记忆`。
- thread 级 mode 先放到当前对话设置区域，不做复杂全局记忆编辑器。

---

## 6. 实施任务

### Task 1: 数据库迁移和 repository

Files:

- Add `backend/src/main/resources/db/migration/V10__long_term_memory_pipeline.sql`
- Add memory entity/mapper/repository classes under persistence and memory packages.
- Modify `SchemaCommentsCoverageTest` only if当前覆盖规则需要显式纳入新表；优先沿用自动扫描。

Steps:

1. 新建 `bq_memory_jobs`、`bq_memory_candidates`、`bq_memory_artifacts`、`bq_memory_references`。
2. 扩展 `bq_threads` memory mode 字段。
3. 如有必要扩展 `bq_context_snapshots` 长期记忆引用字段。
4. 为所有表和字段补 SQL 中文注释和 `bq_schema_comments`。
5. 新增 entity 字段中文注释。
6. 新增 repository adapter 测试，覆盖 insert、lease claim、status update、artifact version query。

Test:

```powershell
cd backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest,MemoryRepositoryTest" test
```

### Task 2: 记忆设置、thread mode 和污染模式

Files:

- Add `backend/src/main/java/com/wzx/babiq/server/memory/model/MemoryMode.java`
- Add `backend/src/main/java/com/wzx/babiq/server/memory/MemorySettingsService.java`
- Add `backend/src/main/java/com/wzx/babiq/server/memory/MemoryPollutionService.java`
- Modify `backend/src/main/java/com/wzx/babiq/server/settings/AppSettingsService.java` only through existing extension style.

Steps:

1. 定义全局 `generateEnabled`、`readEnabled` 和 thread mode 的优先级。
2. 在 thread repository 中支持读取和更新 memory mode。
3. 实现污染判定和用户 opt-out 判定。
4. 确保关闭生成时不创建 Phase 1 job，关闭读取时 ContextAssembler 不注入 summary。

Test:

```powershell
cd backend
.\mvnw.cmd "-Dtest=MemorySettingsServiceTest,MemoryPollutionServiceTest" test
```

### Task 3: Secret redaction

Files:

- Add `backend/src/main/java/com/wzx/babiq/server/memory/redaction/MemorySecretRedactor.java`
- Add `MemorySecretRedactionRule.java`
- Add `MemorySecretRedactionResult.java`

Steps:

1. 建立规则列表，覆盖 header、env var、URL credential、常见 key pattern。
2. Phase 1 输入和输出都走 redaction。
3. redaction count 和命中类型写入 candidate。
4. redaction 命中次数大于等于 3，或命中 `PRIVATE_KEY`、`URL_CREDENTIAL`、`AUTHORIZATION_HEADER` 等高风险类型时，将 candidate 标记 `SECRET_RISK`。

Test:

```powershell
cd backend
.\mvnw.cmd "-Dtest=MemorySecretRedactorTest" test
```

### Task 4: Phase 1 候选提取

Files:

- Add `backend/src/main/java/com/wzx/babiq/server/memory/pipeline/MemoryStageOneExtractor.java`
- Add `SpringAiMemoryStageOneExtractor.java`
- Add `MemoryStageOneRequest.java`
- Add `MemoryStageOneResult.java`
- Add prompt template constants or resource file under `backend/src/main/resources/prompts/`.

Steps:

1. 从 `ConversationRepository`、`RunRecordService`、`ContextSnapshotRepository` 读取候选来源。
2. 使用 `ApproximateContextTokenEstimator` 和 `phase1InputWindowPercent` 控制 Phase 1 输入长度。
3. 调用 `MemorySecretRedactor` 清洗输入，再构造最小 prompt，声明只基于给定证据提取，不允许杜撰。
4. 使用 Spring AI structured output 生成 `MemoryStageOnePayload`。
5. 调用 `MemorySecretRedactor` 清洗模型输出，重新计算 redaction count 和污染状态。
6. 验证 source item id 必须来自输入。
7. 对 empty/no reusable learning 返回 `NO_OUTPUT`。

Test:

```powershell
cd backend
.\mvnw.cmd "-Dtest=SpringAiMemoryStageOneExtractorTest,MemoryStageOnePromptTest,MemorySecretRedactorTest" test
```

### Task 5: Job worker 和调度

Files:

- Add `backend/src/main/java/com/wzx/babiq/server/memory/pipeline/LongTermMemoryPipeline.java`
- Add `MemoryJobWorker.java`
- Add `MemoryJobScheduler.java`
- Modify turn completion recording path only to update memory dirty watermark; do not enqueue a model job immediately.

Steps:

1. turn completed 后只更新 thread 记忆输入水位，不直接调用模型。
2. 后端启动后执行一次 Phase 1 scan；之后按 `phase1ScanIntervalMillis` 周期扫描。
3. scanner 只挑选最新 turn 已空闲超过 `phase1MinIdleMillis` 的 thread，并受 `phase1MaxThreadsPerScan` 限制。
4. Spring Scheduling 拉取 `PENDING` 或 lease expired job。
5. worker 执行 Phase 1 并落库 candidate。
6. Phase 1 成功后，如果未归并 `CLEAN` candidate 数量大于等于 `phase2TriggerOnCandidateCount`，则入队下一代 Phase 2 job。
7. job 失败只更新 memory job，不影响原用户 turn。
8. 达到 retry 上限后标记 `FAILED`。

Test:

```powershell
cd backend
.\mvnw.cmd "-Dtest=MemoryJobServiceTest,LongTermMemoryPipelineTest,MemoryJobSchedulerTest" test
```

### Task 6: Phase 2 归并和 Markdown mirror

Files:

- Add `backend/src/main/java/com/wzx/babiq/server/memory/pipeline/MemoryConsolidationStrategy.java`
- Add `SpringAiStructuredMemoryConsolidationStrategy.java`
- Add `backend/src/main/java/com/wzx/babiq/server/memory/artifact/MemoryArtifactMirror.java`
- Add `MemoryArtifactService.java`

Steps:

1. `memory/consolidate`、Phase 1 阈值触发或 `phase2ScanIntervalMillis` 兜底扫描创建下一代 `phase2:{generation}` job。
2. 创建 Phase 2 时遵守 `phase2MinIntervalMillis` 防抖。
3. 选择 `CLEAN` 且未归并的 candidates，排序规则为 `usage_count DESC, COALESCE(last_used_at, created_at) DESC, created_at DESC, candidate_id DESC LIMIT phase2MaxCandidates`。
4. 读取上一版 artifact。
5. Java 直接生成 `raw_memories.md` 和 `rollout_summaries/`。
6. 调用 structured consolidation strategy 分别生成 `memory_summary.md` 和 `MEMORY.md`。
7. `MemoryArtifactMirror` 原子写文件。
8. 成功后写 `bq_memory_artifacts`，并标记 candidates selected。

Test:

```powershell
cd backend
.\mvnw.cmd "-Dtest=MemoryArtifactMirrorTest,MemoryConsolidationServiceTest,SpringAiStructuredMemoryConsolidationStrategyTest" test
```

### Task 7: Read path 注入 ContextAssembler

Files:

- Add `backend/src/main/java/com/wzx/babiq/server/memory/LongTermMemoryReadService.java`
- Modify `backend/src/main/java/com/wzx/babiq/server/context/ContextAssembler.java`
- Modify `backend/src/main/java/com/wzx/babiq/server/context/model/ContextEnvelope.java`
- Modify `backend/src/main/java/com/wzx/babiq/server/context/model/LongTermMemoryReference.java` if needed.
- Modify snapshot persistence if新增 memory refs字段。

Steps:

1. `ContextWindowRuntime.prepare()` 调用 `LongTermMemoryReadService` 获取最新 summary。
2. `ContextAssembler` 把 summary 放入 `long_term_memory` reference 层。
3. renderer 明确提示长期记忆是参考，不覆盖当前用户消息。
4. `bq_memory_references` 写入 `SUMMARY_INJECTED`。
5. `ContextSnapshot` 记录注入 artifact id、token estimate 和 excluded reason。

Test:

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextAssemblerLongTermMemoryTest,ContextWindowRuntimeMemoryTest,MemoryReadServiceTest" test
.\mvnw.cmd "-Dtest=ContextAssemblerCompactionTest" test
```

### Task 8: JSON-RPC 和桌面最小接入

Files:

- Add backend memory handlers.
- Modify desktop protocol/client/state/UI files listed in section 5.

Steps:

1. 后端注册 `memory/status`、`memory/settings/set`、`memory/jobs/list`、`memory/artifacts/list`、`memory/consolidate`。
2. 桌面端新增协议模型和 `AgentClient` 方法。
3. Settings 接入生成/读取开关。
4. ComposerContextBar 展示长期记忆状态。
5. 后端状态为污染或关闭时，UI 显示真实后端状态。

Test:

```powershell
cd backend
.\mvnw.cmd "-Dtest=MemoryHandlersTest,MemorySettingsServiceTest" test

cd ..\desktop
.\gradlew.bat test --tests "*MemoryModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*ComposerContextBarTest"
```

### Task 9: 文档、handoff 和全量验证

Files:

- Update `docs/superpowers/plans/p3-4-long-term-memory/codex-handoff.md`
- Update `AGENTS.md`
- Update `CLAUDE.md`
- Update `docs/superpowers/plans/p3-task-index.md`
- Update `docs/superpowers/plans/p3-master.md`

Validation:

```powershell
cd backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest,MemoryRepositoryTest,MemorySettingsServiceTest,MemoryPollutionServiceTest,MemorySecretRedactorTest,LongTermMemoryPipelineTest,MemoryConsolidationServiceTest,ContextAssemblerLongTermMemoryTest,MemoryHandlersTest" test
.\mvnw.cmd "-Dtest=ContextAssemblerCompactionTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*MemoryModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*ComposerContextBarTest"
.\gradlew.bat test
```

Manual smoke:

1. 启动 backend 和 desktop。
2. 在可写工作区完成一次含工具调用的会话。
3. 等待 Phase 1 idle 扫描，或在测试配置中缩短 `phase1ScanIntervalMillis` 后触发扫描。
4. 确认 `memory_summary.md` 生成。
5. 新开一轮对话，确认 context snapshot 中有长期记忆引用，且 UI chip 显示长期记忆开启。
6. 关闭读取开关，再次发送消息，确认没有长期记忆注入。
7. 输入“不要记住这次内容”，确认该 turn 不生成 candidate。

---

## 7. 风险和处理

### 风险 1: 模型把候选记忆写得过度泛化

处理：

- Phase 1 prompt 要求只写可复用、可验证、来自证据的事实。
- `MemoryStageOnePayload` 必须带 source item ids。
- Phase 2 只归并有来源 id 的候选。

### 风险 2: secret 进入长期记忆

处理：

- 输入和输出双向 redaction。
- redaction 命中次数大于等于 3，或命中 `PRIVATE_KEY`、`URL_CREDENTIAL`、`AUTHORIZATION_HEADER` 等高风险类型时，candidate 不进入 Phase 2。
- 测试覆盖常见 key、URL、header 和 env var。

### 风险 3: 外部工具结果污染用户长期偏好

处理：

- MCP/网页/外部检索内容默认进入污染评估。
- 未标记可信来源的外部内容不生成长期记忆。
- 用户可手动关闭当前 thread 的记忆生成。

### 风险 4: Phase 2 写文件失败导致 DB 和文件不一致

处理：

- 先写临时文件，再原子替换。
- 文件写成功后再写 artifact 记录。
- artifact 有 content hash，可检测文件漂移。
- SQLite 仍保存 summary 副本，文件缺失可恢复。

### 风险 5: 长期记忆注入压缩当前用户意图

处理：

- `summaryTokenBudget` 默认 2500 token。
- 当前用户消息和 recent history 优先级高于 long-term memory。
- budget 不足时宁可不注入长期记忆，也不挤掉 current turn。

---

## 8. 验收标准

P3-4 只有满足以下条件才可声明完成：

- `V10__long_term_memory_pipeline.sql` 和所有新增实体具备中文注释。
- `SchemaCommentsCoverageTest` 覆盖新增表和字段。
- Phase 1 可在启动扫描或周期扫描中挑选 idle thread 生成 candidate，且支持 no-output。
- secret redaction 对输入和输出生效。
- Phase 2 可生成 Markdown mirror 和 `bq_memory_artifacts`。
- `ContextAssembler` 可注入最新 `memory_summary`，并写入 `bq_memory_references`。
- 全局读取/生成开关和 thread mode 对后端真实生效，不只是 UI 变化。
- 污染模式能阻止不可信 turn 进入长期记忆。
- JSON-RPC 和桌面端能显示真实后端长期记忆状态。
- 后端 targeted tests、后端 `clean verify`、桌面 targeted tests、桌面全量 `test` 全部通过。

---

## 9. 执行顺序建议

1. 先做 Task 1 和 Task 2，确保事实源、开关和模式边界稳定。
2. 再做 Task 3 到 Task 5，先建立 redaction，再让 Phase 1 扫描式地产出或跳过候选。
3. 接着做 Task 6，完成 Phase 2 artifact 归并。
4. 然后做 Task 7，把长期记忆接入真实模型上下文。
5. 最后做 Task 8 和 Task 9，收口协议、桌面、文档和全量验证。

不要先做 UI。P3-4 的核心验收是“后端 Agent 真正按设置生成、归并、读取长期记忆”，UI 只是展示和控制入口。
