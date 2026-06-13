# 第 3 课: Gradle 和项目结构

## 这一课解决什么

你需要知道 BaBiQ 桌面端代码放在哪里、依赖在哪里声明、测试怎么跑。否则后面学 Compose、Ktor、serialization 时，很容易不知道从哪下手。

## 先看 `desktop/`

```text
desktop/
  build.gradle.kts
  src/main/kotlin/
  src/main/resources/
  src/main/composeResources/
  src/test/kotlin/
```

这些目录的分工是：

- `build.gradle.kts`: 构建脚本，声明 Kotlin、Compose、Ktor、serialization、coroutines 等依赖。
- `src/main/kotlin`: 生产代码。
- `src/test/kotlin`: 测试代码。
- `src/main/resources`: JVM 运行时资源。
- `src/main/composeResources`: Compose 资源。

## `build.gradle.kts` 是什么

它是 Gradle Kotlin DSL。它看起来像 Kotlin，但它的主要作用不是写业务逻辑，而是配置构建。

你会看到：

```kotlin
plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.compose") version "1.11.0"
}
```

这表示桌面端使用 Kotlin/JVM、Kotlin serialization 插件和 Compose 插件。

你也会看到：

```kotlin
implementation("io.ktor:ktor-client-websockets:$ktorVersion")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
```

这些就是后面课程要学的库。

## 常用命令

在 `desktop/` 目录运行：

```powershell
.\gradlew.bat test
```

运行桌面端：

```powershell
.\gradlew.bat run
```

在仓库根目录运行时，要先切目录：

```powershell
cd desktop
.\gradlew.bat test
```

## 源码入口

- `desktop/build.gradle.kts`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/Main.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/app/BaBiQDesktopApp.kt`
- `desktop/src/test/kotlin/com/wzx/babiq/desktop`

## 小练习

打开 `desktop/build.gradle.kts`，回答：

1. Kotlin 版本是多少？
2. Compose 版本是多少？
3. Ktor 版本是多少？
4. 哪一行说明项目使用了 serialization 插件？

## 自检

1. `src/main/kotlin` 和 `src/test/kotlin` 的区别是什么？
2. `build.gradle.kts` 是普通业务代码吗？
3. 为什么学习技术栈前要先看依赖版本？

## 继续阅读

- 下一课: [JSON 与 kotlinx.serialization](0004-json-serialization.md)
- 官方资料: [Gradle Kotlin DSL Primer](https://docs.gradle.org/current/userguide/kotlin_dsl.html)

