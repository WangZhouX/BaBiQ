# P3-1 Codex / Spring 证据交接

> 本文件记录 P3-1 设计和实现时核对过的外部实现、官方能力和 BaBiQ 当前落点。

## 1. Codex 源码证据

已核对本机 Codex 源码：

- `E:\wzx\codex\codex-rs\core\src\context_manager\history.rs`
- `E:\wzx\codex\codex-rs\core\src\session\turn.rs`
- `E:\wzx\codex\codex-rs\core\src\session\mod.rs`
- `E:\wzx\codex\codex-rs\core\src\compact.rs`
- `E:\wzx\codex\codex-rs\memories\README.md`

关键结论：

- Codex 只把模型可见 item 作为 prompt input 的历史，不把 UI transcript 原样注入。
- `build_prompt` 将历史 input、工具 specs、base instructions、personality 和 output schema 分开。
- `build_initial_context` 把 permissions、developer instructions、skills、apps、plugins、environment 等内容分 slot 放入 developer 或 contextual sections。
- skills 有 metadata budget，不会默认注入所有 skill 正文。
- compaction 是运行时事件，会替换 active history，并推进新的 context window。
- 长期记忆是异步 pipeline，read path 注入的是受预算控制的 summary。

BaBiQ 吸收方式：

- 用 `ContextEnvelope` 显式表达 current turn、recent history、summary、memory、workspace facts、capability catalog。
- 用 `ContextSnapshot` 记录 included/excluded/token estimate/source id。
- 工具 schema 继续走 Spring AI/SAA tool calling，envelope 只放 capability catalog summary。

## 2. Spring AI 证据

Context7 重新核对来源：

- Spring AI Chat Memory: <https://docs.spring.io/spring-ai/reference/api/chat-memory.html>
- Spring AI Tools: <https://docs.spring.io/spring-ai/reference/api/tools.html>

可复用能力：

- `ChatMemory` / `MessageWindowChatMemory` 可以维护 conversation messages。
- ChatClient advisor 可以基于 conversation id 注入短期消息记忆。
- Spring AI `Message` / `Prompt` 是 provider-portable 的模型输入抽象。
- Spring AI `ToolCallback` 是工具 schema 和调用入口。
- 后续长期记忆可复用 structured output、VectorStore、RAG advisor。

BaBiQ 判断：

- `MessageWindowChatMemory` 只能解决“按消息数窗口”问题，不解决 token budget、运行快照、tool pairing、压缩事件和跨 provider 降级。
- P3 需要在 ReactAgent 前加 BaBiQ 自己的上下文装配层，再输出 Spring AI 兼容消息。

## 3. Spring AI Alibaba 证据

Context7 重新核对来源：

- Spring AI Alibaba: <https://github.com/alibaba/spring-ai-alibaba>

可复用能力：

- `ReactAgent` 是 BaBiQ 当前 Agent 主链路的基础。
- Agent Framework 有 Hook、Interceptor、MemorySaver。
- 官方方向包含 context engineering、context compaction/editing、HITL、动态工具选择、MCP。

BaBiQ 判断：

- P3-1 先实现 provider/model 无关的 Context Envelope 和 Snapshot。
- P3-2 再评估把 `ContextAssembler` 放到 ReactAgent 调用前，必要时配合 SAA interceptor/hook。
- P3-3 再评估 SAA `ContextEditingInterceptor`、`SummarizationHook` 是否适合作为压缩策略实现。

## 4. BaBiQ 当前实现落点

P3-1 已新增：

- `ContextAssembler`
- `CapabilityCatalogAssembler`
- `ContextTokenEstimator`
- `ContextEnvelope`
- `ContextSnapshot`
- `ContextAssemblyInput`
- `ContextAssemblyResult`
- 相关 priority/source/reason/value records

当前实现边界：

- 已能把 `ThreadItem` 历史过滤成模型可见 recent history。
- 已能生成 snake_case envelope JSON。
- 已能生成 Spring AI `SystemMessage` + contextual `UserMessage` + final current `UserMessage`。
- 已能把 excluded runtime item 写入 snapshot。
- 已能从 Spring AI `ToolCallback` 提取能力摘要且不携带 input schema。

未做：

- 未新增数据库表。
- 未接入真实 `AgentLoop`。
- 未实现压缩和长期记忆。
- 未改桌面协议和 UI。

## 5. 后续提醒

- P3-2 需要把 `ContextAssembler` 接到真实 turn 前置链路，并持久化 `ContextSnapshot`。
- P3-2 新增业务表时必须同步 SQL 中文注释、`bq_schema_comments` 和覆盖测试。
- P3-3 做压缩时必须先保证“压缩成功落库后才替换 active window”。
- P3-4 长期记忆必须有 eligibility、secret redaction、污染模式和用户开关。
- P3-5 才做按需工具/Skill/MCP schema 装配和向量检索增强。
