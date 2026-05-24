-- P2 运行摘要收口：BaBiQ 只持久化 token、耗时和工具调用，不再保存价格/成本字段。
-- SQLite 对列删除和注释能力都比较有限，因此这里用“建新表 -> 拷贝数据 -> 替换旧表”的方式安全迁移。

CREATE TABLE bq_turn_summaries_token_only (
    -- 数据库内部主键，自增，不暴露给协议层。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 所属 turnId，一轮只允许一条摘要。
    turn_id TEXT NOT NULL UNIQUE,
    -- 输入 token 数，来自模型 usage；如果供应商未返回 usage，则保持 0，避免伪造价格或估算。
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    -- 输出 token 数，来自模型 usage；如果供应商未返回 usage，则保持 0。
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    -- 输入和输出 token 总数，便于列表、统计面板和人工排查直接读取。
    total_tokens INTEGER NOT NULL DEFAULT 0,
    -- 本轮耗时毫秒数，由后端 turn 计时器写入。
    duration_ms INTEGER NOT NULL DEFAULT 0,
    -- 本轮工具调用次数，用于桌面端运行反馈和本地观测。
    tool_count INTEGER NOT NULL DEFAULT 0,
    -- 摘要生成时间，只在 turn 结束后写入。
    created_at TEXT NOT NULL,
    FOREIGN KEY (turn_id) REFERENCES bq_turns(turn_id) ON DELETE CASCADE
);

INSERT INTO bq_turn_summaries_token_only(
    id,
    turn_id,
    prompt_tokens,
    completion_tokens,
    total_tokens,
    duration_ms,
    tool_count,
    created_at
)
SELECT
    id,
    turn_id,
    prompt_tokens,
    completion_tokens,
    COALESCE(prompt_tokens, 0) + COALESCE(completion_tokens, 0),
    duration_ms,
    tool_count,
    created_at
FROM bq_turn_summaries;

DROP TABLE bq_turn_summaries;

ALTER TABLE bq_turn_summaries_token_only RENAME TO bq_turn_summaries;

DELETE FROM bq_schema_comments
WHERE table_name = 'bq_turn_summaries';

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_turn_summaries', '__table__', '保存 turn 结束后的 token、耗时和工具统计，不保存价格或成本信息。'),
('bq_turn_summaries', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_turn_summaries', 'turn_id', '所属 turnId，一轮只允许一条摘要。'),
('bq_turn_summaries', 'prompt_tokens', '输入 token 数，来自模型 usage；供应商未返回 usage 时为 0。'),
('bq_turn_summaries', 'completion_tokens', '输出 token 数，来自模型 usage；供应商未返回 usage 时为 0。'),
('bq_turn_summaries', 'total_tokens', '总 token 数，等于输入 token 和输出 token 之和。'),
('bq_turn_summaries', 'duration_ms', '本轮耗时毫秒数。'),
('bq_turn_summaries', 'tool_count', '本轮工具调用次数。'),
('bq_turn_summaries', 'created_at', '摘要生成时间。');

UPDATE bq_schema_comments
SET comment = '协议层 turnId，用于关联 item、审批和运行摘要。',
    updated_at = CURRENT_TIMESTAMP
WHERE table_name = 'bq_turns'
  AND column_name = 'turn_id';
