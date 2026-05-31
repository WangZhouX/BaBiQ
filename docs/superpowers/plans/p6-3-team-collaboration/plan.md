# P6-3 实时 team 协作 — 正式实现 plan（草案）

> 状态：**草案，待用户确认**（2026-05-31 创建）。
> 隶属：`docs/superpowers/plans/p6-master.md` §5.6 P6-3；**前置：P6-1（委派底座）必须先完成，P6-2（并发 + approve-once）强烈推荐先完成**。
> 性质：**P6 最重、自研最多、风险最高的一层**。因官方无直接等价（见 §1），**强烈建议先做 P6-3-0 spike**（见 §2），再进正式实现。
> 对齐 Figma 原型 `P6 03 会话-团队协作`（`202:2`）/ `P6 05 团队执行-分屏`（`221:2`）。

---

## 0. 一句话目标

让多个子 Agent（Leader = 主 Agent + 常驻 teammates）**同时在线、互发消息协作**完成一个任务；薄封装官方 `ReactAgent` 做每个 teammate，**自研一个最小消息总线 + 并发生命周期层**承载 SendMessage / 协调 / 终止；桌面端「团队执行」分屏看消息时间线、可直接喊话某个 teammate；**对话始终是主体**；仅本机进程内，跨网络 A2A 留后续。

---

## 1. 关键前提：SAA 无 in-process live 多 Agent 原语（Context7 已确认）

> Context7（`java2ai` + repo）核对结论：Spring AI Alibaba 的多 Agent 只有两类——
> - **graph / flow 编排**（`StateGraph` + 路由、`SequentialAgent` / `ParallelAgent`）：**run-to-completion**，不是"多 Agent 长期在线互发消息"。
> - **A2A 远程**（`A2aRemoteAgent` + Nacos）：跨进程 / 分布式，**P6 不做**。
> **没有** SendMessage / agent mailbox / live swarm / leader-teammates 实时协作原语。

⇒ **结论**：P6-3 的"实时协作层"（消息总线 + 并发 teammate 生命周期 + 协调/终止）**必须 BaBiQ 自研**；但**每个 teammate / Leader 仍是官方 `ReactAgent`**（不自研 Agent 引擎，CLAUDE.md §4）。这是 P6 唯一"官方缺失、允许自研"的部分，且必须在代码注释 / plan 说明自研理由。

---

## 2. 强烈建议：先做 P6-3-0 spike（隔离预研）

因为自研层多、并发风险高（消息总线、actor 生命周期、并发审批/记录、**终止/防死锁**都没有官方范式兜底），**建议像 P6-0 那样先 spike**，回答：

- Q1：teammate = 官方 `ReactAgent` + 持久 `MemorySaver` 线程，能否"消息触发运行、上下文跨消息保留"（actor 模型）？
- Q2：自研最小 `MessageBus`（mailbox + SendMessage）+ 有界线程池，能否让 2 个 teammate 互发消息并被 Leader 协调？
- Q3：**终止**——消息/轮次预算 + 空闲检测能否可靠停下（防 A↔B 无限互发 / 死锁 / 活锁）？
- Q4：并发下运行记录 / token 归属、approve-once 授权能否线程安全？

spike 通过、自研层骨架可行后，再按本 plan 正式实现。**未经 spike 不建议直接全量实现 P6-3。**

---

## 3. 范围（做 / 不做）

**P6-3 做**：

- **Leader + 少量 teammates（建议 ≤3）实时协作**：teammate = 官方 `ReactAgent`（复用 P6-1/P6-2 子 Agent 底座），常驻一个团队会话期间。
- **自研最小消息总线**：`SendMessage(to, content)` 作为工具暴露给 Leader / teammates；每 agent 一个 mailbox；消息触发该 agent 运行。
- **approve-once 组队级授权**（借鉴 P6-2）：组队 + 写范围 + 沙箱模式运行前整体批准一次。
- **直接喊话 teammate**（P6 05）：用户切换「对话对象」直接给某 teammate 发消息；Leader 仍是协调者。
- **协议 + UI**：团队卡 + 消息时间线 + 团队执行分屏（对话保留）。
- **有界 + 可终止**：消息/轮次预算、空闲检测、Leader 决定收尾。

**P6-3 不做（明确推迟）**：

- `a2a.*` 跨进程 / 跨网络远程 Agent、跨机协作（master 已划后续）。
- **运行中逐消息/逐工具交互审批**（并发下极难）→ 用 approve-once 替代；如需，留后续。
- 无界团队规模、跨 turn 长期常驻 agent（团队生命周期限本任务）。
- 自研 Agent 执行引擎（teammate 必须是官方 `ReactAgent`）。
- 升级 SAA / Spring AI；不改 `AgentLoop.invoke` 行数约束（`AgentLoopLineCountTest` 不退化）。

---

## 4. 前置依赖

- **P6-1**：`BabiqAgentSpec` / `SubAgentRuntimeFactory` / `agentDelegation` / 运行记录归属 / 横切复用。
- **P6-2（强烈推荐先完成）**：并发归属线程安全、`FlowApprovalService`（approve-once）——P6-3 直接复用并加重（实时并发比 flow 更复杂）。
- **P6-0 spike 结论**：写类 / HITL 用 asNode；P6-3 用 approve-once 规避并发逐工具审批。

---

## 5. 关键设计决策（D1–D8）

| # | 决策 | 说明 |
|---|---|---|
| D1 | **Leader = 主 Agent；teammates = 常驻并发子 Agent** | Leader 即你对话的本体，协调者；teammates = 官方 `ReactAgent`（复用 P6-1 底座），团队期间常驻 |
| D2 | **自研最小 `MessageBus` + `SendMessage` 工具** | 每 agent 一个 mailbox（线程安全队列）；`SendMessage(to, content)` 入队；**worker 不自研，是官方 ReactAgent** |
| D3 | **actor 模型：消息触发运行 + `MemorySaver` 保上下文** | teammate 收到消息 → 用其持久线程跑一次 `ReactAgent` → 可再 SendMessage；上下文跨消息由 saver 保留 |
| D4 | **审批 = approve-once 组队级（借鉴 P6-2 §5.1 4 条语义）** | 组队 + 各成员写范围 + 沙箱模式运行前整批一次；**并发逐工具/逐消息交互审批不做**。沙箱仍是硬边界 |
| D5 | **直接喊话 teammate（P6 05）** | 用户切「对话对象」→ 直接给某 teammate 发消息（走同一 MessageBus）；Leader 仍协调、不被绕过编排意图 |
| D6 | **并发安全 + 可终止（最高风险）** | 有界线程池跑 teammates；mailbox / 运行记录 / token / emitter 线程安全；**消息+轮次预算 + 空闲检测 + Leader 收尾**，防死锁/活锁/无限互发；超预算强制终止 |
| D7 | **协议 + UI** | 新增 `team` / `teamMessage` item（成员 + 状态 + 消息）；桌面团队卡 + 消息时间线 + 团队执行分屏；中间消息进时间线不灌父聊天流；对话保留 |
| D8 | **进程内单机、团队有界** | 仅本机进程内；团队规模 / 生命周期有界；跨网络 A2A 留后续 |

---

## 6. 后端实现要点（挂点）

> 先读 P6-1/P6-2 产物 + `ReActStrategy` / `approval/*` / `persistence/*` / `conversation/items/*` / `interceptor/*`（沙箱）/ `hook/*`（token）。

| 文件 / 类 | 动作 | 原因 |
|---|---|---|
| `TeamMessageBus`（**自研**）| **新增** | 每 agent mailbox（线程安全）、`send(to, msg)`、消息分发；**唯一自研核心**，注释说明官方无等价 |
| `SendMessageTool`（新增）| **新增** | `@Tool` 暴露给 Leader / teammates；name ASCII（`send_message`），中文走 description/searchText |
| `TeamRuntime` / `TeammateActor`（新增）| **新增** | teammate = 官方 `ReactAgent`（`SubAgentRuntimeFactory` 构建）+ 持久线程；消息触发运行；有界线程池 + 预算 + 终止 |
| `TeamApprovalService`（复用 P6-2 `FlowApprovalService`）| 复用/扩展 | 组队级 approve-once（4 条语义：沙箱硬边界 / 范围展示 / 批准后冻结成员与写范围 / 危险禁止）|
| `conversation/items/{TeamItem, TeamMessageItem}` | **新增** | `team`（成员 + 状态）+ `teamMessage`（sender/receiver/content/ts）|
| `ConversationService` / `ItemEmitter` | 修改 | emit team / 消息 item，**并发有序 / 线程安全** |
| `RunRecordService` / `bq_tool_calls` | 复用/修改 | teammate 工具调用归属（agent_name=teammate）线程安全（复用 P6-2） |
| `security/SystemPromptSecurityRule` + `CapabilityAliasDictionary` | 修改 | Leader prompt 加「如何协调团队 / 何时 SendMessage / 文件 ownership」；补「团队/协作/队友/消息/协调」中文别名 |

---

## 7. 桌面端实现要点（对齐 P6 03 / P6 05）

- `protocol/ThreadModels.kt`：`ThreadItem.Team` / `TeamMessage` + serializer。
- `state/UiModels.kt` / `ChatReducer.kt`：团队状态（成员 + 消息时间线）；对话保留；团队执行分屏状态。
- `ui/runtime/*`：右侧「团队」卡（Leader + teammates 状态）+「消息时间线」+「团队执行 ▸」分屏（对齐 P6 05）。
- 对话栏「对话对象」切换（默认 Leader，可选某 teammate 直接喊话，P6 05）。
- 内联「👥 团队」组队块；中间消息进时间线不灌父聊天流。

---

## 8. 数据库

- 新增 migration（编号以仓库当前最大 V 为准）：
  - `bq_teams`：团队（id / thread_id / turn_id / goal / status / 成员 / 起止时间）。
  - `bq_team_messages`：消息（team_id / sender / receiver / content / ts）。
  - `bq_tool_calls`：复用 P6-1/P6-2 归属字段（teammate 并发写）。
- **每个新表/字段**：SQL 中文注释 + `bq_schema_comments` + Entity 注释 + `SchemaCommentsCoverageTest`（§4 红线）。

---

## 9. TDD 任务清单（先红后绿；建议 spike 通过后开始）

1. `TeamMessageBus`：mailbox 入队/分发、线程安全、`send`（`TeamMessageBusTest`）。
2. `SendMessageTool`：Leader/teammate 发消息（`SendMessageToolTest`）。
3. `TeammateActor`：官方 ReactAgent + 持久线程，消息触发运行、上下文保留（`TeammateActorTest`，假 ChatModel）。
4. `TeamRuntime` 生命周期 + **终止**：预算 / 空闲检测 / Leader 收尾，防无限互发（`TeamLifecycleTest`）。
5. **并发安全**：并发 teammates + 运行记录 / token / emitter 线程安全（`TeamConcurrencyTest`）。
6. `TeamApprovalService` 组队 approve-once（4 条语义，复用 P6-2）（`TeamApprovalServiceTest`）。
7. 直接喊话 teammate（`DirectTeammateMessageTest`）。
8. 协议 team / teamMessage item（`ThreadItemJsonTest` + `ConversationServiceTest`）。
9. DB team / message 记录 + migration（`SchemaCommentsCoverageTest` + `TeamRepositoryTest`）。
10. 桌面端协议 + reducer + 团队卡/消息时间线/团队执行分屏/对话对象切换（`*ThreadItemJsonTest` / `*ChatReducerTest` / `*TeamSectionTest`）。
11. 端到端 IT（`TeamCollaborationIT`：Leader + 2 teammates 组队 approve-once → 互发消息协作 → 有界终止 → 并发归属落库 → 对话保留）。

---

## 10. 验收

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=TeamMessageBusTest,SendMessageToolTest,TeammateActorTest,TeamLifecycleTest,TeamConcurrencyTest,TeamApprovalServiceTest,ThreadItemJsonTest,TeamRepositoryTest,SchemaCommentsCoverageTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*TeamSectionTest"
.\gradlew.bat test
```

- `clean verify` 全绿（含 IT）；`SchemaCommentsCoverageTest` 过；`AgentLoopLineCountTest` 不退化。
- 真实模型人工烟测：组建 Leader + 2 teammates（explorer + worker）→ **组队 approve-once 一次** → 互发消息协作（worker 写受沙箱）→ **有界终止**（不无限互发）→ 运行记录按 teammate 归属、无并发串味 → 可直接喊话某 teammate → 团队执行分屏 + 消息时间线可见 → **对话栏全程可用**；视觉对齐原型 P6 03 / P6 05。

---

## 11. 风险与缓解（本阶段最重）

1. **并发 / 终止（最高风险）**：teammates 并发运行 + 互发消息 → 死锁 / 活锁 / **无限互发** / 资源耗尽。缓解：有界线程池 + **消息+轮次预算 + 空闲检测 + Leader 强制收尾**；spike（§2）先验。
2. **成本失控**：多 agent 并发调模型 → token / 费用放大。缓解：预算上限 + 团队规模 ≤3 + token 归属可见。
3. **自研消息总线可靠性**：官方无等价、自研风险高。缓解：spike 先验最小可行 + 充分并发测试。
4. **审批并发**：用 approve-once 组队级（不做并发逐工具审批）；沙箱硬边界兜底。
5. **并行写冲突**：借鉴 Codex worker「文件 ownership」分配不重叠职责。
6. **范围蔓延**：A2A / 跨机 / 无界团队 / 并发逐工具审批坚决不混入。

---

## 12. 参照

- **前置**：`p6-1-subagent-delegation/`、`p6-2-flow-orchestration/`（approve-once §5.1 + 并发归属）、`p6-0-mechanism-spike/`。
- **master**：`p6-master.md`（§5 UI 模型与原型、§7 风险）。
- **原型**：`P6 03`（`202:2`）/ `P6 05`（`221:2`）。
- **官方（Context7 核对）**：确认 SAA 仅 graph/flow 编排 + A2A 远程，**无 in-process live 多 Agent 原语** → 自研薄消息总线（worker 仍官方 `ReactAgent`）。
- **借鉴源（概念，不照搬）**：Claude Code `src/utils/swarm/*`（leader + teammates + SendMessage + 有界协作）、Codex worker（文件 ownership）——BaBiQ 自研最小消息总线、不照搬其 TS/Rust 实现。

---

## 13. 下一步

1. 本 plan 由用户确认（尤其"自研薄层 + spike 先行 + 团队有界可终止 + approve-once"边界）。
2. 确认后**先写 `p6-3-0-team-spike`**（消息总线 + actor 生命周期 + 终止 + 并发审批/记录预研），spike 通过再写 `p6-3-team-collaboration/codex-handoff.md` 并按 §9 Task 1→11 实现。
3. P6-3 闭环后：P6 三层（委派 / 编排 / 团队）齐备；后续可评估 A2A 远程、P6-2b 运行中逐工具审批等独立增强。
