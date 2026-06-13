# BaBiQ 桌面端技术栈地图

这张地图回答一个问题：当你在 BaBiQ 桌面端看到某个技术名时，它到底负责什么，应该去哪里看源码。

## 总览

| 技术 | 在 BaBiQ 中负责什么 | 主要入口 |
| --- | --- | --- |
| Kotlin | 桌面端主语言 | `desktop/src/main/kotlin/com/wzx/babiq/desktop` |
| Compose Multiplatform Desktop | 桌面 UI | `app/`、`ui/`、`flowcanvas/` |
| kotlinx.coroutines | 异步任务、状态流、重连循环 | `state/ChatController.kt`、`client/KtorAgentTransport.kt` |
| kotlinx.serialization | JSON 协议模型编码和解码 | `protocol/` |
| Ktor Client | WebSocket 客户端 | `client/` |
| Gradle Kotlin DSL | 构建、测试、打包和依赖管理 | `desktop/build.gradle.kts` |

## 一条真实链路

用户在输入框发送消息后，桌面端大致经过这条路径：

```text
Compose UI
  -> ChatController
  -> AgentClient
  -> KtorAgentTransport
  -> WebSocket JSON-RPC
  -> backend /ws/agent
  -> ServerEvent
  -> ChatReducer
  -> AppState
  -> Compose UI 重组
```

## 目录怎么读

| 目录 | 读什么 |
| --- | --- |
| `app/` | 应用装配，把 controller、state 和 UI 接起来 |
| `client/` | 后端连接、JSON-RPC 请求、事件接收 |
| `protocol/` | 前后端共享的协议数据模型 |
| `state/` | AppState、UI 模型、reducer、controller |
| `ui/` | Compose UI 组件 |
| `flowcanvas/` | P8 画布编排编辑器的模型、布局和复杂交互 |
| `runtime/` | 桌面端启动和管理后端进程 |

## 初学者优先顺序

不要从最大文件开始硬读。建议按这个顺序：

1. `desktop/build.gradle.kts`
2. `app/BaBiQDesktopApp.kt`
3. `state/AppState.kt`
4. `state/UiModels.kt`
5. `protocol/ThreadModels.kt`
6. `protocol/JsonRpcModels.kt`
7. `client/AgentClient.kt`
8. `client/KtorAgentTransport.kt`
9. `ui/shell/AppShell.kt`
10. `ui/chat/ChatScreen.kt`

