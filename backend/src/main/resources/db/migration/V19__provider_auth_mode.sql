-- P7 Provider 多认证模式：为 Provider 配置增加认证模式字段。
-- SQLite 不支持原生 COMMENT，因此新增字段仍同步写入 bq_schema_comments。

ALTER TABLE bq_provider_configs
    ADD COLUMN auth_mode TEXT NOT NULL DEFAULT 'api_key';

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_provider_configs', 'auth_mode', 'Provider 认证模式：api_key 表示使用本地 SecretStore 中的 API Key，oauth_cli 表示通过 Anthropic ant CLI 本地 OAuth 凭证获取访问令牌。');
