# 术语表

## Kotlin

BaBiQ 桌面端的主语言。它运行在 JVM 上，与 Java 生态互通。

## Compose Multiplatform Desktop

JetBrains 的声明式 UI 框架。BaBiQ 用它写桌面端界面。

## Composable

带 `@Composable` 注解的函数。它描述 UI 应该长什么样，而不是手动命令界面一步步变化。

## 重组

Compose 在状态变化后重新执行相关 Composable，以得到新的界面描述。

## Ktor Client

Kotlin 生态的网络客户端。BaBiQ 桌面端用它连接后端 WebSocket。

## WebSocket

一种双向通信协议。BaBiQ 桌面端和后端通过它持续交换 JSON-RPC 消息。

## JSON-RPC

一种基于 JSON 的远程调用协议。BaBiQ 用它表达 `thread/create`、`turn/start`、`item/added` 等请求和通知。

## kotlinx.serialization

Kotlin 官方序列化库。BaBiQ 用它把 JSON 消息转换成强类型 Kotlin 对象。

## Coroutine

Kotlin 协程。它能挂起和恢复，让异步代码看起来接近顺序代码。

## StateFlow

Kotlin 协程生态中的状态流。BaBiQ 用它向 Compose UI 暴露当前 `AppState`。

## Reducer

把旧状态和事件变成新状态的函数。BaBiQ 用 reducer 处理后端事件和 UI 状态更新。

## AppState

BaBiQ 桌面端的全局 UI 状态。Compose 读取它，并在它变化时更新界面。

