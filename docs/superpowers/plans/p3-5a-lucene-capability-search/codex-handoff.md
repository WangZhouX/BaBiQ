# P3-5a Lucene 能力搜索替换 — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p3-5a-lucene-capability-search\plan.md`

## 当前状态

- P3-5a 已完成，是 P3-5 的**算法层补强**：
  - 已接入 `org.springaicommunity:tool-searcher-lucene:1.0.1`（Apache Lucene + BM25）
  - 已**物理删除** `FallbackLexicalCapabilitySearchService`
  - 不留两套实现作可配置 fallback，避免代码污染
- 本阶段不引入 `tool-search-tool`（Advisor）和 `tool-searcher-vectorstore`（向量），原因见 plan §3.2。
- Context7 和 `javap` 已确认 `LuceneToolSearcher` 使用 `StandardAnalyzer`。它支持 Unicode/CJK token、lowercase 和 stop words，但不做英文 stemming；因此验收用例以 `"read file"` 和中文 `"读取"` 为准。

## 必读入口

1. `E:\BaBiQ\CLAUDE.md`（项目级纪律，特别是 §4 实现规则）
2. `E:\BaBiQ\docs\superpowers\plans\p3-master.md`
3. `E:\BaBiQ\docs\superpowers\plans\p3-task-index.md`
4. `E:\BaBiQ\docs\superpowers\plans\p3-5-capability-retrieval-control\plan.md`（前置阶段）
5. `E:\BaBiQ\docs\superpowers\plans\p3-5a-lucene-capability-search\plan.md`（**本阶段完整计划**）
6. Spring AI Community 1.0.x 分支 README：<https://github.com/spring-ai-community/spring-ai-tool-search-tool/tree/1.0.x>
7. 下载的 JAR 类定义参考（实施前可用 `javap` 复核）：
   - `org/springaicommunity/tool/search/ToolSearcher`
   - `org/springaicommunity/tool/search/ToolReference`
   - `org/springaicommunity/tool/search/ToolSearchRequest` / `ToolSearchResponse`
   - `org/springaicommunity/tool/searcher/LuceneToolSearcher`

## 为什么必须现在做（不是"以后再说"）

完整论证见 plan §1。最关键的四点：

1. **违反 BaBiQ 实现规则**：CLAUDE.md §4 明文要求"优先官方组件、不重复造轮子"。Apache Lucene + Spring AI Community thin wrapper 是合规选择；自家 130 行子串匹配是违规。
2. **当前中文搜索现在就是坏的**：`FallbackLexical.terms()` 用 `[^字母数字]+` split + `haystack.contains(term)` 子串匹配，对中文 query 几乎完全失效。BaBiQ 项目自身中文为主，确定性会踩。
3. **越晚替换历史包袱越大**：`bq_capability_search_events.strategy` 已经在记录 `FALLBACK_LEXICAL` 评分语义。越久数据越漂移，A/B 对比越难。
4. **此前推迟理由全部不成立**：详见 plan §1.4 逐条反驳。

## 为什么必须删除 Fallback（不能保留作可配置兜底）

完整论证见 plan §2。核心：

- 保留两套实现 → 配置项膨胀 + 测试矩阵爆炸 + 评分语义不一致
- BaBiQ 是学习项目，要的是"最佳实践展示"，不是"反例并列"
- 没有兜底诱惑才能强制保证 Lucene 集成质量

历史数据库记录 `strategy='FALLBACK_LEXICAL'` 保留作审计，**只删代码不删数据**。

## 关键代码挂点

新增 / 删除：

| 文件 | 动作 | 原因 |
|---|---|---|
| `backend/pom.xml` | 新增 `tool-searcher-lucene:1.0.1` 依赖 | 接入官方搜索器 |
| `backend/src/main/java/com/wzx/babiq/server/capability/LuceneCapabilitySearchService.java` | **新增** | 实现 `CapabilitySearchService` 接口，包装 `LuceneToolSearcher` |
| `backend/src/test/java/com/wzx/babiq/server/capability/LuceneCapabilitySearchServiceTest.java` | **新增** | BM25 评分、英文基础 query、CJK token、索引重建等用例 |
| `backend/src/main/java/com/wzx/babiq/server/capability/FallbackLexicalCapabilitySearchService.java` | **删除** | 自家轮子，违反 §4 实现规则 |
| `backend/src/test/java/com/wzx/babiq/server/capability/CapabilitySearchServiceTest.java` | **删除** | 测的是 Fallback 行为，已被新测试覆盖 |
| `backend/src/main/java/com/wzx/babiq/server/capability/CapabilityCatalogSyncService.java` | 修改 | 同步完目录后发 `CapabilityCatalogChangedEvent` 触发 Lucene 重建 |
| `backend/src/main/resources/db/migration/V12__lucene_capability_search_comments.sql` | 新增 | 刷新搜索策略字段中文说明，不改写已发布 V11 migration |

不动：

- `CapabilityExposurePlanner` / `ToolSearchTool` / `CapabilityRepository`
- `bq_capabilities` / `bq_capability_search_events` 表结构
- Spring AI 版本（保持 1.1.6）
- 任何 JSON-RPC handler 和桌面端协议

## 执行规则

1. 严格按 plan §4 的 6 个 Task 顺序执行。
2. 用 `superpowers:test-driven-development`，每个 Task 先写失败测试再实现。
3. **Task 4 删除文件前必须先 grep 确认无外部引用**（plan §4 Task 4 Step 1）。
4. 每个 Task 完成用中文 conventional commit。
5. 不主动 push。
6. 完成后必须更新 `AGENTS.md`、`CLAUDE.md`、`p3-master.md`、`p3-task-index.md`。
7. 不允许 `@Disabled` 占位测试用例。
8. 不引入 `tool-search-tool`（Advisor，跟 SAA `ReactAgent` 不兼容）。
9. 不引入 `tool-searcher-vectorstore`（语义搜索留给后续阶段）。
10. 不升 Spring AI 1.1.6 主线。

## 关键默认参数

| 参数 | 值 | 说明 |
|---|---|---|
| `LuceneToolSearcher` 最低分阈值 | `0.1f` | 过滤明显不相关结果。官方示例用 `0.4f`；BaBiQ 工具集少，放宽一点避免空结果 |
| Lucene Analyzer | 默认 `StandardAnalyzer` | 库自带，含 Unicode/CJK token、lowercase、stop words；不做英文 stemming。后续如中文相关性不足，可评估 `SmartChineseAnalyzer` |
| Lucene session id | `"babiq"` 单 session | 学习项目单进程，不需要多租户隔离 |
| 索引重建策略 | 全量 rebuild on `CapabilityCatalogChangedEvent` | 当前能力 < 50，毫秒级，不优化为真正增量 |
| 搜索 strategy 字段值 | `"LUCENE"` | 写入 `bq_capability_search_events.strategy` |

## 最终验收命令

```powershell
cd E:\BaBiQ\backend

# 1. 专项测试
.\mvnw.cmd "-Dtest=LuceneCapabilitySearchServiceTest,CapabilityCatalogSyncServiceTest,CapabilityExposurePlannerTest,ToolSearchToolTest,CapabilityHandlersTest,SchemaCommentsCoverageTest" test

# 2. 后端全量
.\mvnw.cmd clean verify

# 3. 桌面端
cd ..\desktop
.\gradlew.bat test

# 4. 确认 Fallback 类已删除
cd ..\backend
rg -n "FallbackLexicalCapabilitySearchService|FALLBACK_LEXICAL" src/main/java src/test/java
# Expected: 0 hit
# 历史 migration 中的旧策略说明不改写；V12 会刷新新库最终 bq_schema_comments。

# 5. 依赖树确认
.\mvnw.cmd "dependency:tree" "-Dincludes=org.springaicommunity,org.apache.lucene"
```

## 完成报告必须包含

- Task 1-6 逐条完成状态（✅/❌）
- 跑过的验证命令和**实际输出**（不是预期）
- `FallbackLexicalCapabilitySearchService` 已删除证据（git log + grep 空命中）
- Lucene 依赖树证据
- 中文 query 烟测对比（同一 query 在 P3-5 时 FallbackLexical 返回什么，P3-5a 后 LuceneToolSearcher 返回什么）
- P3-5 回归测试通过证据（`CapabilityExposurePlannerTest`、`ToolSearchToolTest`、`CapabilityHandlersTest` 等）
- 中文 conventional commit 列表

## 本次完成证据

- `.\mvnw.cmd "dependency:tree" "-Dincludes=org.springaicommunity,org.apache.lucene"`：通过，确认 `tool-searcher-lucene:1.0.1` 传递引入 `tool-search-tool:1.0.1` 和 `lucene-core:9.12.3`。
- `rg -n "FallbackLexicalCapabilitySearchService|FALLBACK_LEXICAL" src/main/java src/test/java`：0 命中，Java 生产代码和测试已不再保留 fallback 实现或策略常量。
- `.\mvnw.cmd "-Dtest=LuceneCapabilitySearchServiceTest,CapabilityCatalogSyncServiceTest,CapabilityExposurePlannerTest,ToolSearchToolTest,CapabilityHandlersTest,SchemaCommentsCoverageTest" test`：通过，13 tests，0 failures，0 errors。
- `.\mvnw.cmd clean verify`：通过，后端全量回归成功。
- `.\gradlew.bat test`：通过，桌面端回归成功。
- 中文 query 验收由 `LuceneCapabilitySearchServiceTest.search_should_support_cjk_query_tokens` 覆盖：`"读取"` 能命中 `local.read_file`，并且不会把 `local.write_file` 排在结果里。
- 明确说明未 push

## 下一步

P3-5a 验收通过后，按 P3 master plan 进入：

- **P3-6（暂定）**：embedding provider 接入 + `tool-searcher-vectorstore` 语义搜索
- 或：P4 详细计划编写

不要直接把 vectorstore/embedding 提前混进 P3-5a。
