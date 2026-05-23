# P1-4 Compose Desktop UI Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 BaBiQ 的 Kotlin Compose Desktop 可用桌面端，让用户通过图形界面完成聊天、工具审批、Provider/模型切换、连接状态感知和 Turn 成本反馈查看。

**Architecture:** 桌面端保持轻客户端定位，通过 Ktor Client WebSocket 连接后端 `/ws/agent`，以 JSON-RPC 2.0 请求和后端通知驱动 UI 状态。UI 采用已确认的 V2 高保真原型：项目/模式/分支/worktree/权限/模型上下文放在输入框附近，右侧运行详情默认收起，所有状态由协议模型和 reducer 收敛后再渲染到 Compose 组件。

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.11.0, Gradle 9.3.0, JDK 21, Ktor Client 3.5.0, kotlinx.serialization 1.11.0, kotlinx.coroutines 1.11.0, Kotlin Test.

---

## 0. 当前确认

- P1-3B 安全与可观测已完成，后端已经提供 `turnSummary`、结构化 turn 日志和内存级 metrics。
- P1-4 V2 高保真原型已经完成并经过用户审核，暂时没有问题。
- V1 原型已经删除，不再作为实现参考。
- P1-4 交互流程图已经完成，后续实现必须按 `prototype/flows/` 执行。
- 本计划只进入 P1-4 Compose Desktop UI，不实现 P2+ Actuator、Prometheus、Langfuse、KeyStore、Provider 编辑、文件 pinning 或多工作区管理。

## 1. 官方版本核对

实现前必须再次核对官方版本；截至 2026-05-23，本计划锁定以下最新稳定版，不使用 RC/Beta/EAP：

| 能力 | 版本 | 官方依据 | 实施要求 |
| --- | --- | --- | --- |
| Kotlin JVM / serialization plugin | `2.3.21` | Kotlin FAQ 与 KMP compatibility guide 均标记当前稳定版为 `2.3.21` | `desktop/build.gradle.kts` 保持 `kotlin("jvm") version "2.3.21"`，新增 `kotlin("plugin.serialization") version "2.3.21"` |
| Compose Multiplatform | `1.11.0` | JetBrains Kotlin Blog 发布 Compose Multiplatform 1.11.0 | `org.jetbrains.compose` 保持 `1.11.0`，不降级 |
| Gradle | `9.3.0` | 当前 wrapper 已锁定 `gradle-9.3.0-bin.zip`，且 Kotlin 2.3.21 兼容 Gradle 7.6.3-9.3.0 | 保持 wrapper 不变 |
| Ktor Client | `3.5.0` | Ktor releases 当前最新稳定版 `3.5.0`，Ktor WebSockets 文档页为 `Ktor 3.5.0 Help` | 新增 `ktor-client-core`、`ktor-client-cio`、`ktor-client-websockets` |
| kotlinx.serialization JSON | `1.11.0` | Kotlinx serialization GitHub release 最新 `1.11.0` | 新增 `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` |
| kotlinx.coroutines | `1.11.0` | Kotlinx coroutines GitHub README/release 最新 `1.11.0` | 新增 `kotlinx-coroutines-core`、`kotlinx-coroutines-swing`、`kotlinx-coroutines-test` |

版本策略：

- [ ] 实现开始前重新打开官方文档，确认上述版本仍是最新稳定版。
- [ ] 如果出现更新的稳定版，优先更新计划和依赖，再开始编码。
- [ ] 如果只有 RC/Beta/EAP 更新，不采用。
- [ ] 如果最新稳定版与当前 Kotlin/Compose/Gradle 组合发生依赖解析冲突，停止实现并在计划中记录冲突，不静默降级。

## 2. 必读材料

实现者必须先读这些文件和原型材料，不能只按记忆实现：

- `E:\BaBiQ\AGENTS.md`
- `E:\BaBiQ\docs\ARCHITECTURE.md`
- `E:\BaBiQ\docs\superpowers\plans\2026-05-21-p1-master.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-3b-security-observability\plan.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-3b-security-observability\codex-handoff.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\README.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\figma.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\README.md`

原型入口：

- Figma: <https://www.figma.com/design/frTp55zgrKf4NAWxn6LdI7>
- V2 首页输入框上下文条: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-01-home-context-bar.png`
- V2 聊天运行态: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-02-chat-runtime.png`
- V2 审批弹窗: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-03-approval-context-aware.png`
- V2 输入框附近模型切换: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-04-model-picker-near-composer.png`
- V2 设置页: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-05-settings-workspace-providers.png`

交互流程：

- `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\01-send-message-and-turn-summary.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\02-tool-approval.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\03-provider-model-switch.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\04-connection-and-reconnect.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\05-runtime-details-and-cost.md`
- `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\06-workspace-context-bar.md`

## 3. 当前桌面端基线

当前 `desktop/` 是最小骨架：

- `E:\BaBiQ\desktop\build.gradle.kts`
  - Kotlin `2.3.21`
  - Compose `1.11.0`
  - JDK toolchain `21`
  - 依赖只有 `compose.desktop.currentOs` 与 `compose.material3`
- `E:\BaBiQ\desktop\gradle\wrapper\gradle-wrapper.properties`
  - Gradle `9.3.0`
- `E:\BaBiQ\desktop\src\main\kotlin\com\wzx\babiq\desktop\Main.kt`
  - 只显示 `BaBiQ Desktop - P1-0 skeleton OK`

P1-4 需要把桌面端从 skeleton 推进到真实可用 UI，但仍保持 P1 范围。

## 4. 目标行为

P1-4 完成后应满足：

- 启动桌面端后，显示 V2 首页布局，而不是 skeleton 文案。
- 输入框可输入任务，按 `Enter` 或点击发送按钮触发消息发送。
- 首次发送前如果没有 thread，应调用 `thread/create`，再调用 `turn/start`。
- 收到 `turn/started`、`item/added`、`item/updated`、`item/completed`、`turn/completed`、`turn/failed`、`approval/request` 后，UI 状态即时更新。
- 收到 `turnSummary` 类型 item 后，渲染 tokens、成本、耗时、工具次数等摘要。
- 成本展示只能来自后端 `turnSummary`。首页/idle 状态不显示成本 chip，运行中不展示预估成本；`ComposerContextBar` 不承担成本展示职责。
- 工具审批弹窗展示工具名、命令/参数、风险上下文，并支持 Approve、Deny、Always、Edit。
- Provider/模型下拉靠近输入框，切换后调用 `model/providers/set-active`，仅从下一条消息开始生效。
- 设置页只读展示 Provider 信息，不做 API key 编辑。
- 连接断开时保留聊天历史和输入草稿，禁用发送和审批，显示重连状态；按 1s 到 10s 指数退避自动重连，并提供手动重试按钮；恢复连接后可继续新 turn。
- 如果断线发生在 turn 运行中，UI 标记为“状态未知”，重连后等待后端后续事件；P1 不做离线发送队列。
- 右侧运行详情默认收起，展开后展示工具轨迹、事件、耗时和成本明细。
- 所有新增解释性代码注释使用中文，且只在逻辑不显然处添加。
- 左侧 Sidebar 中 `新对话`、最近任务和设置入口可用；`搜索`、`插件`、`自动化` 在 P1-4 只作为禁用占位或隐藏，不得实现真实能力。
- 首页快速操作卡如果保留，只能作为禁用的 P2 placeholder；P1-4 不接入消息传送、电子邮件或网盘/文件连接器。

## 5. 协议边界

P1-4 只消费后端现有 JSON-RPC 协议：

- 请求：
  - `thread/create`
  - `turn/start`
  - `turn/interrupt`
  - `turn/cancel`
  - `approval/respond`
  - `model/providers/list`
  - `model/providers/set-active`
- 通知：
  - `turn/started`
  - `item/added`
  - `item/updated`
  - `item/completed`
  - `turn/completed`
  - `turn/failed`
  - `approval/request`

如果实现时发现 `model/providers/list` 或 `model/providers/set-active` 在后端仍是占位处理，桌面端只完成协议调用、错误展示和 P1 可见提示；不得在 P1-4 内擅自扩展后端 Provider 管理能力，除非用户确认计划变更。

## 6. 文件结构

所有路径以 `E:\BaBiQ` 为根。

### 6.1 修改文件

- `desktop\build.gradle.kts`
  - 增加 serialization plugin、Ktor、kotlinx.serialization、kotlinx.coroutines、测试依赖。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\Main.kt`
  - 缩减为窗口启动和 `BaBiQDesktopApp()` 入口。
- `AGENTS.md`
  - P1-4 实现完成后更新当前检查点、验收命令与下一阶段提示。
- `CLAUDE.md`
  - P1-4 实现完成后与 `AGENTS.md` 同步更新当前检查点、验收命令与下一阶段提示。
- `docs\superpowers\plans\p1-4-compose-desktop-ui\codex-handoff.md`
  - P1-4 实现完成后更新交接状态。

### 6.2 新增生产代码

- `desktop\src\main\kotlin\com\wzx\babiq\desktop\app\BaBiQDesktopApp.kt`
  - 应用根组合函数，负责组装 theme、state holder、shell。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\app\DesktopConfig.kt`
  - 后端地址、WebSocket 路径、重连间隔、P1 默认上下文。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\JsonRpcModels.kt`
  - JSON-RPC request/response/notification envelope。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\ThreadModels.kt`
  - Thread、Turn、Item、TurnSummary、ToolCall、FileChange 等协议模型。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\ApprovalModels.kt`
  - 审批请求、审批响应、审批动作。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\ProviderModels.kt`
  - Provider、Model、active provider/model、只读设置模型。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\ProtocolJson.kt`
  - `Json` 配置、polymorphic item 解析、自定义 serializer。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\client\AgentTransport.kt`
  - 传输抽象，便于 fake transport 测试。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\client\KtorAgentTransport.kt`
  - Ktor WebSocket 实现。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\client\AgentClient.kt`
  - typed client，封装 JSON-RPC method。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\AppState.kt`
  - 顶层 UI state。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\ChatReducer.kt`
  - 纯 reducer，处理协议事件到 UI 状态。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\ChatController.kt`
  - coroutine scope、连接生命周期、发送消息、审批响应。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\UiModels.kt`
  - UI 专用 view model，避免 Compose 直接依赖 wire shape。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\theme\BaBiQTheme.kt`
  - Material3 theme、颜色、间距、字体常量。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\shell\AppShell.kt`
  - 左侧导航、主内容、可折叠右侧运行详情。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\shell\Sidebar.kt`
  - 新对话、最近任务、设置入口；搜索、插件、自动化只做禁用 P2 占位或隐藏。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\ChatScreen.kt`
  - 首页/聊天运行态总入口。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\MessageList.kt`
  - 消息列表、streaming item、空状态。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\MessageBubble.kt`
  - 用户消息、助手消息、工具消息、文件变更消息。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\Composer.kt`
  - 输入框、发送按钮、Enter 发送。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\ComposerContextBar.kt`
  - 项目、模式、分支、worktree、权限、模型 chip；禁止显示成本 chip。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\ProviderSelector.kt`
  - 输入框附近模型切换下拉。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\approval\ApprovalDialog.kt`
  - 工具审批弹窗。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\runtime\RuntimeDetailsPanel.kt`
  - 右侧运行详情。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\runtime\TurnSummaryBar.kt`
  - Turn 成本与耗时摘要。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\settings\SettingsPanel.kt`
  - Provider 只读设置页。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\common\StatusBadge.kt`
  - 连接、权限、运行状态 badge。
- `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\common\EmptyState.kt`
  - 首页空状态。

### 6.3 新增测试代码

- `desktop\src\test\kotlin\com\wzx\babiq\desktop\protocol\ProtocolJsonTest.kt`
- `desktop\src\test\kotlin\com\wzx\babiq\desktop\protocol\ThreadItemJsonTest.kt`
- `desktop\src\test\kotlin\com\wzx\babiq\desktop\client\AgentClientTest.kt`
- `desktop\src\test\kotlin\com\wzx\babiq\desktop\state\ChatReducerTest.kt`
- `desktop\src\test\kotlin\com\wzx\babiq\desktop\state\ChatControllerTest.kt`
- `desktop\src\test\kotlin\com\wzx\babiq\desktop\ui\TurnSummaryFormattingTest.kt`

## 7. 实施计划

### Task 1: 锁定最新稳定依赖

**Files:**
- Modify: `desktop\build.gradle.kts`

- [ ] **Step 1: 重新核对官方版本**

打开官方文档：

```text
https://kotlinlang.org/docs/faq.html
https://blog.jetbrains.com/kotlin/2026/05/compose-multiplatform-1-11-0/
https://ktor.io/docs/releases.html
https://ktor.io/docs/client-websockets.html
https://github.com/Kotlin/kotlinx.serialization
https://github.com/Kotlin/kotlinx.coroutines
```

Expected: 最新稳定版仍为 Kotlin `2.3.21`、Compose `1.11.0`、Ktor `3.5.0`、kotlinx.serialization `1.11.0`、kotlinx.coroutines `1.11.0`。

- [ ] **Step 2: 修改 Gradle 插件和依赖**

在 `plugins` 增加：

```kotlin
kotlin("plugin.serialization") version "2.3.21"
```

在 `dependencies` 增加：

```kotlin
val ktorVersion = "3.5.0"
val kotlinxSerializationVersion = "1.11.0"
val kotlinxCoroutinesVersion = "1.11.0"

implementation("io.ktor:ktor-client-core:$ktorVersion")
implementation("io.ktor:ktor-client-cio:$ktorVersion")
implementation("io.ktor:ktor-client-websockets:$ktorVersion")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$kotlinxCoroutinesVersion")

testImplementation(kotlin("test"))
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
```

- [ ] **Step 3: 跑依赖解析**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat testClasses
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```powershell
git add desktop/build.gradle.kts
git commit -m "build(p1-4): 锁定桌面端最新稳定依赖"
```

### Task 2: 建立协议模型和 JSON 解析

**Files:**
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\JsonRpcModels.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\ThreadModels.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\ApprovalModels.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\ProviderModels.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\ProtocolJson.kt`
- Test: `desktop\src\test\kotlin\com\wzx\babiq\desktop\protocol\ProtocolJsonTest.kt`
- Test: `desktop\src\test\kotlin\com\wzx\babiq\desktop\protocol\ThreadItemJsonTest.kt`

- [ ] **Step 1: 写失败测试**

测试至少覆盖：

```kotlin
@Test
fun `item added turnSummary can be decoded`() {
    val json = """
        {
          "jsonrpc": "2.0",
          "method": "item/added",
          "params": {
            "threadId": "thread-1",
            "turnId": "turn-1",
            "item": {
              "id": "summary-1",
              "type": "turnSummary",
              "tokensIn": 100,
              "tokensOut": 50,
              "costUsd": 0.0123,
              "durationMs": 1234,
              "toolCount": 2
            }
          }
        }
    """.trimIndent()

    val event = protocolJson.decodeFromString<ServerNotification>(json)
    assertEquals("item/added", event.method)
    assertTrue(event.params.item is ThreadItem.TurnSummary)
}
```

还要覆盖：

- unknown item type 不崩溃，落入 `ThreadItem.Unknown`。
- `approval/request` 能解析工具名、命令、参数、approvalId。
- `model/providers/list` response 能解析 provider/model 列表。

- [ ] **Step 2: 验证测试失败**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ProtocolJsonTest" --tests "*ThreadItemJsonTest"
```

Expected: FAIL，原因是协议模型不存在。

- [ ] **Step 3: 实现最小模型**

关键结构：

```kotlin
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class ServerNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: NotificationParams,
)
```

`ThreadItem` 用 sealed model 表示：

```kotlin
sealed interface ThreadItem {
    val id: String

    data class UserMessage(...)
    data class AssistantMessage(...)
    data class ToolCall(...)
    data class FileChange(...)
    data class TurnSummary(...)
    data class Unknown(...)
}
```

使用 `JsonContentPolymorphicSerializer<ThreadItem>` 按 `type` 字段分派。

- [ ] **Step 4: 跑协议测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ProtocolJsonTest" --tests "*ThreadItemJsonTest"
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol
git commit -m "feat(p1-4): 建立桌面端协议模型"
```

### Task 3: 实现 Ktor WebSocket transport 和 typed client

**Files:**
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\app\DesktopConfig.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\client\AgentTransport.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\client\KtorAgentTransport.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\client\AgentClient.kt`
- Test: `desktop\src\test\kotlin\com\wzx\babiq\desktop\client\AgentClientTest.kt`

- [ ] **Step 1: 写 fake transport 测试**

测试至少覆盖：

```kotlin
@Test
fun `turn start sends json rpc request`() = runTest {
    val transport = FakeAgentTransport()
    val client = AgentClient(transport)

    client.startTurn(threadId = "thread-1", prompt = "分析项目结构")

    val sent = transport.sent.single()
    assertEquals("turn/start", sent.method)
    assertEquals("2.0", sent.jsonrpc)
}
```

还要覆盖：

- `thread/create` 请求。
- `approval/respond` 请求。
- `model/providers/list` 请求。
- `model/providers/set-active` 请求。
- response `error` 转为 UI 可展示错误。

- [ ] **Step 2: 验证测试失败**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*AgentClientTest"
```

Expected: FAIL。

- [ ] **Step 3: 实现 transport 抽象**

接口：

```kotlin
interface AgentTransport : AutoCloseable {
    val incoming: Flow<String>
    suspend fun connect()
    suspend fun send(text: String)
}
```

Ktor 实现要求：

- `HttpClient(CIO) { install(WebSockets) { pingIntervalMillis = 20_000 } }`
- 默认连接 `ws://127.0.0.1:8080/ws/agent`，端口以后可从 config 改。
- 只发送/接收 text frame；binary frame 记录为未知事件，不让 UI 崩溃。
- 关闭时取消协程并关闭 client。

- [ ] **Step 4: 实现 typed client**

`AgentClient` 负责：

- 生成递增 request id。
- encode request。
- decode response/notification。
- 将 notification 暴露为 `Flow<AgentEvent>`。
- request-response 等待必须有 timeout，避免 UI 永久卡住。

- [ ] **Step 5: 跑 client 测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*AgentClientTest"
```

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/app/DesktopConfig.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/client desktop/src/test/kotlin/com/wzx/babiq/desktop/client
git commit -m "feat(p1-4): 接入桌面端 WebSocket 客户端"
```

### Task 4: 建立 UI 状态和 reducer

**Files:**
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\AppState.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\UiModels.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\ChatReducer.kt`
- Test: `desktop\src\test\kotlin\com\wzx\babiq\desktop\state\ChatReducerTest.kt`

- [ ] **Step 1: 写 reducer 测试**

覆盖交互流中要求的状态：

- 空闲 `idle`。
- 发送中 `sending`。
- 运行中 `running`。
- 等待审批 `waitingApproval`。
- 完成 `completed`。
- 失败 `failed`。
- 断线 `disconnected`。

示例：

```kotlin
@Test
fun `approval request moves state to waiting approval`() {
    val state = AppState.empty().copy(turnState = TurnState.Running)
    val next = ChatReducer.reduce(state, AgentEvent.ApprovalRequested(sampleApproval))

    assertEquals(TurnState.WaitingApproval, next.turnState)
    assertEquals(sampleApproval.id, next.pendingApproval?.id)
}
```

- [ ] **Step 2: 验证测试失败**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ChatReducerTest"
```

Expected: FAIL。

- [ ] **Step 3: 实现纯 reducer**

要求：

- reducer 不直接调用网络。
- reducer 不引用 Compose API。
- 收到 `turnSummary` 后更新 `latestSummary` 并追加到消息列表。
- 收到 `turn/failed` 后保留已有消息并显示错误。
- 收到 unknown item/event 后进入运行详情，不阻断主聊天。

- [ ] **Step 4: 跑 reducer 测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ChatReducerTest"
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/state desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatReducerTest.kt
git commit -m "feat(p1-4): 建立桌面端状态归约"
```

### Task 5: 实现 ChatController 生命周期

**Files:**
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\ChatController.kt`
- Test: `desktop\src\test\kotlin\com\wzx\babiq\desktop\state\ChatControllerTest.kt`

- [ ] **Step 1: 写 controller 测试**

覆盖：

- `sendMessage` 在没有 thread 时先 `thread/create` 再 `turn/start`。
- 正在 running 时禁止重复发送。
- disconnected 时禁止发送并返回 UI 错误。
- `respondApproval` 调用 `approval/respond` 后关闭 pending approval。
- reconnect 后恢复 `connected` 状态，但不重放未发送消息。

- [ ] **Step 2: 验证测试失败**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ChatControllerTest"
```

Expected: FAIL。

- [ ] **Step 3: 实现 controller**

要求：

- 使用 `CoroutineScope(SupervisorJob() + Dispatchers.Swing)` 管 UI 生命周期。
- 对网络调用使用 suspend function 和 timeout。
- controller 暴露 `StateFlow<AppState>`。
- 错误统一写入 `AppState.bannerMessage` 或 `AppState.lastError`。

- [ ] **Step 4: 跑 controller 测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ChatControllerTest"
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt
git commit -m "feat(p1-4): 串联桌面端聊天生命周期"
```

### Task 6: 搭建 V2 应用外壳和主题

**Files:**
- Modify: `desktop\src\main\kotlin\com\wzx\babiq\desktop\Main.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\app\BaBiQDesktopApp.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\theme\BaBiQTheme.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\shell\AppShell.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\shell\Sidebar.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\common\StatusBadge.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\common\EmptyState.kt`

- [ ] **Step 1: 替换 skeleton**

`Main.kt` 仅保留：

```kotlin
fun main() = singleWindowApplication(
    title = "BaBiQ",
    state = rememberWindowState(width = 1180.dp, height = 780.dp),
) {
    BaBiQDesktopApp()
}
```

- [ ] **Step 2: 实现 V2 shell**

布局要求：

- 左侧固定 288px 导航区。
- Sidebar 可用入口只包括新对话、最近任务和设置；搜索、插件、自动化如果出现在视觉上，必须是禁用的 P2 placeholder，不能点击触发真实功能。
- 中间为聊天主区域。
- 右侧运行详情默认折叠，展开宽度 320px。
- 首页第一视觉是输入框和上下文条，不做营销页。
- 首页快速操作卡如果保留，只能禁用并标记为 P2 placeholder；P1-4 不实现连接消息传送、电子邮件、文件/网盘等外部连接器。
- 不使用大面积渐变和装饰性图形。
- 圆角控制在 8px 以内，除输入框等局部控件可按原型略大。

- [ ] **Step 3: 运行桌面端做静态视觉检查**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat run
```

Expected: 桌面窗口打开，首页结构接近 `v2-01-home-context-bar.png`，不再显示 skeleton 文案。

- [ ] **Step 4: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/Main.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/app/BaBiQDesktopApp.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/ui
git commit -m "feat(p1-4): 搭建桌面端 V2 外壳"
```

### Task 7: 实现聊天主界面和输入框上下文条

**Files:**
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\ChatScreen.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\MessageList.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\MessageBubble.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\Composer.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\ComposerContextBar.kt`

- [ ] **Step 1: 实现消息列表**

要求：

- 用户消息右侧对齐或以明显样式区分。
- 助手消息左侧对齐。
- 工具调用使用紧凑行展示，避免卡片套卡片。
- long text 自动换行，不允许溢出输入框或消息区域。

- [ ] **Step 2: 实现 Composer**

要求：

- `Enter` 发送。
- `Shift+Enter` 换行。
- running / disconnected / waitingApproval 时禁用发送。
- 发送按钮使用图标或短文本，不把说明文字塞进按钮。

- [ ] **Step 3: 实现 ComposerContextBar**

上下文 chip：

- `BaBiQ`
- `本地模式`
- 当前分支，默认从 config/state 来，P1 可先显示 `master`
- `worktree`
- 权限状态
- 当前模型
- 不显示成本、预估成本或“本轮约”信息

重要：不要恢复右侧独立“文件上下文”入口，用户已确认上下文应靠近输入框。
成本只能在收到后端 `turnSummary` 后由 `TurnSummaryBar` 和 `RuntimeDetailsPanel` 渲染。

- [ ] **Step 4: 运行桌面端检查**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat run
```

Expected: 首页与聊天运行态分别接近 `v2-01-home-context-bar.png` 和 `v2-02-chat-runtime.png`；如果截图里仍有上下文条成本 chip，以本计划为准，不实现该 chip。

- [ ] **Step 5: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat
git commit -m "feat(p1-4): 实现聊天界面和上下文条"
```

### Task 8: 实现 Provider/模型切换

**Files:**
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\chat\ProviderSelector.kt`
- Modify: `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\ChatController.kt`
- Modify: `desktop\src\test\kotlin\com\wzx\babiq\desktop\state\ChatControllerTest.kt`

- [ ] **Step 1: 补 Provider controller 测试**

覆盖：

- 启动后调用 `model/providers/list`。
- 切换模型调用 `model/providers/set-active`。
- 切换仅影响下一次 `turn/start` 参数或后端 active provider。
- 后端返回错误时，下拉恢复原 active 模型并展示错误。

- [ ] **Step 2: 实现 ProviderSelector**

要求：

- 入口靠近输入框右下区域，按 V2 原型。
- 展示 provider 名、model 名、能力简短标签。
- P1 不展示 API Key 编辑入口。
- 当前 turn running 时可允许选择但提示“下一轮生效”；如果实现复杂，P1 可在 running 时禁用切换。

- [ ] **Step 3: 跑测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ChatControllerTest"
```

Expected: PASS。

- [ ] **Step 4: 运行视觉检查**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat run
```

Expected: 模型切换接近 `v2-04-model-picker-near-composer.png`。

- [ ] **Step 5: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ProviderSelector.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/state desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt
git commit -m "feat(p1-4): 实现桌面端模型切换"
```

### Task 9: 实现工具审批弹窗

**Files:**
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\approval\ApprovalDialog.kt`
- Modify: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\shell\AppShell.kt`
- Modify: `desktop\src\test\kotlin\com\wzx\babiq\desktop\state\ChatReducerTest.kt`
- Modify: `desktop\src\test\kotlin\com\wzx\babiq\desktop\state\ChatControllerTest.kt`

- [ ] **Step 1: 补审批状态测试**

覆盖：

- `approval/request` 打开弹窗。
- Approve/Deny/Always/Edit 生成正确 `approval/respond` payload。
- disconnected 时禁用审批按钮。
- response 成功后关闭弹窗。
- response 失败后保留弹窗并展示错误。

- [ ] **Step 2: 实现弹窗**

要求：

- 展示工具名、命令或参数、工作区、权限模式。
- 命令区域使用等宽字体。
- `Approve`、`Deny`、`Always`、`Edit` 清晰分组。
- `Edit` P1 可以只允许编辑命令文本再提交；如果后端不支持修改命令，按钮应禁用并说明“后端暂未开放编辑执行”。

- [ ] **Step 3: 跑测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ChatReducerTest" --tests "*ChatControllerTest"
```

Expected: PASS。

- [ ] **Step 4: 运行视觉检查**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat run
```

Expected: 审批弹窗接近 `v2-03-approval-context-aware.png`。

- [ ] **Step 5: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/approval desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/AppShell.kt desktop/src/test/kotlin/com/wzx/babiq/desktop/state
git commit -m "feat(p1-4): 实现工具审批弹窗"
```

### Task 10: 实现 TurnSummary 和运行详情

**Files:**
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\runtime\TurnSummaryBar.kt`
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\runtime\RuntimeDetailsPanel.kt`
- Test: `desktop\src\test\kotlin\com\wzx\babiq\desktop\ui\TurnSummaryFormattingTest.kt`

- [ ] **Step 1: 写格式化测试**

覆盖：

- `tokensIn`、`tokensOut` 格式化。
- `costUsd` 至少保留 4 位小数，小金额不显示成 `0`。
- `durationMs` 转秒。
- 空 summary 不展示错误。

- [ ] **Step 2: 实现 TurnSummaryBar**

要求：

- completed/failed 后显示最新 summary。
- running 时显示当前耗时或 skeleton 状态。
- 不自己估算 tokens；只展示后端返回数据。
- `TurnSummaryBar` 是聊天主区唯一的成本摘要条；`ComposerContextBar` 不重复展示成本。
- 首页/idle 状态没有 `turnSummary` 时隐藏成本摘要，不显示 `0` 或预估值。

- [ ] **Step 3: 实现 RuntimeDetailsPanel**

要求：

- 默认收起。
- 展开后展示工具轨迹、审批结果、事件时间线、成本详情。
- unknown event/item 放到详情区，避免主聊天异常。

- [ ] **Step 4: 跑测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*TurnSummaryFormattingTest"
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/TurnSummaryFormattingTest.kt
git commit -m "feat(p1-4): 展示运行详情和成本摘要"
```

### Task 11: 实现连接状态与重连提示

**Files:**
- Modify: `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\ChatController.kt`
- Modify: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\common\StatusBadge.kt`
- Modify: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\shell\AppShell.kt`
- Modify: `desktop\src\test\kotlin\com\wzx\babiq\desktop\state\ChatControllerTest.kt`

- [ ] **Step 1: 补连接状态测试**

覆盖：

- 启动连接成功显示 connected。
- socket 关闭显示 disconnected。
- 断线后按固定间隔重连。
- disconnected 时发送按钮和审批按钮不可用。
- P1 不做离线消息队列。

- [ ] **Step 2: 实现重连状态**

要求：

- 重连间隔初始 1s，最高 10s。
- 用户可点击“重试”立即重连。
- 断线时保留当前聊天记录。

- [ ] **Step 3: 跑测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ChatControllerTest"
```

Expected: PASS。

- [ ] **Step 4: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/common/StatusBadge.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/AppShell.kt desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt
git commit -m "feat(p1-4): 增加桌面端连接状态"
```

### Task 12: 实现只读设置页

**Files:**
- Create: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\settings\SettingsPanel.kt`
- Modify: `desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\shell\Sidebar.kt`
- Modify: `desktop\src\main\kotlin\com\wzx\babiq\desktop\state\AppState.kt`

- [ ] **Step 1: 实现设置导航**

要求：

- 左侧 settings 入口打开设置页。
- 返回聊天不丢失当前 thread 状态。

- [ ] **Step 2: 实现只读 Provider 信息**

展示：

- provider name。
- active model。
- base url 或后端提供的 provider hint。
- enabled/disabled 状态。
- 最近错误。

不做：

- API key 输入。
- Provider 新增/删除。
- 持久化配置编辑。

- [ ] **Step 3: 运行视觉检查**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat run
```

Expected: 设置页接近 `v2-05-settings-workspace-providers.png`。

- [ ] **Step 4: 提交**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/Sidebar.kt desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt
git commit -m "feat(p1-4): 增加 Provider 只读设置页"
```

### Task 13: 端到端联调和验收

**Files:**
- Modify as needed: desktop code only unless用户确认后端协议缺口。

- [ ] **Step 1: 后端全量验证**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd clean verify
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 桌面端单元测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 启动后端**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd spring-boot:run
```

Expected: 后端监听 WebSocket `/ws/agent`。

- [ ] **Step 4: 启动桌面端**

另开终端：

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat run
```

Expected: 桌面端启动并连接后端。

- [ ] **Step 5: 真实业务验收场景**

在桌面端输入：

```text
分析 E:\BaBiQ 项目结构并写一个总结
```

Expected:

- UI 新建或复用 thread。
- 消息发送后进入 running。
- 工具调用需要审批时弹窗出现。
- 审批后工具轨迹进入运行详情。
- 最终助手消息展示总结。
- `turnSummary` 显示 tokens、成本、耗时、工具次数。
- 后端结构化日志仍正常输出。

- [ ] **Step 6: 视觉验收**

人工对照五张 V2 截图：

- 首页上下文条。
- 聊天运行态。
- 审批弹窗。
- 模型切换。
- 设置页。

Expected: 布局、入口位置和信息层级与 V2 原型一致；不得出现文字溢出、控件重叠、卡片套卡片。

- [ ] **Step 7: 提交联调修正**

```powershell
git add desktop
git commit -m "fix(p1-4): 完成桌面端联调修正"
```

如果没有修正，不需要空提交。

### Task 14: 文档同步和阶段收尾

**Files:**
- Modify: `CLAUDE.md`
- Modify: `AGENTS.md`
- Modify: `docs\superpowers\plans\p1-4-compose-desktop-ui\codex-handoff.md`
- Modify: `docs\superpowers\plans\2026-05-21-p1-master.md`
- Modify: `docs\ARCHITECTURE.md` if implementation changed desktop architecture details

- [ ] **Step 1: 更新 P1-4 handoff**

写清：

- 已实现的 UI 能力。
- 已通过的后端/桌面端验证命令。
- 真实业务验收结果。
- 已知限制。
- 下一步阶段。

- [ ] **Step 2: 更新 AGENTS.md 和 CLAUDE.md**

必须更新：

- 当前检查点改为 P1-4 Compose Desktop UI 已完成或待验收。
- 追加桌面端验收命令：

```powershell
cd desktop
.\gradlew.bat test
.\gradlew.bat run
```

- 保留“完成一个计划后主动更新 AGENTS.md”的规则。
- `CLAUDE.md` 必须与 `AGENTS.md` 保持同等阶段状态，不能停留在“P1-4 计划尚未完成”。

- [ ] **Step 3: 更新 master plan 状态**

在 `docs\superpowers\plans\2026-05-21-p1-master.md` 中把 P1-4 状态改成真实状态，只能基于实际测试结果。

- [ ] **Step 4: 更新架构文档**

如果最终 UI 采用 V2 输入框上下文条，应同步 `docs\ARCHITECTURE.md` 中与“Provider 顶部栏”不一致的描述，避免后续 Codex 回到旧设计。

- [ ] **Step 5: 最终验证**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd clean verify
cd E:\BaBiQ\desktop
.\gradlew.bat test
```

Expected: 两边均成功。

- [ ] **Step 6: 收尾提交**

```powershell
git add AGENTS.md CLAUDE.md docs/ARCHITECTURE.md docs/superpowers/plans/2026-05-21-p1-master.md docs/superpowers/plans/p1-4-compose-desktop-ui/codex-handoff.md
git commit -m "docs(p1-4): 同步桌面端阶段状态"
```

## 8. 验收清单

P1-4 只有同时满足以下条件才可标记完成：

- [ ] `E:\BaBiQ\desktop\src\main\kotlin\com\wzx\babiq\desktop\Main.kt` 不再显示 skeleton。
- [ ] `cd E:\BaBiQ\desktop; .\gradlew.bat test` 成功。
- [ ] `cd E:\BaBiQ\backend; .\mvnw.cmd clean verify` 成功。
- [ ] `cd E:\BaBiQ\desktop; .\gradlew.bat run` 能启动真实 UI。
- [ ] 真实业务场景“分析 E:\BaBiQ 项目结构并写一个总结”能从 UI 端完成。
- [ ] 审批弹窗能展示并响应工具审批。
- [ ] Provider/模型切换入口位于输入框附近，并从下一轮请求生效。
- [ ] `turnSummary` 能展示成本、token、耗时、工具次数。
- [ ] 断线状态有可见提示，发送和审批不会静默失败。
- [ ] 设置页只读展示 provider 信息，不做 P2+ 配置编辑。
- [ ] UI 与五张 V2 截图保持一致，并按本计划修正成本 chip、P2 占位等语义；不恢复 V1。
- [ ] `AGENTS.md`、`CLAUDE.md`、`codex-handoff.md` 和 master plan 已按真实状态更新。
- [ ] 已用中文 conventional commit 主动提交，且没有 push。

## 9. 风险和处理

- **后端 provider 方法仍是占位：** 桌面端必须展示错误或只读状态，不擅自补后端能力。
- **Ktor 3.5.0 与当前构建冲突：** 停止实现，记录官方版本与 Gradle 错误，等待用户确认；不静默降级。
- **Compose Desktop UI 自动化测试成本高：** P1 以 reducer/controller 单测覆盖行为，以人工视觉验收覆盖高保真一致性。
- **turnSummary 字段与实际后端 JSON 不一致：** 先补协议测试复现，再按后端真实 wire shape 修正 serializer；不要改后端字段名，除非用户确认协议变更。
- **长文本或命令溢出：** 通过 fixed width、wrap、scroll container 和等宽命令区域处理，验收时重点检查。

## 10. 执行方式

正式实现时必须使用：

- `superpowers:test-driven-development`
- `superpowers:executing-plans`
- `superpowers:verification-before-completion`

如果当前环境支持子代理，可使用：

- `superpowers:subagent-driven-development`

实现期间每个 Task 结束后按计划提交。用户已要求“主动 commit，中文 commit，不 push”，所以每个提交使用中文 conventional commit，并且禁止主动 push。
