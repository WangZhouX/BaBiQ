-- P6-4b 工作容器配置快照：保存右侧 Inspector 中的编排节点/团队成员设置。
-- 配置只描述“准备怎么执行”，真实运行结果仍由 bq_orchestrations / bq_teams 记录。

-- 表：bq_work_unit_configs，保存每个工作容器的最新配置 JSON。
CREATE TABLE IF NOT EXISTS bq_work_unit_configs (
    -- 数据库内部自增主键，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 所属工作容器 id，对应 bq_work_units.work_unit_id。
    work_unit_id TEXT NOT NULL UNIQUE,
    -- 编排节点或团队成员配置 JSON，由桌面端按 kind 解析。
    config_json TEXT NOT NULL,
    -- 创建时间，ISO-8601 文本。
    created_at TEXT NOT NULL,
    -- 更新时间，ISO-8601 文本。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_work_unit_configs_unit
    ON bq_work_unit_configs(work_unit_id);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_work_unit_configs', '__table__', 'P6-4b 工作容器配置快照表，保存右侧 Inspector 中的编排节点或团队成员设置。'),
('bq_work_unit_configs', 'id', '数据库内部自增主键，不暴露给 JSON-RPC。'),
('bq_work_unit_configs', 'work_unit_id', '所属工作容器 id，对应 bq_work_units.work_unit_id。'),
('bq_work_unit_configs', 'config_json', '配置 JSON，保存节点/成员任务、模型选择等右侧详情页可编辑内容。'),
('bq_work_unit_configs', 'created_at', '创建时间，ISO-8601 文本。'),
('bq_work_unit_configs', 'updated_at', '更新时间，ISO-8601 文本。');
