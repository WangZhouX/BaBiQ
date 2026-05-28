# 🎓 BaBiQ 学习指南

> 这个目录是 BaBiQ **学习者视角**的入口（与 `docs/` 实施视角分开）。
> 如果你是第一次看这个项目的代码，从这里开始。

---

## 这是什么

BaBiQ 是一个对标 OpenAI Codex 桌面端的本地 AI Agent 学习项目：

- **后端**：Java 21 + Spring Boot 3.5.14 + Spring AI Alibaba `ReactAgent`
- **桌面端**：Kotlin 2.3.21 + Compose Multiplatform Desktop 1.11.0 + Ktor Client
- **通信**：WebSocket + JSON-RPC 2.0
- **持久化**：SQLite + MyBatis-Plus + Flyway

这个学习目录的目标，是让一个第一次接触代码的人，**也能逐步看懂整个系统是怎么工作的**。

---

## 项目当前进度（截至 P3-5a）

BaBiQ 经过 P1 → P2 → P3 三个大阶段累积了完整的 Agent 能力。每个阶段都有详细的实施 plan，落在 `docs/superpowers/plans/`：

| 阶段 | 内容 | 关键交付 |
|---|---|---|
| **P1-3a/3b** | Agent Loop 内核 + 安全可观测 | `ReactAgent`、6 个本地工具、HITL 审批、沙箱、Spotlighting、TurnSummary |
| **P1-4** | Compose Desktop UI | ChatScreen、审批弹窗、Provider 切换、连接断线 / 重连 |
| **P2-1 ~ P2-6** | SQLite 持久化 + MCP | 多会话历史、Provider/沙箱/审批设置系统、运行记录、本地可观测、MCP Client |
| **P3-1 ~ P3-3a** | 上下文工程 | ContextSnapshot、当前窗口运行时、短期压缩（BM25-style）、压缩鲁棒性补强 |
| **P3-4** | 长期记忆 | 异步两阶段流水线（Phase 1 抽取 + Phase 2 归并）、`memory_summary.md` 注入 |
| **P3-5 / P3-5a** | 按需能力装配 | `tool_search` 工具、Skill 注册表、Lucene/BM25 能力搜索、中文别名富化 |

详细路线见 [`docs/superpowers/plans/p3-master.md`](../docs/superpowers/plans/p3-master.md)。

---

## 谁该读这个

| 你是谁 | 建议路线 |
|---|---|
| 想了解 BaBiQ 这个系统怎么用 | `00-quickstart/`（待写）|
| 想学 AI Agent 框架（Spring AI Alibaba）| `01-architecture-tour/` 看俯瞰 → `02-reading-path/` 02/03/04 章 |
| 想学 Kotlin / Compose Desktop | `02-reading-path/12-desktop-state.md` ✅（含 Kotlin 入门讲解）|
| 想看用到的具体技术原理（Lucene / RAG / Memory / MCP / Hook）| `03-tech-deep-dive/01-react-hook-interceptor.md` ✅（ReactAgent + Hook + Interceptor 机制深挖）|
| 想看一个完整任务的端到端链路 | `04-walkthroughs/01-read-file-full-trace.md` ✅（27 阶段全链路追踪）|

---

## 目录结构

```
learn/
├── README.md                       # 你正在看这里
├── 00-quickstart/                  # 从零跑通（30 分钟见效）（待写）
├── 01-architecture-tour/           # 俯瞰式架构导览（待写）
├── 02-reading-path/                # 源码阅读路线（按依赖顺序）
│   ├── 03-agent-loop.md            # ✅ 后端源码阅读起点（IDE 跟读 8 站路线）
│   └── 12-desktop-state.md         # ✅ Kotlin 桌面端状态管理深度讲解
├── 03-tech-deep-dive/              # 用到的核心技术专题
│   ├── 01-react-hook-interceptor.md # ✅ ReactAgent + Hook + Interceptor 机制深挖
│   ├── 02-context-engineering.md    # ✅ 上下文工程全家桶（P3 全景）
│   ├── 03-security-spotlighting.md  # ✅ 安全机制专题（Spotlighting + Sandbox + PathGuard）
│   └── 04-protocol-websocket.md     # ✅ 协议层（JSON-RPC + WebSocket + Kotlin 协程）
├── 04-walkthroughs/                # 端到端实战讲解
│   ├── 01-read-file-full-trace.md  # ✅ 「读 README 并总结」27 阶段全链路追踪
│   └── 02-write-file-with-approval.md # ✅ 「写 notes.md + 审批弹窗」HITL 完整路径
├── 05-exercises/                   # 动手练习（待写）
├── code-index.md                   # 🔍 反查表：从代码找文档
└── glossary.md                     # 📖 术语表
```

> **当前状态**：仓库正在逐章铺设。**12-desktop-state.md 是完整示范章**，演示文档体例。
> 推荐先看它体会教学风格，再用 [code-index.md](code-index.md) 和 [glossary.md](glossary.md) 辅助阅读真实代码。

---

## 推荐阅读路径（按目的）

### 路径 A：理解整个系统的运作（推荐第一次读）

1. 看本 README（你已经在看）
2. 浏览 [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) §1-§5（系统总览、协议、状态模型）
3. 看 [12-desktop-state.md](02-reading-path/12-desktop-state.md) 理解桌面端状态管理
4. 读 [04-walkthroughs/01-read-file-full-trace.md](04-walkthroughs/01-read-file-full-trace.md) 看一次「读 README 并总结」的 27 阶段全链路
5. 用 [code-index.md](code-index.md) 按模块跳进后端代码

### 路径 B：理解 AI Agent 框架（学 Spring AI Alibaba）

1. 看 [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) §15（Hooks/Interceptors）+ §17（HITL）
2. 跟着 [02-reading-path/03-agent-loop.md](02-reading-path/03-agent-loop.md) 在 IDE 里用 8 站路线读完后端核心
3. 读 [03-tech-deep-dive/01-react-hook-interceptor.md](03-tech-deep-dive/01-react-hook-interceptor.md) 深入理解 ReactAgent 图模型 + Hook/Interceptor 机制

### 路径 C：理解上下文工程（学 Codex 级 Context Engineering）

1. 读 [03-tech-deep-dive/02-context-engineering.md](03-tech-deep-dive/02-context-engineering.md)（P3 全景深挖：5 层装配 + 压缩 + 长期记忆 + Lucene/BM25）
2. 读 `docs/superpowers/plans/p3-master.md`（P3 总体设计）
3. 读 `context/` 包了解三层上下文：
   - `ContextAssembler`（分层组装）
   - `runtime/ContextWindowRuntime`（每轮窗口管理）
   - `compaction/ContextCompactionService`（短期压缩）
4. 读 `memory/` 包了解长期记忆两阶段流水线

### 路径 D：理解按需工具装配（学 Codex tool_search 风格）

1. 读 `docs/superpowers/plans/p3-5-capability-retrieval-control/plan.md`
2. 读 `capability/` 包：
   - `CapabilityExposurePlanner`（VISIBLE / DEFERRED / DISABLED 决策）
   - `LuceneCapabilitySearchService`（BM25 搜索）
   - `CapabilityAliasDictionary`（中文别名富化）
3. 读 `tool/impl/ToolSearchTool.java`（普通工具，经审批/沙箱/Spotlighting）

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

打开一个源文件却不知道哪章讲过它？查 [code-index.md](code-index.md)，按类名/包名都能找到对应章节或代码位置。

---

## 术语表

不熟悉的术语？查 [glossary.md](glossary.md)。覆盖 HITL、ReAct、Spotlighting、BM25、Lucene、ContextSnapshot、Phase 1/2 长期记忆等。
