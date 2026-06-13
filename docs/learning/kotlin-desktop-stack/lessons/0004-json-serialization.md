# 第 4 课: JSON 与 kotlinx.serialization

## 这一课解决什么

BaBiQ 后端通过 WebSocket 发来的不是 Kotlin 对象，而是 JSON 文本。桌面端必须把 JSON 解析成强类型对象，UI 才能安全地使用它们。

## JSON 到 Kotlin 的转换

后端可能发来类似这样的消息：

```json
{
  "jsonrpc": "2.0",
  "method": "item/added",
  "params": {
    "threadId": "thr_01",
    "turnId": "turn_01",
    "item": {
      "id": "it_01",
      "type": "agentMessage",
      "text": "你好"
    }
  }
}
```

桌面端不希望到处写：

```kotlin
json["params"]?.jsonObject?.get("threadId")
```

所以 BaBiQ 用 kotlinx.serialization 把它变成 `ServerEvent.ItemAdded`、`ThreadItem.AgentMessage` 这样的类型。

## `@Serializable`

带 `@Serializable` 的类可以被 serialization 库编码和解码。

```kotlin
@Serializable
data class JsonRpcRequest(
    val id: Long,
    val method: String,
    val params: JsonElement,
)
```

## `JsonElement`

有些字段结构很灵活，不能马上转成固定 data class。此时用 `JsonElement` 暂存原始 JSON。

BaBiQ 的 JSON-RPC 消息就会用到它，因为不同 method 的 `params` 结构不同。

## 自定义 serializer

`ThreadItem` 有很多类型，真正类型要看 JSON 里的 `type` 字段：

```json
{ "type": "reasoning", "text": "..." }
```

这种场景需要自定义 serializer：先读原始 JSON，再根据 `type` 选择具体 data class。

## 源码入口

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ProtocolJson.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/JsonRpcModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ThreadModels.kt`
- `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ThreadItemJsonTest.kt`
- `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ProtocolJsonTest.kt`

## 小练习

打开 `ThreadModels.kt`，找到自定义 serializer 中处理 `type` 的 `when`。

回答：

1. `agentMessage` 会被解析成哪个 Kotlin 类型？
2. 未知 `type` 会怎么处理？
3. 为什么保留未知类型比直接报错更适合长期演进的协议？

## 自检

1. `@Serializable` 的作用是什么？
2. `JsonElement` 适合什么场景？
3. 为什么 `ThreadItem` 不能只靠普通 `data class` 自动解析？

## 继续阅读

- 下一课: [协程与 StateFlow](0005-coroutines-stateflow.md)
- 官方资料: [Kotlin Serialization](https://kotlinlang.org/docs/serialization.html)

