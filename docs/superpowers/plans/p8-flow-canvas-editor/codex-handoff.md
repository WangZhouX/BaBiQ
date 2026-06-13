# P8 画布编排编辑器 - Codex 交接

> 完整计划：`docs/superpowers/plans/p8-flow-canvas-editor/plan.md`（必读，按 Task 1→10 顺序执行）
> 原型审查：`docs/superpowers/plans/p8-flow-canvas-editor/prototype-review.md`
> 视觉基准：Figma「总原型UI」`frTp55zgrKf4NAWxn6LdI7` section `395:2` 七帧 `399:x`（P8 01-07），2026-06-13 复查通过
> 锁定技术栈：Spring AI Alibaba `1.1.2.3`、Spring AI `1.1.6`、Java 21、SQLite + MyBatis-Plus + Flyway、Compose Desktop 纯标准 API（**不引入第三方图形/布局库**——不是偏好，是 2026-06-13 选型调研结论：成熟节点编辑器全在 JS/TS 生态需嵌 WebView，JVM 系 JGraphX/JUNG 已停滞且为 Swing 时代产物，Compose 生态无成熟节点编辑库；完整调研与唯一备选 compose-dnd 见 plan D7，遇阻先查 D7 再考虑加依赖）

## 当前状态（2026-06-13）

- **计划已定稿**：独立评审 Approved（评审者逐行核对过代码锚点），4 条建议性修正已吸收；原型审查两轮（首轮 15 项问题 → 修订后复查全部通过）。
- **设计已含三轮增量**：D14-D16（原型审查产出的视觉/交互规范）、D17 + Task 4b（对话式节点配置编辑）。
- **代码实现已完成**：本交接已从开工交接更新为收尾交接；实现详情和验证证据见同目录 `implementation-report.md`。
- **当前边界**：自动化验收已通过；真实模型嵌套运行、人工 UI 烟测、窄分屏视觉复核仍需在可操作桌面与真实 Provider 环境中执行。

## 已完成的载荷级查证（不得凭记忆推翻；复跑方式附后）

1. **嵌套拓扑官方可行（本计划命脉）**：本地 jar javap 证据——
   `javap -cp %USERPROFILE%\.m2\repository\com\alibaba\cloud\ai\spring-ai-alibaba-agent-framework\1.1.2.3\spring-ai-alibaba-agent-framework-1.1.2.3.jar com.alibaba.cloud.ai.graph.agent.flow.agent.FlowAgent`
   输出确认：`public abstract class FlowAgent extends com.alibaba.cloud.ai.graph.agent.Agent`，字段 `protected List<Agent> subAgents` → Sequential/Parallel/LlmRouting 互为子 Agent 类型成立。jar 内另有 `LoopAgent`，**本期明确不用**。
2. **递归编译改造点**：`backend/.../agent/flow/FlowOrchestrationService.java:62-98` `buildOfficialFlowAgent` 平铺 switch，三种官方 builder 调用链已在生产运行；改造 = 把叶子列表换成"子项（叶子或子组）"递归。
3. **节点模型零改动**：`BabiqFlowNode`（`nodeId/name/displayName/role/task/toolNames/modelPolicy/mode/order/branch/outputKey/writeScopes`）字段足够；`branch` 语义升级为"所属组 id"。
4. **migration 编号**：当前最新为 V19（P7），P8 使用 **V20**；若开工时已有他人占用 V20，顺延编号并同步 plan。
5. **桌面锚点**：现有卡片堆编辑器 `OrchestrationTopologyEditor` 等位于 `desktop/.../ui/runtime/OrchestrationSection.kt`（当前约 1035 行），Task 8 整体删除；右侧面板宽度上限 `AppShell.kt` `MaxRuntimePanelWidth = 760.dp`、用户可调 320-760，画布 ≥600dp 启用、不足降级紧凑列表。
6. **对话式编辑挂点**：`WorkUnitManageTool` 现有 action 仅 `append_goal/create/update_goal/start/remove`；Task 4b 增 `read_config/update_config`；刷新通道复用现有 `WorkUnitItem` emit（工具内已有 `emitAdded` 先例）。
7. Compose Desktop 画布 API（`Canvas`/`graphicsLayer`/`pointerInput`/`onPointerEvent(PointerEventType.Scroll)`）均为官方标准 API，仓库内 `SkillLibraryPanel.kt` 已有 `onPointerEvent` 使用先例。

## 开工后第一批必须补核对（plan §0 待核对清单，写代码前完成）

1. 嵌套 FlowAgent 的 `saver`/`CompileConfig` 语义（子组是否各配 `MemorySaver`）——做最小 spike，结论固化进 `FlowOrchestrationServiceNestedTest` 断言与中文注释。
2. 两个并行组串联时 `mergeOutputKey` 互不覆盖——spike 断言。
3. `LlmRoutingAgent` 作为子组时 `.model/.fallbackAgent` 语义。
4. Windows 滚轮 `scrollDelta` 符号/步长实测，缩放系数集中为常量。
5. 展开分屏实际可用宽度实测，确认 ≥600dp 阈值合理。

## 不可违背的决策红线（详见 plan §1，违反即返工）

- 受限树：根组 + 子项为节点或组，**组内不嵌组**（深度≤2）；每个节点**恰好被结构引用一次**；不做自由 DAG/LoopAgent/运行中编辑。
- 执行引擎只递归装配官方 FlowAgent，不自研图执行器、不走 StateGraph 任意图。
- approve-once 语义不变：结构随 spec 冻结；审批弹窗口语化分步 + 每节点读/写标注 + 写入范围高亮（视觉基准帧 `399:511`）。
- 画布活在右侧详情展开分屏，**不开全屏新窗口**；<600dp 降级紧凑列表（帧 `399:1045`）。
- D14 视觉通道：节点中性底色 + 角色色点，颜色留给状态；**红色仅 failed/危险**；编辑激活态用主题强调色。
- D15 锚定浮层字段必须含：名称（重命名）/任务/**工具模式**/模型/删除。
- D16 无拖拽模式开关；组标签不渲染技术 groupId；不做运行侧卡。
- D17 对话编辑只动草稿、运行中拒绝、全量覆盖（`update_plan` 同语义）、emit WorkUnitItem 刷新、桌面草稿冲突提示 [加载最新]/[保留草稿]。
- D18 **画布核心是可移植组件包**：核心层 `com.wzx.babiq.desktop.flowcanvas` 零 BaBiQ 业务依赖（禁止 import `desktop.protocol/state/ui.theme`，主题经 `FlowCanvasTheme` 参数注入、节点内容经 slot composable 注入），BaBiQ 接线全部在适配层 `ui/runtime/FlowStructureAdapter.kt`；由 `FlowCanvasPortabilityTest` 源码 import 守卫强制，**该测试不得用 @Disabled 绕过**。未来开源 = 拆独立 Gradle module，本期不做但边界必须现在守住。
- 原型 nit 不跟随：帧 `399:874` 失败示例在末位节点、右上 chip 仍"运行中"是原型笔误——实现按正确语义（失败链路下游中止灰态、chip 状态机含"已失败"）。

## 实施顺序与提交约定

按 plan Task 1 → 2 → 3 → 4 → 4b → 5 → 6 → 7 → 8 → 9 → 10 严格 TDD（每 Task 内先红后绿），每 Task 一笔中文 conventional commit（**必须带类型前缀**，如 `feat(p8): ...`，不要裸中文标题——P7 期间出现过两笔漏前缀提交，不要重演）。后端先行（Task 1-4b 全绿后再动桌面），Task 8 删除旧编辑器时同步迁移/重写其测试，禁止 `@Disabled` 占位。

仓库硬规则逐条适用：新增生产代码全部中文教学型注释（类/方法/重要字段三层）；V20 三件套（SQL 中文注释 + `bq_schema_comments` + `SchemaCommentsCoverageTest` 过）；`AgentLoopLineCountTest` 不得退化；P6-2/P6-4 既有测试零回退。

## 验证命令（声称完成前必须全绿）

```powershell
cd backend
.\mvnw.cmd "-Dtest=BabiqFlowStructureTest,FlowOrchestrationServiceNestedTest,FlowOrchestrationServiceTest,FlowApprovalServiceTest,FlowNodeRuntimeTest,FlowConcurrencyAttributionTest,FlowOrchestrationToolTest,WorkUnitManageToolTest,CapabilityAliasDictionaryTest,SystemPromptSecurityRuleTest,ThreadItemJsonTest,WorkUnitServiceTest,WorkUnitHandlersTest,SchemaCommentsCoverageTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*FlowGraphModelTest" --tests "*FlowCanvasLayoutTest" --tests "*FlowCanvasTest" --tests "*OrchestrationSectionTest" --tests "*WorkUnitModelsTest" --tests "*ThreadItemJsonTest" --tests "*ChatControllerTest"
.\gradlew.bat test --rerun-tasks
```

注意：桌面 `test` 不带 `--rerun-tasks` 时可能全 UP-TO-DATE（缓存），**最终证据必须是 `--rerun-tasks` 真执行**。

## 人工烟测（自动化全绿后、声称 P8 完成前必做）

按 plan §5 的 8 项执行：编辑体验（线上+菜单/拖拽入组出组/undo/锚定浮层四字段/整体保存）、空任务校验、缩放平移、真实模型嵌套运行（审批分步+回放裁剪+运行记录归属+TurnSummary）、失败态、旧数据回归、窄分屏降级、**对话式编辑五场景**（加并行组/改任务/删节点/草稿冲突/冻结拒绝）。

## 完成报告必须包含

- 每条验证命令的真实输出摘要（测试数/失败数）；`clean verify` 与 `--rerun-tasks` 为最终证据。
- spike 结论（嵌套 saver/mergeOutputKey/路由组语义）及其落入哪个测试断言。
- 8 项人工烟测逐项结果；未执行项明确标注原因。
- 中文 conventional commit 列表；明确未 push。
- 与 plan 的任何偏离及理由（含 V20 编号是否顺延）。
- CLAUDE.md / AGENTS.md 检查点同步情况（Task 10 Step 3）。

## 已知边界（越界必须新开阶段，不得混入 P8）

- 自由连边/任意 DAG、LoopAgent、运行中编辑与逐节点审批（P6-2b 领地）、节点坐标持久化、minimap、子图模板、跨 flow 复制粘贴、capability 目录节点搜索、团队（P6-3）画布化。
- Spring AI / Spring AI Alibaba 版本升级。

## 风险提醒（plan §6 全表 + 两条强调）

1. 嵌套 saver 语义若与预期不符：回退方案 = 结构树仅作展示、执行平铺受限为单层组，UI 不受影响——先 spike 再写递归编译，不要赌。
2. `OrchestrationSection.kt` 是另一条在途工作线的修改目标（见"开工前置冲突检查"）——P8 开工前必须确认该线已收口。
