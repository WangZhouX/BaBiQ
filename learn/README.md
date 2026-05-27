# 🎓 BaBiQ 学习指南

> 这个目录是 BaBiQ **学习者视角**的入口（与 `docs/` 实施视角分开）。
> 如果你是第一次看这个项目的代码，从这里开始。

---

## 这是什么

BaBiQ 是一个对标 OpenAI Codex 桌面端的本地 AI Agent 学习项目：

- **后端**：Java 21 + Spring Boot + Spring AI Alibaba `ReactAgent`
- **桌面端**：Kotlin + Compose Multiplatform Desktop
- **通信**：WebSocket + JSON-RPC 2.0

这个学习目录的目标，是让一个第一次接触代码的人，**也能逐步看懂整个系统是怎么工作的**。

---

## 谁该读这个

| 你是谁 | 建议路线 |
|---|---|
| 想了解 BaBiQ 这个系统怎么用 | `00-quickstart/` 跑起来体验 |
| 想学 AI Agent 框架 | `01-architecture-tour/` 看俯瞰 → `02-reading-path/` 02/03/04 章 |
| 想学 Kotlin / Compose Desktop | `02-reading-path/` 11、12 章（含 Kotlin 入门讲解） |
| 想看用到的具体技术原理 | `03-tech-deep-dive/` 按需取阅 |
| 想看一个完整任务的端到端链路 | `04-walkthroughs/` |

---

## 目录结构

```
learn/
├── README.md                       # 你正在看这里
├── 00-quickstart/                  # 从零跑通（30 分钟见效）
├── 01-architecture-tour/           # 俯瞰式架构导览
├── 02-reading-path/                # 源码阅读路线（按依赖顺序）
│   └── 12-desktop-state.md         # ✅ 已完成示范章节（Kotlin 状态管理深入讲解）
├── 03-tech-deep-dive/              # 用到的核心技术专题
├── 04-walkthroughs/                # 端到端实战讲解
├── 05-exercises/                   # 动手练习
├── code-index.md                   # 🔍 反查表：从代码找文档
└── glossary.md                     # 📖 术语表
```

> **当前状态**：仓库正在逐章铺设。已完整写完的章节会在上方标记 ✅。
> 推荐先看 [02-reading-path/12-desktop-state.md](02-reading-path/12-desktop-state.md) 体会教学风格。

---

## 文档约定

每一章都遵循同一个三段式结构：

1. **🎯 学完你会知道**：明确这一章的学习产出
2. **🗺️ 源码地图 + 📖 逐段讲解**：每段都带文件路径 + 行号链接，可直接 Ctrl+点击跳转
3. **🔧 动手实操 + 🧠 思考题**：跑命令、看输出、自己提问

---

## 跳转方式说明

文档里的代码链接长这样：

```markdown
[`AgentLoop.invoke()` (L42-L95)](../backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java#L42-L95)
```

- 在 **GitHub Web** 上点击会跳到指定行并高亮
- 在 **IDEA / VSCode** 里 Ctrl+点击会打开文件（行号锚点需要插件支持）
- 即使行号失效，文档里都贴了**代码片段**，可以直接全局搜索回去

---

## 反查：从代码找文档

打开一个源文件却不知道哪章讲过它？查 [code-index.md](code-index.md)，按类名/文件名都能找到对应章节。

---

## 术语表

不熟悉的术语？查 [glossary.md](glossary.md)。比如 HITL、ReAct、Spotlighting、Reducer 等。
