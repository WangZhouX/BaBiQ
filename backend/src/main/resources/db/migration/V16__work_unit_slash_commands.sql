-- P6-4 Slash 命令与命名工作容器：保存编排/团队容器及其目标队列。
-- WorkUnit 是可复用容器；真实执行仍由 P6-2/P6-3 的编排和团队运行表承载。

-- 表：bq_work_units，保存命名工作容器的当前状态。
CREATE TABLE IF NOT EXISTS bq_work_units (
    -- 数据库内部自增主键，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层工作容器 id，以 wu_ 开头。
    work_unit_id TEXT NOT NULL UNIQUE,
    -- 所属 thread id，用于按对话查询容器。
    thread_id TEXT NOT NULL,
    -- 容器类型：orchestration 或 team。
    kind TEXT NOT NULL,
    -- 用户可读名称。
    name TEXT NOT NULL,
    -- 归一化名称，用于服务端同名复用。
    normalized_name TEXT NOT NULL,
    -- 容器状态：waiting_config、waiting_start、running、completed、failed、removed。
    status TEXT NOT NULL,
    -- 当前正在运行或最近激活的目标 id。
    current_goal_id TEXT,
    -- 创建时工作目录快照。
    cwd TEXT,
    -- 创建时沙箱模式快照，容器不得自动提升权限。
    sandbox_mode TEXT,
    -- 是否已从 UI 移除，1 表示已移除。
    removed INTEGER NOT NULL DEFAULT 0,
    -- 从 UI 移除的时间，未移除为空。
    removed_at TEXT,
    -- 创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 更新时间，ISO-8601 文本。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_work_units_visible_name
    ON bq_work_units(thread_id, kind, normalized_name, removed);
CREATE INDEX IF NOT EXISTS idx_bq_work_units_thread_updated
    ON bq_work_units(thread_id, updated_at DESC);

-- 表：bq_work_unit_goals，保存工作容器内的目标队列。
CREATE TABLE IF NOT EXISTS bq_work_unit_goals (
    -- 数据库内部自增主键，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层目标 id，以 goal_ 开头。
    goal_id TEXT NOT NULL UNIQUE,
    -- 所属工作容器 id，对应 bq_work_units.work_unit_id。
    work_unit_id TEXT NOT NULL,
    -- 所属 thread id，便于审计和查询。
    thread_id TEXT NOT NULL,
    -- 目标正文。
    goal_text TEXT NOT NULL,
    -- 目标状态：pending、running、completed、failed、cancelled。
    status TEXT NOT NULL,
    -- 真实执行引用类型：team 或 orchestration。
    run_ref_type TEXT,
    -- 真实执行引用 id，例如 team_... 或 orch_...。
    run_ref_id TEXT,
    -- 完成摘要。
    summary TEXT,
    -- 失败原因。
    error_message TEXT,
    -- 创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 启动时间，未启动为空。
    started_at TEXT,
    -- 完成时间，未完成为空。
    completed_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_bq_work_unit_goals_unit_created
    ON bq_work_unit_goals(work_unit_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_bq_work_unit_goals_thread_status
    ON bq_work_unit_goals(thread_id, status);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_work_units', '__table__', 'P6-4 命名工作容器状态表，保存 slash 创建的编排或团队容器。'),
('bq_work_units', 'id', '数据库内部自增主键，不暴露给 JSON-RPC。'),
('bq_work_units', 'work_unit_id', '协议层工作容器 id，以 wu_ 开头。'),
('bq_work_units', 'thread_id', '所属 thread id，用于按对话查询容器。'),
('bq_work_units', 'kind', '容器类型：orchestration 或 team。'),
('bq_work_units', 'name', '用户可读名称。'),
('bq_work_units', 'normalized_name', '归一化名称，用于服务端同名复用。'),
('bq_work_units', 'status', '容器状态：waiting_config、waiting_start、running、completed、failed、removed。'),
('bq_work_units', 'current_goal_id', '当前正在运行或最近激活的目标 id。'),
('bq_work_units', 'cwd', '创建时工作目录快照。'),
('bq_work_units', 'sandbox_mode', '创建时沙箱模式快照，容器不得自动提升权限。'),
('bq_work_units', 'removed', '是否已从 UI 移除，1 表示已移除。'),
('bq_work_units', 'removed_at', '从 UI 移除的时间，未移除为空。'),
('bq_work_units', 'created_at', '创建时间，ISO-8601 文本。'),
('bq_work_units', 'updated_at', '更新时间，ISO-8601 文本。'),
('bq_work_unit_goals', '__table__', 'P6-4 工作容器目标队列表，保存容器内每一批目标。'),
('bq_work_unit_goals', 'id', '数据库内部自增主键，不暴露给 JSON-RPC。'),
('bq_work_unit_goals', 'goal_id', '协议层目标 id，以 goal_ 开头。'),
('bq_work_unit_goals', 'work_unit_id', '所属工作容器 id，对应 bq_work_units.work_unit_id。'),
('bq_work_unit_goals', 'thread_id', '所属 thread id，便于审计和查询。'),
('bq_work_unit_goals', 'goal_text', '目标正文。'),
('bq_work_unit_goals', 'status', '目标状态：pending、running、completed、failed、cancelled。'),
('bq_work_unit_goals', 'run_ref_type', '真实执行引用类型：team 或 orchestration。'),
('bq_work_unit_goals', 'run_ref_id', '真实执行引用 id，例如 team_... 或 orch_...。'),
('bq_work_unit_goals', 'summary', '完成摘要。'),
('bq_work_unit_goals', 'error_message', '失败原因。'),
('bq_work_unit_goals', 'created_at', '创建时间，ISO-8601 文本。'),
('bq_work_unit_goals', 'started_at', '启动时间，未启动为空。'),
('bq_work_unit_goals', 'completed_at', '完成时间，未完成为空。');
