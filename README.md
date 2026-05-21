# BaBiQ

## 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| Backend JDK | Java 21 LTS |
| Backend | Spring Boot 3.5.14 |
| Backend 构建 | Maven（含 `backend/mvnw`） |
| Desktop JDK | Java 21 |
| Desktop | Kotlin 2.3.21 + Compose Multiplatform 1.11.0 |
| Desktop 构建 | Gradle 8.13 wrapper |

## 项目结构

```text
BaBiQ/
├── backend/
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/main/java/com/wzx/babiq/server/
├── desktop/
│   ├── build.gradle.kts
│   ├── gradlew / gradlew.bat
│   └── src/main/kotlin/com/wzx/babiq/desktop/
├── docs/
└── README.md
```

## 启动

### Backend

```powershell
cd backend
$env:JAVA_HOME='C:\Users\王校长\scoop\apps\openjdk21\current'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run
```

### Desktop

```powershell
cd desktop
$env:JAVA_HOME='C:\Users\王校长\scoop\apps\openjdk21\current'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:HTTP_PROXY='http://127.0.0.1:7890'
$env:HTTPS_PROXY='http://127.0.0.1:7890'
.\gradlew.bat --no-daemon run
```

## 备注

- 后端和桌面端是两个独立项目
- 所有敏感配置走环境变量
