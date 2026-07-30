-- 日程创建操作的耐久幂等状态；只保存绑定字段和请求摘要，不保存 OA Token、fileIds、表单正文或远程响应正文。
CREATE TABLE bq_business_schedule_operations (
    operation_id TEXT PRIMARY KEY,
    desktop_instance_id TEXT NOT NULL,
    desktop_session_id TEXT NOT NULL,
    auth_session_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    identity_generation INTEGER NOT NULL CHECK (identity_generation > 0),
    client_operation_id TEXT NOT NULL,
    actor_user_id TEXT NOT NULL,
    form_revision INTEGER NOT NULL CHECK (form_revision >= 0),
    attachment_batch_id TEXT,
    request_fingerprint TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('IN_FLIGHT','COMPLETED','OUTCOME_UNKNOWN','FAILED')),
    result_revision INTEGER,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX idx_bq_business_schedule_operations_binding
    ON bq_business_schedule_operations(
        desktop_instance_id, desktop_session_id, auth_session_id, tenant_id,
        identity_generation, client_operation_id
    );
CREATE INDEX idx_bq_business_schedule_operations_state
    ON bq_business_schedule_operations(state, updated_at);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_business_schedule_operations','__table__','日程创建操作的耐久幂等状态，不保存 OA Token、文件编号或表单正文'),
('bq_business_schedule_operations','operation_id','服务端从身份绑定和客户端操作标识派生的稳定摘要'),
('bq_business_schedule_operations','desktop_instance_id','绑定的桌面进程实例标识'),
('bq_business_schedule_operations','desktop_session_id','绑定的桌面会话标识'),
('bq_business_schedule_operations','auth_session_id','绑定的 OA 会话标识'),
('bq_business_schedule_operations','tenant_id','绑定的 OA 租户标识'),
('bq_business_schedule_operations','identity_generation','创建操作时的身份版本'),
('bq_business_schedule_operations','client_operation_id','桌面端生成的单次创建操作标识'),
('bq_business_schedule_operations','actor_user_id','服务端 READY 身份中的实际操作用户标识'),
('bq_business_schedule_operations','form_revision','创建前重新核验的表单选项版本'),
('bq_business_schedule_operations','attachment_batch_id','绑定的附件批次标识；无附件时为空'),
('bq_business_schedule_operations','request_fingerprint','身份、表单、附件批次和创建参数的单向请求指纹，不含表单正文'),
('bq_business_schedule_operations','state','创建操作状态：发送前为 IN_FLIGHT，完成、结果未知或确定失败后进入对应终态'),
('bq_business_schedule_operations','result_revision','完成后返回给桌面端的服务端修订号'),
('bq_business_schedule_operations','created_at','操作首次创建时间'),
('bq_business_schedule_operations','updated_at','操作最近状态更新时间');
