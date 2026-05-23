# P1-4 交互流程图

本目录存放 P1-4 Compose Desktop UI 的交互流程图。当前流程以 V2 原型为准:项目、模式、分支、worktree、权限和模型都放在输入框附近的上下文条中,不再把“文件上下文”做成独立导航页。

## 流程文件

- `01-send-message-and-turn-summary.md`: 发送消息、流式事件和 TurnSummary 展示。
- `02-tool-approval.md`: 工具审批弹窗和 `approval/respond` 回写。
- `03-provider-model-switch.md`: 输入框附近的模型切换流程。
- `04-connection-and-reconnect.md`: WebSocket 连接、断线和重连。
- `05-runtime-details-and-cost.md`: 右侧运行详情、工具轨迹和成本反馈。
- `06-workspace-context-bar.md`: 工作区上下文条的项目、模式、分支、worktree、权限交互。

## 使用规则

- 后续补全 `../plan.md` 时,必须把这些流程映射到具体 Compose 组件、状态模型和验收测试。
- 如果 Figma 原型继续调整,本目录也要同步更新。
- Mermaid 图中的协议名必须和后端已有协议保持一致,例如 `thread/create`、`turn/start`、`approval/request`、`approval/respond`、`item/added`、`turnSummary`。
