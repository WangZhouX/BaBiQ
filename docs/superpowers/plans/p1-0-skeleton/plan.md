# P1-0: Monorepo 骨架 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **v2 (2026-05-21)**: 版本矩阵升级到 2026-05 最新稳定;修复 critic v1 评审的 3 个 BLOCKER + 4 个 SHOULD FIX。

**Goal:** 把当前单 module 项目重构成 Monorepo:`backend/`(Spring Boot)+ `desktop/`(Compose Kotlin),两者完全独立项目,各自能 `build` 和 `run`。

**Architecture:** 根目录只放 git/文档,**不引父 POM 聚合**(YAGNI)。`backend/` 自带 mvnw,继承 spring-boot-starter-parent;`desktop/` 自带 gradlew(Task 6 第一步生成,锁定 Gradle 8.13),独立 Kotlin 项目。

**Tech Stack (2026-05 最新稳定):**
- Java **21 LTS**(Spring Boot 3.5.x 官方支持范围;不冒险用 Java 25)
- Spring Boot **3.5.14**(2026-04-23 发布,3.5 线最新)
- Kotlin **2.3.21**(GA stable,Gradle 7.6.3-9.3.0 全支持)
- Compose Multiplatform **1.11.0**(官方配 Kotlin 2.3.21)
- Gradle **8.13**(通过 wrapper 锁定;**绝对不要用全局 gradle 9.x**,Kotlin 2.3 最高支持 9.3 但 wrapper 8.13 更稳)

**Master Plan Reference:** [2026-05-21-p1-master.md](../2026-05-21-p1-master.md)

**Milestone:** M0(详见 master plan §4)

---

## Files Touched

### Created
- `backend/` (目录)
- `desktop/build.gradle.kts`
- `desktop/settings.gradle.kts`
- `desktop/gradle.properties`
- `desktop/gradlew`, `desktop/gradlew.bat`, `desktop/gradle/wrapper/*`(Task 6 第 1 步生成)
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/Main.kt`
- `desktop/src/main/resources/`(空目录占位)
- `backend/src/main/resources/application.yml`

### Modified
- `backend/src/main/java/com/wzx/babiq/server/BaBiQApplication.java`(从 `com.wzx.babiq.BaBiQApplication` 改包)
- `backend/src/test/java/com/wzx/babiq/server/BaBiQApplicationTests.java`(同上)
- `backend/pom.xml`(从根迁移,调整 groupId + Java 版本)
- `.gitignore`(移除 `README.md` 这个错配置 + 新增 desktop/build, desktop/.gradle)
- `README.md`(反映 Monorepo)
- `docs/ARCHITECTURE.md` §3(同步 Monorepo 决策,Task 9 中处理)

### Moved
- 原 `src/` → `backend/src/`
- 原 `pom.xml` → `backend/pom.xml`
- 原 `mvnw` / `mvnw.cmd` / `.mvn/` → `backend/`

### Deleted
- 原 `src/main/resources/application.properties`(被 `application.yml` 替换)

---

## Pre-flight Check

> 所有 PowerShell 命令默认在 `F:\wwwxxxx\BaBiQ` 下执行,后续步骤如需切目录会显式 `cd`。

- [ ] **Step 0.1: 确认当前在 BaBiQ 项目根**

Run (PowerShell):
```powershell
cd F:\wwwxxxx\BaBiQ
ls
```

Expected: 看到 `README.md`, `pom.xml`, `src/`, `mvnw`, `mvnw.cmd`, `.mvn/`, `docs/`。

- [ ] **Step 0.2: 检查 git 状态**

Run:
```powershell
git status
```

Expected — 任一情况:
- 已是 git 仓库:正常输出
- 不是 git 仓库:`fatal: not a git repository`,Task 1 会先 `git init`

- [ ] **Step 0.3: 检查 Java 版本(必须 21+)**

Run:
```powershell
java -version
```

> ⚠️ **决策**: 本 plan 锁定 **JDK 21 LTS**。原 pom.xml 写的是 `<java.version>25</java.version>`,但 Spring Boot 3.5.x 官方测试矩阵不含 25,**Task 4 必须把 java.version 改为 21**。

Expected: 输出 OpenJDK 21.x 或 Oracle JDK 21.x。
**如果不是 21.x**,请先用 SDKMAN / Choco / Scoop 装 JDK 21,然后再继续。

- [ ] **Step 0.4: 检查 Gradle(可选,Task 6 会自建 wrapper)**

Run:
```powershell
gradle -v
```

Expected — 任一情况:
- 系统装了 Gradle(任意版本 ≥ 7.0):Task 6 第 1 步用它生成 wrapper(8.13),之后全程用 `.\gradlew.bat`
- 没装 Gradle:Task 6 第 0 步需要临时装(`choco install gradle` 或下载 zip 解压加 PATH)

> ⚠️ **关键**: **永远不要直接用全局 `gradle run`**,即使版本兼容。本 plan 一律走 `.\gradlew.bat`,确保所有人版本一致。

---

## Task 1: Git 初始化 + 修复 .gitignore

**Files:**
- Create/Use: `.git/`
- Modify: `.gitignore`

- [ ] **Step 1.1: 初始化 git(若需要)**

Run:
```powershell
git status 2>&1
# 若输出 "fatal: not a git repository":
git init
```

Expected: `Initialized empty Git repository in F:\wwwxxxx\BaBiQ\.git\` 或已是仓库。

- [ ] **Step 1.2: 修复 .gitignore(BLOCKER 修复)**

> ⚠️ 现有 `.gitignore` **第 1 行就是 `README.md`**,这是项目初始化时的错配置,会导致后续 `git add .` 始终跳过 README,**baseline commit 会丢 README,Task 8 修改 README 也无法被跟踪**。必须先修。

Edit `.gitignore`,**删除首行 `README.md`** 这一行。

验证:
```powershell
Get-Content .gitignore -TotalCount 3
```

Expected: 输出不再以 `README.md` 开头(应该是 `target/` 或注释)。

- [ ] **Step 1.3: 提交 baseline**

Run:
```powershell
git add .
git status
```

Expected: `README.md` 出现在 staged 列表(确认上一步修复生效)。

```powershell
git commit -m "chore: baseline before monorepo restructure"
```

Expected: commit 成功,包含 README.md 在内的所有文件。

---

## Task 2: 创建 backend/ 目录,迁移现有文件

**Files:**
- Create: `backend/` 目录
- Move (git mv): `src/` → `backend/src/`, `pom.xml` → `backend/pom.xml`, `mvnw` → `backend/mvnw`, `mvnw.cmd` → `backend/mvnw.cmd`, `.mvn/` → `backend/.mvn/`

- [ ] **Step 2.1: 创建 backend 目录**

Run:
```powershell
mkdir backend
```

Expected: 出现空目录 `backend/`。

- [ ] **Step 2.2: 用 git mv 迁移文件(保留历史)**

Run:
```powershell
git mv src backend/src
git mv pom.xml backend/pom.xml
git mv mvnw backend/mvnw
git mv mvnw.cmd backend/mvnw.cmd
git mv .mvn backend/.mvn
```

> 若以上有文件未被 git 跟踪而报错,可用 PowerShell 替代: `Move-Item -Path src -Destination backend\src` 等。但优先尝试 `git mv` 保留历史。

Expected: 文件全部移动到 backend/ 下。

- [ ] **Step 2.3: 验证目录结构**

Run:
```powershell
ls
ls backend
```

Expected — 根目录仅剩:`README.md`, `.gitignore`, `.gitattributes`, `docs/`, `backend/`(可能加 `.idea/` 和 `.git/`)。
backend 下: `src/`, `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`。

- [ ] **Step 2.4: Commit**

Run:
```powershell
git add -A
git commit -m "chore(p1-0): move existing project into backend/"
```

Expected: 一次 commit,显示一堆 rename。

---

## Task 3: Backend package 重命名 → `com.wzx.babiq.server`

**Files:**
- Move + Modify: `backend/src/main/java/com/wzx/babiq/BaBiQApplication.java` → `backend/src/main/java/com/wzx/babiq/server/BaBiQApplication.java`
- Move + Modify: `backend/src/test/java/com/wzx/babiq/BaBiQApplicationTests.java` → `backend/src/test/java/com/wzx/babiq/server/BaBiQApplicationTests.java`

- [ ] **Step 3.1: 创建新 package 目录**

Run:
```powershell
mkdir backend\src\main\java\com\wzx\babiq\server
mkdir backend\src\test\java\com\wzx\babiq\server
```

- [ ] **Step 3.2: 移动 BaBiQApplication.java**

Run:
```powershell
git mv backend\src\main\java\com\wzx\babiq\BaBiQApplication.java backend\src\main\java\com\wzx\babiq\server\BaBiQApplication.java
```

- [ ] **Step 3.3: 修改 BaBiQApplication.java 的 package 声明**

Edit `backend/src/main/java/com/wzx/babiq/server/BaBiQApplication.java`,完整内容应为:
```java
package com.wzx.babiq.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BaBiQApplication {

	public static void main(String[] args) {
		SpringApplication.run(BaBiQApplication.class, args);
	}

}
```

- [ ] **Step 3.4: 移动 + 修改 BaBiQApplicationTests.java**

Run:
```powershell
git mv backend\src\test\java\com\wzx\babiq\BaBiQApplicationTests.java backend\src\test\java\com\wzx\babiq\server\BaBiQApplicationTests.java
```

Edit `backend/src/test/java/com/wzx/babiq/server/BaBiQApplicationTests.java`:
```java
package com.wzx.babiq.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BaBiQApplicationTests {

	@Test
	void contextLoads() {
	}

}
```

- [ ] **Step 3.5: 验证旧 package 目录无残留 .java + package 声明文本校验(安全做法)**

Run:
```powershell
ls backend\src\main\java\com\wzx\babiq
ls backend\src\test\java\com\wzx\babiq
Select-String -Path backend\src\main\java\com\wzx\babiq\server\BaBiQApplication.java -Pattern "^package com\.wzx\.babiq\.server;"
Select-String -Path backend\src\test\java\com\wzx\babiq\server\BaBiQApplicationTests.java -Pattern "^package com\.wzx\.babiq\.server;"
```

Expected:
- 前两行: 两个目录下**只有 `server` 子目录**,没有 .java 文件直接挂在 `babiq\` 下
- 后两行: 两个 .java 文件的首行 package 声明都精确匹配 `com.wzx.babiq.server`

> 加入文本级校验是为了让 package 声明错误在此立即暴露,而不是等到 Task 4 编译时才发现,降低回溯成本。

> ⚠️ **不要用 `Remove-Item -Recurse -Force backend\src\main\java\com\wzx\babiq`**,那会连 `server/` 一起删掉。如果上一步验证确认无残留,直接跳到 Step 3.6。如果意外有残留 .java,手动 `Remove-Item` 单个文件名,不要用 `-Recurse`。

- [ ] **Step 3.6: Commit(编译验证留到 Task 4 末尾统一做)**

> 📌 此处不立即编译,因为 backend/pom.xml 的 `<java.version>25</java.version>` 在当前 JDK 21 上会失败。Task 4 修完 pom.xml 后,Step 4.5 / 4.6 / 4.7 会统一做编译 + 测试 + 打包验证。如果你执意要在此先验证编译,**必须先跳到 Task 4 完成 Step 4.1 / 4.2 / 4.3 再回来**。

```powershell
git add -A
git commit -m "refactor(p1-0): rename package com.wzx.babiq -> com.wzx.babiq.server"
```

---

## Task 4: 调整 `backend/pom.xml`(Java 版本 + Web Starter + artifact)

**Files:**
- Modify: `backend/pom.xml`

> 📌 **Task 顺序说明**: Task 3 只做 package 重命名(不做编译验证),编译/测试验证统一放在本 Task 末尾。原因:Task 4 的 pom.xml 修改(降 Java 版本 + 加 web starter)是编译能通过的前提,必须先做完再验证。

- [ ] **Step 4.1: 修复 `<java.version>` (BLOCKER 修复)**

> ⚠️ 现有 pom.xml 写的是 `<java.version>25</java.version>`,系统是 JDK 21,**直接编译会因 `--release 25` 失败**。

Edit `backend/pom.xml`,定位:
```xml
<properties>
	<java.version>25</java.version>
</properties>
```

改为:
```xml
<properties>
	<java.version>21</java.version>
</properties>
```

- [ ] **Step 4.2: 加入 `spring-boot-starter-web` (BLOCKER 修复)**

> ⚠️ 当前 pom.xml 只有 `spring-boot-starter`(纯 IoC,**无内嵌 Tomcat**),应用启动后会立即退出,**不会绑定端口 8080**。Step 5.3、Step 9.3、Done Criteria 全部会失败。
> P1-1 反正要做 WebSocket,P1-0 阶段就把 web starter 加上完全合理。

Edit `backend/pom.xml`,定位:
```xml
<dependency>
	<groupId>org.springframework.boot</groupId>
	<artifactId>spring-boot-starter</artifactId>
</dependency>
```

改为:
```xml
<dependency>
	<groupId>org.springframework.boot</groupId>
	<artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

> `spring-boot-starter-web` 自身就传递依赖了 `spring-boot-starter`,不会丢功能。

- [ ] **Step 4.3: 修改 groupId / artifactId / name**

Edit `backend/pom.xml`,定位:
```xml
<groupId>com.wzx</groupId>
<artifactId>BaBiQ</artifactId>
<version>0.0.1-SNAPSHOT</version>
<name>BaBiQ</name>
<description>BaBiQ</description>
```

改为:
```xml
<groupId>com.wzx.babiq</groupId>
<artifactId>babiq-server</artifactId>
<version>0.0.1-SNAPSHOT</version>
<name>babiq-server</name>
<description>BaBiQ Agent Server (P1)</description>
```

- [ ] **Step 4.4: Lombok 暂态决策(记录,不动手)**

> 📌 master plan D3 决定 Item 模型用 `sealed interface + records`,Lombok 长期可能下线。但 P1-0 阶段**保留 Lombok**(YAGNI),P1-1 写第一个业务类时再决定是否清理。本步无代码操作,仅显式记录此决策。

- [ ] **Step 4.5: 验证编译(Task 3 重命名 + Task 4 pom 修复后的统一验证)**

Run:
```powershell
cd backend
.\mvnw.cmd clean compile
cd ..
```

Expected: `BUILD SUCCESS`,无 "release version 25 not supported" 或 "package does not exist" 错误。

- [ ] **Step 4.6: 验证测试**

Run:
```powershell
cd backend
.\mvnw.cmd test
cd ..
```

Expected: `BUILD SUCCESS`,1 test (`contextLoads`) 通过。

- [ ] **Step 4.7: 验证打包**

Run:
```powershell
cd backend
.\mvnw.cmd package -DskipTests
ls target\*.jar
cd ..
```

Expected: `backend/target/babiq-server-0.0.1-SNAPSHOT.jar` 存在。

- [ ] **Step 4.8: Commit**

```powershell
git add -A
git commit -m "chore(p1-0): rename artifact + lock Java 21 + add web starter"
```

---

## Task 5: `application.properties` → `application.yml`

**Files:**
- Delete: `backend/src/main/resources/application.properties`
- Create: `backend/src/main/resources/application.yml`

- [ ] **Step 5.1: 删除旧 properties**

Run:
```powershell
git rm backend\src\main\resources\application.properties
```

- [ ] **Step 5.2: 新建 application.yml**

Create `backend/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: BaBiQ-Server

server:
  port: 8080

logging:
  level:
    com.wzx.babiq.server: DEBUG
```

> 端口固定 8080(master plan 协议设计依赖此端口);DEBUG 级别只给 server package 开,便于 P1 调试。

- [ ] **Step 5.3: 启动验证**

Run:
```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Expected: 控制台看到:
```
Tomcat started on port 8080 (http)
Started BaBiQApplication in X.XXX seconds
```

按 Ctrl+C 停止。

```powershell
cd ..
```

- [ ] **Step 5.4: Commit**

```powershell
git add -A
git commit -m "chore(p1-0): convert application.properties to yml + lock port 8080"
```

---

## Task 6: 创建 desktop/ 项目骨架(Kotlin 2.3.21 + Compose 1.11.0 + Gradle 8.13)

**Files:**
- Create: `desktop/settings.gradle.kts`, `desktop/gradle.properties`, `desktop/build.gradle.kts`
- Create: `desktop/gradlew`, `desktop/gradlew.bat`, `desktop/gradle/wrapper/*`(Step 6.4 生成)
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/Main.kt`

- [ ] **Step 6.1: 创建目录结构**

Run:
```powershell
mkdir desktop
mkdir desktop\src\main\kotlin\com\wzx\babiq\desktop
mkdir desktop\src\main\resources
```

- [ ] **Step 6.2: 写 `desktop/settings.gradle.kts`**

Create `desktop/settings.gradle.kts`:
```kotlin
pluginManagement {
	repositories {
		mavenCentral()
		google()
		gradlePluginPortal()
		maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
	}
}

rootProject.name = "babiq-desktop"
```

- [ ] **Step 6.3: 写 `desktop/gradle.properties`**

Create `desktop/gradle.properties`:
```properties
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
```

- [ ] **Step 6.4: 生成 Gradle 8.13 wrapper(BLOCKER 修复:必须先于任何 gradle 调用)**

> ⚠️ 系统 Gradle 可能是 9.x,与 Kotlin 2.3.21 兼容但不稳;**强制锁定 8.13** via wrapper。

Run(用系统 Gradle 任意版本生成 wrapper):
```powershell
cd desktop
gradle wrapper --gradle-version 8.13 --distribution-type bin
ls gradlew gradlew.bat gradle\wrapper\gradle-wrapper.properties
cd ..
```

Expected: 出现 `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`。
`gradle-wrapper.properties` 应包含 `distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip`。

> 如果系统**完全没装 Gradle**:
> 1. 优先 `choco install gradle` 或 `scoop install gradle`
> 2. 都没有也可以:从 https://gradle.org/releases/ 下载 8.13 zip,解压到任意目录(如 `C:\tools\gradle-8.13`),临时加 PATH `$env:Path = "C:\tools\gradle-8.13\bin;$env:Path"`,然后回到本步
>
> **关于用 Gradle 9.x 生成 8.13 wrapper**: 这是 Gradle 官方支持的常见用法(`gradle wrapper` task 向后/向前兼容),不会有问题。生成后的 wrapper 自己下载 8.13 distribution,与生成时用的版本无关。

- [ ] **Step 6.5: 写 `desktop/build.gradle.kts`**

Create `desktop/build.gradle.kts`:
```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	kotlin("jvm") version "2.3.21"
	id("org.jetbrains.compose") version "1.11.0"
	kotlin("plugin.compose") version "2.3.21"
}

group = "com.wzx.babiq.desktop"
version = "0.0.1-SNAPSHOT"

repositories {
	mavenCentral()
	google()
	maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
	implementation(compose.desktop.currentOs)
	implementation(compose.material3)
}

kotlin {
	jvmToolchain(21)
}

compose.desktop {
	application {
		mainClass = "com.wzx.babiq.desktop.MainKt"
		nativeDistributions {
			// 仅 Windows;后续若要 macOS/Linux,加 Dmg / Deb
			targetFormats(TargetFormat.Msi, TargetFormat.Exe)
			packageName = "BaBiQ"
			packageVersion = "1.0.0"
		}
	}
}
```

> 说明: `jvmToolchain(21)` 强制 Kotlin 用 JDK 21 编译,Gradle 缺失时会自动下载。与 backend 的 Java 21 保持一致,简化心智。

- [ ] **Step 6.6: 写 `desktop/src/main/kotlin/com/wzx/babiq/desktop/Main.kt`**

Create `desktop/src/main/kotlin/com/wzx/babiq/desktop/Main.kt`:
```kotlin
package com.wzx.babiq.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication

fun main() = singleWindowApplication(
	title = "BaBiQ Desktop",
	state = WindowState(size = DpSize(900.dp, 700.dp))
) {
	App()
}

@Composable
fun App() {
	MaterialTheme {
		Surface(Modifier.fillMaxSize()) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text("BaBiQ Desktop — P1-0 skeleton OK ✅")
			}
		}
	}
}
```

- [ ] **Step 6.7: 跑通 desktop(用 wrapper,不用全局 gradle)**

Run:
```powershell
cd desktop
.\gradlew.bat run
```

> ⚠️ 首次执行会下载约 300-500MB 依赖(Gradle 8.13 distribution + Kotlin 2.3.21 + Compose 1.11.0 + JDK 21 toolchain 若缺失),耐心等 3-5 分钟。
> **如果遇到 `daemon` 启动失败**: 加 `--no-daemon` 重试;若仍失败,跑 `.\gradlew.bat --status` 查状态。

Expected: 桌面弹出 900x700 窗口,中央显示 "BaBiQ Desktop — P1-0 skeleton OK ✅"。
关闭窗口后命令结束(可能仍显示"running",Ctrl+C 退出 gradle)。

```powershell
cd ..
```

- [ ] **Step 6.8: Commit**

```powershell
git add -A
git commit -m "feat(p1-0): scaffold desktop module (Kotlin 2.3.21 + Compose 1.11.0 + Gradle 8.13)"
```

---

## Task 7: 更新 .gitignore

**Files:**
- Modify: `.gitignore`

- [ ] **Step 7.1: 追加 desktop 相关忽略项**

Edit `.gitignore`,在文件末尾追加(注意 **不要** 再次加 `README.md`):
```
### Gradle / Desktop ###
desktop/build/
desktop/.gradle/
desktop/.kotlin/
desktop/out/
**/local.properties
```

- [ ] **Step 7.2: 验证忽略生效**

Run:
```powershell
cd desktop
.\gradlew.bat build -x test    # 产生 build/ 目录
cd ..
git status
```

Expected: `desktop/build/`、`desktop/.gradle/` 不出现在 untracked 列表里。

- [ ] **Step 7.3: Commit**

```powershell
git add .gitignore
git commit -m "chore(p1-0): ignore gradle build artifacts"
```

---

## Task 8: 更新根 README.md 反映 Monorepo

**Files:**
- Modify: `README.md`

- [ ] **Step 8.1: 替换"启动"段落**

Edit `README.md`,**替换原"快速开始"中"启动项目"那一节**为以下内容:

```markdown
## 项目结构(Monorepo)

```
BaBiQ/                            # Monorepo 根
├── backend/                      # Spring Boot Agent Server (Java 21 LTS)
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/main/java/com/wzx/babiq/server/
├── desktop/                      # Compose Multiplatform Desktop (Kotlin 2.3.21)
│   ├── build.gradle.kts
│   ├── gradlew / gradlew.bat
│   └── src/main/kotlin/com/wzx/babiq/desktop/
├── docs/
│   ├── ARCHITECTURE.md
│   └── superpowers/plans/
└── README.md
```

## 启动

### Backend (Java 21 + Spring Boot 3.5.14)

```powershell
cd backend
.\mvnw.cmd spring-boot:run
# → Tomcat started on port 8080
```

### Desktop (Kotlin 2.3.21 + Compose Multiplatform 1.11.0)

```powershell
cd desktop
.\gradlew.bat run    # 首次会下载 Compose/JDK21 toolchain
# → 桌面窗口弹出
```
```

> 同时把 README 顶部"技术栈"表里 Java 25 改为 Java 21 LTS,Kotlin 版本若有提及也同步到 2.3.21。

- [ ] **Step 8.2: Commit**

```powershell
git add README.md
git commit -m "docs(p1-0): update README for monorepo layout (Java 21 + Kotlin 2.3.21)"
```

---

## Task 9: 同步 ARCHITECTURE.md + M0 最终验收

**Files:**
- Modify: `docs/ARCHITECTURE.md`(目录结构 §3)

- [ ] **Step 9.1: 同步 master plan + ARCHITECTURE.md(版本矩阵 + 目录结构)**

需要同步**两处**外部文档,使其与本 plan v3 保持一致:

**A. `docs/superpowers/plans/2026-05-21-p1-master.md`**
- 第 10 行附近 "Tech Stack" 行:把 `Java 25, Spring Boot 3.5.14, Kotlin 2.0, Compose Multiplatform 1.6+` 改为本 plan 顶部 v2 的版本矩阵(Java 21 / Kotlin 2.3.21 / Compose 1.11.0 / Gradle 8.13)

**B. `docs/ARCHITECTURE.md`**
- §7.1 Backend 表格:Java 25 → **Java 21 LTS**
- §7.2 Desktop 表格:Kotlin 2.0+ → **Kotlin 2.3.21**;Compose Multiplatform 1.6+ → **1.11.0**
- §3 目录结构:`backend/src/main/java/com/wzx/babiq/` → `backend/src/main/java/com/wzx/babiq/server/`(若 master plan 同步时已改则跳过)

验证:
```powershell
Select-String -Path docs\superpowers\plans\2026-05-21-p1-master.md -Pattern "Java 21|Kotlin 2.3.21"
Select-String -Path docs\ARCHITECTURE.md -Pattern "com/wzx/babiq/server"
```

Expected: 两个命令都有匹配输出。

- [ ] **Step 9.2: backend clean package**

Run:
```powershell
cd backend
.\mvnw.cmd clean package
cd ..
```

Expected:
- `BUILD SUCCESS`
- `backend\target\babiq-server-0.0.1-SNAPSHOT.jar` 存在

- [ ] **Step 9.3: backend spring-boot:run 烟测**

Run:
```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Expected: 90 秒内出现 `Started BaBiQApplication`。按 Ctrl+C 退出。

```powershell
cd ..
```

- [ ] **Step 9.4: desktop assemble**

Run:
```powershell
cd desktop
.\gradlew.bat assemble
cd ..
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 9.5: desktop run 烟测**

Run:
```powershell
cd desktop
.\gradlew.bat run
```

Expected: 弹出窗口显示 "BaBiQ Desktop — P1-0 skeleton OK ✅"。关闭窗口。

```powershell
cd ..
```

- [ ] **Step 9.6: 确认目录终态**

Run:
```powershell
ls
ls backend
ls desktop
```

Expected: 根目录三大分支 `backend/` / `desktop/` / `docs/` + `README.md` + `.gitignore` + `.git/` + `.idea/`(本地)。

- [ ] **Step 9.7: 最终 commit + 打 tag**

```powershell
git add -A
git status
# 若有未提交内容:
git commit -m "chore(p1-0): finalize monorepo skeleton + sync ARCHITECTURE"
git tag p1-0-skeleton
git log --oneline -15
```

Expected: 看到 P1-0 期间约 8 个 commit,tag `p1-0-skeleton` 已打。

---

## Done Criteria (M0 整体验收)

P1-0 算完成的硬标准(任一项不达成都需回到对应 Task 修复):

- [x] 根目录:`README.md`, `.gitignore`, `.gitattributes`, `docs/`, `backend/`, `desktop/`(+ `.git/`, `.idea/` 本地)
- [x] `.gitignore` 首行**不是** `README.md`
- [x] `cd backend && .\mvnw.cmd clean package` 成功,产出 `babiq-server-0.0.1-SNAPSHOT.jar`
- [x] `cd backend && .\mvnw.cmd spring-boot:run` 看到 `Started BaBiQApplication`,端口 8080
- [x] `backend/pom.xml` 的 `<java.version>` 是 **21**(不是 25)
- [x] `cd desktop && .\gradlew.bat assemble` 成功
- [x] `cd desktop && .\gradlew.bat run` 弹窗显示 `BaBiQ Desktop — P1-0 skeleton OK ✅`
- [x] `desktop/gradle/wrapper/gradle-wrapper.properties` 指向 **gradle-8.13**(P1-0 时;后续 post-P1-0 升级到 9.3.0,见文末"事后说明")
- [x] 后端 package:`com.wzx.babiq.server`
- [x] 桌面端 package:`com.wzx.babiq.desktop`
- [x] git 历史保留(`git log --follow backend/pom.xml` 能看到旧 `pom.xml` 的历史)
- [x] `git tag p1-0-skeleton` 已创建
- [x] `docs/ARCHITECTURE.md` §3 路径与现实一致

---

## 完成后下一步

P1-0 完成后:

---

## 📝 事后说明(post-P1-0,2026-05-21)

P1-0 落地后发现两个 hindsight 问题,已在 master 后续 commit 修复:

### 1. Gradle 8.13 不支持 JDK 25 作为 daemon JVM
- **现象**: 用户机器系统默认 JDK 是 25.0.2(scoop 默认),跑 `.\gradlew.bat run` 抛 `25.0.2` 异常
- **根因**: Gradle 8.x 系列 daemon JVM 兼容 JDK 17-24,**不含 25**;Gradle 9.1.0 起才支持 JDK 25 daemon
- **修复**: wrapper 升级到 **Gradle 9.3.0**(Kotlin 2.3.21 declared 支持的最高版本 + 含 JDK 25 daemon 支持)
- **当时为什么选了 8.13**: 见 plan v2 Tech Stack —— 我误以为 9.x 太新不稳,实际上 9.1+ 已经是必需的

### 2. 机器特定路径写到了仓库 gradle.properties
- **现象**: `desktop/gradle.properties` 包含本机 JDK 绝对路径 + 代理设置
- **修复**:
  - 机器路径挪到 **用户级** `~/.gradle/gradle.properties`(Gradle 官方机制,跨项目共享,不进仓库)
  - 仓库新增 `desktop/gradle.properties.local.example` 作为团队模板
  - `.gitignore` 排除真实 `.local` 文件
- **重要陷阱**: 用户级 properties 里如果路径含中文(如 `C:\Users\王校长\...`),**必须用 Unicode escape** (`王校长`),否则 Gradle 按 ISO-8859-1 解析会失败

### 当前实际版本矩阵(覆盖 plan 顶部的"v3")
- Java 21 LTS(backend)+ Java 21 toolchain(desktop,可同时支持 25)
- Spring Boot 3.5.14
- Kotlin 2.3.21
- Compose Multiplatform 1.11.0
- **Gradle 9.3.0**(原 plan 写的 8.13 已不适用)


1. 跑 **superpowers:verification-before-completion** 跨步验收
2. 让我为 **P1-1(协议层)** 写详细 plan(预计 12-15 步,引入 spring-boot-starter-websocket 与 Spring AI Alibaba 1.1.2.x BOM)
3. 在 `feat/p1-1-protocol` 分支上推进
