# P4 Plan/Todo 可视化 — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p4-plan-todo-visualization\plan.md`

## 当前状态

- **计划已定稿为正式执行计划**（2026-05-30）。§3 决策（D1-D5）全部确认。
- **Figma 原型已出并经用户确认**（页 `35:2` "P3 上下文与记忆平台原型"）：
  - `P3 12 会话-运行面板`（节点 `134:2`）：展开态，右侧固定面板竖向三段 **进度 / 环境信息 / 来源**。
  - `P3 13 会话-运行面板（收起）`（节点 `154:2`）：收起态，右上角 `◐ 计划进行中 · 3/5 展开▸` 提醒胶囊，主列变宽。
  - `00 交互总览-P3`（节点 `35:3`）已补 2 张索引卡 + 更新主线说明。
- **代码尚未实现**。这是接下来要做的事。
- **唯一启动门禁：先完成 P3 总体验收**（CLAUDE.md §3）。验收通过前不得开始 P4 实现，且 P4 任何提交不得混入 P3 收口。

## 一句话目标

让模型用 `update_plan` 工具**全量重发**多步任务计划（步骤 + 状态），后端发 `PlanItem` 协议事件，桌面端在**右侧固定运行面板的「进度」区**渲染可勾选、原地更新、全完成自动隐藏的进度清单。**简单任务不出现、复杂任务才出现，完全由 system prompt 约束模型，绝不写复杂度检测代码。**

## 必读入口

1. `E:\BaBiQ\CLAUDE.md`（项目级纪律，特别是 §3 阶段边界、§4 实现规则、§4.1 工具命名/searchText 规则、§7 Git 规则）
2. `E:\BaBiQ\docs\superpowers\plans\p3-master.md` / `p3-task-index.md`
3. `E:\BaBiQ\docs\superpowers\plans\p4-plan-todo-visualization\plan.md`（**本阶段完整计划**）
4. 业界源码参照（设计依据，实施前可复看）：
   - Codex：`E:\wzx\codex\codex-rs\protocol\src\plan_tool.rs`、`core\src\tools\handlers\plan_spec.rs`、`plan.rs`、`protocol\src\prompts\base_instructions\default.md`（§Planning，第 52-70 行）
   - Claude Code：`E:\wzx\claude-code\src\tools\TodoWriteTool\TodoWriteTool.ts`、`prompt.ts`、`src\utils\todo\types.ts`
5. BaBiQ 现有挂点代码：
   - `backend/.../conversation/items/PlanItem.java`（已存在占位 record，要扩展）
   - `backend/.../conversation/items/ThreadItem.java`（已注册 `type="plan"`）
   - `backend/.../agent/ReActStrategy.java`（`buildHitlHook` 审批名单、`systemPrompt`、`.tools(...)` 装配）
   - `backend/.../tool/impl/WriteFileTool.java`（**参照样板**：工具如何从 toolContext 取 emitter 发 item）
   - `backend/.../interceptor/BaBiQSandboxInterceptor.java`（`WRITE_TOOLS` 集合）
   - `backend/.../security/SystemPromptSecurityRule.java`（system prompt 挂点）
   - `backend/.../capability/CapabilityAliasDictionary.java` / `CapabilityCatalogSyncService.java`（中文别名）
   - `desktop/.../protocol/ThreadModels.kt`（`ThreadItem` sealed + `ThreadItemSerializer`）
   - `desktop/.../state/UiModels.kt` / `ChatReducer.kt` / `ui/runtime/RuntimeDetailsPanel.kt`

## 为什么做（完整论证见 plan §1）

1. **协议占位、后端没发、前端没画**——`PlanItem` 在 P1-1 就定义并注册 `type="plan"`，Javadoc 写明"后续前端可直接基于 steps 渲染任务进度"，但**至今无任何代码发出它**；桌面端 `ThreadItem` 没有 `Plan` 变体（会落进 `Unknown`）。补它是兑现 P1-1 留的扩展点。
2. **`PlanStep` 缺 `status`**——当前只有 `order` + `description`，做进度勾选必须补 `status`。
3. **Codex + Claude Code 都有此能力**（`update_plan` / `TodoWrite`），对 Codex-like 学习项目这是显眼短板。

## 核心设计要点（Codex + Claude Code 双源印证）

| 维度 | 结论 | 依据 |
|---|---|---|
| 产出方式 | 本地工具 `update_plan`（不是 Hook、不是解析文本） | 两家都是工具 |
| 数据更新 | **全量重发**整份计划，最新一份生效（**不做 planId 增量**） | 两家都是全量 |
| 步骤状态 | `pending` / `in_progress` / `completed` 三态 | Codex `StepStatus` / Claude `TodoStatusSchema` 一致 |
| 步骤文本 | `description`（祈使）+ 可选 `activeForm`（进行时，进行中步骤显示） | 借鉴 Claude Code 双形式 |
| 约束 | **任何时候最多一步 in_progress**；完成才标 completed | 两家都强调 |
| 简单任务不出现 | **纯 system prompt 约束模型**，无任何代码判断复杂度 | 两家都无复杂度检测代码 |
| 别在正文复述 | `update_plan` 后助手只总结改动，不重复整份计划（面板已展示） | Codex base_instructions 第 58 行 |
| 全部完成 | 面板/进度区自动隐藏 | 借鉴 Claude Code `newTodos = allDone ? [] : todos` |
| 副作用 | 无，**不审批、不算写类工具** | 两家都 `allow` / 不审批 |
| 工具可见性 | **默认 VISIBLE，不 deferred**（计划是核心，别让模型先 tool_search） | Codex `defer_loading: None` |
| 工具调用展示 | 隐藏工具调用本身，**plan item 进右侧面板，不进聊天流** | 借鉴 Claude Code `renderToolUseMessage → null` |

## UI 决策（§3-D3 / D5，原型已确认）

- **右侧固定运行面板**（不随对话流滚走），竖向堆叠：
  - **实时层（常驻顶部）**：进度（计划清单，主角）+ 环境信息（收编原底部 chip：工作目录/沙箱/Provider/模型/上下文）+ 来源（本地工具/MCP/Skill/长期记忆/Lucene）。
  - **审计层**：现有 `RuntimeDetailsPanel` 的 快照/工具/记忆/能力搜索 tab 并入同一面板（**不要并列两个右侧面板**）。
- 收起态：整体收起 → 右上角 `◐ 计划进行中 · N/M ▸` 提醒胶囊，主列变宽。
- 交互闭环：**有计划自动展开 → 可收起留提醒胶囊 → 全完成自动隐藏**。

## 关键代码挂点

新增 / 修改：

| 文件 | 动作 | 原因 |
|---|---|---|
| `backend/.../conversation/items/PlanItem.java` | 修改 | `PlanStep` 补 `status` + 可选 `activeForm`；`goal`/`reasoning` 改可选；**不加 planId** |
| `backend/.../tool/impl/UpdatePlanTool.java` | **新增** | `@Tool name="update_plan"`，全量入参，从 toolContext 取 emitter 首发 `item/added`、续发 `item/updated` |
| `backend/.../security/SystemPromptSecurityRule.java`（或新增规则段） | 修改 | 加「计划使用规则」：何时用/何时不用、最多一步 in_progress、别在正文复述。**无复杂度检测代码** |
| `backend/.../agent/ReActStrategy.java` | 修改 | 把 `update_plan` 装进 `.tools(...)`；确认**不在** `buildHitlHook` 审批名单 |
| `backend/.../capability/CapabilityAliasDictionary.java` + `CapabilityCatalogSyncService.java` | 修改 | 补 `plan` 中文别名（计划/任务清单/待办/步骤/规划）；能力暴露默认 VISIBLE |
| `desktop/.../protocol/ThreadModels.kt` | 修改 | 新增 `ThreadItem.Plan` data class + `ThreadItemSerializer` 的 `"plan"` 分支 |
| `desktop/.../state/UiModels.kt` | 修改 | 新增 `PlanUiState`（计划 + 展开/收起态），挂进 `AppState` |
| `desktop/.../state/ChatReducer.kt` | 修改 | plan item 进 `PlanUiState` 不进 `messages`；`item/updated` 原地覆盖；全完成隐藏 |
| `desktop/.../ui/runtime/RunPanel.kt` / `PlanSection.kt` | **新增** | 右侧面板三段 + 收起胶囊；审计层并入现有 `RuntimeDetailsPanel` |
| `desktop/.../ui/chat/Composer.kt` | 修改 | 去掉已收编到「环境信息」的底部状态 chip |
| 后端 / 桌面端测试 | **新增** | 见 plan §2.1 测试清单 |

不动：

- **不新增数据库表 / 不写 Flyway migration**（计划复用 `bq_items` payload，当前计划 = thread 内最新 plan item）。
- 不引入 planId 增量、不做用户手动编辑步骤、不接 `ReasoningItem`、不做 plan.md 文档查看器。
- 不升级 Spring AI / Spring AI Alibaba 版本。
- `AgentLoop.invoke` 主循环不动（计划产出走工具，不进主循环；`AgentLoopLineCountTest` 不受影响）。

## 执行规则

1. **先确认 P3 总体验收已通过**，再开始（CLAUDE.md §3 门禁）。P4 提交不得混入 P3 收口。
2. 严格按 plan §4 的 Task 1-6 顺序执行（Task 0 原型已完成）。
3. 用 `superpowers:test-driven-development`，每个 Task 先写失败测试再实现。
4. **`update_plan` 必须不审批、不算写类工具**：确认它不在 `ReActStrategy.buildHitlHook` 名单、不在 `BaBiQSandboxInterceptor.WRITE_TOOLS`。
5. **"简单任务不出现"只靠 system prompt**——任何"判断复杂度"的 Java/Kotlin 代码都是错的，禁止。
6. 工具 `name="update_plan"` 必须 ASCII；中文检索靠 `displayName`/`description`/`searchText` 别名（CLAUDE.md §4.1）。
7. plan item **不得进 `messages` 列表**——必须进 `PlanUiState`，否则会随聊天流堆卡/滚走。
8. 新增/改动生产代码必须补中文教学注释（CLAUDE.md §4）。
9. 每个 Task 完成用中文 conventional commit（`feat(p4): ...` / `docs(p4): ...`）。**不主动 push**。
10. 完成后更新 `AGENTS.md`、`CLAUDE.md` 当前检查点、`p3-task-index.md`（或新建 p4 索引）。
11. 不允许 `@Disabled` 占位测试用例。

## 关键默认参数

| 参数 | 值 | 说明 |
|---|---|---|
| 步骤状态枚举 | `pending` / `in_progress` / `completed` | 对齐 Codex + Claude Code |
| in_progress 数量约束 | 任意时刻 ≤ 1 | 工具 description + system prompt 双重声明 |
| 工具可见性 | VISIBLE（不 defer） | 计划是核心能力 |
| plan 持久化 | 复用 `bq_items`，无新表 | 当前计划 = thread 内最新 plan item |
| 更新语义 | 全量覆盖；首发 `item/added`、续发 `item/updated`（同 item id） | plan item id 绑定当前 turn 上下文 |
| 全完成 | 进度区 + 提醒胶囊一起隐藏 | 借鉴 Claude Code 全完成清空 |

## 最终验收命令

```powershell
cd E:\BaBiQ\backend

# 1. 后端专项
.\mvnw.cmd "-Dtest=UpdatePlanToolTest,PlanItemJsonTest,CapabilityAliasDictionaryTest,CapabilityCatalogSyncServiceTest,ReActStrategyTest,AgentLoopLineCountTest" test

# 2. 后端全量
.\mvnw.cmd clean verify

# 3. 桌面端
cd ..\desktop
.\gradlew.bat test --tests "*ThreadHistoryModelsTest" --tests "*ChatReducerTest" --tests "*PlanSectionTest" --tests "*RunPanelTest"
.\gradlew.bat test

# 4. 确认 update_plan 不审批 / 不算写类工具 / 无复杂度检测代码
cd ..\backend
rg -n "update_plan" src/main/java
```

人工烟测必须覆盖三种：
- 简单任务（如「README 是什么」）→ **不应**出现计划面板。
- 复杂多步任务（如「分 3 步重构 X 并跑测试」）→ 模型调 `update_plan`，进度区出现并随推进更新 status。
- 全部 completed → 进度区与提醒胶囊一起隐藏。

## 完成报告必须包含

- Task 1-6 逐条完成状态（✅/❌）。
- 跑过的验证命令和**实际输出**（不是预期）。
- `update_plan` 不在审批名单 / 不在 `WRITE_TOOLS` 的证据。
- **无复杂度检测代码**的说明（"简单不出现"仅由 system prompt 实现）。
- plan item 进 `PlanUiState` 而非 `messages` 的测试证据。
- 三种人工烟测结果（简单不出现 / 复杂出现并更新 / 全完成隐藏）。
- 右侧面板与现有"运行详情"合并（§3-D5）的实现说明。
- `AgentLoopLineCountTest` 不退化证据。
- 中文 conventional commit 列表；明确说明未 push。

## 下一步

- P3 总体验收通过后启动本计划。
- 实现完成、`clean verify` 与桌面端测试全绿、人工烟测通过后，才可声称 P4 计划可视化完成。
- 后续如要做 plan.md/artifact 文档查看器、用户手动编辑计划、`ReasoningItem` 接通，均属新的专项，不混入本阶段。
