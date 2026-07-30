-- OA SecretStore 引用的耐久清理 tombstone；只保存不透明引用与固定审计码，不保存任何凭据内容。
-- 本表故意不对 OA 会话建立外键，确保会话记录删除后待清理引用仍可被后续重试发现。
CREATE TABLE bq_business_oa_secret_cleanup (
    secret_ref TEXT NOT NULL PRIMARY KEY,
    auth_session_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('RESERVED','DELETE_PENDING')),
    reason_code TEXT NOT NULL,
    operation_id TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    last_attempt_at TEXT,
    last_result_code TEXT
);

CREATE INDEX idx_bq_business_oa_secret_cleanup_state_updated
    ON bq_business_oa_secret_cleanup(state, updated_at, secret_ref);
CREATE INDEX idx_bq_business_oa_secret_cleanup_session
    ON bq_business_oa_secret_cleanup(auth_session_id, state);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_business_oa_secret_cleanup','__table__','OA SecretStore 不透明引用的耐久清理 tombstone；不保存访问令牌、刷新令牌或其他凭据内容。'),
('bq_business_oa_secret_cleanup','secret_ref','待保存或待删除密钥的 SecretStore 不透明引用；严禁保存密钥内容。'),
('bq_business_oa_secret_cleanup','auth_session_id','引用所属 OA 认证会话标识；会话删除后本记录仍独立保留。'),
('bq_business_oa_secret_cleanup','state','清理状态；RESERVED 表示已预留引用，DELETE_PENDING 表示等待幂等删除。'),
('bq_business_oa_secret_cleanup','reason_code','创建或转入清理状态的固定内部原因码；不含远程错误正文。'),
('bq_business_oa_secret_cleanup','operation_id','可选的业务操作标识，用于关联同一次非敏感操作。'),
('bq_business_oa_secret_cleanup','attempt_count','删除失败次数；每次失败原子递增。'),
('bq_business_oa_secret_cleanup','created_at','清理 tombstone 首次创建时间。'),
('bq_business_oa_secret_cleanup','updated_at','清理 tombstone 最近状态更新时间。'),
('bq_business_oa_secret_cleanup','last_attempt_at','最近一次失败删除尝试时间；尚未失败时为空。'),
('bq_business_oa_secret_cleanup','last_result_code','最近一次删除失败的固定内部结果码；不含异常正文。');
