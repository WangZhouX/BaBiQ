# Turn-Scoped Tool Call Identity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复不同 turn 复用相同 `tool_call_id` 时触发 `local_persistence_failed` 的问题，同时保留历史运行记录、业务动作审计和原始协议 ID。

**Architecture:** SQLite 中工具调用和业务动作改用 `(turn_id, tool_call_id)` 组合身份；服务层所有单条更新都携带 turnId。V24 通过表重建迁移既有数据，并恢复原索引、外键与 append-only 触发器。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、Flyway、SQLite、JUnit 5、AssertJ

---

### Task 1: 锁定跨 Turn ID 复用缺陷

**Files:**
- Modify: `backend/src/test/java/com/wzx/babiq/server/persistence/BusinessIdentityScopeMigrationTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/tool/ApplicationActionToolPersistenceIT.java`

- [x] 添加两个 turn 使用相同 `tool_call_id` 的迁移约束测试。
- [x] 添加第二个 turn 能登记并绑定 application action 的集成回归测试。
- [x] 运行测试并确认当前实现因全局唯一约束或旧记录选择失败。

### Task 2: 实现 V24 组合身份迁移

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__turn_scoped_tool_call_identity.sql`
- Modify: `backend/src/test/java/com/wzx/babiq/server/persistence/BusinessIdentityScopeMigrationTest.java`

- [x] 重建三张相关表并复制全部既有数据。
- [x] 将工具调用、动作唯一约束和动作外键改为 `(turn_id, tool_call_id)`。
- [x] 恢复工具调用索引、动作索引和事件 append-only 触发器。
- [x] 更新 `bq_schema_comments` 中的身份语义。
- [x] 运行迁移测试并确认约束符合设计。

### Task 3: 服务层按 Turn 精确定位

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/ToolCallPersistenceService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ToolCallMapper.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/interceptor/ToolObservationInterceptor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationActionTool.java`
- Modify: related backend tests

- [x] `recordStarted` 查询组合身份。
- [x] `recordFinished` 增加 turnId 并只更新所属 turn 的记录。
- [x] `bindExecutionId` 增加 turnId，mapper 更新条件包含 turnId。
- [x] 更新拦截器、业务动作工具和测试调用点。
- [x] 运行定向测试确认 RED 转 GREEN。

### Task 4: 全量验证

**Files:**
- Verify: `backend/`

- [x] 运行 `BusinessIdentityScopeMigrationTest`、工具持久化和业务动作定向测试。
- [x] 运行 `SchemaCommentsCoverageTest`。
- [x] 运行 `backend/.mvnw.cmd clean verify`。
- [x] 检查 diff，确认未覆盖现有未提交修改且没有迁移数据丢失。

### Task 5: 补齐业务桌面本地审计库的 Turn 作用域身份

**Files:**
- Modify: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/database/BusinessDesktopMigrationTest.kt`
- Modify: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/execution/SQLiteActionExecutionStoreTest.kt`
- Create: `business-desktop/security-audit-core/src/main/resources/db/migration/V2__turn_scoped_tool_call_identity.sql`
- Modify: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/execution/SQLiteActionExecutionStore.kt`

- [x] 先写 migration 测试：不同 turn 可复用同一 `tool_call_id`，同一 turn 重复仍失败。
- [x] 运行 `:security-audit-core:test`，确认测试因 V1 全局唯一索引而 RED。
- [x] 新增 V2：删除单列唯一索引，创建 `(turn_id, tool_call_id)` 条件唯一索引并更新中文字段说明。
- [x] 先写 store 测试：跨 turn 两条 execution 均创建；同 turn 第二条返回 `EXECUTION_CONFLICT`，
  且第一条状态不变。
- [x] 运行 store 测试，确认当前实现因 SQLite 唯一约束异常而 RED。
- [x] 在 `compareAndCreate` 的同一 `BEGIN IMMEDIATE` 事务中检查 turn/tool 身份占用，
  将同 turn 冲突转换为 `ExecutionCreateResult.Conflict`。
- [x] 运行 `:security-audit-core:test`，确认 RED 转 GREEN。

### Task 6: 真实旧库副本与前后端闭环验证

**Files:**
- Verify: `backend/`
- Verify: `business-desktop/`
- Verify copy of: `%USERPROFILE%/.huitai-agent-desktop/desktop/data/business-desktop.db`

- [x] 复制真实桌面数据库到仓库忽略的临时目录，对副本执行 Flyway V2。
- [x] 对副本运行 `PRAGMA quick_check`、核对 Flyway version=2、历史 execution/event/approval 数量不变。
- [x] 在副本中插入不同 turn 的同名工具调用，确认成功；同 turn 重复确认被唯一约束拒绝。
- [x] 运行后端 turn-scoped 定向测试和 `clean verify`。
- [x] 运行业务桌面 `:security-audit-core:test` 与全量 `test --rerun-tasks`。
- [x] 检查当前上下文与启动恢复测试，确认旧 thread 不进入新 thread、恢复流程不重放工具。

## 验证记录

- 真实业务桌面库副本：V1 → V2，`quick_check=ok`，13 条 execution、47 条 event、
  0 条 approval 全部保留；跨 turn 复用成功，同 turn 重复被组合唯一键拒绝。
- 后端：`.\mvnw.cmd clean verify`，993 tests，0 failures，0 errors。
- 业务桌面：`.\gradlew.bat test --rerun-tasks --no-daemon --max-workers=1 --no-parallel`，
  741 tests，0 failures，0 errors；额外覆盖内存适配器身份一致性，以及双 SQLite
  适配器并发提交同一 `(turnId, toolCallId)` 时一个成功、一个受控冲突。
- 启动恢复：`TurnRecoveryServiceTest` 通过；旧 RUNNING/SENDING 只收口为 INTERRUPTED，
  WAITING_APPROVAL 只收口为 EXPIRED，不调用工具执行链。
