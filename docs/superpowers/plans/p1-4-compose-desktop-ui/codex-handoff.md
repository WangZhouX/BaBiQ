# P1-4 任务交接给 Codex

> 当前文件是 P1-4 Compose Desktop UI 阶段的执行入口摘要。真正实施前必须先读完整计划：
> `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\plan.md`

## 当前状态

- P1-3B 安全 + 可观测已完成，并已通过后端 `mvn clean verify`。
- P1-4 计划目录已创建。
- P1-4 V2 高保真原型已完成并经用户审核，暂时没有问题。
- P1-4 交互流程图已完成。
- P1-4 正式详细实现计划已完成：
  - `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\plan.md`
- P1-4 Compose Desktop UI 已完成代码实现：
  - 桌面端入口 `BaBiQDesktopApp()` 已替换 P1-0 skeleton。
  - 已实现协议模型、JSON-RPC client、Ktor WebSocket transport、状态 reducer/controller。
  - 已实现 V2 shell、聊天主区、输入框上下文条、Provider/模型下拉、审批弹窗、TurnSummary、运行详情和只读设置页。
  - 已实现连接失败后的 1s-10s 自动重连，断线期间保留草稿并禁用发送/审批。
  - 已补充核心 Kotlin 代码中文注释，便于学习协议、协程、StateFlow、reducer 分层。
- P1-4 已通过自动化/启动验证：
  - `cd E:\BaBiQ\desktop; .\gradlew.bat test`
  - `cd E:\BaBiQ\backend; .\mvnw.cmd clean verify`
  - `cd E:\BaBiQ\desktop; .\gradlew.bat run --no-daemon` 已进入 `:run` 并在受控烟测中保持运行。
- 真实 Provider/API Key 未在本交接中固化；如果要验收“分析 E:\BaBiQ 项目结构并写一个总结”，需要在可用模型环境中人工复验。
- 下一步建议：先做 P1 总体验收；进入 P2 前先编写并确认新的详细 plan。

## 已知限制

- 审批弹窗的“始终允许”按钮当前保持禁用。后端 `ApprovalRespondHandler` 与 `ApprovalDecision` 只支持 `approve` / `deny` / `edit`，尚未定义 `always` 决策语义；后续必须先扩展后端协议和指标语义，再启用该按钮。

## 必读入口

1. `E:\BaBiQ\AGENTS.md`
2. `E:\BaBiQ\docs\ARCHITECTURE.md`
3. `E:\BaBiQ\docs\superpowers\plans\2026-05-21-p1-master.md`
4. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\plan.md`
5. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\README.md`
6. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\figma.md`
7. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\README.md`

## 原型材料入口

- Figma 文件: <https://www.figma.com/design/frTp55zgrKf4NAWxn6LdI7>
- V2 截图目录: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens`
- 交互流程目录: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows`
- 当前只保留 V2 方案，不要再参考旧版 V1。

## P1-4 实现目标摘要

- 已把后端已经发出的协议事件展示到 Compose Desktop UI。
- 已完成聊天、审批、Provider/模型切换、Provider 只读设置、Turn 成本反馈。
- 成本只来自后端 `turnSummary`；首页/idle 状态和 `ComposerContextBar` 不显示成本 chip。
- Sidebar 的搜索、插件、自动化在 P1-4 只作为禁用占位；首页快速操作卡未实现，避免误导 P1 能力。
- 桌面端已加入 `slf4j-simple` runtime binding，避免 Ktor 启动时出现 `No SLF4J providers were found` 警告。
- 使用最新稳定版：
  - Kotlin `2.3.21`
  - Compose Multiplatform `1.11.0`
  - Ktor Client `3.5.0`
  - kotlinx.serialization `1.11.0`
  - kotlinx.coroutines `1.11.0`
- 保持 P1 范围，只做端到端最小可用 UI。

## 后续规则

- 进入 P2 前重新核对官方版本，禁止使用 RC/Beta/EAP。
- 后续新阶段继续按详细 plan 的 Task 顺序推进，并使用 TDD。
- 涉及 Compose、Ktor、kotlinx.serialization、kotlinx.coroutines 时，优先使用官方能力，不重复造轮子。
- 完成每个阶段后必须主动更新根目录 `AGENTS.md`、`CLAUDE.md`、对应 handoff 和 master plan。
- 用户已要求主动 commit、中文 commit、不要 push。
