# P1-3B 任务交接给 Codex

> 当前文件是执行入口摘要。真正实施前必须先读完整计划:
> `E:\BaBiQ\docs\superpowers\plans\p1-3b-security-observability\plan.md`

## 当前状态

- P1-3A Agent Loop 内核已实现。
- P1-3A 验收测试已补齐并通过过 `backend .\mvnw.cmd clean verify`。
- P1-3B 安全 + 可观测已实现。
- 本阶段实现前已刷新官方能力查证并记录到 `official-capability-check.md`。
- 已完成两批功能提交：
  - `4009f36 feat(p1-3b): 接入工具结果安全标注`
  - `f9207cd feat(p1-3b): 增加 turn 可观测摘要`
- 当前剩余动作只有最终文档同步、最终 `clean verify`、收尾提交。

## 本阶段目标

已实现 **安全 + 可观测**:

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

## 后续规则

1. 不 push。
2. 不直接进入 P1-4 UI 实现；P1-4 需要先写或确认详细 plan。
3. 后续所有 Agent / LLM / Tool / Hook / Interceptor / Memory / HITL / observability / sandbox / protocol 相关实现,仍必须先查 Spring AI Alibaba、Spring AI 官方仓库/文档和本地锁定 jar。
4. 每个阶段完成后主动更新根目录 `AGENTS.md` 当前检查点和下一阶段。

## 最终验收命令

```powershell
cd backend
mvn clean verify
cd ..
git status --short --branch
```

## 完成报告必须包含

- P1-3B Done Criteria 逐条状态。
- 运行过的测试命令和结果。
- 是否仍有已知缺口。
- commit 列表。
- 明确说明未 push。
