# P1-4 任务交接给 Codex

> 当前文件是 P1-4 Compose Desktop UI 阶段的执行入口摘要。真正实施前必须先读完整计划:
> `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\plan.md`

## 当前状态

- P1-3B 安全 + 可观测已完成并通过后端 `mvn clean verify`。
- P1-4 计划目录已创建。
- P1-4 V2 高保真原型已完成并经用户初审通过,暂时没有问题。
- P1-4 交互流程图已完成。
- P1-4 详细实施计划尚未完成。
- 不允许直接开始桌面端实现;必须先补全并确认详细计划。

## 必读入口

1. `E:\BaBiQ\AGENTS.md`
2. `E:\BaBiQ\docs\ARCHITECTURE.md`
3. `E:\BaBiQ\docs\superpowers\plans\2026-05-21-p1-master.md`
4. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\README.md`
5. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\figma.md`
6. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\README.md`
7. `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\plan.md`

## 原型材料入口

- Figma 文件: <https://www.figma.com/design/frTp55zgrKf4NAWxn6LdI7>
- V2 截图目录: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens`
- 交互流程目录: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows`
- 当前只保留 V2 方案,不要再参考旧版 V1。

## P1-4 目标摘要

- 把后端已发出的协议事件展示到 Compose Desktop UI。
- 完成聊天、审批、Provider 切换、Provider 只读设置、Turn 成本反馈。
- 保持 P1 范围,只做端到端最小可用 UI。

## 后续规则

- 先读取并吸收 `prototype/` 下的 V2 原型截图和 flows。
- 原型已初审通过;下一步是补全详细计划。
- 计划确认后,再使用 TDD/分步实现。
- 涉及 Compose、Ktor、kotlinx.serialization 时,实现前必须查官方文档或官方示例。
- 完成阶段后必须主动更新根目录 `AGENTS.md`。
