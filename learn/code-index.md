# 🔍 代码反查索引

> 你在 IDEA 里打开一个源文件，想知道哪一章讲过它？这里就是答案。
> 当前这份索引随章节同步扩充，未列出的文件表示对应章节尚未完成。

---

## 桌面端（Kotlin）

| 类 / 文件 | 路径 | 在哪一章讲过 |
|---|---|---|
| `AppState` | [`desktop/.../state/AppState.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt) | [02-reading-path/12 §1 AppState](02-reading-path/12-desktop-state.md#1-appstate--所有界面状态的快照) |
| `ChatReducer` | [`desktop/.../state/ChatReducer.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt) | [02-reading-path/12 §3 ChatReducer](02-reading-path/12-desktop-state.md#3-chatreducer--纯函数归约) |
| `ChatController` | [`desktop/.../state/ChatController.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt) | [02-reading-path/12 §4 ChatController](02-reading-path/12-desktop-state.md#4-chatcontroller--协调副作用与状态) |
| `ChatMessage`、`AgentEvent`、`PendingApproval` 等 | [`desktop/.../state/UiModels.kt`](../desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt) | [02-reading-path/12 §2 UiModels](02-reading-path/12-desktop-state.md#2-uimodels--用-sealed-interface-表达几种可能) |
| `ChatReducerTest` | [`desktop/.../state/ChatReducerTest.kt`](../desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatReducerTest.kt) | [02-reading-path/12 §6 动手实操](02-reading-path/12-desktop-state.md#-动手实操) |

---

## 后端（Java）

> 后端章节正在筹备，下方先列出主要文件位置作为占位。

| 类 / 文件 | 路径 | 状态 |
|---|---|---|
| `AgentLoop` | [`backend/.../agent/AgentLoop.java`](../backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java) | 待写章节 |
| `ReActStrategy` | [`backend/.../agent/ReActStrategy.java`](../backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java) | 待写章节 |
| `Spotlighter` | [`backend/.../security/Spotlighter.java`](../backend/src/main/java/com/wzx/babiq/server/security/Spotlighter.java) | 待写章节 |
| `SpotlightingToolInterceptor` | [`backend/.../interceptor/SpotlightingToolInterceptor.java`](../backend/src/main/java/com/wzx/babiq/server/interceptor/SpotlightingToolInterceptor.java) | 待写章节 |
| `TurnSummaryEmitter` | [`backend/.../observability/TurnSummaryEmitter.java`](../backend/src/main/java/com/wzx/babiq/server/observability/TurnSummaryEmitter.java) | 待写章节 |
| `JsonRpcWebSocketHandler` | [`backend/.../api/JsonRpcWebSocketHandler.java`](../backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java) | 待写章节 |
| Flyway 迁移 V2-V5 | [`backend/src/main/resources/db/migration/`](../backend/src/main/resources/db/migration/) | 待写章节 |
| MCP `McpClientManager` 等 | [`backend/.../mcp/`](../backend/src/main/java/com/wzx/babiq/server/mcp/) | 待写章节 |

---

## 如何使用这份索引

1. 在 IDE 里看到一个不认识的类（比如 `ChatReducer`）
2. 打开 [code-index.md](code-index.md) 搜 `ChatReducer`
3. 点链接跳到对应学习章节，看清它的设计意图和上下游关系
4. 再回 IDE 看代码，效率比硬啃高得多
