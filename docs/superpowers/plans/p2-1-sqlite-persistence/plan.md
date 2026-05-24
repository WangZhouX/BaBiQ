# P2-1 SQLite + MyBatis-Plus Persistence Foundation Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, otherwise use `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 BaBiQ 后端建立 SQLite + MyBatis-Plus + Flyway 的本地持久化底座，让 Thread、Turn、Item、审批、Provider 配置和 App 设置具备可测试的数据结构。

**Architecture:** P2-1 只搭建数据库、migration、entity、mapper、persistence service 和 repository adapter，不把它接入现有 `ConversationService` 的运行链路。Agent 领域层只面向 repository 接口，MyBatis-Plus 细节留在 `persistence/` 包内，为 P2-2 多会话历史接入做准备。

**Tech Stack:** Java 21, Spring Boot 3.5.14, MyBatis-Plus 3.5.16, SQLite JDBC 3.53.1.0, Flyway 12.6.2, JUnit 5, AssertJ, Spring Boot Test, JSON-RPC 2.0.

---

## 0. 当前上下文

P1 总体验收已由用户确认通过。当前后端仍是 P1 内存态:

- `ConversationService` 使用 `ConcurrentHashMap` 保存 `Thread` 和 `Turn`。
- `api/method/*Handler` 通过 JSON-RPC 处理桌面端请求。
- P2-1 不替换现有运行路径，只新增持久化基础设施和测试。

P2-1 完成后，后端启动时应能自动创建 SQLite 数据库和表结构，测试能通过 repository adapter 对临时 SQLite 文件完成插入、查询、分页和约束验证。

## 1. 官方能力与版本核对

已在 2026-05-24 核对:

- Spring Boot 当前仓库锁定 `3.5.14`，Spring 官方发布说明显示 3.5.14 已发布并进入 Maven Central。
- MyBatis-Plus 官方安装文档说明 Spring Boot 3 使用 `mybatis-plus-spring-boot3-starter`，且 `3.5.9+` 后分页插件需要单独引入 `mybatis-plus-jsqlparser`。
- Maven Central 元数据:
  - `com.baomidou:mybatis-plus-spring-boot3-starter` latest/release: `3.5.16`
  - `com.baomidou:mybatis-plus-jsqlparser` latest/release: `3.5.16`
  - `org.xerial:sqlite-jdbc` latest/release: `3.53.1.0`
  - `org.flywaydb:flyway-core` latest/release: `12.6.2`
- MyBatis-Plus 分页插件文档列出 SQLite 支持，并建议单数据库显式指定 `DbType`。
- xerial sqlite-jdbc 官方 README 说明 `sqlite-jdbc` 可通过 JDBC 访问和创建 SQLite database file，并给出 `jdbc:sqlite:sample.db` 用法。
- Flyway 官方支持数据库页面列出 SQLite 在 supported database 列表中。

P2-1 实现时继续使用这些稳定版本，不使用 snapshot、RC、Beta、EAP。

## 2. 文件结构

### 后端生产代码

- Modify: `backend/pom.xml`
  - 增加 MyBatis-Plus、sqlite-jdbc、Flyway 依赖和版本属性。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/config/BaBiQPersistenceProperties.java`
  - 读取 `babiq.persistence.*` 配置，集中管理数据库路径、busy timeout、WAL、foreign_keys。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/config/SQLiteDataSourceConfig.java`
  - 创建 SQLite `DataSource`，确保目录存在，设置 JDBC URL。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/config/SQLiteConnectionInitializer.java`
  - 在连接创建后执行 `PRAGMA foreign_keys=ON`、`PRAGMA journal_mode=WAL`、`PRAGMA busy_timeout=...`。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/config/MyBatisPlusConfig.java`
  - `@MapperScan`，注册 `MybatisPlusInterceptor` 和 `PaginationInnerInterceptor(DbType.SQLITE)`。
- Create: `backend/src/main/resources/db/migration/V2__create_p2_persistence_tables.sql`
  - 创建 P2 初始表、唯一索引、外键、查询索引和 `bq_schema_comments` 字段中文说明元数据表。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/*Entity.java`
  - 数据库实体，不作为协议 DTO 返回。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/*Mapper.java`
  - MyBatis-Plus `BaseMapper`。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/*PersistenceService.java`
  - 单表或少量表操作封装，隐藏 mapper 细节。
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/adapter/*Repository.java`
  - SQLite adapter，把 entity 转为领域 repository 模型。
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/repository/*.java`
  - 领域 repository 接口，供 P2-2 接入。
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/SecretStore.java`
  - P2-1 只定义接口，不实现真实 KeyStore。
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/NoopSecretStore.java`
  - 测试和开发用实现，只保存 `secretRef` 语义，不落明文 key。

### 后端测试

- Create: `backend/src/test/java/com/wzx/babiq/server/persistence/SQLiteMigrationIT.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/persistence/MyBatisPlusConfigTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/persistence/RepositoryAdapterIT.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/settings/SecretStoreTest.java`

### 文档

- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/plans/p2-master.md`
- Create/Modify: `docs/superpowers/plans/p2-1-sqlite-persistence/codex-handoff.md`

## 3. 数据表设计

Migration 必须创建以下表:

| 表 | 作用 |
|---|---|
| `bq_threads` | 会话线程快照，保存协议 thread id、标题、cwd、provider/model/sandbox/approval 快照、归档状态 |
| `bq_turns` | 单轮执行记录，保存 turn id、状态、输入摘要、上下文快照、开始/结束时间、失败原因 |
| `bq_items` | 聊天和工具 item 流，保存原始 item JSON、顺序号和状态 |
| `bq_turn_summaries` | TurnSummary 历史，保存 tokens、成本、耗时、工具数量 |
| `bq_approvals` | 审批请求和结果，保存 tool、args、edited args、decision、scope、状态 |
| `bq_provider_configs` | Provider 配置，保存 display name、type、base url、model、secret ref、启用状态 |
| `bq_app_settings` | 普通 app 设置，保存 key/value/type |
| `bq_schema_comments` | SQLite 字段中文说明元数据表，保存每张业务表和每个业务字段的中文注释 |

约束要求:

- 所有协议业务 ID 使用 `TEXT` 并建立唯一索引，例如 `thread_id`、`turn_id`、`item_id`。
- 数据库内部主键使用 `INTEGER PRIMARY KEY AUTOINCREMENT`。
- `bq_turns.thread_id`、`bq_items.thread_id`、`bq_items.turn_id`、`bq_turn_summaries.turn_id`、`bq_approvals.turn_id` 必须有外键。
- SQLite 需要启用 `PRAGMA foreign_keys=ON`，否则外键不会生效。
- `bq_items(thread_id, turn_id, sequence_no)` 建索引，支持 P2-2 加载历史 item 流。
- `bq_threads(updated_at)` 建索引，支持最近会话列表。
- 每张表和每个字段必须有中文注释；SQLite 不保存原生列注释，所以必须同时写 SQL `--` 注释和 `bq_schema_comments` 元数据。

### 3.1 字段中文注释硬规则

P2-1 migration 必须满足以下规则:

- 每个 `CREATE TABLE` 前写中文 `--` 表注释。
- 每个字段定义前写中文 `--` 字段注释，说明字段含义、写入来源、读取方和空值语义。
- `bq_schema_comments` 使用 `table_name + column_name` 唯一约束；表级说明使用 `column_name='__table__'`。
- 每创建一张表或新增一个字段，都必须 `INSERT OR REPLACE INTO bq_schema_comments(...)` 写入中文说明。
- `SchemaCommentsCoverageTest` 必须用 `PRAGMA table_info(<table>)` 扫描所有 `bq_*` 表，校验每个字段都能在 `bq_schema_comments` 中查到非空中文说明。
- Entity 字段必须有中文字段级注释，和 `bq_schema_comments.comment` 语义保持一致。

## 4. 任务分解

### Task 1: 依赖、配置属性和 DataSource

**Files:**

- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/config/BaBiQPersistenceProperties.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/config/SQLiteDataSourceConfig.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/config/SQLiteConnectionInitializer.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/MyBatisPlusConfigTest.java`

- [ ] **Step 1: 写失败测试，验证配置属性默认值**

创建 `MyBatisPlusConfigTest`，使用 `ApplicationContextRunner` 或 `@SpringBootTest` 验证:

```java
assertThat(properties.databasePath()).endsWith(".babiq/babiq.db");
assertThat(properties.busyTimeoutMillis()).isEqualTo(5000);
assertThat(properties.enableWal()).isTrue();
assertThat(properties.enableForeignKeys()).isTrue();
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=MyBatisPlusConfigTest test
```

Expected: 编译失败或找不到 `BaBiQPersistenceProperties`。

- [ ] **Step 3: 添加依赖和配置类**

在 `pom.xml` 增加:

```xml
<mybatis-plus.version>3.5.16</mybatis-plus.version>
<sqlite-jdbc.version>3.53.1.0</sqlite-jdbc.version>
<flyway.version>12.6.2</flyway.version>
```

新增依赖:

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>${mybatis-plus.version}</version>
</dependency>
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-jsqlparser</artifactId>
    <version>${mybatis-plus.version}</version>
</dependency>
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>${sqlite-jdbc.version}</version>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>${flyway.version}</version>
</dependency>
```

实现 `BaBiQPersistenceProperties`，必须有中文类型级和字段级注释。

- [ ] **Step 4: 实现 SQLite DataSource**

`SQLiteDataSourceConfig` 负责:

- 默认数据库路径为 `${user.home}/.babiq/babiq.db`。
- 父目录不存在时自动创建。
- JDBC URL 为 `jdbc:sqlite:<absolute-path>`。
- 测试可通过 `babiq.persistence.database-path` 覆盖到临时目录。

- [ ] **Step 5: 运行配置测试**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=MyBatisPlusConfigTest test
```

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add backend/pom.xml backend/src/main/java/com/wzx/babiq/server/persistence/config backend/src/test/java/com/wzx/babiq/server/persistence/MyBatisPlusConfigTest.java
git commit -m "feat(p2-1): 添加 SQLite 持久化依赖和配置"
```

### Task 2: Flyway migration 和 SQLite PRAGMA 验证

**Files:**

- Create: `backend/src/main/resources/db/migration/V2__create_p2_persistence_tables.sql`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/config/SQLiteConnectionInitializer.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/SQLiteMigrationIT.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`

- [ ] **Step 1: 写失败测试，验证 migration 自动建表**

测试使用临时 SQLite 文件:

```java
@TempDir
Path tempDir;
```

启动 Spring 上下文时覆盖:

```java
@DynamicPropertySource
static void persistenceProperties(DynamicPropertyRegistry registry) {
    registry.add("babiq.persistence.database-path", () -> tempDbPath.toString());
}
```

断言:

- `bq_threads` 等 7 张业务表存在。
- `bq_schema_comments` 存在。
- `flyway_schema_history` 存在。
- `PRAGMA foreign_keys` 返回 `1`。
- `PRAGMA journal_mode` 返回 `wal` 或在当前环境允许时等于 `wal`。
- 所有 `bq_*` 表字段都有中文注释元数据。

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=SQLiteMigrationIT test
```

Expected: 找不到 migration 或表不存在。

- [ ] **Step 3: 编写 migration**

`V2__create_p2_persistence_tables.sql` 必须包含:

```sql
CREATE TABLE IF NOT EXISTS bq_threads (...);
CREATE UNIQUE INDEX IF NOT EXISTS ux_bq_threads_thread_id ON bq_threads(thread_id);
CREATE INDEX IF NOT EXISTS ix_bq_threads_updated_at ON bq_threads(updated_at);
```

每张表字段按 §3 表设计实现。时间字段统一使用 `TEXT NOT NULL` 保存 ISO-8601 字符串，避免 SQLite 时区和 Java `Instant` 映射复杂度污染 P2-1。

每个表和字段都必须写中文注释，例如:

```sql
-- 会话线程表：保存桌面端每个对话的业务标识、工作目录和归档状态。
CREATE TABLE IF NOT EXISTS bq_threads (
    -- 数据库内部主键，只供 SQLite 关联和排序使用，不暴露给 JSON-RPC 协议。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层 thread id，由 ConversationService 创建，桌面端通过它加载历史会话。
    thread_id TEXT NOT NULL
);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment, created_at, updated_at)
VALUES
    ('bq_threads', '__table__', '会话线程表：保存桌面端每个对话的业务标识、工作目录和归档状态。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('bq_threads', 'id', '数据库内部主键，只供 SQLite 关联和排序使用，不暴露给 JSON-RPC 协议。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('bq_threads', 'thread_id', '协议层 thread id，由 ConversationService 创建，桌面端通过它加载历史会话。', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
```

- [ ] **Step 4: 实现连接初始化**

`SQLiteConnectionInitializer` 使用 `DataSource` 获取连接并执行:

```sql
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA busy_timeout = 5000;
```

中文注释必须解释: SQLite 外键默认不启用，WAL 是桌面本地读写体验选择，busy timeout 是避免短暂锁竞争直接失败。数据库字段注释必须解释字段业务含义，不允许写“字段 id”这类空注释。

- [ ] **Step 5: 运行 migration 测试**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=SQLiteMigrationIT test
.\mvnw.cmd -Dtest=SchemaCommentsCoverageTest test
```

Expected: PASS。

- [ ] **Step 6: 提交**

```powershell
git add backend/src/main/resources/db/migration backend/src/main/java/com/wzx/babiq/server/persistence/config/SQLiteConnectionInitializer.java backend/src/test/java/com/wzx/babiq/server/persistence/SQLiteMigrationIT.java backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java
git commit -m "feat(p2-1): 创建 P2 SQLite migration"
```

### Task 3: Entity、Mapper 和 MyBatis-Plus 分页配置

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ThreadEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/TurnEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ItemEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/TurnSummaryEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ApprovalEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ProviderConfigEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/AppSettingEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/*Mapper.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/config/MyBatisPlusConfig.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/RepositoryAdapterIT.java`

- [ ] **Step 1: 写失败测试，验证 Mapper 能插入和分页查询**

测试先只覆盖 `ThreadMapper`:

```java
ThreadEntity entity = ThreadEntity.newActive("thr_test", "E:\\BaBiQ", now);
threadMapper.insert(entity);
Page<ThreadEntity> page = threadMapper.selectPage(Page.of(1, 20), new LambdaQueryWrapper<ThreadEntity>().orderByDesc(ThreadEntity::getUpdatedAt));
assertThat(page.getRecords()).hasSize(1);
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=RepositoryAdapterIT test
```

Expected: 编译失败或 mapper 未注册。

- [ ] **Step 3: 实现 Entity**

Entity 要求:

- 使用 MyBatis-Plus 注解 `@TableName`、`@TableId`、`@TableField`。
- 字段级中文注释说明数据库字段、业务来源、空值语义。
- 不直接复用 `conversation.Thread`、`Turn` 或 `ThreadItem`。
- `payloadJson`、`argsJson`、`editedArgsJson` 保持字符串，不在 entity 层绑定具体 JSON 类型。

- [ ] **Step 4: 实现 Mapper**

每个 mapper 只继承 `BaseMapper<Entity>`。暂不写 XML，除非测试证明 MyBatis-Plus 基础 CRUD 无法表达。

- [ ] **Step 5: 实现 MyBatisPlusConfig**

配置:

```java
@MapperScan("com.wzx.babiq.server.persistence.mapper")
@Bean
MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.SQLITE));
    return interceptor;
}
```

注释解释为什么显式 `DbType.SQLITE`。

- [ ] **Step 6: 扩展测试到 7 张表**

覆盖:

- Thread 插入、唯一约束。
- Turn 外键关联 Thread。
- Item 顺序号查询。
- TurnSummary 一对一关联 Turn。
- Approval pending/resolved 字段。
- ProviderConfig 不保存明文 key，只保存 `secretRef`。
- AppSetting key/value/type。

- [ ] **Step 7: 运行测试**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=RepositoryAdapterIT test
```

Expected: PASS。

- [ ] **Step 8: 提交**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/persistence/entity backend/src/main/java/com/wzx/babiq/server/persistence/mapper backend/src/main/java/com/wzx/babiq/server/persistence/config/MyBatisPlusConfig.java backend/src/test/java/com/wzx/babiq/server/persistence/RepositoryAdapterIT.java
git commit -m "feat(p2-1): 添加持久化实体和 mapper"
```

### Task 4: Persistence Service 和 Repository Adapter

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/repository/ConversationRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/repository/TurnRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/repository/ItemRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/repository/ApprovalRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/service/*PersistenceService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/adapter/*SQLite*Repository.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/RepositoryAdapterIT.java`

- [ ] **Step 1: 写失败测试，面向 repository 接口**

测试不要直接调用 mapper:

```java
Thread saved = conversationRepository.createThread("thr_repo", "E:\\BaBiQ", snapshot);
Optional<Thread> loaded = conversationRepository.findByThreadId("thr_repo");
assertThat(loaded).isPresent();
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=RepositoryAdapterIT test
```

Expected: repository 接口或 bean 不存在。

- [ ] **Step 3: 定义领域 repository 接口**

接口只表达 P2-2 需要的领域动作:

- `createThread`
- `findThread`
- `listRecentThreads`
- `archiveThread`
- `saveTurn`
- `saveItem`
- `saveTurnSummary`
- `saveApproval`
- `resolveApproval`

接口方法参数优先使用领域对象或小型 command record，不暴露 entity。

- [ ] **Step 4: 实现 Persistence Service**

Service 只封装 mapper:

- `ThreadPersistenceService`
- `TurnPersistenceService`
- `ItemPersistenceService`
- `TurnSummaryPersistenceService`
- `ApprovalPersistenceService`
- `ProviderPersistenceService`
- `AppSettingPersistenceService`

每个 service 必须有中文注释说明: 它是数据库操作封装，不承载 Agent 业务决策。

- [ ] **Step 5: 实现 SQLite Adapter**

Adapter 负责:

- 领域对象和 entity 转换。
- `Instant` 与 ISO-8601 字符串转换。
- 分页参数转换。
- `payloadJson` 原样保存。

- [ ] **Step 6: 运行 repository 测试**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=RepositoryAdapterIT test
```

Expected: PASS。

- [ ] **Step 7: 提交**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/conversation/repository backend/src/main/java/com/wzx/babiq/server/persistence/service backend/src/main/java/com/wzx/babiq/server/persistence/adapter backend/src/test/java/com/wzx/babiq/server/persistence/RepositoryAdapterIT.java
git commit -m "feat(p2-1): 增加 SQLite repository 适配层"
```

### Task 5: SecretStore 接口和 Provider 配置安全边界

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/settings/SecretStore.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/NoopSecretStore.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/ProviderPersistenceService.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/settings/SecretStoreTest.java`

- [ ] **Step 1: 写失败测试，证明 Provider 表不保存明文 key**

测试:

```java
String secretRef = secretStore.save("provider_deepseek", "sk-test");
providerPersistenceService.saveProvider(..., secretRef);
ProviderConfigEntity entity = providerMapper.selectById(...);
assertThat(entity.getSecretRef()).isEqualTo(secretRef);
assertThat(entity.toString()).doesNotContain("sk-test");
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=SecretStoreTest test
```

Expected: SecretStore 不存在。

- [ ] **Step 3: 定义 SecretStore**

接口:

```java
public interface SecretStore {
    String save(String namespace, String secretPlainText);
    Optional<String> load(String secretRef);
    void delete(String secretRef);
}
```

P2-1 的 `NoopSecretStore` 可以只在内存中保存，真实 Windows Credential Manager / JDK KeyStore 留给 P2-3 详细计划。

- [ ] **Step 4: 运行测试**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=SecretStoreTest test
```

Expected: PASS。

- [ ] **Step 5: 提交**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/settings backend/src/test/java/com/wzx/babiq/server/settings/SecretStoreTest.java
git commit -m "feat(p2-1): 定义 Provider 密钥存储边界"
```

### Task 6: 全量验证和文档收口

**Files:**

- Modify: `docs/superpowers/plans/p2-1-sqlite-persistence/codex-handoff.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/plans/p2-master.md`

- [ ] **Step 1: 运行 P2-1 专项测试**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=MyBatisPlusConfigTest,SecretStoreTest test
.\mvnw.cmd -Dtest=SQLiteMigrationIT,SchemaCommentsCoverageTest,RepositoryAdapterIT test
```

Expected: PASS。

- [ ] **Step 2: 运行后端全量验证**

Run:

```powershell
cd backend
.\mvnw.cmd clean verify
```

Expected: PASS，P1 既有测试不回归。

- [ ] **Step 3: 更新 handoff**

`codex-handoff.md` 必须记录:

- 已实现文件。
- 数据库默认路径。
- migration 版本。
- 数据库表/字段中文注释覆盖情况。
- 已跑命令和结果。
- P2-2 接入时应优先使用哪些 repository。
- 未接入运行链路的边界说明。

- [ ] **Step 4: 更新 AGENTS.md / CLAUDE.md**

写清:

- P2-1 是否完成。
- P2-2 是否是下一步。
- 新增验证命令。
- 仍禁止未确认子计划直接实现 P2-2/P2-3。

- [ ] **Step 5: 最终提交**

```powershell
git add AGENTS.md CLAUDE.md docs/superpowers/plans/p2-master.md docs/superpowers/plans/p2-1-sqlite-persistence
git commit -m "docs(p2-1): 更新持久化底座交接文档"
```

## 5. 验收标准

P2-1 只有在以下条件全部满足时才算完成:

- `backend/pom.xml` 使用已核对的稳定版本。
- SQLite 文件数据库能在临时目录和默认目录配置下启动。
- Flyway 自动创建 7 张 P2 业务表和 `bq_schema_comments` 元数据表。
- 每张 `bq_*` 表和每个字段都有中文注释，且 `SchemaCommentsCoverageTest` 通过。
- `PRAGMA foreign_keys=ON` 生效。
- MyBatis-Plus mapper 能插入、查询、分页。
- Repository adapter 测试不依赖 mapper 细节。
- Provider 配置表不保存明文 API Key。
- `cd backend; .\mvnw.cmd clean verify` 通过。
- P1 内存运行链路不被替换，桌面端行为不因 P2-1 改变。
- `AGENTS.md`、`CLAUDE.md`、`p2-master.md`、`codex-handoff.md` 已同步。

## 6. P2-1 不做

- 不实现 `thread/list`、`thread/load`、`thread/archive`。
- 不让桌面端最近对话读取数据库。
- 不把 `ConversationService` 从内存 Map 切到 SQLite。
- 不实现真实 Windows Credential Manager / JDK KeyStore。
- 不实现 Provider UI 编辑。
- 不实现 MCP。
- 不引入 Actuator、Prometheus、Langfuse 或 OpenTelemetry。

## 7. 用户确认点

本计划确认后才开始写代码。确认时需要明确:

- 是否接受 P2-1 只做持久化底座，不改变 UI 行为。
- 是否接受默认数据库路径 `${user.home}/.babiq/babiq.db`。
- 是否接受 P2-1 的 `NoopSecretStore` 只定义边界，真实密钥存储放入 P2-3。
