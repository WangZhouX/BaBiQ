-- P3-2 上下文窗口运行时：记录每个 thread 的当前窗口状态，以及每个 turn 发送给模型前的上下文快照。
-- 本迁移只建立 current window 和审计快照，不生成压缩摘要，也不改写聊天历史 item。

-- 表：bq_context_windows，保存 thread 级当前上下文窗口状态。
CREATE TABLE IF NOT EXISTS bq_context_windows (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 所属会话 threadId，一个 thread 只有一个当前窗口状态。
    thread_id TEXT NOT NULL UNIQUE,
    -- 当前窗口序号；P3-2 固定从 0 开始，P3-3 压缩成功后才递增。
    window_ordinal INTEGER NOT NULL DEFAULT 0,
    -- 当前窗口引用的短期摘要 id；P3-2 暂为空，预留给后续压缩摘要。
    active_summary_id TEXT,
    -- 当前模型上下文窗口 token 数，来自 Provider 配置或模型元数据。
    model_context_window INTEGER NOT NULL DEFAULT 0,
    -- 自动压缩阈值 token 数，P3-2 固定按模型窗口 70% 计算。
    auto_compact_threshold INTEGER NOT NULL DEFAULT 0,
    -- 最近一次模型调用前生成的上下文快照 id，供 UI 快速查看当前状态。
    last_snapshot_id TEXT,
    -- 窗口状态创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 窗口状态最近更新时间，每次生成新快照后刷新。
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_bq_context_windows_thread_id
    ON bq_context_windows(thread_id);
CREATE INDEX IF NOT EXISTS idx_bq_context_windows_updated_at
    ON bq_context_windows(updated_at DESC);

-- 表：bq_context_snapshots，保存每轮模型调用前实际装配出的上下文窗口审计快照。
CREATE TABLE IF NOT EXISTS bq_context_snapshots (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层快照 id，以 ctxsnap_ 开头，UI 和运行记录通过它定位快照。
    snapshot_id TEXT NOT NULL UNIQUE,
    -- 所属会话 threadId。
    thread_id TEXT NOT NULL,
    -- 所属 turnId；一个 turn 正常只产生一条 pre_model_call 快照。
    turn_id TEXT NOT NULL,
    -- 快照阶段；P3-2 固定为 pre_model_call，后续可扩展 post_compaction 等阶段。
    phase TEXT NOT NULL,
    -- 本轮使用的 Provider id，来自 turn/start 的运行快照。
    provider_id TEXT,
    -- 本轮使用的模型名，来自 Provider 配置或 active provider。
    model TEXT,
    -- 本轮工作目录快照，帮助审计 workspace facts 的来源。
    cwd TEXT,
    -- 生成快照时的窗口序号。
    window_ordinal INTEGER NOT NULL DEFAULT 0,
    -- 生成快照时采用的模型上下文窗口 token 数。
    model_context_window INTEGER NOT NULL DEFAULT 0,
    -- 生成快照时采用的自动压缩阈值 token 数。
    auto_compact_threshold INTEGER NOT NULL DEFAULT 0,
    -- 预估本轮注入模型的上下文 token 数，由 ContextTokenEstimator 计算。
    estimated_tokens INTEGER NOT NULL DEFAULT 0,
    -- 模型供应商返回的真实 prompt token；调用完成后由观测链路回填，缺失时为空。
    actual_prompt_tokens INTEGER,
    -- 被纳入模型输入的上下文片段数量。
    included_item_count INTEGER NOT NULL DEFAULT 0,
    -- 被明确排除但保留审计记录的上下文片段数量。
    excluded_item_count INTEGER NOT NULL DEFAULT 0,
    -- 分层上下文 envelope JSON，记录模型输入的结构化参考视图。
    envelope_json TEXT NOT NULL,
    -- 快照条目 JSON，记录 included/excluded、来源、原因和 token 预估。
    items_json TEXT NOT NULL,
    -- 能力目录摘要 JSON，只记录工具能力描述，不保存完整 tool schema。
    capability_catalog_json TEXT,
    -- 本轮用户输入预览，便于运行详情快速识别快照内容。
    input_preview TEXT,
    -- 快照创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES bq_threads(thread_id) ON DELETE CASCADE,
    FOREIGN KEY (turn_id) REFERENCES bq_turns(turn_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_bq_context_snapshots_snapshot_id
    ON bq_context_snapshots(snapshot_id);
CREATE INDEX IF NOT EXISTS idx_bq_context_snapshots_thread_id_created_at
    ON bq_context_snapshots(thread_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bq_context_snapshots_turn_id_created_at
    ON bq_context_snapshots(turn_id, created_at DESC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_context_windows', '__table__', '保存每个会话当前上下文窗口状态，供运行时、UI 和后续压缩策略读取。'),
('bq_context_windows', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_context_windows', 'thread_id', '所属会话 threadId，一个会话只保留一个当前窗口状态。'),
('bq_context_windows', 'window_ordinal', '当前窗口序号，P3-2 初始为 0，后续压缩成功后递增。'),
('bq_context_windows', 'active_summary_id', '当前窗口引用的短期摘要 id，P3-2 暂为空，预留给压缩阶段。'),
('bq_context_windows', 'model_context_window', '当前模型上下文窗口 token 数，来自 Provider 配置或模型元数据。'),
('bq_context_windows', 'auto_compact_threshold', '自动压缩阈值 token 数，P3-2 按模型窗口 70% 计算。'),
('bq_context_windows', 'last_snapshot_id', '最近一次模型调用前生成的上下文快照 id。'),
('bq_context_windows', 'created_at', '窗口状态创建时间，使用 ISO-8601 文本保存。'),
('bq_context_windows', 'updated_at', '窗口状态最近更新时间，每次生成新快照后刷新。'),
('bq_context_snapshots', '__table__', '保存每轮模型调用前的上下文窗口审计快照，说明模型实际看到哪些参考上下文。'),
('bq_context_snapshots', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_context_snapshots', 'snapshot_id', '协议层快照 id，以 ctxsnap_ 开头，UI 和运行记录通过它定位快照。'),
('bq_context_snapshots', 'thread_id', '所属会话 threadId，用于按会话查询最近上下文状态。'),
('bq_context_snapshots', 'turn_id', '所属 turnId，用于运行详情展示本轮上下文快照。'),
('bq_context_snapshots', 'phase', '快照阶段，P3-2 固定为 pre_model_call。'),
('bq_context_snapshots', 'provider_id', '本轮使用的 Provider id，来自 turn/start 的运行快照。'),
('bq_context_snapshots', 'model', '本轮使用的模型名，来自 Provider 配置或 active provider。'),
('bq_context_snapshots', 'cwd', '本轮工作目录快照，帮助审计 workspace facts 来源。'),
('bq_context_snapshots', 'window_ordinal', '生成快照时的窗口序号。'),
('bq_context_snapshots', 'model_context_window', '生成快照时采用的模型上下文窗口 token 数。'),
('bq_context_snapshots', 'auto_compact_threshold', '生成快照时采用的自动压缩阈值 token 数。'),
('bq_context_snapshots', 'estimated_tokens', '预估本轮注入模型的上下文 token 数，由 ContextTokenEstimator 计算。'),
('bq_context_snapshots', 'actual_prompt_tokens', '模型供应商返回的真实 prompt token，调用完成后由观测链路回填。'),
('bq_context_snapshots', 'included_item_count', '被纳入模型输入的上下文片段数量。'),
('bq_context_snapshots', 'excluded_item_count', '被明确排除但保留审计记录的上下文片段数量。'),
('bq_context_snapshots', 'envelope_json', '分层上下文 envelope JSON，记录模型输入的结构化参考视图。'),
('bq_context_snapshots', 'items_json', '快照条目 JSON，记录 included/excluded、来源、原因和 token 预估。'),
('bq_context_snapshots', 'capability_catalog_json', '能力目录摘要 JSON，只记录工具能力描述，不保存完整 tool schema。'),
('bq_context_snapshots', 'input_preview', '本轮用户输入预览，便于运行详情快速识别快照内容。'),
('bq_context_snapshots', 'created_at', '快照创建时间，使用 ISO-8601 文本保存。');
