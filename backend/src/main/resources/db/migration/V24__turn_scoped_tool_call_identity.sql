-- 工具调用标识只保证在所属 turn 内唯一。
-- V4 曾把 tool_call_id 声明为全表唯一，但真实模型/兼容中转会在不同 turn 重复产生
-- application_action_0 等短标识。V24 保留原始协议值，并把持久化身份收口为
-- (turn_id, tool_call_id)，同时迁移依赖该身份的业务动作外键和唯一约束。

CREATE TABLE bq_tool_calls_v24 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_call_id TEXT NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    args_json TEXT NOT NULL,
    status TEXT NOT NULL,
    result_preview TEXT,
    error_message TEXT,
    started_at TEXT NOT NULL,
    completed_at TEXT,
    agent_name TEXT NOT NULL DEFAULT 'babiq_agent',
    parent_agent_name TEXT,
    delegation_id TEXT,
    desktop_instance_id TEXT,
    desktop_session_id TEXT,
    auth_session_id TEXT,
    identity_epoch INTEGER,
    user_id TEXT,
    tenant_id TEXT,
    platform_id TEXT,
    execution_id TEXT,
    FOREIGN KEY (thread_id) REFERENCES bq_threads(thread_id) ON DELETE CASCADE,
    FOREIGN KEY (turn_id) REFERENCES bq_turns(turn_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_bq_tool_calls_turn_tool_call
    ON bq_tool_calls_v24(turn_id, tool_call_id);

INSERT INTO bq_tool_calls_v24 (
    id, tool_call_id, thread_id, turn_id, tool_name, args_json, status,
    result_preview, error_message, started_at, completed_at,
    agent_name, parent_agent_name, delegation_id,
    desktop_instance_id, desktop_session_id, auth_session_id, identity_epoch,
    user_id, tenant_id, platform_id, execution_id
)
SELECT
    id, tool_call_id, thread_id, turn_id, tool_name, args_json, status,
    result_preview, error_message, started_at, completed_at,
    agent_name, parent_agent_name, delegation_id,
    desktop_instance_id, desktop_session_id, auth_session_id, identity_epoch,
    user_id, tenant_id, platform_id, execution_id
FROM bq_tool_calls;

CREATE TABLE bq_application_actions_v24 (
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
    status TEXT NOT NULL CHECK(status IN (
        'REQUESTED','ACCEPTED','PREVIEWED','APPROVAL_REQUIRED','EXECUTING',
        'COMPLETED','FAILED','REJECTED','CANCELED','EXPIRED','OUTCOME_UNKNOWN'
    )),
    result_summary_redacted TEXT,
    error_code TEXT,
    error_message_redacted TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    terminal_at TEXT,
    FOREIGN KEY(thread_id) REFERENCES bq_threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY(turn_id) REFERENCES bq_turns(turn_id) ON DELETE RESTRICT,
    FOREIGN KEY(turn_id, tool_call_id)
        REFERENCES bq_tool_calls_v24(turn_id, tool_call_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_bq_application_actions_turn_tool_call
    ON bq_application_actions_v24(turn_id, tool_call_id)
    WHERE tool_call_id IS NOT NULL;

INSERT INTO bq_application_actions_v24 (
    execution_id, action_id, action_version, request_fingerprint,
    thread_id, turn_id, tool_call_id,
    desktop_instance_id, desktop_session_id, auth_session_id, identity_epoch,
    user_id, tenant_id, platform_id, status,
    result_summary_redacted, error_code, error_message_redacted,
    created_at, updated_at, terminal_at
)
SELECT
    execution_id, action_id, action_version, request_fingerprint,
    thread_id, turn_id, tool_call_id,
    desktop_instance_id, desktop_session_id, auth_session_id, identity_epoch,
    user_id, tenant_id, platform_id, status,
    result_summary_redacted, error_code, error_message_redacted,
    created_at, updated_at, terminal_at
FROM bq_application_actions;

CREATE TABLE bq_application_action_events_v24 (
    event_id TEXT PRIMARY KEY,
    execution_id TEXT NOT NULL,
    event_sequence INTEGER NOT NULL CHECK(event_sequence > 0),
    event_type TEXT NOT NULL CHECK(length(event_type) BETWEEN 1 AND 64),
    from_status TEXT,
    to_status TEXT NOT NULL CHECK(to_status IN (
        'REQUESTED','ACCEPTED','PREVIEWED','APPROVAL_REQUIRED','EXECUTING',
        'COMPLETED','FAILED','REJECTED','CANCELED','EXPIRED','OUTCOME_UNKNOWN'
    )),
    payload_summary_redacted TEXT,
    late_result INTEGER NOT NULL DEFAULT 0 CHECK(late_result IN (0,1)),
    occurred_at TEXT NOT NULL,
    UNIQUE (execution_id, event_sequence),
    FOREIGN KEY(execution_id)
        REFERENCES bq_application_actions_v24(execution_id) ON DELETE RESTRICT
);

INSERT INTO bq_application_action_events_v24 (
    event_id, execution_id, event_sequence, event_type, from_status, to_status,
    payload_summary_redacted, late_result, occurred_at
)
SELECT
    event_id, execution_id, event_sequence, event_type, from_status, to_status,
    payload_summary_redacted, late_result, occurred_at
FROM bq_application_action_events;

DROP TABLE bq_application_action_events;
DROP TABLE bq_application_actions;
DROP TABLE bq_tool_calls;

ALTER TABLE bq_tool_calls_v24 RENAME TO bq_tool_calls;
ALTER TABLE bq_application_actions_v24 RENAME TO bq_application_actions;
ALTER TABLE bq_application_action_events_v24 RENAME TO bq_application_action_events;

CREATE INDEX idx_bq_tool_calls_turn_started_at
    ON bq_tool_calls(turn_id, started_at ASC);
CREATE INDEX idx_bq_tool_calls_thread_started_at
    ON bq_tool_calls(thread_id, started_at DESC);
CREATE INDEX idx_bq_tool_calls_delegation
    ON bq_tool_calls(delegation_id, started_at ASC);

CREATE INDEX idx_bq_application_actions_scope_status
    ON bq_application_actions(
        desktop_instance_id, desktop_session_id, auth_session_id,
        identity_epoch, user_id, tenant_id, platform_id, status, updated_at DESC
    );
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
('bq_tool_calls', 'tool_call_id', '模型或框架提供的原始工具调用标识；只在所属 turn_id 内唯一，开始和完成状态必须按组合身份定位。'),
('bq_tool_calls', 'turn_id', '工具调用所属 turnId；与 tool_call_id 共同构成稳定持久化身份，支持不同回合复用相同短标识。'),
('bq_application_actions', 'tool_call_id', '发起动作的原始工具调用标识；只在所属 turn_id 内唯一，并通过组合外键关联精确工具记录。'),
('bq_application_actions', 'turn_id', '发起动作的运行回合标识；与 tool_call_id 共同限定动作和工具调用的一对一关联。');
