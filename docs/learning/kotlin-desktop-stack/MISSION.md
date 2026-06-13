# 使命: 用 BaBiQ 学会 Kotlin 桌面技术栈

## 为什么

BaBiQ 是一个开源的本地 Codex-like AI Agent 学习项目。读者希望不只是使用这个项目，还能通过它系统学会 Kotlin 桌面端开发，并最终能读懂、修改和扩展 BaBiQ 的桌面客户端。

## 成功是什么样子

- 完全初学者能说清 Kotlin、Compose、Ktor、协程、serialization 在 BaBiQ 中分别负责什么。
- 读者能顺着 `BaBiQDesktopApp -> ChatController -> AgentClient -> KtorAgentTransport` 读懂桌面端主链路。
- 读者能完成一个小型桌面端改动，例如新增一个显示字段、一个设置项或一个右侧面板区域，并补充对应测试。
- 读者能在学习过程中把疑问定位到具体源码文件，而不是停留在泛泛的框架概念上。

## 约束

- 面向初学者，不能假设读者已经系统学过 Kotlin、Compose Desktop 或 Ktor。
- 课程必须锚定 BaBiQ 当前仓库源码，避免写成脱离项目的通用教程。
- Compose Desktop 和 Ktor WebSocket 不能只停留在入门，它们后续必须发展为完整专题。
- 学习材料要适合开源仓库提交、代码评审和长期维护。

## 不在范围内

- 不在入门导览阶段讲完整 Compose 性能优化、复杂动画或桌面打包细节。
- 不在入门导览阶段要求读者修改后端 Java / Spring Boot 代码。
- 不把 Kotlin 语法百科搬进仓库；只讲读 BaBiQ 桌面端源码所需的最小知识。

