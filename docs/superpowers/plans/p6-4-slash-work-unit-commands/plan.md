# P6-4 Slash 命令与命名工作容器 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在对话输入框提供 `/子代理`、`/编排`、`/团队` 显式入口，并把编排与团队升级为可复用的命名工作容器，支持同一容器持续完成多个目标、并发存在、完成后移除并从 UI 消失。

**Architecture:** 桌面端只负责解析 slash 命令并把结构化 `executionIntent` 随 `turn/start` 发送；后端保留用户原始任务文本不污染聊天历史。**容器生命周期走服务端确定性逻辑**：slash intent 是确定性结构化输入（mode+name+goal 已由用户选定），`submit()` 路径直接 create-or-reuse `WorkUnit` 容器并 append 目标，拿到 `goalId` 后**在 build toolContext 时注入**（与现有 cwd/sandbox 注入同款）；主 Agent **只负责执行**——读取本轮上下文里注入的高优先级运行意图，调用现有 `explorer` / `orchestrate_flow` / `coordinate_team`，flow/team 工具从 `ToolContext` 读 `goalId` 回写目标状态。`work_unit_manage` 工具**只用于自然语言管理**（running 时补充 vs 排队、追加新目标、移除容器），不是 slash 路径建容器的必经跳。编排和团队不再只是一次工具输出，而是 `WorkUnit` 容器；每个容器有名称、类型、目标队列和运行状态，目标完成后容器可继续追加新目标。

**Tech Stack:** Kotlin Compose Desktop、Ktor WebSocket JSON-RPC、Java 21、Spring Boot、SQLite + MyBatis-Plus + Flyway、Spring AI Alibaba ReactAgent / FlowAgent / StateGraph、BaBiQ 现有审批 / 沙箱 / 运行记录 / 协议 item 链路。

---

## 0. 用户确认后的心智模型

- `/子代理 <任务>`：一次性只读委派，不命名，不维护目标队列；结束后只保留本次委派摘要，可手动移除右侧卡片。
- `/编排 <名称>：<目标>`：创建或复用一个命名编排容器，把 `<目标>` 作为新的目标追加进去并执行。
- `/团队 <名称>：<目标>`：创建或复用一个命名团队容器，把 `<目标>` 作为新的目标追加进去并执行。
- 编排 / 团队是可复用容器，目标是容器里的任务批次。一个容器可以完成目标 1、目标 2、目标 3，不需要用户频繁新建。
- 同一个 thread 内，运行中的编排和团队名称必须唯一；已完成 / 空闲容器可以继续追加目标；被移除的容器从页面消失，但后端仍保留审计事实。
- 多个编排和多个团队可以并发存在，只要运行中的名称不冲突。
- 用户可以通过和主 Agent 对话修改目标、追加目标或移除已完成容器，例如“让前端验收组继续检查技能页”“移除登录页优化流程”。

## 1. 范围

**本阶段做：**

- 桌面输入框 slash command 解析和命令面板。
- `turn/start` 增加结构化 `executionIntent`，不把 `/团队` 等控制语法写入用户消息正文。
- 后端新增 `WorkUnit` 领域模型，统一承载 `TEAM` 与 `FLOW` 容器。
- 编排 / 团队容器支持名称、目标队列、当前目标、运行状态、移除状态。
- 主 Agent 通过系统上下文识别显式 intent，并调用现有 `orchestrate_flow` / `coordinate_team`。
- 现有 `bq_orchestrations`、`bq_teams` 与新增 work unit 表建立关联，不重写 P6-2 / P6-3 执行引擎。
- 右侧运行详情支持多个团队 / 编排容器列表、目标队列、移除已完成容器。
- 支持自然语言管理：主 Agent 可调用管理工具追加目标、更新目标、移除容器。

**本阶段不做：**

- 不实现自由连边图编辑器。
- 不实现运行中逐工具审批和并发中断；继续沿用 P6-2 / P6-3 当前 approve-once 语义。
- 不让 UI 直接调用 `orchestrate_flow` / `coordinate_team` 绕过主 Agent。
- 不做跨 thread 的全局团队复用。
- 不升级 Spring AI / Spring AI Alibaba。
- 不把被移除的容器从 SQLite 审计事实中物理删除。

## 2. 关键语义

### 2.1 Slash 命令语法

支持中文和英文别名：

```text
/子代理 检查当前目录结构
/subagent 检查当前目录结构
/explorer 检查当前目录结构

/编排 登录页优化：按 Figma 原型核对登录页 UI，修复不一致处
/flow 登录页优化：按 Figma 原型核对登录页 UI，修复不一致处
/orchestrate 登录页优化：按 Figma 原型核对登录页 UI，修复不一致处

/团队 前端验收组：分别检查 UI、后端接口和测试覆盖
/team 前端验收组：分别检查 UI、后端接口和测试覆盖
```

解析规则：

- `/子代理` 后面的全部文本是一次性任务。
- `/编排` 和 `/团队` 必须带名称和目标。
- 名称与目标用中文冒号 `：` 或英文冒号 `:` 分隔。
- 名称 trim 后不能为空，目标 trim 后不能为空。
- 未识别命令按普通消息发送，避免误伤用户输入。
- 输入 `/` 时 UI 展示命令面板；用户选择命令后输入框展示模式 chip。

### 2.2 WorkUnit 容器

`WorkUnit` 是命名执行容器，不是一次 turn。

```text
workUnitId: wu_...
threadId: thr_...
type: TEAM | FLOW
name: 前端验收组
status: idle | queued | running | completed | failed | removed
currentGoalId: goal_...
removed: false
```

`WorkUnitGoal` 是容器里的目标批次。

```text
goalId: goal_...
workUnitId: wu_...
goalText: 检查技能页是否符合 Figma 原型
status: pending | running | completed | failed | cancelled
runRefType: team | orchestration
runRefId: team_... 或 orch_...
```

用户继续使用同名容器时（**slash 路径由服务端 `WorkUnitService` 确定性处理，不依赖模型先调工具**）：

- 容器不存在：服务端创建容器，append 目标，得 `goalId`。
- 容器存在且不是 running：服务端复用容器，append 目标，得 `goalId`。
- 容器 running：服务端默认把目标 append 为 `pending`（排队）。**仅当用户用自然语言要求**“补充当前目标 / 排到下一个”时，主 Agent 才调用 `work_unit_manage` 调整——这是需要语言判断、无法纯结构化决定的少数场景。

> **确定性边界**：create / reuse / append-goal 全部在 `submit()` 路径服务端完成（slash intent 已含 mode+name+goal，无需模型判断），`goalId` 随即注入本轮 `ToolContext`；模型只负责调用 `coordinate_team` / `orchestrate_flow` 执行。`work_unit_manage` 工具保留给自然语言管理（补充 vs 排队、追加、移除）。这样去掉“模型必须先调 work_unit_manage 再串 goalId”的脆弱链。

### 2.3 名称唯一

- 同一 thread 内，`removed=0` 且 `status in ('queued','running')` 的 work unit 名称必须唯一。
- 建议唯一键语义：`thread_id + type + normalized_name + active_status`，实现时可用 service 校验，不强依赖复杂 SQLite partial index。
- 如果 `/团队 前端验收组：...` 时已有运行中的同名团队，返回明确错误或追加为该团队的 queued goal。
- 团队和编排可以允许同名，也可以全局唯一；本阶段推荐 **同一 thread 内运行中的团队/编排名称全局唯一**，减少用户说“前端验收组”时的歧义。

### 2.4 移除

- 已完成 / 空闲 / 失败的容器可以移除。
- 运行中的容器不能直接移除；必须先取消或等待完成。
- 移除后该容器从右侧团队/编排页面消失。
- 移除只设置 `removed=1` 和 `removed_at`，不删除运行记录、工具调用和目标审计。
- 被移除后再次使用同名 `/团队` 或 `/编排`，创建新的容器。

## 3. 文件结构

### 后端新增

- Create: `backend/src/main/java/com/wzx/babiq/server/agent/intent/ExecutionIntent.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/agent/intent/ExecutionIntentMode.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnit.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitGoal.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitIntentInstructionBuilder.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/tool/impl/WorkUnitManageTool.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/WorkUnitListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/WorkUnitRemoveHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/WorkUnitEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/WorkUnitGoalEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/WorkUnitMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/WorkUnitGoalMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteWorkUnitRepository.java`
- Create: `backend/src/main/resources/db/migration/V16__work_unit_slash_commands.sql`

### 后端修改

- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/TurnExecutor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/ContextWindowRuntime.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/ContextAssembler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/tool/impl/FlowOrchestrationTool.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/tool/impl/TeamCoordinationTool.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/items/ThreadItem.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/security/SystemPromptSecurityRule.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/capability/CapabilityAliasDictionary.java`

### 桌面端新增

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/slash/SlashCommand.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/slash/SlashCommandParser.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/SlashCommandMenu.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ExecutionIntentModels.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/WorkUnitModels.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/WorkUnitSection.kt`

### 桌面端修改

- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/Composer.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentGateway.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RunDetailsPanel.kt`

### 测试新增 / 修改

- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/slash/SlashCommandParserTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/SlashCommandMenuTest.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/runtime/WorkUnitSectionTest.kt`
- Create: `backend/src/test/java/com/wzx/babiq/server/agent/intent/ExecutionIntentTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/workunit/WorkUnitServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/workunit/WorkUnitIntentInstructionBuilderTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/tool/impl/WorkUnitManageToolTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/WorkUnitHandlersTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/context/ContextAssemblerTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/tool/impl/FlowOrchestrationToolTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/tool/impl/TeamCoordinationToolTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`

---

## Chunk 1: 桌面 slash command 解析与协议 intent

### Task 1: SlashCommandParser

**Files:**

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/slash/SlashCommand.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/slash/SlashCommandParser.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/slash/SlashCommandParserTest.kt`

- [ ] **Step 1: 写失败测试**

测试必须覆盖：

```kotlin
@Test
fun `parse subagent command as one shot task`() {
    val result = SlashCommandParser.parse("/子代理 检查当前目录")
    assertEquals(SlashCommandMode.SubAgent, result.mode)
    assertNull(result.name)
    assertEquals("检查当前目录", result.task)
}

@Test
fun `parse team command with name and goal`() {
    val result = SlashCommandParser.parse("/团队 前端验收组：检查技能页")
    assertEquals(SlashCommandMode.Team, result.mode)
    assertEquals("前端验收组", result.name)
    assertEquals("检查技能页", result.task)
}

@Test
fun `parse flow command with ascii alias`() {
    val result = SlashCommandParser.parse("/flow 登录页优化: 修复按钮状态")
    assertEquals(SlashCommandMode.Flow, result.mode)
    assertEquals("登录页优化", result.name)
    assertEquals("修复按钮状态", result.task)
}

@Test
fun `unknown slash command remains normal text`() {
    assertNull(SlashCommandParser.parse("/不知道 做点什么"))
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*SlashCommandParserTest"
```

Expected: FAIL，类或方法不存在。

- [ ] **Step 3: 实现解析器**

实现要求：

- `SlashCommandMode.SubAgent | Flow | Team`
- `SlashCommandParseResult(mode, name, task, rawCommand, rawText)`
- `/子代理`、`/subagent`、`/explorer`
- `/编排`、`/flow`、`/orchestrate`
- `/团队`、`/team`
- Flow / Team 必须解析 `name` 和 `task`；缺少分隔符或字段为空时返回带 validation error 的结果，供 UI 阻止发送。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*SlashCommandParserTest"
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/slash desktop/src/test/kotlin/com/wzx/babiq/desktop/slash
git commit -m "feat(p6-4): 增加 slash 命令解析器"
```

### Task 2: Composer 命令面板和模式 chip

**Files:**

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/SlashCommandMenu.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/Composer.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/SlashCommandMenuTest.kt`

- [ ] **Step 1: 写失败测试**

测试 UI 模型函数，不做脆弱截图测试：

- 输入 `/` 时返回 3 个命令候选。
- 选择 `/团队` 后 composer 显示 `团队模式` chip。
- Flow / Team 缺名称或目标时 send disabled，并给出简短错误。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*SlashCommandMenuTest"
```

Expected: FAIL。

- [ ] **Step 3: 实现 UI**

UI 要求：

- `OutlinedTextField` 下方展示命令面板，使用现有主题色，不做大块说明文案。
- 命令行：
  - `子代理` / `只读探索`
  - `编排` / `复用流程容器完成目标`
  - `团队` / `复用团队容器完成目标`
- 选择命令后在上下文 chip 行显示 `子代理模式`、`编排：<名称>` 或 `团队：<名称>`。
- 发送时传递 parse result；普通文本保持原行为。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*SlashCommandMenuTest"
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat
git commit -m "feat(p6-4): 增加输入框 slash 命令面板"
```

### Task 3: turn/start 协议携带 executionIntent

**Files:**

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ExecutionIntentModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentGateway.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

- [ ] **Step 1: 写失败测试**

`AgentClientTest` 断言 `/团队 前端验收组：检查技能页` 发出的 JSON：

```json
{
  "method": "turn/start",
  "params": {
    "input": { "text": "检查技能页" },
    "executionIntent": {
      "mode": "TEAM",
      "source": "SLASH_COMMAND",
      "name": "前端验收组",
      "task": "检查技能页",
      "rawCommand": "/团队"
    }
  }
}
```

`ChatControllerTest` 断言本地 optimistic user message 只展示 `检查技能页`，不展示 `/团队 前端验收组：`。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

Expected: FAIL。

- [ ] **Step 3: 实现协议模型**

新增：

```kotlin
@Serializable
data class ExecutionIntentParams(
    val mode: String,
    val source: String = "SLASH_COMMAND",
    val name: String? = null,
    val task: String,
    val rawCommand: String,
)
```

`AgentGateway.startTurn(...)` 增加可选 intent 参数。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol desktop/src/main/kotlin/com/wzx/babiq/desktop/client desktop/src/main/kotlin/com/wzx/babiq/desktop/state desktop/src/test/kotlin/com/wzx/babiq/desktop
git commit -m "feat(p6-4): turn start 携带显式执行意图"
```

---

## Chunk 2: 后端 intent 接收和上下文注入

### Task 4: 后端 ExecutionIntent 模型

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/agent/intent/ExecutionIntent.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/agent/intent/ExecutionIntentMode.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/agent/intent/ExecutionIntentTest.java`

- [ ] **Step 1: 写失败测试**

覆盖：

- `TEAM` / `FLOW` 必须有 name 与 task。
- `SUB_AGENT` 不需要 name。
- mode 非法时报 `INVALID_PARAMS`。
- 归一化名称会去除首尾空白和连续空格。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=ExecutionIntentTest" test
```

Expected: FAIL。

- [ ] **Step 3: 实现模型**

Java record 示例：

```java
public record ExecutionIntent(
        ExecutionIntentMode mode,
        String source,
        String name,
        String task,
        String rawCommand
) {
    public static ExecutionIntent none() { ... }
    public ExecutionIntent normalized() { ... }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=ExecutionIntentTest" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/agent/intent backend/src/test/java/com/wzx/babiq/server/agent/intent
git commit -m "feat(p6-4): 增加后端执行意图模型"
```

### Task 5: TurnStartHandler 解析 intent

**Files:**

- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/TurnExecutor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`

- [ ] **Step 1: 写失败测试**

测试：

- `executionIntent` 可选，缺失时普通 turn 不变。
- `TEAM` intent 入参会传给 `TurnExecutor.submit(...)`。
- 用户正文落库仍是 `input.text`，不是 slash 原文。
- `TEAM` 缺 name 返回 `INVALID_PARAMS`。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=TurnStartHandlerTest" test
```

Expected: FAIL。

- [ ] **Step 3: 修改 submit 链路**

把 `ExecutionIntent` 作为可选参数传入：

- `TurnExecutor.submit(turn, userText, providerId, cwd, emitter, runPolicy, executionIntent)`
- `AgentLoop.invoke(turn, userText, providerId, cwd, emitter, runPolicy, executionIntent)`

**TEAM / FLOW intent 在此路径服务端确定性建容器**：调用 `WorkUnitService.createOrReuseAndAppendGoal(threadId, type, name, goalText)` 拿到 `goalId`，把 `goalId` 一并向下传给 `AgentLoop`，供其在 build toolContext 时注入（见 Task 6 / Task 10）。**不要**让模型先调 `work_unit_manage` 才有 goalId。

不要把 `rawCommand` 写入 `bq_items` 的 user message。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=TurnStartHandlerTest" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java backend/src/main/java/com/wzx/babiq/server/agent backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java
git commit -m "feat(p6-4): turn start 接收显式执行意图"
```

### Task 6: 当前窗口注入 intent 指令

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitIntentInstructionBuilder.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/ContextWindowRuntime.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/ContextAssembler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/workunit/WorkUnitIntentInstructionBuilderTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/context/ContextAssemblerTest.java`

- [ ] **Step 1: 写失败测试**

断言（**注入进现有 `current_turn` 权威层**，不新造弱层——`ContextAssembler` 的 `current_turn` 已被系统提示赋予最高优先级；若另起新层须确保同等权威）：

- `TEAM` intent 注入 `current_turn` 指令：容器与目标**已在服务端创建**（goalId 已在 ToolContext），要求主 Agent **直接调用 `coordinate_team` 执行本轮目标**。
- `FLOW` intent 注入 `current_turn` 指令：容器与目标已在服务端创建，要求主 Agent **直接调用 `orchestrate_flow` 执行本轮目标**。
- `SUB_AGENT` intent 注入只读 explorer 委派指令。
- 注入内容不出现在 recent_history，不污染下一轮历史。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitIntentInstructionBuilderTest,ContextAssemblerTest" test
```

Expected: FAIL。

- [ ] **Step 3: 实现指令构建器**

示例语义：

```text
用户通过 slash command 显式选择 TEAM 模式。
目标容器名称：前端验收组。
本轮目标：检查技能页。
该团队容器与本轮目标已由系统创建/复用并入队（goalId 已在工具上下文中）。
你应直接调用 coordinate_team 执行本轮目标，无需再创建容器。
只有当用户用自然语言要求“补充当前目标 / 排到下一个 / 移除某容器”时，才调用 work_unit_manage 调整。
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitIntentInstructionBuilderTest,ContextAssemblerTest" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/workunit backend/src/main/java/com/wzx/babiq/server/context backend/src/test/java/com/wzx/babiq/server/workunit backend/src/test/java/com/wzx/babiq/server/context
git commit -m "feat(p6-4): 在上下文中注入显式模式指令"
```

---

## Chunk 3: WorkUnit 持久化和管理工具

### Task 7: WorkUnit SQLite 事实源

**Files:**

- Create: `backend/src/main/resources/db/migration/V16__work_unit_slash_commands.sql`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/WorkUnitEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/WorkUnitGoalEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/WorkUnitMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/WorkUnitGoalMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnit.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitGoal.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteWorkUnitRepository.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`

- [ ] **Step 1: 写 migration 失败测试**

新增表：

```sql
bq_work_units
bq_work_unit_goals
```

字段建议：

```text
bq_work_units:
work_unit_id, thread_id, type, name, normalized_name, status, current_goal_id,
removed, removed_at, created_at, updated_at

bq_work_unit_goals:
goal_id, work_unit_id, thread_id, goal_text, status, run_ref_type,
run_ref_id, created_at, started_at, completed_at, error_message
```

所有表和字段必须有 SQL 中文注释和 `bq_schema_comments`。

- [ ] **Step 2: 运行 schema 测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest" test
```

Expected: FAIL 或新表不存在。

- [ ] **Step 3: 实现 migration / entity / repository**

Repository 方法至少包含：

```java
Optional<WorkUnit> findActiveByName(String threadId, WorkUnitType type, String normalizedName);
WorkUnit create(String threadId, WorkUnitType type, String name);
WorkUnitGoal appendGoal(String workUnitId, String threadId, String goalText);
void markGoalRunning(String goalId, String runRefType, String runRefId);
void markGoalCompleted(String goalId, String summary);
void markRemoved(String workUnitId);
List<WorkUnit> listVisible(String threadId);
```

- [ ] **Step 4: 运行 schema 测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/resources/db/migration/V16__work_unit_slash_commands.sql backend/src/main/java/com/wzx/babiq/server/persistence backend/src/main/java/com/wzx/babiq/server/workunit backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java
git commit -m "feat(p6-4): 建立命名工作容器事实源"
```

### Task 8: WorkUnitService 复用和目标队列

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitService.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/workunit/WorkUnitServiceTest.java`

- [ ] **Step 1: 写失败测试**

覆盖：

- 不存在同名容器时创建。
- 已完成容器追加新目标并变为 queued / running。
- 运行中同名容器追加目标为 pending 或返回冲突，由 service 明确决定。
- removed 容器不复用，同名创建新容器。
- remove running 容器失败。
- remove completed 容器成功并从 `listVisible` 消失。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitServiceTest" test
```

Expected: FAIL。

- [ ] **Step 3: 实现服务**

推荐规则：

- `createOrReuseAndAppendGoal(threadId, type, name, goalText)`
- 如果同名容器 running，追加目标为 `pending` 并返回 `queued=true`。
- 如果同名容器 idle / completed / failed，追加目标并设置容器状态为 `queued`。
- `remove(workUnitId)` 只允许 `idle`、`completed`、`failed`。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitServiceTest" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/workunit backend/src/test/java/com/wzx/babiq/server/workunit
git commit -m "feat(p6-4): 支持工作容器复用和目标队列"
```

### Task 9: work_unit_manage 工具

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/tool/impl/WorkUnitManageTool.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/security/SystemPromptSecurityRule.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/capability/CapabilityAliasDictionary.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/tool/impl/WorkUnitManageToolTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/security/SystemPromptSecurityRuleTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/capability/CapabilityAliasDictionaryTest.java`

- [ ] **Step 1: 写失败测试**

测试：

- `work_unit_manage` 能创建或复用 TEAM / FLOW 容器并追加目标（**用于自然语言管理路径**；slash 路径已由服务端 `WorkUnitService` 建容器，不经此工具）。
- 能移除 completed 容器。
- running 容器移除失败。
- capability searchText 包含 `团队 编排 工作容器 目标 追加 移除` 中文别名。
- system prompt 告诉主 Agent：**slash intent 的容器/目标已由服务端建好（goalId 在 ToolContext），直接执行**；只有自然语言管理（补充/排队/追加/移除）才调 `work_unit_manage`。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitManageToolTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest" test
```

Expected: FAIL。

- [ ] **Step 3: 实现工具**

工具名保持 ASCII：

```java
@Tool(
    name = "work_unit_manage",
    description = "Create, reuse, append goals, update or remove named team/flow work units. 管理团队、编排、目标、追加目标、移除容器。"
)
```

操作：

- `CREATE_OR_REUSE_APPEND_GOAL`
- `MARK_GOAL_RUNNING`
- `MARK_GOAL_COMPLETED`
- `REMOVE`

不做：

- 不直接执行团队或编排。
- 不绕过审批。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitManageToolTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/tool/impl/WorkUnitManageTool.java backend/src/main/java/com/wzx/babiq/server/security backend/src/main/java/com/wzx/babiq/server/capability backend/src/test/java/com/wzx/babiq/server
git commit -m "feat(p6-4): 增加工作容器管理工具"
```

---

## Chunk 4: 执行工具与 WorkUnit 关联

### Task 10: Flow / Team 工具写入目标运行引用

**Files:**

- Modify: `backend/src/main/java/com/wzx/babiq/server/tool/impl/FlowOrchestrationTool.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/tool/impl/TeamCoordinationTool.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/tool/impl/FlowOrchestrationToolTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/tool/impl/TeamCoordinationToolTest.java`

- [ ] **Step 1: 写失败测试**

当 ToolContext 中存在 `workUnitGoalId`：

- `orchestrate_flow` 创建 `orch_...` 后标记 goal running。
- 流程完成后标记 goal completed。
- `coordinate_team` 创建 `team_...` 后标记 goal running。
- 团队完成后标记 goal completed。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=FlowOrchestrationToolTest,TeamCoordinationToolTest" test
```

Expected: FAIL。

- [ ] **Step 3: 实现关联**

**可靠路径（采用）= goalId 经 ToolContext 注入，不靠模型串参**：

- slash 路径：`submit()` 服务端已建 goal（Task 5），`AgentLoop` build agent 时把 `goalId` 写入 `ToolContext`（key 如 `CONTEXT_WORK_UNIT_GOAL_ID`），**与现有 `CONTEXT_CWD` 注入同一处、同一套路**（已由 Context7 确认 `ToolContext` 服务端数据不发给模型）。
- `FlowOrchestrationTool` / `TeamCoordinationTool` 读取 `toolContext.getContext().get(CONTEXT_WORK_UNIT_GOAL_ID)`：存在则创建 `orch_`/`team_` 后 `markGoalRunning(goalId, runRefType, runRefId)`，完成后 `markGoalCompleted(goalId, summary)`。
- 自然语言管理路径：模型调 `work_unit_manage` 拿到 `goalId` 后，由该工具把 goalId 写回当前 turn 的可变上下文（或返回结构化结果让后续同 turn 工具读取），同样不靠模型把 goalId 当文本串进参数。

> 不采用“让模型把 goalId 当 task 文本传给后续工具”的脆弱退路；如确需显式参数，可新增**可选** `workUnitGoalId` ToolParam 作为兜底，但默认走 ToolContext。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=FlowOrchestrationToolTest,TeamCoordinationToolTest" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/tool/impl backend/src/test/java/com/wzx/babiq/server/tool/impl
git commit -m "feat(p6-4): 关联工作目标与团队编排运行"
```

### Task 11: WorkUnit JSON-RPC 列表与移除

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/WorkUnitListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/WorkUnitRemoveHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/api/method/WorkUnitHandlersTest.java`

- [ ] **Step 1: 写失败测试**

方法：

- `workunit/list`：按 threadId 返回未 removed 容器和目标列表。
- `workunit/remove`：移除 completed / idle / failed 容器，running 返回错误。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitHandlersTest" test
```

Expected: FAIL。

- [ ] **Step 3: 实现 handlers**

响应字段使用 camelCase，状态展示仍由桌面端中文化。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitHandlersTest" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/method backend/src/test/java/com/wzx/babiq/server/api/method
git commit -m "feat(p6-4): 增加工作容器查询和移除接口"
```

---

## Chunk 5: 桌面 WorkUnit 页面和运行详情

### Task 12: 桌面协议模型与状态聚合

**Files:**

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/WorkUnitModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentGateway.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatReducerTest.kt`

- [ ] **Step 1: 写失败测试**

覆盖：

- `workunit/list` 正确解码。
- `workunit/remove` 正确调用。
- reducer 能保留多个 FLOW / TEAM 容器。
- removed 容器从 UI state 消失。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatReducerTest"
```

Expected: FAIL。

- [ ] **Step 3: 实现协议和状态**

新增 `WorkUnitUiState`：

```kotlin
data class WorkUnitUiState(
    val flowUnits: List<WorkUnitSummary> = emptyList(),
    val teamUnits: List<WorkUnitSummary> = emptyList(),
)
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatReducerTest"
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol desktop/src/main/kotlin/com/wzx/babiq/desktop/client desktop/src/main/kotlin/com/wzx/babiq/desktop/state desktop/src/test/kotlin/com/wzx/babiq/desktop
git commit -m "feat(p6-4): 桌面端接入工作容器协议"
```

### Task 13: 右侧 WorkUnit 列表和移除

**Files:**

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/WorkUnitSection.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RunDetailsPanel.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/runtime/WorkUnitSectionTest.kt`

- [ ] **Step 1: 写失败测试**

覆盖：

- 显示多个团队和编排容器。
- 每个容器显示名称、类型、当前目标、目标计数、状态。
- completed / idle 容器展示“移除”按钮。
- running 容器不展示移除按钮或按钮 disabled。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*WorkUnitSectionTest"
```

Expected: FAIL。

- [ ] **Step 3: 实现 UI**

布局：

- 右侧运行详情新增 “工作容器” 分区。
- `编排` 和 `团队` 使用两个小 tab 或分组标题。
- 容器卡片保持 8dp 以下圆角，避免嵌套卡片。
- 目标队列默认展示最近 3 个，更多用 “展开”。
- 移除后立即从列表消失。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*WorkUnitSectionTest"
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/runtime
git commit -m "feat(p6-4): 运行详情展示命名团队和编排"
```

---

## Chunk 6: 端到端、文档和验收

### Task 14: 端到端测试

**Files:**

- Create: `backend/src/test/java/com/wzx/babiq/server/workunit/WorkUnitSlashIntentIT.java`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

- [ ] **Step 1: 写失败测试**

后端 IT 场景：

- `/团队 前端验收组：检查聊天页` 解析为 TEAM intent。
- 创建 `前端验收组` work unit。
- 追加 goal。
- 主 Agent 调用 `work_unit_manage` 后调用 `coordinate_team`。
- goal 关联 `teamId` 并完成。
- `workunit/list` 返回该容器。
- `workunit/remove` 后列表不再返回。

桌面场景：

- 输入 slash command 后本地消息只显示目标文本。
- work unit 列表展示团队名称。
- 点击移除后 UI 消失。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitSlashIntentIT" test

cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ChatControllerTest"
```

Expected: FAIL。

- [ ] **Step 3: 实现缺口**

只补测试揭示的必要缺口，不扩大范围。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitSlashIntentIT" test

cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ChatControllerTest"
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/test/java/com/wzx/babiq/server/workunit desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt
git commit -m "test(p6-4): 覆盖 slash 触发工作容器端到端"
```

### Task 15: 文档同步

**Files:**

- Modify: `CLAUDE.md`
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/plans/p6-master.md`
- Create: `docs/superpowers/plans/p6-4-slash-work-unit-commands/codex-handoff.md`

- [ ] **Step 1: 更新检查点**

在 CLAUDE.md / AGENTS.md 当前检查点补充：

```text
P6-4 Slash 命令与命名工作容器已完成：输入框支持 /子代理、/编排、/团队；
团队和编排作为可复用命名容器，可持续追加目标、并发存在、完成后移除隐藏；
用户正文不写入 slash 控制语法，executionIntent 只作为本轮运行意图注入上下文。
```

- [ ] **Step 2: 更新 P6 master**

补充：

- P6-4 定位。
- Slash command 语法。
- WorkUnit / Goal 生命周期。
- 与 P6-1/P6-2/P6-3 的关系。

- [ ] **Step 3: 编写 codex-handoff**

交接文件包含：

- 当前状态。
- 实现范围。
- 关键文件。
- 验证命令。
- 未完成/风险。

- [ ] **Step 4: 中文 commit**

```powershell
git add CLAUDE.md AGENTS.md docs/superpowers/plans/p6-master.md docs/superpowers/plans/p6-4-slash-work-unit-commands
git commit -m "docs(p6-4): 同步 slash 工作容器计划和交接"
```

### Task 16: 全量验证

- [ ] **Step 1: 后端专项测试**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=ExecutionIntentTest,TurnStartHandlerTest,WorkUnitIntentInstructionBuilderTest,WorkUnitServiceTest,WorkUnitManageToolTest,WorkUnitHandlersTest,FlowOrchestrationToolTest,TeamCoordinationToolTest,SchemaCommentsCoverageTest,WorkUnitSlashIntentIT" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 2: 后端全量验证**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd clean verify
```

Expected: BUILD SUCCESS，所有 IT 通过。

- [ ] **Step 3: 桌面专项测试**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*SlashCommandParserTest" --tests "*SlashCommandMenuTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*WorkUnitSectionTest"
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: 桌面全量验证**

Run:

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 人工烟测**

使用真实 Provider 验证：

```text
/子代理 检查当前目录结构
/编排 登录页优化：检查登录页 UI 和 Figma 是否一致
/团队 前端验收组：分别检查聊天页、技能页和设置页
/团队 前端验收组：继续检查运行详情面板
```

验收点：

- `/子代理` 不创建 work unit。
- `/团队 前端验收组` 第二次复用同名团队并追加目标。
- 多个团队 / 编排可同时展示。
- 已完成容器移除后从页面消失。
- 用户消息正文不包含 slash 控制语法。

- [ ] **Step 6: 最终中文 commit**

如果验证后有修正：

```powershell
git add .
git commit -m "fix(p6-4): 修正 slash 工作容器验收问题"
```

---

## 4. 实现前必须复核

> **已核对（2026-06-02，Context7 + 现有代码）**：Spring AI 官方 reference 确认 `@Tool` 方法可接 `ToolContext` 参数、`toolContext.getContext().get(key)` 取服务端数据、**该数据永不发送给模型**（runtime/default 合并、runtime 优先）；BaBiQ `ReActStrategy` 已用 `ReactAgent.builder().toolContext(...)` 并通过 `toolContext.getContext().get(CONTEXT_CWD)` 取 cwd/sandbox/observation——goalId 走 ToolContext 是**现成可靠模式**。`orchestrate_flow`/`coordinate_team`（Sequential/Parallel/StateGraph）P6-2/P6-3 已建，本阶段**复用、零新 API、不升级版本**。结论：技术完全可行。

- 使用 Context7 复核 Spring AI / Spring AI Alibaba 当前锁定版本的相关 API：
  - `@Tool` / `ToolContext`
  - Spring AI Alibaba `ReactAgent`
  - `SequentialAgent` / `ParallelAgent` / `LlmRoutingAgent`
  - `StateGraph` / `MemorySaver`
- 同时用本地 jar 或依赖树确认精确 API，以本仓锁定版本为准。
- Slash command 和 WorkUnit 是 BaBiQ 自有协议层，不应强行套 Spring AI 官方接口。

## 5. 风险和回退

- **模型不按 intent 调工具（已大幅降低）**：容器 create/reuse/append-goal 改为**服务端确定性**完成（slash intent 已是结构化输入），模型只需执行 `coordinate_team`/`orchestrate_flow`；goalId 经 ToolContext 注入，不靠模型串参。残余风险仅“模型不执行”，由 `current_turn` 权威指令约束 + `work_unit_manage` 设为可见能力兜底。
- **名称唯一的并发（TOCTOU）**：create-or-reuse 必须包在**事务**里（复用 BaBiQ 压缩链路已有的 `TransactionTemplate` 同款），避免同名容器并发重复创建；SQLite 单写场景风险低，但仍按事务边界实现。
- **审批闸门必须保留**：slash 触发的 flow/team 仍走 P6-2/P6-3 的 approve-once 审批弹窗——复用同工具应自动保留，但**补一个测试钉死**（slash 触发也弹审批、批准后才执行）。
- **`current_turn` 层权威**：intent 指令注入**现有 `current_turn` 权威层**（系统提示已写其优先级最高）；若另起 `current_turn_instruction` 新层，必须确保拿到同等权威，否则注入指令可能不被当作本轮最高优先。
- **名称歧义**：运行中的名称（同 thread 内）唯一；完成/移除后同名允许重新创建。
- **目标排队太复杂**：第一版只支持追加 pending goal，运行中不自动并发启动 queued goal；下一轮由主 Agent 或用户继续触发。
- **移除误解为删除历史**：UI 文案使用“从页面移除”，不写“删除历史”。
- **协议污染历史**：测试必须钉住 `bq_items.userMessage.text` 不含 slash 控制语法。

## 6. 完成报告要求

完成实现后，最终报告必须包含：

- 每个 Task 的完成状态。
- 实际运行过的验证命令和关键输出。
- 中文 conventional commit 列表。
- 说明是否执行真实模型烟测。
- 说明没有主动 push。

