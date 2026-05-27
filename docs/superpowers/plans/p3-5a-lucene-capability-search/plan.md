# P3-5a Lucene 能力搜索替换子计划

> **For agentic workers:** REQUIRED：实施前使用 `superpowers:writing-plans` 复核本计划，实施时使用 `superpowers:executing-plans` 和 `superpowers:test-driven-development`，声称完成前使用 `superpowers:verification-before-completion`。
>
> **状态：** 已完成。本计划是对已完成 P3-5 的"自实现替换为官方组件"补强，不引入新功能边界。

**Goal:** 把 P3-5 自实现的 `FallbackLexicalCapabilitySearchService` 完全替换为 Spring AI Community 官方 `LuceneToolSearcher`（基于 Apache Lucene + BM25），**移除自家轮子**，让 BaBiQ 的能力搜索遵守自己的"优先官方组件、不重复造轮子"规则。

**Architecture:** 不动 P3-5 已落地的 `ToolSearchTool` / `CapabilityExposurePlanner` / `CapabilityCatalogSyncService` 等上层组件，**只换底层搜索引擎实现**。新实现 `LuceneCapabilitySearchService` 仍然实现 `CapabilitySearchService` 接口；删除 `FallbackLexicalCapabilitySearchService` 类及其测试，避免代码污染。

**Tech Stack:** 同 P3-5，新增 `org.springaicommunity:tool-searcher-lucene:1.0.1`（对应 Spring AI 1.1.x 主线，**不升级** Spring AI 版本），间接引入 Apache Lucene Core / Analyzers Common。

---

## 1. 为什么必须现在接入（不是"以后再说"）

### 1.1 当前实现违反 BaBiQ 自己的实现规则

`CLAUDE.md` §4 实现规则明文规定：

> 实现任何 Agent、LLM、工具、Hook、Interceptor、Memory、HITL、观测、沙箱或协议相关能力前，**必须先查看对应的官方代码库或官方文档，优先确认 Spring AI Alibaba、Spring AI、JDK/Java 标准库或成熟 Java 生态中是否已有实现**。
>
> **能使用官方组件、官方扩展点或成熟 Java 库时，优先做薄封装和集成，不重复造轮子**；只有官方能力缺失、与 BaBiQ 协议不匹配或引入成本过高时，才允许自实现，并在计划或代码注释中说明原因。

事实是：

- Apache Lucene 是 20 年历史的 Apache 顶级项目，Elasticsearch / Solr 的底层
- Spring AI Community 官方提供 `tool-searcher-lucene:1.0.1`，专门为 Spring AI 1.1.x + Spring Boot 3 适配
- BaBiQ 当前的 `FallbackLexicalCapabilitySearchService` 是 130 行自家代码，用"子串包含计数"做评分
- 不存在"官方能力缺失"或"引入成本过高"——只用 `LuceneToolSearcher` 这个搜索器类（不引入 Advisor），完全是薄封装

**结论：当前实现违规，必须按 BaBiQ 自己定的规矩纠正。**

### 1.2 当前实现的中文搜索现在就是坏的（不是未来问题）

`FallbackLexicalCapabilitySearchService.terms()` 用 `[^\\p{IsAlphabetic}\\p{IsDigit}_\\-\\.]+` 做分词：

- 这个正则对中文不友好——`\p{IsAlphabetic}` 匹配所有 Unicode 字母（包含 CJK），所以**整句中文会被当成一个 token**。
- `score()` 用 `haystack.contains(term)` 做子串匹配，意味着用户输入 `"读取文件"` 时，必须能力的 searchText 里**恰好**包含 `"读取文件"` 这四个字符的连续子串才能命中。
- 现有能力的 `searchText` 默认来自工具的 `name` 和 `description`——`read_file` 工具的 description 不会写 `"读取文件"` 这种短语。

**实证：BaBiQ 项目自己是中文为主（CLAUDE.md、代码注释、commit message 都中文），用户输入大概率含中文短语。当前搜索器对这种场景几乎完全失效。这是已经在踩的坑，不是未来才发生的事。**

Lucene 默认 `StandardAnalyzer` 对中日韩用单字 token + Unicode 分词，效果比子串匹配强一个数量级；后续如需更精细中文分词，可叠加 `lucene-analyzers-smartcn`，但**当前 StandardAnalyzer 就已经远超 Fallback**。

### 1.3 越晚替换历史包袱越大

`bq_capability_search_events.strategy` 字段记录了搜索策略名。当前所有事件 `strategy='FALLBACK_LEXICAL'`。

如果继续延后替换：

- 用户对话越来越多，事件越来越多
- 一年后回看搜索行为分析时，要解释两种评分语义（FALLBACK 的"0/2/3/5"是子串计数权重；LUCENE 的是 0~1 浮点 BM25 score）
- 想做 A/B 对比时数据不可比

**越早替换，历史数据漂移越小。** 现在 P3-5 刚完成、事件表数据少，是最佳替换窗口。

### 1.4 当时延后的理由站不住脚

复盘上次审查中"延后"的 4 条理由：

| 当时理由 | 真实判断 |
|---|---|
| 当前 18 工具规模小，token 节省 <5% | 误导。Token 节省主要来自"按需暴露"，本计划核心是**搜索精度**和**中文支持**，跟工具数量弱相关 |
| SAA ReactAgent 不兼容 Advisor | 本计划**只引入 `tool-searcher-lucene`，不引入 `tool-search-tool`（Advisor）**，这条不适用 |
| 5MB 依赖太重 | BaBiQ 桌面端打包 100+ MB，5MB 是噪音 |
| 等用户反馈"中文查不到"再升 | 等于让用户先踩坑。BaBiQ 是中文为主的项目，**确定性会踩** |

---

## 2. 为什么必须移除 `FallbackLexicalCapabilitySearchService`（不能保留作 fallback）

### 2.1 一套实现 vs 两套实现的污染成本

保留 Fallback 作"可配置 fallback"看起来"防御性更好"，实际带来：

- 配置项膨胀：`babiq.capability.search.strategy=lucene|fallback` 这种切换开关
- 测试矩阵爆炸：每个能力搜索相关测试都要在两种 strategy 下跑一遍
- 文档解释成本：CLAUDE.md / handoff / 学习文档都要说明"什么时候用哪个"
- 评分语义不一致：两个 strategy 返回的分数尺度完全不同，调用方无法做统一判断

**保留 Fallback 等于把"过渡期决策"永久化，永远还不掉这笔技术债。**

### 2.2 BaBiQ 是学习项目，要的是"最佳实践展示"

CLAUDE.md §1 明确 BaBiQ 是 Codex-like AI Agent **学习项目**。学习项目保留一个比官方组件差的自实现作 fallback，等于在教学示例里同时摆"正例"和"反例"，并把选择题留给读者。

更好的做法：

- 主干用业界标准（Lucene + BM25）
- 自家代码只保留**有真实差异化价值**的部分（如 `ToolSearchTool` 跟 BaBiQ 审批/沙箱/Spotlighting 链路对接，这部分 Spring AI Community 的 Advisor 替不了）

### 2.3 没有兜底诱惑 → 强制保证 Lucene 集成质量

保留 Fallback 会让实施者/Codex 在调试时偷懒切回 fallback，掩盖 Lucene 集成的真实问题。完全移除后：

- Lucene 接入有 bug 必须修，不能切走
- 测试覆盖必须真实覆盖 Lucene 行为，不能 mock fallback 偷工
- 配置错误暴露得早

### 2.4 删除是干净的，不影响历史审计

`bq_capability_search_events.strategy` 字段是 `TEXT`，旧记录里 `'FALLBACK_LEXICAL'` 值保留作**历史审计**，新事件全部写 `'LUCENE'`。代码里只剩一种实现，但数据库表保留所有历史。

---

## 3. 范围边界

### 3.1 必做

- 新增 `tool-searcher-lucene:1.0.1` Maven 依赖（**仅此一个**，不引入 `tool-search-tool` Advisor 模块）
- 新增 `LuceneCapabilitySearchService implements CapabilitySearchService`，作 Spring `@Service` 默认实现
- **删除** `FallbackLexicalCapabilitySearchService.java` 和 `FallbackLexicalCapabilitySearchServiceTest.java`（如果存在）
- 删除原 `CapabilitySearchServiceTest`，新增 `LuceneCapabilitySearchServiceTest` 覆盖 Lucene 行为（BM25 评分、英文基础 query、CJK token 和索引重建）
- 启动时把所有 `repository.listEnabled()` 索引到 LuceneToolSearcher
- 监听能力目录变更（启用/禁用、新增、修改）增量更新索引
- 搜索事件 `strategy` 字段写 `'LUCENE'`

### 3.2 不做

- 不引入 `org.springaicommunity:tool-search-tool:1.0.1`（这个是 `ChatClient.advisor()` 链上的 `ToolSearchToolCallAdvisor`，跟 SAA `ReactAgent` 不兼容；BaBiQ 自家 `ToolSearchTool` 已经实现等效功能且对接了审批/沙箱/Spotlighting）
- 不引入 `org.springaicommunity:tool-searcher-vectorstore`（VectorStore 路径留给后续接入 embedding provider 的阶段做）
- 不引入 `lucene-analyzers-smartcn`（StandardAnalyzer 已经远超 Fallback；如需要更好中文分词，作为后续可选优化）
- 不改 P3-5 的 `CapabilityExposurePlanner` / `ToolSearchTool` / `CapabilityCatalogSyncService` / `CapabilityRepository`
- 不改 `bq_capabilities` / `bq_capability_search_events` 表结构（只换 strategy 字段写入值）
- 不升 Spring AI 1.1.6 主线

---

## 4. 实施任务

### Task 1: 加 Maven 依赖

**Files:**
- Modify: `backend/pom.xml`

**Steps:**

- [ ] **Step 1：在 `<properties>` 区加版本属性**

  ```xml
  <tool-searcher-lucene.version>1.0.1</tool-searcher-lucene.version>
  ```

- [ ] **Step 2：在 `<dependencies>` 区加依赖**

  ```xml
  <dependency>
      <groupId>org.springaicommunity</groupId>
      <artifactId>tool-searcher-lucene</artifactId>
      <version>${tool-searcher-lucene.version}</version>
  </dependency>
  ```

  注意：**不要**加 `tool-search-tool`（Advisor 模块），原因见 §3.2。

- [ ] **Step 3：核对依赖树**

  ```powershell
  cd backend
  .\mvnw.cmd dependency:tree -Dincludes=org.springaicommunity,org.apache.lucene
  ```

  Expected：解析到 `tool-searcher-lucene:1.0.1`、`tool-search-tool:1.0.1`（作为 transitive）和 `lucene-core`。实际核对显示 `LuceneToolSearcher` 使用 Lucene 9.12.3 的 `StandardAnalyzer`，不额外引入 `lucene-analyzers-common`。

- [ ] **Step 4：编译验证**

  ```powershell
  .\mvnw.cmd -DskipTests compile
  ```

  Expected：BUILD SUCCESS，无 Spring AI 版本解析冲突。

**Commit：**

```text
build(p3-5a): 接入 Lucene 工具搜索器依赖
```

---

### Task 2: 实现 `LuceneCapabilitySearchService`

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/capability/LuceneCapabilitySearchService.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/capability/LuceneCapabilitySearchServiceTest.java`

**Steps:**

- [ ] **Step 1：写失败测试**

  关键用例：
  - 基础 BM25 评分：query `"read"` 命中 `read_file` 而不命中 `write_file`
  - 英文基础 query：query `"read file"` 命中 `read_file`；注意 Context7 和 `javap` 已确认 `StandardAnalyzer` 不做 stemming，不能把 `"reading"` 当作 `"read"` 的同义词
  - Stop words：query `"the file reader"` 不会把 `"the"` 当成评分主因
  - CJK 单字 token：query `"读取"` 命中 description 含 `"读取文件"` 的能力
  - DISABLED 能力不进入索引，不会被搜索到
  - DEFERRED 能力可被搜到
  - 限制 `limit` 参数生效
  - `strategy` 字段值为 `"LUCENE"`
  - 搜索事件 `recordEvent=true` 时落库
  - 搜索事件 `recordEvent=false` 时不落库

- [ ] **Step 2：实现服务**

  关键设计要求：

  ```java
  @Service
  public class LuceneCapabilitySearchService implements CapabilitySearchService {

      /** 当前搜索策略名称，写入审计表。 */
      public static final String STRATEGY = "LUCENE";

      /** 全局共享的 session id，单 thread 学习项目用一个 session 就够。 */
      private static final String SESSION_ID = "babiq";

      /** 最低相似度阈值，过滤明显不相关结果。 */
      private static final float MIN_SCORE_THRESHOLD = 0.1f;

      private final CapabilityRepository repository;
      private final LuceneToolSearcher searcher;
      private final ObjectMapper objectMapper = new ObjectMapper();

      public LuceneCapabilitySearchService(CapabilityRepository repository) {
          this.repository = repository;
          this.searcher = new LuceneToolSearcher(MIN_SCORE_THRESHOLD);
      }

      @PostConstruct
      void rebuildIndex() {
          searcher.clearIndex(SESSION_ID);
          for (CapabilityDescriptor descriptor : repository.listEnabled()) {
              if (descriptor.exposureMode() == CapabilityExposureMode.DISABLED) {
                  continue;
              }
              searcher.indexTool(SESSION_ID, toToolReference(descriptor));
          }
      }

      @Override
      public CapabilitySearchResult search(CapabilitySearchRequest request) {
          // ... 委托给 searcher.search(...)，映射 ToolReference 回 CapabilityDescriptor
      }

      private ToolReference toToolReference(CapabilityDescriptor descriptor) {
          String summary = String.join(" | ",
              descriptor.name(),
              descriptor.displayName(),
              descriptor.description(),
              descriptor.searchText());
          return ToolReference.builder()
              .toolName(descriptor.capabilityId())
              .summary(summary)
              .build();
      }
  }
  ```

  注意：

  - `LuceneToolSearcher` 是 `Closeable`，应用关闭时要 close。可以加 `@PreDestroy` 或让 Spring 管理。
  - 索引重建必须在 `CapabilityRepository` 数据就绪后执行；用 `@PostConstruct` 是简单方案，更稳的是监听 `ApplicationReadyEvent`。
  - 能力目录变更（启用/禁用、新增）需要增量更新索引——见 Task 3。
  - 不要把 `ToolReference.relevanceScore` 字段当输入用；它是搜索结果输出字段。

- [ ] **Step 3：跑测试**

  ```powershell
  cd backend
  .\mvnw.cmd "-Dtest=LuceneCapabilitySearchServiceTest" test
  ```

  Expected：PASS。

**Commit：**

```text
feat(p3-5a): 实现 Lucene 能力搜索服务
```

---

### Task 3: 接入能力目录变更增量索引

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/capability/CapabilityCatalogSyncService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/capability/LuceneCapabilitySearchService.java`

**Steps:**

- [ ] **Step 1：设计增量更新接口**

  在 `LuceneCapabilitySearchService` 增加：

  ```java
  /** 新增或更新一个能力到 Lucene 索引（启用、参数变化、暴露模式从 DISABLED 切回时调用）。*/
  public void upsertCapability(CapabilityDescriptor descriptor) {
      if (descriptor.exposureMode() == CapabilityExposureMode.DISABLED || !descriptor.enabled()) {
          removeCapability(descriptor.capabilityId());
          return;
      }
      // Lucene IndexWriter 的 updateDocument 行为是"先删后加",但 LuceneToolSearcher 没暴露这个 API,
      // 所以这里先 clearIndex + 重建当前 session, 或者改用 add+delete 的组合.
      // 简化方案: 调用一次完整 rebuildIndex(), 因为能力数量是 O(几十) 级别, 全量重建毫秒级.
      rebuildIndex();
  }

  public void removeCapability(String capabilityId) {
      // 同理, LuceneToolSearcher.delete(sessionId, toolName) 可用.
      searcher.delete(SESSION_ID, capabilityId);
      searcher.commit(SESSION_ID);
  }
  ```

  > **决策依据**：BaBiQ 当前能力数量 < 50，全量 rebuild 在 Lucene 内存模式下毫秒级完成。`upsertCapability` 用全量 rebuild 是**最简、最安全**的实现。如果未来能力数量上升到几百，再优化为真正的增量。

- [ ] **Step 2：在 `CapabilityCatalogSyncService.sync()` 末尾调用增量更新**

  当目录刚同步完一批能力后，调用 `LuceneCapabilitySearchService.rebuildIndex()`。用 Spring `ApplicationEventPublisher` 发 `CapabilityCatalogChangedEvent`，让搜索服务监听更解耦：

  ```java
  // CapabilityCatalogSyncService
  public class CapabilityCatalogSyncService {
      private final ApplicationEventPublisher events;

      public void sync() {
          // ... 现有逻辑
          events.publishEvent(new CapabilityCatalogChangedEvent());
      }
  }

  // LuceneCapabilitySearchService
  @EventListener
  public void onCatalogChanged(CapabilityCatalogChangedEvent event) {
      rebuildIndex();
  }
  ```

- [ ] **Step 3：补测试**

  覆盖：
  - 同步新增能力后立即可被搜到
  - 同步禁用能力后不再被搜到
  - 同步移除能力后不再被搜到

  ```powershell
  cd backend
  .\mvnw.cmd "-Dtest=LuceneCapabilitySearchServiceTest,CapabilityCatalogSyncServiceTest" test
  ```

**Commit：**

```text
feat(p3-5a): 能力目录变更后增量重建 Lucene 索引
```

---

### Task 4: **删除** `FallbackLexicalCapabilitySearchService` 及测试

**这是本计划的核心动作**——避免代码污染。

**Files:**
- **Delete**: `backend/src/main/java/com/wzx/babiq/server/capability/FallbackLexicalCapabilitySearchService.java`
- **Delete**: `backend/src/test/java/com/wzx/babiq/server/capability/CapabilitySearchServiceTest.java`（这个测试类原本测的是 Fallback 实现；新测试已在 Task 2 中创建为 `LuceneCapabilitySearchServiceTest`）

**Steps:**

- [ ] **Step 1：核对没有其他文件引用 `FallbackLexicalCapabilitySearchService`**

  ```powershell
  cd backend
  grep -rn "FallbackLexicalCapabilitySearchService" src/
  ```

  Expected：除被删除的文件外，无引用。

  如有引用，先改成 `CapabilitySearchService` 接口（依赖倒置），再删。

- [ ] **Step 2：删除文件**

  ```powershell
  Remove-Item src/main/java/com/wzx/babiq/server/capability/FallbackLexicalCapabilitySearchService.java
  Remove-Item src/test/java/com/wzx/babiq/server/capability/CapabilitySearchServiceTest.java
  ```

- [ ] **Step 3：跑 clean verify**

  ```powershell
  .\mvnw.cmd clean verify
  ```

  Expected：BUILD SUCCESS。所有依赖 `CapabilitySearchService` 的代码（`ToolSearchTool`、`CapabilityExposurePlanner` 等）自动注入新的 `LuceneCapabilitySearchService`。

- [ ] **Step 4：扫描审计字段值**

  Java 生产代码和测试里不能有任何地方写 `"FALLBACK_LEXICAL"` 字符串；已发布 migration 中的历史值不改写，避免破坏 Flyway checksum。

  ```powershell
  rg -n "FALLBACK_LEXICAL" src/main/java src/test/java
  ```

  Expected：0 命中。

  > **历史数据保留**：`bq_capability_search_events` 表中旧记录的 `strategy='FALLBACK_LEXICAL'` 不删，作历史审计。新事件全部写 `'LUCENE'`。

**Commit：**

```text
refactor(p3-5a): 移除自实现的 Fallback 能力搜索器
```

---

### Task 5: 整体测试 + 关键场景烟测

**Files：** 不改代码，只验证。

- [ ] **Step 1：后端专项测试**

  ```powershell
  cd backend
  .\mvnw.cmd "-Dtest=LuceneCapabilitySearchServiceTest,CapabilityCatalogSyncServiceTest,CapabilityExposurePlannerTest,ToolSearchToolTest,CapabilityHandlersTest,SchemaCommentsCoverageTest" test
  ```

  Expected：BUILD SUCCESS。

- [ ] **Step 2：后端全量回归**

  ```powershell
  .\mvnw.cmd clean verify
  ```

  Expected：BUILD SUCCESS。重点确认 P3-5 已有测试不退化：
  - `CapabilityExposurePlannerTest`（VISIBLE/DEFERRED/DISABLED 装配逻辑）
  - `ToolSearchToolTest`（工具搜索经过 ToolRegistry）
  - `CapabilityHandlersTest`（JSON-RPC `capability/status` / `capability/search` / `capability/settings/set`）

- [ ] **Step 3：桌面端测试**

  桌面端无代码改动，跑全量确认协议兼容：

  ```powershell
  cd ..\desktop
  .\gradlew.bat test
  ```

  Expected：BUILD SUCCESSFUL。

- [ ] **Step 4：人工烟测——中文 query**

  启动后端 + 桌面端，在桌面端触发能力搜索（或直接发 JSON-RPC `capability/search`）：

  - query `"读取文件"` → 期望命中 `read_file` 这类能力
  - query `"执行命令"` → 期望命中 `exec_shell`
  - query `"read file"`（英文基础词）→ 命中 `read_file`
  - query `"unknown"` → 返回空列表

  对比 P3-5 替换前 FallbackLexical 在同样 query 下的行为，记录在 codex-handoff.md。

---

### Task 6: 文档同步

**Files:**
- Modify: `docs/superpowers/plans/p3-5-capability-retrieval-control/plan.md`（追加 P3-5a 链接）
- Modify: `docs/superpowers/plans/p3-5-capability-retrieval-control/codex-handoff.md`（追加 P3-5a 链接）
- Modify: `docs/superpowers/plans/p3-master.md`（§4 表格 P3-5 状态加注：P3-5a 已完成 Lucene 替换）
- Modify: `docs/superpowers/plans/p3-task-index.md`
- Create: `docs/superpowers/plans/p3-5a-lucene-capability-search/codex-handoff.md`
- Modify: `AGENTS.md`（当前检查点）
- Modify: `CLAUDE.md`（当前检查点）

**关键文案要求：**

- AGENTS.md / CLAUDE.md 当前检查点必须明说：
  > P3-5a 已完成：移除自实现的 `FallbackLexicalCapabilitySearchService`，能力搜索改用 Spring AI Community `tool-searcher-lucene:1.0.1`（Lucene + BM25）。本阶段贯彻 BaBiQ 实现规则"优先官方组件、不重复造轮子"。

- P3-5 plan.md §1.1 / §Tech Stack 行的"BaBiQ 自有 `CapabilitySearchService` 实现"措辞需要更新为"接 Spring AI Community LuceneToolSearcher"。

**Commit：**

```text
docs(p3-5a): 同步 Lucene 能力搜索替换状态
```

---

## 5. 验证清单

所有以下命令必须实际执行并贴出真实输出（不是预期），作为完成证据：

```powershell
# 1. 后端专项
cd backend
.\mvnw.cmd "-Dtest=LuceneCapabilitySearchServiceTest,CapabilityCatalogSyncServiceTest,CapabilityExposurePlannerTest,ToolSearchToolTest,CapabilityHandlersTest,SchemaCommentsCoverageTest" test

# 2. 后端全量回归
.\mvnw.cmd clean verify

# 3. 桌面端
cd ..\desktop
.\gradlew.bat test

# 4. 确认 Fallback 类已删除
cd ..\backend
rg -n "FallbackLexicalCapabilitySearchService|FALLBACK_LEXICAL" src/main/java src/test/java  # Expected: 0 hit

# 5. 确认依赖树
.\mvnw.cmd dependency:tree -Dincludes=org.springaicommunity,org.apache.lucene
```

---

## 6. 风险与处理

| 风险 | 严重度 | 处理 |
|---|---|---|
| `tool-searcher-lucene` 是小社区库，未来停止维护 | 中 | 这只是 thin wrapper（核心 `LuceneToolSearcher.class` 仅 15K），万一停维护，BaBiQ 自己 fork 维护 thin wrapper 是几百行 Java 代码，可控；底层 Apache Lucene 极稳定 |
| `LuceneToolSearcher` 构造器只接 `float threshold`，不接 Analyzer | 低 | 默认 `StandardAnalyzer` 已远超 Fallback；如需中文增强（`SmartChineseAnalyzer`），未来加 `lucene-analyzers-smartcn` 依赖并 fork 一个自定义实现，本计划不做 |
| 删除 Fallback 后无法降级 | 低 | 这是有意为之（见 §2.3）。如果 Lucene 集成出问题，是 bug，要修；不能切走掩盖 |
| 应用启动时 Lucene 索引重建慢 | 低 | 当前能力数量 < 50，毫秒级完成；用 `@PostConstruct` 同步执行可接受 |
| `bq_capability_search_events` 历史数据 strategy 字段值不一致 | 低 | 旧记录保留 `FALLBACK_LEXICAL`，新记录写 `LUCENE`；事件表本质是审计日志，允许 strategy 字段有历史值。文档说明 |
| 测试时 Lucene 索引位置 | 低 | `LuceneToolSearcher` 默认用内存索引（`RAMDirectory` 风格），不会污染测试目录 |

---

## 7. 完成标准

- [x] `backend/pom.xml` 已添加 `org.springaicommunity:tool-searcher-lucene:1.0.1` 依赖
- [x] `LuceneCapabilitySearchService` 已实现并通过完整失败路径测试
- [x] `FallbackLexicalCapabilitySearchService.java` 已**物理删除**
- [x] 原 `CapabilitySearchServiceTest` 已删除，新 `LuceneCapabilitySearchServiceTest` 覆盖等效场景
- [x] Java 生产代码和测试中 `FallbackLexicalCapabilitySearchService|FALLBACK_LEXICAL` 0 命中
- [x] 后端 `clean verify` 通过，且 P3-5 已有测试不退化
- [x] 桌面端 `gradlew.bat test` 通过
- [x] 中文 query 自动化烟测可用（`"读取"` 命中 `read_file` 类能力）
- [x] `AGENTS.md` / `CLAUDE.md` / `p3-master.md` 当前检查点已同步
- [x] 使用中文 conventional commit
- [x] 未 push

---

## 8. 与 P3-5 的衔接

P3-5 已完成的以下组件**不受本计划影响**：

- `CapabilityExposurePlanner`（VISIBLE/DEFERRED/DISABLED 决策）
- `ToolSearchTool`（BaBiQ 自家工具，对接审批/沙箱/Spotlighting）
- `CapabilityCatalogSyncService`（能力目录扫描）
- `CapabilityRepository`（SQLite 持久化）
- `bq_capabilities` / `bq_capability_search_events` 表结构

本计划**只换** `CapabilitySearchService` 接口的实现类。所有依赖该接口的上层组件无感知，行为更准确。

---

## 9. 为什么这是补强而不是回头改

P3-5 已经做对的事：**预留了 `CapabilitySearchService` 接口**。这意味着替换实现类完全不破坏上层 API，是一个**纯内部优化**。

P3-5 留下的债：**默认实现是自家轮子，违反 BaBiQ 实现规则**。P3-5a 还这笔债。

本计划是 P3-5 设计接口的**完整兑现**——P3-5 写接口、留扩展点；P3-5a 把扩展点接上业界标准实现，并删除占位用的 fallback。

---

## 10. 下一步

P3-5a 完成且用户验收后，按 P3 master plan 进入：

- **P3-6（暂定）**：embedding provider 接入 + `tool-searcher-vectorstore` 语义搜索（当前 P3-5/P3-5a 都没做向量路径）
- 或：P4 详细计划编写（具体方向由用户确认）
