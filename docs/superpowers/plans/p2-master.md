# BaBiQ P2 Implementation Plan (Master)

> **For agentic workers:** REQUIRED: Use `superpowers:writing-plans` before creating any P2 child plan, and use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement each child plan task-by-task. Steps in child plans must use checkbox (`- [ ]`) syntax for tracking.
>
> **This is the P2 MASTER plan**. It scopes P2 and defines the order of sub-plans. Detailed task-level TDD plans must live in separate directories under `docs/superpowers/plans/p2-*/` and must be confirmed by the user before implementation.

**Goal:** 把 BaBiQ 从 P1 的内存版可用 Agent，升级为本地可恢复、可配置、可追溯、可扩展的桌面 Agent。

**Architecture:** P2 继续保持 Kotlin Compose Desktop + Spring Boot Agent Server 的跨进程架构，通信仍走 WebSocket + JSON-RPC 2.0。后端引入 SQLite 本地持久化，使用 MyBatis-Plus 和 Java 常见分层结构，但 Agent 领域模型不直接依赖 Mapper；数据库实现通过 repository/adapter 隔离，方便未来切 PostgreSQL。桌面端在 P1 V2 UI 基础上补齐真实会话历史、设置系统和运行记录展示。

**Tech Stack:** Java 21 LTS, Spring Boot 3.5.14, Spring AI 1.1.6, Spring AI Alibaba 1.1.2.3, SQLite, MyBatis-Plus, Flyway, xerial sqlite-jdbc, MyBatis-Plus pagination, Kotlin 2.3.21, Compose Multiplatform 1.11.0, Ktor Client 3.5.0, kotlinx.serialization 1.11.0, kotlinx.coroutines 1.11.0, WebSocket, JSON-RPC 2.0.

**Architecture References:**

- `docs/ARCHITECTURE.md`
- `docs/superpowers/plans/2026-05-21-p1-master.md`
- `docs/superpowers/plans/p1-4-compose-desktop-ui/codex-handoff.md`
- MyBatis-Plus official guide: <https://mybatis.plus/en/guide/>
- MyBatis-Plus pagination plugin: <https://baomidou.com/en/plugins/pagination/>
- Maven Central metadata checked on 2026-05-24:
  - `com.baomidou:mybatis-plus-spring-boot3-starter`: `3.5.16`
  - `com.baomidou:mybatis-plus-jsqlparser`: `3.5.16`
  - `org.xerial:sqlite-jdbc`: `3.53.1.0`
  - `org.flywaydb:flyway-core`: `12.6.2`

---

## 1. P2 当前起点

P1-0 到 P1-4 已完成主要代码实现。当前 P1 能力包括:

- 后端 WebSocket + JSON-RPC 协议。
- `Thread / Turn / Item` 内存状态模型。
- Spring AI Alibaba ReactAgent 主循环。
- 6 个本地工具。
- HITL 审批、沙箱、Spotlighting、安全 prompt、TurnSummary。
- Compose Desktop V2 UI。
- Provider/模型下拉切换。
- 工作目录切换。
- 后端真实沙箱权限展示。

P1 总体验收状态:

- 用户已在 2026-05-24 确认 P1 验收通过。
- `docs/superpowers/plans/p2-0-final-acceptance/codex-handoff.md` 已记录该前置状态。
- P2 正式入口改为 `P2-1 SQLite + MyBatis-Plus 持久化底座`。

若后续又发现 P1 遗留 bug，应单独开 P1 收口 bugfix，不要把 bug 混入 P2 持久化主体。

---

## 2. P2 范围边界

### P2 必须做

| 类别 | 内容 |
|---|---|
| P1 总体验收 | 已由用户确认通过，P2-0 仅保留验收记录 |
| SQLite 持久化 | 本地单文件数据库，保存 thread、turn、item、summary、approval、provider config、app setting |
| MyBatis-Plus 分层 | 采用 `entity / mapper / persistence service / repository adapter / application service` 分层 |
| Migration | 使用 migration 脚本创建和升级表结构，禁止手动建表或依赖 `ddl-auto` |
| 多会话历史 | 实现 `thread/list`、`thread/load`、`thread/archive`，桌面端最近对话改为真实数据 |
| 会话恢复 | 后端重启后能恢复历史 Thread、Turn、Item，桌面端可继续旧会话 |
| 设置系统 | Provider 新增/编辑/删除/测试连接；沙箱权限和审批策略可在 UI 修改并影响下一轮 turn |
| API Key 存储 | Provider 表不保存明文 key，使用 `SecretStore` 抽象保存或引用 secret |
| 审批语义补齐 | 明确 `approve / deny / edit / always` 的后端协议和 UI 行为，启用“始终允许”前必须先完成语义定义 |
| 运行记录 | 能查看 Turn 的状态、耗时、tokens、成本、工具调用、审批结果、失败原因 |
| 基础可观测增强 | 在 P1 JSON 日志和 TurnSummary 基础上，补充本地统计服务或轻量 metrics 出口 |

### P2 可以做，但必须在前置子阶段稳定后再做

| 类别 | 内容 |
|---|---|
| MCP Client 最小接入 | 仅做本地 stdio MCP server 接入，工具调用仍走审批和沙箱链路 |
| 简单语义记忆起步 | 只建立接口和小规模验证，不做完整代码库向量索引 |
| Provider fallback | Provider 调用失败时可配置 fallback provider，但不做 Battle 模式 |

### P2 不做

- Multi-Agent 专家团队。
- A2A 跨机器 Agent 协议。
- OS 级真沙箱、Docker、E2B、agent-sandbox。
- Langfuse、OpenTelemetry、Jaeger/Tempo 完整链路 UI。
- 多模态图片、语音、PDF。
- 大代码库向量索引和完整 Agentic RAG。
- 云端多用户系统。
- 团队同步、账号体系、权限租户。
- Provider Battle 模式。

---

## 3. 子系统拆分与依赖关系

```
P1 总体验收 / 收口
        │
        ▼
P2-1 SQLite + MyBatis-Plus 持久化底座
        │
        ▼
P2-2 多会话历史 + 桌面端最近对话
        │
        ▼
P2-3 Provider / API Key / 沙箱 / 审批设置系统
        │
        ▼
P2-4 持久化后的恢复语义 + 运行记录
        │
        ▼
P2-5 基础可观测增强
        │
        ▼
P2-6 MCP Client 最小接入（可选）
```

依赖说明:

- `P2-1` 是所有 P2 后续能力的基础。
- `P2-2` 依赖 `P2-1` 的 Thread/Turn/Item 持久化。
- `P2-3` 依赖 `P2-1` 的设置表和 SecretStore 基础设计。
- `P2-4` 依赖 `P2-1` 和 `P2-2`，用于补齐状态恢复、运行记录和审计查询。
- `P2-5` 依赖 `P2-4` 的运行记录数据。
- `P2-6` 必须等待审批、沙箱、日志、设置系统稳定后再进入。

---

## 4. P2 技术决策

| 编号 | 决策 | 选择 | 原因 |
|---|---|---|---|
| P2-D1 | 本地数据库 | SQLite | 桌面端本地单用户，零部署、单文件、离线可用，符合 P2 目标 |
| P2-D2 | 数据访问框架 | MyBatis-Plus | 用户希望学习标准 Java 分层；官方支持 SQLite，CRUD 和分页足够高效 |
| P2-D3 | 分层结构 | `api/method` 或 controller → application service → domain service → repository → mapper | 既保留 Java 常见结构，又保护 Agent 核心不直接依赖数据库 |
| P2-D4 | 数据库迁移 | Flyway 或等价 migration 工具 | 表结构必须可追踪、可升级、可测试 |
| P2-D5 | 协议 ID | 保持 `thr_* / turn_* / item_*` 字符串业务 ID | 协议层不暴露数据库自增 ID，未来换库不影响客户端 |
| P2-D6 | 数据库主键 | 内部 `id` 可用自增或雪花；业务唯一键单独建唯一索引 | 兼顾数据库性能和协议稳定 |
| P2-D7 | SQLite 写入模式 | WAL + busy timeout + foreign keys | 提升桌面端读写体验，避免锁等待直接失败 |
| P2-D8 | API Key | Provider 表只保存 `secretRef`，明文 key 交给 `SecretStore` | 不把敏感信息写进普通业务表 |
| P2-D9 | Repository 隔离 | 领域层面向 `ConversationRepository` 等接口 | 未来切 PostgreSQL 或更换 ORM 时只动适配层 |
| P2-D10 | 设置生效范围 | 默认影响下一轮 turn，不中途改 running turn | 保持 P1 已建立的 turn 上下文快照语义 |
| P2-D11 | MyBatis-Plus 分页 | `PaginationInnerInterceptor(DbType.SQLITE)` | 官方分页插件支持 SQLite，单数据库建议显式指定 dbType |
| P2-D12 | 官方能力查证 | 每个子计划开始前重新查官方文档和 Maven Central | 版本会变，禁止盲用过期版本或 RC/Beta/EAP |

---

## 5. 后端目标结构

P2 可以采用熟悉的 Java 后端分层，但需要避免把数据库细节塞进 Agent 核心。

```
backend/src/main/java/com/wzx/babiq/server/
├── api/
│   └── method/
│       ├── ThreadListHandler.java
│       ├── ThreadLoadHandler.java
│       ├── ThreadArchiveHandler.java
│       ├── SettingsGetHandler.java
│       ├── ProviderCreateHandler.java
│       ├── ProviderUpdateHandler.java
│       ├── ProviderDeleteHandler.java
│       ├── ProviderTestHandler.java
│       ├── SandboxPolicySetHandler.java
│       └── ApprovalPolicySetHandler.java
├── conversation/
│   ├── ConversationService.java
│   ├── ConversationApplicationService.java
│   └── repository/
│       ├── ConversationRepository.java
│       ├── TurnRepository.java
│       ├── ItemRepository.java
│       └── ApprovalRepository.java
├── persistence/
│   ├── config/
│   │   ├── SQLiteDataSourceConfig.java
│   │   └── MyBatisPlusConfig.java
│   ├── entity/
│   │   ├── ThreadEntity.java
│   │   ├── TurnEntity.java
│   │   ├── ItemEntity.java
│   │   ├── TurnSummaryEntity.java
│   │   ├── ApprovalEntity.java
│   │   ├── ProviderConfigEntity.java
│   │   └── AppSettingEntity.java
│   ├── mapper/
│   │   ├── ThreadMapper.java
│   │   ├── TurnMapper.java
│   │   ├── ItemMapper.java
│   │   ├── TurnSummaryMapper.java
│   │   ├── ApprovalMapper.java
│   │   ├── ProviderConfigMapper.java
│   │   └── AppSettingMapper.java
│   ├── service/
│   │   ├── ThreadPersistenceService.java
│   │   ├── TurnPersistenceService.java
│   │   ├── ItemPersistenceService.java
│   │   ├── ProviderPersistenceService.java
│   │   └── AppSettingPersistenceService.java
│   └── adapter/
│       ├── SQLiteConversationRepository.java
│       ├── SQLiteTurnRepository.java
│       ├── SQLiteItemRepository.java
│       └── SQLiteApprovalRepository.java
├── settings/
│   ├── AppSettingsService.java
│   ├── ProviderSettingsService.java
│   └── SecretStore.java
└── observability/
    ├── RunRecordService.java
    └── LocalMetricsService.java
```

分层含义:

- `api/method`: WebSocket JSON-RPC 版 controller，只做参数解析、调用 service、返回 DTO。
- `conversation`: Agent 领域和应用编排层，理解 Thread/Turn/Item 业务语义。
- `persistence/entity`: 数据库表实体，允许 MyBatis-Plus 注解，但不能直接作为协议 DTO。
- `persistence/mapper`: MyBatis-Plus `BaseMapper` 和必要自定义 SQL。
- `persistence/service`: 单表或少量表的数据库操作封装。
- `persistence/adapter`: 把数据库结构适配成领域 repository 接口。
- `settings`: Provider、API Key、沙箱、审批策略等设置服务。
- `observability`: 本地运行摘要、统计和查询。

---

## 6. 数据库初始表设计

P2 子计划必须先写 migration，再写 mapper/entity。表名和字段可在子计划中细化，但必须覆盖以下语义。

### `bq_threads`

| 字段 | 说明 |
|---|---|
| `id` | 数据库内部主键 |
| `thread_id` | 协议业务 ID，唯一，例如 `thr_...` |
| `title` | 会话标题，默认从用户第一条消息生成 |
| `cwd` | 工作目录快照 |
| `provider_id` | 最近一次使用的 provider |
| `model` | 最近一次使用的模型 |
| `sandbox_mode` | 最近一次使用的沙箱模式 |
| `approval_policy` | 最近一次使用的审批策略 |
| `status` | `active / archived` |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |
| `archived_at` | 归档时间，未归档为空 |

### `bq_turns`

| 字段 | 说明 |
|---|---|
| `id` | 数据库内部主键 |
| `turn_id` | 协议业务 ID，唯一 |
| `thread_id` | 所属 thread |
| `status` | Turn 状态 |
| `input_text` | 用户本轮输入摘要 |
| `cwd` | 本轮工作目录快照 |
| `provider_id` | 本轮 provider 快照 |
| `model` | 本轮 model 快照 |
| `sandbox_mode` | 本轮沙箱快照 |
| `approval_policy` | 本轮审批策略快照 |
| `started_at` | 开始时间 |
| `completed_at` | 完成时间 |
| `failure_reason` | 失败原因 |

### `bq_items`

| 字段 | 说明 |
|---|---|
| `id` | 数据库内部主键 |
| `item_id` | 协议业务 ID，唯一 |
| `thread_id` | 所属 thread |
| `turn_id` | 所属 turn，可为空 |
| `type` | Item 类型 |
| `sequence_no` | 同一 turn 内顺序 |
| `payload_json` | 原始 item JSON |
| `status` | item 状态 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### `bq_turn_summaries`

| 字段 | 说明 |
|---|---|
| `turn_id` | 对应 turn |
| `tokens_in` | 输入 token |
| `tokens_out` | 输出 token |
| `cost_usd` | 美元估算成本 |
| `duration_ms` | 本轮耗时 |
| `tool_count` | 工具调用次数 |
| `created_at` | 创建时间 |

### `bq_approvals`

| 字段 | 说明 |
|---|---|
| `approval_id` | 审批业务 ID |
| `thread_id` | 所属 thread |
| `turn_id` | 所属 turn |
| `tool_name` | 工具名 |
| `args_json` | 原始工具参数 |
| `edited_args_json` | 用户修改后的参数 |
| `decision` | `approve / deny / edit / always` |
| `scope` | `turn / session` |
| `status` | `pending / resolved / expired` |
| `created_at` | 创建时间 |
| `resolved_at` | 处理时间 |

### `bq_provider_configs`

| 字段 | 说明 |
|---|---|
| `provider_id` | Provider ID，唯一 |
| `display_name` | 展示名 |
| `type` | `dashscope / openai-compatible` |
| `base_url` | OpenAI-compatible base url，可为空 |
| `model` | 默认模型 |
| `secret_ref` | API Key 引用，不保存明文 |
| `context_window` | 可选上下文窗口覆盖 |
| `enabled` | 是否启用 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

### `bq_app_settings`

| 字段 | 说明 |
|---|---|
| `setting_key` | 设置 key |
| `setting_value` | 设置值 |
| `value_type` | `string / number / boolean / json` |
| `updated_at` | 更新时间 |

---

## 7. P2 子阶段

### P2-0: P1 总体验收和收口

**状态:** 已通过，作为 P2 前置完成项保留记录。

**记录:**

- `docs/superpowers/plans/p2-0-final-acceptance/codex-handoff.md`

**说明:** P2-0 不再阻塞 P2-1。若后续发现 P1 遗留 bug，单独开 bugfix 处理。

### P2-1: SQLite + MyBatis-Plus 持久化底座

**目标:** 建立本地 SQLite 数据库、migration、MyBatis-Plus 配置、基础 entity/mapper/service/repository。

**必须实现:**

- Maven 依赖:
  - `mybatis-plus-spring-boot3-starter`
  - `mybatis-plus-jsqlparser`
  - `sqlite-jdbc`
  - migration 工具
- SQLite 数据库默认路径配置。
- WAL、foreign_keys、busy_timeout 初始化。
- `MybatisPlusInterceptor` + `PaginationInnerInterceptor(DbType.SQLITE)`。
- migration 创建 P2 初始表。
- entity、mapper、persistence service。
- repository 接口与 SQLite adapter。
- 临时数据库集成测试。

**输出:**

- `docs/superpowers/plans/p2-1-sqlite-persistence/plan.md`
- 后端测试通过。
- 不改变桌面端 UI 行为。

### P2-2: 多会话历史和桌面端最近对话

**目标:** 从 P1 单内存会话升级为可恢复的多会话历史。

**必须实现:**

- `thread/create` 写入数据库。
- `turn/start`、`item/added`、`turn/completed` 同步写入数据库。
- `thread/list` 返回最近会话。
- `thread/load` 返回指定 thread 的 item 流。
- `thread/archive` 归档会话。
- 桌面端左侧最近对话改为真实数据。
- 新对话、切换旧会话、归档会话。

**验收场景:**

1. 创建会话并发送一轮消息。
2. 关闭后端。
3. 重启后端和桌面端。
4. 左侧仍能看到历史会话。
5. 点击历史会话能恢复消息。
6. 能继续发送下一轮。

**输出:**

- `docs/superpowers/plans/p2-2-thread-history/plan.md`

### P2-3: Provider / API Key / 沙箱 / 审批设置系统

**目标:** 把 P1 只读设置升级为可编辑设置，但仍保持本地单用户边界。

**必须实现:**

- Provider 新增、编辑、删除、启用/禁用。
- Provider 测试连接。
- API Key 使用 `SecretStore`，Provider 表只保存 `secretRef`。
- 沙箱模式 UI 可选: `READ_ONLY / WORKSPACE_WRITE / DANGER_FULL_ACCESS`。
- 审批策略 UI 可选: `NEVER / ON_REQUEST / ON_FAILURE`。
- 设置修改默认影响下一轮 turn，running turn 不被中途改变。
- “始终允许”审批语义补齐:
  - 后端协议支持 `always`。
  - 需要定义 session scope 的生命周期。
  - UI 按真实语义启用按钮。

**输出:**

- `docs/superpowers/plans/p2-3-settings-system/plan.md`

### P2-4: 持久化后的恢复语义和运行记录

**目标:** 让失败、取消、中断、审批、工具调用、成本都可追溯。

**必须实现:**

- Turn 状态恢复规则。
- 启动时处理遗留 `RUNNING / WAITING_APPROVAL` turn。
- 失败原因和取消原因持久化。
- approval pending/resolved/expired 状态持久化。
- 运行详情页读取历史运行记录。
- TurnSummary 历史查询。

**边界:**

- P2 不要求跨进程继续一个正在等待审批的 ReactAgent checkpoint。
- P2 需要保留数据模型和接口，为后续 checkpoint resume 做准备。

**输出:**

- `docs/superpowers/plans/p2-4-recovery-records/plan.md`

### P2-5: 基础可观测增强

**目标:** 在本地学习项目中能看清 Agent 运行情况，不直接上复杂观测平台。

**必须实现:**

- 本地运行统计服务。
- 按 provider/model 统计 turn 数、tokens、成本、失败次数。
- 按工具统计调用次数、失败次数、耗时。
- 后端日志继续保持 JSON。
- 可选: 引入 Actuator + Micrometer，但必须保持 P2 边界，不接 Langfuse/OTel UI。

**输出:**

- `docs/superpowers/plans/p2-5-local-observability/plan.md`

### P2-6: MCP Client 最小接入（可选）

**目标:** 在 P2 主体稳定后，验证 BaBiQ 能接入本地 MCP server。

**必须实现的最小范围:**

- 本地 stdio MCP server 配置。
- 拉取工具列表。
- MCP 工具调用包装为 BaBiQ tool。
- 调用仍走审批、沙箱、日志、TurnSummary。
- 桌面端设置页只展示 MCP server 状态，不做复杂 marketplace。

**不做:**

- MCP Server 实现。
- 远程 MCP。
- OAuth。
- 插件市场。

**输出:**

- `docs/superpowers/plans/p2-6-mcp-client/plan.md`

---

## 8. P2 验收标准

P2 完成必须满足以下验收:

- `cd backend && .\mvnw.cmd clean verify` 通过。
- `cd desktop && .\gradlew.bat test` 通过。
- SQLite migration 自动创建数据库。
- 真实桌面端能创建新会话、发送消息、关闭重启后恢复。
- 左侧最近对话来自数据库。
- 历史会话能加载完整 item 流。
- 归档会话不会再出现在默认最近列表中。
- Provider 可以在 UI 新增、编辑、删除、测试连接。
- API Key 不以明文写入普通 provider 表。
- 沙箱模式和审批策略可以在 UI 修改，并从下一轮 turn 生效。
- “始终允许”按钮要么真实可用，要么仍禁用并在 handoff 中说明原因；不能处于假可用状态。
- TurnSummary 历史可追溯。
- 后端日志能定位完整运行链路。
- P2 所有计划和实现完成后，同步更新:
  - `AGENTS.md`
  - `CLAUDE.md`
  - `docs/ARCHITECTURE.md`
  - `docs/superpowers/plans/p2-master.md`
  - 对应子计划 `codex-handoff.md`

---

## 9. 风险与缓解

| 风险 | 严重度 | 缓解 |
|---|---|---|
| SQLite 写锁导致请求失败 | 中 | WAL、busy timeout、短事务、写操作集中在 service 层 |
| Entity 污染协议模型 | 高 | 强制 entity/DTO/domain 分离，mapper 不出现在 Agent Loop |
| Provider API Key 明文落库 | 高 | provider 表只保存 `secretRef`，SecretStore 单独设计 |
| P2 范围膨胀 | 高 | MCP、RAG、可观测 UI 都放后半段或可选，不阻塞 P2-1 到 P2-4 |
| migration 和测试数据库不一致 | 中 | 每个 repository 集成测试使用临时 SQLite 文件并跑 migration |
| 历史 item JSON 反序列化兼容 | 中 | `payload_json` 保存原始 JSON，新增字段保持向后兼容 |
| running turn 恢复语义不清 | 高 | P2 先标记为 failed/interrupted 并保留记录，checkpoint resume 留到后续 |
| MyBatis-Plus 版本漂移 | 中 | 每个子计划开始前查 Maven Central 和官方文档，禁止 RC/Beta/EAP |

---

## 10. 子计划编写规则

每个 P2 子计划必须包含:

- 当前上下文和依赖关系。
- 官方能力查证结果。
- 版本核对结果。
- 文件清单。
- 数据库 migration 变更。
- TDD 步骤。
- 精确命令和预期结果。
- UI 验收场景。
- 文档同步清单。
- 中文 commit 计划。

子计划路径:

```
docs/superpowers/plans/p2-0-final-acceptance/codex-handoff.md
docs/superpowers/plans/p2-1-sqlite-persistence/plan.md
docs/superpowers/plans/p2-2-thread-history/task-card.md
docs/superpowers/plans/p2-3-settings-system/task-card.md
docs/superpowers/plans/p2-4-recovery-records/task-card.md
docs/superpowers/plans/p2-5-local-observability/task-card.md
docs/superpowers/plans/p2-6-mcp-client/task-card.md
```

其中 P2-2 到 P2-6 当前先保留任务卡，等进入对应子阶段时再升级为详细 `plan.md`。

---

## 11. 立即下一步

1. 用户确认 P1 验收已通过，`P2-0` 已记录为前置完成。
2. 已创建 `docs/superpowers/plans/p2-1-sqlite-persistence/plan.md`。
3. 下一步应先请用户确认 P2-1 计划，再开始实现。
4. 每个子计划都必须先由用户确认，再开始实现。
5. 每个子计划完成后必须验证、更新文档、中文 commit，不主动 push。

推荐下一条用户指令:

```text
确认 P2-1 计划，开始实现 SQLite + MyBatis-Plus 持久化底座。
```

---

## 12. Open Questions

| # | 问题 | 当前建议 |
|---|---|---|
| Q1 | API Key 的 P2 SecretStore 具体实现 | 子计划前查 Java/Windows 官方或成熟库；优先不明文落库 |
| Q2 | P2 是否必须做 MCP | 可选，取决于 P2-1 到 P2-5 稳定度 |
| Q3 | Actuator 是否进入 P2-5 | 可进入，但只做基础 metrics，不接 Langfuse/OTel UI |
| Q4 | P2 是否启用简单 RAG | 不放入必做；最多预留接口或单独子计划 |
| Q5 | SQLite 数据库默认位置 | 建议默认 `${user.home}/.babiq/babiq.db`，允许 `application.yml` 覆盖 |
