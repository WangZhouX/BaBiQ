-- P3-4 长期记忆异步流水线：新增 Phase1/Phase2 job、候选、产物和读取引用表。
-- 事实源仍然是 SQLite；Markdown 文件只是由 bq_memory_artifacts 派生出的可读镜像。

-- 会话长期记忆模式；ENABLED 表示允许长期记忆读写，DISABLED 表示该 thread 完全关闭长期记忆。
ALTER TABLE bq_threads ADD COLUMN memory_mode TEXT NOT NULL DEFAULT 'ENABLED';
-- 会话被标记为上下文污染的原因；为空表示未发现需要阻断长期记忆的污染。
ALTER TABLE bq_threads ADD COLUMN memory_polluted_reason TEXT;
-- 会话被标记污染的时间；为空表示未标记污染。
ALTER TABLE bq_threads ADD COLUMN memory_polluted_at TEXT;

-- 快照中实际注入的长期记忆引用 JSON；由 ContextWindowRuntime 在模型调用前写入。
ALTER TABLE bq_context_snapshots ADD COLUMN long_term_memory_refs_json TEXT;
-- 快照中长期记忆部分的 token 估算；用于审计 read path 预算。
ALTER TABLE bq_context_snapshots ADD COLUMN long_term_memory_token_estimate INTEGER NOT NULL DEFAULT 0;

-- 表：bq_memory_jobs，保存长期记忆后台任务。
CREATE TABLE IF NOT EXISTS bq_memory_jobs (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层任务 id，以 memjob_ 开头。
    job_id TEXT NOT NULL UNIQUE,
    -- 任务类型，PHASE1 表示抽取，PHASE2 表示全局归并。
    job_type TEXT NOT NULL,
    -- 去重键；Phase2 使用 phase2:{generation}，保留每次归并历史。
    job_key TEXT NOT NULL UNIQUE,
    -- Phase2 generation；Phase1 可为 0。
    generation INTEGER NOT NULL DEFAULT 0,
    -- 来源 thread id；Phase2 全局任务为空。
    thread_id TEXT,
    -- 来源 turn id；Phase1 可记录最近 turn，Phase2 为空。
    turn_id TEXT,
    -- 任务状态，PENDING、RUNNING、SUCCEEDED、FAILED。
    status TEXT NOT NULL,
    -- 当前 worker id；只有 RUNNING 任务通常非空。
    worker_id TEXT,
    -- worker 租约过期时间；超时后调度器可重新认领。
    lease_until TEXT,
    -- 当前重试次数。
    retry_count INTEGER NOT NULL DEFAULT 0,
    -- 最大重试次数。
    max_retries INTEGER NOT NULL DEFAULT 3,
    -- 输入水位线，例如 thread updated_at。
    input_watermark TEXT,
    -- 失败原因摘要。
    error_message TEXT,
    -- 任务创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 任务开始执行时间。
    started_at TEXT,
    -- 任务完成时间。
    completed_at TEXT,
    -- 任务最近更新时间。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_memory_jobs_status_lease_until
    ON bq_memory_jobs(status, lease_until);
CREATE INDEX IF NOT EXISTS idx_bq_memory_jobs_thread_turn
    ON bq_memory_jobs(thread_id, turn_id);
CREATE INDEX IF NOT EXISTS idx_bq_memory_jobs_created_at
    ON bq_memory_jobs(created_at DESC);

-- 表：bq_memory_candidates，保存 Phase1 抽取后的长期记忆候选。
CREATE TABLE IF NOT EXISTS bq_memory_candidates (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层候选 id，以 memcand_ 开头。
    candidate_id TEXT NOT NULL UNIQUE,
    -- 来源 thread id。
    thread_id TEXT NOT NULL,
    -- 来源 turn id，可为空。
    turn_id TEXT,
    -- 生成候选的 Phase1 job id。
    job_id TEXT,
    -- 来源工作目录。
    cwd TEXT,
    -- 抽取时使用的 Provider id。
    provider_id TEXT,
    -- 抽取时使用的模型名。
    model TEXT,
    -- 脱敏后的原始长期记忆正文。
    raw_memory TEXT NOT NULL,
    -- 该 rollout 片段摘要；镜像器会直接写入 rollout_summaries。
    rollout_summary TEXT,
    -- rollout_summaries 文件名 slug。
    rollout_slug TEXT,
    -- 来源 item id JSON 数组。
    source_item_ids_json TEXT,
    -- 来源上下文快照 id。
    source_snapshot_id TEXT,
    -- 污染状态，CLEAN 才允许进入 Phase2。
    pollution_status TEXT NOT NULL DEFAULT 'CLEAN',
    -- 脱敏命中次数。
    redaction_count INTEGER NOT NULL DEFAULT 0,
    -- 是否已被 Phase2 选中。
    selected_for_phase2 INTEGER NOT NULL DEFAULT 0,
    -- 被选中时间。
    selected_at TEXT,
    -- read path 引用次数，用于后续排序。
    usage_count INTEGER NOT NULL DEFAULT 0,
    -- 最近 read path 引用时间。
    last_used_at TEXT,
    -- 候选创建时间。
    created_at TEXT NOT NULL,
    -- 候选更新时间。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_memory_candidates_clean_unmerged
    ON bq_memory_candidates(pollution_status, selected_for_phase2, usage_count DESC, last_used_at DESC, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bq_memory_candidates_thread_created_at
    ON bq_memory_candidates(thread_id, created_at DESC);

-- 表：bq_memory_artifacts，保存长期记忆产物及其 Markdown 镜像路径。
CREATE TABLE IF NOT EXISTS bq_memory_artifacts (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层产物 id，以 memart_ 开头。
    artifact_id TEXT NOT NULL UNIQUE,
    -- 产物类型，例如 MEMORY_SUMMARY、MEMORY_HANDBOOK、RAW_MEMORIES、ROLLOUT_SUMMARY。
    artifact_type TEXT NOT NULL,
    -- Markdown 镜像路径，相对 rootDir 保存。
    artifact_path TEXT NOT NULL,
    -- 文件内容 hash，用于后续判断镜像漂移。
    content_hash TEXT NOT NULL,
    -- 产物版本，通常等于 Phase2 generation。
    version INTEGER NOT NULL,
    -- 来源 Phase2 job id。
    source_job_id TEXT,
    -- 选中候选 id JSON 数组。
    candidate_ids_json TEXT,
    -- 摘要或文件正文；read path 只读取 MEMORY_SUMMARY。
    summary_text TEXT,
    -- 文本 token 估算。
    token_estimate INTEGER NOT NULL DEFAULT 0,
    -- 产物创建时间。
    created_at TEXT NOT NULL,
    -- 产物更新时间。
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_bq_memory_artifacts_type_version
    ON bq_memory_artifacts(artifact_type, version);
CREATE INDEX IF NOT EXISTS idx_bq_memory_artifacts_created_at
    ON bq_memory_artifacts(created_at DESC);

-- 表：bq_memory_references，保存每轮上下文窗口引用过的长期记忆。
CREATE TABLE IF NOT EXISTS bq_memory_references (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层引用 id，以 memref_ 开头。
    reference_id TEXT NOT NULL UNIQUE,
    -- 当前 thread id。
    thread_id TEXT NOT NULL,
    -- 当前 turn id。
    turn_id TEXT NOT NULL,
    -- 当前上下文快照 id。
    snapshot_id TEXT NOT NULL,
    -- 被引用的产物 id。
    artifact_id TEXT NOT NULL,
    -- 被引用的候选 id；summary 级引用通常为空。
    candidate_id TEXT,
    -- 引用类型，例如 SUMMARY。
    reference_type TEXT NOT NULL,
    -- 本次引用 token 估算。
    token_estimate INTEGER NOT NULL DEFAULT 0,
    -- 引用创建时间。
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_memory_references_thread_turn
    ON bq_memory_references(thread_id, turn_id);
CREATE INDEX IF NOT EXISTS idx_bq_memory_references_snapshot_id
    ON bq_memory_references(snapshot_id);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_threads', 'memory_mode', '会话长期记忆模式；ENABLED 允许读写，DISABLED 表示该会话关闭长期记忆。'),
('bq_threads', 'memory_polluted_reason', '会话被标记为上下文污染的原因；为空表示未发现需要阻断长期记忆的污染。'),
('bq_threads', 'memory_polluted_at', '会话被标记为上下文污染的时间；为空表示未标记污染。'),
('bq_context_snapshots', 'long_term_memory_refs_json', '快照中实际注入的长期记忆引用 JSON；由 ContextWindowRuntime 在模型调用前写入。'),
('bq_context_snapshots', 'long_term_memory_token_estimate', '快照中长期记忆部分的 token 估算；用于审计 read path 预算。'),
('bq_memory_jobs', '__table__', '保存长期记忆 Phase1/Phase2 后台任务，提供异步执行、租约和审计历史。'),
('bq_memory_jobs', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_memory_jobs', 'job_id', '协议层任务 id，以 memjob_ 开头。'),
('bq_memory_jobs', 'job_type', '任务类型，PHASE1 表示抽取，PHASE2 表示全局归并。'),
('bq_memory_jobs', 'job_key', '任务去重键；Phase2 使用 phase2:{generation}，保留每次归并历史。'),
('bq_memory_jobs', 'generation', 'Phase2 generation；Phase1 可为 0。'),
('bq_memory_jobs', 'thread_id', '来源 thread id；Phase2 全局任务为空。'),
('bq_memory_jobs', 'turn_id', '来源 turn id；Phase1 可记录最近 turn，Phase2 为空。'),
('bq_memory_jobs', 'status', '任务状态，PENDING、RUNNING、SUCCEEDED、FAILED。'),
('bq_memory_jobs', 'worker_id', '当前 worker id；只有 RUNNING 任务通常非空。'),
('bq_memory_jobs', 'lease_until', 'worker 租约过期时间；超时后调度器可重新认领。'),
('bq_memory_jobs', 'retry_count', '当前重试次数。'),
('bq_memory_jobs', 'max_retries', '最大重试次数。'),
('bq_memory_jobs', 'input_watermark', '输入水位线，例如 thread updated_at。'),
('bq_memory_jobs', 'error_message', '失败原因摘要。'),
('bq_memory_jobs', 'created_at', '任务创建时间，ISO-8601 文本。'),
('bq_memory_jobs', 'started_at', '任务开始执行时间。'),
('bq_memory_jobs', 'completed_at', '任务完成时间。'),
('bq_memory_jobs', 'updated_at', '任务最近更新时间。'),
('bq_memory_candidates', '__table__', '保存 Phase1 抽取后的长期记忆候选，Phase2 只选择 CLEAN 且未归并的候选。'),
('bq_memory_candidates', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_memory_candidates', 'candidate_id', '协议层候选 id，以 memcand_ 开头。'),
('bq_memory_candidates', 'thread_id', '来源 thread id。'),
('bq_memory_candidates', 'turn_id', '来源 turn id，可为空。'),
('bq_memory_candidates', 'job_id', '生成候选的 Phase1 job id。'),
('bq_memory_candidates', 'cwd', '来源工作目录。'),
('bq_memory_candidates', 'provider_id', '抽取时使用的 Provider id。'),
('bq_memory_candidates', 'model', '抽取时使用的模型名。'),
('bq_memory_candidates', 'raw_memory', '脱敏后的原始长期记忆正文。'),
('bq_memory_candidates', 'rollout_summary', '该 rollout 片段摘要；镜像器会直接写入 rollout_summaries。'),
('bq_memory_candidates', 'rollout_slug', 'rollout_summaries 文件名 slug。'),
('bq_memory_candidates', 'source_item_ids_json', '来源 item id JSON 数组。'),
('bq_memory_candidates', 'source_snapshot_id', '来源上下文快照 id。'),
('bq_memory_candidates', 'pollution_status', '污染状态，CLEAN 才允许进入 Phase2。'),
('bq_memory_candidates', 'redaction_count', '脱敏命中次数。'),
('bq_memory_candidates', 'selected_for_phase2', '是否已被 Phase2 选中。'),
('bq_memory_candidates', 'selected_at', '被选中进入 Phase2 的时间。'),
('bq_memory_candidates', 'usage_count', 'read path 引用次数，用于后续排序。'),
('bq_memory_candidates', 'last_used_at', '最近 read path 引用时间。'),
('bq_memory_candidates', 'created_at', '候选创建时间。'),
('bq_memory_candidates', 'updated_at', '候选更新时间。'),
('bq_memory_artifacts', '__table__', '保存长期记忆产物及其 Markdown 镜像路径；SQLite 仍是事实源。'),
('bq_memory_artifacts', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_memory_artifacts', 'artifact_id', '协议层产物 id，以 memart_ 开头。'),
('bq_memory_artifacts', 'artifact_type', '产物类型，例如 MEMORY_SUMMARY、MEMORY_HANDBOOK、RAW_MEMORIES、ROLLOUT_SUMMARY。'),
('bq_memory_artifacts', 'artifact_path', 'Markdown 镜像路径，相对 rootDir 保存。'),
('bq_memory_artifacts', 'content_hash', '文件内容 hash，用于后续判断镜像漂移。'),
('bq_memory_artifacts', 'version', '产物版本，通常等于 Phase2 generation。'),
('bq_memory_artifacts', 'source_job_id', '来源 Phase2 job id。'),
('bq_memory_artifacts', 'candidate_ids_json', '选中候选 id JSON 数组。'),
('bq_memory_artifacts', 'summary_text', '摘要或文件正文；read path 只读取 MEMORY_SUMMARY。'),
('bq_memory_artifacts', 'token_estimate', '文本 token 估算。'),
('bq_memory_artifacts', 'created_at', '产物创建时间。'),
('bq_memory_artifacts', 'updated_at', '产物更新时间。'),
('bq_memory_references', '__table__', '保存每轮上下文窗口引用过的长期记忆，便于审计模型看到了哪些 memory_summary。'),
('bq_memory_references', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_memory_references', 'reference_id', '协议层引用 id，以 memref_ 开头。'),
('bq_memory_references', 'thread_id', '当前 thread id。'),
('bq_memory_references', 'turn_id', '当前 turn id。'),
('bq_memory_references', 'snapshot_id', '当前上下文快照 id。'),
('bq_memory_references', 'artifact_id', '被引用的产物 id。'),
('bq_memory_references', 'candidate_id', '被引用的候选 id；summary 级引用通常为空。'),
('bq_memory_references', 'reference_type', '引用类型，例如 SUMMARY。'),
('bq_memory_references', 'token_estimate', '本次引用 token 估算。'),
('bq_memory_references', 'created_at', '引用创建时间。');
