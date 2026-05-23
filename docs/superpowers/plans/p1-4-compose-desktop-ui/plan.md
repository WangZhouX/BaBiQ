# P1-4 Compose Desktop UI Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 BaBiQ 的 Kotlin Compose Desktop 桌面 UI,让用户可以通过图形界面完成聊天、审批、Provider 切换和 Turn 成本反馈查看。

**Architecture:** 桌面端通过 Ktor WebSocket 客户端连接后端 `/ws/agent`,使用 JSON-RPC 2.0 发送请求并消费 `item/added`、`item/updated`、`approval/request`、`turn/completed`、`turnSummary` 等协议事件。UI 层采用 Compose Desktop 组件化拆分,先完成可确认的静态原型,再接入真实协议状态流。

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.11.0, Ktor Client, kotlinx.serialization, Gradle Kotlin DSL.

---

## 当前状态

- P1-4 计划目录已创建。
- V2 高保真原型已完成并经用户初审通过,暂时没有问题。
- V2 交互流程图已完成。
- 本文件仍是 P1-4 详细实施计划的入口稿,真实任务拆解尚未展开。
- 正式实现前必须先把本文件扩展为完整详细计划,并再次由用户确认。

## 原型与交互材料索引

后续编写完整 P1-4 计划和实现桌面端 UI 时,必须优先读取本节材料。

### 原型总入口

- 原型目录: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype`
- 原型说明: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\README.md`
- Figma 说明: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\figma.md`
- Figma 文件: <https://www.figma.com/design/frTp55zgrKf4NAWxn6LdI7>

### V2 高保真截图

- 首页输入框上下文条: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-01-home-context-bar.png`
- 运行态聊天界面: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-02-chat-runtime.png`
- 审批弹窗: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-03-approval-context-aware.png`
- 输入框附近模型切换: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-04-model-picker-near-composer.png`
- 设置页: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\screens\v2-05-settings-workspace-providers.png`

### 交互流程图

- 流程总入口: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\README.md`
- 发送消息与 TurnSummary: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\01-send-message-and-turn-summary.md`
- 工具审批: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\02-tool-approval.md`
- Provider 与模型切换: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\03-provider-model-switch.md`
- 连接与重连: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\04-connection-and-reconnect.md`
- 运行详情与成本反馈: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\05-runtime-details-and-cost.md`
- 工作区上下文条: `E:\BaBiQ\docs\superpowers\plans\p1-4-compose-desktop-ui\prototype\flows\06-workspace-context-bar.md`

### 当前采用的设计结论

- 采用 V2 方案。
- 不把“文件上下文”做成独立左侧入口。
- 项目、模式、分支、worktree、权限、模型都放在输入框附近的上下文条中。
- 模型切换入口靠近输入框,切换从下一条消息开始生效。
- 右侧运行详情默认收起,用户需要查看工具轨迹和成本详情时再展开。

## 计划必须覆盖的范围

- ChatScreen: 输入框、发送、消息列表、流式更新、错误状态。
- ApprovalDialog: 展示工具名、命令或参数、Approve / Deny / Always / Edit。
- ComposerContextBar: 项目、模式、分支、worktree、权限、模型上下文展示与切换。
- ProviderSelector: 输入框附近模型下拉、当前模型展示、切换后下轮请求生效。
- TurnSummaryBar: 展示 `tokensIn / tokensOut / costUsd / durationMs / toolCount`。
- SettingsPanel: P1 阶段只读展示 provider 信息,不做编辑。
- 协议模型映射: 后端 Thread / Turn / Item / Approval / TurnSummary 到 Kotlin sealed model。
- WebSocket 生命周期: 连接、断线、重连提示、请求 id 管理。
- UI 验收: 静态原型截图、桌面端真实运行、端到端场景肉眼可见。

## 后续计划步骤

- [x] 收集并确认 `prototype/` 下的原型材料。
- [x] 设计 V2 高保真截图。
- [x] 设计交互流程图。
- [ ] 阅读 `docs/ARCHITECTURE.md` 中桌面端、Provider UI 和协议章节。
- [ ] 阅读 P1-1 / P1-2 / P1-3A / P1-3B 的 plan 与 handoff。
- [ ] 查看 `desktop/` 当前代码结构和 Gradle 配置。
- [ ] 查阅 JetBrains Compose Multiplatform、Ktor Client、kotlinx.serialization 官方文档。
- [ ] 编写完整 P1-4 详细计划。
- [ ] 用户确认计划。
- [ ] 再开始实现。

## 非目标

- 不修改后端协议,除非计划确认后发现 P1-4 无法消费现有事件。
- 不引入 Web 前端。
- 不做 Provider 编辑、API Key 管理或 KeyStore。
- 不做 P2+ 的 Actuator、Prometheus、OpenTelemetry 或 Langfuse UI。
- 不跳过原型确认直接实现最终界面。
