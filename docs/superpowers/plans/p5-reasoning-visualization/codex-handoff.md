# P5 ReasoningItem 接通（思考过程可视化）— Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p5-reasoning-visualization\plan.md`

## 当前状态

- **代码实现已完成**（2026-05-30）：后端已抽取真实 `reasoning_content` 并发 `ReasoningItem`，桌面端已按 Figma 原型渲染聊天流内联「思考过程」折叠块。
- **自动化验收已通过**：后端专项、后端 `clean verify`、桌面端专项、桌面端全量测试均为 BUILD SUCCESS。
- **人工烟测待用户环境复验**：需要真实 DeepSeek V4 thinking Provider/API Key，验证思考中展开、完成后收起、历史收起，以及非 thinking 模型无块无错。
- **Figma 原型已出**（页 `35:2`），实现按此对齐：
  - `P3 14 会话-思考过程（展开）`（节点 `171:2`）：状态「运行中」+ 💭 思考过程卡**展开**显示推理正文 + 「收起 ▴」+「正在生成回答…」。
  - `P3 15 会话-思考过程（收起）`：状态「已连接」+ 思考卡**收成一行**「思考过程 · 已完成　展开 ▸」+ 完整回答。
  - 「00 交互总览-P3」（节点 `35:3`）补「思考过程·展开 / 思考过程·收起」2 张索引卡 + 主线说明。
  - 注：原型里 💭 emoji 因 Inter 无 emoji 字形未显示，仅文字；Compose 实现走系统 emoji 字体会正常显示。

## 一句话目标

把模型的真实思考过程接通到 UI：turn 期间抽取 DeepSeek V4 已解析的 `reasoning_content`，通过**现成但从未被调用**的 `emitReasoning` 管道发 `ReasoningItem`，桌面端以**聊天流内联的「💭 思考过程 ▸」折叠块**渲染——**进行中展开、所属 turn 完成后自动收起**，非 thinking 模型优雅缺省。

## 关键事实（grep 已核对，决定这是"接通"而非"从零造"）

- `ReasoningItem`（`id`/`type`/`text`，`type="reasoning"`）**已定义并注册**。
- 后端发送管道 `ConversationService.emitReasoning(text)` + `ItemEmitter.emitReasoning(item)` 原本已存在但无调用者；本次已在 agent 输出链路新增真实调用点。
- 桌面端 `ThreadItem.Reasoning` + `ThreadItemSerializer` 的 `"reasoning"` 分支**已存在**；`ChatReducer` 现状把它 fallback 成普通 `ChatMessage.Agent` 气泡（**错误，要改**）。
- `DeepSeekV4OpenAiChatModel` **已解析 DeepSeek V4 的 `reasoning_content`** 进 AssistantMessage metadata，常量 `REASONING_METADATA_KEY = "reasoningContent"`；目前**只用于工具调用恢复回放，从未展示**。
- `ContextAssembler.assembleHistory` 现状 else 分支已把 `ReasoningItem` 排除（不进 recent_history）——本计划补测试钉死它。

**本次已补齐的缺口**：①agent loop / stream consumer 真正调用 `emitReasoning`（从 metadata 取 reasoning）；②桌面端把 reasoning 从普通气泡升级为专属折叠块（含 turn 生命周期折叠）。

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
| `backend/.../agent/AgentLoopOutputHandler.java` | 已修改 | 非流式/最终输出路径取 AssistantMessage metadata `reasoningContent`，非空时在发 assistant message item **之前**调 `emitReasoning` |
| `backend/.../agent/AgentStreamConsumer.java` | 已修改 | 流式 chunk 中一旦出现 reasoning metadata 就先发 ReasoningItem，后续变化用 `item/updated` 刷新 |
| `backend/.../agent/ReasoningContentSupport.java` | 已新增 | 统一抽取 `reasoningContent`、过滤空值并做长度上限保护 |
| `backend/.../conversation/ConversationService.java` / `ItemEmitter.java` | **复用** | `emitReasoning` 管道已存在，不用新建 |
| `backend/.../context/ContextAssembler.java` | 已修改 | ReasoningItem 以 `REASONING_DISPLAY_ONLY` 明确排除，不进 recent_history |
| `desktop/.../state/UiModels.kt` | 已修改 | 新增 `ChatMessage.Reasoning(id, text, completed)` |
| `desktop/.../state/ChatReducer.kt` | 已修改 | `ThreadItem.Reasoning` → `ChatMessage.Reasoning`，turn completed/failed 和历史加载后标记 completed |
| `desktop/.../ui/chat/MessageBubble.kt` | 已修改 | 复用现有气泡渲染，按原型增加 Reasoning 专属淡灰背景、边框、标题与折叠行为 |
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
| 抽取点 | 流式 chunk 优先早发，非流式/最终输出兜底补发 | 兼顾运行中展开和非流式输出 |
| emit 顺序 | ReasoningItem 在 assistant message item **之前** | 保证 UI 思考块在回答上方 |
| reasoning 落库长度上限 | `12_000` 字符 + 截断提示 | 防 `bq_items` payload 膨胀 |
| 折叠状态来源 | turn 生命周期（前端计算），非持久化 | 进行中展开/完成收起 |
| 进上下文？ | 否（`REASONING_DISPLAY_ONLY`，excluded from recent_history） | Task 3 测试钉死 |

## 本次完成证据

- **Task 1 结论**：Context7 已确认 Spring AI 的 `ChatResponse` / `AssistantMessage` 可通过 metadata 承载模型扩展字段；当前 BaBiQ 使用 `DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY = "reasoningContent"`。实现选择“流式 chunk 优先早发，非流式/最终输出兜底补发”；如果流式 provider 不在 chunk 中带 reasoning，则保持现有 streaming 路径不额外反查最终 assistant，避免破坏既有 `AgentLoop` 流式测试约束。
- **Codex / Claude Code 对齐**：Codex 使用独立 `reasoning` item 和流式 delta；Claude Code thinking 块在运行时可展开、完成/历史态收起。BaBiQ 采用同样的“内联 display-only 折叠块”语义。
- **后端实现**：`ReasoningContentSupport` 统一抽取和截断；`AgentStreamConsumer` 负责流式早发/更新；`AgentLoopOutputHandler` 负责非流式兜底；`ContextAssembler` 用 `REASONING_DISPLAY_ONLY` 明确排除 reasoning。
- **桌面实现**：`ChatMessage.Reasoning` 保持在 messages 流内；`MessageBubble` 按原型用淡灰底、细边框、`💭 思考过程` / `💭 思考过程 · 已完成` 标题展示，进行中展开，完成/失败/历史态收起，可手动展开。
- **未执行项**：真实 Provider/API Key 人工烟测未在本次自动化环境执行，需在本机 DeepSeek V4 thinking 配置可用时复验。

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

- 代码实现与自动化验收已完成；下一步是在真实 DeepSeek V4 thinking Provider/API Key 环境做人工烟测。
- 至此 P1-1 的全部占位 item（含 PlanItem、ReasoningItem）已兑现。后续候选：语义检索（VectorStore + embedding，P3-5a 预留）、Multi-Agent/子 Agent（独立大阶段），均须先写详细 plan 并由用户确认。
