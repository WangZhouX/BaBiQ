# P6-0 机制 Spike 结论

> 日期：2026-05-31  
> 性质：隔离预研，不是功能实现。实验代码只用于验证，跑通后已删除，不进入生产路径。  
> 依赖版本：BaBiQ 当前锁定 Spring AI `1.1.6`、Spring AI Alibaba `1.1.2.3`。

## 0. 结论摘要

P6 的“子 Agent 内部触发 HITL 审批，然后由父流程恢复”可以实现，但推荐基座必须分层：

- **需要审批/写操作的子 Agent**：使用 `ReactAgent.asNode(...) + StateGraph + 共享 MemorySaver`。本 spike 已验证可中断、可审批恢复、可继续执行工具、可把工具响应补回子 Agent 的模型上下文。
- **只读委派子 Agent**：可以使用 `AgentTool.create(ReactAgent)` 或 `TaskToolsBuilder` 注册成工具，但不建议承载写类 HITL。`AgentTool` 的执行器期望子 Agent 一次调用返回最终 `AssistantMessage`；遇到嵌套中断时更像“工具执行失败”，不适合作为 BaBiQ 的审批恢复主链路。
- **per-turn 构建仍然需要**：BaBiQ 的 `cwd`、`emitter`、`ObservationContext`、沙箱策略都是本轮 turn 的上下文，不能长期绑定在静态子 Agent 上。P6 正式实现应按本轮请求装配子 Agent 或装配一个携带最新 `toolContext` 的运行实例。

## 1. 查证来源

### 1.1 Context7

本轮按要求尝试使用 Context7 查询 Spring AI / Spring AI Alibaba 最新文档，但 Context7 返回月度配额耗尽，因此没有拿到新的在线文档结果。为避免臆测，本 spike 改用以下本地事实源：

- `backend/pom.xml` 锁定版本：Spring AI `1.1.6`、Spring AI Alibaba `1.1.2.3`。
- 本地 Maven jar：
  - `spring-ai-alibaba-agent-framework-1.1.2.3.jar`
  - `spring-ai-alibaba-graph-core-1.1.2.3.jar`
- `javap` 核对 API 和字节码行为。
- BaBiQ 现有 `ReActStrategy` / `AgentLoopResumeSupport` / `EndToEndIT`。
- Codex / Claude Code 源码只做概念对照，不照搬实现：
  - `E:\wzx\codex\codex-rs\core\src\agent\role.rs`
  - `E:\wzx\claude-code\src\utils\model\agent.ts`
  - `E:\wzx\claude-code\src\tools\AgentTool\runAgent.ts`

#### 2026-05-31 复核补充

补充配置 Context7 API Key 后，已重新用 Context7 核对 Spring AI / Spring AI Alibaba 文档。复核结论支持本 spike 的主体判断：

- Spring AI Alibaba 文档确认了 `ReactAgent.asNode(...) + StateGraph` 的工作流组合，并在 Human-in-the-Loop 示例中展示了 `HumanInTheLoopHook.approvalOn(...)`、`MemorySaver`、`SaverConfig.register(saver)` 和 `RunnableConfig.threadId(...)` 的组合用法。
- Spring AI 文档确认 `ToolContext` 是运行时传给工具执行阶段的上下文，不会作为模型输入发送给模型。这支持 BaBiQ 在 P6 中按 turn 注入 `cwd`、`emitter`、`ObservationContext`、沙箱和审批策略，而不是把这些运行态长期绑定到静态子 Agent 实例。
- Context7 当前可检索到的 Spring AI Alibaba 文档版本最高接近 `v1.1.2.2`，而 BaBiQ 仓库锁定的是 Spring AI Alibaba `1.1.2.3`。因此官方文档用于确认机制方向，精确 API 和边界行为仍以本地 `1.1.2.3` jar、`javap` 字节码和本 spike 实测为准。

### 1.2 本地 API 事实

通过 `javap` 确认当前 Spring AI Alibaba `1.1.2.3` 已具备以下机制：

- `ReactAgent.asNode(boolean includeContents, boolean includeReasoning)`
- `ReactAgent.builder().tools(...).hooks(...).toolContext(...).saver(...)`
- `CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build())`
- `RunnableConfig.builder().threadId(...).addHumanFeedback(metadata).build()`
- `HumanInTheLoopHook.builder().approvalOn(...)`
- `AgentTool.create(ReactAgent)` / `AgentTool.getFunctionToolCallback(ReactAgent)`
- `TaskToolsBuilder` / `AgentSpec` / `SubAgentSpec`

关键字节码观察：

- `asNode` 生成的节点 id 固定为子 Agent 的 `name`，父 `StateGraph.addNode(...)` 传入的 id 必须和它一致。
- `asNode` 子图适配器会检查父图和子图 checkpoint saver；如果子图有 saver 但父图没有，会抛出 `Missing CheckpointSaver in parent graph!`。
- 如果父子图使用同一个 saver，子图 runnable config 会自动派生为 `${parentThreadId}_${subGraphId}`，从而把父子状态放在同一套 saver 中管理。
- `InterruptionMetadata.node()` 在实际 HITL 中断时返回内部 hook 节点 `_AGENT_HOOK_HITL`，不是外层父图节点名。P6 不能依赖该字段判断父图节点，需要依靠 tool feedback / 子 Agent 运行上下文做映射。
- `AgentTool` 的执行器调用 `agent.invoke(...)` 后要求最终消息是 `AssistantMessage`，并会把子 Agent 执行异常包装为工具执行异常；这不适合作为写类嵌套 HITL 的主链路。

## 2. 实验代码与实际输出

### 2.1 一次性 spike 代码

曾临时新增：

`backend/src/test/java/com/wzx/babiq/server/spike/p6/P6AsNodeSharedSaverSpikeTest.java`

该测试只包含：

- 一个共享 `MemorySaver`
- 一个带 `HumanInTheLoopHook.approvalOn("write_file", ...)` 的子 `ReactAgent`
- 一个父 `StateGraph`
- 一个假 `ChatModel`，第一轮返回 `write_file` tool call，第二轮检查是否看到了 `ToolResponseMessage`
- 一个假 `write_file` 工具，用 `AtomicBoolean` 记录是否真的执行

跑通后该文件已删除，避免进入长期测试路径。

### 2.2 失败过程中的机制发现

首次使用 `new StateGraph("p6_spike_parent_graph")` 编译失败：

```text
不兼容的类型: java.lang.String无法转换为com.alibaba.cloud.ai.graph.KeyStrategyFactory
```

结论：当前版本没有只传 graph name 的构造器，应使用 `new StateGraph()` 或显式传 `KeyStrategyFactory`。

第二次父图使用 `addNode("writer", childAgent.asNode(...))` 失败：

```text
node id (spike_writer_agent) specified in addNode method doesn't match the id (writer) of the node to be added!
```

结论：`asNode()` 节点 id 固定为子 Agent name，父图节点名必须一致。

第三次断言 `InterruptionMetadata.node() == "spike_writer_agent"` 失败：

```text
expected: "spike_writer_agent"
 but was: "_AGENT_HOOK_HITL"
```

结论：实际中断点 node 是内部 HITL hook 节点，不是外层 Agent 节点名。

### 2.3 成功命令

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=P6AsNodeSharedSaverSpikeTest" test
```

实际关键输出：

```text
Running com.wzx.babiq.server.spike.p6.P6AsNodeSharedSaverSpikeTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

成功证明：

- 第一次父图调用返回 `InterruptionMetadata`。
- 中断前 `write_file` 没有执行。
- 使用同一父 `threadId` + `addHumanFeedback(approvedMetadata)` + 空输入 `Map.of()` 可恢复。
- 恢复后 `write_file` 被执行。
- 恢复后第二次模型调用前，子 Agent 的 prompt 中已包含对应 `ToolResponseMessage`。

## 3. Q1-Q5 回答

### Q1：子 Agent 内层工具能否携带父 toolContext？

可以，但不是自动继承。

`ReactAgent.builder().toolContext(...)` 可为子 Agent 显式安装上下文。BaBiQ 的 `cwd`、`writableRoots`、`sandboxMode`、`emitter`、`ObservationContext` 应由 P6 的子 Agent runtime 在本轮 turn 创建时注入。

结论：

- read-only 静态子 Agent 可以不带复杂上下文。
- 任何需要当前工作区、审批、工具观测、WebSocket item 输出的子 Agent，必须使用本轮最新 `toolContext` 构建或绑定。

### Q2：HITL 嵌套中断能否恢复？

可以，前提是走 `asNode + StateGraph + 共享 saver`。

已验证链路：

1. 子 Agent 内部 `write_file` 触发 `HumanInTheLoopHook`。
2. 父 `CompiledGraph.invokeAndGetOutput(...)` 返回 `InterruptionMetadata`。
3. BaBiQ 现有 `addHumanFeedback(...)` 语义和该链路匹配。
4. 同一父 `threadId` 加审批 feedback 后可用空输入恢复。
5. 恢复后工具执行，工具响应进入子 Agent 后续模型上下文。

`AgentTool` 不建议用于写类嵌套 HITL。它更适合把只读子 Agent 包成一个普通工具；对需要上浮审批中断的场景，`asNode` 更明确、更可控。

### Q3：子 Agent token / 工具观测能否归集并区分主子归属？

机制上可行，但本 spike 未接入 BaBiQ 生产 interceptor 做完整统计，只做了 API 边界确认。

理由：

- 子 Agent builder 支持 `.interceptors(...)`、`.hooks(...)`、`.toolContext(...)`。
- BaBiQ 现有 `ReActStrategy.buildAgent(...)` 已经把观测、沙箱、Spotlighting、token hook 作为横切装配。
- P6-1 可以抽出一个 `ReactAgentRuntimeFactory`，对主 Agent 和子 Agent 复用同一套横切装配，同时在 `toolContext` 或 observation metadata 中加入 `agentName` / `parentTurnId` / `delegationId`。

建议：

- `bq_tool_calls` 后续可增加或复用归属字段记录 `agent_name`、`parent_agent_name`、`delegation_id`。
- UI 的子 Agent 卡片不要从底层 tool call 生拼，应由协议 item 明确描述 delegation lifecycle。

### Q4：子 Agent 是否必须 per-turn 构建？

对 BaBiQ 来说，**需要当前上下文的子 Agent 必须 per-turn 构建**。

原因：

- `ItemEmitter` 指向当前 WebSocket turn，不能跨 turn 静态复用。
- `cwd`、沙箱权限、approval policy、observability context 都是当前 turn 的运行态。
- `asNode + shared saver` 的 saver 可以跨本轮父子图共享，但 agent 实例本身仍应由本轮 runtime 组装。

允许的例外：

- 不触碰文件、不需要 emitter、不走审批、不需要 turn 归属的只读/纯推理子 Agent，可以作为静态 `AgentTool` 或缓存模板存在。
- 即便缓存，也建议缓存 `AgentSpec` / builder 参数，不缓存绑定了旧 `toolContext` 的完整运行实例。

### Q5：子 Agent / 节点配置如何承载任务 + 模型？

推荐两层设计：

1. **持久化 AgentSpec**：保存 name、displayName、description、systemPrompt、toolNames、model/provider override、sandbox/approval 能力声明。
2. **运行时 ReactAgent 实例**：每轮根据 active provider、AgentSpec、当前 toolContext、横切 interceptor/hook 构建。

Spring AI Alibaba 的 `AgentSpec` 已能表达 `name / description / systemPrompt / toolNames / model`，适合作为参考形态；BaBiQ 可以定义自己的 `BabiqAgentSpec`，保留 Java 生态实现自由度，并在运行时映射到 SAA `ReactAgent.builder()`。

模型策略建议沿用 Claude Code 的概念而不是实现：

- `model=inherit`：继承当前会话 active provider/model。
- `model=provider:model`：覆盖为指定 provider/model。
- `model=fast/read-only`：后续可映射到低成本 explorer 模型。

## 4. 机制选型矩阵

| 机制 | 嵌套 HITL | 横切挂载 | 复杂度 | 建议用途 |
|---|---|---|---|---|
| `AgentTool.create(ReactAgent)` | 不建议承载写类 HITL | 子 Agent builder 可挂载 | 低 | 只读 explorer、纯分析任务 |
| `TaskToolsBuilder + AgentSpec` | 未作为写类主链路验证 | 子 Agent builder 可挂载 | 中 | 多子 Agent 目录/任务注册，偏只读委派 |
| `SubAgentInterceptor + SubAgentSpec` | 未作为主链路验证 | 拦截器式 | 中 | 备选扩展点，不作为 P6-1 起步基座 |
| `asNode + StateGraph + shared saver` | 已验证可中断和恢复 | 子 Agent builder 可挂载，父图需共享 saver | 较高 | 写类子 Agent、需要审批恢复的编排节点 |

最终建议：

- P6-1 起步实现“只读 Explorer 子 Agent”时，可以用 `AgentTool`/`TaskToolsBuilder` 思路。
- 只要要支持写文件、执行命令、修改仓库、触发审批，就必须走 `asNode + StateGraph + shared saver`。
- 不要把 `AgentTool` 当成所有子 Agent 的统一承载层，否则后续 HITL 会很难和 BaBiQ `approval/respond` 对齐。

## 5. P6-1 可复用封装建议

### 5.1 AgentSpec 草案

```java
record BabiqAgentSpec(
        String name,
        String displayName,
        String description,
        String systemPrompt,
        List<String> toolNames,
        String modelPolicy,
        DelegationMode delegationMode
) {}
```

`delegationMode` 建议：

- `READ_ONLY_TOOL`：注册成工具，适用于 explorer。
- `HITL_GRAPH_NODE`：作为 `asNode` 子图节点，适用于写类任务。

### 5.2 运行时工厂草案

```java
ReactAgent buildSubAgent(BabiqAgentSpec spec, TurnRuntimeContext runtime) {
    return ReactAgent.builder()
            .name(spec.name())
            .description(spec.description())
            .systemPrompt(spec.systemPrompt())
            .model(resolveModel(spec.modelPolicy(), runtime.activeProvider()))
            .tools(resolveTools(spec.toolNames()))
            .toolContext(runtime.toolContext())
            .hooks(buildHooks(runtime.approvalPolicy()))
            .interceptors(buildInterceptors(runtime))
            .saver(runtime.sharedSaver())
            .build();
}
```

注意：这里是 P6-1 的设计草案，不是本 spike 已提交的生产代码。

### 5.3 协议 item 草案

```json
{
  "type": "agentDelegation",
  "delegationId": "dlg_xxx",
  "parentAgent": "main",
  "childAgent": "explorer",
  "status": "running|waiting_approval|completed|failed",
  "mode": "READ_ONLY_TOOL|HITL_GRAPH_NODE",
  "summary": "正在检查仓库结构",
  "toolCallCount": 3,
  "tokenEstimate": 1200
}
```

## 6. 风险与注意事项

- `InterruptionMetadata.node()` 实测为 `_AGENT_HOOK_HITL`，不要把它当成子 Agent 名。
- `asNode` 节点 id 必须等于子 Agent name；AgentSpec name 需要满足图节点命名和 function/tool naming 的约束，建议继续使用 ASCII。
- 父图和子 Agent 必须共享同一个 saver 实例；否则嵌套恢复不可用或直接报错。
- 恢复参数沿用 BaBiQ 当前修复后的做法：`addHumanFeedback(metadata)`，不要随意加 `resume()` 占位，避免历史上 DeepSeek/HITL 恢复类 bug 重现。
- 本 spike 没有把 BaBiQ 全套 interceptor 接进实验图，只验证机制门槛。P6-1 需要 TDD 覆盖 sandbox、observation、Spotlighting、token 归属。

## 7. 验收状态

- Q1-Q5 已有明确结论。
- Q2 有可复现证据：`asNode + shared MemorySaver + addHumanFeedback + empty input` 已跑通。
- 实验代码已删除，未进入生产路径。
- 已在删除 spike 代码后执行 `cd E:\BaBiQ\backend; .\mvnw.cmd clean verify`，实际结果：

```text
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
