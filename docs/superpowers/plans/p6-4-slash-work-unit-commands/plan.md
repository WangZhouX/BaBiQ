# P6-4 Slash 命令与命名工作容器 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在对话输入框提供 `/子代理`、`/编排`、`/团队` 显式入口，并把 `/编排`、`/团队` 变成“创建或复用命名工作容器”的前置动作。容器创建后保持 `待配置 / 待启动`，后续节点/成员配置、模型选择和开始执行复用已有编排详情 / 团队详情页面；同一容器可持续承接多个目标、并发存在、完成后移除并从 UI 消失。

**Architecture:** 桌面端解析 slash 命令并把结构化 `executionIntent` 随 `turn/start` 发送；后端保留用户原始任务文本不污染聊天历史。**容器创建走服务端确定性逻辑**：slash intent 是确定性结构化输入（mode+name+goal 已由用户选定），`turn/start` 路径 create-or-reuse `WorkUnit` 容器并 append 初始目标，随后把容器置为 `WAITING_CONFIG`，向桌面端返回/刷新右侧工作容器状态。主 Agent 可以通过自然语言管理工具辅助修改目标、节点职责或成员职责，但**模型 / Provider / 高权限策略必须由用户在详情页手动配置**。开始执行必须来自用户显式动作：点击已有编排/团队详情页的“开始执行”，或在对话中明确要求主 Agent 启动；只有启动阶段才把 `goalId` 注入 `ToolContext` 并调用现有 `orchestrate_flow` / `coordinate_team` 回写目标状态。`work_unit_manage` 工具用于自然语言管理（追加目标、修改目标/职责、移除容器、按用户要求启动），不是 slash 路径建容器的必经跳。

**Tech Stack:** Kotlin Compose Desktop、Ktor WebSocket JSON-RPC、Java 21、Spring Boot、SQLite + MyBatis-Plus + Flyway、Spring AI Alibaba ReactAgent / FlowAgent / StateGraph、BaBiQ 现有审批 / 沙箱 / 运行记录 / 协议 item 链路。

---

## 实施状态（2026-06-02）

- 已落地桌面 slash 解析、`executionIntent` 协议、服务端确定性 WorkUnit 创建/复用、目标队列、SQLite 事实源、`work_unit_manage`、`workunit/list`、`workunit/remove`、显式启动 goalId 关联和右侧工作容器 UI。
- `/编排`、`/团队` 只创建/复用命名容器并追加目标，不自动调用 `orchestrate_flow` / `coordinate_team`；用户正文只保留目标文本，不写入 slash 控制语法。
- 详情页模型/Provider/高权限策略仍由用户手动配置；显式启动时才把 `goalId` 关联到本轮工具上下文并回写目标状态。
- 实现修订：后端没有单独落地 `ExecutionIntent` / `ExecutionIntentMode` 模型，也没有 `WorkUnitIntentInstructionBuilder`。桌面端仍发送结构化 `executionIntent`，后端由 `TurnStartHandler.parseWorkUnitCreateRequest(...)` 直接解析为 `WorkUnitCreateRequest` 并创建容器；slash create-only 路径跳过 AgentLoop，因此不需要向模型注入“已创建、等待配置/启动”的额外指令。
- 实现修订：WorkUnit 当前事实源使用 5 个状态：`waiting_config`、`running`、`completed`、`failed`、`removed`。原计划中的 `waiting_start` 没有独立落库；“待启动”是 `waiting_config` 下的 UI/业务提示含义。
- 定向测试已通过；全量验证和最终提交见 `codex-handoff.md` 与本次完成报告。

## 0. 用户确认后的心智模型

- `/子代理 <任务>`：一次性只读委派，不命名，不维护目标队列；结束后只保留本次委派摘要，可手动移除右侧卡片。
- `/编排 <名称>：<目标>`：创建或复用一个命名编排容器，把 `<目标>` 作为新的目标追加进去；容器创建后保持待配置 / 待启动，不自动执行。
- `/团队 <名称>：<目标>`：创建或复用一个命名团队容器，把 `<目标>` 作为新的目标追加进去；容器创建后保持待配置 / 待启动，不自动执行。
- 编排 / 团队是可复用容器，目标是容器里的任务批次。一个容器可以完成目标 1、目标 2、目标 3，不需要用户频繁新建。
- 同一个 thread 内，运行中的编排和团队名称必须唯一；已完成 / 空闲容器可以继续追加目标；被移除的容器从页面消失，但后端仍保留审计事实。
- 多个编排和多个团队可以并发存在，只要运行中的名称不冲突。
- 用户可以通过和主 Agent 对话修改目标、追加目标、修改节点/成员职责或移除已完成容器，例如“让前端验收组继续检查技能页”“把 analyzer 的职责改成只核对原型差异”“移除登录页优化流程”。
- 模型、Provider、高权限策略必须由用户在已有详情页手动配置；主 Agent 不自动替用户改模型配置。
- 开始执行必须由用户显式触发：可以点击已有详情页里的“开始执行”，也可以在对话中明确告诉主 Agent 启动某个编排 / 团队。
- P6-4 原型只新增两个入口页：`P6-4 01 会话-斜杠创建编排容器`、`P6-4 02 会话-斜杠创建团队容器`。编排配置复用 `P6 06/07/08`，团队配置和执行复用 `P6 03/04/05`。

## 1. 范围

**本阶段做：**

- 桌面输入框 slash command 解析和命令面板。
- `turn/start` 增加结构化 `executionIntent`，不把 `/团队` 等控制语法写入用户消息正文。
- 后端新增 `WorkUnit` 领域模型，统一承载 `TEAM` 与 `FLOW` 容器。
- 编排 / 团队容器支持名称、目标队列、当前目标、配置状态、启动状态、运行状态、移除状态。
- slash 创建容器后进入待配置 / 待启动状态，不自动调用 `orchestrate_flow` / `coordinate_team`。
- 已有编排详情 / 团队详情页面负责后续配置、模型选择和开始执行；P6-4 不重复实现详情配置 UI。
- 用户明确启动后，主 Agent 通过系统上下文识别启动 intent，并调用现有 `orchestrate_flow` / `coordinate_team`。
- 现有 `bq_orchestrations`、`bq_teams` 与新增 work unit 表建立关联，不重写 P6-2 / P6-3 执行引擎。
- 右侧运行详情支持多个团队 / 编排容器列表、目标队列、待配置/待启动状态、跳转已有详情页、移除已完成容器。
- 支持自然语言管理：主 Agent 可调用管理工具追加目标、更新目标/职责、按用户明确要求启动、移除容器。

**本阶段不做：**

- 不实现自由连边图编辑器。
- 不实现运行中逐工具审批和并发中断；继续沿用 P6-2 / P6-3 当前 approve-once 语义。
- 不让 UI 直接调用 `orchestrate_flow` / `coordinate_team` 绕过主 Agent。
- 不重复实现新的编排详情 / 团队详情配置页；复用已有 P6 详情帧和桌面端详情区。
- 不允许主 Agent 自动修改模型、Provider 或高权限策略。
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
status: WAITING_CONFIG | RUNNING | COMPLETED | FAILED | REMOVED
currentGoalId: goal_...
removed: false
```

状态语义补充：

- `WAITING_CONFIG`：slash 创建 / 复用后，仍需要用户检查节点/成员职责或模型配置；UI 可在此状态下提示“待启动”，但不单独落库 `WAITING_START`。
- `RUNNING`：用户显式启动后，才进入真实编排 / 团队执行链路。
- `COMPLETED` / `FAILED` / `REMOVED`：完成、失败或从页面移除；移除不删除审计事实。

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

- 容器不存在：服务端创建容器，append 目标，得 `goalId`，容器进入 `WAITING_CONFIG`，不自动执行。
- 容器存在且不是 running：服务端复用容器，append 目标，得 `goalId`，容器保持待配置 / 待启动，不自动执行。
- 容器 running：服务端默认把目标 append 为 `pending`（排队），不会抢占当前执行。**仅当用户用自然语言要求**“补充当前目标 / 排到下一个”时，主 Agent 才调用 `work_unit_manage` 调整——这是需要语言判断、无法纯结构化决定的少数场景。
- 用户点击详情页“开始执行”或明确说“启动某某编排/团队”时，才把目标切到 running，并由主 Agent 调用 `orchestrate_flow` / `coordinate_team`。

> **确定性边界**：create / reuse / append-goal 全部在 `submit()` 路径服务端完成（slash intent 已含 mode+name+goal，无需模型判断），但这一步只创建“待配置 / 待启动”容器，不触发执行。`goalId` 只在用户显式启动时注入本轮 `ToolContext`；模型只负责在启动阶段调用 `coordinate_team` / `orchestrate_flow` 执行。`work_unit_manage` 工具保留给自然语言管理（修改目标/职责、补充 vs 排队、追加、移除、按用户要求启动）。这样去掉“模型必须先调 work_unit_manage 再串 goalId”的脆弱链，也避免 slash 命令绕过用户配置。

### 2.3 名称唯一

- 同一 thread 内，`removed=0` 的同 kind + normalized_name 容器优先复用；运行中容器追加 pending goal，不替换当前执行目标。
- 建议唯一键语义：`thread_id + kind + normalized_name + removed` 的服务层校验；实现时用 `WorkUnitService` 串行事务处理，不强依赖复杂 SQLite partial index。
- 如果 `/团队 前端验收组：...` 时已有运行中的同名团队，服务端追加为该团队的 pending goal，并保持当前 running 目标不变。
- 团队和编排可以允许同名，也可以全局唯一；本阶段推荐 **同一 thread 内运行中的团队/编排名称全局唯一**，减少用户说“前端验收组”时的歧义。

### 2.4 移除

- 已完成 / 空闲 / 失败的容器可以移除。
- 运行中的容器不能直接移除；必须先取消或等待完成。
- 移除后该容器从右侧团队/编排页面消失。
- 移除只设置 `removed=1` 和 `removed_at`，不删除运行记录、工具调用和目标审计。
- 被移除后再次使用同名 `/团队` 或 `/编排`，创建新的容器。

## 3. 文件结构

### 后端新增

- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnit.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitGoal.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitService.java`
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
- Create: `backend/src/test/java/com/wzx/babiq/server/workunit/WorkUnitServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/tool/impl/WorkUnitManageToolTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/WorkUnitHandlersTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/context/ContextAssemblerTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/tool/impl/FlowOrchestrationToolWorkUnitTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/tool/impl/TeamCoordinationToolWorkUnitTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/workunit/WorkUnitSlashIntentIT.java`

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

### Task 4: 后端 executionIntent 解析模型（已简化）

**Files:**

- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`
- Use: `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitCreateRequest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`

- [ ] **Step 1: 写失败测试**

覆盖：

- `executionIntent.type=create_work_unit` 时，`kind/name/goal` 必填。
- `kind` 只允许 `orchestration` 或 `team`。
- slash create-only 路径创建 WorkUnit 后完成 turn，不提交 `TurnExecutor`。
- 缺少必填字段或非法 kind 时返回 `INVALID_PARAMS`。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=TurnStartHandlerTest" test
```

Expected: FAIL。

- [ ] **Step 3: 实现解析**

实现选择：不额外新增 `ExecutionIntent` / `ExecutionIntentMode` 后端 record。桌面端仍发送结构化 `executionIntent`，后端在 `TurnStartHandler.parseWorkUnitCreateRequest(...)` 中直接解析为 `WorkUnitCreateRequest`，减少一层只转发字段的 DTO。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=TurnStartHandlerTest" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java backend/src/main/java/com/wzx/babiq/server/workunit backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java
git commit -m "feat(p6-4): 解析显式工作容器意图"
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

**TEAM / FLOW intent 在此路径服务端确定性建容器**：调用 `WorkUnitService.createOrAppend(...)` 拿到 `goalId`，但本阶段只把容器置为 `WAITING_CONFIG`，刷新右侧工作容器状态，不把 `goalId` 立即传给 `AgentLoop` 执行。**不要**让模型先调 `work_unit_manage` 才有容器，也不要让 slash 命令自动启动 flow/team。

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

### Task 6: slash create-only 路径不注入模型指令（已简化）

**Files:**

- Modify: none.
- Test: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/workunit/WorkUnitSlashIntentIT.java`

- [ ] **Step 1: 写失败测试**

断言：

- `executionIntent.type=create_work_unit` 时服务端创建 WorkUnit 后直接完成 turn。
- create-only slash 路径不提交 `TurnExecutor`，因此不会进入 `AgentLoop` / `ContextAssembler` / 模型上下文装配。
- 后续显式启动通过 `work_unit_manage start` 或详情页动作进入 AgentLoop，并在启动阶段注入 `goalId` 到 `ToolContext`。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=TurnStartHandlerTest,WorkUnitSlashIntentIT" test
```

Expected: FAIL。

- [ ] **Step 3: 保持确定性 create-only 实现**

不新增 `WorkUnitIntentInstructionBuilder`。理由：slash 创建容器本身不进入模型，直接由后端创建/复用容器并发 `workUnit` item。向模型注入“不要执行”的指令反而会扩大上下文和污染面；显式启动阶段已有 `ToolContext` goalId 关联和 flow/team 工具回写测试覆盖。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=TurnStartHandlerTest,WorkUnitSlashIntentIT" test
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: 中文 commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java backend/src/test/java/com/wzx/babiq/server/workunit/WorkUnitSlashIntentIT.java
git commit -m "test(p6-4): 钉住 slash 工作容器 create-only 语义"
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
- 已完成容器追加新目标并回到 `waiting_config`；运行中容器追加 pending goal，但当前目标仍保持 running。
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

- `createOrAppend(request, thread, turn, cwd, runPolicy)`
- 如果同名容器 running，追加目标为 `pending`，但不替换 `currentGoalId`，容器状态保持 `running`。
- 如果同名容器 completed / failed / waiting_config，追加目标并设置容器状态为 `waiting_config`。
- `remove(workUnitId)` 只允许 `waiting_config`、`completed`、`failed`。

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
- system prompt 告诉主 Agent：**slash intent 的容器/目标已由服务端建好，但处于 waiting_config（待配置/待启动提示）状态，不能自动执行**；只有自然语言管理（修改目标/职责、补充/排队/追加、按用户明确要求启动、移除）才调 `work_unit_manage`。

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
- `UPDATE_GOAL_TEXT`
- `UPDATE_ROLE_TEXT`
- `REQUEST_START`
- `MARK_GOAL_RUNNING`
- `MARK_GOAL_COMPLETED`
- `REMOVE`

不做：

- 不直接执行团队或编排。
- 不绕过审批。
- 不修改模型、Provider 或高权限策略；这些配置必须由用户在详情页手动完成。

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
- Test: `backend/src/test/java/com/wzx/babiq/server/tool/impl/FlowOrchestrationToolWorkUnitTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/tool/impl/TeamCoordinationToolWorkUnitTest.java`

- [ ] **Step 1: 写失败测试**

当用户从已有编排 / 团队详情页点击“开始执行”，或在对话中明确要求主 Agent 启动某个容器时，启动路径把 `workUnitGoalId` 注入 `ToolContext`：

- `orchestrate_flow` 创建 `orch_...` 后标记 goal running。
- 流程完成后标记 goal completed。
- `coordinate_team` 创建 `team_...` 后标记 goal running。
- 团队完成后标记 goal completed。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=FlowOrchestrationToolWorkUnitTest,TeamCoordinationToolWorkUnitTest" test
```

Expected: FAIL。

- [ ] **Step 3: 实现关联**

**可靠路径（采用）= 启动阶段 goalId 经 ToolContext 注入，不靠模型串参**：

- slash 创建路径：`submit()` 服务端只建容器和 goal（Task 5），不启动，不注入 `goalId` 执行。
- 显式启动路径：用户点击详情页“开始执行”或自然语言要求启动后，`AgentLoop` build agent 时把 `goalId` 写入 `ToolContext`（key 如 `CONTEXT_WORK_UNIT_GOAL_ID`），**与现有 `CONTEXT_CWD` 注入同一处、同一套路**（已由 Context7 确认 `ToolContext` 服务端数据不发给模型）。
- `FlowOrchestrationTool` / `TeamCoordinationTool` 读取 `toolContext.getContext().get(CONTEXT_WORK_UNIT_GOAL_ID)`：存在则创建 `orch_`/`team_` 后 `markGoalRunning(goalId, runRefType, runRefId)`，完成后 `markGoalCompleted(goalId, summary)`。
- 自然语言管理路径：模型调 `work_unit_manage` 可修改目标/职责、移除容器或按用户明确要求启动；启动时由服务端把 goalId 写入当前 turn 的可变上下文（或返回结构化结果让后续同 turn 工具读取），同样不靠模型把 goalId 当文本串进参数。

> 不采用“让模型把 goalId 当 task 文本传给后续工具”的脆弱退路；如确需显式参数，可新增**可选** `workUnitGoalId` ToolParam 作为兜底，但默认走 ToolContext。

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=FlowOrchestrationToolWorkUnitTest,TeamCoordinationToolWorkUnitTest" test
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
- 每个容器显示名称、类型、当前目标、目标计数、待配置 / 待启动 / 运行中等状态。
- 待配置 / 待启动容器展示“进入详情”入口，跳转到已有编排详情或团队详情配置区。
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
- 配置和启动入口复用已有页面：编排跳到 P6 06/07/08 对应的详情 / 节点设置 / 编辑节点；团队跳到 P6 03/04/05 对应的团队协作 / 团队设置 / 执行分屏。
- P6-4 不新增独立配置页，只新增“slash 创建容器成功”入口页。
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
- 容器进入 `WAITING_CONFIG`，slash 本身不调用 `coordinate_team`。
- `workunit/list` 返回该容器，状态和目标正确。
- 用户显式启动后，主 Agent 才调用 `coordinate_team`。
- goal 关联 `teamId` 并完成。
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
.\mvnw.cmd "-Dtest=TurnStartHandlerTest,WorkUnitServiceTest,WorkUnitManageToolTest,WorkUnitHandlersTest,FlowOrchestrationToolWorkUnitTest,TeamCoordinationToolWorkUnitTest,SchemaCommentsCoverageTest,WorkUnitSlashIntentIT" test
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
启动 登录页优化
启动 前端验收组
/团队 前端验收组：继续检查运行详情面板
```

验收点：

- `/子代理` 不创建 work unit。
- `/编排` / `/团队` 创建或复用容器后停留在待配置 / 待启动，不自动调用 flow/team。
- 进入已有编排详情 / 团队详情页可以配置职责、模型和启动。
- 模型、Provider、高权限策略只能由用户手动配置。
- 用户显式启动后才触发 `orchestrate_flow` / `coordinate_team` 和审批弹窗。
- `/团队 前端验收组` 第二次复用同名团队并追加目标，但不会自动抢占当前运行。
- 多个团队 / 编排可同时展示，P6-4 原型只作为创建入口页。
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

- **slash 后误自动执行**：容器 create/reuse/append-goal 改为**服务端确定性**完成，但只进入待配置 / 待启动；测试必须钉住 slash 本身不调用 `coordinate_team`/`orchestrate_flow`。真正执行只在用户显式启动后发生，goalId 经 ToolContext 注入，不靠模型串参。
- **名称唯一的并发（TOCTOU）**：create-or-reuse 必须包在**事务**里（复用 BaBiQ 压缩链路已有的 `TransactionTemplate` 同款），避免同名容器并发重复创建；SQLite 单写场景风险低，但仍按事务边界实现。
- **审批闸门必须保留**：用户显式启动 flow/team 后仍走 P6-2/P6-3 的 approve-once 审批弹窗——复用同工具应自动保留，但**补一个测试钉死**（启动时弹审批、批准后才执行）。
- **slash create-only 不污染上下文**：slash 创建/复用容器后直接完成 turn，不向 `current_turn` 注入额外模型指令；真正启动时通过 ToolContext 传递 goalId，避免把控制语义混进对话上下文。
- **名称歧义**：运行中的名称（同 thread 内）唯一；完成/移除后同名允许重新创建。
- **目标队列太复杂**：第一版只支持追加 pending goal，运行中不自动并发启动后续目标；下一轮由主 Agent 或用户继续触发。
- **移除误解为删除历史**：UI 文案使用“从页面移除”，不写“删除历史”。
- **协议污染历史**：测试必须钉住 `bq_items.userMessage.text` 不含 slash 控制语法。
- **模型配置被 Agent 自动改写**：模型、Provider、高权限策略属于用户手动配置区；自然语言管理工具只能改目标和职责文本，不改模型配置。

## 6. 完成报告要求

完成实现后，最终报告必须包含：

- 每个 Task 的完成状态。
- 实际运行过的验证命令和关键输出。
- 中文 conventional commit 列表。
- 说明是否执行真实模型烟测。
- 说明没有主动 push。
