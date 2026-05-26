-- P3-3a 短期压缩鲁棒性补强：为压缩审计记录补齐触发来源、窗口血缘、快照血缘、预算和起止时间。
-- 本迁移只增量扩展 bq_context_compactions，不改写 V8 已有表结构和旧行语义。

-- 压缩触发类型：AUTO_PRE_TURN 自动 pre-turn，MANUAL 用户手动，FORCE_GUARD 极限保护。
ALTER TABLE bq_context_compactions ADD COLUMN trigger_type TEXT;

-- 压缩前的窗口序号，用于乐观并发校验和审计回放。
ALTER TABLE bq_context_compactions ADD COLUMN previous_window_ordinal INTEGER;

-- 压缩成功安装后的窗口序号；失败、跳过或冲突时可为空或等于 previous。
ALTER TABLE bq_context_compactions ADD COLUMN next_window_ordinal INTEGER;

-- 触发压缩时用于估算的输入快照 id，方便回放压缩来源。
ALTER TABLE bq_context_compactions ADD COLUMN input_snapshot_id TEXT;

-- 压缩成功后替换窗口指向的快照 id，方便核对替换效果。
ALTER TABLE bq_context_compactions ADD COLUMN replacement_snapshot_id TEXT;

-- 本次压缩判断时采用的模型上下文窗口 token 数。
ALTER TABLE bq_context_compactions ADD COLUMN model_context_window INTEGER;

-- 本次压缩判断时的有效输入预算 token 数。
ALTER TABLE bq_context_compactions ADD COLUMN effective_input_budget INTEGER;

-- 本次压缩判断时的自动压缩阈值 token 数。
ALTER TABLE bq_context_compactions ADD COLUMN auto_compact_threshold INTEGER;

-- 压缩尝试开始时间，通常在调用摘要模型前生成。
ALTER TABLE bq_context_compactions ADD COLUMN started_at TEXT;

-- 压缩尝试结束时间，成功、跳过、失败或冲突终态后生成。
ALTER TABLE bq_context_compactions ADD COLUMN completed_at TEXT;

-- 旧数据兼容：V8 只有 created_at，迁移后把它作为起止时间的保守默认值。
UPDATE bq_context_compactions
SET started_at = COALESCE(started_at, created_at),
    completed_at = COALESCE(completed_at, created_at),
    trigger_type = COALESCE(trigger_type, 'AUTO_PRE_TURN')
WHERE started_at IS NULL OR completed_at IS NULL OR trigger_type IS NULL;

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_context_compactions', 'trigger_type', '压缩触发类型：AUTO_PRE_TURN 自动 pre-turn 触发，MANUAL 用户手动触发，FORCE_GUARD 极限保护触发。'),
('bq_context_compactions', 'previous_window_ordinal', '压缩前 bq_context_windows 的窗口序号；用于乐观并发校验和审计血缘。'),
('bq_context_compactions', 'next_window_ordinal', '压缩成功安装后的新窗口序号；失败、跳过或冲突时可为空或等于 previous。'),
('bq_context_compactions', 'input_snapshot_id', '触发压缩时用于估算的输入快照 id；用于回放本次压缩的输入上下文。'),
('bq_context_compactions', 'replacement_snapshot_id', '压缩成功后替换窗口指向的快照 id；用于核对摘要安装后的上下文视图。'),
('bq_context_compactions', 'model_context_window', '本次压缩判定时采用的模型窗口 token 数。'),
('bq_context_compactions', 'effective_input_budget', '本次压缩判定时的有效输入预算 token 数。'),
('bq_context_compactions', 'auto_compact_threshold', '本次压缩判定时的自动压缩阈值 token 数。'),
('bq_context_compactions', 'started_at', '压缩流程开始时间，ISO-8601；通常在模型调用前写入。'),
('bq_context_compactions', 'completed_at', '压缩流程结束时间，ISO-8601；成功、跳过、失败或冲突终态后写入。');
