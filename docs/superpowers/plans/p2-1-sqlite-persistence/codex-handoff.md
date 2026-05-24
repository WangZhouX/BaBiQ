# P2-1 SQLite + MyBatis-Plus 持久化底座交接

## 当前状态

- **状态:** 计划已编写，等待用户确认后实现。
- **计划入口:** `docs/superpowers/plans/p2-1-sqlite-persistence/plan.md`
- **前置条件:** P1 总体验收已由用户确认通过。

## 实现边界

P2-1 只负责建立持久化底座:

- SQLite DataSource。
- Flyway migration。
- MyBatis-Plus entity / mapper。
- persistence service。
- repository adapter。
- SecretStore 接口边界。
- `bq_schema_comments` 元数据表。
- 每张数据库业务表、每个字段的中文注释覆盖测试。

P2-1 不替换现有内存态 `ConversationService`，不改变桌面端 UI 行为。多会话历史和运行恢复留到 P2-2 / P2-4。

## 数据库注释要求

- SQLite 不支持原生字段 COMMENT，因此 migration 必须同时提供 SQL `--` 中文注释和 `bq_schema_comments` 元数据。
- 每个 `CREATE TABLE` 和每个字段定义前必须有中文 `--` 注释。
- `bq_schema_comments` 中必须记录每张表和每个字段的中文说明。
- `SchemaCommentsCoverageTest` 必须通过，确保后续新增表/字段不会漏注释。

## 版本核对

2026-05-24 已核对 Maven Central:

- `com.baomidou:mybatis-plus-spring-boot3-starter`: `3.5.16`
- `com.baomidou:mybatis-plus-jsqlparser`: `3.5.16`
- `org.xerial:sqlite-jdbc`: `3.53.1.0`
- `org.flywaydb:flyway-core`: `12.6.2`

## 下一步

用户确认 `plan.md` 后，使用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 逐任务实现。
