-- 业务桌面身份与动作审计：历史记录保持可空兼容，新业务记录冻结完整七元身份。

ALTER TABLE bq_threads ADD COLUMN desktop_instance_id TEXT;
ALTER TABLE bq_threads ADD COLUMN desktop_session_id TEXT;
ALTER TABLE bq_threads ADD COLUMN auth_session_id TEXT;
ALTER TABLE bq_threads ADD COLUMN identity_epoch INTEGER;
ALTER TABLE bq_threads ADD COLUMN user_id TEXT;
ALTER TABLE bq_threads ADD COLUMN tenant_id TEXT;
ALTER TABLE bq_threads ADD COLUMN platform_id TEXT;

ALTER TABLE bq_turns ADD COLUMN desktop_instance_id TEXT;
ALTER TABLE bq_turns ADD COLUMN desktop_session_id TEXT;
ALTER TABLE bq_turns ADD COLUMN auth_session_id TEXT;
ALTER TABLE bq_turns ADD COLUMN identity_epoch INTEGER;
ALTER TABLE bq_turns ADD COLUMN user_id TEXT;
ALTER TABLE bq_turns ADD COLUMN tenant_id TEXT;
ALTER TABLE bq_turns ADD COLUMN platform_id TEXT;

ALTER TABLE bq_context_windows ADD COLUMN desktop_instance_id TEXT;
ALTER TABLE bq_context_windows ADD COLUMN desktop_session_id TEXT;
ALTER TABLE bq_context_windows ADD COLUMN auth_session_id TEXT;
ALTER TABLE bq_context_windows ADD COLUMN identity_epoch INTEGER;
ALTER TABLE bq_context_windows ADD COLUMN user_id TEXT;
ALTER TABLE bq_context_windows ADD COLUMN tenant_id TEXT;
ALTER TABLE bq_context_windows ADD COLUMN platform_id TEXT;

ALTER TABLE bq_context_snapshots ADD COLUMN desktop_instance_id TEXT;
ALTER TABLE bq_context_snapshots ADD COLUMN desktop_session_id TEXT;
ALTER TABLE bq_context_snapshots ADD COLUMN auth_session_id TEXT;
ALTER TABLE bq_context_snapshots ADD COLUMN identity_epoch INTEGER;
ALTER TABLE bq_context_snapshots ADD COLUMN user_id TEXT;
ALTER TABLE bq_context_snapshots ADD COLUMN tenant_id TEXT;
ALTER TABLE bq_context_snapshots ADD COLUMN platform_id TEXT;

ALTER TABLE bq_tool_calls ADD COLUMN desktop_instance_id TEXT;
ALTER TABLE bq_tool_calls ADD COLUMN desktop_session_id TEXT;
ALTER TABLE bq_tool_calls ADD COLUMN auth_session_id TEXT;
ALTER TABLE bq_tool_calls ADD COLUMN identity_epoch INTEGER;
ALTER TABLE bq_tool_calls ADD COLUMN user_id TEXT;
ALTER TABLE bq_tool_calls ADD COLUMN tenant_id TEXT;
ALTER TABLE bq_tool_calls ADD COLUMN platform_id TEXT;
ALTER TABLE bq_tool_calls ADD COLUMN execution_id TEXT;

-- 动作当前态只保存安全摘要，first-terminal-wins；原始动作输入绝不落表。
CREATE TABLE bq_application_actions (
    execution_id TEXT PRIMARY KEY,
    action_id TEXT NOT NULL CHECK(length(action_id) BETWEEN 1 AND 256),
    action_version INTEGER NOT NULL CHECK(action_version > 0),
    request_fingerprint TEXT NOT NULL CHECK(length(request_fingerprint) BETWEEN 1 AND 256),
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    tool_call_id TEXT,
    desktop_instance_id TEXT NOT NULL,
    desktop_session_id TEXT NOT NULL,
    auth_session_id TEXT NOT NULL,
    identity_epoch INTEGER NOT NULL CHECK(identity_epoch > 0),
    user_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    platform_id TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('REQUESTED','ACCEPTED','PREVIEWED','APPROVAL_REQUIRED','EXECUTING','COMPLETED','FAILED','REJECTED','CANCELED','EXPIRED','OUTCOME_UNKNOWN')),
    result_summary_redacted TEXT,
    error_code TEXT,
    error_message_redacted TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    terminal_at TEXT,
    FOREIGN KEY(thread_id) REFERENCES bq_threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY(turn_id) REFERENCES bq_turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY(tool_call_id) REFERENCES bq_tool_calls(tool_call_id) ON DELETE RESTRICT
);

-- 动作事件只允许追加；event_sequence 在一个 execution 内严格唯一。
CREATE TABLE bq_application_action_events (
    event_id TEXT PRIMARY KEY,
    execution_id TEXT NOT NULL,
    event_sequence INTEGER NOT NULL CHECK(event_sequence > 0),
    event_type TEXT NOT NULL CHECK(length(event_type) BETWEEN 1 AND 64),
    from_status TEXT,
    to_status TEXT NOT NULL CHECK(to_status IN ('REQUESTED','ACCEPTED','PREVIEWED','APPROVAL_REQUIRED','EXECUTING','COMPLETED','FAILED','REJECTED','CANCELED','EXPIRED','OUTCOME_UNKNOWN')),
    payload_summary_redacted TEXT,
    late_result INTEGER NOT NULL DEFAULT 0 CHECK(late_result IN (0,1)),
    occurred_at TEXT NOT NULL,
    UNIQUE (execution_id, event_sequence),
    FOREIGN KEY(execution_id) REFERENCES bq_application_actions(execution_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_bq_application_actions_tool_call_id
    ON bq_application_actions(tool_call_id) WHERE tool_call_id IS NOT NULL;
CREATE INDEX idx_bq_application_actions_scope_status
    ON bq_application_actions(desktop_instance_id, desktop_session_id, auth_session_id,
        identity_epoch, user_id, tenant_id, platform_id, status, updated_at DESC);
CREATE INDEX idx_bq_application_actions_thread_turn
    ON bq_application_actions(thread_id, turn_id, updated_at DESC);
CREATE INDEX idx_bq_application_action_events_execution_sequence
    ON bq_application_action_events(execution_id, event_sequence ASC);

CREATE TRIGGER trg_bq_application_action_events_no_update
BEFORE UPDATE ON bq_application_action_events
BEGIN
    SELECT RAISE(ABORT, 'bq_application_action_events is append-only');
END;

CREATE TRIGGER trg_bq_application_action_events_no_delete
BEFORE DELETE ON bq_application_action_events
BEGIN
    SELECT RAISE(ABORT, 'bq_application_action_events is append-only');
END;

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_threads','desktop_instance_id','业务桌面实例标识；历史普通会话为空，业务会话创建时冻结。'),
('bq_threads','desktop_session_id','业务桌面会话标识；用于隔离重连和并行桌面会话。'),
('bq_threads','auth_session_id','业务认证会话标识；身份更新后旧会话不得读取新身份数据。'),
('bq_threads','identity_epoch','业务身份严格递增版本；历史普通会话为空。'),
('bq_threads','user_id','业务用户标识快照；只用于精确归属过滤。'),
('bq_threads','tenant_id','业务租户标识快照；所有业务读取必须精确匹配。'),
('bq_threads','platform_id','业务平台标识快照；用于多平台数据隔离。'),
('bq_turns','desktop_instance_id','本轮冻结的业务桌面实例标识。'),
('bq_turns','desktop_session_id','本轮冻结的业务桌面会话标识。'),
('bq_turns','auth_session_id','本轮冻结的业务认证会话标识。'),
('bq_turns','identity_epoch','本轮冻结的业务身份版本。'),
('bq_turns','user_id','本轮冻结的业务用户标识。'),
('bq_turns','tenant_id','本轮冻结的业务租户标识。'),
('bq_turns','platform_id','本轮冻结的业务平台标识。'),
('bq_context_windows','desktop_instance_id','上下文窗口所属业务桌面实例标识。'),
('bq_context_windows','desktop_session_id','上下文窗口所属业务桌面会话标识。'),
('bq_context_windows','auth_session_id','上下文窗口所属业务认证会话标识。'),
('bq_context_windows','identity_epoch','上下文窗口所属业务身份版本。'),
('bq_context_windows','user_id','上下文窗口所属业务用户标识。'),
('bq_context_windows','tenant_id','上下文窗口所属业务租户标识。'),
('bq_context_windows','platform_id','上下文窗口所属业务平台标识。'),
('bq_context_snapshots','desktop_instance_id','上下文快照冻结的业务桌面实例标识。'),
('bq_context_snapshots','desktop_session_id','上下文快照冻结的业务桌面会话标识。'),
('bq_context_snapshots','auth_session_id','上下文快照冻结的业务认证会话标识。'),
('bq_context_snapshots','identity_epoch','上下文快照冻结的业务身份版本。'),
('bq_context_snapshots','user_id','上下文快照冻结的业务用户标识。'),
('bq_context_snapshots','tenant_id','上下文快照冻结的业务租户标识。'),
('bq_context_snapshots','platform_id','上下文快照冻结的业务平台标识。'),
('bq_tool_calls','desktop_instance_id','工具调用冻结的业务桌面实例标识。'),
('bq_tool_calls','desktop_session_id','工具调用冻结的业务桌面会话标识。'),
('bq_tool_calls','auth_session_id','工具调用冻结的业务认证会话标识。'),
('bq_tool_calls','identity_epoch','工具调用冻结的业务身份版本。'),
('bq_tool_calls','user_id','工具调用冻结的业务用户标识。'),
('bq_tool_calls','tenant_id','工具调用冻结的业务租户标识。'),
('bq_tool_calls','platform_id','工具调用冻结的业务平台标识。'),
('bq_tool_calls','execution_id','application_action 生成的执行标识；其他工具为空。'),
('bq_application_actions','__table__','保存桌面应用动作当前安全状态；终态遵循首次终态优先。'),
('bq_application_actions','execution_id','动作全局执行标识，也是当前态主键。'),
('bq_application_actions','action_id','权限过滤后动作目录中的稳定动作标识。'),
('bq_application_actions','action_version','动作契约正整数版本。'),
('bq_application_actions','request_fingerprint','动作请求的不可逆指纹，不保存原始业务输入。'),
('bq_application_actions','thread_id','发起动作的会话标识。'),
('bq_application_actions','turn_id','发起动作的运行回合标识。'),
('bq_application_actions','tool_call_id','发起动作的工具调用标识；非空时全表唯一。'),
('bq_application_actions','desktop_instance_id','动作冻结的业务桌面实例标识。'),
('bq_application_actions','desktop_session_id','动作冻结的业务桌面会话标识。'),
('bq_application_actions','auth_session_id','动作冻结的业务认证会话标识。'),
('bq_application_actions','identity_epoch','动作冻结的业务身份版本。'),
('bq_application_actions','user_id','动作冻结的业务用户标识。'),
('bq_application_actions','tenant_id','动作冻结的业务租户标识。'),
('bq_application_actions','platform_id','动作冻结的业务平台标识。'),
('bq_application_actions','status','动作当前状态；首个终态写入后不可被晚到结果覆盖。'),
('bq_application_actions','result_summary_redacted','动作结果安全短摘要，禁止原始业务载荷。'),
('bq_application_actions','error_code','动作稳定错误码；成功时为空。'),
('bq_application_actions','error_message_redacted','动作错误安全短摘要，禁止密钥和原始载荷。'),
('bq_application_actions','created_at','动作首次注册时间。'),
('bq_application_actions','updated_at','动作当前态最近更新时间。'),
('bq_application_actions','terminal_at','动作首次进入终态的时间；未终态时为空。'),
('bq_application_action_events','__table__','保存不可变桌面动作生命周期事件，仅允许追加。'),
('bq_application_action_events','event_id','动作事件全局唯一主键。'),
('bq_application_action_events','execution_id','事件所属动作执行标识。'),
('bq_application_action_events','event_sequence','事件在动作内的严格递增序号。'),
('bq_application_action_events','event_type','事件类型，例如 transition、recovery 或 late_result。'),
('bq_application_action_events','from_status','转换前状态；首次注册事件可为空。'),
('bq_application_action_events','to_status','转换后状态。'),
('bq_application_action_events','payload_summary_redacted','事件安全短摘要，禁止保存原始业务载荷。'),
('bq_application_action_events','late_result','是否为首终态之后到达的晚结果，取值零或一。'),
('bq_application_action_events','occurred_at','事件发生时间，使用国际标准时间文本保存。');
