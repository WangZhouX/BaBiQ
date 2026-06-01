-- P6-3 Team Collaboration：保存团队协作的整体状态、成员聚合状态和团队消息时间线。
-- 工具级调用仍复用 bq_tool_calls，本 migration 只补充 supervisor/team 视图所需的审计事实源。

-- 表：bq_teams，保存一次团队协作运行的整体状态。
CREATE TABLE IF NOT EXISTS bq_teams (
    -- 数据库内部自增主键，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层团队 id，以 team_ 开头，用于 UI、运行记录、成员表和消息表串联。
    team_id TEXT NOT NULL UNIQUE,
    -- 所属 thread id，用于按会话查询团队运行记录。
    thread_id TEXT,
    -- 所属 turn id，用于和工具调用、TurnSummary 关联。
    turn_id TEXT,
    -- 用户可读标题。
    title TEXT NOT NULL,
    -- 团队整体目标。
    goal TEXT NOT NULL,
    -- 团队状态：pending、running、completed、failed。
    status TEXT NOT NULL,
    -- 执行时工作目录快照。
    cwd TEXT,
    -- 执行时沙箱模式快照，团队不得自行提升。
    sandbox_mode TEXT NOT NULL,
    -- 是否已通过运行前整体审批，1 表示已审批。
    approved INTEGER NOT NULL DEFAULT 0,
    -- 是否已冻结成员、工具和写入范围，1 表示已冻结。
    frozen INTEGER NOT NULL DEFAULT 0,
    -- supervisor 最多调度轮数，防止团队协作无限循环。
    max_rounds INTEGER NOT NULL DEFAULT 4,
    -- 当前调度轮数。
    current_round INTEGER NOT NULL DEFAULT 0,
    -- 当前正在执行或最近被调度的成员名。
    current_agent TEXT,
    -- 团队短摘要，用于右侧运行详情展示。
    summary TEXT,
    -- 失败原因；成功或运行中为空。
    error_message TEXT,
    -- 创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 更新时间，ISO-8601 文本。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_teams_thread_turn
    ON bq_teams(thread_id, turn_id);
CREATE INDEX IF NOT EXISTS idx_bq_teams_created_at
    ON bq_teams(created_at DESC);

-- 表：bq_team_members，保存一次团队协作里的成员聚合状态。
CREATE TABLE IF NOT EXISTS bq_team_members (
    -- 数据库内部自增主键，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 所属团队 id，对应 bq_teams.team_id。
    team_id TEXT NOT NULL,
    -- 协议层成员 id，用于 UI 状态更新。
    member_id TEXT NOT NULL,
    -- 成员 ASCII 技术名，也是 Spring AI Alibaba Agent 名称。
    name TEXT NOT NULL,
    -- 桌面端展示名。
    display_name TEXT NOT NULL,
    -- 成员角色，例如 explorer、writer、reviewer。
    role TEXT NOT NULL,
    -- 成员委派模式：READ_ONLY_TOOL 或 WORKSPACE_TOOL。
    mode TEXT NOT NULL,
    -- 成员工具白名单，逗号分隔，仅用于运行详情摘要。
    tool_names TEXT,
    -- 成员状态：pending、running、completed、failed。
    status TEXT NOT NULL,
    -- 成员排序号，保证 UI 稳定展示。
    member_order INTEGER NOT NULL DEFAULT 0,
    -- 成员聚合工具调用次数。
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    -- 成员 token 粗估值，不用于计费。
    token_estimate INTEGER NOT NULL DEFAULT 0,
    -- 成员短摘要。
    summary TEXT,
    -- 创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 更新时间，ISO-8601 文本。
    updated_at TEXT NOT NULL,
    UNIQUE(team_id, member_id)
);

CREATE INDEX IF NOT EXISTS idx_bq_team_members_team_order
    ON bq_team_members(team_id, member_order ASC);

-- 表：bq_team_messages，保存 supervisor、成员和用户直发消息组成的团队时间线。
CREATE TABLE IF NOT EXISTS bq_team_messages (
    -- 数据库内部自增主键，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 所属团队 id，对应 bq_teams.team_id。
    team_id TEXT NOT NULL,
    -- 协议层消息 id，用于 UI 去重。
    message_id TEXT NOT NULL UNIQUE,
    -- 所属 thread id，用于按会话查询团队消息。
    thread_id TEXT,
    -- 所属 turn id，用于和当轮执行关联；手动直发消息可为空。
    turn_id TEXT,
    -- 发送方：user、supervisor 或成员名。
    from_agent TEXT NOT NULL,
    -- 接收方：supervisor、成员名或 all。
    to_agent TEXT NOT NULL,
    -- 消息类型：route、member_summary、direct_user、system。
    message_type TEXT NOT NULL,
    -- 消息正文或短摘要。
    content TEXT NOT NULL,
    -- supervisor 路由结构化决策 JSON；非路由消息为空。
    route_decision_json TEXT,
    -- 该消息所属调度轮数。
    round INTEGER NOT NULL DEFAULT 0,
    -- 创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_team_messages_team_round
    ON bq_team_messages(team_id, round ASC, created_at ASC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_teams', '__table__', 'P6-3 团队协作整体运行记录表。'),
('bq_teams', 'id', '数据库内部自增主键，不暴露给 JSON-RPC。'),
('bq_teams', 'team_id', '协议层团队 id，以 team_ 开头，用于 UI、运行记录、成员表和消息表串联。'),
('bq_teams', 'thread_id', '所属 thread id，用于按会话查询团队运行记录。'),
('bq_teams', 'turn_id', '所属 turn id，用于和工具调用、TurnSummary 关联。'),
('bq_teams', 'title', '用户可读标题。'),
('bq_teams', 'goal', '团队整体目标。'),
('bq_teams', 'status', '团队状态：pending、running、completed、failed。'),
('bq_teams', 'cwd', '执行时工作目录快照。'),
('bq_teams', 'sandbox_mode', '执行时沙箱模式快照，团队不得自行提升。'),
('bq_teams', 'approved', '是否已通过运行前整体审批，1 表示已审批。'),
('bq_teams', 'frozen', '是否已冻结成员、工具和写入范围，1 表示已冻结。'),
('bq_teams', 'max_rounds', 'supervisor 最多调度轮数，防止团队协作无限循环。'),
('bq_teams', 'current_round', '当前调度轮数。'),
('bq_teams', 'current_agent', '当前正在执行或最近被调度的成员名。'),
('bq_teams', 'summary', '团队短摘要，用于右侧运行详情展示。'),
('bq_teams', 'error_message', '失败原因；成功或运行中为空。'),
('bq_teams', 'created_at', '创建时间，ISO-8601 文本。'),
('bq_teams', 'updated_at', '更新时间，ISO-8601 文本。'),
('bq_team_members', '__table__', 'P6-3 团队成员聚合状态表。'),
('bq_team_members', 'id', '数据库内部自增主键，不暴露给 JSON-RPC。'),
('bq_team_members', 'team_id', '所属团队 id，对应 bq_teams.team_id。'),
('bq_team_members', 'member_id', '协议层成员 id，用于 UI 状态更新。'),
('bq_team_members', 'name', '成员 ASCII 技术名，也是 Spring AI Alibaba Agent 名称。'),
('bq_team_members', 'display_name', '桌面端展示名。'),
('bq_team_members', 'role', '成员角色，例如 explorer、writer、reviewer。'),
('bq_team_members', 'mode', '成员委派模式：READ_ONLY_TOOL 或 WORKSPACE_TOOL。'),
('bq_team_members', 'tool_names', '成员工具白名单，逗号分隔，仅用于运行详情摘要。'),
('bq_team_members', 'status', '成员状态：pending、running、completed、failed。'),
('bq_team_members', 'member_order', '成员排序号，保证 UI 稳定展示。'),
('bq_team_members', 'tool_call_count', '成员聚合工具调用次数。'),
('bq_team_members', 'token_estimate', '成员 token 粗估值，不用于计费。'),
('bq_team_members', 'summary', '成员短摘要。'),
('bq_team_members', 'created_at', '创建时间，ISO-8601 文本。'),
('bq_team_members', 'updated_at', '更新时间，ISO-8601 文本。'),
('bq_team_messages', '__table__', 'P6-3 团队消息时间线表。'),
('bq_team_messages', 'id', '数据库内部自增主键，不暴露给 JSON-RPC。'),
('bq_team_messages', 'team_id', '所属团队 id，对应 bq_teams.team_id。'),
('bq_team_messages', 'message_id', '协议层消息 id，用于 UI 去重。'),
('bq_team_messages', 'thread_id', '所属 thread id，用于按会话查询团队消息。'),
('bq_team_messages', 'turn_id', '所属 turn id，用于和当轮执行关联；手动直发消息可为空。'),
('bq_team_messages', 'from_agent', '发送方：user、supervisor 或成员名。'),
('bq_team_messages', 'to_agent', '接收方：supervisor、成员名或 all。'),
('bq_team_messages', 'message_type', '消息类型：route、member_summary、direct_user、system。'),
('bq_team_messages', 'content', '消息正文或短摘要。'),
('bq_team_messages', 'route_decision_json', 'supervisor 路由结构化决策 JSON；非路由消息为空。'),
('bq_team_messages', 'round', '该消息所属调度轮数。'),
('bq_team_messages', 'created_at', '创建时间，ISO-8601 文本。');
