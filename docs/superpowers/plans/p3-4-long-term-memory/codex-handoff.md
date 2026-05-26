# P3-4 长期记忆异步流水线交接

## 状态

P3-4 当前仅完成详细开发计划，尚未实现代码。

计划入口：

- `docs/superpowers/plans/p3-4-long-term-memory/plan.md`

## 必读入口

1. `E:\BaBiQ\CLAUDE.md`（项目级纪律）
2. `E:\BaBiQ\docs\ARCHITECTURE.md` §14 三层 Memory 和 §20 RAG
3. `E:\BaBiQ\docs\superpowers\plans\p3-master.md`
4. `E:\BaBiQ\docs\superpowers\plans\p3-task-index.md`
5. `E:\BaBiQ\docs\superpowers\plans\p3-4-long-term-memory\plan.md`（本阶段完整计划）
6. 参考 Codex 源码：`E:\wzx\codex\codex-rs\memories\`（尤其 `write/src/phase1.rs`、`write/src/phase2.rs`、`read/templates/memories/read_path.md`）

## 执行规则

1. 实施前重新核对 Spring AI 1.1.6 和 Spring AI Alibaba 1.1.2.3 是否仍是最新稳定版；如有新稳定版必须先更新计划再实施，禁止使用 RC/Beta/EAP。
2. 用 `superpowers:test-driven-development`，每个 Task 先写失败测试再实现。
3. 每个 Task 完成用中文 conventional commit，例如 `feat(p3-4): 建立长期记忆持久化底座`。
4. 不主动 push。
5. 完成后必须更新 `AGENTS.md`、`CLAUDE.md`、`p3-master.md`、`p3-task-index.md`。
6. 不允许 `@Disabled` 占位测试用例。
7. 不得绕过 plan §4.2 的 `LongTermMemoryProperties` 默认值；要变动默认值必须先在 plan 中写清理由。
8. Spring AI / SAA 相关实现优先使用官方扩展点；参考 plan §1.2 的复用边界。

## 背景

P3-1 到 P3-3A 已完成：

- 当前窗口管理和 `ContextSnapshot`。
- `ContextAssembler` 分层上下文和 `LongTermMemoryReference` 占位。
- 短期压缩、active summary 安装、压缩审计字段、事务边界、乐观锁和启动恢复。

P3-4 要补齐的是长期记忆，不是再做短期压缩。

## 设计结论

- BaBiQ 长期记忆采用 Codex 风格两阶段异步流水线；Phase 1 由启动扫描和周期扫描挑选 idle thread，不在每个 turn 完成后立刻调用模型。
- SQLite 是事实源，Markdown 是用户可读镜像。
- Phase 1 用 Spring AI structured output 提取候选。
- Phase 2 支持候选阈值自动触发、每日兜底扫描和手动 `memory/consolidate`，job 使用 `phase2:{generation}` 保留归并历史。
- Phase 2 首版用受控 Java artifact writer + structured consolidation strategy 归并，不让模型直接写任意文件；`raw_memories.md` 和 `rollout_summaries/` 由 Java 机械生成，模型只生成 `memory_summary.md` 和 `MEMORY.md`。
- Spring AI Alibaba ReactAgent 可作为后续归并策略实现，但不能绕过 SQLite 审计和 artifact lifecycle。
- Read path 默认只注入 `memory_summary`，完整 `MEMORY.md` 和 VectorStore/RAG 放到后续阶段。
- P3-4 必须有用户开关、thread mode、secret redaction 和污染模式。

## 关键默认参数（plan §4.2 LongTermMemoryProperties 的核心默认值）

实施时不要随意改这些默认值；若必须变动需要在 plan 中先记录理由。

| 参数 | 默认值 | 说明 |
|---|---|---|
| `phase1OnStartup` | true | 后端启动后做一次 Phase 1 扫描 |
| `phase1ScanIntervalMillis` | 3_600_000 | 1 小时周期扫描 |
| `phase1MinIdleMillis` | 300_000 | thread 最近 turn 完成后至少空闲 5 分钟才允许提取 |
| `phase1MaxThreadsPerScan` | 4 | 单次扫描提取上限 |
| `phase1InputWindowPercent` | 70 | Phase 1 输入最多使用模型窗口的 70% |
| `phase1FallbackTokenLimit` | 150_000 | 缺少模型窗口元数据时的 Phase 1 输入兜底 token 上限 |
| `phase2TriggerOnCandidateCount` | 5 | CLEAN candidate 累计到 5 个自动触发 Phase 2 |
| `phase2ScanIntervalMillis` | 86_400_000 | 24 小时兜底扫描 |
| `phase2MinIntervalMillis` | 3_600_000 | 两次 Phase 2 防抖 1 小时 |
| `phase2MaxCandidates` | 256 | 单次 Phase 2 选择候选上限 |
| `summaryTokenBudget` | 2_500 | read path 注入 summary 预算 |
| `maxRetries` | 3 | job 最大重试 |
| SECRET_RISK 触发 | redaction ≥ 3 或命中 `PRIVATE_KEY` / `URL_CREDENTIAL` / `AUTHORIZATION_HEADER` | 命中后 candidate 不进 Phase 2 |

## 关键代码挂点

新增包（完整列表见 plan §4.1）：

- `backend/src/main/resources/db/migration/V10__long_term_memory_pipeline.sql`
- `backend/src/main/java/com/wzx/babiq/server/memory/{model,repository,pipeline,redaction,artifact}/`
- 对应 `backend/src/main/java/com/wzx/babiq/server/persistence/entity/Memory*Entity.java` 和 mapper / SQLite adapter

主要修改入口和挂点理由：

| 文件 | P3-4 改什么 / 挂点理由 |
|---|---|
| `ContextWindowRuntime.prepare()` | 调用 `LongTermMemoryReadService` 拿 summary，写入 `ContextAssemblyInput.longTermMemoryRefs` |
| `ContextAssembler.assemble()` | 把 long_term_memory 层填进 `ContextEnvelope`，priority=REFERENCE |
| `ContextEnvelope` / `LongTermMemoryReference` | 如需要补字段（artifact_id、token_estimate）才动 |
| `AppSettingsService` | 接 `memory.generateEnabled` / `memory.readEnabled` 全局开关 |
| `ConversationRepository.listItems()` | Phase 1 取 thread 最近 items 的入口（只用，不要改语义）|
| `RunRecordService.getTurn()` | Phase 1 取 turn summary / tool calls 的入口 |
| `ContextSnapshotRepository` | Phase 1 关联 `source_snapshot_id` 用 |
| `ApproximateContextTokenEstimator` | Phase 1 输入预算 + summary 截断都复用它 |
| `AgentLoop` | **不需要改**；长期记忆完全经过 ContextWindowRuntime / ContextAssembler 注入 |

桌面端入口见 plan §5.2：默认只改协议模型和最小 UI，不做记忆编辑器。

## 必须保留的边界

- 不要把长期记忆写成普通 assistant message。
- 不要让 ChatMemory / MessageWindowChatMemory 成为事实源。
- 不要让模型直接写任意文件。
- 不要把完整长期记忆每轮塞进模型。
- 不要把 VectorStore/RAG 提前混进 P3-4。
- 不要只做 UI 开关；后端 AgentLoop 和 ContextAssembler 必须真实按设置变化。
- 不要恢复每轮 turn completed 立即模型抽取；必须遵守 idle scan、batch limit 和 provider/model 独立配置。

## 与 P3-3a 的衔接（回归红线）

P3-3a 刚完成短期压缩鲁棒性补强（commit `d41a123`），P3-4 实施时不要破坏以下基线：

- 不要改 `ContextAssembler` 中 `short_term_summary` 层的处理逻辑（P3-3 active summary 注入）。
- 不要改 `ContextCompactionService` / `WindowInstallRequest` / window ordinal CAS。
- 不要改 V8 / V9 migration；V10 只能 `ALTER TABLE ADD COLUMN` 或 `CREATE TABLE`。
- 跑 P3-4 测试时必须额外跑以下回归测试，确认 P3-3a 不退化：

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextAssemblerCompactionTest,ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,ContextCompactionRecoveryServiceTest" test
```

如果这几条测试失败，先停下来排查 P3-4 改动是否触动了 P3-3a 边界，再继续。

## 验证命令

计划要求的最终验证：

```powershell
cd backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest,MemoryRepositoryTest,MemorySettingsServiceTest,MemoryPollutionServiceTest,MemorySecretRedactorTest,LongTermMemoryPipelineTest,MemoryConsolidationServiceTest,ContextAssemblerLongTermMemoryTest,MemoryHandlersTest" test
.\mvnw.cmd "-Dtest=ContextAssemblerCompactionTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*MemoryModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest" --tests "*ComposerContextBarTest"
.\gradlew.bat test
```

如果测试类名在实现中有调整，必须在 handoff 中同步真实命令和结果。

## 完成报告必须包含

- Task 1-9 逐条完成状态（用 ✅/❌ 标注）
- 跑过的验证命令和实际输出（不是预期）
- SQLite 验证：查 `bq_memory_jobs`、`bq_memory_candidates`、`bq_memory_artifacts`、`bq_memory_references`，确认 `generation`、`pollution_status`、`selected_for_phase2`、`status`、`token_estimate` 都有真实值
- 手动烟测结果（plan §6 Task 9 列的 7 步）
- 默认配置实际生效证据，例如启动日志包含 "Phase 1 scan: N thread eligible" 或类似
- P3-3a 回归测试通过证据
- 中文 conventional commit 列表
- 明确说明未 push
- `@Disabled` 用例清单（理论上为空）
- 实施过程中对 plan 默认值或接口的偏离记录（如果有）

## 下一步

等待用户确认后，按 `plan.md` 从 Task 1 开始实现。实现前必须使用 `superpowers:executing-plans` 和 `superpowers:test-driven-development`。

### 实施期可能需要拍板的两个小决策

1. `memory/consolidate` 手动触发是否豁免 `phase2MinIntervalMillis` 防抖？当前 plan 没区分手动和自动；建议给 handler 增加 `force=true` 参数，手动入口豁免防抖（只保留"同时只有一个 RUNNING"的并发约束）。
2. `bq_memory_jobs.generation` 字段 SQL 类型：建议 `INTEGER` 允许 `NULL`，Phase 1 写 `NULL`，Phase 2 写 generation 编号。

如果实施时需要把这两点变更落进 plan，优先回 plan 修订再继续 Task。
