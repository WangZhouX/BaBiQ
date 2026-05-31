# P6-1 子 Agent 委派 — 正式实现 plan（草案）

> 状态：**草案，待用户确认**（2026-05-31 创建）。
> 隶属：`docs/superpowers/plans/p6-master.md` §5.6 P6-1；前置 `p6-0-mechanism-spike/`（spike 结论）。
> 性质：**P6 第一段真实生产实现**（TDD、生产级、合并进主路径），对齐 Figma 原型 `P6 01 会话-子 Agent 委派`（`184:2`）与 `P6 04 团队设置-子 Agent 模型`（`211:2`）。

---

## 0. 一句话目标

让 BaBiQ 主 Agent 能在回答中**自行委派一个只读 `explorer` 子 Agent** 去探查代码库，子 Agent 走 BaBiQ 现有沙箱（只读）/ Spotlighting / 观测 / 运行记录链路，**只把结果摘要回传**主 Agent 综合；桌面端以内联「🤝 委派」块 + 右侧「子 Agent」卡呈现，**对话视图始终是主体**。

---

## 1. 范围（做 / 不做）

**P6-1 做**：

- **只读 `explorer` 子 Agent 委派**：主 Agent 通过官方 `AgentTool.getFunctionToolCallback(explorerAgent)` 把 explorer 当工具调用，传一句自然语言任务，拿回结果摘要。
- **per-turn 子 Agent 运行时**：按本轮 turn 装配子 Agent（emitter / cwd / observation / sandbox），复用 BaBiQ 横切层。
- **协议 + 桌面 UI**：新增 `agentDelegation` ThreadItem；内联委派块 + 右侧「子 Agent」卡；子 Agent 中间过程不进父聊天流。
- **模型默认继承 + 可覆盖的数据模型**：explorer `AgentSpec.model` 默认继承 active provider，覆盖经 `ChatClientFactory.resolveChatModel(providerId)`。
- **运行记录归属**：工具调用 / token 区分「主 Agent」与「子 Agent」。
- **能力别名 + system prompt**：中文 query 命中委派；主 Agent prompt 加「何时委派 explorer」。

**P6-1 不做（明确推迟）**：

- **写类子 Agent（worker）/ 任何下放写操作**：写 + HITL 需 `asNode + StateGraph + 共享 saver`（spike 结论），比 `AgentTool` 重；留 **P6-1b（asNode 写类委派）**或并入 P6-2。P6-1 的 explorer **工具集层面禁止写工具 + 沙箱兜底**双保险。
- **并行委派多个 explorer**（Codex 鼓励）：P6-1 先支持**单次委派**；并行留后续。
- **flow 编排 / 实时 team / 团队设置完整编辑 UI（P6 04 的可编辑形态）/ 用户自定义 agent 目录**：分别属于 P6-2 / P6-3。
- 不引入 `a2a.*`，不升级 SAA / Spring AI 版本，不改 `AgentLoop.invoke` 主循环行数约束（`AgentLoopLineCountTest` 不退化）。

> **保守起步理由**：spike 已证 HITL 嵌套中断需 `asNode`（较重）。P6-1 只下放只读委派可**完全规避嵌套中断风险**，快速跑通"委派→横切→协议→UI→归属"全链路，把写类 HITL 留到 asNode 基座单独硬验。

---

## 2. 前置依赖（P6-0 spike 结论，已采纳）

- **基座选型**：只读委派用 `AgentTool`；写类 / 审批用 `asNode + StateGraph + 共享 saver`。P6-1 用前者。
- **AgentTool 已确认行为（Context7 + jar）**：
  - `AgentTool.getFunctionToolCallback(agent)` 把 ReactAgent 包成 `ToolCallback`，工具 name/description = agent 的 `.name()` / `.description()`，**输入为 `String`（一句自然语言任务）**，**只回传最终结果**（子 Agent 中间过程对父不可见）。
  - **透传父 `RunnableConfig` 业务 metadata**：`RunnableConfig.builder(parentConfig).threadId(parentThreadId + "_" + agent.name())...`——BaBiQ 的 `cwd / writableRoots / sandboxMode / observationContext / emitter`（`ReActStrategy.buildConfig` 写入 RunnableConfig metadata）**自动透传**给子 Agent。
- **横切可挂任意 ReactAgent**：`ReactAgent.builder().tools().hooks().interceptors().toolContext().saver()`（事实源 `ReActStrategy.buildAgent`）。
- **per-turn 构建**：emitter/cwd/observation 是本轮运行态，子 Agent 必须按本轮装配（spike Q4）。

---

## 3. 关键设计决策（D1–D9，实现前确认）

| # | 决策 | 说明 |
|---|---|---|
| D1 | **基座 = `AgentTool`（只读 explorer）** | 最薄、metadata 自动透传；写类不下放（规避 HITL 嵌套）|
| D2 | **per-turn 子 Agent 运行时** | 新增 `SubAgentRuntimeFactory`，按本轮 `TurnRuntimeContext`（cwd/emitter/observation/sandbox/provider）装配 explorer，再 `AgentTool` 包装注册给主 Agent |
| D3 | **横切复用** | explorer 子 Agent 用与主 Agent **同一套** interceptor（sandbox / observation / spotlighting / eviction）+ 必要 hook；RunnableConfig metadata 透传 + `toolContext` 显式补 emitter |
| D4 | **只读双保险** | explorer 工具集只含 `read_file / list_dir / grep / glob`（**不含** write_file / exec_shell / apply_patch）；**且**沙箱对 explorer 强制只读，即使误配也写不了 |
| D5 | **协议 = 新 `agentDelegation` item** | 字段：`delegationId / parentAgent / childAgent / status(running\|completed\|failed) / mode(READ_ONLY_TOOL) / summary / toolCallCount / tokenEstimate`；**子 Agent 中间过程不进父聊天流**，只在委派块/子 Agent 卡呈现 |
| D6 | **模型默认继承可覆盖** | `BabiqAgentSpec.model`：`inherit`（默认，跟随 active provider）/ `provider:model`（覆盖，经 `ChatClientFactory.resolveChatModel`）。P6-1 落数据模型 + 默认继承 + 子 Agent 卡展示；完整编辑 UI 留 P6-3 |
| D7 | **运行记录归属** | `bq_tool_calls` 增 `agent_name` / `parent_agent_name` / `delegation_id`；token 计入本 turn TurnSummary 并标注归属 |
| D8 | **能力别名 + 工具命名** | 委派工具 name = ASCII（如 `explorer`），中文经 `displayName` / `description` / `searchText`；`CapabilityAliasDictionary` 补「子代理 / 委派 / 子任务 / 探查 / 探索 / 代理」（CLAUDE.md §4.1）|
| D9 | **system prompt（借鉴 Codex explorer）** | 主 Agent prompt 加「何时委派 explorer：针对代码库的明确、可独立回答的问题；信任其结论；不重复探查」。**借鉴不照搬** Codex `role.rs` 文案 |
| D10 | **explorer 上下文精简（借鉴 Claude Code `omitClaudeMd`）** | `SubAgentRuntimeFactory` 给 explorer 装**精简上下文**：只用其 `AgentSpec.systemPrompt` + 只读工具 + 本轮委派任务（String），**不灌主 Agent 全量 system prompt / 安全规则 / 能力目录**——省 token、更快、隔离更干净（主 Agent 负责综合，子 Agent 无需重复全局上下文）。注意：Spotlighting / 沙箱仍由 **interceptor** 生效，与 prompt 精简无关，不削弱安全 |

---

## 4. 后端实现要点（挂点）

> 改代码前先读：`ReActStrategy.java`、`AgentLoop`、`interceptor/*`、`hook/*`、`model/ChatClientFactory.java`、`conversation/items/ThreadItem.java`、`persistence/*`（`bq_tool_calls` 落库链路）、`capability/CapabilityAliasDictionary.java`、`security/SystemPromptSecurityRule.java`。

| 文件 / 类 | 动作 | 原因 |
|---|---|---|
| `BabiqAgentSpec`（record，新增）| **新增** | name/displayName/description/systemPrompt/toolNames/modelPolicy/delegationMode；explorer 为内置 spec（代码定义，不读用户目录）|
| `SubAgentRuntimeFactory`（新增）| **新增** | 按 `TurnRuntimeContext` 用 `ReactAgent.builder()` 构建子 Agent + 装横切 + `toolContext`，返回 `AgentTool.getFunctionToolCallback(subAgent)`；explorer 用**精简 systemPrompt（D10）**，不灌主 Agent 全量 prompt / 安全规则 / 能力目录 |
| `ReActStrategy.buildAgent(...)` | 修改 | 把 explorer 委派工具加进主 Agent `.tools(...)`（per-turn）；确保子 Agent 与主 Agent 共用 interceptor/hook 装配逻辑（抽公共方法，避免复制）|
| `BaBiQSandboxInterceptor` | 修改/确认 | explorer 调用上下文强制只读（即便工具集错配也拦写操作）|
| `ToolObservationInterceptor` / `BaBiQTokenUsageHook` | 修改 | 观测 / token 记录带「子 Agent 归属」（agent_name / delegation_id）|
| `conversation/items/ThreadItem.java` + `AgentDelegationItem`（新增）| **新增** | 注册 `type="agentDelegation"`，承载 D5 字段 |
| `ConversationService` / `ItemEmitter` | 修改 | 新增 `emitAgentDelegation(...)`：委派开始 `item/added`、状态变化 `item/updated`、完成补 summary |
| `RunRecordService` / `bq_tool_calls` 落库 | 修改 | 写入归属字段（见 §6）|
| `model/ChatClientFactory.java` | 复用/确认 | 子 Agent 模型覆盖经 `resolveChatModel(providerId)`；`inherit` 时取 active provider |
| `capability/CapabilityAliasDictionary.java` + `CapabilityCatalogSyncService` | 修改 | 补委派相关中文别名；委派工具进能力目录、searchText 含中文 |
| `security/SystemPromptSecurityRule.java` | 修改 | 加「委派 explorer」使用规则（D9）|

---

## 5. 桌面端实现要点（对齐原型 P6 01 / P6 04）

> 改代码前先读：`protocol/ThreadModels.kt`、`state/UiModels.kt`、`state/ChatReducer.kt`、`ui/chat/MessageBubble.kt`、`ui/runtime/*`（右侧运行面板）。

- `protocol/ThreadModels.kt`：新增 `ThreadItem.AgentDelegation` + `ThreadItemSerializer` 的 `"agentDelegation"` 分支。
- `state/UiModels.kt`：新增 `SubAgentUiState`（当前委派列表 + 状态）；委派的内联块作为 `ChatMessage` 变体（参考 P5 Reasoning 折叠块），子 Agent 卡作为右侧面板区。
- `state/ChatReducer.kt`：`agentDelegation` item → 内联「🤝 委派」块（进 messages）+ 右侧「子 Agent」卡状态（不进 messages 的部分）；状态 `running/completed/failed` 驱动展开/收起。
- `ui/chat/MessageBubble.kt`：内联委派块渲染（淡灰底折叠块，标题「🤝 委派 · explorer（只读）」+ 子任务 + 返回摘要）。
- `ui/runtime/*`：右侧运行面板「子 Agent」卡（explorer 状态 + 模型 + 工具次数）。
- `ui/chat/Composer.kt` 或运行面板：子 Agent 卡展示其模型（默认「继承主 Agent · <model>」）。

---

## 6. 数据库（归属字段 + migration）

- 新增 migration `V13__subagent_delegation_attribution.sql`（编号以仓库当前最大 V 为准）：
  - `bq_tool_calls` 增列：`agent_name`（调用工具的 Agent 名，主 Agent 为 `main`）、`parent_agent_name`（委派来源，主 Agent 调用为空）、`delegation_id`（一次委派的 id，主 Agent 直接调用为空）。
  - （可选）`bq_delegations` 表记录一次委派的生命周期（id / thread_id / turn_id / parent / child / status / summary / 起止时间）——若 §5 子 Agent 卡需要独立查询则建，否则复用 item。
- **每个新表 / 字段**：SQL `--` 中文注释 + 写入 `bq_schema_comments` + Entity 中文字段注释 + `SchemaCommentsCoverageTest` 覆盖（CLAUDE.md §4 红线）。

---

## 7. TDD 任务清单（先写失败测试再实现）

> 用 `superpowers:test-driven-development` 的节奏；每个 Task 先红后绿。

- **Task 1 — `BabiqAgentSpec` + 内置 explorer spec + 只读工具集解析**：`BabiqAgentSpecTest`（spec 字段、explorer 只读工具集不含写工具）。
- **Task 2 — `SubAgentRuntimeFactory` per-turn 构建 + 横切装配**：`SubAgentRuntimeFactoryTest`（子 Agent 带 sandbox/observation/spotlighting interceptor + toolContext 含 cwd/emitter；模型按 modelPolicy 解析）。
- **Task 3 — explorer 作为 `AgentTool` 注册 + 委派调用回传**：`SubAgentDelegationTest`（假 ChatModel：主 Agent 调 `explorer` 工具→子 Agent 跑只读工具→结果摘要回主 Agent；子 Agent 中间消息不外泄）。
- **Task 4 — 协议 `agentDelegation` item**：`ThreadItemJsonTest`（新增 `agentDelegation` 序列化/反序列化）+ `ConversationServiceTest`（emit added/updated/completed）。
- **Task 5 — 运行记录归属 + migration**：`SchemaCommentsCoverageTest`（新字段中文说明）+ `RunRecordServiceTest`（工具调用写入 agent_name/delegation_id）。
- **Task 6 — 模型继承 / 覆盖**：`SubAgentModelResolutionTest`（`inherit`→active provider；`provider:model`→`ChatClientFactory`）。
- **Task 7 — 桌面端协议 + reducer + 渲染**：`*ThreadItemJsonTest`（Kotlin）、`*ChatReducerTest`（agentDelegation→内联块 + 子 Agent 卡）、`*SubAgentSectionTest`（渲染）。
- **Task 8 — system prompt + 能力别名**：`SystemPromptSecurityRuleTest`（含委派规则）、`CapabilityAliasDictionaryTest` / `CapabilityCatalogSyncServiceTest`（中文别名命中委派）。
- **Task 9 — 端到端 IT + 真实烟测脚本**：`SubAgentDelegationIT`（假 model 端到端：委派→只读工具→沙箱拦写→归属落库→协议 item）；真实模型人工烟测清单（见 §8）。

---

## 8. 验收（CLAUDE.md §5 / §8）

**自动化（必须全绿、无 `@Disabled`）**：

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=BabiqAgentSpecTest,SubAgentRuntimeFactoryTest,SubAgentDelegationTest,SubAgentModelResolutionTest,ThreadItemJsonTest,RunRecordServiceTest,SchemaCommentsCoverageTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*SubAgentSectionTest"
.\gradlew.bat test
```

- `SchemaCommentsCoverageTest` 通过；`AgentLoopLineCountTest` 不退化；`backend clean verify` 全绿（含 IT）。

**真实模型人工烟测（需可用 Provider/API Key）**：

- 主 Agent 针对"梳理仓库鉴权流程/入口"类问题**自行委派 explorer**；explorer 只读探查、回传摘要、主 Agent 综合回答。
- 子 Agent 工具调用**经沙箱（只读，写工具被拒）+ 观测 + 运行记录归属（agent_name=explorer）**。
- 子 Agent **模型默认继承**主 Agent（卡片显示「继承主 Agent · <model>」）；覆盖一个便宜模型后生效。
- 桌面端：内联「🤝 委派」块 + 右侧「子 Agent」卡可见；**对话栏全程可用、子 Agent 中间过程不灌聊天流**。
- 视觉对齐原型 P6 01。

---

## 9. 风险与缓解

1. **emitter 透传是否够**：AgentTool 透传 RunnableConfig metadata，但 `emitter` 是否在其中需 Task 2/3 实测；不够则 `toolContext` 显式补 + per-turn 构建兜底。
2. **explorer 误用写能力**：D4 双保险（工具集不含写工具 + 沙箱强制只读）；Task 1/3 测试钉死。
3. **子 turn threadId 派生**（`parent_+agentName`）与 BaBiQ 现有 threadId/恢复语义冲突：Task 3 验证不破坏现有 HITL / 恢复 / 运行记录。
4. **token / 观测归属串味**：并发暂不做（P6-1 单委派）；归属字段 + 测试隔离主/子。
5. **`InterruptionMetadata.node()` 矛盾**：P6-1 只读不触发 HITL，本阶段不涉及；写类（asNode）阶段再实测复验（spike 遗留项）。
6. **范围蔓延**：worker / 并行 / 团队 / 编排坚决不混入 P6-1。

---

## 10. 参照

- **spike 结论**：`p6-0-mechanism-spike/spike-findings.md`（基座选型、AgentTool metadata 透传、per-turn、风险）。
- **master**：`p6-master.md`（§4 横切清单、§5 UI 模型与原型、§7 风险）。
- **原型**：`P6 01 会话-子 Agent 委派`（`184:2`）、`P6 04 团队设置-子 Agent 模型`（`211:2`）。
- **官方（Context7 核对）**：`examples/multiagent-patterns/supervisor`（AgentTool 暴露 agent 为工具、String 输入、只回传最终结果、透传父 config metadata）、`.../subagent`、`agent-framework/tutorials/hooks`。
- **BaBiQ 挂点**：`ReActStrategy.buildAgent/buildConfig`、`interceptor/*`、`hook/*`、`model/ChatClientFactory.java`、`conversation/items/ThreadItem.java`、`persistence/*`、`capability/*`、`security/SystemPromptSecurityRule.java`。
- **借鉴源（概念，不照搬；已核对真实源码）**：
  - Claude Code `src/tools/AgentTool/built-in/exploreAgent.ts`（只读 explorer：`disallowedTools` 移除写工具 + 强提示 READ-ONLY + `omitClaudeMd` 精简上下文 + model `inherit`/`haiku`）、`runAgent.ts`（子 Agent 作用域权限 `permissionMode`/`allowedTools`/`canUseTool`）。
  - Codex `core/src/agent/role.rs`（explorer/worker 姿态）、`core/src/codex_delegate.rs`（子 Agent exec/patch 审批**上浮父 session** = P6-1b asNode 的借鉴范式）。
  - 差异结论：BaBiQ 薄封装官方 `AgentTool`（不自研 runner）、只读 3 层防护（工具集 + 提示 + 沙箱，比 Claude Code 多沙箱一层）、写类有意推迟到 asNode。

---

## 11. 下一步

1. 本 plan 由用户确认。
2. 确认后写 `p6-1-subagent-delegation/codex-handoff.md`，再按 §7 Task 1→9 TDD 实现。
3. P6-1 闭环（自动化 + 真实烟测）后：评估 **P6-1b 写类委派（asNode）**——**借鉴 Codex「子 Agent 的 exec/patch 审批上浮到父 session」范式**（`codex_delegate.rs` 的 `handle_exec_approval` / `handle_patch_approval`：子 Agent 审批不就地处理，而是 `Initiate approval via parent session`），用 SAA `asNode + invokeAndGetOutput 中断 + addHumanFeedback 恢复` 落地（与 P6-0 spike 结论一致）——或直接进 **P6-2 flow 编排**。
