# 业务桌面零占位助手与左下角设置 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收起小律助手时不再占用整条右侧宽度，删除 Compose 顶部工具栏，并将设置入口固定到左侧导航底部。

**Architecture:** `BusinessDesktopLayoutPolicy` 只负责展开停靠宽度，收起态返回零宽助手；`BusinessDesktopShell` 保持单一业务内容组合，在收起态以局部浮动层放置吉祥物；`BusinessSidebar` 同时承载顶部业务入口与底部设置入口。原生 Windows 标题栏继续是唯一顶部品牌区域。

**Tech Stack:** Kotlin 2.3、Compose Multiplatform Desktop、Material 3、Compose UI Test、JUnit 4、Gradle 9.3、Java 21

---

## 文件职责

- `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicy.kt`：业务区、分隔条和展开助手宽度的纯计算。
- `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicyTest.kt`：宽度守恒、展开阈值和极端输入契约。
- `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebar.kt`：左侧顶部业务导航与底部设置入口。
- `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopDestination.kt`：目的地及侧栏业务入口集合。
- `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebarTest.kt`：侧栏位置、唯一标签、点击与选中语义。
- `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`：全高左右壳层、业务内容、展开助手和收起吉祥物浮层。
- `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`：整体几何、导航、助手交互与状态保持。
- 删除 `BusinessTopNavigation.kt` 与 `BusinessTopNavigationTest.kt`：删除不再可达的 Compose 顶部工具栏。

## Chunk 1：零宽收起布局

### Task 1：布局策略不再保留右侧控制列

**Files:**
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicyTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicy.kt`

- [ ] **Step 1: 将收起布局测试改为零宽契约**

把固定 124dp 的断言改为：

```kotlin
assertEquals(availableWidth, layout.businessWidth)
assertEquals(0.dp, layout.dividerWidth)
assertEquals(0.dp, layout.assistantWidth)
assertEquals(layout.availableWidth, layout.businessWidth)
```

覆盖正常宽度、低于旧 124dp 的宽度、阈值下一像素、超大有限宽度、负数/NaN/Infinity。

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:test --tests "*BusinessDesktopLayoutPolicyTest" --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

Expected: FAIL，现有实现仍返回 `assistantWidth = 124.dp` 或占满窄窗口。

- [ ] **Step 3: 实现最小零宽收起布局**

删除 `collapsedAssistantWidth`，将 `collapsedLayout` 改为：

```kotlin
return BusinessDesktopDockLayout(
    availableWidth = availableWidth,
    businessWidth = availableWidth,
    dividerWidth = 0.dp,
    assistantWidth = 0.dp,
    canExpand = canExpand,
    assistantExpanded = false,
)
```

- [ ] **Step 4: 运行策略测试并确认 GREEN**

Run 同 Step 2。

Expected: `BusinessDesktopLayoutPolicyTest` 全部通过。

- [ ] **Step 5: 中文提交**

```powershell
git add -- business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicy.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicyTest.kt
git commit -m "fix(桌面): 收起助手不再占用业务宽度"
```

## Chunk 2：左下角设置与无顶部工具栏

### Task 2：设置入口固定到侧栏底部

**Files:**
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebarTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebar.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopDestination.kt`

- [ ] **Step 1: 写侧栏设置入口失败测试**

新增 `BusinessSidebarTags.SETTINGS` 契约，并在 210dp x 600dp 侧栏中断言：

```kotlin
rule.onNodeWithTag(BusinessSidebarTags.SETTINGS).assertExists()
assertTrue(bounds(BusinessSidebarTags.SETTINGS).bottom <= bounds(BusinessSidebarTags.ROOT).bottom)
assertTrue(bounds(BusinessSidebarTags.SETTINGS).top > bounds(BusinessSidebarTags.RUN_HISTORY).bottom)
rule.onAllNodes(hasClickAction()).assertCountEquals(4)
```

同时覆盖设置点击回调和设置选中 Tab 语义。

- [ ] **Step 2: 运行侧栏测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:test --tests "*BusinessSidebarTest" --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

Expected: FAIL，侧栏当前不存在设置入口。

- [ ] **Step 3: 实现底部设置区域**

- 在导航项之后加入 `Spacer(Modifier.weight(1f))`。
- 用与业务入口一致的可选择语义渲染设置，但使用独立标签。
- `businessSidebarDestinations` 仍只包含三个业务入口，设置单独渲染，避免破坏“顶部业务、底部全局设置”的结构。
- 更新 `BusinessDesktopDestination.kt` 注释，移除“设置始终由顶部工具栏进入”的过期约束。

- [ ] **Step 4: 运行侧栏测试并确认 GREEN**

Run 同 Step 2。

Expected: `BusinessSidebarTest` 全部通过。

- [ ] **Step 5: 中文提交**

```powershell
git add -- business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebar.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopDestination.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebarTest.kt
git commit -m "feat(桌面): 将设置固定到左侧导航底部"
```

### Task 3：Shell 删除顶部工具栏

**Files:**
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`
- Delete: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessTopNavigation.kt`
- Delete: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessTopNavigationTest.kt`

- [ ] **Step 1: 写无顶部工具栏失败测试**

在 Shell 测试中断言：

```kotlin
rule.onNodeWithTag(BusinessTopNavigationTags.ROOT).assertDoesNotExist()
assertEquals(0f, bounds(BusinessSidebarTags.ROOT).top)
assertEquals(0f, bounds(BusinessUiTags.CONTENT).top)
```

把原来的顶部设置点击测试改为点击 `BusinessSidebarTags.SETTINGS`。

- [ ] **Step 2: 运行 Shell 测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:test --tests "*BusinessDesktopShellTest" --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

Expected: FAIL，当前 Shell 仍组合 52dp 顶部工具栏。

- [ ] **Step 3: 删除顶部栏并让主体占满全高**

- 将 Shell 根节点从 `Column(topBar + body)` 改为全高 `Row(sidebar + dock)`。
- 删除 `BusinessTopNavigation` 调用和不可达组件文件。
- 保留 `onTopNavigationComposed` 参数一个兼容周期时，不再触发它；若仓库调用点只用于测试则同步删除参数和调用点。

- [ ] **Step 4: 运行 Shell、侧栏及编译测试并确认 GREEN**

Run:

```powershell
.\gradlew.bat :app:test --tests "*BusinessDesktopShellTest" --tests "*BusinessSidebarTest" --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

Expected: 所有指定测试通过且删除组件后无编译引用。

- [ ] **Step 5: 中文提交**

```powershell
git add -A -- business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell
git commit -m "refactor(桌面): 移除多余顶部设置工具栏"
```

## Chunk 3：右下角局部悬浮吉祥物

### Task 4：收起态仅绘制吉祥物自身

**Files:**
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`

- [ ] **Step 1: 写几何失败测试**

删除 124dp 控制列断言，改为：

```kotlin
rule.onNodeWithTag(BusinessUiTags.COLLAPSED_ASSISTANT_CONTROL).assertDoesNotExist()
assertApproximately(dock.width, business.width)
assertTrue(mascot.right <= business.right)
assertTrue(mascot.bottom <= business.bottom)
assertTrue(mascot.left > business.center.x)
assertTrue(mascot.top > business.center.y)
```

同时断言吉祥物自身没有 `fillMaxHeight`/整条背景语义，展开后浮动吉祥物消失并出现相邻助手面板。

- [ ] **Step 2: 运行 Shell 测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:test --tests "*BusinessDesktopShellTest" --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

Expected: FAIL，当前仍存在 `COLLAPSED_ASSISTANT_CONTROL` 整高容器。

- [ ] **Step 3: 实现局部浮动入口**

- 删除收起态 `Box(width = layout.assistantWidth, fillMaxHeight)`。
- 在停靠区最外层 `Box` 中，仅当 `!layout.assistantExpanded` 时渲染 `BusinessAssistantMascotButton(Modifier.align(Alignment.BottomEnd))`。
- 宽度不足提示以 `Modifier.align(Alignment.BottomEnd).padding(bottom = mascotHeight)` 放在吉祥物上方，不影响 Row 宽度。
- 展开分支继续渲染分隔条、助手面板和 resize handle。
- 保持 `BusinessContent` 只有一个组合调用位置。

- [ ] **Step 4: 运行 Shell 测试并确认 GREEN**

Run 同 Step 2。

Expected: Shell 全部通过，包括展开、收起、拖动、宽度提示和 Provider 编辑草稿状态保持。

- [ ] **Step 5: 中文提交**

```powershell
git add -- business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt
git commit -m "fix(桌面): 吉祥物收起态改为右下角局部悬浮"
```

## Chunk 4：验收与同步

### Task 5：回归、真实窗口与验收记录

**Files:**
- Create: `docs/superpowers/plans/2026-07-22-business-desktop-zero-reservation-assistant-qa.md`

- [ ] **Step 1: 运行聚焦测试**

```powershell
.\gradlew.bat :app:test --tests "*BusinessDesktopLayoutPolicyTest" --tests "*BusinessDesktopShellTest" --tests "*BusinessSidebarTest" --tests "*BusinessAgentAttachmentWorkflowIT" --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

Expected: 所有指定测试通过。

- [ ] **Step 2: 运行桌面全量测试**

```powershell
.\gradlew.bat test --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

Expected: 全量通过；若仅出现已记录的 `BusinessDesktopFrameworkIT` 快速终态竞态，必须如实记录，不得放宽断言。

- [ ] **Step 3: 真实窗口验证**

分别启动开发后端和前端，最大化窗口后检查：

- 原生标题栏下方没有额外 Compose 顶栏。
- 左侧底部显示设置。
- 收起助手时业务区延伸到窗口右边缘，没有灰色占位条。
- 吉祥物只有自身范围可见、可点击。
- 展开助手后相邻分栏，拖动和收起有效。

- [ ] **Step 4: 写验收记录并中文提交**

```powershell
git add -- docs/superpowers/plans/2026-07-22-business-desktop-zero-reservation-assistant-qa.md
git commit -m "docs(桌面): 记录零占位助手验收"
```

- [ ] **Step 5: 最终差异检查**

```powershell
git diff --check 9d31a8c..HEAD
git status --short
git log --oneline 9d31a8c..HEAD
```

Expected: 差异无行尾错误；隔离工作区无未提交文件；提交均为本任务中文提交。
