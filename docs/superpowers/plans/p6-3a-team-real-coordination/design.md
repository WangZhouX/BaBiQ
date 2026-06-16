# P6-3 团队真协调 + 团队记忆工作区 设计文档（Design Spec）

> 状态：**设计已与用户多轮确认**（2026-06-14）。UI 模型 = 团队为主对话**右侧可开合面板**（无 tab / 无独立页面、多团队切换、面板自带 composer 默认对 Leader）；执行顺序动态指令驱动、目标可改、每成员可换模型 + 有「职能」字段。下一步用 `superpowers:writing-plans` 拆成 `plan.md` + `codex-handoff.md` 逐任务实现。
> 本文件是设计 spec，不是实现计划。配套高保真原型见 Figma 文件 `frTp55zgrKf4NAWxn6LdI7` 页面「团队协作」（6 帧）。
> 关联：`docs/superpowers/plans/p6-master.md`（§5.6 P6-3）、`docs/superpowers/plans/p6-3-team-collaboration/`（已落地但空心的版本）。
> 阶段定位：这是对**已落地但空心**的 P6-3 团队协作的**强化补做**，不是 master 里预留的 P6-3b（teammate 点对点真并发 swarm 仍不在本设计内）。

---

## 0. 前置查证记录（2026-06-14，本设计据实核对，非记忆）

> 用户要求“要去确认，不能自以为是”。以下每条都对当前代码/官方文档逐项核实，设计中的架构判断均以此为据。

| 主题 | 核实结论 | 证据 |
|---|---|---|
| 现有团队执行模型 | 一次性 `compiledGraph.invoke(Map.of("input", goal))` 跑完整 StateGraph（loop 在图内：成员节点→supervisor 条件边） | `TeamCoordinationService.run/buildGraph`（:57-103） |
| supervisor 决策形态 | **已是独立 `ChatClient` 调用**（不是图内 LLM 节点），structured output 出 `SupervisorRouteDecision` | `SpringAiSupervisorRoutingStrategy.decide`（:29-56） |
| supervisor 是否看得到成员产出 | **看不到**：`decide()` 只喂 `repository.listMessages()`，而后者只含 supervisor 自己的 `route` 决策；成员 `outputKey` 产出从不被读取 | `TeamCoordinationService`（:110/119/176），`outputKey()` 全包仅 2 处使用且均非读取 |
| 团队最终结果 | 成功固定返回硬编码 `"团队协作已完成"`，`finalState` 只读 `next` | `TeamCoordinationService.run`（:70-71） |
| 成员级观测 | `bq_team_members` 已有 `tool_call_count/token_estimate/summary` 列，但写入端硬编码 0 | `V15`（:72-76），`TeamCoordinationTool.memberRecords/item`（:121/263-264） |
| 时间线类型 | `bq_team_messages.message_type` 已合法包含 `member_summary`，但从未被生产 | `V15`（:103-104），`saveMessage` 全包仅 `route` + `direct_user` 两处 |
| 喊话回路 | `TeamDirectMessageService` 只落库 + 返回展示 item，注释自承“后续要唤醒成员” = 未实现 | `TeamDirectMessageService.send`（:32-64，类注释 :12-14） |
| 成员 Agent 构建 | 复用 `SubAgentRuntimeFactory.buildChildAgentForTeam`，挂沙箱/观测/spotlighting/eviction/limit/tokenUsage hook，**无 HITL 审批 hook**（approve-once 模型，与 flow 一致） | `SubAgentRuntimeFactory.buildChildAgent`（:178-211，hooks 仅 limit+tokenUsage） |
| 成员同步调用先例 | P6-1 委派已用 `AgentTool.getFunctionToolCallback(agent).call(input, ctx)` 阻塞调用并取最终文本 | `SubAgentRuntimeFactory.delegate`（:94-102） |
| 子 Agent 归属字段 | V13 已为 `bq_tool_calls` 建 `agent_name/parent_agent_name/delegation_id`（P6-1） | `V13__subagent_delegation_attribution.sql` |
| 记忆 Markdown 镜像范式 | `MemoryArtifactMirror`：`Files.createDirectories(rootDir)` → 写 `.md` → 返回带 `sha256`/`tokenEstimate` 的产物记录落库；根目录 `${user.home}/.babiq/memories` | `MemoryArtifactMirror.mirror`（:52-100），`application.yml`（:67-85） |
| 注入预算范式 | 长期记忆 read path 用 `read-budget-tokens: 2500` 截断注入 | `application.yml`（:85） |
| BaBiQ 真实 HITL 恢复 API | `agent.stream(Map.of("jump_to", JumpTo.tool), resumeConfig)` + `InterruptionMetadata` + `PausedReactAgentRegistry`（**非** `compiledGraph.resume`） | `AgentLoopResumeSupport.resumeFromApproval`（:40-52） |
| SAA 官方中断机制 | `InterruptableAction.interrupt/interruptAfter(nodeId,state,…)` + `CompiledGraph.stream → Flux<NodeOutput>` + checkpoint 恢复，确实支持节点前/后中断 | Context7 `/alibaba/spring-ai-alibaba` graph-core README（InterruptableAction、CompiledGraph.stream） |
| 锁定版本 | 不升级 Spring AI `1.1.6` / Spring AI Alibaba `1.1.2.3`；Context7 仅有 v1.1.2.2，1.1.2.3 细节需本机 jar 复核（见 §10 spike） | CLAUDE.md / p6-master §2.3 |

---

## 1. 目标与范围

### 1.1 目标
把 P6-3 团队从“结构是真循环、但信息流空心”补成**真正能协调并交付**的团队：
1. 成员产出被**捕获**为有界结构化摘要 + 全文 md（团队记忆工作区）。
2. supervisor 路由决策**看得到**成员产出摘要（堵“瞎转”）。
3. 团队结束**聚合成果并回传**主 Agent（堵“结果丢弃”）。
4. 运行中**喊话成员真响应**（轮次间注入，用户已选“完整实时协作”档）。
5. 成员级 **token / 工具次数真实归属**（堵桩死的 0）。
6. 成员间**结果互见**（经团队记忆 blackboard，谁都能 `read_file` 别人的摘要 md）。

### 1.2 不做（明确划界，防膨胀）
- **真并发中断在飞成员 / teammate 点对点直连 swarm** → 仍属 master 预留的 **P6-3b**。
- 跨进程 / A2A 远程 Agent、真 OS 沙箱。
- 升级 Spring AI / Spring AI Alibaba 版本。
- 团队画布化编辑（不在本设计）。
- 成员级**逐工具 HITL 审批**：团队继续用 **approve-once 团队级整体授权 + 沙箱**模型（与现状、与 flow 一致），不引入成员内部逐工具弹窗。
- **团队记录的搜索检索（第 3 层深历史召回，Lucene/BM25）** → 本期不做；但由 §4.5「永久记录不可变量」保证它**以后可无损追加**（用户 2026-06-14 决定：暂不需要，但必须确定可实现）。

### 1.3 团队语义（2026-06-14 用户补充确认）
- **执行顺序是动态的、由指令/用户输入驱动**：团队**没有固定流程**；supervisor（Leader）按团队目标 + 成员产出摘要 + 用户在团队面板的指令，逐轮决定下一个谁上 / 是否 FINISH。这正是团队区别于「编排（固定拓扑）」的根本——编排在设计期画死顺序，团队在运行期按指令涌现。
- **团队目标是可改的概念，且团队不止做一件事**：复用 WorkUnit 目标队列（`append_goal` / `update_goal`），目标可编辑、可追加。**与 approve-once 正交**：approve-once 冻结的是「成员 / 工具 / 写入范围 / 沙箱」这套**结构与权限**，**不冻结目标**——结构冻结后仍可改目标 / 给新目标，让同一支已授权团队连续做多件事。
- **每个成员可单独换模型**：`BabiqTeamMember.modelPolicy`（继承主 Agent 或覆盖为指定 provider+model），在成员配置里逐个选。
- **每个成员都有「职能」字段（自由文本，用户指派）**：即 `task`，用户给每个成员明确职责；角色是短标签、职能是自由文本职责描述（见 §7.1）。

---

## 2. 架构总览

核心改动是**两件事**：① 新增“团队记忆工作区”作为成员间和 supervisor 的共享 blackboard；② 把“一次性 `compiledGraph.invoke` 跑完”改成 **BaBiQ 自驱的逐轮循环**，让每轮成为可注入、可记录、可观测的边界。

```
coordinate_team(工具入口, 不变: approve-once + 沙箱 + WorkUnit goalId 闸门)
        │
        ▼
TeamCoordinationService.run  ← 改为 BaBiQ 自驱逐轮循环
  repeat (round = 0..maxRounds):
    1. decide      : SpringAiSupervisorRoutingStrategy.decide(spec, 摘要时间线[预算截断], round)
                     —— 输入新增「成员摘要卡」，堵瞎转
    2. normalize   : 白名单 + maxRounds（不变）
    3. if FINISH   : 聚合 result.md → 回传摘要给主 Agent（替掉硬编码串）→ break
    4. drain       : 取出本轮前用户发给目标成员的 direct_user 消息，折进本轮指令（轮次间注入）
    5. assemble+invoke : 装配成员上下文(目标 + 本职任务 + supervisor 路由理由 + 有界滚动讨论概要 digest.md + team.md 索引引用; 不 push 任何全文)
                     → 构建成员 ReactAgent(approve-once, 无状态/每轮新建) → AgentTool.call 阻塞取最终文本
    6. capture     : 全文 → rounds/r<round>-<member>.md ; 一句话 → team.md 索引 ; 有界摘要卡 → bq_team_messages(member_summary)
                     + bq_team_members(summary/tool_call_count/token_estimate 真实值) ; 刷新 digest.md 滚动讨论概要
    7. emit        : TeamItem / TeamMessageItem 增量推送（UI 实时）
        │
        ▼
团队记忆工作区  ~/.babiq/teams/<teamId>/  (team.md 索引 / digest.md 讨论概要 / rounds/*.md 详情 / result.md)
  └─ SQLite 为事实源(bq_team_messages + bq_team_members + 新 bq_team_artifacts) + Markdown 镜像
  └─ 成员无状态/每轮新建: 上下文由「四块」有界重建(见 §3.8), 讨论轮数增长不让单成员上下文膨胀
```

> 与现状最大差异：**放弃“把循环编进 StateGraph + 一次性 invoke”**，改为 BaBiQ 在 Java 里逐轮推进。理由见 §4.2（已核实可行且更简单、风险更低）。

---

## 3. 组件设计

每个组件标注：职责 / 接口 / 依赖。新组件放 `com.wzx.babiq.server.agent.team`（沿用现有包）。

### 3.1 `TeamMemoryWorkspace`（新）— 团队记忆 blackboard
- **职责**：管理某团队在 `~/.babiq/teams/<teamId>/` 的目录与文件读写；薄封装 `MemoryArtifactMirror` 的同款套路（`Files.createDirectories` → 写 `.md` → 返回带 `sha256`/`tokenEstimate` 的产物记录）。**与长期记忆完全分离**（长期记忆是用户级跨会话提炼事实；团队记忆是单次运行的任务级工作草稿）。
- **目录布局**：
  ```
  ~/.babiq/teams/<teamId>/
    team.md                       # 索引清单(manifest): 每条一行 `[r<round> <member>](文件) — 一句话摘要`; 照搬记忆系统 MEMORY.md 范式, 成员靠它快速定位
    digest.md                     # 滚动「讨论概要」: 压缩过的全局进展(非原文), 有界, 供成员共享态势感知
    rounds/r<round>-<member>.md   # 每轮每成员全文产出(详情, 仅按需 read_file)
    result.md                     # FINISH 时聚合成果
  ```
- **接口**（草案）：
  ```java
  void initTeam(String teamId, BabiqTeamSpec spec);                              // 建目录 + 写 team.md 头
  TeamArtifactRecord writeMemberOutput(String teamId, int round, String member, String fullText);
  void appendIndexEntry(String teamId, int round, String member, String oneLine, Path detailRef); // 追加 team.md 清单条目
  TeamArtifactRecord writeDigest(String teamId, String rollingDigestMarkdown);   // 刷新 digest.md 滚动讨论概要
  TeamArtifactRecord writeResult(String teamId, String aggregatedMarkdown);
  Path teamDir(String teamId);   // 供成员 read_file 按需引用
  ```
- **依赖**：`ContextTokenEstimator`（token 估算，与 mirror 一致）、`TeamMemoryProperties`（根目录配置）、`TeamRepository`（产物记录落库）。
- **复用判断**：`MemoryArtifactMirror` 与团队语义不同（它面向 Phase2 批量记忆候选），不直接复用其类，但**复用其落盘+产物记录范式**，避免重复造轮子且与现有技术栈一致。
- **源记录不可变量（关键）**：本工作区写出的 `rounds/*.md` 及对应 `bq_team_artifacts` / `bq_team_messages` 是**追加式、永久、完整**记录，**不压缩、不删除**；压缩只发生在派生的 `digest.md`。这条是 §4.5 长跑召回“以后可无损追加搜索检索”的根本前提。

### 3.2 `TeamCoordinationService`（改造）— BaBiQ 自驱逐轮循环
- **职责**：取代现有“`buildGraph` + 一次性 `invoke`”，改为 §2 的 Java 逐轮循环；每轮串起 decide → drain → invoke 成员 → capture → emit。
- **接口**：保持 `TeamExecutionResult run(BabiqTeamSpec spec, ToolContext parentToolContext)` 签名不变（对 `TeamCoordinationTool` 透明），内部实现重写。
- **成员调用方式**（已核实可行）：复用 P6-1 路径——`AgentTool.getFunctionToolCallback(memberAgent).call(roundInstructionJson, childContext)` 取最终文本；`memberAgent` 由 `SubAgentRuntimeFactory` 构建（每成员自己的 `MemorySaver` 即可，**不再需要团队级 shared saver / StateGraph**）。
- **依赖**：`SupervisorRoutingStrategy`、`TeamMemberAgentFactory`、`TeamMemoryWorkspace`、`TeamRepository`、`TeamSummaryCardBuilder`(§3.3)、`ObjectMapper`。
- **保留**：白名单归一化、`maxRounds` 上限、确定性回退（provider 不可用时轮询）——这些是现状里做对的部分，回归不能破。

### 3.3 `TeamSummaryCardBuilder`（新）— 成员产出 → 有界摘要卡
- **职责**：把成员最终输出转成喂给 supervisor 的**有界摘要卡**。
- **策略（用户已定）**：成员最终输出**即结果**；系统按预算**确定性截断**为摘要卡（状态 + 头部若干字符 + “详情见 rounds/r<round>-<member>.md”引用），全文写进 md。**默认不额外调 LLM 摘要**；若以后截断太糙，再加可选 LLM 摘要步骤（YAGNI，本期不做）。
- **接口**：`String buildCard(String member, int round, String fullText, Path detailRef, int maxChars)`。
- **依赖**：`ContextTokenEstimator`。

### 3.4 supervisor 路由输入改造（改 `SpringAiSupervisorRoutingStrategy` 调用点）
- **现状**：`decide(spec, repository.listMessages(teamId), round)` —— timeline 只有 route 消息。
- **改造**：`TeamCoordinationService` 在调 `decide` 前，从 `bq_team_messages` 取 `member_summary` + `route` + `direct_user` 组成的时间线，**按 `supervisor-context-budget-tokens` 预算截断**（超预算时丢最旧的 member_summary，保留最近若干轮 + 全部 route/direct）。`SpringAiSupervisorRoutingStrategy` 的 prompt 模板基本不变（它已遍历 timeline 拼 `from -> to [type]: content`），只是现在 timeline 里**真有成员结果**了。
- **依赖**：预算截断逻辑放 `TeamCoordinationService`（或抽 `TeamTimelineBudget` 小工具）。

### 3.5 结果聚合（`TeamCoordinationService` FINISH 分支）
- FINISH 时：把各成员最新 `member_summary` 卡 + 关键产物引用拼成 `result.md`（机械拼接，不必调 LLM；如需更好可选 LLM 收尾，本期默认机械拼接），写入工作区；`TeamExecutionResult.summary` 改为返回该聚合摘要（替掉 `"团队协作已完成"`），回传给主 Agent。

### 3.6 轮次间喊话注入（改 `TeamDirectMessageService` + 循环 drain）
- `TeamDirectMessageService.send` 仍负责落库 `direct_user` 消息（不变）。
- **新增 drain**：`TeamCoordinationService` 每轮第 4 步查询“`round >= 上次消费点` 且 `to_agent == 目标成员/supervisor`”的未消费 `direct_user` 消息，折进本轮成员指令（或作为 supervisor 下一轮决策输入）。消费后用 round 标记推进，避免重复注入。
- **落点语义**：用户消息在**下一轮边界**生效（用户已选此档）；不做在飞中断。

### 3.7 成员级观测归属（改 capture 步）
- 成员经 `SubAgentRuntimeFactory` 构建时已带 delegation 上下文（`agent_name=member.name`，`parent_agent_name=MAIN_AGENT`）。成员运行后：
  - 工具次数：按 `delegation_id` 聚合 `bq_tool_calls`（V13 字段）。
  - token：从 `BaBiQTokenUsageHook` / 成员输出估算。
- 把真实值写入 `bq_team_members.tool_call_count/token_estimate/summary` 与 `TeamItem.MemberStatus`，替掉硬编码 0。
- **依赖**：复用 P6-1/V13 已有归属链路；本期只补“查询并写回”。

### 3.8 `TeamMemberContextAssembler`（新）— 成员读路径与上下文装配（关键：防爆窗口）
- **职责**：成员被调度时装配它本轮看到的上下文，保证**有界且够用**。这是“讨论次数多了会不会爆”的正面回答。
- **成员上下文 = 固定四块**：① 团队目标；② 本职任务（+ supervisor 本轮路由理由/指令）；③ **有界滚动「讨论概要」**（`digest.md`，压缩过的全局进展，做共享态势感知）；④ `team.md` 索引引用 + supervisor 点名的相关条目（“相关见 r1-explorer”）。**不 push 任何 `rounds/*.md` 全文。**
- **无状态成员**：每轮新建成员 ReactAgent、调用一次即止，**不跨轮累积**；连续性完全由上面四块重建 ⇒ “对话次数多”不会让单成员上下文越堆越大。
- **按需 pull**：成员持只读 `read_file`，据 ②/④ 指引主动读特定 `rounds/*.md` 详情；读什么由模型按任务决定，不全量 push。
- **预算**：③ 按 `discussion-digest-budget-tokens` 截断；④ 索引按条目数，过长折叠更早条目。
- **接口**：`String assembleMemberInstruction(BabiqTeamSpec spec, BabiqTeamMember member, int round, String supervisorReason, List<TeamMessageRecord> injected)`。
- **依赖**：`TeamMemoryWorkspace`（读 digest/index）、`TeamDiscussionDigest`（§3.9）、`ContextTokenEstimator`。

### 3.9 `TeamDiscussionDigest`（新，薄复用 P3 压缩）— 滚动讨论概要
- **职责**：把“讨论进展”维持成一条**有界**的滚动概要（`digest.md`），避免随轮数线性膨胀。
- **策略**：每轮成员产出后把新摘要卡并入概要；当概要超 `discussion-digest-budget-tokens` 时，**复用 P3-3 短期压缩**（`ContextCompactionService` / `SpringAiContextCompactionStrategy`）把更早内容滚动压缩，最近几轮保留较完整。
- **依据（已核实）**：P3-3 已有压缩服务与策略（CLAUDE.md P3-3 检查点），本期薄复用，不另造摘要引擎。
- **接口**：`String roll(String currentDigest, String newCard, int budgetTokens)`。
- **依赖**：P3 `ContextCompactionService`（或其策略端口）、`ContextTokenEstimator`。

---

## 4. 关键架构决策

### 4.1 团队记忆与长期记忆分离
团队记忆是**任务级、单次运行、可丢弃的工作草稿**，长期记忆是**用户级、跨会话、提炼后的事实**。二者目录、表、生命周期都不同。团队记忆**不写入** `~/.babiq/memories`，而是新 `~/.babiq/teams/<teamId>/` + 新 `bq_team_artifacts` 表；只复用 mirror 的**落盘范式**，不复用其类。

### 4.2 执行模型：BaBiQ 自驱逐轮循环（取代 StateGraph 一次性 invoke）—— 推荐方案 + 备选

**推荐：Path A — BaBiQ 自驱 Java 逐轮循环。**
- **依据（已核实）**：supervisor 决策本就是独立 ChatClient 调用（非图节点）；P6-1 已用 `AgentTool.call` 阻塞调用单个成员并取文本；团队是 approve-once（成员内部无逐工具 HITL，不需要图级共享 saver 来上浮中断）。
- **收益**：轮边界 = Java 循环迭代，注入/记录/观测/emit 全部天然可控；复用 P6-1 已验证的成员调用；删掉团队级 StateGraph / asNode / shared saver / compileConfig 这套**当前并未用上其 HITL 嵌套收益**的机制，**净简化**。
- **代价**：偏离 p6-master §5.6 当初为 P6-3 选的“StateGraph + asNode”范式。但该范式的主要理由（HITL 嵌套）团队并不使用，故偏离有充分理由。

**备选：Path B — 保留 StateGraph，supervisor 节点实现 `InterruptableAction.interruptAfter` 每轮中断 → drain → `CompiledGraph.stream` 恢复。**
- 更贴近“官方图编排”，与 master 一致；但中断/恢复 plumbing 更重，且 1.1.2.3 在“团队图上按节点中断再恢复”的确切行为需本机 jar 复核。

**结论**：推荐 Path A。但执行模型是本设计**头号风险**，§10 列为**实现前必须先做的最小 spike**：用真实 1.1.2.3 验证 Path A 的成员 `AgentTool.call` 在团队循环里能否正确携带父 toolContext（emitter/cwd/observation/沙箱）并落 `bq_tool_calls` 归属；若有硬伤再回退 Path B。**spike 通过前不写正式实现。**

### 4.3 上下文预算（防爆窗口）
supervisor 看到的是**摘要卡时间线**而非全文，且按 `supervisor-context-budget-tokens` 截断（超预算丢最旧 member_summary）。全文只在磁盘 md，成员/ supervisor 需要细节时用 `read_file` 主动取。这与长期记忆 read-budget 范式一致。

### 4.4 hub-and-spoke + 无状态成员（不是群聊）
团队是 supervisor 中枢调度，**不是 N×N 群聊**：成员之间不直接对话、不互相持有对方完整消息流（真 peer-to-peer 群聊属 P6-3b swarm，本期不做）。“成员沟通确认”经 blackboard 间接完成（写 → `team.md` 索引 → 别人按需读）。成员**无状态、每轮重建**，单成员上下文由 §3.8 的四块有界重建，因此**讨论轮数增长不会导致单成员上下文爆炸**；全局增长由 §3.9 滚动压缩吸收。这是本设计“既像讨论又不爆”的根本原因。

### 4.5 长跑召回：三层降级 + 永久记录保证（第 3 层搜索检索留作可无损追加的扩展）
- **场景**：团队若长跑（极端假设数周、上千轮），用户回头问“最开始讨论的 X”，会不会被“遗忘”？**不会**——会被压缩的只有派生的 `digest.md`，源记录永久完整在盘/DB。
- **永久记录不可变量**：`rounds/*.md`、`bq_team_messages`、`bq_team_artifacts` 追加式永久完整、不压缩不删除（见 §3.1）。⇒ 早期内容只是不在工作概要里，从未真正消失。
- **召回三层优雅降级**：① `digest.md`（近期，免检索）→ ② `team.md` 索引 + `read_file`（可导航历史，数小时~数天）→ ③ **搜索检索**（Lucene/BM25 over 永久记录，深历史/数周）。
- **第 3 层本期不做，但保证可无损追加**（用户 2026-06-14 决定）：因源记录永久完整且结构化（`team_id/round/member/content/relative_path` 齐全），未来任何时候都能直接在其上建 Lucene 索引 + 有界检索注入，**无需数据迁移**，薄复用 P3-5a（Lucene/BM25 `CapabilitySearchService` 范式）+ P3-5（有界检索注入 + 引用 + 审计 `LongTermMemoryRetrievalService` 范式）。**真要实现前需先核实这两处的具体 API（不得自以为是）。**
- **与全局长期记忆的关系**：第 3 层检索的是**团队自己的永久记录**，不是全局长期记忆（P3-4 `~/.babiq/memories`，那是用户级跨会话提炼事实）。团队转录不倒进全局记忆；可选地把“值得长期留存的结论”蒸馏进全局记忆是另一件事，不在本设计。

---

## 5. 数据模型变更（最小化）

> 原则（CLAUDE.md §4）：新表/新字段必须同步 SQL 中文注释 + `bq_schema_comments` + Entity 注释 + `SchemaCommentsCoverageTest` 覆盖。

- **复用、不改**：`bq_team_members.tool_call_count/token_estimate/summary`（已存在，只是接真实值）；`bq_team_messages.member_summary` 类型（已合法，只是开始生产）。
- **唯一新表 `bq_team_artifacts`**（V21 migration）：团队记忆 md 的产物记录，类比 `bq_memory_artifacts`。
  - 字段：`id`、`team_id`、`artifact_id`、`artifact_type`(TEAM_INDEX/MEMBER_OUTPUT/RESULT)、`relative_path`、`sha256`、`token_estimate`、`round`、`member_name`、`content`(可选镜像正文)、`created_at`、`updated_at`。
  - 同步：SQL 中文 `--` 注释、`bq_schema_comments` 插入、Entity 中文字段注释、`SchemaCommentsCoverageTest` 覆盖。
- **新 migration 编号**：V21（V20 已是 P8 flow canvas，不得改写已发布 migration）。

---

## 6. 配置（新 `babiq.team.*`，对齐 `babiq.memory.*` 风格）

```yaml
babiq:
  team:
    enabled: true
    root-dir: ${user.home}/.babiq/teams          # 团队记忆工作区根目录
    supervisor-context-budget-tokens: 3000        # 喂 supervisor 的摘要时间线预算上限
    discussion-digest-budget-tokens: 2000         # 喂成员的滚动「讨论概要」预算上限, 超则复用 P3 压缩
    member-summary-max-chars: 600                 # 单条成员摘要卡截断长度
    max-rounds-ceiling: 12                        # maxRounds 硬上限(现状 param 文档写 12 但未 clamp,本期补 clamp)
```
> 顺带修一个现状小缺陷：`TeamCoordinationTool.toSpec` 现在 `maxRounds == null ? 4 : maxRounds` **未 clamp 到 12**，本期按配置 clamp。

---

## 7. 桌面 UI 与协议（2026-06-14 修订 v2：团队 = 主对话右侧可开合面板，无独立 tab/页面）

> **取代 p6-master §5，并修正本节 v1 的“团队一等 tab / 独立页面”误读。** 用户 2026-06-14 决定：**不要团队 nav tab、不要独立团队页面**；主 Agent 在主对话里直接协调多个团队；团队 = 停靠主对话**右侧的可开合面板**（对标用户提供的 Codex 侧边聊天）。

### 7.1 布局：主对话 + 右侧团队面板（同一窗口，两条独立消息流）
- **主对话（中/左）**：你 ↔ 主 Agent，保持干净。**不离开此对话、无团队 tab、无独立团队页。** 主 Agent 结束团队后回报的**最终聚合结果**进主对话；过程 chatter 不进。
- **团队面板（右侧，可开合）**：默认可收起为细 rail（显示“团队(N) ›”）；展开后停靠右侧，内含：
  - **多团队切换器**（顶部）：主 Agent 协调的多个团队（团队真协调 / 文档迁移 …）在此切换——这取代了“团队列表页”，团队不再是独立页面。
  - 选中团队的 **成员**（紧凑，点开配置）+ **执行时间线**（route / member_summary / direct_user）+ **团队自带 composer**。
- **两条独立消息流**（这是“主对话不乱、又能发团队”的根本办法）：主对话流（你 ↔ 主 Agent）+ 团队流（`bq_team_messages`，每团队一条）。团队 chatter 只进团队流 → 团队面板；主对话流只装主对话。

### 7.2 团队面板 composer（作用域会话）
- **面板自带输入框**（用户 2026-06-14 定，像 Codex 侧边聊天）：主对话输入框只管主对话，团队 composer 只发团队流——两个输入框职责清晰、互不干扰。
- 默认**对该团队 Leader/协调者**说话（switch 可单聊某成员）；发送写入 `bq_team_messages(direct_user)`，由 §3.6 轮次间注入在下一轮 drain 给目标。
- 成员配置（模型 / 角色【标签：预设+自定义】/ 职能【自由文本=task】/ 工具 / 模式 / 写入范围）：点面板里的成员卡，在面板内展开/弹层编辑。

### 7.3 协议
- 复用 `TeamItem`（成员状态，`toolCalls/tokens/summary` 填真实值）+ `TeamMessageItem`（时间线，含新生产的 `member_summary`）。
- `team/message/send` 扩展：`to_agent` 支持 `leader`（默认）或成员名；按 teamId 作用域。
- 读路径：`team/list`（面板顶部多团队切换器数据）+ `team/get`（选中团队的成员 + 时间线）；现有 `bq_teams` 查询够则薄封装。
- 主对话不被污染：沿用现有 `ChatReducer` 把 `ThreadItem.Team`/`TeamMessage` 从主聊天 `messages` `filterNot` 掉（live:251-279 / history:19-28）；它们渲染到**右侧团队面板**（带多团队切换 + 自带 composer 的可开合面板）。
- **可改目标**：复用现有 WorkUnit `update_goal` / `append_goal`，无需新协议方法；approve-once 只冻结结构（成员/工具/范围/沙箱），不冻结目标（见 §1.3）。
- **团队作用域会话流**：团队面板按 `teamId` 读 `bq_team_messages` 作为该团队的消息流；**不复用主对话 Thread、也不为团队新建 Thread 行**（团队流就是 `bq_team_messages`，已有）。
- **改动主要在桌面 UI**：后端复用现有团队基建（`bq_teams` / `bq_team_members` / `bq_team_messages` + §3 真协调改造 + §5 新表 `bq_team_artifacts`）；本 UI 修订不新增后端表，仅扩展 `team/message/send`（`to_agent=leader`）+ 薄读路径 `team/list` / `team/get`。

### 7.4 UI 不变量
无团队 tab / 无独立团队页 · 主对话独立干净（多团队不互扰）· 团队面板两条流隔离 · Leader 中枢（默认对 Leader）· approve-once 冻结 · 失败据实回报。

---

## 8. 横切不变量（回归不能破）

approve-once 团队级整体审批 + 写入范围校验（`TeamApprovalService.approveOnce/validateWriteScopes`）、沙箱模式快照不可自提升、白名单归一化（未知成员→FINISH）、`maxRounds` 上限、Spotlighting、WorkUnit `goalId` 启动闸门（裸跑拒绝）、成员复用 `SubAgentRuntimeFactory` 的沙箱/观测/spotlighting/eviction/limit/tokenUsage —— **全部保留**。

---

## 9. 测试与验收（延续 P8 硬纪律）

> 团队这块的历史教训正是“结构全绿但功能空心”。本期每个能力必须有**先红后绿的行为测试**，且“缺功能不能靠绿测试掩盖”。

必备行为测试（先红）：
1. `TeamCoordinationServiceTest`：supervisor `decide` 的输入时间线**包含 member_summary**（断言成员产出真的进了决策输入）。
2. 结果聚合：`run()` 返回的 summary **来自成员产出聚合**，不是常量串（断言含成员内容/引用）。
3. `member_summary` 生产 + 镜像：成员运行后 `bq_team_messages` 出现 `member_summary`，`~/.babiq/teams/<id>/rounds/*.md` 写入全文（用临时目录）。
4. 轮次间注入：循环前置一条 `direct_user` 消息 → 下一轮该成员指令**含该消息**。
5. 成员观测归属：成员跑完后 `bq_team_members.tool_call_count/token_estimate` **非 0**（按 `bq_tool_calls` 归属）。
6. `TeamMemoryWorkspaceTest`：目录/文件落盘（team.md 索引 / digest.md / rounds）、产物记录 sha/token、引用路径正确。
7. `TeamMemberContextAssemblerTest`：成员上下文**只含四块**（目标/任务/讨论概要/索引引用）、**不含任何 rounds 全文**；`TeamDiscussionDigestTest`：概要超预算时触发 P3 压缩且保持有界。
8. `SchemaCommentsCoverageTest`：`bq_team_artifacts` 字段中文注释无缺失。
9. 回归：白名单/maxRounds/approve-once/沙箱/WorkUnit 闸门用例不退化。

验收命令：
```powershell
cd backend; .\mvnw.cmd "-Dtest=TeamCoordinationServiceTest,TeamMemoryWorkspaceTest,TeamMemberContextAssemblerTest,TeamDiscussionDigestTest,TeamSummaryCardBuilderTest,TeamDirectMessageServiceTest,TeamApprovalServiceTest,SchemaCommentsCoverageTest,AgentLoopLineCountTest" test
cd backend; .\mvnw.cmd clean verify
cd desktop; .\gradlew.bat test --tests "*TeamSectionTest" --tests "*ThreadItemJsonTest"
cd desktop; .\gradlew.bat test --rerun-tasks
```
纪律：逐能力 `feat/fix(p6-3):` 提交;人工烟测(真实模型多成员协作 + 运行中喊话 + 失败态)无法在无头环境执行的逐项标“未执行+原因”,**不许谎报**。

---

## 10. 风险与未决（实现 plan 必须逐条回答）

1. **【头号风险】执行模型 spike（实现前必做）**：用真实 1.1.2.3 验证 Path A——团队循环里 `AgentTool.call(memberAgent)` 能否携带父 toolContext（emitter/cwd/observation/沙箱模式）、成员工具调用能否正确落 `bq_tool_calls` 且归属到成员、emitter 时效性是否 OK。通过则按 Path A 实现；有硬伤回退 Path B（StateGraph + `interruptAfter`）。
2. **上下文预算**：`supervisor-context-budget-tokens` 截断策略需在多轮长任务下验证不爆窗口、又不过度丢信息；必要时引入“最近 N 轮全摘要 + 更早仅 route”分层。
3. **并发与一致性**：本期是**单线程逐轮**（无真并发），SQLite 写入与 emitter 在循环内顺序进行，风险低；真并发留 P6-3b。
4. **喊话消费幂等**：drain 的“已消费点”推进要幂等，避免重复注入或漏注入（用 round/消费水位标记）。
5. **token 归属精度**：成员 token 现阶段为粗估（`token_estimate`，不用于计费），与项目“不记录价格”一致。
6. **失败态**：成员失败 / supervisor 决策异常时，团队状态、`result.md`、回传摘要要据实反映（复用现有 failed 收口 + 回退路由）。

---

## 11. 阶段定位与命名

- 本设计是 **P6-3 强化补做**（让既有团队真协调），不是 master 预留的 **P6-3b**（teammate 点对点真并发 swarm）。
- 落地计划建议命名 `docs/superpowers/plans/p6-3-team-collaboration/real-coordination-remediation-plan.md`（或新建 `p6-3a-*` 子目录），与既有 p6-3 文档同区，便于回看“空心版 → 真协调版”的演进。
- 实现顺序建议：①执行模型 spike → ②团队记忆工作区 + 产物表(V21) → ③成员产出捕获(摘要卡+md+member_summary) → ④成员读路径(team.md 索引 + 滚动讨论概要[复用 P3 压缩] + 上下文装配器) → ⑤supervisor 看得见结果(决策输入改造) → ⑥结果聚合回传 → ⑦轮次间喊话注入 → ⑧成员级观测 → ⑨桌面 UI → ⑩全量验证 + 文档同步。
