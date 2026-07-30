-- 将服务项目附件批次绑定到经过服务端校验的“服务记录 + 项目”二元上下文。
ALTER TABLE bq_business_attachment_batches
    ADD COLUMN parent_record_id TEXT;

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_business_attachment_batches','parent_record_id','服务项目附件经服务端校验后的上级服务记录标识；非服务项目为空');
