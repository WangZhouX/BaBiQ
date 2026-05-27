-- P3-5 按需能力装配和记忆检索：新增能力目录、能力搜索事件和长期记忆检索事件。
-- SQLite 仍是事实源；模型每轮只收到经过 Planner 筛选后的能力摘要和记忆引用。

-- 表：bq_capabilities，保存 BaBiQ 已知 local tool、MCP tool 和本地 Skill 元数据。
CREATE TABLE IF NOT EXISTS bq_capabilities (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 稳定能力 id，例如 local.exec_shell、mcp.filesystem.read_file、skill.superpowers.test_driven_development。
    capability_id TEXT NOT NULL UNIQUE,
    -- 能力类型，LOCAL_TOOL、MCP_TOOL 或 SKILL。
    type TEXT NOT NULL,
    -- 能力命名空间，用于隔离 local、mcp server 和 skill 来源。
    namespace TEXT NOT NULL,
    -- 工具或 skill 的短名称。
    name TEXT NOT NULL,
    -- 桌面端展示名称。
    display_name TEXT NOT NULL,
    -- 给模型和用户看的短说明，不包含敏感参数值。
    description TEXT NOT NULL,
    -- 来源 id；local 工具为 local，MCP 为 serverId，Skill 为目录 id。
    source_id TEXT NOT NULL,
    -- 工具 schema 或 Skill 正文摘要 hash，用于识别元数据变化。
    schema_hash TEXT,
    -- 搜索索引文本，包含名称、说明和标签，不包含真实 secret。
    search_text TEXT NOT NULL,
    -- 暴露模式，VISIBLE 表示默认给模型，DEFERRED 表示需 tool_search，DISABLED 表示禁用。
    exposure_mode TEXT NOT NULL,
    -- 用户是否启用该能力；0 表示完全不进入搜索和模型可见列表。
    enabled INTEGER NOT NULL DEFAULT 1,
    -- 最近一次扫描发现该能力的时间。
    last_seen_at TEXT NOT NULL,
    -- 记录创建时间。
    created_at TEXT NOT NULL,
    -- 最近更新时间。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_capabilities_enabled_mode
    ON bq_capabilities(enabled, exposure_mode, type);
CREATE INDEX IF NOT EXISTS idx_bq_capabilities_source
    ON bq_capabilities(type, source_id);

-- 表：bq_capability_search_events，保存每次 tool_search 或手动搜索的审计记录。
CREATE TABLE IF NOT EXISTS bq_capability_search_events (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层搜索事件 id，以 capev_ 开头。
    event_id TEXT NOT NULL UNIQUE,
    -- 来源 thread id；手动设置页搜索可为空。
    thread_id TEXT,
    -- 来源 turn id；手动设置页搜索可为空。
    turn_id TEXT,
    -- 搜索词或模型提出的能力需求。
    query_text TEXT NOT NULL,
    -- 搜索策略，例如 FALLBACK_LEXICAL、LUCENE 或 SPRING_AI_TOOL_SEARCH。
    strategy TEXT NOT NULL,
    -- 返回候选数量。
    result_count INTEGER NOT NULL DEFAULT 0,
    -- 最终返回或装配的能力 id JSON 数组。
    selected_capability_ids_json TEXT NOT NULL,
    -- 被过滤、禁用或未命中的能力 id JSON 数组。
    rejected_capability_ids_json TEXT NOT NULL,
    -- 事件创建时间。
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_capability_search_events_thread_turn
    ON bq_capability_search_events(thread_id, turn_id);
CREATE INDEX IF NOT EXISTS idx_bq_capability_search_events_created_at
    ON bq_capability_search_events(created_at DESC);

-- 表：bq_memory_retrieval_events，保存长期记忆检索注入过程。
CREATE TABLE IF NOT EXISTS bq_memory_retrieval_events (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层检索事件 id，以 memret_ 开头。
    retrieval_id TEXT NOT NULL UNIQUE,
    -- 来源 thread id。
    thread_id TEXT NOT NULL,
    -- 来源 turn id。
    turn_id TEXT NOT NULL,
    -- 当前上下文快照 id；快照落库失败时可为空。
    snapshot_id TEXT,
    -- 从本轮用户输入派生的检索查询。
    query_text TEXT NOT NULL,
    -- 检索策略，LEXICAL、VECTOR_STORE 或 HYBRID。
    strategy TEXT NOT NULL,
    -- 初筛候选数量。
    candidate_count INTEGER NOT NULL DEFAULT 0,
    -- 被注入的 artifact/candidate/reference id JSON 数组。
    selected_references_json TEXT NOT NULL,
    -- 本次注入片段 token 估算。
    token_estimate INTEGER NOT NULL DEFAULT 0,
    -- 污染或低可信标记 JSON 数组；为空数组表示未命中风险。
    pollution_flags_json TEXT NOT NULL,
    -- 事件创建时间。
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_memory_retrieval_events_thread_turn
    ON bq_memory_retrieval_events(thread_id, turn_id);
CREATE INDEX IF NOT EXISTS idx_bq_memory_retrieval_events_created_at
    ON bq_memory_retrieval_events(created_at DESC);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_capabilities', '__table__', '保存 BaBiQ 已知 local tool、MCP tool 和本地 Skill 元数据，是按需能力装配的事实源。'),
('bq_capabilities', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_capabilities', 'capability_id', '稳定能力 id，例如 local.exec_shell、mcp.filesystem.read_file、skill.superpowers.test_driven_development。'),
('bq_capabilities', 'type', '能力类型，LOCAL_TOOL、MCP_TOOL 或 SKILL。'),
('bq_capabilities', 'namespace', '能力命名空间，用于隔离 local、MCP server 和 skill 来源。'),
('bq_capabilities', 'name', '工具或 skill 的短名称。'),
('bq_capabilities', 'display_name', '桌面端展示名称。'),
('bq_capabilities', 'description', '给模型和用户看的短说明，不包含敏感参数值。'),
('bq_capabilities', 'source_id', '来源 id；local 工具为 local，MCP 为 serverId，Skill 为目录 id。'),
('bq_capabilities', 'schema_hash', '工具 schema 或 Skill 正文摘要 hash，用于识别元数据变化。'),
('bq_capabilities', 'search_text', '搜索索引文本，包含名称、说明和标签，不包含真实 secret。'),
('bq_capabilities', 'exposure_mode', '暴露模式，VISIBLE 表示默认给模型，DEFERRED 表示需 tool_search，DISABLED 表示禁用。'),
('bq_capabilities', 'enabled', '用户是否启用该能力；0 表示完全不进入搜索和模型可见列表。'),
('bq_capabilities', 'last_seen_at', '最近一次扫描发现该能力的时间。'),
('bq_capabilities', 'created_at', '记录创建时间。'),
('bq_capabilities', 'updated_at', '最近更新时间。'),
('bq_capability_search_events', '__table__', '保存每次 tool_search 或设置页手动能力搜索的审计记录。'),
('bq_capability_search_events', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_capability_search_events', 'event_id', '协议层搜索事件 id，以 capev_ 开头。'),
('bq_capability_search_events', 'thread_id', '来源 thread id；手动设置页搜索可为空。'),
('bq_capability_search_events', 'turn_id', '来源 turn id；手动设置页搜索可为空。'),
('bq_capability_search_events', 'query_text', '搜索词或模型提出的能力需求。'),
('bq_capability_search_events', 'strategy', '搜索策略，例如 FALLBACK_LEXICAL、LUCENE 或 SPRING_AI_TOOL_SEARCH。'),
('bq_capability_search_events', 'result_count', '返回候选数量。'),
('bq_capability_search_events', 'selected_capability_ids_json', '最终返回或装配的能力 id JSON 数组。'),
('bq_capability_search_events', 'rejected_capability_ids_json', '被过滤、禁用或未命中的能力 id JSON 数组。'),
('bq_capability_search_events', 'created_at', '事件创建时间。'),
('bq_memory_retrieval_events', '__table__', '保存长期记忆检索注入过程，便于审计模型看到过哪些记忆片段。'),
('bq_memory_retrieval_events', 'id', '数据库内部主键，自增，不暴露给协议层。'),
('bq_memory_retrieval_events', 'retrieval_id', '协议层检索事件 id，以 memret_ 开头。'),
('bq_memory_retrieval_events', 'thread_id', '来源 thread id。'),
('bq_memory_retrieval_events', 'turn_id', '来源 turn id。'),
('bq_memory_retrieval_events', 'snapshot_id', '当前上下文快照 id；快照落库失败时可为空。'),
('bq_memory_retrieval_events', 'query_text', '从本轮用户输入派生的检索查询。'),
('bq_memory_retrieval_events', 'strategy', '检索策略，LEXICAL、VECTOR_STORE 或 HYBRID。'),
('bq_memory_retrieval_events', 'candidate_count', '初筛候选数量。'),
('bq_memory_retrieval_events', 'selected_references_json', '被注入的 artifact/candidate/reference id JSON 数组。'),
('bq_memory_retrieval_events', 'token_estimate', '本次注入片段 token 估算。'),
('bq_memory_retrieval_events', 'pollution_flags_json', '污染或低可信标记 JSON 数组；为空数组表示未命中风险。'),
('bq_memory_retrieval_events', 'created_at', '事件创建时间。');
