# P3 UI Prototype Refresh Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:executing-plans` to execute this plan, and use Figma MCP tools for prototype inspection or updates. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **状态:** 待用户确认后执行。本计划只刷新 Figma 原型，不直接修改 `desktop/` 或 `backend/` 生产代码。

**Goal:** 把现有 P2 高保真交互原型升级为能表达 P3 上下文窗口、短期压缩、长期记忆、按需能力装配和 Lucene 中文能力搜索的 P3 原型。

**Architecture:** 原型继续基于当前 Figma 文件 `frTp55zgrKf4NAWxn6LdI7`，保留既有聊天、审批、Provider、权限、运行详情、搜索、技能和 MCP 基础流。新增页面以“用户可感知的上下文工程”为中心：输入栏状态 chip、上下文快照、记忆控制、能力中心和中文能力搜索演示，避免把 P4 多 Agent 或未来 VectorStore 语义搜索提前塞入 P3 原型。

**Tech Stack:** Figma Prototype, Figma MCP, Kotlin Compose Desktop UI 约束参考, BaBiQ P3 JSON-RPC 状态模型, SQLite 审计概念, Spring AI / Spring AI Alibaba 能力分层概念。

---

## 1. 范围边界

### 1.1 必做

- 将原型标题和总览从 `P2 高保真交互原型` 更新为 `P3 上下文与记忆平台原型`。
- 补齐会话输入栏中的 P3 状态 chip：上下文占用、压缩次数、长期记忆、能力装配和 Lucene 搜索。
- 新增上下文状态弹层，展示当前模型窗口、75% 自动压缩阈值、active summary、长期记忆注入和本轮可见层级。
- 升级运行详情页，增加“上下文快照 / 记忆引用 / 能力搜索 / 工具调用”分区或页签。
- 新增记忆设置页，覆盖长期记忆开关、Phase 1/Phase 2 状态、手动归并、记忆检索和 SECRET_RISK 隔离。
- 升级技能页为能力中心，清晰区分 Local tools、MCP tools、Skills、默认可见、deferred 和下一轮提升可见。
- 新增中文能力搜索演示：`读取文件`、`运行命令`、`列出目录`、`打补丁` 等 query 命中对应 ASCII capability。
- 新增短期压缩事件流和长期记忆引用流，表达“记忆是参考，不覆盖当前用户指令”。

### 1.2 不做

- 不修改工具 `name` / `capability_id` 展示规则，仍保持 ASCII 技术标识。
- 不把 VectorStore 语义搜索作为已完成能力展示；如果需要展示，只能作为“后续 P3-6 候选”。
- 不设计 P4 多 Agent、A2A、远程 MCP OAuth 或真 OS 沙箱。
- 不修改 `desktop/` 或 `backend/` 代码。
- 不把价格、成本或美元成本展示重新放回原型；本项目只展示 token、耗时和工具次数。

---

## 2. 目标页面结构

| 页面 | 目标 |
|---|---|
| `00 交互总览` | 更新为 P3 信息架构入口，新增上下文、记忆、能力中心、中文搜索演示入口 |
| `01 首页-会话历史` | 输入栏增加 P3 状态 chip，左侧项目区保持真实项目列表语义 |
| `02 会话详情-运行中` | 展示上下文占用、能力装配状态、长期记忆检索状态 |
| 新增 `P3 上下文状态弹层` | 点击上下文 chip 后展示窗口预算、层级 envelope 和压缩阈值 |
| 新增 `P3 短期压缩事件` | 展示自动压缩发生时的事件、摘要和节省 token |
| 新增 `P3 运行详情-上下文快照` | 展示 included/excluded、summary、memory、capability catalog |
| 新增 `P3 记忆设置` | 展示长期记忆 pipeline、任务状态、手动归并和记忆检索 |
| 新增 `P3 能力中心` | 展示 local/MCP/Skill 能力、可见性和 deferred 状态 |
| 新增 `P3 中文能力搜索` | 展示 Lucene/BM25 + 中文别名命中矩阵 |
| `30 插件-本地MCP` | 补充 MCP server 详情和工具 deferred 状态入口 |

---

## 3. Figma 命名规则

- 新增 frame 使用 `P3 xx 名称` 前缀，例如 `P3 01 上下文状态弹层`。
- 交互热区继续使用 `交互热区-*` 命名，方便后续 Figma MCP metadata 检索。
- 输入栏状态 chip 使用 `状态-*` 命名，例如 `状态-上下文 68%`、`状态-已压缩 2 次`。
- 原型中可以显示中文用户文案，但技术标识必须保留 ASCII，例如 `local.read_file`、`mcp.filesystem.read_text_file`。

---

## 4. 实施任务

### Task 1: 原型结构复核和页面清单

**Files:**
- Inspect: Figma file `frTp55zgrKf4NAWxn6LdI7`, node `35:2`
- Modify: `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md`

- [ ] **Step 1: 拉取当前 Figma 页面结构**

  Run: 使用 Figma MCP `get_metadata(fileKey=frTp55zgrKf4NAWxn6LdI7, nodeId=35:2)`

  Expected: 能看到现有 `00` 到 `33` 的 frame 列表。

- [ ] **Step 2: 记录当前可复用页面**

  在 `codex-handoff.md` 中列出可复用页面、需要新增页面和需要改名页面。

- [ ] **Step 3: 截图归档**

  Run: 使用 Figma MCP `get_screenshot(fileKey=frTp55zgrKf4NAWxn6LdI7, nodeId=35:2, maxDimension=1800)`

  Expected: 拿到总览截图 URL，并在 handoff 中记录截图获取时间和用途。

- [ ] **Step 4: Commit**

  ```powershell
  git add docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md
  git commit -m "docs(p3-ui): 记录原型结构复核结果"
  ```

### Task 2: 更新总览和导航信息架构

**Files:**
- Modify: Figma frames `00 交互总览`, `01 首页-会话历史`, existing sidebar components
- Modify: `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md`

- [ ] **Step 1: 更新原型标题**

  把 `00 交互总览` 标题更新为 `BaBiQ P3 上下文与记忆平台原型`。

- [ ] **Step 2: 更新总览卡片**

  总览至少包含：会话、上下文状态、记忆设置、能力中心、中文能力搜索、运行详情、权限策略、本地 MCP。

- [ ] **Step 3: 统一侧栏语义**

  侧栏建议保留：新对话、搜索、技能、 本地 MCP、自动化、项目、最近、设置。`插件` 如继续存在，应在文案中明确它当前承载本地 MCP。

- [ ] **Step 4: 截图验收**

  Run: 对更新后的总览 frame 调用 Figma MCP `get_screenshot(...)`

  Expected: 新入口在首屏可见，且没有文字重叠。

- [ ] **Step 5: Commit**

  ```powershell
  git add docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md
  git commit -m "docs(p3-ui): 同步原型总览信息架构"
  ```

### Task 3: 设计输入栏 P3 状态 chip 和上下文弹层

**Files:**
- Modify: Figma frames `01 首页-会话历史`, `02 会话详情-运行中`, `32 会话详情-完成后-工具收起`
- Create: Figma frame `P3 01 上下文状态弹层`
- Modify: `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md`

- [ ] **Step 1: 在输入栏加入状态 chip**

  必须包含：
  - `目录 BaBiQ`
  - `工作区可写`
  - `模型 deepseek-v4-pro`
  - `上下文 68%`
  - `已压缩 2 次`
  - `长期记忆 开`
  - `能力按需`

- [ ] **Step 2: 设计上下文弹层**

  弹层展示：
  - 模型窗口：`1,000,000 tokens`
  - 当前估算：`680,000 tokens`
  - 自动压缩阈值：`75%`
  - active summary：`summary_003`
  - 长期记忆：`已注入 3 条引用`
  - 本轮层级：`current_turn / recent_history / short_term_summary / long_term_memory / capability_catalog`

- [ ] **Step 3: 连接交互热区**

  点击 `上下文 68%` chip 打开上下文弹层，点击遮罩返回会话。

- [ ] **Step 4: 截图验收**

  Expected: 弹层能解释“模型这一轮看到什么”，不把完整历史或完整 Skill 正文误表达为常驻注入。

- [ ] **Step 5: Commit**

  ```powershell
  git add docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md
  git commit -m "docs(p3-ui): 设计上下文状态入口"
  ```

### Task 4: 升级运行详情为 P3 审计视图

**Files:**
- Modify: Figma frame `07 运行记录-详情`
- Create: Figma frame `P3 02 运行详情-上下文快照`
- Create: Figma frame `P3 03 运行详情-记忆引用`
- Create: Figma frame `P3 04 运行详情-能力搜索`
- Modify: `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md`

- [ ] **Step 1: 增加运行详情页签或分区**

  推荐页签：`概览`、`上下文快照`、`工具调用`、`记忆引用`、`能力搜索`。

- [ ] **Step 2: 设计上下文快照页**

  展示 included / excluded 列表、排除原因、token 估算、window ordinal 和 summary id。

- [ ] **Step 3: 设计记忆引用页**

  展示长期记忆引用 id、片段、来源 thread、置信边界和“仅供参考”标识。

- [ ] **Step 4: 设计能力搜索页**

  展示本轮候选能力、实际暴露能力、deferred 能力、`tool_search` 命中和 Lucene 分数。

- [ ] **Step 5: Commit**

  ```powershell
  git add docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md
  git commit -m "docs(p3-ui): 设计运行详情审计视图"
  ```

### Task 5: 新增记忆设置页

**Files:**
- Create: Figma frame `P3 05 设置-记忆`
- Create: Figma frame `P3 06 记忆检索测试`
- Modify: existing settings tabs
- Modify: `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md`

- [ ] **Step 1: 在设置页增加 `记忆` tab**

  与 Provider、权限审批、本地 MCP 并列。

- [ ] **Step 2: 设计长期记忆状态卡片**

  展示：
  - 长期记忆开关
  - Phase 1 最近扫描
  - Phase 2 最近归并
  - 未归并 candidate 数
  - SECRET_RISK 隔离数

- [ ] **Step 3: 设计控制按钮**

  包含 `立即扫描`、`手动归并`、`测试检索`、`打开记忆目录`。

- [ ] **Step 4: 设计记忆检索测试页**

  输入中文 query 后展示命中的 memory snippet、引用来源和注入预算。

- [ ] **Step 5: Commit**

  ```powershell
  git add docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md
  git commit -m "docs(p3-ui): 设计长期记忆设置页"
  ```

### Task 6: 升级技能页为能力中心

**Files:**
- Modify: Figma frame `28 技能-列表`
- Create: Figma frame `P3 07 能力中心-详情`
- Create: Figma frame `P3 08 中文能力搜索`
- Modify: `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md`

- [ ] **Step 1: 增加能力分类**

  能力中心必须区分：
  - Local tools
  - MCP tools
  - Skills
  - 默认可见
  - deferred
  - disabled

- [ ] **Step 2: 设计能力详情**

  展示 `displayName`、`capability_id`、description、searchText 中文别名、exposure mode 和最近命中。

- [ ] **Step 3: 设计中文能力搜索矩阵**

  至少覆盖：
  - `读取文件` -> `local.read_file`
  - `运行命令` -> `local.exec_shell`
  - `列出目录` -> `local.list_dir`
  - `搜索关键字` -> `local.grep`
  - `写文件` -> `local.write_file`
  - `打补丁` -> `local.apply_patch`

- [ ] **Step 4: 表达 `tool_search` 提升机制**

  展示“本轮检索命中，下一轮暴露工具 schema”的状态变化，避免误以为所有 MCP/Skill 都常驻模型窗口。

- [ ] **Step 5: Commit**

  ```powershell
  git add docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md
  git commit -m "docs(p3-ui): 设计能力中心和中文搜索"
  ```

### Task 7: 补齐会话内 P3 事件流

**Files:**
- Create: Figma frame `P3 09 会话-自动压缩事件`
- Create: Figma frame `P3 10 会话-长期记忆引用`
- Create: Figma frame `P3 11 会话-tool_search 命中`
- Modify: `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md`

- [ ] **Step 1: 设计自动压缩事件**

  消息流里展示：`上下文接近上限，已自动压缩旧历史`，并附带覆盖范围、summary id 和节省 token。

- [ ] **Step 2: 设计长期记忆引用事件**

  展示：`已检索 3 条长期记忆`，展开后显示引用片段和“记忆仅作为参考”的边界说明。

- [ ] **Step 3: 设计 tool_search 命中事件**

  展示：`已找到 4 个相关能力，下轮将暴露 2 个工具 schema`。

- [ ] **Step 4: 截图验收**

  Expected: 事件流不会压过助手回答主体，也不会把内部实现细节展示成用户必须理解的说明书。

- [ ] **Step 5: Commit**

  ```powershell
  git add docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md
  git commit -m "docs(p3-ui): 设计会话内上下文事件流"
  ```

### Task 8: 视觉一致性和交付同步

**Files:**
- Modify: Figma file `frTp55zgrKf4NAWxn6LdI7`
- Modify: `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md`
- Modify: `docs/superpowers/plans/p3-master.md`
- Modify: `docs/superpowers/plans/p3-task-index.md`

- [ ] **Step 1: 统一侧栏宽度和样式**

  旧版 292px 和新版 196px 侧栏不应混用；选择一个作为最终 P3 原型规格，并记录原因。

- [ ] **Step 2: 移除“成本”文案**

  把 `本轮成本反馈` 改为 `本轮运行反馈` 或 `本轮 token 反馈`。

- [ ] **Step 3: 导出关键截图**

  至少导出：总览、会话输入栏、上下文弹层、运行详情上下文快照、记忆设置、能力中心、中文能力搜索。

- [ ] **Step 4: 更新计划状态**

  执行完成后，把 `p3-master.md` 和 `p3-task-index.md` 中 `P3-UI` 状态改为 `已完成`，并在 handoff 记录截图和验收命令。

- [ ] **Step 5: 最终提交**

  ```powershell
  git add docs/superpowers/plans/p3-ui-prototype-refresh docs/superpowers/plans/p3-master.md docs/superpowers/plans/p3-task-index.md
  git commit -m "docs(p3-ui): 完成 P3 原型刷新交接"
  ```

---

## 5. 验收标准

- Figma 总览能清楚表达 P3 新能力，不再只像 P2 设置/审批原型。
- 用户在会话页一眼能看到当前目录、权限、模型、上下文占用、压缩状态、长期记忆和能力装配状态。
- 运行详情能解释“本轮模型看见什么、为什么看见、哪些被排除、哪些被记忆引用”。
- 能力中心能解释 Local/MCP/Skill 的可见性差异和 deferred 机制。
- 中文能力搜索演示能覆盖 `读取文件`、`运行命令`、`列出目录`、`搜索关键字`、`写文件`、`打补丁`。
- 原型不展示价格/成本，不把 VectorStore 语义搜索伪装成已完成能力。
- `docs/superpowers/plans/p3-ui-prototype-refresh/codex-handoff.md` 记录所有新增页面、截图、剩余风险和后续 Compose UI 对齐建议。

---

## 6. 推荐执行顺序

1. 先做 Task 1 到 Task 3，让 P3 状态入口在会话主路径可见。
2. 再做 Task 4 到 Task 6，把审计、记忆和能力中心补齐。
3. 最后做 Task 7 到 Task 8，补事件流和视觉一致性。

这个顺序能保证每一批 Figma 更新都有独立可验收价值，不会等到最后才看到效果。
