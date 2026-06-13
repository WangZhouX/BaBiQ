# 第 2 课: Kotlin 数据建模

## 这一课解决什么

BaBiQ 桌面端有大量“模型”：协议模型、UI 状态模型、画布模型、设置模型。Kotlin 很适合表达这些模型，因为它有 `data class`、`enum class` 和 `sealed interface`。

## `data class`: 描述一坨数据

```kotlin
data class ThreadSummaryInfo(
    val threadId: String,
    val title: String,
    val updatedAt: String,
)
```

`data class` 适合表达“字段集合”。Kotlin 会自动生成 `equals`、`hashCode`、`toString` 和 `copy`。

BaBiQ 的状态更新很依赖 `copy`：

```kotlin
val next = current.copy(connectionState = ConnectionState.Connected)
```

这不是修改旧对象，而是创建一个带有新字段的新对象。

## `enum class`: 固定选项

```kotlin
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
}
```

如果某个值只允许固定几种选择，就适合用 `enum class`。连接状态、屏幕类型、运行状态都属于这一类。

## `sealed interface`: 一组已知子类型

```kotlin
sealed interface ChatMessage {
    data class User(val id: String, val text: String) : ChatMessage
    data class Agent(val id: String, val text: String) : ChatMessage
}
```

`sealed interface` 表示“这组类型是封闭的”。它特别适合 BaBiQ 的 `ThreadItem`：后端可能发来用户消息、Agent 消息、reasoning、工具调用、plan、team 等不同 item。

## 为什么不用一堆 `Map<String, Any>`

因为 `Map<String, Any>` 看起来灵活，但读代码的人不知道里面到底有什么字段，也无法让编译器帮忙检查。

强类型模型的好处是：

- IDE 能提示字段。
- 测试能直接构造对象。
- `when` 能发现漏掉的类型。
- UI 不需要到处手写 JSON 取字段。

## 源码入口

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ThreadModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowGraphModel.kt`

## 小练习

打开 `ThreadModels.kt`，找到 `sealed interface ThreadItem`。

完成三件事：

1. 选三个子类型，例如 `UserMessage`、`AgentMessage`、`Plan`。
2. 写下它们各自代表什么。
3. 找出每个子类型里一个可能为空或有默认值的字段，并解释为什么。

## 自检

1. `data class` 适合表达什么？
2. `enum class` 和 `sealed interface` 的区别是什么？
3. 为什么 `ThreadItem` 适合用 sealed 类型建模？

## 继续阅读

- 下一课: [Gradle 和项目结构](0003-gradle-project-structure.md)

