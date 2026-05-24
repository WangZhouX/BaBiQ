# P2-4 Recovery Semantics and Run Records Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, otherwise use `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为持久化后的 Thread/Turn 建立明确恢复语义和运行记录查询能力，让失败、取消、中断、审批、工具调用和 TurnSummary 都可追溯。

**Status:** 已完成实现并通过自动化验收；P2-4 不做跨进程 ReactAgent checkpoint resume，只做诚实的 interrupted/expired 收束。

**Architecture:** 后端启动时由 recovery service 扫描 SQLite 中遗留的 running/waiting turn，并按 P2 规则标记为 interrupted/expired，而不是尝试恢复 Spring AI Alibaba ReactAgent checkpoint。运行记录查询从持久化表聚合，桌面端运行详情面板从“当前内存事件”升级为“当前 + 历史”视图。

**Tech Stack:** Java 21, Spring Boot 3.5.14, MyBatis-Plus 3.5.16, SQLite JDBC 3.53.1.0, Flyway 12.6.2, Kotlin 2.3.21, Compose Multiplatform 1.11.0, Ktor Client 3.5.0, kotlinx.serialization 1.11.0, JSON-RPC 2.0.

---

## 0. 当前上下文

P2-4 必须等待:

- P2-1: 持久化基础。
- P2-2: thread/load、item 持久化、最近会话。

建议等待:

- P2-3: approval always 和策略持久化。

当前 P1/P2 前置能力:

- `TurnExecutor` 用内存 `running` map 追踪后台任务。
- `PendingApprovals` 用内存 map 保存 SAA HITL 暂停元数据。
- `TurnObservationRegistry` 用内存 map 保存当前 turn 观测上下文。
- 后端重启后这些内存状态都会消失。

因此 P2-4 的恢复规则必须诚实: 不恢复正在执行的 ReactAgent，只把数据库中的遗留状态收束成可解释的历史记录。

## 1. 恢复语义

启动时扫描:

| 原状态 | P2-4 启动后状态 | 原因 |
|---|---|---|
| `RUNNING` | `INTERRUPTED` | 后端进程已重启，原 worker 不存在 |
| `SENDING` | `INTERRUPTED` | 请求已进入但未完成，不能假装成功 |
| `WAITING_APPROVAL` | `EXPIRED` | SAA InterruptionMetadata 只在内存中，无法跨进程 resume |
| `COMPLETED` | 保持 | 已完成 |
| `FAILED` | 保持 | 已失败 |
| `CANCELED` | 保持 | 已取消 |

需要新增或确认 `TurnStatus`:

- `INTERRUPTED`
- `EXPIRED`

如果不想扩展 enum，可以先映射到 `FAILED` 并记录 `failureReason=interrupted_by_server_restart`，但推荐扩展 enum，便于 UI 明确显示。

## 2. JSON-RPC 协议

### 2.1 `run/turns/list`

按 thread 查询运行记录:

```json
{
  "threadId": "thr_xxx",
  "limit": 50,
  "cursor": null
}
```

返回:

```json
{
  "turns": [
    {
      "turnId": "turn_xxx",
      "status": "completed",
      "providerId": "deepseek-v4-pro",
      "model": "deepseek-v4-pro",
      "startedAt": "2026-05-24T12:00:00+08:00",
      "completedAt": "2026-05-24T12:00:08+08:00",
      "durationMs": 8200,
      "toolCount": 5,
      "estimatedCostUsd": 0.0021
    }
  ],
  "nextCursor": null
}
```

### 2.2 `run/turn/get`

查询单个 turn 的详情:

```json
{
  "turnId": "turn_xxx"
}
```

返回:

```json
{
  "turn": {},
  "items": [],
  "summary": {},
  "approvals": [],
  "toolCalls": [],
  "failureReason": null
}
```

### 2.3 `run/recovery/status`

启动后给 UI 或调试面板展示最近一次恢复动作:

```json
{
  "lastRecoveredAt": "2026-05-24T12:30:00+08:00",
  "interruptedTurns": 1,
  "expiredApprovals": 1
}
```

## 3. 数据库变更

新增 migration:

- Create: `backend/src/main/resources/db/migration/V4__recovery_run_records.sql`

Migration 注释要求:

- 本阶段新增或修改的每张表、每个字段都必须在 SQL 中有中文 `--` 注释。
- 新增 `bq_tool_calls` 或扩展 `bq_turns` / `bq_approvals` 字段时，必须同步写入 `bq_schema_comments`。
- `SchemaCommentsCoverageTest` 必须继续通过，确保所有 `bq_*` 表字段都有中文说明。

建议补充:

- `bq_turns.recovery_reason`
- `bq_turns.recovered_at`
- `bq_turns.cancel_reason`
- `bq_turns.failure_reason`
- `bq_approvals.status`
- `bq_approvals.resolved_at`
- `bq_tool_calls`

### `bq_tool_calls`

| 字段 | 说明 |
|---|---|
| `tool_call_id` | 工具调用业务 ID |
| `thread_id` | 所属 thread |
| `turn_id` | 所属 turn |
| `tool_name` | 工具名 |
| `args_json` | 参数 JSON |
| `status` | `running/completed/failed/denied` |
| `started_at` | 开始时间 |
| `completed_at` | 完成时间 |
| `duration_ms` | 耗时 |
| `error_message` | 失败原因 |

如果 P2-1 已把 command/file item 存在 `bq_items`，P2-4 仍建议增加工具调用表，方便 P2-5 聚合统计。

## 4. 文件结构

### 后端生产代码

- Create: `backend/src/main/java/com/wzx/babiq/server/recovery/TurnRecoveryService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryStartupRunner.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryReport.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/observability/RunRecordService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/RunTurnsListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/RunTurnGetHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/RunRecoveryStatusHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ToolCallEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ToolCallMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/ToolCallPersistenceService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationEventRecorder.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/TurnExecutor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnCancelHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnInterruptHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ApprovalRespondHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/TurnStatus.java`

### 桌面端生产代码

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/RunRecordModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`

### 测试

- Create: `backend/src/test/java/com/wzx/babiq/server/recovery/TurnRecoveryServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/recovery/RecoveryStartupRunnerIT.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/observability/RunRecordServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/RunRecordHandlersTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnCancelHandlerTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnInterruptHandlerTest.java`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/RunRecordModelsTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

## 5. TDD 任务

### Task 1: Turn recovery service

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/recovery/TurnRecoveryServiceTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/recovery/TurnRecoveryService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryReport.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/TurnStatus.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- RUNNING -> INTERRUPTED。
- WAITING_APPROVAL -> EXPIRED。
- COMPLETED/FAILED/CANCELED 不变。
- recovery report 统计数量正确。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=TurnRecoveryServiceTest test
```

- [ ] **Step 3: 实现 recovery**

实现要求:

- recovery 必须幂等。
- recovery 必须写 `recovered_at`。
- 中文注释解释为什么 P2 不跨进程 resume ReactAgent。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=TurnRecoveryServiceTest test
```

### Task 2: Startup runner integration

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/recovery/RecoveryStartupRunnerIT.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryStartupRunner.java`

- [ ] **Step 1: 写失败测试**

测试流程:

1. 临时 SQLite 中插入 RUNNING turn 和 WAITING_APPROVAL approval。
2. 启动 Spring context。
3. 断言 turn 状态已收束。
4. 断言 approval pending 已 expired。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=RecoveryStartupRunnerIT verify
```

- [ ] **Step 3: 实现 startup runner**

实现要求:

- 使用 `ApplicationRunner`。
- 只在 migration 完成后运行。
- 结构化日志输出恢复报告。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=RecoveryStartupRunnerIT verify
```

### Task 3: Run record service and handlers

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/observability/RunRecordServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/RunRecordHandlersTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/observability/RunRecordService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/RunTurnsListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/RunTurnGetHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/RunRecoveryStatusHandler.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- `run/turns/list` 按 startedAt 倒序。
- `run/turn/get` 返回 items、summary、approvals、toolCalls。
- 不存在的 turn 返回清晰错误。
- recovery status 返回最近一次报告。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=RunRecordServiceTest,RunRecordHandlersTest test
```

- [ ] **Step 3: 实现查询服务和 handlers**

实现要求:

- handler 不直接访问 mapper。
- 返回 DTO 不暴露数据库内部自增 id。
- 历史 items 原样返回协议 JSON。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=RunRecordServiceTest,RunRecordHandlersTest test
```

### Task 4: Tool call records

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ToolCallEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ToolCallMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/ToolCallPersistenceService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/interceptor/ToolObservationInterceptor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/interceptor/BaBiQSandboxInterceptor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/interceptor/SpotlightingToolInterceptor.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/observability/ToolCallRecordTest.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- 工具开始时记录 running。
- 工具成功时记录 completed 和 duration。
- 工具异常时记录 failed 和 error。
- 被审批拒绝时记录 denied。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ToolCallRecordTest test
```

- [ ] **Step 3: 实现工具调用记录**

实现要求:

- 不把超长 stdout/stderr 全量写入 tool call 表；详细内容仍在 item payload。
- tool call 表为统计服务服务，保留核心字段。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ToolCallRecordTest test
```

### Task 5: 桌面端运行详情历史

**Files:**
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/RunRecordModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/RunRecordModelsTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

- [ ] **Step 1: 写失败测试**

覆盖:

- 打开运行详情时调用 `run/turns/list`。
- 点击历史 turn 时调用 `run/turn/get`。
- 当前 turn 事件和历史 turn 不混淆。
- recovery status 能展示为顶部提示或详情条目。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

- [ ] **Step 3: 实现协议、状态和 UI**

UI 要求:

- 运行详情默认展示当前 turn；无当前 turn 时展示最近历史。
- 历史 turn 列表显示状态、耗时、成本、工具数。
- 失败/中断要有清晰原因。
- 不做复杂图表，图表属于 P2-5。

- [ ] **Step 4: 运行测试通过**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

### Task 6: 全量验证和文档同步

**Files:**
- Modify: `docs/superpowers/plans/p2-4-recovery-records/codex-handoff.md`
- Modify: `docs/superpowers/plans/p2-task-index.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: 后端全量验证**

```powershell
cd backend
.\mvnw.cmd clean verify
```

- [ ] **Step 2: 桌面端全量验证**

```powershell
cd desktop
.\gradlew.bat test
```

- [ ] **Step 3: 手动验收**

1. 启动一个长任务。
2. 强制停止后端。
3. 重启后端。
4. 历史 turn 显示 interrupted，不继续假运行。
5. 触发审批后强制重启，approval 显示 expired。
6. 运行详情能打开历史 turn，看到工具调用、审批、失败原因和 TurnSummary。

- [ ] **Step 4: 更新文档**

- `docs/superpowers/plans/p2-4-recovery-records/codex-handoff.md`
- `docs/superpowers/plans/p2-task-index.md`
- `AGENTS.md`
- `CLAUDE.md`

- [ ] **Step 5: 中文 commit**

```powershell
git add backend desktop docs AGENTS.md CLAUDE.md
git commit -m "feat(p2-4): 补齐恢复语义和运行记录"
```

不要 push。

## 6. 验收标准

- 启动时自动收束遗留 running/waiting turn。
- 不出现后端重启后仍显示 running 的假状态。
- 历史运行详情可查询 items、summary、approvals、tool calls。
- 失败、取消、中断原因可追溯。
- `cd backend; .\mvnw.cmd clean verify` 通过。
- `cd desktop; .\gradlew.bat test` 通过。

## 7. 非目标

- 不实现跨进程 ReactAgent checkpoint resume。
- 不做分布式任务恢复。
- 不做 Langfuse/OTel UI。
- 不做复杂统计图表。

## 8. 实施记录

- 后端恢复语义已落地：`TurnRecoveryService`、`RecoveryStartupRunner`、`RecoveryReport`。
- 后端运行记录查询已落地：`RunRecordService`、`RunTurnsListHandler`、`RunTurnGetHandler`、`RunRecoveryStatusHandler`。
- 工具调用持久化已落地：`ToolCallEntity`、`ToolCallMapper`、`ToolCallPersistenceService`、`V4__recovery_run_records.sql`。
- 桌面端运行详情历史已落地：`RunRecordModels.kt`、`AgentClient` 运行记录方法、`RunRecordState`、`RuntimeDetailsPanel`。
- 已通过目标测试：`TurnRecoveryServiceTest`、`RunRecordServiceTest`、`ToolCallRecordTest`、`AgentClientTest`、`ChatControllerTest`、`RunRecordModelsTest`。
- 已通过全量验证：`backend` 的 `clean verify` 和 `desktop` 的 `test`。
