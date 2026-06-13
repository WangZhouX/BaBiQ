# BaBiQ Kotlin 桌面技术栈学习路径

这是一套面向初学者的项目驱动学习材料。它用 BaBiQ 的真实桌面端代码作为教材，帮助读者从零理解 Kotlin、Compose Multiplatform Desktop、Ktor WebSocket、kotlinx.serialization、kotlinx.coroutines 和 Gradle Kotlin DSL。

本目录按 `teach` 技能的有状态学习工作区组织，但为了适合开源仓库阅读和评审，课程文件采用 Markdown，而不是单人学习工作区里的 HTML。

## 学习目标

学完这套路径后，读者应该能做到三件事：

- 看懂 BaBiQ 桌面端从 UI 到后端 WebSocket 的核心调用链。
- 能在已有代码风格下修改一个小型桌面端功能，并补充测试。
- 知道 Compose、Ktor、协程、序列化分别解决什么问题，而不是把它们混成一团。

## 当前课程

### 入门导览

入门导览是给完全初学者的第一阶段。它不要求读者已经系统学过 Kotlin 或桌面 UI 框架，只要求能打开仓库、愿意跟着源码走。

1. [第 0 课: 认识 BaBiQ 桌面端技术栈](lessons/0000-tech-stack-tour.md)
2. [第 1 课: Kotlin 最小语法包](lessons/0001-kotlin-minimum-syntax.md)
3. [第 2 课: Kotlin 数据建模](lessons/0002-kotlin-data-modeling.md)
4. [第 3 课: Gradle 和项目结构](lessons/0003-gradle-project-structure.md)
5. [第 4 课: JSON 与 kotlinx.serialization](lessons/0004-json-serialization.md)
6. [第 5 课: 协程与 StateFlow](lessons/0005-coroutines-stateflow.md)
7. [第 6 课: Compose Desktop 启动课](lessons/0006-compose-desktop-start.md)
8. [第 7 课: Ktor WebSocket 启动课](lessons/0007-ktor-websocket-start.md)

## 后续专题

入门导览只是第一圈。后续学习会拆成专题轨道：

- Compose Desktop 完整专题：从声明式 UI、状态提升、复杂布局到桌面打包和 UI 测试。
- Ktor WebSocket 完整专题：从 WebSocket 协议、JSON-RPC、事件流到重连、超时和 fake transport 测试。
- Kotlin 协程专题：从 `suspend`、结构化并发到 UI 状态流。
- BaBiQ 实战专题：从新增协议字段、新增设置项到端到端桌面功能。

完整路线见 [ROADMAP.md](ROADMAP.md)。

## 如何学习

建议每节课按这个顺序走：

1. 先读课程目标，确认这节课只解决一个问题。
2. 打开课程里的源码入口，不急着读完整文件，只看标出的概念。
3. 完成练习。练习通常是阅读、标注或运行测试，不要求一开始就改业务代码。
4. 用自检问题确认自己是否真的理解。

## 关键参考

- [学习使命](MISSION.md)
- [资源清单](RESOURCES.md)
- [技术栈地图](reference/tech-stack-map.md)
- [术语表](reference/glossary.md)

