# P2-2 多会话历史和桌面端最近对话 Handoff

## 状态

- 当前状态: 已实现并通过自动化验收，等待后续阶段继续推进。
- 计划入口: `docs/superpowers/plans/p2-2-thread-history/plan.md`
- 依赖: P2-1 SQLite + MyBatis-Plus 持久化底座已完成。

## 目标

把 P1/P2-1 的持久化基础接入真实业务路径，让会话历史能在后端重启后恢复，并让桌面端 Sidebar 的最近对话来自后端 `thread/list`。

## 关键边界

- P2-2 只做会话历史和最近对话。
- 不做 Provider 编辑、API Key 存储、运行详情历史查询、MCP 或搜索。
- JSON-RPC handler 不允许直接访问 MyBatis Mapper。
- 桌面端不直接访问数据库，只通过 JSON-RPC 读取历史。

## 已完成实现

- 后端新增 `thread/list`、`thread/load`、`thread/archive` JSON-RPC 方法。
- 新增 `ConversationApplicationService`，让 handler 只负责协议解析，业务查询统一走应用服务。
- 新增 `ConversationEventRecorder`，在 `ItemEmitter` 发出 WebSocket 事件前先把 ThreadItem、TurnSummary 和 Turn 终态写入 SQLite。
- `ConversationService` 已能把 thread/turn 创建过程同步到 repository，并在重启后从 SQLite 恢复 thread 元数据。
- `SQLiteConversationRepository` 已支持按工作目录列出会话、按 item 顺序加载历史、统计消息数量和读取最新 turn 状态。
- 桌面端新增历史协议模型、`AgentClient` 历史方法、`ThreadHistoryState`，Sidebar 的最近对话改为真实后端数据。
- 桌面端支持新建当前对话、打开历史会话、归档历史会话、切换工作目录后重新加载该目录最近对话。

## 验收命令

```powershell
cd backend
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

已执行并通过：

- `cd backend; .\mvnw.cmd "-Dtest=ThreadListHandlerTest,ThreadLoadHandlerTest,ThreadArchiveHandlerTest,ConversationEventRecorderTest" test`
- `cd backend; .\mvnw.cmd "-Dtest=ConversationHistoryIT" test`
- `cd backend; .\mvnw.cmd "-Dtest=ThreadCreateHandlerTest,TurnStartHandlerTest,ApprovalRespondHandlerTest,ThreadListHandlerTest,ThreadLoadHandlerTest,ThreadArchiveHandlerTest,ConversationEventRecorderTest,ConversationHistoryIT" test`
- `cd desktop; .\gradlew.bat test --tests "*AgentClientTest" --tests "*ThreadHistoryModelsTest"`
- `cd desktop; .\gradlew.bat test --tests "*ChatControllerTest"`
- `cd desktop; .\gradlew.bat test`
- `cd backend; .\mvnw.cmd clean verify`

## 手动验收

1. 创建会话并发送一轮消息。
2. 关闭后端。
3. 重启后端和桌面端。
4. 左侧最近对话仍能看到刚才的会话。
5. 点击历史会话能恢复消息和 TurnSummary。
6. 归档后默认最近列表隐藏该会话。

## 下一步

- 进入 `P2-3 Provider / API Key / 沙箱 / 审批设置系统`。
- P2-3 需要把 P2-1 的 `bq_provider_configs`、`bq_app_settings`、SecretStore 和当前 P1 的 Provider/沙箱/审批协议接起来。
