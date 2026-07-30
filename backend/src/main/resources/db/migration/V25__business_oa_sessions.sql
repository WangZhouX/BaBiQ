-- OA 会话与认证事件索引：只保存非敏感归属和 SecretStore 引用，不保存 Token、密码或远程正文。
CREATE TABLE bq_business_oa_sessions (
    auth_session_id TEXT PRIMARY KEY,
    desktop_instance_id TEXT NOT NULL,
    desktop_session_id TEXT NOT NULL,
    user_id TEXT,
    tenant_id TEXT,
    platform_id TEXT,
    phase TEXT NOT NULL CHECK (phase IN ('SIGNED_OUT','AUTHENTICATING','RESTORING','INSTALLING','READY','DETACHED','REVOKING','REVOKED')),
    generation INTEGER NOT NULL CHECK (generation >= 0),
    active_credential_ref TEXT,
    staged_credential_ref TEXT,
    credential_version INTEGER NOT NULL DEFAULT 0 CHECK (credential_version >= 0),
    install_started_at TEXT,
    installed_at TEXT,
    detached_at TEXT,
    revoked_at TEXT,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX ux_bq_business_oa_sessions_desktop
    ON bq_business_oa_sessions(desktop_instance_id, desktop_session_id);
CREATE INDEX idx_bq_business_oa_sessions_phase_updated
    ON bq_business_oa_sessions(phase, updated_at DESC);

CREATE TABLE bq_business_auth_events (
    event_id TEXT PRIMARY KEY,
    auth_session_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    result_code TEXT NOT NULL,
    identity_generation INTEGER NOT NULL CHECK (identity_generation >= 0),
    occurred_at TEXT NOT NULL,
    FOREIGN KEY (auth_session_id) REFERENCES bq_business_oa_sessions(auth_session_id) ON DELETE CASCADE
);

CREATE INDEX idx_bq_business_auth_events_session_time
    ON bq_business_auth_events(auth_session_id, occurred_at DESC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_business_oa_sessions','__table__','OA 桌面认证会话的非敏感索引；不保存访问令牌、刷新令牌、密码或远程正文。'),
('bq_business_oa_sessions','auth_session_id','服务端生成的 OA 会话标识；绑定一个桌面实例和桌面会话。'),
('bq_business_oa_sessions','desktop_instance_id','桌面应用实例标识；用于隔离不同本地桌面进程。'),
('bq_business_oa_sessions','desktop_session_id','桌面连接会话标识；用于重连附着校验。'),
('bq_business_oa_sessions','user_id','OA 用户稳定标识；仅保存归属索引。'),
('bq_business_oa_sessions','tenant_id','OA 租户稳定标识；仅保存归属索引。'),
('bq_business_oa_sessions','platform_id','OA 平台标识；用于平台隔离。'),
('bq_business_oa_sessions','phase','服务端认证阶段；REVOKED 为不可恢复终态。'),
('bq_business_oa_sessions','generation','会话并发版本；用于登录、刷新、撤销和恢复 CAS。'),
('bq_business_oa_sessions','active_credential_ref','当前生效凭据的 SecretStore 引用；不含令牌明文。'),
('bq_business_oa_sessions','staged_credential_ref','安装事务暂存凭据的 SecretStore 引用；成功 CAS 后才转为 active。'),
('bq_business_oa_sessions','credential_version','凭据封装版本；用于拒绝旧格式恢复。'),
('bq_business_oa_sessions','install_started_at','认证安装事务开始时间。'),
('bq_business_oa_sessions','installed_at','最近一次 READY 安装完成时间。'),
('bq_business_oa_sessions','detached_at','最近一次 WebSocket 脱离时间；脱离不等同退出。'),
('bq_business_oa_sessions','revoked_at','会话撤销时间；存在后不可恢复。'),
('bq_business_oa_sessions','updated_at','索引最近更新时间。'),
('bq_business_auth_events','__table__','OA 认证安全审计事件；只记录固定事件和结果码。'),
('bq_business_auth_events','event_id','认证事件唯一标识。'),
('bq_business_auth_events','auth_session_id','事件所属 OA 会话标识。'),
('bq_business_auth_events','event_type','固定认证事件类型；不含远程正文。'),
('bq_business_auth_events','result_code','固定稳定结果码；不含 OA message 或 traceId。'),
('bq_business_auth_events','identity_generation','事件发生时的身份版本。'),
('bq_business_auth_events','occurred_at','事件发生时间。');
