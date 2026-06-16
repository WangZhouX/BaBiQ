# P6-3 团队真协调 + 团队记忆 + 团队面板 实现计划（Implementation Plan）

> **For agentic workers:** REQUIRED SUB-SKILL：用 `superpowers:test-driven-development` 逐任务实现、`superpowers:verification-before-completion` 收尾。步骤用 checkbox（`- [ ]`）跟踪。
>
> **开工前必读（设计依据，语义一律以它为准）：** 同目录 `design.md`（团队真协调设计 spec；含 §0 逐文件核实、§1.3 团队语义、§3 组件、§4 决策、§5 数据模型、§7 桌面 UI=团队右侧可开合面板、§9 测试、§10 风险）。配套 Figma 原型：文件 `frTp55zgrKf4NAWxn6LdI7` 页面「团队协作」6 帧。
> **关联现状：** `docs/superpowers/plans/p6-3-team-collaboration/`（已落地但“空心”的 P6-3 旧版）。本计划是把它补成“真协调 + 团队记忆 + 团队面板”。

**Goal:** 让 P6-3 团队从“结构是真循环但信息流空心”补成真正能协调并交付，并把团队 UI 落成“主对话右侧可开合面板”（无 tab/页面、多团队切换、面板自带 composer 默认对 Leader）。

**Architecture:** 后端继续薄封装 Spring AI Alibaba（成员 = `ReactAgent`，经 `SubAgentRuntimeFactory`）；执行模型先 spike 定 Path A（BaBiQ 自驱逐轮循环）/Path B（StateGraph+interrupt）。团队记忆 = `~/.babiq/teams/<id>/` blackboard（薄复用 `MemoryArtifactMirror` 范式 + P3 压缩）。桌面 UI 仅改渲染面（团队 chatter 仍走现有 `ChatReducer` 分流），团队面板按 `teamId` 读 `bq_team_messages`。

**Tech Stack:** Java 21 / Spring Boot / Spring AI Alibaba `1.1.2.3`（不升级）；Kotlin / Compose Desktop；SQLite + Flyway + MyBatis-Plus。

---

## 0. 元约束（沿用 P8 补做那轮的硬纪律，逐条遵守）

1. **先红后绿**：每个新功能必须先写一个会失败的测试，跑红，再实现到绿。缺功能不能靠“绿测试”掩盖（团队空心的历史教训）。
2. **逐任务一笔提交**：中文 conventional commit，前缀 `feat(p6-3):` / `fix(p6-3):` / `test(p6-3):` / `docs(p6-3):`。不揉大提交。
3. **严禁把“未实现”写成“未验证”**：完成报告里凡声称“已实现”的，必须能指出 `file:line`。
4. **人工烟测做不了**（无头/无真实 Provider/无可操作桌面）就逐项标“未执行 + 原因”，**绝不标“通过”**。
5. **红线（不可触碰）**：
   - 不升级 Spring AI / Spring AI Alibaba 版本；成员继续薄封装官方 `ReactAgent`，不自研子 Agent 引擎。
   - 团队继续 **approve-once 团队级整体授权 + 沙箱**（不引入成员级逐工具 HITL）；approve-once 冻结**结构**（成员/工具/写入范围/沙箱）**而非目标**（spec §1.3）。
   - 新表/新字段必须同步 SQL 中文注释 + `bq_schema_comments` + Entity 注释 + `SchemaCommentsCoverageTest`。
   - 团队 chatter **不进主对话 `messages` 流**（沿用 `ChatReducer` 既有 `filterNot`）；**不加团队 nav tab、不做独立团队页面**。
6. **回归不能破**：现状里做对的部分（supervisor 真循环、白名单归一化、maxRounds、approve-once、确定性回退、WorkUnit goalId 闸门）补做后必须仍全绿；`AgentLoopLineCountTest` 不退化。

---

## 1. 文件影响总览

**后端（真协调，包 `com.wzx.babiq.server.agent.team`）**
- Create `TeamMemoryWorkspace.java`（blackboard 落盘 + 产物记录，薄复用 `MemoryArtifactMirror` 范式）、`TeamMemoryProperties.java`（`babiq.team.*` 配置）。
- Create `TeamSummaryCardBuilder.java`（成员产出→有界摘要卡）、`TeamDiscussionDigest.java`（滚动讨论概要，薄复用 P3 `ContextCompactionService`）、`TeamMemberContextAssembler.java`（成员读路径=有界四块）。
- Modify `TeamCoordinationService.java`（执行模型改造 + supervisor 看得见结果 + 结果聚合 + 轮次间注入 + 成员观测回写）。
- Modify `SpringAiSupervisorRoutingStrategy.java` 调用点（decide 输入改为 member_summary 时间线，按预算截断）。
- Modify `TeamDirectMessageService.java`（落库不变；新增“已消费水位”支持轮次间 drain）。
- Modify `TeamCoordinationTool.java`（成员 token/工具真实归属，替掉硬编码 0；maxRounds clamp 到配置上限）。
- Create migration `V21__team_artifacts.sql`（`bq_team_artifacts`）。

**后端协议（包 `com.wzx.babiq.server.api.method`）**
- Create `TeamListHandler.java` / `TeamGetHandler.java`（薄读 `bq_teams` / `bq_team_messages`）。
- Modify `TeamMessageSendHandler.java`（`to_agent` 支持 `leader` 默认）。

**桌面（Compose）**
- Modify `state/UiModels.kt`、`state/ChatController.kt`、`state/ChatReducer.kt`、`protocol/TeamModels.kt`（多团队列表 + 选中团队 + 团队面板开合状态 + 团队 composer 目标）。
- Modify `ui/runtime/TeamSection.kt` → 重构为**主对话右侧可开合团队面板**（多团队切换 + 成员 + 时间线 + 自带 composer + 可改目标）；`ui/shell/AppShell.kt`（面板停靠/开合）。
- 成员配置（模型/角色标签/职能/工具/模式/写入范围）在面板内展开或弹层。

**测试（先红后绿）**
- Backend：`TeamCoordinationServiceTest`、`TeamMemoryWorkspaceTest`、`TeamSummaryCardBuilderTest`、`TeamDiscussionDigestTest`、`TeamMemberContextAssemblerTest`、`TeamDirectMessageServiceTest`、`TeamApprovalServiceTest`(回归)、`TeamListHandlerTest`/`TeamGetHandlerTest`/`TeamMessageSendHandlerTest`、`SchemaCommentsCoverageTest`、`AgentLoopLineCountTest`(回归)。
- Desktop：`TeamSectionTest`、`ChatReducerTest`、`ChatControllerTest`、`TeamModelsTest`。

---

## 2. 任务分解

> 顺序：**T0 spike 必须先过**（否则后端真协调返工风险高）→ A 后端真协调（T1–T7）→ B 桌面面板（T8–T10）→ T11 全量验收 + 文档同步。

### T0：执行模型 spike（实现前必做，spec §4.2 / §10 头号风险）
**目的：** 用真实 1.1.2.3 验证 **Path A（BaBiQ 自驱逐轮循环 + 成员 `AgentTool.call`）**能否：① 成员调用携带父 toolContext（emitter/cwd/observation/沙箱）；② 成员工具调用落 `bq_tool_calls` 且 `agent_name` 归属到成员（复用 V13）；③ emitter 时效正常。
- [ ] **Step 1：写 spike 测试** `TeamExecutionModelSpikeTest`（隔离）：构建一个最小成员 `ReactAgent`，经 `SubAgentRuntimeFactory` + `AgentTool.getFunctionToolCallback(agent).call(input, ctx)` 调一次只读工具，断言 `bq_tool_calls` 出现该成员名归属、observation 计数 +1。
- [ ] **Step 2：跑** `cd backend; .\mvnw.cmd "-Dtest=TeamExecutionModelSpikeTest" test`。
- [ ] **Step 3：判定**：通过 → 锁 **Path A**，T6 按自驱循环实现；不通过（toolContext/归属丢失）→ 回退 **Path B**（保留 StateGraph，supervisor 节点 `InterruptableAction.interruptAfter` 每轮中断→drain→`stream` 恢复），并在本任务记录硬伤。
- [ ] **Step 4：Commit** `test(p6-3): 执行模型 spike 锁定团队循环基座`（含结论注释）。

### T1：团队记忆工作区 + `bq_team_artifacts`（spec §3.1 / §5）
**Files:** Create `TeamMemoryWorkspace.java`、`TeamMemoryProperties.java`、`V21__team_artifacts.sql`；Entity `TeamArtifactEntity.java` + mapper；Test `TeamMemoryWorkspaceTest.java`、`SchemaCommentsCoverageTest`(增量)。
- [ ] **Step 1：失败测试**：`initTeam` 建 `~/.babiq/teams/<id>/` + 写 `team.md` 头；`writeMemberOutput` 落 `rounds/r<n>-<member>.md` 全文并返回带 `sha256`/`tokenEstimate` 的产物记录；`appendIndexEntry` 往 `team.md` 追加一行 `[r<n> <member>](文件) — 摘要`；`writeDigest`/`writeResult` 落盘；`SchemaCommentsCoverageTest` 断言 `bq_team_artifacts` 全字段中文注释非空（先红）。用临时目录（`@TempDir`），不写真实家目录。
- [ ] **Step 2：跑红** `.\mvnw.cmd "-Dtest=TeamMemoryWorkspaceTest,SchemaCommentsCoverageTest" test`。
- [ ] **Step 3：实现**：薄复用 `MemoryArtifactMirror` 的 `Files.createDirectories`→写 md→产物记录范式；V21 建表 + SQL 中文注释 + `bq_schema_comments` 插入 + Entity 中文字段注释。**源记录追加式永久、不压缩不删除**（spec §3.1 不可变量）。
- [ ] **Step 4：跑绿** 同上。
- [ ] **Step 5：Commit** `feat(p6-3): 团队记忆工作区与 bq_team_artifacts`

### T2：成员产出捕获 → 摘要卡 + md 镜像 + 成员观测（堵“结果丢弃”“观测桩死”）
**Files:** Create `TeamSummaryCardBuilder.java`；Modify `TeamCoordinationService.java`（capture 步）、`TeamCoordinationTool.java`（成员记录归属）；Test `TeamSummaryCardBuilderTest.java`、`TeamCoordinationServiceTest`(增量)。
- [ ] **Step 1：失败测试**：成员产出后 `bq_team_messages` 出现 `messageType=member_summary`（当前从未生产）；全文写入 `rounds/*.md`；`bq_team_members.tool_call_count/token_estimate` **非 0**（按 `bq_tool_calls` 的成员归属聚合，复用 V13）；`TeamSummaryCardBuilder` 按 `member-summary-max-chars` 截断 + 附 `详情见 rounds/...` 引用，不额外调 LLM。
- [ ] **Step 2：跑红** → **Step 3：实现** → **Step 4：跑绿**。
- [ ] **Step 5：Commit** `feat(p6-3): 捕获成员产出为摘要卡与 md 并归属观测`

### T3：成员读路径 — team.md 索引 + 滚动讨论概要 + 上下文装配（spec §3.8/3.9，防爆窗口）
**Files:** Create `TeamDiscussionDigest.java`（薄复用 P3 `ContextCompactionService`）、`TeamMemberContextAssembler.java`；Test `TeamDiscussionDigestTest.java`、`TeamMemberContextAssemblerTest.java`。
- [ ] **Step 1：失败测试**：`TeamMemberContextAssembler` 装配的成员上下文**只含四块**（团队目标 / 本职任务+supervisor 理由 / 有界滚动讨论概要 / team.md 索引引用），**断言不含任何 `rounds/*.md` 全文**；`TeamDiscussionDigest.roll` 超 `discussion-digest-budget-tokens` 时触发 P3 压缩且结果有界；成员无状态（每轮新建，不跨轮累积）。
- [ ] **Step 2-4：红→绿**。
- [ ] **Step 5：Commit** `feat(p6-3): 成员读路径与滚动讨论概要`

### T4：supervisor 看得见结果（堵“瞎转”，spec §3.4）
**Files:** Modify `TeamCoordinationService.java`（decide 输入）、`SpringAiSupervisorRoutingStrategy.java`（prompt 模板基本不变）；Test `TeamCoordinationServiceTest`(增量)。
- [ ] **Step 1：失败测试**：`decide` 收到的 timeline **包含 `member_summary`**（断言成员产出真进了决策输入，不再只有 route）；超 `supervisor-context-budget-tokens` 时丢最旧 member_summary、保留 route/direct。
- [ ] **Step 2-4：红→绿**。
- [ ] **Step 5：Commit** `fix(p6-3): supervisor 路由可见成员产出摘要`

### T5：结果聚合回传（堵“返回硬编码串”，spec §3.5）
**Files:** Modify `TeamCoordinationService.java`（FINISH 分支）；Test `TeamCoordinationServiceTest`(增量)。
- [ ] **Step 1：失败测试**：`run()` 成功返回的 summary **来自成员产出聚合**（断言含成员内容/`result.md` 引用），**不是常量** `"团队协作已完成"`；`result.md` 落盘。
- [ ] **Step 2-4：红→绿**。
- [ ] **Step 5：Commit** `feat(p6-3): 团队结果聚合并回传主 Agent`

### T6：执行模型改造 + 轮次间注入（spec §3.2/§3.6，按 T0 结论）
**Files:** Modify `TeamCoordinationService.java`（按 Path A/B 重写 run）、`TeamDirectMessageService.java`（消费水位）；Test `TeamCoordinationServiceTest`、`TeamDirectMessageServiceTest`(增量)。
- [ ] **Step 1：失败测试**：循环前置一条 `direct_user`（to=tester），断言**下一轮该成员的指令包含该消息**（轮次间注入）；消费水位幂等（不重复注入、不漏注入）；maxRounds 上限与白名单回归不破。
- [ ] **Step 2-4：红→绿**（Path A：Java 逐轮循环，每轮 decide→drain→`AgentTool.call` 成员→capture→emit；Path B：interrupt/resume）。
- [ ] **Step 5：Commit** `feat(p6-3): 逐轮可中断循环与轮次间消息注入`

### T7：可改目标（spec §1.3，结构冻结≠目标冻结）
**Files:** Modify（如需）`DefaultWorkUnitService.java`（确认 `update_goal`/`append_goal` 不被 `frozen` 拦）；Test `WorkUnitServiceTest`/`TeamApprovalServiceTest`(增量)。
- [ ] **Step 1：失败测试**：团队 `approved&frozen` 后，`update_goal`/`append_goal` **仍可改/追加目标**；但改成员/工具/写入范围**被拒**（结构冻结）。
- [ ] **Step 2-4：红→绿**（多数是验证现有语义 + 补测试；若现有 `frozen` 误拦目标则修正）。
- [ ] **Step 5：Commit** `feat(p6-3): 团队结构冻结但目标可改`

### T8：协议读路径 + 发送扩展（spec §7.3）
**Files:** Create `TeamListHandler.java`、`TeamGetHandler.java`；Modify `TeamMessageSendHandler.java`、`MethodRegistry`；Test `TeamListHandlerTest`、`TeamGetHandlerTest`、`TeamMessageSendHandlerTest`。
- [ ] **Step 1：失败测试**：`team/list` 返回多团队（状态/轮次/成员数）；`team/get` 返回选中团队的成员 + 时间线；`team/message/send` `to_agent` 缺省=`leader`，写入 `bq_team_messages(direct_user)`。
- [ ] **Step 2-4：红→绿**（薄封装现有 `bq_teams`/`bq_team_messages` 查询，不另造）。
- [ ] **Step 5：Commit** `feat(p6-3): 团队列表/详情读路径与发送默认对 Leader`

### T9：桌面 — 主对话保持干净 + 右侧可开合团队面板 + 多团队切换（spec §7.1）
**Files:** Modify `protocol/TeamModels.kt`、`state/UiModels.kt`、`state/ChatReducer.kt`、`state/ChatController.kt`、`ui/shell/AppShell.kt`；Test `ChatReducerTest`、`ChatControllerTest`、`TeamModelsTest`。
- [ ] **Step 1：失败测试**：`team`/`teamMessage` item **不进主聊天 `messages`**（回归现有 `filterNot`）；多团队状态可由 `team/list` 装配并切换；面板开合状态可切；选中团队的消息按 `teamId` 过滤（不串台）。**不新增团队 nav tab。**
- [ ] **Step 2-4：红→绿**。
- [ ] **Step 5：Commit** `feat(p6-3): 桌面团队右侧可开合面板与多团队切换`

### T10：桌面 — 团队面板内容（成员配置 / 时间线 / 自带 composer / 可改目标）（spec §7.1/7.2）
**Files:** Modify `ui/runtime/TeamSection.kt`（重构）；Test `TeamSectionTest`(重写/增量)。
- [ ] **Step 1：失败测试**：面板渲染 多团队切换器 + 成员列表（点开配置：模型继承/覆盖、角色标签、职能自由文本、工具、模式、写入范围）+ 执行时间线（route/member_summary/direct_user 分渲染、成员卡含真实 token/工具数）+ **团队自带 composer 默认对 Leader、可切成员** + 目标行可编辑（`✎ 改目标`，结构冻结仍可改目标）。
- [ ] **Step 2-4：红→绿**（对齐 Figma「团队协作」页帧 02 主对话面板展开 / 帧 03 成员配置）。
- [ ] **Step 5：Commit** `feat(p6-3): 团队面板成员配置/时间线/自带 composer`

### T11：全量验收 + 文档同步
- [ ] **Step 1：后端**
```powershell
cd backend
.\mvnw.cmd "-Dtest=TeamCoordinationServiceTest,TeamMemoryWorkspaceTest,TeamSummaryCardBuilderTest,TeamDiscussionDigestTest,TeamMemberContextAssemblerTest,TeamDirectMessageServiceTest,TeamApprovalServiceTest,TeamListHandlerTest,TeamGetHandlerTest,TeamMessageSendHandlerTest,SchemaCommentsCoverageTest,AgentLoopLineCountTest" test
.\mvnw.cmd clean verify
```
- [ ] **Step 2：桌面**
```powershell
cd desktop
.\gradlew.bat test --tests "*TeamSectionTest" --tests "*ChatReducerTest" --tests "*ChatControllerTest" --tests "*TeamModelsTest"
.\gradlew.bat test --rerun-tasks
```
- [ ] **Step 3：人工烟测**（真实模型，逐项；做不了标“未执行+原因”）：多成员协作真跑（supervisor 看得见成员产出、结果聚合回主 Agent）、轮次间喊话生效、失败态、团队面板多团队切换 + 自带 composer 发送、可改目标、主对话全程不被团队 chatter 污染。
- [ ] **Step 4：文档同步**：`CLAUDE.md` / `AGENTS.md` P6-3 检查点（从“空心版自动化验收”更新为“真协调 + 团队面板已实现 + 自动化验收，真实烟测状态据实标注”）；`p6-master.md` §5.6 P6-3 注记 UI 模型修订（团队=主对话右侧面板，非 tab）。
- [ ] **Step 5：Commit** `docs(p6-3): 同步团队真协调与团队面板检查点`

---

## 3. 验收口径（声称完成前必须满足）
1. T0 spike 有结论且锁定基座；T1–T10 各任务**先红后绿**、各一笔 `feat/fix(p6-3):` 提交、每个新功能能指出 `file:line`。
2. `cd backend; .\mvnw.cmd clean verify` 全绿；`cd desktop; .\gradlew.bat test --rerun-tasks` 真执行全绿。
3. 现状做对的部分（supervisor 真循环、白名单、maxRounds、approve-once、WorkUnit 闸门、`SchemaCommentsCoverageTest`、`AgentLoopLineCountTest`）回归不破。
4. 人工烟测逐项标注（通过 / 未执行+原因），**不得把“未实现”写成“未验证”**。
5. 主对话不被污染、无团队 tab、团队=右侧可开合面板，与 Figma「团队协作」页一致。
