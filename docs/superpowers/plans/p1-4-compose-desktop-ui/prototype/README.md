# P1-4 UI 原型投放区

这个目录专门用于存放 P1-4 Compose Desktop UI 的原型材料。外部设计或协作者可以把原型图、交互说明、截图和参考资料放到这里。

## 建议目录

- `wireframes/`: 低保真线框图,用于确认布局和信息层级。
- `screens/`: 高保真界面图或截图,用于确认视觉风格。
- `flows/`: 交互流程图,用于描述聊天、审批、Provider 切换、错误恢复等流程。
- `references/`: 参考产品截图、风格参考、设计备注。

## 建议文件命名

```text
wireframes/01-chat-screen.md
wireframes/02-approval-dialog.md
screens/01-chat-screen.png
flows/01-send-message-and-approval.md
references/README.md
```

## 原型至少需要覆盖

- 主聊天界面: 消息列表、输入框、发送状态、流式输出状态。
- 输入框附近 Provider 选择器: 当前 provider、model、切换入口。
- 审批弹窗: 工具名、参数或命令、Approve / Deny / Always / Edit 操作。
- Turn 成本反馈条: tokens、费用、耗时、工具调用次数。
- 设置面板: provider 只读列表。
- 连接状态: 未连接、连接中、已连接、断线。

## 交付说明

原型进入本目录后,Codex 需要先阅读这些材料,再把确认后的 UI 结构、组件边界和验收标准写入 `../plan.md`。

## 当前原型

已创建 Figma 高保真原型,详情见:

- `figma.md`

已导出的本地截图位于:

- `screens/v2-01-home-context-bar.png`
- `screens/v2-02-chat-runtime.png`
- `screens/v2-03-approval-context-aware.png`
- `screens/v2-04-model-picker-near-composer.png`
- `screens/v2-05-settings-workspace-providers.png`

已设计的交互流程图位于:

- `flows/README.md`
- `flows/01-send-message-and-turn-summary.md`
- `flows/02-tool-approval.md`
- `flows/03-provider-model-switch.md`
- `flows/04-connection-and-reconnect.md`
- `flows/05-runtime-details-and-cost.md`
- `flows/06-workspace-context-bar.md`

当前推荐使用 V2 方案:把项目、模式、分支、worktree、权限、模型放在输入框附近的上下文条中,不要把“文件上下文”做成独立导航页。

## 原型语义修正

- 首页/idle 状态不显示成本 chip；成本只能在后端发送 `turnSummary` 后展示。
- `ComposerContextBar` 不显示“本轮约 $...”之类成本信息，避免和聊天流中的 `TurnSummaryBar` 重复。
- `TurnSummaryBar` 是聊天主区的成本摘要入口；右侧运行详情展开后展示同一份 `turnSummary` 的明细。
- Sidebar 中 `搜索`、`插件`、`自动化` 在 P1-4 只作为禁用 P2 占位或隐藏，不实现真实功能。
- 首页三张快速操作卡如果保留，只能作为禁用 P2 placeholder；P1-4 不实现消息传送、电子邮件、文件/网盘连接器。
