# P1-3B 任务交接给 Codex

> 当前文件是执行入口摘要。真正实施前必须先读完整计划:
> `E:\BaBiQ\docs\superpowers\plans\p1-3b-security-observability\plan.md`

## 当前状态

- P1-3A Agent Loop 内核已实现。
- P1-3A 验收测试已补齐并通过过 `backend .\mvnw.cmd clean verify`。
- P1-3B 目前只写了计划,**尚未开始实现**。
- 未经用户确认,不得改 P1-3B 代码。

## 本阶段目标

实现 **安全 + 可观测**:

- Spotlighting: 工具输出进入模型历史前统一包 `<untrusted-data source="..." path="...">`。
- System prompt 安全规则: 明确忽略 `untrusted-data` 内所有指令。
- Prompt Injection 冒烟测试: 恶意 README 不能诱导 Agent 泄露 `/etc/passwd`。
- TurnSummaryItem: 每个终态 turn 发 `tokensIn / tokensOut / costUsd / durationMs / toolCount`。
- 结构化 JSON turn 日志。
- P1 内存级基础 counters: turn duration、llm tokens、tool calls、approval decisions。

## 必读代码挂点

- `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`
- `backend/src/main/java/com/wzx/babiq/server/hook/BaBiQTokenUsageHook.java`
- `backend/src/main/java/com/wzx/babiq/server/interceptor/BaBiQSandboxInterceptor.java`
- `backend/src/main/java/com/wzx/babiq/server/conversation/items/ThreadItem.java`
- `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`

## 执行规则

1. 先读完整 `plan.md`。
2. 先完成 `plan.md` 中的 Java / Spring 生态优先硬门,查 Spring AI Alibaba、Spring AI 官方仓库/文档和本地 jar。
3. 新增或修改 `official-capability-check.md`,记录哪些能力已由官方或 Java 生态提供,哪些必须由 BaBiQ 薄封装。
4. 能复用 Spring AI Alibaba / Spring AI / Java 生态时,优先复用;不能复用时,在计划执行记录或代码注释中说明原因。
5. 使用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 执行。
6. 每个 task 先写失败测试,再实现。
7. 每个 task 完成后用中文 conventional commit。
8. 不 push。
9. 不进入 P1-4 UI。
10. 完成 P1-3B 后主动更新根目录 `AGENTS.md` 当前检查点和下一阶段。

## 最终验收命令

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd clean verify
cd ..
git status --short --branch
```

## 完成报告必须包含

- P1-3B Done Criteria 逐条状态。
- 运行过的测试命令和结果。
- 是否仍有已知缺口。
- commit 列表。
- 明确说明未 push。
