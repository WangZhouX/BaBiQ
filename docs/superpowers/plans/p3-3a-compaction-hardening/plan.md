# P3-3a 短期压缩鲁棒性补强子计划

> **For agentic workers:** REQUIRED: 实施前使用 `superpowers:writing-plans` 复核本计划，实施时使用 `superpowers:executing-plans` 和 `superpowers:test-driven-development`，声称完成前使用 `superpowers:verification-before-completion`。
>
> **状态:** 已完成。本计划是对已完成的 P3-3 的"鲁棒性补强 + 字段对齐"，不引入新功能边界，也不重新设计 P3-3 的复用边界。

**Goal:** 补齐 P3-3 计划承诺但实施时被简化的 3 类能力，让短期压缩在**数据一致性、并发安全和审计回放**上达到原计划的鲁棒性目标。

**Architecture:** 不改变 P3-3 的"BaBiQ 维护审计 + Spring AI 承载摘要生成"分工。本阶段只做四件事：

1. V9 migration 给 `bq_context_compactions` 补 10 个字段（trigger、ordinal 血缘、快照血缘、预算审计、起止时间）
2. 把 summary 写入 + compaction 写入 + window 更新合并成一个事务边界（模型调用仍在事务外）
3. 在 `bq_context_windows` 更新时加 `window_ordinal` 乐观校验
4. 新增 `ContextCompactionRecoveryService`，启动时清理跨进程崩溃残留

**Tech Stack:** 同 P3-3。Java 21, Spring Boot 3.5.14, Spring AI 1.1.6, Spring AI Alibaba 1.1.2.3, SQLite, MyBatis-Plus, Flyway, Spring `TransactionTemplate`。

---

## 1. 偏差证据（来自 P3-3 实施审查）

### 1.1 偏差 A：`bq_context_compactions` 表字段被简化

P3-3 `plan.md` §4.2 明确承诺字段：

```
trigger_type, previous_window_ordinal, next_window_ordinal,
input_snapshot_id, replacement_snapshot_id,
model_context_window, effective_input_budget, auto_compact_threshold,
started_at, completed_at
```

实际 `V8__context_short_term_compaction.sql` 只有：

```
compaction_id, thread_id, turn_id, status, summary_id,
source_item_range, source_start_item_id, source_end_item_id,
estimated_tokens_before, estimated_tokens_after,
error_message, created_at
```

后果：
- 无法区分 `AUTO_PRE_TURN` / `MANUAL` / `FORCE_GUARD` 触发原因，调试很难
- 无法回放 input snapshot → replacement snapshot 血缘
- 无法做 window ordinal 乐观并发校验
- 缺少 started_at/completed_at 分别记录，意味着没法度量压缩耗时和检测"中间态卡死"

### 1.2 偏差 B：`ContextCompactionRecoveryService` 未实现

P3-3 `plan.md` §5 Task 8 明确承诺：
- 启动时扫描 `RUNNING` compaction → 标记 `FAILED`
- summary 已写入但 window 未更新时补偿安装

实际：文件不存在。当前同步 saveSummary + saveCompaction + windowRepository.upsert 三步不在同一事务里，进程在第 2 步与第 3 步之间崩溃会留下"summary 已存但 active window 未指向它"的不一致状态。

### 1.3 偏差 C：窗口 ordinal 缺少乐观锁

P3-3 `plan.md` §5 Task 5 明确承诺：
> "更新窗口时必须校验 `previous_window_ordinal`，避免并发 turn 覆盖新窗口"

实际 `ContextWindowRuntime.prepare()` 中：

```java
int windowOrdinal = existingWindow == null ? 0 : existingWindow.windowOrdinal();
if (compactionOutcome.compacted()) {
    windowOrdinal = windowOrdinal + 1;
}
windowRepository.upsert(windowRecord(...));   // ← 无 WHERE window_ordinal=? 校验
```

后果：同 thread 并发 turn 时可能丢失 ordinal 递增（实际单 thread 通常无并发 turn，但符号化 invariant 被破坏，且未来 Multi-Agent 引入后会变成真实风险）。

### 1.4 偏差 D：关键失败路径测试覆盖不足

- `ContextCompactionServiceTest`：1 个用例，**仅覆盖 strategy throws → FAILED 路径**，未覆盖 SUCCESS / SKIPPED / threshold-not-reached
- `ContextWindowRuntimeCompactionTest`：1 个用例（happy path），未覆盖压缩失败时 runtime 继续走未压缩快照的兜底
- `AgentLoopContextRuntimeTest`：1 个用例（happy path），未覆盖 runtime throws 和 HITL resume 路径

---

## 2. 修正范围

### 2.1 本计划必做

- 数据模型补字段（V9 migration + ContextCompactionRecord 扩展）
- 事务边界重构（保持模型调用在事务外，三表写入在同一事务内）
- 启动恢复服务（清理 SUCCESS 但未安装的孤儿摘要）
- 乐观并发校验（`UPDATE bq_context_windows ... WHERE window_ordinal = ?`）
- 关键失败路径补单元测试

### 2.2 本计划不做

- 不重新设计 P3-3 复用边界
- 不引入 SAA `SummarizationHook` / `ContextEditingInterceptor` 替换 Spring AI 策略（留给后续阶段评估）
- 不改 P3-1 envelope 结构、P3-2 ContextSnapshot 模型
- 不接入长期记忆（P3-4）或按需工具装配（P3-5）
- 不修改 V8 已经写入的旧数据迁移逻辑（V9 用 `ALTER TABLE ADD COLUMN` 增量添加，保留旧行的兼容默认值）

---

## 3. 数据库设计

### 3.1 V9 Migration

新增 migration：`V9__context_compaction_audit_fields.sql`

加字段（全部允许 NULL，旧数据兼容）：

```sql
-- 触发类型：AUTO_PRE_TURN / MANUAL / FORCE_GUARD
ALTER TABLE bq_context_compactions ADD COLUMN trigger_type TEXT;

-- 压缩前后的窗口序号，用于审计血缘和并发回放
ALTER TABLE bq_context_compactions ADD COLUMN previous_window_ordinal INTEGER;
ALTER TABLE bq_context_compactions ADD COLUMN next_window_ordinal INTEGER;

-- 快照血缘：input 是触发压缩时的 pre_model_call 快照；replacement 是压缩后下一次装配的快照
ALTER TABLE bq_context_compactions ADD COLUMN input_snapshot_id TEXT;
ALTER TABLE bq_context_compactions ADD COLUMN replacement_snapshot_id TEXT;

-- 预算审计：本轮模型窗口、有效输入预算、自动压缩阈值
ALTER TABLE bq_context_compactions ADD COLUMN model_context_window INTEGER;
ALTER TABLE bq_context_compactions ADD COLUMN effective_input_budget INTEGER;
ALTER TABLE bq_context_compactions ADD COLUMN auto_compact_threshold INTEGER;

-- 起止时间：started_at 在模型调用前写入；completed_at 在成功/失败安装后写入
ALTER TABLE bq_context_compactions ADD COLUMN started_at TEXT;
ALTER TABLE bq_context_compactions ADD COLUMN completed_at TEXT;

-- 旧数据填充：把已有 created_at 同时作为 started_at 和 completed_at
UPDATE bq_context_compactions
SET started_at = COALESCE(started_at, created_at),
    completed_at = COALESCE(completed_at, created_at),
    trigger_type = COALESCE(trigger_type, 'AUTO_PRE_TURN')
WHERE started_at IS NULL OR completed_at IS NULL OR trigger_type IS NULL;

-- 补 bq_schema_comments
INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_context_compactions', 'trigger_type', '压缩触发类型：AUTO_PRE_TURN 自动 pre-turn 触发，MANUAL 用户手动触发，FORCE_GUARD 极限保护触发。'),
('bq_context_compactions', 'previous_window_ordinal', '压缩前 bq_context_windows 的窗口序号；用于乐观并发校验和审计血缘。'),
('bq_context_compactions', 'next_window_ordinal', '压缩成功安装后的新窗口序号；失败或跳过时与 previous 相同。'),
('bq_context_compactions', 'input_snapshot_id', '触发压缩时的 pre_model_call 快照 id；用于回放本次压缩的输入上下文。'),
('bq_context_compactions', 'replacement_snapshot_id', '压缩成功后下一次装配生成的快照 id；用于核对替换效果。'),
('bq_context_compactions', 'model_context_window', '本次压缩判定时采用的模型窗口 token 数。'),
('bq_context_compactions', 'effective_input_budget', '本次压缩判定时的有效输入预算（去除输出预留和 safety margin）。'),
('bq_context_compactions', 'auto_compact_threshold', '本次压缩判定时的自动压缩阈值 token 数。'),
('bq_context_compactions', 'started_at', '压缩流程开始时间，ISO-8601；模型调用前写入。'),
('bq_context_compactions', 'completed_at', '压缩流程结束时间，ISO-8601；成功或失败终态后写入。');
```

### 3.2 ContextCompactionRecord 同步扩展

`ContextCompactionRecord` Java record 增加对应字段。所有新字段都使用包装类型（`Integer` 而非 `int`），允许 NULL 表示"未启用乐观锁/未启用快照血缘"等兼容场景。

---

## 4. 实施任务

### Task 1: V9 migration + record 扩展

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__context_compaction_audit_fields.sql`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextCompactionRecord.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextCompactionEntity.java`（如果存在 entity）
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextCompactionRepository.java`（如果存在 SQLite adapter）
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`

**Steps:**

- [ ] **Step 1：写 V9 migration**

  按 §3.1 落地，每个新字段都有 `--` 中文注释和 `bq_schema_comments` 插入。

- [ ] **Step 2：扩展 ContextCompactionRecord**

  在 record 末尾追加字段（Java record 改字段顺序会破坏所有 `new` 调用，但新加在末尾是兼容的）：

  ```java
  public record ContextCompactionRecord(
      String compactionId,
      String threadId,
      String turnId,
      String status,
      String summaryId,
      String sourceItemRange,
      String sourceStartItemId,
      String sourceEndItemId,
      int estimatedTokensBefore,
      int estimatedTokensAfter,
      String errorMessage,
      Instant createdAt,
      // ↓ 新增（P3-3a）
      String triggerType,
      Integer previousWindowOrdinal,
      Integer nextWindowOrdinal,
      String inputSnapshotId,
      String replacementSnapshotId,
      Integer modelContextWindow,
      Integer effectiveInputBudget,
      Integer autoCompactThreshold,
      Instant startedAt,
      Instant completedAt
  ) {
      /** 保留原有构造签名给 P3-3 老调用方使用；新字段默认 null。 */
      public ContextCompactionRecord(/* 原 12 个参数 */) {
          this(/* ... */, null, null, null, null, null, null, null, null, null, null);
      }
  }
  ```

- [ ] **Step 3：扩展 entity / SQLite adapter**

  把新字段映射到 V9 列。读写都走 `Integer` 包装类型，避免 SQLite NULL 转 0 的坑。

- [ ] **Step 4：跑 schema 覆盖测试**

  ```powershell
  cd backend
  .\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest" test
  ```

  Expected: PASS。

- [ ] **Step 5：commit**

  ```powershell
  git add backend/src/main/resources/db/migration/V9__context_compaction_audit_fields.sql backend/src/main/java/com/wzx/babiq/server/context/repository/ContextCompactionRecord.java backend/src/main/java/com/wzx/babiq/server/persistence
  git commit -m "feat(p3-3a): 扩展短期压缩审计字段"
  ```

---

### Task 2: 引入显式安装阶段，事务化三表写入

当前 `ContextCompactionService.compactIfNeeded(...)` 一个方法做了：
1. 判断是否压缩
2. 调模型（不应在事务内）
3. saveSummary
4. saveCompaction
5. 返回 outcome 给 caller，caller 自己 upsert window

3+4+window upsert 之间不在同一事务，是偏差 B 的根因。

**重构方向**：把 ContextCompactionService 拆成 "尝试 / 安装" 两段：

```java
// Step A：尝试压缩（包含模型调用，明确不在事务里）
public CompactionAttempt tryCompact(ContextCompactionRequest request) { ... }

// Step B：把尝试结果原子地落库（包含 summary + compaction + window）
@Transactional
public ContextCompactionOutcome installAttempt(CompactionAttempt attempt, WindowUpdate update) { ... }
```

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/compaction/CompactionAttempt.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/compaction/WindowInstallRequest.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextManualCompactionService.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/context/compaction/ContextCompactionServiceTest.java`

**Steps:**

- [ ] **Step 1：写失败测试覆盖事务原子性**

  使用 mock window repository，让 `windowRepository.upsert(...)` 在写入 summary 后抛异常。断言：
  - summary 没有被 commit（重新查询 returns null）
  - compaction record 没有被 commit
  - outcome 状态为 FAILED
  - active window 未变化

  > 注意：SQLite 在 Spring `@Transactional` 下需要确保 `DataSourceTransactionManager` 配置正确。如果当前没启用，需要在 Task 2 内补 `@EnableTransactionManagement` 或显式 bean。

- [ ] **Step 2：拆分 ContextCompactionService**

  - `tryCompact(...)`：负责"选择来源 / 调用 strategy / 估算 token / 构造 CompactionAttempt"。不写库。
  - `installAttempt(...)`：`@Transactional`，按顺序写 summary、compaction、window。任何一步抛异常都回滚。
  - 保留 `compactIfNeeded(...)` 作为兼容入口：内部串接 `tryCompact` + `installAttempt`，但显式把"模型调用"放在 transaction template 之外。

- [ ] **Step 3：让 ContextWindowRuntime 调新接口**

  从 `ContextWindowRuntime.prepare` 里移除"窗口 ordinal++ 后手动 upsert"逻辑，改为：
  - 调用 `compactionService.compactIfNeeded(request, WindowInstallRequest.of(...))`
  - 如果 outcome.compacted()，runtime 拿到的 outcome 已经包含 next_window_ordinal（事务内已写好）
  - runtime 用 outcome.nextWindowOrdinal() 构造本轮的 `ContextSnapshot.windowOrdinal`

- [ ] **Step 4：让 ContextManualCompactionService 调新接口**

  把当前手动压缩里"自己 upsert window"那段移走，改用统一 installAttempt。trigger_type 传 MANUAL。

- [ ] **Step 5：跑测试**

  ```powershell
  cd backend
  .\mvnw.cmd "-Dtest=ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,ContextWindowRuntimeTest" test
  ```

  Expected: PASS。

- [ ] **Step 6：commit**

  ```powershell
  git add backend/src/main/java/com/wzx/babiq/server/context/compaction backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java backend/src/test/java/com/wzx/babiq/server/context/compaction/ContextCompactionServiceTest.java
  git commit -m "feat(p3-3a): 压缩三表写入统一事务边界"
  ```

---

### Task 3: 加 window ordinal 乐观锁

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextWindowRepository.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextWindowRepository.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ContextWindowMapper.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionService.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/ContextSnapshotPersistenceTest.java`

**Steps:**

- [ ] **Step 1：repository 增加乐观更新方法**

  ```java
  public interface ContextWindowRepository {
      Optional<ContextWindowRecord> findByThreadId(String threadId);
      void upsert(ContextWindowRecord record);   // 保留：仅用于初次创建

      /**
       * 乐观锁更新：仅当当前 window_ordinal = expectedOrdinal 时才安装新窗口。
       *
       * @return true 如果安装成功；false 如果 ordinal 已被其他 turn 更新
       */
      boolean compareAndSwapOrdinal(String threadId,
                                    int expectedOrdinal,
                                    ContextWindowRecord nextRecord);
  }
  ```

- [ ] **Step 2：SQLite adapter 实现**

  Mapper 中执行：
  ```sql
  UPDATE bq_context_windows
  SET window_ordinal = #{next.windowOrdinal},
      active_summary_id = #{next.activeSummaryId},
      last_snapshot_id = #{next.lastSnapshotId},
      updated_at = #{next.updatedAt}
  WHERE thread_id = #{threadId}
    AND window_ordinal = #{expectedOrdinal}
  ```
  返回 affectedRows > 0。

- [ ] **Step 3：失败测试**

  并发场景模拟：两个调用都基于 ordinal=0 尝试 CAS 到 ordinal=1，预期只有一个 returns true，另一个 returns false。

- [ ] **Step 4：installAttempt 用 CAS**

  在 Task 2 的 `installAttempt(...)` 里，window 更新从 `upsert` 切到 `compareAndSwapOrdinal`：
  - CAS 失败 → 抛 `WindowOrdinalConflictException` → @Transactional 回滚 → outcome 状态置为 `CONFLICT`，failureReason="window_ordinal 被并发 turn 抢先更新"

- [ ] **Step 5：跑测试**

  ```powershell
  cd backend
  .\mvnw.cmd "-Dtest=ContextSnapshotPersistenceTest,ContextCompactionServiceTest" test
  ```

  Expected: PASS。

- [ ] **Step 6：commit**

  ```powershell
  git add backend/src/main/java/com/wzx/babiq/server/context/repository backend/src/main/java/com/wzx/babiq/server/persistence backend/src/main/java/com/wzx/babiq/server/context/compaction backend/src/test/java/com/wzx/babiq/server/persistence
  git commit -m "feat(p3-3a): 窗口安装加 ordinal 乐观锁"
  ```

---

### Task 4: 启动恢复服务

新增 `ContextCompactionRecoveryService`，在应用启动后异步扫描数据库，处理跨进程崩溃残留：

1. **场景 1（孤儿 summary）**：`bq_context_compactions` 有状态 `SUCCESS` 且 `summary_id` 非空，但对应 thread 的 `bq_context_windows.active_summary_id` 不等于该 summary_id。
   - 处理：把这条 compaction 状态改为 `ORPHANED`，写明 failure_reason，**不动 summary 行**（保留作为审计），不动 window。
   - 理由：进程崩在了 window CAS 之前；既然 window 没装，就让它保持原态，下一轮重新触发压缩即可。

2. **场景 2（半开始未完成）**：`bq_context_compactions` 有 `started_at` 非空但 `completed_at` 为空。
   - 处理：标记为 `INTERRUPTED`，写明 failure_reason。
   - 注意：Task 2 重构后，正常路径在事务里同时写 started_at 和 completed_at。半开状态只在事务前崩溃才会出现。

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionRecoveryService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/context/compaction/CompactionRecoveryReport.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryStartupRunner.java`（加一行调用 P3-3a recovery）
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/RunRecoveryStatusHandler.java`（如果需要把压缩恢复也加入 status）
- Test: `backend/src/test/java/com/wzx/babiq/server/context/compaction/ContextCompactionRecoveryServiceTest.java`

**Steps:**

- [ ] **Step 1：失败测试**

  - 用例 1：插入一条 `SUCCESS` compaction + summary，但 window 不指向它 → recovery 后 compaction 状态变 `ORPHANED`
  - 用例 2：插入一条 `SUCCESS` compaction + summary，且 window 正确指向它 → recovery 不动它
  - 用例 3：插入一条有 started_at 没 completed_at 的 compaction → recovery 后状态变 `INTERRUPTED`
  - 用例 4：空数据库 → recovery 安全返回空 report

- [ ] **Step 2：实现 service**

  - 只扫描最近 N 天（例如 30 天）的 compaction，避免历史数据膨胀
  - 全量遍历用流式查询；不在内存里全 join
  - 每条记录单独事务处理，单条失败不影响后续

- [ ] **Step 3：接入启动钩子**

  在 P2-4 已有的 `RecoveryStartupRunner` 里追加 `compactionRecoveryService.scan()` 调用。如果该 runner 现在是 `@PostConstruct` 或 `ApplicationReadyEvent`，保持同一时机。

- [ ] **Step 4：测试**

  ```powershell
  cd backend
  .\mvnw.cmd "-Dtest=ContextCompactionRecoveryServiceTest,RunRecordServiceTest" test
  ```

  Expected: PASS。

- [ ] **Step 5：commit**

  ```powershell
  git add backend/src/main/java/com/wzx/babiq/server/context/compaction/ContextCompactionRecoveryService.java backend/src/main/java/com/wzx/babiq/server/context/compaction/CompactionRecoveryReport.java backend/src/main/java/com/wzx/babiq/server/recovery backend/src/test/java/com/wzx/babiq/server/context/compaction
  git commit -m "feat(p3-3a): 启动恢复压缩崩溃残留"
  ```

---

### Task 5: 补关键失败路径测试

**Files:**
- Modify: `backend/src/test/java/com/wzx/babiq/server/context/compaction/ContextCompactionServiceTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeCompactionTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopContextRuntimeTest.java`

**Steps:**

- [ ] **Step 1：ContextCompactionServiceTest 补 4 个用例**

  - SUCCESS 路径：strategy 返回非空 summary → outcome status=SUCCESS、summaryRepository 被 save、compactionRepository 被 save、trigger_type=AUTO_PRE_TURN、started_at/completed_at 都已设置
  - SKIPPED 路径：source 为空 → outcome status=SKIPPED、summaryRepository 不被 save
  - 空摘要 FAILED 路径：strategy 返回空字符串 → status=FAILED、reason="压缩策略返回空摘要"
  - threshold-not-reached 路径：estimatedTokens 远低于 threshold → outcome status=NOT_NEEDED、什么都不写

- [ ] **Step 2：ContextWindowRuntimeCompactionTest 补 2 个用例**

  - 压缩失败时 runtime 应继续使用未压缩快照：mock strategy 抛异常 → prepare 仍 returns ContextWindowRuntimeResult，windowOrdinal 保持原值，emitter 不发 ContextCompactionItem
  - 压缩 SUCCESS 但 window CAS 失败：mock window CAS returns false → outcome status=CONFLICT，windowOrdinal 不变

- [ ] **Step 3：AgentLoopContextRuntimeTest 补 2 个用例**

  - runtime throws 时 turn 进入 FAILED：mock runtime.prepare 抛异常 → AgentLoop 用 AgentLoopSupport.fail，turn 状态=FAILED，没调 agent.stream
  - HITL resume 不重复 recordUsage：模拟 approval resume 路径 → recordUsage 不被 second-call（这条用例可能需要重构 AgentLoopResumeSupport 才能测；如果实现复杂，本计划允许只加一个 `@Disabled("p3-3a: 暂未抽 resume mock seam")` 占位**并在 codex-handoff 里明记**）

  > **不允许把这个 `@Disabled` 留过 P3-4。** 如果 P3-3a 完成时该用例仍是 disabled，必须在 P3-4 启动前补上。

- [ ] **Step 4：跑测试**

  ```powershell
  cd backend
  .\mvnw.cmd "-Dtest=ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,AgentLoopContextRuntimeTest" test
  ```

  Expected: PASS。

- [ ] **Step 5：commit**

  ```powershell
  git add backend/src/test/java/com/wzx/babiq/server/context backend/src/test/java/com/wzx/babiq/server/agent
  git commit -m "test(p3-3a): 补关键失败路径测试"
  ```

---

### Task 6: 桌面端协议字段（按需）

如果新字段需要在桌面端展示（`triggerType`、压缩耗时 = completedAt - startedAt 等），桌面端协议要同步：

**Files（评估后决定）:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ContextModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/RunRecordModels.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ContextModelsTest.kt`

**Steps:**

- [ ] **Step 1：决策**

  审查 `run/turn/get`、`context/status` 当前是否返回 compaction 详情：
  - 如果**返回**：必须给新字段加 nullable Kotlin 属性，否则 kotlinx.serialization 反序列化会失败
  - 如果**不返回**：暂时不动桌面端，加测试用例覆盖"忽略未知字段"即可

- [ ] **Step 2：按决策实施 + 测试**

- [ ] **Step 3：commit**

  ```powershell
  git add desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol
  git commit -m "feat(p3-3a): 桌面端识别新增压缩审计字段"
  ```

---

### Task 7: 文档同步

**Files:**
- Modify: `docs/superpowers/plans/p3-master.md`（§4 表格里 P3-3 状态加注：P3-3a 已完成补强）
- Modify: `docs/superpowers/plans/p3-task-index.md`
- Modify: `docs/superpowers/plans/p3-3-short-term-compaction/plan.md`（§9 实施结果里追加"P3-3a 修正记录"段，链接到本计划）
- Create: `docs/superpowers/plans/p3-3a-compaction-hardening/codex-handoff.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

**Steps:**

- [ ] **Step 1：写 codex-handoff**

  记录：
  - 已完成的字段补齐 / 事务化 / 乐观锁 / 启动恢复
  - 验证命令和结果
  - 已知遗留（如果有 disabled 用例）

- [ ] **Step 2：更新两个主入口**

  AGENTS.md 和 CLAUDE.md 的"当前检查点"区里 P3-3 段下追加：
  > P3-3a 已完成：补齐压缩审计字段、事务化三表写入、ordinal 乐观锁、启动恢复服务、补足关键失败路径测试。

- [ ] **Step 3：commit**

  ```powershell
  git add docs/superpowers/plans AGENTS.md CLAUDE.md
  git commit -m "docs(p3-3a): 同步压缩鲁棒性补强状态"
  ```

---

## 5. 验证

### 5.1 后端专项（先跑这个，快速发现问题）

```powershell
cd backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest,ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,AgentLoopContextRuntimeTest,ContextCompactionRecoveryServiceTest,ContextSnapshotPersistenceTest" test
```

预期：全绿。

### 5.2 后端全量

```powershell
cd backend
.\mvnw.cmd clean verify
```

预期：BUILD SUCCESS，整体测试数比 P3-3 完成时多 ≥ 10 个。

### 5.3 桌面端（如果 Task 6 改了协议）

```powershell
cd desktop
.\gradlew.bat test
```

### 5.4 人工烟测

1. 启动后端 + 桌面端
2. 让一个 thread 跑到接近自动压缩阈值（可以通过临时把 ContextBudgetProperties.autoCompactRatio 调到 0.10 触发）
3. 看到 ContextCompactionItem，记录 `compaction_id`
4. 直接查 SQLite：
   ```sql
   SELECT compaction_id, status, trigger_type, previous_window_ordinal,
          next_window_ordinal, input_snapshot_id, replacement_snapshot_id,
          started_at, completed_at
   FROM bq_context_compactions ORDER BY id DESC LIMIT 5;
   ```
   预期：所有新字段都有值。
5. 重启后端 → 看启动日志里是否有 "context compaction recovery scan: 0 records cleaned"
6. 模拟崩溃残留：手动在 SQLite 里插入一条 status='SUCCESS' 但 active_summary_id 不匹配的 compaction，重启后端 → 该 compaction 应变成 ORPHANED

---

## 6. 风险与处理

| 风险 | 严重度 | 处理 |
|---|---|---|
| Spring `@Transactional` 在 SQLite 上的 isolation 行为不如 Postgres 严格 | 中 | 测试用 H2 in-memory 或 Testcontainers 跑事务回滚；不要只在 mock 上测。SQLite 默认 SERIALIZABLE 是足够的，但要确认 Mybatis-Plus 的事务传播行为 |
| V9 ALTER TABLE 在 SQLite 下并不快，迁移 1M 行数据会卡 | 低 | BaBiQ 数据量很小（学习项目），不是问题。如果未来引入大规模，再考虑 backfill 脚本 |
| 旧的 P3-3 调用方（含外部测试 fixture）依赖 ContextCompactionRecord 的 12 参数构造器 | 中 | 必须保留兼容构造器；Task 1 Step 2 明确写出 |
| Task 2 重构后 `ContextCompactionService.compactIfNeeded` 行为对老测试不兼容 | 中 | 老测试通过 Step 5 修补，不能直接删 |
| AgentLoopResumeSupport 的 HITL resume 路径耦合较深，Task 5 Step 3 可能需要先拆 seam | 中 | 允许该用例临时 `@Disabled`，但必须在 P3-4 前补上 |
| CAS 失败时如何 graceful degrade（让 turn 直接走 UNCOMPACTED）还是直接 turn FAILED | 中 | 计划决策：走 UNCOMPACTED（CAS 失败属于"窗口被并发抢先安装"，本轮可以用旧窗口继续，下一轮再判断压缩需要） |

---

## 7. 完成标准

P3-3a 完成必须同时满足：

- [x] `V9__context_compaction_audit_fields.sql` 已落地，且 `SchemaCommentsCoverageTest` 通过
- [x] `ContextCompactionRecord` 已扩展为 22 字段，保留 12 参数兼容构造器
- [x] `ContextCompactionService` 已把模型调用放在事务外，并用 `TransactionTemplate` 包住 summary + compaction + window 安装
- [x] `ContextWindowRepository.compareAndSwapOrdinal(...)` 已实现，SQLite adapter 真正执行带 WHERE 条件的 UPDATE 并检查 affected rows
- [x] `ContextCompactionRecoveryService` 存在并接入启动 runner
- [x] 失败路径测试覆盖：SUCCESS / SKIPPED / FAILED / NOT_NEEDED / CONFLICT / runtime throws / window CAS 失败 / 启动恢复孤儿
- [x] 后端 `clean verify` 全绿
- [x] 桌面端测试全绿；Task 6 决策为未修改桌面协议
- [x] 文档同步：P3-3 plan.md 末尾追加 P3-3a 链接；AGENTS.md / CLAUDE.md 更新检查点；codex-handoff.md 写完
- [x] 中文 conventional commit，未 push

---

## 8. 实施结果

P3-3a 已完成鲁棒性补强：

- V9 为 `bq_context_compactions` 补齐 10 个审计字段，并同步 SQL 中文注释和 `bq_schema_comments`。
- `ContextCompactionService` 拆出压缩尝试和安装阶段：摘要模型调用在事务外，summary、compaction audit、active window 安装在事务模板内完成。
- `ContextWindowRepository.compareAndSwapOrdinal(...)` 已接入运行时和手动压缩，CAS 冲突会记录 `CONFLICT`，本轮继续使用未压缩上下文。
- 新增 `ContextCompactionRecoveryService` 和 `CompactionRecoveryReport`，启动时会把半完成记录标记为 `INTERRUPTED`，把未安装的成功记录标记为 `ORPHANED`。
- 补齐关键失败路径测试：未达阈值、空来源、空摘要、策略异常、CAS 冲突、runtime 异常和启动恢复。
- Task 6 决策：本次没有把新增审计字段暴露到桌面协议，kotlinx serialization 当前模型不会消费这些字段，因此无需改桌面端。

已验证：

```powershell
cd backend
.\mvnw.cmd "-Dtest=SchemaCommentsCoverageTest,ContextCompactionServiceTest,ContextWindowRuntimeCompactionTest,AgentLoopContextRuntimeTest,ContextCompactionRecoveryServiceTest,ContextSnapshotPersistenceTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

---

## 9. 下一步

P3-3a 完成且用户验收后，按 P3 master plan 进入 **P3-4 长期记忆异步流水线** 详细计划编写。P3-3a 不顺延 P3-3，是与 P3-4 平行的修复轨。
