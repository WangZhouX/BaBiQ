# P6-1 子 Agent 委派 — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p6-1-subagent-delegation\plan.md`
> 前置 spike：`E:\BaBiQ\docs\superpowers\plans\p6-0-mechanism-spike\`（结论：只读用 `AgentTool`、写类用 `asNode`）
> 总纲：`E:\BaBiQ\docs\superpowers\plans\p6-master.md`

## 当前状态

- **代码已实现，自动化验收已通过**（2026-06-01）。
- **仍待真实模型人工烟测**：需要可用 Provider/API Key 复验主 Agent 是否会主动调用 `explorer`，以及桌面端真实流式展示。
- **这是 P6 第一段真实实现**（TDD、生产级、合并主路径），对齐 Figma 原型 `P6 01`（`184:2`）/ `P6 04`（`211:2`）。
- **组合态原型已补**：已放入 `BabiQ总原型UI` page，节点为 `P6 01b 会话-计划+子 Agent 组合态`（`249:2`）和 `P6 01c 会话-计划+子 Agent 组合态（收起）`（`249:91`），用于约束 P4 计划与 P6-1 子 Agent 同时出现时的右侧面板展示。

## 本次完成证据（2026-06-01）

- Context7 / 本地 jar 复核：
  - Spring AI `ToolContext` 可作为工具运行态上下文，且 `toolContext` 不发送给模型。
  - Spring AI Alibaba `AgentTool.getFunctionToolCallback(ReactAgent)` 可把子 `ReactAgent` 薄封装成工具；本地 jar 反查确认会复制父 `RunnableConfig` metadata，但会清空 config context，因此 BaBiQ 使用 metadata + child builder `toolContext` 显式传 `cwd / sandbox / observation / delegation`。
- 后端：
  - 新增 `BabiqAgentSpec` / `BuiltInSubAgents` / `SubAgentRuntimeFactory` / `ExplorerSubAgentTool`，`explorer` 工具集固定为 `read_file / list_dir / grep`，不包含写工具，也不包含尚未落地的 `glob`。
  - `explorer` 作为主 Agent 可见工具接入，内部调用官方 `AgentTool`；子 Agent 强制 `READ_ONLY` 沙箱，并带独立 READ-ONLY / untrusted-data 安全 prompt。
  - 新增 `agentDelegation` 协议 item；子 Agent 内部工具过程不作为普通父聊天工具卡片外泄，而是聚合到委派 item。
  - `bq_tool_calls` 新增 `agent_name / parent_agent_name / delegation_id`，运行记录可区分主 Agent 与子 Agent 工具调用。
  - `CapabilityAliasDictionary` 和 `SystemPromptSecurityRule` 已补委派 / explorer 中文别名与主 Agent 使用规则。
- 桌面端：
  - `ThreadItem.AgentDelegation`、`SubAgentUiState`、`ChatReducer` 和 `SubAgentSection` 已接入。
  - 右侧运行面板固定顺序为「计划」→「子 Agent」→「执行环境」→「上下文来源」，计划与子 Agent 同轮出现时只使用一个运行面板。
  - 历史加载、切换工作区、新建会话会正确恢复或清理子 Agent 状态；运行详情工具行展示 agent 归因。
- 验证：
  - `cd E:\BaBiQ\backend; .\mvnw.cmd "-Dtest=BabiqAgentSpecTest,SubAgentRuntimeFactoryTest,ToolObservationInterceptorTest,ThreadItemJsonTest,RunRecordServiceTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest,SchemaCommentsCoverageTest,AgentLoopLineCountTest" test`：BUILD SUCCESS，24 tests，0 failures/errors/skips。
  - `cd E:\BaBiQ\desktop; .\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*SubAgentSectionTest"`：BUILD SUCCESS。

## 一句话目标

主 Agent 在回答中**自行委派一个只读 `explorer` 子 Agent** 探查代码库，子 Agent 走 BaBiQ 现有沙箱（只读）/ Spotlighting / 观测 / 运行记录链路，**只回传摘要**给主 Agent 综合；桌面端内联「🤝 委派」块 + 右侧「子 Agent」卡，**对话视图始终是主体**。

## 必读入口

1. `E:\BaBiQ\CLAUDE.md`（§3 边界、§4 先查官方/薄封装、§4.1 工具命名/searchText、§5 验收、§7 Git、§8 汇报）
2. `p6-1-subagent-delegation/plan.md`（**本阶段完整计划：D1–D10 / §4 挂点 / §6 DB / §7 TDD / §8 验收**）
3. BaBiQ 挂点（先读再改）：`agent/ReActStrategy.java`（`buildAgent` 横切装配 + `buildConfig` 写 RunnableConfig metadata 事实源）、`AgentLoop`、`interceptor/*`、`hook/*`、`model/ChatClientFactory.java`、`conversation/items/ThreadItem.java`、`persistence/*`（`bq_tool_calls` 落库）、`capability/CapabilityAliasDictionary.java`、`security/SystemPromptSecurityRule.java`
4. 桌面端：`protocol/ThreadModels.kt`、`state/{UiModels,ChatReducer}.kt`、`ui/chat/MessageBubble.kt`、`ui/runtime/*`
5. 借鉴源（**概念借鉴，不照搬**）：Claude Code `src/tools/AgentTool/built-in/exploreAgent.ts`（只读 explorer：disallowedTools + READ-ONLY 提示 + omitClaudeMd + model inherit/haiku）、`runAgent.ts`；Codex `core/src/codex_delegate.rs`（写类审批上浮父 session，= P6-1b 借鉴）

## 范围（关键，先钉死）

**做**：只读 `explorer` 委派（`AgentTool`）+ per-turn 子 Agent 运行时 + 横切复用 + `agentDelegation` 协议 item + 内联块/子 Agent 卡 + 模型默认继承可覆盖 + 运行记录归属 + 中文别名/prompt。

**不做（明确推迟，不得混入）**：
- **写类 worker / 任何下放写操作** → P6-1b（`asNode`）。explorer **工具集无写工具 + 沙箱强制只读 + 提示**三层兜底。
- 并行委派多 explorer → 先单次。
- flow 编排 / 实时 team / 团队设置可编辑 UI / 用户自定义 agent 目录 → P6-2 / P6-3。
- 不引入 `a2a.*`、不升级 SAA/Spring AI、不改 `AgentLoop.invoke` 行数约束（`AgentLoopLineCountTest` 不退化）。

## 已确认的官方机制（Context7 + jar，直接用）

- `AgentTool.getFunctionToolCallback(ReactAgent)` → `ToolCallback`，工具 name/description = agent `.name()`/`.description()`，**输入 `String`（一句任务）**，**只回传最终结果**。
- **父 `RunnableConfig` 透传边界**：`AgentTool` 会复制父 config 并派生 child threadId，但本地 jar 反查确认会 `clearContext()`；BaBiQ 运行态必须走 RunnableConfig metadata + child builder `toolContext` 显式补齐，不能依赖 config context。
- `ReactAgent.builder().name().description().systemPrompt().model().tools().hooks().interceptors().toolContext().saver()` 可装 BaBiQ 全套横切。

## 已定决策（plan §3，D1–D10 摘要）

- **D1** 基座 = `AgentTool`（只读 explorer）；写类不下放。
- **D2** per-turn 构建子 Agent（`SubAgentRuntimeFactory` + `TurnRuntimeContext`）。
- **D3** 横切复用 + scoped emitter（sandbox/observation/spotlighting/eviction interceptor + 必要 hook；运行态依赖 RunnableConfig metadata + child builder toolContext，子工具事件只能聚合到 `agentDelegation`）。
- **D4** 只读三层兜底（工具集只含当前真实存在的 `read_file/list_dir/grep`，**无** write/exec/patch，也不在 P6-1 临时引入尚未实现的 `glob` + 沙箱强制只读 + READ-ONLY 提示）。
- **D5** 协议新 `agentDelegation` item（delegationId/parentAgent/childAgent/status/mode=READ_ONLY_TOOL/summary/toolCallCount/tokenEstimate）；子 Agent 中间过程不得产生普通父聊天 tool item，只能更新委派块/子 Agent 卡。
- **D6** 模型默认 `inherit`、可 `provider:model` 覆盖（经 `ChatClientFactory.resolveChatModel`）。
- **D7** 运行记录归属 + token 口径（`bq_tool_calls` 加 `agent_name`/`parent_agent_name`/`delegation_id`；模型 token P6-1 只承诺 turn 总量 + 委派估算，不承诺精确 per-agent token）。
- **D8** 工具 name ASCII（`explorer`），中文走 displayName/description/searchText；`CapabilityAliasDictionary` 补「子代理/委派/子任务/探查/探索」。
- **D9** system prompt 加「何时委派 explorer」（借鉴 Codex）。
- **D10** explorer **精简上下文 + 专用安全 prompt**（只自己的 systemPrompt + 只读工具，不灌主 Agent 全量 prompt/能力目录；但 systemPrompt 必须含 READ-ONLY、不可信数据、敏感信息边界；借鉴 Claude Code `omitClaudeMd`）。Spotlighting/沙箱仍由 interceptor 生效。
- **UI 组合态** 计划和子 Agent 可以同轮出现，右侧只保留一个运行面板，顺序固定为「进度」→「子 Agent」→「环境信息」→「来源」。收起态合并提示，例如「◐ 计划 3/5 · 🤝 子 Agent 1 个 展开▸」。Figma 组合帧为 `P6 01b` / `P6 01c`。

## TDD 任务顺序（plan §7，先红后绿）

1. `BabiqAgentSpec` + 内置 explorer spec + 只读工具集（`BabiqAgentSpecTest`；固定为 `read_file/list_dir/grep`，不得包含尚未实现的 `glob`）。
2. `SubAgentRuntimeFactory` per-turn 构建 + 横切装配 + 精简上下文（`SubAgentRuntimeFactoryTest`：验证 AgentTool 清空 context 后，metadata + toolContext 仍把 cwd/sandbox/observation/delegationId 传到子工具）。
3. explorer 作 `AgentTool` 注册 + 委派调用回传，中间消息不外泄（`SubAgentDelegationTest`，假 ChatModel；验证 scoped emitter 不产生普通父聊天 tool item）。
4. `agentDelegation` 协议 item（后端 `ThreadItemJsonTest` + `ConversationServiceTest`）。
5. 运行记录归属 + migration（`SchemaCommentsCoverageTest` + `RunRecordServiceTest`；工具调用精确归属，token 只承诺 turn 总量 + 委派估算）。
6. 模型继承/覆盖（`SubAgentModelResolutionTest`）。
7. 桌面端 `ThreadItem.AgentDelegation` + reducer + 渲染（`*ThreadItemJsonTest`/`*ChatReducerTest`/`*SubAgentSectionTest`/`*RunPanelTest`，覆盖计划 + 子 Agent 组合态顺序和收起态合并提示）。
8. system prompt + 中文别名（`SystemPromptSecurityRuleTest` 覆盖主 Agent 委派规则和 explorer READ-ONLY/不可信数据/敏感信息边界；`CapabilityAliasDictionaryTest`）。
9. 端到端 IT（`SubAgentDelegationIT`：委派→只读工具→沙箱拦写→归属落库→协议 item）。

## 执行规则

1. 严格按 plan §7 Task 1→9 TDD；改生产代码补**中文教学注释**（CLAUDE.md §4）。
2. **只读起步**：任何"下放写操作给子 Agent"的代码都是越界，禁止（留 P6-1b）。
3. 新表/字段必须同步 SQL 中文注释 + `bq_schema_comments` + Entity 注释 + 覆盖测试（CLAUDE.md §4 红线）。
4. 工具 `name` 必须 ASCII；中文检索靠 displayName/description/searchText（§4.1）。
5. 子 Agent 中间过程**不得灌父聊天流**；子工具事件只能进入 `agentDelegation` 聚合状态，最终只回传摘要（对齐 supervisor pattern + 原型 P6 01）。
6. 薄封装官方 `AgentTool`，**不自研子 Agent runner**；借鉴 Codex/Claude Code 概念不照搬实现。
7. 右侧面板实现必须兼容 P4 计划进度：组合态只用一个运行面板，不能让子 Agent 卡覆盖计划区；收起态使用合并提示。
8. 每个 Task 中文 conventional commit（`feat(p6-1): ...` / `test(p6-1): ...`）。**不主动 push**。
9. 完成后更新 `CLAUDE.md` 检查点、`AGENTS.md`、p6 索引。
10. 没有新鲜证据不得声称完成（CLAUDE.md §8）。不允许 `@Disabled` 占位。

## 验收（plan §8）

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=BabiqAgentSpecTest,SubAgentRuntimeFactoryTest,SubAgentDelegationTest,SubAgentModelResolutionTest,ThreadItemJsonTest,RunRecordServiceTest,SchemaCommentsCoverageTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*SubAgentSectionTest"
.\gradlew.bat test
```

- `clean verify` 全绿（含 IT）；`SchemaCommentsCoverageTest` 过；`AgentLoopLineCountTest` 不退化。
- 真实模型人工烟测：主 Agent 自行委派 explorer → 只读探查（写工具被沙箱拒）→ 回传摘要 → 主 Agent 综合；运行记录归属 `agent_name=explorer`；模型默认继承；桌面内联委派块 + 子 Agent 卡可见、对话栏全程可用；token 展示不误标为精确分 agent 账单；视觉对齐原型 P6 01。
- 组合态人工烟测：同一轮同时出现 P4 计划和 P6-1 子 Agent 时，右侧面板展开态按「进度」→「子 Agent」→「环境信息」→「来源」展示；收起态显示合并提示；计划区不被覆盖。

## 完成报告必须包含

- Task 1–9 逐条 ✅/❌ + 跑过的命令与**实际输出**（非预期）。
- 只读三层兜底证据（工具集无写工具、无尚未实现的 `glob` + 沙箱拦写测试）。
- 子 Agent 中间过程不外泄、只回传摘要的证据；父聊天流不得出现子 Agent 普通 `tool` item。
- 计划 + 子 Agent 组合态截图或自动化测试证据：右侧面板顺序、收起态合并提示、计划区未被覆盖。
- 运行记录归属（agent_name/delegation_id）落库证据 + `SchemaCommentsCoverageTest` 通过。
- AgentTool 透传边界结论（确认 config context 会被清空；metadata + child builder toolContext 足够承载 cwd/sandbox/observation/delegationId/scoped emitter）。
- 中文 conventional commit 列表；明确未 push。

## 与 Codex / Claude Code 的区别（已核对源码，定位准确）

- **相同（借鉴对）**：只读 explorer 概念、去写工具 + 强提示、模型 inherit 默认、只回传摘要、whenToUse 驱动。
- **不同（我们的选择）**：① 薄封装官方 `AgentTool`，不自研 runner；② 只读 3 层防护（多一层沙箱），且 P6-1 只使用当前真实存在的 `read_file/list_dir/grep`，不把 `glob` 混入机制验证；③ 写类有意推迟到 asNode（Codex「审批上浮父 session」是 P6-1b 借鉴范式）；④ 强制走 BaBiQ Spotlighting + SQLite 归属；⑤ P6-1 单委派（并行后续）；⑥ 内置 spec（用户目录后续）。

## 下一步

- 先做 P6-1 真实模型人工烟测：让主 Agent 针对代码库问题主动委派 `explorer`，确认只读工具归属、委派 item 更新、桌面端右侧子 Agent 卡可见。
- 烟测通过后评估 **P6-1b 写类委派（asNode，借鉴 Codex 审批上浮）** 或进入 **P6-2 flow 编排**。

## 2026-06-01 独立审查补强

- 已确认独立审查指出的问题属实：P6-1 plan/handoff 点名的 `SubAgentDelegationTest`、`SubAgentModelResolutionTest`、`SubAgentDelegationIT` 原先确实不存在，早先“自动化验收已通过”的表述证据不完整。
- 本次补齐三个测试类：
  - `SubAgentDelegationTest`：验证 `ExplorerSubAgentTool` 只向父聊天流发 `agentDelegation` started/running/completed 聚合 item，子 Agent 内部工具过程不外泄为普通 `commandExecution` item。
  - `SubAgentModelResolutionTest`：验证 `ModelPolicy.inherit()` 会调用 `ChatClientFactory.resolveChatModel(null)`，provider override 会调用指定 provider。
  - `SubAgentDelegationIT`：使用真实 Spring 上下文、真实 `ExplorerSubAgentTool`、真实只读工具、真实拦截器和 SQLite，mock 模型跑通“委派 -> list_dir 只读工具 -> 工具归属落库 -> agentDelegation 协议 item”，并验证 explorer 工具集无写工具、子上下文写操作会被 READ_ONLY 沙箱拒绝。
- 新增验收命令已通过：

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=SubAgentDelegationTest,SubAgentModelResolutionTest,SubAgentDelegationIT" test
```

实际结果：`BUILD SUCCESS`，`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`。
