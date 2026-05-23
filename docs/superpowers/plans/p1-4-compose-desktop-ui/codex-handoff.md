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
- 下一步可以在用户确认后进入 P1-4 Compose Desktop UI 实现。

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

- 把后端已经发出的协议事件展示到 Compose Desktop UI。
- 完成聊天、审批、Provider/模型切换、Provider 只读设置、Turn 成本反馈。
- 成本只来自后端 `turnSummary`；首页/idle 状态和 `ComposerContextBar` 不显示成本 chip。
- Sidebar 的搜索、插件、自动化和首页快速操作卡在 P1-4 只允许禁用占位或隐藏。
- 使用最新稳定版：
  - Kotlin `2.3.21`
  - Compose Multiplatform `1.11.0`
  - Ktor Client `3.5.0`
  - kotlinx.serialization `1.11.0`
  - kotlinx.coroutines `1.11.0`
- 保持 P1 范围，只做端到端最小可用 UI。

## 后续规则

- 实现前重新核对官方版本，禁止使用 RC/Beta/EAP。
- 实现时按 `plan.md` 的 Task 顺序推进，并使用 TDD。
- 涉及 Compose、Ktor、kotlinx.serialization、kotlinx.coroutines 时，优先使用官方能力，不重复造轮子。
- 完成 P1-4 后必须主动更新根目录 `AGENTS.md`、本交接文档和 master plan。
- 完成 P1-4 后也必须同步更新 `CLAUDE.md`，不能只更新 `AGENTS.md`。
- 用户已要求主动 commit、中文 commit、不要 push。
