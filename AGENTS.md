# BaBiQ Codex Agent 规则

> 本文件是 BaBiQ 仓库级 Codex 工作规则。
> 除非子目录下存在更近的 `AGENTS.md` 覆盖，否则本文件对整个仓库生效。

## 0. 启动规则

- superpowers-codex bootstrap
- 开始实现前，按任务类型使用对应 superpowers 技能：
  - 写新的多步骤实现计划前，使用 `superpowers:writing-plans`。
  - 做功能或修 bug 前，使用 `superpowers:test-driven-development`。
  - 声称完成、通过、可进入下一阶段前，使用 `superpowers:verification-before-completion`。

## 1. 项目定位

BaBiQ 是一个本地 Codex-like AI Agent 学习项目。

当前架构：

- `desktop/`：Kotlin Compose Desktop 客户端。
- `backend/`：Java 21 + Spring Boot Agent Server。
- 通信协议：WebSocket + JSON-RPC 2.0，端点为 `/ws/agent`。
- Agent 核心：Spring AI Alibaba `ReactAgent`、本地工具、HITL 审批、沙箱、`Thread / Turn / Item` 状态模型。

## 2. 必读上下文入口

做状态判断或实现前，必须先读相关文档和代码，不能只靠记忆。

主入口文档：

1. `docs/ARCHITECTURE.md`
2. `docs/superpowers/plans/2026-05-21-p1-master.md`
3. 当前阶段的 `docs/superpowers/plans/p1-*/plan.md`
4. 当前阶段的 `docs/superpowers/plans/p1-*/codex-handoff.md`

当前检查点：

- P1-3a Agent Loop 内核已实现。
- P1-3a 缺失的自动化验收证据已经补齐。
- 下一阶段是 P1-3b。
- 在写 P1-3b 代码前，必须先写详细 P1-3b plan，并等待用户确认。

如果仓库状态发生变化，不要盲信本检查点；必须重新核对代码、文档、测试和 `git status`。

## 3. 阶段边界

不要把未来阶段内容混进当前阶段。

P1-3a 范围：

- 接入 `ReactAgent`。
- 6 个本地工具：`read_file`、`write_file`、`list_dir`、`grep`、`exec_shell`、`apply_patch`。
- `PathGuard` 和三档沙箱模式。
- `HumanInTheLoopHook`、`MemorySaver`、`approval/respond`、`turn/interrupt`。
- `BaBiQTokenUsageHook` 只做 token 累计，供后续成本反馈使用。

P1-3b 范围：

- 用 `<untrusted-data ...>` 包装工具输出，做 Spotlighting。
- 加入系统提示词安全规则，防 indirect prompt injection。
- 增加 Prompt Injection 烟测。
- 增加 TurnSummary / 成本反馈后端 item。
- 按 master plan 要求补结构化日志和基础 counter。

P1-4 范围：

- Compose Desktop UI。
- P1-3b 没有完成 plan、实现和验证前，不进入 P1-4。

## 4. 实现规则

- 改代码前先读代码。
- 优先沿用仓库现有模式，不随意创造新抽象。
- 修改范围必须贴合当前 issue 或阶段。
- 不做无关重构。
- P1 收口期间，不引入 P2+ 功能。
- 新增解释性注释默认用中文，但只在逻辑不明显时添加。
- Agent Loop 的横切逻辑放在 Hook 或 Interceptor，不塞进主循环。
- 必需验收测试禁止用 `@Disabled` 占位。
- 禁止使用已经废弃的手写审批方案，例如：
  - `CompletableFuture<ApprovalDecision>`
  - `ApprovalChannel`
  - `SynchronousQueue`
  - 虚构的 `HITLHelper` API
  - 虚构的 `compiledGraph.resume` API

## 5. 测试与验收

后端改动默认完整验证命令：

```powershell
cd backend
.\mvnw.cmd clean verify
```

P1-3a 自动化硬验收证据包括：

- 6 个工具都有 `*ToolTest`，且每个至少 3 个行为用例。
- `PathGuardTest` 必须覆盖符号链接或路径穿越逃逸，且不能 skip。
- `SandboxModeRegressionTest` 必须有 5 个通过用例。
- `AgentLoopLineCountTest` 通过。
- `AgentLoopTest` 通过。
- `EndToEndIT` 必须在 `clean verify` 的 failsafe 阶段执行并通过。

桌面端改动需要在 `desktop/` 下运行对应 Gradle 命令；涉及 UI 时要做实际视觉验证。

没有新鲜验证证据前，不要声称完成、通过或可以进入下一阶段。

## 6. Git 规则

- 编辑前后都看 `git status --short --branch`。
- 看到无关 dirty 文件时，默认认为是用户改动，不要回滚。
- 只有用户明确要求时才 commit。
- commit message 使用中文 conventional commit，例如：
  - `test(p1-3a): 补齐工具和沙箱验收测试`
  - `docs(p1-3b): 编写安全与可观测详细计划`
- 不要主动 push 或 tag，除非用户明确要求。

## 7. 汇报规则

汇报状态时：

- 先说真实仓库状态，不说猜测。
- 写清楚跑了什么验证命令，以及结果。
- 剩余缺口必须直接说明。
- 如果下一步是新阶段，要说明详细 plan 是否已经存在。

对于本仓库，只有在 P1-3b plan 已存在、代码已实现、`clean verify` 和阶段专属烟测都通过后，才可以说 P1-3b 完成。
