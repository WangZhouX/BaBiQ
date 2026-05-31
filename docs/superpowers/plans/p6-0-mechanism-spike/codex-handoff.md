# P6-0 机制 spike — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p6-0-mechanism-spike\plan.md`
> 总纲见：`E:\BaBiQ\docs\superpowers\plans\p6-master.md`

## 当前状态

- **spike 详细计划已定稿（草案待用户确认）**（2026-05-31）。
- **spike 实验尚未开始**：无任何后端实验代码。
- **这是 spike，不是功能实现**：产物是「结论 + 选型决策 + 协议骨架草案」，不接真实业务、不改 `AgentLoop`、不改桌面正式 UI、实验代码隔离且可丢弃。
- 关键 SAA Java API 已由 Context7（`/websites/java2ai` + repo `v1.1.2.2`）+ 本机 `spring-ai-alibaba-agent-framework-1.1.2.3.jar` 反编译核对（见 plan §2）；实施时可复核但不必从零查。

## 一句话目标

在隔离实验里对比四种官方子 Agent 组合机制，**重点验证子 Agent 内部触发 HITL 审批时的嵌套中断能否对接 BaBiQ 现有 `approval/respond`**，据此**锁定 P6 子 Agent 基座**，产出结论文档 + 协议骨架草案，供 P6-1 正式实现 plan 使用。

## 必读入口

1. `E:\BaBiQ\CLAUDE.md`（项目纪律：§3 阶段边界、§4 实现规则/先查官方、§4.1 工具命名、§5 验收、§7 Git、§8 汇报）
2. `E:\BaBiQ\docs\superpowers\plans\p6-master.md`（§2 三方源码对照、§4 横切清单、§5 UI 模型与 8 帧原型、§7 风险）
3. `E:\BaBiQ\docs\superpowers\plans\p6-0-mechanism-spike\plan.md`（**本 spike 完整计划**）
4. BaBiQ 现有挂点（spike 复用的事实源）：
   - `backend/.../agent/ReActStrategy.java`（横切装配事实源：`buildAgent` 的 `.interceptors/.hooks/.toolContext/.saver`；`buildResumeConfig` 已用 `addHumanFeedback`）
   - `backend/.../interceptor/*`、`backend/.../hook/*`、`backend/.../model/ChatClientFactory.java`、`backend/.../approval/*`
5. 借鉴源（**概念借鉴，不照搬实现**）：Codex `E:\wzx\codex\codex-rs\core\src\agent\role.rs`（explorer/worker 姿态）、Claude Code `E:\wzx\claude-code\src\utils\model\agent.ts`（inherit 默认 + 可覆盖）、`src\utils\swarm\*`（Leader+队友协作）

## 这是 spike（先钉死纪律）

- 不发布功能：不改 `AgentLoop` 主循环、不改桌面正式 UI、不接 WebSocket 真路径。
- 隔离：实验代码放独立 spike 包（如 `backend/.../spike/p6/`）或局部 `@SpringBootTest`，与生产路径解耦。
- 可丢弃：spike 代码不要求生产级质量/完整中文注释；但**结论文档要写实**。
- 不建表 / 不写 Flyway migration（配置承载只出草案）；不引入 `a2a.*`；不升级 SAA/Spring AI 版本。
- 不破坏现有 `clean verify`（spike 测试可独立运行）。

## 要回答的 5 个问题 + 成功判据（spike 的核心）

| # | 问题 | 通过线 |
|---|---|---|
| Q1 | 子 Agent 内层工具能否携带父 `toolContext`（emitter/cwd/observation）| 子 Agent 工具能读到父 cwd/emitter 并发出输出，或证明需 per-turn 重建 |
| **Q2（头号）** | **HITL 嵌套中断**：子 Agent 内部 `write_file` 触发审批，能否中断并经 BaBiQ `approval/respond` 恢复 | `asNode`+共享 saver 下 `invokeAndGetOutput` 拿到 `InterruptionMetadata`、`addHumanFeedback` 能恢复；明确 `AgentTool` 是否支持嵌套中断 |
| Q3 | 子 Agent token/观测能否归集并区分主/子 | tokenUsageHook/ToolObservationInterceptor 在子 Agent 上累计 + 可打归属标记 |
| Q4 | 子 Agent 是否必须 per-turn 构建（emitter 时效）| 明确结论：静态单例可行 / 必须 per-turn |
| Q5 | 节点/子 Agent「任务+模型」配置如何承载 | `AgentSpec`（systemPrompt=任务、model=模型）方案草案；模型覆盖经 `ChatClientFactory.resolveChatModel(providerId)` 验证可行 |

> **Q2 是决定性问题**：若 `AgentTool` 路径不支持嵌套中断 → 写类子 Agent 必须走 `asNode`+StateGraph，`AgentTool` 仅用于只读/`NEVER` 审批子 Agent。

## 关键已确认 API（plan §2，实施直接用）

- **HITL 嵌套**：`agent.asNode(includeContents, includeReasoning)` 嵌入 `StateGraph`；**父工作流 `CompileConfig.saverConfig(SaverConfig.builder().register(saver).build())` 与子 Agent `.saver(saver)` 必须同一 `MemorySaver` 实例**；中断经 `compiledGraph.invokeAndGetOutput(input, RunnableConfig.threadId(tid))` 检查 `InterruptionMetadata`；恢复用同 threadId + `addHumanFeedback(metadata)` + 空输入。
- **坑**：`InterruptionMetadata.node()` 返回 Agent 节点名（非 Agent 内部节点名），BaBiQ 路由审批要做映射。
- **契合点**：`ReActStrategy.buildResumeConfig` 现已用 `addHumanFeedback(metadata)` —— 恢复 API 与官方嵌套范式一致。
- **横切挂载**：`ReactAgent.builder().tools().hooks().interceptors().toolContext().saver()` 全是 builder 方法，子 Agent 可装 BaBiQ 全套 interceptor/hook/toolContext（事实源 `ReActStrategy.buildAgent`）。
- **四机制**：`AgentTool.getFunctionToolCallback(ReactAgent)`；`TaskToolsBuilder.builder().subAgent(name, agent).build()` + `AgentSpec(name,description,systemPrompt,toolNames,model)`；`SubAgentInterceptor`+`SubAgentSpec.builder()`；`asNode`+StateGraph。

## 实验任务顺序（plan §5）

- **Task 0**：隔离脚手架（ChatModel + 最小工具集，不接 WS/AgentLoop）。
- **Task 1**：四机制各搭一个"父委派 explorer"最小例子，记录复杂度/契合度。
- **Task 2（最关键）**：对 (a)AgentTool 与 (d)asNode 验 HITL 嵌套中断→`addHumanFeedback` 恢复→`InterruptionMetadata.node()` 映射。
- **Task 3**：给子 Agent 挂 BaBiQ 沙箱/观测/Spotlighting interceptor + toolContext，验内层工具是否经过。
- **Task 4**：token/观测归属 + per-turn 构建结论。
- **Task 5**：`AgentSpec` 任务+模型承载；模型覆盖经 `ChatClientFactory` 验证。
- **Task 6**：委派/子 Agent/节点配置 `ThreadItem` 协议字段草案（不进生产 items、不改前端）。

## 选型预判（spike 验证或推翻，填进 plan §7 矩阵）

**只读委派用 (a) AgentTool；需审批的写类 / 编排用 (d) asNode+StateGraph；(b) TaskToolsBuilder 作多子 Agent 注册补充。**

## 执行规则

1. 严格按 plan §5 Task 0→6 顺序；每个 Task 产出"结论 + 能跑的最小代码/测试 + 实际输出"。
2. spike 代码隔离，不混入生产路径；不破坏现有 `clean verify`。
3. **借鉴不照搬**：Codex/Claude Code 仅借鉴概念，实现落到 SAA Java + BaBiQ 横切层。
4. 中文 conventional commit（`spike(p6-0): ...` 或 `docs(p6-0): ...`）；**不主动 push**。
5. 没有新鲜证据不得声称结论（CLAUDE.md §8）。

## 产物 / 完成报告必须包含

- `spike-findings.md`：Q1–Q5 逐条结论 + 实际跑过的代码片段与**实际输出**（非预期）。
- Q2 的可复现 interrupt→`approval/respond`→`addHumanFeedback` resume 证据，或明确退化结论。
- §7 决策矩阵填完、基座选定 + 理由。
- 委派/子 Agent/节点配置协议骨架草案。
- P6-1 可复用"薄封装范式片段"清单。
- 中文 conventional commit 列表；明确说明未 push。

## 验收（spike 通过标准）

- Q1–Q5 全部有明确结论（含实际证据，非推测）。
- Q2 有可复现证据或明确退化结论。
- 基座选定、协议草案产出。
- spike 与生产隔离，`backend` 现有 `clean verify` 不被破坏。

## 下一步

- spike plan + 本 handoff 由用户确认 → 按 Task 0→6 跑 spike → 产出结论文档与选型 → 基座锁定后写 **P6-1 子 Agent 委派**正式实现 plan（TDD、生产级、对齐原型 P6 01 / P6 04）。
