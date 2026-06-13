-- P8 Flow Canvas Editor: persist editable nested flow structure separately from flat node settings.

ALTER TABLE bq_orchestrations
    ADD COLUMN structure_json TEXT;

ALTER TABLE bq_work_unit_configs
    ADD COLUMN structure_json TEXT;

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_orchestrations', 'structure_json', '流程画布结构树 JSON，描述串行、并行、路由分组和节点引用；为空时按旧版平铺 topology 解释。'),
('bq_work_unit_configs', 'structure_json', '工作容器编排配置的画布结构树 JSON；为空时表示旧版节点配置或团队配置。');
