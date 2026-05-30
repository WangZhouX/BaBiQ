# P5 ReasoningItem 接通（思考过程可视化）专项计划

> **For agentic workers:** 本文件是**已定稿的正式执行计划**。实施时用 `superpowers:executing-plans` + `superpowers:test-driven-development`，声称完成前用 `superpowers:verification-before-completion`。交接见同目录 `codex-handoff.md`。
>
> **状态：** 正式执行计划（2026-05-30 用户定稿）。§3 决策全部确认：D1=真实 `reasoning_content`、D2=聊天流内联折叠块、D3=思考中展开/完成后自动收起、D4=优雅缺省。本计划是 P1-1 最后一个未接通占位 item 的兑现，纵向特性「模型 → AgentLoop → 协议 → 桌面 UI」，属 P4 之后的新专项。
>
> **设计依据：** 对标 Codex 的 reasoning 摘要展示与 Claude 的 thinking 块；BaBiQ 模型层 `DeepSeekV4OpenAiChatModel` 已解析 DeepSeek V4 的 `reasoning_content`。

**Goal:** 把模型的「思考过程 / 推理」接通到 UI——在 turn 收尾时抽取模型产生的 reasoning（DeepSeek V4 已解析的 `reasoning_content`），通过已存在的 `ReasoningItem` 协议 + `emitReasoning` 管道发给桌面端，桌面端以**独立、淡色、默认收起的「思考过程」折叠块**渲染（紧贴它对应的回答），而不是混进普通回答气泡。

**Architecture:** 复用 BaBiQ 现成但**从未被调用**的管道：`ConversationService.emitReasoning(text)` + `ItemEmitter.emitReasoning(item)` + 协议 `ReasoningItem(type="reasoning")` + 桌面端 `ThreadItem.Reasoning` + serializer 分支。缺的只有两段：①agent loop 收尾处**真正调用** `emitReasoning`（从 AssistantMessage metadata 取 `reasoningContent`）；②桌面端把 reasoning 从「普通 agent 气泡」升级为**专属折叠块**。**不引入新机制、不新增数据库表**（reasoning 落已有 `bq_items`）。

**Tech Stack:** 同 P3/P4，不升级 Spring AI / Spring AI Alibaba。后端：`AgentLoopOutputHandler`（收尾抽 reasoning）+ 现成 `emitReasoning` 管道。桌面端：`ChatReducer` + 新增 `ChatMessage.Reasoning` UI 模型 + Compose 折叠块。

---

## 1. 为什么做 / 为什么是真缺口

### 1.1 协议占位、管道铺好、但从没被调用（半接通）

已用 grep 核对真实代码：

- `ReasoningItem`（`id`/`type`/`text`，`type="reasoning"`）已定义并在 `ThreadItem` 注册。Javadoc 原话：「P1-1 只定义 schema，**不输出推理内容**。后续如果模型或 agent loop 产生可展示的思考摘要，桌面端会通过该类型渲染。」
- 后端发送管道 `ConversationService.emitReasoning(text)`（L276）+ `ItemEmitter.emitReasoning(item)`（L152）**都已存在**。
- 桌面端 `ThreadItem.Reasoning` + `ThreadItemSerializer` 的 `"reasoning"` 分支**已存在**，`ChatReducer` 把它映射成 `ChatMessage.Agent(id, text)`（普通气泡）。
- **但全代码库没有任何地方调用 `emitReasoning`**（grep 只命中定义，无调用者）。所以推理内容从不产生。

**结论：和 P4 之前的 `PlanItem` 是同一类「占位未接通」，但 ReasoningItem 更接近完成——发送管道和桌面 variant 都现成，只差"抽取 + 专属渲染"两段。**

### 1.2 模型其实已经在产生思考内容，只是没展示

- `DeepSeekV4OpenAiChatModel` 已把 DeepSeek V4 的 `reasoning_content` 解析并存入 AssistantMessage metadata，常量 `REASONING_METADATA_KEY = "reasoningContent"`。
- 但这份 reasoning **目前只用于工具调用恢复回放**（CLAUDE.md 里的 DeepSeek V4 reasoning 回放补丁），**从没展示给用户**。
- 接通它＝把已经流过系统的思考内容顺手展示出来，几乎零额外成本（不额外调模型、不额外烧 token）。

### 1.3 Codex / Claude Code 都有此能力

Codex 展示 reasoning 摘要，Claude 展示 thinking 块。对 Codex-like 学习项目，"只给答案不给思路"是显眼短板；接通后用户能看到模型的推理链。

---

## 2. 范围边界

### 2.1 必做

- **后端**
  - 在 `AgentLoopOutputHandler` 的 completed 收尾路径，从最终 `AssistantMessage` 的 metadata 取 `reasoningContent`（`DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY`）；非空时在**发 assistant message item 之前**调用 `emitter.emitReasoning(...)` 发一条 ReasoningItem。
  - reasoning 为空（非 thinking 模型 / 无 reasoning_content）时**不发**——优雅缺省，UI 无思考块。
  - 确认 reasoning 内容**不进入 ContextAssembler 的 recent_history**（现状 `assembleHistory` 的 else 分支已把 ReasoningItem 排除为 RUNTIME_SUMMARY；本计划补一条测试钉死它，防止 reasoning 回灌污染下一轮上下文）。
  - reasoning 文本可能很长：落 `bq_items` 时按需做长度上限保护（如截断到 N 千字符 + 标记），避免 payload 膨胀。
- **桌面端**
  - 新增 `ChatMessage.Reasoning`（区别于 `ChatMessage.Agent`），`ChatReducer` 把 `ThreadItem.Reasoning` 映射成它（不再 fallback 成普通 agent 气泡）。
  - 新增 Compose「思考过程」折叠块：淡色 / 次要样式 / **进行中展开、所属 turn 完成后自动收起 / 可手动切换**；位置紧贴它所属 turn 的回答（在回答上方）。折叠状态由 turn 生命周期驱动（见 §3-D3）。
  - `thread/load` 历史恢复时，reasoning item 同样渲染成折叠块，且因所属 turn 已完成 → 默认收起（复用 `messagesFromItems`）。
- **测试**
  - 后端：收尾抽取 reasoning 并 emit 的测试（有 reasoning_content → 发 ReasoningItem 且在 assistant item 之前；无 → 不发）；ReasoningItem 不进 context recent_history 的回归测试；长度上限测试。
  - 桌面端：`ThreadItem.Reasoning` 解码、`ChatReducer` 映射成 `ChatMessage.Reasoning`、折叠块 Compose 渲染（收起/展开/空态）。

### 2.2 不做

- **不做方案 B（让模型额外写思路摘要）**，除非 §3-D1 选 B——默认用模型真实 `reasoning_content`。
- **不动 DeepSeek V4 reasoning 回放机制**（工具恢复用的内部 reasoning 链路不变；展示只是额外读同一份 metadata，不修改）。
- **不新增数据库表 / 不写 migration**（reasoning 落已有 `bq_items`）。
- **不把 reasoning 喂回模型上下文**（display-only）。
- **不做** reasoning 的二次加工/翻译/摘要（直接展示模型产出）。
- 不升级 Spring AI / Spring AI Alibaba 版本；`AgentLoop.invoke` 主循环不动（抽取放在 OutputHandler，不进 ≤50 行主循环）。

---

## 3. 决策（已定稿）

### D1：reasoning 来源 —— ✅ 真实 `reasoning_content`

用 DeepSeek V4 thinking 已解析进 AssistantMessage metadata 的 `reasoning_content`（`DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY="reasoningContent"`）。零额外 token、最真实，对标 Codex/Claude 的"展示真实推理"。
**代价（接受）**：能力只对 thinking 模型生效；非 thinking 模型无此字段 → 无思考块（见 D4）。**不做**方案 B（让模型额外写思路摘要）。

### D2：UI 位置 —— ✅ 聊天流内联折叠块

紧贴对应回答（在其上方），淡色 / 次要样式。**关键区别于 P4**：计划是持久运行状态 → 右侧面板、不进 messages；**reasoning 是某条回答的临时思考 → 内联折叠块、进 messages**。Codex/Claude 也都把 thinking 放回答旁内联，不放侧栏。
> 现状 reducer 把 Reasoning 映射成普通 `ChatMessage.Agent`（混进回答气泡）——错误 fallback，本计划改成独立 `ChatMessage.Reasoning` 折叠块。

### D3：折叠生命周期 —— ✅ 思考中展开 / 完成后自动收起

一行「💭 思考过程 ▸」。折叠状态**由所属 turn 的生命周期驱动，不固化在 item 上**：

- **进行中（owning turn 仍 Running）**：思考块**展开**，用户实时看到模型在想什么。
- **该 turn 完成（turn/completed 或最终回答到达）**：思考块**自动收起**成一行。
- **历史会话加载**（过去的 turn 都已完成）→ 全部**收起**。
- 用户可手动点开/收起，**手动操作覆盖自动状态**。

> 这正是 Codex/Claude 的行为：边想边展示、想完收起。为让"展开阶段"有意义，reasoning 应尽量**早发**（在最终回答之前/期间），优先走流式；若实现上只能在收尾拿到，则退化为"收尾瞬间展开 → turn 完成即收起"，但**历史态收起**这条必须保证（见 §4 Task 1/2）。

### D4：非 thinking 模型 —— ✅ 优雅缺省

非 thinking 模型（无 `reasoning_content`）→ 不发 ReasoningItem → 无思考块、不报错、不占位。文档说明"思考块仅在 thinking 模型可用"。

---

## 4. 实施任务（决策确认后细化；基于 §3 推荐 A/A/收起/优雅缺省）

### Task 1: 验证 reasoning_content 可达性 + 抽取点选择（先验，决定可行性）

**Files:** 仅探查 + 一个验证测试。

**Steps:**
- [ ] 确认 DeepSeek V4 的 `reasoning_content` 经 SAA graph 后，能从 `AgentLoopOutputHandler` 拿到的最终 `AssistantMessage.getMetadata()` 取到（key=`reasoningContent`）。
- [ ] 判定抽取点（影响 §3-D3 "展开阶段"是否有意义）：
  - **优先**：能否在流式阶段（`BaBiQStreamingTokenUsageInterceptor` 同款 `StreamingModelInterceptor.onStreamChunk` 扩展点）拿到 reasoning chunk，从而**在最终回答之前/期间就 emit**（让思考块先展开）。
  - **退化**：若只能在收尾 metadata 拿到，则收尾一次性 emit；此时"展开阶段"接近瞬时，但**历史态收起**仍由 turn 生命周期保证。
- [ ] 写验证测试/烟测确认能拿到非空 reasoning，并记录最终选用的抽取点。

**Commit：** `test(p5): 验证 reasoning_content 可达性与抽取点`

---

### Task 2: 后端收尾抽取并发出 ReasoningItem

**Files:**
- Modify: `backend/.../agent/AgentLoopOutputHandler.java`（completed 路径）
- 复用：`ConversationService.emitReasoning` / `ItemEmitter.emitReasoning`（已存在）
- Test: 新增 `AgentLoopReasoningEmitTest`（或并入现有 OutputHandler 测试）

**Steps:**
- [ ] 写失败测试：有 `reasoningContent` → 发 ReasoningItem 且在 assistant message item **之前**（保证 UI 思考块在回答上方）；无 reasoning → 不发。
- [ ] 按 Task 1 选定的抽取点 emit：优先流式早发（turn 仍 Running 时发，让思考块先展开），否则收尾一次性发。非空且超长则截断（加省略标记），调 `emitReasoning`。
- [ ] 确认不影响现有 token 统计、TurnSummary、DeepSeek V4 工具恢复回放（展示只读同一份 reasoning，不修改回放逻辑）。

**Commit：** `feat(p5): 抽取模型 reasoning 并发出 ReasoningItem`

---

### Task 3: 钉死 reasoning 不进上下文（防回灌污染）

**Files:**
- Verify/Modify: `backend/.../context/ContextAssembler.java`（`assembleHistory`）
- Test: `ContextAssemblerTest` 增用例

**Steps:**
- [ ] 确认 `ReasoningItem` 在 `assembleHistory` 被排除（现状走 else → RUNTIME_SUMMARY）；若需要更明确，给它单独的 `ContextExclusionReason`（如 `REASONING_DISPLAY_ONLY`）。
- [ ] 加测试：历史里含 ReasoningItem 时，它不进 envelope.recent_history、被标为 excluded。

**Commit：** `test(p5): 钉死 reasoning 为 display-only 不进上下文`

---

### Task 4: 桌面端 reasoning 折叠块

**Files:**
- Modify: `desktop/.../state/UiModels.kt`（新增 `ChatMessage.Reasoning`）
- Modify: `desktop/.../state/ChatReducer.kt`（`ThreadItem.Reasoning` → `ChatMessage.Reasoning`，不再 fallback agent 气泡）
- Create: `desktop/.../ui/chat/ReasoningBlock.kt`（折叠块）
- Modify: `MessageList.kt` / `MessageBubble.kt`（渲染分支）
- Test: `ChatReducerTest` + `ReasoningBlockTest`

**Steps:**
- [ ] `ChatMessage.Reasoning(id, text)`（带所属 turnId，用于按 turn 生命周期判定折叠）+ reducer 映射；保持在 messages 流内（紧贴回答，区别于 P4 的 plan 不进 messages）。
- [ ] 折叠块：淡色/次要样式，标题「💭 思考过程 ▸」。**折叠状态 = 进行中（owning turn==当前 Running turn）展开 / 否则收起**，用户手动切换覆盖自动态。
- [ ] `messagesFromItems` 历史恢复同样产出 Reasoning 折叠块；历史 turn 已完成 → 收起。
- [ ] Compose 测试：进行中展开、turn 完成后自动收起、历史态收起、手动切换、空 reasoning 不渲染、与普通气泡区分。

**Commit：** `feat(p5): 桌面端思考过程折叠块（进行中展开/完成收起）`

---

### Task 5: 端到端验证 + 文档同步

**Steps:**
- [ ] 后端专项 + `clean verify`；桌面端 `gradlew.bat test`。
- [ ] 真实模型人工烟测（DeepSeek V4 thinking）：复杂任务出现思考块、默认收起可展开；非 thinking 模型无思考块且无报错。
- [ ] 文档：CLAUDE.md / AGENTS.md 检查点；可补 `learn/` 走读。

**Commit：** `docs(p5): 同步 ReasoningItem 接通状态`

---

## 5. 验证清单（定稿后补具体命令）

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=AgentLoopReasoningEmitTest,ContextAssemblerTest,ThreadItemJsonTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ChatReducerTest" --tests "*ReasoningBlockTest" --tests "*ThreadHistoryModelsTest"
.\gradlew.bat test
```

人工烟测覆盖：thinking 模型出现思考块（默认收起）/ 非 thinking 模型无块无错 / reasoning 不污染下一轮上下文。

---

## 6. 风险与处理

| 风险 | 严重度 | 处理 |
|---|---|---|
| `reasoning_content` metadata 经 SAA graph 后被剥离，收尾取不到 | 中 | Task 1 先验；备选从 streaming interceptor 缓存 reasoning 到 `TurnObservationContext`（和 token usage 同款） |
| 仅 DeepSeek V4 thinking 有 reasoning，其它模型无 | 低 | 设计即优雅缺省（D4）；文档说明能力边界 |
| reasoning 很长撑大 `bq_items` payload | 低 | 落库前长度上限截断 + 省略标记 |
| reasoning 误进上下文导致回灌/token 膨胀 | 中 | Task 3 钉死排除 + 测试 |
| 与 DeepSeek V4 工具恢复回放冲突 | 低 | 展示只读同一份 metadata，不修改；回放逻辑不动 |
| reasoning 当作不可信指令？ | 低 | reasoning 是模型自身输出（非外部数据），不需要 Spotlighting；但不喂回上下文（见 Task 3） |
| 现状 reducer 把 Reasoning 当普通 agent 气泡 | 低 | Task 4 改为独立 `ChatMessage.Reasoning`，避免混淆 |

---

## 7. 完成标准（实现时逐条勾选）

- [ ] 收尾能从 AssistantMessage 取到 reasoning 并 emit ReasoningItem（在 assistant item 之前）；无 reasoning 不发
- [ ] reasoning 落 `bq_items`，超长有上限保护
- [ ] ReasoningItem 不进 ContextAssembler recent_history（有测试钉死）
- [ ] 桌面端 `ChatMessage.Reasoning` + 折叠块（进行中展开 / turn 完成后自动收起 / 历史态收起 / 可手动切换 / 空态不渲染），不再 fallback 普通气泡
- [ ] `thread/load` 历史恢复思考块（且因 turn 已完成默认收起）
- [ ] 后端 `clean verify` + 桌面端 `gradlew.bat test` 全绿，`AgentLoopLineCountTest` 不退化
- [ ] 真实模型烟测：thinking「思考中展开 → 完成收起 → 历史收起」/ 非 thinking 无块无错 / reasoning 不污染上下文
- [ ] CLAUDE.md / AGENTS.md 检查点同步
- [ ] 中文 conventional commit，未 push

---

## 8. 阶段门禁

- P1–P4 已全量闭环、P3 总体验收已通过（2026-05-30），本专项可在用户确认 §3 决策后启动。
- 本特性是 P4 之后的新专项，独立分支实现，合并入 master 前自动化 + 真实模型烟测须通过。

---

## 9. 与现有协议 / 能力的衔接

- 复用：`ReasoningItem`（已注册）、`ConversationService.emitReasoning` / `ItemEmitter.emitReasoning`（已存在管道）、`DeepSeekV4OpenAiChatModel` 的 `reasoningContent` metadata、`item/added` 协议、桌面 `ThreadItem.Reasoning` + serializer（已存在）。
- 兑现：P1-1 预留的 `ReasoningItem` 占位（最后一个未接通的 P1-1 item）。
- 边界清晰：**Plan（P4）= 持久运行状态 → 右侧面板、不进 messages、不进上下文**；**Reasoning（P5）= 某条回答的临时思考 → 内联折叠块、进 messages、不进上下文**。

---

## 10. 下一步

1. ✅ §3 决策已定（D1 真实 `reasoning_content` / D2 内联折叠块 / D3 进行中展开-完成收起 / D4 优雅缺省）。
2. ✅ 已编写同目录 `codex-handoff.md`。
3. 按 §4 Task 1 → 5 顺序执行（Task 1 先验抽取点，决定流式早发 vs 收尾发）；每个 Task 中文 conventional commit、不 push。
4. reasoning 折叠块视觉简单，可直接对齐现有气泡风格实现，不强制先出 Figma 原型。

---

## 11. 参考来源（已实读核对）

- BaBiQ 现状：
  - `backend/.../conversation/items/ReasoningItem.java`（占位 schema）
  - `backend/.../conversation/ConversationService.java#L276 emitReasoning` + `ItemEmitter.java#L152 emitReasoning`（管道已有、无调用者）
  - `backend/.../org/springframework/ai/openai/DeepSeekV4OpenAiChatModel.java`（`REASONING_METADATA_KEY="reasoningContent"`，已解析 reasoning_content，仅用于工具恢复回放）
  - `desktop/.../protocol/ThreadModels.kt`（`ThreadItem.Reasoning` + serializer 已有）
  - `desktop/.../state/ChatReducer.kt`（现状 `is Reasoning -> ChatMessage.Agent` 普通气泡，待改）
  - `backend/.../context/ContextAssembler.java`（`assembleHistory` else 分支已排除 reasoning）
- 业界对标：Codex reasoning 摘要展示；Claude Code thinking 块（默认折叠）。
