# P1-4 Figma 高保真原型

## Figma 文件

- 文件名: `BaBiQ P1-4 Compose Desktop UI Prototype`
- 链接: <https://www.figma.com/design/frTp55zgrKf4NAWxn6LdI7>
- 创建日期: 2026-05-23

## 已创建画板

- `01 V2 Home / Composer Context`: 首页输入框附近展示工作区、模式、分支、worktree、权限和模型。首页/idle 状态不显示成本。
- `02 V2 Chat / Runtime`: 运行态聊天界面,右侧运行详情默认收起。
- `03 V2 Approval / Context Aware`: 审批弹窗展示当前工作区、权限和工具。
- `04 V2 Model Picker Near Composer`: 模型切换入口靠近输入框,切换从下一条消息生效。
- `05 V2 Settings / Workspace And Providers`: 设置页展示工作区上下文和 Provider 只读信息。

## 已导出本地截图

- `screens/v2-01-home-context-bar.png`
- `screens/v2-02-chat-runtime.png`
- `screens/v2-03-approval-context-aware.png`
- `screens/v2-04-model-picker-near-composer.png`
- `screens/v2-05-settings-workspace-providers.png`

## 备注

- 当前只保留 V2 方案。
- V2 按用户反馈调整:不再把“文件上下文”做成单独左侧入口,改为类似 Codex / Claude Code 的输入框上下文条。
- P1-4 详细计划应采用 V2 方案。
- 成本展示以 `turnSummary` 为唯一来源；如果截图中仍出现上下文条成本 chip,实现时按本备注和 `plan.md` 处理为废弃视觉,不要实现。
- Sidebar 的搜索、插件、自动化入口和首页快速操作卡在 P1-4 只允许禁用占位或隐藏,不接入真实外部服务。
