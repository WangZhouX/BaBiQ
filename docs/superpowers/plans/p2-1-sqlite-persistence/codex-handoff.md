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

P2-1 不替换现有内存态 `ConversationService`，不改变桌面端 UI 行为。多会话历史和运行恢复留到 P2-2 / P2-4。

## 版本核对

2026-05-24 已核对 Maven Central:

- `com.baomidou:mybatis-plus-spring-boot3-starter`: `3.5.16`
- `com.baomidou:mybatis-plus-jsqlparser`: `3.5.16`
- `org.xerial:sqlite-jdbc`: `3.53.1.0`
- `org.flywaydb:flyway-core`: `12.6.2`

## 下一步

用户确认 `plan.md` 后，使用 `superpowers:executing-plans` 或 `superpowers:subagent-driven-development` 逐任务实现。
