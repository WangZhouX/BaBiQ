# BaBiQ

BaBiQ 是一个本地运行的 Codex-like AI Agent 学习项目。它的目标不是做一个云端商业产品，而是用 Java / Kotlin 生态完整复刻一个现代 Agent 桌面端应该具备的核心机制：对话协议、工具调用、人工审批、沙箱、持久化、上下文窗口管理、短期压缩和长期记忆。

当前项目已经从 P1/P2 的“可用本地 Agent”推进到 P3/P4/P5/P6 的“上下文、记忆、能力装配、计划可视化和多 Agent 实验平台”。截至当前仓库状态，P3-6 官方 SkillRegistry 薄封装已完成，P6-2 flow 编排自动化验收已完成。

> 注意：这是学习项目，不建议直接作为生产级 Agent 平台使用。真实 API Key、数据库、日志、KeyStore 和本地配置都应放在环境变量或本地忽略文件中。

---

## 项目定位

BaBiQ 对标的是 OpenAI Codex / Claude Code / Cursor 这类本地 Agent 体验，但技术主线固定在 Java 生态：

- 后端：Java 21 + Spring Boot + Spring AI + Spring AI Alibaba。
- 桌面端：Kotlin + Compose Multiplatform Desktop。
- 协议：WebSocket + JSON-RPC 2.0。
- 数据：SQLite + MyBatis-Plus + Flyway。
- Agent 核心：Spring AI Alibaba `ReactAgent` + BaBiQ 自己的 Thread / Turn / Item 状态模型。
- 目标能力：本地代码助手、通用工具助手、上下文工程和长期记忆平台。

BaBiQ 的核心设计原则是：**Spring AI / Spring AI Alibaba 承载模型和 Agent 基础能力，BaBiQ 自己掌控协议、状态、权限、审计和上下文策略。**

---

## 技术栈

| 层 | 技术 | 当前版本 / 说明 |
|---|---|---|
| Backend JDK | Java | 21 LTS |
| Backend 框架 | Spring Boot | 3.5.14 |
| AI 框架 | Spring AI | 当前主线 1.1.6，P3-5 暂不升级，仅评估可选能力依赖 |
| Agent 框架 | Spring AI Alibaba | 1.1.2.3 |
| Provider | DashScope / OpenAI Compatible / DeepSeek V4 / Ollama | 通过 Provider 配置切换 |
| 数据库 | SQLite | 本地持久化事实源 |
| 数据访问 | MyBatis-Plus + Flyway | migration + repository adapter |
| MCP | MCP Java SDK | 1.1.3，本地 stdio MCP Client |
| Desktop JDK | Java | 21 |
| Desktop 语言 | Kotlin | 2.3.21 |
| Desktop UI | Compose Multiplatform | 1.11.0 |
| Desktop 通信 | Ktor Client | WebSocket + JSON-RPC |
| 构建 | Maven / Gradle | `backend/mvnw.cmd`、`desktop/gradlew.bat` |

---

## 总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│                    Compose Desktop Client                     │
│                                                              │
│  Chat UI  ── Approval Dialog ── Settings ── Runtime Details  │
│     │                │              │              │          │
│     └────────────────┴──────────────┴──────────────┘          │
│                            │                                 │
│                 Ktor WebSocket AgentClient                   │
└────────────────────────────┼─────────────────────────────────┘
                             │
                             │ JSON-RPC 2.0 over WebSocket
                             │ ws://localhost:8080/ws/agent
                             ▼
┌──────────────────────────────────────────────────────────────┐
│                 Spring Boot Agent Server                      │
│                                                              │
│  JsonRpcWebSocketHandler                                     │
│      │                                                       │
│      ▼                                                       │
│  JsonRpcDispatcher ── method handlers                        │
│      │                                                       │
│      ├── ConversationService / Thread / Turn / Item          │
│      ├── Settings / Provider / Sandbox / Approval            │
│      ├── Run Records / Observability                         │
│      ├── MCP Client / Tool Catalog                           │
│      ├── ContextWindowRuntime                                │
│      ├── ContextCompactionService                            │
│      └── LongTermMemoryPipeline                              │
│              │                                               │
│              ▼                                               │
│       AgentLoop / ReactAgent                                 │
│              │                                               │
│              ├── ChatModel / Provider                        │
│              ├── ToolRegistry                                │
│              ├── Approval + Sandbox                          │
│              └── Tool Observation + Spotlighting             │
│                                                              │
│  SQLite / Flyway / MyBatis-Plus                              │
└──────────────────────────────────────────────────────────────┘
```

### 核心分层

| 层 | 职责 |
|---|---|
| Desktop UI | 展示聊天、历史会话、审批弹窗、运行详情、MCP、设置和上下文状态 |
| JSON-RPC 协议层 | 统一承载 `thread/*`、`turn/*`、`approval/*`、`settings/*`、`context/*`、`memory/*` 等方法 |
| Conversation 层 | 管理 Thread / Turn / Item 生命周期和事件流 |
| Agent 层 | 调用模型、解析工具调用、执行工具回灌、处理恢复和中断 |
| Tool 层 | 本地工具、MCP 工具、审批、沙箱、Spotlighting 和工具观测 |
| Context 层 | 组装本轮模型可见上下文、生成快照、控制 token 预算、触发短期压缩 |
| Memory 层 | 异步提取长期记忆、归并 artifact、生成 Markdown mirror、注入 memory summary |
| Persistence 层 | SQLite 作为事实源，所有业务表和字段都有中文注释与覆盖测试 |

---

## 项目结构

```text
BaBiQ/
├── README.md
├── AGENTS.md
├── CLAUDE.md
├── backend/
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/
│       ├── main/java/com/wzx/babiq/server/
│       │   ├── agent/          # AgentLoop、恢复、审批暂停续跑
│       │   ├── api/            # JSON-RPC 协议和 method handlers
│       │   ├── approval/       # 审批策略和 always rule
│       │   ├── context/        # 当前窗口、上下文快照、短期压缩
│       │   ├── conversation/   # Thread / Turn / Item 模型
│       │   ├── hook/           # token usage、恢复清理等 Hook
│       │   ├── interceptor/    # 沙箱、Spotlighting、工具观测
│       │   ├── mcp/            # 本地 MCP Client 和工具适配
│       │   ├── memory/         # 长期记忆异步流水线
│       │   ├── model/          # Provider、ChatClientFactory、模型元数据
│       │   ├── observability/  # 本地统计和运行记录
│       │   ├── persistence/    # Entity、Mapper、Repository adapter
│       │   ├── sandbox/        # PathGuard 和沙箱策略
│       │   ├── settings/       # Provider、API Key、沙箱和审批设置
│       │   └── tool/           # 本地工具注册与实现
│       └── main/resources/
│           ├── application.yml
│           └── db/migration/   # Flyway migration
├── desktop/
│   ├── build.gradle.kts
│   ├── gradlew / gradlew.bat
│   └── src/main/kotlin/com/wzx/babiq/desktop/
│       ├── app/                # 桌面应用入口和配置
│       ├── client/             # Ktor WebSocket 传输
│       ├── protocol/           # JSON-RPC DTO
│       ├── state/              # AppState、ChatController、Reducer
│       └── ui/                 # Chat、Settings、Runtime、Sidebar
└── docs/
    ├── ARCHITECTURE.md
    └── superpowers/plans/      # P1 / P2 / P3 阶段计划和 handoff
```

---

## 通信协议

BaBiQ 使用 WebSocket 承载 JSON-RPC 2.0。桌面端发 request，后端返回 response，同时后端会主动推送 notification。

默认端点：

```text
ws://localhost:8080/ws/agent
```

常用 Client -> Server 方法：

| 方法 | 说明 |
|---|---|
| `thread/create` | 创建新会话 |
| `thread/list` | 查询历史会话 |
| `thread/load` | 载入历史会话 |
| `thread/archive` | 归档会话 |
| `turn/start` | 启动一轮 Agent 对话 |
| `turn/cancel` | 取消正在运行的 turn |
| `turn/interrupt` | 中断正在运行的 turn |
| `approval/respond` | 响应工具审批 |
| `settings/get` / `settings/update` | 查询和更新应用设置 |
| `provider/*` | Provider 增删改查、切换和测试连接 |
| `sandbox/policy/set` | 修改沙箱策略 |
| `approval/policy*` | 修改审批策略 |
| `run/turns/list` / `run/turn/get` | 查询运行记录 |
| `observability/*` | 查询本地统计 |
| `mcp/*` | 查询和刷新 MCP server / tools |
| `context/status` / `context/snapshot/get` / `context/compact` | 查询上下文状态、快照和手动压缩 |
| `memory/status` / `memory/settings/set` / `memory/jobs/list` / `memory/artifacts/list` / `memory/consolidate` | 查询和控制长期记忆 |

常用 Server -> Client 通知：

| 通知 | 说明 |
|---|---|
| `turn/started` | turn 开始 |
| `item/added` | 新增 ThreadItem |
| `item/updated` | 流式更新 ThreadItem |
| `approval/request` | 请求用户审批工具调用 |
| `turn/completed` | turn 完成 |
| `turn/failed` | turn 失败 |

---

## Agent 运行流程

```text
1. Desktop 发送 turn/start
2. 后端创建 Turn，写入 userMessage item
3. ContextWindowRuntime 读取历史、短期摘要、长期记忆 summary 和能力目录
4. ContextAssembler 生成本轮模型可见上下文
5. 如果超过预算，ContextCompactionService 触发短期压缩并安装 active summary
6. AgentLoop 调用 Spring AI Alibaba ReactAgent / ChatModel
7. 模型需要工具时进入 ToolRegistry
8. 写入类或执行类工具经过审批和沙箱
9. 工具结果通过 Spotlighting 包裹为非可信数据，再回灌给模型
10. 后端持续推送 item/added、item/updated、turnSummary 和 turn/completed
11. ConversationEventRecorder、RunRecordService、MemoryPipeline 写入 SQLite 审计记录
```

---

## 数据持久化

BaBiQ 从 P2 起以 SQLite 为事实源。数据库由 Flyway migration 创建，业务表统一使用 `bq_*` 前缀。

已建立的主要表族：

| 表族 | 说明 |
|---|---|
| `bq_threads` / `bq_turns` / `bq_items` | 会话、轮次、消息和工具事件 |
| `bq_turn_summaries` | token、耗时、工具次数，不记录价格成本 |
| `bq_provider_configs` / `bq_app_settings` | Provider 和应用设置 |
| `bq_approvals` / `bq_approval_rules` | 审批请求、响应和本会话 always rule |
| `bq_tool_calls` | 工具调用审计 |
| `bq_mcp_servers` / `bq_mcp_tools` | MCP server 和 MCP 工具目录 |
| `bq_context_windows` / `bq_context_snapshots` | 当前上下文窗口和每轮快照 |
| `bq_context_summaries` / `bq_context_compactions` | 短期压缩摘要和压缩审计 |
| `bq_memory_jobs` / `bq_memory_candidates` / `bq_memory_artifacts` / `bq_memory_references` | 长期记忆任务、候选、产物和引用 |
| `bq_schema_comments` | SQLite 字段中文说明元数据 |

项目有 `SchemaCommentsCoverageTest` 校验每个业务表字段都有中文说明，避免 migration 和 Entity 失去教学可读性。

---

## Provider 与模型

BaBiQ 支持通过配置和桌面设置切换 Provider：

- DashScope / 通义千问。
- DeepSeek 官方 OpenAI-compatible endpoint。
- OneAPI / NewAPI 等 OpenAI 兼容中转。
- Ollama 本地 OpenAI-compatible endpoint。

API Key 不应写入仓库，默认通过环境变量读取：

```powershell
$env:AI_DASHSCOPE_API_KEY = "..."
$env:DEEPSEEK_API_KEY = "..."
$env:ONEAPI_BASE_URL = "https://your-relay.example.com/v1"
$env:ONEAPI_KEY = "..."
$env:ONEAPI_MODEL = "..."
```

P2 起桌面端支持 Provider 新增、编辑、删除、测试连接和切换 active provider。API Key 由后端写入本地 JDK `JCEKS` KeyStore，数据库和 API 响应只暴露 `secretRef` / `hasApiKey`。

---

## 工具、审批和沙箱

### 本地工具

当前已实现的本地工具：

| 工具 | 说明 |
|---|---|
| `read_file` | 读取文件 |
| `write_file` | 写入文件 |
| `exec_shell` | 执行 shell 命令 |
| `list_dir` | 列目录 |
| `grep` | 文本搜索 |
| `apply_patch` | 应用补丁 |

### MCP 工具

P2-6 已接入本地 stdio MCP Client：

- 支持启动配置好的本地 MCP server。
- 支持拉取 MCP tools 并合并进 `ToolRegistry`。
- MCP 工具命名格式为 `mcp.<serverId>.<toolName>`。
- MCP 工具继续经过审批、沙箱、Spotlighting、工具观测和 TurnSummary。

当前不支持远程 MCP、OAuth、插件市场和任意 command 编辑入口。

### 审批

审批策略支持：

- 按需询问。
- 从不询问。
- 本会话内 always allow rule。

`approval/respond` 支持 `decision=always`，但只在当前 session scope、tool name 和 args fingerprint 下生效，不提供永久全局放行。

### 沙箱

沙箱策略支持：

- `read-only`
- `workspace-write`
- `danger-full-access`

当前阶段是 Java PathGuard + 工作区路径校验，不是真 OS 沙箱。真正的容器、jail、microVM、网络隔离属于后续阶段。

---

## 上下文与记忆系统

BaBiQ P3 的重点是 Codex 级上下文工程，而不是简单把历史消息塞进模型。

### 当前窗口管理

P3-2 已实现 `ContextWindowRuntime`：

- 每个 turn 调模型前生成临时上下文视图。
- 用户真实输入仍写入 `bq_items`，模型收到的是临时上下文视图，避免污染聊天历史。
- 每轮生成 `ContextSnapshot`，记录 included/excluded items、token 估算、模型窗口和排除原因。
- 提供 `context/status` 和 `context/snapshot/get`。
- 桌面端输入栏和运行详情展示上下文状态。

### 短期记忆 / 上下文压缩

P3-3 已实现短期压缩：

- 超过 75% 阈值时触发 pre-turn 自动压缩。
- 支持 `context/compact` 手动压缩。
- 压缩成功后安装 active summary，并递增 window ordinal。
- `ContextAssembler` 注入 `short_term_summary` 层，并用 `REPLACED_BY_SUMMARY` 排除被 summary 覆盖的旧历史。
- 桌面端支持 `contextCompaction` item 和“已压缩 N 次”状态。

P3-3A 已完成鲁棒性补强：

- 压缩审计字段。
- 事务安装边界。
- `window_ordinal` 乐观锁。
- 启动恢复清理 interrupted/orphaned 压缩记录。

### 长期记忆

P3-4 已实现长期记忆异步流水线：

- Phase 1 idle 扫描 thread，抽取候选记忆。
- Java secret redaction。
- `SECRET_RISK` 记忆隔离。
- Phase 2 generation 归并。
- Markdown mirror：生成用户可读的长期记忆文件。
- Read path 默认注入 `memory_summary`，不把完整 `MEMORY.md` 塞进模型。
- 提供 `memory/status`、`memory/settings/set`、`memory/jobs/list`、`memory/artifacts/list`、`memory/consolidate`。
- 桌面端已接入长期记忆状态和最小控制。

---

## 当前完成进度

### 已完成：P1 内核和桌面最小可用

- WebSocket + JSON-RPC 2.0 通信。
- Thread / Turn / Item 状态模型。
- ReactAgent 主循环和 ReAct 工具调用。
- 6 个本地核心工具。
- HITL 审批弹窗。
- PathGuard 沙箱。
- Spotlighting 工具输出防 prompt injection。
- TurnSummary token、耗时和工具次数反馈。
- Compose Desktop V2 Chat UI。
- Provider / 模型切换 UI。
- 运行详情面板。
- 断线提示和自动重连。

### 已完成：P2 持久化、设置、运行记录、MCP

- SQLite + MyBatis-Plus + Flyway 持久化底座。
- `bq_*` 业务表和中文字段说明覆盖测试。
- 多会话历史：`thread/list`、`thread/load`、`thread/archive`。
- 桌面端最近对话列表。
- Provider 设置系统。
- API Key JCEKS 本地存储。
- 沙箱策略和审批策略设置。
- `approval/respond decision=always`。
- 启动恢复语义：遗留 running / waiting approval turn 收束。
- 运行记录和工具调用记录。
- 本地可观测统计：turns、tokens、状态分布、工具分布。
- 本地 stdio MCP Client 最小接入。
- MCP 工具纳入 ToolRegistry、审批、沙箱和观测链路。
- DeepSeek V4 官方 endpoint thinking + tool call 恢复适配。

### 已完成：P3-1 上下文与记忆平台底座

- `ContextAssembler`
- `ContextSnapshot`
- `ContextEnvelope`
- `ContextTokenEstimator`
- `CapabilityCatalogAssembler`
- 能生成分层 envelope、included/excluded snapshot 和能力目录摘要。

### 已完成：P3-2 当前窗口运行时

- `ContextWindowRuntime` 接入 `AgentLoop`。
- `bq_context_windows`、`bq_context_snapshots`。
- `context/status`、`context/snapshot/get`。
- 运行详情展示 context snapshot。
- 桌面端输入栏上下文 chip。

### 已完成：P3-3 短期记忆和上下文压缩

- `ContextBudgetPolicy`
- `CompactionSourceSelector`
- `ContextCompactionService`
- `SpringAiContextCompactionStrategy`
- `ContextManualCompactionService`
- `bq_context_summaries`
- `bq_context_compactions`
- 自动压缩和手动压缩。
- active summary 安装和上下文替换。

### 已完成：P3-3A 压缩鲁棒性补强

- 压缩审计字段补齐。
- 压缩安装事务边界。
- `window_ordinal` 乐观锁。
- 压缩恢复服务。
- CAS 冲突记录和回退。

### 已完成：P3-4 长期记忆异步流水线

- `LongTermMemoryPipeline`
- `LongTermMemoryReadService`
- `LongTermMemoryScheduler`
- `MemoryPhase2TriggerService`
- `SpringAiMemoryStageOneExtractor`
- `SpringAiStructuredMemoryConsolidationStrategy`
- `MemorySecretRedactor`
- `MemoryArtifactMirror`
- `bq_memory_jobs`
- `bq_memory_candidates`
- `bq_memory_artifacts`
- `bq_memory_references`
- 长期记忆状态、设置、任务、artifact 和手动归并 JSON-RPC。
- 桌面端长期记忆状态和控制。

### 已完成：P3-5 / P3-5a 能力装配与中文检索

- 能力目录已统一 local tool、MCP tool 和 Skill metadata。
- `tool_search` 已接入 BaBiQ 自有能力门控，模型按需发现 deferred 能力，实际执行仍走审批、沙箱、Spotlighting 和 SQLite 审计。
- 长期记忆 read path 已支持有界检索增强。
- 能力搜索底层已替换为 Spring AI Community `tool-searcher-lucene` / Lucene BM25。
- `CapabilityCatalogSyncService` 已通过中文别名字典富化 searchText，典型中文 query 可以命中本地工具和 MCP 工具。

### 已完成：P3-6 官方 SkillRegistry 薄封装

- `LocalSkillRegistry` 已改为薄封装 Spring AI Alibaba 官方 `FileSystemSkillRegistry`。
- 默认 Skill 目录已迁移为 `~/.agents/skills` 和 `<cwd>/.agents/skills`。
- 旧 `~/.codex/skills` 不再默认扫描；如果需要兼容旧目录，请通过 `babiq.skills.additional-directories` 显式配置。
- `allowedTools` 仅作为后端 Skill metadata 透出，不参与工具授权，不做桌面展示。
- 本阶段不接 `SkillPromptAugmentAdvisor`、`SpringAiSkillAdvisor`、`SkillsAgentHook`，继续保持 BaBiQ 的 `tool_search`、审批、沙箱和 SQLite 审计链路。

---

## 尚未完成 / 后续路线

### 后续可选专项

- 第三方 Skill 受管安装 / Skill 市场。
- VectorStore 语义能力搜索和语义记忆检索增强。
- 运行中逐工具审批 + flow 并发中断恢复。
- P6-3 实时 team 协作。
- 远程 MCP、OAuth、A2A、更强 OS 级沙箱和多模态能力。

### P3/P4+ 未完成能力

- 真 OS 沙箱：容器、jail、microVM、网络默认 deny。
- 远程 MCP、OAuth、MCP 插件市场。
- Multi-Agent：SequentialAgent、RoutingAgent、SupervisorAgent。
- A2A 跨机器 Agent 协议。
- 多模态：图片、截图、音频、PDF。
- Agentic RAG 代码库语义搜索。
- 更完整的记忆管理 UI：查看、删除、污染标记、引用追溯。
- GitHub Actions CI。
- 发布包、安装器和自动更新。
- 生产级安全审计。

### 当前已知边界

- BaBiQ 仍是学习项目，不是生产系统。
- 当前沙箱不是 OS 级隔离。
- 当前 MCP 只做本地 stdio Client。
- 长期记忆已实现 summary read path，但检索增强还在 P3-5 计划中。
- P3-5 之前不会把所有工具 schema、完整 Skill 正文或完整 `MEMORY.md` 常驻注入模型上下文。

---

## 启动方式

### 1. 配置环境变量

至少配置一个可用 Provider 的 API Key。

```powershell
$env:AI_DASHSCOPE_API_KEY = "your-dashscope-key"
$env:DEEPSEEK_API_KEY = "your-deepseek-key"
```

如果使用 OpenAI-compatible 中转：

```powershell
$env:ONEAPI_BASE_URL = "https://your-relay.example.com/v1"
$env:ONEAPI_KEY = "your-relay-key"
$env:ONEAPI_MODEL = "your-model"
```

### 2. 启动后端

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

默认服务端口：

```text
http://localhost:8080
ws://localhost:8080/ws/agent
```

### 3. 启动桌面端

```powershell
cd desktop
.\gradlew.bat --no-daemon run
```

---

## 测试

后端完整验证：

```powershell
cd backend
.\mvnw.cmd clean verify
```

桌面端完整验证：

```powershell
cd desktop
.\gradlew.bat test
```

P3-4 长期记忆相关核心测试：

```powershell
cd backend
.\mvnw.cmd -q -Dtest="MemorySecretRedactorTest,MemoryArtifactMirrorTest,LongTermMemoryReadServiceTest,MemoryPhase2TriggerServiceTest,LongTermMemoryPipelineTest,MemoryHandlersTest,SchemaCommentsCoverageTest,ContextWindowRuntimeTest,ContextWindowRuntimeCompactionTest,ContextAssemblerTest,ContextAssemblerCompactionTest,ContextCompactionServiceTest,ContextCompactionRecoveryServiceTest" test
```

---

## 配置和安全

开源仓库中不要提交：

- `.env`
- `application-local.yml`
- `application-secret.yml`
- SQLite 数据库
- JCEKS / KeyStore
- 日志
- 真实 API Key
- 本地绝对路径里的私密信息

仓库里的 `application.yml` 只能保留环境变量占位，例如：

```yaml
api-key: ${DEEPSEEK_API_KEY:}
```

如果曾经把真实 Key 推送到公开仓库，请立即轮换 / 作废该 Key，并使用 `git filter-repo` 或 BFG 清理历史。

---

## 文档入口

| 文档 | 说明 |
|---|---|
| `docs/ARCHITECTURE.md` | 长篇架构设计和早期路线材料 |
| `docs/superpowers/plans/p2-master.md` | P2 总计划 |
| `docs/superpowers/plans/p3-master.md` | P3 上下文和记忆平台总计划 |
| `docs/superpowers/plans/p3-task-index.md` | P3 子任务索引 |
| `docs/superpowers/plans/p3-4-long-term-memory/plan.md` | P3-4 长期记忆实施计划 |
| `docs/superpowers/plans/p3-5-capability-retrieval-control/plan.md` | P3-5 按需能力和检索增强计划 |

---

## License

当前仓库尚未在 README 中声明最终 License。公开开源前建议补充 `LICENSE` 文件。Java / Spring 生态项目可以优先考虑：

- Apache-2.0
- MIT
