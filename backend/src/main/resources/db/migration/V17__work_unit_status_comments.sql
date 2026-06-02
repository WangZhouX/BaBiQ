-- P6-4：刷新工作容器状态字段中文说明。
-- 注意：V16 已在用户本地库执行过，不能再改旧迁移；状态说明变更必须通过后续迁移承接。

UPDATE bq_schema_comments
SET comment = '容器状态：waiting_config、running、completed、failed、removed；待启动是 waiting_config 下的 UI 提示。'
WHERE table_name = 'bq_work_units'
  AND column_name = 'status';
