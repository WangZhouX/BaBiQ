# BaBiQ Codex Agent 规则

> 本文件是 BaBiQ 仓库级 Codex 工作规则。
> 除非子目录下存在更近的 `AGENTS.md` 覆盖，否则本文件对整个仓库生效。

## 0. 启动规则

- superpowers-codex bootstrap
- 开始实现前，按任务类型使用对应 superpowers 技能：
  - 写新的多步骤实现计划前，使用 `superpowers:writing-plans`。
  - 做功能或修 bug 前，使用 `superpowers:test-driven-development`。
  - 声称完成、通过、可进入下一阶段前，使用 `superpowers:verification-before-completion`。

## 1. 项目定位

BaBiQ 是一个本地 Codex-like AI Agent 学习项目。

当前架构：

- `desktop/`: Kotlin Compose Desktop 客户端。
- `backend/`: Java 21 + Spring Boot Agent Server。
- 通信协议: WebSocket + JSON-RPC 2.0，端点为 `/ws/agent`。
- Agent 核心: Spring AI Alibaba `ReactAgent`、本地工具、HITL 审批、沙箱、`Thread / Turn / Item` 状态模型。
- 技术主线: 后端框架以 Java 生态为主，优先使用 Spring AI Alibaba、Spring AI 及其 agent-framework 的最新稳定能力；如需升级依赖，必须先核对官方发布、兼容性和现有代码约束。

## 2. 必读上下文入口

做状态判断或实现前，必须先读相关文档和代码，不能只靠记忆。

主入口文档：

1. `docs/ARCHITECTURE.md`
2. `docs/superpowers/plans/2026-05-21-p1-master.md`
3. `docs/superpowers/plans/p2-master.md`
4. `docs/superpowers/plans/p3-master.md`
5. `docs/superpowers/plans/p3-task-index.md`
6. 当前阶段的 `docs/superpowers/plans/p3-*/plan.md`
7. 当前阶段的 `docs/superpowers/plans/p3-*/codex-handoff.md`

当前检查点：

- P1-3A Agent Loop 内核已实现。
- P1-3A 验收补齐已完成。
- P1-3B 安全 + 可观测已实现，并已通过后端全量测试。
- P1-4 Compose Desktop UI 已完成实现：
  - 桌面端已从 skeleton 升级为 V2 Chat UI。
  - 已实现 JSON-RPC 协议模型、Ktor WebSocket 客户端、AgentClient、AppState、ChatReducer、ChatController。
  - 已实现聊天消息、工具/文件/TurnSummary 渲染、审批弹窗、Provider/模型下拉、只读设置页、连接断开提示和 1s-10s 自动重连。
  - `turnSummary` 只展示 token、耗时和工具次数；项目不再记录或展示价格/成本。
  - Sidebar 搜索、插件、自动化只作为 P1 禁用占位，不实现真实能力。
- P1-4 计划、原型和交互材料仍保留在：
  - `docs/superpowers/plans/p1-4-compose-desktop-ui/plan.md`
  - `docs/superpowers/plans/p1-4-compose-desktop-ui/codex-handoff.md`
  - `docs/superpowers/plans/p1-4-compose-desktop-ui/prototype/`
- P2 master plan 已创建：
  - `docs/superpowers/plans/p2-master.md`
  - P2 技术主线为 SQLite + MyBatis-Plus + Flyway/migration + Java 常见分层结构。
  - P1 总体验收已由用户在 2026-05-24 确认通过，`P2-0` 仅保留验收记录。
  - P2 已按用户 goal 全量执行完成；P2-1、P2-2、P2-3、P2-4、P2-5、P2-6 均已完成。
  - P2-1 到 P2-6 的详细计划和 handoff 已同步；进入下一阶段前必须先做 P2 总体验收复盘并编写新阶段详细 plan。
- P2 任务文档已创建：
  - `docs/superpowers/plans/p2-task-index.md`
  - `docs/superpowers/plans/p2-0-final-acceptance/codex-handoff.md`
  - `docs/superpowers/plans/p2-1-sqlite-persistence/plan.md`
  - `docs/superpowers/plans/p2-1-sqlite-persistence/codex-handoff.md`
  - `docs/superpowers/plans/p2-2-thread-history/plan.md`
  - `docs/superpowers/plans/p2-2-thread-history/codex-handoff.md`
  - `docs/superpowers/plans/p2-3-settings-system/plan.md`
  - `docs/superpowers/plans/p2-3-settings-system/codex-handoff.md`
  - `docs/superpowers/plans/p2-4-recovery-records/plan.md`
  - `docs/superpowers/plans/p2-4-recovery-records/codex-handoff.md`
  - `docs/superpowers/plans/p2-5-local-observability/plan.md`
  - `docs/superpowers/plans/p2-5-local-observability/codex-handoff.md`
  - `docs/superpowers/plans/p2-6-mcp-client/plan.md`
  - `docs/superpowers/plans/p2-6-mcp-client/codex-handoff.md`
- P2-1 SQLite + MyBatis-Plus 持久化底座已完成：
  - 后端已引入 SQLite JDBC、MyBatis-Plus、Flyway，并建立 `bq_*` 业务表。
  - 每张业务表和每个业务字段都在 SQL 注释和 `bq_schema_comments` 中保留中文说明。
  - 已建立 Thread、Turn、Item、TurnSummary、ProviderConfig、AppSetting、Approval、MetricsDaily 等 Entity / Mapper / Repository adapter。
  - `bq_turn_summaries` 已在 `V6__turn_summary_token_only.sql` 收口为 token-only 结构，只保留 `prompt_tokens`、`completion_tokens`、`total_tokens`、耗时和工具次数，不再保留 `cost_usd`。
  - 已有 `SchemaCommentsCoverageTest` 校验所有业务字段中文说明不缺失。
  - 提交: `e149244 feat(p2-1): 建立 SQLite 持久化底座`。
- P2-2 多会话历史和桌面端最近对话已完成：
  - 后端新增 `thread/list`、`thread/load`、`thread/archive` JSON-RPC 方法。
  - `ConversationEventRecorder` 已把运行中 ThreadItem、TurnSummary 和 Turn 终态同步落库。
  - `ConversationService` 和 `SQLiteConversationRepository` 已支持从 SQLite 查询 thread 元数据、item 历史、消息数量和最新 turn 状态。
  - 桌面端 Sidebar 最近对话已改为真实 `thread/list` 数据，支持打开历史会话、归档会话、新建当前对话、切换工作目录后重载列表。
- 已验证：
  - `cd desktop; .\gradlew.bat test`
  - `cd backend; .\mvnw.cmd clean verify`
  - `cd desktop; .\gradlew.bat run --no-daemon` 已进入 `:run` 并在受控烟测中保持运行。
  - P2-2 额外验证：`cd backend; .\mvnw.cmd "-Dtest=ThreadCreateHandlerTest,TurnStartHandlerTest,ApprovalRespondHandlerTest,ThreadListHandlerTest,ThreadLoadHandlerTest,ThreadArchiveHandlerTest,ConversationEventRecorderTest,ConversationHistoryIT" test`
  - P2-2 额外验证：`cd desktop; .\gradlew.bat test --tests "*AgentClientTest" --tests "*ThreadHistoryModelsTest"`、`cd desktop; .\gradlew.bat test --tests "*ChatControllerTest"`
- P2-3 Provider / API Key / 沙箱 / 审批设置系统已完成：
  - 后端新增 `settings/*`、`provider/*`、`sandbox/policy/set`、`approval/policy*` JSON-RPC 方法。
  - `ProviderSettingsService` 已把 Provider 配置写入 SQLite，API Key 写入 JDK `JCEKS` KeyStore，数据库和 API 响应只暴露 `secretRef` / `hasApiKey`。
  - `AppSettingsService`、`SandboxSettingsService`、`ApprovalPolicyService` 已支持 active provider、默认 cwd、sandbox mode、approval policy 的本地持久化。
  - `approval/respond` 已支持 `decision=always`，并通过 `ApprovalRuleService` 做 session scope、tool name、args fingerprint 匹配，不实现永久全局放行。
  - 桌面端设置页已支持 Provider 新增、编辑、删除、测试连接、切换当前 Provider，以及沙箱/审批策略修改；审批弹窗“始终允许”已接真实协议。
  - P2-3 额外验证：`cd backend; .\mvnw.cmd "-Dtest=ProviderSettingsServiceTest,LocalKeyStoreSecretStoreTest,AppSettingsServiceTest,ProviderSettingsHandlersTest,SettingsHandlersTest,ApprovalRuleServiceTest,ApprovalRespondHandlerTest" test`
  - P2-3 额外验证：`cd backend; .\mvnw.cmd "-Dtest=AgentLoopLineCountTest,ProviderTestControllerIntegrationTest" test`
  - P2-3 全量验证：`cd backend; .\mvnw.cmd clean verify`
  - P2-3 额外验证：`cd desktop; .\gradlew.bat test --tests "*SettingsModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest"`
  - P2-3 全量验证：`cd desktop; .\gradlew.bat test`
- P2-4 持久化后的恢复语义和运行记录已完成：
  - 后端新增启动恢复语义，遗留 `RUNNING` / `SENDING` turn 会收束为 `INTERRUPTED`，遗留 `WAITING_APPROVAL` turn 会收束为 `EXPIRED`，pending approval 会过期。
  - 后端新增 `run/turns/list`、`run/turn/get`、`run/recovery/status` JSON-RPC 方法。
  - 后端新增 `bq_tool_calls` 工具调用记录表，并为新增表和字段同步 SQL 中文注释与 `bq_schema_comments`。
  - 工具调用、审批请求、审批响应、取消和中断已写入持久化运行记录。
  - 桌面端运行详情面板已接入真实历史运行记录，支持展示恢复报告、历史 turn、工具调用、审批记录和 TurnSummary。
  - P2-4 额外验证：`cd backend; .\mvnw.cmd "-Dtest=TurnRecoveryServiceTest,RunRecordServiceTest,ToolCallRecordTest" test`
  - P2-4 额外验证：`cd desktop; .\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*RunRecordModelsTest"`
  - P2-4 全量验证：`cd backend; .\mvnw.cmd clean verify`
  - P2-4 全量验证：`cd desktop; .\gradlew.bat test`
- P2-5 基础可观测增强已完成：
  - 后端新增 `LocalObservabilityService`，只基于 SQLite 持久化运行记录聚合，不读取 P1 内存计数器。
  - 后端新增 `observability/snapshot`、`observability/tools`、`observability/costs` JSON-RPC 方法。
  - 后端已支持按 range/cwd 聚合 turns、tokens、状态分布、provider/model token 用量和工具调用分布；不再聚合价格或成本。
  - 桌面端运行详情面板已接入本地统计展示，支持 `7d`、`30d`、`all` 三个范围切换。
  - P2-5 决策为不启用 Actuator/Micrometer；本阶段不暴露额外 HTTP 观测 endpoint。
  - P2-5 额外验证：`cd backend; .\mvnw.cmd "-Dtest=LocalObservabilityServiceTest" test`
  - P2-5 额外验证：`cd backend; .\mvnw.cmd "-Dtest=ObservabilityHandlersTest,LocalObservabilityServiceTest" test`
  - P2-5 额外验证：`cd desktop; .\gradlew.bat test --tests "*ObservabilityModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest"`
  - P2-5 全量验证：`cd backend; .\mvnw.cmd clean verify`
  - P2-5 全量验证：`cd desktop; .\gradlew.bat test`
- P2-6 MCP Client 最小接入已完成：
  - 后端新增 `babiq.mcp` 配置、`McpClientManager`、`McpToolCatalog`、`McpToolAdapter` 和官方 MCP Java SDK 薄适配连接器。
  - 后端新增 `bq_mcp_servers`、`bq_mcp_tools` 两张表，所有表和字段都已写入 SQL 中文注释和 `bq_schema_comments`。
  - MCP 工具以 `mcp.<serverId>.<toolName>` 命名并合并进 `ToolRegistry`，继续经过审批、沙箱、Spotlighting、工具观测和 TurnSummary 链路。
  - 后端新增 `mcp/servers/list`、`mcp/tools/list`、`mcp/servers/refresh` JSON-RPC 方法。
  - 桌面端新增“本地 MCP”入口，可展示 server 状态、错误信息、工具列表并手动刷新；不提供任意 command 编辑入口。
  - P2-6 依赖决策：不使用 milestone 版 Spring AI MCP starter；使用官方 MCP Java SDK 稳定版 `1.1.3`。
  - P2-6 额外验证：`cd backend; .\mvnw.cmd "-Dtest=McpPropertiesTest,McpClientManagerTest,McpToolAdapterTest,McpHandlersTest,McpEndToEndIT,ToolRegistryTest" test`
  - P2-6 额外验证：`cd backend; .\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest" test`
  - P2-6 额外验证：`cd desktop; .\gradlew.bat test --tests "*McpModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest"`
  - P2 全量验证：`cd backend; .\mvnw.cmd clean verify`
  - P2 全量验证：`cd desktop; .\gradlew.bat test`
- P2 DeepSeek V4 官方端点工具恢复补丁已完成：
  - `https://api.deepseek.com` + `deepseek-v4-pro` / `deepseek-v4-flash` 必须使用 `DeepSeekV4OpenAiChatModel` 专用适配器，不再用关闭 thinking 的临时绕法。
  - 专用适配器会在工具调用恢复时回放 Spring AI 已解析到的 `reasoning_content`；如果历史里没有 reasoning，则写入明确的占位 reasoning，避免 DeepSeek V4 在 thinking + tool call 续轮时返回 400。
  - 专用适配器只在 thinking 启用时移除 `tool_choice`，并保留 OpenAI-compatible 的 streaming usage 链路，用于继续统计 prompt/completion/total tokens。
  - 已验证：`cd backend; .\mvnw.cmd -q -Dtest="DeepSeekV4OpenAiChatModelTest,OpenAiCompatibleProviderFactoryTest" test`
  - 已验证：`cd backend; .\mvnw.cmd clean verify`
- P3-1 上下文与记忆平台最小底座已完成：
  - `docs/superpowers/plans/p3-master.md`
  - `docs/superpowers/plans/p3-task-index.md`
  - `docs/superpowers/plans/p3-1-context-memory-platform/plan.md`
  - `docs/superpowers/plans/p3-1-context-memory-platform/codex-handoff.md`
  - 已核对 Codex 源码的当前窗口管理、短期压缩、长期记忆流水线，并通过 Context7 核对 Spring AI / Spring AI Alibaba 可复用能力。
  - 后端新增 `com.wzx.babiq.server.context` 领域包，已实现 `ContextAssembler`、`ContextSnapshot`、`ContextEnvelope`、`ContextTokenEstimator` 和 `CapabilityCatalogAssembler`。
  - P3-1 只落地最小底座：能生成 Spring AI messages、分层 envelope、included/excluded snapshot 和不含 input schema 的能力目录摘要；尚未接入真实 `AgentLoop`、未新增数据库表、未改桌面 UI。
  - 已验证：`cd backend; .\mvnw.cmd "-Dtest=CapabilityCatalogAssemblerTest,ContextAssemblerTest" test`
- P3-2 当前窗口管理运行时已完成：
  - `docs/superpowers/plans/p3-2-context-window-runtime/plan.md`
  - `docs/superpowers/plans/p3-2-context-window-runtime/codex-handoff.md`
  - 后端新增 `ContextWindowRuntime`，普通 `turn/start` 会在调用模型前生成本轮临时上下文输入。
  - 后端新增 `bq_context_windows`、`bq_context_snapshots`，并同步 SQL 中文注释、`bq_schema_comments` 和覆盖测试。
  - `AgentLoop` 已接入 P3-2 运行时：用户真实输入仍写入 `bq_items`，模型收到的是临时上下文视图，避免污染聊天历史。
  - 后端新增 `context/status`、`context/snapshot/get`，并在 `run/turn/get` 返回最新上下文快照摘要。
  - 桌面端新增上下文协议模型、状态刷新、输入栏上下文 chip 和运行详情快照摘要。
  - 已验证：`cd backend; .\mvnw.cmd clean verify`、`cd desktop; .\gradlew.bat test`。
- P3-3 短期记忆/上下文压缩已完成：
  - `docs/superpowers/plans/p3-3-short-term-compaction/plan.md`
  - `docs/superpowers/plans/p3-3-short-term-compaction/codex-handoff.md`
  - 后端新增 `ContextBudgetPolicy`、`CompactionSourceSelector`、`ContextCompactionService`、`SpringAiContextCompactionStrategy` 和 `ContextManualCompactionService`。
  - 新增 `bq_context_summaries`、`bq_context_compactions`，并同步 SQL 中文注释、`bq_schema_comments` 和覆盖测试。
  - `ContextWindowRuntime` 已支持 pre-turn 自动压缩：超过 75% 阈值时压缩旧历史，成功后安装 active summary、递增 `window_ordinal`，再重新装配本轮上下文。
  - `ContextAssembler` 已把 active summary 注入 `short_term_summary` 层，并用 `REPLACED_BY_SUMMARY` 排除被覆盖旧历史。
  - 后端新增 `context/compact` 手动入口，`context/status` 返回 active summary、压缩次数和最近压缩状态。
  - 桌面端已支持 `contextCompaction` item，并在输入栏上下文 chip 展示 `已压缩 N 次` 或压缩失败状态。
  - 已验证：`cd backend; .\mvnw.cmd "-Dtest=ContextBudgetPolicyTest,CompactionSourceSelectorTest,ContextAssemblerCompactionTest,ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,ContextHandlersTest,SchemaCommentsCoverageTest" test`。
- **下一步**：编写并确认 P3-4 长期记忆异步流水线详细计划，不能直接开始实现长期记忆。

如果仓库状态发生变化，不要盲信本检查点；必须重新核对代码、文档、测试和 `git status`。

## 3. 阶段边界

不要把未来阶段内容混进当前阶段。

P1-3B 已完成范围：

- 工具输出 `<untrusted-data>` spotlighting。
- system prompt 安全规则。
- prompt injection smoke test。
- `turnSummary` 协议 item、token 用量摘要、结构化 turn JSON 日志。
- P1 内存级 counters: turn、tokens、tool calls、approval decisions。
- 不引入 Actuator、Prometheus、OpenTelemetry、Langfuse UI、Lakera Guard、Dual LLM 或 OWASP 大数据集回归。

P1-4 已完成范围：

- Compose Desktop UI。
- 消费后端已经发出的 `turnSummary`、`approval/request`、`item/added`、`turn/completed` 等协议事件。
- 完成 ChatScreen、ApprovalDialog、ProviderSelector、Provider 只读设置、token 反馈条和协议模型映射。
- 采用用户审核通过的 V2 原型：上下文条和模型切换靠近输入框，右侧运行详情默认收起。
- 不恢复 V1 原型，不把“文件上下文”做成独立右侧入口。
- 不做 Provider 编辑、API Key 管理、KeyStore、多工作区文件 pinning 或 P2+ 可观测 UI。

下一阶段边界：

- P2-1、P2-2、P2-3、P2-4、P2-5、P2-6 已完成；用户已暂时验收 P2，P3-1 最小上下文底座、P3-2 当前窗口管理运行时、P3-3 短期记忆/上下文压缩已完成。
- P2-6 已完成 MCP Client 最小接入；后续如要扩展远程 MCP、OAuth、插件市场、MCP server 开发或复杂沙箱编排，必须进入新阶段计划，不得混入 P2 收口。
- P3 当前限定为 Codex 级当前窗口管理、短期记忆/上下文压缩、长期记忆平台；Multi-Agent、真 OS 沙箱、A2A、多模态仍属于后续阶段，不能混入 P3。
- P3-1 已完成最小底座；P3-2 已完成真实 Agent 前置接入、快照持久化和 UI 指示；P3-3 已完成短期压缩、summary 替换 active window 和 `ContextCompactionItem` 事件。P3-4 仍需先写详细 plan，确认后才允许实现长期记忆异步提取/归并。
- P2 范围内 SQLite 使用 MyBatis-Plus 和 Java 常见分层，但 Agent 核心不得直接依赖 Mapper；必须通过 repository/adapter 或 application service 隔离。
- 后续任何新增业务表或业务字段都必须同步 SQL 中文注释、`bq_schema_comments` 元数据和覆盖测试。

## 4. 实现规则

- 改代码前先读代码。
- 实现任何 Agent、LLM、工具、Hook、Interceptor、Memory、HITL、观测、沙箱或协议相关能力前，必须先查看对应的官方代码库或官方文档，优先确认 Spring AI Alibaba、Spring AI、JDK/Java 标准库或成熟 Java 生态中是否已有实现。
- 能使用官方组件、官方扩展点或成熟 Java 库时，优先做薄封装和集成，不重复造轮子；只有官方能力缺失、与 BaBiQ 协议不匹配或引入成本过高时，才允许自实现，并在计划或代码注释中说明原因。
- 查证顺序优先级：Spring AI Alibaba 官方仓库/文档、Spring AI 官方仓库/文档、Java/JDK 官方文档、成熟 Java 生态库；涉及版本差异时，以当前仓库锁定版本和官方最新稳定说明共同判断。
- Compose/Kotlin/Ktor/kotlinx 相关实现也必须先查官方文档或官方仓库。
- 版本使用最新稳定版，禁止为了“看起来更新”使用 RC、Beta、EAP。
- 优先沿用仓库现有模式，不随意创造新抽象。
- 修改范围必须贴合当前 issue 或阶段。
- 不做无关重构。
- P2 执行期间，严格按已确认子计划实施；未确认的后续 P2/P3 功能不得提前混入当前阶段。
- 新增或修改生产代码时，必须同步补充中文教学型注释：
  - 类、接口、record、data class、enum、Composable、public 方法和重要 private 方法必须有方法级/类型级中文注释，说明“它负责什么、为什么这样设计、和上下游怎么协作”。
  - 重要字段、构造参数、data class 属性、状态字段和协议字段必须有字段级/参数级中文注释，说明“这个值代表什么、由谁写入、被谁读取、为空或默认值意味着什么”。
  - 数据库 migration 中每一张业务表、每一个业务字段都必须有中文注释；SQLite 不支持原生列注释，因此必须同时满足：
    - SQL 文件中每个 `CREATE TABLE` 和每个字段定义前都有中文 `--` 注释。
    - `bq_schema_comments` 元数据表中写入每个表和字段的中文说明，后续 migration 新增字段时也必须同步补充。
    - Entity 字段必须有中文字段级注释，说明数据库字段含义、写入来源、读取方和空值语义。
    - 必须有测试用 `PRAGMA table_info` 校验所有 `bq_*` 业务表字段在 `bq_schema_comments` 中都有非空中文说明。
  - 复杂、关键或容易误解的代码块必须补充行级中文注释，例如协议分发、Agent/HITL 恢复、沙箱路径校验、并发/协程、缓存、token 统计、Provider 切换、工作目录切换。
  - 简单字段、直观赋值和样板 getter/setter 不强行逐行注释；如果逐行注释会降低可读性，应使用方法级注释加关键行注释。
  - 注释必须解释意图和边界，不写“把 A 赋给 A”这类空注释。
- 必需验收测试禁止用 `@Disabled` 占位。

## 5. 测试与验收

后端改动默认完整验证命令：

```powershell
cd backend
.\mvnw.cmd clean verify
```

桌面端改动默认验证命令：

```powershell
cd desktop
.\gradlew.bat test
.\gradlew.bat run
```

P1-4 实现验收必须包含：

- `desktop` 单元测试通过。
- 后端 `clean verify` 通过。
- 桌面端真实启动。
- UI 能完成“分析 E:\BaBiQ 项目结构并写一个总结”的真实业务场景。
- 审批弹窗、Provider/模型切换、TurnSummary token 反馈、断线提示均可见可用。
- 视觉对齐 V2 原型截图，不能出现文字溢出、控件重叠或 V1 旧设计回流。

P1-4 当前自动化验收补充：

```powershell
cd desktop
.\gradlew.bat test --tests "*ChatControllerTest"
.\gradlew.bat test

cd ..\backend
.\mvnw.cmd clean verify
```

桌面启动烟测可用 `.\gradlew.bat run --no-daemon`；如果没有配置真实 Provider/API Key，只能验证窗口启动、断线/重连提示和只读 UI，真实“分析项目结构并总结”需要在可用模型环境中人工复验。

P2 收口自动化验收补充：

```powershell
cd backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest" test
.\mvnw.cmd "-Dtest=McpPropertiesTest,McpClientManagerTest,McpToolAdapterTest,McpHandlersTest,McpEndToEndIT,ToolRegistryTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*McpModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest"
.\gradlew.bat test
```

P3-2 当前窗口运行时自动化验收补充：

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextWindowRuntimeTest,ContextualPromptRendererTest,ContextSnapshotPersistenceTest,AgentLoopContextRuntimeTest,ContextHandlersTest,RunRecordServiceTest,SchemaCommentsCoverageTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ContextModelsTest" --tests "*RunRecordModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*ComposerContextBarTest"
.\gradlew.bat test
```

P3-3 短期记忆与上下文压缩自动化验收补充：

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextBudgetPolicyTest,CompactionSourceSelectorTest,ContextAssemblerCompactionTest,ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,ContextHandlersTest,SchemaCommentsCoverageTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ContextModelsTest" --tests "*ThreadHistoryModelsTest" --tests "*ComposerContextBarTest" --tests "*ChatControllerTest"
.\gradlew.bat test
```

没有新鲜验证证据前，不要声称完成、通过或可进入下一阶段。

## 6. 计划完成后的文档同步

每完成一个阶段计划或重要子计划后，Codex 必须主动检查并更新本文件和 `CLAUDE.md`。

必须更新的内容：

- `当前检查点`: 写清楚刚完成了什么、哪些验收已通过。
- `下一步阶段`: 写清楚下一步应进入哪个阶段，是否需要先写详细 plan。
- `阶段边界`: 如果新阶段范围发生变化，必须同步调整，避免后续 Codex 越界实现。
- `测试与验收`: 如果新增了硬验收命令、测试类或烟测脚本，必须补进来。

要求：

- 更新 `AGENTS.md` / `CLAUDE.md` 应作为完成计划后的收尾动作主动提出或直接执行。
- 如果用户要求 commit，`AGENTS.md` / `CLAUDE.md` 更新应和该计划的收尾提交一起提交，或单独用 `docs(...)` 提交。
- 不要让 `AGENTS.md` 或 `CLAUDE.md` 停留在过期阶段状态；下一轮 Codex / Claude 会依赖它们判断当前项目进度。

## 7. Git 规则

- 编辑前后都看 `git status --short --branch`。
- 看到无关 dirty 文件时，默认认为是用户改动，不要回滚。
- 用户已要求本仓库收尾时主动 commit：完成计划、文档或功能验收后，使用中文 conventional commit 主动提交。
- commit message 使用中文 conventional commit，例如：
  - `test(p1-3a): 补齐工具和沙箱验收测试`
  - `docs(p1-4): 编写桌面端详细实现计划`
- 不要主动 push 或 tag，除非用户明确要求。

## 8. 汇报规则

汇报状态时：

- 先说真实仓库状态，不说猜测。
- 写清楚跑了什么验证命令，以及结果。
- 剩余缺口必须直接说明。
- 如果下一步是新阶段，要说明详细 plan 是否已经存在。

对于本仓库，只有在当前阶段 plan 已存在、代码已实现、`clean verify` 和阶段专属验收都通过后，才可以说该阶段完成。
