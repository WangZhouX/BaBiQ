# Business Backend Stale Session Recovery Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** IDEA 停止独立业务后端后，即使开发会话文件残留，下一次启动也能安全自动恢复。

**Architecture:** runner 以生命周期级跨进程锁串行化新 runner 与 session lease；对升级前的无锁会话，再通过认证 WebSocket 探测和 loopback 端口可绑定探测区分真实存活后端与残留文件。只有会话不可认证且端口空闲时，才在所有权锁内以文件身份和指纹保护回收残留描述符。

**Tech Stack:** Kotlin/JVM 21、Ktor WebSocket、Java NIO FileLock/Socket、Gradle JavaExec、kotlin.test。

---

## Chunk 1: Safe stale-session recovery

### Task 1: 固定恢复语义并实现最小修复

**Files:**
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentDevelopmentSessionFile.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendDevelopmentRunner.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendDevelopmentRunnerTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentDevelopmentSessionFileTest.kt`

- [x] 写失败测试：不可认证且端口空闲的残留会话会被回收并成功启动。
- [x] 写失败测试：可认证会话或仍占用端口的会话被保留且拒绝重复启动。
- [x] 写失败测试：损坏或探测期间被替换的会话采用安全恢复/失败语义。
- [x] 运行定向测试，确认因旧“只看文件存在”逻辑按预期失败。
- [x] 实现可注入的会话存活探测、端口空闲探测和身份/指纹保护删除。
- [x] 增加生命周期级跨进程所有权锁，修复审查发现的最终检查到删除之间竞态。
- [x] 运行定向测试确认全部转绿，并重构重复夹具。

### Task 2: 回归与真实启动验证

- [x] 运行 app runtime/composition 聚焦测试：43 tests，0 failures/errors。
- [x] 运行 `business-desktop` 全量测试（`--rerun-tasks`）：805 tests，0 failures/errors/skipped。
- [x] 用真实残留文件启动，确认后端监听 49391 并重新发布会话。
- [x] 在后端运行时启动第二份 runner，确认跨进程锁拒绝重复启动。
- [x] 强制终止 runner/child，保留 `development-session.json` 与 `.lock` 后再次启动；会话时间从 10:40:13 更新为 10:46:59，49391 恢复监听。
- [x] 停止所有烟测进程，确认 49391 无监听进程。
- [x] 执行 `git diff --check`，并记录未触碰的无关脏工作区改动。
- [x] 独立规格与代码质量复审通过：规格 PASS，质量 APPROVED，无剩余 Critical/Important。
