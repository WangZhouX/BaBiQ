-- P6-3 团队面板移除语义补强：团队运行记录被用户移除后，重启也不再出现在团队列表。
-- 注意：这里是持久化隐藏，不物理删除审计数据、成员记录、消息记录或产物记录。

ALTER TABLE bq_teams ADD COLUMN removed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE bq_teams ADD COLUMN removed_at TEXT;

UPDATE bq_teams
SET removed = 1,
    removed_at = COALESCE(
        (
            SELECT i.updated_at
            FROM bq_items i
            WHERE i.thread_id = bq_teams.thread_id
              AND i.type = 'team'
              AND i.item_id = 'it_' || bq_teams.team_id
              AND lower(i.status) = 'removed'
            LIMIT 1
        ),
        bq_teams.updated_at
    )
WHERE EXISTS (
    SELECT 1
    FROM bq_items i
    WHERE i.thread_id = bq_teams.thread_id
      AND i.type = 'team'
      AND i.item_id = 'it_' || bq_teams.team_id
      AND lower(i.status) = 'removed'
);

CREATE INDEX IF NOT EXISTS idx_bq_teams_thread_removed_updated_at
    ON bq_teams(thread_id, removed, updated_at DESC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_teams', 'removed', '团队运行记录是否已被用户移除；1 表示从团队面板隐藏，但继续保留审计数据。'),
('bq_teams', 'removed_at', '用户移除团队运行记录的时间，ISO-8601 文本。');
