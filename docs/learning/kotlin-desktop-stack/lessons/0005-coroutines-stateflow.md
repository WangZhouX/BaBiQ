# 第 5 课: 协程与 StateFlow

## 这一课解决什么

桌面应用不能在等待网络时卡住界面。BaBiQ 用 Kotlin 协程处理后台任务，用 `StateFlow` 把最新 UI 状态交给 Compose。

## 协程先理解成什么

你可以先把协程理解成“可以暂停和恢复的任务”。它让异步代码更像顺序代码。

例如网络请求可能需要等待，但 UI 线程不能被堵住：

```kotlin
scope.launch {
    val providers = client.listProviders()
    updateState(providers)
}
```

## `CoroutineScope`

`CoroutineScope` 是协程运行的范围。BaBiQ 的 controller 和 transport 都有自己的 scope，用来管理后台任务生命周期。

## `SupervisorJob`

`SupervisorJob` 的意义是：一个子任务失败时，不一定拖垮整个 scope。对于桌面端重连、事件监听这类场景，这很有用。

## `StateFlow`

`StateFlow` 表示“总有一个当前值”的状态流。

BaBiQ 中常见模式：

```kotlin
private val _state = MutableStateFlow(initialState)
val state: StateFlow<AppState> = _state
```

外部只能读 `state`，内部通过 `_state` 更新。这样可以保护状态边界。

## 状态怎么更新

状态更新通常不是“改旧对象”，而是生成新对象：

```kotlin
_state.value = _state.value.copy(connectionState = ConnectionState.Connected)
```

这种风格让 Compose 更容易判断状态变化，也让测试更简单。

## 源码入口

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/KtorAgentTransport.kt`
- `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

## 小练习

打开 `ChatController.kt`，找出：

1. `_state` 在哪里定义。
2. `state` 如何暴露给外部。
3. 至少一个调用 `copy(...)` 更新状态的地方。

然后用一句话解释：为什么不直接把 `AppState` 的字段做成可变属性？

## 自检

1. 协程为什么适合网络请求？
2. `MutableStateFlow` 和 `StateFlow` 的区别是什么？
3. 为什么不可变状态更适合 Compose？

## 继续阅读

- 下一课: [Compose Desktop 启动课](0006-compose-desktop-start.md)
- 官方资料: [Kotlin Coroutines basics](https://kotlinlang.org/docs/coroutines-basics.html)

