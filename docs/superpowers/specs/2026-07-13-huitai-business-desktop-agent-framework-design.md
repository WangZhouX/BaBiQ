# 汇泰业务桌面 Agent 通用框架设计

> 日期：2026-07-13  
> 状态：已获用户确认，已按首轮规格审查修订，待复审  
> 目标仓库：`E:\huitai-work\BaBiQ`  
> 业务参考：`E:\huitai-work\huitai-law-oa`、`E:\huitai-work\huitai_cloud`

概念原型：

- `docs/superpowers/specs/assets/huitai-business-desktop-agent-framework/huitai-business-agent-v1.png`
- `docs/superpowers/specs/assets/huitai-business-desktop-agent-framework/huitai-business-agent-v1.html`

## 1. 背景与目标

汇泰需要一个纯 Kotlin Compose 业务桌面客户端，内置 BaBiQ Agent 能力。用户可以通过对话让 Agent 理解当前页面和输入资料，提出结构化表单变更、执行应用操作、调用汇泰业务接口，并在高风险动作前获得明确审批。

本阶段只建设通用框架，不迁移客户、案件、文书等具体 OA 业务。框架验收完成后，才能进入业务迁移阶段。

### 1.1 本阶段必须完成

1. 独立的 `business-desktop/` Kotlin Compose 多模块工程。
2. 业务展示核心。
3. 应用操作核心。
4. Agent 客户端和内置本地 Agent 服务桥。
5. 汇泰 HTTP、认证、租户、权限和业务 WebSocket 集成底座。
6. 风险分级、审批、幂等和审计底座。
7. 不依赖真实 OA 服务的通用框架演示。
8. 单元、协议、集成、桌面和打包级自动化验证。
9. 框架人工烟测和验收报告。

### 1.2 明确非目标

- 不实现客户、案件、文书、日程、审批等真实 OA 页面。
- 不复制 `huitai-law-oa` 的 Vue、Element Plus 或 Pinia 代码。
- 不在框架演示中使用真实律师业务模型和动作名。
- 不让 Agent 通过鼠标坐标、键盘模拟、截图识别或 Compose 反射控制应用。
- 不让 Agent 直接读取 Token、密码、Refresh Token 或本地密钥。
- 不承诺跨 Agent 进程恢复 Spring AI Alibaba 的内存暂停点。
- 不把 `huitai_cloud` 代码迁入 BaBiQ。

## 2. 总体决策

### D1：新增独立业务桌面产品

在 BaBiQ 根目录新增 `business-desktop/`，现有 `desktop/` 继续作为 BaBiQ 通用客户端。两个客户端都可以复用 `backend/` Agent 服务，但 UI、状态和产品语义相互独立。

### D2：纯 Kotlin Compose

业务界面使用 Kotlin Compose 原生实现，不嵌入现有 Vue 页面。`huitai-law-oa` 仅作为页面、接口、字段、校验、权限和流程迁移依据。

### D3：双进程内置 Agent

Compose 主程序自动启动安装包内的 `babiq-server.jar`，后端只绑定动态 loopback 端口。用户不需要独立安装或管理 Agent 服务。

### D4：应用操作核心是唯一写入口

用户点击和 Agent 操作必须经过同一个 `ApplicationActionBus`。Agent 不得直接修改 Compose 状态，也不得绕过动作核心调用汇泰 API。

### D5：结构化页面上下文

Agent 通过桌面主动发布的 `PageContextSnapshot` 理解当前页面，不使用视觉识别或控件树扫描。

### D6：写操作先预览，高风险独立审批

普通写入必须展示结构化变更预览；提交、发送、删除等高风险操作必须每次独立确认，不能使用本会话永久放行。

### D7：业务模式运行数据完全隔离

业务桌面使用独立 Spring profile、数据库、KeyStore、日志、记忆目录和单实例锁。现有通用 `desktop/` 与 `business-desktop/` 不能共享 `~/.babiq` 运行数据，也不能互相恢复或收束对方的 Turn。

### D8：业务模式能力默认拒绝

业务模式只允许显式 allowlist 中的工具。首期只暴露 `application_action`、`update_plan` 和必要的纯只读 Agent 内部能力；文件写入、Shell、任意 MCP、Skill、Flow、Team 和子 Agent 默认禁用，防止绕过 `ApplicationActionBus` 产生副作用。

### D9：业务审批只由桌面动作核心负责

`application_action` 不复用后端通用工具 HITL 审批。动作风险、预览和审批由桌面的 `ApplicationActionBus` 与 `ActionRiskPolicy` 独占处理，后端仅等待结构化终态，避免出现两次审批或风险判断不一致。

### D10：远程写入必须声明可靠性语义

每个远程动作必须声明是否可安全重放、是否支持远程幂等键、是否支持结果对账。无法确认远程真实结果时进入 `OUTCOME_UNKNOWN`，不得自动重复写入。

### D11：业务长期记忆首期关闭

业务 profile 首期关闭长期记忆生成、读取和检索。Thread、Turn、上下文和审计仍按用户/租户身份隔离。只有后续完成 tenant/user scoped 长期记忆设计与测试后，才允许在业务客户端开启长期记忆。

## 3. 系统架构

```text
┌─────────────────────────────────────────────────────────────┐
│ business-desktop · Kotlin Compose                           │
│                                                             │
│  Business UI ── Application Action Core ── Huitai Client   │
│       │                  │                     │             │
│  Page Context       Approval/Audit       HTTPS/WebSocket    │
│       │                  │                     │             │
│  Agent Panel ───── Agent Client Bridge       huitai_cloud   │
└───────────────────────┬─────────────────────────────────────┘
                        │ JSON-RPC 2.0 / WebSocket
┌───────────────────────▼─────────────────────────────────────┐
│ backend · bundled local Agent service                       │
│                                                             │
│ ReactAgent / Context / Memory / Plan / Reasoning            │
│                 │                                           │
│         application_action tool                             │
│                 │                                           │
│ PendingApplicationActions / Tool Audit / Recovery           │
└─────────────────────────────────────────────────────────────┘
```

### 3.1 业务 profile 与运行目录

`business-desktop` 启动后端时固定传入 `business-desktop` profile，并使用独立目录：

```text
${user.home}/.huitai-agent-desktop/
├── agent/
│   ├── data/babiq-business.db
│   ├── secrets/business-agent.jceks
│   ├── logs/backend.log
│   ├── memory/                 # 首期存在但长期记忆关闭
│   └── instance.lock
└── desktop/
    ├── data/business-desktop.db
    ├── secrets/business-desktop.jceks
    ├── logs/desktop.log
    └── instance.lock
```

启动参数必须显式覆盖数据源、日志、KeyStore 和记忆根目录。业务后端实例只扫描自己的数据库做启动恢复。桌面和 Agent 各自持有进程级文件锁，同一 Windows 用户默认只允许一个业务桌面实例运行。

### 3.2 身份隔离

业务模式中的 Thread、Turn、ContextSnapshot、ApplicationAction 和桌面审计都绑定：

```text
userId
tenantId
platformId
authSessionId
desktopInstanceId
```

任何历史查询、上下文装配和动作结果查询都必须按该 identity scope 过滤。租户切换或登出会创建新的 `authSessionId`，旧会话不能再注入当前 Agent 上下文。

## 4. 工程结构与依赖

```text
business-desktop/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── app/
├── presentation-core/
├── application-action-core/
├── agent-client-core/
├── huitai-integration-core/
├── security-audit-core/
└── framework-demo/
```

### 4.1 模块职责

| 模块 | 职责 |
|---|---|
| `app` | Compose 入口、窗口、路由、模块装配、内置 Agent 生命周期 |
| `presentation-core` | 页面契约、StateFlow、页面上下文、统一 Shell 和 Agent 面板容器 |
| `application-action-core` | 动作注册、参数校验、预览、状态机、执行、风险/审批/审计端口和结果模型 |
| `agent-client-core` | JSON-RPC、重连、上下文发布、动作请求和响应关联 |
| `huitai-integration-core` | Ktor HTTP、认证、Token 刷新、租户、权限和业务 WebSocket |
| `security-audit-core` | 风险策略、审批、脱敏、幂等、SQLite 审计 |
| `framework-demo` | 通用资料录入页面、演示动作和 Fake 汇泰适配器 |

### 4.2 依赖方向

```text
app
├── presentation-core
├── agent-client-core
├── huitai-integration-core
├── security-audit-core
└── framework-demo

presentation-core ──> application-action-core
agent-client-core ──> application-action-core
huitai-integration-core ──> application-action-core
security-audit-core ──> application-action-core
framework-demo ──> presentation-core + application-action-core
```

核心模块禁止依赖 `framework-demo`。`application-action-core` 禁止依赖 Compose、Ktor、SQLite 和 Agent 协议实现。风险判断、审批存储、审计持久化和远程调用只以端口存在，分别由 `security-audit-core`、`huitai-integration-core` 提供 adapter，避免反向依赖和循环依赖。

## 5. 业务展示核心

### 5.1 页面状态契约

```kotlin
interface BusinessScreenContract<S : Any, E : Any> {
    val state: StateFlow<S>
    fun dispatch(event: E)
}
```

每个页面由不可变 `ScreenState`、`ScreenEvent` 和纯 `ScreenReducer` 组成。网络、持久化和 Agent 副作用放在 Controller/UseCase 中，不进入 Reducer。

### 5.2 Agent 页面契约

```kotlin
interface AgentAwareScreen {
    fun pageContext(): PageContextSnapshot
}
```

```kotlin
data class PageContextSnapshot(
    val snapshotId: String,
    val pageId: String,
    val pageTitle: String,
    val route: String,
    val revision: Long,
    val mode: PageMode,
    val entityReferences: List<EntityReference>,
    val fields: List<FieldContext>,
    val availableActions: List<AvailableAction>,
    val validationSummary: ValidationSummary,
    val selection: SelectionContext?,
)
```

字段包含 ID、标签、类型、可编辑性、必填性、校验错误和敏感等级。页面上下文是 Agent 可见页面事实的唯一来源。

### 5.3 敏感等级

```text
PUBLIC
INTERNAL
SENSITIVE
SECRET
```

- `PUBLIC`、`INTERNAL` 按需要提供。
- `SENSITIVE` 默认脱敏。
- `SECRET` 永不进入 Agent 协议。
- Token、密码和密钥不得注册为页面字段。

### 5.4 FormPatch

```kotlin
data class FormPatch(
    val pageId: String,
    val baseRevision: Long,
    val changes: List<FieldChange>,
)
```

每个变更包含旧值、新值、原因、置信度和来源。执行前必须验证页面 ID、revision、字段可编辑性、权限、类型和业务校验。

页面 revision 变化时旧 Patch 返回 `CONTEXT_STALE`，不得覆盖用户最新输入。

## 6. 应用操作核心

### 6.1 动作接口

```kotlin
interface ApplicationAction<I : Any, O : Any> {
    val descriptor: ActionDescriptor

    // 必须是无副作用纯预览，禁止调用远程写接口或修改页面状态。
    suspend fun preview(input: I, context: ActionContext): ActionPreview

    suspend fun execute(input: I, context: ActionContext): ActionResult<O>

    // 远程写入结果不确定时用于查询或对账；不支持时返回 Unsupported。
    suspend fun reconcile(
        input: I,
        context: ActionContext,
        remoteReference: String?,
    ): ReconciliationResult
}
```

### 6.2 动作描述

```kotlin
data class ActionDescriptor(
    val id: String,
    val version: Int,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val riskLevel: ActionRiskLevel,
    val requiredPermissions: Set<String>,
    val target: ActionTarget,
    val replayPolicy: ActionReplayPolicy,
    val reconciliationPolicy: ReconciliationPolicy,
)
```

```text
ActionReplayPolicy
├── SAFE                    # 只读或后端明确幂等，可在刷新后重放
├── IDEMPOTENCY_KEY_REQUIRED# 写操作必须把 executionId 传为远程幂等键
└── NEVER                   # 不自动重放，只能对账或人工处理
```

### 6.3 风险等级

```text
READ_ONLY
REVERSIBLE_WRITE
HIGH_RISK
```

| 风险 | 行为 |
|---|---|
| `READ_ONLY` | 权限校验后自动执行 |
| `REVERSIBLE_WRITE` | 先展示差异，确认后执行 |
| `HIGH_RISK` | 独立审批，每次执行单独确认 |

未知动作、未知字段和未知风险默认拒绝。

### 6.4 统一命令

```kotlin
data class ActionCommand(
    val executionId: String,
    val actionId: String,
    val input: JsonObject,
    val origin: ActionOrigin,
    val contextRevision: Long,
)
```

用户点击和 Agent 请求都进入同一个 ActionBus，仅通过 `origin=USER|AGENT` 区分来源。

### 6.5 状态机

```text
RECEIVED
→ VALIDATING
├─ READ_ONLY ─────────────────────→ EXECUTING
└─ WRITE → PREVIEWED
           ├─ REJECTED ───────────→ CANCELED
           └─ ACCEPTED
               ├─ REVERSIBLE_WRITE → EXECUTING
               └─ HIGH_RISK → WAITING_APPROVAL
                              ├─ DENIED → CANCELED
                              └─ APPROVED → EXECUTING

EXECUTING
→ SUCCEEDED / FAILED / CANCELED / EXPIRED / OUTCOME_UNKNOWN
```

每个状态迁移必须有测试。终态不可再次迁移。

`preview()` 必须无副作用。只读动作不进入 preview/approval；可逆写必须预览；高风险动作在接受预览后再进入独立审批。

### 6.6 幂等

`executionId` 是动作幂等键，同时保存 `actionId` 和输入 fingerprint。

- 相同 executionId 已成功：返回原结果。
- 相同 executionId 正在执行：等待或返回运行中，不重复执行。
- 相同 executionId 但 actionId/fingerprint 不同：返回协议冲突。
- 已失败、取消或过期：返回原终态。
- `OUTCOME_UNKNOWN`：只允许进入 reconciliation，不允许再次 execute。

### 6.7 远程可靠性

远程写操作必须遵守：

1. `executionId` 同时作为本地幂等键和远程幂等键；如果现有 OA endpoint 不支持幂等键，该动作必须声明 `NEVER` 自动重放。
2. 401/499 刷新后只自动重放 `SAFE` 或 `IDEMPOTENCY_KEY_REQUIRED` 且已成功附带幂等键的请求。
3. 已发送写请求但响应丢失时进入 `OUTCOME_UNKNOWN`，调用 `reconcile()` 或业务查询接口确认，不得直接重发。
4. 对账确认成功后转为 `SUCCEEDED`；确认失败转为 `FAILED`；仍无法确认则保持 `OUTCOME_UNKNOWN` 并要求人工处理。
5. 框架演示必须提供一个可模拟“远程已写入但响应丢失”的 Fake Gateway 场景。

## 7. Agent 核心与桌面动作桥

### 7.1 复用范围

业务 profile 继续复用现有 BaBiQ 的核心运行时：

- Spring AI Alibaba ReactAgent。
- ContextWindowRuntime 和短期压缩；长期记忆首期关闭。
- Reasoning、Plan、Tool Call 和 TurnSummary。
- Provider 和能力目录基础设施。
- HITL、运行记录、恢复和观测。

新增一个统一的 `application_action` 工具，不为每个桌面动作创建独立 Java 工具类。

业务 profile 的模型可见工具采用固定 allowlist：

```text
application_action
update_plan
```

框架演示不暴露 `write_file`、`apply_patch`、`exec_shell`、任意 MCP、Skill、Flow、Team、子 Agent 或其他可能产生副作用的工具。未来需要开放时必须经过单独设计，并证明所有副作用仍受业务动作风险策略约束。

### 7.2 动作目录同步

桌面连接后发送：

```text
application/catalog/register
application/catalog/update
application/context/publish
application/action/status
application/identity/bind
application/identity/update
```

目录与页面上下文 envelope 包含：

```text
protocolVersion
desktopInstanceId
authSessionId
catalogEpoch
contextSequence
generatedAt
payloadSize
```

后端只接受同一认证连接上递增的 `catalogEpoch/contextSequence`；旧 epoch、旧 identity 或超过最大载荷的消息被拒绝。后端只向模型提供当前页面、当前权限下可用的动作摘要和 schema。描述、字段值和来源全部作为不可信数据注入模型。

### 7.3 双向协议

Server 到 Desktop：

```text
application/action/request
application/action/cancel
```

Desktop 到 Server：

```text
application/action/accepted
application/action/previewed
application/action/approval-required
application/action/running
application/action/completed
application/action/failed
application/action/rejected
application/action/canceled
application/action/expired
application/action/outcome-unknown
```

双向 Request 方法：

```text
application/action/status
application/action/result/get
```

所有消息包含：

```text
desktopSessionId
threadId
turnId
toolCallId
executionId
protocolVersion
authSessionId
tenantId
userId
sequence
```

服务端不信任 payload 自报的 `desktopSessionId/userId/tenantId`。这些字段必须与握手认证得到的连接身份完全一致，否则返回 `PROTOCOL_ERROR` 并关闭连接。

可信身份绑定分两层：

1. WebSocket 握手只建立本机桌面进程身份：`desktopInstanceId + WebSocketSession.id`。
2. 汇泰登录成功后，Desktop 通过认证连接发送 `application/identity/bind`，载荷包含 `identityEpoch`、`authSessionId`、`userId`、`tenantId`、`platformId`、roles 和 permissions 摘要。
3. Backend 将该 identity 绑定到真实 WebSocketSession，而不是信任后续动作消息自报身份。
4. Token 刷新但业务身份不变时不增加 epoch；登录、登出、用户变化和租户切换必须增加 `identityEpoch` 并发送 `application/identity/update`。
5. Backend 只接受当前连接上严格递增的 identityEpoch；旧 epoch 消息被拒绝。
6. identity 变化时，Desktop 先取消所有未执行预览；Backend 把旧 identity 下 WAITING/EXECUTING 之前的 Turn 收束为 `EXPIRED`。已进入远程 EXECUTING 的动作不能假定取消，必须走 status/reconciliation，且结果只能写入旧 identity scope，不能注入新租户上下文。
7. 登出后连接保留为“本机已认证、汇泰未登录”状态，只允许登录和框架级安全动作，不允许发布业务页面动作。

### 7.4 等待模型

后端使用 `PendingApplicationActions` 维护 executionId 到等待结果的关联。工具协程等待桌面结果，完成、失败、拒绝、取消、过期和结果不确定只能消费一次。

职责边界：

- Desktop `ApplicationActionBus` 决定 preview、风险和审批。
- Backend 不再对 `application_action` 触发通用 HITL；只把桌面动作状态作为工具进度显示。
- Desktop 返回终态后，Backend 才完成 `application_action` 工具调用并回灌模型。
- `application/action/cancel` 与 Desktop 执行竞态时，以 Desktop 持久化的首个终态为准。

超时归属：

```text
acceptTimeout   Backend 等待 Desktop 接收请求
previewTimeout  Desktop 等待生成预览
approvalTimeout Desktop 等待用户确认
executeTimeout  Backend 等待 Desktop 终态
```

`acceptTimeout`、`previewTimeout` 和 `approvalTimeout` 发生时动作尚未进入副作用阶段，可以安全生成 `EXPIRED`。`executeTimeout` 发生时不得直接过期：Backend 先通过 `application/action/status` 查询 Desktop 持久化状态；若 Desktop 已有终态则采用该终态，仍在执行则继续等待有界 grace period，无法确认本地或远程真实结果时进入 `OUTCOME_UNKNOWN` 并触发 reconciliation。执行阶段迟到结果用于 reconciliation 和审计，但不得恢复已经结束的模型 Turn。

### 7.5 断线和恢复

| 场景 | 语义 |
|---|---|
| 未确认预览时断线 | 取消，不执行 |
| 已确认但未开始 | 取消，不执行 |
| 本地动作执行中断线 | 完成动作并持久化结果，重连后通过 `application/action/result/get` 查询 |
| 远程请求中断线 | 进入 `OUTCOME_UNKNOWN`，只允许 reconciliation |
| Agent 服务重启 | 未进入执行的 Turn 收束为 `INTERRUPTED` 或 `EXPIRED`；执行中动作由桌面事实源进入状态查询或 `OUTCOME_UNKNOWN` |
| Desktop 重启 | 未完成预览失效，不恢复旧模型暂停点 |

重连后桌面用 `application/action/status` 对齐非终态执行；后端若仍持有同一进程暂停点，可继续等待，否则将旧 Turn 收束为 `EXPIRED`。迟到响应只记审计，不恢复已终止工具。

### 7.6 本机会话认证

业务桌面启动 Agent 服务时生成一次性 `desktopSessionToken`：

- 后端只绑定 `127.0.0.1`。
- Token 通过权限受限的临时文件传给子进程；后端读取后立即删除文件，不通过命令行或日志传递。
- WebSocket 握手使用 `Authorization: Bearer <desktopSessionToken>`，由业务 profile 专用 handshake interceptor 校验。
- Token 只存在于当前桌面和 Agent 子进程内存，连接建立后绑定到真实 `WebSocketSession.id`。
- 每个 turn 绑定 `desktopSessionId`。
- 业务客户端模式不允许 `allowed-origins: "*"`。
- 未携带 Token、Token 不匹配、重复绑定或连接身份漂移时拒绝握手。
- 业务 profile 必须强制开启认证，不能通过配置关闭。

## 8. 汇泰集成底座

### 8.1 组件

```text
HuitaiHttpClient
AuthSessionManager
AuthenticationStateMachine
TenantContextManager
PermissionSnapshotProvider
TokenRefreshCoordinator
HuitaiWebSocketClient
SecretStore
CommonResultDecoder
```

### 8.2 请求边界

根据现有 OA 契约：

- 请求自动携带 `Authorization: Bearer ...`。
- 请求自动携带 `tenant-id`。
- 使用统一响应 envelope 解码。
- 二进制下载和 JSON 错误响应必须区分。
- 401/499 触发单飞刷新；只有动作 `replayPolicy` 允许的请求才在刷新成功后自动重放。
- 刷新失败清空登录态。
- `1002010000` 会员过期作为独立终态，不能进入 Token 刷新循环。
- 租户切换清理旧权限、旧页面上下文和未执行 Patch。

认证状态机：

```text
SIGNED_OUT
→ SIGNING_IN
→ AUTHENTICATED
→ REFRESHING
→ AUTHENTICATED / EXPIRED

AUTHENTICATED
→ SWITCHING_TENANT
→ AUTHENTICATED / EXPIRED

任意非终态 → MEMBERSHIP_EXPIRED / SIGNED_OUT
```

`AuthIdentitySnapshot` 至少包含：

```text
authSessionId
userId
tenantId
platformId
roles
permissions
authenticatedAt
```

登录成功、Token 刷新、租户切换和登出都发布新 identity snapshot。Thread、Turn、页面上下文、动作和审计必须绑定 snapshot，而不是临时读取全局变量。

Identity snapshot 发布到 Agent 后端时使用第 7.3 节的 `application/identity/bind|update` 协议。`identityEpoch` 是连接内单调递增序号，Backend 将其保存为可信连接身份；所有 catalog、context、turn 和 action 消息必须引用当前 epoch。租户切换和登出时，旧 epoch 立即停止接收新动作。

### 8.3 SecretStore

凭据不得进入普通配置、日志或 Agent 上下文。第一阶段提供可替换 `SecretStore` 端口和 JCEKS 实现；未来可以增加 Windows Credential Manager adapter。

### 8.4 框架阶段适配器

框架演示使用 `FakeHuitaiGateway`，真实 Ktor transport、认证刷新和 envelope 解码必须实现并测试，但不声明任何真实 OA endpoint。

## 9. 安全审批与审计

### 9.1 风险策略

```kotlin
interface ActionRiskPolicy {
    fun evaluate(
        descriptor: ActionDescriptor,
        command: ActionCommand,
        context: ActionContext,
    ): RiskEvaluation
}
```

决策依据包括动作基础风险、用户权限、租户、敏感字段、远程副作用和可撤销性。模型不能降低风险等级。

### 9.2 双层审计

Agent 后端审计：

- threadId、turnId、toolCallId。
- `application_action` 参数和结构化结果摘要。

桌面动作审计：

- executionId、actionId/version、origin。
- pageId、contextRevision、preview 摘要。
- 风险、审批人、审批结果。
- 状态、远程请求关联号、开始和完成时间。
- userId、tenantId、platformId、authSessionId、desktopInstanceId。
- accepted、previewed、rejected、approval、running、terminal 全部状态事件。
- 超时、取消竞态、迟到响应、OUTCOME_UNKNOWN 和 reconciliation 结果。

### 9.3 桌面审计库

业务桌面使用独立本地 SQLite 保存动作执行和审批事实。手动动作在 Agent 服务不可用时仍需审计。所有业务表和字段必须有中文说明，并提供 schema coverage test。

双层审计对账规则：Agent 的 `toolCallId` 必须关联唯一桌面 `executionId`；桌面动作不存在时 Agent 工具记录不得标记 completed；桌面终态存在但 Agent 断线时保留 orphaned link，重连后可查询但不能伪造原 Turn 恢复。

### 9.4 脱敏

审计禁止保存 Token、密码、密钥、完整文件内容和模型完整内部推理。身份证、手机号、银行账户等真实业务字段在后续业务阶段必须注册脱敏器。

## 10. 框架演示

### 10.1 UI

三栏布局：

- 左侧通用导航。
- 中间通用资料录入表单。
- 右侧 Agent 对话、计划、变更预览和审批。

### 10.2 演示字段

```text
资料名称
资料类型
联系人
金额
日期
状态
详细说明
```

### 10.3 演示动作

```text
page.navigate
page.read_context
form.read_state
form.preview_patch
form.apply_patch
demo.save_draft
demo.submit
```

### 10.4 演示场景

1. 用户输入一段非结构化资料。
2. Agent 读取当前页面上下文。
3. Agent 生成字段 Patch。
4. UI 展示旧值、新值、原因和来源。
5. 用户接受单字段或全部变更。
6. 普通保存经过预览后执行。
7. 提交动作触发独立高风险审批。
8. 结果写入审计并返回 Agent。

## 11. 错误模型

框架统一错误码至少包含：

```text
ACTION_NOT_FOUND
ACTION_DISABLED
PERMISSION_DENIED
VALIDATION_FAILED
CONTEXT_STALE
APPROVAL_DENIED
APPROVAL_EXPIRED
EXECUTION_CONFLICT
EXECUTION_TIMEOUT
DESKTOP_DISCONNECTED
AGENT_DISCONNECTED
AUTH_EXPIRED
MEMBERSHIP_EXPIRED
REMOTE_REQUEST_FAILED
OUTCOME_UNKNOWN
PROTOCOL_ERROR
```

错误必须区分用户可修复、需要重新登录、需要重试和不可重试。界面显示可读说明，审计保留结构化原因。

## 12. 测试策略

### 12.1 单元测试

- ActionRegistry 重名、版本和查询。
- ActionBus 用户/Agent 一致执行。
- 动作状态机和终态保护。
- FormPatch 类型、权限和 revision 校验。
- 风险升级和未知默认拒绝。
- executionId 幂等和冲突。
- 页面上下文脱敏。
- TokenRefreshCoordinator 单飞和安全重放。
- 非 replay-safe 写请求刷新后不自动重放。
- OUTCOME_UNKNOWN 与 reconciliation。
- AuditRedactor。

### 12.2 协议契约测试

- Java/Kotlin JSON round-trip。
- catalog register/update。
- context publish。
- action request/result 关联。
- accepted/running/rejected/canceled/expired/outcome-unknown 全终态。
- status/result 查询与重连对齐。
- protocolVersion、catalogEpoch、contextSequence 和最大载荷。
- identity bind/update、identityEpoch 递增、旧身份拒绝和租户切换收束。
- 未知字段向前兼容。
- 错误码和终态一致。

### 12.3 集成测试

- Agent tool 到 Desktop Action 再回到模型工具结果。
- 普通写入预览与确认。
- 高风险审批暂停与恢复。
- 重复 executionId。
- 断线、超时和迟到响应。
- executeTimeout 不误报 EXPIRED，结果未知时进入 reconciliation。
- SQLite 动作和审批审计。
- 本机会话 Token 拒绝未授权连接。
- 业务 profile 工具 allowlist 不暴露 Shell、文件写入、MCP 和 Skill。
- 通用 desktop 与 business-desktop 并行运行时数据库、KeyStore、日志和恢复互不影响。
- tenant/user identity scope 阻止跨租户历史、上下文和动作查询。

### 12.4 桌面测试

- 三栏布局和稳定尺寸。
- 建议字段高亮和来源。
- 单字段/全部接受。
- revision 冲突提示。
- 高风险审批弹窗。
- 断线、超时、失败和过期状态。
- 窄窗口下业务区和 Agent 面板不重叠。

### 12.5 最终验证

```powershell
cd backend
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --rerun-tasks

cd ..\business-desktop
.\gradlew.bat clean test --rerun-tasks
.\gradlew.bat packageDistributionForCurrentOS
```

还必须对生成的安装包执行自动化 smoke harness：安装/解包、启动业务桌面、验证内置 Agent 使用独立 profile 和动态 loopback 端口、验证未认证 WebSocket 被拒绝、关闭桌面后子进程退出。随后完成框架演示人工烟测。

## 13. 框架验收清单

以下条件全部满足才能进入 OA 业务迁移：

1. `business-desktop/` 可构建、测试和启动。
2. 三个核心和两个底座都有真实实现，不是空接口或占位目录。
3. 动作目录、页面上下文和动作结果协议端到端贯通。
4. 框架演示不依赖 `huitai_cloud`。
5. 用户和 Agent 共用同一个 ActionBus。
6. 普通写入先预览，高风险动作独立审批。
7. 幂等、权限、revision、断线、超时和迟到响应有自动化覆盖。
8. Agent 无法读取 Token 和 Secret。
9. 用户与 Agent 动作都有可关联审计。
10. 安装包自动启动并关闭本地 Agent 服务。
11. 生产源码不包含客户、案件、文书等具体 OA 实现。
12. 后端、现有 desktop、新 business-desktop 全量测试通过。
13. 完成框架人工烟测和验收报告。
14. 通用 desktop 与 business-desktop 的数据库、KeyStore、日志、记忆、锁和启动恢复完全隔离。
15. 业务 profile 的工具 allowlist 已证明无法绕过 ApplicationActionBus 写文件、执行 Shell 或调用未审核 MCP。
16. 用户/租户 identity scope 覆盖 Thread、Turn、上下文、动作和审计；长期记忆保持关闭。
17. 远程写入支持 replay policy、OUTCOME_UNKNOWN 和 reconciliation，不安全写请求不会自动重放。

## 14. 后续业务阶段

框架验收后再创建独立业务迁移计划：

1. 从 `huitai-law-oa` 生成页面、接口、字段、权限和流程清单。
2. 按业务域逐个迁移 Compose 页面。
3. 为每个业务动作注册 ActionDescriptor、schema、风险和权限。
4. 对照 `huitai_cloud` 当前接口做契约测试。
5. 每个业务域独立验收，不在框架阶段提前实现。
