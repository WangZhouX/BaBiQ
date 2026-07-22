# Business Desktop Split Development Runtime Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 为 IDEA 增加可独立启动、停止和查看日志的 Business Backend 与 Business Frontend 开发任务。

**Architecture:** 新增受限的开发后端托管入口，复用现有业务 profile、JCEKS 密码和认证握手；通过 owner-only 临时会话描述文件把连接身份交给只连接不拉子进程的前端开发模式。生产 embedded 模式保持不变。

**Tech Stack:** Kotlin/JVM 21、Compose Desktop、Gradle JavaExec、Ktor WebSocket、Spring Boot 3、JCEKS。

---

## Chunk 1: Runtime contract

### Task 1: 开发会话描述文件

**Files:**
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessDesktopRuntimePaths.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentDevelopmentSessionFile.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentDevelopmentSessionFileTest.kt`

- [x] 写失败测试：发布后可读取完全相同的 `AgentConnectRequest`。
- [x] 写失败测试：拒绝非 loopback URL、非 UUID、超限文件和符号链接。
- [x] 写失败测试：关闭后删除会话文件且不泄漏 token 到 `toString`。
- [x] 运行测试确认因实现缺失失败。
- [x] 实现原子写入、owner-only 权限、有界读取和严格校验。
- [x] 运行定向测试确认通过。

### Task 2: 可复用认证连接会话

**Files:**
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentRuntimeSession.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentProcessLauncherTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRootTest.kt`

- [x] 写失败测试：无 `Process` 的外部会话可使用现有 supervisor 建立连接和重连。
- [x] 写失败测试：external composition 不调用 embedded child launcher。
- [x] 运行测试确认失败。
- [x] 提取认证连接会话，让 embedded runtime 和 external runtime 共享连接逻辑。
- [x] 在 production configuration 中加入显式 Embedded/External 模式。
- [x] 运行定向测试确认通过。

## Chunk 2: Independent processes

### Task 3: 独立后端开发入口

**Files:**
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendDevelopmentRunner.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentProcessLauncher.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendDevelopmentRunnerTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentProcessLauncherTest.kt`

- [x] 写失败测试：runner 读取现有 backend KeyStore 密码并生成新会话。
- [x] 写失败测试：开发启动继承 stdout/stderr，而 embedded 启动仍追加日志文件。
- [x] 写失败测试：runner 退出时终止 child、删除描述文件并擦除密码。
- [x] 运行测试确认失败。
- [x] 实现开发 runner 和可选择输出目标的 process launcher。
- [x] 运行定向测试确认通过。

### Task 4: 独立 Gradle 任务

**Files:**
- Modify: `business-desktop/app/build.gradle.kts`

- [x] 增加 `runBusinessBackendDevelopment` JavaExec，依赖后端 JAR 打包并运行开发 runner。
- [x] 增加 `runBusinessFrontendDevelopment` JavaExec，仅运行 external 模式 `MainKt`。
- [x] 用 `--dry-run` 验证前端任务不依赖 `packageBusinessBackendJar`，后端任务依赖它。

## Chunk 3: IDEA and verification

### Task 5: 两个 IDEA 配置

**Files:**
- Delete: `.idea/runConfigurations/Business_Desktop.xml`
- Create: `.idea/runConfigurations/Business_Backend.xml`
- Create: `.idea/runConfigurations/Business_Frontend.xml`

- [x] 后端配置运行 `:app:runBusinessBackendDevelopment`，控制台直接显示 Spring Boot 日志。
- [x] 前端配置运行 `:app:runBusinessFrontendDevelopment`，不附加 backend log。
- [x] XML 解析校验名称、任务、JDK 与环境变量。

### Task 6: 自动化与真实双进程烟测

- [x] 运行新增 runtime 与 composition 定向测试。
- [x] 运行 `business-desktop` 全量测试。
- [x] 运行 `backend` 相关认证/业务 profile 定向测试。
- [x] 启动 Backend 开发任务并等待固定端口、会话文件和 Spring Boot Started 日志。
- [x] 启动 Frontend 开发任务并确认 Compose 进程连接同一后端。
- [x] 分别停止前端和后端，确认生命周期互不错误收束。
- [x] 检查无遗留 `babiq-server` 进程、无 token/密码日志泄漏。

