# Application Action Fast Terminal and Safe Schema Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复快速业务动作终态被后端拒绝导致 Turn 卡住的问题，并让模型获得可安全使用的动作输入结构。

**Architecture:** 后端状态机在风险路径已到合法执行门槛时合成 RUNNING，再消费快速终态；业务应用上下文只投影白名单 JSON Schema 结构；桌面演示动作声明完整表单 Patch schema。

**Tech Stack:** Java 21、Spring Boot、JUnit 5、Kotlin、kotlinx.serialization、Gradle、SQLite、WebSocket JSON-RPC 2.0

---

### Task 1: 锁定快速终态缺陷

**Files:**
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/action/PendingApplicationActionsTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/ApplicationBridgeEndToEndIT.java`

- [x] 写入 `ACCEPTED -> COMPLETED` 的失败测试。
- [x] 确认当前状态机返回 false。
- [x] 跨端 IT 移除显式 running，并要求 Item 和审计仍包含 running/executing。

### Task 2: 实现安全快速终态收束

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/action/PendingApplicationActions.java`

- [x] 仅对路径合法执行门槛合成 RUNNING。
- [x] 在终态前发布合成进度并排队审计事件。
- [x] 保持高风险非法跳转拒绝语义。

### Task 3: 投影模型可见安全输入 Schema

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/context/ApplicationContextModelContributor.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/context/ApplicationContextModelContributorTest.java`

- [x] 先写缺少 schema 的失败测试。
- [x] 递归投影白名单结构字段。
- [x] 移除 executionId、描述文本和凭据型字段。
- [x] 对未知结构 fail-closed。

### Task 4: 补齐表单 Patch 嵌套 Schema

**Files:**
- Modify: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/DemoActionCatalog.kt`
- Modify: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/form/FormPreviewPatchAction.kt`
- Modify: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/form/FormApplyPatchAction.kt`
- Modify: `business-desktop/framework-demo/src/test/kotlin/com/wzx/huitai/demo/action/DemoActionCatalogTest.kt`

- [x] 先写嵌套 schema 缺失的失败测试。
- [x] 声明 Patch、FieldChange 和 SourceReference 结构与预算。
- [x] 让预览和应用动作复用同一 schema。

### Task 5: 验证

**Files:**
- Verify: `backend/`
- Verify: `business-desktop/`

- [x] 运行后端定向测试。
- [x] 运行业务桌面定向测试。
- [x] 运行 `backend/.mvnw.cmd clean verify`。
- [x] 使用仓库内 ASCII Gradle 缓存运行 `business-desktop/gradlew.bat test`。
- [x] 检查 diff、日志和工作区状态。
