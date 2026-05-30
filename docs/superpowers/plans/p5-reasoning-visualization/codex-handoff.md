# P5 ReasoningItem 接通（思考过程可视化）— Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p5-reasoning-visualization\plan.md`

## 当前状态

- **计划已定稿为正式执行计划**（2026-05-30）。§3 决策（D1-D4）全部确认。
- **代码尚未实现**——这是接下来要做的事。
- **前置条件已满足**：P1–P4 全量闭环、P3 总体验收已通过（2026-05-30）。可直接启动本专项。
- 不强制先出 Figma 原型（reasoning 折叠块视觉简单，对齐现有气泡风格即可）。

## 一句话目标

把模型的真实思考过程接通到 UI：turn 期间抽取 DeepSeek V4 已解析的 `reasoning_content`，通过**现成但从未被调用**的 `emitReasoning` 管道发 `ReasoningItem`，桌面端以**聊天流内联的「💭 思考过程 ▸」折叠块**渲染——**进行中展开、所属 turn 完成后自动收起**，非 thinking 模型优雅缺省。

## 关键事实（grep 已核对，决定这是"接通"而非"从零造"）

- `ReasoningItem`（`id`/`type`/`text`，`type="reasoning"`）**已定义并注册**。
- 后端发送管道 `ConversationService.emitReasoning(text)` + `ItemEmitter.emitReasoning(item)` **都已存在，但全代码库 0 个调用者**。
- 桌面端 `ThreadItem.Reasoning` + `ThreadItemSerializer` 的 `"reasoning"` 分支**已存在**；`ChatReducer` 现状把它 fallback 成普通 `ChatMessage.Agent` 气泡（**错误，要改**）。
- `DeepSeekV4OpenAiChatModel` **已解析 DeepSeek V4 的 `reasoning_content`** 进 AssistantMessage metadata，常量 `REASONING_METADATA_KEY = "reasoningContent"`；目前**只用于工具调用恢复回放，从未展示**。
- `ContextAssembler.assembleHistory` 现状 else 分支已把 `ReasoningItem` 排除（不进 recent_history）——本计划补测试钉死它。

**缺口只有两段**：①agent loop 真正调 `emitReasoning`（从 metadata 取 reasoning）；②桌面端把 reasoning 从普通气泡升级为专属折叠块（含 turn 生命周期折叠）。

## 已定决策（§3）

| # | 决策 | 结论 |
|---|---|---|
| D1 | reasoning 来源 | **真实 `reasoning_content`**（DeepSeek V4 metadata，零额外 token）。不做"让模型额外写摘要"。代价：仅 thinking 模型有。 |
| D2 | UI 位置 | **聊天流内联折叠块**（贴回答上方）。区别于 P4：plan 进右侧面板不进 messages；reasoning 进 messages。 |
| D3 | 折叠生命周期 | **进行中展开 / 所属 turn 完成后自动收起 / 历史态收起 / 可手动切换**。折叠状态由 turn 生命周期驱动，不固化在 item 上。 |
| D4 | 非 thinking 模型 | **优雅缺省**：无 reasoning → 不发 item、无块、不报错、不占位。 |

## 关键代码挂点

| 文件 | 动作 | 原因 |
|---|---|---|
| `backend/.../agent/AgentLoopOutputHandler.java` | 修改 | 取 AssistantMessage metadata `reasoningContent`，非空时在发 assistant message item **之前**调 `emitReasoning`；优先按 Task 1 选定的流式早发点 |
| `backend/.../conversation/ConversationService.java` / `ItemEmitter.java` | **复用** | `emitReasoning` 管道已存在，不用新建 |
| `backend/.../context/ContextAssembler.java` | 验证/微调 | 确认 ReasoningItem 不进 recent_history（display-only），补测试 |
| `desktop/.../state/UiModels.kt` | 修改 | 新增 `ChatMessage.Reasoning(id, text, turnId)` |
| `desktop/.../state/ChatReducer.kt` | 修改 | `ThreadItem.Reasoning` → `ChatMessage.Reasoning`（不再 fallback agent 气泡）；保持在 messages 流内 |
| `desktop/.../ui/chat/ReasoningBlock.kt` | **新增** | 「💭 思考过程 ▸」折叠块，折叠状态按 turn 生命周期 |
| `desktop/.../ui/chat/MessageList.kt` / `MessageBubble.kt` | 修改 | 渲染分支接入 Reasoning 折叠块 |
| 后端 / 桌面端测试 | **新增** | 见 plan §2.1 / §4 |

不动：

- **不新增数据库表 / 不写 migration**（reasoning 落已有 `bq_items`）。
- **不动 DeepSeek V4 reasoning 回放机制**（展示只读同一份 metadata，不修改回放）。
- **不把 reasoning 喂回模型上下文**（display-only；Task 3 钉死）。
- 不做方案 B、不做 reasoning 二次加工/翻译、不升级 Spring AI / SAA、`AgentLoop.invoke` 主循环不动。

## 执行规则

1. 严格按 plan §4 的 Task 1 → 5 顺序。**Task 1 先验**：reasoning 能否拿到 + 抽取点（流式早发 vs 收尾发），它决定 D3"展开阶段"是否有意义。
2. 用 `superpowers:test-driven-development`，每个 Task 先写失败测试再实现。
3. ReasoningItem **进 messages**（区别于 P4 plan 不进 messages）——它是某条回答的内联思考，但**不进上下文**（Task 3 测试钉死）。
4. 折叠状态**由 turn 生命周期驱动**，不要固化在 item 或后端：进行中（owning turn==当前 Running）展开，否则收起，手动切换覆盖。
5. 非 thinking 模型：无 reasoning 一律不发、不占位、不报错。
6. reasoning 落 `bq_items` 前做长度上限保护（超长截断 + 省略标记）。
7. 新增/改动生产代码补中文教学注释（CLAUDE.md §4）。
8. 每个 Task 中文 conventional commit（`feat(p5): ...` / `test(p5): ...` / `docs(p5): ...`）。**不主动 push**。
9. 完成后更新 `AGENTS.md`、`CLAUDE.md` 检查点（及 p3/p5 索引）。
10. 不允许 `@Disabled` 占位测试用例。

## 关键默认参数

| 参数 | 值 | 说明 |
|---|---|---|
| reasoning metadata key | `DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY` = `"reasoningContent"` | 抽取来源 |
| 抽取点 | Task 1 决定：优先流式早发，否则收尾 metadata | 影响"展开阶段"是否有意义 |
| emit 顺序 | ReasoningItem 在 assistant message item **之前** | 保证 UI 思考块在回答上方 |
| reasoning 落库长度上限 | 实现时定（如几千字符）+ 省略标记 | 防 `bq_items` payload 膨胀 |
| 折叠状态来源 | turn 生命周期（前端计算），非持久化 | 进行中展开/完成收起 |
| 进上下文？ | 否（display-only，excluded from recent_history） | Task 3 测试钉死 |

## 最终验收命令

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=AgentLoopReasoningEmitTest,ContextAssemblerTest,ThreadItemJsonTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ChatReducerTest" --tests "*ReasoningBlockTest" --tests "*ThreadHistoryModelsTest"
.\gradlew.bat test
```

人工烟测（需 DeepSeek V4 thinking + API Key）必须覆盖：
- thinking 模型：思考中**展开** → turn 完成**自动收起** → 历史加载**收起**。
- 非 thinking 模型：无思考块、无报错。
- reasoning **不污染下一轮上下文**（多轮对话后上下文窗口不含历史 reasoning）。

## 完成报告必须包含

- Task 1-5 逐条完成状态（✅/❌）。
- 跑过的验证命令和**实际输出**（不是预期）。
- Task 1 结论：最终选用的抽取点（流式 / 收尾）+ 证据。
- ReasoningItem 不进上下文的测试证据。
- 折叠生命周期（进行中展开/完成收起/历史收起）的测试证据。
- 真实模型烟测三种场景结果。
- `AgentLoopLineCountTest` 不退化、DeepSeek V4 工具恢复回放未受影响的证据。
- 中文 conventional commit 列表；明确说明未 push。

## 下一步

- 实现完成、`clean verify` + 桌面端测试全绿、真实模型烟测通过后，才可声称 P5 完成。
- 至此 P1-1 的全部占位 item（含 PlanItem、ReasoningItem）已兑现。后续候选：语义检索（VectorStore + embedding，P3-5a 预留）、Multi-Agent/子 Agent（独立大阶段），均须先写详细 plan 并由用户确认。
