# P2-1 SQLite + MyBatis-Plus 持久化底座交接

## 当前状态

- **状态：** 已实现并通过 targeted 验证。
- **计划入口：** `docs/superpowers/plans/p2-1-sqlite-persistence/plan.md`
- **前置条件：** P1 总体验收已由用户确认通过。

## 已完成内容

- 后端新增 SQLite + Flyway + MyBatis-Plus 持久化底座。
- 新增 `babiq.persistence.*` 配置：
  - 默认数据库路径：`${user.home}/.babiq/babiq.db`
  - 默认 `busy_timeout=5000`
  - 默认启用 WAL
  - 默认启用 SQLite 外键
- 新增 `SQLitePragmaDataSource`，确保每次获取连接后都应用 SQLite 连接级 PRAGMA。
- 新增 Flyway migration：`backend/src/main/resources/db/migration/V2__create_p2_persistence_tables.sql`
- 新增 P2 初始表：
  - `bq_threads`
  - `bq_turns`
  - `bq_items`
  - `bq_turn_summaries`
  - `bq_approvals`
  - `bq_provider_configs`
  - `bq_app_settings`
  - `bq_schema_comments`
- 新增 MyBatis-Plus entity / mapper / persistence service。
- 新增领域仓库接口 `ConversationRepository` 和 SQLite adapter。
- 新增 `SecretStore` 抽象和开发期 `NoopSecretStore`。

## 数据库注释要求

SQLite 不支持原生字段 `COMMENT`，因此本阶段采用双层说明：

- migration 中每个 `CREATE TABLE` 和每个字段定义前都有中文 `--` 注释。
- `bq_schema_comments` 记录所有 `bq_*` 表和字段的中文说明。
- `SchemaCommentsCoverageTest` 会扫描所有 `bq_*` 表和字段，确保每个字段都有非空中文说明。
- 后续新增表或字段时，必须同步更新 SQL 注释和 `bq_schema_comments`，否则测试会失败。

## 版本核对

2026-05-24 已核对 Maven Central / 官方资料：

- `com.baomidou:mybatis-plus-spring-boot3-starter`: `3.5.16`
- `com.baomidou:mybatis-plus-jsqlparser`: `3.5.16`
- `org.xerial:sqlite-jdbc`: `3.53.1.0`
- `org.flywaydb:flyway-core`: `12.6.2`
- `org.flywaydb:flyway-database-nc-sqlite`: `12.6.2`

## 验证记录

已运行：

```powershell
cd backend
.\mvnw.cmd "-Dtest=MyBatisPlusConfigTest,SQLiteMigrationIT,SchemaCommentsCoverageTest,RepositoryAdapterIT,SecretStoreTest" test
```

结果：

- `Tests run: 12`
- `Failures: 0`
- `Errors: 0`
- `Skipped: 0`
- `BUILD SUCCESS`

## 边界说明

P2-1 只建立持久化底座，不替换 P1 内存态 `ConversationService` 的运行链路，不改变桌面端 UI 行为。

后续接入点：

- P2-2：把多会话历史、最近对话和 item 恢复接入 `ConversationRepository`。
- P2-3：把设置页、Provider 编辑和审批策略接入 `bq_app_settings` / `bq_provider_configs` / `SecretStore`。
- P2-4：把未完成 turn、恢复语义和运行记录接入 `bq_turns` / `bq_approvals`。
