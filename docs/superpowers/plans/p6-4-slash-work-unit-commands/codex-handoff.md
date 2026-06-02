# P6-4 Slash 命令与命名工作容器 — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p6-4-slash-work-unit-commands\plan.md`（16 个 Task / 6 个 Chunk，含逐步 TDD）
> 上游：P6-1 子 Agent（`explorer`）、P6-2 flow 编排（`orchestrate_flow`）、P6-3 团队（`coordinate_team`）均已合并入 master。
> 锁定版本：Spring AI `1.1.6`、Spring AI Alibaba `1.1.2.3`（**不升级**）。

## 当前状态

- **代码已按 P6-4 语义落地（2026-06-02）**：slash `/编排`、`/团队` 由桌面端解析为 `executionIntent`，后端在 `turn/start` 路径确定性创建/复用 `WorkUnit` 并追加目标，slash 本身不自动执行 flow/team。
- **WorkUnit 事实源已落地**：新增 `bq_work_units`、`bq_work_unit_goals`、领域服务、SQLite repository、`workunit/list`、`workunit/remove` 和 `work_unit_manage` 工具。
- **显式启动归属已接入**：`work_unit_manage start` 会把 `goalId` 记入本轮 `TurnObservationContext`；`orchestrate_flow` / `coordinate_team` 读取后回写目标 running/completed/failed。
- **桌面端已接入**：右侧运行详情新增工作容器列表，容器不会进入聊天正文；已完成或空闲容器支持手动移除，运行中由后端拒绝。
- 这是在已有 P6-1/2/3 之上加一层**显式入口（slash）+ 命名可复用容器（WorkUnit）**；**复用现有执行引擎，不重写**。
- 技术可行性已 Context7 + 现有代码双确认（见下）。

## 本次完成证据

- 桌面 slash 解析与协议：`SlashCommandParser`、`ExecutionIntent.CreateWorkUnit`、`AgentClient.startTurn(..., executionIntent)`。
- 后端 slash 只建容器：`TurnStartHandler` 识别 `executionIntent.type=create_work_unit` 后发 `workUnit` item 并完成 turn，不提交模型执行。
- 后端持久化：`V16__work_unit_slash_commands.sql`、`WorkUnitEntity`、`WorkUnitGoalEntity`、`SQLiteWorkUnitRepository`。
- 后端管理与启动关联：`DefaultWorkUnitService`、`WorkUnitManageTool`、`WorkUnitContextKeys`、`FlowOrchestrationTool`、`TeamCoordinationTool`。
- 桌面右侧 UI：`ThreadItem.WorkUnit`、`WorkUnitUiState`、`WorkUnitSection`、`ChatController.removeWorkUnit`。

### 已通过的定向验证

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=WorkUnitHandlersTest" test
.\mvnw.cmd "-Dtest=FlowOrchestrationToolWorkUnitTest,TeamCoordinationToolWorkUnitTest,WorkUnitServiceTest" test
.\mvnw.cmd "-Dtest=WorkUnitManageToolTest" test
.\mvnw.cmd "-Dtest=FlowOrchestrationToolWorkUnitTest,TeamCoordinationToolWorkUnitTest,WorkUnitServiceTest,WorkUnitHandlersTest,WorkUnitManageToolTest" test
.\mvnw.cmd "-Dtest=WorkUnitSlashIntentIT" test

cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ThreadItemJsonTest.can parse work unit item" --tests "*ChatReducerTest.work unit item updates runtime state without adding chat message" --tests "*ChatReducerTest.removed work unit item disappears from runtime state" --tests "*ChatReducerTest.work unit state from history keeps visible units and ignores removed units" --tests "*WorkUnitSectionTest" --tests "*ChatControllerTest.removeWorkUnit calls backend and hides runtime item" --tests "*AgentClientTest.work unit interfaces can list and remove containers"
.\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*SlashCommandParserTest" --tests "*WorkUnitSectionTest"
```

以上命令均已 `BUILD SUCCESS`。最终全量验证以完成报告为准。

## 一句话目标

输入框提供 `/子代理`、`/编排 <名称>：<目标>`、`/团队 <名称>：<目标>`；`/编排`、`/团队` 只负责**创建或复用命名 `WorkUnit` 容器**并追加目标，容器进入 `待配置 / 待启动`，不自动执行。后续节点/成员职责、模型配置和开始执行复用已有编排详情 / 团队详情页面；用户正文**不写入 slash 控制语法**，`executionIntent` 只作容器创建意图。

## ⚠️ 审查修订要点（**优先于 plan 正文里任何"模型驱动"的旧表述**）

plan 已整体改为下面这套；落地时**以此为准**：

1. **slash 只创建/复用容器，不自动执行**：slash intent 是结构化输入（mode+name+goal 已定）。在 **`turn/start` 路径**调用 `WorkUnitService.createOrAppend(...)` 完成 create/reuse/append-goal，容器进入 `WAITING_CONFIG`；“待启动”只是该状态下的 UI/业务提示，不单独落库。**不要**让模型先调 `work_unit_manage` 才有容器，也不要让 slash 直接调 `orchestrate_flow` / `coordinate_team`。
2. **配置和启动复用已有详情页**：编排配置走 `P6 06/07/08`（编排详情 / 节点设置 / 编辑节点），团队配置和执行走 `P6 03/04/05`（团队协作 / 团队设置 / 执行分屏）。P6-4 原型只保留两个创建入口页。
3. **模型配置必须用户手动完成**：模型、Provider、高权限策略不允许主 Agent 自动改；主 Agent 只能按用户自然语言修改目标、节点职责或成员职责。
4. **goalId 只在显式启动阶段经 ToolContext 注入**：用户点击详情页“开始执行”或明确告诉主 Agent 启动后，`AgentLoop` build agent 才把 `goalId` 写入 `ToolContext`（新 key `CONTEXT_WORK_UNIT_GOAL_ID`），与现有 `CONTEXT_CWD` 注入同一处、同一套路；`FlowOrchestrationTool`/`TeamCoordinationTool` 回写 `markGoalRunning/Completed`。
5. **`work_unit_manage` 工具只用于自然语言管理**（修改目标/职责、running 时"补充 vs 排队"、追加新目标、启动、移除容器），**不是 slash 路径建容器的必经跳**。
6. **slash create-only 不注入模型指令**：slash 创建容器后直接完成 turn，不进入 AgentLoop，因此不需要 `WorkUnitIntentInstructionBuilder`；显式启动阶段才通过 ToolContext 关联 goalId。
7. **审批闸门必须保留**：用户显式启动 flow/team 时仍走 P6-2/P6-3 approve-once 弹窗——**补测试钉死**（启动时弹审批、批准后才执行）。
8. **名称唯一包事务**：create-or-reuse 用 `TransactionTemplate`（复用压缩链路同款）防 TOCTOU。

## 已验证的官方/现有机制（Context7 + 本地代码，直接用）

```java
// Spring AI 官方(Context7 reference 确认): @Tool 接 ToolContext, 服务端数据, 永不发给模型
@Tool(description="...") Customer get(Long id, ToolContext tc){ tc.getContext().get("tenantId"); }
// BaBiQ 现有同款(ReActStrategy line 207 / FlowOrchestrationTool line 305-308):
.toolContext(toolContext)                                  // ReactAgent.builder()
toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_CWD)   // 取 cwd/sandbox/observation
```

- `TurnExecutor.submit(...)` / `AgentLoop.invoke(...)` 已有 5/6 参重载 → 加 `executionIntent`（+ goalId）顺势。
- `ContextAssembler` 已有 `current_turn` 权威层（line 115）。
- `TeamCoordinationService.run(spec, parentToolContext)`、`FlowOrchestrationTool` 已收 ToolContext → 读 goalId 干净。
- `orchestrate_flow`/`coordinate_team`、`bq_orchestrations`(V14)/`bq_teams`(V15) 已存在 → **复用，零新 SAA API**。

## 必读入口

1. `E:\BaBiQ\CLAUDE.md`（§3 边界、§4 先查官方/薄封装、§4.1 工具命名/searchText、§5 验收、§7 Git、§8 汇报）。
2. `p6-4-slash-work-unit-commands/plan.md`（**完整 16 Task / §2 语义 / §3 文件结构 / §4 复核 / §5 风险**）。
3. 现有挂点（先读再改）：`agent/TurnExecutor.java`、`agent/AgentLoop.java`、`agent/ReActStrategy.java`（`.toolContext(...)` + `buildConfig`）、`context/ContextAssembler.java`（`current_turn` 层）、`context/ContextWindowRuntime.java`、`tool/impl/FlowOrchestrationTool.java`、`tool/impl/TeamCoordinationTool.java`、`sandbox/BaBiQSandboxInterceptor.java`（`CONTEXT_CWD` 注入处）、`api/method/TurnStartHandler.java`、`conversation/items/ThreadItem.java`。
4. 桌面：`ui/chat/Composer.kt`、`state/{ChatController,ChatReducer,UiModels}.kt`、`client/{AgentClient,AgentGateway}.kt`、`protocol/ProtocolJson.kt`（`ignoreUnknownKeys=true` 已开）、`ui/runtime/RunDetailsPanel.kt`。

## 范围（plan §1）

**做**：slash 解析 + 命令面板；`turn/start` 带 `executionIntent`；`WorkUnit`(TEAM/FLOW) 领域模型 + SQLite 事实源（`bq_work_units`/`bq_work_unit_goals`）；服务端确定性创建/复用容器；`work_unit_manage`（NL 管理）；`workunit/list`+`workunit/remove`；右侧多容器列表 + 待配置/待启动状态 + 跳转已有详情页 + 移除；显式启动时复用 `orchestrate_flow`/`coordinate_team`。

**不做**：自由连边图编辑器；重复实现新的编排/团队详情配置页；运行中逐工具审批 / 并发中断（沿用 approve-once）；UI 直接调 flow/team 绕过主 Agent；主 Agent 自动改模型/Provider/高权限策略；跨 thread 全局复用；升级 Spring AI/SAA；物理删除被移除容器的审计事实。

## 关键决策（plan §0/§2 + 本修订）

- 容器是命名可复用 `WorkUnit`（不是一次 turn）；目标是容器里的批次（`WorkUnitGoal`）。
- `/编排`、`/团队` 创建后停在待配置 / 待启动；启动必须由用户点击或明确自然语言触发。
- 模型配置、Provider、高权限策略属于用户手动配置区；Agent 只能辅助修改目标和职责文本。
- 同 thread 内 `removed=0` 的同 kind + 同归一化名称 work unit 优先复用；运行中容器只追加 pending goal，不替换当前目标。
- 移除只置 `removed=1`+`removed_at`，**不删**运行记录/工具调用/目标审计；被移除后同名 `/团队`/`/编排` 建新容器。
- 工具 `name` ASCII（`work_unit_manage`）；中文检索靠 displayName/description/searchText（§4.1，含 `团队 编排 工作容器 目标 追加 移除`）。

## TDD 任务顺序（plan Chunk 1→6，先红后绿）

- **Chunk 1**（桌面）：Task 1 `SlashCommandParser` → Task 2 命令面板/模式 chip → Task 3 `turn/start` 带 `executionIntent`（本地 optimistic 消息只显示目标文本）。
- **Chunk 2**（后端 intent）：Task 4 桌面结构化 `executionIntent` → Task 5 `TurnStartHandler` 接 intent **并服务端建容器/append-goal，状态为待配置** → Task 6 确认 slash create-only 不进入模型、不注入额外指令。
- **Chunk 3**（持久化/工具）：Task 7 `bq_work_units`/`bq_work_unit_goals`（V16，**SQL 中文注释 + `bq_schema_comments` + Entity 注释 + 覆盖测试**）→ Task 8 `WorkUnitService` 复用/队列 → Task 9 `work_unit_manage`（NL 管理）。
- **Chunk 4**（关联/接口）：Task 10 显式启动后 flow/team 从 **ToolContext 读 goalId** 回写状态 → Task 11 `workunit/list`+`workunit/remove`。
- **Chunk 5**（桌面 UI）：Task 12 协议/状态聚合（多 FLOW/TEAM 容器、待配置/待启动提示、removed 消失）→ Task 13 右侧 `WorkUnitSection` + 跳转已有详情页 + 移除。
- **Chunk 6**：Task 14 端到端 IT（`WorkUnitSlashIntentIT` + 桌面 `ChatControllerTest`）→ Task 15 文档同步 → Task 16 全量验证。

## 执行规则

1. 严格按 plan Chunk 1→6 / 每 Task 5 步 TDD；改生产代码补**中文教学注释**（CLAUDE.md §4）。
2. **slash 只创建容器 + 显式启动时 goalId 经 ToolContext**（审查修订 1/4）——这是本阶段最关键约束，别回退成"slash 后模型直接执行"或"模型先调 work_unit_manage 串 goalId"。
3. 新表 `bq_work_units`/`bq_work_unit_goals` 必须同步 SQL 中文注释 + `bq_schema_comments` + Entity 注释 + `SchemaCommentsCoverageTest`（CLAUDE.md §4 红线）。
4. **用户正文不含 slash 控制语法**：测试钉死 `bq_items` 的 user message = `input.text`，不是 slash 原文。
5. 复用现有 `orchestrate_flow`/`coordinate_team`，**不重写** P6-2/P6-3 引擎；仅在用户显式启动时进入执行链路，审批/沙箱/运行记录链路保留。
6. 工具 `name` ASCII；中文走 displayName/description/searchText。
7. 不升级 Spring AI/SAA；不引入新 SAA API；`AgentLoop.invoke` 若有行数约束测试不得退化。
8. 每 Task 中文 conventional commit（`feat(p6-4): ...` / `test(p6-4): ...`）。**不主动 push**。
9. 完成后更新 `CLAUDE.md`/`AGENTS.md` 检查点、`p6-master.md`、`p6-task-index`（如有）。
10. 没有新鲜证据不得声称完成（CLAUDE.md §8）；**禁止 `@Disabled` 占位**。

## 验收（plan §Task 16）

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=TurnStartHandlerTest,WorkUnitServiceTest,WorkUnitManageToolTest,WorkUnitHandlersTest,FlowOrchestrationToolWorkUnitTest,TeamCoordinationToolWorkUnitTest,SchemaCommentsCoverageTest,WorkUnitSlashIntentIT" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*SlashCommandParserTest" --tests "*SlashCommandMenuTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*WorkUnitSectionTest"
.\gradlew.bat test
```

- 通过标准：上述定向全绿；`clean verify` 全绿（含 IT）；桌面无回归；`SchemaCommentsCoverageTest` 过；`bq_items` user message 不含 slash 语法。
- 真实模型人工烟测（plan §Task 16 Step 5）：`/子代理` 不建 work unit；`/编排` / `/团队` 只创建或复用容器并停在待配置 / 待启动；进入已有详情页可配置职责、模型和启动；模型/Provider/高权限策略只能用户手动改；用户显式启动后才弹审批并执行；`/团队 前端验收组：…` 第二次复用同名并追加目标；多个团队/编排并发展示；已完成容器移除后从页面消失。

## 完成报告必须包含

- 每个 Task ✅/❌ + 跑过的命令与**实际输出**（非预期）。
- "slash 只创建/复用容器，不自动执行"的实现与测试证据。
- "显式启动阶段 goalId 经 ToolContext（非模型串参）"的实现与测试证据。
- "用户显式启动 flow/team 仍弹审批"的测试证据。
- "模型/Provider/高权限策略只能用户手动配置"的 UI 与测试证据。
- 用户正文不含 slash 语法、新表 schema 注释覆盖、slash create-only 不注入模型上下文的证据。
- 中文 conventional commit 列表；明确未 push；是否执行真实模型烟测。

## 与 Codex / Claude Code 的关系

- **借鉴**：Claude Code/Codex 的 slash command 显式入口 + 命名工作单元概念。
- **不同（我们的选择）**：① slash/WorkUnit 是 BaBiQ 自有协议层，不套 Spring AI 官方接口；② slash 只负责服务端确定性创建/复用容器，执行必须用户显式启动；③ 复用 P6-1/2/3 既有引擎 + 审批/沙箱/SQLite 审计；④ 对话视图始终是主体，P6-4 只新增创建入口，配置/启动复用已有详情页。

## 下一步

- 按 plan Chunk 1→6 + 本交接修订要点 TDD 落地 → 跑 §Task 16 验收 → 真实模型烟测 → 出完成报告。
