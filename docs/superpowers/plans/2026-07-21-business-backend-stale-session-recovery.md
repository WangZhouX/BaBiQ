# Business Backend Stale Session Recovery Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** IDEA 停止独立业务后端后，即使开发会话文件残留，下一次启动也能安全自动恢复。

**Architecture:** runner 启动前通过认证 WebSocket 探测和 loopback 端口可绑定探测区分真实存活后端与残留文件。只有会话不可认证且端口空闲时，才以文件身份校验保护回收残留描述符。

**Tech Stack:** Kotlin/JVM 21、Ktor WebSocket、Java NIO、Gradle JavaExec、kotlin.test。

---

## Chunk 1: Safe stale-session recovery

### Task 1: 固定恢复语义并实现最小修复

**Files:**
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentDevelopmentSessionFile.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendDevelopmentRunner.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendDevelopmentRunnerTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentDevelopmentSessionFileTest.kt`

- [ ] 写失败测试：不可认证且端口空闲的残留会话会被回收并成功启动。
- [ ] 写失败测试：可认证会话或仍占用端口的会话被保留且拒绝重复启动。
- [ ] 写失败测试：损坏或探测期间被替换的会话采用安全恢复/失败语义。
- [ ] 运行定向测试，确认因当前“只看文件存在”逻辑按预期失败。
- [ ] 实现可注入的会话存活探测、端口空闲探测和身份保护删除。
- [ ] 运行定向测试确认全部转绿，并重构重复夹具。

### Task 2: 回归与真实启动验证

- [ ] 运行 app runtime/composition 聚焦测试。
- [ ] 运行 `business-desktop` 全量测试。
- [ ] 使用当前真实残留文件复现自动回收，确认后端监听 49391 并重新发布会话。
- [ ] 停止后端后再次启动，确认无需手工删除且没有残留 child/端口。
- [ ] 执行 `git diff --check` 并记录未触碰的无关脏工作区改动。

