# P1-4 Compose Desktop UI Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 BaBiQ 的 Kotlin Compose Desktop 桌面 UI,让用户可以通过图形界面完成聊天、审批、Provider 切换和 Turn 成本反馈查看。

**Architecture:** 桌面端通过 Ktor WebSocket 客户端连接后端 `/ws/agent`,使用 JSON-RPC 2.0 发送请求并消费 `item/added`、`item/updated`、`approval/request`、`turn/completed`、`turnSummary` 等协议事件。UI 层采用 Compose Desktop 组件化拆分,先完成可确认的静态原型,再接入真实协议状态流。

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.11.0, Ktor Client, kotlinx.serialization, Gradle Kotlin DSL.

---

## 当前状态

- 本文件是 P1-4 详细计划占位稿。
- 真实详细任务尚未展开。
- 正式写计划前必须先吸收 `prototype/` 目录中的原型材料。
- 正式实现前必须由用户确认完整计划。

## 计划必须覆盖的范围

- ChatScreen: 输入框、发送、消息列表、流式更新、错误状态。
- ApprovalDialog: 展示工具名、命令或参数、Approve / Deny / Always / Edit。
- ProviderSelector: 顶部 provider 下拉、当前模型展示、切换后下轮请求生效。
- TurnSummaryBar: 展示 `tokensIn / tokensOut / costUsd / durationMs / toolCount`。
- SettingsPanel: P1 阶段只读展示 provider 信息,不做编辑。
- 协议模型映射: 后端 Thread / Turn / Item / Approval / TurnSummary 到 Kotlin sealed model。
- WebSocket 生命周期: 连接、断线、重连提示、请求 id 管理。
- UI 验收: 静态原型截图、桌面端真实运行、端到端场景肉眼可见。

## 后续计划步骤

- [ ] 收集并确认 `prototype/` 下的原型材料。
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
