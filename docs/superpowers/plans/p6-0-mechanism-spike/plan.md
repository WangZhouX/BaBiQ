# P6-0 机制 spike — 详细 plan（草案）

> 状态：**草案，待用户确认**（2026-05-31 创建）。
> 隶属：`docs/superpowers/plans/p6-master.md` §5.6 P6-0。
> 性质：**隔离预研（spike），不是功能实现**——目的是回答"用哪套官方机制做基座 + 最难的 HITL 嵌套中断能不能成"，产物是**结论 + 选型决策 + 协议骨架草案**，不接真实业务、不改桌面正式 UI、实验代码用完可丢。

---

## 0. 一句话目标

在隔离实验里**对比四种官方子 Agent 组合机制**，重点验证**子 Agent 内部触发 HITL 审批时的嵌套中断与恢复**能否对接 BaBiQ 现有 `approval/respond`，据此**锁定 P6 的子 Agent 基座**，并产出最小协议骨架草案，供 P6-1 正式实现 plan 使用。

---

## 1. 这是 spike，不是实现（纪律，先钉死）

- **不发布功能**：不改 `AgentLoop` 主循环、不改桌面正式 UI、不接真实 turn/start 业务路径。
- **隔离**：实验代码放在独立 spike 包 / 独立测试类里（如 `backend/.../spike/p6/`），或纯测试（`@SpringBootTest` 局部），与生产路径解耦。
- **可丢弃**：spike 代码不要求生产级质量、不要求完整中文教学注释；但**结论文档要写实**。
- **时间盒**：以"回答完 §4 全部问题"为终点，不在 spike 阶段顺手实现 P6-1。
- **产物是知识不是产品**：见 §6。

> 例外：spike 里如果验证出某段"薄封装范式"可直接复用，可在 P6-1 plan 里标注复用，但 spike 本身不合并进生产。

---

## 2. Context7 已确认的 SAA Java 事实（grounding，spike 基于这些设计）

> 来源：Context7 `/websites/java2ai`（文档站）+ `/alibaba/spring-ai-alibaba/v1.1.2.2`（仓库），并经本机 `spring-ai-alibaba-agent-framework-1.1.2.3.jar` `javap` 核对。版本与 BaBiQ 锁定的 `1.1.2.3` 对齐。

### 2.1 HITL 嵌套中断 + 恢复（最关键，已确认可行路径）
- **嵌入**：`agent.asNode(boolean includeContents, boolean includeReasoning)` 把一个 `ReactAgent` 作为节点加入 `StateGraph`。
- **共享 saver（强制）**：官方 considerations 原文——"ensure a shared checkpoint saver instance is used for consistency between Workflows and nested Agents"。即父工作流 `CompileConfig.saverConfig(SaverConfig.builder().register(saver).build())` 与子 Agent `.saver(saver)` **必须是同一个 `MemorySaver` 实例**。
- **中断**：`compiledGraph.invokeAndGetOutput(input, RunnableConfig.builder().threadId(tid).build())` 返回的 `NodeOutput` 里检查 `InterruptionMetadata`。
- **恢复**：用**同一个 `threadId`** + `RunnableConfig` 里 **`addHumanFeedback(metadata)`** + 通常传**空输入 Map**（状态已在 checkpoint）。
- **关键坑**：`InterruptionMetadata.node()` 返回的是 **Agent 节点在工作流里的名字**，不是 Agent 内部节点名——BaBiQ 路由审批时要注意这一层映射。
- **BaBiQ 现状契合点**：`ReActStrategy.buildResumeConfig(...)` 现在**已经用 `.addHumanFeedback(metadata)`** 恢复（2026-05-25 bug 修复时确立）。即恢复 API 与官方嵌套范式**完全一致**——这是 HITL 嵌套大概率打通的核心依据。

### 2.2 横切层可挂到任意 ReactAgent（含子 Agent）
- `ReactAgent.builder().name().model().tools(...).systemPrompt(...)/.instruction(...).hooks(...).interceptors(...).toolContext(...).saver(...)` 全是 builder 方法。
- ⇒ BaBiQ 的 `sandbox / toolObservation / spotlighting / eviction` interceptor + `hitl / modelCallLimit / resumeJumpCleanup / tokenUsage` hook + `toolContext({cwd, writableRoots, emitter, sandboxMode, observationContext})` 都能像装配主 Agent 一样装配到子 Agent（事实源：`ReActStrategy.buildAgent`）。

### 2.3 四种官方子 Agent 组合机制（spike 对比对象）
- **(a) `AgentTool.getFunctionToolCallback(ReactAgent)` / `create(ReactAgent)`** → 子 Agent = 一个 `ToolCallback`，注册给父 ReactAgent；父用工具调用触发子 Agent（官方 supervisor 范式）。
- **(b) `TaskToolsBuilder` + `AgentSpec`**：`.subAgent(name, ReactAgent)` / `.subAgents(Map)` / `.addAgentDirectory(String)` / `.addAgentResource(Resource)` / `.taskRepository(..)` / `.defaultTools(..)` → 官方 subagent/Task 范式（含后台任务、目录加载）。`AgentSpec(name, description, systemPrompt, List<String> toolNames, model)`。
- **(c) `extension.interceptor.{SubAgentInterceptor, SubAgentSpec}}`**：拦截器式；`SubAgentSpec.builder()`（name/description/systemPrompt/model/tools/interceptors/enableLoopingLog）。
- **(d) `ReactAgent.asNode(...)` + `StateGraph` + 共享 `MemorySaver`**：见 2.1，**官方 HITL 示例采用此式**。

### 2.4 flow 编排（P6-2 用，spike 只确认存在/可构建，不深做）
- `flow.agent.{SequentialAgent, ParallelAgent, LlmRoutingAgent, LoopAgent}` + builder/strategy 齐全；本 spike 仅做"能构建一个 Sequential/Parallel flow 并跑通"的冒烟确认，编排细节留 P6-2。

---

## 3. 借鉴（非照搬）Codex / Claude Code

> 用户明确：**借鉴，不绝对模仿**。下列是"概念/姿态"借鉴；实现一律落到 SAA Java + BaBiQ 现有横切层，不照搬其 Rust / TS 机制。

- **Codex `explorer` / `worker` role（`role.rs`）**：借鉴"只读 explorer（快速、可并行、信任结论）+ 执行 worker（文件 ownership、不是唯一改代码者）"的**角色姿态**与 whenToUse 文案 → 落成 BaBiQ 的内置子 Agent `AgentSpec`（systemPrompt + 工具子集 + 模型）。**不照搬** Codex 的 config-layer / `agent_jobs` Rust 机制。
- **Claude Code Task（`agent.ts`）**：借鉴"子 Agent 模型默认 `inherit` 父级、可逐个覆盖"的**默认姿态** → 落成 BaBiQ `AgentSpec.model` 默认继承 active provider、可覆盖（复用 P2-3 + `ChatClientFactory`）。**不照搬** TS Task 工具族实现。
- **Claude Code swarm（`src/utils/swarm/`）**：借鉴"Leader + 常驻队友 + 互发消息"的**协作模型** → P6-3 的自研薄编排层参考其角色划分，但**不照搬** `permissionSync`/`inProcessRunner`/pane 机制。
- **保守起步姿态**（借鉴两家）：spike 与 P6-1 默认"先只读 explorer、写操作不下放子 Agent"，把 HITL 嵌套风险降到最低。

---

## 4. 要回答的问题（Q1–Q5）与成功判据

| # | 问题 | 成功判据（spike 通过线） |
|---|---|---|
| **Q1** | 子 Agent 内层工具调用能否携带父 `toolContext`（emitter / cwd / observation）？ | 子 Agent 内部某工具能读到父传入的 cwd 与 emitter，且工具输出能经 emitter 发出（或证明需要 per-turn 重建，见 Q4）|
| **Q2（头号）** | **HITL 嵌套中断**：子 Agent 内部 `write_file` 触发审批，能否中断、并经 BaBiQ `approval/respond` 恢复？ | `asNode`+共享 saver 路径下：`invokeAndGetOutput` 能拿到 `InterruptionMetadata`、`addHumanFeedback` 能恢复并继续子 Agent；明确 `AgentTool` 路径是否支持嵌套中断 |
| **Q3** | 子 Agent 的 token / 工具观测能否归集到本 turn，并区分主/子归属？ | tokenUsageHook / ToolObservationInterceptor 在子 Agent 上能累计；能加"子 Agent 归属"标记 |
| **Q4** | 子 Agent 是否必须 per-turn 构建（因为 emitter 指向当前 WebSocket turn）？ | 给出明确结论：静态单例 `AgentTool` 可行 / 必须 per-turn 构建子 Agent |
| **Q5** | 子 Agent / 编排节点的「任务 + 模型」配置如何承载？ | 给出 `AgentSpec`（systemPrompt=任务、model=模型）落库 vs 运行时构建的可行方案草案；模型覆盖走 `ChatClientFactory.resolveChatModel(providerId)` 验证可行 |

> **决定性结论**：Q2 的答案直接决定基座选型——
> - 若 `AgentTool` 路径**不支持**嵌套中断（子 Agent `.call()` 在工具执行内阻塞、中断不上浮），则**写类子 Agent 必须走 `asNode`+StateGraph**，`AgentTool` 仅用于**只读 / `NEVER` 审批**子 Agent。
> - 若 `asNode`+共享 saver+`addHumanFeedback` 能对接 BaBiQ `approval/respond`，则它是**支持写操作的子 Agent 的基座**。

---

## 5. 实验任务清单（spike tasks，按依赖顺序）

> 每个 Task 产出"结论 + 证据（能跑的最小代码/测试 + 实际输出）"，写进 §6 结论文档。

### Task 0：隔离实验脚手架
- 建 `backend/.../spike/p6/`（或独立测试包），准备一个 `ChatModel`（可用现有 Provider/ test double）、一个最小工具集（`echo` / 假 `write_file`）。
- 不接 WebSocket、不接 `AgentLoop`；用 `@SpringBootTest` 或 main 方法驱动。

### Task 1：四机制最小搭建 + 对比（回答"能不能搭、复杂度多少"）
- 各搭一个"父 Agent 委派 explorer 子 Agent"的最小例子：
  - (a) `AgentTool.getFunctionToolCallback(explorerAgent)` 注册给父 ReactAgent。
  - (b) `TaskToolsBuilder.builder().subAgent("explorer", explorerAgent).build()` 注册给父。
  - (c) `SubAgentInterceptor` + `SubAgentSpec`。
  - (d) `explorerAgent.asNode(true,false)` 嵌入一个父 `StateGraph`。
- 记录：构建复杂度、是否需要 StateGraph 改造、与 BaBiQ `ReactAgent.builder` 现状的契合度。

### Task 2：HITL 嵌套中断 + 恢复（Q2，最关键）
- 子 Agent 装 `HumanInTheLoopHook.approvalOn("write_file", ...)` + `.saver(sharedSaver)`。
- 对 (a) `AgentTool` 与 (d) `asNode` 两条路径分别验证：
  1. 触发子 Agent 调 `write_file` → 是否产生 `InterruptionMetadata`、`invokeAndGetOutput` 能否拿到。
  2. 用 BaBiQ 现成的 `addHumanFeedback(metadata)`（同 threadId、空输入）恢复 → 子 Agent 是否继续执行。
  3. 验证 `InterruptionMetadata.node()` 返回值（Agent 节点名）能否映射到 BaBiQ 的 approval payload（工具名 / 参数）。
- **结论**：哪条路径支持嵌套中断、BaBiQ `approval/respond` 链路如何对接、需不需要把"主 Agent + 子 Agent"包成 StateGraph。

### Task 3：横切层挂载（Q1，emitter/沙箱/观测/toolContext）
- 用选定/候选机制给子 Agent 装 BaBiQ 的 `BaBiQSandboxInterceptor` / `ToolObservationInterceptor` / `SpotlightingToolInterceptor` + `toolContext({cwd, writableRoots, emitter, sandboxMode, observationContext})`。
- 验证：子 Agent 内层工具调用是否经过沙箱校验、输出是否被 Spotlighting 包裹、是否计入观测、能否经 emitter 发协议 item。

### Task 4：token / 观测归属 + per-turn 构建（Q3 / Q4）
- 验证 `BaBiQTokenUsageHook` / `BaBiQStreamingTokenUsageInterceptor` 在子 Agent 上能否累计；如何打"子 Agent 归属"标记（为 P6-1 的 `bq_tool_calls` 归属字段铺路）。
- 验证 emitter 时效性：静态子 Agent vs per-turn 重建，给出明确结论。

### Task 5：节点 / 子 Agent 配置承载（Q5，任务 + 模型）
- 用 `AgentSpec(name, description, systemPrompt=任务, toolNames, model=模型)` 构建子 Agent；验证 `model` 覆盖经 `ChatClientFactory.resolveChatModel(providerId)` 能切到不同 Provider/模型；为空时继承 active provider。
- 给出"AgentSpec 落库（表草案）vs 运行时构建"的取舍建议（不在 spike 建表）。

### Task 6：协议骨架草案（产物）
- 基于 Task 1–5 结论，草拟委派 / 子 Agent / 节点配置的 `ThreadItem` 协议字段（仅草案，不进 `conversation/items`，不改前端）。
- 对齐 §5.5 原型：子 Agent 卡 / 编排节点 / 团队消息所需的协议字段。

---

## 6. 产物 / 交付

1. **spike 结论文档**（`docs/superpowers/plans/p6-0-mechanism-spike/spike-findings.md`）：Q1–Q5 逐条结论 + 实际跑过的代码片段与输出。
2. **选型决策**：明确"写类子 Agent 用哪套、只读子 Agent 用哪套"，并说明理由（以 Q2 为决定性依据）。见 §7 矩阵。
3. **协议骨架草案**：委派 / 子 Agent / 节点配置 `ThreadItem` 字段草案。
4. **P6-1 输入清单**：spike 验证可复用的"薄封装范式片段"清单（供 P6-1 正式实现引用）。

---

## 7. 选型决策矩阵（spike 跑完填）

| 机制 | 嵌套 HITL 中断 | 横切层挂载 | 编排（flow）支持 | 实现复杂度 | 建议用途 |
|---|---|---|---|---|---|
| (a) AgentTool | 待验（预期：仅只读/NEVER）| 子 Agent builder 装 | 经 flow node 包装 | 最低 | 只读 explorer 委派 |
| (b) TaskToolsBuilder+AgentSpec | 待验 | 子 Agent builder 装 | 与 flow 可组合 | 中 | 多子 Agent 委派 / 目录加载 |
| (c) SubAgentInterceptor | 待验 | 拦截器链 | — | 中 | 备选 |
| (d) asNode + StateGraph + 共享 saver | **预期可行（官方 HITL 范式）** | 子 Agent builder 装 + 工作流 saver | 原生 | 较高（需 StateGraph 包装）| **写类子 Agent / 需审批场景** |

> 预判（spike 验证或推翻）：**只读委派用 (a) AgentTool、需审批的写类 / 编排用 (d) asNode**；(b) 作为多子 Agent 注册的补充。

---

## 8. spike 边界（不做）

- 不实现 P6-1 正式委派功能、不改 `AgentLoop`、不改桌面正式 UI、不接 WebSocket 真路径。
- 不建数据库表 / 不写 Flyway migration（配置承载只出草案）。
- 不做 flow 编排细节（仅冒烟确认可构建）、不做实时 swarm。
- 不引入 `a2a.*`、不升级 SAA / Spring AI 版本。
- 不照搬 Codex/Claude Code 实现（§3 仅借鉴概念）。

---

## 9. 验收（spike 的"通过"标准）

- Q1–Q5 **全部有明确结论**（含实际跑过的最小代码与输出，不是推测）。
- Q2（HITL 嵌套中断）有**可复现的 interrupt→`approval/respond`→`addHumanFeedback` resume 证据**，或明确的退化结论（写操作不下放 + 哪套机制托底）。
- §7 决策矩阵填完、基座选定。
- 协议骨架草案产出。
- spike 代码与生产路径隔离，`backend` 现有 `clean verify` 不被 spike 破坏（spike 测试可独立运行）。
- 结论文档写实（CLAUDE.md §8：无新鲜证据不得声称通过）。

---

## 10. 参照

- **本机 jar / Context7 已确认 API**：见 §2（`asNode` / `addHumanFeedback` / `ReactAgent.builder` hooks+interceptors+toolContext+saver / `AgentTool` / `TaskToolsBuilder` / `AgentSpec` / `SubAgentSpec`）。
- **官方文档**：`java2ai.com/docs/frameworks/agent-framework/advanced/human-in-the-loop`（HITL 嵌套 + 共享 saver + resume considerations）、`.../tutorials/hooks`、`examples/multiagent-patterns/{subagent, supervisor}`（repo `v1.1.2.2`）。
- **BaBiQ 现有挂点**：`ReActStrategy.buildAgent`（横切装配事实源）、`ReActStrategy.buildResumeConfig`（已用 `addHumanFeedback`）、`interceptor/*`、`hook/*`、`model/ChatClientFactory.java`、`approval/*`。
- **借鉴源（概念）**：Codex `role.rs`（explorer/worker 姿态）、Claude Code `agent.ts`（inherit 默认 + 可覆盖）、`src/utils/swarm/*`（Leader+队友协作模型）。
- **master**：`docs/superpowers/plans/p6-master.md`（§4 横切清单、§5 UI 模型与原型、§7 风险）。

---

## 11. 下一步

1. 本 spike plan 由用户确认。
2. 确认后按 §5 Task 0→6 执行 spike，产出 §6 结论文档与选型决策。
3. 基座锁定后，写 **P6-1 子 Agent 委派**正式实现 plan（TDD、生产级、对齐 §5.5 原型 P6 01 / P6 04）。
