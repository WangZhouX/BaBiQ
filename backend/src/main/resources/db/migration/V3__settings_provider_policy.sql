-- P2-3 设置系统：新增 session scope 的 always 审批规则表。
-- SQLite 不支持原生 COMMENT，因此新表和每个字段都要同时写 SQL 中文注释和 bq_schema_comments 元数据。

-- 表：bq_approval_rules，保存“始终允许”规则。P2-3 只启用 session scope，不做永久全局放行。
CREATE TABLE IF NOT EXISTS bq_approval_rules (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC 协议。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 规则业务 ID，用于日志和后续排查。
    rule_id TEXT NOT NULL UNIQUE,
    -- 规则作用域，P2-3 只允许 session，避免误伤所有未来任务。
    scope TEXT NOT NULL,
    -- session scope 绑定的 threadId；同一工具在其他会话中不会自动放行。
    thread_id TEXT,
    -- workspace scope 预留工作目录字段；P2-3 默认不启用。
    cwd TEXT,
    -- 被允许的工具名，例如 write_file、exec_shell、apply_patch。
    tool_name TEXT NOT NULL,
    -- 工具参数指纹，防止同一工具不同参数被宽泛放行。
    args_fingerprint TEXT NOT NULL,
    -- 规则决策，P2-3 固定为 always。
    decision TEXT NOT NULL,
    -- 规则过期时间；为空代表随 session 清理，由服务显式失效。
    expires_at TEXT,
    -- 规则创建时间，用于审计和调试。
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_approval_rules_session_match
    ON bq_approval_rules(scope, thread_id, tool_name, args_fingerprint, decision);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_approval_rules', '__table__', '保存 session scope 的始终允许审批规则，用于同会话同工具同参数自动放行。'),
('bq_approval_rules', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_approval_rules', 'rule_id', '规则业务 ID，用于日志、审计和排查。'),
('bq_approval_rules', 'scope', '规则作用域；P2-3 只允许 session，不支持永久全局 always。'),
('bq_approval_rules', 'thread_id', 'session scope 绑定的 threadId，其他会话不共享规则。'),
('bq_approval_rules', 'cwd', 'workspace scope 预留工作目录；P2-3 默认为空。'),
('bq_approval_rules', 'tool_name', '被允许自动放行的工具名。'),
('bq_approval_rules', 'args_fingerprint', '工具参数指纹，确保只对同一参数自动放行。'),
('bq_approval_rules', 'decision', '规则决策，P2-3 固定为 always。'),
('bq_approval_rules', 'expires_at', '规则过期时间；为空代表由 session 清理逻辑失效。'),
('bq_approval_rules', 'created_at', '规则创建时间。');
