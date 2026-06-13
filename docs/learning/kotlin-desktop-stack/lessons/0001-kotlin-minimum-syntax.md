# 第 1 课: Kotlin 最小语法包

## 这一课解决什么

这一课不是完整 Kotlin 教程。它只教你读 BaBiQ 桌面端源码最先需要的语法。

## 你会看到的 Kotlin

### `val` 和 `var`

```kotlin
val threadId = "thr_01"
var retryCount = 0
```

`val` 只能赋值一次，适合默认选择。`var` 可以重新赋值，适合计数、临时状态和可变草稿。

### `fun`

```kotlin
fun displayName(value: String): String {
    return value.ifBlank { "未命名" }
}
```

`fun` 定义函数。Kotlin 经常把小函数写得很短。

### 类型和类型推断

```kotlin
val model: String = "qwen-plus"
val provider = "dashscope"
```

第一行显式写了类型，第二行让 Kotlin 自己推断。BaBiQ 里两种都会看到。

### 空安全

```kotlin
val errorMessage: String? = null
```

`String?` 表示这个值可能为空。协议字段、可选配置、错误信息经常会这样写。

### 默认参数

```kotlin
data class ModelInfo(
    val id: String,
    val displayName: String = id,
)
```

默认参数让调用方不用每次都传完整字段。协议模型里常用它表达“没有值时用默认语义”。

### `when`

```kotlin
val label = when (state) {
    ConnectionState.Connected -> "已连接"
    ConnectionState.Connecting -> "连接中"
    ConnectionState.Disconnected -> "已断开"
}
```

`when` 很像更强的 `switch`。当它处理 `enum class` 或 `sealed interface` 时，编译器能帮你发现漏掉的分支。

### 集合

```kotlin
val messages = listOf("你好", "正在处理", "完成")
val visible = messages.filter { it.isNotBlank() }
```

BaBiQ 的 UI 状态里有很多列表，例如聊天消息、运行记录、工具调用、技能列表。

## 源码入口

先读这些短而密集的文件：

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ProviderModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/SettingsModels.kt`

不要试图一次读完。先找 `val`、`data class`、`enum class`、`String?`。

## 小练习

在 `UiModels.kt` 中找出：

- 三个 `enum class`
- 三个带 `String?` 的字段
- 一个带默认值的 `data class` 字段

把它们翻译成自然语言，例如：“这个字段可能为空，因为当前没有错误信息”。

## 自检

1. `val` 和 `var` 的区别是什么？
2. `String` 和 `String?` 的区别是什么？
3. 为什么 `when` 适合处理连接状态？

## 继续阅读

- 下一课: [Kotlin 数据建模](0002-kotlin-data-modeling.md)
- 官方资料: [Kotlin Basic syntax overview](https://kotlinlang.org/docs/basic-syntax.html)

