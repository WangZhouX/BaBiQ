# P6 Multi-Agent / 子 Agent 平台 — Master 计划（草案）

> 状态：**草案，待用户确认**（2026-05-31 创建；同日完成原型评审与文档梳理）。
> 本文件是 P6 大阶段的总纲，对标 `2026-05-21-p1-master.md` / `p2-master.md` / `p3-master.md`。
> 确认后再按 §5.6 子阶段拆出各自的 `plan.md` + `codex-handoff.md`，逐子阶段实现并验收。
> **进度**：调研结论已核对、UI 模型已评审定稿、Figma 原型已出 8 帧（见 §5.5）；P6-0 spike 已完成，P6-1 只读 `explorer` 子 Agent 委派已全量闭环；P6-2 flow 编排、P6-3 团队协作、P6-4 slash/自然语言 WorkUnit 入口均已完成代码实现和自动化验收，真实模型烟测按各子阶段 handoff 继续补齐。

---

## 0. 一句话定位

让 BaBiQ 从「单一主 Agent + 工具」升级为「主 Agent 可委派子 Agent，并逐步支持 flow 编排与实时 swarm/team 协作」的多 Agent 平台，**全程薄封装 Spring AI Alibaba 官方多 Agent 构件，不自研子 Agent 引擎**，并把 BaBiQ 既有的审批 / 沙箱 / Spotlighting / 观测 / 运行记录 / 上下文工程 / 协议 UI 全部接进子 Agent。

---

## 1. 为什么做 / 目标

- **现状短板**：BaBiQ 目前是单主 Agent（`ReActStrategy` 构建一个 `babiq_agent`），无法把「探索代码库」「并行执行多个独立子任务」「不同角色分工」交给专门的子 Agent。Codex（`explorer`/`worker` role + `agent_jobs`）和 Claude Code（`Task` 子 Agent + `swarm` team）都已具备，对一个 Codex-like 学习项目这是显眼缺口。
- **用户目标（2026-05-31 确认）**：最终要同时具备
  1. **子 Agent 委派**（delegation）：主 Agent 把受限子任务交给专家子 Agent，结果回传。
  2. **flow 编排**（orchestration）：多个子 Agent 顺序 / 并行 / 路由 / 循环执行。
  3. **实时 swarm/team 协作**（live collaboration）：多个 Agent 同时在线、互发消息协作。
- **机制选型**：官方提供多套子 Agent 机制（见 §3），用户决定**在 P6-0 spike 中做对比实验再锁定基座**，不提前在本 master 写死。

> 三层是**累进**关系：必须先有委派（P6-1），才能在其上做 flow 编排（P6-2），最后才是实时协作（P6-3）。本 master 给出累进路线，不要求一次实现全部。

---

## 2. 三方源码对照（本次已逐项核对，非记忆）

### 2.1 Codex（`E:\wzx\codex\codex-rs\core\src\agent\`）

- **role 模型**（`role.rs` 已读）：内置 `default` / `explorer` / `worker`（`awaiter` 已注释）。role = 一个配置层（`model` / `model_reasoning_effort` / `service_tier`）+ 一段 `description`（whenToUse）。
  - `explorer`：只读、快速、权威；用于针对代码库的明确问题；**鼓励并行 spawn 多个**；信任其结论；复用已有 explorer。
  - `worker`：执行 / 改代码；明确文件 **ownership**；告知 worker「你不是唯一在改代码的人」，不要回滚他人改动。
- **编排归属**：`role.rs` 只负责把 role 配置叠到 session config；**何时 spawn、用哪个 role 由多 Agent 工具 handler 拥有**（`tools/handlers/agent_jobs.rs`、`spawn_agents_on_csv.rs` 批量 spawn、`report_agent_job_result.rs`、`codex_delegate.rs`、`thread_manager.rs`）。
- **子 Agent 继承**：spawn 时子 Agent 默认继承父级 `model_provider` / `service_tier`，除非 role 显式覆盖。
- **基础设施 crate**：`agent-graph-store`（持久化 agent 图）、`agent-identity`（agent 身份 / 昵称）。

### 2.2 Claude Code（`E:\wzx\claude-code\src\`）

- **两层能力**：
  1. **Task 子 Agent**（`src/tools/Task{Create,Get,List}Tool`、`src/components/agents/.../Task{Output,Stop}Tool`）：spawn 一个 `subagent_type` 子 Agent，带自己的 tools / model，结果回传。`utils/model/agent.ts` 已读：子 Agent model 默认 `inherit` 父级，可选 sonnet/opus/haiku；agent 定义带 name/description/tools/model（`loadPluginAgents.ts` 从目录加载）。
  2. **swarm / team 实时协作**（`src/utils/swarm/`）：leader + teammates，`SendMessage` 互发消息，`permissionSync.ts`(27KB) 权限同步，`inProcessRunner.ts`(55KB)，后端 `backends/{InProcessBackend, PaneBackendExecutor}`（进程内 or tmux pane），`leaderPermissionBridge`、`teammate*`。**这是重型实时协作模型**，但仍是本机进程内 / pane，不是跨网络。

### 2.3 Spring AI Alibaba `1.1.2.3`（本机 jar 反编译核对 + Context7 官方文档交叉印证）

> 证据：`spring-ai-alibaba-agent-framework-1.1.2.3.jar` 类清单 + `javap -public` 签名，并经 Context7（repo `v1.1.2.2` + 文档站 `java2ai`）逐条交叉印证。

| BaBiQ 需求 | 官方类（包 `com.alibaba.cloud.ai.graph.agent`） | 已核对 API |
|---|---|---|
| 子 Agent 即工具 | `AgentTool` | `static ToolCallback create(ReactAgent)`、`getFunctionToolCallback(ReactAgent)` |
| 子 Agent 规格 | `tools.task.AgentSpec`（record） | `AgentSpec(String name, String description, String systemPrompt, List<String> toolNames, String model)`、`AgentSpec.of(name, desc, systemPrompt)` |
| Task 工具族 | `tools.task.{TaskTool, TaskToolsBuilder, TaskOutputTool, BackgroundTask, TaskRepository, DefaultTaskRepository, AgentSpecLoader, AgentSpecReactAgentFactory}` | `TaskToolsBuilder.builder().subAgent(name, ReactAgent).subAgents(Map).addAgentDirectory(String).addAgentResource(Resource).taskRepository(..).agentSpecFactory(..).chatModel(..).chatClient(..).defaultTools(ToolCallback...).build() → List<ToolCallback>` |
| 拦截器式子 Agent | `extension.interceptor.{SubAgentInterceptor, SubAgentSpec}` | `SubAgentSpec.builder()`：name/description/systemPrompt/model/tools/interceptors/enableLoopingLog |
| **agent 即图节点（HITL 关键）** | `ReactAgent.asNode(boolean includeContents, boolean includeReasoning)` | 嵌入 `StateGraph` 作为节点，与父图共享 `MemorySaver`，中断经 `compiledGraph.invokeAndGetOutput` 上浮（官方 HITL 示例采用此式）|
| flow 编排 | `flow.agent.{FlowAgent, SequentialAgent, ParallelAgent, LlmRoutingAgent, LoopAgent}` + `flow.builder.*` + `flow.strategy.*` + `flow.node.{RoutingNode, ParallelResultAggregator, ...}` | builder/strategy 齐全；Loop 含 Array/Condition/Count 策略；README 称 `RoutingAgent`，jar 路由实现类为 `LlmRoutingAgent` |
| 远程 Agent（**本阶段不做**）| `a2a.{A2aRemoteAgent, AgentCardProvider, RemoteAgentCardProvider, AgentCardWrapper}` | A2A 跨进程，CLAUDE.md 已划后续 |

**结论**：委派 / 编排 / 拦截器子 Agent / agent-as-node 在锁定版**官方已内置且 API 可用**。P6-3 团队中枢协调（hub-and-spoke）用 **graph-core supervisor 模式**（`StateGraph` + `SupervisorNode` + `asNode`，均 1.1.2.3 原语；**注意 `SupervisorAgent` 类不在 1.1.2.3**，见 §5.6 注）；只有 teammate 点对点真并发 swarm（P6-3b）才需自研消息总线。worker 全程复用官方 `ReactAgent`。

> **Context7 交叉印证要点（2026-05-31）**：
> - **agent 即工具**：`AgentTool.getFunctionToolCallback(agent)`（官方 `examples/multiagent-patterns/supervisor`）。
> - **Task 委派**：子 Agent 可 **API 定义**（`ReactAgent.builder()`）或 **Markdown 定义**（目录 + YAML front matter，等价 Claude Code agent frontmatter）；编排器用 `write_todos` + `Task`/`TaskOutput` 委派（官方 `examples/multiagent-patterns/subagent`）。
> - **agent 即图节点（对 §7 风险关键）**：官方 HITL workflow 示例用 `agent.asNode(...)` 把 ReactAgent 嵌进 `StateGraph`，工作流与子 Agent 共享同一 `MemorySaver`，HITL 中断经父 `compiledGraph.invokeAndGetOutput(...)` 上浮（`frameworks/agent-framework/advanced/human-in-the-loop`）。
> - **A2A**：`A2aRemoteAgent` + `AgentCardProvider` 从 **Nacos 注册中心**发现远程 Agent，确认 A2A = 跨进程/分布式，**P6 不做**（`frameworks/agent-framework/advanced/a2a`）。

---

## 3. 官方能力映射与「薄封装」原则（CLAUDE.md §4 红线）

- **委派 / 编排**：必须薄封装 `AgentTool` / `TaskToolsBuilder` / `AgentSpec` / `flow.agent.*`，**禁止自研子 Agent 执行引擎**。
- **子 Agent 本体**：复用现有 `ReactAgent.builder()`（与主 Agent 同一构建器），只换 systemPrompt（任务）/ 工具子集 / 模型。
- **A2A**：本阶段不引入 `a2a.*`，不升级 SAA / Spring AI 版本。
- **只有在官方缺失时才自研**：P6-3 中枢协调用 graph-core supervisor 模式（官方原语薄搭，非自研引擎）；**仅点对点真并发 swarm（P6-3b）的消息总线**官方无等价、才需自研，且必须在该 plan 说明理由。

---

## 4. 横切集成清单（本阶段真正的难度所在）

> 关键事实（已读 `ReActStrategy.buildAgent`）：BaBiQ 的横切层全部是挂在 `ReactAgent.builder()` 上的 SAA **Interceptor + Hook + toolContext**：
> `.interceptors(sandbox, toolObservation, spotlighting, eviction)`、`.streamingInterceptors(streamingTokenUsage)`、`.hooks(hitl?, modelCallLimit, resumeJumpCleanup, tokenUsage)`、`.toolContext({cwd, writableRoots, emitter, sandboxMode, observationContext})`、`.saver(memorySaver)`。
> ⇒ 子 Agent 只要用**同一套 interceptor/hook + 正确的 toolContext** 构建，其内层工具调用就能复用 BaBiQ 横切层。这是整个 P6 可行性的支点。

每个子阶段都必须确认下列横切项在子 Agent 上的行为：

1. **HITL 审批**：子 Agent 内部 `write_file`/`exec_shell`/`apply_patch`/`mcp.*` 是否要审批？默认**要**。难点：嵌套中断（子 Agent 在父工具调用内部触发 HITL，而当前中断/恢复由主 `AgentLoop` + `MemorySaver` + `InterruptionMetadata` 驱动）。**这是 P6-0 spike 的头号问题**（已找到官方 `asNode` 解路径，见 §7）。
2. **沙箱 + PathGuard**：子 Agent 的 cwd / 可写根边界（默认继承父 turn 的 `AgentRunPolicy` 与 writableRoots）。
3. **Spotlighting**：子 Agent 工具输出同样 `<untrusted-data>` 包裹；子 Agent 回传给父 Agent 的「最终结果」也应算 untrusted（防子 Agent 间接注入）。
4. **工具观测 + 运行记录**：子 Agent 工具调用要落 `bq_tool_calls`、计入 turn 观测；需区分「主 Agent 调用」与「子 Agent 调用」（新增归属字段 / 子 turn 概念）。
5. **协议 + 桌面 UI**：需要新协议表示「子 Agent 在跑什么 / 委派了什么 / 子 Agent 结果 / Agent 间消息」。**UI 模型见 §5**——对话始终是主体，委派 / 编排 / 团队都是「对话 + 内联块 + 右侧面板（可展开详情分屏）」，不另开全屏、不隐藏对话。
6. **上下文工程**：子 Agent 有**独立上下文窗口**（不复用父窗口）；结果只以「结果摘要」回灌父 Agent，避免污染父历史（类比 P3 summary-only read path）。
7. **能力装配 + token 统计**：子 Agent 可见的工具子集（P3-5 `CapabilityExposurePlanner` 是否对子 Agent 生效）；子 Agent token 计入本 turn TurnSummary 并标注归属。

---

## 5. UI 模型与原型（2026-05-31 评审确认，已落地 Figma 8 帧）

### 5.1 核心 UI 模型

**对话视图始终是主体，不被替换。** 委派 / 编排 / 团队都是「对话（聊天 + 内联折叠块）+ 右侧面板增强」，按需展开「详情分屏」；**详情分屏（执行时间线 / 拓扑）从不隐藏对话、对话始终可用**。早先"模式 B = 独立全屏工作台"的设想已在评审中**否决**（团队 / 编排若丢掉对话不可接受）。

统一交互骨架（三模式完全一致）：
- **主体**：左侧聊天（你 ↔ 主 Agent），底部输入框始终可用。
- **内联**：聊天流里一个折叠块，标记本轮的委派 / 编排 / 组队。
- **增强**：右侧「常驻紧凑卡 →（可选）点入口展开详情分屏」。展开时对话栏收窄但保留、仍可对话，可「收起」还原。

### 5.2 三种模式（同一套骨架，区别只在右侧内容）

| 模式 | 内联块 | 右侧常驻卡 | 可展开详情分屏 | 入口 |
|---|---|---|---|---|
| 委派（P6-1）| 🤝 委派 | 「子 Agent」卡 | —（子 Agent 转瞬即逝、被主 Agent 调用即返回）| 自动出现 |
| 编排（P6-2）| 🧩 编排 | 「编排」卡（阶段进度，紧凑竖向）| 「编排详情」拓扑 + 节点增删 / 配置 | 「编排详情 ▸」 |
| 团队（P6-3）| 👥 团队 | 「团队」卡 + 消息时间线 | 「团队执行」消息时间线 / 交互详情 | 「团队执行 ▸」/「⚙ 团队设置」 |

### 5.3 Agent 角色（同一批 agent 的不同关系，不是不同 agent）

- **主 Agent**：你对话的本体（BaBiQ）。委派模式里它直接委派；团队模式里它就是 **Leader / 协调者**。
- **子 Agent**：explorer / worker / analyzer / tester，均建立在官方 `ReactAgent` + `AgentTool` / `Task` / `AgentSpec` 上。
- **对话入口始终面向主 Agent**；**子 Agent 不单独对话**。例外：**团队模式**可切换「对话对象」直接喊话某个常驻子 Agent（纠偏 / 插话），Leader 仍是协调者、不绕过编排。委派模式不适用（子 Agent 无常驻会话）。

### 5.4 子 Agent / 编排节点配置（任务 + 模型）

- 每个子 Agent / 编排节点 = 一个官方 `AgentSpec(name, description, systemPrompt=任务, toolNames, model=模型)`。
- **模型默认继承主 Agent，可逐个覆盖**（只读子 Agent 用便宜快的模型、执行子 Agent 用强模型）。官方 `AgentSpec.model` / `SubAgentSpec.model(...)` 直接支持，复用 BaBiQ P2-3 Provider 体系 + `ChatClientFactory.resolveChatModel(providerId)`，零额外造轮子。对标 Claude Code 子 Agent `inherit` 默认、Codex role 可钉 `model`。
- **编排可视化编辑**：点节点 → 配「任务 / 模型 / 删除」；「+ 添加节点」加一个子 Agent 步骤、「✕ / 删除节点」减一个（= 往 flow 增减子 Agent）。
- **分期边界（重要，防膨胀）**：P6 只做"在顺序 / 并行结构里增删节点 + 配置任务 / 模型"；**自由连边 / 任意分支的可视化图编辑器留作后续独立增强**，不在 P6 范围。

### 5.5 Figma 原型清单（页 `35:2`，8 帧）

| # | 帧 | 节点 | 演示要点 |
|---|---|---|---|
| 1 | P6 01 会话-子 Agent 委派 | `184:2` | 对话内委派 + 内联「🤝 委派」块 + 右侧「子 Agent」卡（并行 explorer / worker 待命）|
| 2 | P6 02 会话-编排 | `206:2` | 对话 + 内联「🧩 编排」块 + 右侧「编排」卡（含「编排详情 ▸」入口）|
| 3 | P6 03 会话-团队协作 | `202:2` | 对话 + 内联「👥 团队」块 + 右侧「团队」卡 + 消息时间线（含「⚙ 团队设置」「团队执行 ▸」入口）|
| 4 | P6 04 团队设置-子 Agent 模型 | `211:2` | 每个子 Agent 模型：默认继承主 Agent、可逐个覆盖（tester 覆盖为 flash）|
| 5 | P6 05 团队执行-分屏（展开）| `221:2` | 窄对话（仍可对话）+ 宽执行时间线 + 「对话对象」切换直接喊话子 Agent |
| 6 | P6 06 编排详情-分屏（展开）| `230:2` | 窄对话（仍可对话）+ 宽竖向拓扑（START → explorer →（analyzer ∥ tester）→ 汇总 → END）|
| 7 | P6 07 编排-节点设置（任务+模型）| `237:2` | 点节点 → 弹层配「任务」+「模型」+「删除节点」|
| 8 | P6 08 编排-编辑（增删节点）| `242:2` | 「+ 添加节点」+ 各节点 ✕ 删除（编辑模式）|

文件：`https://www.figma.com/design/frTp55zgrKf4NAWxn6LdI7/?node-id=184-2`

### 5.6 子阶段路线图（累进）

> 命名沿用仓库惯例：`p6-N-<slug>/plan.md` + `codex-handoff.md`，并建 `p6-task-index.md`。

#### P6-0：机制 spike + 协议/数据骨架（先验，**不发布完整功能**）
- **spike 目标**：在隔离实验里对比四种官方 agent 组合机制，回答「BaBiQ 用哪个做基座」：
  - (a) `AgentTool.create/getFunctionToolCallback(ReactAgent)`：最薄，子 Agent = 一个 ToolCallback（官方 supervisor 范式）。
  - (b) `TaskToolsBuilder` + `AgentSpec` + `TaskRepository`：最全，含后台任务 + agent 目录加载（官方 subagent 范式）。
  - (c) `extension.interceptor.SubAgentInterceptor` + `SubAgentSpec`：拦截器式。
  - (d) `ReactAgent.asNode(includeContents, includeReasoning)` 嵌入 `StateGraph` + 共享 `MemorySaver`：**官方 HITL 示例采用此式，最可能解决 HITL 嵌套中断**。
- **必须回答**：① 子 Agent 内层工具调用能否携带父 toolContext（emitter / cwd / observation）；② **HITL 嵌套中断**能否工作（最关键）——先验官方 `asNode` + 共享 `MemorySaver` + `invokeAndGetOutput` 上浮中断能否对接 BaBiQ `approval/respond` 链路；③ 子 Agent token / 观测能否归集；④ 子 Agent 是否需要 per-turn 构建（emitter 时效性）；⑤ 子 Agent / 节点的「任务 + 模型」配置如何承载（`AgentSpec` 落库 / 运行时构建）。
- **产物**：spike 结论文档 + 选定基座；最小协议骨架（委派 / 子 Agent / 节点配置 ThreadItem 草案）。**不接真实业务、不改桌面正式 UI。**

#### P6-1：子 Agent 委派（最小可行，对标 Codex explorer/worker + Claude Code Task）
- 主 Agent 可委派 **1–2 个内置专家子 Agent**（先做只读 `explorer`，再评估 `worker`），结果回传父 Agent。
- 子 Agent 复用 §4 横切层（至少：沙箱、Spotlighting、观测、运行记录；HITL 按 spike 结论）。
- 桌面端（对应 P6 01）：对话 + 内联「委派」块 + 右侧「子 Agent」卡；子 Agent 输出不直接灌父聊天流，只回传摘要。
- 子 Agent 模型默认继承主 Agent、可覆盖（对应 P6 04 的配置语义）。
- system prompt 借鉴 Codex `explorer`/`worker` 的 whenToUse + ownership 规则。

#### P6-2：flow 编排（Sequential / Parallel / Routing / Loop）
- 薄封装 `flow.agent.{Sequential,Parallel,LlmRouting,Loop}Agent`，支持多子 Agent 顺序 / 并行 / 路由 / 循环。
- 桌面端（对应 P6 02 / 06 / 07 / 08）：对话 + 右侧「编排」卡 → 可展开「编排详情」分屏（拓扑 + 各节点状态）；点节点配「任务 / 模型」；「+ 添加 / ✕ 删除」节点（限顺序 / 并行结构，见 §5.4 分期边界）。**对话始终保留**。
- 明确并行下的运行记录归属、token 归集、审批并发语义。
- **2026-06-01 状态**：已落地 `orchestrate_flow` 工具、`OrchestrationItem` 协议、V14 编排持久化、桌面右侧编排卡，并通过后端专项 / 后端 `clean verify` / 桌面专项 / 桌面全量测试；真实模型烟测尚未执行。
- **2026-06-11 修订**：`orchestrate_flow` 只能在显式启动 WorkUnit 且 `ToolContext` 已绑定 goalId 时运行；自然语言要求“用编排”先由 `work_unit_manage` 创建/复用 WorkUnit 并等待用户配置。嵌套 flow 不再复用父 ReAct `RunnableConfig`，避免误用父 checkpoint。

#### P6-3：团队协作（Supervisor 中枢协调，对标 Claude Code swarm 的 hub-and-spoke）
- Leader（主 Agent）迭代协调少量常驻 teammate（explorer/worker），消息经 Leader 中转（hub-and-spoke）；teammate 点对点互发（同时在线、不经 Leader）留 **P6-3b**。
- 桌面端（对应 P6 03 / 05）：对话 + 右侧「团队」卡 + 消息时间线 → 可展开「团队执行」分屏；可切换「对话对象」直接喊话某常驻子 Agent。**对话始终保留**。
- **用 graph-core supervisor 模式薄搭**（`StateGraph` + `SupervisorNode` 路由 teammate/`FINISH` + `ReactAgent.asNode` teammates + 共享 `MemorySaver`）；teammate = 官方 `ReactAgent`，approve-once 组队级授权。**注**：`SupervisorAgent` 类在文档（v1.1.2.2）但**不在 1.1.2.3 jar**（已 `jar tf` 核对），故用 graph-core 原语自搭。
- **仅限本机进程内协作**；跨网络 A2A 仍划后续阶段。
- **2026-06-11 修订**：`coordinate_team` 只能在显式启动 WorkUnit 且 `ToolContext` 已绑定 goalId 时运行；自然语言要求“用团队”先由 `work_unit_manage` 创建/复用 WorkUnit 并等待用户配置。团队 graph 使用自己的 child config，不复用父 turn checkpoint。

#### P6-4：slash / 自然语言 WorkUnit 入口（显式配置与启动闸门）
- slash `/编排`、`/团队` 是确定性快捷入口：服务端 create/reuse/append-goal 后停在待配置 / 待启动，不进入模型执行。
- 普通自然语言中的“使用编排 / flow / 团队 / team / 多 Agent 协作”也走同一心智模型：主 Agent 先调用 `work_unit_manage` 准备 WorkUnit，并提示用户去右侧详情页检查节点/成员、模型、工具权限、写入范围和沙箱策略。
- `orchestrate_flow` / `coordinate_team` 是显式启动阶段的运行工具；缺少 WorkUnit goalId 时必须拒绝裸跑，不写入 `bq_orchestrations` / `bq_teams` 运行记录。

---

## 6. 阶段边界（做 / 不做）

**P6 做**：

- 薄封装官方委派（P6-1）、flow 编排（P6-2）、graph-core supervisor 模式团队协调（P6-3）。
- 子 Agent / 编排节点的「任务 + 模型」配置（默认继承主 Agent、可覆盖）。
- 子 Agent 全程复用 BaBiQ 审批 / 沙箱 / Spotlighting / 观测 / 运行记录 / 协议 UI。
- 必要的新业务表 / 字段（如子 Agent 运行归属、节点配置），并同步 SQL 中文注释 + `bq_schema_comments` + 覆盖测试。

**P6 不做（划后续独立阶段）**：

- `a2a.A2aRemoteAgent` 跨进程 / 跨网络远程 Agent、Agent Card 发布。
- 真 OS 级沙箱隔离（子 Agent 仍用现有 PathGuard + 三档沙箱）。
- **自由连边 / 任意分支的可视化工作流图编辑器**（P6 编排编辑仅限顺序 / 并行结构里增删节点 + 配置）。
- 多模态子 Agent、插件市场 / 用户自定义 agent 目录的完整 UI 编辑。
- 升级 Spring AI / Spring AI Alibaba 版本（保持 `1.1.6` / `1.1.2.3`）。
- 改动 `AgentLoop.invoke` 主循环行数约束以外的无关重构（`AgentLoopLineCountTest` 不得退化）。

---

## 7. 关键风险与未决问题（plan 必须逐条回答）

1. **HITL 嵌套中断（最高风险，但已找到官方解路径）**：子 Agent 在父工具调用内部触发审批时，SAA 的 `MemorySaver` + `InterruptionMetadata` + BaBiQ `approval/respond` 恢复链路能否正确暂停/恢复嵌套图？**Context7 已找到官方 HITL + 嵌套 ReactAgent 范式**（`agent.asNode(...)` + 工作流/子 Agent 共享同一 `MemorySaver`，中断经 `compiledGraph.invokeAndGetOutput` 上浮）；P6-0 spike 应优先验证它能否对接 BaBiQ 的 `approval/respond`，可行则风险大幅下降。退化策略保留：子 Agent 默认 `NEVER` 审批 + 受限只读工具集，写类操作不下放给子 Agent。
2. **emitter 时效性**：子 Agent toolContext 里的 `emitter` 必须指向当前 WebSocket turn ⇒ 子 Agent 很可能需要 per-turn 构建，不能用静态单例 `AgentTool`。
3. **运行记录 / 观测归属**：需要「子 turn」或归属字段区分主/子 Agent 的工具调用与 token。
4. **上下文回灌**：子 Agent 独立窗口的结果如何只以摘要回灌父 Agent，避免污染父历史与上下文预算。
5. **节点 / 子 Agent 配置承载**：`AgentSpec`（任务 + 模型）如何落库与运行时构建；模型覆盖如何走 `ChatClientFactory`。
6. **协议 / UI 表达**：委派 / 子 Agent 状态 / 子 Agent 结果 / Agent 间消息 / 编排拓扑的协议 item 与桌面渲染（按 §5：对话不被替换，详情进右侧可展开分屏）。
7. **并发（P6-2/P6-3）**：并行子 Agent 的 SQLite 写入、审批并发、token 累计线程安全。
8. **能力搜索一致性**：中文 query 命中委派/子 Agent 相关能力，需在 `CapabilityAliasDictionary` 补「子代理 / 委派 / 子任务 / 协作 / 团队 / 编排」等中文别名（CLAUDE.md §4.1）。

---

## 8. 验收方式（每子阶段独立闭环）

- 每子阶段：`cd backend; .\mvnw.cmd clean verify` 全绿 + `cd desktop; .\gradlew.bat test` 全绿 + 子阶段专项测试。
- 涉及新业务表：`SchemaCommentsCoverageTest` 通过。
- 真实模型人工烟测：子 Agent 委派 / 编排 / 协作的真实场景可见可用；HITL、token、运行记录归属、模型覆盖正确。
- 桌面端视觉对齐本阶段 Figma 原型（§5.5）。
- 不得用 `@Disabled` 占位；没有新鲜验证证据前不得声称完成（CLAUDE.md §5/§8）。

---

## 9. 参照源码清单（实施前可复看）

- **Codex**：`core/src/agent/{role.rs, control.rs, registry.rs, status.rs, agent_resolver.rs, builtins/}`、`core/src/codex_delegate.rs`、`core/src/thread_manager.rs`、`core/src/tools/handlers/agent_jobs.rs`（+ `agent_jobs/spawn_agents_on_csv.rs`、`report_agent_job_result.rs`）、crate `agent-graph-store`、`agent-identity`。
- **Claude Code**：`src/tools/Task{Create,Get,List}Tool`、`src/components/agents/.../Task{Output,Stop}Tool`、`src/utils/swarm/{inProcessRunner.ts, permissionSync.ts, teamHelpers.ts, spawnInProcess.ts, backends/*, leaderPermissionBridge.ts, teammate*}`、`src/utils/model/agent.ts`、`src/utils/plugins/loadPluginAgents.ts`。
- **Spring AI Alibaba 1.1.2.3（本机 jar）**：`com.alibaba.cloud.ai.graph.agent.{AgentTool, ReactAgent（含 asNode）}`、`agent.tools.task.{TaskToolsBuilder, AgentSpec, TaskRepository, AgentSpecReactAgentFactory}`、`agent.extension.interceptor.{SubAgentInterceptor, SubAgentSpec}`、`agent.flow.agent.{SequentialAgent, ParallelAgent, LlmRoutingAgent, LoopAgent}`。
- **Spring AI Alibaba 官方示例 / 文档（Context7 核对，2026-05-31）**：`examples/multiagent-patterns/{subagent, supervisor}`（repo `v1.1.2.2`）；文档站 `java2ai.com/docs/frameworks/agent-framework/{tutorials/hooks, advanced/human-in-the-loop, advanced/a2a}`。
- **BaBiQ 现有挂点**：`backend/.../agent/ReActStrategy.java`（横切装配事实源）、`AgentLoop`、`interceptor/*`、`hook/*`、`conversation/items/ThreadItem.java`、`capability/CapabilityExposurePlanner.java`、`model/ChatClientFactory.java`（Provider/模型解析，子 Agent 模型覆盖复用它）。

---

## 10. 下一步

1. 对 P6-2 / P6-3 / P6-4 做真实模型人工烟测：验证自然语言或 slash 先创建/复用 WorkUnit、详情页配置、用户显式启动后 flow/team 执行、右侧面板状态和运行记录归属；自然语言“请使用编排/团队完成”不得直接跑 `orchestrate_flow` / `coordinate_team`。
2. 烟测通过后，优先决策进入 **P6-2b**（运行中逐节点工具审批 / 并发中断恢复补强）或 **P6-4b**（WorkUnit 详情页更强编辑、跳转和批量目标管理）。
3. 每子阶段继续遵循：详细 plan → codex-handoff → TDD 实现 → 自动化 + 真实烟测 → 文档同步（CLAUDE.md / AGENTS.md / p6-task-index）。
