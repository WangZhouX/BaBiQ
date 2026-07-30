-- 为 OA 登录/恢复/刷新安装事务增加服务端归属和 90 秒租约，迟到结果只能被 CAS 丢弃。
ALTER TABLE bq_business_oa_sessions ADD COLUMN installation_id TEXT;
ALTER TABLE bq_business_oa_sessions ADD COLUMN installation_owner_desktop_instance_id TEXT;
ALTER TABLE bq_business_oa_sessions ADD COLUMN installation_owner_desktop_session_id TEXT;
ALTER TABLE bq_business_oa_sessions ADD COLUMN installation_target_generation INTEGER NOT NULL DEFAULT 0 CHECK (installation_target_generation >= 0);
ALTER TABLE bq_business_oa_sessions ADD COLUMN installation_expires_at TEXT;

CREATE UNIQUE INDEX ux_bq_business_oa_sessions_installation
    ON bq_business_oa_sessions(installation_id)
    WHERE installation_id IS NOT NULL;

CREATE INDEX idx_bq_business_oa_sessions_installation_expiry
    ON bq_business_oa_sessions(phase, installation_expires_at);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_business_oa_sessions','installation_id','服务端生成的一次性 OA 安装事务标识，不接受客户端指定。'),
('bq_business_oa_sessions','installation_owner_desktop_instance_id','安装事务所属桌面实例，提交 READY 时必须匹配当前可信连接。'),
('bq_business_oa_sessions','installation_owner_desktop_session_id','安装事务所属桌面会话，提交 READY 时必须匹配当前可信连接。'),
('bq_business_oa_sessions','installation_target_generation','安装事务开始前的会话代次，迟到结果必须通过代次 CAS。'),
('bq_business_oa_sessions','installation_expires_at','安装事务过期时间，默认从开始时间起 90 秒。');
