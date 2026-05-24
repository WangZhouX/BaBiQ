-- P2-4 恢复语义与运行记录：为 turn 增加恢复/取消说明，并新增工具调用审计表。
-- SQLite 不支持原生 COMMENT，本迁移继续把每个新增表和字段写入 bq_schema_comments。

-- bq_turns.recovery_reason：服务端启动恢复时写入，解释为什么非终态 turn 被收口。
ALTER TABLE bq_turns ADD COLUMN recovery_reason TEXT;

-- bq_turns.recovered_at：恢复收口发生时间；为空表示该 turn 不是恢复流程关闭的。
ALTER TABLE bq_turns ADD COLUMN recovered_at TEXT;

-- bq_turns.cancel_reason：用户主动取消或中断时写入，和失败原因、恢复原因区分开。
ALTER TABLE bq_turns ADD COLUMN cancel_reason TEXT;

-- 表：bq_tool_calls，保存每次工具调用的审计记录，供运行详情和后续观测统计使用。
CREATE TABLE IF NOT EXISTS bq_tool_calls (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC 协议。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- SAA 工具调用 id；同一次工具调用开始和完成更新都使用它定位。
    tool_call_id TEXT NOT NULL UNIQUE,
    -- 工具调用所属 threadId，便于按会话聚合运行记录。
    thread_id TEXT NOT NULL,
    -- 工具调用所属 turnId，运行详情按 turn 回放时使用。
    turn_id TEXT NOT NULL,
    -- 工具名，例如 read_file、write_file、exec_shell。
    tool_name TEXT NOT NULL,
    -- 工具原始参数 JSON；展示和再次分析时都必须按不可信输入处理。
    args_json TEXT NOT NULL,
    -- 工具状态，running、completed、failed 或 denied。
    status TEXT NOT NULL,
    -- 工具结果短预览；大输出会被服务层截断，完整输出仍以协议 item 或日志为准。
    result_preview TEXT,
    -- 错误或拒绝原因；成功时为空。
    error_message TEXT,
    -- 工具开始时间，ISO-8601 文本。
    started_at TEXT NOT NULL,
    -- 工具完成时间，运行中为空。
    completed_at TEXT,
    FOREIGN KEY (thread_id) REFERENCES bq_threads(thread_id) ON DELETE CASCADE,
    FOREIGN KEY (turn_id) REFERENCES bq_turns(turn_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_bq_tool_calls_turn_started_at
    ON bq_tool_calls(turn_id, started_at ASC);

CREATE INDEX IF NOT EXISTS idx_bq_tool_calls_thread_started_at
    ON bq_tool_calls(thread_id, started_at DESC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_turns', 'recovery_reason', '恢复收口原因；服务端启动时把遗留 RUNNING 或 WAITING_APPROVAL turn 转为终态后写入。'),
('bq_turns', 'recovered_at', '恢复收口时间；为空表示该 turn 不是由启动恢复流程关闭。'),
('bq_turns', 'cancel_reason', '用户主动取消或中断原因；用于和模型失败、启动恢复原因区分。'),
('bq_tool_calls', '__table__', '保存每次工具调用的审计记录，用于运行详情回放、失败排查和后续可观测统计。'),
('bq_tool_calls', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_tool_calls', 'tool_call_id', 'SAA 工具调用 id，用于幂等更新同一次工具调用的开始和完成状态。'),
('bq_tool_calls', 'thread_id', '工具调用所属 threadId，便于按会话聚合运行记录。'),
('bq_tool_calls', 'turn_id', '工具调用所属 turnId，便于按运行回合回放工具轨迹。'),
('bq_tool_calls', 'tool_name', '工具名，例如 read_file、write_file、exec_shell。'),
('bq_tool_calls', 'args_json', '工具原始参数 JSON，展示和分析时必须视为不可信输入。'),
('bq_tool_calls', 'status', '工具状态，running 表示已开始，completed 表示成功，failed 表示异常，denied 表示被沙箱或权限拒绝。'),
('bq_tool_calls', 'result_preview', '工具结果短预览，由服务层截断，避免大输出撑爆运行记录面板。'),
('bq_tool_calls', 'error_message', '工具错误或拒绝原因；成功执行时为空。'),
('bq_tool_calls', 'started_at', '工具开始时间，使用 ISO-8601 字符串保存。'),
('bq_tool_calls', 'completed_at', '工具完成时间；运行中或进程中断时为空。');
