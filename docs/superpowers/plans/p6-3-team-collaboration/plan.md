# P6-3 团队协作（Supervisor 中枢协调）— 正式实现 plan（草案）

> 状态：**草案，待用户确认**（2026-05-31 创建；同日两次按 Context7 + 本地 jar 复核修正——见 §1；2026-06-01 补充入口工具、shared saver 构建路径、直接喊话协议、结构化路由和 P6-2 烟测前置）。最终结论：**用 graph-core 官方原语薄搭 supervisor 模式**，因为 `SupervisorAgent` 类不在锁定的 1.1.2.3 jar 里。
> 隶属：`docs/superpowers/plans/p6-master.md` §5.6 P6-3；**前置：P6-1（委派底座）必须先完成，P6-2（approve-once + 并发归属）推荐先完成**。
> 性质：真实生产实现（TDD），对齐 Figma 原型 `P6 03 会话-团队协作`（`202:2`）/ `P6 05 团队执行-分屏`（`221:2`）。

---

## 0. 一句话目标

让主 Agent 作为 **Leader / Supervisor**，用官方 **graph-core supervisor 模式**（`StateGraph` + 路由 `SupervisorNode` + `ReactAgent.asNode` teammates + 条件边循环至 `FINISH` + 共享 `MemorySaver`）协调少量 teammate 子 Agent（explorer / worker，官方 `ReactAgent`）：Supervisor 看前序记录 → 决定下一个调哪个 teammate（或 `FINISH`）→ 迭代到任务完成；消息经 Leader 中转（hub-and-spoke）。桌面端「团队执行」分屏看协调时间线、可直接喊话某 teammate；写操作用 approve-once + 沙箱；**对话始终是主体**；进程内单机。

---

## 1. 关键前提（Context7 + 本地 jar 复核；**以 jar 为准**）

> **两次修正，钉死事实**：
> ① 早先误称"SAA 无 leader-teammates 协调"——不对。
> ② 随后据 Context7 文档说"薄封装官方 `SupervisorAgent` 类"——**也不对**：`SupervisorAgent` 出现在文档（v1.1.2.2 / 站点），但 **`jar tf` 全量核对锁定的 `1.1.2.3` 全部 jar（agent-framework / graph-core 等）均无 `Supervisor*` 类**。**结论以 jar 为准**（正是 `spike-findings.md` 的教训：文档定方向、精确 API 以本地 jar / `javap` 为准）。

**1.1.2.3 里 supervisor 模式怎么落地**：用 **graph-core 官方原语自搭**（下列类 1.1.2.3 都有，已核对）：

- `StateGraph` + 自定义 `SupervisorNode`（实现 `NodeAction`，路由 `next` = teammate 名 / `FINISH`）+ `addConditionalEdges`（teammate 跑完回到 supervisor，循环至 `FINISH → END`）。
- teammates = 官方 `ReactAgent`，经 `ReactAgent.asNode(includeContents, includeReasoning)` 接入图。
- 共享 `MemorySaver`（与 P6-0 spike / asNode 一致；支持 approve-once 后续恢复）。
- 这正是官方文档 graph-core `multi-agent-supervisor` 示例形态（`SupervisorNode` + 成员 + `next` 路由 + `FINISH`），以及 `LlmRoutingAgent`（单次路由，可作 supervisor 决策的简化备选）。

⇒ **P6-3 = 用 graph-core 官方原语薄搭 supervisor 模式**：不自研协调引擎、不自研消息总线；teammates 仍官方 `ReactAgent`。比"薄封装一个类"略重，但远轻于自研 actor swarm，且**确实在 1.1.2.3 可用**。终止由 `FINISH` + 轮次上限兜底。

- **仍无官方等价、明确推迟**：teammate **点对点真并发 swarm**（同时在线、不经 Leader 互发消息）→ **P6-3b**（需自研消息总线 + 并发 actor + 终止，须先 spike）。

### 1.1 2026-06-01 复核补充（实现前必须落实）

- **入口工具必须明确**：主 Agent 发起团队协作只通过本地工具 `coordinate_team`（ASCII name，默认 `VISIBLE`，走 BaBiQ 现有工具调用、审批、沙箱、运行记录和能力搜索链路）。不把团队协作隐式塞进普通回答，也不让桌面端直接伪造 Agent 行为。
- **shared saver 构建路径必须新增**：当前 P6-1/P6-2 的 `SubAgentRuntimeFactory` 默认给每个 child `ReactAgent` 创建独立 `MemorySaver`；P6-3 使用 `ReactAgent.asNode(...) + StateGraph` 时，父图和所有 teammate 子图必须共享同一个 `MemorySaver`，并通过 `CompileConfig.builder().saverConfig(SaverConfig.builder().register(sharedSaver).build())` 安装到父图。实现时必须新增 `buildChildAgentForGraph(...)` 或等价 `SubAgentRuntimeOptions`，显式传入 shared saver / compile config，不得复用会隐式 new saver 的路径。
- **直接喊话 teammate 必须有后端协议入口**：桌面端「对话对象」切到 teammate 后，发送请求走 `team/message/send` JSON-RPC（或同等明确方法），参数至少包含 `teamId`、`targetAgentName`、`content`、`threadId`。它绕过本轮 supervisor 路由，但仍使用同一 team 记录、同一沙箱快照、同一 run record 归属，并发出 `teamMessage` 协议 item。
- **Supervisor 路由不能依赖自由文本**：`SupervisorRoutingNode` 的 LLM 输出必须解析成结构化 `SupervisorRouteDecision`（字段建议：`next`、`reason`、`confidence`），`next` 只允许 teammate name 或 `FINISH`。解析失败 / 未命中成员时必须进入确定性 fallback（优先 `FINISH` 或按计划中定义的安全默认成员），并写入路由审计。
- **协议 item 形态必须拆清楚**：`team` item 表示团队整体状态和成员快照；`teamMessage` item 表示一条协调时间线消息（supervisor 路由、teammate 结果、直接喊话、错误/终止）。桌面 reducer 从两类 item 聚合右侧团队面板；两类 item 都不进入父聊天消息流。
- **P6-2 真实模型烟测应作为开工前置**：P6-3 复用 P6-2 的 approve-once、可写节点沙箱、运行记录归属和桌面运行面板语义；进入 P6-3 实现前，至少先完成一次 P6-2 真实模型烟测，或在 handoff 中明确记录“未烟测风险”并把 P6-3 首个任务设为补烟测。

---

## 2. 范围（做 / 不做）

**P6-3 做**：

- **graph-core supervisor 模式的 Leader 中枢协调**：Leader（主 Agent）= supervisor 路由节点，迭代路由到 teammate（explorer / worker）直到 `FINISH`。
- **`coordinate_team` 入口工具**：主 Agent 通过该工具提交团队规格、目标、成员、写入范围、轮次上限和沙箱模式；工具内部创建 team 记录、发出 `team` item，并启动 supervisor 图。
- **teammate = 官方 `ReactAgent`**：复用 P6-1/P6-2 子 Agent 底座（`SubAgentRuntimeFactory` + 横切 + 精简上下文 + 沙箱 + 模型可配），经 `asNode` 接入 supervisor 图。
- **approve-once 组队级授权**（复用 P6-2 §5.1 的 4 条语义）：组队 + 写范围 + 沙箱模式运行前整批一次。
- **直接喊话 teammate**（P6 05）：用户可指定把一条消息**直接发给某 teammate**（绕过本次 supervisor 路由），通过 `team/message/send` 或等价明确后端入口执行，supervisor 仍维护整体协调。
- **协议 + UI**：`team` item（Leader + teammates + 状态）+ `teamMessage` item（supervisor 路由 / teammate 结果 / 直接喊话 / 错误）+ 团队执行分屏；中间过程进时间线不灌父聊天流；对话保留。

**P6-3 不做（明确推迟）**：

- **teammate 点对点真并发 swarm** → **P6-3b**（官方无等价，自研消息总线 + 并发 actor + 终止，须先 spike）。
- **运行中逐工具交互审批** → 用 approve-once 替代（supervisor 图与 flow 一样可设计为运行前整批 + 沙箱）。
- 真并发多 teammate（默认迭代一次一个）→ 如需并发，**可选**某轮叠 `ParallelAgent`，非必做。
- `a2a.*` 跨网络、跨 turn 常驻 agent、无界团队规模。
- 升级 SAA / Spring AI；不改 `AgentLoop.invoke` 行数约束（`AgentLoopLineCountTest` 不退化）。

---

## 3. 前置依赖

- **P6-1**：`BabiqAgentSpec` / `SubAgentRuntimeFactory` / `agentDelegation` / 运行记录归属 / 横切复用。
- **P6-2（推荐先完成，且建议先真实模型烟测）**：`FlowApprovalService`（approve-once + §5.1 四条语义）、并发归属——P6-3 复用；P6-3 开工前应至少完成一次 P6-2 真实模型烟测，确认可写节点、沙箱、归属和 UI 语义没有真实模型层面的偏差。
- **P6-0 spike**：`asNode` + 共享 `MemorySaver` 已验证——supervisor 图用同一套图原语；写类 HITL 用 approve-once 规避。

---

## 4. 关键设计决策（D1–D10）

| # | 决策 | 说明 |
|---|---|---|
| D1 | **Leader = graph-core supervisor 图（不是 `SupervisorAgent` 类）** | `StateGraph` + `SupervisorNode`（`NodeAction` 路由 teammate/`FINISH`）+ 条件边循环 + 共享 `MemorySaver`；用 1.1.2.3 确有的官方原语，不自研协调引擎 |
| D2 | **teammate = 官方 `ReactAgent`（复用 P6-1 底座），经 `asNode` 接入** | `SubAgentRuntimeFactory` per-turn 构建 + 横切 + 精简上下文 + 沙箱 + 模型可配（只读 explorer / 可写 worker）；P6-3 必须新增 shared saver 构建路径，所有 teammate 与父图共享同一个 `MemorySaver` |
| D3 | **审批 = approve-once 组队级（复用 P6-2 §5.1）** | 组队 + 写范围 + 沙箱模式运行前整批；运行中不逐工具弹；沙箱硬边界、批准后冻结成员与写范围 |
| D4 | **直接喊话 teammate（P6 05）** | 用户切「对话对象」→ 一条消息直发某 teammate（绕过本次路由）；supervisor 仍是协调者 |
| D5 | **协议 + UI** | 新增 `team` item（团队状态快照）+ `teamMessage` item（协调消息）；团队卡 + 消息时间线 + 团队执行分屏；对话保留 |
| D6 | **终止靠 `FINISH` + 轮次上限兜底** | SupervisorNode 路由到 `FINISH` 即结束；另设轮次上限防意外不收敛 |
| D7 | **并发可选、进程内单机** | 默认迭代协调（一次一个 teammate）；如需并发某轮叠 `ParallelAgent`；点对点真并发 swarm → P6-3b；A2A 留后续 |
| D8 | **入口工具 = `coordinate_team`** | 由主 Agent 显式调用，提交 team spec 并启动 supervisor 图；工具名保持 ASCII，中文走 displayName / description / searchText |
| D9 | **路由输出结构化** | `SupervisorRoutingNode` 使用 `SupervisorRouteDecision(next, reason, confidence)`；`next` 只允许成员名或 `FINISH`；解析失败写审计并走确定性 fallback |
| D10 | **P6-2 烟测前置** | P6-3 复用 P6-2 approve-once 和归属语义，开工前先跑 P6-2 真实模型烟测，或在 handoff 中显式记录风险 |

---

## 5. 后端实现要点（挂点）

> 先读 P6-1/P6-2 产物 + Context7 HITL 示例（`StateGraph` + `asNode` + `addConditionalEdges` + 共享 saver 的真实用法）+ graph-core `multi-agent-supervisor` 示例 + `approval/*`（复用 `FlowApprovalService`）。

| 文件 / 类 | 动作 | 原因 |
|---|---|---|
| `TeamCoordinationTool` / `coordinate_team`（新增本地工具）| **新增** | 主 Agent 发起团队协作的唯一稳定入口；输入 team spec、目标、成员、写范围、轮次上限、沙箱模式；输出简短结果，细节通过 `team` / `teamMessage` 协议 item 展示 |
| `TeamMessageSendHandler`（新增 JSON-RPC，方法名建议 `team/message/send`）| **新增** | 桌面端「对话对象」直发 teammate 的明确后端入口；绕过本轮 supervisor 路由，但仍复用 team 记录、沙箱、归属和协议 item |
| `SupervisorRouteDecision`（新增 record）| **新增** | supervisor 路由的结构化结果，字段建议 `next`、`reason`、`confidence`；用于约束 LLM 输出、测试 fallback 和落库审计 |
| `SupervisorRoutingNode`（新增，实现 `NodeAction`）| **新增** | supervisor 决策：读前序状态 → LLM 决定 `next` = teammate 名 / `FINISH`；规范化路由（参照 graph-core `SupervisorNode`）|
| `TeamCoordinationService`（新增）| **新增** | 用 `StateGraph` 组装：supervisor 节点 + 各 teammate `asNode` + 条件边循环至 `FINISH→END`；共享 `MemorySaver`；per-turn 构建并执行 |
| `BabiqTeamSpec`（新增 record）| **新增** | 团队定义：goal + supervisor prompt + teammate 列表（`BabiqAgentSpec`）+ 轮次上限 |
| `SubAgentRuntimeFactory`（P6-1，复用并扩展）| 修改 | 增加 `buildChildAgentForGraph(...)` 或 `SubAgentRuntimeOptions`：允许传入 shared `MemorySaver` / `CompileConfig` / outputKey；P6-3 不得走会隐式 `new MemorySaver()` 的构建路径 |
| `FlowApprovalService`（P6-2，复用/泛化）| 复用 | 组队级 approve-once（4 条语义）；泛化为 flow / team 通用"运行前整批" |
| `conversation/items/{TeamItem, TeamMessageItem}` | **新增** | `team` 表示团队整体状态快照；`teamMessage` 表示 supervisor 路由、teammate 结果、直接喊话、错误和终止 |
| `ConversationService` / `ItemEmitter` | 修改 | emit team + 协调时间线 item；teammate 中间过程不灌父聊天流 |
| `RunRecordService` / `bq_tool_calls` | 复用 | teammate 工具调用归属（agent_name=teammate）|
| `security/SystemPromptSecurityRule` + `CapabilityAliasDictionary` | 修改 | supervisor prompt 加「协调规则 + 何时 FINISH + worker 文件 ownership」；补「团队/协作/队友/协调/主管」中文别名 |

---

## 6. 桌面端实现要点（对齐 P6 03 / P6 05）

- `protocol/ThreadModels.kt`：`ThreadItem.Team` + `ThreadItem.TeamMessage` + serializer。
- `state/UiModels.kt` / `ChatReducer.kt`：团队状态（成员 + 协调时间线）；从 `team` / `teamMessage` 聚合右侧面板；对话保留；团队执行分屏状态。
- `ui/runtime/*`：右侧「团队」卡（Leader + teammates 状态）+ 协调「消息时间线」+「团队执行 ▸」分屏（P6 05）。
- 对话栏「对话对象」切换（默认 Leader，可选 teammate 直发，P6 05）；选中 teammate 后调用 `team/message/send`，不走普通 `turn/start`；内联「👥 团队」组队块。

---

## 7. 数据库

- 新增 migration（编号以仓库当前最大 V 为准）：
  - `bq_teams`：团队（id / thread_id / turn_id / goal / 成员 / 轮次上限 / status / 起止时间）。
  - `bq_team_messages`：协调消息（team_id / message_id / from_agent / to_agent / message_type / content / route_decision_json / round / ts）。
  - `bq_tool_calls`：复用 P6-1/P6-2 归属字段。
- **每个新表/字段**：SQL 中文注释 + `bq_schema_comments` + Entity 注释 + `SchemaCommentsCoverageTest`（§4 红线）。

---

## 8. TDD 任务清单（先红后绿）

0. P6-2 真实模型烟测或风险记录：执行一次 `orchestrate_flow` 顺序 / 并行 / 路由烟测；若无法执行，必须在 handoff 中记录未烟测风险和原因。
1. `BabiqTeamSpec` + 团队定义/轮次上限校验（`BabiqTeamSpecTest`）。
2. `coordinate_team` 工具输入 schema / capability 别名 / system prompt 使用边界（`TeamCoordinationToolTest`、`CapabilityAliasDictionaryTest`、`SystemPromptSecurityRuleTest`）。
3. shared saver 构建路径：`SubAgentRuntimeFactory` 新增 graph 构建方法，断言父图和 teammate 使用同一个 `MemorySaver` / `SaverConfig`（`SubAgentGraphRuntimeFactoryTest`）。
4. `SupervisorRouteDecision` + `SupervisorRoutingNode` 路由规范化（teammate / `FINISH` / 解析失败 fallback）（`SupervisorRoutingNodeTest`）。
5. `TeamCoordinationService` 用 `StateGraph` + `asNode` teammates + 条件边搭 supervisor 图，迭代路由至 `FINISH`（`TeamCoordinationServiceTest`，假 ChatModel）。
6. teammate 经 `SubAgentRuntimeFactory` 构建 + 横切 + 沙箱（只读 / 可写）（复用 P6-1/P6-2 扩展）。
7. approve-once 组队级（复用 `FlowApprovalService`，4 条语义）（`TeamApprovalTest`）。
8. 终止：`FINISH` + 轮次上限兜底（`TeamTerminationTest`）。
9. 直接喊话 teammate：`team/message/send` handler + 协议 item + 归属（`DirectTeammateMessageTest`）。
10. 协议 `team` + `teamMessage` item（`ThreadItemJsonTest` + `ConversationServiceTest`）。
11. DB team / message 记录 + migration（`SchemaCommentsCoverageTest` + `TeamRepositoryTest`）。
12. 桌面端协议 + reducer + 团队卡/消息时间线/团队执行分屏/对话对象切换（`*ThreadItemJsonTest` / `*ChatReducerTest` / `*TeamSectionTest`）。
13. 端到端 IT（`TeamCoordinationIT`：Leader 协调 explorer + worker，approve-once 一次 → 迭代路由 → `FINISH` 终止 → 归属落库 → 对话保留）。

---

## 9. 验收

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=BabiqTeamSpecTest,TeamCoordinationToolTest,SubAgentGraphRuntimeFactoryTest,SupervisorRoutingNodeTest,TeamCoordinationServiceTest,TeamApprovalTest,TeamTerminationTest,DirectTeammateMessageTest,ThreadItemJsonTest,TeamRepositoryTest,SchemaCommentsCoverageTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*TeamSectionTest"
.\gradlew.bat test
```

- `clean verify` 全绿（含 IT）；`SchemaCommentsCoverageTest` 过；`AgentLoopLineCountTest` 不退化。
- 真实模型人工烟测：先确认 P6-2 `orchestrate_flow` 烟测已执行或风险已记录；再组建 Leader 协调 explorer + worker → 组队 approve-once 一次 → supervisor 迭代路由（explorer 探查、worker 写受沙箱）→ `FINISH` 正常终止（不空转）→ 运行记录按 teammate 归属 → `team/message/send` 可直接喊话某 teammate → 团队执行分屏 + 协调时间线可见 → **对话栏全程可用**；视觉对齐原型 P6 03 / P6 05。

---

## 10. 风险与缓解

1. **supervisor 图自搭复杂度**：比"薄封装一个类"重（要搭 StateGraph + 条件边 + asNode + 共享 saver）。缓解：Task 2/3 先把最小 supervisor 图跑通再加业务；严格参照官方 `multi-agent-supervisor` 示例 + P6-0 spike 的 asNode/saver 经验。
2. **shared saver 漏装**：如果 teammate 子图和父图不是同一个 saver，会复现 P6-0 spike 中的 checkpoint 问题。缓解：新增 `SubAgentGraphRuntimeFactoryTest`，断言父 `StateGraph` 的 `SaverConfig` 和子 `ReactAgent` 使用同一个 saver；禁止 P6-3 走隐式 `new MemorySaver()` 路径。
3. **不收敛 / 空转**：缓解：`FINISH` + **轮次上限兜底** + supervisor prompt 明确收尾条件。
4. **路由自由文本不可控**：缓解：结构化 `SupervisorRouteDecision` + whitelist 校验 + fallback + 路由审计。
5. **成本**：多轮协调放大 token。缓解：轮次上限 + 团队规模 ≤3 + token 归属可见。
6. **审批**：approve-once 组队级（复用 P6-2），沙箱硬边界兜底。
7. **并行写冲突**（若叠 ParallelAgent）：借鉴 Codex worker「文件 ownership」分配不重叠职责。
8. **文档≠版本**：所有 SAA API 实现前必须 `javap` / `jar tf` 核对在 1.1.2.3 存在（本阶段已踩过 `SupervisorAgent` 坑）。
9. **范围蔓延**：点对点真并发 swarm（P6-3b）/ A2A / 跨 turn 常驻 / 无界团队坚决不混入。

---

## 11. 参照

- **前置**：`p6-1-subagent-delegation/`、`p6-2-flow-orchestration/`（approve-once §5.1）、`p6-0-mechanism-spike/`（asNode + 共享 saver + 文档/jar 一致性教训）。
- **master**：`p6-master.md`（§5 UI 模型与原型、§7 风险）。
- **原型**：`P6 03`（`202:2`）/ `P6 05`（`221:2`）。
- **官方（Context7 + jar 核对）**：graph-core `multi-agent-supervisor`（`SupervisorNode` 路由 `next`/`FINISH`，1.1.2.3 可用的 `StateGraph`/`NodeAction`/`addConditionalEdges`/`asNode` 原语）。**注意**：`SupervisorAgent` 类（文档 v1.1.2.2）**不在 1.1.2.3**，故用原语自搭；`LlmRoutingAgent`（1.1.2.3 有）可作 supervisor 决策的简化备选；Spring AI `ToolContext` 可承载 cwd/emitter/沙箱等运行态上下文且不会发给模型。
- **借鉴源（概念，不照搬）**：Claude Code swarm（leader + teammates hub-and-spoke；点对点留 P6-3b）、Codex worker（文件 ownership）。

---

## 12. 下一步

1. 本 plan 由用户确认（graph-core supervisor 模式自搭 + `coordinate_team` 入口工具 + shared saver 构建路径 + approve-once + 终止兜底 + 点对点真并发留 P6-3b）。
2. 确认后先补 P6-2 真实模型烟测记录，再写 `p6-3-team-collaboration/codex-handoff.md`，按 §8 Task 0→13 TDD 实现（**前置 P6-1，推荐 P6-2 先完成且已烟测**）。
3. P6-3 闭环后：P6 三层（委派 / 编排 / 团队-中枢协调）齐备；后续可评估 **P6-3b 点对点真并发 swarm**、A2A 远程、P6-2b 运行中逐工具审批等独立增强。
