# P1-3B 官方能力查证记录

> 本文件是 P1-3B 执行前的生态复用检查表。实现阶段已经按 `plan.md`
> 刷新官方文档、官方仓库和本地锁定版本 jar；后续新增 Agent 能力时必须继续追加查证结果。

## 初步查证来源

- Spring AI Alibaba 官方仓库: https://github.com/alibaba/spring-ai-alibaba
- Spring AI Alibaba Hooks / Interceptors 文档: https://java2ai.com/en/docs/frameworks/agent-framework/tutorials/hooks/
- Spring AI Alibaba HITL 文档: https://java2ai.com/en/docs/frameworks/agent-framework/advanced/human-in-the-loop/
- Spring AI Tool Calling 文档: https://docs.spring.io/spring-ai/reference/api/tools.html
- Spring AI Alibaba Releases: https://github.com/alibaba/spring-ai-alibaba/releases
- 本地 jar / javap:
  - `spring-ai-alibaba-agent-framework-1.1.2.3.jar`
  - `spring-ai-alibaba-graph-core-1.1.2.3.jar`
  - `spring-ai-model-1.1.6.jar`

## 2026-05-23 实现前刷新

执行环境: `backend`, Java 21, Maven 测试基线已通过。

### 官方文档结论

- Spring AI Alibaba Hooks / Interceptors 文档确认 `ReactAgent.builder()` 原生支持
  `.hooks(...)` 与 `.interceptors(...)`,内置能力覆盖 `HumanInTheLoopHook`、
  `ModelCallLimitHook`、`ToolRetryInterceptor`、`ContextEditingInterceptor`、
  `PIIDetectionHook`,并明确自定义扩展点包括 `MessagesModelHook`、
  `ModelHook`、`ModelInterceptor`、`ToolInterceptor`。
- Spring AI Tool Calling 文档确认工具调用由 `ToolCallback` / `ToolContext`
  与工具调用生命周期承载,并提供 `spring.ai.tool` observation 支持。
- Spring AI Alibaba Release 记录确认 1.1.2 系列已升级 Spring AI 到 1.1.2,
  当前项目锁定 `spring-ai-alibaba-agent-framework:1.1.2.3` 与
  `spring-ai:1.1.6`,继续以本地锁定 jar API 为准。

### 本地 jar / javap 结论

命令摘要:

```powershell
jar tf spring-ai-alibaba-agent-framework-1.1.2.3.jar |
  Select-String 'ContextEditing|MessagesModelHook|ModelInterceptor|ToolInterceptor|ToolCallResponse|ModelCallLimit|HumanInTheLoop|PII'

javap -classpath spring-ai-alibaba-agent-framework-1.1.2.3.jar `
  com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor `
  com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest `
  com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse `
  com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler

jar tf spring-ai-model-1.1.6.jar |
  Select-String 'Observation|ToolCallback|ToolContext|Usage'
```

确认存在:

- `com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor`
- `ToolCallRequest#getToolName()/#getArguments()/#getContext()`
- `ToolCallResponse#getResult()/#getStatus()/#getMetadata()`
- `ToolCallResponse(String toolName, String toolCallId, String result, String status, Map metadata)`
- `contextediting.ContextEditingInterceptor`
- `hook.messages.MessagesModelHook`
- `hook.modelcalllimit.ModelCallLimitHook`
- `hook.hip.HumanInTheLoopHook`
- `hook.pii.PIIDetectionHook`
- Spring AI `ToolCallback`, `ToolContext`, `Usage`
- Spring AI `tool.observation.*`, `chat.observation.*`, `model.observation.*`

## 初步结论

| 能力 | 官方/生态候选 | 是否复用 | 不复用原因或薄封装说明 |
|---|---|---:|---|
| ReAct agent 装配 | Spring AI Alibaba `ReactAgent.builder()` | 是 | P1-3A 已使用, P1-3B 继续在 builder 上挂 system prompt、hooks、interceptors。 |
| HITL 审批 | Spring AI Alibaba `HumanInTheLoopHook` + checkpoint/resume | 是 | P1-3A 已使用, P1-3B 不回退手写审批。 |
| 模型调用限制 | Spring AI Alibaba `ModelCallLimitHook` | 是 | P1-3A 已使用, P1-3B 不自写 limit。 |
| 工具输出截断 | Spring AI Alibaba `LargeResultEvictionInterceptor` | 是 | P1-3A 已使用, P1-3B 不自写 truncation。 |
| 工具响应改写 | Spring AI Alibaba `ToolInterceptor` | 是 | 用官方扩展点做薄封装；是否存在更直接的内置 spotlighting 组件,实现前继续查。 |
| untrusted-data 工具输出包装 | `ToolInterceptor` / `ContextEditingInterceptor` / `MessagesModelHook` | 是,薄封装 | 已确认有官方拦截扩展点,但本地锁定 jar 未发现等价 Spotlighting/PromptInjection 专用组件；用 `ToolInterceptor` 只改写工具结果。 |
| system prompt 安全规则注入 | `ReactAgent.builder().systemPrompt(...)` | 是 | 本地 `javap` 已确认 builder 有 `systemPrompt(String)`。 |
| token/usage 采集 | Spring AI `Usage` metadata + Spring AI Alibaba Hook | 是 | P1-3A 已有 `BaBiQTokenUsageHook`; P1-3B 只做 turn 级薄汇总。 |
| turn metrics | Spring AI observation / Micrometer / 内存 fallback | 是,薄封装 | Spring AI 已有 observation 类；P1 不引入 Actuator/Prometheus,先实现内存 counters,命名与后续 Micrometer 指标保持一致。 |
| 结构化日志 | SLF4J / Logback + Jackson | 是 | P1 用 Jackson 输出单行 JSON；不引入额外日志栈。 |
| 成本计算 | Spring AI `Usage` + 配置化价格表 | 是,薄封装 | 已确认 usage 元数据,未发现官方成本价格表；用配置化 `CostCalculator`,不硬编码供应商价格。 |

## 实现前必须补充

- [x] 查 `spring-ai-alibaba-agent-framework-1.1.2.3.jar` 是否存在 Prompt Injection / Guardrail / Spotlighting / ContextEditing 等可直接复用组件。
- [x] 查 Spring AI / Spring AI Alibaba 最新官方文档是否已有工具结果安全标注模式。
- [x] 查 Micrometer / Observation 是否可以在不引入 P2 Actuator 范围的情况下承载 P1 counters。
- [x] 若发现官方等价能力,先更新 `plan.md`,再实现。
