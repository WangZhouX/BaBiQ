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

- `desktop/`: Kotlin Compose Desktop 客户端。
- `backend/`: Java 21 + Spring Boot Agent Server。
- 通信协议: WebSocket + JSON-RPC 2.0，端点为 `/ws/agent`。
- Agent 核心: Spring AI Alibaba `ReactAgent`、本地工具、HITL 审批、沙箱、`Thread / Turn / Item` 状态模型。
- 技术主线: 后端框架以 Java 生态为主，优先使用 Spring AI Alibaba、Spring AI 及其 agent-framework 的最新稳定能力；如需升级依赖，必须先核对官方发布、兼容性和现有代码约束。

## 2. 必读上下文入口

做状态判断或实现前，必须先读相关文档和代码，不能只靠记忆。

主入口文档：

1. `docs/ARCHITECTURE.md`
2. `docs/superpowers/plans/2026-05-21-p1-master.md`
3. 当前阶段的 `docs/superpowers/plans/p1-*/plan.md`
4. 当前阶段的 `docs/superpowers/plans/p1-*/codex-handoff.md`

当前检查点：

- P1-3A Agent Loop 内核已实现。
- P1-3A 验收补齐已完成。
- P1-3B 安全 + 可观测已实现，并已通过后端全量测试。
- P1-4 Compose Desktop UI 已完成原型、交互流程图和正式详细实现计划：
  - `docs/superpowers/plans/p1-4-compose-desktop-ui/plan.md`
  - `docs/superpowers/plans/p1-4-compose-desktop-ui/codex-handoff.md`
  - `docs/superpowers/plans/p1-4-compose-desktop-ui/prototype/`
- 下一步是在用户确认后，按 P1-4 详细计划进入 Compose Desktop UI 实现。

如果仓库状态发生变化，不要盲信本检查点；必须重新核对代码、文档、测试和 `git status`。

## 3. 阶段边界

不要把未来阶段内容混进当前阶段。

P1-3B 已完成范围：

- 工具输出 `<untrusted-data>` spotlighting。
- system prompt 安全规则。
- prompt injection smoke test。
- `turnSummary` 协议 item、成本估算、结构化 turn JSON 日志。
- P1 内存级 counters: turn、tokens、tool calls、approval decisions。
- 不引入 Actuator、Prometheus、OpenTelemetry、Langfuse UI、Lakera Guard、Dual LLM 或 OWASP 大数据集回归。

P1-4 范围：

- Compose Desktop UI。
- 消费后端已经发出的 `turnSummary`、`approval/request`、`item/added`、`turn/completed` 等协议事件。
- 完成 ChatScreen、ApprovalDialog、ProviderSelector、Provider 只读设置、成本反馈条和协议模型映射。
- 采用用户审核通过的 V2 原型：上下文条和模型切换靠近输入框，右侧运行详情默认收起。
- 不恢复 V1 原型，不把“文件上下文”做成独立右侧入口。
- 不做 Provider 编辑、API Key 管理、KeyStore、多工作区文件 pinning 或 P2+ 可观测 UI。

## 4. 实现规则

- 改代码前先读代码。
- 实现任何 Agent、LLM、工具、Hook、Interceptor、Memory、HITL、观测、沙箱或协议相关能力前，必须先查看对应的官方代码库或官方文档，优先确认 Spring AI Alibaba、Spring AI、JDK/Java 标准库或成熟 Java 生态中是否已有实现。
- 能使用官方组件、官方扩展点或成熟 Java 库时，优先做薄封装和集成，不重复造轮子；只有官方能力缺失、与 BaBiQ 协议不匹配或引入成本过高时，才允许自实现，并在计划或代码注释中说明原因。
- 查证顺序优先级：Spring AI Alibaba 官方仓库/文档、Spring AI 官方仓库/文档、Java/JDK 官方文档、成熟 Java 生态库；涉及版本差异时，以当前仓库锁定版本和官方最新稳定说明共同判断。
- Compose/Kotlin/Ktor/kotlinx 相关实现也必须先查官方文档或官方仓库。
- 版本使用最新稳定版，禁止为了“看起来更新”使用 RC、Beta、EAP。
- 优先沿用仓库现有模式，不随意创造新抽象。
- 修改范围必须贴合当前 issue 或阶段。
- 不做无关重构。
- P1 收口期间，不引入 P2+ 功能。
- 新增解释性代码注释默认用中文，但只在逻辑不明显时添加。
- 必需验收测试禁止用 `@Disabled` 占位。

## 5. 测试与验收

后端改动默认完整验证命令：

```powershell
cd backend
.\mvnw.cmd clean verify
```

桌面端改动默认验证命令：

```powershell
cd desktop
.\gradlew.bat test
.\gradlew.bat run
```

P1-4 实现验收必须包含：

- `desktop` 单元测试通过。
- 后端 `clean verify` 通过。
- 桌面端真实启动。
- UI 能完成“分析 E:\BaBiQ 项目结构并写一个总结”的真实业务场景。
- 审批弹窗、Provider/模型切换、TurnSummary 成本反馈、断线提示均可见可用。
- 视觉对齐 V2 原型截图，不能出现文字溢出、控件重叠或 V1 旧设计回流。

没有新鲜验证证据前，不要声称完成、通过或可进入下一阶段。

## 6. 计划完成后的文档同步

每完成一个阶段计划或重要子计划后，Codex 必须主动检查并更新本文件和 `CLAUDE.md`。

必须更新的内容：

- `当前检查点`: 写清楚刚完成了什么、哪些验收已通过。
- `下一步阶段`: 写清楚下一步应进入哪个阶段，是否需要先写详细 plan。
- `阶段边界`: 如果新阶段范围发生变化，必须同步调整，避免后续 Codex 越界实现。
- `测试与验收`: 如果新增了硬验收命令、测试类或烟测脚本，必须补进来。

要求：

- 更新 `AGENTS.md` / `CLAUDE.md` 应作为完成计划后的收尾动作主动提出或直接执行。
- 如果用户要求 commit，`AGENTS.md` / `CLAUDE.md` 更新应和该计划的收尾提交一起提交，或单独用 `docs(...)` 提交。
- 不要让 `AGENTS.md` 或 `CLAUDE.md` 停留在过期阶段状态；下一轮 Codex / Claude 会依赖它们判断当前项目进度。

## 7. Git 规则

- 编辑前后都看 `git status --short --branch`。
- 看到无关 dirty 文件时，默认认为是用户改动，不要回滚。
- 用户已要求本仓库收尾时主动 commit：完成计划、文档或功能验收后，使用中文 conventional commit 主动提交。
- commit message 使用中文 conventional commit，例如：
  - `test(p1-3a): 补齐工具和沙箱验收测试`
  - `docs(p1-4): 编写桌面端详细实现计划`
- 不要主动 push 或 tag，除非用户明确要求。

## 8. 汇报规则

汇报状态时：

- 先说真实仓库状态，不说猜测。
- 写清楚跑了什么验证命令，以及结果。
- 剩余缺口必须直接说明。
- 如果下一步是新阶段，要说明详细 plan 是否已经存在。

对于本仓库，只有在当前阶段 plan 已存在、代码已实现、`clean verify` 和阶段专属验收都通过后，才可以说该阶段完成。
