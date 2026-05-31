# P6-2 flow 编排 — 正式实现 plan（草案）

> 状态：**草案，待用户确认**（2026-05-31 创建）。
> 隶属：`docs/superpowers/plans/p6-master.md` §5.6 P6-2；**前置：P6-1（委派底座）已完成**、`p6-0-mechanism-spike/`（基座结论）。
> 性质：真实生产实现（TDD），对齐 Figma 原型 `P6 02 会话-编排`（`206:2`）/ `P6 06 编排详情-分屏`（`230:2`）/ `P6 07 节点设置`（`237:2`）/ `P6 08 增删节点`（`242:2`）。

---

## 0. 一句话目标

让主 Agent 能把一个多步任务**编排成多个子 Agent 的工作流**（顺序 / 并行 / 路由），**节点可读可写、并行做真实工作**（探查 / 分析 / 实现 / 跑测试），薄封装 SAA 官方 `SequentialAgent` / `ParallelAgent` / `LlmRoutingAgent`；写操作受 BaBiQ 沙箱 + **运行前整体批准**管；桌面端在「编排详情」分屏可视化拓扑与各节点状态、可配置每节点「任务 + 模型」、可在顺序/并行结构里增删节点；**对话视图始终是主体**。

---

## 1. 范围（做 / 不做）

**P6-2 做**：

- **子 Agent 的 flow 编排（节点可读可写，并行做真实工作）**：薄封装 `SequentialAgent`（顺序）+ `ParallelAgent`（并行 + 合并）+ `LlmRoutingAgent`（LLM 路由），支持嵌套组合（如 explorer 探查 → (worker 实现 ∥ tester 跑测试) → 汇总 router）。
- **每节点 = `BabiqAgentSpec`**：复用 P6-1 的 spec + `SubAgentRuntimeFactory` + 横切，per-turn 构建；节点可配「任务（systemPrompt）+ 模型（默认继承可覆盖）」。
- **编排详情分屏 UI**：拓扑可视化 + 各节点状态；点节点配任务/模型；「+ 添加节点 / ✕ 删除节点」（**限顺序/并行结构内**）。
- **并发归属**：`ParallelAgent` 并行节点的运行记录 / token / 工具调用**线程安全归属**（区分各节点）。
- **写操作审批 = 运行前整体批准 + 沙箱**：含写节点的 flow 运行**前**由用户整体批准一次（或 NEVER / 工作区可写策略），运行中**不**逐工具弹审批；写边界由 PathGuard / 沙箱兜底。
- **协议 + 卡片**：编排卡（编排详情 ▸ 入口）+ 编排拓扑/节点状态协议 item + 运行前整体批准的 approval。

**P6-2 不做（明确推迟）**：

- **运行中逐工具交互审批 + 并行分支并发中断**：flow agent `.invoke()` 是 run-to-completion、不 surface HITL 中断（Context7 确认）；"运行中每次写都中途弹审批、暂停某条并行分支等用户再恢复"需 `StateGraph + asNode + 共享 saver` + **并发中断处理**（spike 只验单中断、未验并发）= **P6-2b**。P6-2 用「运行前整体批准 + 沙箱」替代（见 §4 D1）。
- **`LoopAgent`（循环）**：评估后续纳入或独立增强，P6-2 先做 Sequential / Parallel / Routing（原型拓扑）。
- **自由连边 / 任意分支的可视化图编辑器**（master §6 已划后续）；**用户自定义 agent 目录**；**实时 team（P6-3）**；`a2a.*`；升级 SAA/Spring AI。
- 不改 `AgentLoop.invoke` 行数约束（`AgentLoopLineCountTest` 不退化）。

> **理由**：P6-2 用高层 flow agent（`SequentialAgent` 等）编排**可读可写**节点做并行真实工作；写操作受沙箱 + **运行前整体批准**管，避开"运行中逐工具暂停审批"（那需 asNode + 并发中断 = P6-2b）。这样既能并行写、又不碰最难的并发 HITL。

---

## 2. 前置依赖

- **P6-1 必须先完成**：`BabiqAgentSpec`、`SubAgentRuntimeFactory`（per-turn 子 Agent + 横切 + 精简上下文）、`agentDelegation` 协议、运行记录归属、子 Agent 防护机制——P6-2 复用并按节点类型扩展（只读节点沿用只读兜底、可写节点用沙箱 + 运行前整体批准）。
- **P6-0 spike 结论**：写类 HITL 需 asNode（故 P6-2 不做写类节点）。

---

## 3. Context7 已确认的 flow.agent API（grounding，直接用）

> 来源：`java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent`，对齐锁定版 `1.1.2.3`（精确 API 以本地 jar 为准）。

- **顺序**：`SequentialAgent.builder().name().description().subAgents(List.of(a, b)).build()`；每个子 Agent `.outputKey("k")`，后一节点可读 `{k}`；`.invoke(input) → Optional<OverAllState>`。
- **并行**：`ParallelAgent.builder().name().description().mergeOutputKey("merged").subAgents(List.of(...)).mergeStrategy(new ParallelAgent.DefaultMergeStrategy()).build()`；并发执行、按 mergeStrategy 合并。
- **路由**：`LlmRoutingAgent.builder().name().description().model(chatModel).subAgents(List.of(...)).build()`；LLM 按请求选一个子 Agent。
- **嵌套组合**：`SequentialAgent.subAgents(List.of(parallelAgent, analysisAgent, routingAgent))` 合法（hybrid workflow）。
- **审批边界（关键）**：flow agent 是 `.invoke()` run-to-completion，**不通过 `invokeAndGetOutput` surface `InterruptionMetadata`**——即运行中不能逐工具暂停弹审批。⇒ P6-2 **节点可写**，写节点用**运行前整体批准 + 沙箱**（高层 flow agent 直接支持）；"运行中逐工具审批 + 并行并发中断"须降到 `StateGraph + asNode`（= **P6-2b**，不在 P6-2）。

---

## 4. 关键设计决策（D1–D8）

| # | 决策 | 说明 |
|---|---|---|
| D1 | **节点可读可写（并行真实工作）；写受沙箱 + 运行前整体批准** | 节点可用写工具（worker 式）；含写 flow 运行**前**整体批准一次（或 NEVER/工作区可写），运行中不逐工具弹；写边界 PathGuard/沙箱兜底。"运行中逐工具审批 + 并发中断" → P6-2b |
| D2 | **拓扑 = Sequential / Parallel / LlmRouting** | 薄封装三类 flow agent；`LoopAgent` 评估后续。增删节点**限这些结构内**（非自由连边）|
| D3 | **每节点复用 P6-1 子 Agent 底座** | 节点 = `BabiqAgentSpec`（任务 + 模型 + 工具集，**只读 explorer 或可写 worker**）；`SubAgentRuntimeFactory` per-turn 构建 + 横切 + 精简上下文 + 沙箱；模型默认继承可覆盖 |
| D4 | **编排由主 Agent 发起（delegation 的升级）** | 主 Agent 面对多步分析任务时编排一个 flow（从单 explorer → 多节点拓扑）；用户可在「编排详情」查看/编辑 flow（节点配置 + 增删）。flow per-turn 构建 |
| D5 | **协议 = 编排 + 节点 item** | 新增 `orchestration` item（flowId / topology / 节点列表 / 整体状态）+ 复用/扩展 `agentDelegation` 表示每节点状态（running/completed/failed + summary）；中间过程不灌父聊天流 |
| D6 | **并发安全（ParallelAgent）** | 并行节点的 `bq_tool_calls` 归属、token 累计、运行记录写入必须线程安全；每节点独立 `delegation_id` / `agent_name`；emitter 并发发 item 需有序/加锁 |
| D7 | **节点配置 + 增删（P6 07/08）** | 节点「任务 + 模型」可编辑并持久化；「+ 添加 / ✕ 删除」节点限顺序/并行结构；编辑后下次运行生效 |
| D8 | **UI：编排卡 + 编排详情分屏（P6 02/06）** | 对话保留；右侧「编排」卡（阶段进度）→「编排详情 ▸」展开拓扑分屏；分屏从不隐藏对话 |

---

## 5. 后端实现要点（挂点）

> 先读：P6-1 产物（`BabiqAgentSpec` / `SubAgentRuntimeFactory` / `agentDelegation` / 归属字段）、`ReActStrategy`、`persistence/*`、`conversation/items/ThreadItem.java`。

| 文件 / 类 | 动作 | 原因 |
|---|---|---|
| `FlowOrchestrationService`（新增）| **新增** | 按 flow 定义（节点 specs + 拓扑）用 `SequentialAgent`/`ParallelAgent`/`LlmRoutingAgent` builder 组装并 `.invoke(...)`；每节点经 `SubAgentRuntimeFactory` per-turn 构建 |
| `BabiqFlowSpec` / `BabiqFlowNode`（新增 record）| **新增** | 描述一个 flow：topology(SEQUENTIAL/PARALLEL/ROUTING) + 节点列表（每节点 = `BabiqAgentSpec` + order/branch）|
| `SubAgentRuntimeFactory`（P6-1，复用/扩展）| 修改 | 支持为 flow 每节点构建子 Agent（含精简上下文 + 横切 + 模型解析）|
| `conversation/items/{OrchestrationItem, AgentDelegationItem}` | **新增/扩展** | `orchestration` item（flow 拓扑 + 节点状态）；节点状态复用 `agentDelegation` |
| `ConversationService` / `ItemEmitter` | 修改 | `emitOrchestration(...)` + 节点状态 added/updated；**并发节点发 item 有序/线程安全** |
| `RunRecordService` / `bq_tool_calls` | 修改 | 并行节点工具调用归属（agent_name=节点名 / delegation_id / parent）线程安全写入 |
| `FlowApprovalService`（新增）| **新增** | 含写节点的 flow 运行**前**生成一次「整体批准」approval/request；批准后整轮按授权执行（运行中不逐工具弹）；复用 BaBiQ `approval/*` 链路。**approve-once 语义见 §5.1 的 4 条（安全关键）** |
| `model/ChatClientFactory` | 复用 | 每节点模型按 spec 解析（inherit/override）|
| `security/SystemPromptSecurityRule` + `CapabilityAliasDictionary` | 修改 | 主 Agent prompt 加「何时编排 flow」；补「编排/工作流/流程/顺序/并行/路由」中文别名 |

### 5.1 approve-once（运行前整体批准）语义 — `FlowApprovalService` 必须满足（安全关键）

> **核心定义：approve-once = 沙箱内 + 已声明范围内的「一次性执行授权」，不是 god-mode。** 沙箱是兜底硬边界。

1. **沙箱仍是硬边界**：approve-once **≠ 关沙箱**。批准后所有操作仍受 `PathGuard` / 沙箱模式（只读 / 工作区可写 / 完全访问）约束；工作区外写、网络、危险命令该禁还禁。沙箱模式在批准**前**由用户选定，approve-once 不提升沙箱档位。
2. **弹窗列清授权范围**：approval/request 必须展示——涉及哪些**节点**、各自**任务**、用到的**工具**（读 / 写 / 命令）、**写路径范围**、**沙箱模式**。一键批准的前提是「看得见批的是什么」，不签空白支票。
3. **批准后 flow 冻结**：批准即固定该 flow 的节点 / 拓扑 / 工具 / 写范围；运行中**不得**加节点、换工具或扩大写范围。要改 → 停止 flow、改编排、**重新批准**。
4. **不可逆危险操作由沙箱禁止**：删工作区外文件 / `git push` / 联网等，即使 flow 已批准也不应能做——由**沙箱层拒绝**，而非靠这次审批放行；除非用户显式选「完全访问」沙箱并知风险。

---

## 6. 桌面端实现要点（对齐 P6 02/06/07/08）

- `protocol/ThreadModels.kt`：新增 `ThreadItem.Orchestration`（+ 节点状态）+ serializer 分支。
- `state/UiModels.kt` / `ChatReducer.kt`：编排状态（节点 + 拓扑 + 进度）；编排卡 + 编排详情分屏状态；对话保留。
- `ui/runtime/*`：「编排」卡（阶段进度）+「编排详情」分屏（竖向拓扑 + 节点状态，对齐 P6 06）。
- 节点设置弹层（P6 07）：点节点 → 配任务 + 模型 + 删除；「+ 添加节点」（P6 08）。
- 编辑动作回写后端 flow 定义（见 §7）。

---

## 7. 数据库（flow 定义持久化 + 并发归属）

- 新增 migration（编号以仓库当前最大 V 为准）：
  - `bq_orchestrations`：一个 flow 的定义/运行（id / thread_id / turn_id / topology / status / 起止时间）。
  - `bq_orchestration_nodes`：节点（orchestration_id / name / role / task(systemPrompt) / model_policy / order / branch / status）——支撑 P6 07/08 的配置与增删持久化。
  - `bq_tool_calls`：复用 P6-1 的 `agent_name`/`parent_agent_name`/`delegation_id` 归属（并行节点并发写）。
- **每个新表/字段**：SQL 中文注释 + `bq_schema_comments` + Entity 中文注释 + `SchemaCommentsCoverageTest` 覆盖（CLAUDE.md §4 红线）。

---

## 8. TDD 任务清单（先红后绿）

1. `BabiqFlowSpec` / `BabiqFlowNode` 模型 + 拓扑校验（`BabiqFlowSpecTest`）。
2. `FlowOrchestrationService` 组装 Sequential/Parallel/Routing + `.invoke`（`FlowOrchestrationServiceTest`，假 ChatModel：顺序串联 outputKey、并行合并 mergeOutputKey、路由选择）。
3. 每节点经 `SubAgentRuntimeFactory` 构建 + 横切 + 沙箱 + 精简上下文（`FlowNodeRuntimeTest`：只读 explorer 与可写 worker 节点都正确装配）。
4. **并发安全**：`ParallelAgent` 并行节点的归属/token/运行记录线程安全（`FlowConcurrencyAttributionTest`）。
5. 协议 `orchestration` + 节点 item（后端 `ThreadItemJsonTest` + `ConversationServiceTest`）。
6. flow 定义 + 节点配置持久化 + migration（`SchemaCommentsCoverageTest` + `OrchestrationRepositoryTest`）。
7. 节点模型继承/覆盖（复用 P6-1 `SubAgentModelResolutionTest` 扩展）。
8. 桌面端协议 + reducer + 编排详情/节点设置/增删渲染（`*ThreadItemJsonTest` / `*ChatReducerTest` / `*OrchestrationSectionTest`）。
9. 主 Agent prompt + 中文别名（`SystemPromptSecurityRuleTest` / `CapabilityAliasDictionaryTest`）。
10. 端到端 IT（`FlowOrchestrationIT`：编排 explorer 探查→(worker 实现∥tester 跑测试)→汇总，运行前整体批准、并发归属落库、写受沙箱、协议 item、对话保留）。

---

## 9. 验收

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=BabiqFlowSpecTest,FlowOrchestrationServiceTest,FlowNodeRuntimeTest,FlowConcurrencyAttributionTest,ThreadItemJsonTest,OrchestrationRepositoryTest,SubAgentModelResolutionTest,SchemaCommentsCoverageTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*OrchestrationSectionTest"
.\gradlew.bat test
```

- `clean verify` 全绿（含 IT）；`SchemaCommentsCoverageTest` 过；`AgentLoopLineCountTest` 不退化。
- 真实模型人工烟测：主 Agent 对"实现某模块 + 跑测试"类任务编排 flow（explorer 探查 → (worker 实现 ∥ tester 跑测试) → 汇总）；**含写节点的 flow 运行前整体批准一次**，运行中并行执行（含写）、运行记录**按节点归属**、无并发串味、写操作受沙箱边界约束；编排详情分屏显示拓扑 + 各节点状态；点节点改任务/模型、增删节点生效；**对话栏全程可用**；视觉对齐原型 P6 02/06/07/08。

---

## 10. 风险与缓解

1. **并发归属串味（最高风险）**：ParallelAgent 并行节点同时写 `bq_tool_calls`/token/emitter → 必须线程安全 + 每节点独立 delegation_id；Task 4 专测。
2. **审批边界 / approve-once 安全**：P6-2 含写节点用「运行前整体批准 + 沙箱」，运行中不逐工具弹。**关键**：approve-once = 沙箱内 + 已声明范围的一次性授权（**非 god-mode**）——沙箱硬边界、弹窗列清范围、批准后 flow 冻结、危险不可逆操作由沙箱禁止（4 条详见 §5.1）。"运行中逐工具审批 + 并行并发中断"留 P6-2b。
3. **emitter 到每节点**：flow 每节点 per-turn 构建并注入本轮 emitter；并行下 item 顺序/线程安全。
4. **flow 定义持久化与运行解耦**：编辑的 flow 定义（bq_orchestration_nodes）与运行实例分离，避免运行中改定义导致不一致。
5. **并行写冲突**：多个 worker 并行写可能冲突 → **借鉴 Codex worker「文件 ownership」**：节点 spec / system prompt 给各并行写节点分配**不重叠的文件 / 职责范围**，并告知"不是唯一在改代码者、不回滚他人改动"。
6. **范围蔓延**：运行中逐工具审批 / 并发中断（P6-2b）/ Loop / 自由图编辑器 / team 坚决不混入 P6-2。

---

## 11. 参照

- **前置**：`p6-1-subagent-delegation/`（子 Agent 底座）、`p6-0-mechanism-spike/spike-findings.md`（HITL 边界）。
- **master**：`p6-master.md`（§5 UI 模型与原型、§7 风险）。
- **原型**：`P6 02`（`206:2`）/ `P6 06`（`230:2`）/ `P6 07`（`237:2`）/ `P6 08`（`242:2`）。
- **官方（Context7 核对）**：`agent-framework/advanced/multi-agent`（SequentialAgent / ParallelAgent / LlmRoutingAgent / hybrid 构建）、`.../human-in-the-loop`（写类降到 asNode 的边界）。
- **借鉴源（概念，不照搬）**：Codex 多 Agent 编排（agent_jobs / 并行 explorer）、Claude Code 多 agent 组合——BaBiQ 薄封装 SAA 官方 flow agent，不自研编排引擎。
- **BaBiQ 挂点**：P6-1 产物 + `ReActStrategy` / `persistence/*` / `conversation/items/*` / `ChatClientFactory`。

---

## 12. 下一步

1. 本 plan 由用户确认（尤其"节点可读可写、写用运行前整体批准、运行中逐工具审批 + 并发中断留 P6-2b、Loop 后续"边界）。
2. 确认后写 `p6-2-flow-orchestration/codex-handoff.md`，再按 §8 Task 1→10 TDD 实现（**前置 P6-1 完成**）。
3. P6-2 闭环后：补 **P6-2b（编排运行中逐工具审批 + 并行并发中断，asNode）** 或进 **P6-3 实时 team 协作**；单 Agent 写类 HITL 由 **P6-1b** 覆盖。
