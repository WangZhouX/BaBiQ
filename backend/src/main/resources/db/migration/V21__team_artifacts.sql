-- P6-3a Team Artifacts：保存团队记忆工作区 Markdown 镜像的事实源记录。
-- Markdown 文件服务可读性，SQLite 仍是产物元数据和正文副本的事实源。

-- 表：bq_team_artifacts，保存 team.md、digest.md、rounds/*.md 和 result.md 的产物记录。
CREATE TABLE IF NOT EXISTS bq_team_artifacts (
    -- 数据库内部自增主键，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 所属团队 id，对应 bq_teams.team_id。
    team_id TEXT NOT NULL,
    -- 协议层产物 id，以 teamart_ 开头，用于 UI 和审计引用。
    artifact_id TEXT NOT NULL UNIQUE,
    -- 产物类型：TEAM_INDEX、MEMBER_OUTPUT、DIGEST、RESULT。
    artifact_type TEXT NOT NULL,
    -- Markdown 镜像路径，相对团队目录保存。
    relative_path TEXT NOT NULL,
    -- 文件内容 SHA-256，用于后续判断镜像漂移。
    sha256 TEXT NOT NULL,
    -- 文本 token 粗估值，不用于计费。
    token_estimate INTEGER NOT NULL DEFAULT 0,
    -- 产物所属调度轮；非轮次产物为 0。
    round INTEGER NOT NULL DEFAULT 0,
    -- 成员名；非成员产物为空。
    member_name TEXT,
    -- Markdown 正文副本，便于后续搜索检索无损追加。
    content TEXT,
    -- 创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 更新时间，ISO-8601 文本。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_team_artifacts_team_round
    ON bq_team_artifacts(team_id, round ASC, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_bq_team_artifacts_team_type
    ON bq_team_artifacts(team_id, artifact_type, created_at DESC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_team_artifacts', '__table__', 'P6-3a 团队记忆工作区 Markdown 产物记录表。'),
('bq_team_artifacts', 'id', '数据库内部自增主键，不暴露给 JSON-RPC。'),
('bq_team_artifacts', 'team_id', '所属团队 id，对应 bq_teams.team_id。'),
('bq_team_artifacts', 'artifact_id', '协议层产物 id，以 teamart_ 开头，用于 UI 和审计引用。'),
('bq_team_artifacts', 'artifact_type', '产物类型：TEAM_INDEX、MEMBER_OUTPUT、DIGEST、RESULT。'),
('bq_team_artifacts', 'relative_path', 'Markdown 镜像路径，相对团队目录保存。'),
('bq_team_artifacts', 'sha256', '文件内容 SHA-256，用于后续判断镜像漂移。'),
('bq_team_artifacts', 'token_estimate', '文本 token 粗估值，不用于计费。'),
('bq_team_artifacts', 'round', '产物所属调度轮；非轮次产物为 0。'),
('bq_team_artifacts', 'member_name', '成员名；非成员产物为空。'),
('bq_team_artifacts', 'content', 'Markdown 正文副本，便于后续搜索检索无损追加。'),
('bq_team_artifacts', 'created_at', '创建时间，ISO-8601 文本。'),
('bq_team_artifacts', 'updated_at', '更新时间，ISO-8601 文本。');
