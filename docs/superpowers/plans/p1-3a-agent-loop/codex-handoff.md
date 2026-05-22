# P1-3a Agent Loop 任务交接给 OpenAI Codex(本地 CLI 版)

> **使用方式**：把本文件**整篇**粘贴给 Codex，或让 Codex `read_file` 这个文件作为入口。
> 所有上下文、约束、代码质量要求都自包含。
>
> **前置**：必须先完成 P1-2（`p1-2-providers/codex-handoff.md`），tag `p1-2-providers` 已打，工作树 clean。

---

## 0. 你是谁，要做什么

你是 OpenAI Codex，在 **Windows 11 + PowerShell** 环境运行，**cwd 必须是 `F:\wwwxxxx\BaBiQ\`**。

**任务**：执行 **BaBiQ 项目 P1-3a（Agent Loop 内核）** 详细 plan。这是整个 P1 阶段技术含量最高的一块，落地以下能力：

- Spring AI Alibaba **ReactAgent** 接入（不是 bare ChatClient，由框架内部驱动 ReAct 循环）
- 6 个工具：`read_file` / `write_file` / `list_dir` / `grep` / `exec_shell` / `apply_patch`
- 沙箱三档（READ_ONLY / WORKSPACE_WRITE / DANGER_FULL_ACCESS）+ `PathGuard` 防符号链接
- SAA `ToolInterceptor` 统一沙箱拦截 + 内置 `LargeResultEvictionInterceptor` 截断大输出
- SAA 内置 `HumanInTheLoopHook` + 原生 `interrupt/resume` 模型实现审批（**不阻塞 Agent 线程**）
- SAA 内置 `ModelCallLimitHook` 防 ReAct 死循环
- 自写 `BaBiQTokenUsageHook`（AFTER_MODEL token 累计）
- 协议层 3 个 handler 切真实（`TurnStartHandler` / `TurnInterruptHandler` / `ApprovalRespondHandler`）
- 端到端测试跑通 mock ChatModel + tool_call 完整 ReAct（**严禁 @Disabled**）

**详细 plan 路径**：
`F:\wwwxxxx\BaBiQ\docs\superpowers\plans\p1-3a-agent-loop\plan.md`（3000+ 行 / 14 Task / 85+ step）

**第一件事**：`read_file` plan.md，**完整理解任务全貌后再动手**。plan 经过 3 轮内部 critic + 2 轮外部 javap 实地核对，所有 SAA 1.1.2.3 API 已对齐，不要凭直觉改契约。

---

## 1. ⛔ 代码质量铁律（用户特别强调，违反就算未完成）

**与 P1-2 一致**，本节复述，全程生效。

| # | 铁律 | 反面例子 | 正面要求 |
|:-:|---|---|---|
| 1 | **中文注释，且必须有** | `// process` / 没注释 | 每个类、每个公开方法、每个关键逻辑块都有中文注释，讲**为什么**这么写 |
| 2 | **逻辑清晰严谨** | "我先这样写凑合，后面再优化" | 边界条件、null / 空集合 / 异常路径显式处理 |
| 3 | **条理清楚** | 一个方法干 5 件事 | 一个方法只做一件事；一个类只有一个职责（SRP） |
| 4 | **优雅易懂** | `int a = b > 0 ? f(c, d, e) : g(h, i, j, k);` | 新人 30 秒能看懂；复杂表达式必须拆分 + 命名变量 |
| 5 | **不写屎山** | （见下方禁忌清单） | （见下方正面清单） |

### 🚫 严格禁止

- ❌ **Agent Loop 主流程方法体超过 50 行**（D21，M3a 硬验收项；plan 已给出 ≤50 行实现）
- ❌ if-else 嵌套超过 3 层 → 用 early return / guard clause 扁平化
- ❌ 任何方法 > 50 行 / 类 > 300 行
- ❌ `CompletableFuture<ApprovalDecision>` 手写审批状态机（D8 已废弃，违反等于推翻 D23）
- ❌ `path.startsWith(workspaceRoot)` 这种"裸字符串"路径校验（D31，符号链接可绕）
- ❌ `Runtime.exec(...)` 不带 timeout / 资源限制
- ❌ `catch (Exception e) {}` 静默吞异常
- ❌ Hook 写进 `agent/` 包（必须在 `hook/` 包，与 `agent/` 平行）
- ❌ 把"工具输出截断"写进 `ToolRegistry` 内部（必须在 `LargeResultEvictionInterceptor`）
- ❌ 在 P1-3a 引入 Multi-Agent / Spotlighting / TurnSummaryItem / RAG / VectorStore（那是 P2 / P1-3b 才做）
- ❌ 变量名 `a` / `x` / `tmp` / `data` / `result` → 取业务含义的名字
- ❌ catch (Exception e) { /* ignore */ } → 要么处理要么往上抛
- ❌ 多个 boolean 参数 → 用 enum 或 builder

### ✅ 必须做到

- ✅ 每个 record / class 顶部：中文 JavaDoc 说明"是什么 + 为什么存在 + 谁会用它 + 关联决策编号"
- ✅ 每个 public 方法：中文 JavaDoc 说明"做什么 + 参数 / 返回 / 异常"
- ✅ Hook / Interceptor 单一职责：一个 Hook 只做一件事；**绝不**在一个 Hook 里同时管截断和 token 统计
- ✅ `PathGuard.toRealPath()` + 白名单前缀比较，**所有路径校验单测必须覆盖符号链接攻击**
- ✅ 每个工具：**单元测试 + 边界用例（空文件 / 不存在 / 二进制 / 超大）**
- ✅ commit message **中文**（prefix 英文）：`feat(p1-3a): 实现 BaBiQSandboxInterceptor + 单测`
- ✅ ⚠️ **每一处涉及 D21 / D23 / D31 的关键代码，行内中文注释标注决策编号**

---

## 2. 项目背景（60 秒读完）

- **项目**：BaBiQ — 对标 OpenAI Codex 桌面端的 AI Agent 学习项目
- **当前状态**：P1-0 / P1-1 / P1-2 已完成
- **P1-3a 你要做**：接入 SAA `ReactAgent`，实现 Agent Loop 内核 + 6 工具 + 沙箱 + HITL
- **完整架构上下文**：`docs/ARCHITECTURE.md` 重点读 §5 / §6 / §14.6 / §15 / §17 / §21

---

## 3. 硬约束

| 约束 | 值 | 备注 |
|---|---|---|
| **JDK** | Java 21 LTS | P1-0 已锁定 |
| **Spring Boot** | 3.5.14 | 不要升 |
| **Spring AI** | **1.1.6**（v4 修订升级，BOM） | Task 1 Step 1.0 改 pom 版本属性 |
| **Spring AI Alibaba** | **1.1.2.3**（v4 修订升级，BOM） | 同上 |
| **新增依赖** | `spring-ai-alibaba-agent-framework` | 子坐标由 BOM 托管 |
| **后端端口** | 8080 | |
| **shell** | PowerShell | Windows 11 |
| **commit message** | **中文**（prefix 英文） | |
| **package** | `com.wzx.babiq.server.*` | 不要改 |

### P1-3a 必须遵守的全局决策（D 决策）

- **D19**：工具输出截断走 SAA 内置 `LargeResultEvictionInterceptor`（v4 改，不再自写 Hook）
- **D21**：`AgentLoop` 主流程方法 ≤ 50 行；横切关注点全部走 Hook / Interceptor
- **D22**：所有 Item / ToolResult / ApprovalRequestPayload 用 record，字段 `@JsonProperty(required=true)`
- **D23**：审批走 SAA `HumanInTheLoopHook` + `MemorySaver` + `RunnableConfig.addHumanFeedback.resume` + 手动 `ToolFeedback.Builder`（v4 改，**严禁** HITLHelper / compiledGraph.resume 这些不存在的 API）
- **D24**：只对写类工具触发审批（`write_file` / `exec_shell` / `apply_patch`）；读类永远放行
- **D26**：本阶段不引入 Multi-Agent，单 ReactAgent
- **D31**：沙箱三档 + `PathGuard.toRealPath()` 防符号链接

---

## 4. P1-3a 任务清单总览（详细 step 在 plan.md）

| # | Task | 关键产出 |
|:-:|---|---|
| Pre-flight | 确认 P1-2 完成 / 工作树 clean / Java / Maven / API key | 不修改文件 |
| 1 | pom.xml 升 BOM（SAA 1.1.2.3 + Spring AI 1.1.6）+ 加 agent-framework + AgentFrameworkSmokeTest | 阻塞门 |
| 2 | `SandboxMode` enum + `SandboxPolicy` record + `SandboxViolationException` | |
| 3 | `PathGuard.java`（`toRealPath()` + `relativize()` 算法）+ 测试（含符号链接） | D31 核心 |
| 4 | `Tool` marker + `ToolRegistry`（含 `allCallbacks()` ToolCallback[] 生成）+ `ToolResult` record | |
| 5 | 6 工具实现（**工具内部不做沙箱判断**，纯 IO）+ ExecShell **异步 gobbler 修死锁** | D2 复用 SAA + D4 死锁修 |
| 6 | `BaBiQSandboxInterceptor extends ToolInterceptor` + `interceptToolCall(ToolCallRequest, ToolCallHandler)` 实现 | D31 + D2 拦截器 |
| 7 | **整个删除**（改用 SAA 内置 `ModelCallLimitHook`，无新文件） | D4 删自实现 |
| 8 | `BaBiQTokenUsageHook extends ModelHook + @HookPositions(AFTER_MODEL)` + 测试 | 保留自写 |
| 9 | `PendingApprovals` + `ApprovalRequestPayload` + **Step 9.4 给 ItemEmitter 加 `emitApprovalRequest(...)`** | D23 + D6 SAA 原生 HITL |
| 10 | `AgentLoopProperties` + `ReActStrategy`（装配 `ReactAgent.builder()`）+ `AgentLoop`（用 `agent.invokeAndGetOutput()` + `instanceof InterruptionMetadata`）+ AgentLoopLineCountTest | D21 + D7 核心 |
| 11 | `TurnExecutor`（`submit` + `submitResume`）+ 3 个 handler 切真实（`JsonNode params` 签名 + `findTurn(turnId)` + 手动 `ToolFeedback.Builder` + `RunnableConfig.addHumanFeedback().resume()`） | D8 续跑 |
| 12 | `SandboxModeRegressionTest` 走 `BaBiQSandboxInterceptor.checkOrReject()` 路径 | D31 回归 |
| 13 | wscat-smoke.md + **EndToEndIT 跑通 mock ChatModel + tool_call 完整 ReAct（严禁 @Disabled）** | M3 硬验收 |
| 14 | 文档同步 + 收尾 commit | tag 由用户决定 |

---

## 5. ⚠️ Plan 内可能撞到的关键坑（5 个）

### A. SAA 1.1.2.3 API 名漂移（v4 已用 javap 实地核对）

plan 中所有 SAA API 都基于 1.1.2.3 jar 的真实 javap 结果。若实际签名与 plan 略有出入：
- **不要硬猜**，先 `javap -public <ClassFile>` 验证
- 真实包参考：
  - `com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor` / `ToolCallRequest` / `ToolCallResponse` / `ToolCallHandler`
  - `com.alibaba.cloud.ai.graph.agent.extension.interceptor.LargeResultEvictionInterceptor`（注意 `extension.interceptor`）
  - `com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook`
  - `com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook` + `ToolConfig`
  - `com.alibaba.cloud.ai.graph.action.InterruptionMetadata` + `ToolFeedback` + `FeedbackResult`
  - `com.alibaba.cloud.ai.graph.RunnableConfig.Builder.addHumanFeedback(InterruptionMetadata).resume()`
  - `com.alibaba.cloud.ai.graph.NodeOutput`（**没有** `interruption()` 方法，用 `instanceof InterruptionMetadata`）

### B. starter auto-config 冲突（沿用 P1-2 经验）

`application.yml` 已有 `spring.ai.dashscope.chat.enabled: false` + `spring.ai.openai.chat.enabled: false`。**不要删！** 否则 starter 会自动创建 ChatModel Bean 与 `ChatClientFactory` 冲突。

### C. `ItemEmitter.emitApprovalRequest(...)` 必须在 Task 9.4 加（不能拖到 Task 11）

`ReActStrategy.emitApprovalRequests()` 会调 `emitter.emitApprovalRequest(payload)`。如果把该方法拖到 Task 11A 再补，Task 10C 编译 `AgentLoop + ReActStrategy` 时会失败。
plan 已明确把该 Step 放在 Task 9.4，必须按顺序执行。

### D. 工具内部不做沙箱判断（v4 决策）

Task 5 的工具实现（包括 plan 给出的代码示例）必须**删掉**所有 `guard.checkRead/Write(...)` / `policy.isReadOnly()` 调用，工具仅做纯 IO。沙箱判断由 Task 6 `BaBiQSandboxInterceptor` 统一拦。

### E. EndToEndIT 严禁 @Disabled

Task 13 的 EndToEndIT 必须跑通：mock ChatModel 第一次返回 tool_call → ReactAgent 自动执行 → mock 第二次返回 final text → 推 agentMessage + turn/completed。
**任何 `@Disabled` 占位都不算完成 P1-3a。**

---

## 6. 工作流约定

1. **先读后做**：每个 Task 开始前 `read_file` 对应段落
2. **每步独立 commit**（中文 message，prefix 英文）：`feat(p1-3a): 实现 BaBiQSandboxInterceptor + 单测`
3. **遇到失败先看 plan**：plan 里有大量陷阱预警，特别是 SAA API 漂移
4. **不要超出范围**：不要顺手加 RAG / VectorStore / SummarizationHook / TurnSummary（那是 P2 / P1-3b）
5. **代码质量铁律（§1）一直生效**
6. **审批策略**：你默认 `on-request`，以下命令必须征求用户同意：
   - `git commit`（**不要 `git tag`、不要 `git push`**；由用户自行决定 push / tag 时机）
   - `mvnw clean package` / `spring-boot:run` / `mvnw test`
   - 任何 `Remove-Item` / `mkdir`（plan 没要求的）

---

## 7. 完成后请给用户的汇报

```
## ✅ P1-3a 完成报告

### Done Criteria（按 plan 末尾清单）
[逐条勾选，失败的标 ❌ 并说明]

### git 历史
[git log --oneline p1-2-providers..HEAD]

### 关键产物
- backend jar: backend/target/babiq-server-0.0.1-SNAPSHOT.jar
- 单测: N tests, 0 failures
  - 沙箱/路径单测 (PathGuardTest / SandboxPolicyTest / BaBiQSandboxInterceptorTest)
  - 6 工具单测
  - BaBiQTokenUsageHookTest / PendingApprovalsTest
  - AgentLoopTest（mock invokeAndGetOutput）+ AgentLoopLineCountTest
  - SandboxModeRegressionTest（5 个，含符号链接）
- 集成测: M tests, 0 failures
  - AgentFrameworkSmokeTest（SAA 类可加载）
  - EndToEndIT（mock ChatModel + tool_call 完整 ReAct 跑通）
  - 3 个 handler test 更新通过
- REST/WebSocket 烟测: ✅
- **未 push，未打 tag**（等用户自行决定）

### 代码质量自检（对照 §1 铁律）
- [ ] 所有 public 方法都有中文 JavaDoc
- [ ] 所有 class 都有顶部说明
- [ ] AgentLoop 主流程 ≤ 50 行（AgentLoopLineCountTest 已硬验）
- [ ] 无方法超过 50 行 / 无类超过 300 行
- [ ] 无 if-else 嵌套超 3 层
- [ ] 无变量名 a/x/tmp/data/result（除非测试 fixture）
- [ ] 无 catch + 静默吞异常
- [ ] 沙箱判断不在工具内，全在 BaBiQSandboxInterceptor（v4 决策）
- [ ] HITL 走 SAA 原生 interrupt/resume，无 ApprovalChannel / SynchronousQueue / HITLHelper / compiledGraph.resume
- [ ] commit message 全部中文

### 遇到的偏差或问题
[列出执行中和 plan 不一致的地方，尤其是 §5 的 5 个坑里实际撞到哪几个；若无写 "无"]

### 下一步建议
P1-3a 完成。下个阶段是 P1-3b（成本估算 + TurnSummaryItem）。plan 待编写。
```

---

## 8. 应急情况

| 情况 | 处理 |
|---|---|
| 连续 3 次同一步骤失败 | **停下**，把状态 + 错误汇报用户。**不要**破坏性回滚 |
| SAA API 名变了 | 用 `javap` 查 jar 内真实签名，不要硬猜 |
| `AgentFrameworkSmokeTest` 类加载失败 | 用 `dependency:tree` 验证 artifact 引入；若 1.1.2.3 内部包名变，按 javap 实际为准回修测试 |
| `ItemEmitter.emitApprovalRequest` 在 Task 9.4 漏补 | Task 10C 编译会立即失败提醒，回 Task 9.4 补即可 |
| 工具内部 `guard.checkXxx` 删后单测红 | 单测同步更新（沙箱测试改走 `BaBiQSandboxInterceptor`） |
| EndToEndIT mock ChatModel 不返回 tool_call | 先确认 SAA `AssistantMessage.ToolCall` 构造方式（用 Spring AI 1.1.6 javap）；若 mock API 漂移，按真实签名调整 |
| auto-config 冲突 | 检查 §5.B yml 是否正确 |
| 没有 `AI_DASHSCOPE_API_KEY` 环境变量 | plan 用 `${VAR:}` 占位；单测和 EndToEndIT 都用 mock，不需要真 key |
| 测试不稳（flaky） | 不要 `@Disabled`，汇报用户；EndToEndIT 严禁 `@Disabled` |

---

## 9. 一个最重要的提醒

**你写的代码是用户拿来学习的**，不是为了让 plan 跑过就完事。
**质量比速度重要**。
慢一点没关系，屎山不可原谅。

如果某个步骤 plan 写得不够清楚，**宁可花 5 分钟想清楚再写**，也不要凑合塞代码。
如果有更优雅的写法，**在保持 plan 语义不变的前提下**可以采用，但要在 commit message 注明"对 plan 的优化：XXX"。

**特别注意 SAA API 漂移**：本 plan v4 已用 javap 实地核对 1.1.2.3 jar，绝大部分 API 都是真实存在的。但若你撞到任一编译错误，**第一反应是用 javap 验证真实签名**，而不是按文档直觉猜测。

---

**好了，开始吧。第一步：`read_file` plan.md，然后从 Pre-flight 走起。**
