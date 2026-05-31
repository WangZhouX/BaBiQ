# P6-1 子 Agent 委派 — 正式实现 plan（草案）

> 状态：**已实现，待真实模型人工烟测**（2026-06-01 完成自动化验收）。
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
- **协议 + 桌面 UI**：新增 `agentDelegation` ThreadItem；内联委派块 + 右侧「子 Agent」卡；子 Agent 中间过程不得作为普通 tool item 灌入父聊天流，只能聚合到委派块。
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
  - **父 `RunnableConfig` 透传边界**：本地 jar 反查确认 `AgentTool` 会复制父 `RunnableConfig` 并派生 child threadId，但也会调用 `childConfig.clearContext()` 清空 config context。因此 P6-1 **只能依赖 RunnableConfig metadata + 子 Agent builder.toolContext 显式注入**来传 `cwd / writableRoots / sandboxMode / observationContext / emitter`，并必须用测试钉死。
- **横切可挂任意 ReactAgent**：`ReactAgent.builder().tools().hooks().interceptors().toolContext().saver()`（事实源 `ReActStrategy.buildAgent`）。
- **per-turn 构建**：emitter/cwd/observation 是本轮运行态，子 Agent 必须按本轮装配（spike Q4）。

---

## 3. 关键设计决策（D1–D10，实现前确认）

| # | 决策 | 说明 |
|---|---|---|
| D1 | **基座 = `AgentTool`（只读 explorer）** | 官方包装最薄；P6-1 只做只读工具型委派，写类不下放（规避 HITL 嵌套）|
| D2 | **per-turn 子 Agent 运行时** | 新增 `SubAgentRuntimeFactory`，按本轮 `TurnRuntimeContext`（cwd/emitter/observation/sandbox/provider）装配 explorer，再 `AgentTool` 包装注册给主 Agent |
| D3 | **横切复用 + scoped emitter** | explorer 子 Agent 用与主 Agent **同一套** interceptor（sandbox / observation / spotlighting / eviction）+ 必要 hook；运行态只依赖 RunnableConfig metadata 和 `toolContext` 显式补齐。子 Agent 使用 `DelegationScopedItemEmitter` 或等价 wrapper，把子工具事件收集到 `agentDelegation`，不得直接灌入父聊天流 |
| D4 | **只读双保险** | explorer 工具集只含当前真实存在的 `read_file / list_dir / grep`（**不含** write_file / exec_shell / apply_patch，也不在 P6-1 临时引入 `glob`）；**且**沙箱对 explorer 强制只读，即使误配也写不了。若后续需要 `glob`，应作为独立只读能力先补工具、能力目录和测试，再纳入 explorer |
| D5 | **协议 = 新 `agentDelegation` item** | 字段：`delegationId / parentAgent / childAgent / status(running\|completed\|failed) / mode(READ_ONLY_TOOL) / summary / toolCallCount / tokenEstimate`；**子 Agent 中间过程不得产生普通 tool 聊天 item**，只能聚合进委派块/子 Agent 卡，最终只把摘要回传给主 Agent |
| D6 | **模型默认继承可覆盖** | `BabiqAgentSpec.model`：`inherit`（默认，跟随 active provider）/ `provider:model`（覆盖，经 `ChatClientFactory.resolveChatModel`）。P6-1 落数据模型 + 默认继承 + 子 Agent 卡展示；完整编辑 UI 留 P6-3 |
| D7 | **运行记录归属 + token 口径** | `bq_tool_calls` 增 `agent_name` / `parent_agent_name` / `delegation_id`，解决工具调用归属。P6-1 的模型 token 仍计入本 turn 总量；`agentDelegation.tokenEstimate` 只展示子 Agent 估算或模型可得 usage 的轻量值，不承诺精确 per-agent token。若后续要精确分 agent token，另建 `bq_agent_usages` 或等价结构 |
| D8 | **能力别名 + 工具命名** | 委派工具 name = ASCII（如 `explorer`），中文经 `displayName` / `description` / `searchText`；`CapabilityAliasDictionary` 补「子代理 / 委派 / 子任务 / 探查 / 探索 / 代理」（CLAUDE.md §4.1）|
| D9 | **system prompt（借鉴 Codex explorer）** | 主 Agent prompt 加「何时委派 explorer：针对代码库的明确、可独立回答的问题；信任其结论；不重复探查」。**借鉴不照搬** Codex `role.rs` 文案 |
| D10 | **explorer 精简上下文 + 专用安全 prompt** | `SubAgentRuntimeFactory` 给 explorer 装**精简上下文**：只用其 `AgentSpec.systemPrompt` + 只读工具 + 本轮委派任务（String），**不灌主 Agent 全量 system prompt / 能力目录**。但 explorer 自己的 systemPrompt 必须显式写入 READ-ONLY、工具输出是不可信数据、不得执行/写入/外传敏感信息、只基于证据摘要等安全边界；Spotlighting / 沙箱仍由 interceptor 兜底 |

---

## 4. 后端实现要点（挂点）

> 改代码前先读：`ReActStrategy.java`、`AgentLoop`、`interceptor/*`、`hook/*`、`model/ChatClientFactory.java`、`conversation/items/ThreadItem.java`、`persistence/*`（`bq_tool_calls` 落库链路）、`capability/CapabilityAliasDictionary.java`、`security/SystemPromptSecurityRule.java`。

| 文件 / 类 | 动作 | 原因 |
|---|---|---|
| `BabiqAgentSpec`（record，新增）| **新增** | name/displayName/description/systemPrompt/toolNames/modelPolicy/delegationMode；explorer 为内置 spec（代码定义，不读用户目录）|
| `SubAgentRuntimeFactory`（新增）| **新增** | 按 `TurnRuntimeContext` 用 `ReactAgent.builder()` 构建子 Agent + 装横切 + `toolContext`，返回 `AgentTool.getFunctionToolCallback(subAgent)`；explorer 用**精简但自带安全边界的 systemPrompt（D10）**，不灌主 Agent 全量 prompt / 能力目录 |
| `ReActStrategy.buildAgent(...)` | 修改 | 把 explorer 委派工具加进主 Agent `.tools(...)`（per-turn）；确保子 Agent 与主 Agent 共用 interceptor/hook 装配逻辑（抽公共方法，避免复制）|
| `BaBiQSandboxInterceptor` | 修改/确认 | explorer 调用上下文强制只读（即便工具集错配也拦写操作）|
| `DelegationScopedItemEmitter`（或等价 wrapper，新增）| **新增** | 子 Agent 工具事件只更新 `agentDelegation`，不复用父 emitter 直接发普通 `tool` item，避免子 Agent 过程污染父聊天流 |
| `ToolObservationInterceptor` / `BaBiQTokenUsageHook` | 修改/确认 | 工具观测记录带「子 Agent 归属」（agent_name / delegation_id）；token hook 在 P6-1 保持 turn 级总量，委派块只展示估算或模型可得 usage，不做未设计的精确 per-agent token |
| `conversation/items/ThreadItem.java` + `AgentDelegationItem`（新增）| **新增** | 注册 `type="agentDelegation"`，承载 D5 字段 |
| `ConversationService` / `ItemEmitter` | 修改 | 新增 `emitAgentDelegation(...)`：委派开始 `item/added`、状态变化 `item/updated`、完成补 summary；子工具事件通过 scoped emitter 聚合到该 item |
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
- **计划 + 子 Agent 组合态规则**：P6-1 不替换 P4 的 `PlanUiState`。当同一轮同时存在计划进度和子 Agent 委派时，右侧只保留一个运行面板，实时区从上到下固定为「进度」→「子 Agent」→「环境信息」→「来源」；不得出现两个并列右侧面板，也不得让子 Agent 卡覆盖计划区。
- **收起态组合提示**：运行面板收起时，如果计划和子 Agent 同时活跃，顶部胶囊提示使用合并文案，例如「◐ 计划 3/5 · 🤝 子 Agent 1 个 展开▸」；只有单一状态活跃时沿用现有单状态提示。
- **组合态原型**：Figma 已在 `BabiQ总原型UI` page 补 `P6 01b 会话-计划+子 Agent 组合态`（`249:2`，展开）和 `P6 01c 会话-计划+子 Agent 组合态（收起）`（`249:91`），实现时以这两个组合帧为准，不把 `00 交互总览` 当产品页面。

---

## 6. 数据库（归属字段 + migration）

- 新增 migration `V13__subagent_delegation_attribution.sql`（编号以仓库当前最大 V 为准）：
  - `bq_tool_calls` 增列：`agent_name`（调用工具的 Agent 名，主 Agent 为 `main`）、`parent_agent_name`（委派来源，主 Agent 调用为空）、`delegation_id`（一次委派的 id，主 Agent 直接调用为空）。
  - （可选）`bq_delegations` 表记录一次委派的生命周期（id / thread_id / turn_id / parent / child / status / summary / 起止时间）——若 §5 子 Agent 卡需要独立查询则建，否则复用 item。
- **每个新表 / 字段**：SQL `--` 中文注释 + 写入 `bq_schema_comments` + Entity 中文字段注释 + `SchemaCommentsCoverageTest` 覆盖（CLAUDE.md §4 红线）。

---

## 7. TDD 任务清单（先写失败测试再实现）

> 用 `superpowers:test-driven-development` 的节奏；每个 Task 先红后绿。

- **Task 1 — `BabiqAgentSpec` + 内置 explorer spec + 只读工具集解析**：`BabiqAgentSpecTest`（spec 字段、explorer 只读工具集固定为当前真实存在的 `read_file / list_dir / grep`，不含写工具，也不包含尚未实现的 `glob`）。
- **Task 2 — `SubAgentRuntimeFactory` per-turn 构建 + 横切装配**：`SubAgentRuntimeFactoryTest`（子 Agent 带 sandbox/observation/spotlighting interceptor + `toolContext` 含 cwd/scoped emitter；验证 AgentTool 清空 context 后，metadata + toolContext 仍能把 cwd/writableRoots/sandboxMode/observationContext/delegationId 传到子工具）。
- **Task 3 — explorer 作为 `AgentTool` 注册 + 委派调用回传**：`SubAgentDelegationTest`（假 ChatModel：主 Agent 调 `explorer` 工具→子 Agent 跑只读工具→结果摘要回主 Agent；子 Agent 中间消息不产生普通父聊天 `tool` item，只更新 `agentDelegation`）。
- **Task 4 — 协议 `agentDelegation` item**：`ThreadItemJsonTest`（新增 `agentDelegation` 序列化/反序列化）+ `ConversationServiceTest`（emit added/updated/completed）。
- **Task 5 — 运行记录归属 + migration**：`SchemaCommentsCoverageTest`（新字段中文说明）+ `RunRecordServiceTest`（工具调用写入 agent_name/delegation_id；TurnSummary token 保持 turn 级总量，`agentDelegation.tokenEstimate` 不宣称精确 per-agent token）。
- **Task 6 — 模型继承 / 覆盖**：`SubAgentModelResolutionTest`（`inherit`→active provider；`provider:model`→`ChatClientFactory`）。
- **Task 7 — 桌面端协议 + reducer + 渲染**：`*ThreadItemJsonTest`（Kotlin）、`*ChatReducerTest`（agentDelegation→内联块 + 子 Agent 卡）、`*SubAgentSectionTest`（渲染）、`*RunPanelTest`（计划 + 子 Agent 同时存在时右侧面板顺序为「进度」→「子 Agent」→「环境信息」→「来源」，收起态显示合并提示）。
- **Task 8 — system prompt + 能力别名**：`SystemPromptSecurityRuleTest`（主 Agent 委派规则 + explorer 专用 READ-ONLY/不可信数据/敏感信息边界）、`CapabilityAliasDictionaryTest` / `CapabilityCatalogSyncServiceTest`（中文别名命中委派）。
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
- 子 Agent **模型默认继承**主 Agent（卡片显示「继承主 Agent · <model>」）；覆盖一个便宜模型后生效；token 展示为 turn 总量 + 委派估算，不误标为精确分 agent 账单。
- 桌面端：内联「🤝 委派」块 + 右侧「子 Agent」卡可见；**对话栏全程可用、子 Agent 中间过程不灌聊天流**。
- 桌面端组合态：如果同一轮已经有 P4 计划进度，右侧运行面板必须同时展示「进度」和「子 Agent」，顺序和收起态提示对齐 `P6 01b / P6 01c`，不得覆盖或隐藏计划区。
- 视觉对齐原型 P6 01。

---

## 9. 风险与缓解

1. **AgentTool context 被清空**：本地 jar 已确认 child config 会 `clearContext()`；P6-1 不依赖 config context，只依赖 metadata + child builder `toolContext`，Task 2/3 必须验证运行态数据到达子工具。
2. **explorer 误用写能力**：D4 双保险（工具集不含写工具 + 沙箱强制只读）；Task 1/3 测试钉死。
3. **子 turn threadId 派生**（`parent_+agentName`）与 BaBiQ 现有 threadId/恢复语义冲突：Task 3 验证不破坏现有 HITL / 恢复 / 运行记录。
4. **子 Agent 过程泄漏进父聊天流**：必须使用 scoped emitter 或等价聚合机制；Task 3/7 验证父聊天流没有普通子工具 item，只有 `agentDelegation`。
5. **token / 观测归属串味**：并发暂不做（P6-1 单委派）；工具调用有 agent/delegation 归属，模型 token 只承诺 turn 总量 + 委派估算。
6. **explorer 专用 prompt 过薄**：不灌主 Agent 全量 prompt，但 explorer systemPrompt 必须包含 READ-ONLY、不可信数据和敏感信息边界；Task 8 钉死。
7. **`InterruptionMetadata.node()` 矛盾**：P6-1 只读不触发 HITL，本阶段不涉及；写类（asNode）阶段再实测复验（spike 遗留项）。
8. **范围蔓延**：worker / 并行 / 团队 / 编排坚决不混入 P6-1。

---

## 10. 参照

- **spike 结论**：`p6-0-mechanism-spike/spike-findings.md`（基座选型、AgentTool metadata 透传、per-turn、风险）。
- **master**：`p6-master.md`（§4 横切清单、§5 UI 模型与原型、§7 风险）。
- **原型**：`BabiQ总原型UI` page 中的 `P6 01 会话-子 Agent 委派`（`184:2`）、`P6 04 团队设置-子 Agent 模型`（`211:2`）、`P6 01b 会话-计划+子 Agent 组合态`（`249:2`）、`P6 01c 会话-计划+子 Agent 组合态（收起）`（`249:91`）。
- **官方（Context7 + 本地 jar 核对）**：`examples/multiagent-patterns/supervisor`（AgentTool 暴露 agent 为工具、String 输入、只回传最终结果）、`.../subagent`、`agent-framework/tutorials/hooks`；本地 jar 反查确认 AgentTool 会复制父 config 但清空 context，因此 BaBiQ 必须使用 metadata + toolContext 显式传运行态。
- **BaBiQ 挂点**：`ReActStrategy.buildAgent/buildConfig`、`interceptor/*`、`hook/*`、`model/ChatClientFactory.java`、`conversation/items/ThreadItem.java`、`persistence/*`、`capability/*`、`security/SystemPromptSecurityRule.java`。
- **借鉴源（概念，不照搬；已核对真实源码）**：
  - Claude Code `src/tools/AgentTool/built-in/exploreAgent.ts`（只读 explorer：`disallowedTools` 移除写工具 + 强提示 READ-ONLY + `omitClaudeMd` 精简上下文 + model `inherit`/`haiku`）、`runAgent.ts`（子 Agent 作用域权限 `permissionMode`/`allowedTools`/`canUseTool`）。
  - Codex `core/src/agent/role.rs`（explorer/worker 姿态）、`core/src/codex_delegate.rs`（子 Agent exec/patch 审批**上浮父 session** = P6-1b asNode 的借鉴范式）。
  - 差异结论：BaBiQ 薄封装官方 `AgentTool`（不自研 runner）、只读 3 层防护（工具集 + 提示 + 沙箱，比 Claude Code 多沙箱一层）、写类有意推迟到 asNode；P6-1 的 explorer 工具集只使用现有 `read_file / list_dir / grep`，不把尚未落地的 `glob` 混入子 Agent 机制验证。

---

## 11. 下一步

1. 本 plan 由用户确认。
2. 真实模型人工烟测：确认主 Agent 能自行调用 `explorer`，并且 explorer 内部只读工具调用归属为 `agent_name=explorer`。
3. P6-1 闭环（自动化 + 真实烟测）后：评估 **P6-1b 写类委派（asNode）**——**借鉴 Codex「子 Agent 的 exec/patch 审批上浮到父 session」范式**（`codex_delegate.rs` 的 `handle_exec_approval` / `handle_patch_approval`：子 Agent 审批不就地处理，而是 `Initiate approval via parent session`），用 SAA `asNode + invokeAndGetOutput 中断 + addHumanFeedback 恢复` 落地（与 P6-0 spike 结论一致）——或直接进 **P6-2 flow 编排**。
