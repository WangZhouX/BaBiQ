# P3-1 上下文与记忆平台最小底座计划

> **执行规则:** 实现本计划时必须使用 `superpowers:executing-plans` 和 `superpowers:test-driven-development`；声称完成前必须使用 `superpowers:verification-before-completion`。
>
> 本计划最初是 P3 总体设计阶段。根据用户后续要求，P3-1 已扩展为“设计 + 最小可运行底座”：先落地分层 Context Envelope、ContextSnapshot 和能力目录摘要，不在本阶段接入完整 ReactAgent 运行时、不新增数据库表。

## 1. 目标

把 BaBiQ 的上下文工程从“直接依赖历史消息”推进到 Codex 风格的分层模型：

- 本轮用户消息是最高优先级事实源。
- 最近历史、短期摘要、长期记忆、工作区事实、工具/Skill/MCP 能力目录都只是辅助上下文。
- 每轮模型可见内容都能生成 `ContextSnapshot`，说明 included/excluded、来源、原因和 token 估算。
- 能力目录和真实工具 schema 分离，避免把所有工具定义无脑塞进 prompt。
- 底层继续兼容 Spring AI `Message` / `ToolCallback`，后续可接 Spring AI Alibaba `ReactAgent`、Hook、Interceptor。

## 2. 已核对外部实现

### 2.1 Codex 源码结论

已核对 `E:\wzx\codex` 中的关键实现：

- `codex-rs/core/src/context_manager/history.rs`
- `codex-rs/core/src/session/turn.rs`
- `codex-rs/core/src/session/mod.rs`
- `codex-rs/core/src/compact.rs`
- `codex-rs/memories/README.md`

设计吸收点：

- Codex 的历史不是 UI transcript 原样回放，而是模型可见历史。
- prompt 分成 `input`、`tools`、base/developer/contextual sections，而不是把所有内容拼成一坨用户消息。
- skill/app/permission/environment 等上下文按 slot 注入，并有预算控制。
- 上下文压缩是运行时事件，完成后替换 active history，并保留可审计记录。
- 长期记忆是异步流水线，读路径只注入受预算控制的 summary。

### 2.2 Spring AI / Spring AI Alibaba 结论

通过 Context7 重新核对：

- Spring AI `ChatMemory`、`MessageWindowChatMemory`、`ChatClient Advisor` 适合做 provider-portable 的消息记忆组件。
- Spring AI `ToolCallback` 是真实工具 schema 的承载通道。
- Spring AI `VectorStore`、RAG advisor、structured output 适合后续长期记忆检索和抽取。
- Spring AI Alibaba 提供 `ReactAgent`、Hook、Interceptor、MemorySaver，以及上下文工程、压缩、动态工具选择等方向能力。

BaBiQ 设计判断：

- P3 不能只依赖 `MessageWindowChatMemory`，因为 BaBiQ 主链路是 ReactAgent + 自有 `ThreadItem`/运行记录。
- BaBiQ 自己维护 `ContextAssembler` 和 `ContextSnapshot`，Spring AI/SAA 作为底层模型、消息和工具抽象。

## 3. P3-1 已实现范围

### 3.1 后端领域包

新增包：

```text
backend/src/main/java/com/wzx/babiq/server/context/
├── ApproximateContextTokenEstimator.java
├── CapabilityCatalogAssembler.java
├── ContextAssembler.java
├── ContextTokenEstimator.java
└── model/
    ├── CapabilityCatalog.java
    ├── CapabilityDescriptor.java
    ├── ContextAssemblyInput.java
    ├── ContextAssemblyResult.java
    ├── ContextEnvelope.java
    ├── ContextExclusionReason.java
    ├── ContextPriority.java
    ├── ContextSnapshot.java
    ├── ContextSnapshotItem.java
    ├── ContextSourceType.java
    ├── LongTermMemoryReference.java
    ├── RecentHistoryItem.java
    └── ShortTermSummary.java
```

核心职责：

- `ContextAssembler`: 把当前 turn、历史、摘要、记忆、工作区事实和能力目录组装成 `ContextEnvelope`、`ContextSnapshot` 和 Spring AI `Message` 列表。
- `CapabilityCatalogAssembler`: 从 Spring AI `ToolCallback` 提取工具摘要，但不暴露 input schema。
- `ContextTokenEstimator`: 隔离 token 预估策略，P3-2 可替换成 provider-aware tokenizer。

### 3.2 分层 Context Envelope

当前 envelope 结构：

```json
{
  "current_turn": {},
  "recent_history": {},
  "short_term_summary": {},
  "long_term_memory": {},
  "workspace_context": {},
  "capability_catalog": {}
}
```

优先级规则：

- `current_turn`: `AUTHORITATIVE`
- `recent_history`: `HIGH`
- `short_term_summary`: `MEDIUM`
- `long_term_memory`: `REFERENCE`
- `workspace_context`: `REFERENCE`
- `capability_catalog`: `REFERENCE`

Spring AI message 渲染规则：

1. `SystemMessage`: 上下文优先级规则，声明本轮用户消息最高。
2. `UserMessage`: snake_case 的 envelope JSON。
3. `UserMessage`: 原始本轮用户消息，保持最后输入位置。

### 3.3 历史过滤规则

P3-1 已实现的最小过滤：

- `UserMessageItem` -> recent history `user`
- 完整 `AgentMessageItem.text` -> recent history `assistant`
- 只有 `textDelta` 的 assistant 增量 -> 排除，原因 `INCOMPLETE_ASSISTANT_MESSAGE`
- `TurnSummaryItem` -> 排除，原因 `RUNTIME_SUMMARY`
- `ContextCompactionItem` -> 排除，原因 `COMPACTION_MARKER`
- 空文本 -> 排除，原因 `EMPTY_TEXT`

### 3.4 能力目录规则

能力目录只包含：

- `name`
- `source`
- `description`
- `approvalRequired`
- `riskLevel`

不包含：

- `inputSchema`
- 完整 tool definition
- MCP server 返回的大 schema
- Skill 正文

真实工具 schema 仍由 Spring AI/SAA tool calling 通道负责。

## 4. 测试

新增测试：

- `ContextAssemblerTest`
  - 验证当前 turn 是 authoritative，并且最终用户消息放在 Spring AI messages 最末尾。
  - 验证 recent history 只包含模型可见 user/assistant 历史。
  - 验证 `TurnSummaryItem` 和 `ContextCompactionItem` 被记录为 excluded snapshot item。
  - 验证 capability catalog 是 reference，不包含 input schema。
- `CapabilityCatalogAssemblerTest`
  - 验证从 Spring AI `ToolCallback` 提取工具目录摘要。
  - 验证 `exec_shell` 被标记为高风险、需要审批。

## 5. 非本阶段范围

P3-1 不做：

- 不新增 `bq_context_*` 或 `bq_memory_*` 表。
- 不把 `ContextAssembler` 接入真实 `AgentLoop` 或 `ReactAgent` 调用链。
- 不实现自动压缩、手动压缩、长期记忆写入或向量检索。
- 不改桌面 UI。

这些内容进入后续阶段：

- P3-2: `ContextWindowRuntime`、持久化 snapshot、实际 Agent 前置接入、桌面上下文指示。
- P3-3: 短期压缩和 `ContextCompactionItem` 真实事件。
- P3-4: 长期记忆异步流水线。
- P3-5: 按需工具/Skill/MCP 装配、记忆检索增强和桌面记忆控制。

## 6. 验收命令

P3-1 最小验收：

```powershell
cd backend
.\mvnw.cmd "-Dtest=CapabilityCatalogAssemblerTest,ContextAssemblerTest" test
.\mvnw.cmd clean verify
```

桌面端未改代码，本阶段不强制运行 `desktop` 测试。
