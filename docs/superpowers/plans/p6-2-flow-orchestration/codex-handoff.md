# P6-2 flow 编排 — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p6-2-flow-orchestration\plan.md`
> 前置：**P6-1（委派底座）必须先完成**；`p6-0-mechanism-spike/`（基座结论）。
> 总纲：`E:\BaBiQ\docs\superpowers\plans\p6-master.md`

## 当前状态

- **plan 已定稿（草案待用户确认）**（2026-05-31，已并入评审更正：节点**可读可写**、并行真实工作、approve-once 运行前整体批准的 4 条安全语义）。
- **实现尚未开始**：无 P6-2 生产代码。
- **真实生产实现**（TDD），对齐 Figma 原型 `P6 02`（`206:2`）/ `P6 06`（`230:2`）/ `P6 07`（`237:2`）/ `P6 08`（`242:2`）。

## 一句话目标

让主 Agent 把多步任务**编排成多个子 Agent 的工作流**（顺序 / 并行 / 路由），**节点可读可写、并行做真实工作**（探查 / 分析 / 实现 / 跑测试），薄封装 SAA 官方 `SequentialAgent` / `ParallelAgent` / `LlmRoutingAgent`；写操作受沙箱 + **运行前整体批准（approve-once）** 管；桌面端「编排详情」分屏可视化拓扑 + 节点状态、可配节点「任务 + 模型」、可增删节点；**对话始终是主体**。

## 必读入口

1. `E:\BaBiQ\CLAUDE.md`（§3 边界、§4 先查官方/薄封装、§4.1 命名、§5 验收、§7 Git、§8 汇报）
2. `p6-2-flow-orchestration/plan.md`（**完整计划：§3 flow API / §4 D1–D8 / §5 挂点 + §5.1 approve-once 4 条 / §7 DB / §8 TDD / §9 验收**）
3. **前置 P6-1 产物**：`BabiqAgentSpec`、`SubAgentRuntimeFactory`、`agentDelegation` 协议、运行记录归属、子 Agent 防护——P6-2 复用并按节点类型扩展。
4. BaBiQ 挂点：`ReActStrategy`、`approval/*`（FlowApprovalService 复用）、`interceptor/*`（沙箱）、`persistence/*`、`conversation/items/*`、`model/ChatClientFactory.java`、`security/SystemPromptSecurityRule.java`、`capability/*`
5. 借鉴源（**概念，不照搬**）：Codex worker「文件 ownership」（并行写不互踩）；Codex/Claude Code 多 Agent 编排——BaBiQ 薄封装 SAA 官方 flow agent，不自研编排引擎。

## 范围（先钉死）

**做**：子 Agent flow 编排（**节点可读可写**，Sequential / Parallel / LlmRouting）+ 每节点复用 P6-1 底座 + 运行前整体批准（approve-once）+ 编排详情分屏 UI + 节点配置/增删 + 并发归属。

**不做（明确推迟）**：
- **运行中逐工具交互审批 + 并行分支并发中断** → **P6-2b**（需 `StateGraph + asNode + 共享 saver` + 并发中断处理；flow agent `.invoke()` 是 run-to-completion，不 surface 中断）。
- **`LoopAgent`（循环）**、自由连边图编辑器、用户自定义 agent 目录、实时 team（P6-3）、`a2a.*`、升级 SAA/Spring AI。
- 不改 `AgentLoop.invoke` 行数约束（`AgentLoopLineCountTest` 不退化）。

## flow.agent API（Context7 文档来源 + jar 已核对**类存在**；方法签名实现前必须 `jar tf` / `javap` 复核 1.1.2.3）

- `SequentialAgent.builder().name().description().subAgents(List.of(a,b)).build()`；每子 Agent `.outputKey("k")`，后节点读 `{k}`；`.invoke(input)→Optional<OverAllState>`。
- `ParallelAgent.builder().name().description().mergeOutputKey("merged").subAgents(List).mergeStrategy(new ParallelAgent.DefaultMergeStrategy()).build()`；并发 + 合并。
- `LlmRoutingAgent.builder().name().description().model(chatModel).subAgents(List).build()`；LLM 路由选一个子 Agent。
- 可嵌套：`SequentialAgent.subAgents(List.of(parallelAgent, analysisAgent, routingAgent))`。
- **⚠️ jar 核对硬规则（SupervisorAgent 教训，必做）**：以上 builder 签名（`subAgents` / `outputKey` / `mergeOutputKey` / `mergeStrategy` / `ParallelAgent.DefaultMergeStrategy` / `.invoke`）来自 Context7 **v1.1.2.2 文档**，本仓只 jar 核实了**类存在**、未核实方法签名。**实现前必须 `javap` 锁定版 `1.1.2.3` jar 逐个确认方法/签名真实存在**——`SupervisorAgent` 已证「文档有、1.1.2.3 jar 无」。签名不符时以 jar 为准、相应调整实现，不得照搬文档。
- **边界**：flow agent `.invoke()` run-to-completion，**不通过 `invokeAndGetOutput` surface `InterruptionMetadata`** ⇒ 写节点用 approve-once（运行前批），运行中不逐工具弹；逐工具中断 = P6-2b。

## 已定决策（plan §4，D1–D8）

- **D1** 节点可读可写（并行真实工作）；写受沙箱 + 运行前整体批准；运行中逐工具审批 + 并发中断 → P6-2b。
- **D2** 拓扑 = Sequential / Parallel / LlmRouting；增删节点限这些结构内；Loop 后续。
- **D3** 每节点 = `BabiqAgentSpec`（任务 + 模型 + 工具集，**只读 explorer 或可写 worker**），`SubAgentRuntimeFactory` per-turn 构建 + 横切 + 精简上下文 + 沙箱。
- **D4** 编排由主 Agent 发起（delegation 升级）；用户可在编排详情查看/编辑 + 增删。
- **D5** 协议 = `orchestration` item（拓扑 + 节点状态）+ 复用 `agentDelegation` 表示节点；中间过程不灌父聊天流。
- **D6** 并发安全（ParallelAgent）：归属 / token / 运行记录线程安全，每节点独立 delegation_id。
- **D7** 节点「任务 + 模型」配置 + 增删持久化。
- **D8** UI：编排卡 + 编排详情分屏（对话保留）。

## ⚠️ approve-once 4 条安全语义（plan §5.1，安全关键，必须满足）

> **approve-once = 沙箱内 + 已声明范围内的一次性执行授权，不是 god-mode。**

1. **沙箱仍是硬边界**：批准 ≠ 关沙箱；写仍受 PathGuard / 沙箱模式约束；approve-once 不提升沙箱档位。
2. **弹窗列清范围**：approval/request 展示涉及节点 / 各任务 / 工具（读/写/命令）/ 写路径 / 沙箱模式——不签空白支票。
3. **批准后 flow 冻结**：固定节点/拓扑/工具/写范围；运行中不得加节点或扩权；要改 → 停止、改编排、重新批准。
4. **危险不可逆操作由沙箱禁止**：删工作区外 / `git push` / 联网等由沙箱层拒绝，不靠这次审批放行（除非用户显式选「完全访问」沙箱）。

## TDD 任务顺序（plan §8，先红后绿）

1. `BabiqFlowSpec` / `BabiqFlowNode` + 拓扑校验。
2. `FlowOrchestrationService` 组装 Sequential/Parallel/Routing + `.invoke`（假 ChatModel）。
3. 每节点经 `SubAgentRuntimeFactory` 构建 + 横切 + 沙箱 + 精简上下文（只读 explorer 与可写 worker 都正确装配）。
4. **并发安全**：ParallelAgent 并行节点归属/token/运行记录线程安全。
5. `FlowApprovalService` approve-once（运行前整批 + 4 条语义：沙箱硬边界 / 范围展示 / 冻结 / 危险禁止）。
6. 协议 `orchestration` + 节点 item。
7. flow 定义 + 节点配置持久化 + migration（`SchemaCommentsCoverageTest`）。
8. 节点模型继承/覆盖。
9. 桌面端协议 + reducer + 编排详情/节点设置/增删渲染。
10. 端到端 IT（`FlowOrchestrationIT`：explorer 探查→(worker 实现∥tester 跑测试)→汇总，运行前整批、并发归属落库、写受沙箱、协议 item、对话保留）。

## 执行规则

1. 严格按 plan §8 Task 1→10 TDD；生产代码补**中文教学注释**（CLAUDE.md §4）。
2. **approve-once 安全 4 条不可省**（plan §5.1）——尤其沙箱硬边界、批准后冻结。
3. **并行写**：借鉴 Codex worker「文件 ownership」，给各并行写节点分配**不重叠**文件/职责，告知"不是唯一改代码者、不回滚他人改动"。
4. 新表/字段同步 SQL 中文注释 + `bq_schema_comments` + Entity 注释 + 覆盖测试（§4 红线）。
5. 工具/Agent `name` ASCII；中文走 displayName/description/searchText。
6. 节点中间过程不灌父聊天流；薄封装官方 flow agent，不自研编排引擎。
7. **并发归属线程安全**（Task 4）——并行节点同写 `bq_tool_calls`/token/emitter。
8. 每个 Task 中文 conventional commit（`feat(p6-2): ...`）。**不主动 push**。
9. 完成后更新 `CLAUDE.md` 检查点 / `AGENTS.md` / p6 索引。无新鲜证据不得声称完成；不允许 `@Disabled`。

## 验收（plan §9）

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=BabiqFlowSpecTest,FlowOrchestrationServiceTest,FlowNodeRuntimeTest,FlowConcurrencyAttributionTest,FlowApprovalServiceTest,ThreadItemJsonTest,OrchestrationRepositoryTest,SubAgentModelResolutionTest,SchemaCommentsCoverageTest,SystemPromptSecurityRuleTest,CapabilityAliasDictionaryTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*ThreadItemJsonTest" --tests "*ChatReducerTest" --tests "*OrchestrationSectionTest"
.\gradlew.bat test
```

- `clean verify` 全绿（含 IT）；`SchemaCommentsCoverageTest` 过；`AgentLoopLineCountTest` 不退化。
- 真实模型人工烟测：编排「实现某模块 + 跑测试」→ explorer 探查→(worker 实现∥tester 跑测试)→汇总；**含写 flow 运行前整体批准一次**、运行中并行执行（含写）、运行记录按节点归属、无并发串味、写受沙箱边界；编排详情分屏 + 节点配置/增删生效；**对话栏全程可用**；视觉对齐原型。

## 完成报告必须包含

- Task 1–10 逐条 ✅/❌ + 跑过命令与**实际输出**（非预期）。
- approve-once 4 条语义的测试证据（尤其沙箱硬边界、批准后冻结、危险操作被沙箱拒）。
- 并发归属线程安全证据（并行节点不串味）。
- 节点中间过程不外泄、对话保留证据。
- 新表字段中文注释 + `SchemaCommentsCoverageTest` 通过。
- 中文 conventional commit 列表；明确未 push。

## 与 Codex / Claude Code 的区别

- **相同（借鉴）**：多 Agent 顺序/并行/路由编排、并行真实工作、worker 文件 ownership。
- **不同（我们的选择）**：① 薄封装 SAA 官方 flow agent，不自研编排引擎；② 写操作用 **approve-once（运行前整批 + 沙箱硬边界）**，而非 Codex 的"运行中逐工具审批上浮父 session"（后者 = P6-2b）；③ 强制走 BaBiQ Spotlighting + SQLite 并发归属；④ Loop / 自由图编辑器 / team 推迟。

## 下一步

- plan + 本 handoff 由用户确认 → 按 Task 1→10 TDD 实现（**前置 P6-1 完成**）→ 自动化 + 真实烟测闭环 → 补 **P6-2b（运行中逐工具审批 + 并发中断，asNode）** 或进 **P6-3 实时 team 协作**。
