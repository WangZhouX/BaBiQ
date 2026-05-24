# P2-2 Thread History and Recent Conversations Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, otherwise use `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 BaBiQ 从“当前窗口内存聊天”升级为“可重启恢复的多会话历史”，让桌面端左侧最近对话来自 SQLite。

**Architecture:** 后端以 P2-1 的 repository adapter 为唯一持久化入口，不让 JSON-RPC handler 或桌面端碰 Mapper。运行中仍允许 `ConversationService` 保留活动 turn 的小型内存索引，但 Thread、Turn、Item、TurnSummary 的长期真相源切到 SQLite；桌面端通过新增 JSON-RPC method 加载 thread 列表和历史 item 流。

**Tech Stack:** Java 21, Spring Boot 3.5.14, MyBatis-Plus 3.5.16, SQLite JDBC 3.53.1.0, Flyway 12.6.2, Kotlin 2.3.21, Compose Multiplatform 1.11.0, Ktor Client 3.5.0, kotlinx.serialization 1.11.0, WebSocket, JSON-RPC 2.0.

---

## 0. 当前上下文

P2-2 必须在 P2-1 完成后实现。当前 P1/P2 起点:

- `ConversationService` 仍用 `ConcurrentHashMap` 保存 thread 和 turn。
- `ThreadCreateHandler` 只返回 `threadId`。
- `TurnStartHandler` 创建 turn 后把真实 AgentLoop 交给 `TurnExecutor`。
- `ItemEmitter` 只负责向 WebSocket 推送 `turn/started`、`item/added`、`turn/completed` 等事件。
- 桌面端 `Sidebar` 的“最近”仍是 `state.messages` 推导出的假数据。
- `AgentClient` 只实现 `thread/create`、`turn/start`、审批、Provider、Sandbox 等 P1 方法。

P2-2 的核心是补齐“历史数据查询”和“运行时事件落库”，但不做 Provider 编辑、设置系统、运行统计大屏或 MCP。

## 1. 依赖和前置检查

- 必须已完成 P2-1:
  - `ConversationRepository`
  - `TurnRepository`
  - `ItemRepository`
  - `TurnSummaryRepository`
  - SQLite migration
  - repository adapter 集成测试
- 实现前重新运行:

```powershell
cd backend
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

预期: 两边都通过，确认 P2-1 基线没有坏。

## 2. 协议设计

### 2.1 `thread/list`

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "thread/list",
  "params": {
    "cwd": "E:\\BaBiQ",
    "includeArchived": false,
    "limit": 30,
    "cursor": null
  }
}
```

Response:

```json
{
  "threads": [
    {
      "threadId": "thr_xxx",
      "title": "分析 BaBiQ 项目结构",
      "cwd": "E:\\BaBiQ",
      "providerId": "deepseek-v4-pro",
      "model": "deepseek-v4-pro",
      "status": "active",
      "lastTurnStatus": "completed",
      "updatedAt": "2026-05-24T12:00:00+08:00",
      "messageCount": 8
    }
  ],
  "nextCursor": null
}
```

### 2.2 `thread/load`

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "method": "thread/load",
  "params": {
    "threadId": "thr_xxx",
    "limit": 200,
    "beforeItemId": null
  }
}
```

Response:

```json
{
  "thread": {
    "threadId": "thr_xxx",
    "title": "分析 BaBiQ 项目结构",
    "cwd": "E:\\BaBiQ",
    "status": "active"
  },
  "items": [
    {
      "id": "it_xxx",
      "type": "userMessage",
      "text": "你好啊"
    }
  ],
  "latestSummary": null,
  "nextBeforeItemId": null
}
```

### 2.3 `thread/archive`

Request:

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "method": "thread/archive",
  "params": {
    "threadId": "thr_xxx"
  }
}
```

Response:

```json
{
  "ok": true,
  "threadId": "thr_xxx",
  "archived": true
}
```

## 3. 文件结构

### 后端生产代码

- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java`
  - 保留活动 turn 内存索引。
  - 创建 thread/turn 时同步调用 repository。
  - 生成默认标题。
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationApplicationService.java`
  - 为 JSON-RPC handler 提供 thread list/load/archive 的应用服务。
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationEventRecorder.java`
  - 统一记录 `turn/started`、`item/added`、`turn/completed`、`turn/failed`、`turnSummary`。
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`
  - 注入可选 recorder，在发出事件时同步落库。
  - 先写库再发事件；写库失败时抛异常，让 turn 明确失败，而不是 UI 看到幽灵事件。
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadLoadHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadArchiveHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/dto/ThreadSummaryDto.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/dto/ThreadLoadResult.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadCreateHandler.java`
  - 返回 `title` 和 `cwd`，便于前端立即更新侧边栏。
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`
  - 构造 `ItemEmitter` 时传入 recorder。

### 桌面端生产代码

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ThreadHistoryModels.kt`
  - `ThreadListResult`
  - `ThreadSummaryInfo`
  - `ThreadLoadResult`
  - `ThreadArchiveResult`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
  - `listThreads`
  - `loadThread`
  - `archiveThread`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
  - 增加 `ThreadListItem`、`ThreadHistoryState`。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
  - 增加 `threadHistory`。
  - 增加 `currentThreadTitle`。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
  - 连接后加载最近会话。
  - 新对话时清空当前 thread 但不清空历史列表。
  - 切换工作目录后重新加载该目录下最近会话。
  - 点击历史会话时调用 `thread/load` 并把 item 转成 UI 消息。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt`
  - item 事件仍更新当前聊天流。
  - turn 完成后触发最近会话刷新。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/Sidebar.kt`
  - 最近对话列表改用 `state.threadHistory.items`。
  - 增加选中态、归档入口、加载/空态。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/AppShell.kt`
  - 传入 `onNewChat`、`onOpenThread`、`onArchiveThread`。

### 后端测试

- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ThreadListHandlerTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ThreadLoadHandlerTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ThreadArchiveHandlerTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/conversation/ConversationEventRecorderTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/conversation/ConversationHistoryIT.java`

### 桌面端测试

- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatReducerTest.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ThreadHistoryModelsTest.kt`

## 4. TDD 任务

### Task 1: 后端 thread/list 协议

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ThreadListHandlerTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/dto/ThreadSummaryDto.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationApplicationService.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- `method()` 返回 `thread/list`。
- `limit` 为空时默认 30。
- `limit > 100` 时裁剪到 100。
- `includeArchived=false` 时不返回 archived thread。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ThreadListHandlerTest test
```

Expected: 编译失败或 handler 不存在。

- [ ] **Step 3: 实现最小 handler 和 DTO**

实现规则:

- handler 只解析参数。
- 查询逻辑全部委托 `ConversationApplicationService.listThreads(...)`。
- handler 不依赖 mapper。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ThreadListHandlerTest test
```

Expected: PASS。

### Task 2: 后端 thread/load 协议

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ThreadLoadHandlerTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadLoadHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/dto/ThreadLoadResult.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationApplicationService.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- 缺少 `threadId` 返回 `INVALID_PARAMS`。
- thread 不存在返回 `INVALID_PARAMS` 或 `NOT_FOUND`，以当前错误码体系为准。
- 返回 items 时保持 `sequence_no` 顺序。
- `payload_json` 作为原始 item JSON 返回，不额外改写字段。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ThreadLoadHandlerTest test
```

- [ ] **Step 3: 实现 load**

实现规则:

- 读取 thread metadata。
- 分页读取 items。
- 读取最新 turnSummary。
- 不在 load 时恢复 running turn，这属于 P2-4。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ThreadLoadHandlerTest test
```

### Task 3: 后端 thread/archive 协议

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ThreadArchiveHandlerTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadArchiveHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationApplicationService.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- 归档 active thread。
- 重复归档幂等。
- 归档后默认 `thread/list` 不返回。
- 当前 running turn 不允许归档，返回业务错误。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ThreadArchiveHandlerTest test
```

- [ ] **Step 3: 实现归档**

实现规则:

- 设置 `status=archived` 和 `archived_at`。
- 不物理删除 items。
- 如果归档的是当前内存 thread，清理 `ConversationService` 中的活动索引。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ThreadArchiveHandlerTest test
```

### Task 4: 运行时事件落库

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/conversation/ConversationEventRecorderTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationEventRecorder.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoopSupport.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/observability/TurnSummaryEmitter.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- `emitItemAdded(UserMessageItem)` 后能在 repository 查到 item。
- `TurnSummaryEmitter.emit(...)` 后能查到 `bq_turn_summaries` 和对应 item。
- `emitTurnCompleted` 后 turn 状态为 completed。
- `emitTurnFailed` 后 turn 状态为 failed 且记录 reason。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ConversationEventRecorderTest test
```

- [ ] **Step 3: 实现 recorder**

实现规则:

- recorder 负责序列化完整 item payload。
- sequence_no 在 repository 内按 thread/turn 追加生成。
- 如果落库失败，不吞异常。
- 关键代码必须写中文注释，解释为什么“先落库再发事件”。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ConversationEventRecorderTest test
```

### Task 5: 后端端到端重启恢复集成测试

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/conversation/ConversationHistoryIT.java`

- [ ] **Step 1: 写失败测试**

测试流程:

1. 使用临时 SQLite 文件启动 Spring context。
2. 创建 thread。
3. 记录 user item、agent item、turnSummary。
4. 重启 context 或重新构造 repository。
5. 调用 `thread/list` 和 `thread/load`。
6. 断言历史仍在。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ConversationHistoryIT verify
```

- [ ] **Step 3: 修通集成路径**

重点:

- 测试数据库路径不要污染真实 `${user.home}/.babiq/babiq.db`。
- 使用 P2-1 的测试配置。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ConversationHistoryIT verify
```

### Task 6: 桌面端协议模型和 AgentClient

**Files:**
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ThreadHistoryModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ThreadHistoryModelsTest.kt`

- [ ] **Step 1: 写失败测试**

覆盖:

- `listThreads()` 发送 method `thread/list`。
- `loadThread()` 能把 item JSON 解成 `ThreadItem`。
- `archiveThread()` 发送 method `thread/archive`。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest"
```

- [ ] **Step 3: 实现协议模型和 client 方法**

实现规则:

- 保持 `ignoreUnknownKeys=true`。
- 日期先用字符串传输，UI 展示再格式化，避免跨平台时区解析风险。

- [ ] **Step 4: 运行测试通过**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest"
```

### Task 7: 桌面端状态和侧边栏

**Files:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/Sidebar.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/AppShell.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatReducerTest.kt`

- [ ] **Step 1: 写失败测试**

覆盖:

- 连接成功后自动调用 `listThreads`。
- 点击历史会话后调用 `loadThread` 并替换当前消息列表。
- 新对话只清空当前会话，不删除历史。
- 归档会话后从列表移除。
- 切换工作目录后按新 cwd 重新加载最近列表。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*ChatControllerTest" --tests "*ChatReducerTest"
```

- [ ] **Step 3: 实现状态层**

实现规则:

- `ThreadHistoryState` 包含 `loading/error/items/selectedThreadId`。
- 历史加载失败只显示错误，不影响当前聊天继续运行。
- 从历史 item 转 UI message 时复用 reducer 里的 item 转换逻辑，避免双份映射。

- [ ] **Step 4: 实现 Sidebar UI**

要求:

- 最近对话显示标题、更新时间、最后状态。
- 当前 thread 有选中态。
- 归档按钮或菜单必须不挤压标题。
- 空态显示“暂无对话”。
- 不启用搜索/插件/自动化真实能力。

- [ ] **Step 5: 运行测试通过**

```powershell
cd desktop
.\gradlew.bat test --tests "*ChatControllerTest" --tests "*ChatReducerTest"
```

### Task 8: 全量验证和文档同步

**Files:**
- Modify: `docs/superpowers/plans/p2-2-thread-history/codex-handoff.md`
- Modify: `docs/superpowers/plans/p2-task-index.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: 后端全量测试**

```powershell
cd backend
.\mvnw.cmd clean verify
```

Expected: BUILD SUCCESS。

- [ ] **Step 2: 桌面端全量测试**

```powershell
cd desktop
.\gradlew.bat test
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: 手动验收**

流程:

1. 启动后端。
2. 启动桌面端。
3. 新建对话并发送一轮。
4. 停掉后端。
5. 重启后端和桌面端。
6. 左侧最近对话仍显示刚才会话。
7. 点击会话能恢复消息和成本摘要。
8. 归档后默认列表不显示。

- [ ] **Step 4: 更新文档**

更新:

- `docs/superpowers/plans/p2-2-thread-history/codex-handoff.md`
- `docs/superpowers/plans/p2-task-index.md`
- `AGENTS.md`
- `CLAUDE.md`

- [ ] **Step 5: 中文 commit**

```powershell
git add backend desktop docs AGENTS.md CLAUDE.md
git commit -m "feat(p2-2): 接入多会话历史和最近对话"
```

不要 push。

## 5. 验收标准

- 后端新增 `thread/list`、`thread/load`、`thread/archive`。
- Thread、Turn、Item、TurnSummary 在运行中同步落库。
- 后端重启后历史会话仍可查询。
- 桌面端最近对话来自真实后端数据。
- 点击历史会话可恢复消息流。
- 归档会话默认隐藏。
- `cd backend; .\mvnw.cmd clean verify` 通过。
- `cd desktop; .\gradlew.bat test` 通过。
- `AGENTS.md` 和 `CLAUDE.md` 已同步新检查点。

## 6. 非目标

- 不做 Provider/API Key 编辑。
- 不做运行详情历史查询大屏。
- 不做全文搜索。
- 不做跨设备同步。
- 不做 P2-4 的 running turn 恢复语义。
