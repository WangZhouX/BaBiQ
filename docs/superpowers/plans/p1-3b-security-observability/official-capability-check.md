# P1-3B 官方能力查证记录

> 本文件是 P1-3B 执行前的生态复用检查表。当前内容是计划阶段的初步查证；真正实现前必须按 `plan.md` 再刷新一次官方文档、官方仓库和本地锁定版本 jar。

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

## 初步结论

| 能力 | 官方/生态候选 | 是否复用 | 不复用原因或薄封装说明 |
|---|---|---:|---|
| ReAct agent 装配 | Spring AI Alibaba `ReactAgent.builder()` | 是 | P1-3A 已使用, P1-3B 继续在 builder 上挂 system prompt、hooks、interceptors。 |
| HITL 审批 | Spring AI Alibaba `HumanInTheLoopHook` + checkpoint/resume | 是 | P1-3A 已使用, P1-3B 不回退手写审批。 |
| 模型调用限制 | Spring AI Alibaba `ModelCallLimitHook` | 是 | P1-3A 已使用, P1-3B 不自写 limit。 |
| 工具输出截断 | Spring AI Alibaba `LargeResultEvictionInterceptor` | 是 | P1-3A 已使用, P1-3B 不自写 truncation。 |
| 工具响应改写 | Spring AI Alibaba `ToolInterceptor` | 是 | 用官方扩展点做薄封装；是否存在更直接的内置 spotlighting 组件,实现前继续查。 |
| untrusted-data 工具输出包装 | `ToolInterceptor` / `ContextEditingInterceptor` / `MessagesModelHook` | 待刷新 | 初步未确认有等价内置 Spotlighting 组件；实现前必须查 jar 和官方源码,若存在直接复用。 |
| system prompt 安全规则注入 | `ReactAgent.builder().systemPrompt(...)` | 是 | 本地 `javap` 已确认 builder 有 `systemPrompt(String)`。 |
| token/usage 采集 | Spring AI `Usage` metadata + Spring AI Alibaba Hook | 是 | P1-3A 已有 `BaBiQTokenUsageHook`; P1-3B 只做 turn 级薄汇总。 |
| turn metrics | Micrometer / Spring AI observation / Spring AI Alibaba `observationRegistry(...)` | 待刷新 | P1 不接 Actuator/Prometheus；实现前先评估是否可用 `MeterRegistry` 薄封装,否则保留内存 fallback。 |
| 结构化日志 | SLF4J / Logback + Jackson | 是 | P1 用 Jackson 输出单行 JSON；不引入额外日志栈。 |
| 成本计算 | Spring AI usage/observation | 待刷新 | 初步只确认 usage,未确认官方成本价格表；实现前若无官方成本扩展点,使用配置化 `CostCalculator`。 |

## 实现前必须补充

- [ ] 查 `spring-ai-alibaba-agent-framework-1.1.2.3.jar` 是否存在 Prompt Injection / Guardrail / Spotlighting / ContextEditing 等可直接复用组件。
- [ ] 查 Spring AI / Spring AI Alibaba 最新官方文档是否已有工具结果安全标注模式。
- [ ] 查 Micrometer / Observation 是否可以在不引入 P2 Actuator 范围的情况下承载 P1 counters。
- [ ] 若发现官方等价能力,先更新 `plan.md`,再实现。
