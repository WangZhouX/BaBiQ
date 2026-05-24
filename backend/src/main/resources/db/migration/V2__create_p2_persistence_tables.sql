-- P2 本地持久化底座：SQLite 不支持原生 COMMENT，因此每个业务表和字段都要同时写入 bq_schema_comments。
-- 本迁移只建立结构和注释，不导入运行期数据；运行期写入由 MyBatis-Plus repository 负责。

-- 表：bq_schema_comments，保存 BaBiQ 数据库结构的中文说明，弥补 SQLite 没有 COMMENT 的限制。
CREATE TABLE IF NOT EXISTS bq_schema_comments (
    -- 主键，自增，便于后续人工排查某一条注释记录。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 被说明的表名，例如 bq_threads。
    table_name TEXT NOT NULL,
    -- 被说明的字段名；表级说明固定使用 __table__。
    column_name TEXT NOT NULL,
    -- 中文说明文本，必须能让实现者理解字段业务含义和上下游关系。
    comment TEXT NOT NULL,
    -- 注释记录创建时间，使用 ISO-8601 文本保存，避免 SQLite 时区歧义。
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 注释记录更新时间，后续字段说明调整时同步刷新。
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (table_name, column_name)
);

-- 表：bq_threads，保存一次用户会话线程的可恢复外壳。
CREATE TABLE IF NOT EXISTS bq_threads (
    -- 数据库内部主键，自增，不暴露给 JSON-RPC 协议。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层 threadId，桌面端、运行期和历史查询都使用它定位会话。
    thread_id TEXT NOT NULL UNIQUE,
    -- 会话标题，默认可由用户首条输入截断生成，后续 P2-2 支持重命名。
    title TEXT NOT NULL,
    -- 会话绑定的工作目录，用于最近列表过滤和恢复运行上下文。
    cwd TEXT NOT NULL,
    -- 当前会话默认 Provider 标识，例如 deepseek 或 openaiCompatible。
    provider_id TEXT NOT NULL,
    -- 当前会话默认模型名，例如 deepseek-v4-pro。
    model TEXT NOT NULL,
    -- 会话默认沙箱模式，写入时取自请求或全局设置快照。
    sandbox_mode TEXT NOT NULL,
    -- 会话默认审批策略，控制工具执行前是否需要 HITL。
    approval_policy TEXT NOT NULL,
    -- 会话状态，active 表示正常可见，archived 表示被软归档。
    status TEXT NOT NULL,
    -- 会话创建时间，用于历史排序和审计。
    created_at TEXT NOT NULL,
    -- 会话更新时间，任意 turn/item 写入后应前移，用于最近列表排序。
    updated_at TEXT NOT NULL,
    -- 软归档时间；为空代表默认最近列表仍可见。
    archived_at TEXT
);

-- 表：bq_turns，保存一次用户请求到 Agent 完成之间的运行回合。
CREATE TABLE IF NOT EXISTS bq_turns (
    -- 数据库内部主键，自增，不暴露给协议层。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层 turnId，前后端事件、恢复和成本摘要都通过它关联。
    turn_id TEXT NOT NULL UNIQUE,
    -- 所属 threadId，指向 bq_threads.thread_id。
    thread_id TEXT NOT NULL,
    -- turn 当前状态，例如 running、completed、failed。
    status TEXT NOT NULL,
    -- 用户本轮输入文本，用于恢复历史和后续摘要生成。
    input_text TEXT NOT NULL,
    -- 本轮实际工作目录快照，防止用户切换目录后误解历史运行位置。
    cwd TEXT NOT NULL,
    -- 本轮实际 Provider 标识快照。
    provider_id TEXT NOT NULL,
    -- 本轮实际模型名快照。
    model TEXT NOT NULL,
    -- 本轮实际沙箱模式快照。
    sandbox_mode TEXT NOT NULL,
    -- 本轮实际审批策略快照。
    approval_policy TEXT NOT NULL,
    -- turn 开始时间，用于恢复未完成运行和计算耗时。
    started_at TEXT NOT NULL,
    -- turn 完成时间；运行中或异常中断未收口时为空。
    completed_at TEXT,
    -- 失败原因；只有 failed 或恢复诊断场景需要写入。
    failure_reason TEXT,
    FOREIGN KEY (thread_id) REFERENCES bq_threads(thread_id) ON DELETE CASCADE
);

-- 表：bq_items，保存 turn 过程中的消息、工具调用、文件引用等协议 item。
CREATE TABLE IF NOT EXISTS bq_items (
    -- 数据库内部主键，自增，不暴露给协议层。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层 itemId，前端增量渲染和幂等写入使用它去重。
    item_id TEXT NOT NULL UNIQUE,
    -- 所属 threadId，便于不加载 turn 时按会话读取完整历史。
    thread_id TEXT NOT NULL,
    -- 所属 turnId，便于按运行回合回放 item。
    turn_id TEXT NOT NULL,
    -- item 类型，例如 userMessage、assistantMessage、toolCall、fileChange。
    type TEXT NOT NULL,
    -- item 在 thread 内的顺序号，用于恢复原始显示顺序。
    sequence_no INTEGER NOT NULL,
    -- item 的原始 JSON payload，保留协议扩展能力，避免频繁改表。
    payload_json TEXT NOT NULL,
    -- item 状态，例如 streaming、completed、failed。
    status TEXT NOT NULL,
    -- item 创建时间，用于审计和调试。
    created_at TEXT NOT NULL,
    -- item 更新时间，用于流式片段合并或状态更新。
    updated_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES bq_threads(thread_id) ON DELETE CASCADE,
    FOREIGN KEY (turn_id) REFERENCES bq_turns(turn_id) ON DELETE CASCADE
);

-- 表：bq_turn_summaries，保存 turn 结束后的 token、成本、耗时和工具统计。
CREATE TABLE IF NOT EXISTS bq_turn_summaries (
    -- 数据库内部主键，自增，不暴露给协议层。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 所属 turnId，一轮只允许一条摘要。
    turn_id TEXT NOT NULL UNIQUE,
    -- 输入 token 数，来自模型 usage 或后端估算。
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    -- 输出 token 数，来自模型 usage 或后端估算。
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    -- 美元成本估算，使用小数文本兼容 SQLite NUMERIC 存储。
    cost_usd NUMERIC NOT NULL DEFAULT 0,
    -- 本轮耗时毫秒数，由后端 turn 计时器写入。
    duration_ms INTEGER NOT NULL DEFAULT 0,
    -- 本轮工具调用次数，用于桌面端运行反馈和本地观测。
    tool_count INTEGER NOT NULL DEFAULT 0,
    -- 摘要生成时间，只在 turn 结束后写入。
    created_at TEXT NOT NULL,
    FOREIGN KEY (turn_id) REFERENCES bq_turns(turn_id) ON DELETE CASCADE
);

-- 表：bq_approvals，保存 HITL 审批请求和用户决策。
CREATE TABLE IF NOT EXISTS bq_approvals (
    -- 数据库内部主键，自增，不暴露给协议层。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 协议层 approvalId，桌面端响应审批时带回该值。
    approval_id TEXT NOT NULL UNIQUE,
    -- 所属 threadId，便于按会话查询审批历史。
    thread_id TEXT NOT NULL,
    -- 所属 turnId，便于恢复某轮等待中的审批。
    turn_id TEXT NOT NULL,
    -- 请求执行的工具名，例如 shell 或 fileWrite。
    tool_name TEXT NOT NULL,
    -- 工具原始参数 JSON，必须当作不可信输入展示和审计。
    args_json TEXT NOT NULL,
    -- 用户编辑后的参数 JSON；未编辑时为空。
    edited_args_json TEXT,
    -- 用户决策，例如 approve、deny、always、edit。
    decision TEXT,
    -- 审批生效范围，例如 once、session 或 workspace。
    scope TEXT,
    -- 审批状态，例如 pending、resolved、expired。
    status TEXT NOT NULL,
    -- 审批请求创建时间。
    created_at TEXT NOT NULL,
    -- 审批完成时间；等待用户决策时为空。
    resolved_at TEXT,
    FOREIGN KEY (thread_id) REFERENCES bq_threads(thread_id) ON DELETE CASCADE,
    FOREIGN KEY (turn_id) REFERENCES bq_turns(turn_id) ON DELETE CASCADE
);

-- 表：bq_provider_configs，保存模型 Provider 配置，但只保存 secretRef，不保存明文 API Key。
CREATE TABLE IF NOT EXISTS bq_provider_configs (
    -- 数据库内部主键，自增，不暴露给 UI。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- Provider 标识，作为设置页和运行请求引用的稳定 key。
    provider_id TEXT NOT NULL UNIQUE,
    -- Provider 展示名称，例如 DeepSeek 或 OpenAI Compatible。
    display_name TEXT NOT NULL,
    -- Provider 类型，用于选择后端客户端适配器。
    type TEXT NOT NULL,
    -- OpenAI 兼容或厂商 API Base URL。
    base_url TEXT NOT NULL,
    -- 默认模型名，新建 turn 时作为初始模型。
    model TEXT NOT NULL,
    -- 密钥引用，只能指向 SecretStore，禁止保存明文 API Key。
    secret_ref TEXT,
    -- 上下文窗口大小，用于 UI 展示和后续 prompt 预算。
    context_window INTEGER NOT NULL DEFAULT 0,
    -- 是否启用；禁用后前端不应作为可选模型展示。
    enabled INTEGER NOT NULL DEFAULT 1,
    -- 配置创建时间。
    created_at TEXT NOT NULL,
    -- 配置更新时间。
    updated_at TEXT NOT NULL
);

-- 表：bq_app_settings，保存轻量全局设置，例如沙箱模式、审批策略和默认工作目录。
CREATE TABLE IF NOT EXISTS bq_app_settings (
    -- 数据库内部主键，自增，不暴露给 UI。
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 设置 key，使用点分命名，例如 sandbox.mode。
    setting_key TEXT NOT NULL UNIQUE,
    -- 设置值，统一保存为文本，由 service 按 value_type 解释。
    setting_value TEXT NOT NULL,
    -- 值类型，例如 string、boolean、number、json。
    value_type TEXT NOT NULL,
    -- 设置更新时间，用于 UI 刷新和冲突排查。
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bq_threads_cwd_updated_at ON bq_threads(cwd, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_bq_threads_status_updated_at ON bq_threads(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_bq_turns_thread_started_at ON bq_turns(thread_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_bq_items_thread_sequence ON bq_items(thread_id, sequence_no ASC);
CREATE INDEX IF NOT EXISTS idx_bq_items_turn_sequence ON bq_items(turn_id, sequence_no ASC);
CREATE INDEX IF NOT EXISTS idx_bq_approvals_turn_status ON bq_approvals(turn_id, status);

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_schema_comments', '__table__', '保存 BaBiQ 数据库表和字段的中文说明，补足 SQLite 不支持 COMMENT 的能力。'),
('bq_schema_comments', 'id', '注释记录主键，用于定位和排查单条结构说明。'),
('bq_schema_comments', 'table_name', '被说明的业务表名，例如 bq_threads。'),
('bq_schema_comments', 'column_name', '被说明的字段名；表级说明固定写入 __table__。'),
('bq_schema_comments', 'comment', '中文说明正文，解释字段含义、写入方、读取方和边界。'),
('bq_schema_comments', 'created_at', '注释记录创建时间，由 migration 写入。'),
('bq_schema_comments', 'updated_at', '注释记录更新时间，字段说明调整时同步刷新。'),
('bq_threads', '__table__', '保存用户会话线程，是历史列表、恢复和运行上下文的根记录。'),
('bq_threads', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_threads', 'thread_id', '协议层 threadId，桌面端和后端通过它定位会话。'),
('bq_threads', 'title', '会话标题，用于最近对话列表展示。'),
('bq_threads', 'cwd', '会话绑定的工作目录，用于隔离不同项目的历史。'),
('bq_threads', 'provider_id', '会话默认 Provider 标识，新 turn 默认继承。'),
('bq_threads', 'model', '会话默认模型名，新 turn 默认继承。'),
('bq_threads', 'sandbox_mode', '会话默认沙箱模式快照。'),
('bq_threads', 'approval_policy', '会话默认审批策略快照。'),
('bq_threads', 'status', '会话状态，active 可见，archived 软归档。'),
('bq_threads', 'created_at', '会话创建时间。'),
('bq_threads', 'updated_at', '会话最近更新时间，用于最近列表排序。'),
('bq_threads', 'archived_at', '会话软归档时间；为空代表未归档。'),
('bq_turns', '__table__', '保存每一轮用户请求和 Agent 执行过程的状态快照。'),
('bq_turns', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_turns', 'turn_id', '协议层 turnId，用于关联 item、审批和成本摘要。'),
('bq_turns', 'thread_id', '所属 threadId，指向 bq_threads.thread_id。'),
('bq_turns', 'status', 'turn 状态，例如 running、completed、failed。'),
('bq_turns', 'input_text', '本轮用户输入文本，用于历史恢复和审计。'),
('bq_turns', 'cwd', '本轮实际工作目录快照。'),
('bq_turns', 'provider_id', '本轮实际 Provider 标识快照。'),
('bq_turns', 'model', '本轮实际模型名快照。'),
('bq_turns', 'sandbox_mode', '本轮实际沙箱模式快照。'),
('bq_turns', 'approval_policy', '本轮实际审批策略快照。'),
('bq_turns', 'started_at', 'turn 开始时间。'),
('bq_turns', 'completed_at', 'turn 完成时间；未完成时为空。'),
('bq_turns', 'failure_reason', '失败原因；仅失败或恢复诊断场景写入。'),
('bq_items', '__table__', '保存会话中的协议 item，用于历史重放和 UI 渲染。'),
('bq_items', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_items', 'item_id', '协议层 itemId，用于幂等写入和前端更新。'),
('bq_items', 'thread_id', '所属 threadId，便于按会话读取 item。'),
('bq_items', 'turn_id', '所属 turnId，便于按运行回合读取 item。'),
('bq_items', 'type', 'item 类型，例如 userMessage、assistantMessage、toolCall。'),
('bq_items', 'sequence_no', 'item 在会话内的显示顺序。'),
('bq_items', 'payload_json', 'item 原始 JSON payload，保留协议扩展能力。'),
('bq_items', 'status', 'item 状态，例如 streaming、completed、failed。'),
('bq_items', 'created_at', 'item 创建时间。'),
('bq_items', 'updated_at', 'item 更新时间。'),
('bq_turn_summaries', '__table__', '保存 turn 结束后的 token、成本、耗时和工具统计。'),
('bq_turn_summaries', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_turn_summaries', 'turn_id', '所属 turnId，一轮只允许一条摘要。'),
('bq_turn_summaries', 'prompt_tokens', '输入 token 数，来自模型 usage 或估算。'),
('bq_turn_summaries', 'completion_tokens', '输出 token 数，来自模型 usage 或估算。'),
('bq_turn_summaries', 'cost_usd', '美元成本估算，供桌面端成本反馈展示。'),
('bq_turn_summaries', 'duration_ms', '本轮耗时毫秒数。'),
('bq_turn_summaries', 'tool_count', '本轮工具调用次数。'),
('bq_turn_summaries', 'created_at', '摘要生成时间。'),
('bq_approvals', '__table__', '保存 HITL 审批请求和用户决策历史。'),
('bq_approvals', 'id', '数据库内部主键，不暴露给协议层。'),
('bq_approvals', 'approval_id', '协议层 approvalId，桌面端响应审批时带回。'),
('bq_approvals', 'thread_id', '所属 threadId，便于按会话查询审批。'),
('bq_approvals', 'turn_id', '所属 turnId，便于恢复等待中的审批。'),
('bq_approvals', 'tool_name', '请求执行的工具名。'),
('bq_approvals', 'args_json', '工具原始参数 JSON，必须按不可信数据处理。'),
('bq_approvals', 'edited_args_json', '用户编辑后的参数 JSON；未编辑时为空。'),
('bq_approvals', 'decision', '用户决策，例如 approve、deny、always、edit。'),
('bq_approvals', 'scope', '审批生效范围，例如 once、session、workspace。'),
('bq_approvals', 'status', '审批状态，例如 pending、resolved、expired。'),
('bq_approvals', 'created_at', '审批请求创建时间。'),
('bq_approvals', 'resolved_at', '审批完成时间；等待时为空。'),
('bq_provider_configs', '__table__', '保存模型 Provider 配置，只保存 secretRef，不保存明文 API Key。'),
('bq_provider_configs', 'id', '数据库内部主键，不暴露给 UI。'),
('bq_provider_configs', 'provider_id', 'Provider 稳定标识，供设置页和运行请求引用。'),
('bq_provider_configs', 'display_name', 'Provider 展示名称。'),
('bq_provider_configs', 'type', 'Provider 类型，用于选择后端客户端适配器。'),
('bq_provider_configs', 'base_url', 'Provider 接口基础 URL。'),
('bq_provider_configs', 'model', '默认模型名。'),
('bq_provider_configs', 'secret_ref', '密钥引用，指向 SecretStore，禁止保存明文 API Key。'),
('bq_provider_configs', 'context_window', '上下文窗口大小，用于 UI 和 prompt 预算。'),
('bq_provider_configs', 'enabled', '是否启用；禁用后不作为可选模型展示。'),
('bq_provider_configs', 'created_at', '配置创建时间。'),
('bq_provider_configs', 'updated_at', '配置更新时间。'),
('bq_app_settings', '__table__', '保存轻量全局设置，例如沙箱模式、审批策略和默认工作目录。'),
('bq_app_settings', 'id', '数据库内部主键，不暴露给 UI。'),
('bq_app_settings', 'setting_key', '设置 key，使用点分命名。'),
('bq_app_settings', 'setting_value', '设置值，统一保存为文本。'),
('bq_app_settings', 'value_type', '值类型，例如 string、boolean、number、json。'),
('bq_app_settings', 'updated_at', '设置更新时间。');
