# P8 画布编排编辑器实现报告

> 日期：2026-06-13
> 分支 / worktree：`codex/p8-flow-canvas-editor` / `E:\BaBiQ\.worktrees\p8-flow-canvas-editor`

## 结论

P8 画布编排编辑器已经完成代码实现、自动化验证和文档检查点同步。本报告只声明“实现与自动化验收完成”：真实模型嵌套运行、人工 UI 烟测、窄分屏视觉复核仍需在可操作桌面与真实 Provider 环境中执行后，才能声明 P8 全量验收闭环。

## Context7 核对

- `Context7 /alibaba/spring-ai-alibaba`：确认继续复用官方 FlowAgent 组合形态，P8 只递归装配 `SequentialAgent`、`ParallelAgent`、`LlmRoutingAgent`，不自研图执行器。
- `Context7 /websites/spring_io_spring-ai_reference`：确认 Spring AI 侧仍保持既有模型/工具调用边界，P8 不绕过 BaBiQ 审批、沙箱和工具观测。
- `Context7 /jetbrains/compose-multiplatform`：确认桌面画布使用 Compose 标准 API 方向，未引入第三方节点图/布局库。

## 已实现范围

- 后端新增 `BabiqFlowStructure` 受限结构树：根组 + 节点引用/一级子组，校验每个节点恰好引用一次，禁止组内再嵌组，自由 DAG 和 LoopAgent 仍在范围外。
- `BabiqFlowSpec` 保持旧平铺配置兼容：缺少结构时自动按旧 topology/nodes 升级为结构树，并在冻结执行前校验结构和节点一致。
- `FlowOrchestrationService` 改为递归编译官方 FlowAgent；叶子仍走既有 `DefaultFlowNodeAgentFactory`，并行子组使用独立 merge output key，子组各自配置 `MemorySaver`。
- V20 migration 为 `bq_orchestrations` 与 `bq_work_unit_configs` 增加 `structure_json`，并同步 `bq_schema_comments`。
- WorkUnit 配置更新、列表、目标更新和运行 `orchestration` item 均贯通 `structureJson`，避免桌面画布结构只停留在本地草稿。
- 桌面新增可移植 `com.wzx.babiq.desktop.flowcanvas` 组件包，包含核心图模型、自动布局、画布渲染、节点卡和主题参数。
- 画布核心通过 `FlowCanvasPortabilityTest` 守住移植边界：不依赖 BaBiQ 协议、状态层或项目 UI theme。
- 右侧编排详情改为画布式编辑/回放：START/END 是端点，不再伪装为节点；新增节点默认从空图开始，只有用户显式添加后才出现中间节点；串行边有箭头，并行边无箭头；线上插入点进入串行/并行/路由菜单。

## 自动化验证

```powershell
cd E:\BaBiQ\.worktrees\p8-flow-canvas-editor\backend
.\mvnw.cmd clean verify
```

结果：exit code 0。Surefire 报告汇总为 132 份报告、436 tests、0 failures、0 errors、0 skipped；V20 `flow canvas structure` migration 在测试库中实际应用。

```powershell
cd E:\BaBiQ\.worktrees\p8-flow-canvas-editor\desktop
.\gradlew.bat test --rerun-tasks
```

结果：exit code 0，`BUILD SUCCESSFUL in 20s`。测试报告头部统计为 48 份报告、252 tests、0 failures、0 errors、0 skipped。

## 未执行项

- 真实模型嵌套编排烟测未执行：需要可用 Provider、审批弹窗人工确认和真实文件工作区。
- 人工 UI 视觉烟测未执行：需要运行桌面端确认画布缩放、平移、线上插入菜单、节点设置区域、失败态和窄分屏降级。
- P7 Anthropic API Key / `ant auth login` 真实 Provider 烟测仍未执行，与 P8 无直接阻塞，但根文档已继续保留该待办。

## 后续建议

1. 先执行 P8 人工烟测：结构编辑、审批弹窗、运行回放、失败态、旧配置回归、窄分屏降级、对话式配置编辑。
2. 人工烟测通过后，再决定是否进入 P6-2b 的运行中逐节点审批/中断恢复，或继续做 P8 后续增强。
3. 若要引入自由连线、任意 DAG、节点坐标持久化、minimap、团队画布化或 capability 节点搜索，必须新开阶段计划。
