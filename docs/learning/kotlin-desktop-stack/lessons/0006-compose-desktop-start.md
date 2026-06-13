# 第 6 课: Compose Desktop 启动课

## 这一课解决什么

这一课只是 Compose Desktop 的启动课，不是完整专题。目标是让你看懂 BaBiQ 最外层 UI 如何接入状态。

完整掌握 Compose Desktop 会在后续专题中展开，包括布局、状态提升、副作用、复杂交互、画布、测试和打包。

## Compose 的核心想法

传统 UI 常常像这样思考：

```text
找到按钮 -> 改文字 -> 改颜色 -> 刷新
```

Compose 更像这样思考：

```text
给定当前状态 -> 描述界面应该长什么样
```

状态变了，Compose 会重新执行相关 Composable，生成新的界面描述。

## `@Composable`

```kotlin
@Composable
fun BaBiQDesktopApp(config: DesktopConfig) {
    ...
}
```

带 `@Composable` 的函数可以描述 UI。它不是普通的“执行一次就结束”的函数，Compose 可能因为状态变化多次调用它。

## `remember`

`remember` 用来让某个值在重组之间保留。

BaBiQ 会用它保留 controller，避免每次 UI 重组都创建一个新 controller。

## `collectAsState`

`StateFlow` 属于协程世界，Compose UI 需要把它转成 Compose 能观察的 state。

```kotlin
val appState by controller.state.collectAsState()
```

这样 `AppState` 变化后，UI 会自动重组。

## 源码入口

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/app/BaBiQDesktopApp.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/AppShell.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ChatScreen.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/theme/BaBiQTheme.kt`

## 小练习

打开 `BaBiQDesktopApp.kt`，找到：

1. `remember` 出现在哪里。
2. `collectAsState` 出现在哪里。
3. 哪个组件接收了 `appState`。

然后画出一条很短的线：

```text
ChatController.state -> collectAsState -> AppShell
```

## 自检

1. `@Composable` 函数和普通函数有什么不同？
2. 为什么 controller 要 `remember`？
3. 为什么 `StateFlow` 需要 `collectAsState` 才能驱动 UI？

## 继续阅读

- 下一课: [Ktor WebSocket 启动课](0007-ktor-websocket-start.md)
- 官方资料: [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/)

