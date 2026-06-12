# P8 画布编排编辑器（受限结构画布 + 嵌套拓扑）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 P6-2 的"卡片堆 + 全局单拓扑"编排编辑器升级为可缩放平移的节点画布：支持串/并/路由**嵌套**拓扑、拖拽式结构编辑、参数内嵌节点、运行态实时回放；后端同步把 `BabiqFlowSpec` 从平铺结构升级为嵌套树并递归编译成官方 FlowAgent。

**Architecture:** 后端新增 `BabiqFlowStructure` 嵌套树（叶子=节点引用，组=拓扑+子项，深度≤2），`FlowOrchestrationService` 由平铺 switch 改为**递归编译**——每个组映射官方 `SequentialAgent`/`ParallelAgent`/`LlmRoutingAgent`，叶子仍走 `DefaultFlowNodeAgentFactory` 的 ReactAgent（薄封装路线不变，本地 jar 已证 `FlowAgent extends Agent`，嵌套类型成立）。桌面端新增纯 Compose 画布组件（graphicsLayer 缩放平移 + Canvas 贝塞尔连线 + 自动分层布局），**布局由结构推导、拖拽即结构操作**，编辑/回放共用一套渲染。approve-once 安全语义不变：结构随 spec 冻结，审批弹窗按树展平列范围。

**Tech Stack:** Spring AI Alibaba 1.1.2.3（不升级；`flow.agent.{Sequential,Parallel,LlmRouting}Agent` 嵌套）、Spring AI 1.1.6、SQLite + Flyway（V20）、Compose Desktop 纯标准 API（`graphicsLayer` / `pointerInput` / `Canvas`，不引第三方图形库）。

**视觉基准：** Figma「总原型UI」页 `395:2` 的 P8 01–05 五帧（创建容器/添加结构/节点编辑/启动审批/运行回放）；2026-06-12 原型审查结论与待改项见同目录 `prototype-review.md`，计划侧采纳的修订 = D9/D10 修订 + D14/D15/D16。

---

## 0. 前置查证记录（2026-06-12，实现者复跑时不得跳过）

按 CLAUDE.md §4 查证顺序完成；实现中如发现与此不符，停下来重新核对而不是硬改。

**已核对（本地 jar javap + Context7 + 本仓库源码）：**

1. **嵌套拓扑官方可行（本计划命脉，已用本地 jar 强验证）**：
   - `javap -cp spring-ai-alibaba-agent-framework-1.1.2.3.jar com.alibaba.cloud.ai.graph.agent.flow.agent.FlowAgent` 输出：
     `public abstract class FlowAgent extends com.alibaba.cloud.ai.graph.agent.Agent`，字段 `protected java.util.List<com.alibaba.cloud.ai.graph.agent.Agent> subAgents`。
   - 即 `SequentialAgent`/`ParallelAgent`/`LlmRoutingAgent` 本身是 `Agent`，可作为彼此的 subAgent → "串行主干内嵌并行组"在官方类型系统上成立。
   - jar 内还有 `LoopAgent`（含 `LoopAgentBuilder`）——本期明确不做（见范围外）。
   - Context7（`/alibaba/spring-ai-alibaba` examples/multiagent-patterns/pipeline）确认 Sequential/Parallel/Routing 是官方推荐组合形态。
2. **现有装配代码就是改造点**：`FlowOrchestrationService.buildOfficialFlowAgent`（backend `agent/flow/FlowOrchestrationService.java:62-98`）当前按 `spec.topology()` 平铺 switch，三种拓扑的 builder 调用链（`.name/.description/.subAgents/.saver(new MemorySaver())`、Parallel 的 `.mergeOutputKey/.mergeStrategy`、Routing 的 `.model/.fallbackAgent/.systemPrompt/.instruction`）已在生产运行，递归编译只需把"叶子列表"换成"子项列表（叶子或子组）"。
3. **`BabiqFlowNode` 字段足够，无需改节点模型**：`nodeId/name/displayName/role/task/toolNames/modelPolicy/mode/order/branch/outputKey/writeScopes` 全在（`BabiqFlowNode.java:28-41`）；`branch` 字段语义升级为"所属组 id"。
4. **持久化落点**：V14 的 `bq_orchestrations.topology TEXT` 保留（根拓扑），V20 新增 `structure_json`；WorkUnit 配置侧 V18 `bq_work_unit_configs`（config snapshot）同样加 `structure_json`。
5. **桌面协议扩展点**：`WorkUnitConfiguration(topology, nodes: List<WorkUnitConfigEntry>)`（desktop `protocol/WorkUnitModels.kt`）与 `orchestration` ThreadItem 均为 kotlinx-serialization data class，可加 `structure: FlowStructureDto? = null` 默认空保持向后兼容。
6. **Compose Desktop 画布 API 全部为官方标准 API**：`Canvas` 绘制（官方 tutorials/Image_And_Icons_Manipulations 确认）、`Modifier.onPointerEvent(PointerEventType.Scroll)` 桌面滚轮（官方 Tab_Navigation/Mouse_Events tutorials 中的 `onPointerEvent` 同源 API）、`graphicsLayer{scaleX/scaleY/translationX/translationY}`、`pointerInput + detectDragGestures`。无需任何第三方库。
7. **UI 模型约束（p6-master §5）**：对话始终是主体 + 右侧面板可展开"详情分屏"；画布只能活在展开分屏里，**不开全屏新窗口、不隐藏对话**。现有 `AppShell` 已有编排详情展开态（fe96cbc 落地），画布替换其内容区。

**实现期间必须补充核对（写代码前确认）：**

- [ ] 嵌套 FlowAgent 的 `saver`/`CompileConfig` 语义：子 FlowAgent 是否需要独立 `MemorySaver`、还是只在根上配（用 jar 内 `FlowAgentBuilder` javap + 最小 spike 验证；若子组必须配 saver，递归编译时每组 `new MemorySaver()`）。
- [ ] `ParallelAgent` 嵌套时 `mergeOutputKey` 唯一性约束：同一棵树里多个并行组的 mergeOutputKey 是否会在共享 state 里互相覆盖（spike：两个并行组串联，断言两组输出都在）。
- [ ] `LlmRoutingAgent` 作为子组时 `.model(...)` 与 fallbackAgent 语义在嵌套层是否一致。
- [ ] Compose `onPointerEvent(PointerEventType.Scroll)` 在 Windows 高 DPI 下 `scrollDelta` 的符号与步长（官方 Mouse_Events tutorial + 本机实测），缩放系数按 `1.1^(-delta.y)` 起步调参。
- [ ] `AppShell` 编排详情展开态的实际可用宽度（≥600dp 才启用画布；不足时降级为现有紧凑列表）。

**明确不做（Out of scope，越界必须新开阶段）：**

- 自由连边 / 任意 DAG / 条件边——执行引擎仍是官方 FlowAgent 组合，不自研图执行器，不走 StateGraph 任意图编译（留作 P8b 评估）。
- `LoopAgent` 循环拓扑；运行中编辑（approve-once 冻结语义不变）；运行中逐节点审批/中断恢复（仍属 P6-2b）。
- 节点自由摆放坐标持久化、minimap、子图/模板嵌套、跨 flow 复制粘贴、连线动画特效。
- 接入全量 capability 目录做节点搜索库（第一版用固定节点模板；目录接入留增强）。
- 团队（P6-3 supervisor）画布化——本期只做 flow 编排，团队面板不动。

---

## 1. 决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | 嵌套模型 = 受限树（根组 + 子项为节点或组，**组内不再嵌组**，深度≤2），不是任意 DAG | 覆盖"并行探查→串行汇总→并行验证"主场景；审批范围可读可审计；官方 FlowAgent 嵌套即可执行，零自研引擎 |
| D2 | 画布是"结构的直接操纵视图"：布局由结构自动推导，**拖拽=结构操作**（重排/入组/出组），不做自由坐标 | 执行语义由结构决定；自由坐标+结构推导连线会产生"图上看着像、执行不是"的视觉谎言；免坐标持久化。与 ComfyUI 的差异点，明示给用户 |
| D3 | 三个 ComfyUI 精髓落地：直接操纵（拖拽/就地增删）、参数内嵌节点（任务/模型/工具模式在节点上编辑）、执行态实时回放（运行节点高亮/失败标红/完成打勾） | 这三项决定"好用感"，且都不依赖自由 DAG |
| D4 | 后端递归编译：组→官方 FlowAgent，叶子→`DefaultFlowNodeAgentFactory` ReactAgent；每个并行组 mergeOutputKey = `<groupId>_output` | 薄封装路线不变；嵌套类型已用本地 jar 验证 |
| D5 | `BabiqFlowSpec` 增加可空 `structure`；为空 = 旧平铺语义（全局 topology + 节点序），所有旧数据/旧协议自动兼容 | 不破坏 V14 已落库数据和 P6-2 既有测试 |
| D6 | V20 migration：`bq_orchestrations` + `bq_work_unit_configs` 各加 `structure_json TEXT`（可空），三件套齐全 | 复用既有表；可空列零迁移成本 |
| D7 | 画布纯 Compose 标准 API 实现，不引第三方图形/布局库 | **2026-06-13 选型调研结论**（按 CLAUDE.md §4 顺序查证）：① 成熟节点编辑器全在 Web 系（React Flow、litegraph.js、AntV G6、Neo4j NVL、Relation Graph 等，均 JS/TS），接入 Compose 唯一途径是嵌 JCEF/WebView——Chromium 运行时 + JS 桥 + 主题/手势割裂，引入成本过高；② JVM 系图可视化库已停滞或不匹配：JGraphX 2020 年官方 EOL、JUNG 多年无维护、GraphStream 是研究型查看器非编辑器，且全是 Swing/JavaFX 渲染需 SwingPanel 互操作；yFiles 商业许可；ELK Java 布局内核真实可用但带 Eclipse/EMF 依赖，对 ≤16 节点受限串并树是大炮打蚊子；③ Compose 生态无任何成熟节点编辑库（Context7 双关键词检索为空）。**引库省不掉真正的工作量**：渲染+布局只占 Task 6/7 约 300 行可单测代码，大头是与 `BabiqFlowStructure` 强耦合的交互语义（drop zone/undo/双模式），任何图形库都不提供。备选：若实现期拖拽机制遇阻，`mohamedrejeb/compose-dnd`（Compose 多平台拖放库，质量良好）为预审定的唯一候补，但其列表重排模型与缩放画布 drop zone 不直接匹配，默认不用 |
| D8 | 画布活在右侧详情展开分屏（复用现有展开态）；面板收起时显示现有紧凑节点列表 | 遵守 p6-master "对话始终主体"UI 模型；紧凑列表复用 `OrchestrationNodeRowView` 零成本 |
| D9 | 编辑/回放同一 `FlowCanvas`，按 mode 切换：编辑（配置期 WorkUnit）可改结构；回放（运行期 orchestration item）只读 + 状态着色。**回放态必须裁剪全部编辑 affordance**：线上插入点、模板/组按钮全部隐藏，启动按钮变"运行中"禁用态 | 一套布局/渲染两处用，状态来源都是现有协议事件，不加新协议；冻结后还显示编辑入口是语义矛盾（原型审查 A2） |
| D10 | 节点添加以**线上 "+" 插入菜单**为主（连线/组内空位上点 "+" 弹出：探查/实现/验证/自定义 + 并行组/路由组，插入位置所见即所得），画布工具栏模板按钮保留为"追加到末尾"快捷方式；新节点任务为空 + placeholder，**空任务禁止启动** | 采纳原型方案（比纯工具栏+afterEntry 更直观）；菜单必须含全部 4 模板 + 2 种组（原型缺路由组，实现补齐）；吸收层1缺陷修复 |
| D11 | 结构操作支持 undo/redo（内存双栈，深度 20，会话级） | 直接操纵必须可反悔；结构操作粒度小，快照成本可忽略 |
| D12 | 旧 `OrchestrationTopologyEditor` 卡片堆编辑器整体删除，其测试迁移到画布模型测试 | 避免双编辑器并存的状态同步问题 |
| D13 | START/END 不再伪装成"节点"：画布上是固定端点徽标；工作目标的编辑移到画布上方独立"目标"输入区，与节点任务彻底分离 | 修复层1发现的"START 任务=改 goal"暗坑和 `substringAfter("路")` 脆 hack |
| D14 | **视觉通道分离规范**：节点主体一律中性底色，角色只用左侧小色点表示；**颜色最强通道留给运行状态**（pending 灰 / running 强调色+呼吸 / completed 绿 / failed 红）；红色全局仅用于危险与失败语义，"编辑"等模式激活态用主题强调色 | 原型审查 A1：角色常驻色（如 reviewer 红）会在回放态被误读为失败，双重编码冲突必须让位 |
| D15 | 节点设置 = **锚定浮层**：跟随选中节点定位（带指向标记、自动避让其他节点与画布边界），字段含任务、模型、**工具模式（只读/工作区写入）**、**重命名**、删除节点 | 原型审查 A3：固定右上浮层与被编节点距离远且遮挡；mode 决定审批写入范围必须可见可改；"参数内嵌节点卡"在缩放画布上输入体验差，锚定浮层是务实折中 |
| D16 | 无"拖拽模式"开关：拖节点=结构操作（drop zone 高亮）、拖空白=平移画布，固定手势分工；组标签只显示"并行组/路由组"或自定义组名，技术 groupId 进 tooltip；不做"运行回放"侧卡（画布即状态显示器，收起态用现有紧凑列表） | 原型审查 B8/B6/B9：模式开关多一步心智负担且易残留；技术 id 不是 UI 文案；侧卡与画布信息重复 |
| D17 | **对话式配置编辑**：扩展 `work_unit_manage` 新增 `read_config`（读当前草稿 nodes+structure+校验状态）与 `update_config`（**全量覆盖**提交，JSON 与桌面 `workunit/config/update` 同构），增删改节点 = 模型"读→改→全量写"；不做 add_node/remove_node 增量 action。编辑只动草稿、运行中容器拒绝；启动/移除沿用既有显式语义（agent 不能绕过 approve-once）。成功后 emit `WorkUnitItem` 驱动画布实时刷新；桌面本地有未保存草稿时提示"配置已被 Agent 更新"，提供[加载最新]/[保留草稿]（last-write-wins + V18 快照审计，不做三方合并） | 全量覆盖是 BaBiQ 已验证的模式（P4 `update_plan` 同语义）：无 op 合并冲突、快照天然可审计、schema 复用 orchestrate_flow 的 nodes+structure 形态；刷新通道复用现有 WorkUnitItem 协议零新增 |

---

## 2. 文件结构总览

```
backend/
├── src/main/java/com/wzx/babiq/server/agent/flow/
│   ├── BabiqFlowStructure.java                # 新建：嵌套树（FlowStructureEntry = NodeRef | Group）+ 校验/展平/升格
│   ├── BabiqFlowSpec.java                     # 修改：+structure 可空组件，flatten 兼容
│   ├── FlowOrchestrationService.java          # 修改：递归编译 buildOfficialFlowAgent
│   ├── FlowApprovalService.java               # 修改：审批范围按树分组展示
│   ├── OrchestrationRecord.java               # 修改：+structureJson
│   └── OrchestrationRepository.java           # 修改：读写 structureJson
├── src/main/java/com/wzx/babiq/server/tool/impl/
│   ├── FlowOrchestrationTool.java             # 修改：orchestrate_flow 增可选 structure 入参 + 解析校验
│   └── WorkUnitManageTool.java                # 修改：+read_config/update_config 对话式配置编辑（D17）
├── src/main/java/com/wzx/babiq/server/agent/
│   └── ReActStrategy.java                     # 修改：system prompt 编排段补"读→改→全量写"引导
├── src/main/java/com/wzx/babiq/server/capability/
│   └── CapabilityAliasDictionary.java         # 修改：补"节点/加节点/删节点/改节点/编辑编排"中文别名
├── src/main/java/com/wzx/babiq/server/workunit/
│   ├── WorkUnitConfig.java                    # 修改：+structureJson
│   └── DefaultWorkUnitService.java            # 修改：config 校验（空任务/结构合法性）
├── src/main/java/com/wzx/babiq/server/persistence/
│   ├── entity/OrchestrationEntity.java        # 修改：+structure_json 字段注释
│   └── entity/WorkUnitConfigEntity.java       # 修改：+structure_json 字段注释
├── src/main/resources/db/migration/
│   └── V20__flow_canvas_structure.sql         # 新建
└── src/test/java/com/wzx/babiq/server/agent/flow/
    ├── BabiqFlowStructureTest.java            # 新建
    ├── FlowOrchestrationServiceNestedTest.java# 新建（含嵌套 saver/mergeKey spike 断言）
    └── （FlowOrchestrationServiceTest / FlowApprovalServiceTest / ThreadItemJsonTest 增量）

desktop/
├── src/main/kotlin/com/wzx/babiq/desktop/
│   ├── protocol/FlowStructureModels.kt        # 新建：FlowStructureDto（与后端 JSON 同构）
│   ├── protocol/WorkUnitModels.kt             # 修改：WorkUnitConfiguration +structure
│   ├── protocol/ThreadModels.kt               # 修改：orchestration item +structure
│   ├── ui/flowcanvas/FlowGraphModel.kt        # 新建：画布图模型 + 结构操作（纯函数：增删/重排/入组/出组/undo）
│   ├── ui/flowcanvas/FlowCanvasLayout.kt      # 新建：自动分层布局（纯函数，输出节点矩形+边路径锚点）
│   ├── ui/flowcanvas/FlowCanvas.kt            # 新建：渲染 + 缩放/平移/拖拽手势 + 贝塞尔连线
│   ├── ui/flowcanvas/FlowNodeCard.kt          # 新建：节点卡（内嵌任务编辑/模型 chip/模式 chip/状态徽标）
│   ├── ui/runtime/OrchestrationSection.kt     # 重构：编辑态接 FlowCanvas，删除 OrchestrationTopologyEditor 卡片堆
│   └── state/ChatController.kt                # 修改：structure 贯通保存/校验
└── src/test/kotlin/com/wzx/babiq/desktop/
    ├── ui/flowcanvas/FlowGraphModelTest.kt    # 新建
    ├── ui/flowcanvas/FlowCanvasLayoutTest.kt  # 新建
    └── （OrchestrationSectionTest / WorkUnitModelsTest / ThreadItemJsonTest / ChatControllerTest 增量）
```

---

## 3. 核心数据模型（先定形，Task 1/4 直接照此实现）

后端嵌套树（同构 JSON 在协议/数据库/桌面三处共用）：

```java
/**
 * 流程嵌套结构树。
 *
 * <p>P8 的核心模型：把 P6-2 的"全局单拓扑 + 平铺节点"升级为受限嵌套树。
 * 受限点：根必须是组；组的子项是节点引用或组；组内不再嵌组（深度≤2）。
 * 这样既覆盖"并行探查→串行汇总"的主场景，又保证审批范围可平铺解释、
 * 官方 FlowAgent 可直接递归装配。</p>
 */
public record BabiqFlowStructure(FlowGroup root) {

    /** 组：拓扑 + 有序子项。groupId 用于并行 mergeOutputKey 和 UI 选中。 */
    public record FlowGroup(String groupId, BabiqFlowTopology topology, List<FlowEntry> children) implements FlowEntry { }

    /** 叶子：引用 BabiqFlowSpec.nodes 里的 nodeId，节点本体仍平铺存放（运行记录归属不变）。 */
    public record FlowNodeRef(String nodeId) implements FlowEntry { }

    public sealed interface FlowEntry permits FlowGroup, FlowNodeRef { }
}
```

JSON 形态（`structure_json` 列与协议 `structure` 字段同构）：

```json
{"root": {"groupId": "g_root", "topology": "SEQUENTIAL", "children": [
  {"nodeId": "explorer_a"},
  {"groupId": "g_par_1", "topology": "PARALLEL", "children": [{"nodeId": "worker_1"}, {"nodeId": "worker_2"}]},
  {"nodeId": "reviewer"}
]}}
```

校验规则（构造期强制）：根非空；**`spec.nodes()` 中每个节点必须被结构恰好引用一次**（引用集合 == 节点集合，杜绝"审批范围里有、执行时不跑"的孤儿节点）；组内不嵌组；PARALLEL/ROUTING 组子项 ≥2；空组拒绝。`structure == null` 时按旧语义：`root = group(spec.topology, 全部节点按 order)`——`升格函数 fromLegacy(spec)` 提供。

---

## 4. 任务分解

### Task 1: 后端嵌套树模型 `BabiqFlowStructure`

**Files:** Create `agent/flow/BabiqFlowStructure.java`；Test `agent/flow/BabiqFlowStructureTest.java`

- [ ] **Step 1: 写失败测试**：合法树构造成功；组内嵌组抛错；引用不存在 nodeId 抛错；重复引用抛错；**漏引用节点（孤儿节点）抛错**；PARALLEL 组 1 个子项抛错；`fromLegacy` 把平铺 spec 升格为单组树；`flattenNodeIds()` 按深度优先返回有序 id；JSON 序列化往返（Jackson 多态：children 元素按有无 `nodeId`/`groupId` 区分）。
- [ ] **Step 2: 跑失败** `cd backend; .\mvnw.cmd -q "-Dtest=BabiqFlowStructureTest" test`
- [ ] **Step 3: 实现**（§3 形态 + 全部中文注释 + Jackson `@JsonTypeInfo(deduction)` 或手写多态解析，二选一以 1.1.6 锁定的 Jackson 版本实测为准）
- [ ] **Step 4: 跑通过** → **Step 5: Commit** `feat(p8): 新增流程嵌套结构树模型`

### Task 2: `BabiqFlowSpec` 接入 structure + 递归编译

**Files:** Modify `BabiqFlowSpec.java`（+可空 `structure` 组件，兼容构造器，冻结校验含 structure）、`FlowOrchestrationService.java`；Create `FlowOrchestrationServiceNestedTest.java`

- [ ] **Step 1: 写失败测试**：
  - `nested_sequential_with_parallel_group_should_build_official_agents`：串行根内嵌并行组 → 断言根是 `SequentialAgent`，其 subAgents 中第二项是 `ParallelAgent`（反射或类型断言），并行组 mergeOutputKey = `g_par_1_output`。
  - `structure_null_should_fall_back_to_legacy_flat_build`：旧平铺 spec 行为与 P6-2 现有测试完全一致（回归锁）。
  - `routing_group_should_use_routing_model_supplier`。
  - **spike 断言**（§0 待核对项落地）：两个并行组串联执行 fake 节点，两组输出都进入 state、互不覆盖；子组 saver 配置按 spike 结论固化并在注释记录原因。
- [ ] **Step 2: 跑失败** → **Step 3: 实现**：`buildOfficialFlowAgent` 改为 `compile(entry)` 递归——

```java
/** 递归把结构树编译为官方 FlowAgent；叶子复用既有 nodeAgentFactory，组按拓扑映射官方构件。 */
private Agent compile(BabiqFlowStructure.FlowEntry entry, BabiqFlowSpec spec, ToolContext ctx) {
    if (entry instanceof BabiqFlowStructure.FlowNodeRef ref) {
        return nodeAgentFactory.create(spec.node(ref.nodeId()).orElseThrow(), ctx);
    }
    BabiqFlowStructure.FlowGroup group = (BabiqFlowStructure.FlowGroup) entry;
    List<Agent> children = group.children().stream().map(child -> compile(child, spec, ctx)).toList();
    return switch (group.topology()) { /* 三种官方 builder，Parallel 用 group.groupId()+"_output" */ };
}
```

- [ ] **Step 4: 跑通过**（含 P6-2 既有 `FlowOrchestrationServiceTest`/`FlowNodeRuntimeTest`/`FlowConcurrencyAttributionTest` 零回退）→ **Step 5: Commit** `feat(p8): 流程规格支持嵌套结构并递归编译官方 FlowAgent`

### Task 3: V20 migration + 持久化贯通

**Files:** Create `V20__flow_canvas_structure.sql`；Modify `OrchestrationEntity/Record/Repository`、`WorkUnitConfigEntity`、`WorkUnitConfig`、对应持久化 service；Test：`SchemaCommentsCoverageTest`（自动）、`OrchestrationRepositoryTest` 增量（编排结构往返）、`WorkUnitServiceTest` 增量（配置结构往返）

- [ ] **Step 1: 失败测试**：orchestration 落库带 structure_json 往返；WorkUnit config 带 structure 往返；旧行（NULL）读出 structure 为空不报错。
- [ ] **Step 2: 跑失败** → **Step 3: 实现**（SQL 中文注释 + `bq_schema_comments` 两表两行 + Entity 字段注释，三件套）：

```sql
-- P8 画布编排：流程嵌套结构 JSON；为空表示 V20 之前的平铺拓扑语义。
ALTER TABLE bq_orchestrations ADD COLUMN structure_json TEXT;
ALTER TABLE bq_work_unit_configs ADD COLUMN structure_json TEXT;
INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_orchestrations', 'structure_json', '流程嵌套结构树 JSON（组/节点引用）；为空表示旧版平铺拓扑，按 topology 字段解释。'),
('bq_work_unit_configs', 'structure_json', '工作容器编排配置的嵌套结构树 JSON；为空表示旧版平铺节点列表。');
```

- [ ] **Step 4: 跑通过**（`SchemaCommentsCoverageTest` 必须绿）→ **Step 5: Commit** `feat(p8): V20 编排嵌套结构持久化`

### Task 4: 协议贯通（orchestrate_flow 工具 + orchestration item + WorkUnit config）

**Files:** Modify `FlowOrchestrationTool.java`（structure 可选入参 + 校验 + 审批范围按组分段）、`FlowApprovalService.java`、orchestration ThreadItem、`DefaultWorkUnitService`（**启动路径**校验空任务，config update 仍接受保存草稿）；桌面 `FlowStructureModels.kt`/`WorkUnitModels.kt`/`ThreadModels.kt`（桌面保存时占位文案一律存为空任务，后端只需校验空值，不维护占位字符串常量）；Test：Create `FlowOrchestrationToolTest.java`（新建专项类，注意既有类名是 `FlowOrchestrationToolWorkUnitTest`，不要混用）+ `FlowApprovalServiceTest`/`ThreadItemJsonTest`（双端）/`WorkUnitHandlersTest` 增量

- [ ] **Step 1: 失败测试**：工具收到嵌套 structure 正确建 spec；非法结构返回模型可读错误（不抛栈）；审批文案按组分段且**口语化分步 + 每节点标注读/写**（"第 2 步 并行执行：designer（只读）、tester（只读）"，技术结构串折叠进详情区）；item JSON 双端往返 structure 字段缺省兼容；**config 含空任务/`补充这个节点的任务`占位时 `workunit/config/update` 通过但 `启动` 校验拒绝并提示节点名**。
- [ ] **Step 2-4: 红→绿** → **Step 5: Commit** `feat(p8): 编排协议与工具支持嵌套结构`

### Task 4b: 对话式配置编辑（agent 直达画布草稿，D17）

**Files:** Modify `tool/impl/WorkUnitManageTool.java`（+`read_config`/`update_config` action）、`workunit/WorkUnitService.java`+`DefaultWorkUnitService.java`（复用 `workunit/config/update` 同一条 `updateConfiguration` 路径与 `BabiqFlowStructure` 校验，成功后 emit `WorkUnitItem`）、`agent/ReActStrategy.java`（system prompt 编排段）、`capability/CapabilityAliasDictionary.java`；Desktop Modify `state/ChatController.kt`+`ui/runtime/OrchestrationSection.kt`（草稿冲突提示条）；Test：`WorkUnitManageToolTest`、`WorkUnitServiceTest`、`CapabilityAliasDictionaryTest`、`SystemPromptSecurityRuleTest`（回归）、`ChatControllerTest`/`OrchestrationSectionTest` 增量

- [ ] **Step 1: 写失败测试（后端）**：
  - `read_config` 返回当前草稿 nodes+structure+校验状态（含空任务节点清单），容器不存在/类型不符报可读错误；
  - `update_config` 全量覆盖：非法结构（组内嵌组/孤儿节点）返回模型可读错误且不落库；合法提交生成 V18 快照 + V20 structure、emit `WorkUnitItem`；
  - **运行中容器拒绝 `update_config`**（冻结语义）；`update_config` 不改变 goal/启动状态（与 `start`/`append_goal` 职责互斥）；
  - 中文别名命中：`tool_search`"给编排加一个节点"能召回 `work_unit_manage`。
- [ ] **Step 2: 跑失败** → **Step 3: 实现**（action 描述写明"增删改节点请先 read_config 再整体 update_config"；system prompt 编排段补同样引导，改动后跑 `SystemPromptSecurityRuleTest` 回归）
- [ ] **Step 4: 写失败测试（桌面）**：本地存在未保存画布草稿时收到 `WorkUnitItem` 配置更新 → 显示"配置已被 Agent 更新"提示条 + [加载最新][保留草稿]；无本地草稿时画布静默刷新；选择保留草稿后再保存 = 正常生成新快照。
- [ ] **Step 5: 跑通过** → **Step 6: Commit** `feat(p8): 工作容器支持对话式节点配置编辑`

### Task 5: 桌面画布图模型与结构操作（纯函数层）

**Files:** Create `ui/flowcanvas/FlowGraphModel.kt`；Test `FlowGraphModelTest.kt`

- [ ] **Step 1: 失败测试**（全部纯函数，无 Compose 依赖）：`addNode(template, afterEntry)`；`addGroup(parallel/routing)`；`removeEntry`（删组时子节点提升回父序列）；`moveEntry(entry, dropTarget)`——重排/入组/出组三语义；组内不嵌组被拒；undo/redo 双栈（操作后可回退，redo 在新操作后清空）；与 `FlowStructureDto` 互转无损。
- [ ] **Step 2-4: 红→绿**（操作实现为不可变数据 + copy，便于 undo 快照）→ **Step 5: Commit** `feat(p8): 画布图模型与结构操作纯函数层`

### Task 6: 自动分层布局（纯函数）

**Files:** Create `ui/flowcanvas/FlowCanvasLayout.kt`；Test `FlowCanvasLayoutTest.kt`

- [ ] **Step 1: 失败测试**：串行链纵向等距；并行组横向分列、组框包络子节点、超宽时分支换行（每行最多 3 列）；边锚点 = 上节点底中心→下节点顶中心，组边界处分叉/汇聚到组框锚点；输出 `CanvasLayout(nodeRects, groupRects, edges: List<EdgePath>)` 坐标确定性（同输入同输出）。
- [ ] **Step 2-4: 红→绿**（节点固定尺寸 180×84dp 起步；布局算法 ~100 行：深度优先测量子树宽度 → 自上而下定位）→ **Step 5: Commit** `feat(p8): 画布自动分层布局算法`

### Task 7: FlowCanvas 渲染 + 手势

**Files:** Create `ui/flowcanvas/FlowCanvas.kt`、`FlowNodeCard.kt`；Test：节点卡状态映射/手势换算用例（`FlowCanvasTest.kt`，逻辑层），视觉人工验收

- [ ] **Step 1: 失败测试**：缩放换算 `zoomAt(cursor, delta)` 保持光标下世界坐标不动（纯函数抽出）；缩放范围 clamp [0.4, 2.0]；节点状态→样式映射遵循 D14（主体中性底色 + 角色色点；pending 灰/running 强调色+呼吸/completed 绿勾/failed 红，**状态徽标互斥**——running 节点不得同时显示"空"徽标）；手势分工固定（D16）：节点区域拖动产生结构操作意图、空白区域拖动产生平移，无模式开关。
- [ ] **Step 2-4: 红→绿**，渲染结构（关键骨架）：

```kotlin
// 画布：外层捕获缩放(滚轮)与平移(空白拖拽)，内层 graphicsLayer 应用变换；
// 连线画在节点层之下的 Canvas 上，节点是普通 composable（保证内嵌输入框可交互）。
Box(Modifier.clipToBounds()
    .onPointerEvent(PointerEventType.Scroll) { e -> camera = camera.zoomAt(e.position, e.scrollDelta.y) }
    .pointerInput(Unit) { detectDragGestures { _, drag -> camera = camera.pan(drag) } }) {
    Box(Modifier.graphicsLayer {
        scaleX = camera.scale; scaleY = camera.scale
        translationX = camera.offset.x; translationY = camera.offset.y
        transformOrigin = TransformOrigin(0f, 0f)
    }) {
        Canvas(Modifier.size(layout.contentSize)) { layout.edges.forEach { drawBezierEdge(it) } }
        layout.groupRects.forEach { GroupFrame(it) }          // 并行/路由组的虚线包络框
        layout.nodeRects.forEach { (entry, rect) -> FlowNodeCard(entry, rect, mode, ...) }
    }
}
```

- [ ] **Step 5: Commit** `feat(p8): 画布渲染与缩放平移手势`

### Task 8: 编辑交互（工具栏/拖拽/内嵌编辑/删除/undo）

**Files:** Modify `FlowCanvas.kt`/`FlowNodeCard.kt`；重构 `OrchestrationSection.kt`（编辑态换画布、删除 `OrchestrationTopologyEditor`/卡片堆及其辅助函数、目标输入区独立于画布上方）；Modify `ChatController.kt`（structure 保存、启动前校验、dirty 提示）；Test：`OrchestrationSectionTest` 重写 + `ChatControllerTest` 增量

- [ ] **Step 1: 失败测试**：**线上 "+" 插入菜单**在指定边/组内位置产出正确结构操作，菜单含 4 模板 + 并行组 + 路由组（D10）；工具栏模板按钮等价"追加到末尾"；节点拖到 drop zone（节点间隙/组内/组外）映射 `moveEntry`；节点卡 "×" 与**锚定浮层**"删除节点"都真实删除（层1缺陷回归测试）；锚定浮层定位在选中节点旁且含任务/模型/**工具模式**/**重命名**字段（D15），改 mode 后保存进 config；组标签不渲染技术 groupId（D16）；整体"保存配置"调 `workunit/config/update` 一次（替换逐节点保存）；存在未保存改动时显示 dirty 标识；`Ctrl+Z`/`Ctrl+Shift+Z` 触发 undo/redo；空任务节点启动按钮禁用并提示。
- [ ] **Step 2-4: 红→绿** → **Step 5: Commit** `feat(p8): 画布结构编辑与统一保存`

### Task 9: 运行态回放模式

**Files:** Modify `OrchestrationSection.kt`（运行期 orchestration item → 只读画布；收起态保留现有紧凑列表）；Test：`OrchestrationSectionTest` 增量

- [ ] **Step 1: 失败测试**：运行态 model 把 item.nodes 状态映射进画布节点样式；**回放态裁剪断言（D9）**——无线上插入点、无模板/组按钮、启动按钮为"运行中"禁用态、节点不可拖拽、浮层只读；**失败态展示**——failed 节点红框 + 错误摘要可见 + 下游未执行节点呈中止灰态；面板收起 → 紧凑列表（现有 `OrchestrationNodeRowView` 复用）；展开 → 画布回放；structure 缺失的旧运行记录按平铺升格展示。
- [ ] **Step 2-4: 红→绿** → **Step 5: Commit** `feat(p8): 编排运行态画布回放`

### Task 10: 全量验证 + 文档同步

- [ ] **Step 1: 后端**

```powershell
cd backend
.\mvnw.cmd "-Dtest=BabiqFlowStructureTest,FlowOrchestrationServiceNestedTest,FlowOrchestrationServiceTest,FlowApprovalServiceTest,FlowNodeRuntimeTest,FlowConcurrencyAttributionTest,FlowOrchestrationToolTest,WorkUnitManageToolTest,CapabilityAliasDictionaryTest,SystemPromptSecurityRuleTest,ThreadItemJsonTest,WorkUnitServiceTest,WorkUnitHandlersTest,SchemaCommentsCoverageTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify
```

- [ ] **Step 2: 桌面**

```powershell
cd desktop
.\gradlew.bat test --tests "*FlowGraphModelTest" --tests "*FlowCanvasLayoutTest" --tests "*FlowCanvasTest" --tests "*OrchestrationSectionTest" --tests "*WorkUnitModelsTest" --tests "*ThreadItemJsonTest" --tests "*ChatControllerTest"
.\gradlew.bat test --rerun-tasks
```

- [ ] **Step 3: 同步 CLAUDE.md / AGENTS.md**（检查点 + 验收命令 + 阶段边界：自由 DAG/Loop/运行中编辑仍不做）
- [ ] **Step 4: Commit** `docs(p8): 同步画布编排编辑器检查点`

---

## 5. 人工烟测清单（自动化通过后、声称完成前必做）

1. **编辑体验**：`/编排 烟测画布` 建 WorkUnit → 展开详情分屏 → 用**线上 "+" 菜单**加"探查×2 + 并行组（实现/验证入组）"，再用工具栏按钮追加一个自定义节点 → 拖拽把一个探查节点拖进并行组、再拖出 → undo/redo 各两步 → 锚定浮层改任务/模型/**工具模式**/**重命名** → 整体保存 → 重开会话配置不丢。
2. **校验**：留一个空任务节点 → 启动按钮禁用且提示节点名；填好后可启动。
3. **缩放/平移**：滚轮以光标为中心缩放、空白拖拽平移、8 节点+2 组布局无重叠、连线分叉汇聚正确、缩放 0.4–2.0 不糊不裁切。
4. **真实模型嵌套运行**：启动"并行探查→串行汇总"flow（含写节点走 approve-once 弹窗，范围口语化分步 + 读/写标注）→ 画布实时高亮运行节点、完成打勾；**回放态确认无任何编辑入口**（无 "+"、无模板按钮、启动为禁用态）；运行记录按节点归属正确（`bq_tool_calls`）；TurnSummary token 正常。
5. **失败态**：构造一个必失败节点（如读取不存在路径）→ 该节点红框 + 错误摘要可见，下游节点呈中止灰态，流程状态/`bq_orchestrations` 记录 failed。
6. **回归**：旧平铺编排（不带 structure 的历史记录）回放正常；P6-1 子代理、P6-3 团队、P6-4 slash 不受影响；对话栏全程可用。
7. **降级**：分屏宽度 <600dp 时回退紧凑列表不崩。
8. **对话式编辑**：对话输入"给编排加一个并行组，里面放两个探查节点"→ 画布实时出现对应结构；"把 writer 的任务改成 XX"→ 节点任务更新；"删掉 tester"→ 节点消失且结构合法；画布上留有未保存草稿时让 Agent 改配置 → 出现"配置已被 Agent 更新"提示条，[加载最新]/[保留草稿] 行为正确；对运行中容器要求改节点 → Agent 返回"已冻结不能修改"的可读拒绝。

## 6. 风险与回退

| 风险 | 缓解 |
|---|---|
| 嵌套 FlowAgent 的 saver/CompileConfig 语义与预期不符 | §0 待核对项做最小 spike 先行（Task 2 Step 1 内）；若官方嵌套有硬伤，回退方案=结构树仅作展示、执行仍平铺（拓扑受限为"单层组"），UI 不受影响 |
| 并行组 mergeOutputKey 冲突覆盖 state | groupId 唯一性构造期校验 + spike 断言 |
| Compose 手势在 Windows 滚轮/高 DPI 步长差异 | 缩放系数集中一处常量；烟测 #3 实测调参 |
| `OrchestrationSection` 重构破坏 P6-4 工作容器交互 | 现有 `OrchestrationSectionTest`/`WorkUnitSectionTest`/`RuntimeDetailsPanelTest` 全量回归 + 烟测 #5 |
| 旧数据/旧协议不带 structure | D5 可空兼容 + `fromLegacy` 升格 + 专项回归测试 |
| 画布在窄分屏不可用 | <600dp 自动降级紧凑列表（Task 9） |
