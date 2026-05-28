# 阅读路线 03：后端源码阅读起点（IDE 跟读的第一步）

> deep-dive 章节解释「为什么」，walkthrough 章节追「在哪发生」，**这一章是「拿起 IDE 怎么读」**。
>
> 目标：让一个第一次打开 BaBiQ 后端代码的人，**在 2 小时内**理解整个后端结构、知道哪些文件值得精读、哪些只需要扫一眼。

---

## 🎯 学完你会知道

1. 后端代码这么多包，**为什么从 `AgentLoop.java` 开始读**，而不是从 `BaBiqApplication.main()` / `WebSocketConfig` / `TurnStartHandler` 开始。
2. **8 站推荐路线**：每站读什么、读多久、读到什么程度算结束。
3. 哪些包**可以暂时跳过**——以及为什么跳过它们不影响理解 Agent 核心。
4. IDE 准备：项目导入、Maven build、log level、推荐插件、单测怎么跑。
5. **「Cmd+B 黑洞」**怎么避免——为什么过度跳转会让你迷路。
6. 跟读一次真实 turn 的推荐断点序列。

---

## 🧱 预备知识

- 看过 [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) §1-§5（系统总览）。
- 看过 [`04-walkthroughs/01-read-file-full-trace.md`](../04-walkthroughs/01-read-file-full-trace.md)（知道一个 turn 经过哪些类）。
- 装了 IntelliJ IDEA（社区版即可）或 VSCode + Java extension pack。
- 装了 JDK 21+ 和 Maven 3.9+。

> 💡 **没看 deep-dive 章节也没关系**——这章只引导你**怎么读**，不解释概念。等你跟着读完代码再回到 deep-dive 看「为什么这么设计」效果最好。

---

## 1. 这章不一样：reading-path 不是 deep-dive

| 类型 | 输出 | 何时读 |
|---|---|---|
| **deep-dive**（`03-tech-deep-dive/`） | 「为什么 BaBiQ 这么设计」 | 读完代码想理解决策 |
| **walkthrough**（`04-walkthroughs/`） | 「一个真实任务经过哪些类」 | 跟代码跑通一次完整链路 |
| **reading-path**（本章所在的 `02-reading-path/`） | 「**拿起 IDE 怎么读** + **每个文件读多深**」 | 第一次打开代码不知道从哪下手 |

**这章不解释 Hook 怎么工作、不解释上下文怎么压缩**——那些是 deep-dive 的事。这章只告诉你：**先看这个文件，再看那个文件，停在这里就够了**。

---

## 2. 为什么从 AgentLoop 开始

打开 backend 目录，乍一看会发现这么多包：

```
backend/src/main/java/com/wzx/babiq/server/
├── BaBiqApplication.java       ← Spring Boot main
├── agent/                      ← Agent 主流程
├── api/                        ← JSON-RPC 协议入口
├── approval/                   ← HITL 审批
├── capability/                 ← P3-5 按需能力
├── config/                     ← Spring 配置
├── context/                    ← P3 上下文工程
├── conversation/               ← Thread / Turn / Item 状态
├── hook/                       ← SAA hook（4 个）
├── interceptor/                ← SAA interceptor（4 个）
├── memory/                     ← P3-4 长期记忆
├── model/                      ← Provider / ChatModel
├── observability/              ← 本地统计
├── persistence/                ← SQLite + MyBatis-Plus
├── sandbox/                    ← PathGuard
├── security/                   ← Spotlighting
├── settings/                   ← 设置
└── tool/                       ← 6 个本地工具
```

200+ Java 文件。**从哪开始读？**

### 2.1 三个错误的起点

#### ❌ 错误起点 A：从 `main()` 开始

```java
@SpringBootApplication
public class BaBiqApplication {
    public static void main(String[] args) {
        SpringApplication.run(BaBiqApplication.class, args);
    }
}
```

读完你只知道「这是个 Spring Boot 项目」。然后呢？Spring 帮你扫描所有 `@Component`，初始化 bean。你接下来要读 100 个 bean 的构造器吗？

#### ❌ 错误起点 B：从 `JsonRpcWebSocketHandler` / `TurnStartHandler` 开始

确实，**协议入口**是请求的物理起点。但从这开始读：
- 先要理解 JSON-RPC 协议（dispatcher 怎么找 handler）。
- 然后理解 `TurnStartHandler` 怎么解析 params。
- 然后理解 `ConversationService` 怎么创建 turn。
- 然后理解 `TurnExecutor` 怎么异步提交。
- ……到第 5 层才到核心。

这条路线你**在外围花了 80% 时间，核心只看了 20%**。

#### ❌ 错误起点 C：按字母顺序看包

`agent` → `api` → `approval` → ... 这样读相当于读字典，没有故事线。

### 2.2 正确起点：AgentLoop.java

打开 [`AgentLoop.java`](../../backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java)：

```java
@Component
public class AgentLoop {
    public void invoke(Turn turn, String userText, String providerId, String cwd,
                       ItemEmitter emitter, AgentRunPolicy runPolicy) {
        // ... 50 行业务逻辑
    }
    public void invokeResume(...) { ... }
}
```

只有 **100 行**。但它的依赖**触及整个后端的核心**：

```
AgentLoop
 ├── ReActStrategy        ← 装配 ReactAgent
 ├── TurnSummaryEmitter   ← 输出 token 统计
 ├── TurnObservationRegistry ← 观测
 ├── ContextWindowRuntime ← 上下文工程入口
 ├── AgentLoopOutputHandler ← 收尾分发
 └── 间接依赖：Hook / Interceptor / Tool / Capability
```

**它是「中枢神经系统」**：往上看是协议层，往下看是 Hook/Interceptor/Tool，往两边看是 Context/Memory/Capability。

**好处**：
- 100 行**一口气读完**。
- 每个依赖是「平的」（兄弟节点），你可以选择一个一个深入，不会迷路。
- 真正的业务从这开始，不会被框架噪音淹没。

> ⚠️ **请记住一个 BaBiQ 铁规则**：`AgentLoop.invoke()` ≤ 50 行业务逻辑（注释/空行不算）。有 `AgentLoopLineCountTest` 守护。所以你永远不会在这里看到 200 行长方法。

---

## 3. 准备 IDE

### 3.1 打开项目

```powershell
git clone <repo>
cd BaBiQ
# 用 IDEA 打开 backend 目录
# 或：用 VSCode + Java extension pack 打开整个 BaBiQ 目录
```

### 3.2 第一次构建

```powershell
cd backend
.\mvnw.cmd compile
```

第一次会下 Spring AI Alibaba、SQLite JDBC、Lucene 等依赖（几百 MB）。耐心等。

### 3.3 验证测试能跑

```powershell
cd backend
.\mvnw.cmd "-Dtest=AgentLoopLineCountTest" test
```

预期：1 个测试通过。这证明你的 build 环境 OK。

### 3.4 推荐 IDE 配置

#### IntelliJ IDEA

1. **导入为 Maven 项目**（自动）。
2. **设置 JDK 21**：File → Project Structure → Project SDK → 21。
3. **启用 annotation processing**（Lombok 不用，但有些 SAA 注解需要）：Settings → Build, Execution, Deployment → Compiler → Annotation Processors → Enable。
4. **推荐插件**：
   - **Lombok**（虽然 BaBiQ 不用，但 SAA 依赖可能用）。
   - **MyBatis Log Plugin**（看 SQL）。
   - **String Manipulation**（处理长 JSON）。

#### VSCode

1. 装 **Extension Pack for Java**。
2. 装 **Spring Boot Extension Pack**。
3. 用 `Ctrl+Shift+P` → `Java: Reload Projects`。

### 3.5 调整 log level

在 `backend/src/main/resources/application.yml` 临时加：

```yaml
logging:
  level:
    com.wzx.babiq: DEBUG          # BaBiQ 自己的日志，看 turn 流转
    org.springframework.ai: DEBUG  # Spring AI 模型调用细节
    # com.alibaba.cloud.ai: DEBUG  # SAA 内部细节（噪音大，按需开）
```

**为什么需要 DEBUG**：BaBiQ 在很多关键点用了 `log.debug(...)` 记录调用细节。INFO 看不到这些。

⚠️ 调试完**记得改回 INFO**，否则日志爆炸。

### 3.6 跑后端

#### 方式 A：命令行

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

#### 方式 B：IDEA

1. 找到 `BaBiqApplication.java`。
2. 点 `main()` 旁边的绿色三角。

启动成功后日志会显示：

```
JSON-RPC method 注册完成: count=49, methods=[turn/start, thread/create, ...]
WebSocket 已注册: path=/ws/agent
```

50 个左右的 method 全注册到 dispatcher 了 → 后端就绪。

### 3.7 跑桌面端（可选）

如果你想完整跟读 + 看 UI 行为：

```powershell
cd desktop
.\gradlew.bat run --no-daemon
```

桌面端会连 `ws://localhost:8080/ws/agent`。

---

## 4. 阅读路线总览

```
                    [START]
                       │
                       ▼
              第 1 站：AgentLoop  ←─────────────  100 行，5 分钟
                       │
              ┌────────┼────────┬─────────┐
              ▼        ▼        ▼         ▼
        第 2 站：    第 6 站：   第 11 站：（跳过 / 后看）
        ReActStrategy TurnExecutor ContextWindowRuntime
        装配        + TurnStartHandler  上下文入口
              │        │
              ▼        ▼
        第 3 站：     第 10 站：
        Hook 包      ItemEmitter
        (4 文件)      + OutputHandler
              │
              ▼
        第 4 站：
        Interceptor 包
        (4 文件)
              │
              ▼
        第 5 站：
        Tool 实现
        (6 文件)
```

**8 站路线**：

| 站 | 内容 | 推荐时长 |
|---|---|---|
| 1 | `agent/AgentLoop.java` | 5 分钟 |
| 2 | `agent/ReActStrategy.java` | 15 分钟 |
| 3 | `hook/` 包（4 文件） | 10 分钟 |
| 4 | `interceptor/` 包（4 文件） | 15 分钟 |
| 5 | `tool/impl/` 包（6 文件，扫读） | 10 分钟 |
| 6 | `agent/TurnExecutor.java` + `api/method/TurnStartHandler.java` | 10 分钟 |
| 7 | `conversation/ItemEmitter.java` + `agent/AgentLoopOutputHandler.java` | 15 分钟 |
| 8 | `context/runtime/ContextWindowRuntime.java`（只看 `prepare()`） | 15 分钟 |

**总计 ≈ 1.5-2 小时**。读完你对后端有完整心智地图。

---

## 5. 第 1 站：AgentLoop.java（5 分钟）

📁 `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`

### 5.1 怎么读

1. 整个文件 100 行，**一次性看完**。
2. 重点看 `invoke()` 方法（L45-L73）。
3. 注意它的依赖列表（构造器参数）：

```java
public AgentLoop(ReActStrategy strategy,
                 PendingApprovals pendingApprovals,
                 TurnSummaryEmitter summaryEmitter,
                 TurnObservationRegistry observationRegistry,
                 ContextWindowRuntime contextWindowRuntime)
```

这 5 个就是你接下来要按需深入的入口。

### 5.2 读完应该理解

- 一个 turn 主流程的 7 个步骤：emit user item → 规划能力 → 准备上下文 → buildAgent → stream → 记录用量 → handleOutput。
- HITL 恢复路径走 `invokeResume`。
- 异常路径仍然要 `recordContextUsage`（避免上下文使用不被统计）。

### 5.3 暂时不深入

- `AgentRunPolicy`、`AgentLoopDiagnostics`、`CapabilityExposurePlan`——记住有就行，**不要 Ctrl+B 跳进去**。

### 5.4 终止条件

当你能在 60 秒内回答这两个问题，就可以进下一站：

1. AgentLoop 的 5 个依赖分别干什么？
2. `invoke()` 第 7 步 `handleOutput` 走哪里？

---

## 6. 第 2 站：ReActStrategy.java（15 分钟）

📁 `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`

这是 BaBiQ「装配 SAA ReactAgent」的中央车间。

### 6.1 怎么读

文件 478 行，**不要从头读到尾**。重点看 4 个方法：

1. **构造器**（L93-L137）：看它依赖谁——这告诉你「哪些 Hook、Interceptor 被装配」。
2. **`buildAgent(...)`**（L162-L216）：看它怎么把所有积木拼成一个 ReactAgent。
3. **`buildHitlHook()`**（L451-L463）：看它决定哪些工具要审批。
4. **`buildResumeConfig(...)`**（L331-L354）：看 HITL 恢复时怎么传 metadata。

其它方法（`extractAssistantMessage` / `autoApprovedFeedback` / `emitApprovalRequests`）扫一眼名字知道存在就行。

### 6.2 读完应该理解

- `.interceptors(sandbox, observation, spotlighting, eviction)` 的注册顺序就是洋葱链顺序（外 → 内）。
- `.hooks(...)` 在 `ApprovalPolicy.NEVER` 时跳过 HITL Hook。
- `tokenUsageHook.reset()` 在 buildAgent 时重置——每轮归零。
- `toolContext` 是把 cwd / sandbox mode / emitter 传给工具的桥梁。

### 6.3 暂时不深入

- `LargeResultEvictionInterceptor` 来自 SAA，知道有就行。
- `ChatClientFactory`——P1-2 的 Provider 工厂，下次专门看。
- `CapabilityExposurePlan`——P3-5 的能力装配，下次专门看。

### 6.4 终止条件

能在脑子里回答：「读 `read_file` 工具的请求，从 model 出来后经过哪 4 个 interceptor，按什么顺序？」

如果想不清楚，回去看 [Hook/Interceptor 章 §7 执行顺序总览](../03-tech-deep-dive/01-react-hook-interceptor.md)。

---

## 7. 第 3 站：hook/ 包（10 分钟）

📁 `backend/src/main/java/com/wzx/babiq/server/hook/`

只有 2 个 BaBiQ 自写的文件：

```
hook/
├── BaBiQTokenUsageHook.java       ← 152 行
└── ResumeJumpCleanupHook.java     ← 73 行
```

加上 SAA 自带、被 ReActStrategy 装配的 2 个：
- `HumanInTheLoopHook`（SAA）
- `ModelCallLimitHook`（SAA）

### 7.1 怎么读

**只读 BaBiQ 自写的两个**：

#### `BaBiQTokenUsageHook.java`

- 看 `@HookPositions({HookPosition.AFTER_MODEL})` 注解。
- 看 `afterModel` 怎么从 state 取 Usage。
- 看 `reset()` 在哪被调（提示：`ReActStrategy.buildAgent`）。

#### `ResumeJumpCleanupHook.java`

- 看 `beforeModel` 的判断条件：`jump_to == tool` && `lastMessage == ToolResponseMessage`。
- 看返回值 `MARK_FOR_REMOVAL` 是什么意思。
- 读类注释里的 bug 背景。

**SAA 那两个跳过**——你不需要看官方代码，知道它们的契约就行。

### 7.2 读完应该理解

- Hook 是「绑在图调度上的钩子」，能改 state。
- BaBiQ 自写 Hook 解决的两个问题：累 token / 清 jump_to。
- 为什么没用 SAA 自带的 token hook？因为要写入 `TurnObservationContext`（领域对象）。

### 7.3 终止条件

能解释：「如果忘了 `tokenUsageHook.reset()` 会出什么 bug？」

参考 [Hook/Interceptor 章 §9 反例 3](../03-tech-deep-dive/01-react-hook-interceptor.md)。

---

## 8. 第 4 站：interceptor/ 包（15 分钟）

📁 `backend/src/main/java/com/wzx/babiq/server/interceptor/`

4 个文件：

```
interceptor/
├── BaBiQSandboxInterceptor.java          ← 264 行（最复杂）
├── BaBiQStreamingTokenUsageInterceptor.java ← 201 行
├── SpotlightingToolInterceptor.java      ← 84 行（最简单）
└── ToolObservationInterceptor.java       ← 148 行
```

### 8.1 推荐顺序

按**简单→复杂**读：

1. **`SpotlightingToolInterceptor`**（5 分钟）：洋葱模式的最小例子。读完你就懂 `interceptToolCall(request, handler)` 的 around 模式。
2. **`ToolObservationInterceptor`**（5 分钟）：双重职责（metrics + 持久化），catch 异常但不抛出。
3. **`BaBiQSandboxInterceptor`**（10 分钟）：BaBiQ 最复杂的 interceptor，看 4 个关键方法：
   - `interceptToolCall`（入口）
   - `checkOrReject`（沙箱判断）
   - `resolveAgainstCwd`（路径解析，关键）
   - `writableRoots`（白名单收集）
4. **`BaBiQStreamingTokenUsageInterceptor`**：扫一眼。理解它专门处理流式 chunk 就行。

### 8.2 读完应该理解

- Interceptor = around 模式：`handler.call(request)` 前后做事。
- 修改 response 要 `new ToolCallResponse(...)`（不可变）。
- 异常情况要 short-circuit（错误不包 spotlight 等）。
- `extends ToolInterceptor` vs `implements StreamingModelInterceptor` 是两条不同的扩展点。

### 8.3 暂时不深入

- `BaBiQSandboxInterceptor` 的 `emitDeniedFileChangeIfNeeded`——它依赖 `ItemEmitter`，下次第 7 站再看。
- `ToolObservationInterceptor` 的 `ToolCallPersistenceService`——P2-4 的持久化，下次按需看。

### 8.4 终止条件

能解释：「`Sandbox` / `Observation` / `Spotlighting` / `Eviction` 的注册顺序为什么是这样？」

---

## 9. 第 5 站：tool/impl/ 包（10 分钟）

📁 `backend/src/main/java/com/wzx/babiq/server/tool/impl/`

6 个本地工具：

```
tool/impl/
├── ReadFileTool.java      ← 67 行
├── WriteFileTool.java     ← ~100 行
├── ListDirTool.java
├── GrepTool.java
├── ExecShellTool.java
├── ApplyPatchTool.java
└── ToolSearchTool.java    ← P3-5 加的
```

### 9.1 怎么读

**只精读 1-2 个，剩下扫**。推荐：

1. **`ReadFileTool.java`**（3 分钟）：最简单。理解 `@Tool` / `@ToolParam` / `ToolContext` 模式。
2. **`WriteFileTool.java`**（3 分钟）：理解工具怎么 emit FileChangeItem。
3. 其它扫一眼：「噢，原来 grep 是怎么实现的」「噢，apply_patch 用了什么算法」——**不深入**。

### 9.2 读完应该理解

- 工具是普通 `@Component`，靠 `@Tool` 注解被 `ToolRegistry` 扫描成 `ToolCallback`。
- 工具**不做权限校验**——那是 Sandbox 的事。
- 工具**自己 emit fileChange / commandExecution item**——让 UI 实时可见。

### 9.3 终止条件

能回答：「如果我想加一个 `git_diff` 工具，需要改哪几处？」

提示：写一个 `@Component class GitDiffTool` + `@Tool name="git_diff"` → 自动注册。但要考虑：
- 它需要审批吗？（要加进 `ReActStrategy.buildHitlHook`）。
- 它需要沙箱吗？（不需要——是读操作）。
- 它的 capability 描述（中文别名）？

---

## 10. 第 6 站：上游入口（10 分钟）

到这里你已经看了「Agent 怎么跑」。现在往**上游**看「请求怎么进来」。

### 10.1 文件

```
agent/TurnExecutor.java                          ← 148 行
api/method/TurnStartHandler.java                 ← 看 handle() 方法
```

### 10.2 怎么读

#### `TurnExecutor`

只看 3 个方法：
- `submit(...)`：异步提交普通 turn。
- `submitResume(...)`：异步提交 HITL 恢复。
- `interrupt(turnId)`：取消运行中的 turn。

注意：
- `Executors.newCachedThreadPool()` 是 P1 简化版（注释里写了 P2 可换）。
- `running.put / remove` 用 `ConcurrentHashMap` 跟踪 Future。

#### `TurnStartHandler`

只看 `handle()` 方法。理解：
- params → Thread / runPolicy → 新建 Turn 并落库 → 构造 ItemEmitter → `turnExecutor.submit(...)` → 立即返回 result。
- WebSocket 线程**几毫秒**就返回，AgentLoop 在线程池跑。

### 10.3 读完应该理解

- 同步 → 异步切换的边界就在 `TurnStartHandler.handle()` 最后一行 `submit`。
- 异步执行后 WebSocket 线程立刻可处理别的协议消息（比如 `turn/interrupt`）。

### 10.4 终止条件

能解释：「用户点发送后到 AgentLoop.invoke 之间，经过哪些类、切换几次线程？」

---

## 11. 第 7 站：下游输出（15 分钟）

往**下游**看「输出怎么出去」。

### 11.1 文件

```
conversation/ItemEmitter.java                    ← ~150 行
agent/AgentLoopOutputHandler.java                ← ~200 行
observability/TurnSummaryEmitter.java            ← ~100 行
```

### 11.2 怎么读

#### `ItemEmitter`

看它的核心方法：
- `emitItemAdded(ThreadItem)`：发 `item/added` notification。
- `emitApprovalRequest(...)`：发 `approval/request`。
- `emitFileChange(...)`：发 `fileChange` item。
- `emitTurnCompleted` / `emitTurnFailed`。

每个方法内部都做两件事：
1. 序列化成 JSON-RPC notification 写 WebSocket。
2. 调 `ConversationEventRecorder` 落库到 `bq_items`。

#### `AgentLoopOutputHandler`

只看 3 个方法：
- `handleOutput(...)`：根据 `StreamResult.kind` 分发。
- `handleCompleted(...)`：正常完成路径。
- `handleWaitingApproval(...)`：HITL 暂停路径（看它怎么 `pausedRegistry.register` + `emitApprovalRequests`）。

#### `TurnSummaryEmitter`

只看 `emit(...)` 方法。理解它怎么从 `BaBiQTokenUsageHook.snapshot()` 拿 token、从 `TurnObservationContext` 拿 toolCallCount。

### 11.3 读完应该理解

- 所有「服务端 → 桌面」的 notification 都通过 `ItemEmitter`，不直接 `session.send`。
- 每个 emit 都同步落库（事件源 + 持久化双轨）。
- TurnSummary 是一种特殊 ThreadItem，可被反序列化为 UI 的 token 反馈条。

### 11.4 终止条件

能回答：「`item/added` 怎么走到桌面端 UI 的？」（提示：参考 [walkthrough 01](../04-walkthroughs/01-read-file-full-trace.md) §阶段 21-25）

---

## 12. 第 8 站：上下文工程入口（15 分钟）

这一站是「**深井入口**」——只看一个方法，理解它的 13 步流程。**不要**深入到 ContextAssembler / ContextCompactionService / LongTermMemoryReadService 等子系统。

### 12.1 文件

```
context/runtime/ContextWindowRuntime.java        ← 448 行
```

### 12.2 怎么读

**只看 `prepare()` 方法**（L140-L187）。

逐行扫，看它的 13 步流程：
1. 读 existing window
2. 计算预算
3. 装配能力目录
4. 读历史
5. 读 active summary
6. **读长期记忆**
7. **装配一次**
8. **判断是否压缩**
9. 如果压缩成功 → 重新装配
10. 渲染 modelInputText
11. 持久化 snapshot
12. 写 memory references 审计
13. 写/更新 window state

### 12.3 读完应该理解

- prepare 是**每轮 turn pre-call 都跑一次**的同步方法。
- 它把分层 envelope 拍扁成 `modelInputText`，喂给 `agent.stream(...)`。
- 失败也继续：snapshot 落库失败时 log warn + 返回 snapshotId=null。

### 12.4 暂时不深入

- `ContextAssembler.assemble(...)` 的 5 层装配（见 [上下文工程章](../03-tech-deep-dive/02-context-engineering.md) §3）。
- `ContextCompactionService.compactIfNeeded(...)`（见 [上下文工程章](../03-tech-deep-dive/02-context-engineering.md) §6）。
- `LongTermMemoryReadService` 的 summary-only vs retrieval 模式。

⚠️ 这些**每一个**都是单独的深井。读了 deep-dive 再回来看才不会迷路。

### 12.5 终止条件

能回答：「`prepare()` 失败时，AgentLoop 会失败吗？为什么？」（提示：try-catch + 返回 snapshotId=null）

---

## 13. 跳过的包（暂时不读）

到这里你已经掌握 Agent 核心。以下包**第一遍不读**：

| 包 | 跳过原因 | 何时回来读 |
|---|---|---|
| `persistence/` | MyBatis-Plus + Flyway，**框架噪音**多 | 想理解数据库时 |
| `observability/`（除 Emitter） | metrics + 统计聚合 | 想做监控时 |
| `memory/` 完整包 | 长期记忆是独立异步流水线 | 看完上下文工程章 |
| `capability/` 完整包 | 按需能力装配 | 想理解 P3-5 |
| `mcp/` | MCP Client 接入 | 想加自定义 MCP server |
| `settings/`（除接口） | CRUD 设置 | 想加新设置项 |
| `model/provider/` | DeepSeek / DashScope 工厂细节 | 想加新 Provider |
| `api/method/*` 大部分 handler | 模板化（解 params → 调 service → 返回 DTO） | 想加新协议 method |
| `conversation/items/*` | 各种 item 的 record 定义 | 想加新 item 类型 |

**为什么不读**：这些是**外围细节**。Agent 核心已经看完了。这些包是「同心圆的外层」，可以按需读。

> 💡 **关键洞察**：BaBiQ 后端 200 个文件里，**核心只有 20 个左右**。剩下都是「外围支撑」。第一遍读懂 20 个核心，远比把 200 个都扫一遍有用。

---

## 14. 完整 turn 跟读：推荐断点序列

打开 [`walkthrough 01`](../04-walkthroughs/01-read-file-full-trace.md) 同时，在 IDEA 里下这些断点（按触发顺序）：

```java
// 1. JsonRpcWebSocketHandler.handleTextMessage  ← 协议入口
//    断点目的：看 JSON-RPC 报文长什么样
//    Evaluate: message.getPayload()

// 2. TurnStartHandler.handle                    ← 业务入口
//    断点目的：看 turn 怎么创建
//    Evaluate: thread.id(), turn.id()

// 3. TurnExecutor.submit                        ← 异步切换
//    断点目的：看线程切换
//    Evaluate: Thread.currentThread().getName()

// 4. AgentLoop.invoke (第 1 行)                 ← Agent 主流程开始
//    断点目的：看核心入口
//    Evaluate: userText, providerId, runPolicy

// 5. ContextWindowRuntime.prepare (return)      ← 上下文装配完
//    断点目的：看 modelInputText 长什么样
//    Evaluate: contextInput.modelInputText().length()

// 6. ReActStrategy.buildAgent (return)          ← Agent 实例就绪
//    断点目的：看装配了哪些工具
//    Evaluate: callbacks.length

// 7. BaBiQSandboxInterceptor.checkOrReject      ← 沙箱判断
//    Condition: toolName.equals("read_file")
//    断点目的：看路径解析

// 8. ReadFileTool.readFile                      ← 工具真正执行
//    断点目的：看读到什么

// 9. SpotlightingToolInterceptor.interceptToolCall (return)
//    断点目的：看包装后的字符串

// 10. AgentLoopOutputHandler.handleCompleted    ← 收尾
//     断点目的：看 final AssistantMessage

// 11. TurnSummaryEmitter.emit                   ← 发反馈条
//     断点目的：看 token 统计

// 12. ItemEmitter.emitItemAdded                 ← 输出帧
//     断点目的：看 JSON notification
```

设置 IDE：把这 12 个断点都设上 → 启动后端 → 桌面端发个「读 README 总结」 → F9 一路按下去。**一次 turn 看完 = 你已经读懂 BaBiQ 后端**。

---

## 15. 怎么避免迷路：Cmd+B 黑洞

读源码最常见的迷路方式：

```
1. 看 AgentLoop.invoke
2. Cmd+B 跳进 ReActStrategy.buildAgent
3. 看到 chatClientFactory.resolveChatModel → 跳进去
4. 看到 ProviderConfig → 跳进去
5. 看到 SecretStore → 跳进去
6. ... 30 分钟后
7. 你在 SQLite JDBC 源码里 ???
8. 已经忘记最开始为什么开始读了
```

**避免方式**：

### 15.1 规则 1：每次 Cmd+B 之前先问自己一个问题

「**我跳进去是为了回答什么具体问题？**」

- 不知道？→ **不要跳**。
- 知道？→ 跳，看完答案立即用 Cmd+Alt+← 回来。

### 15.2 规则 2：用注释回答 80% 的问题

BaBiQ 强制要求中文教学型注释（CLAUDE.md 规则）。**先读注释**，不要直接跳代码。

```java
/** 工具输出安全拦截器，用 spotlighting 标记外部内容，降低 prompt injection 风险。 */
private final SpotlightingToolInterceptor spotlightingInterceptor;
```

这一行注释告诉你「它做 spotlighting，防 prompt injection」。够了，不用跳进去看实现。

### 15.3 规则 3：保留「主题」

打开 IDEA 新建一个本地 Note：

```
今天在读：「AgentLoop 怎么把请求送进 ReactAgent」

已读：
- AgentLoop.invoke
- ReActStrategy.buildAgent

待读：
- AgentStreamConsumer.consume
- AgentLoopOutputHandler.handleOutput

不读：
- ChatClientFactory（Provider 工厂，下次专门看）
- TurnObservationRegistry（观测细节，下次看）
```

**写下来**。三天后回来还能继续。

### 15.4 规则 4：用调试日志辅助

实在没头绪时，在你不确定的方法里加一行：

```java
log.info("DEBUG: invoke called with userText={}", userText);
```

跑一次，看日志真实流转。**比纯静态阅读快 10 倍**。读完记得删掉调试日志。

---

## 16. 推荐的辅助配置

### 16.1 单测怎么跑

最有价值的几个测试：

```powershell
# 1. 守护红线
.\mvnw.cmd "-Dtest=AgentLoopLineCountTest" test

# 2. Hook / Interceptor 测试（看 BaBiQ 怎么验证装配顺序）
.\mvnw.cmd "-Dtest=ReActStrategyTest,SpotlightingToolInterceptorTest,ToolObservationInterceptorTest" test

# 3. 沙箱回归（看 PathGuard 防御）
.\mvnw.cmd "-Dtest=PathGuardTest,SandboxModeRegressionTest" test

# 4. 端到端 IT
.\mvnw.cmd "-Dtest=EndToEndIT" test

# 5. 全量验收
.\mvnw.cmd clean verify
```

读源码累了，跑测试看绿条，能换个角度理解代码。

### 16.2 SQL 日志（看数据库怎么写）

如果你想看 `bq_items` 是怎么落库的，在 `application.yml` 加：

```yaml
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
```

然后跑一次 turn → 控制台会打印每条 SQL。

### 16.3 热重载

修改 Java 代码后想立即生效（不用重启）：

1. **IDEA**：装 `JRebel`（收费）或 `Spring Boot DevTools`（依赖里通常已经有）。
2. 改完 Java → Ctrl+F9（Build Project） → DevTools 自动重启 Spring 上下文。

⚠️ DevTools 不支持改 `@Component` 的字段类型。改方法体没问题。

---

## 17. 思考题

> 读完这条路线后，能回答这些问题说明你确实「读进去了」。

1. **如果 BaBiQ 启动时报错「JSON-RPC method 重复注册: turn/start」，可能是什么原因？**
   提示：[`JsonRpcDispatcher.indexHandlers`](../../backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java#L123) putIfAbsent 检查。可能两个 `@Component` 都重写了 `method()` 返回相同字符串。

2. **`AgentLoop.invoke()` 抛了异常，`bq_turn_summaries` 还会有这轮 turn 的记录吗？**
   提示：看 `AgentLoopSupport.fail(...)` 是否调 `summaryEmitter`。

3. **`TurnExecutor` 用 `cachedThreadPool`。如果同时跑 1000 个 turn 会发生什么？**
   提示：cachedThreadPool 没有大小限制，会无限创建线程。P2 注释里写「可换受控线程池」。

4. **为什么 `ItemEmitter` 同步写 WebSocket 而不是用 reactor？**
   提示：参考 [协议章 §5.1](../03-tech-deep-dive/04-protocol-websocket.md) synchronized(session) 的设计。

5. **如果你想加一个新工具 `git_status`，最少需要改几个文件？**
   提示：1 个新文件 `GitStatusTool.java`，加 `@Component @Tool`。**自动注册**，不需要改 `ToolRegistry`。但要考虑能力 searchText 中文别名（参考 [上下文工程章 §11.2](../03-tech-deep-dive/02-context-engineering.md)）。

6. **读到一半 Cmd+B 跳进了 SAA 的源码，怎么办？**
   提示：Cmd+Alt+← 回来，写一行 note「SAA 内部细节，不读」，继续主线。

7. **`@Component` 的 `BaBiQTokenUsageHook` 是单例。两个 turn 同时跑，token 会混吗？**
   提示：`tokenUsageHook.reset()` 在 buildAgent 时调。如果两个 turn 在同一进程同时跑（无锁），可能。BaBiQ 当前**不支持单进程并发跑多 turn**——是「单用户本地 Agent」的设计简化。

8. **看完 AgentLoop 这一站后，你想下次先读哪个深井？**
   提示：基于你的兴趣选——想懂安全？读 sandbox/。想懂数据库？读 persistence/。想懂上下文？读 context/（配合 deep-dive 章）。

---

## 18. 一句话总结

**从 AgentLoop 这个 100 行的中枢神经系统出发，按「装配 → Hook → Interceptor → Tool → 上游 → 下游 → 上下文入口」8 站读，1.5-2 小时建立完整心智地图。**

- 不要从 main() 或字母顺序读。
- 不要 Cmd+B 跳进 SAA / SQLite / Spring 源码。
- 注释回答 80% 的问题——先读注释再读代码。
- 同步开 walkthrough 章和 deep-dive 章配合：一个是「在哪发生」，一个是「为什么这么设计」，一个是「拿起 IDE 怎么读」。

读完这条路线后，你应该有能力：
- 给一个新需求选准入手类。
- 给一个 bug 现象快速定位调用栈。
- 给一个新同事说清「先读这 8 个文件就够了」。

---

## 19. 延伸阅读

### 配合本章读
- [04-walkthroughs/01-read-file-full-trace.md](../04-walkthroughs/01-read-file-full-trace.md)（同时打开，每读一站对照看一次实际执行）
- [03-tech-deep-dive/01-react-hook-interceptor.md](../03-tech-deep-dive/01-react-hook-interceptor.md)（读完第 2-4 站后看，理解装配的「为什么」）

### 读完本章后推荐的其它路线
- [03-tech-deep-dive/02-context-engineering.md](../03-tech-deep-dive/02-context-engineering.md)（深入第 8 站，理解 P3 全家桶）
- [03-tech-deep-dive/03-security-spotlighting.md](../03-tech-deep-dive/03-security-spotlighting.md)（深入第 4 站的 Sandbox + Spotlighting）
- [03-tech-deep-dive/04-protocol-websocket.md](../03-tech-deep-dive/04-protocol-websocket.md)（深入第 6 站的协议入口）
- [02-reading-path/12-desktop-state.md](12-desktop-state.md)（换条线，看桌面端代码怎么读）

### BaBiQ 元文档
- [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md)（架构总览）
- [`CLAUDE.md`](../../CLAUDE.md)（项目规则，包含「为什么这样写代码」）
- [code-index.md](../code-index.md)（按类名反查文档章节）
- [glossary.md](../glossary.md)（术语表）

---

> **下一步建议**：
> 跟着这条路线读完后端核心（1.5-2 小时），再去配合 deep-dive 章节加深理解。
> 真正想动手时：在 IDE 设好 §14 那 12 个断点，启动后端 + 桌面端，跑一次「读 README 总结」走完整链路。
