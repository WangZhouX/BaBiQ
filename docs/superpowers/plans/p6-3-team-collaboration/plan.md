# P6-3 团队协作（Supervisor 中枢协调）— 正式实现 plan（草案）

> 状态：**草案，待用户确认**（2026-05-31 创建；同日两次按 Context7 + 本地 jar 复核修正——见 §1。最终结论：**用 graph-core 官方原语薄搭 supervisor 模式**，因为 `SupervisorAgent` 类不在锁定的 1.1.2.3 jar 里）。
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

---

## 2. 范围（做 / 不做）

**P6-3 做**：

- **graph-core supervisor 模式的 Leader 中枢协调**：Leader（主 Agent）= supervisor 路由节点，迭代路由到 teammate（explorer / worker）直到 `FINISH`。
- **teammate = 官方 `ReactAgent`**：复用 P6-1/P6-2 子 Agent 底座（`SubAgentRuntimeFactory` + 横切 + 精简上下文 + 沙箱 + 模型可配），经 `asNode` 接入 supervisor 图。
- **approve-once 组队级授权**（复用 P6-2 §5.1 的 4 条语义）：组队 + 写范围 + 沙箱模式运行前整批一次。
- **直接喊话 teammate**（P6 05）：用户可指定把一条消息**直接发给某 teammate**（绕过本次 supervisor 路由），supervisor 仍维护整体协调。
- **协议 + UI**：团队卡（Leader + teammates + 状态）+ 协调消息时间线（supervisor 路由 + teammate 结果）+ 团队执行分屏；中间过程进时间线不灌父聊天流；对话保留。

**P6-3 不做（明确推迟）**：

- **teammate 点对点真并发 swarm** → **P6-3b**（官方无等价，自研消息总线 + 并发 actor + 终止，须先 spike）。
- **运行中逐工具交互审批** → 用 approve-once 替代（supervisor 图与 flow 一样可设计为运行前整批 + 沙箱）。
- 真并发多 teammate（默认迭代一次一个）→ 如需并发，**可选**某轮叠 `ParallelAgent`，非必做。
- `a2a.*` 跨网络、跨 turn 常驻 agent、无界团队规模。
- 升级 SAA / Spring AI；不改 `AgentLoop.invoke` 行数约束（`AgentLoopLineCountTest` 不退化）。

---

## 3. 前置依赖

- **P6-1**：`BabiqAgentSpec` / `SubAgentRuntimeFactory` / `agentDelegation` / 运行记录归属 / 横切复用。
- **P6-2（推荐先完成）**：`FlowApprovalService`（approve-once + §5.1 四条语义）、并发归属——P6-3 复用。
- **P6-0 spike**：`asNode` + 共享 `MemorySaver` 已验证——supervisor 图用同一套图原语；写类 HITL 用 approve-once 规避。

---

## 4. 关键设计决策（D1–D7）

| # | 决策 | 说明 |
|---|---|---|
| D1 | **Leader = graph-core supervisor 图（不是 `SupervisorAgent` 类）** | `StateGraph` + `SupervisorNode`（`NodeAction` 路由 teammate/`FINISH`）+ 条件边循环 + 共享 `MemorySaver`；用 1.1.2.3 确有的官方原语，不自研协调引擎 |
| D2 | **teammate = 官方 `ReactAgent`（复用 P6-1 底座），经 `asNode` 接入** | `SubAgentRuntimeFactory` per-turn 构建 + 横切 + 精简上下文 + 沙箱 + 模型可配（只读 explorer / 可写 worker）|
| D3 | **审批 = approve-once 组队级（复用 P6-2 §5.1）** | 组队 + 写范围 + 沙箱模式运行前整批；运行中不逐工具弹；沙箱硬边界、批准后冻结成员与写范围 |
| D4 | **直接喊话 teammate（P6 05）** | 用户切「对话对象」→ 一条消息直发某 teammate（绕过本次路由）；supervisor 仍是协调者 |
| D5 | **协议 + UI** | 新增 `team` item（成员 + 状态）+ 协调消息（supervisor 路由 / teammate 结果，复用/扩展 `agentDelegation`）；团队卡 + 消息时间线 + 团队执行分屏；对话保留 |
| D6 | **终止靠 `FINISH` + 轮次上限兜底** | SupervisorNode 路由到 `FINISH` 即结束；另设轮次上限防意外不收敛 |
| D7 | **并发可选、进程内单机** | 默认迭代协调（一次一个 teammate）；如需并发某轮叠 `ParallelAgent`；点对点真并发 swarm → P6-3b；A2A 留后续 |

---

## 5. 后端实现要点（挂点）

> 先读 P6-1/P6-2 产物 + Context7 HITL 示例（`StateGraph` + `asNode` + `addConditionalEdges` + 共享 saver 的真实用法）+ graph-core `multi-agent-supervisor` 示例 + `approval/*`（复用 `FlowApprovalService`）。

| 文件 / 类 | 动作 | 原因 |
|---|---|---|
| `SupervisorRoutingNode`（新增，实现 `NodeAction`）| **新增** | supervisor 决策：读前序状态 → LLM 决定 `next` = teammate 名 / `FINISH`；规范化路由（参照 graph-core `SupervisorNode`）|
| `TeamCoordinationService`（新增）| **新增** | 用 `StateGraph` 组装：supervisor 节点 + 各 teammate `asNode` + 条件边循环至 `FINISH→END`；共享 `MemorySaver`；per-turn 构建并执行 |
| `BabiqTeamSpec`（新增 record）| **新增** | 团队定义：goal + supervisor prompt + teammate 列表（`BabiqAgentSpec`）+ 轮次上限 |
| `SubAgentRuntimeFactory`（P6-1，复用）| 复用 | 构建每个 teammate（横切 + 沙箱 + 模型）供 `asNode` 接入 |
| `FlowApprovalService`（P6-2，复用/泛化）| 复用 | 组队级 approve-once（4 条语义）；泛化为 flow / team 通用"运行前整批" |
| `conversation/items/{TeamItem, …}` | **新增** | `team`（成员 + 状态）+ 协调消息（复用 `agentDelegation` 表示 teammate 调用）|
| `ConversationService` / `ItemEmitter` | 修改 | emit team + 协调时间线 item；teammate 中间过程不灌父聊天流 |
| `RunRecordService` / `bq_tool_calls` | 复用 | teammate 工具调用归属（agent_name=teammate）|
| `security/SystemPromptSecurityRule` + `CapabilityAliasDictionary` | 修改 | supervisor prompt 加「协调规则 + 何时 FINISH + worker 文件 ownership」；补「团队/协作/队友/协调/主管」中文别名 |

---

## 6. 桌面端实现要点（对齐 P6 03 / P6 05）

- `protocol/ThreadModels.kt`：`ThreadItem.Team`（+ 协调消息）+ serializer。
- `state/UiModels.kt` / `ChatReducer.kt`：团队状态（成员 + 协调时间线）；对话保留；团队执行分屏状态。
- `ui/runtime/*`：右侧「团队」卡（Leader + teammates 状态）+ 协调「消息时间线」+「团队执行 ▸」分屏（P6 05）。
- 对话栏「对话对象」切换（默认 Leader，可选 teammate 直发，P6 05）；内联「👥 团队」组队块。

---

## 7. 数据库

- 新增 migration（编号以仓库当前最大 V 为准）：
  - `bq_teams`：团队（id / thread_id / turn_id / goal / 成员 / 轮次上限 / status / 起止时间）。
  - `bq_team_messages`：协调消息（team_id / from / to / content / round / ts）。
  - `bq_tool_calls`：复用 P6-1/P6-2 归属字段。
- **每个新表/字段**：SQL 中文注释 + `bq_schema_comments` + Entity 注释 + `SchemaCommentsCoverageTest`（§4 红线）。

---

## 8. TDD 任务清单（先红后绿）

1. `BabiqTeamSpec` + 团队定义/轮次上限校验（`BabiqTeamSpecTest`）。
2. `SupervisorRoutingNode` 路由规范化（teammate / `FINISH`）（`SupervisorRoutingNodeTest`）。
3. `TeamCoordinationService` 用 `StateGraph` + `asNode` teammates + 条件边搭 supervisor 图，迭代路由至 `FINISH`（`TeamCoordinationServiceTest`，假 ChatModel）。
4. teammate 经 `SubAgentRuntimeFactory` 构建 + 横切 + 沙箱（只读 / 可写）（复用 P6-1/P6-2 扩展）。
5. approve-once 组队级（复用 `FlowApprovalService`，4 条语义）（`TeamApprovalTest`）。
6. 终止：`FINISH` + 轮次上限兜底（`TeamTerminationTest`）。
7. 直接喊话 teammate（`DirectTeammateMessageTest`）。
8. 协议 `team` + 协调消息 item（`ThreadItemJsonTest` + `ConversationServiceTest`）。
9. DB team / message 记录 + migration（`SchemaCommentsCoverageTest` + `TeamRepositoryTest`）。
10. 桌面端协议 + reducer + 团队卡/消息时间线/团队执行分屏/对话对象切换（`*ThreadItemJsonTest` / `*ChatReducerTest` / `*TeamSectionTest`）。
11. 端到端 IT（`TeamCoordinationIT`：Leader 协调 explorer + worker，approve-once 一次 → 迭代路由 → `FINISH` 终止 → 归属落库 → 对话保留）。

---

## 9. 验收

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=BabiqTeamSpecTest,SupervisorRoutingNodeTest,TeamCoordinationServiceTest,TeamApprovalTest,TeamTerminationTest,DirectTeammateMessageTest,ThreadItemJsonTest,TeamRepositoryTest,SchemaCommentsCoverageTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*TeamSectionTest"
.\gradlew.bat test
```

- `clean verify` 全绿（含 IT）；`SchemaCommentsCoverageTest` 过；`AgentLoopLineCountTest` 不退化。
- 真实模型人工烟测：组建 Leader 协调 explorer + worker → 组队 approve-once 一次 → supervisor 迭代路由（explorer 探查、worker 写受沙箱）→ `FINISH` 正常终止（不空转）→ 运行记录按 teammate 归属 → 可直接喊话某 teammate → 团队执行分屏 + 协调时间线可见 → **对话栏全程可用**；视觉对齐原型 P6 03 / P6 05。

---

## 10. 风险与缓解

1. **supervisor 图自搭复杂度**：比"薄封装一个类"重（要搭 StateGraph + 条件边 + asNode + 共享 saver）。缓解：Task 2/3 先把最小 supervisor 图跑通再加业务；严格参照官方 `multi-agent-supervisor` 示例 + P6-0 spike 的 asNode/saver 经验。
2. **不收敛 / 空转**：缓解：`FINISH` + **轮次上限兜底** + supervisor prompt 明确收尾条件。
3. **成本**：多轮协调放大 token。缓解：轮次上限 + 团队规模 ≤3 + token 归属可见。
4. **审批**：approve-once 组队级（复用 P6-2），沙箱硬边界兜底。
5. **并行写冲突**（若叠 ParallelAgent）：借鉴 Codex worker「文件 ownership」分配不重叠职责。
6. **文档≠版本**：所有 SAA API 实现前必须 `javap` / `jar tf` 核对在 1.1.2.3 存在（本阶段已踩过 `SupervisorAgent` 坑）。
7. **范围蔓延**：点对点真并发 swarm（P6-3b）/ A2A / 跨 turn 常驻 / 无界团队坚决不混入。

---

## 11. 参照

- **前置**：`p6-1-subagent-delegation/`、`p6-2-flow-orchestration/`（approve-once §5.1）、`p6-0-mechanism-spike/`（asNode + 共享 saver + 文档/jar 一致性教训）。
- **master**：`p6-master.md`（§5 UI 模型与原型、§7 风险）。
- **原型**：`P6 03`（`202:2`）/ `P6 05`（`221:2`）。
- **官方（Context7 + jar 核对）**：graph-core `multi-agent-supervisor`（`SupervisorNode` 路由 `next`/`FINISH`，1.1.2.3 可用的 `StateGraph`/`NodeAction`/`addConditionalEdges`/`asNode` 原语）。**注意**：`SupervisorAgent` 类（文档 v1.1.2.2）**不在 1.1.2.3**，故用原语自搭；`LlmRoutingAgent`（1.1.2.3 有）可作 supervisor 决策的简化备选。
- **借鉴源（概念，不照搬）**：Claude Code swarm（leader + teammates hub-and-spoke；点对点留 P6-3b）、Codex worker（文件 ownership）。

---

## 12. 下一步

1. 本 plan 由用户确认（graph-core supervisor 模式自搭 + approve-once + 终止兜底 + 点对点真并发留 P6-3b）。
2. 确认后写 `p6-3-team-collaboration/codex-handoff.md`，按 §8 Task 1→11 TDD 实现（**前置 P6-1，推荐 P6-2 先完成**）。
3. P6-3 闭环后：P6 三层（委派 / 编排 / 团队-中枢协调）齐备；后续可评估 **P6-3b 点对点真并发 swarm**、A2A 远程、P6-2b 运行中逐工具审批等独立增强。
