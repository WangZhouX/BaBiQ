# P8 画布编排编辑器 补做计划（Remediation）

> **For agentic workers:** REQUIRED SUB-SKILL：用 `superpowers:test-driven-development` 逐任务实现，`superpowers:verification-before-completion` 收尾。所有步骤用 checkbox（`- [x]`）跟踪。
>
> **开工前必读（同目录）：** `plan.md`（D1–D18 决策、Task 1–10 原始拆解、§5 人工烟测清单）、`prototype-review.md`（视觉基准）、`codex-handoff.md`。本文件只补“已核实未做/做错”的部分，**设计语义一律以 `plan.md` 为准**，不要在本文件里重新发明设计。

**Goal:** 补齐 P8 已核实缺失/做错的画布交互与对话式编辑能力，使 P8 真正达到 `plan.md` 声明的范围，并修正被夸大的完成度文档。

**执行状态（2026-06-13）：** R1-R8 已按当前工作区完成代码补做和自动化验证，R9 已补四组验证命令与文档纠偏；人工烟测仍按 §4/§5 标注为“未执行 + 原因”，不得声明 P8 全量验收通过。

**Architecture:** 桌面画布继续用纯 Compose 标准 API（不引第三方图形/布局库）；核心层 `flowcanvas` 包保持零 BaBiQ 业务依赖（受 `FlowCanvasPortabilityTest` 守卫）；对话式编辑薄封装到既有 `WorkUnitManageTool` 的 action 上，复用 `workunit/config/update` 同一条落库路径，继续走审批/沙箱/SQLite 审计。

**Tech Stack:** Java 21 / Spring Boot / Spring AI Alibaba（后端工具 + system prompt）、Kotlin / Compose Desktop（画布与交互）。

---

## 0. 本次补做的由来（独立核实结论，2026-06-13）

> 这些是对 `4d4055e feat(p8): 完成画布编排编辑器` 当前代码逐文件核实的结果，不是猜测。`implementation-report.md` 声称的“代码实现 + 自动化验收完成”**与代码不符**：后端 436 / 桌面 252 测试 0 失败是真的，但**缺失的功能根本没有对应测试可以失败**——这是“全绿但没做全”。

| 区块 | 真实状态 | 代码出处 |
|---|---|---|
| Task 1 受限结构树 / Task 2 递归编译 / Task 3 V20 持久化 / Task 4 协议贯通 | ✅ 真做，回归不能破 | 后端 `BabiqFlowStructure`、`FlowOrchestrationService` 等 |
| Task 5 核心图模型 + 适配层 + 可移植性守卫 | ⚠️ 部分：`insert*`/`removeNode`/`replaceNode`/`FlowGraphHistory(undo/redo)` 有且有测试；**`moveEntry` 不存在**，`InsertMode` 无 `Routing` | `flowcanvas/FlowGraphModel.kt`（无 `moveEntry`；`InsertMode` 仅 Serial/Parallel，第 162-165 行） |
| Task 6 自动分层布局 | ✅ 真做，含测试 | `flowcanvas/FlowCanvasLayout.kt` |
| Task 7 画布渲染 | ✅ 渲染（边/箭头/组框/START·END/节点/「+」插入菜单/选择）真做 | `flowcanvas/FlowCanvas.kt` |
| **Task 7 缩放/平移相机** | ❌ **完全没做**：全 desktop 源码 grep 不到 `graphicsLayer`/`PointerEventType.Scroll`/`zoomAt`/`clamp`/`0.4`/`2.0`；画布固定尺寸绝对定位，大图无法导航 | `FlowCanvas.kt`（只有 `drawGrid` 的 DPI `scale`，无相机） |
| **Task 7 逻辑测试 `FlowCanvasTest.kt`** | ❌ **不存在** | `desktop/src/test/.../flowcanvas/` 下只有 Layout/Portability/GraphModel 三个测试 |
| **Task 8 拖拽改结构（moveEntry）** | ❌ 核心无 `moveEntry`，UI 无拖拽手势 | `FlowGraphModel.kt`、`FlowNodeCard.kt`（节点只有 `clickable`，无拖拽） |
| **Task 8 undo/redo 接 UI** | ❌ 核心 `FlowGraphHistory` 在，但 `OrchestrationConfigPanel` 用裸 `FlowGraph`，无 `Ctrl+Z`/`Ctrl+Shift+Z` | `OrchestrationSection.kt:227`（`var graph`，未用 History） |
| **Task 8 Routing 组** | ❌ 建不出：`FlowInsertKind.Routing` 被错误地走 `insertParallel`，生成并行组 | `OrchestrationSection.kt:289` |
| **Task 8 节点编辑 4 字段（D15）** | ❌ 只有 任务+模型+删除；**缺 工具模式 选择器、缺 重命名**；且非“节点旁锚定浮层”，是画布下方面板 | `OrchestrationSection.kt:295-345` |
| **Task 9 失败态展示（D9）** | ❌ failed 仅把 “ERR” 文字染色；**无红框、无错误摘要（`FlowNode` 无 error 字段）、无下游中止灰态** | `FlowNodeCard.kt:103-118`、`FlowGraphModel.kt:41-51` |
| **Task 4b 对话式配置编辑（D17）** | ❌ **完全没做**：`WorkUnitManageTool` 只有 `append_goal/update_goal/start/remove`；无 `read_config`/`update_config`；别名字典无对应词；桌面无“配置已被 Agent 更新”草稿冲突提示条 | `tool/impl/WorkUnitManageTool.java:42-90`、`capability/CapabilityAliasDictionary.java:93-94` |

**结论：P8 现状 = 结构后端 + 静态画布渲染 + 「+」插入菜单 + 任务/模型编辑。交互层（缩放/平移/拖拽/undo）、Routing 组、失败态、对话式编辑均未达 `plan.md` 范围，P8 不能算闭环。**

---

## 1. 元约束（针对上一轮的具体失败，必须逐条遵守）

> 上一轮的两个根因：(a) 跳过实现却让“全绿”掩盖；(b) 把“未实现”在报告里写成“未验证”。以下约束就是堵这两个洞。

1. **严禁把“未实现”写成“未验证”。** 每个 R 任务完成时，必须能指出**具体代码位置（`file:line`）**证明功能存在；§7 完成报告里凡声称“已实现”的，旁边必须附该代码位置。做不到就老实写“未实现”。
2. **“全绿”不等于“做全”。** 每个新功能必须**先有一个会失败的测试**（红），再实现到绿。补测试前若测试就能过，说明测试没真正覆盖该功能，要重写测试。
3. **每个 R 任务一笔中文 conventional commit**，类型前缀 `feat(p8):` 或 `fix(p8):`，方便逐条审查“哪几条真做了”。不要把多个 R 揉成一笔大提交（这正是上一轮的问题）。
4. **人工烟测做不了就逐项标注“未执行 + 原因”**（无头/无真实 Provider/无可操作桌面），**绝不标“通过”**。
5. **红线（不可触碰）：**
   - 画布只用纯 Compose 标准 API，**不引第三方节点图/布局/手势库**。
   - `FlowCanvasPortabilityTest` **不得 `@Disabled` 绕过**；`flowcanvas` 包不得 import `com.wzx.babiq.desktop.protocol`/`...state`/`...ui.theme`，白名单仅 `androidx.compose`/`kotlin`/`kotlinx`/`java`。
   - 受限结构树**深度 ≤ 1 级嵌套组**（组内不嵌组）语义不变；`BabiqFlowStructure` 校验回归不能破。
   - 运行前 **approve-once 冻结语义不变**：批准后 flow 结构冻结、运行中不可编辑（运行中逐节点审批属 P6-2b，不在本次范围）。
   - 自由 DAG / LoopAgent / 节点坐标持久化 / minimap / 团队画布化 **仍不做**。
6. **已做扎实的部分（Task 1/2/3/4/5 现有 `insert*`/`remove`/`history`/6）回归不能破**：补做完后合并 P8 后端套件与桌面套件必须仍全绿。

---

## 2. 文件影响总览

**后端（Task 4b）：**
- Modify `backend/src/main/java/com/wzx/babiq/server/tool/impl/WorkUnitManageTool.java`：+ `read_config` / `update_config` action。
- Modify `backend/src/main/java/com/wzx/babiq/server/workunit/WorkUnitService.java` + `DefaultWorkUnitService.java`：暴露/复用 `updateConfiguration`（与 `workunit/config/update` 同一路径 + `BabiqFlowStructure` 校验），成功后 emit `WorkUnitItem`；读取当前草稿（nodes + structure + 校验状态）。
- Modify `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`：system prompt 编排段补“增删改节点先 `read_config` 再整体 `update_config`”引导。
- Modify `backend/src/main/java/com/wzx/babiq/server/capability/CapabilityAliasDictionary.java`：补“节点/加节点/改节点/删节点”→ `work_unit_manage` 别名。

**桌面核心层（Task 5/7/9）：**
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowGraphModel.kt`：+ `moveEntry`（重排/入组/出组）、+ Routing 组插入、`FlowNode` + `error`/`aborted` 字段。
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowCanvas.kt`：+ 相机（缩放/平移）、+ 节点拖拽手势透出 `onMove`。
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowNodeCard.kt`：failed 红框 + 错误摘要；下游中止灰态。
- Create `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowCanvasCamera.kt`：相机纯函数（`zoomAt`/`pan`/`clamp`）。

**桌面接线层（Task 8/9 + 4b 提示条）：**
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/OrchestrationSection.kt`：接 `FlowGraphHistory` + 键位、修 Routing 分支、节点编辑加 工具模式/重命名、拖拽 `moveEntry`、失败态回放。
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`：收到 Agent 触发的 `WorkUnitItem` 配置更新时，若本地有未保存草稿则提示“配置已被 Agent 更新”。

**测试（必须先红后绿）：**
- Backend：`WorkUnitManageToolTest`、`WorkUnitServiceTest`、`CapabilityAliasDictionaryTest`、`SystemPromptSecurityRuleTest`（回归）。
- Desktop：新建 `flowcanvas/FlowCanvasCameraTest.kt`、`flowcanvas/FlowCanvasTest.kt`；`FlowGraphModelTest`（+moveEntry/Routing/error）、`OrchestrationSectionTest`、`ChatControllerTest` 增量。

---

## 3. 任务分解

> 建议顺序：**R1 → R2 → R3 → R4 → R5 → R6 → R7 → R8（文档）**。R1/R2/R3 是上一轮跳过、也是用户最在意的核心交互，优先做。

### R1：画布相机（缩放 + 平移）— Task 7

**现状（已核实）：** `FlowCanvas.kt` 无任何相机；画布固定尺寸绝对定位。
**目标：** 滚轮以光标为中心缩放（clamp `[0.4, 2.0]`）、空白区域拖拽平移；内层用 `graphicsLayer` 应用变换（见 `plan.md` Task 7 骨架）。相机换算抽成**纯函数**便于测试。

**Files:**
- Create `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowCanvasCamera.kt`
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowCanvas.kt`
- Test: Create `desktop/src/test/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowCanvasCameraTest.kt`

- [x] **Step 1：写失败测试** `FlowCanvasCameraTest.kt`
  - `zoomAt(cursor, delta)` 放大后，**光标下的世界坐标保持不动**（缩放围绕光标）；
  - 缩放 clamp：连续放大不超过 `2.0`、连续缩小不低于 `0.4`；
  - `pan(drag)` 累加偏移；
  - 纯函数，无 Compose 依赖（`FlowCanvasCamera` 是 data class + 纯函数）。
- [x] **Step 2：跑红** `cd desktop; .\gradlew.bat test --tests "*FlowCanvasCameraTest"` → 预期 FAIL（类不存在）。
- [x] **Step 3：实现** `FlowCanvasCamera`（`scale`/`offset` + `zoomAt`/`pan`/`clamp`），并在 `FlowCanvas.kt` 外层 `onPointerEvent(Scroll)` + 空白区 `detectDragGestures` 驱动相机，内层 `Box(Modifier.graphicsLayer { scaleX=…; scaleY=…; translationX=…; translationY=…; transformOrigin=TransformOrigin(0f,0f) })` 包裹现有节点/连线层。**注意：** 平移手势只在空白区，节点区域的拖拽留给 R2（手势分工 D16），避免冲突。
- [x] **Step 4：跑绿** 同 Step 2 命令 → PASS。
- [x] **Step 5：Commit** `feat(p8): 画布相机缩放与平移`

---

### R2：节点拖拽改结构 `moveEntry` — Task 5 核心 + Task 8 手势

**现状（已核实）：** `FlowGraphModel.kt` 无 `moveEntry`；`FlowNodeCard` 节点只有 `clickable`。
**目标：** 核心层补 `moveEntry(nodeId, dropTarget)` 三语义（同级重排 / 拖入并行组 / 拖出到父序列），UI 上节点可拖到 drop 目标触发 `moveEntry`。保持不可变 + copy，便于 undo 快照（配合 R3）。

**Files:**
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowGraphModel.kt`
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowCanvas.kt` / `FlowNodeCard.kt`
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/OrchestrationSection.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowGraphModelTest.kt`（增量）

- [x] **Step 1：写失败测试**（核心层纯函数）：`moveEntry` 同级重排顺序正确；拖入并行组后该节点成为组子节点；拖出后回到父序列对应位置；移动后**结构仍满足深度 ≤ 1 与“每节点恰好引用一次”**（复用既有 `validate()`）；非法移动（如制造组内嵌组）被拒/无副作用。
- [x] **Step 2：跑红** `cd desktop; .\gradlew.bat test --tests "*FlowGraphModelTest"` → FAIL。
- [x] **Step 3：实现** `FlowGraph.moveEntry(...)`（递归在 `root` 上删除再插入，复用现有 `removeNode` + `insert*` 私有逻辑），并在 `FlowCanvas`/`FlowNodeCard` 节点上加 `detectDragGestures`，拖拽结束按落点映射 drop 目标，回调 `onMove(nodeId, dropTarget)`；`OrchestrationSection` 接收后 `graph = graph.moveEntry(...)` 并 `persistGraph`。手势分工遵循 D16：节点区拖拽 = 结构操作，空白区拖拽 = 平移（R1）。
- [x] **Step 4：跑绿** 同 Step 2 → PASS。
- [x] **Step 5：Commit** `feat(p8): 画布节点拖拽改结构 moveEntry`

---

### R3：undo / redo 接入编辑 UI — Task 8

**现状（已核实）：** 核心 `FlowGraphHistory(undo/redo)` 存在且有测试，但 `OrchestrationConfigPanel`（`OrchestrationSection.kt:227`）用裸 `var graph`，没接历史，`Ctrl+Z` 无效。
**目标：** 编辑态用 `FlowGraphHistory` 承载 graph；每次结构操作（插入/删除/移动/改节点）走 `history.apply(next)`；`Ctrl+Z` undo、`Ctrl+Shift+Z` redo。

**Files:**
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/OrchestrationSection.kt`
- Test: `desktop/src/test/kotlin/.../OrchestrationSectionTest.kt`（逻辑层增量）+ `FlowGraphModelTest`（history 已覆盖，确认 apply 清空 redo 栈）

- [x] **Step 1：写失败测试**：编辑态状态持有 `FlowGraphHistory`；插入节点后 undo 回到上一图、redo 复原；新操作后 redo 栈清空（若 `FlowGraphHistory` 已覆盖该语义，则在 `OrchestrationSection` 逻辑层断言“操作→undo→redo”链路调用了 history 而非裸 copy）。
- [x] **Step 2：跑红** → FAIL。
- [x] **Step 3：实现**：`var history by remember { mutableStateOf(FlowGraphHistory(initialGraph)) }`，所有结构写操作改为 `history = history.apply(next)`；选择/草稿编辑不入历史；在面板根 `Modifier.onPreviewKeyEvent { … Ctrl+Z / Ctrl+Shift+Z … }` 触发 undo/redo；undo/redo 后 `persistGraph(history.current)`。
- [x] **Step 4：跑绿** → PASS。
- [x] **Step 5：Commit** `feat(p8): 画布编辑接入 undo/redo`

---

### R4：修复 Routing 组无法创建 — Task 5/8 缺陷

**现状（已核实）：** `InsertMode` 仅 Serial/Parallel；`OrchestrationSection.kt:289` 把 `FlowInsertKind.Routing` 走 `insertParallel`，实际生成并行组。
**目标：** Routing 分支能真正生成 `FlowTopology.Routing` 组；编译/回放/标签都按路由组语义。

**Files:**
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowGraphModel.kt`
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/OrchestrationSection.kt:289`
- Test: `FlowGraphModelTest`（增量）

- [x] **Step 1：写失败测试**：`insertRouting(anchor, node)` 生成 `topology=Routing` 的组（或参数化 `insertGroup(topology)`）；现有 Parallel 行为不变；深度/引用唯一性校验通过。
- [x] **Step 2：跑红** → FAIL（当前会生成 Parallel）。
- [x] **Step 3：实现**：`InsertMode` 增 `Routing` 或把 `insertParallel` 泛化为 `insertGroup(topology)`；修 `OrchestrationSection.kt:289` 让 `FlowInsertKind.Routing` 调路由插入。
- [x] **Step 4：跑绿** → PASS。
- [x] **Step 5：Commit** `fix(p8): 画布路由组插入生成 Routing 拓扑`

---

### R5：节点编辑补齐「工具模式 + 重命名」— Task 8 / D15

**现状（已核实）：** `OrchestrationSection.kt:295-345` 节点编辑只有 任务/模型/删除。
**目标：** 节点编辑区补 **工具模式选择器**（`FlowNodeMode.ReadOnlyTool/WorkspaceTool`）和 **重命名（title）字段**，保存进 config（`mode` 落入 `update_config` / `workunit/config/update`）。（“锚定浮层定位在节点旁”是 D15 的视觉目标，若工作量大可保留当前面板形态，但**4 个字段不能少**——本任务以字段齐全为硬验收，浮层定位为可选增强。）

**Files:**
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/OrchestrationSection.kt`
- Test: `OrchestrationSectionTest`（逻辑层增量）+ 确认 `buildFlowConfigJson`/`buildFlowStructureJson` 带上 `mode`/`title`

- [x] **Step 1：写失败测试**：改 mode 后保存，生成的 config JSON 含新 `mode`；改 title 后节点 `title` 更新且结构 JSON 一致；`nodeChanged` 在 mode/title 变化时也置真（当前只看 task/model）。
- [x] **Step 2：跑红** → FAIL。
- [x] **Step 3：实现**：编辑区加 mode 下拉（只读/写两档，对应 D14 角色色点）和 title `OutlinedTextField`；`Save node` 把 `mode`/`title` 一并写入 `node.copy(...)`；扩展 `nodeChanged` 判定。
- [x] **Step 4：跑绿** → PASS。
- [x] **Step 5：Commit** `feat(p8): 节点编辑支持工具模式与重命名`

---

### R6：失败态展示 — Task 9 / D9

**现状（已核实）：** `FlowNodeCard.kt:103-118` failed 仅 “ERR” 染色；`FlowNode` 无 error 字段；无红框/错误摘要/下游灰态。
**目标：** failed 节点红框 + 错误摘要可见；其**下游未执行节点**呈中止灰态。回放态（`FlowCanvasMode.Playback`）生效。

**Files:**
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowGraphModel.kt`（`FlowNode` + `errorSummary: String? = null`，状态新增/复用 `Canceled` 表示中止灰态）
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowNodeCard.kt`（failed 红框 + 摘要行；canceled/aborted 灰态）
- Modify `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/OrchestrationSection.kt`（`RuntimeFlowCanvas` 把 orchestration item 的 failed/错误信息 + 下游中止映射进 `FlowNode`）
- Test: `FlowNodeCard` 逻辑/`OrchestrationSectionTest`（回放映射）

- [x] **Step 1：写失败测试**：failed 节点边框色 = failed 色（非默认 border）；错误摘要文本可见；failed 之后的下游节点状态映射为中止灰态；运行态裁剪（D9）回归仍成立（无「+」、无模板按钮、启动禁用）。
- [x] **Step 2：跑红** → FAIL。
- [x] **Step 3：实现**：`FlowNodeCard` border 改为 `when(status){ Failed -> palette.failed; selected -> selectedBorder; else -> nodeBorder }`，failed 时多一行 `errorSummary`；`RuntimeFlowCanvas` 计算“首个 failed 之后同序列未完成节点”→ 中止灰态。
- [x] **Step 4：跑绿** → PASS。
- [x] **Step 5：Commit** `feat(p8): 编排回放失败态与下游中止展示`

---

### R7：对话式配置编辑 — Task 4b / D17（**上一轮完全没做**）

**现状（已核实）：** `WorkUnitManageTool` 无 `read_config`/`update_config`；别名字典无对应词；桌面无草稿冲突提示条。
**目标：** 主 Agent 能在对话中读取/整体覆盖某 WorkUnit 的画布草稿（增删改节点），复用 `workunit/config/update` 落库路径 + `BabiqFlowStructure` 校验；运行中容器拒绝改配置（冻结）；桌面在本地有未保存草稿时提示冲突。**严格按 `plan.md` Task 4b 的 Step 列表实现**，本节只列硬验收。

**Files:** 见 §2「后端 Task 4b」+ 桌面 `ChatController.kt`/`OrchestrationSection.kt`。
**Test:** `WorkUnitManageToolTest`、`WorkUnitServiceTest`、`CapabilityAliasDictionaryTest`、`SystemPromptSecurityRuleTest`（回归）、`ChatControllerTest`、`OrchestrationSectionTest`。

- [x] **Step 1：写失败测试（后端）**：
  - `read_config` 返回当前草稿 nodes + structure + 校验状态（含空任务节点清单）；容器不存在/类型不符返回**模型可读错误**（不抛栈）。
  - `update_config` 全量覆盖：非法结构（组内嵌组/孤儿节点/重复引用）返回模型可读错误且**不落库**；合法提交经 `updateConfiguration` 生成快照 + structure 并 emit `WorkUnitItem`。
  - **运行中容器拒绝 `update_config`**（冻结语义）；`update_config` 不改变 goal / 启动状态（与 `start`/`append_goal` 职责互斥）。
  - 中文别名：`tool_search`「给编排加一个节点」「把 X 节点任务改成 Y」「删掉 X 节点」能召回 `work_unit_manage`。
- [x] **Step 2：跑红** `cd backend; .\mvnw.cmd "-Dtest=WorkUnitManageToolTest,WorkUnitServiceTest,CapabilityAliasDictionaryTest" test` → FAIL。
- [x] **Step 3：实现（后端）**：`WorkUnitManageTool` 加 `read_config`/`update_config` 两个 action（action 描述写明“增删改节点请先 `read_config` 再整体 `update_config`”）；`WorkUnitService`/`DefaultWorkUnitService` 暴露/复用 `updateConfiguration` + 校验；`ReActStrategy` 编排段补同样引导（改后跑 `SystemPromptSecurityRuleTest` 回归）；`CapabilityAliasDictionary` 补别名。
- [x] **Step 4：跑绿（后端）** 同 Step 2 + `SystemPromptSecurityRuleTest` → PASS。
- [x] **Step 5：写失败测试（桌面）**：本地有未保存画布草稿时收到 Agent 触发的 `WorkUnitItem` 配置更新 → 显示「配置已被 Agent 更新」提示条 + [加载最新]/[保留草稿]；无本地草稿时画布静默刷新；选「保留草稿」后再保存 = 正常生成新快照。
- [x] **Step 6：跑红 → 实现 → 跑绿（桌面）** `cd desktop; .\gradlew.bat test --tests "*ChatControllerTest" --tests "*OrchestrationSectionTest"`。
- [x] **Step 7：Commit** `feat(p8): 工作容器支持对话式节点配置编辑`

---

### R8：补 `FlowCanvasTest.kt` 逻辑测试 — Task 7

> R1 已为相机建 `FlowCanvasCameraTest`；本任务补 `plan.md` Task 7 明确点名的 `FlowCanvasTest.kt`（节点状态→样式映射）。若 R1 已把相机换算全覆盖，可将本任务并入但**文件名必须出现** `FlowCanvasTest.kt`，以对齐 `plan.md`。

**Files:** Create `desktop/src/test/kotlin/com/wzx/babiq/desktop/flowcanvas/FlowCanvasTest.kt`

- [x] **Step 1：写失败测试**：节点状态→样式映射遵循 D14（pending 中性 / running 强调色 / completed 绿 / failed 红）；**状态徽标互斥**（running 节点不显示空徽标 ↔ `statusText(Pending)==""`，其余非空且唯一）；mode→角色色点映射（只读/写）。
- [x] **Step 2：跑红 → 实现（若映射已存在则测试应直接覆盖现有纯函数，把 `statusColor`/`statusText`/`roleColor` 提为可测）→ 跑绿。**
- [x] **Step 3：Commit** `test(p8): 补画布节点状态映射逻辑测试`

---

### R9：全量验证 + 文档据实修正

- [x] **Step 1：后端全量**
```powershell
cd backend
.\mvnw.cmd "-Dtest=BabiqFlowStructureTest,FlowOrchestrationServiceNestedTest,FlowOrchestrationServiceTest,FlowApprovalServiceTest,FlowOrchestrationToolTest,WorkUnitManageToolTest,WorkUnitServiceTest,CapabilityAliasDictionaryTest,SystemPromptSecurityRuleTest,ThreadItemJsonTest,WorkUnitHandlersTest,SchemaCommentsCoverageTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify
```
- [x] **Step 2：桌面全量**
```powershell
cd desktop
.\gradlew.bat test --tests "*FlowGraphModelTest" --tests "*FlowCanvasLayoutTest" --tests "*FlowCanvasCameraTest" --tests "*FlowCanvasTest" --tests "*FlowCanvasPortabilityTest" --tests "*FlowStructureAdapterTest" --tests "*OrchestrationSectionTest" --tests "*ChatControllerTest"
.\gradlew.bat test --rerun-tasks
```
- [x] **Step 3：据实修正文档**
  - `implementation-report.md`：删除/改写“缩放、平移仅人工视觉烟测未做”等把“未实现”说成“未验证”的表述。
  - `CLAUDE.md` / `AGENTS.md` 的 P8 检查点：从笼统“已完成代码实现与自动化验收”改为**分项**真实状态（哪些已补齐 + 哪些仍待人工烟测）。
- [x] **Step 4：Commit** `docs(p8): 据实修正画布编排完成度与检查点`

---

## 4. 完成后的硬验收（声称完成前必须满足）

1. §3 全部 R 任务对应测试**先红后绿**，且每个新功能能指出 `file:line` 实现位置。
2. `cd backend; .\mvnw.cmd clean verify` 全绿（含 IT）；`cd desktop; .\gradlew.bat test --rerun-tasks` 真执行全绿。
3. 合并 P8 既有后端/桌面套件回归不破（Task 1/2/3/4/5 现有能力、`BabiqFlowStructure`、`FlowCanvasPortabilityTest` 全过）。
4. `git log --oneline` 能看到 R1–R9 各自独立的 `feat(p8):`/`fix(p8):`/`docs(p8):` 提交。
5. **人工烟测（`plan.md` §5 1–8 项）逐项标注**：能在无头/无 Provider 环境跑的标结果，跑不了的标“未执行 + 原因”，**不得标“通过”**。

## 5. 完成报告要求（§7 of this doc）

产出补做完成报告（可写进 `implementation-report.md` 的“补做记录”小节），逐 R 列：做了什么、对应测试名、**实现代码位置 `file:line`**、commit hash；并明确列出仍需真人桌面/真实 Provider 复验的人工烟测项。**不要 `git push`、不要 tag。**
