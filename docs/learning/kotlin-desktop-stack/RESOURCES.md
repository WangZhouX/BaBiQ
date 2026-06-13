# Kotlin 桌面技术栈资源

## 知识(Knowledge)

- [Kotlin Basic syntax overview](https://kotlinlang.org/docs/basic-syntax.html)
  Kotlin 官方基础语法入口。适用于第 1 课，帮助读者理解 `val`、`var`、`fun`、`when`、集合和空安全。

- [Kotlin Coroutines basics](https://kotlinlang.org/docs/coroutines-basics.html)
  Kotlin 官方协程入门。适用于第 5 课和后续协程专题，用来理解为什么 BaBiQ 可以用顺序风格写异步网络逻辑。

- [Kotlin Serialization](https://kotlinlang.org/docs/serialization.html)
  Kotlin 官方序列化文档。适用于第 4 课，帮助读者理解 `@Serializable`、JSON 编码和解码。

- [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/)
  JetBrains 面向 Kotlin 的声明式跨平台 UI 框架入口。适用于第 6 课和后续 Compose Desktop 完整专题。

- [Ktor Client WebSockets](https://ktor.io/docs/client-websockets.html)
  Ktor 官方 WebSocket Client 文档。适用于第 7 课和后续 Ktor WebSocket 完整专题。

- [Gradle Kotlin DSL Primer](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
  Gradle 官方 Kotlin DSL 文档。适用于第 3 课，帮助读者理解 `build.gradle.kts` 不是普通 Kotlin 程序，而是构建脚本。

## 智慧(社区)

- [Kotlin Discussions](https://discuss.kotlinlang.org/)
  Kotlin 官方社区。适用于语言和协程问题的讨论。

- [Kotlin Slack](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up)
  Kotlin 社区常用实时讨论入口。适用于 Compose、Ktor、serialization 等生态问题。

- [JetBrains Compose Multiplatform GitHub](https://github.com/JetBrains/compose-multiplatform)
  Compose Multiplatform 源码和 issue。适用于排查桌面端框架行为、版本变化和边界问题。

## 项目内资源

- [`desktop/build.gradle.kts`](../../../desktop/build.gradle.kts)
  BaBiQ 桌面端实际依赖版本和打包配置。

- [`desktop/src/main/kotlin/com/wzx/babiq/desktop`](../../../desktop/src/main/kotlin/com/wzx/babiq/desktop)
  BaBiQ 桌面端主源码目录。

- [`desktop/src/test/kotlin/com/wzx/babiq/desktop`](../../../desktop/src/test/kotlin/com/wzx/babiq/desktop)
  BaBiQ 桌面端测试目录。初学者应优先读测试，因为测试通常比生产代码更短、更聚焦。

