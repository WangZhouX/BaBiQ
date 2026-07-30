-- 业务桌面附件票据、批次和资源句柄的服务端状态；不保存密码、OA Token、远程正文或原始 URL。
CREATE TABLE bq_business_attachment_batches (
    batch_id TEXT PRIMARY KEY,
    desktop_instance_id TEXT NOT NULL,
    desktop_session_id TEXT NOT NULL,
    auth_session_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    identity_generation INTEGER NOT NULL CHECK (identity_generation > 0),
    operation TEXT NOT NULL CHECK (operation = 'SCHEDULE_CREATE'),
    client_operation_id TEXT NOT NULL,
    actor_user_id TEXT NOT NULL,
    scope TEXT NOT NULL CHECK (scope IN ('PERSONAL','TEAM')),
    team_id TEXT,
    schedule_type_id TEXT NOT NULL,
    parent_relation_type TEXT NOT NULL CHECK (parent_relation_type IN ('CASE','CUSTOMER','VISIT','SERVICE')),
    parent_resource_id TEXT NOT NULL,
    form_revision TEXT NOT NULL,
    declaration_secret_ref TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING','READY','CONSUMING','CONSUMED','FAILED','OUTCOME_UNKNOWN','REVOKED')),
    file_id_secret_ref TEXT,
    expires_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE bq_business_attachment_tickets (
    ticket_id TEXT PRIMARY KEY,
    batch_id TEXT NOT NULL UNIQUE,
    desktop_instance_id TEXT NOT NULL,
    desktop_session_id TEXT NOT NULL,
    auth_session_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    identity_generation INTEGER NOT NULL CHECK (identity_generation > 0),
    state TEXT NOT NULL CHECK (state IN ('ISSUED','CLAIMED','IN_FLIGHT','SUCCEEDED','REJECTED','OUTCOME_UNKNOWN','EXPIRED','REVOKED')),
    expires_at TEXT NOT NULL,
    claimed_at TEXT,
    completed_at TEXT,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (batch_id) REFERENCES bq_business_attachment_batches(batch_id) ON DELETE CASCADE
);

CREATE TABLE bq_business_resource_handles (
    handle_id TEXT PRIMARY KEY,
    desktop_instance_id TEXT NOT NULL,
    desktop_session_id TEXT NOT NULL,
    auth_session_id TEXT NOT NULL,
    tenant_id TEXT NOT NULL,
    identity_generation INTEGER NOT NULL CHECK (identity_generation > 0),
    media_type TEXT NOT NULL,
    content_length INTEGER NOT NULL CHECK (content_length > 0),
    storage_ref TEXT NOT NULL,
    policy TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    revoked_at TEXT
);

CREATE TABLE bq_business_attachment_secret_cleanup (
    secret_ref TEXT PRIMARY KEY,
    secret_kind TEXT NOT NULL CHECK (secret_kind IN ('FILE_IDS','DECLARATION')),
    reason_code TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    last_attempt_at TEXT,
    last_result_code TEXT
);

CREATE INDEX idx_bq_business_attachment_batches_scope_state
    ON bq_business_attachment_batches(desktop_instance_id, desktop_session_id, identity_generation, state, updated_at DESC);
CREATE INDEX idx_bq_business_attachment_tickets_state_expiry
    ON bq_business_attachment_tickets(state, expires_at);
CREATE INDEX idx_bq_business_resource_handles_scope_expiry
    ON bq_business_resource_handles(desktop_instance_id, desktop_session_id, identity_generation, expires_at);
CREATE INDEX idx_bq_business_attachment_secret_cleanup_updated
    ON bq_business_attachment_secret_cleanup(updated_at, secret_ref);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_business_attachment_secret_cleanup','__table__','附件 SecretStore 不透明引用的耐久删除重试记录，不保存文件编号、文件名或声明正文'),
('bq_business_attachment_secret_cleanup','secret_ref','等待删除的 SecretStore 不透明引用'),
('bq_business_attachment_secret_cleanup','secret_kind','固定的附件密钥类型：FILE_IDS 或 DECLARATION'),
('bq_business_attachment_secret_cleanup','reason_code','创建清理记录的固定内部原因码，不含异常正文'),
('bq_business_attachment_secret_cleanup','attempt_count','SecretStore 删除失败的累计次数'),
('bq_business_attachment_secret_cleanup','created_at','清理记录创建时间'),
('bq_business_attachment_secret_cleanup','updated_at','清理记录最近更新时间'),
('bq_business_attachment_secret_cleanup','last_attempt_at','最近一次删除失败时间，尚未失败时为空'),
('bq_business_attachment_secret_cleanup','last_result_code','最近一次删除失败的固定结果码，不含异常正文');

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_business_attachment_batches','__table__','业务桌面附件批次的服务端状态，不保存 OA 文件编号或文件正文'),
('bq_business_attachment_batches','batch_id','服务端生成的附件批次不透明标识'),
('bq_business_attachment_batches','desktop_instance_id','绑定的桌面进程实例标识'),
('bq_business_attachment_batches','desktop_session_id','绑定的桌面会话标识'),
('bq_business_attachment_batches','auth_session_id','绑定的 OA 会话标识'),
('bq_business_attachment_batches','tenant_id','绑定的 OA 租户标识'),
('bq_business_attachment_batches','identity_generation','创建批次时的身份版本'),
('bq_business_attachment_batches','operation','允许消费批次的固定业务操作'),
('bq_business_attachment_batches','client_operation_id','桌面端生成的业务幂等操作标识'),
('bq_business_attachment_batches','actor_user_id','服务端从 READY 身份绑定的实际操作用户标识'),
('bq_business_attachment_batches','scope','服务端核验后的个人或团队日程范围'),
('bq_business_attachment_batches','team_id','服务端核验后的团队标识，个人范围为空'),
('bq_business_attachment_batches','schedule_type_id','准备附件时核验通过的日程类型标识'),
('bq_business_attachment_batches','parent_relation_type','服务端核验通过的父资源关系类型'),
('bq_business_attachment_batches','parent_resource_id','服务端核验后的父资源标识'),
('bq_business_attachment_batches','form_revision','表单选项版本'),
('bq_business_attachment_batches','declaration_secret_ref','上传声明的 SecretStore 不透明引用，SQLite 不保存文件名或摘要明文'),
('bq_business_attachment_batches','state','附件批次状态机状态'),
('bq_business_attachment_batches','file_id_secret_ref','OA 文件编号的 SecretStore 不透明引用，不保存文件编号明文'),
('bq_business_attachment_batches','expires_at','附件批次过期时间'),
('bq_business_attachment_batches','created_at','附件批次创建时间'),
('bq_business_attachment_batches','updated_at','附件批次最近更新时间'),
('bq_business_attachment_tickets','__table__','业务桌面单次上传票据状态，不保存票据明文'),
('bq_business_attachment_tickets','ticket_id','服务端生成的票据摘要标识'),
('bq_business_attachment_tickets','batch_id','票据绑定的唯一附件批次'),
('bq_business_attachment_tickets','desktop_instance_id','绑定的桌面进程实例标识'),
('bq_business_attachment_tickets','desktop_session_id','绑定的桌面会话标识'),
('bq_business_attachment_tickets','auth_session_id','绑定的 OA 会话标识'),
('bq_business_attachment_tickets','tenant_id','绑定的 OA 租户标识'),
('bq_business_attachment_tickets','identity_generation','票据签发时的身份版本'),
('bq_business_attachment_tickets','state','上传票据状态机状态'),
('bq_business_attachment_tickets','expires_at','上传票据过期时间'),
('bq_business_attachment_tickets','claimed_at','票据被单次领取的时间'),
('bq_business_attachment_tickets','completed_at','上传完成或终止的时间'),
('bq_business_attachment_tickets','updated_at','票据最近更新时间'),
('bq_business_resource_handles','__table__','业务桌面二进制资源句柄索引，不保存远程 URL'),
('bq_business_resource_handles','handle_id','服务端生成的资源不透明句柄'),
('bq_business_resource_handles','desktop_instance_id','绑定的桌面进程实例标识'),
('bq_business_resource_handles','desktop_session_id','绑定的桌面会话标识'),
('bq_business_resource_handles','auth_session_id','绑定的 OA 会话标识'),
('bq_business_resource_handles','tenant_id','绑定的 OA 租户标识'),
('bq_business_resource_handles','identity_generation','资源注册时的身份版本'),
('bq_business_resource_handles','media_type','服务端确认的安全媒体类型'),
('bq_business_resource_handles','content_length','服务端确认的资源字节数'),
('bq_business_resource_handles','storage_ref','受控本地存储引用，不是远程 URL'),
('bq_business_resource_handles','policy','资源读取策略'),
('bq_business_resource_handles','created_at','资源句柄创建时间'),
('bq_business_resource_handles','expires_at','资源句柄过期时间'),
('bq_business_resource_handles','revoked_at','资源句柄撤销时间');
