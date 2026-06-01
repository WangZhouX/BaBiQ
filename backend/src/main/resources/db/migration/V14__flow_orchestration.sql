-- P6-2 Flow Orchestration：保存多 Agent 流程的整体运行记录和节点聚合状态。
-- 工具级调用仍复用 bq_tool_calls，本 migration 只补充流程视图所需的审计事实源。

-- 表：bq_orchestrations，保存一次流程编排的整体状态。
CREATE TABLE IF NOT EXISTS bq_orchestrations (
    -- 数据库内部自增主键，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层流程 id，以 orch_ 开头，用于 UI、运行记录和节点表串联。
    orchestration_id TEXT NOT NULL UNIQUE,
    -- 所属 thread id，用于按会话查询流程运行记录。
    thread_id TEXT,
    -- 所属 turn id，用于和工具调用、TurnSummary 关联。
    turn_id TEXT,
    -- 用户可读标题。
    title TEXT NOT NULL,
    -- 拓扑类型：sequential、parallel 或 routing。
    topology TEXT NOT NULL,
    -- 流程状态：pending、running、completed、failed。
    status TEXT NOT NULL,
    -- 执行时工作目录快照。
    cwd TEXT,
    -- 执行时沙箱模式快照，流程不得自行提升。
    sandbox_mode TEXT NOT NULL,
    -- 是否已通过运行前整体审批，1 表示已审批。
    approved INTEGER NOT NULL DEFAULT 0,
    -- 是否已冻结拓扑、节点、工具和写入范围，1 表示已冻结。
    frozen INTEGER NOT NULL DEFAULT 0,
    -- 流程短摘要，用于右侧运行详情展示。
    summary TEXT,
    -- 失败原因；成功或运行中为空。
    error_message TEXT,
    -- 创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 更新时间，ISO-8601 文本。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_orchestrations_thread_turn
    ON bq_orchestrations(thread_id, turn_id);
CREATE INDEX IF NOT EXISTS idx_bq_orchestrations_created_at
    ON bq_orchestrations(created_at DESC);

-- 表：bq_orchestration_nodes，保存一次流程运行里的节点聚合状态。
CREATE TABLE IF NOT EXISTS bq_orchestration_nodes (
    -- 数据库内部自增主键，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 所属流程 id，对应 bq_orchestrations.orchestration_id。
    orchestration_id TEXT NOT NULL,
    -- 协议层节点 id，用于 UI 状态更新。
    node_id TEXT NOT NULL,
    -- 节点 ASCII 技术名。
    name TEXT NOT NULL,
    -- 桌面端展示名。
    display_name TEXT NOT NULL,
    -- 节点委派模式：READ_ONLY_TOOL 或 WORKSPACE_TOOL。
    mode TEXT NOT NULL,
    -- 节点工具白名单，逗号分隔，仅用于运行详情摘要。
    tool_names TEXT,
    -- 节点状态：pending、running、completed、failed。
    status TEXT NOT NULL,
    -- 节点排序号，保证 UI 稳定展示。
    node_order INTEGER NOT NULL DEFAULT 0,
    -- 节点聚合工具调用次数。
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    -- 节点 token 粗估值，不用于计费。
    token_estimate INTEGER NOT NULL DEFAULT 0,
    -- 节点短摘要。
    summary TEXT,
    -- 创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 更新时间，ISO-8601 文本。
    updated_at TEXT NOT NULL,
    UNIQUE(orchestration_id, node_id)
);

CREATE INDEX IF NOT EXISTS idx_bq_orchestration_nodes_orch_order
    ON bq_orchestration_nodes(orchestration_id, node_order ASC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_orchestrations', '__table__', 'P6-2 多 Agent 流程编排整体运行记录表。'),
('bq_orchestrations', 'id', '数据库内部自增主键，不暴露给 JSON-RPC。'),
('bq_orchestrations', 'orchestration_id', '协议层流程 id，以 orch_ 开头，用于 UI、运行记录和节点表串联。'),
('bq_orchestrations', 'thread_id', '所属 thread id，用于按会话查询流程运行记录。'),
('bq_orchestrations', 'turn_id', '所属 turn id，用于和工具调用、TurnSummary 关联。'),
('bq_orchestrations', 'title', '用户可读标题。'),
('bq_orchestrations', 'topology', '拓扑类型：sequential、parallel 或 routing。'),
('bq_orchestrations', 'status', '流程状态：pending、running、completed、failed。'),
('bq_orchestrations', 'cwd', '执行时工作目录快照。'),
('bq_orchestrations', 'sandbox_mode', '执行时沙箱模式快照，流程不得自行提升。'),
('bq_orchestrations', 'approved', '是否已通过运行前整体审批，1 表示已审批。'),
('bq_orchestrations', 'frozen', '是否已冻结拓扑、节点、工具和写入范围，1 表示已冻结。'),
('bq_orchestrations', 'summary', '流程短摘要，用于右侧运行详情展示。'),
('bq_orchestrations', 'error_message', '失败原因；成功或运行中为空。'),
('bq_orchestrations', 'created_at', '创建时间，ISO-8601 文本。'),
('bq_orchestrations', 'updated_at', '更新时间，ISO-8601 文本。'),
('bq_orchestration_nodes', '__table__', 'P6-2 多 Agent 流程节点聚合状态表。'),
('bq_orchestration_nodes', 'id', '数据库内部自增主键，不暴露给 JSON-RPC。'),
('bq_orchestration_nodes', 'orchestration_id', '所属流程 id，对应 bq_orchestrations.orchestration_id。'),
('bq_orchestration_nodes', 'node_id', '协议层节点 id，用于 UI 状态更新。'),
('bq_orchestration_nodes', 'name', '节点 ASCII 技术名。'),
('bq_orchestration_nodes', 'display_name', '桌面端展示名。'),
('bq_orchestration_nodes', 'mode', '节点委派模式：READ_ONLY_TOOL 或 WORKSPACE_TOOL。'),
('bq_orchestration_nodes', 'tool_names', '节点工具白名单，逗号分隔，仅用于运行详情摘要。'),
('bq_orchestration_nodes', 'status', '节点状态：pending、running、completed、failed。'),
('bq_orchestration_nodes', 'node_order', '节点排序号，保证 UI 稳定展示。'),
('bq_orchestration_nodes', 'tool_call_count', '节点聚合工具调用次数。'),
('bq_orchestration_nodes', 'token_estimate', '节点 token 粗估值，不用于计费。'),
('bq_orchestration_nodes', 'summary', '节点短摘要。'),
('bq_orchestration_nodes', 'created_at', '创建时间，ISO-8601 文本。'),
('bq_orchestration_nodes', 'updated_at', '更新时间，ISO-8601 文本。');
