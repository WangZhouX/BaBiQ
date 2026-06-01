# P6-3 团队协作 — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p6-3-team-collaboration\plan.md`
> 总纲：`E:\BaBiQ\docs\superpowers\plans\p6-master.md`
> 前置：P6-1 子 Agent 委派底座、P6-2 flow 编排底座、P6-0 `asNode + shared MemorySaver` spike 结论。

## 当前状态

- **plan 已补齐并等待用户确认**（2026-06-01）：已明确 `coordinate_team` 入口工具、shared `MemorySaver` 构建路径、`team/message/send` 直接喊话协议、结构化 supervisor 路由、`team` / `teamMessage` 协议拆分，以及 P6-2 真实模型烟测前置。
- **实现尚未开始**：当前只有计划文档更新，没有 P6-3 生产代码、测试、migration 或桌面端代码。
- **技术基座已确认**：P6-3 使用 Spring AI Alibaba graph-core 官方原语自搭 supervisor 模式，不依赖 `SupervisorAgent` 类。

## 一句话目标

让主 Agent 作为 Leader / Supervisor，使用官方 graph-core 原语（`StateGraph` + 自定义 `NodeAction` supervisor + `ReactAgent.asNode(...)` teammates + 条件边循环 + shared `MemorySaver`）协调少量 teammate 子 Agent（explorer / worker），通过 `coordinate_team` 工具发起，通过 `team` / `teamMessage` 协议可视化团队状态和协调时间线；对话始终保留，写操作使用 approve-once + 沙箱硬边界。

## 必读入口

1. `E:\BaBiQ\CLAUDE.md` / `E:\BaBiQ\AGENTS.md`
2. `E:\BaBiQ\docs\superpowers\plans\p6-3-team-collaboration\plan.md`
3. `E:\BaBiQ\docs\superpowers\plans\p6-0-mechanism-spike\spike-findings.md`
4. `E:\BaBiQ\docs\superpowers\plans\p6-2-flow-orchestration\codex-handoff.md`
5. `E:\BaBiQ\backend\src\main\java\com\wzx\babiq\server\agent\delegation\SubAgentRuntimeFactory.java`
6. `E:\BaBiQ\backend\src\main\java\com\wzx\babiq\server\agent\flow\FlowApprovalService.java`
7. `E:\BaBiQ\backend\src\main\java\com\wzx\babiq\server\tool\impl\FlowOrchestrationTool.java`
8. `E:\BaBiQ\backend\src\main\java\com\wzx\babiq\server\conversation\items\ThreadItem.java`
9. `E:\BaBiQ\desktop\src\main\kotlin\com\wzx\babiq\desktop\protocol\ThreadModels.kt`
10. `E:\BaBiQ\desktop\src\main\kotlin\com\wzx\babiq\desktop\ui\runtime\RuntimeDetailsPanel.kt`

## 官方能力核对结论

- Context7 已确认 Spring AI Alibaba 文档存在 graph-core `multi-agent-supervisor` 示例形态：`SupervisorNode` 路由 `next` / `FINISH`，成员节点执行后回到 supervisor。
- 本地锁定 jar `1.1.2.3` 已确认存在：
  - `com.alibaba.cloud.ai.graph.StateGraph`
  - `com.alibaba.cloud.ai.graph.action.NodeAction`
  - `StateGraph.addConditionalEdges(...)`
  - `com.alibaba.cloud.ai.graph.agent.ReactAgent.asNode(boolean, boolean)`
  - `com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver`
  - `CompileConfig.Builder.saverConfig(...)`
- 本地锁定 jar 未找到任何 `Supervisor*` 类。**不要依赖文档里的 `SupervisorAgent` 类**，以 jar 为准。
- Spring AI `ToolContext` 可承载 cwd、emitter、沙箱、delegation/team metadata 等运行态上下文，且不会发送给模型；P6-3 仍要通过 BaBiQ 现有 ToolContext / RunnableConfig metadata 链路传递上下文。

## 范围边界

**P6-3 做：**

- 新增 `coordinate_team` 本地工具，作为主 Agent 发起团队协作的唯一入口。
- 新增 Team supervisor 图：`StateGraph` + `SupervisorRoutingNode` + teammates `ReactAgent.asNode(...)` + 条件边循环到 `FINISH`。
- 新增 shared saver 构建路径：父图和所有 teammate 子图共享同一个 `MemorySaver`。
- 复用 / 泛化 P6-2 `FlowApprovalService` 做团队级 approve-once。
- 新增 `team` / `teamMessage` 协议 item，并接桌面右侧团队面板。
- 新增 `team/message/send` JSON-RPC 或等价明确入口，用于 UI 直接喊话 teammate。
- 新增 `bq_teams` / `bq_team_messages`，并同步中文 SQL 注释、`bq_schema_comments`、Entity 注释和覆盖测试。

**P6-3 不做：**

- 不做点对点真并发 swarm（留 P6-3b）。
- 不做 A2A 远程 Agent。
- 不做跨 turn 常驻 team。
- 不做运行中逐工具审批和嵌套并发中断（继续使用 approve-once；逐工具恢复留 P6-2b / P6-3b）。
- 不升级 Spring AI / Spring AI Alibaba。

## 必须落实的 6 个约束

1. **入口工具：`coordinate_team`**
   - 工具名必须 ASCII。
   - 默认进入可见能力集合，走 BaBiQ 工具调用、审批、沙箱、Spotlighting、运行记录和能力搜索链路。
   - 不能让桌面端伪造 Agent 行为；桌面端只能渲染后端协议 item。

2. **shared `MemorySaver` 构建路径**
   - 当前 `SubAgentRuntimeFactory` 默认给 child agent `new MemorySaver()`，P6-3 不能复用这个路径。
   - 必须新增 `buildChildAgentForGraph(...)` 或 `SubAgentRuntimeOptions`，显式传入 shared saver / compile config / outputKey。
   - 父 `StateGraph` 编译时必须通过 `CompileConfig.builder().saverConfig(SaverConfig.builder().register(sharedSaver).build())` 安装同一个 saver。

3. **直接喊话协议：`team/message/send`**
   - 参数至少包含 `teamId`、`threadId`、`targetAgentName`、`content`。
   - 绕过本轮 supervisor 路由，但仍写入 team 记录、运行记录归属和 `teamMessage` item。

4. **结构化路由：`SupervisorRouteDecision`**
   - 字段建议：`next`、`reason`、`confidence`。
   - `next` 只允许 teammate name 或 `FINISH`。
   - 解析失败、模型返回未知成员、空输出时必须走确定性 fallback，并写路由审计。

5. **协议 item 拆分**
   - `team` item：团队整体状态、成员快照、轮次、终态、摘要。
   - `teamMessage` item：supervisor 路由、teammate 结果、直接喊话、错误、终止。
   - 两类 item 都不进入父聊天消息流，只更新右侧团队面板和时间线。

6. **P6-2 真实模型烟测前置**
   - 开工前先跑一次 P6-2 `orchestrate_flow` 顺序 / 并行 / 路由真实模型烟测。
   - 若无法烟测，必须在本 handoff 后续更新中记录“未烟测风险”和原因，再进入实现。

## TDD 任务顺序

0. P6-2 真实模型烟测或风险记录。
1. `BabiqTeamSpec`：团队目标、成员、轮次上限、沙箱、写范围校验。
2. `coordinate_team`：工具 schema、能力别名、system prompt 使用边界。
3. `SubAgentRuntimeFactory` shared saver graph 构建路径。
4. `SupervisorRouteDecision` + `SupervisorRoutingNode`：结构化路由、白名单校验、fallback。
5. `TeamCoordinationService`：组装 `StateGraph`、添加 teammate `asNode`、条件边循环至 `FINISH`。
6. teammate 横切复用：沙箱、Spotlighting、工具观测、token hook、模型继承/覆盖。
7. 团队级 approve-once：复用 / 泛化 `FlowApprovalService` 四条安全语义。
8. 终止策略：`FINISH` + 轮次上限兜底。
9. `team/message/send`：直接喊话 teammate。
10. 协议：`TeamItem` / `TeamMessageItem` JSON 序列化和历史加载。
11. DB：`bq_teams` / `bq_team_messages` migration、repository、schema comments。
12. 桌面端：协议模型、reducer、右侧团队卡、消息时间线、对话对象切换。
13. 端到端 IT：Leader 协调 explorer + worker，approve-once，一直到 `FINISH`。

## 验收命令

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=BabiqTeamSpecTest,TeamCoordinationToolTest,SubAgentGraphRuntimeFactoryTest,SupervisorRoutingNodeTest,TeamCoordinationServiceTest,TeamApprovalTest,TeamTerminationTest,DirectTeammateMessageTest,ThreadItemJsonTest,TeamRepositoryTest,SchemaCommentsCoverageTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*TeamSectionTest"
.\gradlew.bat test
```

## 真实模型烟测

1. 先确认 P6-2 `orchestrate_flow` 已烟测，或记录未烟测风险。
2. 发起一个真实团队任务：Leader 协调 explorer 探查，worker 在 approve-once + 沙箱范围内写入。
3. 观察 supervisor 是否迭代路由到正确 teammate，并在完成后返回 `FINISH`。
4. 验证 `teamMessage` 时间线可见，父聊天流不被中间过程污染。
5. 验证 `bq_tool_calls.agent_name` / `parent_agent_name` / `delegation_id` 归属正确。
6. 验证 `team/message/send` 可直接喊话 teammate。
7. 验证桌面端对话栏全程可用，右侧团队面板与 Figma P6 03 / P6 05 对齐。

## 完成报告必须包含

- Task 0–13 逐条完成状态。
- Context7 + 本地 jar 核对结果，尤其 `SupervisorAgent` 不存在、graph-core 原语存在。
- shared saver 测试证据。
- `coordinate_team` 和 `team/message/send` 协议证据。
- `team` / `teamMessage` 不污染父聊天流的测试证据。
- approve-once 四条语义复用证据。
- 新表字段中文注释与 `SchemaCommentsCoverageTest` 通过证据。
- 后端和桌面端实际验证输出。
- 中文 conventional commit 列表。
- 明确是否 push。

## 不要做的事

- 不要实现自研 actor / message bus swarm。
- 不要依赖 `SupervisorAgent` 类。
- 不要让 teammate 中间消息直接进入父聊天消息流。
- 不要绕过 BaBiQ 审批、沙箱、Spotlighting、运行记录或 ContextAssembler。
- 不要把 `coordinate_team` / teammate name 改成中文工具名。
- 不要升级 Spring AI / Spring AI Alibaba。
- 不要用 `@Disabled` 占位测试。

## 下一步

1. 用户确认本 handoff。
2. 先补 P6-2 真实模型烟测记录。
3. 按 Task 0→13 TDD 实现 P6-3。
