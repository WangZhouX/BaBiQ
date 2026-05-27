# 📖 BaBiQ 术语表

> 第一次见到某个术语？在这里查一查再回去看代码。

---

## A

- **Agent Loop**：Agent 的主循环。模型决策 → 调工具 → 反馈结果 → 再次决策，直到结束。BaBiQ 后端的 [`AgentLoop.java`](../backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java) 主流程被严格限制在 ≤50 行。
- **Approval（审批）**：当 Agent 调用高风险工具（write_file、exec_shell、apply_patch）时，先暂停等用户决策。三档反馈：`approve` / `deny` / `edit`。

## C

- **ChatController**：桌面端协调层。把用户操作翻译成对后端的网络请求，把后端事件交给 ChatReducer 归约。带协程作用域、StateFlow、断线重连逻辑。
- **ChatReducer**：纯函数。输入 `(旧 state, event)`，输出 `新 state`。不做网络、不启协程、不依赖 Compose API。
- **Compose Desktop**：JetBrains 的桌面 UI 框架。声明式 UI，类似 React/SwiftUI，但用 Kotlin 写。
- **Coroutine（协程）**：Kotlin 的轻量级并发原语。比线程便宜得多，用 `suspend` 关键字标记可挂起函数。

## D

- **data class**：Kotlin 关键字。自动生成 `equals` / `hashCode` / `copy` / `toString` 的"数据载体"类。BaBiQ 桌面端的状态都用 data class。

## H

- **HITL（Human-in-the-Loop）**：人在回路。Agent 执行高风险动作前暂停，等人审批后再继续。BaBiQ 用 Spring AI Alibaba 的 `HumanInTheLoopHook` 实现。
- **Hook**：横切关注点的扩展点，挂在 Agent 生命周期的特定阶段（BEFORE_MODEL / AFTER_MODEL / BEFORE_TOOL / AFTER_TOOL 等）。

## I

- **Interceptor**：链式包装器，可决定是否继续往下传。BaBiQ 用它做 Spotlighting 工具输出包装、工具观测埋点等。
- **Item**：协议级别的"对话原子"。一次 turn 里产生多种 Item：`userMessage`、`agentMessage`、`commandExecution`、`fileChange`、`turnSummary` 等。

## J

- **JSON-RPC 2.0**：BaBiQ 内层协议。Desktop ↔ Backend 通信走 WebSocket + JSON-RPC，方法名如 `turn/start`、`approval/respond`。

## K

- **Ktor**：Kotlin 生态的 HTTP/WebSocket 框架。BaBiQ 桌面端用 Ktor Client 连后端 WebSocket。

## M

- **MCP（Model Context Protocol）**：Anthropic 提出的"Agent ↔ 工具"协议。BaBiQ P2-6 接入了官方 MCP Java SDK，把外部 MCP 工具合并进本地 ToolRegistry。

## R

- **ReactAgent**：Spring AI Alibaba 提供的 ReAct 范式 Agent 抽象。BaBiQ 的 Agent 主体就是它。
- **Reducer**：状态归约器。借自 Redux / Elm 的"纯函数 + 事件流"模式。BaBiQ 桌面端用 [ChatReducer](02-reading-path/12-desktop-state.md) 实现。

## S

- **sealed interface / sealed class**：Kotlin 的"封闭"类层级。子类必须在同一文件或同一模块内声明。配合 `when` 表达式做穷尽分支检查，编译器会强制处理所有情况。BaBiQ 桌面端用它表达 `ChatMessage`、`AgentEvent` 等"有限可能性"。
- **Spotlighting**：把工具输出包成 `<untrusted-data>` 标签注入模型上下文，配合 system prompt 安全规则防 indirect prompt injection。
- **StateFlow**：Kotlin Coroutines 的"热流"。永远持有一个当前值，新订阅者立即拿到当前值。BaBiQ 桌面端用 `StateFlow<AppState>` 驱动 Compose 重组。
- **suspend**：Kotlin 关键字。修饰一个"可以暂停又恢复"的函数。只能在协程或其他 suspend 函数里调用。

## T

- **Thread / Turn / Item**：BaBiQ 三层状态模型。
  - **Thread**：一个会话，长生命周期。
  - **Turn**：一轮对话，五态机（CREATED / RUNNING / WAITING_APPROVAL / COMPLETED / FAILED / CANCELED）。
  - **Item**：Turn 里产生的"原子事件"。
- **TurnSummary**：每轮结束时后端发出的协议 item，包含 tokens、duration、toolCalls、cost 等指标。

## W

- **when 表达式**：Kotlin 的"超级 switch"。可以匹配类型、值、范围、布尔条件。配合 sealed 做穷尽匹配。
- **WebSocket**：全双工长连接协议。BaBiQ 内层协议的传输层。

---

## 待补充

下列术语等对应章节落地后补：

- BEFORE_MODEL / AFTER_MODEL / BEFORE_TOOL / AFTER_TOOL（Hook 阶段）
- ApprovalEngine（已被 HumanInTheLoopHook 替代）
- MemorySaver / Checkpointer
- PathGuard / Sandbox Mode
- Flyway / MyBatis-Plus
- A2A（Agent-to-Agent 协议）
