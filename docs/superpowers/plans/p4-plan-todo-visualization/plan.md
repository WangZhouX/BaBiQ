# P4 Plan/Todo 可视化专项计划（草案）

> **For agentic workers:** 本文件目前是**草案（DRAFT）**，尚未经用户确认，**不得据此开始实现**。
> 实施前需：(1) ✅ §3 决策已定（D1/D2 双源印证，D3/D4 已用 Figma 原型确认）；(2) 完成 P3 总体验收复盘；(3) 用 `superpowers:writing-plans` 复核定稿；实施时用 `superpowers:executing-plans` + `superpowers:test-driven-development`；声称完成前用 `superpowers:verification-before-completion`。
>
> **状态：** 草案 / 待用户确认。本计划新增一条「协议 → AgentLoop → 桌面 UI」纵向特性，属于 P3 之后的新阶段，**不在 P3 范围内**。
>
> **设计依据：** 已交叉核对两套业界实现源码 —— Codex `update_plan`（`E:\wzx\codex`）和 Claude Code `TodoWrite`（`E:\wzx\claude-code`），并用 Context7 核对 Spring AI / Spring AI Alibaba 工具与 system prompt 能力。详见 §11。

**Goal:** 把 Agent 的多步任务计划做成**可视化进度面板**——模型通过 `update_plan` 工具**全量重发**任务步骤及其状态（pending / in_progress / completed），后端发 `PlanItem` 协议事件，桌面端在**右侧固定运行面板**中渲染一个可勾选、原地更新、可收起、全部完成后自动隐藏的「进度」区。**简单任务不出现、复杂任务才出现，完全由 system prompt 约束模型自主决定，不写任何复杂度检测代码。**

**Architecture:** 复用 BaBiQ 现有「工具发 item → emitter 落库 → WebSocket notification → 桌面 reducer → Compose 渲染」链路，**不引入新机制**。计划由本地工具 `update_plan` 产生（和 `write_file` 发 `fileChange` item 同一模式）。**全量覆盖语义**（对齐 Codex + Claude Code）：每次调用携带完整计划列表，最新一份生效；首次 `item/added`、同一计划后续 `item/updated`（原地刷新，不在聊天流堆卡）；全部 completed 时面板隐藏。

**Tech Stack:** 同 P3，不升级 Spring AI / Spring AI Alibaba。后端：扩展已存在的 `PlanItem` 协议 record + 新增 `UpdatePlanTool`（`@Tool`）+ system prompt 计划规则段。桌面端：扩展 `ThreadItem`/`ChatReducer`/`AppState` + 新增 Compose 进度面板。**不新增数据库表**（计划落在已有 `bq_items` payload）。

---

## 1. 为什么做 / 为什么是真缺口

### 1.1 协议占了位，但从未接通

- 后端 `conversation/items/PlanItem.java` 早在 P1-1 就定义了 wire schema：`goal` + `steps[order, description]` + `reasoning`，并在 `ThreadItem` sealed interface 注册为 `type="plan"`。
- 它的 Javadoc 白纸黑字写着：「P1-1 先固化 wire schema，**后续前端可直接基于 steps 渲染任务进度**。」
- 实证（已用 grep 核对）：`PlanItem` **只在 `ThreadItem.java` 注册了类型，没有任何 `AgentLoop` / handler / 工具真正发出它**。
- 桌面端 `protocol/ThreadModels.kt` 的 `ThreadItem`（Kotlin 镜像）**没有 `Plan` 变体**，`ThreadItemSerializer` 没有 `"plan"` 分支——即使后端发了 plan，桌面端现在也只会落进 `Unknown`，在运行详情里显示一坨 raw JSON。
- `PlanStep` 当前**只有 `order` + `description`，没有 `status`** —— 做进度勾选必须补 status。

**结论：这是「协议占位、后端没发、前端没画」的三不管地带。补它正是兑现 P1-1 当初留的扩展点。**

### 1.2 P3 原型里也没有这一屏

- 已核对 Figma 原型（`frTp55zgrKf4NAWxn6LdI7` 节点 `35:2`，约 45 屏）：所有会话屏右上角只有图标按钮，**没有任务步骤清单 / 进度面板**。AI 的多步计划现在只能以普通正文（`1. 2. 3.`）混在回答气泡里——刷新滚动后就找不到，无法表达「第 2 步进行中、第 3 步未开始」。
- 有「运行详情」面板，但那是**上下文 / 记忆 / 工具的审计数据视图**，不是 Agent 任务计划。

### 1.3 这是 Agent 体验的核心能力（Codex + Claude Code 都有）

- **Codex** 有 `update_plan`（`plan_tool.rs` / `plan_spec.rs` / `plan.rs`）。
- **Claude Code** 有 `TodoWrite`（`src/tools/TodoWriteTool/`），正是用户参考截图里「进度」面板的来源。
- 两套都把"让用户看到 Agent 打算怎么干、干到哪一步"作为标志能力。对一个 Codex-like 学习项目，缺少计划可视化是一个**显眼的能力短板**。

---

## 2. 范围边界

### 2.1 必做

- **后端**
  - 扩展 `PlanItem` / `PlanStep` 协议 record：
    - 给 `PlanStep` 增加 `status`（`pending` / `in_progress` / `completed`）。
    - 给 `PlanStep` 增加可选 `activeForm`（进行时文案，借鉴 Claude Code：`description="运行测试"` + `activeForm="正在运行测试"`，进行中的步骤显示 activeForm）。
    - `goal` / `reasoning` 设为可选（对齐 Codex 的 `explanation` 可选、无独立 goal 字段）。
    - **不引入 planId 增量**——采用全量覆盖语义。
  - 新增本地工具 `UpdatePlanTool`（`@Tool name="update_plan"`）：
    - 入参为**完整计划**（steps 列表，每步含 description/status/可选 activeForm + 可选 reasoning/goal）。
    - 从 `toolContext` 取 `ItemEmitter`，首次 `emitItemAdded(PlanItem)`、同 turn 后续 `emitItemUpdated(PlanItem)`（同一 item id 原地刷新）。
    - **不审批、不算写类工具**（无副作用，只发 item）。
    - 工具 description 精简（含「最多一步 in_progress」约束），详细使用规则放 system prompt。
  - 新增 system prompt「计划使用规则」段（见 Task 3，**核心**）。
  - 把 `update_plan` 注册进 `ToolRegistry`，并在 `CapabilityCatalogSyncService` / `CapabilityAliasDictionary` 补中文别名（计划 / 任务清单 / 待办 / 步骤 / 规划）。能力暴露模式设为**默认 VISIBLE，不 deferred**（计划是核心能力，不应让模型先 tool_search 才能用）。
  - 计划落在已有 `bq_items`（通过 `ConversationEventRecorder`），**不新增数据库表**；当前计划 = thread 内最新的 plan item。
- **桌面端**
  - `protocol/ThreadModels.kt`：新增 `ThreadItem.Plan` data class（含 steps[description, status, activeForm]）+ `ThreadItemSerializer` 的 `"plan"` 分支。
  - `state/UiModels.kt`：新增 `PlanUiState`（当前计划 + 展开/收起状态），挂进 `AppState`。
  - `state/ChatReducer.kt`：处理 plan item 的 `item/added` / `item/updated`，更新 `PlanUiState`（最新计划原地覆盖，**不进 `messages` 列表**）；**全部 completed 时隐藏进度区**（借鉴 Claude Code 的"全完成清空"）。
  - **新增右侧固定运行面板（位置已定，见 §3-D3）**，竖向堆叠三段：
    - **进度**：步骤清单（○ pending / ◐ in_progress / ● completed），进行中的步骤显示 activeForm、蓝色高亮；有计划时自动展开。
    - **环境信息**：工作目录 / 沙箱模式 / Provider / 模型 / 上下文用量 —— **收编原输入栏底部状态 chip**，避免和右侧重复。
    - **来源**：本地工具 / MCP server / Skill / 长期记忆 / Lucene 能力检索（映射 BaBiQ 的能力与上下文来源）。
  - **收起态**：面板可整体收起，右上角保留「◐ 计划进行中 · N/M ▸」提醒胶囊（点击恢复面板），主列变宽。
  - `thread/load` 恢复历史会话时，从 item 流取最新 plan item 还原进度区。
- **测试**
  - 后端：`UpdatePlanToolTest`（全量覆盖 / 步骤状态 / activeForm / 首发 added 续发 updated / emitter 调用）、`PlanItem` JSON round-trip 测试、`update_plan` 不触发审批 / 不算写类工具的回归、`AgentLoopLineCountTest` 不退化。
  - 桌面端：`ThreadModels` plan 解码测试、`ChatReducer` plan 原地更新 + 全完成隐藏测试、计划面板 Compose 渲染测试。
- **原型（已完成，见 §3-D4）**：Figma 已出 `P3 12 会话-运行面板`（展开态，节点 `134:2`）、`P3 13 会话-运行面板（收起）`（节点 `154:2`），并在「00 交互总览-P3」（节点 `35:3`）补 2 张索引卡。

### 2.2 不做

- **不写复杂度检测代码**——"简单任务不出现"完全靠 system prompt（见 §3-D1）。
- **不做** plan.md / artifact 文档查看器侧窗（用户圈的第二块）——独立特性，优先级低，留作后续。
- **不做** `ReasoningItem` 的接通（也是 P1-1 占位，但属"推理过程展示"，与计划是两件事）。
- **不新增数据库表**、不写 Flyway migration（计划复用 `bq_items`）。
- **不做** planId 增量补丁（采用全量覆盖，对齐 Codex + Claude Code）。
- **不做**用户手动编辑计划步骤（P4 先做"模型产出 + 只读展示"）。
- **不升级** Spring AI / Spring AI Alibaba 版本；**不引入** 多 Agent / 子任务派发。

---

## 3. 待确认决策

> D1 / D2 双源印证；D3 / D4 已通过 Figma 原型确认。本节四项决策均已定，进入实现只剩 §8 的 P3 总体验收门禁。

### D1：计划由「工具」产生 + "简单任务不出现"靠 prompt —— ✅ 双源印证，推荐确定

- **Codex**：`update_plan` 工具 + `base_instructions/default.md` 第 56 行「Do not use plans for simple or single-step queries」+ 第 62-70 行「Use a plan when…」。
- **Claude Code**：`TodoWrite` 工具 + `prompt.ts` 的「When NOT to Use … only one trivial task … better off just doing the task directly」。
- **两家都没有任何代码判断任务复杂度**——面板出不出现，取决于模型要不要调工具，由 prompt 引导。
- **BaBiQ 适配**：新增 `update_plan` 工具 + 把使用规则写进 system prompt。**明确不写复杂度检测逻辑。**

### D2：状态模型 3 态 + 全量覆盖 + 双形式 —— ✅ 双源印证，推荐确定

- **状态**：`pending` / `in_progress` / `completed`（Codex `StepStatus`、Claude Code `TodoStatusSchema` 完全一致）。
- **全量覆盖**：每次调用携带完整列表，最新一份生效（两家都这样，不做增量）。
- **双形式（借鉴 Claude Code）**：`description`（祈使「运行测试」）+ 可选 `activeForm`（进行时「正在运行测试」），进行中的步骤显示 activeForm。Codex 只有单一 `step`，Claude Code 多了 activeForm——**推荐采纳 activeForm，面板更生动**。
- **全部完成后隐藏**（借鉴 Claude Code `newTodos = allDone ? [] : todos`）：计划只在进行中占位，做完自动消失。
- 是否需要额外状态（blocked/skipped）？**推荐：先只做 3 态**，够覆盖。

### D3：UI 位置 —— ✅ 已定（右侧固定多段面板 + 可收起提醒胶囊）

用户用其实际在用的 Codex 桌面端运行时截图佐证：进度应放**右侧固定面板**，且面板天然是**竖向堆叠多段（进度 / 环境信息 / 来源）**。这同时解决了"内联会被流式输出顶走"的问题。最终方案：

- **右侧固定运行面板**（不随对话流滚动），竖向堆叠三段：**进度 + 环境信息 + 来源**。
- **进度**为主角（计划步骤清单），有计划时自动展开。
- **环境信息**收编原输入栏底部状态 chip（工作目录/沙箱/Provider/模型/上下文），避免和右侧重复。
- **来源**映射 BaBiQ 的能力与上下文来源（本地工具/MCP/Skill/长期记忆/Lucene 能力检索）。
- 面板**可整体收起**，右上角保留「◐ 计划进行中 · N/M ▸」提醒胶囊（即用户要的"收起后仍提醒计划在进行中"）；收起后主列变宽。
- 交互闭环：**有计划自动展开 → 可收起留提醒胶囊 → 全部完成自动隐藏**。

> 注：原先推荐的"A 顶部 sticky 卡"被否决，因为用户的真实参照物（Codex 桌面端）就是右侧多段面板；右侧方案也彻底规避流式滚走问题。

### D4：是否先画 Figma 原型 —— ✅ 已完成

已先出原型再实现（符合 BaBiQ 先原型后实现习惯）。Figma `frTp55zgrKf4NAWxn6LdI7` 页 `35:2` 已新增：

- **P3 12 会话-运行面板**（节点 `134:2`）：展开态，右侧 进度 / 环境信息 / 来源 三段。
- **P3 13 会话-运行面板（收起）**（节点 `154:2`）：收起态，右上角 `◐ 计划进行中 3/5 展开▸` 提醒胶囊，主列变宽。
- 「00 交互总览-P3」（节点 `35:3`）补 2 张索引卡（运行面板·进度 / 运行面板·收起）+ 更新主线说明。

---

## 4. 实施任务（决策确认后细化）

> 以下任务基于 §3 已定决策（工具 / 3 态 + 全量 + activeForm / 右侧固定多段面板 + 可收起胶囊 / 原型已出）。

### Task 0: 原型补屏 —— ✅ 已完成

**Files:** Figma `frTp55zgrKf4NAWxn6LdI7` 页 `35:2`

- [x] 新增 `P3 12 会话-运行面板`（节点 `134:2`）：右侧 进度（○/◐/● + activeForm 蓝色高亮）/ 环境信息 / 来源 三段。
- [x] 新增 `P3 13 会话-运行面板（收起）`（节点 `154:2`）：右上角提醒胶囊 + 主列变宽。
- [x] 「00 交互总览-P3」（节点 `35:3`）补 2 张索引卡 + 更新主线说明。
- [x] 用户审核截图通过。

---

### Task 1: 扩展 `PlanItem` 协议 schema（后端）

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/items/PlanItem.java`
- Test: 新增 `PlanItemJsonTest`（或并入 `ThreadItemJsonTest`）

**Steps:**
- [ ] `PlanStep` 加 `status`（受约束字符串或枚举：`pending`/`in_progress`/`completed`）+ 可选 `activeForm`，带中文教学注释。
- [ ] `PlanItem` 的 `goal` / `reasoning` 改可选；**不加 planId**。
- [ ] JSON round-trip 测试：含 status + activeForm 的 plan item 正确序列化，`type="plan"`。
- [ ] 确认 `ThreadItem` sealed 注册不变（已注册）。

**Commit：** `feat(p4): 扩展 PlanItem 协议补步骤状态与进行时文案`

---

### Task 2: 实现 `UpdatePlanTool`（后端）

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/tool/impl/UpdatePlanTool.java`
- Test: `backend/src/test/java/.../UpdatePlanToolTest.java`

**Steps:**
- [ ] 写失败测试：调用 `update_plan` 传完整计划 → 从 toolContext 取 emitter → 首次 `emitItemAdded(PlanItem)`；同 turn 再次调用 → `emitItemUpdated(PlanItem)`（同 item id，全量覆盖）；status/activeForm 正确写入；返回模型一段简短确认文本（类似 Codex `"Plan updated"` / Claude Code `"Todos have been modified successfully…"`）。
- [ ] 实现 `@Tool(name="update_plan", description="…最多一步 in_progress…")`，参数为完整 steps 列表（含 status + 可选 activeForm）+ 可选 explanation/reasoning。
- [ ] 当前 plan item id 的跟踪：放在 `TurnObservationContext` 或 toolContext（同 turn 内复用，实现"原地更新"）。
- [ ] 确认 `update_plan` **不在** `ReActStrategy.buildHitlHook` 审批名单、**不在** `BaBiQSandboxInterceptor.WRITE_TOOLS`。
- [ ] description 中英双语 + 中文别名（计划 / 任务清单 / 待办 / 步骤 / 规划）。

**Commit：** `feat(p4): 实现 update_plan 工具发出计划 item`

---

### Task 3: system prompt 计划规则 + 能力注册（后端，**核心**）

**Files:**
- Modify: `SystemPromptSecurityRule`（或新增 plan 规则段，挂进 ReactAgent systemPrompt）
- Modify: `ToolRegistry` / `CapabilityCatalogSyncService` / `CapabilityAliasDictionary`

**Steps:**
- [ ] system prompt 新增「计划使用规则」段，**翻译并融合 Codex + Claude Code 的规则**：
  - 何时用：非平凡多步任务 / 有先后依赖的阶段 / 用户一次让你做多件事 / 用户明确要 TODO。
  - **何时不用：单步 / 平凡 / 纯咨询 / 能立即完成的任务——直接做，不要建计划。**
  - 任何时候**最多一步 in_progress**；完成才标 completed（测试没过/部分完成/有错误不标）。
  - **`update_plan` 后不要在正文重复整份计划**——面板已展示，只总结这次改了什么。
  - 保持现有安全规则不变。
- [ ] **明确不写任何复杂度检测代码**——出不出现由模型按 prompt 决定（注释中说明依据 Codex + Claude Code）。
- [ ] `update_plan` 进 `ToolRegistry`，能力暴露默认 **VISIBLE（不 defer）**。
- [ ] `CapabilityAliasDictionary` 补 `plan` 类别中文别名，补对应测试用例。
- [ ] 回归：`CapabilityCatalogSyncServiceTest` / `LuceneCapabilitySearchServiceTest`（中文 query「计划」「待办」命中 `update_plan`）。

**Commit：** `feat(p4): 加入计划使用 system 规则与能力注册`

---

### Task 4: 桌面端协议 + 状态（desktop）

**Files:**
- Modify: `desktop/.../protocol/ThreadModels.kt`（加 `ThreadItem.Plan` + serializer 分支）
- Modify: `desktop/.../state/UiModels.kt`（加 `PlanUiState` + 挂进 `AppState`）
- Modify: `desktop/.../state/ChatReducer.kt`（处理 plan item/added + item/updated + 全完成隐藏）
- Test: `ThreadHistoryModelsTest` / `ChatReducerTest`（或新增）

**Steps:**
- [ ] `ThreadItem.Plan`（id/type/goal?/steps[description, status, activeForm?]/reasoning?）+ `ThreadItemSerializer` 加 `"plan"` 分支。
- [ ] `PlanUiState`（current plan + 是否折叠），加进 `AppState`，在 `newChat` / `openThread` 正确重置 / 恢复。
- [ ] `ChatReducer`：plan item **不进 `messages`**，进 `PlanUiState`；`item/updated` 原地覆盖；**全部 completed 时清空/隐藏**。
- [ ] `thread/load` 恢复：从 items 取最新 plan 还原面板。
- [ ] 测试：plan 解码（含 activeForm）/ 原地更新 / 全完成隐藏 / 切会话重置。

**Commit：** `feat(p4): 桌面端接入计划协议与状态`

---

### Task 5: 右侧运行面板 UI（desktop）

**Files:**
- Create: `desktop/.../ui/runtime/RunPanel.kt`（或在现有 `RuntimeDetailsPanel.kt` 基础上扩展）
- Create: `desktop/.../ui/runtime/PlanSection.kt`（进度区）
- Modify: `ChatScreen.kt` / `AppShell.kt` 挂载右侧面板；`Composer.kt` 去掉已收编到「环境信息」的底部 chip
- Test: `PlanSectionTest` / `RunPanelTest`（Compose 渲染）

**Steps:**
- [ ] 右侧固定面板，竖向堆叠三段：**进度 / 环境信息 / 来源**（对齐原型 `134:2`）。
- [ ] 进度区：步骤清单（○ pending / ◐ in_progress / ● completed）+ 进行中步骤显示 activeForm 蓝色高亮。
- [ ] 环境信息区：工作目录/沙箱/Provider/模型/上下文（收编原底部 chip）。来源区：本地工具/MCP/Skill/长期记忆/Lucene。
- [ ] **收起态**：整体可收起 → 右上角「◐ 计划进行中 · N/M ▸」提醒胶囊（对齐原型 `154:2`），点击恢复；主列变宽。
- [ ] 有计划自动展开进度区；全部 completed 时进度区与提醒一起隐藏。
- [ ] Compose 测试：3 态图标 + activeForm / 收起-展开 / 提醒胶囊 / 空态与全完成隐藏 / 环境与来源数据绑定。

**Commit：** `feat(p4): 实现右侧运行面板与计划进度区`

---

### Task 6: 端到端验证 + 文档同步

**Files:** 验证 + `CLAUDE.md` / `AGENTS.md` /（可选）`learn/`

**Steps:**
- [ ] 后端专项 + `clean verify`；桌面端 `gradlew.bat test` + `run` 烟测。
- [ ] 人工烟测对比：
  - 简单任务（如「README 是什么」）→ **不应**出现计划面板。
  - 复杂多步任务（如「分 3 步重构 X 并跑测试」）→ 模型调 `update_plan`，面板出现并随推进更新 status，全完成后隐藏。
- [ ] 文档：CLAUDE.md / AGENTS.md 当前检查点；可补 `learn/04-walkthroughs/04-plan-todo-trace.md` 走读。

**Commit：** `docs(p4): 同步计划可视化状态`

---

## 5. 验证清单（定稿后补具体命令）

```powershell
# 后端专项
cd backend
.\mvnw.cmd "-Dtest=UpdatePlanToolTest,PlanItemJsonTest,CapabilityAliasDictionaryTest,CapabilityCatalogSyncServiceTest,ReActStrategyTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

# 桌面端
cd ..\desktop
.\gradlew.bat test --tests "*ThreadHistoryModelsTest" --tests "*ChatReducerTest" --tests "*PlanPanelTest"
.\gradlew.bat test

# 确认 update_plan 不在审批 / 写类工具名单、且无复杂度检测代码
cd ..\backend
rg -n "update_plan" src/main/java
```

人工烟测必须覆盖「简单任务不出现 / 复杂任务出现并更新 / 全完成隐藏」三种。

---

## 6. 风险与处理

| 风险 | 严重度 | 处理 |
|---|---|---|
| 模型不主动调 `update_plan` | 中 | system prompt 引导（融合 Codex + Claude Code 规则）；但不强制（简单任务不该有计划） |
| 模型对简单任务也建计划 | 中 | prompt 明确「单步/平凡/纯咨询不要建计划，直接做」；人工烟测覆盖 |
| 扩展 `PlanItem` schema 影响已发布协议 | 低 | `PlanItem` 是从未发出的占位 record，无存量数据 |
| `AgentLoop.invoke` 50 行红线 | 中 | 计划产出走工具，**不进主循环**；`AgentLoopLineCountTest` 不受影响 |
| plan item 落进 `messages` 导致聊天流堆卡 | 中 | reducer 明确放进 `PlanUiState` 而非 `messages`；测试覆盖 |
| 右侧"运行面板"与现有"运行详情"面板职责重叠 | 中 | 实现时二选一收口：把现有"运行详情"审计 tab 并入右侧面板，或运行面板只做 进度/环境/来源、审计仍走原"运行详情"。定稿前明确，避免两个右侧面板 |
| 收编底部 chip 后，老用户找不到上下文/沙箱信息 | 低 | 信息平移到右侧"环境信息"区，收起态胶囊 + 标题仍可见关键状态；首次引导说明 |
| `item/updated` 同 item id 跟踪在并发 turn 下错乱 | 低 | BaBiQ 单进程不并发跑多 turn；plan item id 绑定 turn 上下文 |

---

## 7. 完成标准（草案，定稿后勾选）

- [ ] `PlanItem`/`PlanStep` 已补 status + 可选 activeForm，JSON round-trip 测试通过
- [ ] `UpdatePlanTool` 已实现（全量覆盖，首发 added / 续发 updated），单测通过
- [ ] `update_plan` 默认 VISIBLE（不 defer）、有中文别名、**不触发审批 / 不算写类工具**
- [ ] system prompt 已加计划使用规则（含"简单不用 / 一个 in_progress / 不重复正文"），**无任何复杂度检测代码**
- [ ] 桌面端 `ThreadItem.Plan` + reducer + `PlanUiState` 完成，plan 不进 messages，全完成隐藏
- [ ] 右侧运行面板三段（进度/环境信息/来源）+ 收起提醒胶囊渲染完成，对齐原型 `134:2` / `154:2`，Compose 测试通过
- [ ] 底部 chip 已收编到「环境信息」区，无重复展示
- [ ] `thread/load` 能恢复历史计划
- [ ] 后端 `clean verify` + 桌面端 `gradlew.bat test` 全绿，`AgentLoopLineCountTest` 不退化
- [ ] 人工烟测：简单任务不出现 / 复杂任务出现并更新 / 全完成隐藏
- [ ] CLAUDE.md / AGENTS.md 当前检查点同步
- [ ] 中文 conventional commit，未 push

---

## 8. 阶段门禁（重要）

按 `CLAUDE.md` §2 / §3：

- 当前检查点是「**下一步：进行 P3 总体验收复盘；用户确认后再编写 P4 或新的专项增强详细计划**」。
- §3 决策已全部确认（D1/D2 双源印证、D3/D4 原型确认），**剩余唯一门禁是 P3 总体验收通过**，之后方可进入实现。
- 本特性是"协议 → AgentLoop → 桌面 UI"纵向新能力，**不得混入任何 P3 收口提交**。

---

## 9. 与现有协议 / 能力的衔接

- 复用：`ItemEmitter`（发 item）、`ConversationEventRecorder`（落 `bq_items`）、`item/added` + `item/updated` 协议事件、`ToolRegistry` + 能力目录 + Lucene 中文搜索、`CapabilityExposurePlanner`（VISIBLE）、SAA `ReactAgent.systemPrompt`。
- 兑现：P1-1 预留的 `PlanItem` 扩展点（"后续前端可直接基于 steps 渲染任务进度"）。
- 不影响：上下文工程（P3-1~3a）、长期记忆（P3-4）、能力检索（P3-5/5a）。

---

## 10. 下一步

1. ✅ §3 决策已定（D1/D2 双源印证，D3/D4 原型确认）。
2. 完成 P3 总体验收复盘（唯一剩余门禁）。
3. 用 `superpowers:writing-plans` 把本草案定稿为正式 plan，补 §5 具体命令和 §4 细化步骤；定稿时确认 §6 中「右侧运行面板 vs 现有运行详情面板」如何收口。
4. 编写 `codex-handoff.md`。

---

## 11. 参考来源（已实读核对）

### Codex `update_plan`（`E:\wzx\codex`）
- `codex-rs/protocol/src/plan_tool.rs`：`UpdatePlanArgs { explanation?, plan: Vec<PlanItemArg> }`、`PlanItemArg { step, status }`、`StepStatus { Pending, InProgress, Completed }`。**全量重发**。
- `codex-rs/core/src/tools/handlers/plan_spec.rs`：工具 description「Updates the task plan… At most one step can be in_progress at a time.」
- `codex-rs/core/src/tools/handlers/plan.rs`：handler 解析参数 → 发 `PlanUpdate` 事件 → 回 `"Plan updated"`，**无副作用、不审批**。
- `codex-rs/protocol/src/prompts/base_instructions/default.md`（§Planning，第 52-70 行）：何时用/何时不用计划；第 56 行「Do not use plans for simple or single-step queries」；第 58 行「不要在正文重复计划」；第 60 行「恰好一个 in_progress」。

### Claude Code `TodoWrite`（`E:\wzx\claude-code`）
- `src/utils/todo/types.ts`：`TodoItem { content, status, activeForm }`，`status ∈ pending/in_progress/completed`。**content 祈使 + activeForm 进行时**。
- `src/tools/TodoWriteTool/TodoWriteTool.ts`：入参 `{ todos }` 全量；`checkPermissions → allow`（不审批）；**全完成 `newTodos = []`（清空）**；`renderToolUseMessage → null`（工具调用隐藏，面板单独渲染）；`shouldDefer: true`（Claude Code 按需加载——BaBiQ 选择不 defer）。
- `src/tools/TodoWriteTool/prompt.ts`：详细「When to Use / When NOT to Use」+ 8 个正反例 + 「Exactly ONE task in_progress」+「完成才标 completed」。

### Spring AI / Spring AI Alibaba（Context7 核对）
- Spring AI：`@Tool(description=…)` + `@ToolParam(description=…, required=…)` + `ToolContext`；Java `enum` 自动生成 string-enum schema，`List<对象>` 自动生成 array-of-object schema —— 和 BaBiQ 现有工具同一套。
- Spring AI Alibaba：`ReactAgent.builder().systemPrompt(…)` / `.instruction(…)` / `.tools(…)`；计划使用规则放 systemPrompt（BaBiQ 已用 `SystemPromptSecurityRule.PROMPT`）。
