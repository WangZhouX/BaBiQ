# P1-4 任务交接给 Codex

> 当前文件是 P1-4 Compose Desktop UI 阶段的执行入口摘要。真正实施前必须先读完整计划:
> `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\plan.md`

## 当前状态

- P1-3B 安全 + 可观测已完成并通过后端 `mvn clean verify`。
- P1-4 计划目录已创建。
- P1-4 详细计划尚未完成。
- P1-4 原型材料尚未放入 `prototype/`。
- 不允许直接开始桌面端实现;必须先补全并确认详细计划。

## 必读入口

1. `E:\BaBiQ\AGENTS.md`
2. `E:\BaBiQ\docs\ARCHITECTURE.md`
3. `E:\BaBiQ\docs\superpowers\plans\2026-05-21-p1-master.md`
4. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\README.md`
5. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\plan.md`

## P1-4 目标摘要

- 把后端已发出的协议事件展示到 Compose Desktop UI。
- 完成聊天、审批、Provider 切换、Provider 只读设置、Turn 成本反馈。
- 保持 P1 范围,只做端到端最小可用 UI。

## 后续规则

- 先等原型材料进入 `prototype/`。
- 原型确认后,再补全详细计划。
- 计划确认后,再使用 TDD/分步实现。
- 涉及 Compose、Ktor、kotlinx.serialization 时,实现前必须查官方文档或官方示例。
- 完成阶段后必须主动更新根目录 `AGENTS.md`。
