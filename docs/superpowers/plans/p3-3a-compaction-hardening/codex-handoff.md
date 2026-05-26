# P3-3a 短期压缩鲁棒性补强 — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p3-3a-compaction-hardening\plan.md`

## 当前状态

- P3-3 短期压缩主链路已完成，当前补强轨 P3-3a 也已完成实现和验证。
- 本次先核对了 P3-3a 文档中的偏差判断：整体正确；唯一修正是文档原先写“8 个审计字段”，实际应为 10 个字段。
- P3-3a 不改变 P3-3 的设计边界：仍由 BaBiQ 维护 SQLite 审计和 active window 安装流程，Spring AI 承载摘要模型调用。

## 已完成内容

- 新增 `V9__context_compaction_audit_fields.sql`，为 `bq_context_compactions` 补齐 trigger、窗口 ordinal 血缘、快照血缘、预算审计和起止时间 10 个字段。
- 扩展 `ContextCompactionRecord` / `ContextCompactionEntity` / `SQLiteContextCompactionRepository`，并保留 P3-3 的 12 参数兼容构造器。
- 重构 `ContextCompactionService`：模型压缩调用保持在事务外；summary、compaction audit、active window 安装通过 `TransactionTemplate` 进入同一提交边界。
- 新增 `ContextWindowRepository.compareAndSwapOrdinal(...)`，SQLite adapter 使用 `WHERE thread_id=? AND window_ordinal=?` 做乐观安装。
- 新增 `ContextCompactionRecoveryService` 和 `CompactionRecoveryReport`，并接入 `RecoveryStartupRunner`。
- 补齐关键失败路径测试：SUCCESS、SKIPPED、FAILED、NOT_NEEDED、CONFLICT、runtime throws、CAS 失败和启动恢复。
- Task 6 决策：新增审计字段不暴露给桌面协议，当前桌面端不消费这些字段，因此无需改 Kotlin 协议模型。

## 验证结果

已通过：

```powershell
cd backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest,ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,AgentLoopContextRuntimeTest,ContextCompactionRecoveryServiceTest,ContextSnapshotPersistenceTest" test
```

结果：`BUILD SUCCESS`，18 个相关测试全部通过。

已通过：

```powershell
cd backend
.\mvnw.cmd clean verify
```

结果：`BUILD SUCCESS`。

已通过：

```powershell
cd desktop
.\gradlew.bat test
```

结果：`BUILD SUCCESSFUL`。

## 已知遗留

- 本次没有新增 `@Disabled` 测试。
- 未 push。
- 当前仓库中 `backend/src/main/resources/application.yml` 和 `learn/` 是本任务外已有变更，不纳入 P3-3a 提交。

## 下一步

P3-3a 验收后，继续按 P3 master plan 编写并确认 **P3-4 长期记忆异步流水线** 详细计划；不要直接开始长期记忆实现。
