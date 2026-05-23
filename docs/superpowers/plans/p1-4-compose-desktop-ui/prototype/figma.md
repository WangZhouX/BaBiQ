# P1-4 Figma 高保真原型

## Figma 文件

- 文件名: `BaBiQ P1-4 Compose Desktop UI Prototype`
- 链接: <https://www.figma.com/design/frTp55zgrKf4NAWxn6LdI7>
- 创建日期: 2026-05-23

## 已创建画板

### V2: 输入框上下文条方案

- `01 V2 Home / Composer Context`: 首页输入框附近展示工作区、模式、分支、worktree、成本和模型。
- `02 V2 Chat / Runtime`: 运行态聊天界面,右侧运行详情默认收起。
- `03 V2 Approval / Context Aware`: 审批弹窗展示当前工作区、权限和工具。
- `04 V2 Model Picker Near Composer`: 模型切换入口靠近输入框,切换从下一条消息生效。
- `05 V2 Settings / Workspace And Providers`: 设置页展示工作区上下文和 Provider 只读信息。

### V1: 初版方案

- `01 Chat Main / Connected`: 主聊天界面,包含消息流、工具调用、Turn 观察和成本反馈条。
- `02 Approval Dialog / Tool Call`: 工具审批弹窗,覆盖 `exec_shell` 审批场景。
- `03 Provider Dropdown`: 顶部 Provider 下拉切换。
- `04 Settings / Providers Readonly`: Provider 只读设置页。
- `05 Disconnected / Reconnect State`: 后端断线和重连状态。

## 已导出本地截图

### V2

- `screens/v2-01-home-context-bar.png`
- `screens/v2-02-chat-runtime.png`
- `screens/v2-03-approval-context-aware.png`
- `screens/v2-04-model-picker-near-composer.png`
- `screens/v2-05-settings-workspace-providers.png`

### V1

- `screens/01-chat-main-connected.png`
- `screens/02-approval-dialog.png`
- `screens/03-provider-dropdown.png`
- `screens/04-settings-providers.png`

## 备注

- V2 按用户反馈调整:不再把“文件上下文”做成单独左侧入口,改为类似 Codex / Claude Code 的输入框上下文条。
- P1-4 详细计划应优先采用 V2 方案。
