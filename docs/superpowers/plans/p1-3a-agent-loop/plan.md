# P1-3a: Agent Loop 内核(ReAct + Tools + Sandbox + HITL)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **v1 (2026-05-22)**: 首版,与 master plan v3 / ARCHITECTURE §5 / §15 / §17 / §21 对齐,严格遵循 D19 / D21 / D23 / D24 / D26 / D31。

**Goal:** 在已落地的 P1-1(协议层)与 P1-2(Provider + Memory + ModelMetadata)之上,实现 **真正的 ReAct Agent Loop**。落地:6 个工具(`read_file` / `write_file` / `exec_shell` / `list_dir` / `grep` / `apply_patch`)+ `PathGuard` 沙箱(D31)+ `HumanInTheLoopHook` 三档审批(D23,**取代 D8 手写 ApprovalEngine**)+ 4 个 Hook(限流 / 输出截断 / 审批 / token 计数)+ MemorySaver checkpoint。`turn/start` / `turn/interrupt` / `approval/respond` 三个协议方法**从 mock 切到真实实现**。wscat 端到端输入"读 README 并总结"能跑完整 ReAct 循环;`write_file` / `exec_shell` / `apply_patch` 在 `on-request` 策略下触发 `approval/request`;`read-only` 沙箱下 `write_file` 立刻拒绝并发 `fileChange(status=denied)`。

**Architecture:** 严格遵守 ARCHITECTURE §5.3(Agent Loop)+ §15(Hook/Interceptor)+ §17(HITL)+ §21(沙箱)+ master plan §3 文件结构。**核心原则(D21)**:**Agent Loop 主流程 ≤50 行**,横切关注点(限流 / 审批 / 截断 / token 统计)全部用 Hook,**绝不写 if 判断进 loop**。

**⭐ 技术路线(v3 修订)**:P1-3a **直接使用 SAA `ReactAgent.builder()`** 作为 ReAct 引擎(不是 bare ChatClient):
- `ReactAgent.builder().model(chatModel).tools(toolCallbacks).hooks(...).interceptors(...).saver(new MemorySaver()).build()` — SAA 内部管理 ReAct 循环(LLM→tool_calls→执行→回灌→再调 LLM)
- 模型层 Hook 实现 SAA 的 `ModelHook` / `AgentHook` 接口,用 `@HookPositions` 注解声明挂载点(只支持 `BEFORE_AGENT / AFTER_AGENT / BEFORE_MODEL / AFTER_MODEL`)
- 工具层走 SAA `ToolInterceptor`(`interceptToolCall(ToolCallRequest, ToolCallHandler) → ToolCallResponse`);**没有 ToolHook / AFTER_TOOL**(外部审查 D2 已证实 1.1.2.3 jar 不存在该 API)
- 审批走 SAA 内置 `HumanInTheLoopHook.builder().approvalOn(toolName, config).build()`(D23)+ `RunnableConfig.builder().addHumanFeedback(InterruptionMetadata).resume().build()` 续跑(注意 1.1.2.3 没有 HITLHelper / CompiledGraph.resume,需要手动 ToolFeedback.Builder),**严禁**任何阻塞 Agent 线程的设计
- `MemorySaver` 用于 checkpoint(HITL 中断恢复)
- 沙箱用纯 Java `PathGuard.toRealPath()` 防符号链接(D31 P1 兜底),在 `BaBiQSandboxInterceptor` 统一拦截

**为什么用 ReactAgent 而不是 bare ChatClient:**
1. ReactAgent 内部管理 ReAct 循环,AgentLoop 无需手写 while 循环 → 保持 ≤50 行
2. Hook / Interceptor 有标准接口(`ModelHook.beforeModel()` / `ToolInterceptor.interceptToolCall()`),不是散装 plain class
3. `MemorySaver` 内置 checkpoint,HITL 中断后可从 checkpoint resume(事件驱动,不阻塞线程)
4. `HumanInTheLoopHook` 有 builder API,直接声明哪些工具需要审批

**Tech Stack(P1-3a 增量):**
- 继承 P1-0 / P1-1 / P1-2 全部基线(Java 21 / Spring Boot 3.5.14)
- **v3 修订:升级到 Spring AI 1.1.6 + Spring AI Alibaba 1.1.2.3**(在 1.1 稳定线内,不跳 2.0.0-M6;BOM 已实际验证可解析)
- 新增依赖:`com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework`(版本由 `spring-ai-alibaba-bom` 托管)。**Step 1.3 用 `dependency:tree` 验证以下类在 classpath**:`ReactAgent` / `AgentHook` / `ModelHook` / `ToolInterceptor` / `HumanInTheLoopHook` / `MemorySaver` / `RunnableConfig` / `OverAllState` / `ModelCallLimitHook` / `LargeResultEvictionInterceptor` / `InterruptionMetadata`(无 HITLHelper,手动用 ToolFeedback.Builder)
- 测试栈:沿用 JUnit 5 + AssertJ + Mockito 5 + awaitility(P1-1 已引)

**Master Plan Reference:** [../2026-05-21-p1-master.md](../2026-05-21-p1-master.md)

**Architecture Reference:** [../../../ARCHITECTURE.md](../../../ARCHITECTURE.md) §5 / §6 / §14.6 / §15 / §17 / §21

**Milestone:** **M3a**(详见 master plan §4),硬验收清单见本 plan 末尾 Done Criteria。

**Prerequisite Milestones:** M0 / M1 / M2(P1-0 / P1-1 / P1-2 已完成,工作树 clean)。

---

## 0. 代码质量铁律(全程生效)

> 本节复述 `p1-1-protocol/codex-handoff.md §1` 的铁律,P1-3a 复杂度更高,**任一违反等同未完成**。

### 🚫 严格禁止
- ❌ **Agent Loop 主流程方法体超过 50 行**(D21,M3a 硬验收项)
- ❌ 任何方法 > 50 行 / 类 > 300 行 / if-else 嵌套 > 3 层
- ❌ `CompletableFuture<ApprovalDecision>` 手写审批状态机(D8 已废弃,违反等于推翻 D23)
- ❌ `path.startsWith(workspaceRoot)` 这种"裸字符串"路径校验(D31,符号链接可绕)
- ❌ `Runtime.exec(...)` 不带 timeout / 资源限制
- ❌ catch (Exception e) {} 静默吞异常
- ❌ Hook 写进 `agent/` 包(必须在 `hook/` 包,与 `agent/` 平行)
- ❌ 把"工具输出截断"写进 `ToolRegistry` 内部(必须在 `ToolOutputTruncationHook`)
- ❌ 在 P1-3a 引入 Multi-Agent / Spotlighting / TurnSummaryItem(那是 D25 / D34 / D32,P2 / P1-3b 才做)

### ✅ 必须做到
- ✅ 每个 record / class 顶部:中文 JavaDoc 说明"是什么 + 为什么存在 + 谁会用它 + 关联决策编号"
- ✅ 每个 public 方法:中文 JavaDoc 说明"做什么 + 参数 / 返回 / 异常"
- ✅ Hook 单一职责:一个 Hook 只做一件事;**绝不** 在一个 Hook 里同时管截断和 token 统计
- ✅ `PathGuard.toRealPath()` + 白名单前缀比较,**所有路径校验单测都要覆盖符号链接攻击**
- ✅ 每个工具:**单元测试 + 边界用例(空文件 / 不存在 / 二进制 / 超大)**
- ✅ commit message 中文(prefix 英文):`feat(p1-3a): 实现 ReadFileTool + 单测`
- ✅ ⚠️ **每一处涉及 D21 / D23 / D31 的关键代码,行内中文注释标注决策编号**

---

## Files Touched

### Created(生产代码 — `backend/src/main/java/com/wzx/babiq/server/`)

```
agent/
├── AgentLoop.java                    # ⭐ 主流程 ≤50 行(D21 硬性要求)
├── AgentLoopProperties.java          # @ConfigurationProperties("babiq.agent")
└── ReActStrategy.java                # ReactAgent builder 封装(挂 Hook + Saver)

tool/
├── Tool.java                         # marker interface(Spring AI @Tool 注解约定)
├── ToolRegistry.java                 # 工具发现 / 元信息持有
├── ToolResult.java                   # record:{ok, output, error, truncated}
└── impl/
    ├── ReadFileTool.java             # @Tool 读文件(带 PathGuard)
    ├── WriteFileTool.java            # @Tool 写文件(审批 + PathGuard)
    ├── ExecShellTool.java            # @Tool 执行命令(审批 + 沙箱)
    ├── ListDirTool.java              # @Tool 列目录
    ├── GrepTool.java                 # @Tool 文本搜索
    └── ApplyPatchTool.java           # @Tool 应用 unified diff

approval/                             # 协议语义层(实现委托 hook/)
├── ApprovalPolicy.java               # enum: NEVER / ON_REQUEST / ON_FAILURE
├── ApprovalDecision.java             # enum: APPROVED / REJECTED / EDITED
└── ApprovalRequest.java              # record:approval/request 通知载荷

sandbox/
├── SandboxMode.java                  # enum: READ_ONLY / WORKSPACE_WRITE / DANGER_FULL_ACCESS
├── SandboxPolicy.java                # record:{mode, writableRoots}
├── PathGuard.java                    # ⭐ Path.toRealPath() + 白名单(D31)
└── SandboxViolationException.java

hook/                                 # ⭐ D21 横切关注点(SAA Hook 接口;v3 大幅瘦身)
├── BaBiQTokenUsageHook.java          # extends ModelHook + @HookPositions(AFTER_MODEL),累计 token(供 P1-3b)
└── ItemEmittingHook.java             # extends AgentHook(若需),AFTER_AGENT 桥接 NodeOutput 到 ItemEmitter(Task 11 实现)

interceptor/                          # ⭐ v3 新增:SAA ToolInterceptor 层(替代 v2 的 ToolHook)
└── BaBiQSandboxInterceptor.java      # extends ToolInterceptor,D31 写类工具路径白名单 + READ_ONLY 拒绝

agent/runtime/                        # turn 运行时上下文(协议层与 ReactAgent 桥接)
├── TurnExecutor.java                 # 在 Agent 工作线程执行 ReactAgent.invokeAndGetOutput,管理 interrupt + submitResume
├── PendingApprovals.java             # ⭐ v3 新增:threadId → InterruptionMetadata 缓存,供 ApprovalRespondHandler 取出
├── ApprovalRequestPayload.java       # ⭐ v3 新增:approval/request 推送给客户端的载荷 record
└── InterruptFlag.java                # AtomicBoolean 封装,turn/interrupt 触发
```

### Modified(P1-1 / P1-2 已存在的文件)

```
api/method/
├── TurnStartHandler.java             # 真实化:mock stream → AgentLoop.runAsync(...)
├── TurnInterruptHandler.java         # 真实化:翻 InterruptFlag + 让 Hook 抛 InterruptException
└── ApprovalRespondHandler.java       # 真实化:三档反馈,调 HumanInTheLoopHook resume

conversation/
├── ConversationService.java          # 新增 emitCommandExecution / emitFileChange / emitReasoning 辅助
├── ItemEmitter.java                  # 新增 emitApprovalRequest(ApprovalRequestPayload),直接发 approval/request notification
└── items/
    ├── CommandExecutionItem.java     # 已有,新增工厂方法 pending / running / completed / denied
    └── FileChangeItem.java           # 同上

config/
└── BaBiQAgentConfig.java(新增,P1-2 的 ChatClientFactory 注入 ReActStrategy)

resources/
└── application.yml                   # 新增 babiq.agent.* / babiq.tools.* / babiq.sandbox.* / babiq.approval.*
```

### Created(测试代码 — `backend/src/test/java/com/wzx/babiq/server/`)

```
sandbox/
├── PathGuardTest.java                # ⭐ 必覆盖符号链接攻击 / `..` 穿越 / Windows UNC
└── SandboxPolicyTest.java

tool/impl/
├── ReadFileToolTest.java
├── WriteFileToolTest.java
├── ExecShellToolTest.java            # 用 PowerShell echo 跨平台
├── ListDirToolTest.java
├── GrepToolTest.java
└── ApplyPatchToolTest.java

hook/
└── BaBiQTokenUsageHookTest.java          # 累计 + snapshot 不可变 + 负数拒绝

interceptor/
└── BaBiQSandboxInterceptorTest.java      # 读类直接放行 / READ_ONLY 拒写 / 工作区外拒写 / DANGER 放行

agent/
├── AgentLoopTest.java                # mock ReactAgent + mock ItemEmitter,验证 invoke 顺序
├── AgentLoopLineCountTest.java       # ⭐ 物理校验主方法 ≤50 行
├── PendingApprovalsTest.java         # put / take / peek 短期缓存
└── EndToEndIT.java                   # @SpringBootTest + mock ChatModel(带 tool_calls)→ ReAct 跑通

sandbox/
└── SandboxModeRegressionTest.java    # D31 三档模式回归 + 符号链接攻击

api/method/
└── TurnInterruptHandlerTest.java     # interrupt 翻 flag → loop 退出 → emit "interrupted"
```

### Unchanged(明确不动)
- `desktop/` 全部(P1-4)
- `model/` 全部(P1-2 已锁)
- `api/JsonRpcWebSocketHandler.java` / `JsonRpcDispatcher.java` / `JsonRpcMessage.java`(P1-1 已锁)
- `conversation/Turn.java` / `TurnStatus.java`(P1-1 已锁,但 P1-3a 会用 `WAITING_APPROVAL → RUNNING` 边,转移表已就绪)

---

## Pre-flight Check

> 所有 PowerShell 命令默认在 `F:\wwwxxxx\BaBiQ\backend` 下,需切目录时显式 `cd`。

- [ ] **Step 0.1: 确认 M1 + M2 完成 + 工作树干净**

Run:
```powershell
cd F:\wwwxxxx\BaBiQ\backend
.\mvnw.cmd -q clean test
cd ..
git status
git log --oneline -10
```

Expected:
- `BUILD SUCCESS`,P1-1 / P1-2 全部测试通过
- 工作树 clean
- log 中能看到 P1-1 / P1-2 期 commit

- [ ] **Step 0.2: 创建 feature 分支**

Run:
```powershell
git checkout -b feat/p1-3a-agent-loop
git branch --show-current
```

Expected: `feat/p1-3a-agent-loop`。

- [ ] **Step 0.3: 准备 API key(本任务需真模型)**

P1-3a 端到端验收必须跑真 LLM(ReAct 循环没法 mock)。设置任一:
```powershell
$env:AI_DASHSCOPE_API_KEY = "sk-xxxxx"        # 优先,qwen-plus 1M context
# 或
$env:DEEPSEEK_API_KEY = "sk-xxxxx"            # 备选,deepseek-chat 128K
# 或本地兜底
ollama list                                    # 确保 qwen2.5-coder:7b 或同等模型可用
```

> 单元测试与 Hook 测试**不需要** API key,仅 `AgentLoopEndToEndIT` 与 Task 14 wscat 烟测需要。

- [ ] **Step 0.4: 确认前置组件可用**

Run:
```powershell
cd backend
.\mvnw.cmd dependency:tree | Select-String "spring-ai-alibaba|spring-ai-starter-model-openai|spring-ai-advisors|websocket"
cd ..
```

Expected: 输出包含 P1-2 引入的 SAA + Spring AI 全部坐标 + P1-1 引入的 websocket。

---

## Task 1: 依赖准备(spring-ai-alibaba-agent-framework + BOM 升级)

**目标:** 升级 SAA BOM 到 1.1.2.3 + Spring AI BOM 到 1.1.6;引入 `spring-ai-alibaba-agent-framework`;**子坐标全部由 BOM 托管,绝不在子 dependency 写 version**。

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1.0: 升级 BOM 版本属性(v3 修订)**

> 📌 **v3 修订(外部审查 D1)**:Maven Central 已确认可解析。1.1.2.3 / 1.1.6 都在稳定线内。

Edit `backend/pom.xml`,把 `<properties>` 中两个版本号改成新值:

```xml
<properties>
    <!-- v3:Spring AI 升 1.1.5 → 1.1.6(1.1 稳定线最新,不跳 2.0.0-M6) -->
    <spring-ai.version>1.1.6</spring-ai.version>
    <!-- v3:SAA 升 1.1.2.1 → 1.1.2.3(BOM + agent-framework 都已发布) -->
    <spring-ai-alibaba.version>1.1.2.3</spring-ai-alibaba.version>
</properties>
```

跑 dependency:resolve 确认 BOM 可下载,再继续 1.1。

- [ ] **Step 1.1: 加 agent-framework 坐标**

Edit `backend/pom.xml`,在 `<dependencies>` 段尾追加:

```xml
<!-- D23 / D21:ReactAgent + HumanInTheLoopHook + MemorySaver + ModelCallLimiterHook -->
<!-- 版本由 spring-ai-alibaba-bom (P1-2 已引) 托管 -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-agent-framework</artifactId>
</dependency>
```

> ⚠️ **若 SAA 1.1.2.x 将 HITL 拆到 `spring-ai-alibaba-graph-core` 或 `spring-ai-alibaba-hooks`**,以 `dependency:tree` 实际结果为准调整坐标;**绝不** 在此处写明示 version,统一靠 BOM 托管(避免与 P1-2 的 `${spring-ai-alibaba.version}` 漂移)。

- [ ] **Step 1.2: dependency:tree 验证四个关键类可见**

Run:
```powershell
cd backend
.\mvnw.cmd -q dependency:tree | Select-String "agent-framework|graph-core|hooks"
.\mvnw.cmd -q dependency:resolve
```

如未输出预期 artifact,Run:
```powershell
.\mvnw.cmd -q dependency:tree -Dincludes=com.alibaba.cloud.ai
```

确认目标 artifact 在依赖图中。然后用一次性脚本核对 4 个类可被加载:
```powershell
.\mvnw.cmd -q exec:java "-Dexec.mainClass=java.lang.Class" `
  "-Dexec.args=" `
  -Dexec.includeProjectDependencies=true 2>$null
```

> 若以上 exec 不便,**改用单测兜底**(Step 1.3)。

- [ ] **Step 1.3: 写一个最小烟测,确认四个类能 import**

Create `backend/src/test/java/com/wzx/babiq/server/agent/AgentFrameworkSmokeTest.java`:

```java
package com.wzx.babiq.server.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仅用于验证 spring-ai-alibaba-agent-framework 坐标引入正确,
 * 关键类可在 classpath 加载。D21 / D23 的前置依赖检查。
 *
 * v3 修订:已用 javap 实地核对 1.1.2.3 jar,类全名固定。
 */
class AgentFrameworkSmokeTest {

    @Test
    void core_classes_present_on_classpath() {
        // v3:全部使用 1.1.2.3 实际类全名,不再用 || 兜底
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.ReactAgent")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.ModelHook")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.AgentHook")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.HookPosition")).isTrue();
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor")).isTrue();
        // SAA 内置实现(v3 复用)
        assertThat(loadable("com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook")).isTrue();
        // HITL 数据契约
        assertThat(loadable("com.alibaba.cloud.ai.graph.action.InterruptionMetadata")).isTrue();
    }

    private static boolean loadable(String fqcn) {
        try { Class.forName(fqcn, false, AgentFrameworkSmokeTest.class.getClassLoader()); return true; }
        catch (ClassNotFoundException e) { return false; }
    }
}
```

Run:
```powershell
.\mvnw.cmd test "-Dtest=AgentFrameworkSmokeTest"
cd ..
```

Expected: 通过。**若失败,说明 1.1.2.3 内部包名再变,以 javap 实际为准回修测试**(plan 已用 1.1.2.3 jar javap 核对,正常情况不应失败)。

- [ ] **Step 1.4: application.yml 占位声明(实际配置由 Task 10A 写入)**

> 📌 **v2 修订(MINOR-3 fix)**:本 Step 仅在 application.yml 添加 `babiq` 顶级 key 占位注释,实际配置项(maxIterations / approvalPolicy / sandboxMode / tools / writableRoots / approval)由 **Task 10A** 一次写入,与 `AgentLoopProperties` 字段一一对齐,避免命名漂移。

Edit `backend/src/main/resources/application.yml`,追加:

```yaml
# P1-3a 配置占位 — 实际字段由 AgentLoopProperties 定义,Task 10A 写入完整配置
# babiq:
#   agent:
#     max-iterations: 20           # 防 ReAct 死循环
#     approval-policy: ON_REQUEST  # NEVER / ON_REQUEST / ON_FAILURE
#     sandbox-mode: WORKSPACE_WRITE
#     tools:
#       output:
#         max-tokens: 4000
#         per-tool-override:
#           read_file: 8000
```

- [ ] **Step 1.5: Commit**

```powershell
git add backend/pom.xml backend/src/main/resources/application.yml
git add backend/src/test/java/com/wzx/babiq/server/agent/AgentFrameworkSmokeTest.java
git commit -m "chore(p1-3a): 引入 spring-ai-alibaba-agent-framework 并加 babiq.agent/tools/sandbox/approval 配置"
```

---

## Task 2: 沙箱 — PathGuard + SandboxPolicy + SandboxMode

**目标:** 实现 D31 P1 沙箱:`Path.toRealPath()` + 白名单前缀比较,**单测覆盖符号链接攻击**。

**Files:**
- Create: `sandbox/SandboxMode.java`
- Create: `sandbox/SandboxPolicy.java`
- Create: `sandbox/PathGuard.java`
- Create: `sandbox/SandboxViolationException.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/sandbox/PathGuardTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/sandbox/SandboxPolicyTest.java`

- [ ] **Step 2.1: TDD — 写 PathGuardTest(先红)**

Create `backend/src/test/java/com/wzx/babiq/server/sandbox/PathGuardTest.java`:

```java
package com.wzx.babiq.server.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D31 P1 沙箱核心测试 — 必须覆盖:
 *   1. 正常路径通过
 *   2. ../../../etc/passwd 类穿越被拒
 *   3. 符号链接绕过被拒(toRealPath 触发)
 *   4. 不存在的子路径(尚未创建)能正确处理
 *   5. Windows 路径大小写、UNC 不致命
 */
class PathGuardTest {

    @Test
    void allow_path_within_writable_root(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(List.of(root.toRealPath()));
        Path file = root.resolve("a.txt");
        Files.writeString(file, "hello");
        assertThat(guard.checkRead(file.toString())).isEqualTo(file.toRealPath());
        assertThat(guard.checkWrite(file.toString())).isEqualTo(file.toRealPath());
    }

    @Test
    void reject_path_traversal(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(List.of(root.toRealPath()));
        Path escape = root.resolve("..").resolve("escape.txt");
        assertThatThrownBy(() -> guard.checkRead(escape.toString()))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("outside writable roots");
    }

    @Test
    void reject_symlink_escape(@TempDir Path root) throws Exception {
        // 在 root 内创建一个指向 root 外的符号链接,toRealPath 应揭穿它
        Path outside = Files.createTempDirectory("outside-");
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // Windows 非管理员无法创建 symlink,跳过(测试运行用户应有权限,CI 会拒)
            return;
        }
        PathGuard guard = new PathGuard(List.of(root.toRealPath()));
        assertThatThrownBy(() -> guard.checkRead(link.resolve("anything").toString()))
                .isInstanceOf(SandboxViolationException.class);
    }

    @Test
    void allow_nonexistent_child_for_write(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(List.of(root.toRealPath()));
        Path notYet = root.resolve("subdir/new.txt");
        // write 检查时父目录可能尚未存在,需要回退到最近存在的祖先做 toRealPath
        Path resolved = guard.checkWrite(notYet.toString());
        assertThat(resolved).startsWith(root.toRealPath());
    }

    @Test
    void empty_roots_rejects_everything() {
        PathGuard guard = new PathGuard(List.of());
        assertThatThrownBy(() -> guard.checkRead("C:/anywhere"))
                .isInstanceOf(SandboxViolationException.class);
    }
}
```

Expected: RED(类未实现)。

- [ ] **Step 2.2: 实现 SandboxMode + SandboxPolicy + SandboxViolationException**

Create `sandbox/SandboxMode.java`:
```java
package com.wzx.babiq.server.sandbox;

/**
 * D31 三档沙箱模式(对标 Codex sandbox_mode)。
 * <ul>
 *   <li>READ_ONLY        - 只允许读类工具(read_file / list_dir / grep / web_search)</li>
 *   <li>WORKSPACE_WRITE  - 默认,允许写 writable-roots 内,外部需审批</li>
 *   <li>DANGER_FULL_ACCESS - 绕过沙箱,仅调试用</li>
 * </ul>
 */
public enum SandboxMode {
    READ_ONLY, WORKSPACE_WRITE, DANGER_FULL_ACCESS
}
```

Create `sandbox/SandboxViolationException.java`:
```java
package com.wzx.babiq.server.sandbox;

/**
 * 沙箱违例。捕获后:
 *   - 工具层应转成 ToolResult.failure(...),不冒泡到 Agent Loop
 *   - 协议层应发 fileChange/commandExecution(status=denied) Item
 */
public class SandboxViolationException extends RuntimeException {
    public SandboxViolationException(String message) { super(message); }
}
```

Create `sandbox/SandboxPolicy.java`:
```java
package com.wzx.babiq.server.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * 沙箱策略快照 — 每个 turn 创建一份并传给 PathGuard / 工具。
 * 不可变 record,线程安全。
 *
 * @param mode           沙箱模式
 * @param writableRoots  可写白名单(已 toRealPath 规范化)
 */
public record SandboxPolicy(SandboxMode mode, List<Path> writableRoots) {

    public boolean isReadOnly()      { return mode == SandboxMode.READ_ONLY; }
    public boolean isFullAccess()    { return mode == SandboxMode.DANGER_FULL_ACCESS; }
}
```

- [ ] **Step 2.3: 实现 PathGuard(D31 核心,精读注释)**

Create `sandbox/PathGuard.java`:

```java
package com.wzx.babiq.server.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * D31 P1 沙箱核心:路径白名单前缀比较 + Path.toRealPath() 防符号链接绕过。
 *
 * <p><b>为什么不用 path.startsWith(root) 裸字符串?</b><br>
 * 攻击者可在 root 内放符号链接(如 root/link -> /etc),
 * "/workspace/link/passwd".startsWith("/workspace") 为 true,
 * 但真实落点在 /etc,这是 Ona 公司 agent 自杀事故的模式。
 * 必须 Path.toRealPath() 揭穿链接、消除 ..、规范化大小写。</p>
 *
 * <p><b>不存在的子路径如何 toRealPath?</b><br>
 * write_file 的目标常尚未存在;toRealPath 会抛 NoSuchFileException。
 * 解决:沿父目录向上回退,直到找到已存在的祖先,对祖先 toRealPath,
 * 再把剩余 segment 拼回去,最后用 startsWith 做白名单对比。</p>
 */
public final class PathGuard {

    private final List<Path> writableRoots;

    public PathGuard(List<Path> writableRoots) {
        // 直接保留外部已 toRealPath 过的 root;空表 = 拒绝一切
        this.writableRoots = List.copyOf(writableRoots);
    }

    /**
     * 校验读路径,返回 toRealPath 后的规范路径。
     * @throws SandboxViolationException 路径落在白名单外
     */
    public Path checkRead(String raw) {
        Path resolved = resolveRealOrAncestor(Paths.get(raw));
        if (!isUnderAnyRoot(resolved)) {
            throw new SandboxViolationException(
                    "Path [" + raw + "] resolves to [" + resolved + "] outside writable roots");
        }
        return resolved;
    }

    /**
     * 校验写路径 — 与 checkRead 同算法,沙箱模式语义由调用方(SandboxPolicy)负责。
     */
    public Path checkWrite(String raw) {
        return checkRead(raw);   // 算法相同,语义层差异在 SandboxPolicy.isReadOnly()
    }

    private boolean isUnderAnyRoot(Path candidate) {
        for (Path root : writableRoots) {
            if (candidate.startsWith(root)) return true;
        }
        return false;
    }

    /**
     * 对存在的路径直接 toRealPath;对尚不存在的路径,
     * 回退到最近存在祖先 toRealPath 后再 resolve 余下 segments。
     *
     * <p>v3 修订(外部审查 D5):重写为 Path.relativize() 拼接,避免之前 subpath/getNameCount
     * 在 Windows root(如 C:\)和 UNC 路径下崩溃的问题。算法不变:找到最近存在的祖先,
     * 对祖先 toRealPath,再用 relativize(probe, absolute) 拼接到 real 后面。</p>
     */
    private Path resolveRealOrAncestor(Path raw) {
        Path absolute = raw.toAbsolutePath().normalize();
        Path probe = absolute;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = probe.getParent();
            if (parent == null) break;
            probe = parent;
        }
        if (probe == null) {
            // 极端兜底:连根都拿不到,返回规范化绝对路径(白名单比较仍能拒绝)
            return absolute;
        }
        try {
            Path real = probe.toRealPath();
            if (probe.equals(absolute)) {
                return real;
            }
            Path remaining = probe.relativize(absolute);   // probe -> absolute 的相对差
            return real.resolve(remaining).normalize();
        } catch (IOException e) {
            return absolute;
        }
    }
}
```

> ⚠️ 该算法故意保守:不存在的子路径回退到祖先 toRealPath,再用 `relativize()` 拼接子树。
> 单测中 `allow_nonexistent_child_for_write` 验证此分支。
>
> 📌 **v3 修订(外部审查 D5)**:用 `Path.relativize(probe, absolute)` 替代之前的
> `absolute.subpath(probe.getNameCount(), absolute.getNameCount())`,后者在 Windows root
> (C:\)和 UNC 路径 `\\server\share` 下 `getNameCount()` 行为不一致会越界。
>
> 📌 **DANGER_FULL_ACCESS 怎么处理**:`PathGuard.checkWrite()` 在白名单为空(`writableRoots.isEmpty()`)时仍会拒绝(`isUnderAnyRoot` 返回 false)。**Task 6 `BaBiQSandboxInterceptor.checkOrReject()` 在 `policy.isFullAccess()` 为 true 时直接 return null,根本不调 `guard.checkWrite()`**,所以 DANGER_FULL_ACCESS 测试通过该路径。Task 12 `SandboxModeRegressionTest.danger_full_access_writes_anywhere` 是验证整条沙箱链,不是 PathGuard 单测。

- [ ] **Step 2.4: 实现 SandboxPolicyTest(覆盖三档语义)**

Create `backend/src/test/java/com/wzx/babiq/server/sandbox/SandboxPolicyTest.java`:

```java
package com.wzx.babiq.server.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxPolicyTest {

    @Test
    void read_only_is_read_only() {
        var p = new SandboxPolicy(SandboxMode.READ_ONLY, List.of(Path.of(".")));
        assertThat(p.isReadOnly()).isTrue();
        assertThat(p.isFullAccess()).isFalse();
    }

    @Test
    void workspace_write_is_neither_extreme() {
        var p = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(Path.of(".")));
        assertThat(p.isReadOnly()).isFalse();
        assertThat(p.isFullAccess()).isFalse();
    }

    @Test
    void danger_full_access_flag() {
        var p = new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, List.of());
        assertThat(p.isFullAccess()).isTrue();
    }
}
```

- [ ] **Step 2.5: 跑测试(绿)**

Run:
```powershell
cd backend
.\mvnw.cmd test "-Dtest=PathGuardTest,SandboxPolicyTest"
cd ..
```

Expected: 全绿。**若 `reject_symlink_escape` 报"无权限创建 symlink",改用管理员 PowerShell 重跑;CI 阶段可加 `@EnabledOnOs(WINDOWS) @DisabledIfSystemProperty(named="ci",matches="true")` 兜底,但本地必须过**。

- [ ] **Step 2.6: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/sandbox/
git add backend/src/test/java/com/wzx/babiq/server/sandbox/
git commit -m "feat(p1-3a): D31 PathGuard 沙箱(toRealPath 防符号链接 + 单测覆盖攻击场景)"
```

---

## Task 3: 审批协议语义层(approval/ 包)

**目标:** 把 D8 已废弃的"手写 ApprovalEngine + CompletableFuture"换成**纯协议语义 record / enum**;真正拦截走 `BaBiQHumanInTheLoopHook`(Task 6),持久化走 `MemorySaver`(Task 7)。

**Files:**
- Create: `approval/ApprovalPolicy.java`
- Create: `approval/ApprovalDecision.java`
- Create: `approval/ApprovalRequest.java`

- [ ] **Step 3.1: 实现 ApprovalPolicy**

```java
package com.wzx.babiq.server.approval;

/**
 * D24 / §6.1 三档审批策略。运行时由 application.yml(babiq.approval.policy)注入。
 * 与 D8(已废弃)不同,这里只是"语义标签",真正拦截走 BaBiQHumanInTheLoopHook。
 */
public enum ApprovalPolicy {
    /** 全自动,不弹审批 — 仅自动化场景 */
    NEVER,
    /** 默认:write_file / exec_shell / apply_patch 触发审批 */
    ON_REQUEST,
    /** 工具执行失败时才审批,询问是否重试 */
    ON_FAILURE
}
```

- [ ] **Step 3.2: 实现 ApprovalDecision(三档,对应 D23)**

```java
package com.wzx.babiq.server.approval;

/**
 * §17.1 三档反馈:
 *   APPROVED  - 用原参数执行
 *   REJECTED  - 拒绝,reason 回灌为工具失败
 *   EDITED    - 用户改了参数后再执行(EDITED 是 game changer)
 */
public enum ApprovalDecision { APPROVED, REJECTED, EDITED }
```

- [ ] **Step 3.3: 实现 ApprovalRequest record**

```java
package com.wzx.babiq.server.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * approval/request notification 的 params 载荷(D22:record + @JsonProperty)。
 * 由 BaBiQHumanInTheLoopHook 拦截到写类工具时构造,经 ItemEmitter 发出。
 *
 * @param threadId    所属 thread
 * @param turnId      所属 turn
 * @param approvalId  本次审批的唯一 id(approval/respond 用此关联)
 * @param tool        工具名(write_file / exec_shell / apply_patch)
 * @param args        当前模型给的参数;客户端可在 editedArgs 改之
 * @param reason      为何要审批(可空)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalRequest(
        @JsonProperty(required = true) String threadId,
        @JsonProperty(required = true) String turnId,
        @JsonProperty(required = true) String approvalId,
        @JsonProperty(required = true) String tool,
        @JsonProperty(required = true) Map<String, Object> args,
        String reason
) {}
```

- [ ] **Step 3.4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/approval/
git commit -m "feat(p1-3a): approval 协议语义层(D23 取代 D8 手写 ApprovalEngine)"
```

---

## Task 4: ToolResult + ToolRegistry + Tool 接口

**目标:** 工具结果统一容器 + 注册表(从 Spring context 收集 `@Tool` 实例)。**截断不在这里**(D19 由 `ToolOutputTruncationHook` 处理)。

**Files:**
- Create: `tool/Tool.java`
- Create: `tool/ToolResult.java`
- Create: `tool/ToolRegistry.java`

- [ ] **Step 4.1: 实现 Tool marker interface**

```java
package com.wzx.babiq.server.tool;

/**
 * 工具 marker interface。所有 @Tool 注解的 bean 必须 implements 此接口,
 * 以便 ToolRegistry 通过 Spring context 收集(避免依赖 @Tool 反射全局扫描)。
 *
 * <p>具体 schema 由 Spring AI 的 @Tool 注解 + MethodToolCallbackProvider 自动推断(D6 / D7)。
 */
public interface Tool {
    /** 工具名(必须与 @Tool(name=...) 一致;在 hook 白名单中按此匹配)*/
    String name();
}
```

- [ ] **Step 4.2: 实现 ToolResult record**

```java
package com.wzx.babiq.server.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 工具统一结果容器。
 *
 * <p><b>不在这里截断 — 截断在 ToolOutputTruncationHook(D19)</b>。
 *
 * @param ok        是否成功
 * @param output    成功时的输出文本(失败时为空字符串,不要 null,简化 LLM 处理)
 * @param error     失败原因(成功时 null);包含沙箱违例 / IO 异常 / 命令非 0 等
 * @param truncated 是否被 Hook 截断过(供日志统计;工具自己永远填 false)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        @JsonProperty(required = true) boolean ok,
        @JsonProperty(required = true) String output,
        String error,
        @JsonProperty(required = true) boolean truncated
) {
    public static ToolResult ok(String output) {
        return new ToolResult(true, output == null ? "" : output, null, false);
    }
    public static ToolResult failure(String error) {
        return new ToolResult(false, "", error, false);
    }
}
```

- [ ] **Step 4.3: 实现 ToolRegistry**

```java
package com.wzx.babiq.server.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工具注册表 — Spring 注入所有 Tool bean,按 name() 建立 map。
 * <b>截断 / 审批 / 沙箱拦截都在 Hook 层(D21),本类只负责发现 + 查表。</b>
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> byName;

    public ToolRegistry(List<Tool> all) {
        this.byName = all.stream().collect(Collectors.toUnmodifiableMap(Tool::name, t -> t));
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<String> names() { return List.copyOf(byName.keySet()); }
}
```

- [ ] **Step 4.4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/tool/Tool.java
git add backend/src/main/java/com/wzx/babiq/server/tool/ToolResult.java
git add backend/src/main/java/com/wzx/babiq/server/tool/ToolRegistry.java
git commit -m "feat(p1-3a): Tool 接口 + ToolResult record + ToolRegistry(截断不在此层)"
```

---

## Task 5: 6 个工具实现(TDD,每个 ≤80 行)

**目标:** 实现 6 个工具;每个工具:① 用 `@Tool` 注解描述,② 失败返回 `ToolResult.failure(...)`,**不抛异常到 Agent Loop**。

> 📌 **v3 修订(外部审查 D2)— 最大复用 SAA 内置工具**:
>
> | 工具 | v3 方案 |
> |------|---------|
> | `read_file` / `list_dir` | **改为引用 SAA 内置 `FileSystemTools`(若 artifact 提供),BaBiQ 不再自写**。沙箱拦截由 Task 6 `BaBiQSandboxInterceptor extends ToolInterceptor` 统一处理。若 1.1.2.3 jar 内无对应类(用 javap 验证),回退自写,使用本节代码 |
> | `grep` | 暂保留自写(SAA grep 实现细节不确定) |
> | `write_file` / `apply_patch` | **保留自写**(沙箱+审批语义需 BaBiQ 自控,D31) |
> | `exec_shell` | **保留自写,但 5E 修复死锁**:用 2 个 gobbler 线程异步消费 stdout/stderr |
>
> **影响**:Task 5A / 5C(ReadFile / ListDir)的自写实现 + 测试**降级为"备用方案"**;Step 5A.1 / 5C.1 先尝试 `@Bean` 暴露 SAA 内置工具,若编译失败再切自写。Files Touched 同步加可选标注。
>
> **沙箱路径校验从工具内部移除**:之前每个工具调 `guard.checkRead/checkWrite`,v3 改由 Task 6 的 `BaBiQSandboxInterceptor` 在 ToolInterceptor 层统一拦截,工具实现简化为纯 IO。
>
> 📌 **v4 修订(外部审查)— 工具必须删除内部沙箱 check**:
> 用户已确认决策"工具纯 IO + 沙箱全走 interceptor"。
> 因此本 Task 给出的代码示例(WriteFileTool / ApplyPatchTool / ExecShellTool)中的:
> - `if (policy.isReadOnly()) return ToolResult.failure(...)` — **删掉**(BaBiQSandboxInterceptor 已拦)
> - `guard.checkWrite(path)` 调用 — **删掉**,直接 `Path real = Paths.get(path)`(沙箱已通过才会进入工具)
> - `ReadFileTool` / `ListDirTool` / `GrepTool` 内部 `guard.checkRead(path)` — **删掉**
> - 工具构造函数不再注入 `PathGuard / SandboxPolicy`,仅保留业务必要参数
>
> 这样工具实现可缩到 ~20 行/个;沙箱在 Interceptor 一处统一控制,符合 SRP。
> Files Touched 中工具的依赖同步精简(无 `PathGuard guard, SandboxPolicy policy`)。

### 5A. ReadFileTool

**Files:**
- Create: `tool/impl/ReadFileTool.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/tool/impl/ReadFileToolTest.java`

- [ ] **Step 5A.1: TDD — 写 ReadFileToolTest(先红)**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadFileToolTest {

    @Test
    void reads_existing_file(@TempDir Path root) throws Exception {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "hello\nworld");
        var tool = new ReadFileTool(guard(root), policy(root));
        ToolResult r = tool.readFile(f.toString());
        assertThat(r.ok()).isTrue();
        assertThat(r.output()).contains("hello").contains("world");
    }

    @Test
    void rejects_outside_root(@TempDir Path root) throws Exception {
        var tool = new ReadFileTool(guard(root), policy(root));
        ToolResult r = tool.readFile("C:/Windows/System32/drivers/etc/hosts");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("outside writable roots");
    }

    @Test
    void file_not_found_returns_failure(@TempDir Path root) {
        var tool = new ReadFileTool(guard(root), policy(root));
        ToolResult r = tool.readFile(root.resolve("nope.txt").toString());
        assertThat(r.ok()).isFalse();
    }

    private PathGuard guard(Path root) throws Exception {
        return new PathGuard(List.of(root.toRealPath()));
    }
    private SandboxPolicy policy(Path root) throws Exception {
        return new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(root.toRealPath()));
    }
}
```

- [ ] **Step 5A.2: 实现 ReadFileTool**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.sandbox.SandboxViolationException;
import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * read_file — 读文本文件。无须审批(D24:读类工具直接放行)。
 * 输出截断由 ToolOutputTruncationHook 处理(D19),本类不做截断。
 */
@Component
public class ReadFileTool implements Tool {

    private final PathGuard guard;
    private final SandboxPolicy policy;

    public ReadFileTool(PathGuard guard, SandboxPolicy policy) {
        this.guard = guard;
        this.policy = policy;
    }

    @Override public String name() { return "read_file"; }

    @org.springframework.ai.tool.annotation.Tool(
            name = "read_file",
            description = "读取指定路径文件的文本内容;只读操作无须审批"
    )
    public ToolResult readFile(
            @ToolParam(description = "绝对或工作目录相对路径") String path) {
        try {
            Path real = guard.checkRead(path);
            if (!Files.isRegularFile(real)) {
                return ToolResult.failure("Not a regular file: " + path);
            }
            String content = Files.readString(real);
            return ToolResult.ok(content);
        } catch (SandboxViolationException sve) {
            return ToolResult.failure(sve.getMessage());
        } catch (IOException ioe) {
            return ToolResult.failure("IO error: " + ioe.getMessage());
        }
    }
}
```

- [ ] **Step 5A.3: 跑测试**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=ReadFileToolTest"
cd ..
```

Expected: 3 个测试全绿。

- [ ] **Step 5A.4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/tool/impl/ReadFileTool.java
git add backend/src/test/java/com/wzx/babiq/server/tool/impl/ReadFileToolTest.java
git commit -m "feat(p1-3a): ReadFileTool + 单测(读类工具不审批,D24)"
```

### 5B. WriteFileTool

- [ ] **Step 5B.1: TDD — 写 WriteFileToolTest**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WriteFileToolTest {

    @Test
    void writes_new_file(@TempDir Path root) throws Exception {
        var tool = new WriteFileTool(guard(root), policy(root, SandboxMode.WORKSPACE_WRITE));
        Path target = root.resolve("out.txt");
        ToolResult r = tool.writeFile(target.toString(), "hello");
        assertThat(r.ok()).isTrue();
        assertThat(Files.readString(target)).isEqualTo("hello");
    }

    @Test
    void read_only_mode_rejects_write(@TempDir Path root) throws Exception {
        var tool = new WriteFileTool(guard(root), policy(root, SandboxMode.READ_ONLY));
        ToolResult r = tool.writeFile(root.resolve("x").toString(), "y");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("read-only");
    }

    @Test
    void outside_root_rejected(@TempDir Path root) throws Exception {
        var tool = new WriteFileTool(guard(root), policy(root, SandboxMode.WORKSPACE_WRITE));
        ToolResult r = tool.writeFile("C:/Windows/System32/evil.txt", "x");
        assertThat(r.ok()).isFalse();
    }

    private PathGuard guard(Path root) throws Exception {
        return new PathGuard(List.of(root.toRealPath()));
    }
    private SandboxPolicy policy(Path root, SandboxMode m) throws Exception {
        return new SandboxPolicy(m, List.of(root.toRealPath()));
    }
}
```

- [ ] **Step 5B.2: 实现 WriteFileTool**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.sandbox.SandboxViolationException;
import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * write_file — 写文本到指定路径。
 * <b>审批由 BaBiQHumanInTheLoopHook 在 BEFORE_TOOL 拦截</b>(D23 / D24),本类只做沙箱与 IO。
 * READ_ONLY 模式立即返回 failure(协议层据此发 fileChange(denied),D31)。
 */
@Component
public class WriteFileTool implements Tool {

    private final PathGuard guard;
    private final SandboxPolicy policy;

    public WriteFileTool(PathGuard guard, SandboxPolicy policy) {
        this.guard = guard;
        this.policy = policy;
    }

    @Override public String name() { return "write_file"; }

    @org.springframework.ai.tool.annotation.Tool(
            name = "write_file",
            description = "把内容写到指定路径(覆盖)。需要审批(on-request)"
    )
    public ToolResult writeFile(
            @ToolParam(description = "目标路径") String path,
            @ToolParam(description = "要写入的内容") String content) {
        if (policy.isReadOnly()) {
            return ToolResult.failure("Sandbox is read-only, write rejected");
        }
        try {
            Path real = guard.checkWrite(path);
            Files.createDirectories(real.getParent());
            Files.writeString(real, content == null ? "" : content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return ToolResult.ok("Wrote " + (content == null ? 0 : content.length()) + " chars to " + path);
        } catch (SandboxViolationException sve) {
            return ToolResult.failure(sve.getMessage());
        } catch (IOException ioe) {
            return ToolResult.failure("IO error: " + ioe.getMessage());
        }
    }
}
```

- [ ] **Step 5B.3: 跑测试 + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=WriteFileToolTest"
cd ..
git add backend/src/main/java/com/wzx/babiq/server/tool/impl/WriteFileTool.java
git add backend/src/test/java/com/wzx/babiq/server/tool/impl/WriteFileToolTest.java
git commit -m "feat(p1-3a): WriteFileTool + 单测(read-only 立即拒绝,D31)"
```

### 5C. ListDirTool

- [ ] **Step 5C.1: TDD**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ListDirToolTest {

    @Test
    void lists_files_in_dir(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "x");
        Files.createDirectory(root.resolve("sub"));
        var tool = new ListDirTool(new PathGuard(List.of(root.toRealPath())),
                new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(root.toRealPath())));
        ToolResult r = tool.listDir(root.toString());
        assertThat(r.ok()).isTrue();
        assertThat(r.output()).contains("a.txt").contains("sub");
    }
}
```

- [ ] **Step 5C.2: 实现 ListDirTool**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.sandbox.SandboxViolationException;
import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** list_dir — 列目录。无须审批(D24)。*/
@Component
public class ListDirTool implements Tool {
    private final PathGuard guard;
    private final SandboxPolicy policy;

    public ListDirTool(PathGuard guard, SandboxPolicy policy) {
        this.guard = guard; this.policy = policy;
    }
    @Override public String name() { return "list_dir"; }

    @org.springframework.ai.tool.annotation.Tool(
            name = "list_dir",
            description = "列出目录下条目;只读"
    )
    public ToolResult listDir(@ToolParam(description = "目录路径") String path) {
        try {
            Path real = guard.checkRead(path);
            if (!Files.isDirectory(real)) return ToolResult.failure("Not a directory: " + path);
            try (Stream<Path> s = Files.list(real)) {
                StringBuilder sb = new StringBuilder();
                s.sorted().forEach(p -> sb.append(Files.isDirectory(p) ? "[D] " : "[F] ")
                        .append(p.getFileName()).append('\n'));
                return ToolResult.ok(sb.toString());
            }
        } catch (SandboxViolationException sve) {
            return ToolResult.failure(sve.getMessage());
        } catch (IOException ioe) {
            return ToolResult.failure("IO error: " + ioe.getMessage());
        }
    }
}
```

- [ ] **Step 5C.3: 跑测试 + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=ListDirToolTest"
cd ..
git add backend/src/main/java/com/wzx/babiq/server/tool/impl/ListDirTool.java
git add backend/src/test/java/com/wzx/babiq/server/tool/impl/ListDirToolTest.java
git commit -m "feat(p1-3a): ListDirTool + 单测"
```

### 5D. GrepTool

- [ ] **Step 5D.1: TDD**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GrepToolTest {

    @Test
    void finds_pattern_recursively(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "hello\nfoo bar\nworld");
        Path sub = Files.createDirectory(root.resolve("s"));
        Files.writeString(sub.resolve("b.txt"), "FOO inside");
        var tool = new GrepTool(new PathGuard(List.of(root.toRealPath())),
                new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(root.toRealPath())));
        ToolResult r = tool.grep("(?i)foo", root.toString());
        assertThat(r.ok()).isTrue();
        assertThat(r.output()).contains("a.txt").contains("b.txt");
    }
}
```

- [ ] **Step 5D.2: 实现 GrepTool**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.sandbox.SandboxViolationException;
import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** grep — 在目录树下递归搜正则。无须审批(D24)。*/
@Component
public class GrepTool implements Tool {

    private final PathGuard guard;
    private final SandboxPolicy policy;

    public GrepTool(PathGuard guard, SandboxPolicy policy) {
        this.guard = guard; this.policy = policy;
    }

    @Override public String name() { return "grep"; }

    @org.springframework.ai.tool.annotation.Tool(
            name = "grep",
            description = "在目录下递归搜文本(支持 Java 正则);只读"
    )
    public ToolResult grep(@ToolParam(description = "正则模式") String pattern,
                           @ToolParam(description = "搜索根目录") String root) {
        Pattern p;
        try { p = Pattern.compile(pattern); }
        catch (PatternSyntaxException ex) { return ToolResult.failure("Bad regex: " + ex.getMessage()); }

        try {
            Path real = guard.checkRead(root);
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> walk = Files.walk(real)) {
                walk.filter(Files::isRegularFile).forEach(f -> appendMatches(sb, f, p));
            }
            return sb.length() == 0 ? ToolResult.ok("(no match)") : ToolResult.ok(sb.toString());
        } catch (SandboxViolationException sve) {
            return ToolResult.failure(sve.getMessage());
        } catch (IOException ioe) {
            return ToolResult.failure("IO error: " + ioe.getMessage());
        }
    }

    private void appendMatches(StringBuilder sb, Path f, Pattern p) {
        try {
            var lines = Files.readAllLines(f);
            for (int i = 0; i < lines.size(); i++) {
                if (p.matcher(lines.get(i)).find()) {
                    sb.append(f).append(':').append(i + 1).append(':').append(lines.get(i)).append('\n');
                }
            }
        } catch (IOException ignore) { /* 跳过二进制 / 不可读文件 */ }
    }
}
```

- [ ] **Step 5D.3: 跑测试 + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=GrepToolTest"
cd ..
git add backend/src/main/java/com/wzx/babiq/server/tool/impl/GrepTool.java
git add backend/src/test/java/com/wzx/babiq/server/tool/impl/GrepToolTest.java
git commit -m "feat(p1-3a): GrepTool + 单测(支持 Java 正则递归)"
```

### 5E. ExecShellTool

> ⚠️ **关键陷阱**:`cmd.exe /c xxx` + spawn 子进程 = 子进程继承 OS 权限,**绕过沙箱**。
> P1 实现先打补丁:① **强制 timeout**(默认 30s),② **限制最大输出 bytes**,③ **工作目录强制为 writable-roots[0]**,④ **由 BaBiQHumanInTheLoopHook 强审批**。
> 真正的 OS 级隔离留给 P3 `spring-ai-community/agent-sandbox`(D31)。

- [ ] **Step 5E.1: TDD**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecShellToolTest {

    @Test
    void runs_simple_echo(@TempDir Path root) throws Exception {
        var tool = new ExecShellTool(
                new PathGuard(List.of(root.toRealPath())),
                new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(root.toRealPath())),
                30, 64 * 1024);
        // 跨平台:Java 自带 hostname 命令
        ToolResult r = tool.execShell("hostname");
        assertThat(r.ok()).isTrue();
        assertThat(r.output()).isNotBlank();
    }

    @Test
    void read_only_rejects(@TempDir Path root) throws Exception {
        var tool = new ExecShellTool(
                new PathGuard(List.of(root.toRealPath())),
                new SandboxPolicy(SandboxMode.READ_ONLY, List.of(root.toRealPath())),
                30, 64 * 1024);
        ToolResult r = tool.execShell("hostname");
        assertThat(r.ok()).isFalse();
    }

    @Test
    void timeout_kills_process(@TempDir Path root) throws Exception {
        var tool = new ExecShellTool(
                new PathGuard(List.of(root.toRealPath())),
                new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(root.toRealPath())),
                1, 64 * 1024);
        // ping -n 10(Windows)或 ping -c 10(Linux):两边都至少 10s
        String cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? "ping -n 10 127.0.0.1" : "ping -c 10 127.0.0.1";
        ToolResult r = tool.execShell(cmd);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).containsIgnoringCase("timeout");
    }
}
```

- [ ] **Step 5E.2: 实现 ExecShellTool**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * exec_shell — 执行 shell 命令。
 *
 * <p><b>⚠️ P1 沙箱兜底</b>:
 * 子进程继承 OS 权限,所以本工具靠"timeout + 输出上限 + cwd 锁定 writable-roots[0] + 强审批"做软兜底。
 * 真正 OS 级隔离在 P3 引入 spring-ai-community/agent-sandbox(D31)。</p>
 *
 * <p>审批由 BaBiQHumanInTheLoopHook 在 BEFORE_TOOL 拦截;READ_ONLY 模式立即拒绝。</p>
 */
@Component
public class ExecShellTool implements Tool {

    private final PathGuard guard;
    private final SandboxPolicy policy;
    private final int timeoutSeconds;
    private final int maxOutputBytes;

    public ExecShellTool(PathGuard guard, SandboxPolicy policy,
                         @Value("${babiq.tools.exec.timeout-seconds:30}") int timeoutSeconds,
                         @Value("${babiq.tools.exec.max-output-bytes:65536}") int maxOutputBytes) {
        this.guard = guard;
        this.policy = policy;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
    }

    @Override public String name() { return "exec_shell"; }

    @org.springframework.ai.tool.annotation.Tool(
            name = "exec_shell",
            description = "在工作目录执行 shell 命令(写类工具,需要审批)"
    )
    public ToolResult execShell(@ToolParam(description = "命令行") String command) {
        if (policy.isReadOnly()) return ToolResult.failure("Sandbox is read-only, exec rejected");
        if (command == null || command.isBlank()) return ToolResult.failure("Empty command");
        // v3 修订(外部审查 D4):异步 gobbler 消费 stdout/stderr,主线程先 waitFor,
        // 防止"子进程不退出 + 输出未读 → 读阻塞 + waitFor 超时永远不触发"死锁。
        Process p = null;
        Thread gobbler = null;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            ProcessBuilder pb = buildProcess(command);
            p = pb.start();
            gobbler = startGobbler(p.getInputStream(), sink);
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                gobbler.join(1000);
                return ToolResult.failure("Command timeout after " + timeoutSeconds + "s");
            }
            gobbler.join(1000);
            String out = sink.toString();
            int code = p.exitValue();
            if (code != 0) return ToolResult.failure("Exit " + code + ": " + out);
            return ToolResult.ok(out);
        } catch (IOException ioe) {
            return ToolResult.failure("Exec error: " + ioe.getMessage());
        } catch (InterruptedException ie) {
            java.lang.Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return ToolResult.failure("Exec interrupted");
        }
    }

    /** stdout/stderr 异步 gobbler — 防止 Process 输出缓冲区填满导致死锁。 */
    private Thread startGobbler(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    int allow = Math.min(n, maxOutputBytes - total);
                    if (allow > 0) sink.write(buf, 0, allow);
                    total += n;
                    if (total >= maxOutputBytes) {
                        sink.write("\n...[output truncated by exec_shell cap]".getBytes());
                        // 仍继续 drain,避免子进程因写阻塞挂起
                    }
                }
            } catch (IOException ignored) {
                // 进程被 destroy 时常见
            }
        }, "exec-shell-gobbler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private ProcessBuilder buildProcess(String command) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb = win
                ? new ProcessBuilder(List.of("cmd.exe", "/c", command))
                : new ProcessBuilder(List.of("sh", "-c", command));
        pb.redirectErrorStream(true);
        // cwd 锁定到第一个 writable-root,避免命令在敏感目录运行
        if (!policy.writableRoots().isEmpty()) {
            pb.directory(policy.writableRoots().get(0).toFile());
        }
        return pb;
    }
}
```

- [ ] **Step 5E.3: 跑测试 + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=ExecShellToolTest"
cd ..
git add backend/src/main/java/com/wzx/babiq/server/tool/impl/ExecShellTool.java
git add backend/src/test/java/com/wzx/babiq/server/tool/impl/ExecShellToolTest.java
git commit -m "feat(p1-3a): ExecShellTool + 单测(timeout + 输出上限 + cwd 锁定)"
```

### 5F. ApplyPatchTool

- [ ] **Step 5F.1: TDD(简化版 unified diff)**

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplyPatchToolTest {

    @Test
    void replaces_full_file_content(@TempDir Path root) throws Exception {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "old\n");
        var tool = new ApplyPatchTool(new PathGuard(List.of(root.toRealPath())),
                new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(root.toRealPath())));
        // P1 简化:patch payload 直接给目标文件的"新全文",path + newContent 模式
        ToolResult r = tool.applyPatch(f.toString(), "new\nlines\n");
        assertThat(r.ok()).isTrue();
        assertThat(Files.readString(f)).isEqualTo("new\nlines\n");
    }

    @Test
    void read_only_rejects(@TempDir Path root) throws Exception {
        var tool = new ApplyPatchTool(new PathGuard(List.of(root.toRealPath())),
                new SandboxPolicy(SandboxMode.READ_ONLY, List.of(root.toRealPath())));
        ToolResult r = tool.applyPatch(root.resolve("x").toString(), "y");
        assertThat(r.ok()).isFalse();
    }
}
```

- [ ] **Step 5F.2: 实现 ApplyPatchTool**

> **P1 简化版**:不实现完整 unified diff,只接受 `(path, newContent)` 全量替换。P2 起可换为真 diff 算法。
> 这是为了避开 P1 阶段引入 java-diff-utils 依赖。

```java
package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.sandbox.SandboxViolationException;
import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * apply_patch — P1 简化为"全文替换"。P2 起换 java-diff-utils 真 diff。
 * 写类工具,需要审批(D24)。
 */
@Component
public class ApplyPatchTool implements Tool {

    private final PathGuard guard;
    private final SandboxPolicy policy;

    public ApplyPatchTool(PathGuard guard, SandboxPolicy policy) {
        this.guard = guard; this.policy = policy;
    }

    @Override public String name() { return "apply_patch"; }

    @org.springframework.ai.tool.annotation.Tool(
            name = "apply_patch",
            description = "对指定文件应用 patch(P1 简化为全文替换);需要审批"
    )
    public ToolResult applyPatch(@ToolParam(description = "目标路径") String path,
                                 @ToolParam(description = "新全文(P1)/ unified diff(P2+)") String newContent) {
        if (policy.isReadOnly()) return ToolResult.failure("Sandbox is read-only, patch rejected");
        try {
            Path real = guard.checkWrite(path);
            Files.createDirectories(real.getParent());
            Files.writeString(real, newContent == null ? "" : newContent);
            return ToolResult.ok("Patched " + path + " (" + (newContent == null ? 0 : newContent.length()) + " chars)");
        } catch (SandboxViolationException sve) {
            return ToolResult.failure(sve.getMessage());
        } catch (IOException ioe) {
            return ToolResult.failure("IO error: " + ioe.getMessage());
        }
    }
}
```

- [ ] **Step 5F.3: 跑测试 + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=ApplyPatchToolTest"
cd ..
git add backend/src/main/java/com/wzx/babiq/server/tool/impl/ApplyPatchTool.java
git add backend/src/test/java/com/wzx/babiq/server/tool/impl/ApplyPatchToolTest.java
git commit -m "feat(p1-3a): ApplyPatchTool(P1 简化为全文替换,P2 换真 diff)"
```

### 5G. 6 工具集成回归

- [ ] **Step 5G.1: 跑全部工具测试**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=*ToolTest"
cd ..
```

Expected: 6 个工具单测全绿。

---

## Task 6: 工具拦截器 — 沙箱 + 输出截断(v4 按 javap 真实 API 重写)

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/interceptor/BaBiQSandboxInterceptor.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/interceptor/BaBiQSandboxInterceptorTest.java`

> ⚠️ **v4 修订(外部审查)— javap 实地核对的真实 API**:
> - `ToolInterceptor` 包名:`com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor`(它是抽象类,自带 `getName()`)
> - 真实方法签名:`ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler)`
> - `ToolCallRequest`:`getToolName()` / `getArguments()` / `getToolCallId()` / `getContext() Map<String,Object>` / `getExecutionContext() Optional<ToolCallExecutionContext>`
> - `ToolCallResponse.error(String toolName, String toolCallId, String errorMessage)` 静态工厂
> - `LargeResultEvictionInterceptor` 在 `com.alibaba.cloud.ai.graph.agent.extension.interceptor` 包下,builder 是 `.toolTokenLimitBeforeEvict(Integer).excludeTool(String).build()` — **没有 defaultMaxTokens / perToolOverride**
>
> **v4 拆成两件事**:
> 1. **沙箱拦截**:`BaBiQSandboxInterceptor extends ToolInterceptor` — 用 `ToolCallRequest.getArguments()`(JSON 串)取 path,做 `PathGuard.checkWrite(...)`;READ_ONLY 时直接 `ToolCallResponse.error(...)`。
> 2. **输出截断**:**复用 SAA 内置 `LargeResultEvictionInterceptor`**,通过 `.toolTokenLimitBeforeEvict(Integer)` + 多次 `.excludeTool(String)` 配置,不再自写。

- [ ] **Step 6.1: 写失败测试(纯逻辑层)**

Create `BaBiQSandboxInterceptorTest.java`:
```java
package com.wzx.babiq.server.interceptor;

import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BaBiQSandboxInterceptorTest {

    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "exec_shell", "apply_patch");

    @Test
    void read_tool_bypasses_sandbox_check(@TempDir Path tmp) throws Exception {
        var policy = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(tmp.toRealPath()));
        var guard = new PathGuard(List.of(tmp.toRealPath()));
        var interceptor = new BaBiQSandboxInterceptor(guard, policy, WRITE_TOOLS);

        assertThat(interceptor.shouldEnforceSandbox("read_file")).isFalse();
        assertThat(interceptor.shouldEnforceSandbox("list_dir")).isFalse();
    }

    @Test
    void write_tool_under_read_only_is_rejected(@TempDir Path tmp) throws Exception {
        var policy = new SandboxPolicy(SandboxMode.READ_ONLY, List.of(tmp.toRealPath()));
        var guard = new PathGuard(List.of(tmp.toRealPath()));
        var interceptor = new BaBiQSandboxInterceptor(guard, policy, WRITE_TOOLS);

        String rejection = interceptor.checkOrReject("write_file", tmp.resolve("a.txt").toString());
        assertThat(rejection).contains("read-only");
    }

    @Test
    void write_tool_outside_workspace_is_rejected(@TempDir Path tmp) throws Exception {
        var policy = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(tmp.toRealPath()));
        var guard = new PathGuard(List.of(tmp.toRealPath()));
        var interceptor = new BaBiQSandboxInterceptor(guard, policy, WRITE_TOOLS);

        Path outside = java.nio.file.Files.createTempDirectory("outside");
        String rejection = interceptor.checkOrReject("write_file", outside.resolve("a.txt").toString());
        assertThat(rejection).isNotNull();
    }

    @Test
    void danger_full_access_allows_anywhere(@TempDir Path tmp) throws Exception {
        var policy = new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, List.of());
        var guard = new PathGuard(List.of());
        var interceptor = new BaBiQSandboxInterceptor(guard, policy, WRITE_TOOLS);

        Path anywhere = java.nio.file.Files.createTempDirectory("anywhere");
        String rejection = interceptor.checkOrReject("write_file", anywhere.resolve("a.txt").toString());
        assertThat(rejection).isNull();
    }
}
```

- [ ] **Step 6.2: 跑测试确认 FAIL**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=BaBiQSandboxInterceptorTest"
cd ..
```

Expected: 编译失败(`BaBiQSandboxInterceptor` 不存在)。

- [ ] **Step 6.3: 实现 BaBiQSandboxInterceptor**

Create `BaBiQSandboxInterceptor.java`:
```java
package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.sandbox.PathGuard;
import com.wzx.babiq.server.sandbox.SandboxPolicy;
import com.wzx.babiq.server.sandbox.SandboxViolationException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 沙箱拦截器(D31)— 写类工具调用前统一拦截。
 *
 * <p>v4 修订(外部审查):用 SAA 1.1.2.3 真实 ToolInterceptor 抽象类 +
 * interceptToolCall(ToolCallRequest, ToolCallHandler) 方法签名。
 * 工具层无 ToolHook / AFTER_TOOL,沙箱判断必须放在 Interceptor 层。</p>
 *
 * <p>D24:只对写类工具触发(write_file / exec_shell / apply_patch);
 * 读类工具(read_file / list_dir / grep)永远 bypass,直接交给下一个 handler。</p>
 *
 * <p>v3 决策已确认:工具实现保持纯 IO,沙箱校验全走本拦截器,不在工具内重复 check。</p>
 */
@Component
public final class BaBiQSandboxInterceptor extends ToolInterceptor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PathGuard guard;
    private final SandboxPolicy policy;
    private final Set<String> writeTools;

    /**
     * 构造沙箱拦截器。
     *
     * @param guard 路径白名单校验器
     * @param policy 当前沙箱策略(READ_ONLY / WORKSPACE_WRITE / DANGER_FULL_ACCESS)
     * @param writeTools 需要审批的写类工具白名单
     */
    public BaBiQSandboxInterceptor(PathGuard guard, SandboxPolicy policy, Set<String> writeTools) {
        this.guard = guard;
        this.policy = policy;
        this.writeTools = Set.copyOf(writeTools);
    }

    @Override
    public String getName() { return "babiq_sandbox"; }

    /**
     * SAA 工具拦截入口 — 实现 ToolInterceptor.interceptToolCall。
     *
     * <p>读类工具直接放行;写类工具先做沙箱校验,失败立即返回 ToolCallResponse.error 不进 handler。</p>
     */
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        String toolName = request.getToolName();
        if (!shouldEnforceSandbox(toolName)) {
            return handler.call(request);
        }

        String path = extractPathArgument(request);
        String rejection = checkOrReject(toolName, path);
        if (rejection != null) {
            return ToolCallResponse.error(toolName, request.getToolCallId(), rejection);
        }
        return handler.call(request);
    }

    /** 判断该工具是否需要沙箱校验(读类工具永远不需要)。 */
    public boolean shouldEnforceSandbox(String toolName) {
        return writeTools.contains(toolName);
    }

    /**
     * 对写类工具做沙箱判断。
     *
     * @return null 表示放行;非 null 表示拒绝原因
     */
    public String checkOrReject(String toolName, String path) {
        if (policy.isReadOnly()) {
            return "Sandbox is read-only, " + toolName + " rejected";
        }
        if (policy.isFullAccess()) {
            return null;
        }
        if (path == null || path.isBlank()) {
            return toolName + " missing 'path' argument";
        }
        try {
            guard.checkWrite(path);
            return null;
        } catch (SandboxViolationException sve) {
            return "Sandbox violation: " + sve.getMessage();
        }
    }

    /** 从 ToolCallRequest.getArguments() (JSON 串)拿出 path 字段;exec_shell 无 path,返回 null。 */
    private String extractPathArgument(ToolCallRequest request) {
        String args = request.getArguments();
        if (args == null || args.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(args);
            JsonNode pathNode = node.get("path");
            return pathNode == null || pathNode.isNull() ? null : pathNode.asText();
        } catch (Exception e) {
            return null;   // 参数解析失败,交给工具层报错(我们不拦)
        }
    }
}
```

- [ ] **Step 6.4: 跑测试确认 PASS + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=BaBiQSandboxInterceptorTest"
cd ..
git add backend/src/main/java/com/wzx/babiq/server/interceptor/
git add backend/src/test/java/com/wzx/babiq/server/interceptor/
git commit -m "feat(p1-3a): 实现 BaBiQSandboxInterceptor(D31,v4 用真实 ToolInterceptor API)"
```

- [ ] **Step 6.5: 工具输出截断 — 复用 SAA LargeResultEvictionInterceptor**

> 📌 **v4 修订**:不自写截断 Interceptor,直接用 SAA 内置 `LargeResultEvictionInterceptor`。
> **真实包名**:`com.alibaba.cloud.ai.graph.agent.extension.interceptor.LargeResultEvictionInterceptor`(注意 `extension.interceptor`,不是 `interceptor`)。
> **真实 builder**:`.toolTokenLimitBeforeEvict(Integer)` + `.excludeTool(String)` 多次调用 + `.excludeFilesystemTools()` + `.build()` —— **没有 `defaultMaxTokens` / `perToolOverride`**。
>
> 装配方式(在 Task 10B `ReActStrategy.buildAgent()` 中):
> ```java
> LargeResultEvictionInterceptor evict = LargeResultEvictionInterceptor.builder()
>     .toolTokenLimitBeforeEvict(properties.tools().output().maxTokens())
>     .excludeTool("write_file")     // 写类工具不截断
>     .excludeTool("apply_patch")
>     .build();
> ```
>
> 若需要 per-tool 不同阈值,SAA 内置版本不支持,P1-3a 简化为"全局一个阈值 + 写类不截断"。
> 真要 per-tool,P2 再写一个自定义 ToolInterceptor 包装它。
---

## Task 7: ModelCallLimit — 复用 SAA 内置 ModelCallLimitHook(v3 完全删除自实现)

**Files:** 无新增文件。

> ⚠️ **v3 完全删除(外部审查 D4)**:SAA 1.1.2.3 已内置
> `com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook`,功能完全覆盖原 `BaBiQModelCallLimiterHook` 需求。
> 不再自写 Hook + 测试,**直接在 Task 10B `ReActStrategy.buildAgent()` 装配 SAA 内置实例**:
>
> ```java
> ModelCallLimitHook limiter = ModelCallLimitHook.builder()
>     .runLimit(properties.maxIterations())                       // 默认 20
>     .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)        // 超限抛异常,被 AgentLoop catch
>     .build();
> ```
>
> 装配 + 测试已合并到 Task 10B / Task 10C(单测验证超限后 turn 转 FAILED + emit 失败 Item)。
>
> 📌 **若日后 BaBiQ 需要在超限时推一个 `turnSummary` 协议事件**,可在 P1-3b 阶段写一个**纯薄包装**:
> `class BaBiQModelCallLimiterHook implements Hook { 委托 SAA ModelCallLimitHook + emit 自家事件 }`,但当前 P1-3a 不需要。

- [ ] **Step 7.1: 验证 SAA ModelCallLimitHook 在 classpath**

由 Task 1 Step 1.3 的 `AgentFrameworkSmokeTest` 顺带覆盖(已在 v3 验证类列表里加入 `ModelCallLimitHook`),本 Task 不再单独写测试。

---

## Task 8: Hook — BaBiQTokenUsageHook(SAA ModelHook,token 累计,为 P1-3b 留接口)

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/hook/BaBiQTokenUsageHook.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/hook/BaBiQTokenUsageHookTest.java`

> ⚠️ **目的**:每次 AFTER_MODEL 把本次调用的 input/output token 累计到本 Hook 实例(供 turn 结束时由 P1-3b 的 `TurnSummaryEmitter` 算 cost)。P1-3a 只做累计,**不**算 cost(D32 P1-3b 才做)。
>
> ⚠️ **v2 修订(C2 fix)**:继承 SAA `ModelHook` + `@HookPositions(AFTER_MODEL)`,
> 可直接传入 `ReactAgent.builder().hooks(...)`。`afterModel()` 从 state 提取 ChatResponse metadata 中的 token 用量并累加。
> 纯累计逻辑 `record()` / `snapshot()` 保留,供单测直接验证。

- [ ] **Step 8.1: 写失败测试**

Create `BaBiQTokenUsageHookTest.java`:
```java
package com.wzx.babiq.server.hook;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BaBiQTokenUsageHookTest {

    @Test
    void initial_usage_is_zero() {
        BaBiQTokenUsageHook hook = new BaBiQTokenUsageHook();
        assertThat(hook.totalPromptTokens()).isZero();
        assertThat(hook.totalCompletionTokens()).isZero();
    }

    @Test
    void accumulate_across_calls() {
        BaBiQTokenUsageHook hook = new BaBiQTokenUsageHook();
        hook.record(100, 50);
        hook.record(200, 80);
        assertThat(hook.totalPromptTokens()).isEqualTo(300);
        assertThat(hook.totalCompletionTokens()).isEqualTo(130);
    }

    @Test
    void snapshot_is_immutable() {
        BaBiQTokenUsageHook hook = new BaBiQTokenUsageHook();
        hook.record(10, 5);
        BaBiQTokenUsageHook.Snapshot snapshot = hook.snapshot();
        hook.record(20, 10);   // 后续修改不应影响 snapshot
        assertThat(snapshot.promptTokens()).isEqualTo(10);
        assertThat(snapshot.completionTokens()).isEqualTo(5);
    }

    @Test
    void negative_tokens_rejected() {
        BaBiQTokenUsageHook hook = new BaBiQTokenUsageHook();
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> hook.record(-1, 0));
    }
}
```

- [ ] **Step 8.2: 跑测试确认 FAIL**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=BaBiQTokenUsageHookTest"
cd ..
```

Expected: 编译失败。

- [ ] **Step 8.3: 实现 BaBiQTokenUsageHook**

```java
package com.wzx.babiq.server.hook;

import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

/**
 * Token 用量累计 Hook — AFTER_MODEL 阶段自动记录每次 LLM 调用的 token 消耗。
 *
 * <p>每次 LLM 调用返回后,从 OverAllState 拿 prompt/completion token
 * 累加到本 Hook。turn 结束时,由 P1-3b 的 TurnSummaryEmitter 读取 snapshot,
 * 配合 ModelMetadata 算出 cost,通过 TurnSummaryItem 推给客户端显示。</p>
 *
 * <p>P1-3a 阶段本类只做累计,不算 cost。snapshot 提供不可变快照供下游消费。</p>
 *
 * <p>继承 SAA {@link ModelHook},通过 {@code @HookPositions(AFTER_MODEL)}
 * 注册到 {@code ReactAgent.builder().hooks(...)},框架自动在每次 LLM 返回后回调。</p>
 *
 * <p>线程安全:用 LongAdder 而非 AtomicLong,因为 turn 内可能多线程并发
 * AFTER_MODEL(虽然 ReactAgent 通常串行)。</p>
 */
@HookPositions({HookPosition.AFTER_MODEL})
public final class BaBiQTokenUsageHook extends ModelHook {

    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();

    /**
     * AFTER_MODEL 回调 — 从 state 提取本次 LLM 返回的 token 用量并累加。
     *
     * <p>注意:若 state 中没有 token 信息(部分模型不返回),则本次不累加,
     * 不抛异常。P1-3b 阶段再决定是否 warn。</p>
     */
    @Override
    public CompletableFuture<Map<String, Object>> afterModel(
            OverAllState state, RunnableConfig config) {
        // 从 state 提取 token 用量(key 由 ReactAgent 写入)
        Object promptObj = state.value("llm.promptTokens").orElse(null);
        Object completionObj = state.value("llm.completionTokens").orElse(null);
        if (promptObj instanceof Number && completionObj instanceof Number) {
            record(((Number) promptObj).longValue(), ((Number) completionObj).longValue());
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * 记录一次 LLM 调用的 token 用量(也可由测试直接调用)。
     *
     * @param prompt 本次 prompt token 数(≥ 0)
     * @param completion 本次 completion token 数(≥ 0)
     * @throws IllegalArgumentException 任一参数为负数时抛出
     */
    public void record(long prompt, long completion) {
        if (prompt < 0 || completion < 0) {
            throw new IllegalArgumentException(
                "token 数必须 ≥ 0,实际 prompt=" + prompt + ",completion=" + completion);
        }
        promptTokens.add(prompt);
        completionTokens.add(completion);
    }

    /** 当前累计的 prompt token 总数。 */
    public long totalPromptTokens() {
        return promptTokens.sum();
    }

    /** 当前累计的 completion token 总数。 */
    public long totalCompletionTokens() {
        return completionTokens.sum();
    }

    /** 取不可变快照(供 P1-3b TurnSummaryEmitter 消费)。 */
    public Snapshot snapshot() {
        return new Snapshot(totalPromptTokens(), totalCompletionTokens());
    }

    /** 重置(turn 结束由 AgentLoop 调用)。 */
    public void reset() {
        promptTokens.reset();
        completionTokens.reset();
    }

    /** 不可变 token 快照。 */
    public record Snapshot(long promptTokens, long completionTokens) {
        /** 总 token 数(供成本估算)。 */
        public long total() {
            return promptTokens + completionTokens;
        }
    }
}
```

- [ ] **Step 8.4: 跑测试确认 PASS**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=BaBiQTokenUsageHookTest"
cd ..
```

Expected: 4 个测试全绿。

- [ ] **Step 8.5: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/hook/BaBiQTokenUsageHook.java
git add backend/src/test/java/com/wzx/babiq/server/hook/BaBiQTokenUsageHookTest.java
git commit -m "feat(p1-3a): 实现 BaBiQTokenUsageHook(SAA ModelHook,token 累计,为 P1-3b 留接口)"
```

---

## Task 9: HITL — 复用 SAA 原生 interrupt/resume(v3 完全重写)

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/agent/PendingApprovals.java`(threadId → InterruptionMetadata 短期记忆,供 resume 使用)
- Create: `backend/src/main/java/com/wzx/babiq/server/agent/ApprovalRequestPayload.java`(record:推送给客户端的审批请求载荷)
- Test: `backend/src/test/java/com/wzx/babiq/server/agent/PendingApprovalsTest.java`

> ⚠️ **v3 完全重写(外部审查 D6)**:**严禁**之前 v2 设计的 `ApprovalChannel + SynchronousQueue + 阻塞 Agent 线程**。
> 真实 SAA HITL 是**事件驱动 + checkpoint resume** 模型,不阻塞业务线程。
>
> **正确流程**:
> 1. Agent 跑到写类工具时,`HumanInTheLoopHook.builder().approvalOn("write_file", ...)` 触发**中断**;
> 2. `agent.invokeAndGetOutput(...)` / `agent.stream(...)` 返回的 `NodeOutput` 含 `InterruptionMetadata`(其中有 `toolFeedbacks` 列表);
> 3. AgentLoop **不阻塞**,而是把 metadata 缓存到 `PendingApprovals(threadId → metadata)`,
>    然后用 `ItemEmitter` 推 `approval/request` 给客户端,turn 状态转 `WAITING_APPROVAL`,Agent 线程**释放**;
> 4. 客户端发 `approval/respond` 时,`ApprovalRespondHandler` **手动用** `InterruptionMetadata.Builder` + `ToolFeedback.Builder` + `FeedbackResult.{APPROVED,REJECTED,EDITED}` 构造新的 `InterruptionMetadata`,再构造 `RunnableConfig.builder().threadId(...).addHumanFeedback(metadata).resume().build()`,**再次** `agent.invokeAndGetOutput(...)` 续跑(checkpoint 由 `MemorySaver` 保存);
> 5. resume 内部可能再次触发中断,循环直到 turn 完成。
>
> 关键好处:不阻塞线程池;支持长审批(用户睡一觉再决定也行,turn 状态由 MemorySaver 持久化);天然支持 edit(改参数后续跑)。

- [ ] **Step 9.1: 写 PendingApprovals 测试**

Create `PendingApprovalsTest.java`:
```java
package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class PendingApprovalsTest {

    @Test
    void put_and_take_returns_metadata() {
        PendingApprovals pending = new PendingApprovals();
        InterruptionMetadata metadata = Mockito.mock(InterruptionMetadata.class);

        pending.put("thr_x", metadata);
        assertThat(pending.take("thr_x")).isSameAs(metadata);
        // 拿出来后即移除
        assertThat(pending.take("thr_x")).isNull();
    }

    @Test
    void take_on_unknown_thread_returns_null() {
        PendingApprovals pending = new PendingApprovals();
        assertThat(pending.take("missing")).isNull();
    }
}
```

- [ ] **Step 9.2: 实现 PendingApprovals**

Create `PendingApprovals.java`:
```java
package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待审批中断元数据短期缓存 — threadId 维度。
 *
 * <p>v3 设计(外部审查 D6):SAA HITL 触发中断后,AgentLoop 把 InterruptionMetadata
 * 缓存到本 Map,供 ApprovalRespondHandler 在 resume 时取出 + 手动用 ToolFeedback.Builder 构造反馈。
 * 不再用 SynchronousQueue 阻塞,审批可跨长时间窗口完成。</p>
 *
 * <p>P1-3a 用进程内 ConcurrentHashMap;P1-3b/P2 接入持久化 Saver 后,本类可换为
 * 直接从 checkpoint 读取(本类此时降级或删除)。</p>
 */
@Component
public final class PendingApprovals {

    private final Map<String, InterruptionMetadata> byThread = new ConcurrentHashMap<>();

    /** 缓存一次中断元数据(覆盖同 threadId 的旧值)。 */
    public void put(String threadId, InterruptionMetadata metadata) {
        byThread.put(threadId, metadata);
    }

    /** 取出并移除元数据。 */
    public InterruptionMetadata take(String threadId) {
        return byThread.remove(threadId);
    }

    /** 仅查询,不移除(用于 turn/interrupt 时清理)。 */
    public InterruptionMetadata peek(String threadId) {
        return byThread.get(threadId);
    }

    public void remove(String threadId) {
        byThread.remove(threadId);
    }
}
```

- [ ] **Step 9.3: 实现 ApprovalRequestPayload**

Create `ApprovalRequestPayload.java`:
```java
package com.wzx.babiq.server.agent;

import java.util.List;

/**
 * 推送给客户端的审批请求载荷(approval/request notification 的 params)。
 *
 * <p>每个 InterruptionMetadata 可能包含多个 toolFeedback(批量工具调用),
 * 但 P1-3a 阶段先按 1 工具 1 请求处理,toolName + arguments 直接展开。</p>
 *
 * @param threadId   会话线程 id(供客户端 ack)
 * @param turnId     当前 turn id
 * @param itemId     该次审批的唯一 id(由 AgentLoop 生成,客户端 approval/respond 必须回传)
 * @param toolName   待审批工具名(D24 限定为 write_file / exec_shell / apply_patch)
 * @param arguments  工具入参 JSON 字符串(客户端展示给用户确认)
 * @param description 工具描述(由 HumanInTheLoopHook ToolConfig.description 来)
 */
public record ApprovalRequestPayload(
    String threadId,
    String turnId,
    String itemId,
    String toolName,
    String arguments,
    String description
) {}
```

- [ ] **Step 9.4: 给 ItemEmitter 新增 approval/request 发射方法**

> ⚠️ **必须在 Task 10B 创建 `ReActStrategy` 之前完成**。`ReActStrategy.emitApprovalRequests(...)` 会直接调用
> `emitter.emitApprovalRequest(payload)`,如果把该方法拖到 Task 11A 再补,Task 10C 编译 `AgentLoop + ReActStrategy`
> 时会因为 `ItemEmitter` 方法不存在而失败。

Modify `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`,新增 import:

```java
import com.wzx.babiq.server.agent.ApprovalRequestPayload;
```

在 `emitTurnFailed(...)` 后、私有辅助方法前新增:

```java
/**
 * 发射 approval/request JSON-RPC notification(P1-3a HITL 审批请求)。
 *
 * <p>审批请求不是 ThreadItem,不走 item/added;客户端通过 method=approval/request
 * 收到待审批工具调用,再用 approval/respond 回传用户决策。</p>
 *
 * @param payload 审批请求载荷
 * @throws IOException WebSocket 写入失败时抛出
 */
public void emitApprovalRequest(ApprovalRequestPayload payload) throws IOException {
    sendNotification("approval/request", payload);
}
```

- [ ] **Step 9.5: 跑测试 + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=PendingApprovalsTest"
cd ..
git add backend/src/main/java/com/wzx/babiq/server/agent/PendingApprovals.java
git add backend/src/main/java/com/wzx/babiq/server/agent/ApprovalRequestPayload.java
git add backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java
git add backend/src/test/java/com/wzx/babiq/server/agent/PendingApprovalsTest.java
git commit -m "feat(p1-3a): HITL 短期缓存 + 审批载荷(v3 改 interrupt/resume,D23+D24)"
```

> 📌 **HITL Hook 装配 + resume 调用**:都在 Task 10B `ReActStrategy.buildAgent()` 和 Task 11D `ApprovalRespondHandler` 完成,本 Task 不重复。

---

## Task 10: AgentLoop + ReActStrategy + AgentLoopProperties(D21 核心)

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoopProperties.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopLineCountTest.java`(D21 主流程行数硬卡)

> ⚠️ **D21 决策**:**`AgentLoop` 主流程 ≤50 行(实际逻辑,不计 import / class 头)**。横切关注点(限流 / 审批 / 截断 / token)全部用 Hook,**严禁** 在 loop 内写 if 判断。
>
> ⚠️ **API 名漂移预警**:`ReactAgent.builder()` 的 `hooks(...)` / `saver(...)` / `tools(...)` 签名可能与示例不同。Step 10.4 实现前先查 [java2ai.com](https://java2ai.com) 拿一手代码。

### 10A. AgentLoopProperties

- [ ] **Step 10A.1: 实现配置载体**

Create `AgentLoopProperties.java`:
```java
package com.wzx.babiq.server.agent;

import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Agent Loop 配置载体,绑定 `babiq.agent.*` 配置项。
 *
 * <p>设计:全部字段用 record 不可变;maxIterations 防 ReAct 死循环;
 * approvalPolicy / sandboxMode 分别对应 D24 / D31 决策;tools.output 是
 * Task 6 ToolOutputTruncationHook 的配置入口(per-tool override)。</p>
 *
 * @param maxIterations 单 turn 最大 LLM 调用次数(D21 兜底,推荐 20)
 * @param approvalPolicy 审批策略(NEVER / ON_REQUEST / ON_FAILURE),默认 ON_REQUEST
 * @param sandboxMode 沙箱模式,默认 WORKSPACE_WRITE
 * @param tools 工具相关配置
 */
@ConfigurationProperties(prefix = "babiq.agent")
public record AgentLoopProperties(
    int maxIterations,
    ApprovalPolicy approvalPolicy,
    SandboxMode sandboxMode,
    Tools tools
) {
    /** 提供默认值的构造器,Spring Boot 用反射调用。 */
    public AgentLoopProperties {
        if (maxIterations <= 0) maxIterations = 20;
        if (approvalPolicy == null) approvalPolicy = ApprovalPolicy.ON_REQUEST;
        if (sandboxMode == null) sandboxMode = SandboxMode.WORKSPACE_WRITE;
        if (tools == null) tools = new Tools(new Output(4000, Map.of()));
    }

    /** 工具配置子节。 */
    public record Tools(Output output) {}

    /**
     * 工具输出配置。
     *
     * @param defaultMaxTokens 默认 token 上限(默认 4000)
     * @param perTool 按工具名 override(如 read_file -> 8000)
     */
    public record Output(int defaultMaxTokens, Map<String, Integer> perTool) {
        public Output {
            if (defaultMaxTokens <= 0) defaultMaxTokens = 4000;
            if (perTool == null) perTool = Map.of();
        }
    }
}
```

- [ ] **Step 10A.2: 注册到 Spring Boot 配置扫描**

修改 `BaBiQApplication.java`,在 `@SpringBootApplication` 注解上方加:
```java
@EnableConfigurationProperties({BaBiQProperties.class, AgentLoopProperties.class})
```

(若 `@EnableConfigurationProperties` 已存在,把 `AgentLoopProperties.class` 加入数组。)

- [ ] **Step 10A.3: 加 application.yml 配置**

`backend/src/main/resources/application.yml` 的 `babiq:` 块下追加:
```yaml
babiq:
  # ... 原有 providers / memory / tools 不变
  agent:
    max-iterations: 20
    approval-policy: ON_REQUEST   # NEVER / ON_REQUEST / ON_FAILURE
    sandbox-mode: WORKSPACE_WRITE # READ_ONLY / WORKSPACE_WRITE / DANGER_FULL_ACCESS
    tools:
      output:
        default-max-tokens: 4000
        per-tool:
          read_file: 8000
          grep: 2000
```

### 10B. ReActStrategy(ReactAgent builder 封装)

> 📌 **v2 修订(M4/M5 fix)前置依赖**:`ReactAgent.builder().model(...)` 需要 raw `ChatModel`(不是包装后的 `ChatClient`);`tools(...)` 需要 `ToolCallback[]`(不是 `Tool` 集合)。
> 现有 `ChatClientFactory.resolve()` 只返回 `ChatClient`,`ToolRegistry` 只有 `get()/names()`。
> 因此需要先补两个 API:Step 10B.0a 给 `ChatClientFactory` 加 `resolveChatModel()`,Step 10B.0b 给 `ToolRegistry` 加 `allCallbacks()`。

- [ ] **Step 10B.0a: ChatClientFactory 新增 resolveChatModel(String providerId)**

> ⚠️ **修改既有 P1-2 文件**:`backend/src/main/java/com/wzx/babiq/server/model/ChatClientFactory.java`。
> 思路:把 `buildClient()` 中"取 ProviderConfig → 取 ProviderFactory → 构建 ChatModel"的前半段抽成 `resolveChatModel(providerId)` public 方法,buildClient 复用。返回 raw `ChatModel`,不挂 advisor(ReactAgent 自己管 memory)。

修改要点(伪代码,实际 diff 请在执行时基于现有代码生成):
```java
// ChatClientFactory.java 新增 public 方法
public ChatModel resolveChatModel(String providerId) {
    ModelProviderConfig providerConfig = registry.get(providerId);
    ProviderFactory providerFactory = factoriesByType.get(providerConfig.type());
    if (providerFactory == null) {
        throw new IllegalStateException("没有注册 ProviderFactory,type=" + providerConfig.type()
                + ",providerId=" + providerId);
    }
    return providerFactory.build(providerConfig);
}

// buildClient(...) 改为复用上面的方法,避免重复
private ChatClient buildClient(String providerId) {
    ChatModel chatModel = resolveChatModel(providerId);
    // 后续 advisor 包装逻辑不变
    ...
}
```

> 📌 **providerId 为 null 时**:沿用 `active()` 的语义 → `registry.active().id()`。建议 `resolveChatModel(null)` 等价于 `resolveChatModel(registry.active().id())`,与 `active()` 行为一致。

补充测试 `ChatClientFactoryTest`:
```java
@Test
void resolveChatModel_returns_raw_chatmodel_without_advisor() {
    ChatModel model = factory.resolveChatModel("dashscope-main");
    assertThat(model).isNotNull();
    // 不验证是否挂 advisor:ChatModel 接口本身没 advisor 概念,advisor 是 ChatClient 层
}
```

- [ ] **Step 10B.0b: ToolRegistry 新增 allCallbacks() 返回 ToolCallback[]**

> ⚠️ **修改既有 Task 4 文件**:`backend/src/main/java/com/wzx/babiq/server/tool/ToolRegistry.java`。
> 思路:用 Spring AI 的 `MethodToolCallbackProvider.builder().toolObjects(...)` 把所有 Tool bean 转成 ToolCallback 数组。前提是每个 Tool 的工具方法都加了 `@Tool` 注解(Task 5 已要求)。

修改要点:
```java
// ToolRegistry.java 新增字段 + 方法
private final ToolCallback[] callbacks;

public ToolRegistry(List<Tool> tools) {
    // 原构建索引逻辑保留
    this.byName = tools.stream().collect(toMap(Tool::name, t -> t));
    // 新增:用 Spring AI Provider 扫描 @Tool 注解,生成 ToolCallback[]
    this.callbacks = MethodToolCallbackProvider.builder()
            .toolObjects(tools.toArray())
            .build()
            .getToolCallbacks();
}

public ToolCallback[] allCallbacks() {
    return callbacks;
}
```

> ⚠️ **API 漂移预警**:Spring AI 1.1.x 的 `MethodToolCallbackProvider` 可能在 `org.springframework.ai.tool.method` 包下,且 `.toolObjects(Object...)` 接受可变参数。若实际 API 不同(如 `.toolBeans(...)` 或返回值类型不是 `ToolCallback[]`),按真实 API 调整。

补充测试 `ToolRegistryTest`:
```java
@Test
void allCallbacks_contains_all_registered_tools() {
    ToolRegistry registry = new ToolRegistry(List.of(readTool, writeTool, listDirTool));
    ToolCallback[] callbacks = registry.allCallbacks();
    assertThat(callbacks).hasSizeGreaterThanOrEqualTo(3);
    // ToolCallback.getToolDefinition().name() 应能从中找到 "read_file" 等
}
```

- [ ] **Step 10B.1: 实现 ReActStrategy**

> ⚠️ **v2 修订(C1/C2 fix)**:直接使用 SAA `ReactAgent.builder()`,不是 bare ChatClient。
> ReactAgent 内部管理 ReAct 循环(LLM→tool_calls→执行→回灌→再调 LLM),AgentLoop 无需手写 while。
>
> ⚠️ **API 漂移预警**:SAA 1.1.2.x 的 `ReactAgent.builder()` 签名可能与下方不同。
> **实现前先查 [java2ai.com/docs/frameworks/agent-framework/tutorials/hooks](https://java2ai.com) 拿一手 API**。
> 若签名不同按真实 API 调整,核心契约不变:挂 Hook + 工具集 + ChatModel + MemorySaver。

Create `ReActStrategy.java`:
```java
package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.LargeResultEvictionInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.hook.BaBiQTokenUsageHook;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * ReAct 策略 — 把 SAA ReactAgent + Hook + Interceptor + 工具 + ChatModel 装配成可执行实体。
 *
 * <p>v3 修订:
 * - ModelCallLimit 改用 SAA 内置 {@link ModelCallLimitHook}(删自写)
 * - 工具输出截断改用 SAA 内置 {@link LargeResultEvictionInterceptor}(删自写)
 * - HITL 走 SAA 原生 interrupt/resume + {@link MemorySaver}(不再用 SynchronousQueue)
 * - 沙箱拦截在 ToolInterceptor 层做(不在 Hook 层做)</p>
 *
 * <p>⚠️ API 漂移:若 SAA 1.1.2.3 的 ReactAgent.builder() / Interceptor builder 签名与下方不同,按真实 API 调整。</p>
 */
@Component
public final class ReActStrategy {

    private final ChatClientFactory chatClientFactory;
    private final ToolRegistry toolRegistry;
    private final AgentLoopProperties properties;
    private final BaBiQSandboxInterceptor sandboxInterceptor;
    private final BaBiQTokenUsageHook tokenUsageHook;
    private final MemorySaver memorySaver;

    public ReActStrategy(ChatClientFactory chatClientFactory,
                         ToolRegistry toolRegistry,
                         AgentLoopProperties properties,
                         BaBiQSandboxInterceptor sandboxInterceptor,
                         BaBiQTokenUsageHook tokenUsageHook) {
        this.chatClientFactory = chatClientFactory;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.sandboxInterceptor = sandboxInterceptor;
        this.tokenUsageHook = tokenUsageHook;
        // D23:MemorySaver 用于 HITL checkpoint(中断→resume)
        this.memorySaver = new MemorySaver();
    }

    /**
     * 为一次 turn 装配 ReactAgent 实例。
     *
     * @param providerId 本轮使用的 provider(null = 用 active 默认)
     * @return 已挂 Hook + Interceptor 的 ReactAgent
     */
    public ReactAgent buildAgent(String providerId) {
        ChatModel chatModel = chatClientFactory.resolveChatModel(providerId);
        ToolCallback[] tools = toolRegistry.allCallbacks();

        // D23/D24:SAA 内置 HumanInTheLoopHook,声明式配置需要审批的写类工具
        HumanInTheLoopHook hitlHook = HumanInTheLoopHook.builder()
            .approvalOn("write_file", ToolConfig.builder().description("写入文件,请确认").build())
            .approvalOn("exec_shell", ToolConfig.builder().description("执行 Shell 命令,请确认").build())
            .approvalOn("apply_patch", ToolConfig.builder().description("应用补丁,请确认").build())
            .build();

        // D21:防 ReAct 死循环,复用 SAA 内置 ModelCallLimitHook
        ModelCallLimitHook limitHook = ModelCallLimitHook.builder()
            .runLimit(properties.maxIterations())
            .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
            .build();

        // D19:工具输出截断,复用 SAA 内置 LargeResultEvictionInterceptor
        // ⚠️ builder 签名(.maxTokens / .perToolOverride 等)以 javap 实际为准
        var largeResultInterceptor = LargeResultEvictionInterceptor.builder()
            .defaultMaxTokens(properties.tools().output().maxTokens())
            .perToolOverride(properties.tools().output().perToolOverride())
            .build();

        return ReactAgent.builder()
            .name("babiq_agent")
            .model(chatModel)
            .tools(tools)
            .hooks(hitlHook, limitHook, tokenUsageHook)
            .interceptors(sandboxInterceptor, largeResultInterceptor)
            .saver(memorySaver)
            .build();
    }

    /**
     * 构造 turn 级 RunnableConfig(threadId 用于跨轮记忆)。
     */
    public RunnableConfig buildConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /**
     * 从 NodeOutput 中取出 final AssistantMessage(供 AgentLoop 推 agentMessage)。
     *
     * <p>⚠️ API 漂移:NodeOutput 取消息的 API 在 1.1.2.3 应为 `node.state().value("messages")`
     * 取最后一条 AssistantMessage;具体以 javap 为准。</p>
     */
    public AssistantMessage extractAssistantMessage(NodeOutput node) {
        // 占位伪代码:实现时按 SAA 真实 API 提取
        return (AssistantMessage) node.state().value("messages")
            .map(msgs -> ((java.util.List<?>) msgs).get(((java.util.List<?>) msgs).size() - 1))
            .orElseThrow(() -> new IllegalStateException("NodeOutput 内未取到 AssistantMessage"));
    }

    /**
     * 把 InterruptionMetadata 中的 toolFeedbacks 展开为 approval/request 通知推给客户端。
     *
     * <p>v4 修订(外部审查 M5):不再用 `ApprovalRequestItem`(sealed ThreadItem 没有该子类)。
     * 改为调 `ItemEmitter.emitApprovalRequest(...)` 方法直接推 JSON-RPC notification(method=approval/request)。
     * 该方法已在 Task 9.4 提前补到 ItemEmitter,避免 Task 10B/10C 编译失败。</p>
     *
     * <p>P1-3a:1 个 toolFeedback 推 1 个 approval/request。多工具 batch P2 再优化。</p>
     */
    public void emitApprovalRequests(Turn turn, ItemEmitter emitter, InterruptionMetadata metadata) throws Exception {
        for (var feedback : metadata.toolFeedbacks()) {
            ApprovalRequestPayload payload = new ApprovalRequestPayload(
                turn.threadId(), turn.id(),
                "appr_" + java.util.UUID.randomUUID().toString().substring(0, 8),
                feedback.getName(),
                feedback.getArguments() == null ? "" : feedback.getArguments(),
                "请确认是否执行该工具调用"
            );
            // v4:走 notification 而非 item/added,因为审批不是 ThreadItem(没有持久化语义)
            emitter.emitApprovalRequest(payload);
        }
    }

    public ToolRegistry toolRegistry() { return toolRegistry; }
    public AgentLoopProperties properties() { return properties; }
}
```

> 📌 **v4 修订需同步给 ItemEmitter(P1-1 既有类)新增方法**:
> ```java
> /** 发射 approval/request JSON-RPC notification(v4 新增,P1-3a HITL 需要)。 */
> public void emitApprovalRequest(ApprovalRequestPayload payload) throws IOException {
>     sendNotification("approval/request", payload);
> }
> ```
> ItemEmitter 已有私有 `sendNotification(String method, Object params)`,直接复用。
> 上述方法已在 Task 9.4 提前补到 `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`,
> 不要拖到 Task 11A,否则 Task 10B 的 `ReActStrategy` 会先编译失败。

### 10C. AgentLoop 主流程(≤50 行硬约束)

- [ ] **Step 10C.1: 写行数硬卡测试**

Create `AgentLoopLineCountTest.java`:
```java
package com.wzx.babiq.server.agent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D21 决策硬卡:AgentLoop 主流程 ≤50 行实际逻辑。
 * 整文件 ≤100 行(含 import / class 头 / JavaDoc / 空行)。
 */
class AgentLoopLineCountTest {

    @Test
    void agent_loop_file_must_be_at_most_100_lines() throws Exception {
        var resource = new FileSystemResource(
            "src/main/java/com/wzx/babiq/server/agent/AgentLoop.java");
        long totalLines;
        try (var br = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            totalLines = br.lines().count();
        }
        assertThat(totalLines)
            .as("D21: AgentLoop 主流程必须保持精简")
            .isLessThanOrEqualTo(100L);
    }
}
```

- [ ] **Step 10C.2: 实现 AgentLoop**

> 📌 **v3 修订(外部审查 D7)Agent 调用 API**:
> 用 **`agent.invokeAndGetOutput(input, config)`** 替代 `agent.call(...)`,返回 `Optional<NodeOutput>`,
> 若包含 `InterruptionMetadata` 说明 HITL 触发了中断 → AgentLoop 缓存 metadata + 推 `approval/request` + 把 turn 转 `WAITING_APPROVAL` + **立即返回**(线程释放,不阻塞);
> 否则取最终 AssistantMessage 走完成流程。
>
> 📌 **v2 修订(M3 fix)ItemEmitter 生命周期**:`ItemEmitter` 是 **per-session per-turn** 短生命周期,
> 由 `TurnStartHandler` 构造后通过参数传给 `AgentLoop.invoke(...)`。AgentLoop 是 singleton @Component。

Create `AgentLoop.java`:
```java
package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent Loop 主入口 — D21 决策:主流程 ≤50 行实际逻辑。
 *
 * <p>横切关注点(限流 / 审批 / 截断 / token 统计)全部走 SAA Hook 和 Interceptor,
 * 本类只做"接收 user input → 构建 ReactAgent → 调 invokeAndGetOutput → 发 Item → 完成/中断"骨架。</p>
 *
 * <p>v3 修订(外部审查 D7)Agent 调用方式:用 SAA `agent.invokeAndGetOutput()` 拿
 * NodeOutput,若 `InterruptionMetadata` 存在则 HITL 触发了中断,本方法不阻塞,
 * 把 metadata 缓存到 PendingApprovals,推 approval/request 给客户端,turn 转
 * WAITING_APPROVAL,**线程立即释放**。客户端 approval/respond 由 ApprovalRespondHandler
 * 触发 RunnableConfig.addHumanFeedback.resume + 再次 invokeAndGetOutput,提交到 TurnExecutor 走新一轮 invoke。</p>
 */
@Component
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ReActStrategy strategy;
    private final PendingApprovals pendingApprovals;

    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals) {
        this.strategy = strategy;
        this.pendingApprovals = pendingApprovals;
    }

    /**
     * 在 turn 上执行一次 ReAct 调用(可能完成、可能因 HITL 中断挂起)。
     *
     * @param turn       已 transition 到 RUNNING 的 Turn
     * @param userText   用户输入文本
     * @param providerId 本轮 provider id(null 用 active 默认)
     * @param emitter    本 turn 专属的 ItemEmitter
     */
    public void invoke(Turn turn, String userText, String providerId, ItemEmitter emitter) {
        try {
            emitter.emitItemAdded(new UserMessageItem(newId("it_"), "userMessage", userText));
            ReactAgent agent = strategy.buildAgent(providerId);
            RunnableConfig config = strategy.buildConfig(turn.threadId());

            Optional<NodeOutput> output = agent.invokeAndGetOutput(Map.of("messages", userText), config);
            handleOutput(turn, emitter, output);
        } catch (Exception ex) {
            log.error("Agent Loop 执行失败 turnId={}", turn.id(), ex);
            turn.fail(ex.getMessage());
            tryEmit(() -> emitter.emitTurnFailed(ex.getMessage()));
        }
    }

    /**
     * 解析 NodeOutput:正常完成 vs HITL 中断 vs 异常。
     *
     * <p>v4 修订(外部审查):NodeOutput 上没有 .interruption() 方法。
     * InterruptionMetadata extends NodeOutput,用 instanceof 判别即可。</p>
     */
    private void handleOutput(Turn turn, ItemEmitter emitter, Optional<NodeOutput> output) throws Exception {
        if (output.isEmpty()) {
            throw new IllegalStateException("ReactAgent 返回空 NodeOutput,turnId=" + turn.id());
        }
        NodeOutput node = output.get();
        if (node instanceof InterruptionMetadata interruption) {
            handleInterruption(turn, emitter, interruption);
            return;
        }
        AssistantMessage assistant = strategy.extractAssistantMessage(node);
        emitter.emitItemAdded(new AgentMessageItem(newId("it_"), "agentMessage", assistant.getText()));
        turn.complete();
        emitter.emitTurnCompleted("completed");
    }

    /** HITL 中断:缓存 metadata + emit approval/request + turn 转 WAITING_APPROVAL,线程释放。 */
    private void handleInterruption(Turn turn, ItemEmitter emitter, InterruptionMetadata metadata) throws Exception {
        pendingApprovals.put(turn.threadId(), metadata);
        turn.waitApproval();
        // 把 metadata 中的工具调用展开为 approval/request 推送
        // ⚠️ 具体 toolFeedbacks() 取出 toolName / arguments / description 的 API
        //    按 SAA 1.1.2.3 实际方法名调整(getName / getArguments / getDescription)
        strategy.emitApprovalRequests(turn, emitter, metadata);
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static void tryEmit(IoRunnable action) {
        try { action.run(); } catch (Exception ignore) { log.warn("turn/failed 通知发送失败"); }
    }

    @FunctionalInterface
    private interface IoRunnable { void run() throws Exception; }
}
```

> 📌 **v3 修订要点**:
> - 用 `agent.invokeAndGetOutput()` 而非 `agent.call()`,返回 `Optional<NodeOutput>`;
> - 检测 `instanceof InterruptionMetadata` 判定是否 HITL(NodeOutput 上没有 .interruption() 方法,InterruptionMetadata extends NodeOutput);
> - **不阻塞线程等审批**,缓存 metadata 后立刻 emit + 返回;
> - turn 状态机用 `Turn.waitApproval()`,与 P1-1 既有状态机对齐。
>
> ⚠️ **API 漂移**:`extractAssistantMessage()` / `emitApprovalRequests()` 是
> 的实际签名以 SAA 1.1.2.3 javap 为准。`extractAssistantMessage` 和 `emitApprovalRequests` 是
> **本 plan 在 ReActStrategy 内补的薄助手方法**,把 SAA NodeOutput 解析的脏活集中处理,
> AgentLoop 主流程保持精简。

- [ ] **Step 10C.3: 写集成测试(mock ChatModel)**

Create `AgentLoopTest.java`:
```java
package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AgentLoop 单元测试 — 验证主流程的 emit 顺序和状态转换。
 *
 * <p>v4 修订(外部审查 M10):
 * - 构造函数改为 new AgentLoop(strategy, pendingApprovals)
 * - mock 改为 agent.invokeAndGetOutput(...) 返回 Optional<NodeOutput>(NodeOutput stub 不带 interruption)
 * - 用 strategy.extractAssistantMessage(node) 拿最终消息</p>
 */
class AgentLoopTest {

    private final List<ThreadItem> emitted = new ArrayList<>();

    private ItemEmitter capturingEmitter() throws Exception {
        ItemEmitter emitter = mock(ItemEmitter.class);
        doAnswer(inv -> { emitted.add(inv.getArgument(0)); return null; })
            .when(emitter).emitItemAdded(any(ThreadItem.class));
        return emitter;
    }

    @BeforeEach
    void setUp() { emitted.clear(); }

    @Test
    void invoke_emits_user_then_agent_messages() throws Exception {
        // arrange:mock NodeOutput(非 InterruptionMetadata)+ strategy.extractAssistantMessage 返回固定文本
        NodeOutput nodeOutput = mock(NodeOutput.class);
        ReactAgent mockAgent = mock(ReactAgent.class);
        when(mockAgent.invokeAndGetOutput(any(java.util.Map.class), any(RunnableConfig.class)))
            .thenReturn(java.util.Optional.of(nodeOutput));

        ReActStrategy strategy = mock(ReActStrategy.class);
        when(strategy.buildAgent(any())).thenReturn(mockAgent);
        when(strategy.buildConfig(anyString()))
            .thenReturn(RunnableConfig.builder().threadId("thr_test").build());
        when(strategy.extractAssistantMessage(nodeOutput))
            .thenReturn(new AssistantMessage("fixed reply"));

        Turn turn = new Turn("turn_test", "thr_test");
        turn.start();
        AgentLoop loop = new AgentLoop(strategy, new PendingApprovals());

        // act
        loop.invoke(turn, "hello", null, capturingEmitter());

        // assert:emit 顺序 userMessage → agentMessage
        assertThat(emitted).hasSize(2);
        assertThat(emitted.get(0).type()).isEqualTo("userMessage");
        assertThat(emitted.get(1).type()).isEqualTo("agentMessage");
        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void invoke_marks_turn_failed_on_exception() throws Exception {
        ReactAgent mockAgent = mock(ReactAgent.class);
        when(mockAgent.invokeAndGetOutput(any(java.util.Map.class), any(RunnableConfig.class)))
            .thenThrow(new RuntimeException("model crashed"));

        ReActStrategy strategy = mock(ReActStrategy.class);
        when(strategy.buildAgent(any())).thenReturn(mockAgent);
        when(strategy.buildConfig(anyString()))
            .thenReturn(RunnableConfig.builder().threadId("thr_test").build());

        Turn turn = new Turn("turn_fail", "thr_test");
        turn.start();
        AgentLoop loop = new AgentLoop(strategy, new PendingApprovals());

        loop.invoke(turn, "boom", null, capturingEmitter());

        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.failureReason()).contains("model crashed");
    }
}
```

> ⚠️ **API 漂移**:`ReactAgent.call()` 的返回值类型需查实际 SAA API。
> 若返回 `String` 而非 `AssistantMessage`,调整 mock 和 AgentLoop.invoke() 中的取值方式。

- [ ] **Step 10C.4: 跑测试 + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=AgentLoopTest,AgentLoopLineCountTest"
cd ..
git add backend/src/main/java/com/wzx/babiq/server/agent/
git add backend/src/main/resources/application.yml
git add backend/src/main/java/com/wzx/babiq/server/BaBiQApplication.java
git add backend/src/test/java/com/wzx/babiq/server/agent/
git commit -m "feat(p1-3a): 实现 AgentLoop + ReActStrategy(D21 主流程精简)"
```

---

## Task 11: 协议层真实化(api/method/ 切 mock → 真实)

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`(P1-1 mock → 调 AgentLoop)
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnInterruptHandler.java`(P1-1 mock → set flag)
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ApprovalRespondHandler.java`(P1-1 mock → 手动 ToolFeedback.Builder + RunnableConfig.addHumanFeedback.resume,v4)
- Create: `backend/src/main/java/com/wzx/babiq/server/agent/TurnExecutor.java`(异步 invoke + interrupt flag 管理)
- Test: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerIT.java`(更新)
- Test: `backend/src/test/java/com/wzx/babiq/server/api/method/ApprovalRespondHandlerTest.java`

### 11A. TurnExecutor — 异步执行 + interrupt

- [ ] **Step 11A.1: 实现 TurnExecutor**

> ⚠️ **关键设计**:`turn/start` 同步返回 turnId,Agent 工作真正跑在独立线程;`turn/interrupt` 通过 `interrupt flag` 让 Hook 在 BEFORE_MODEL/BEFORE_TOOL 抛 InterruptedException 优雅停下。
>
> 📌 **v2 修订(M3 fix)**:`submit()` 签名增加 `ItemEmitter emitter` 参数。emitter 由 TurnStartHandler 用当前 session 构造,生命周期与 turn 严格绑定。AgentLoop 是 singleton,无法注入 emitter。

Create `TurnExecutor.java`:
```java
package com.wzx.babiq.server.agent;

import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Turn 执行器 — 把 AgentLoop.invoke() 包到独立线程并管理 interrupt flag。
 *
 * <p>设计:每个 turn 起一个 Future,interrupt 时调 Future.cancel(true) 让线程抛
 * InterruptedException;同时设置 interrupt flag,Hook 在 BEFORE_MODEL/BEFORE_TOOL
 * 主动检查 flag 提前 return,保证 SAA 内部不响应中断时也能优雅停。</p>
 *
 * <p>P1-3a 用 cachedThreadPool,turn 并发 1 万级别就需要换有界队列 + 拒绝策略。</p>
 */
@Component
public final class TurnExecutor {

    private static final Logger log = LoggerFactory.getLogger(TurnExecutor.class);

    private final AgentLoop agentLoop;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        java.lang.Thread t = new java.lang.Thread(r);
        t.setName("babiq-agent-" + t.getId());
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Future<?>> runningTurns = new ConcurrentHashMap<>();
    private final Map<String, Boolean> interruptFlags = new ConcurrentHashMap<>();

    public TurnExecutor(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    /**
     * 异步启动 Agent 执行(本方法不阻塞)。
     *
     * @param turn       已 transition 到 RUNNING 的 Turn
     * @param userText   用户输入
     * @param providerId 本轮 provider id(null = active)
     * @param emitter    本 turn 专属的 ItemEmitter(由 TurnStartHandler 构造)
     */
    public void submit(Turn turn, String userText, String providerId, ItemEmitter emitter) {
        interruptFlags.put(turn.id(), false);
        Future<?> future = executor.submit(() -> {
            try {
                agentLoop.invoke(turn, userText, providerId, emitter);
            } finally {
                runningTurns.remove(turn.id());
                interruptFlags.remove(turn.id());
            }
        });
        runningTurns.put(turn.id(), future);
    }

    /**
     * 中断正在跑的 Turn。
     *
     * @param turnId 目标 turnId
     * @return 是否成功标记中断
     */
    public boolean interrupt(String turnId) {
        Future<?> future = runningTurns.get(turnId);
        if (future == null) {
            log.warn("turn/interrupt 找不到运行中的 turn,可能已结束: turnId={}", turnId);
            return false;
        }
        interruptFlags.put(turnId, true);
        future.cancel(true);
        return true;
    }

    /** Hook 在 BEFORE_MODEL / BEFORE_TOOL 主动检查。 */
    public boolean isInterrupted(String turnId) {
        return Boolean.TRUE.equals(interruptFlags.get(turnId));
    }
}
```

### 11B. TurnStartHandler 切真实

- [ ] **Step 11B.1: 修改 TurnStartHandler.java**

> 📌 **v2 修订(M6 fix)**:对齐 P1-1 已实现的 handler 接口签名 `Object handle(JsonNode params, WebSocketSession session)`,**不**使用 `JsonRpcMessage.Request` 包装。

把原来 P1-1 跑 mock 流的逻辑替换为提交 AgentLoop。`handle()` 方法体:

```java
@Override
public Object handle(JsonNode params, WebSocketSession session) {
    String threadId = requiredText(params, "threadId");
    String userText = requiredInputText(params);
    String providerId = optionalText(params, "providerId");  // 可选,null = active

    Turn turn;
    try {
        turn = conversationService.startTurn(threadId);
    } catch (IllegalArgumentException exception) {
        throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
    }
    turn.start();

    // ⭐ M3 fix:ItemEmitter per session+turn,在这里构造后传给 TurnExecutor
    ItemEmitter emitter = new ItemEmitter(session, objectMapper, threadId, turn.id());
    try {
        emitter.emitTurnStarted();
    } catch (Exception ex) {
        log.error("发送 turn/started 失败: turnId={}", turn.id(), ex);
        // 协议层失败:不影响主流程,Agent 仍执行
    }

    // 异步执行,Item 由 emitter 推到同一 WebSocket session
    turnExecutor.submit(turn, userText, providerId, emitter);
    return Map.of("turnId", turn.id());
}

// 新增 optionalText 辅助(在已有 requiredText/requiredInputText 旁边)
private String optionalText(JsonNode params, String fieldName) {
    if (params == null || !params.hasNonNull(fieldName)) return null;
    String value = params.get(fieldName).asText();
    return value.isBlank() ? null : value;
}
```

> 📌 **构造函数变化**:删除 `@Value("${babiq.protocol.mock-agent-text:...}")` 参数,新增 `TurnExecutor turnExecutor` 依赖。`mockAgentText` 字段同步删除。
>
> 📌 **runMockStream / markTurnFailedIfPossible / emitTurnFailedIfPossible 三个私有方法删除**:Agent 失败现在由 AgentLoop catch + emitter.emitTurnFailed 处理。

### 11C. TurnInterruptHandler 切真实

- [ ] **Step 11C.1: 修改 TurnInterruptHandler.java**

> 📌 **v2 修订(M6 fix)**:对齐 P1-1 handler 签名,直接返回 `Object`(dispatcher 写入 Response.result)。

```java
@Override
public Object handle(JsonNode params, WebSocketSession session) {
    String turnId = requiredText(params, "turnId");
    boolean accepted = turnExecutor.interrupt(turnId);
    if (!accepted) {
        throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
            "turnId 不存在或已结束: " + turnId);
    }
    return Map.of("accepted", true);
    // 实际 turn/completed (status="interrupted") 由 AgentLoop catch InterruptedException 时发
}

private String requiredText(JsonNode params, String fieldName) {
    if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
        throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
    }
    return params.get(fieldName).asText();
}
```

### 11D. ApprovalRespondHandler 切真实

- [ ] **Step 11D.1: 修改 ApprovalRespondHandler.java**

> 📌 **v4 完全重写(外部审查)— javap 实地核对的真实 API**:
> - **没有 `HITLHelper`**:手动用 `InterruptionMetadata.builder()` + `ToolFeedback.builder()` + `FeedbackResult.{APPROVED,REJECTED,EDITED}` 构造。
> - **没有 `CompiledGraph.resume()`**:用 `RunnableConfig.builder().addHumanFeedback(metadata).resume().build()` 构造续跑配置,然后**再次调 `agent.invokeAndGetOutput(input, config)`**(同一 threadId,因为 MemorySaver 已存了 checkpoint)。
> - **`ConversationService.findTurn` 签名**:只有 `findTurn(String turnId)`(P1-1 现有 API),没有 `findTurn(threadId, turnId)`。
>
> 流程:取出缓存 metadata → 用 ToolFeedback.Builder 构造反馈 → 包进 InterruptionMetadata.builder() → 提交给 TurnExecutor.submitResume(),那里再用新 RunnableConfig.addHumanFeedback().resume() 重新 invoke。

```java
@Override
public Object handle(JsonNode params, WebSocketSession session) {
    String threadId = requiredText(params, "threadId");
    String turnId = requiredText(params, "turnId");
    String itemId = requiredText(params, "itemId");
    String decisionStr = requiredText(params, "decision");
    String editedArgs = optionalText(params, "editedArgs");

    // 1) 取出 HITL 中断元数据(由 AgentLoop 缓存)
    InterruptionMetadata original = pendingApprovals.take(threadId);
    if (original == null) {
        throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
            "未找到待审批的中断元数据,可能 turn 已完成或超时:threadId=" + threadId);
    }

    // 2) 手动构造反馈 metadata(无 HITLHelper)
    InterruptionMetadata feedback = buildFeedback(original, decisionStr, editedArgs);

    // 3) 查 turn(P1-1 实际 API 是 findTurn(turnId);不要写 findTurn(threadId, turnId))
    Turn turn = conversationService.findTurn(turnId)
        .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
            "turn 不存在:" + turnId));
    turn.resume();

    // 4) 提交给 TurnExecutor 异步续跑(不阻塞 handler 线程)
    ItemEmitter emitter = new ItemEmitter(session, objectMapper, threadId, turnId);
    turnExecutor.submitResume(turn, feedback, emitter);

    return Map.of("delivered", true);
}

/** v4 手动构造 InterruptionMetadata(无 HITLHelper)。 */
private static InterruptionMetadata buildFeedback(
        InterruptionMetadata original, String decisionStr, String editedArgs) {
    InterruptionMetadata.Builder builder = InterruptionMetadata.builder(original);
    for (InterruptionMetadata.ToolFeedback tf : original.toolFeedbacks()) {
        InterruptionMetadata.ToolFeedback.Builder tfb = InterruptionMetadata.ToolFeedback.builder(tf);
        switch (decisionStr.toLowerCase()) {
            case "approve" -> tfb.result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED);
            case "deny" -> tfb.result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED)
                              .description("用户拒绝");
            case "edit" -> tfb.result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED)
                              .arguments(editedArgs);
            default -> throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                "未知 decision: " + decisionStr);
        }
        builder.addToolFeedback(tfb.build());
    }
    return builder.build();
}

// requiredText / optionalText 辅助同 TurnStartHandler
```

> 📌 **TurnExecutor 新增方法 `submitResume(turn, feedback, emitter)`**:
> ```java
> public void submitResume(Turn turn, InterruptionMetadata feedback, ItemEmitter emitter) {
>     executor.submit(() -> agentLoop.invokeResume(turn, feedback, emitter));
> }
> ```
> AgentLoop 增加 `invokeResume(turn, feedback, emitter)` 方法,内部构造 RunnableConfig 时加
> `.addHumanFeedback(feedback).resume()`,然后再调 `agent.invokeAndGetOutput(...)`,
> NodeOutput 处理逻辑(再次中断 / 正常完成)与首次 invoke 完全一致 — 可以复用同一个 `handleOutput()`。
>
> ⚠️ **真实 API 全名(已 javap 核对 1.1.2.3)**:
> - `com.alibaba.cloud.ai.graph.action.InterruptionMetadata.Builder` + `addToolFeedback(ToolFeedback)`
> - `com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback.Builder.{result,arguments,description,build}()`
> - `com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult.{APPROVED,REJECTED,EDITED}`
> - `com.alibaba.cloud.ai.graph.RunnableConfig.Builder.addHumanFeedback(InterruptionMetadata).resume()`

### 11E. 集成测试 + Commit

- [ ] **Step 11E.1: 跑全部 method handler 测试**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=*HandlerTest,*HandlerIT,JsonRpcWebSocketHandlerIT"
cd ..
```

Expected: 全绿(原 P1-1 测试 + 新增 ApprovalRespondHandlerTest)。

- [ ] **Step 11E.2: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/agent/TurnExecutor.java
git add backend/src/main/java/com/wzx/babiq/server/api/method/
git add backend/src/test/java/com/wzx/babiq/server/api/method/
git commit -m "feat(p1-3a): 协议层 3 个 handler 切 mock → 真实(D23 + turn/interrupt)"
```

---

## Task 12: 沙箱三档模式回归测试

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/sandbox/SandboxModeRegressionTest.java`

> ⚠️ **D31 决策硬验**:三档沙箱(READ_ONLY / WORKSPACE_WRITE / DANGER_FULL_ACCESS)行为各跑一个端到端用例。

- [ ] **Step 12.1: 写三档模式回归测试**

```java
package com.wzx.babiq.server.sandbox;

import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 沙箱三档模式回归测试(D31 决策硬验)。
 *
 * <p>v4 修订(外部审查 M8):工具已纯 IO 化,沙箱由 BaBiQSandboxInterceptor 统一处理。
 * 因此回归测试改为直接验证 interceptor 的 checkOrReject 路径,而不再 new WriteFileTool。
 * symlink 攻击测试保留对 PathGuard 的单元验证(底层算法层)。</p>
 */
class SandboxModeRegressionTest {

    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "exec_shell", "apply_patch");

    @Test
    void read_only_rejects_write(@TempDir Path tmp) throws Exception {
        var policy = new SandboxPolicy(SandboxMode.READ_ONLY, List.of(tmp.toRealPath()));
        var guard = new PathGuard(List.of(tmp.toRealPath()));
        var interceptor = new BaBiQSandboxInterceptor(guard, policy, WRITE_TOOLS);

        String rejection = interceptor.checkOrReject("write_file", tmp.resolve("a.txt").toString());
        assertThat(rejection).isNotNull().containsAnyOf("read-only", "read_only");
    }

    @Test
    void workspace_write_allows_within_cwd(@TempDir Path tmp) throws Exception {
        var policy = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(tmp.toRealPath()));
        var guard = new PathGuard(List.of(tmp.toRealPath()));
        var interceptor = new BaBiQSandboxInterceptor(guard, policy, WRITE_TOOLS);

        String rejection = interceptor.checkOrReject("write_file", tmp.resolve("b.txt").toString());
        assertThat(rejection).isNull();
    }

    @Test
    void workspace_write_rejects_outside_cwd(@TempDir Path tmp) throws Exception {
        var policy = new SandboxPolicy(SandboxMode.WORKSPACE_WRITE, List.of(tmp.toRealPath()));
        var guard = new PathGuard(List.of(tmp.toRealPath()));
        var interceptor = new BaBiQSandboxInterceptor(guard, policy, WRITE_TOOLS);

        Path outside = java.nio.file.Files.createTempDirectory("babiq-other");
        String rejection = interceptor.checkOrReject("write_file", outside.resolve("c.txt").toString());
        assertThat(rejection).isNotNull().containsAnyOf("outside", "拒绝", "violation");
    }

    @Test
    void danger_full_access_allows_anywhere(@TempDir Path tmp) throws Exception {
        // DANGER_FULL_ACCESS:Interceptor 直接 return null,根本不走 PathGuard 白名单
        var policy = new SandboxPolicy(SandboxMode.DANGER_FULL_ACCESS, List.of());
        var guard = new PathGuard(List.of());
        var interceptor = new BaBiQSandboxInterceptor(guard, policy, WRITE_TOOLS);

        Path anywhere = java.nio.file.Files.createTempDirectory("babiq-danger");
        String rejection = interceptor.checkOrReject("write_file", anywhere.resolve("d.txt").toString());
        assertThat(rejection).isNull();
    }

    @Test
    void path_guard_rejects_symlink_traversal(@TempDir Path tmp) throws Exception {
        // 底层 PathGuard 单测:符号链接逃逸必须被 toRealPath() 揭穿
        PathGuard guard = new PathGuard(List.of(tmp.toRealPath()));

        Path target = java.nio.file.Files.createTempDirectory("babiq-symlink-target");
        Path link = tmp.resolve("escape");
        try {
            java.nio.file.Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "符号链接创建失败,跳过");
            return;
        }

        org.junit.jupiter.api.Assertions.assertThrows(
            SandboxViolationException.class,
            () -> guard.checkWrite(link.resolve("evil.txt").toString()));
    }
}
```

- [ ] **Step 12.2: 跑 + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=SandboxModeRegressionTest"
cd ..
git add backend/src/test/java/com/wzx/babiq/server/sandbox/SandboxModeRegressionTest.java
git commit -m "test(p1-3a): 沙箱三档模式回归测试(含符号链接攻击防护)"
```

---

## Task 13: 端到端烟测(wscat + 真模型或 mock)

**Files:**
- Create: `docs/superpowers/plans/p1-3a-agent-loop/wscat-smoke.md`(手动烟测脚本,文档化)
- Create: `backend/src/test/java/com/wzx/babiq/server/agent/EndToEndIT.java`(自动化端到端)

### 13A. 手动 wscat 烟测脚本(给执行者+用户参考)

- [ ] **Step 13A.1: 写 wscat 烟测脚本**

Create `docs/superpowers/plans/p1-3a-agent-loop/wscat-smoke.md`:
```markdown
# P1-3a wscat 端到端烟测脚本

## 前置
1. 后端启动:`cd backend && .\mvnw.cmd spring-boot:run`
2. 看到 "Tomcat started on port 8080" + "Started BaBiQApplication"
3. 新开终端:`wscat -c ws://localhost:8080/ws/agent`

## 场景 A:读 README 并总结(read-only,无审批)

`application.yml` 临时设 `babiq.agent.sandbox-mode: READ_ONLY`,重启。

发:
```json
{"jsonrpc":"2.0","method":"thread/create","id":1,"params":{"cwd":"F:/wwwxxxx/BaBiQ"}}
```
期望:`{"id":1,"result":{"threadId":"thr_xxx"}}`

发:
```json
{"jsonrpc":"2.0","method":"turn/start","id":2,"params":{
  "threadId":"thr_xxx",
  "input":{"type":"text","text":"读取 README.md 并用 3 句话总结"}
}}
```
期望同步返回 turnId,然后顺序异步收到:
- `turn/started`
- `item/added` (userMessage)
- `item/added` (commandExecution,工具 read_file 调用) — 可选,取决于模型策略
- `item/added` (agentMessage,3 句话总结)
- `turn/completed`

## 场景 B:写文件触发审批

`application.yml` 设 `babiq.agent.sandbox-mode: WORKSPACE_WRITE`,`approval-policy: ON_REQUEST`,重启。

发:
```json
{"jsonrpc":"2.0","method":"turn/start","id":3,"params":{
  "threadId":"thr_xxx",
  "input":{"type":"text","text":"在 cwd 下创建 hello.txt 写入 'hi'"}
}}
```
期望:
- `turn/started`
- `item/added` (userMessage)
- 服务端推 `approval/request`(method=approval/request, tool=write_file)
- 客户端不响应 → 5 分钟超时自动 deny

或客户端发:
```json
{"jsonrpc":"2.0","method":"approval/respond","id":99,"params":{
  "itemId":"<上一步收到的 itemId>",
  "decision":"approve"
}}
```
期望:工具执行 → fileChange item → agentMessage → turn/completed,`hello.txt` 已创建。

## 场景 C:中断长任务

启动一个慢任务后立即发:
```json
{"jsonrpc":"2.0","method":"turn/interrupt","id":99,"params":{"turnId":"turn_yyy"}}
```
期望:
- 同步返回 `{"id":99,"result":{"accepted":true}}`
- 异步收到 `turn/completed (status="interrupted")` 或 `turn/failed`

## 场景 D:read-only 拒写

`sandbox-mode: READ_ONLY` 下发场景 B 请求,期望服务端直接推 `fileChange(status=denied)` + agentMessage("写操作被沙箱拒绝"),turn 短路完成。
```

### 13B. 自动化端到端 IT

- [ ] **Step 13B.1: 写自动化 IT(用 mock ChatModel + tool call,**严禁 @Disabled**)**

> 📌 **v3 修订(外部审查 D10)— M3 硬验收**:不允许 `@Disabled` 占位。EndToEndIT 必须跑通:
> ① mock ChatModel 第一次返回带 `tool_calls` 的 AssistantMessage(调 `read_file`);
> ② ReactAgent 自动执行该 tool call;
> ③ AgentLoop 推 commandExecution(或 fileChange) item;
> ④ mock ChatModel 第二次返回 final assistant text;
> ⑤ AgentLoop 推 agentMessage item + turn/completed。

```java
package com.wzx.babiq.server.agent;

import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-3a 端到端集成测试 — 启动完整 Spring 上下文,用 mock ChatModel 验证
 * Agent Loop + ToolInterceptor + ReactAgent + ItemEmitter 全链路。
 *
 * <p>v3 修订(外部审查 D10):M3 硬验收必须跑通真实 ReAct 流程,不允许 @Disabled。
 * mock ChatModel 第一次返回 tool_calls,ReactAgent 触发 read_file 后第二次返回 final text。</p>
 */
@SpringBootTest
class EndToEndIT {

    @TestConfiguration
    static class MockModelConfig {
        @Bean @Primary
        ChatModel mockChatModel() {
            ChatModel m = mock(ChatModel.class);
            // 第一次调用:返回带 tool_call 的 AssistantMessage,触发 read_file
            AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                    "call_1", "tool", "read_file",
                    "{\"path\":\"README.md\"}")))
                .build();
            // 第二次调用:基于工具返回结果,给出最终文本
            AssistantMessage finalMessage = new AssistantMessage(
                "README 的核心内容:BaBiQ 是一个 Java AI Agent 学习项目。");

            when(m.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolCallMessage))))
                .thenReturn(new ChatResponse(List.of(new Generation(finalMessage))));
            return m;
        }
    }

    @Autowired
    private AgentLoop agentLoop;

    @Autowired
    private ReActStrategy reActStrategy;

    @Test
    void context_loads_with_all_agent_beans() {
        assertThat(agentLoop).isNotNull();
        assertThat(reActStrategy).isNotNull();
    }

    @Test
    void agent_loop_runs_full_react_cycle_with_mocked_tool_call() throws Exception {
        // arrange:捕获 emit 顺序
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = mock(ItemEmitter.class);
        Mockito.doAnswer(inv -> { emitted.add(inv.getArgument(0)); return null; })
            .when(emitter).emitItemAdded(any(ThreadItem.class));

        Turn turn = new Turn("turn_e2e", "thr_e2e");
        turn.start();

        // act
        agentLoop.invoke(turn, "总结 README", null, emitter);

        // assert:① userMessage,② tool execution(commandExecution / toolResult),③ agentMessage
        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(emitted).hasSizeGreaterThanOrEqualTo(2);
        assertThat(emitted.get(0).type()).isEqualTo("userMessage");
        ThreadItem last = emitted.get(emitted.size() - 1);
        assertThat(last.type()).isEqualTo("agentMessage");
    }
}
```

> ⚠️ **API 漂移**:`AssistantMessage.builder().toolCalls(...)` 在 Spring AI 1.1.6 应可用;
> 若 builder 签名不同,按真实 API 改造 mock。**核心契约不变:两次 mock 调用 → ReAct 一轮工具调用 → final agentMessage**。
>
> ⚠️ **依赖**:mock 配置的 `read_file` 工具需要在 Spring 上下文存在(Task 5A 已提供 ReadFileTool 或复用 SAA FileSystemTools);若上下文无,改 mock 调一个无副作用的工具名,或在 `@TestConfiguration` 内额外注册一个 stub Tool bean。

- [ ] **Step 13B.2: 跑 + Commit**

```powershell
cd backend
.\mvnw.cmd test "-Dtest=EndToEndIT"
cd ..
git add backend/src/test/java/com/wzx/babiq/server/agent/EndToEndIT.java
git add docs/superpowers/plans/p1-3a-agent-loop/wscat-smoke.md
git commit -m "test(p1-3a): 端到端集成测试 + wscat 手动烟测脚本"
```

---

## Task 14: 文档同步 + 收尾

**Files:**
- Modify: `docs/ARCHITECTURE.md`(若 §5.3 / §15 / §17 / §21 文件清单有出入,同步)
- Modify: `docs/superpowers/plans/2026-05-21-p1-master.md`(§3 文件结构若有偏差,同步)
- Modify: `README.md`(如有 P1-3a 启动相关变化)

- [ ] **Step 14.1: 跨文档一致性检查**

```powershell
Select-String -Path docs\ARCHITECTURE.md -Pattern "hook/|sandbox/|approval/"
Select-String -Path docs\superpowers\plans\2026-05-21-p1-master.md -Pattern "hook/|sandbox/|approval/"
```

Expected: 三处包名提及与实际 backend/src 包结构一致。若有偏差,Edit 同步。

- [ ] **Step 14.2: backend clean verify 全绿**

```powershell
cd backend
.\mvnw.cmd clean verify
cd ..
```

Expected: BUILD SUCCESS,所有单测 + IT 通过,新增 ~20-30 个测试。

- [ ] **Step 14.3: Commit 收尾**

```powershell
git add -A
git status
# 若有未提交:
git commit -m "docs(p1-3a): 同步 ARCHITECTURE 与 master plan 文件清单(若需)"
git log --oneline -25
```

Expected:
- 看到 P1-3a 期间约 13-15 个 commit(每个 Task 一个 commit + 部分 Task 多个)
- **不打 tag,不 push**(D 决策)

---

## 风险与待定项

> 执行者撞到这些问题时,**不要硬猜**,按对应缓解策略处理。

| # | 风险 | 严重度 | 缓解 |
|:-:|---|:-:|---|
| 1 | **`HumanInTheLoopHook` 实际 artifact / API 名漂移** | 🔴 高 | Step 9.3 实现前先 `mvn dependency:tree | Select-String hitl`,然后查 [java2ai.com](https://java2ai.com);若 1.1.2.x 把 HITL 切到子模块,改坐标但保持 BOM 托管;**严禁**回退 D8 手写 |
| 2 | **`ReactAgent.builder()` hooks/saver/tools API 漂移** | 🔴 高 | Task 10B 实现前先看 [java2ai.com/docs/frameworks/agent-framework](https://java2ai.com) 一手代码,按真实签名调整;核心契约不变(4 个 Hook + 工具 + ChatClient 全挂上) |
| 3 | **ReactAgent.invoke() 同步阻塞 vs 协议异步 Item 推送** | 🟡 中 | TurnExecutor 用 cachedThreadPool 异步;ItemEmitter `synchronized(session)` 防并发写;ChatClient/ReactAgent 在 worker 线程跑,WebSocket session 仍可推 Item |
| 4 | **turn/interrupt 在 ReactAgent 内部如何中断 LLM 流** | 🟡 中 | 双保险:① `Future.cancel(true)` 抛 InterruptedException;② TurnExecutor.isInterrupted(turnId) 让 Hook 在 BEFORE_MODEL/BEFORE_TOOL 主动检查 flag 提前 return;** AgentLoop catch InterruptedException 时发 turn/completed (status="interrupted") |
| 5 | **ApplyPatchTool P1 简化为全文替换,P2 换真 diff** | 🟢 低 | plan 注释已说明;ToolResult.message 显式标 "P1 简化" |
| 6 | **PathGuard 在 Windows 上 toRealPath() 大小写不敏感** | 🟡 中 | 比较前都 normalize() + toRealPath();Step 12.1 测试覆盖大小写绕过 |
| 7 | **SAA agent-framework 与 spring-ai-starter-model-openai 可能 Bean 冲突** | 🟡 中 | Step 1.3 启动烟测必跑;若冲突,application.yml 加 `spring.ai.openai.chat.enabled: false`(P1-2 已有先例) |
| 8 | **AgentLoop.java 100 行硬卡可能因 import / JavaDoc 超** | 🟢 低 | 测试用整文件 ≤100 行;主逻辑实际 ≤50 行;超时拆分到 helper 类(如 `AgentLoopErrorReporter`) |
| 9 | **exec_shell 在 Windows 上行为差异**(`cmd.exe /c` vs `bash -c`)| 🟡 中 | ExecShellTool 用 ProcessBuilder + OS 检测;Step 5E 测试覆盖跨平台 |
| 10 | **HITL interrupt/resume 失序**(用户提交 approval/respond 时 turn 已超时或 Agent 状态不一致) | 🟡 中 | `PendingApprovals.take()` 后立即转 RUNNING,并发情况靠 `ConcurrentHashMap` + Turn 状态机 transition 校验拦住非法状态 |

---

## Done Criteria(M3a 整体硬验收)

逐项检查,任一项不达成都需回到对应 Task 修复。

### 自动化(必须全过)
- [ ] `cd backend && .\mvnw.cmd clean verify` 全绿
- [ ] `*ToolTest`(6 个工具)单测全绿,每个 ≥ 3 测试
- [ ] `BaBiQTokenUsageHookTest` / `BaBiQSandboxInterceptorTest` / `PendingApprovalsTest` 单测全绿(v3:删除 LimiterHook/TruncationHook/HITLHook 自写测试)
- [ ] `SandboxModeRegressionTest` 5 个测试通过(含符号链接攻击拒绝)
- [ ] `AgentLoopLineCountTest` 通过(整文件 ≤ 100 行)
- [ ] `AgentLoopTest` 2 个测试通过(成功路径 + 失败标 FAILED)
- [ ] `EndToEndIT` 通过 — **v3 严禁 @Disabled**,必须跑通 mock ChatModel + tool call 完整 ReAct

### 文件结构(必须存在)
- [ ] `backend/.../agent/AgentLoop.java`(整文件 ≤ 100 行)
- [ ] `backend/.../agent/AgentLoopProperties.java`(record)
- [ ] `backend/.../agent/ReActStrategy.java`
- [ ] `backend/.../agent/TurnExecutor.java` / `PendingApprovals.java` / `ApprovalRequestPayload.java`
- [ ] `backend/.../tool/Tool.java` + `ToolRegistry.java` + `ToolResult.java` + `impl/` 6 个工具
- [ ] `backend/.../approval/` 3 个文件(Policy enum + Decision enum + Request record,**无 Engine**)
- [ ] `backend/.../sandbox/` 3 个文件(Mode enum + Policy + PathGuard)
- [ ] `backend/.../hook/BaBiQTokenUsageHook.java`(v3 仅保留 1 个自写 Hook;ModelCallLimit / 输出截断改用 SAA 内置)
- [ ] `backend/.../interceptor/BaBiQSandboxInterceptor.java`(v3 新增)

### 决策合规
- [ ] **D21**:`AgentLoop` 整文件 ≤ 100 行,主流程不写 if 审批/截断/限流(Hook/Interceptor 全包)
- [ ] **D22**:6 个工具的 ToolResult / CommandExecutionItem / FileChangeItem 全是 record,字段标 `@JsonProperty(required=true)`
- [ ] **D23**(v4):审批走 SAA 内置 `HumanInTheLoopHook` + `MemorySaver` + `RunnableConfig.addHumanFeedback.resume` + 手动 `ToolFeedback.Builder`(无 HITLHelper / 无 CompiledGraph.resume / 无 ApprovalChannel / 无 SynchronousQueue / 不阻塞 Agent 线程)
- [ ] **D24**:`BaBiQSandboxInterceptor.shouldEnforceSandbox()` 验证 `read_file`/`list_dir`/`grep` 永远不触发沙箱
- [ ] **D19**(v3):工具输出走 SAA 内置 `LargeResultEvictionInterceptor`,**工具实现内不写截断**
- [ ] **D31**:`PathGuard` 用 `Path.toRealPath()` + `Path.relativize()`,**无 `path.startsWith()` 字符串前缀比较 / 无 `subpath` 拼接**
- [ ] **D26**:本阶段未引入 Multi-Agent(单 ReactAgent),但 ReActStrategy 设计预留 sub-agent 包装位
- [ ] **D8**:全代码库 `grep -rn "CompletableFuture<ApprovalDecision>"` 零匹配

### 协议契约(wscat / IT 验证)
- [ ] `turn/start` 同步返回 turnId,异步顺序推 `turn/started` → item/* → `turn/completed`
- [ ] `turn/interrupt` 能中断正在跑的 invoke,后续发 `turn/completed (status="interrupted")`
- [ ] `approval/respond` 三档(approve/deny/edit)各跑通一次,deny 后工具结果回灌"用户拒绝"
- [ ] `read-only` 沙箱下 `write_file` 立刻推 `fileChange(status=denied)` 不真写文件
- [ ] `workspace-write` 沙箱下 cwd 内 write 直接成功(策略 NEVER 时)或触发审批(策略 ON_REQUEST 时)

### Git
- [ ] P1-3a 期间 ~13-15 个原子 commit,**全部中文 commit message**
- [ ] **不打 tag**(D 决策)
- [ ] **不 push**(D 决策)
- [ ] 工作树 clean(`git status` 输出 `nothing to commit`)

---

## 完成后下一步

P1-3a 完成后:
1. 按 §1 代码质量铁律自检(中文 JavaDoc / 无屎山 / 方法 ≤50 行 等)
2. 把完整 Done Criteria 勾选状态汇报给用户
3. 等用户决定是否进入 **P1-3b(Spotlighting + 实时成本反馈 + JSON 日志)**

