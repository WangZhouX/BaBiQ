# BaBiQ 架构设计文档

> 一个对标 OpenAI Codex 桌面端、基于 Spring AI Alibaba 的通用 AI Agent 学习项目。
> 文档版本: v0.1 (2026-05-21)
> 状态: **架构草案**(实施前会再迭代,以 brainstorming + writing-plans 产出的最终版为准)

---

## 1. 项目目标

- **学习目标**: 通过实现一个迷你版 Codex,掌握 Agent 系统设计的全套核心机制
- **产品形态**: Kotlin Compose Desktop 客户端 + Spring Boot Agent Server,本地运行
- **核心能力**: 代码助手 → 通用工具助手(渐进式扩展)
- **非目标**: 不追求生产级稳定性、不做商业化、不做云端版本

## 2. 系统总览

```
┌────────────────────────────────────────────────────────────────┐
│                  Kotlin Compose Desktop Client                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │  Chat View   │  │ Approval UI  │  │ Settings / Workspace │  │
│  │  (流式渲染)   │  │ (审批弹窗)    │  │  (model/policy/cwd)  │  │
│  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘  │
│         └─────────────────┴─────────────────────┘              │
│                            │                                    │
│                  ┌─────────▼──────────┐                         │
│                  │  AgentClient (Ktor)│                         │
│                  │  WebSocket+JSON-RPC│                         │
│                  └─────────┬──────────┘                         │
└────────────────────────────┼────────────────────────────────────┘
                             │  ws://localhost:8080/ws/agent
                             │  JSON-RPC 2.0 over WebSocket
┌────────────────────────────┼────────────────────────────────────┐
│                            ▼                                    │
│              Spring Boot Agent Server (Java 21 LTS)             │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              JsonRpcWebSocketHandler                     │   │
│  │      (路由 request/notification, 维护连接生命周期)         │   │
│  └────────────┬─────────────────────────────────────────────┘   │
│               │                                                 │
│  ┌────────────▼──────────────────────────────────────────────┐  │
│  │              ConversationService                          │  │
│  │  - Thread/Turn 生命周期管理                                │  │
│  │  - Item 流式发射                                           │  │
│  │  - 历史持久化(P1 内存,P2 SQLite)                          │  │
│  └────────────┬──────────────────────────────────────────────┘  │
│               │                                                 │
│  ┌────────────▼──────────────────────────────────────────────┐  │
│  │              AgentLoop (ReAct 主循环)                     │  │
│  │  while not done:                                          │  │
│  │     1. 调用 ChatClient (Spring AI Alibaba + DashScope)    │  │
│  │     2. 解析 ToolCall                                      │  │
│  │     3. 走 ApprovalEngine 决定是否需要审批                  │  │
│  │     4. ToolRegistry 执行工具                              │  │
│  │     5. 把结果回灌为下一轮输入                              │  │
│  └─────┬────────────┬───────────────┬────────────────────────┘  │
│        │            │               │                           │
│  ┌─────▼─────┐ ┌────▼──────┐ ┌──────▼──────────┐                │
│  │ChatClient │ │ Approval  │ │  ToolRegistry   │                │
│  │  (SAA)    │ │  Engine   │ │ ┌─────────────┐ │                │
│  │           │ │ never/    │ │ │ read_file   │ │                │
│  │ DashScope │ │ on-request│ │ │ write_file  │ │                │
│  │ qwen-plus │ │ on-failure│ │ │ exec_shell  │ │                │
│  │           │ │           │ │ │ list_dir    │ │                │
│  └───────────┘ └────┬──────┘ │ │ grep        │ │                │
│                     │        │ │ apply_patch │ │                │
│                     │        │ │ web_search  │ │                │
│                     │        │ │ ...         │ │                │
│                     │        │ └─────────────┘ │                │
│                     │        └────────┬────────┘                │
│                     │                 │                         │
│              ┌──────▼─────────────────▼─────┐                   │
│              │       SandboxPolicy           │                   │
│              │  read-only / workspace-write  │                   │
│              │       / danger-full-access    │                   │
│              └───────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.1 双层协议架构 ⭐

BaBiQ 采用 **双层协议设计**:**内层为本机 UX 服务,外层为跨机器互通服务**。两层各管一边,互不替代。

```
┌─────────────────────────────────────────────────────────────────┐
│  机器 A(你的工作站)                                              │
│                                                                  │
│   ┌──────────────────────────────────────────────────┐           │
│   │ Desktop Client A (Kotlin Compose)                │           │
│   └────────────┬─────────────────────────────────────┘           │
│                │                                                 │
│   ╭──────── 内层协议 ───────╮                                    │
│   │ WebSocket + JSON-RPC 2.0 │  P1 立即做(自己写,真双向)         │
│   │ Thread / Turn / Item     │  优势: 双向 / 低延迟 / 学习深       │
│   ╰──────────┬───────────────╯                                   │
│              ▼                                                   │
│   ┌──────────────────────────────────────────────────┐           │
│   │           BaBiQ Backend A (Spring Boot)          │           │
│   │   ┌─────────────────────────────────────────┐    │           │
│   │   │  Core Agent (ReactAgent + Hooks + ...)  │    │           │
│   │   └─────────────────────────────────────────┘    │           │
│   │   ┌────────────────────┐                         │           │
│   │   │  A2A Server        │  ← 外层入口(SAA 现成)   │           │
│   │   └─────────┬──────────┘                         │           │
│   └─────────────┼────────────────────────────────────┘           │
└─────────────────┼────────────────────────────────────────────────┘
                  │
   ╭──────── 外层协议 ───────╮  P4+ 才启用
   │ A2A (HTTP+JSON-RPC+SSE) │  优势: 标准 / 跨语言 / Nacos 发现
   │ Task / Message / Part   │
   ╰──────────┬───────────────╯
              ▼
┌─────────────────────────────────────────────────────────────────┐
│  机器 B(同事 / 家人 / 你的另一台机器)                              │
│                                                                  │
│   ┌──────────────────────────────────────────────────┐           │
│   │           BaBiQ Backend B + A2A Server           │           │
│   └────────────┬─────────────────────────────────────┘           │
│                │ 内层 WS                                          │
│                ▼                                                 │
│   ┌──────────────────────────────────────────────────┐           │
│   │            Desktop Client B                       │           │
│   └──────────────────────────────────────────────────┘           │
└─────────────────────────────────────────────────────────────────┘
```

#### 两层各自的"领地"

| 维度 | **内层** (Desktop ↔ Backend,本机) | **外层** (Backend ↔ Backend,跨机器) |
|---|---|---|
| 协议 | WebSocket + JSON-RPC 2.0 | A2A (HTTP + JSON-RPC + SSE) |
| 谁实现 | **自己写**(D1) | **SAA 现成**(`spring.ai.alibaba.a2a.server`) |
| 状态模型 | Thread / Turn / Item(细粒度,UI 友好,详见 §4-§5) | Task / Message / Part(A2A 标准,详见 §19) |
| 双向通信 | ✅ 真双向(WS) | ⚠️ 半双向(HTTP+SSE) |
| 启用阶段 | **P1 立即** | **P4+** |
| 学习深度 | **深**(理解协议机制) | 中(用标准组件) |

#### P4+ 场景示意

> 你在桌面端输入"帮我跟室友的 BaBiQ 协作整理共享文档" →
> 你的 backend 通过 Nacos 发现室友 BaBiQ 的 A2A AgentCard →
> A2A 调用室友的 `index_shared_drive` 工具 →
> 室友机器上的 BaBiQ 在他桌面端弹审批弹窗(走他本机内层 WS)→
> 室友 approve,执行,通过 A2A 把结果返回 →
> 你的 backend 通过你本机内层 WS 推给你桌面端显示。
>
> **内外两层都用了,各自发挥强项**。

#### 关键洞察

- **D1 + D28 不冲突,而是互补** —— 各管一层
- **D29 总览**:双层协议架构(§5 全局决策)
- **Core Agent 在两层之间共享** —— 同一个 `ReactAgent + Hooks + ChatMemory` 同时为两层服务

---

## 3. Monorepo 目录结构

```
BaBiQ/
├── README.md
├── docs/
│   ├── ARCHITECTURE.md           # 本文档
│   ├── PROTOCOL.md               # JSON-RPC 协议详细规范 (后续补)
│   └── ROADMAP.md                # 学习路线 (后续补)
│
├── backend/                      # Spring Boot Agent Server (Java 21 LTS)
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   └── src/main/java/com/wzx/babiq/server/
│       ├── BaBiQApplication.java
│       ├── api/
│       │   ├── JsonRpcWebSocketHandler.java
│       │   ├── JsonRpcRequest.java
│       │   ├── JsonRpcResponse.java
│       │   └── JsonRpcNotification.java
│       ├── conversation/
│       │   ├── ConversationService.java
│       │   ├── Thread.java
│       │   ├── Turn.java
│       │   └── items/                   # 12 种 Item 实现
│       │       ├── ThreadItem.java       # 抽象基类 (sealed)
│       │       ├── UserMessageItem.java
│       │       ├── AgentMessageItem.java
│       │       ├── ReasoningItem.java
│       │       ├── PlanItem.java
│       │       ├── CommandExecutionItem.java
│       │       ├── FileChangeItem.java
│       │       ├── McpToolCallItem.java
│       │       ├── CollabToolCallItem.java
│       │       ├── WebSearchItem.java
│       │       ├── ImageViewItem.java
│       │       ├── ReviewModeItem.java
│       │       └── ContextCompactionItem.java
│       ├── agent/
│       │   ├── AgentLoop.java
│       │   └── ReActStrategy.java
│       ├── tool/
│       │   ├── ToolRegistry.java
│       │   ├── Tool.java                 # 接口
│       │   └── impl/
│       │       ├── ReadFileTool.java
│       │       ├── WriteFileTool.java
│       │       ├── ExecShellTool.java
│       │       ├── ListDirTool.java
│       │       ├── GrepTool.java
│       │       └── ApplyPatchTool.java
│       ├── approval/
│       │   ├── ApprovalEngine.java
│       │   ├── ApprovalPolicy.java       # never/on-request/on-failure
│       │   └── ApprovalRequest.java
│       ├── sandbox/
│       │   ├── SandboxPolicy.java
│       │   ├── SandboxMode.java          # read-only/workspace-write/danger
│       │   └── PathGuard.java
│       └── config/
│           └── BaBiQProperties.java
│
└── desktop/                      # Compose Multiplatform Desktop (Kotlin)
	├── build.gradle.kts
	├── settings.gradle.kts
	└── src/main/kotlin/com/wzx/babiq/desktop/
		├── Main.kt
		├── client/
		│   ├── AgentClient.kt            # WebSocket + JSON-RPC
		│   └── ProtocolModels.kt         # 与后端镜像的 Item DSL
		├── ui/
		│   ├── ChatScreen.kt
		│   ├── ApprovalDialog.kt
		│   ├── SettingsScreen.kt
		│   └── components/
		│       ├── MessageBubble.kt
		│       ├── CommandPreview.kt
		│       └── DiffViewer.kt
		└── state/
			└── ChatViewModel.kt
```

---

## 4. 通信协议 (简化版 Codex JSON-RPC)

### 4.1 传输层

- **协议**: WebSocket
- **端点**: `ws://localhost:8080/ws/agent`
- **消息格式**: JSON-RPC 2.0
- **方向**: 双向 (client↔server),审批请求走 server→client→server

### 4.2 方法列表

#### Client → Server (Request)

| 方法 | 说明 |
| --- | --- |
| `thread/create` | 创建新会话,返回 `threadId` |
| `thread/list` | 列出历史会话 (P2+) |
| `thread/load` | 载入历史会话 (P2+) |
| `turn/start` | 在 thread 上开启一轮对话 |
| `turn/cancel` | 取消正在进行的 turn |
| `approval/respond` | 响应审批请求 (允许/拒绝/总是允许) |
| `thread/inject_items` | 注入历史 item (P2+) |

#### Server → Client (Notification)

| 通知 | 说明 |
| --- | --- |
| `turn/started` | 一轮对话开始 |
| `item/added` | 新增 item |
| `item/updated` | item 内容更新 (流式 token) |
| `item/completed` | item 完成 |
| `approval/request` | 服务端请求审批 (client 用 `approval/respond` 应答) |
| `turn/completed` | 一轮对话结束 |
| `turn/failed` | 一轮对话失败 |

### 4.3 完整 12 种 Item 类型 (对标 Codex)

| Item 类型 | 用途 | P1 | P2 | P3+ |
| --- | --- | :-: | :-: | :-: |
| `userMessage` | 用户输入文本 / 图片 | ✅ | | |
| `agentMessage` | 助手回复(支持流式 delta) | ✅ | | |
| `reasoning` | 思考过程(qwq-plus 等思考模型) | ✅ | | |
| `plan` | 任务计划(TodoWrite 风格) | ✅ | | |
| `commandExecution` | shell 命令执行(命令/stdout/stderr/exit_code) | ✅ | | |
| `fileChange` | 文件读写/diff 展示 | ✅ | | |
| `mcpToolCall` | MCP 协议工具调用 | | ✅ | |
| `collabToolCall` | 多 Agent 协作工具调用 | | | ✅ |
| `webSearch` | 联网搜索结果 | | ✅ | |
| `imageView` | 图片查看(多模态) | | ✅ | |
| `reviewMode` | 进入/退出审查模式(只读快照) | | | ✅ |
| `contextCompaction` | 上下文压缩事件 | | | ✅ |

### 4.4 典型消息样本

#### 创建 thread
```json
// Request
{"jsonrpc":"2.0","method":"thread/create","id":1,"params":{
  "cwd":"F:/projects/demo",
  "model":"qwen-plus"
}}

// Response
{"jsonrpc":"2.0","id":1,"result":{"threadId":"thr_01"}}
```

#### 发起一轮对话
```json
// Request
{"jsonrpc":"2.0","method":"turn/start","id":2,"params":{
  "threadId":"thr_01",
  "input":{"type":"text","text":"读取 README.md 并总结"},
  "approvalPolicy":"on-request",
  "sandboxMode":"workspace-write"
}}

// Response (立即返回 turnId)
{"jsonrpc":"2.0","id":2,"result":{"turnId":"turn_001"}}
```

#### 流式 Item 通知
```json
{"jsonrpc":"2.0","method":"turn/started","params":{
  "threadId":"thr_01","turnId":"turn_001"
}}

{"jsonrpc":"2.0","method":"item/added","params":{
  "threadId":"thr_01","turnId":"turn_001",
  "item":{"id":"it_01","type":"userMessage","text":"读取 README.md 并总结"}
}}

{"jsonrpc":"2.0","method":"item/added","params":{
  "threadId":"thr_01","turnId":"turn_001",
  "item":{"id":"it_02","type":"reasoning","text":"用户想读取文件..."}
}}

{"jsonrpc":"2.0","method":"item/added","params":{
  "threadId":"thr_01","turnId":"turn_001",
  "item":{"id":"it_03","type":"fileChange","action":"read",
          "path":"README.md","status":"pending"}
}}

{"jsonrpc":"2.0","method":"item/updated","params":{
  "threadId":"thr_01","turnId":"turn_001",
  "item":{"id":"it_03","status":"completed","contentPreview":"..."}
}}

{"jsonrpc":"2.0","method":"item/added","params":{
  "threadId":"thr_01","turnId":"turn_001",
  "item":{"id":"it_04","type":"agentMessage","textDelta":"这个 README"}
}}
{"jsonrpc":"2.0","method":"item/updated","params":{
  "threadId":"thr_01","turnId":"turn_001",
  "item":{"id":"it_04","textDelta":" 介绍了..."}
}}

{"jsonrpc":"2.0","method":"turn/completed","params":{
  "threadId":"thr_01","turnId":"turn_001",
  "usage":{"promptTokens":1234,"completionTokens":567}
}}
```

#### 审批往返
```json
// Server → Client
{"jsonrpc":"2.0","method":"approval/request","id":99,"params":{
  "threadId":"thr_01","turnId":"turn_001","itemId":"it_05",
  "tool":"exec_shell",
  "args":{"command":"rm -rf node_modules"},
  "reason":"删除目录可能不可逆"
}}

// Client → Server
{"jsonrpc":"2.0","method":"approval/respond","id":99,"params":{
  "decision":"approve",    // approve | deny | always-allow
  "scope":"turn"           // turn | session
}}
```

---

## 5. 状态机模型

### 5.1 Thread / Turn / Item 关系

```
Thread (thr_01)                       会话级,长生命周期
  ├─ Turn (turn_001)                  对话级,一轮交互
  │   ├─ Item (userMessage)
  │   ├─ Item (reasoning)
  │   ├─ Item (commandExecution)
  │   ├─ Item (fileChange)
  │   └─ Item (agentMessage)
  ├─ Turn (turn_002)
  │   └─ ...
  └─ Turn (turn_003)
      └─ ...
```

### 5.2 Turn 状态机

```
        ┌──────────┐
        │  CREATED │
        └────┬─────┘
             │ turn/start
        ┌────▼─────┐
   ┌────│  RUNNING │◄─────┐
   │    └────┬─────┘      │
   │         │            │ approval/respond=approve
   │ user    │ need       │
   │ cancel  │ approval   │
   │    ┌────▼──────────┐ │
   │    │ WAITING_APPRV ├─┘
   │    └────┬──────────┘
   │         │ approval/respond=deny
   │    ┌────▼─────┐
   │    │  FAILED  │
   │    └──────────┘
   │
   │    ┌──────────┐
   ├───►│ CANCELED │
   │    └──────────┘
   │
   │    ┌──────────┐
   └───►│COMPLETED │
        └──────────┘
```

### 5.3 Agent Loop 主流程

```
turn/start
    │
    ▼
[1] 把 userMessage 添加到 Thread 历史
    │
    ▼
[2] 调用 ChatClient.prompt(history).tools(allTools).stream()
    │
    ├─ 流式 token ──► 发射 agentMessage item/updated
    │
    └─ tool_calls ──► [3]
                        │
                        ▼
                  [3] ApprovalEngine.check(tool, args, policy)
                        │
                        ├─ 不需审批 ──► 直接执行
                        │
                        └─ 需要审批 ──► 发 approval/request
                                          │
                                          ▼
                                     等 approval/respond
                                          │
                                          ├─ deny ──► 把"用户拒绝"作为工具结果回灌
                                          └─ approve ──► 执行
                        │
                        ▼
                  [4] SandboxPolicy.guard(path/command)
                        │
                        ├─ 违规 ──► 抛 SandboxViolation
                        └─ 通过 ──► ToolRegistry.execute
                        │
                        ▼
                  [5] 发射 commandExecution / fileChange item
                        │
                        ▼
                  [6] 把工具结果回灌到 history,回到 [2]
    │
    ▼
[7] 模型不再调用工具时,发 turn/completed
```

---

## 6. 审批与沙箱

> 📌 **重构说明(post-§17)**: 本章描述的 `ApprovalEngine` 概念性设计在 P1-3 阶段会**用 Spring AI Alibaba 的 `HumanInTheLoopHook` 落地实现**,审批反馈从二档(approve/deny)扩展为三档(approve/deny/**edit**),具体见 §17。本章节保留作为"审批语义"的概念定义,实现细节以 §17 为准。

### 6.1 三档审批策略 (照搬 Codex)

| 策略 | 行为 |
| --- | --- |
| `never` | 不弹任何审批,所有工具直接执行(危险,仅自动化场景用) |
| `on-request` | 默认策略。模型每次调用"写入类"工具(`write_file`/`exec_shell`/`apply_patch`)都弹审批 |
| `on-failure` | 工具执行失败时才弹审批(让用户选是否重试/换方式) |

### 6.2 三档沙箱模式

| 模式 | 行为 |
| --- | --- |
| `read-only` | 只允许读类工具,所有写入/执行都拒绝 |
| `workspace-write` | 默认。允许在 `cwd` + `writable_roots` 内写,外部需审批 |
| `danger-full-access` | 全开,绕过沙箱(只用于调试) |

### 6.3 工具与策略的关系矩阵

| 工具 | read-only | workspace-write | danger-full |
| --- | :-: | :-: | :-: |
| `read_file` | ✅ | ✅ | ✅ |
| `list_dir` | ✅ | ✅ | ✅ |
| `grep` | ✅ | ✅ | ✅ |
| `web_search` | ✅ | ✅ | ✅ |
| `write_file` (cwd 内) | ❌ | ✅(可能需审批) | ✅ |
| `write_file` (cwd 外) | ❌ | ❌ → 需审批升级 | ✅ |
| `exec_shell` | ❌ | ✅(默认需审批) | ✅ |
| `apply_patch` | ❌ | ✅(需审批) | ✅ |

---

## 7. 技术栈

### 7.1 Backend (Java)

| 组件 | 选型 | 用途 |
| --- | --- | --- |
| 语言 | Java 21 LTS | 主语言 |
| 框架 | Spring Boot 3.5.14 | Web/IoC/AutoConfig |
| AI 框架 | Spring AI Alibaba 1.1.2.x + Spring AI(OpenAI 兼容) | ChatClient/Tool 抽象 |
| 模型 | **多 Provider 可配置(DashScope / DeepSeek / OpenAI 兼容中转 / Ollama 本地)** | LLM 调用 |
| WebSocket | spring-boot-starter-websocket | 通信 |
| JSON | Jackson | 序列化 |
| 构建 | Maven (mvnw) | 构建工具 |
| 日志 | Logback + SLF4J | 日志 |

---

## 8. 多模型 Provider 架构

> **核心理念**: 模型是可插拔的资源,**用户在配置文件 + UI 设置面板**里维护一个 provider 列表,每个 provider 可独立配置 `base-url` / `apiKey` / 模型名,运行时按 `providerId` 切换。
> 这是 Codex 自身就有的设计(`[model_providers.xxx]`),我们对标实现。

### 8.1 支持的 Provider 类型

| Provider 类型 | 接入方式 | 典型场景 |
| --- | --- | --- |
| **DashScope (阿里通义)** | Spring AI Alibaba 原生 starter | qwen-plus / qwen-max / qwq-plus |
| **DeepSeek (官方)** | Spring AI deepseek 模块 | deepseek-chat / deepseek-reasoner |
| **OpenAI 官方** | Spring AI openai 模块 | gpt-4 / gpt-4o / o1 |
| **OpenAI 兼容中转** | OpenAiApi 自定义 baseUrl | OneAPI / NewAPI / 第三方代理 |
| **Ollama 本地** | OpenAI 兼容模式 | llama3 / qwen2.5-coder / 本地模型 |
| **Azure OpenAI** | OpenAiApi 自定义 baseUrl | 企业内部 Azure 部署 |

### 8.2 配置示例(参考 Codex `config.toml` 风格)

`application.yml`:
```yaml
babiq:
  # 当前激活的 provider id(用户可在 UI 设置面板里切换)
  active-provider: dashscope-default

  providers:
    # ========== 阿里 DashScope(原生) ==========
    - id: dashscope-default
      name: "通义千问 (DashScope)"
      type: dashscope
      api-key: ${AI_DASHSCOPE_API_KEY}
      model: qwen-plus
      options:
        temperature: 0.7
        max-tokens: 4096

    # ========== DeepSeek 官方 ==========
    - id: deepseek-official
      name: "DeepSeek (官方)"
      type: openai-compatible
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      model: deepseek-chat
      options:
        temperature: 0.5

    - id: deepseek-reasoner
      name: "DeepSeek-R1 (推理)"
      type: openai-compatible
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      model: deepseek-reasoner
      supports-reasoning: true       # 标记支持思考流

    # ========== 第三方 OpenAI 兼容中转 ==========
    - id: oneapi-relay
      name: "我的中转 (OneAPI)"
      type: openai-compatible
      base-url: https://my-relay.example.com/v1
      api-key: ${MY_RELAY_KEY}
      model: gpt-4o

    # ========== 本地 Ollama ==========
    - id: ollama-local
      name: "本地 Llama3"
      type: openai-compatible
      base-url: http://localhost:11434/v1
      api-key: ollama                  # Ollama 不校验,占位即可
      model: llama3:8b

    # ========== Azure OpenAI ==========
    - id: azure-gpt4
      name: "公司 Azure GPT-4"
      type: openai-compatible
      base-url: https://my-azure.openai.azure.com/openai/deployments/gpt4
      api-key: ${AZURE_OPENAI_KEY}
      model: gpt-4
```

### 8.3 后端实现(关键类)

```
backend/src/main/java/com/wzx/babiq/model/
├── ModelProviderRegistry.java      # 维护所有 provider 实例,按 id 查找
├── ModelProviderConfig.java        # provider 配置实体(对应 yaml 节点)
├── ChatClientFactory.java          # 按 providerId 返回 ChatClient
├── ProviderType.java               # enum: DASHSCOPE / OPENAI_COMPATIBLE
└── provider/
    ├── DashScopeProviderFactory.java
    ├── OpenAiCompatibleProviderFactory.java
    └── ProviderFactory.java        # 接口
```

`ChatClientFactory` 的核心逻辑(伪代码):
```java
public ChatClient resolve(String providerId) {
    ModelProviderConfig cfg = registry.get(providerId);

    return switch (cfg.getType()) {
        case DASHSCOPE -> {
            // 用 Spring AI Alibaba 原生 starter
            DashScopeChatModel model = DashScopeChatModel.builder()
                .api(DashScopeApi.builder().apiKey(cfg.getApiKey()).build())
                .defaultOptions(DashScopeChatOptions.builder()
                    .model(cfg.getModel())
                    .temperature(cfg.getOptions().getTemperature())
                    .build())
                .build();
            yield ChatClient.builder(model).build();
        }
        case OPENAI_COMPATIBLE -> {
            // 用 Spring AI OpenAiApi.mutate() 自定义 baseUrl
            OpenAiApi api = baseOpenAiApi.mutate()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .build();
            OpenAiChatModel model = baseOpenAiChatModel.mutate()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                    .model(cfg.getModel())
                    .temperature(cfg.getOptions().getTemperature())
                    .build())
                .build();
            yield ChatClient.builder(model).build();
        }
    };
}
```

> **设计要点**: 只用 2 个 Factory(DashScope + OpenAiCompatible)就能覆盖 6 类 provider,
> 因为 **DeepSeek / OpenAI 官方 / Azure / OneAPI / Ollama 全都走 OpenAI Compatible 协议**,
> 只是 `base-url` 和 `model` 不同。这是 Spring AI 设计的红利。

### 8.4 协议扩展(让客户端能切换)

`turn/start` 增加可选参数 `providerId`,**优先级**: 请求级 > session 级 > 全局默认。

```json
// Client → Server
{"jsonrpc":"2.0","method":"turn/start","id":3,"params":{
  "threadId":"thr_01",
  "providerId":"deepseek-reasoner",   // ← 本轮使用 DeepSeek-R1
  "input":{"type":"text","text":"分析这段代码的复杂度"}
}}
```

新增方法 `model/providers/list`,客户端用它拉取可选 provider 列表渲染下拉框:

```json
// Request
{"jsonrpc":"2.0","method":"model/providers/list","id":4}

// Response
{"jsonrpc":"2.0","id":4,"result":{
  "active":"dashscope-default",
  "providers":[
    {"id":"dashscope-default","name":"通义千问 (DashScope)","model":"qwen-plus","type":"dashscope"},
    {"id":"deepseek-official","name":"DeepSeek (官方)","model":"deepseek-chat","type":"openai-compatible"},
    {"id":"ollama-local","name":"本地 Llama3","model":"llama3:8b","type":"openai-compatible"},
    {"id":"oneapi-relay","name":"我的中转 (OneAPI)","model":"gpt-4o","type":"openai-compatible"}
  ]
}}
```

新增方法 `model/providers/set-active`(切换全局默认):
```json
{"jsonrpc":"2.0","method":"model/providers/set-active","id":5,"params":{
  "providerId":"deepseek-official"
}}
```

### 8.5 桌面端 UI 体现

- **顶部状态栏**: 显示当前 provider 名 + model,点击下拉切换 → 触发 `model/providers/set-active`
- **设置面板**: 列出所有 provider,支持新增 / 编辑 / 删除 / 测试连接
- **每条消息底部**: 显示"由 xxx 模型生成"(便于多模型对比学习)
- **可选**: 一次性把同一 prompt 发给 2-3 个 provider,并排对比输出(后期 Battle 模式)

### 8.6 安全与可配置项

| 项 | 说明 |
| --- | --- |
| API Key 存储 | **优先环境变量**(`${VAR_NAME}`),次选用户目录加密文件(P2 加 KeyStore) |
| 配置热加载 | P1 重启生效,P2 支持运行时 reload |
| 连接测试 | UI 提供"测试连接"按钮 → 后端 `model/providers/test` 方法,发个 `ping` prompt |
| Reasoning 模型 | 配置项 `supports-reasoning: true` 时,把模型输出按 `<think>...</think>` 切成 `reasoning` Item |
| 失败降级 | provider 调用失败时,可配置 `fallback-provider-id` 自动切换 |



| 组件 | 选型 | 用途 |
| --- | --- | --- |
| 语言 | Kotlin 2.3.21 | 主语言 |
| UI 框架 | Compose Multiplatform 1.11.0 | 桌面 UI |
| HTTP/WS 客户端 | Ktor Client 2.3+ | 后端通信 |
| 序列化 | kotlinx.serialization 1.7+ | JSON |
| 构建 | Gradle (Kotlin DSL) | 构建工具 |
| 打包 | Compose Desktop nativeDistributions | MSI/Dmg/Deb |

---

## 9. 学习路线分期

### P1 — 内核 + 端到端最小可用 (4-6 周)
- [ ] backend: WebSocket + JSON-RPC 框架
- [ ] backend: Thread/Turn/Item 数据模型 (12 种 Item 的接口与基础实现)
- [ ] backend: **多 Provider 配置体系**(DashScope + OpenAI Compatible 两个 Factory)
- [ ] backend: Agent Loop + ChatClient + 工具调用
- [ ] backend: 6 个核心工具 (read_file / write_file / exec_shell / list_dir / grep / apply_patch)
- [ ] backend: 审批引擎 (三档策略)
- [ ] backend: 沙箱 PathGuard (workspace-write)
- [ ] backend: `model/providers/*` 协议方法
- [ ] desktop: Compose Desktop 骨架 + ChatScreen
- [ ] desktop: WebSocket 客户端 + 流式渲染 agentMessage
- [ ] desktop: ApprovalDialog 弹窗
- [ ] desktop: 顶部 Provider 切换下拉 + 设置面板
- [ ] desktop: CommandPreview + DiffViewer 组件

**验收**: 在桌面端输入"分析 X 项目结构并写一个 README",能完整流程跑通,过程中弹审批,最终落盘文件。

### P2 — 持久化 + MCP + 多模型 (4-6 周)
- [ ] backend: SQLite 持久化 thread/turn/item
- [ ] backend: 历史会话恢复
- [ ] backend: MCP Client 接入 (调用外部 MCP Server)
- [ ] backend: 多模型路由 (qwen-plus / qwq-plus / 切换其他厂商)
- [ ] backend: webSearch + imageView 两种 Item 实现
- [ ] desktop: 会话列表 + 历史浏览
- [ ] desktop: 设置面板 (模型/沙箱/审批策略)

### P3 — 进阶机制 (长期)
- [ ] backend: 上下文压缩 (contextCompaction Item)
- [ ] backend: Review Mode (只读快照)
- [ ] backend: 真正的 OS 沙箱 (容器/jail)
- [ ] backend: 多 Agent 协作 (collabToolCall)
- [ ] desktop: 图片/文件拖拽
- [ ] desktop: 主题与快捷键

### P4 — 工程化 (长期)
- [ ] CI/CD (GitHub Actions)
- [ ] 可观测性 (ARMS / Micrometer)
- [ ] 自动化测试 (单元 + 集成)
- [ ] 文档站 (Docusaurus / mkdocs)

---

## 10. 关键设计决策

| 决策 | 选择 | 原因 |
| --- | --- | --- |
| 前后端是否分离 | **是,跨进程** | Kotlin 桌面端 + Java 后端天然两进程,顺势协议化 |
| 传输协议 | **WebSocket + JSON-RPC 2.0** | 双向通信(审批回弹),协议标准 wscat/Postman 都能测 |
| 状态模型 | **照抄 Codex Thread/Turn/Item** | 验证过的模型,不要发明 |
| Item 类型数量 | **完整 12 种(分期落地)** | 接口先齐,实现按 P1/P2/P3 分批 |
| 审批策略 | **三档照抄 Codex** | 已是社区共识 |
| 沙箱实现 | **P1 用 PathGuard(纯 Java),P3 上 OS 级** | Java 23 起 SecurityManager 废弃,P1 用路径白名单足够 |
| 持久化 | **P1 内存,P2 SQLite** | 学习项目不要一上来就引数据库 |
| 多 Thread 支持 | **P1 单 Thread,P2 多 Thread** | 减少状态管理复杂度 |
| 桌面端框架 | **Compose Multiplatform Desktop** | 用户偏好 Kotlin,Compose 是当前最现代选择 |

---

## 11. 风险与待定项

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Java 25 + Spring Boot 3.5 兼容 | 编译/运行问题 | 直接固定 JDK 21 LTS |
| Compose Desktop 生态较新 | 资料少,踩坑多 | 多看 JetBrains 官方 sample |
| Spring AI Alibaba 版本迭代快 | API 可能变动 | 锁定 1.1.2.x,升级前看 changelog |
| WebSocket 流式 token 切包 | UI 卡顿/乱码 | 客户端做 buffer 拼接,服务端按 token 边界发 |
| 审批 UX | 用户体验决定项目可用性 | 参考 Codex 的 always-allow 机制 |

---

## 12. 参考资料

- **Codex 源码**: [openai/codex](https://github.com/openai/codex)
- **Codex 协议文档**: [codex-rs/app-server/README.md](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md)
- **Spring AI Alibaba**: [java2ai.com](https://java2ai.com/)
- **Compose Multiplatform**: [jetbrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform)
- **JSON-RPC 2.0 规范**: [jsonrpc.org/specification](https://www.jsonrpc.org/specification)

---

## 13. 下一步动作

待用户确认本架构后:
1. 进入 `superpowers:brainstorming`,细化协议字段、错误码、配置项
2. 进入 `superpowers:writing-plans`,产出可执行的 P1 任务清单(带验收标准)
3. 用 OMC 执行,从 backend 骨架开始
4. 全程用 wscat/Postman 验证后端协议,**桌面端最后写**

---

## 14. Memory 与上下文工程

> 本章是 P1 之后才会涉及的设计,但接口约定在 P1 期就需要锁定。
> 对照 OpenAI Codex / Claude Code 的等价机制,借助 Spring AI Alibaba 内置组件落地。

### 14.1 三个相近概念辨析

| 概念 | 定义 | 决定方 | 示例 |
|---|---|---|---|
| **上下文窗口** (Context Window) | 模型一次能接受的 token 物理上限 | 模型架构,**不可改** | qwen-plus 1M / gpt-5 1M / claude-opus-4-7 200K |
| **上下文工程** (Context Engineering) | "**如何往窗口里塞最有效的内容**"的方法论与工程 | 应用代码 | PromptBuilder / RAG / Hook |
| **Memory**(记忆) | 上下文工程的一个**子集**,专指"管理对话历史"这块 | 应用代码 | ChatMemory / Store |

**比喻**: 窗口是杯子的容量,工程是倒水策略,Memory 是其中"管理已经倒进去的水"这一环。

### 14.2 双层记忆模型(对标 Codex / Claude Code,用 Spring AI Alibaba 实现)

```
┌──────────────────────────────────────────────────────────┐
│  长期记忆 (Long-term Memory) — Spring AI Alibaba `Store`  │
│  - 跨 Thread / 跨会话的用户偏好、知识、经验               │
│  - 由 LLM 通过 saveMemory / getMemory 工具主动读写         │
│  - namespace + key + value(可选向量化)                  │
│  - 对标 Codex 的 `POST /memories/summarize` 后产出物       │
│  - 实现: MemoryStore(内存) / RedisStore / 自定义          │
└──────────────────────────────────────────────────────────┘
         ↕(LLM 在每个 Turn 可主动召回)
┌──────────────────────────────────────────────────────────┐
│  短期记忆 (Short-term Memory) — Spring AI 标准 `ChatMemory` │
│  - 同一 Thread 内对话历史                                  │
│  - MessageWindowChatMemory: 滑动窗口,保留最近 N 条          │
│  - + ChatMemoryRepository: 持久化(JDBC / Redis)            │
│  - + SummarizationHook: 接近上限时 LLM 自动摘要(策略 3)   │
│  - 对标 Codex 的 ResponseItem 数组 + `/compaction`         │
└──────────────────────────────────────────────────────────┘
         ↓ 注入到 Prompt
┌──────────────────────────────────────────────────────────┐
│  Model Context Window (qwen-plus 1M / qwen-max 256K / ...) │
│  + 由 ModelMetadata 自动识别上限                            │
└──────────────────────────────────────────────────────────┘
```

### 14.3 上下文工程的 5 大支柱(Spring AI Alibaba 提供的组件)

| 支柱 | Spring AI Alibaba 组件 | 用途 |
|---|---|---|
| **PromptBuilder** | `PromptBuilder` 接口 + `PromptContribution` | 多源 prompt 片段按 `priority` 动态拼装 |
| **ChatMemory** | `MessageWindowChatMemory` + `MessageChatMemoryAdvisor` | 短期会话历史滑窗 |
| **Hook 系统** | `MessagesModelHook` + `@HookPositions({BEFORE_MODEL})` | 在送给模型前拦截/改写历史 |
| **Tool / Function Calling** | `FunctionToolCallback` + `@Tool` | LLM 主动拉上下文(查库、查文件、查 Memory) |
| **Store(长期记忆)** | `Store` + `namespace/key/value` | 跨会话用户偏好 / 知识 |
| **VectorStore Advisor(可选)** | `VectorStoreChatMemoryAdvisor` | RAG 语义召回过去对话 |

### 14.4 模型上下文窗口管理 — `ModelMetadata`

**问题**: Spring AI / Spring AI Alibaba 没有内置"获取模型 context window 大小"的 API。
**方案**: 智能默认(内置主流模型映射)+ `application.yml` 可 override。

#### 14.4.1 内置主流模型映射(2026-05 数据)

| Provider | 模型 | Context Window |
|---|---|---:|
| 阿里通义 | qwen-plus(3.5/3.6) | **1,000,000** |
| | qwen-turbo | 128,000 |
| | qwen-max(qwen3-max) | 262,144 |
| | qwq-plus | 131,072 |
| DeepSeek | deepseek-chat / reasoner | 128,000 |
| | deepseek-v4 | 1,000,000 |
| OpenAI | gpt-4o | 128,000 |
| | gpt-4.1 / gpt-5 | 1,000,000 |
| | o1 | 200,000 |
| Anthropic | claude-opus-4-7 / sonnet-4-6 | 200,000 |
| 本地 Ollama | llama3:8b | 8,192 |
| | qwen2.5-coder:7b | 32,768 |

#### 14.4.2 实现伪代码

```java
package com.wzx.babiq.server.model;

import java.util.Map;
import static java.util.Map.entry;

public final class ModelMetadata {
	public static final int DEFAULT_CONTEXT_WINDOW = 32_768;

	private static final Map<String, Integer> CONTEXT_WINDOWS = Map.ofEntries(
		entry("qwen-plus",            1_000_000),
		entry("qwen-turbo",             128_000),
		entry("qwen-max",               262_144),
		entry("qwq-plus",               131_072),
		entry("deepseek-chat",          128_000),
		entry("deepseek-v4",          1_000_000),
		entry("gpt-4o",                 128_000),
		entry("gpt-5",                1_000_000),
		entry("claude-opus-4-7",        200_000),
		entry("llama3:8b",                8_192)
		// ... 其余见 P1-2 实现
	);

	public static int contextWindowOf(String model) {
		return CONTEXT_WINDOWS.getOrDefault(model.toLowerCase(), DEFAULT_CONTEXT_WINDOW);
	}
}
```

#### 14.4.3 配置可 override

```yaml
babiq:
  providers:
    - id: my-custom-relay
      type: openai-compatible
      model: my-proprietary-llm
      context-window: 65536    # ← 自定义模型显式指定,缺省则用 ModelMetadata
```

### 14.5 会话压缩策略 — 4 种方案对照

| # | 策略 | 工作原理 | 优 | 缺 | BaBiQ 阶段 |
|:-:|---|---|---|---|:-:|
| 1 | **Sliding Window** | 只保留最近 N 条,直接丢弃旧的 | 零 LLM 成本、零延迟 | 丢早期信息 | **P1** |
| 2 | Rolling Summary | 全程维护一个总结,每轮更新 | 信息连续 | 每轮 LLM 调用,延迟高 | ❌ 不用 |
| 3 | **Summary + Buffer** | 旧消息压成摘要 + 最近 N 条原样;接近上限才触发 | 平衡性能与信息保留 | 触发瞬间延迟突增 | **P2-P3** |
| 4 | Hierarchical Summary | 多粒度摘要(最近原样/中期段摘要/远期元摘要) | 长会话最强 | 实现复杂 | P4+ 可选 |

**业界共识**: 策略 1 兜底,策略 3 主力,策略 4 仅长会话场景。

#### 14.5.1 Spring AI Alibaba 的 SummarizationHook(策略 3 落地组件)

```java
SummarizationHook summarizationHook = SummarizationHook.builder()
    .model(/* 便宜的 summary 专用模型,如 qwen-turbo */)
    .maxTokensBeforeSummary((int)(contextWindow * 0.70))   // 70% 触发
    .messagesToKeep(10)                                     // 保留最近 10 条
    .build();

ReactAgent agent = ReactAgent.builder()
    .model(chatModel)
    .hooks(summarizationHook)
    .saver(new MemorySaver())
    .build();
```

#### 14.5.2 P3 期的自定义 Code-Agent 压缩 instructions(对标 Codex)

```java
public class BaBiQSummarizationHook extends MessagesModelHook {
    private static final String CODE_AGENT_INSTRUCTIONS = """
        总结以下对话,**必须保留**:
        1. 用户的原始需求和目标
        2. 已执行过的 shell 命令及结果
        3. 已读/写过的文件路径与关键内容
        4. 遇到的错误及尝试过的解决方案
        5. 待办事项 (TODO)

        **不必保留**: 寒暄、重复的中间思考、已被覆盖的旧文件内容
        """;
    // 触发摘要时把 instructions 拼到 prompt 里;
    // 摘要完成后发 contextCompaction Item 通知客户端
}
```

并发 `contextCompaction` Item(对标 Codex `thread/compact/start`):
```json
{
  "method": "item/added",
  "params": {
    "item": {
      "type": "contextCompaction",
      "summarizedCount": 35,
      "summary": "...",
      "tokensSaved": 18432
    }
  }
}
```

### 14.6 工具输出截断 — Code Agent 必做(P1 起就要)

**风险**: 一次 `read_file` 读大文件、`exec_shell` 输出 stack trace,可能直接吃掉数十万 token。
**策略**: 每个工具的返回结果**强制截断**到上限,超出部分用占位符替代。

```yaml
babiq:
  tools:
    output-max-tokens: 4000              # 每个工具单次输出上限
    output-truncate-marker: "...[truncated, N tokens omitted]"
    per-tool-override:
      read_file: 8000                    # 文件读可放宽
      exec_shell: 4000
      grep: 2000
```

**实现位置**: `ToolRegistry` 在调用工具之后、把结果送回 Agent Loop 之前统一截断。

### 14.7 BaBiQ 分期路线

| 阶段 | Memory | 工具截断 | 压缩 | 长期记忆 | 模型元数据 |
|---|---|---|---|---|---|
| **P1** | `MessageWindowChatMemory(20)` + `MessageChatMemoryAdvisor` | ✅ output-max-tokens 4K | ❌(策略 1 兜底) | ❌ | ✅ ModelMetadata |
| **P2** | + `ChatMemoryRepository`(SQLite) | ✅ | ❌ | ❌ | ✅ |
| **P3** | 同 P2 | ✅ | ✅ `SummarizationHook` + 自定义 instructions + `contextCompaction` Item | ❌ | ✅ |
| **P3+** | 同 P3 | ✅ | ✅ + 用户可手动 `/compact` | ✅ `Store` + saveMemory/getMemory 工具 | ✅ + 调度同步 LiteLLM JSON |
| **P4** | 同 P3+ | ✅ | ✅(可选 Hierarchical) | ✅ + VectorStore RAG | ✅ |

### 14.8 关键决策点

| 决策 | 选择 | 原因 |
|---|---|---|
| 短期记忆实现 | Spring AI Alibaba `MessageWindowChatMemory` | 标准组件,免造轮子 |
| 触发阈值 | 上下文窗口的 **70%** | 留 30% buffer 给本轮 prompt + 输出 |
| 摘要后保留条数 | 最近 **10 条** | 经验值,够维持对话局部一致性 |
| 摘要模型 | qwen-turbo(便宜) | 与主模型解耦,降本 |
| Token 估算 | P1 用 `length / 4` 粗估;P3 上 Tokenizer 精算 | YAGNI |
| 多 Thread | P1 单 Thread,P2 起多 Thread | 复用 Codex Thread 模型 |
| 手动 compact 命令 | P3 桌面端加 `/compact` 输入 | 用户可控比自动更可靠 |
| 工具输出截断 | **P1 必做**,默认 4K,可 per-tool override | 防 `read_file` / `exec_shell` 单次爆窗 |

### 14.9 已知陷阱 — Rolling Summary 不是银弹

Mem0 / Claude Code 用户社区共识:
- **自动摘要后经常丢关键信息**,用户不爽
- **很多时候开新 Thread 比压缩更好**
- 压缩要**可手动控制**(对标 Codex `/compact`),用户能看到何时压缩,不爽可拒绝
- 压缩后**保留原始 rollout**(完整历史持久化),用户可"回滚"

BaBiQ 设计据此:
- P3 才上 LLM 压缩,**P1-P2 不上**(qwen-plus 1M 够用)
- P3+ 加手动 `/compact` 命令
- P2 SQLite 持久化完整 Item 流,**永远不丢原始历史**

### 14.10 参考资料

- [Spring AI Alibaba Memory Tutorial](https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory)
- [Spring AI Alibaba SummarizationHook](https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks)
- [Spring AI Alibaba PromptBuilder](https://java2ai.com/agents/assistantagent/features/prompt-builder/quickstart)
- [Codex `/compaction` API](https://github.com/openai/codex/blob/main/codex-rs/codex-api/README.md)
- [Codex `/memories/summarize` API](https://github.com/openai/codex/blob/main/codex-rs/codex-api/README.md)
- [LiteLLM model_prices_and_context_window.json](https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json)
- [Mem0: LLM Chat History Summarization Guide](https://mem0.ai/blog/llm-chat-history-summarization-guide-2025)
- [arXiv: Memory for Autonomous LLM Agents (2026 survey)](https://arxiv.org/html/2603.07670v1)

---

## 15. Hooks 与 Interceptors 横切设计

> Spring AI Alibaba 的 Hook/Interceptor 机制可以**重构 BaBiQ 的 Agent Loop**,把横切关注点(限流、审批、截断、token 统计)从 loop 主流程里拆出来。

### 15.1 体系全景

```
ReactAgent 执行生命周期
─────────────────────────────────────────────────
   BEFORE_AGENT  ← 计时器、初始化、整体策略
       │
   循环开始 ↓
   ┌──────────────────────────────────────┐
   │  BEFORE_MODEL  改 messages、限流、PII │
   │       │                              │
   │   调 LLM                             │
   │       │                              │
   │  AFTER_MODEL   统计 token、监控       │
   │       │                              │
   │  BEFORE_TOOL   审批拦截、参数改写    │
   │       │                              │
   │   执行 Tool                          │
   │       │                              │
   │  AFTER_TOOL    输出截断、结果改写    │
   └──────────────────────────────────────┘
       │
   AFTER_AGENT  ← 清理、上报
─────────────────────────────────────────────────
```

| 类 | 作用范围 | 例子 |
|---|---|---|
| `AgentHook` | 整体生命周期 | 计时、整体审计 |
| `ModelHook` | 模型调用前后 | `ModelCallLimiterHook` 限流、`SummarizationHook` 摘要 |
| `MessagesModelHook` | 改 messages 列表的 ModelHook | `SummarizationHook` 继承自此 |
| `ToolHook` | 工具调用前后 | 审批 / 输出截断 |
| `Interceptor` | 链式包装整个调用 | 重试、守门(Guardrail) |

**Hook vs Interceptor**:
- **Hook** = 生命周期点上读 / 改 `state`(主要修数据)
- **Interceptor** = 链式包装,可决定是否继续(主要控流程,如重试、守门)

### 15.2 BaBiQ 落地的 8 个 Hook(对照表)

| # | Hook / Interceptor 名 | 位置 | 作用 | 替代当前设计 | 阶段 |
|:-:|---|---|---|---|:-:|
| 1 | `ModelCallLimiterHook(maxCalls=20)` | BEFORE_MODEL | 防 ReAct 死循环 | 取代 AgentLoop 自己的 max-iterations 兜底 | P1 |
| 2 | `ToolOutputTruncationHook(4K)` | AFTER_TOOL | 工具输出截断(实现 D19) | 从 ToolRegistry 内部移到 Hook,**解耦** | P1 |
| 3 | `ApprovalHook(approvalEngine)` | BEFORE_TOOL | 拦截需审批工具 | 重构 ApprovalEngine 的调用方式 | P1-3 |
| 4 | `TokenUsageHook(eventEmitter)` | AFTER_MODEL | 累计 token,发 `tokenUsage` 事件 | 新增能力(对标 Codex `thread/tokenUsage/updated`) | P1-3 |
| 5 | `SummarizationHook` | BEFORE_MODEL | 自动摘要(见 §14.5) | 已设计 | P3 |
| 6 | `PiiFilterHook` | BEFORE_MODEL | 屏蔽敏感词 | 新增 | P3+ |
| 7 | `AuditLogHook` | AFTER_AGENT | 完整记录 input/output/cost | 替代散落在各处的 log | P2 |
| 8 | `RetryInterceptor` | 包裹模型调用 | 网络抖动 / 429 自动重试 | 新增 | P2 |

### 15.3 用 Hook 重构 Agent Loop 的效果对比

**当前设计(ARCHITECTURE §5.3,硬编码)**:
```
agentLoop() {
    while (!done && iterations < MAX) {
        var modelOut = chatClient.prompt(history).call();
        var toolCall = parser.parse(modelOut);
        if (toolCall == null) break;
        if (approvalEngine.needsApproval(toolCall)) { await approval; }
        if (!sandboxPolicy.allows(toolCall)) { ... }
        var result = toolRegistry.execute(toolCall);
        result = truncate(result, MAX_TOOL_OUTPUT);
        history.add(result);
        iterations++;
    }
}
```
**约 200 行,所有关注点都在一处**。

**Hook 化设计**:
```java
ReactAgent agent = ReactAgent.builder()
    .model(chatModel)
    .tools(toolCallbacks)
    .hooks(
        new ModelCallLimiterHook(20),
        new ApprovalHook(approvalEngine),
        new ToolOutputTruncationHook(4000),
        new TokenUsageHook(eventEmitter)
    )
    .interceptors(
        new SandboxInterceptor(sandboxPolicy),
        new RetryInterceptor(3)
    )
    .build();

agent.call(userMessage, config);   // 主流程就这一行
```
**Agent Loop 本身约 50 行,每个 Hook 单独可测、可插拔**。

### 15.4 关键决策

| 决策 | 选择 | 原因 |
|---|---|---|
| Hook 注册方式 | `ReactAgent.builder().hooks(...)` 构造时传入 | Spring AI Alibaba 标准用法 |
| 横切关注点边界 | **限流 / 审批 / 截断 / token 统计 = Hook**;**重试 / 守门 / 沙箱包装 = Interceptor** | 数据流改写归 Hook,流程控制归 Interceptor |
| 自定义 Hook 位置 | `backend/src/main/java/com/wzx/babiq/server/hook/` | 新包,与 `agent/` 平行 |
| Hook 顺序 | builder 注册顺序 = 执行顺序 | 注意 ApprovalHook 必须在 SandboxInterceptor 之前 |

### 15.5 参考资料

- [Spring AI Alibaba Hooks & Interceptors](https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks)

---

## 16. Structured Output 与 Item 字段契约

> 让模型输出**严格符合 Java 类结构的 JSON**,自动反序列化。
> 这不仅是"格式化输出",而是**Item 模型契约的关键支撑**。

### 16.1 三种用法

```java
// ① BeanOutputConverter(传统两步)
var converter = new BeanOutputConverter<>(AgentPlan.class);
String format = converter.getFormat();  // 自动生成 JSON Schema 描述文本
String json = chatClient.prompt("规划: " + task + "\n\n" + format).call().content();
AgentPlan plan = converter.convert(json);

// ② ChatClient.entity() 一站式(推荐)
AgentPlan plan = chatClient.prompt("规划: " + task)
    .call()
    .entity(AgentPlan.class);  // 自动拼 schema + 调模型 + 反序列化

// ③ Provider 原生 response_format(OpenAI / Ollama / DashScope 支持)
Prompt prompt = new Prompt(task,
    DashScopeChatOptions.builder()
        .format(converter.getJsonSchemaMap())   // 模型层级强制 JSON schema
        .build());
```

**必备注解**: `@JsonProperty(required = true)` 标必填,否则 schema 字段可能缺失。

### 16.2 BaBiQ 落地的 7 个场景

| # | 用途 | 数据结构 | 阶段 |
|:-:|---|---|:-:|
| 1 | **Plan Item 生成**(对标 Codex `plan` item) | `record PlanItem(String id, String goal, List<PlanStep> steps, String reasoning)` | P1-3 |
| 2 | **Agent 下一步决策**(取代手写 ToolCall 解析) | `record NextAction(boolean done, String reasoning, String tool, Map<String,Object> args)` | P1-3 |
| 3 | **fileChange Item diff** | `record FileDiff(String path, String action, List<Hunk> hunks)` | P1-3 |
| 4 | **commandExecution 结构化** | `record ShellResult(int exitCode, String stdout, String stderr, long durationMs)` | P1-3 |
| 5 | **grep 命中结果** | `record GrepHit(String file, int line, String snippet)` | P1-3 |
| 6 | **审批理由** | `record ApprovalReason(String summary, String risk, String alternative)` | P3 |
| 7 | **错误诊断** | `record ErrorDiagnosis(String category, String rootCause, List<String> suggestions)` | P3 |

### 16.3 Item 字段契约 — 闭环设计

**12 种 Item 不再只是"类型 tag",而是 record + Structured Output 闭环**:

```java
@JsonClassDescription("Agent 在某个 Turn 中生成的执行计划")
public record PlanItem(
    @JsonProperty(required = true) String id,
    @JsonProperty(required = true) String type,                    // 固定 "plan"
    @JsonProperty(required = true) String goal,
    @JsonProperty(required = true) List<PlanStep> steps,
    @JsonProperty(required = true) String reasoning
) implements ThreadItem {

    public record PlanStep(
        @JsonProperty(required = true) int order,
        @JsonProperty(required = true) String description,
        String tool,                       // 可选,纯思考步骤可省
        Map<String, Object> toolArgs       // 可选
    ) {}
}

// 让模型直接生成 PlanItem(无需手写 JSON 解析)
PlanItem plan = chatClient.prompt(planningPrompt)
    .call()
    .entity(PlanItem.class);

itemEmitter.emit(plan);   // 直接发出 JSON-RPC item/added 通知
// 客户端用同一份 record(via kotlinx.serialization)反序列化
```

**闭环要素**:
- **Backend**: Java record + `@JsonProperty(required=true)`
- **Schema**: Spring AI 自动生成,送给模型
- **Model**: 输出符合 schema 的 JSON
- **Wire format**: Spring AI 自动反序列化为 record
- **Client**: kotlinx.serialization 用相同字段反序列化

### 16.4 关键决策

| 决策 | 选择 | 原因 |
|---|---|---|
| Item 字段定义方式 | **Java record + `@JsonProperty(required=true)`** | 不可变 + Spring AI 自动生成 schema |
| 模型输出契约 | **每个 Item 子类型都有对应 record** | Plan / FileChange / CommandExecution 都用结构化生成,不写正则 |
| 兼容不支持 `response_format` 的 provider | **回退到 `BeanOutputConverter` + 提示词加 schema** | 自定义 OpenAI Compatible 中转可能不支持 native JSON mode |
| 客户端反序列化 | Kotlin record-equivalent(`data class`) + kotlinx.serialization | 与 Java 一一对应,字段名一致 |
| 验证失败时 | 重试 1 次 + 降级为 text-only `agentMessage` | 防 JSON malformed 阻塞 Turn |

### 16.5 注意事项

- ⚠️ **不是所有 provider 都支持 `response_format`**:OpenAI / Ollama / DashScope 支持,但通过第三方中转可能不支持 → BaBiQ `OpenAiCompatibleProviderFactory` 需要可配置 `supports-structured-output: true/false`
- ⚠️ **复杂嵌套类型用 `ParameterizedTypeReference`**:`List<X>` / `Map<K,V>` 这种泛型必须用,否则擦除丢失
- ⚠️ **本地模型(Llama 等)JSON 输出质量参差**:小模型容易出格式错误,需要重试或降级

### 16.6 参考资料

- [Spring AI Alibaba Structured Output](https://java2ai.com/blog/spring-ai-output-structure)
- [Spring AI BeanOutputConverter](https://java2ai.com/integration/chatmodels/ollama-chat)

---

## 17. HITL 与中断 / 恢复机制(Human-in-the-Loop)

> **关键判断**: 2026 业界共识 — Agent 的安全性 ≠ "拒绝调用工具",而是"**在不可逆动作前暂停 → 让人参与决策 → 接住人的反馈**"。
> Spring AI Alibaba 提供 `HumanInTheLoopHook`,可直接落地这套机制。**§6 的 ApprovalEngine 概念性设计,在 P1-3 用 HITL Hook 实现**。

### 17.1 三档反馈 — 比原设计多一档

| 反馈类型 | 行为 | 在 ItemEmitter 发送的 Item |
|---|---|---|
| **APPROVED** | 批准原参数执行工具 | `commandExecution`(running) / `fileChange` |
| **REJECTED** | 拒绝,带 reason 返回给模型作为"工具失败" | `commandExecution`(denied) + agentMessage |
| **EDITED** ⭐ | **用户修改工具参数后再执行** | `commandExecution`(running, args=edited) |

**EDITED 是 game changer**: 用户看到 `exec_shell {command: "rm -rf node_modules"}`,可以改成 `rm -rf node_modules/old` 后批准,而不是粗暴拒绝让模型重试。

### 17.2 Spring AI Alibaba `HumanInTheLoopHook` 落地

```java
HumanInTheLoopHook hitlHook = HumanInTheLoopHook.builder()
    .approvalOn("write_file")
    .approvalOn("exec_shell", ToolConfig.builder()
        .description("Shell 命令需要人工确认")
        .build())
    .approvalOn("apply_patch", ToolConfig.builder()
        .description("文件 diff 需要审查")
        .build())
    // read_file / list_dir / grep 不放入 → 自动放行
    .build();

ReactAgent agent = ReactAgent.builder()
    .model(chatModel)
    .tools(...)
    .hooks(hitlHook)
    .saver(new MemorySaver())   // ⚠️ 必须配 checkpointer
    .build();
```

`HITLHelper` 工具类提供三类反馈快捷构造:
```java
HITLHelper.approveAll(metadata);
HITLHelper.rejectAll(metadata, "操作不安全");
HITLHelper.editTool(metadata, "exec_shell",
    "{\"command\": \"ls -la\"}");      // 用户编辑后的安全命令
```

### 17.3 Checkpoint + Resume 持久化

HITL 的中断点必须能持久化,否则 turn 一挂就丢:

| Saver 实现 | 适用 | BaBiQ 阶段 |
|---|---|:-:|
| `MemorySaver` | turn 内审批(进程不重启) | **P1** |
| 自定义 `SqliteSaver` | 跨进程恢复(应用重启后还能 resume) | **P2** |
| `RedisSaver` | 高可用 / 多实例 | P3+ |

恢复流程:
```java
// 1. 首次调用 → 命中审批 → graph 在 BEFORE_TOOL 暂停
Optional<NodeOutput> out1 = compiledGraph.invokeAndGetOutput(input,
    RunnableConfig.builder().threadId("turn_001").build());
// 此时 state 已持久化,server 发 approval/request 给客户端

// 2. 客户端用 approval/respond 回复(可能数分钟/数小时后)

// 3. 服务端用 updateState 注入反馈 → graph 接着跑
compiledGraph.updateState(config,
    Map.of("approval_decision", HITLHelper.approveAll(metadata)),
    null);
```

### 17.4 中断协议方法(对标 Codex)

| 协议方法 | 当前 | 新加 | 说明 |
|---|:-:|:-:|---|
| `turn/cancel` | ✅ | | 取消等待审批(已设计) |
| **`turn/interrupt`** | | ✅ 新增 | **立即停 turn**,server 发 `turn/completed` (status="interrupted"),对标 Codex |
| `turn/steer`(P3+) | | ✅ 新增 | 中途改方向(注入新指令,不取消) |
| `approval/respond` | ✅ | 🔄 扩展 | decision 从二档扩到 **三档**:`approve` / `deny` / **`edit`**;edit 需附 `edited_args` |

#### `approval/respond` 三档扩展样本

```json
// Client → Server (现有 approve / deny)
{"jsonrpc":"2.0","method":"approval/respond","id":99,"params":{
  "decision":"approve",
  "scope":"turn"
}}

// 新增:edit
{"jsonrpc":"2.0","method":"approval/respond","id":99,"params":{
  "decision":"edit",
  "scope":"turn",
  "editedArgs":{"command":"ls -la /workspace"}    // 用户改后的参数
}}
```

#### `turn/interrupt` 样本

```json
// Client → Server
{"jsonrpc":"2.0","method":"turn/interrupt","id":31,"params":{
  "threadId":"thr_123",
  "turnId":"turn_456"
}}

// Server → Client(随后)
{"jsonrpc":"2.0","method":"turn/completed","params":{
  "threadId":"thr_123","turnId":"turn_456",
  "status":"interrupted"
}}
```

### 17.5 5 大生产陷阱(必看)

| # | 陷阱 | BaBiQ 对策 |
|:-:|---|---|
| 1 | **永不恢复** — 用户走开后再不来批 | **审批 TTL 默认 5 分钟**,超时自动 deny + 发 `turn/failed` |
| 2 | **thread_id 串台** — 不同用户复用 ID | thread_id 永远 `thr_<uuid>`,不复用 |
| 3 | **过度中断** — 训练用户麻木批准 | 仅 `write_file` / `exec_shell` / `apply_patch` 触发审批;`read_file` / `list_dir` / `grep` 永远放行 |
| 4 | **瞬时错误也触发 HITL** — 网络抖动让用户莫名审批 | `RetryInterceptor` 处理瞬时错误,**不进 HITL 流程** |
| 5 | **规划阶段也审批** — 规划是思考不是执行 | **Plan-and-Execute** 模式:规划期(reasoning/plan)不审批,执行期(tool call)才审批 |

### 17.6 BaBiQ P1-P3 HITL 路线

| 阶段 | 实现 | 反馈类型 | Checkpointer | 协议 |
|:-:|---|---|---|---|
| **P1-3** | `HumanInTheLoopHook` + `approvalOn(...)` 白名单 | **3 档**(approve/deny/edit) | `MemorySaver` | `approval/respond` 三档 + `turn/interrupt` |
| **P2** | + SQLite Saver(跨重启恢复) | 3 档 | `SqliteSaver` 自定义 | + 审批通知(P2+ 可选 Webhook) |
| **P3+** | + RedisSaver(高可用) + `turn/steer` | 3 档 + steer | `RedisSaver` | + Plan-and-Execute 边界审批 |

### 17.7 与原 §6 ApprovalEngine 的关系

| 原 §6 概念 | P1-3 实际实现 |
|---|---|
| `ApprovalEngine.needsApproval(tool, policy)` | `HumanInTheLoopHook(BEFORE_TOOL)` 自动拦截 |
| `ApprovalPolicy`(never/on-request/on-failure) | **保留**,但实现方式:动态切换 Hook 注册的工具白名单 |
| `CompletableFuture<ApprovalDecision>`(D8) | **替换为 Saver checkpoint + updateState** —— 跨进程恢复 |
| `PendingApprovalRegistry` | **不需要**(Saver 已经管 state) |
| `approval/request` / `approval/respond`(协议) | **保留**,但 respond 扩到三档 |
| `ApprovalDecision`(approve / deny) | 扩为 `FeedbackResult.APPROVED / REJECTED / EDITED` |

**结论**: §6 的协议语义不变(`approval/request` / `approval/respond` 还是这些方法),内部实现从"自己写状态机"变成"用 HITL Hook + Saver"。**这是 §6 的实现细化,不是颠覆**。

### 17.8 已落实的关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| 审批实现 | `HumanInTheLoopHook` 取代手写 ApprovalEngine | Spring AI Alibaba 标准组件,自带 checkpoint/resume |
| 反馈档位 | 3 档(approve/deny/**edit**) | EDITED 让用户能修参数,大幅提升 UX |
| Turn 中断 | `turn/interrupt` 新协议方法 | 对标 Codex,用户能立即停 |
| Plan-and-Execute | **规划期不审,执行期才审** | 业界 2026 共识,避免训练用户麻木 |
| Steer(改方向) | P3+,不在 P1-2 范围 | YAGNI |
| 审批 TTL | 默认 5 分钟,可 yml 配置 | 防永不恢复 |

### 17.9 参考资料

- [Spring AI Alibaba HumanInTheLoopHook](https://java2ai.com/docs/frameworks/agent-framework/advanced/human-in-the-loop)
- [Spring AI Alibaba Graph HITL](https://java2ai.com/docs/frameworks/graph-core/examples/human-in-the-loop)
- [Codex `turn/interrupt` API](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md)
- [Codex Steer 机制](https://github.com/openai/codex/blob/main/sdk/python/notebooks/sdk_walkthrough.ipynb)
- [LangChain: Making it easier to build human-in-the-loop agents with interrupt](https://www.langchain.com/blog/making-it-easier-to-build-human-in-the-loop-agents-with-interrupt)
- [Human-in-the-Loop Patterns for AI Agents (2026)](https://myengineeringpath.dev/genai-engineer/human-in-the-loop/)

---

## 18. Multi-Agent 与 Agent-as-Tool

> Spring AI Alibaba 提供 7 种 Multi-Agent 模式 + Agent 包装为 ToolCallback 的能力。
> 这让 BaBiQ **从 P2 起可以从"单 agent 万能"升级为"专家团队"**,对标 Claude Code Task 工具。

### 18.1 七种 Multi-Agent 模式

| 模式 | 类 | 行为 | BaBiQ 阶段 |
|---|---|---|:-:|
| **ReactAgent** | 基础 | 单 agent ReAct 循环(P1 在用) | **P1** |
| **SequentialAgent** | 顺序 | 多 agent 串行,前者 output 喂后者 input | **P2**(Plan→Execute) |
| **ParallelAgent** | 并行 | 多 agent 同时跑,结果 merge 到 mergeOutputKey | P4+ |
| **LlmRoutingAgent** | 路由 | LLM 智能选一个 sub-agent 执行 | **P3**(代码专家分工) |
| **SupervisorAgent** | 监督 | sub-agent 完成后回 supervisor 再决策,可循环 | **P4** |
| **LoopAgent** | 循环 | 重复执行直到满足条件 | P4+ |
| **Hybrid** | 嵌套 | 上面几种自由组合 | P4+ |

### 18.2 四个核心模式代码样本

#### A. SequentialAgent — Plan-and-Execute(P2 落地 D24)
```java
SequentialAgent planAndExecute = SequentialAgent.builder()
    .name("plan_then_execute")
    .subAgents(List.of(
        plannerAgent,    // 规划期:产出 PlanItem,不调工具,不审批
        executorAgent    // 执行期:按 plan 调工具,触发 HITL 审批
    ))
    .build();
```

#### B. ParallelAgent — 多源信息聚合
```java
ParallelAgent research = ParallelAgent.builder()
    .subAgents(List.of(webResearchAgent, codeSearchAgent, gitHistoryAgent))
    .mergeOutputKey("research_data")
    .build();
// 三路并发,速度 ×3
```

#### C. LlmRoutingAgent — 代码专家分工(P3 落地)
```java
LlmRoutingAgent codeRouter = LlmRoutingAgent.builder()
    .model(chatModel)
    .subAgents(List.of(
        codeReaderAgent,      // tools: read_file, list_dir, grep
        codeEditorAgent,      // tools: write_file, apply_patch
        codeRunnerAgent       // tools: exec_shell
    ))
    .build();
// "帮我看一下 X 文件"   → 路由到 codeReaderAgent
// "修改 X 文件的 Y 函数" → 路由到 codeEditorAgent
// "跑一下测试"          → 路由到 codeRunnerAgent
```

#### D. SupervisorAgent — 复杂任务监督(P4 落地)
```java
SupervisorAgent supervisor = SupervisorAgent.builder()
    .model(chatModel)
    .systemPrompt("你是 BaBiQ 主控,负责派遣专家完成用户任务...")
    .instruction("""
        当前任务: {input}
        已完成步骤: {history}
        请决定下一步:
        - code_reader_agent    需要查代码
        - code_editor_agent    需要改代码
        - code_runner_agent    需要跑代码
        - FINISH              任务完成
        """)
    .subAgents(List.of(codeReaderAgent, codeEditorAgent, codeRunnerAgent))
    .build();
// 子 agent 完成 → 回 supervisor → 再决策 → 直到 FINISH
```

### 18.3 Agent-as-Tool — 两种用法

#### 方式 A:`agent.asNode()` 嵌入 StateGraph(细粒度)

```java
StateGraph workflow = new StateGraph(keyStrategyFactory);
workflow.addNode("preprocess", node_async(preprocessor));
workflow.addNode(qaAgent.name(), qaAgent.asNode(
    true,    // includeContents: 传递父图消息历史
    false    // includeReasoning: 不返回推理过程
));
workflow.addEdge("preprocess", qaAgent.name());
workflow.addEdge(qaAgent.name(), "validate");
```

#### 方式 B:包装为 `ToolCallback`(对标 Claude Code Task 工具)⭐

```java
// 把 sub-agent 包装成普通工具
ReactAgent codeReader = ReactAgent.builder()
    .name("code_reader")
    .tools(readFileTool, grepTool, listDirTool)
    .instruction("你是代码阅读专家,只回答代码相关问题")
    .build();

ToolCallback askCodeReaderTool = FunctionToolCallback.builder("ask_code_reader",
        (Function<String, String>) query -> {
            var result = codeReader.invoke(query);
            return result.map(s -> s.value("answer", "").toString()).orElse("");
        })
    .description("把代码相关复杂问题委派给代码读取专家;返回结构化结果")
    .inputType(String.class)
    .build();

// 主 agent 当作普通工具用
ReactAgent mainAgent = ReactAgent.builder()
    .tools(askCodeReaderTool, ...)
    .build();
```

→ **完全等同于 Claude Code 的 `Task` 工具**:派遣 sub-agent 去脏活,主 agent 上下文不被淹没。

### 18.4 BaBiQ 应用的 6 个具体场景

| # | 场景 | 用什么 | 价值 |
|:-:|---|---|---|
| 1 | **Plan-and-Execute** | `SequentialAgent(planner, executor)` | 规划期省成本,执行期才审批(落地 D24) |
| 2 | **代码助手专家分工** | `LlmRoutingAgent(reader/editor/runner)` | 每子 agent 工具少 → 准确率↑ token↓ |
| 3 | **并行调研** | `ParallelAgent(code/git/issue)` | 速度 ×3 |
| 4 | **自我审查** | `SequentialAgent(writer, reviewer, fixer)` | 一次性产出高质量代码 |
| 5 | **Claude Code Task 模式** | sub-agent 包装为 ToolCallback | 主 agent 委派脏活,上下文不被淹没 |
| 6 | **迭代式调试** | `LoopAgent + 失败条件` | "试→失败→修→试" 自动循环 |

### 18.5 对当前架构的影响

| 当前(单 ReactAgent) | Multi-Agent 加入后 |
|---|---|
| 一个 agent 干所有事 | **专家分工**:reader / editor / runner / planner |
| 工具集大,token 占用高 | **每个 sub-agent 工具集小**,prompt 精简 |
| 复杂任务 prompt 难写 | **每个 sub-agent instruction 专注一件事** |
| 没有"分支重试" | LoopAgent + 条件边支持自动迭代 |
| 上下文容易被工具输出淹没 | **sub-agent 包装成工具后,主 agent 只见结果不见过程** |

### 18.6 BaBiQ 引入 Multi-Agent 路线

```
P1   单 ReactAgent
       │ (P1 不引入 Multi-Agent,保持简单可靠)
       ▼
P2   + SequentialAgent (Plan-and-Execute)
       - PlannerAgent → ExecutorAgent
       - 落地 D24:规划不审,执行才审
       - PlanItem 真正激活
       ▼
P3   + LlmRoutingAgent (代码专家分工)
       - CodeReader / CodeEditor / CodeRunner / ProjectExplorer
       - 每个 sub-agent 2-3 个工具
       ▼
P4   + SupervisorAgent + Agent-as-Tool
       - 复杂任务由 supervisor 派遣专家
       - sub-agent 通过 ToolCallback 暴露
       - 对标 Claude Code Task 工具
       ▼
P4+  + ParallelAgent / LoopAgent
       - 多源信息聚合
       - 自动迭代调试
```

### 18.7 协议层的对应扩展

引入 Multi-Agent 后,协议层有几个变化:

| 协议方法 / Item | 何时启用 | 说明 |
|---|---|---|
| `subAgentInvocation` Item(对应 §4.3 `collabToolCall` 类型) | **P2** | 通知客户端"主 agent 派遣 sub-agent X 去执行" |
| `agent/list`(新方法) | P3 | 客户端拉取当前可用 sub-agent 列表渲染 UI |
| `agent/info`(新方法) | P3 | 客户端查 sub-agent 的 description / 工具列表 |
| 复用 `item/added` 嵌套 sub-agent 的 item | P2+ | sub-agent 的 item 用 `parentItemId` 关联到父级 |

### 18.8 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| P1 是否引入 Multi-Agent | **不引入**(单 ReactAgent) | 学习项目要稳;qwen-plus 1M 单 agent 够用 |
| P2 第一个 Multi-Agent | **SequentialAgent**(Plan-and-Execute) | 落地 D24 的最直接方式 |
| Agent-as-Tool 主推 | **方式 B(包装为 ToolCallback)** | 对标 Claude Code Task,主 agent 上下文最干净 |
| Sub-agent 命名规范 | `xxx_agent`(snake_case + 后缀) | LLM 路由识别更稳 |
| Sub-agent 独立 Provider | **可独立配置**(简单任务用便宜模型) | code_reader 用 qwen-turbo,planner 用 qwen-max |

### 18.9 参考资料

- [Spring AI Alibaba Multi-Agent](https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent)
- [Spring AI Alibaba Multi-Agent Supervisor](https://java2ai.com/docs/frameworks/graph-core/examples/multi-agent-supervisor)
- [Spring AI Alibaba Agent Tools](https://java2ai.com/docs/frameworks/agent-framework/tutorials/tools)
- [Claude Code Task Tool(对标场景)](https://docs.anthropic.com/en/docs/claude-code)

---

## 19. Workflow 与 A2A 跨进程 Agent 协议

> 本章讨论两个**更高层**的特性:
> - **Workflow (StateGraph)**: Multi-Agent 的底层引擎,P4+ 复杂流程时显式使用
> - **A2A (Agent-to-Agent)**: 跨进程跨网络的 Agent 通信协议,P4+ 让 BaBiQ 接入更广 Agent 生态
>
> 这两个对 P1-P3 都**不直接使用**,但 P1 协议设计需要为未来 A2A 兼容性留扩展点。

### 19.1 三维度对比:Multi-Agent vs Workflow vs A2A

| 维度 | Multi-Agent | Workflow (StateGraph) | A2A |
|---|---|---|---|
| **作用域** | 单进程内 | 单进程内 | **跨进程 / 跨网络 / 跨组织** |
| **抽象层级** | 模式快捷 API | 底层图编排 | 网络协议 |
| **互操作性** | 同框架内 | 同框架内 | **跨框架** (Google ADK / LangChain / 自研) |
| **运输层** | Java 调用 | Java 调用 | **HTTP + JSON-RPC 2.0** (或 gRPC) |
| **服务发现** | builder 注册 | 编译时确定 | **Nacos / 服务注册中心** |
| **典型场景** | Plan-Execute、专家路由 | 客服流程、审批流程、自动测试 | 多企业 Agent 互通、Agent 互联网 |
| **关系** | 是 Workflow 的快捷工厂 | 底层引擎 | 完全不同维度 |

→ **Multi-Agent 内部就是 Workflow,Workflow 内部可以嵌套 A2aRemoteAgent**。

### 19.2 Workflow (StateGraph) 速览

```java
StateGraph workflow = new StateGraph(keyStrategyFactory);

// 节点(普通 Java)
workflow.addNode("preprocess", node_async(new PreprocessorNode()));
workflow.addNode("validate",   node_async(new ValidatorNode()));

// 节点(嵌套 ReactAgent)
workflow.addNode(qaAgent.name(), qaAgent.asNode(true, false));

// 线性边 + 条件边(支持循环、分支)
workflow.addEdge(START, "preprocess");
workflow.addEdge("preprocess", qaAgent.name());
workflow.addConditionalEdges("validate",
    edge_async(s -> (Boolean) s.value("is_valid", false) ? "end" : "retry"),
    Map.of("end", END, "retry", qaAgent.name()));

CompiledGraph graph = workflow.compile(CompileConfig.builder().build());
```

**何时显式用**:
- P1-P2:不显式使用(用 Multi-Agent 高级 API)
- P3:复杂分支 / 循环时考虑
- P4+:**代码审查工作流、自动测试 + 修复循环、客服多步流程** 等场景

### 19.3 A2A 协议总览

**起源**: Google 2025-04 发布,2026 捐献给 Linux Foundation。

**地位**: 2026 多 Agent 三大支柱
```
MCP   Agent ↔ 工具      已有(MCP)
A2A   Agent ↔ Agent     本节
ADK   跨语言 SDK        Agent Development Kit
```

**4 大协议能力**:
| 能力 | 说明 |
|---|---|
| Capability Discovery | 每个 Agent 发布 `AgentCard` (JSON),声明能力 |
| Task Management | 围绕"任务"的协作语义 |
| Collaboration | 消息 / 上下文 / 工件 / 用户指令交换 |
| UX Negotiation | 消息含 `parts` (text/image/form/iframe),客户端协商 UI 能力 |

**业界采用**: Adobe、S&P Global、ServiceNow 已采用。

### 19.4 A2A 双向使用 — BaBiQ 不只是被动暴露

A2A 协议**本身就是双向的**,BaBiQ 可以扮演三种角色:

```
┌──────────────────────────────────────────────────────────────┐
│ 角色 1: BaBiQ 作为 A2A Client(调用别人)— **优先做这个**     │
│                                                               │
│   User → BaBiQ ──A2A──> 其他 Agent (Google/Adobe/同事/...)    │
│                                                               │
│   场景: 把远程专家 Agent 当工具用,接入更广 Agent 生态        │
│   实现: A2aRemoteAgent + AgentCardProvider (Nacos 发现)       │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ 角色 2: BaBiQ 作为 A2A Server(被人调)                        │
│                                                               │
│   其他 Agent ──A2A──> BaBiQ → User Workspace                 │
│                                                               │
│   场景: BaBiQ 的 code_reader/code_runner 被其他 Agent 调用    │
│   实现: application.yml 配 a2a.server + AgentCard            │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ 角色 3: BaBiQ 同时既是 Client 也是 Server(中继 / 编排器)    │
│                                                               │
│   Agent A ──A2A──> BaBiQ ──A2A──> Agent B                    │
│                                                               │
│   场景: BaBiQ 作为多家 Agent 的"集成枢纽"                    │
└──────────────────────────────────────────────────────────────┘
```

### 19.5 Spring AI Alibaba A2A 实现 — 两端都有

#### 角色 1 (Client):调用远程 Agent

```java
@Component
public class A2AClientExample {
    @Autowired private AgentCardProvider agentCardProvider;

    public void callRemote() {
        A2aRemoteAgent remote = A2aRemoteAgent.builder()
            .name("data_analysis_agent")
            .agentCardProvider(agentCardProvider)   // 从 Nacos 自动获取 AgentCard
            .description("数据分析远程代理")
            .build();

        Optional<OverAllState> result = remote.invoke("请根据 Q4 数据给同环比");
        result.ifPresent(state -> System.out.println(state.value("output")));
    }
}
```

#### 角色 2 (Server):暴露本地 Agent

`application.yml`:
```yaml
spring:
  ai:
    alibaba:
      a2a:
        server:
          version: 1.0.0
          card:
            name: babiq_code_assistant
            description: 代码阅读、编辑、运行的通用助手
            provider:
              name: BaBiQ
              organization: WangZhouX
```

#### 注册中心 Nacos

Nacos 最新版**原生支持 A2A AgentCard 存储和推送**,Server 自动注册 / Client 自动订阅。

### 19.6 BaBiQ 用 A2A 的两种姿势 — Client 优先

| 维度 | 角色 1:BaBiQ 当 Client | 角色 2:BaBiQ 当 Server |
|---|---|---|
| 场景举例 | 调 Google ADK search agent / Adobe Express agent / 同事的私有专家 | 别人 Agent 调用 BaBiQ 的 code_reader、code_runner |
| **学习价值** | **高** — 学怎么消费 A2A | 中 — 学怎么暴露 A2A |
| **实用价值** | **高** — 接入更广生态 | 取决于 BaBiQ 能力是否值得别人调 |
| 优先级 | **P4+ 优先做** | P5+ 才考虑 |

### 19.7 ⭐ A2A + Agent-as-Tool + Multi-Agent 三合一 — Agent 互联网雏形

P4+ 阶段把三个特性叠加,BaBiQ 可以实现:

```java
// 1. 找一个外部 A2A Agent(同事部署的代码库专家)
A2aRemoteAgent codeSpecialist = A2aRemoteAgent.builder()
    .name("code_search_specialist")
    .agentCardProvider(agentCardProvider)
    .build();

// 2. 包装成 ToolCallback(D26 Agent-as-Tool 方式 B)
ToolCallback specialistTool = FunctionToolCallback.builder("ask_code_specialist",
    (Function<String, String>) q ->
        codeSpecialist.invoke(q).map(s -> s.value("output", "").toString()).orElse(""))
    .description("远程代码库专家(A2A)")
    .build();

// 3. 主 agent 把本地工具 + 远程 A2A agent 混合使用
ReactAgent babiqMain = ReactAgent.builder()
    .tools(readFileTool, writeFileTool, execShellTool,    // 本地工具
           specialistTool)                                  // 远程 A2A agent (当成普通工具)
    .build();
```

**用户感知**: 全是"工具",但实际跨了多组织、多机器。这就是 **2026 业界追求的 Agent 互联网雏形**。

### 19.8 与现有 BaBiQ JSON-RPC 协议的兼容性 — 重要!

我们当前 BaBiQ 协议(ARCHITECTURE §4)与 A2A 协议高度同源:

| 维度 | BaBiQ 协议 | A2A 协议 | 兼容性 |
|---|---|---|---|
| 运输 | WebSocket + JSON-RPC 2.0 | HTTP + JSON-RPC 2.0 / gRPC | ✅ 同协议家族 |
| 状态模型 | Thread / Turn / Item | Task / Message / Part | ⚠️ 命名不同但语义相近 |
| 能力发现 | `model/providers/list` | `AgentCard` | ⚠️ 我们是模型级,A2A 是 Agent 级 |
| 流式 | `item/added` / `item/updated` | streaming messages | ✅ 都支持 |
| UX 协商 | (待设计) | `parts` (text/image/form/iframe) | ⚠️ 可借鉴 |

→ **P1 协议设计建议**:
- Item 字段命名保持中性(`type` / `content` / `parts`),不要硬绑 BaBiQ 特定语义
- 流式事件命名保持 `item/added` `item/updated`(与 A2A streaming 同形)
- 预留 `metadata` 字段(MCP / A2A 都有这个扩展点)

**未来添加 `A2AAdapter` 工作量**:把 BaBiQ Thread/Turn/Item 映射到 A2A Task/Message/Part,**预计 1-2 天**。

### 19.9 BaBiQ 引入路线

| 阶段 | Workflow | A2A |
|:-:|---|---|
| **P1** | ❌ 不用(单 ReactAgent) | ❌ 不用,但**协议命名预留兼容性** |
| **P2** | ⚠️ 间接用(SequentialAgent 内部是) | ❌ 不用 |
| **P3** | ⚠️ 复杂分支可能用 | ❌ 不用 |
| **P4** | ✅ 显式用(代码审查 / 自动修复循环) | ✅ **作为 Client 调用外部 Agent**(优先) |
| **P5+** | ✅ 复杂 workflow 设计 | ✅ **同时作为 Server 暴露**(组合 Client + Server) |

### 19.10 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| Workflow 显式引入时机 | **P4+,且仅在 Multi-Agent 不够灵活时** | YAGNI;Multi-Agent 已经覆盖 80% 场景 |
| A2A 第一个角色 | **Client(BaBiQ 调别人)** | 接入更广生态价值更高;Server 留 P5+ |
| A2A 注册中心 | **Nacos**(Spring AI Alibaba 原生支持) | 阿里生态一致性 |
| 协议兼容性预留 | **P1 命名规范偏 A2A**(parts / metadata) | 未来 A2AAdapter 工作量 1-2 天 |
| Agent 互联网完整形态 | **A2A Client + Agent-as-Tool 包装** | 用户视角"全是工具",底层跨组织 |

### 19.11 参考资料

- [Spring AI Alibaba A2A 文档](https://java2ai.com/docs/frameworks/agent-framework/advanced/a2a)
- [Spring AI Alibaba StateGraph](https://java2ai.com/docs/frameworks/graph-core/core/core-library)
- [Google A2A 官方协议](https://a2a-protocol.org/latest/)
- [A2A GitHub 仓库(Linux Foundation)](https://github.com/a2aproject/A2A)
- [Google A2A 原始发布(2025-04)](https://developers.googleblog.com/en/a2a-a-new-era-of-agent-interoperability/)
- [Agent2Agent 协议升级(2026)](https://cloud.google.com/blog/products/ai-machine-learning/agent2agent-protocol-is-getting-an-upgrade)
- [IBM: 什么是 Agent2Agent 协议](https://www.ibm.com/think/topics/agent2agent-protocol)

