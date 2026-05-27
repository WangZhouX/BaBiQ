-- P3-5a 能力搜索切换为 Lucene 后，刷新搜索策略字段的中文说明。
-- 不修改业务表结构，只更新 bq_schema_comments 中的字段说明，避免改写已经发布的 V11 migration。
UPDATE bq_schema_comments
SET comment = '能力搜索策略；P3-5a 之后新事件固定写入 LUCENE，旧记录可能保留历史策略值。'
WHERE table_name = 'bq_capability_search_events'
  AND column_name = 'strategy';
