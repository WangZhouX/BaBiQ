# P3-2 当前窗口运行时交接

> 本文件是 P3-2 实现完成后的交接记录。P3-2 已把 P3-1 的上下文装配底座接入真实 AgentLoop，并完成 SQLite 快照、JSON-RPC 查询和桌面端上下文指示。

## 1. 完成范围

- 后端新增 `ContextWindowRuntime`，在普通 `turn/start` 调用模型前生成本轮临时上下文输入。
- 后端新增 `bq_context_windows`、`bq_context_snapshots`，记录 thread 当前窗口状态和 turn 级 `ContextSnapshot`。
- 后端新增 `context/status`、`context/snapshot/get` JSON-RPC 方法，并把最新快照摘要扩展进 `run/turn/get`。
- `AgentLoop` 继续把用户真实输入写入 `bq_items`，传给模型的是运行时渲染后的临时上下文文本，避免 envelope 污染聊天历史。
- `ContextWindowRuntime` 会在快照落库失败时降级为临时上下文继续执行；快照是审计侧车，不允许拖垮模型主流程。
- 桌面端新增上下文协议模型、客户端方法、`ContextWindowUiState`，在打开会话和 turn 完成后刷新上下文窗口状态。
- 桌面输入栏新增上下文 chip，运行详情面板展示选中 turn 的快照摘要。

## 2. 关键实现文件

- `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextualPromptRenderer.java`
- `backend/src/main/java/com/wzx/babiq/server/context/ContextStatusService.java`
- `backend/src/main/resources/db/migration/V7__context_window_runtime.sql`
- `backend/src/main/java/com/wzx/babiq/server/api/method/ContextStatusHandler.java`
- `backend/src/main/java/com/wzx/babiq/server/api/method/ContextSnapshotGetHandler.java`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ContextModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`

## 3. 数据与协议

新增表：

- `bq_context_windows`: thread 级当前窗口状态，记录 `window_ordinal`、模型窗口、自动压缩阈值和最后快照。
- `bq_context_snapshots`: turn 级模型可见上下文快照，记录估算 token、真实 prompt token、included/excluded 数量、envelope、items 和能力目录摘要。

新增接口：

- `context/status`: 查询当前 thread 的窗口状态和最近快照 token 占用。
- `context/snapshot/get`: 查询指定快照的摘要和 item 列表。
- `run/turn/get.contextSnapshot`: 在运行详情中返回最新上下文快照摘要。

## 4. Spring AI / Spring AI Alibaba 边界

- 继续复用 Spring AI 的 `Message`、`ToolCallback`、ChatModel/usage 抽象。
- 继续复用 Spring AI Alibaba `ReactAgent`、`MemorySaver`、HITL Hook、工具拦截器和 token 观测链路。
- BaBiQ 自己维护跨 provider 的上下文策略、快照持久化、审计查询和桌面协议。
- 本阶段不接管 Spring AI `ChatMemory` 为事实源，也不启用 SAA `ContextEditingInterceptor` / `SummarizationHook` 做压缩；这些留给 P3-3 评估。

## 5. 验证记录

已执行并通过：

```powershell
cd backend
.\mvnw.cmd "-Dtest=ContextWindowRuntimeTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

阶段收口前还需要在最终提交前再跑一次后端 `clean verify` 和桌面 `test`，确保文档同步后没有遗漏。

## 6. P3-3 边界

P3-3 才开始实现短期记忆/上下文压缩：

- 自动压缩触发。
- `ContextCompactionItem` 真实事件。
- summary 落库并替换 active window。
- `windowOrdinal` 压缩成功后递增。
- 压缩失败恢复语义。
- 评估是否复用 Spring AI Alibaba `ContextEditingInterceptor` / `SummarizationHook` 作为压缩策略实现。
