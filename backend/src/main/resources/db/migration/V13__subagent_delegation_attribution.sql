-- P6-1 子 Agent 委派：为工具调用审计补充 Agent 归属字段。
-- SQLite 不支持原生 COMMENT，因此新增字段仍同步写入 bq_schema_comments。

-- bq_tool_calls.agent_name：实际执行本次工具调用的 Agent 名称；主 Agent 默认为 babiq_agent。
ALTER TABLE bq_tool_calls ADD COLUMN agent_name TEXT NOT NULL DEFAULT 'babiq_agent';

-- bq_tool_calls.parent_agent_name：委派来源的父 Agent 名称；主 Agent 直接调用工具时为空。
ALTER TABLE bq_tool_calls ADD COLUMN parent_agent_name TEXT;

-- bq_tool_calls.delegation_id：一次子 Agent 委派的稳定 id；非委派工具调用为空。
ALTER TABLE bq_tool_calls ADD COLUMN delegation_id TEXT;

CREATE INDEX IF NOT EXISTS idx_bq_tool_calls_delegation
    ON bq_tool_calls(delegation_id, started_at ASC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_tool_calls', 'agent_name', '实际执行本次工具调用的 Agent 名称；主 Agent 默认为 babiq_agent，子 Agent 调用时写入 explorer 等名称。'),
('bq_tool_calls', 'parent_agent_name', '委派来源的父 Agent 名称；主 Agent 直接调用工具时为空。'),
('bq_tool_calls', 'delegation_id', '子 Agent 委派 id；用于把工具调用运行记录和 agentDelegation 协议 item 串联，非委派调用为空。');
