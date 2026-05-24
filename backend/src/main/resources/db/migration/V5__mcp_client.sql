-- P2-6 MCP Client：保存本地 stdio MCP server 配置快照和工具目录。
-- SQLite 不支持 COMMENT，本迁移继续把每个新增表和字段写入 bq_schema_comments。

-- 表：bq_mcp_servers，保存后端受信任 MCP server 配置和最近连接状态。
CREATE TABLE IF NOT EXISTS bq_mcp_servers (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC 协议。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- MCP server 稳定标识，例如 local-filesystem。
    server_id TEXT NOT NULL UNIQUE,
    -- 用户可读展示名称。
    display_name TEXT NOT NULL,
    -- MCP 传输类型；P2-6 仅支持 stdio。
    transport TEXT NOT NULL,
    -- stdio 命令；来自受信任配置，不由 UI 任意输入后直接执行。
    command TEXT NOT NULL,
    -- stdio 参数 JSON 数组。
    args_json TEXT NOT NULL,
    -- stdio 进程工作目录；为空表示继承后端进程目录。
    cwd TEXT,
    -- 是否启用；SQLite 使用 0/1 保存。
    enabled INTEGER NOT NULL DEFAULT 1,
    -- 当前连接状态，disabled、configured、connected 或 failed。
    status TEXT NOT NULL,
    -- 最近一次连接或刷新失败原因；成功时为空。
    last_error TEXT,
    -- 创建时间，ISO-8601 字符串。
    created_at TEXT NOT NULL,
    -- 更新时间，ISO-8601 字符串。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_mcp_servers_status
    ON bq_mcp_servers(status, updated_at DESC);

-- 表：bq_mcp_tools，保存 MCP server 最近一次 listTools 返回的工具目录。
CREATE TABLE IF NOT EXISTS bq_mcp_tools (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC 协议。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 工具所属 MCP server id。
    server_id TEXT NOT NULL,
    -- MCP server 原始工具名。
    tool_name TEXT NOT NULL,
    -- BaBiQ 内部命名空间工具名，例如 mcp.local-filesystem.read_file。
    namespaced_name TEXT NOT NULL UNIQUE,
    -- 工具描述，供模型和设置页理解用途。
    description TEXT,
    -- MCP 工具 input schema JSON。
    schema_json TEXT NOT NULL,
    -- 是否启用；P2-6 默认随 server 工具列表启用。
    enabled INTEGER NOT NULL DEFAULT 1,
    -- 工具列表刷新时间，ISO-8601 字符串。
    updated_at TEXT NOT NULL,
    FOREIGN KEY (server_id) REFERENCES bq_mcp_servers(server_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_bq_mcp_tools_server_tool
    ON bq_mcp_tools(server_id, tool_name ASC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_mcp_servers', '__table__', '保存后端受信任 MCP server 配置快照和最近连接状态，用于设置页展示和刷新诊断。'),
('bq_mcp_servers', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_mcp_servers', 'server_id', 'MCP server 稳定标识，例如 local-filesystem。'),
('bq_mcp_servers', 'display_name', '用户可读展示名称。'),
('bq_mcp_servers', 'transport', 'MCP 传输类型；P2-6 仅支持 stdio。'),
('bq_mcp_servers', 'command', 'stdio 命令；来自后端受信任配置，不由 UI 任意输入后直接执行。'),
('bq_mcp_servers', 'args_json', 'stdio 参数 JSON 数组。'),
('bq_mcp_servers', 'cwd', 'stdio 进程工作目录；为空表示继承后端进程目录。'),
('bq_mcp_servers', 'enabled', '是否启用该 MCP server；SQLite 使用 0/1 保存。'),
('bq_mcp_servers', 'status', '当前连接状态，disabled、configured、connected 或 failed。'),
('bq_mcp_servers', 'last_error', '最近一次连接或刷新失败原因；成功时为空。'),
('bq_mcp_servers', 'created_at', '配置创建时间，使用 ISO-8601 字符串保存。'),
('bq_mcp_servers', 'updated_at', '配置或状态更新时间，使用 ISO-8601 字符串保存。'),
('bq_mcp_tools', '__table__', '保存 MCP server 最近一次 listTools 返回的工具目录，用于动态注册和设置页展示。'),
('bq_mcp_tools', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_mcp_tools', 'server_id', '工具所属 MCP server id。'),
('bq_mcp_tools', 'tool_name', 'MCP server 原始工具名。'),
('bq_mcp_tools', 'namespaced_name', 'BaBiQ 内部命名空间工具名，例如 mcp.local-filesystem.read_file。'),
('bq_mcp_tools', 'description', '工具描述，供模型和设置页理解用途。'),
('bq_mcp_tools', 'schema_json', 'MCP 工具 input schema JSON。'),
('bq_mcp_tools', 'enabled', '工具是否启用；P2-6 默认随 server 工具列表启用。'),
('bq_mcp_tools', 'updated_at', '工具列表刷新时间，使用 ISO-8601 字符串保存。');
