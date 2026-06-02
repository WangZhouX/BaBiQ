# P6-4 Slash 命令与命名工作容器 — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p6-4-slash-work-unit-commands\plan.md`（16 个 Task / 6 个 Chunk，含逐步 TDD）
> 上游：P6-1 子 Agent（`explorer`）、P6-2 flow 编排（`orchestrate_flow`）、P6-3 团队（`coordinate_team`）均已合并入 master。
> 锁定版本：Spring AI `1.1.6`、Spring AI Alibaba `1.1.2.3`（**不升级**）。

## 当前状态

- **计划已就绪并经独立审查修订（2026-06-02），代码尚未实现**（待 Codex 按 plan §Chunk 1→6 TDD 落地）。
- 这是在已有 P6-1/2/3 之上加一层**显式入口（slash）+ 命名可复用容器（WorkUnit）**；**复用现有执行引擎，不重写**。
- 技术可行性已 Context7 + 现有代码双确认（见下）。

## 一句话目标

输入框提供 `/子代理`、`/编排 <名称>：<目标>`、`/团队 <名称>：<目标>`；编排/团队升级为**命名可复用 `WorkUnit` 容器**（可持续追加目标、并发存在、完成后移除并从 UI 消失）；用户正文**不写入 slash 控制语法**，`executionIntent` 只作本轮运行意图。

## ⚠️ 审查修订要点（**优先于 plan 正文里任何"模型驱动"的旧表述**）

plan 已整体改为下面这套；落地时**以此为准**：

1. **容器生命周期 = 服务端确定性，不交给模型**：slash intent 是结构化输入（mode+name+goal 已定）。在 **`submit()` 路径**调用 `WorkUnitService.createOrReuseAndAppendGoal(...)` 完成 create/reuse/append-goal，拿到 `goalId`。**不要**让模型先调 `work_unit_manage` 才有 goalId。
2. **goalId 经 ToolContext 注入，不靠模型串参**：`AgentLoop` build agent 时把 `goalId` 写入 `ToolContext`（新 key `CONTEXT_WORK_UNIT_GOAL_ID`），**与现有 `CONTEXT_CWD` 注入同一处、同一套路**；`FlowOrchestrationTool`/`TeamCoordinationTool` 读 `toolContext.getContext().get(CONTEXT_WORK_UNIT_GOAL_ID)` 回写 `markGoalRunning/Completed`。
3. **`work_unit_manage` 工具只用于自然语言管理**（running 时"补充 vs 排队"、追加新目标、移除容器），**不是 slash 路径建容器的必经跳**。
4. **intent 指令注入现有 `current_turn` 权威层**（`ContextAssembler` 已有、系统提示已赋最高优先级），不要新造弱层。
5. **审批闸门必须保留**：slash 触发的 flow/team 仍走 P6-2/P6-3 approve-once 弹窗——**补测试钉死**（slash 触发也弹审批、批准后才执行）。
6. **名称唯一包事务**：create-or-reuse 用 `TransactionTemplate`（复用压缩链路同款）防 TOCTOU。

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

**做**：slash 解析 + 命令面板；`turn/start` 带 `executionIntent`；`WorkUnit`(TEAM/FLOW) 领域模型 + SQLite 事实源（`bq_work_units`/`bq_work_unit_goals`）；服务端确定性容器生命周期；`work_unit_manage`（NL 管理）；`workunit/list`+`workunit/remove`；右侧多容器列表 + 移除；复用 `orchestrate_flow`/`coordinate_team`。

**不做**：自由连边图编辑器；运行中逐工具审批 / 并发中断（沿用 approve-once）；UI 直接调 flow/team 绕过主 Agent；跨 thread 全局复用；升级 Spring AI/SAA；物理删除被移除容器的审计事实。

## 关键决策（plan §0/§2 + 本修订）

- 容器是命名可复用 `WorkUnit`（不是一次 turn）；目标是容器里的批次（`WorkUnitGoal`）。
- 同 thread 内 `removed=0` 且 `status in (queued,running)` 的 work unit 名称唯一。
- 移除只置 `removed=1`+`removed_at`，**不删**运行记录/工具调用/目标审计；被移除后同名 `/团队`/`/编排` 建新容器。
- 工具 `name` ASCII（`work_unit_manage`）；中文检索靠 displayName/description/searchText（§4.1，含 `团队 编排 工作容器 目标 追加 移除`）。

## TDD 任务顺序（plan Chunk 1→6，先红后绿）

- **Chunk 1**（桌面）：Task 1 `SlashCommandParser` → Task 2 命令面板/模式 chip → Task 3 `turn/start` 带 `executionIntent`（本地 optimistic 消息只显示目标文本）。
- **Chunk 2**（后端 intent）：Task 4 `ExecutionIntent` 模型 → Task 5 `TurnStartHandler`/`submit` 接 intent **并服务端建容器/append-goal 拿 goalId** → Task 6 `current_turn` 注入"直接执行"指令。
- **Chunk 3**（持久化/工具）：Task 7 `bq_work_units`/`bq_work_unit_goals`（V16，**SQL 中文注释 + `bq_schema_comments` + Entity 注释 + 覆盖测试**）→ Task 8 `WorkUnitService` 复用/队列 → Task 9 `work_unit_manage`（NL 管理）。
- **Chunk 4**（关联/接口）：Task 10 flow/team 从 **ToolContext 读 goalId** 回写状态 → Task 11 `workunit/list`+`workunit/remove`。
- **Chunk 5**（桌面 UI）：Task 12 协议/状态聚合（多 FLOW/TEAM 容器、removed 消失）→ Task 13 右侧 `WorkUnitSection` + 移除。
- **Chunk 6**：Task 14 端到端 IT（`WorkUnitSlashIntentIT` + 桌面 `ChatControllerTest`）→ Task 15 文档同步 → Task 16 全量验证。

## 执行规则

1. 严格按 plan Chunk 1→6 / 每 Task 5 步 TDD；改生产代码补**中文教学注释**（CLAUDE.md §4）。
2. **容器生命周期服务端确定性 + goalId 经 ToolContext**（审查修订 1/2）——这是本阶段最关键约束，别回退成"模型先调 work_unit_manage 串 goalId"。
3. 新表 `bq_work_units`/`bq_work_unit_goals` 必须同步 SQL 中文注释 + `bq_schema_comments` + Entity 注释 + `SchemaCommentsCoverageTest`（CLAUDE.md §4 红线）。
4. **用户正文不含 slash 控制语法**：测试钉死 `bq_items` 的 user message = `input.text`，不是 slash 原文。
5. 复用现有 `orchestrate_flow`/`coordinate_team`，**不重写** P6-2/P6-3 引擎；审批/沙箱/运行记录链路保留。
6. 工具 `name` ASCII；中文走 displayName/description/searchText。
7. 不升级 Spring AI/SAA；不引入新 SAA API；`AgentLoop.invoke` 若有行数约束测试不得退化。
8. 每 Task 中文 conventional commit（`feat(p6-4): ...` / `test(p6-4): ...`）。**不主动 push**。
9. 完成后更新 `CLAUDE.md`/`AGENTS.md` 检查点、`p6-master.md`、`p6-task-index`（如有）。
10. 没有新鲜证据不得声称完成（CLAUDE.md §8）；**禁止 `@Disabled` 占位**。

## 验收（plan §Task 16）

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=ExecutionIntentTest,TurnStartHandlerTest,WorkUnitIntentInstructionBuilderTest,WorkUnitServiceTest,WorkUnitManageToolTest,WorkUnitHandlersTest,FlowOrchestrationToolTest,TeamCoordinationToolTest,SchemaCommentsCoverageTest,WorkUnitSlashIntentIT" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*SlashCommandParserTest" --tests "*SlashCommandMenuTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*WorkUnitSectionTest"
.\gradlew.bat test
```

- 通过标准：上述定向全绿；`clean verify` 全绿（含 IT）；桌面无回归；`SchemaCommentsCoverageTest` 过；`bq_items` user message 不含 slash 语法。
- 真实模型人工烟测（plan §Task 16 Step 5）：`/子代理` 不建 work unit；`/团队 前端验收组：…` 第二次复用同名并追加目标；多个团队/编排并发展示；已完成容器移除后从页面消失；slash 触发仍弹审批。

## 完成报告必须包含

- 每个 Task ✅/❌ + 跑过的命令与**实际输出**（非预期）。
- "容器生命周期服务端确定性 + goalId 经 ToolContext（非模型串参）"的实现与测试证据。
- "slash 触发 flow/team 仍弹审批"的测试证据。
- 用户正文不含 slash 语法、新表 schema 注释覆盖、`current_turn` 注入不污染 recent_history 的证据。
- 中文 conventional commit 列表；明确未 push；是否执行真实模型烟测。

## 与 Codex / Claude Code 的关系

- **借鉴**：Claude Code/Codex 的 slash command 显式入口 + 命名工作单元概念。
- **不同（我们的选择）**：① slash/WorkUnit 是 BaBiQ 自有协议层，不套 Spring AI 官方接口；② 容器生命周期服务端确定性（比纯模型驱动可靠）；③ 复用 P6-1/2/3 既有引擎 + 审批/沙箱/SQLite 审计；④ 对话视图始终是主体，容器在右侧运行详情。

## 下一步

- 按 plan Chunk 1→6 + 本交接修订要点 TDD 落地 → 跑 §Task 16 验收 → 真实模型烟测 → 出完成报告。
