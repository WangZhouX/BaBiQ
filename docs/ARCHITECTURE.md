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
