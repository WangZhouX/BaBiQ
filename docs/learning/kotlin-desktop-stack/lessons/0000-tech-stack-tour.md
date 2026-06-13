# 第 0 课: 认识 BaBiQ 桌面端技术栈

## 这一课解决什么

你可能现在只知道“BaBiQ 桌面端是 Kotlin 写的”，但还分不清 Kotlin、Compose、Ktor、协程、serialization 各自是什么。这一课只做一件事：建立地图。

## 你需要先知道什么

不需要任何 Kotlin 经验。只要知道 BaBiQ 有一个 `desktop/` 目录和一个 `backend/` 目录就够了。

## 五个名字分别是什么

| 名字 | 一句话解释 | 在 BaBiQ 里负责 |
| --- | --- | --- |
| Kotlin | 编程语言 | 写桌面端代码 |
| Compose Multiplatform Desktop | 桌面 UI 框架 | 画窗口、按钮、聊天列表、设置页 |
| kotlinx.coroutines | 异步工具 | 后台连接后端、收事件、更新状态 |
| kotlinx.serialization | JSON 工具 | 把后端 JSON 变成 Kotlin 对象 |
| Ktor Client | 网络客户端 | 通过 WebSocket 连接后端 |

## 先看这条链路

```text
用户点击发送
  -> Compose UI 调用回调
  -> ChatController 接管动作
  -> AgentClient 组装 JSON-RPC 请求
  -> KtorAgentTransport 通过 WebSocket 发给后端
  -> 后端返回 item/added、item/updated 等事件
  -> ChatReducer 把事件变成新的 AppState
  -> Compose 根据 AppState 重新显示界面
```

你以后读源码时，脑子里一直保留这条链路，就不容易迷路。

## 源码入口

- `desktop/build.gradle.kts`: 看项目用了哪些技术。
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/app/BaBiQDesktopApp.kt`: 看 UI、状态、controller 怎么接起来。
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`: 看桌面端怎么请求后端。
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt`: 看后端事件怎么变成 UI 状态。

## 初学者常见误解

**误解: Compose 就是 Kotlin。**  
不是。Kotlin 是语言，Compose 是用 Kotlin 写 UI 的框架。

**误解: Ktor 和后端 Spring Boot 是同一个东西。**  
不是。BaBiQ 桌面端用 Ktor Client 发请求，后端是 Java Spring Boot 接请求。

**误解: 协程就是多线程。**  
不完全是。协程可以挂起和恢复，背后仍然由线程执行，但你通常用更清晰的顺序风格写异步逻辑。

## 小练习

打开 `desktop/build.gradle.kts`，找出这些依赖：

- `org.jetbrains.compose`
- `ktor-client-websockets`
- `kotlinx-serialization-json`
- `kotlinx-coroutines-core`

然后把它们分别对应到上面的五个名字。

## 自检

如果你能回答下面三个问题，就可以进入下一课：

1. Kotlin 和 Compose 是什么关系？
2. BaBiQ 桌面端为什么需要 Ktor？
3. 后端发来的 JSON 为什么不能直接拿来当 UI 状态用？

## 继续阅读

- 下一课: [Kotlin 最小语法包](0001-kotlin-minimum-syntax.md)
- 参考: [技术栈地图](../reference/tech-stack-map.md)

