-- 业务桌面动作执行主表：保存幂等、身份边界、状态及脱敏结果。
CREATE TABLE bd_action_executions (
    execution_id TEXT PRIMARY KEY,
    action_id TEXT NOT NULL,
    action_version INTEGER NOT NULL CHECK (action_version > 0),
    input_fingerprint TEXT NOT NULL,
    origin TEXT NOT NULL CHECK (origin IN ('USER','AGENT')),
    desktop_instance_id TEXT NOT NULL,
    desktop_session_id TEXT NOT NULL,
    auth_session_id TEXT NOT NULL,
    identity_epoch INTEGER NOT NULL CHECK (identity_epoch >= 0),
    user_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    platform_id TEXT NOT NULL,
    page_id TEXT NOT NULL,
    context_revision INTEGER NOT NULL CHECK (context_revision >= 0),
    thread_id TEXT,
    turn_id TEXT,
    tool_call_id TEXT,
    risk_level TEXT NOT NULL CHECK (risk_level IN ('READ_ONLY','REVERSIBLE_WRITE','HIGH_RISK')),
    replay_policy TEXT NOT NULL CHECK (replay_policy IN ('SAFE','IDEMPOTENCY_KEY_REQUIRED','NEVER')),
    reconciliation_policy TEXT NOT NULL CHECK (reconciliation_policy IN ('NONE','QUERY_REMOTE','MANUAL')),
    status TEXT NOT NULL CHECK (status IN ('RECEIVED','VALIDATING','PREVIEWED','WAITING_APPROVAL','EXECUTING','SUCCEEDED','FAILED','CANCELED','EXPIRED','OUTCOME_UNKNOWN')),
    remote_reference TEXT,
    result_json_redacted TEXT,
    error_code TEXT,
    error_message_redacted TEXT,
    reconciliation_status TEXT NOT NULL CHECK (reconciliation_status IN ('NOT_REQUIRED','PENDING','IN_PROGRESS','SUCCEEDED','FAILED','MANUAL_REQUIRED')),
    reconciliation_attempts INTEGER NOT NULL DEFAULT 0 CHECK (reconciliation_attempts >= 0),
    last_reconciled_at TEXT,
    created_at TEXT NOT NULL,
    started_at TEXT,
    completed_at TEXT,
    updated_at TEXT NOT NULL,
    record_version INTEGER NOT NULL DEFAULT 0 CHECK (record_version >= 0)
);

-- 非空工具调用标识在所有执行记录中保持唯一。
CREATE UNIQUE INDEX bd_action_executions_tool_call_id_unique
    ON bd_action_executions(tool_call_id) WHERE tool_call_id IS NOT NULL;
-- 按完整身份边界及状态检索动作执行。
CREATE INDEX bd_action_executions_identity_scope_status_idx
    ON bd_action_executions(desktop_instance_id, desktop_session_id, auth_session_id, identity_epoch, user_id, tenant_id, platform_id, status);
-- 按对话、轮次及工具调用关联执行记录。
CREATE INDEX bd_action_executions_correlation_idx
    ON bd_action_executions(thread_id, turn_id, tool_call_id);

-- 业务桌面动作事件表：仅追加保存脱敏状态迁移事件。
CREATE TABLE bd_action_events (
    event_id TEXT PRIMARY KEY,
    execution_id TEXT NOT NULL,
    event_sequence INTEGER NOT NULL CHECK (event_sequence > 0),
    from_status TEXT CHECK (from_status IS NULL OR from_status IN ('RECEIVED','VALIDATING','PREVIEWED','WAITING_APPROVAL','EXECUTING','SUCCEEDED','FAILED','CANCELED','EXPIRED','OUTCOME_UNKNOWN')),
    to_status TEXT NOT NULL CHECK (to_status IN ('RECEIVED','VALIDATING','PREVIEWED','WAITING_APPROVAL','EXECUTING','SUCCEEDED','FAILED','CANCELED','EXPIRED','OUTCOME_UNKNOWN')),
    event_type TEXT NOT NULL,
    payload_json_redacted TEXT NOT NULL,
    actor_id TEXT,
    occurred_at TEXT NOT NULL,
    FOREIGN KEY (execution_id) REFERENCES bd_action_executions(execution_id) ON DELETE RESTRICT,
    UNIQUE (execution_id, event_sequence)
);

-- 数据库级禁止修改已追加的动作事件。
CREATE TRIGGER bd_action_events_reject_update
BEFORE UPDATE ON bd_action_events
BEGIN
    SELECT RAISE(ABORT, 'bd_action_events is append-only');
END;
-- 数据库级禁止删除已追加的动作事件。
CREATE TRIGGER bd_action_events_reject_delete
BEFORE DELETE ON bd_action_events
BEGIN
    SELECT RAISE(ABORT, 'bd_action_events is append-only');
END;

-- 业务桌面动作审批表：每个执行最多保存一个审批生命周期。
CREATE TABLE bd_action_approvals (
    approval_id TEXT PRIMARY KEY,
    execution_id TEXT NOT NULL UNIQUE,
    desktop_instance_id TEXT NOT NULL,
    auth_session_id TEXT NOT NULL,
    identity_epoch INTEGER NOT NULL CHECK (identity_epoch >= 0),
    user_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    platform_id TEXT NOT NULL,
    requested_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    decided_at TEXT,
    decision TEXT CHECK (decision IS NULL OR decision IN ('APPROVED','DENIED','EXPIRED')),
    decided_by TEXT,
    reason_redacted TEXT,
    FOREIGN KEY (execution_id) REFERENCES bd_action_executions(execution_id) ON DELETE RESTRICT
);

-- 按完整审批身份边界检索审批记录。
CREATE INDEX bd_action_approvals_identity_scope_idx
    ON bd_action_approvals(desktop_instance_id, auth_session_id, identity_epoch, user_id, tenant_id, platform_id);

-- 数据库结构中文说明表：记录每张业务表及字段的中文用途。
CREATE TABLE bd_schema_comments (
    object_type TEXT NOT NULL CHECK (object_type IN ('TABLE','COLUMN')),
    object_name TEXT NOT NULL,
    column_name TEXT NOT NULL DEFAULT '',
    comment_text TEXT NOT NULL CHECK (length(trim(comment_text)) > 0),
    PRIMARY KEY (object_type, object_name, column_name)
);

-- 表级中文说明。
INSERT INTO bd_schema_comments VALUES
('TABLE','bd_action_executions','','业务桌面动作执行主表'),
('TABLE','bd_action_events','','业务桌面动作状态事件表'),
('TABLE','bd_action_approvals','','业务桌面动作审批记录表'),
('TABLE','bd_schema_comments','','数据库结构中文说明表');

-- 动作执行字段中文说明。
INSERT INTO bd_schema_comments VALUES
('COLUMN','bd_action_executions','execution_id','动作执行唯一标识'),
('COLUMN','bd_action_executions','action_id','动作定义标识'),
('COLUMN','bd_action_executions','action_version','动作定义版本'),
('COLUMN','bd_action_executions','input_fingerprint','脱敏输入指纹'),
('COLUMN','bd_action_executions','origin','动作发起来源'),
('COLUMN','bd_action_executions','desktop_instance_id','桌面安装实例标识'),
('COLUMN','bd_action_executions','desktop_session_id','桌面进程会话标识'),
('COLUMN','bd_action_executions','auth_session_id','认证会话标识'),
('COLUMN','bd_action_executions','identity_epoch','身份切换递增序号'),
('COLUMN','bd_action_executions','user_id','用户标识'),
('COLUMN','bd_action_executions','tenant_id','租户标识'),
('COLUMN','bd_action_executions','platform_id','平台标识'),
('COLUMN','bd_action_executions','page_id','页面标识'),
('COLUMN','bd_action_executions','context_revision','页面上下文版本'),
('COLUMN','bd_action_executions','thread_id','对话标识'),
('COLUMN','bd_action_executions','turn_id','轮次标识'),
('COLUMN','bd_action_executions','tool_call_id','工具调用标识'),
('COLUMN','bd_action_executions','risk_level','动作风险等级'),
('COLUMN','bd_action_executions','replay_policy','动作重放策略'),
('COLUMN','bd_action_executions','reconciliation_policy','结果对账策略'),
('COLUMN','bd_action_executions','status','动作执行状态'),
('COLUMN','bd_action_executions','remote_reference','远端业务关联标识'),
('COLUMN','bd_action_executions','result_json_redacted','脱敏结果数据'),
('COLUMN','bd_action_executions','error_code','错误代码'),
('COLUMN','bd_action_executions','error_message_redacted','脱敏错误说明'),
('COLUMN','bd_action_executions','reconciliation_status','结果对账状态'),
('COLUMN','bd_action_executions','reconciliation_attempts','结果对账尝试次数'),
('COLUMN','bd_action_executions','last_reconciled_at','最近对账时间'),
('COLUMN','bd_action_executions','created_at','记录创建时间'),
('COLUMN','bd_action_executions','started_at','执行开始时间'),
('COLUMN','bd_action_executions','completed_at','执行完成时间'),
('COLUMN','bd_action_executions','updated_at','记录更新时间'),
('COLUMN','bd_action_executions','record_version','乐观并发版本');

-- 动作事件字段中文说明。
INSERT INTO bd_schema_comments VALUES
('COLUMN','bd_action_events','event_id','动作事件唯一标识'),
('COLUMN','bd_action_events','execution_id','关联动作执行标识'),
('COLUMN','bd_action_events','event_sequence','执行内事件递增序号'),
('COLUMN','bd_action_events','from_status','迁移前执行状态'),
('COLUMN','bd_action_events','to_status','迁移后执行状态'),
('COLUMN','bd_action_events','event_type','动作事件类型'),
('COLUMN','bd_action_events','payload_json_redacted','脱敏事件载荷'),
('COLUMN','bd_action_events','actor_id','事件操作人标识'),
('COLUMN','bd_action_events','occurred_at','事件发生时间');

-- 动作审批字段中文说明。
INSERT INTO bd_schema_comments VALUES
('COLUMN','bd_action_approvals','approval_id','审批记录唯一标识'),
('COLUMN','bd_action_approvals','execution_id','关联动作执行标识'),
('COLUMN','bd_action_approvals','desktop_instance_id','桌面安装实例标识'),
('COLUMN','bd_action_approvals','auth_session_id','认证会话标识'),
('COLUMN','bd_action_approvals','identity_epoch','身份切换递增序号'),
('COLUMN','bd_action_approvals','user_id','用户标识'),
('COLUMN','bd_action_approvals','tenant_id','租户标识'),
('COLUMN','bd_action_approvals','platform_id','平台标识'),
('COLUMN','bd_action_approvals','requested_at','审批请求时间'),
('COLUMN','bd_action_approvals','expires_at','审批到期时间'),
('COLUMN','bd_action_approvals','decided_at','审批决定时间'),
('COLUMN','bd_action_approvals','decision','审批决定结果'),
('COLUMN','bd_action_approvals','decided_by','审批决定人标识'),
('COLUMN','bd_action_approvals','reason_redacted','脱敏审批原因');

-- 结构说明表自身字段中文说明。
INSERT INTO bd_schema_comments VALUES
('COLUMN','bd_schema_comments','object_type','结构对象类型'),
('COLUMN','bd_schema_comments','object_name','结构对象名称'),
('COLUMN','bd_schema_comments','column_name','字段名称，表说明时为空'),
('COLUMN','bd_schema_comments','comment_text','结构中文说明');
