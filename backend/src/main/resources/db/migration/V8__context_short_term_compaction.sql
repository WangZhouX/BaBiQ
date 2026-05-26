-- P3-3 短期记忆与上下文压缩：保存压缩摘要和每次压缩尝试的审计记录。
-- 原始对话仍以 bq_items 为事实源，本迁移只增加“当前窗口可引用的摘要”和“压缩过程记录”。

-- 表：bq_context_summaries，保存由短期压缩策略生成的摘要正文。
CREATE TABLE IF NOT EXISTS bq_context_summaries (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层摘要 id，以 ctxsum_ 开头。
    summary_id TEXT NOT NULL UNIQUE,
    -- 所属会话 threadId。
    thread_id TEXT NOT NULL,
    -- 摘要覆盖的 item 范围，例如 it_1..it_8。
    source_item_range TEXT NOT NULL,
    -- 摘要覆盖的起点 item id。
    source_start_item_id TEXT,
    -- 摘要覆盖的终点 item id。
    source_end_item_id TEXT,
    -- 摘要正文，作为 short_term_summary 层注入模型。
    summary TEXT NOT NULL,
    -- 生成摘要时使用的 Provider id。
    provider_id TEXT,
    -- 生成摘要时使用的模型名。
    model TEXT,
    -- 摘要正文预估 token 数。
    estimated_tokens INTEGER NOT NULL DEFAULT 0,
    -- 摘要创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES bq_threads(thread_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_bq_context_summaries_summary_id
    ON bq_context_summaries(summary_id);
CREATE INDEX IF NOT EXISTS idx_bq_context_summaries_thread_created_at
    ON bq_context_summaries(thread_id, created_at DESC);

-- 表：bq_context_compactions，保存每一次自动压缩尝试。
CREATE TABLE IF NOT EXISTS bq_context_compactions (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层压缩尝试 id，以 ctxcmp_ 开头。
    compaction_id TEXT NOT NULL UNIQUE,
    -- 所属会话 threadId。
    thread_id TEXT NOT NULL,
    -- 触发压缩的 turnId。
    turn_id TEXT NOT NULL,
    -- 压缩状态：SUCCESS、SKIPPED 或 FAILED。
    status TEXT NOT NULL,
    -- 成功时生成的摘要 id，失败或跳过时为空。
    summary_id TEXT,
    -- 本次压缩覆盖的 item 范围。
    source_item_range TEXT,
    -- 本次压缩起点 item id。
    source_start_item_id TEXT,
    -- 本次压缩终点 item id。
    source_end_item_id TEXT,
    -- 压缩前本轮上下文预估 token。
    estimated_tokens_before INTEGER NOT NULL DEFAULT 0,
    -- 摘要正文预估 token，失败或跳过时为 0。
    estimated_tokens_after INTEGER NOT NULL DEFAULT 0,
    -- 失败或跳过原因，成功时为空。
    error_message TEXT,
    -- 审计记录创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES bq_threads(thread_id) ON DELETE CASCADE,
    FOREIGN KEY (turn_id) REFERENCES bq_turns(turn_id) ON DELETE CASCADE,
    FOREIGN KEY (summary_id) REFERENCES bq_context_summaries(summary_id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_bq_context_compactions_compaction_id
    ON bq_context_compactions(compaction_id);
CREATE INDEX IF NOT EXISTS idx_bq_context_compactions_thread_created_at
    ON bq_context_compactions(thread_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bq_context_compactions_turn_id
    ON bq_context_compactions(turn_id);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_context_summaries', '__table__', '保存短期上下文压缩生成的摘要正文，供当前窗口作为中优先级参考注入模型。'),
('bq_context_summaries', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_context_summaries', 'summary_id', '协议层摘要 id，以 ctxsum_ 开头。'),
('bq_context_summaries', 'thread_id', '所属会话 threadId，用于按会话查询和级联清理。'),
('bq_context_summaries', 'source_item_range', '摘要覆盖的 item 范围，例如 it_1..it_8。'),
('bq_context_summaries', 'source_start_item_id', '摘要覆盖的起点 item id，帮助判断哪些旧历史已被替换。'),
('bq_context_summaries', 'source_end_item_id', '摘要覆盖的终点 item id，后续新历史从它之后继续注入模型。'),
('bq_context_summaries', 'summary', '摘要正文，由压缩策略生成，作为 short_term_summary 层注入模型。'),
('bq_context_summaries', 'provider_id', '生成摘要时使用的 Provider id，便于审计模型来源。'),
('bq_context_summaries', 'model', '生成摘要时使用的模型名，便于审计和复现。'),
('bq_context_summaries', 'estimated_tokens', '摘要正文预估 token 数，用于观察压缩效果。'),
('bq_context_summaries', 'created_at', '摘要创建时间，使用 ISO-8601 文本保存。'),
('bq_context_compactions', '__table__', '保存每一次短期上下文自动压缩尝试，成功、跳过和失败都保留审计。'),
('bq_context_compactions', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_context_compactions', 'compaction_id', '协议层压缩尝试 id，以 ctxcmp_ 开头。'),
('bq_context_compactions', 'thread_id', '所属会话 threadId，用于按会话查看压缩历史。'),
('bq_context_compactions', 'turn_id', '触发压缩的 turnId，用于运行详情定位。'),
('bq_context_compactions', 'status', '压缩状态：SUCCESS、SKIPPED 或 FAILED。'),
('bq_context_compactions', 'summary_id', '成功时生成的摘要 id，失败或跳过时为空。'),
('bq_context_compactions', 'source_item_range', '本次压缩覆盖的 item 范围。'),
('bq_context_compactions', 'source_start_item_id', '本次压缩起点 item id。'),
('bq_context_compactions', 'source_end_item_id', '本次压缩终点 item id。'),
('bq_context_compactions', 'estimated_tokens_before', '压缩前本轮上下文预估 token。'),
('bq_context_compactions', 'estimated_tokens_after', '摘要正文预估 token，失败或跳过时为 0。'),
('bq_context_compactions', 'error_message', '失败或跳过原因，成功时为空。'),
('bq_context_compactions', 'created_at', '压缩审计记录创建时间，使用 ISO-8601 文本保存。');
