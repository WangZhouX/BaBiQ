# P3 UI Desktop Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按已完成的 Figma P3 原型，把上下文窗口、短期压缩、长期记忆、按需能力装配和中文能力搜索落到 Compose Desktop 真实 UI。

**Architecture:** 本专项以 Figma 页面 `35:2` 的 `P3 上下文与记忆平台原型` 为视觉和交互依据，优先复用现有 `AppState`、`ChatController`、JSON-RPC 协议模型和后端 P3 接口。桌面端只做用户可见表达和状态刷新，不改变 AgentLoop、ContextWindowRuntime、LongTermMemoryPipeline 或 CapabilityExposurePlanner 的核心语义。

**Tech Stack:** Kotlin, Compose Desktop, Material3, kotlinx.serialization, Ktor WebSocket JSON-RPC client, BaBiQ P3 `context/*` / `memory/*` / `capability/*` 协议, Figma MCP.

---

## 1. 范围边界

### 1.1 必做

- 按 Figma 新增的 `P3 01` 到 `P3 11` Frame，把 P3 状态做成桌面端可见 UI。
- 输入栏上下文 chip 支持点击展开 P3 状态弹层，展示上下文窗口、短期摘要、长期记忆、能力装配。
- 运行详情面板增强：上下文快照、记忆引用、能力搜索记录更清晰，不再只是一张压缩文本卡片。
- 设置页增强：长期记忆和能力装配从“简单按钮堆叠”升级为 Figma 中的状态卡片、控制区和审计列表。
- 能力中心增强：区分 Local / MCP / Skill、VISIBLE / DEFERRED / DISABLED，并支持中文 query 搜索验证。
- 聊天流事件增强：自动压缩、长期记忆引用、`tool_search` 命中事件有独立卡片样式。
- 保持工具 `name` / `capability_id` ASCII，不在 UI 里暗示可以改成中文。

### 1.2 不做

- 不改后端 P3 核心策略，不重写上下文窗口、短期压缩、长期记忆或 Lucene 搜索。
- 不引入 VectorStore / 语义搜索 UI，不把未来能力显示为已完成。
- 不实现 P4 多 Agent、远程 MCP、A2A、多模态或更强 OS 沙箱。
- 不做营销落地页，不改变当前桌面应用的工作型信息密度。
- 不把完整长期记忆正文常驻注入聊天页，只展示摘要、引用和审计入口。

## 2. Figma 对照表

| Figma Frame | 目标桌面区域 | 主要代码入口 |
| --- | --- | --- |
| `P3 01 上下文状态弹层` (`97:2`) | 输入栏 chip 弹层 | `ComposerContextBar.kt`, 新增 `ContextStatusPopover.kt` |
| `P3 02 运行详情-上下文快照` (`97:86`) | 右侧运行详情 | `RuntimeDetailsPanel.kt`, 新增 `ContextSnapshotSection.kt` |
| `P3 03 运行详情-记忆引用` (`97:173`) | 右侧运行详情 | `RuntimeDetailsPanel.kt`, 新增 `MemoryReferenceSection.kt` |
| `P3 04 运行详情-能力搜索` (`97:237`) | 右侧运行详情 | `RuntimeDetailsPanel.kt`, 新增 `CapabilitySearchAuditSection.kt` |
| `P3 05 设置-记忆` (`97:307`) | 设置页 | `SettingsPanel.kt`, 新增 `MemorySettingsSection.kt` |
| `P3 06 记忆检索测试` (`97:379`) | 设置页 / 记忆测试区 | `SettingsPanel.kt`, `ChatController.kt` |
| `P3 07 能力中心-详情` (`97:436`) | 设置页能力中心 | 新增 `CapabilityCenterSection.kt` |
| `P3 08 中文能力搜索` (`97:500`) | 能力中心搜索结果 | `CapabilityCenterSection.kt` |
| `P3 09 会话-自动压缩事件` (`97:568`) | 聊天消息卡片 | `MessageBubble.kt` |
| `P3 10 会话-长期记忆引用` (`97:617`) | 聊天消息卡片 | `MessageBubble.kt` |
| `P3 11 会话-tool_search 命中` (`97:663`) | 聊天消息卡片 | `MessageBubble.kt` |

## 3. 预计文件结构

### 3.1 主要修改

- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`
  - 保留现有 chip 文案计算函数，增加点击展开状态弹层。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`
  - 从单张 `DetailCard` 拆出上下文、记忆、能力审计分区。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsPanel.kt`
  - 保留现有设置行为，把长期记忆和能力装配 UI 拆成更可维护的 section。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/MessageBubble.kt`
  - 为 P3 事件 item 增加独立可读样式。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
  - 如果需要，补充纯 UI 状态，例如当前设置页 tab、能力搜索 query、记忆检索 query。
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
  - 如果当前 UI 缺少刷新入口，补充 `searchMemory`、刷新能力状态、刷新记忆审计等协调方法。

### 3.2 建议新增

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ContextStatusPopover.kt`
  - 输入栏上下文状态弹层，消费现有 `ContextWindowUiState`、`MemoryUiState`、`CapabilityUiState`。
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/ContextSnapshotSection.kt`
  - 运行详情上下文快照分区。
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/MemoryReferenceSection.kt`
  - 运行详情长期记忆引用分区。
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/CapabilitySearchAuditSection.kt`
  - 运行详情能力搜索审计分区；如果后端当前详情没有搜索事件字段，则先显示 capability 状态摘要，不伪造数据。
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/MemorySettingsSection.kt`
  - 长期记忆设置和审计 UI。
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/CapabilityCenterSection.kt`
  - 能力中心和中文搜索矩阵 UI。
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/common/MetricStrip.kt`
  - 复用 Figma 中“输入 / 输出 / 总计 / 工具”等紧凑指标条样式。

### 3.3 测试文件

- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBarTest.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/ContextStatusPopoverTest.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/runtime/ContextSnapshotSectionTest.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/settings/MemorySettingsSectionTest.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/settings/CapabilityCenterSectionTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

## 4. Implementation Tasks

### Task 1: 冻结 Figma 到 Desktop 的实现基线

**Files:**
- Read: `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md`
- Read: Figma file `frTp55zgrKf4NAWxn6LdI7`, nodes `97:2`, `97:86`, `97:307`, `97:436`, `97:500`
- Modify: `docs/superpowers/plans/p3-ui-desktop-implementation/codex-handoff.md`

- [ ] **Step 1: 使用 Figma MCP 重新确认关键 Frame**

Run:

```text
get_metadata(fileKey=frTp55zgrKf4NAWxn6LdI7, nodeId=35:2)
get_screenshot(fileKey=frTp55zgrKf4NAWxn6LdI7, nodeId=97:2, maxDimension=1400)
get_screenshot(fileKey=frTp55zgrKf4NAWxn6LdI7, nodeId=97:307, maxDimension=1400)
get_screenshot(fileKey=frTp55zgrKf4NAWxn6LdI7, nodeId=97:436, maxDimension=1400)
```

Expected: 所有 P3 Frame 仍存在，截图没有明显文字溢出或控件重叠。

- [ ] **Step 2: 建立执行 handoff**

Create `docs/superpowers/plans/p3-ui-desktop-implementation/codex-handoff.md`，记录：

- Figma 节点确认结果。
- 本专项只改 desktop UI，不改后端核心逻辑。
- 实现时使用的关键截图 URL。

- [ ] **Step 3: Commit**

```powershell
git add docs/superpowers/plans/p3-ui-desktop-implementation/codex-handoff.md
git commit -m "docs(p3-ui): 记录桌面端实现基线"
```

### Task 2: 输入栏 P3 状态弹层

**Files:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ContextStatusPopover.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/ContextStatusPopoverTest.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBarTest.kt`

- [ ] **Step 1: 写失败测试**

新增纯函数测试，先不要写 Composable 实现：

```kotlin
@Test
fun `上下文状态弹层把 P3 三类状态转成可读条目`() {
    val entries = buildContextStatusPopoverEntries(
        context = ContextWindowUiState(status = ContextStatusResult(
            threadId = "thr_1",
            modelContextWindow = 1_000_000,
            autoCompactThreshold = 750_000,
            lastSnapshotId = "ctxsnap_1",
            lastEstimatedTokens = 120_000,
            usageRatio = 0.12,
            activeSummaryId = "ctxsum_1",
            compactionCount = 2,
        )),
        memory = MemoryUiState(status = MemoryStatusResult(
            enabled = true,
            generateEnabled = true,
            readEnabled = true,
            retrievalEnabled = true,
            rootDir = "E:\\BaBiQ\\.babiq\\memories",
            phase2Generation = 3,
        )),
        capability = CapabilityUiState(status = CapabilityStatusResult(
            totalCount = 12,
            visibleCount = 6,
            deferredCount = 5,
            disabledCount = 1,
        )),
    )

    assertTrue(entries.any { it.title == "上下文窗口" && "12%" in it.detail })
    assertTrue(entries.any { it.title == "短期压缩" && "2 次" in it.detail })
    assertTrue(entries.any { it.title == "长期记忆" && "G3" in it.detail })
    assertTrue(entries.any { it.title == "能力装配" && "按需 5" in it.detail })
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*ContextStatusPopoverTest"
```

Expected: `buildContextStatusPopoverEntries` 未定义。

- [ ] **Step 3: 实现弹层条目模型和 Composable**

在 `ContextStatusPopover.kt` 中新增：

- `data class ContextStatusPopoverEntry`
- `buildContextStatusPopoverEntries(...)`
- `ContextStatusPopover(...)`

设计约束：

- 使用紧凑列表，不使用大面积营销卡片。
- 弹层最多展示 5 到 6 行核心状态。
- 长文本使用换行或截断，不撑破输入栏宽度。
- 只消费已有 state，不自己发网络请求。

- [ ] **Step 4: 把 Composer chip 接上弹层**

在 `ComposerContextBar.kt` 中：

- 上下文、长期记忆、能力 chip 任一点击后打开同一个 P3 状态弹层。
- 保持权限 chip 的下拉菜单行为不变。
- ProviderSelector 仍在最后，避免模型切换位置跳动。

- [ ] **Step 5: 运行测试**

```powershell
cd desktop
.\gradlew.bat test --tests "*ComposerContextBarTest" --tests "*ContextStatusPopoverTest"
```

Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt `
        desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ContextStatusPopover.kt `
        desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBarTest.kt `
        desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/chat/ContextStatusPopoverTest.kt
git commit -m "feat(desktop): 增加 P3 上下文状态弹层"
```

### Task 3: 运行详情 P3 审计分区

**Files:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/ContextSnapshotSection.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/MemoryReferenceSection.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/CapabilitySearchAuditSection.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/runtime/ContextSnapshotSectionTest.kt`

- [ ] **Step 1: 写上下文快照展示测试**

```kotlin
@Test
fun `上下文快照分区按 included 和 excluded 分组`() {
    val snapshot = ContextSnapshotInfo(
        snapshotId = "ctxsnap_1",
        threadId = "thr_1",
        turnId = "turn_1",
        phase = "pre_model_call",
        modelContextWindow = 1_000_000,
        autoCompactThreshold = 750_000,
        estimatedTokens = 120_000,
        includedItemCount = 2,
        excludedItemCount = 1,
        usageRatio = 0.12,
        createdAt = "2026-05-28T10:00:00",
        items = listOf(
            ContextSnapshotItemInfo("item_1", "current_turn", "P0", true, "CURRENT_TURN", 100),
            ContextSnapshotItemInfo("mem_1", "long_term_memory", "P4", true, "MEMORY_REFERENCE", 50),
            ContextSnapshotItemInfo("item_old", "history", "P8", false, "REPLACED_BY_SUMMARY", 500),
        ),
    )

    val model = buildContextSnapshotSectionModel(snapshot)

    assertEquals(2, model.included.size)
    assertEquals(1, model.excluded.size)
    assertEquals("12%", model.usageLabel)
}
```

- [ ] **Step 2: 运行测试确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*ContextSnapshotSectionTest"
```

- [ ] **Step 3: 实现 ContextSnapshotSection**

要求：

- 顶部展示 `snapshotId`、window ordinal、token 使用率。
- included / excluded 分区分开显示。
- `REPLACED_BY_SUMMARY`、`TRIMMED_BY_BUDGET` 等 reason 要中文化展示。
- 不展示完整 JSON，避免右侧面板被撑开。

- [ ] **Step 4: 增加 MemoryReferenceSection**

如果当前 `RunTurnDetailResult` 还没有长期记忆引用列表：

- 先在运行详情中展示 `state.memoryState.status` 和 `state.memoryState.artifacts` 的摘要。
- 不伪造某一轮引用数据。
- 在 handoff 中记录“需要后端详情字段时另开协议补充”。

- [ ] **Step 5: 增加 CapabilitySearchAuditSection**

如果当前运行详情没有 search event 明细：

- 先展示 capability status、visible/deferred/disabled 统计。
- 如果 `capability.searchResults` 存在，则展示最近一次手动搜索结果。

- [ ] **Step 6: 接入 RuntimeDetailsPanel**

把原先 `DetailCard("上下文窗口", ...)` 替换为新的分区组件。

- [ ] **Step 7: 运行测试**

```powershell
cd desktop
.\gradlew.bat test --tests "*ContextSnapshotSectionTest" --tests "*ChatControllerTest"
```

- [ ] **Step 8: Commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime `
        desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/runtime
git commit -m "feat(desktop): 增强运行详情 P3 审计视图"
```

### Task 4: 设置页长期记忆与能力中心重构

**Files:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsPanel.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/MemorySettingsSection.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/CapabilityCenterSection.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/settings/MemorySettingsSectionTest.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/settings/CapabilityCenterSectionTest.kt`

- [ ] **Step 1: 写长期记忆 section 模型测试**

```kotlin
@Test
fun `长期记忆设置分区展示流水线状态`() {
    val model = buildMemorySettingsSectionModel(
        status = MemoryStatusResult(
            enabled = true,
            generateEnabled = true,
            readEnabled = true,
            retrievalEnabled = true,
            rootDir = "E:\\BaBiQ\\.babiq\\memories",
            pendingJobs = 1,
            runningJobs = 0,
            cleanCandidateCount = 5,
            phase2Generation = 4,
        ),
        jobs = emptyList(),
        artifacts = emptyList(),
    )

    assertEquals("已启用", model.enabledLabel)
    assertTrue(model.pipelineLabel.contains("待执行 1"))
    assertTrue(model.pipelineLabel.contains("CLEAN 5"))
}
```

- [ ] **Step 2: 写能力中心中文搜索测试**

```kotlin
@Test
fun `能力中心按类型和暴露模式分组`() {
    val groups = groupCapabilitiesForCenter(
        listOf(
            CapabilityInfo("local.read_file", "LOCAL_TOOL", "local", "read_file", "读取文件", "Read", "VISIBLE", true),
            CapabilityInfo("mcp.fs.read_text_file", "MCP_TOOL", "mcp.fs", "read_text_file", "读取文本", "Read text", "DEFERRED", true),
            CapabilityInfo("skill.plan", "SKILL", "skill", "plan", "计划", "Plan", "DISABLED", false),
        ),
    )

    assertEquals(1, groups.local.size)
    assertEquals(1, groups.mcp.size)
    assertEquals(1, groups.skills.size)
}
```

- [ ] **Step 3: 运行测试确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*MemorySettingsSectionTest" --tests "*CapabilityCenterSectionTest"
```

- [ ] **Step 4: 拆出 MemorySettingsSection**

UI 要求：

- 顶部状态卡：总开关、后台生成、上下文注入、检索增强。
- 流水线指标：pending/running/CLEAN/generation。
- 最近任务和产物列表保持紧凑。
- 手动归并按钮继续走 `memory/consolidate`。

- [ ] **Step 5: 拆出 CapabilityCenterSection**

UI 要求：

- 显示 total/enabled/visible/deferred/disabled。
- 支持中文 query 搜索能力。
- 结果行展示 displayName、ASCII capabilityId、type、exposureMode。
- 可切换 VISIBLE / DEFERRED / DISABLED，继续走 `capability/settings/set`。

- [ ] **Step 6: 接回 SettingsPanel**

`SettingsPanel.kt` 只保留页面布局和回调透传，具体 section 放到独立文件。

- [ ] **Step 7: 运行测试**

```powershell
cd desktop
.\gradlew.bat test --tests "*MemorySettingsSectionTest" --tests "*CapabilityCenterSectionTest" --tests "*ChatControllerTest"
```

- [ ] **Step 8: Commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings `
        desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/settings
git commit -m "feat(desktop): 重构 P3 记忆设置和能力中心"
```

### Task 5: 记忆检索测试与能力搜索体验

**Files:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/MemorySettingsSection.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/CapabilityCenterSection.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

- [ ] **Step 1: 写 Controller 测试**

新增 FakeGateway 用例：

- `searchMemory("上下文窗口")` 会调用 `memory/search`。
- 成功后把 references 放入 `memoryState.searchResults` 或等价 UI 状态。
- 失败后设置 `memoryState.error`，不影响聊天输入草稿。

- [ ] **Step 2: 运行测试确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*ChatControllerTest"
```

- [ ] **Step 3: 补 UI 状态**

在 `UiModels.kt` 中为 `MemoryUiState` 增加：

- `searchQuery`
- `searchResults`
- `searchTokenEstimate`

字段注释必须说明这些只是设置页测试结果，不代表下一轮一定注入。

- [ ] **Step 4: 实现 ChatController.searchMemory**

调用现有 gateway `searchMemory(query, currentThreadId)`。

边界：

- 空 query 直接返回，不打后端。
- 运行中允许搜索，因为它只读，不改变当前 turn。
- 错误只落到 memory state，不弹全局发送失败。

- [ ] **Step 5: 设置页接入记忆检索测试**

按 Figma `P3 06 记忆检索测试`：

- 输入框：中文 query。
- 按钮：搜索记忆。
- 结果：引用文本、confidence、tokenEstimate。

- [ ] **Step 6: 中文能力搜索矩阵**

在能力中心增加示例 query chips：

- `读取文件`
- `运行命令`
- `列出目录`
- `搜索关键字`
- `打补丁`

点击 chip 后填入 query 并执行 `capability/search`。

- [ ] **Step 7: 运行测试**

```powershell
cd desktop
.\gradlew.bat test --tests "*ChatControllerTest" --tests "*CapabilityCenterSectionTest"
```

- [ ] **Step 8: Commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/state `
        desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings `
        desktop/src/test/kotlin/com/wzx/babiq/desktop/state `
        desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/settings
git commit -m "feat(desktop): 增加记忆检索和中文能力搜索体验"
```

### Task 6: 聊天流 P3 事件卡片

**Files:**
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/MessageBubble.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/MessageBubbleTest.kt`
- Test: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatReducerTest.kt`

- [ ] **Step 1: 核对现有 ThreadItem 类型**

Read:

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ThreadHistoryModels.kt`
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt`
- `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ThreadItemJsonTest.kt`

Expected: 已存在 `contextCompaction` item；如果长期记忆引用或 tool_search 事件没有独立 item，UI 不伪造协议事件。

- [ ] **Step 2: 写 MessageBubble 格式化测试**

覆盖：

- 自动压缩事件：显示压缩状态、summary id、替换历史数量。
- 长期记忆引用：如果当前协议只有 assistant 文本引用，则不单独显示为事件卡。
- tool_search：如果当前协议只有工具调用记录，则在工具消息里显示命中工具数量。

- [ ] **Step 3: 运行测试确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*MessageBubbleTest" --tests "*ChatReducerTest"
```

- [ ] **Step 4: 实现 P3 事件卡样式**

要求：

- 使用紧凑浅色卡片。
- 不嵌套卡片。
- 不展示大段 JSON。
- 对失败/跳过/成功使用不同色调。

- [ ] **Step 5: 运行测试**

```powershell
cd desktop
.\gradlew.bat test --tests "*MessageBubbleTest" --tests "*ChatReducerTest"
```

- [ ] **Step 6: Commit**

```powershell
git add desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/MessageBubble.kt `
        desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatReducer.kt `
        desktop/src/test/kotlin/com/wzx/babiq/desktop/ui/MessageBubbleTest.kt `
        desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatReducerTest.kt
git commit -m "feat(desktop): 优化 P3 聊天流事件卡片"
```

### Task 7: 视觉验收与文档同步

**Files:**
- Modify: `docs/superpowers/plans/p3-ui-desktop-implementation/codex-handoff.md`
- Modify: `docs/superpowers/plans/p3-master.md`
- Modify: `docs/superpowers/plans/p3-task-index.md`

- [ ] **Step 1: 跑桌面端专项测试**

```powershell
cd desktop
.\gradlew.bat test --tests "*ComposerContextBarTest" `
                   --tests "*ContextStatusPopoverTest" `
                   --tests "*ContextSnapshotSectionTest" `
                   --tests "*MemorySettingsSectionTest" `
                   --tests "*CapabilityCenterSectionTest" `
                   --tests "*MessageBubbleTest" `
                   --tests "*ChatControllerTest"
```

Expected: BUILD SUCCESS。

- [ ] **Step 2: 跑桌面端全量测试**

```powershell
cd desktop
.\gradlew.bat test
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: 启动桌面端烟测**

```powershell
cd desktop
.\gradlew.bat run --no-daemon
```

人工验收：

- 输入栏 chip 不换行挤压发送按钮。
- P3 状态弹层可打开和关闭。
- 设置页记忆和能力中心可滚动，按钮不重叠。
- 运行详情在 1365x768 视口下无明显溢出。
- 中文 query 能触发能力搜索，并展示 ASCII capability id。

- [ ] **Step 4: 更新 handoff**

记录：

- 已实现文件列表。
- 测试命令真实输出摘要。
- 桌面端烟测结论。
- 仍未实现的后续协议增强，例如运行详情按 turn 展示完整 memory reference / capability search event。

- [ ] **Step 5: 更新 P3 状态文档**

- `p3-master.md`：把 `P3-UI-Desktop` 状态改为 `已完成`。
- `p3-task-index.md`：同步状态和验收摘要。

- [ ] **Step 6: 最终验证**

```powershell
cd desktop
.\gradlew.bat test

cd ..
git diff --check
```

- [ ] **Step 7: Commit**

```powershell
git add desktop docs/superpowers/plans/p3-ui-desktop-implementation docs/superpowers/plans/p3-master.md docs/superpowers/plans/p3-task-index.md
git commit -m "docs(p3-ui): 同步桌面端 UI 实现状态"
```

## 5. 验收标准

- 输入栏 P3 状态 chip 和弹层真实消费 `ContextWindowUiState`、`MemoryUiState`、`CapabilityUiState`。
- 设置页长期记忆和能力中心不再只是按钮堆叠，能清晰展示状态、开关、最近任务、最近产物、中文搜索结果。
- 运行详情能清楚展示上下文快照 included / excluded，以及当前可用的记忆和能力审计摘要。
- 聊天流中的 P3 事件比普通 assistant 文本更容易识别，但不打断主对话。
- 不改变后端核心语义，不伪造尚未存在的协议事件。
- `desktop` 专项测试通过。
- `desktop` 全量测试通过。
- 桌面端真实启动后，1365x768 和 1920x1080 下无明显文字溢出、按钮重叠或输入栏跳动。

## 6. 执行说明

- 可以直接根据 Figma 原型开发：实现时用 Figma MCP 重新取关键节点截图，按本计划的 Frame 对照表逐个落地。
- Figma 是视觉和交互真相源，后端 SQLite / JSON-RPC 是数据真相源；当二者冲突时，不能让 UI 展示后端不存在的数据。
- 如果实施时发现后端缺少必要字段，应暂停该小块，写清楚缺口并另开协议补充任务，不要在桌面端硬编码假数据。
