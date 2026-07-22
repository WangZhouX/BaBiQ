# 业务桌面左右壳层纠偏 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复助手收起态占用整条底部、业务导航被错误迁移到顶部和应用内容区重复品牌三个问题，形成“顶部设置工具栏 + 左侧业务导航 + 中间业务区 + 右侧助手”的稳定左右布局。

**Architecture:** Windows 原生标题栏继续承担唯一品牌展示和系统窗口控制；Compose 顶部栏只提供设置入口。Shell 主体先固定 210dp 左导航，再把剩余 `dockWidth` 交给纯 `BusinessDesktopLayoutPolicy` 计算业务区和 124dp 收起控制列或 8dp 分隔条加可调宽助手，所有区域都用相邻 `Row` 渲染，不使用覆盖层和整行底部安全区。

**Tech Stack:** Kotlin/JVM 21、Compose Desktop、Material 3、Compose UI Test、Gradle。

---

## Chunk 1: 顶部设置栏与左侧业务导航

### Task 1: 把顶部一级导航收口为设置工具栏

**Files:**
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessTopNavigationTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessTopNavigation.kt`

- [ ] **Step 1: 先重写失败测试**

  将顶部组件测试改为断言：根节点高度 52dp；只存在 `navigation-settings`；不存在品牌、Logo、工作台、资料录入和运行记录节点；点击设置只回传 `BusinessDesktopDestination.SETTINGS`；设置页时保持选中语义。

- [ ] **Step 2: 运行测试并确认 RED**

  Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessTopNavigationTest" --rerun-tasks --no-daemon --max-workers=1`

  Expected: FAIL，旧组件仍存在 Logo/产品名和三个业务入口，且高度为 64dp。

- [ ] **Step 3: 最小修改顶部组件**

  保留现有公开函数和 `onComposed` 回调以避免破坏安装包烟测信号；删除 `BrandBlock`、`primaryNavigationItems` 和三个顶部业务按钮。顶部 `Surface` 改为 52dp，只在右侧渲染设置按钮，并保留稳定 test tag、Tab role 和选中状态。

- [ ] **Step 4: 重跑聚焦测试确认 GREEN**

  Expected: `BusinessTopNavigationTest` 全部通过。

- [ ] **Step 5: 中文提交**

  Commit: `fix(桌面): 顶部栏只保留设置入口`

### Task 2: 恢复左侧业务导航但排除设置

**Files:**
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebar.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebarTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopDestination.kt`

- [ ] **Step 1: 写左侧导航失败测试**

  覆盖工作台、资料录入、运行记录三个节点；设置节点不存在；选中语义正确；三次点击分别回传 canonical destination；宽度受父 Shell 固定为 210dp。

- [ ] **Step 2: 运行测试确认 RED**

  Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessSidebarTest" --rerun-tasks --no-daemon --max-workers=1`

  Expected: FAIL，因为当前分支已删除 `BusinessSidebar`。

- [ ] **Step 3: 恢复并收紧实现**

  从删除前的现有实现恢复左侧导航样式；在 `BusinessDesktopDestination.kt` 增加只包含 `WORKBENCH`、`DATA_ENTRY`、`RUN_HISTORY` 的 `businessSidebarDestinations`。不要恢复旧 `AGENT` 目的地、compact tab 或设置入口。

- [ ] **Step 4: 重跑测试确认 GREEN**

- [ ] **Step 5: 中文提交**

  Commit: `feat(桌面): 恢复左侧业务导航`

---

## Chunk 2: 收起控制列与宽度守恒

### Task 3: 让纯布局策略计算 210dp 导航和 124dp 收起控制列

**Files:**
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicyTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicy.kt`

- [ ] **Step 1: 先修改策略测试**

  新契约：`navigationWidth=210dp`、`collapsedAssistantWidth=124dp`；策略输入始终是扣除导航后的 `dockWidth`。收起时 `businessWidth + assistantWidth == dockWidth` 且 divider 为 0；展开时 `businessWidth + dividerWidth + assistantWidth == dockWidth`；1007dp dock 拒绝、1008dp dock 允许；非法和极端输入继续安全归一化。

- [ ] **Step 2: 运行测试确认 RED**

  Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopLayoutPolicyTest" --rerun-tasks --no-daemon --max-workers=1`

  Expected: FAIL，旧收起结果仍为业务区全宽、助手宽度 0。

- [ ] **Step 3: 最小实现新策略**

  在策略对象增加两个尺寸常量；`collapsedLayout` 在宽度足够时把 124dp 分配给助手控制列，其余分配给业务区。小于 124dp 的非法测试输入把控制列钳制到可用宽度，保证所有输出非负且严格守恒。展开阈值继续是 1008dp，但只接收 Shell 扣除导航后的 `dockWidth`。

- [ ] **Step 4: 重跑策略测试确认 GREEN**

- [ ] **Step 5: 中文提交**

  Commit: `refactor(桌面): 定义左右壳层宽度守恒`

### Task 4: 重组 Shell 并删除整行底部安全区

**Files:**
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`

- [ ] **Step 1: 先把 Shell 测试改成新结构**

  覆盖：顶部设置栏存在；左侧导航宽 210dp；1200dp 窗口收起时业务宽 866dp、右控制列宽 124dp；不存在旧 `MASCOT_SAFE_AREA`；吉祥物位于控制列右下并与保存/提交按钮不相交；1217dp 整体宽度拒绝展开、1218dp 允许且助手按下限钳制为 360dp；提示位于控制列内且在吉祥物上方；1400dp 窗口使用默认 460dp 助手时，全 Row 宽度满足 `210 + business + 8 + assistant == content`；设置从顶部进入、资料录入从左侧返回；再次收起恢复 124dp 控制列。

  必须先搜索 `BusinessDesktopShellTest` 中所有 `agentPanelExpanded = true` 或会点击展开吉祥物的硬编码宽度，并逐项调整：

  - `1217/1218dp` 只用于拒绝/允许阈值和 360dp 动态下限，不用于默认宽度或拖拽。
  - `expanded dock fills...` 从 1200dp 改为至少 1318dp；计划统一使用 1400dp，继续断言默认助手 460dp。
  - `real drag...` 从 1200dp 改为至少 1374dp；计划统一使用 1400dp，继续断言 500dp、516dp。
  - `one pointer gesture...` 从 1200dp 改为 1400dp，继续验证 460→500dp 与外部 520→480dp。
  - 其余展开态/附件态/分隔条命中测试统一使用 1400dp；收起态底部遮挡回归仍保留 1200dp，以证明窄于展开阈值时左右收起布局也正确。

- [ ] **Step 2: 运行 Shell 测试确认 RED**

  Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopShellTest" --rerun-tasks --no-daemon --max-workers=1`

  Expected: FAIL，旧 Shell 没有左导航且仍创建 124dp 高的整行底部区域。

- [ ] **Step 3: 最小重组生产布局**

  Shell 外层保持 `Column(topBar, body)`；body 改为 `Row(sidebar, dockArea)`。sidebar 固定 210dp；dockArea 使用 `BoxWithConstraints(weight(1f))`，把其 `maxWidth` 直接交给策略。收起和展开都用同一个水平 `Row`：业务区在左，右侧是 124dp 控制列或 8dp 分隔条加助手面板。

  删除 `CollapsedBusinessRegion` 和 `MASCOT_SAFE_AREA`。新增稳定 `COLLAPSED_ASSISTANT_CONTROL` tag；控制列用 `Box(fillMaxHeight)`，吉祥物 `Alignment.BottomCenter`，不足宽提示在同一列中 `Alignment.BottomCenter` 并通过 bottom padding 放在吉祥物上方，二者 bounds 不相交。

- [ ] **Step 4: 重跑 Shell、顶部栏、侧栏和策略测试确认 GREEN**

  Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopShellTest" --tests "*BusinessTopNavigationTest" --tests "*BusinessSidebarTest" --tests "*BusinessDesktopLayoutPolicyTest" --rerun-tasks --no-daemon --max-workers=1`

- [ ] **Step 5: 中文提交**

  Commit: `fix(桌面): 修正吉祥物与左右分栏布局`

---

## Chunk 3: 品牌审计、回归和真实窗口

### Task 5: 更新品牌与安装包组成契约

**Files:**
- Modify if required: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/brand/BusinessVisibleCopyAuditTest.kt`
- Modify if required: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeWindowCompositionTest.kt`
- Modify if required: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeWindowComposition.kt`

- [ ] **Step 1: 添加/更新失败契约**

  断言应用内容区的 Shell/顶部组件源码不再包含第二套 `BusinessBrandResources.logoImageBitmap()` 或可见产品名；原生 `BusinessDesktopWindowSpec.title`、窗口图标和 JPackage 品牌保持不变。安装包组成信号仍必须等待顶部设置栏和 Shell 完成 composition。

- [ ] **Step 2: 运行契约测试确认 RED 或确认现有契约已覆盖**

  若现有测试立即通过，记录其已覆盖的行为，不为制造 RED 而重复测试；只对缺失的品牌唯一性契约执行 TDD。

- [ ] **Step 3: 做最小必要调整并重跑聚焦测试**

- [ ] **Step 4: 中文提交**

  Commit: `test(桌面): 加固左右壳层品牌契约`

### Task 6: 全量验证和真实窗口验收

**Files:**
- Create: `docs/superpowers/plans/2026-07-22-business-desktop-left-right-shell-correction-qa.md`

- [ ] **Step 1: 运行业务桌面全量测试**

  Run: `cd business-desktop; .\gradlew.bat test -x :app:packageBusinessBackendJar --rerun-tasks --no-daemon --max-workers=1 "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process"`

  Expected: `BUILD SUCCESSFUL`，XML 中 failures/errors 为 0。

- [ ] **Step 2: 独立启动后端和前端做真实 UI 验收**

  使用隔离临时 home，先运行 `:app:runBusinessBackendDevelopment`，再单独运行 `:app:runBusinessFrontendDevelopment`。检查最大化窗口中：原生标题栏是唯一品牌；应用顶部只显示设置；左侧显示三个业务入口；右下吉祥物不产生底部横条、不覆盖保存/提交；点击后助手从右侧展开且左导航保持；拖动宽度有效；再次点击恢复窄控制列。

- [ ] **Step 3: 记录证据并清理精确隔离进程**

  文档记录窗口尺寸、关键 bounds、测试总数和异常；只停止本轮隔离目录对应的进程，不清理用户数据或其他工作树临时目录。

- [ ] **Step 4: 最终静态检查和中文提交**

  Run: `git diff --check <本任务起始提交>..HEAD`

  Commit: `docs(桌面): 记录左右壳层纠偏验收`
