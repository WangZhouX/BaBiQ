# P6-1 子 Agent 委派 — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p6-1-subagent-delegation\plan.md`
> 前置 spike：`E:\BaBiQ\docs\superpowers\plans\p6-0-mechanism-spike\`（结论：只读用 `AgentTool`、写类用 `asNode`）
> 总纲：`E:\BaBiQ\docs\superpowers\plans\p6-master.md`

## 当前状态

- **plan 已定稿（草案待用户确认）**（2026-05-31，已并入与 Codex/Claude Code 源码对照后的 2 处微调：D10 explorer 精简上下文、P6-1b 借鉴 Codex 审批上浮）。
- **实现尚未开始**：无任何 P6-1 生产代码。
- **这是 P6 第一段真实实现**（TDD、生产级、合并主路径），对齐 Figma 原型 `P6 01`（`184:2`）/ `P6 04`（`211:2`）。

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
- **透传父 `RunnableConfig` metadata**（`RunnableConfig.builder(parentConfig).threadId(parent+"_"+agent.name())`）→ BaBiQ 的 cwd/observation/sandbox（`buildConfig` 写入）自动透传给子 Agent。
- `ReactAgent.builder().name().description().systemPrompt().model().tools().hooks().interceptors().toolContext().saver()` 可装 BaBiQ 全套横切。

## 已定决策（plan §3，D1–D10 摘要）

- **D1** 基座 = `AgentTool`（只读 explorer）；写类不下放。
- **D2** per-turn 构建子 Agent（`SubAgentRuntimeFactory` + `TurnRuntimeContext`）。
- **D3** 横切复用（sandbox/observation/spotlighting/eviction interceptor + 必要 hook + toolContext 补 emitter）。
- **D4** 只读三层兜底（工具集只含 `read_file/list_dir/grep/glob`，**无** write/exec/patch + 沙箱强制只读 + READ-ONLY 提示）。
- **D5** 协议新 `agentDelegation` item（delegationId/parentAgent/childAgent/status/mode=READ_ONLY_TOOL/summary/toolCallCount/tokenEstimate）；中间过程不进父聊天流。
- **D6** 模型默认 `inherit`、可 `provider:model` 覆盖（经 `ChatClientFactory.resolveChatModel`）。
- **D7** 运行记录归属（`bq_tool_calls` 加 `agent_name`/`parent_agent_name`/`delegation_id`）。
- **D8** 工具 name ASCII（`explorer`），中文走 displayName/description/searchText；`CapabilityAliasDictionary` 补「子代理/委派/子任务/探查/探索」。
- **D9** system prompt 加「何时委派 explorer」（借鉴 Codex）。
- **D10** explorer **精简上下文**（只自己的 systemPrompt + 只读工具，不灌主 Agent 全量 prompt/安全/能力目录；借鉴 Claude Code `omitClaudeMd`）。Spotlighting/沙箱仍由 interceptor 生效，不削弱安全。

## TDD 任务顺序（plan §7，先红后绿）

1. `BabiqAgentSpec` + 内置 explorer spec + 只读工具集（`BabiqAgentSpecTest`）。
2. `SubAgentRuntimeFactory` per-turn 构建 + 横切装配 + 精简上下文（`SubAgentRuntimeFactoryTest`）。
3. explorer 作 `AgentTool` 注册 + 委派调用回传，中间消息不外泄（`SubAgentDelegationTest`，假 ChatModel）。
4. `agentDelegation` 协议 item（后端 `ThreadItemJsonTest` + `ConversationServiceTest`）。
5. 运行记录归属 + migration（`SchemaCommentsCoverageTest` + `RunRecordServiceTest`）。
6. 模型继承/覆盖（`SubAgentModelResolutionTest`）。
7. 桌面端 `ThreadItem.AgentDelegation` + reducer + 渲染（`*ThreadItemJsonTest`/`*ChatReducerTest`/`*SubAgentSectionTest`）。
8. system prompt + 中文别名（`SystemPromptSecurityRuleTest` / `CapabilityAliasDictionaryTest`）。
9. 端到端 IT（`SubAgentDelegationIT`：委派→只读工具→沙箱拦写→归属落库→协议 item）。

## 执行规则

1. 严格按 plan §7 Task 1→9 TDD；改生产代码补**中文教学注释**（CLAUDE.md §4）。
2. **只读起步**：任何"下放写操作给子 Agent"的代码都是越界，禁止（留 P6-1b）。
3. 新表/字段必须同步 SQL 中文注释 + `bq_schema_comments` + Entity 注释 + 覆盖测试（CLAUDE.md §4 红线）。
4. 工具 `name` 必须 ASCII；中文检索靠 displayName/description/searchText（§4.1）。
5. 子 Agent 中间过程**不得灌父聊天流**；只回传摘要（对齐 supervisor pattern + 原型 P6 01）。
6. 薄封装官方 `AgentTool`，**不自研子 Agent runner**；借鉴 Codex/Claude Code 概念不照搬实现。
7. 每个 Task 中文 conventional commit（`feat(p6-1): ...` / `test(p6-1): ...`）。**不主动 push**。
8. 完成后更新 `CLAUDE.md` 检查点、`AGENTS.md`、p6 索引。
9. 没有新鲜证据不得声称完成（CLAUDE.md §8）。不允许 `@Disabled` 占位。

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
- 真实模型人工烟测：主 Agent 自行委派 explorer → 只读探查（写工具被沙箱拒）→ 回传摘要 → 主 Agent 综合；运行记录归属 `agent_name=explorer`；模型默认继承；桌面内联委派块 + 子 Agent 卡可见、对话栏全程可用；视觉对齐原型 P6 01。

## 完成报告必须包含

- Task 1–9 逐条 ✅/❌ + 跑过的命令与**实际输出**（非预期）。
- 只读三层兜底证据（工具集无写工具 + 沙箱拦写测试）。
- 子 Agent 中间过程不外泄、只回传摘要的证据。
- 运行记录归属（agent_name/delegation_id）落库证据 + `SchemaCommentsCoverageTest` 通过。
- emitter/toolContext 透传结论（AgentTool metadata 是否够，或 toolContext 显式补）。
- 中文 conventional commit 列表；明确未 push。

## 与 Codex / Claude Code 的区别（已核对源码，定位准确）

- **相同（借鉴对）**：只读 explorer 概念、去写工具 + 强提示、模型 inherit 默认、只回传摘要、whenToUse 驱动。
- **不同（我们的选择）**：① 薄封装官方 `AgentTool`，不自研 runner；② 只读 3 层防护（多一层沙箱）；③ 写类有意推迟到 asNode（Codex「审批上浮父 session」是 P6-1b 借鉴范式）；④ 强制走 BaBiQ Spotlighting + SQLite 归属；⑤ P6-1 单委派（并行后续）；⑥ 内置 spec（用户目录后续）。

## 下一步

- plan + 本 handoff 由用户确认 → 按 Task 1→9 TDD 实现 → 自动化 + 真实烟测闭环 → 评估 **P6-1b 写类委派（asNode，借鉴 Codex 审批上浮）** 或进 **P6-2 flow 编排**。
