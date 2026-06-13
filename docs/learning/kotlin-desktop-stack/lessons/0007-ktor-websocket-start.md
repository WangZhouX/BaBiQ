# 第 7 课: Ktor WebSocket 启动课

## 这一课解决什么

这一课只是 Ktor WebSocket 的启动课。目标是让你看懂 BaBiQ 桌面端如何建立连接、发送文本消息、接收后端事件。

完整掌握 Ktor WebSocket 会在后续专题中展开，包括 session 生命周期、ping、timeout、请求响应匹配、重连、取消、fake transport 测试和 JSON-RPC 端到端实战。

## 为什么需要 WebSocket

BaBiQ 桌面端和后端不是一次请求一次响应那么简单。后端会持续推送事件，例如：

- `turn/started`
- `item/added`
- `item/updated`
- `approval/request`
- `turn/completed`

这些事件要求连接保持打开，所以 WebSocket 比普通 HTTP request 更合适。

## Ktor 在这里负责什么

Ktor Client 是桌面端的网络客户端。BaBiQ 用它做三件事：

1. 建立 WebSocket session。
2. 发送 JSON-RPC 文本消息。
3. 接收后端发回来的文本消息。

## transport 和 client 的分工

BaBiQ 把网络层拆成两层：

```text
AgentClient
  负责 JSON-RPC 语义：请求 id、pending map、解析 response/event

KtorAgentTransport
  负责 WebSocket 传输：连接、发送 text frame、接收 text frame
```

这个边界很重要。以后测试 `AgentClient` 时，可以用假的 transport，不一定真的连后端。

## 源码入口

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentTransport.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/KtorAgentTransport.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`

## 小练习

打开 `KtorAgentTransport.kt`，找出：

1. 创建 WebSocket session 的地方。
2. 发送文本消息的地方。
3. 接收消息并交给回调的地方。

然后打开 `AgentClient.kt`，找出：

1. `pending` map。
2. 创建 `JsonRpcRequest` 的地方。
3. 区分 response 和 notification 的地方。

## 自检

1. 为什么 BaBiQ 不只用普通 HTTP 请求？
2. `AgentClient` 和 `KtorAgentTransport` 的职责有什么区别？
3. 为什么这个拆分有利于测试？

## 继续阅读

- 回到 [README](../README.md)
- 官方资料: [Ktor Client WebSockets](https://ktor.io/docs/client-websockets.html)

