# 工具调用按 Turn 唯一修复设计

## 现场证据

真实运行中，新 turn `turn_0d47df089203` 再次产生 `application_action_0/1/2`。SQLite 中这三个 `tool_call_id` 已被旧 turn 占用，后端先记录 `tool call immutable metadata conflict`，随后 `bq_application_actions` 的全局唯一索引拒绝新动作登记，最终被统一映射成 `local_persistence_failed`。

数据库 `quick_check` 正常、Flyway V23 成功且没有 `SQLITE_BUSY`，因此问题不是数据库损坏、文件权限、代理或模型网络，而是持久化身份约束与实际工具调用语义不一致。

## 方案比较

1. 清空历史记录或每次重启前删除数据库：只能暂时绕过冲突，会丢失审计数据，拒绝采用。
2. 在协议层把 `tool_call_id` 改写成全局命名空间 ID：迁移较小，但会把内部持久化细节暴露给桌面协议和运行记录，拒绝采用。
3. 保留模型提供的原始 `tool_call_id`，把持久化唯一身份改为 `(turn_id, tool_call_id)`：符合工具调用只在所属 turn 内相关的事实，保留历史和协议兼容性，采用该方案。

## 数据库设计

- 新增 V24 migration。
- 重建 `bq_tool_calls`，移除 `tool_call_id` 单列唯一约束，增加 `UNIQUE(turn_id, tool_call_id)`。
- 重建 `bq_application_actions`，把动作关联唯一约束和外键都改为 `(turn_id, tool_call_id)`。
- 同步重建 `bq_application_action_events`，保留 append-only 触发器、既有数据、索引和中文字段说明。
- 同一 turn 内重复登记仍然失败，不同 turn 可以复用相同的模型工具调用 ID。

## 服务设计

- `ToolCallPersistenceService` 的开始、结束和动作执行 ID 绑定统一按 `turnId + toolCallId` 查询。
- `ToolObservationInterceptor` 在完成或失败持久化时传入当前 `TurnObservationContext.turnId`。
- `ApplicationActionTool` 绑定 execution 时同时传入 invocation 的 turnId。
- 运行详情和桌面协议继续展示原始 `tool_call_id`，不改变 JSON-RPC 字段。

## 业务桌面本地审计库补充

真实故障同时发生在业务桌面自己的
`~/.huitai-agent-desktop/desktop/data/business-desktop.db`。该库的 V1 migration
仍通过 `bd_action_executions_tool_call_id_unique` 把 `tool_call_id` 当作全库唯一值，
因此即使后端已经按 turn 定位，新 turn 再次产生 `application_action_0/1/2`
时，桌面动作仍会在 `compareAndCreate` 阶段抛出 `SQLITE_CONSTRAINT_UNIQUE`。

- 新增业务桌面 V2 migration，不改写已经发布的 V1。
- 删除 `tool_call_id` 单列唯一索引，新增
  `UNIQUE(turn_id, tool_call_id) WHERE turn_id IS NOT NULL AND tool_call_id IS NOT NULL`。
- 保留原始 `tool_call_id`、所有历史执行、审批和 append-only 事件；迁移不删除历史数据。
- `SQLiteActionExecutionStore.compareAndCreate` 在同一 turn 内遇到另一个 execution
  占用相同工具调用 ID 时返回受控的 `EXECUTION_CONFLICT`，不得让 SQLite 异常逃逸并中断
  `DefaultDispatcher` 协程。
- 不同 turn 使用相同短 ID 时必须各自创建、更新和终止自己的 execution；任何一方的状态变化
  都不得修改另一方。

## 会话与恢复边界

- 模型上下文仍只从当前 `threadId` 读取；本修复不把历史运行记录加入模型消息。
- 启动恢复只把旧 `RUNNING/SENDING` 收口为 `INTERRUPTED`、待审批收口为 `EXPIRED`，
  不重新执行工具或业务动作。
- 历史上因旧版全局 ID 查询造成的错误终态只作为既有审计数据保留；在无法证明真实业务结果时，
  migration 不猜测、不改写其终态。

## 错误处理与验证

- 保留工具观测持久化失败不影响真实工具执行的降级语义。
- 为迁移增加真实 SQLite 约束测试：跨 turn 相同 ID 成功、同 turn 重复失败、动作外键准确关联所属 turn。
- 为服务增加跨 turn 相同 ID 的开始/完成/动作绑定回归测试。
- 运行定向测试、`SchemaCommentsCoverageTest` 和后端 `clean verify`。
- 业务桌面增加真实 SQLite migration/store 回归：跨 turn 相同 ID 成功、同 turn 重复受控冲突、
  既有 V1 数据升级后全部保留。
- 在真实用户数据库副本上执行 Flyway 升级与 `PRAGMA quick_check`；不得直接用测试修改用户原库。
