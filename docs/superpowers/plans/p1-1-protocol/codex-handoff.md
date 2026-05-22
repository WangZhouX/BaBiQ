# P1-1 协议层任务交接给 OpenAI Codex(本地 CLI 版)

> **使用方式**:把本文件**整篇**粘贴给 Codex,或让 Codex `read_file` 这个文件作为入口。
> 所有上下文、约束、代码质量要求都自包含。

---

## 0. 你是谁,要做什么

你是 OpenAI Codex,在 **Windows 11 + PowerShell** 环境运行,**cwd 必须是 `F:\wwwxxxx\BaBiQ\`**(若不是,**第一步立刻 `cd` 切过去**)。

**任务**:执行 **BaBiQ 项目 P1-1(协议层)** 详细 plan,落地 WebSocket + JSON-RPC 2.0 内层协议框架。

**详细 plan 路径**:
`F:\wwwxxxx\BaBiQ\docs\superpowers\plans\p1-1-protocol\plan.md`(2240 行 / 13 Task / 97 step)

**第一件事**:`read_file` plan.md,完整理解任务全貌后再动手。

> 完成 P1-1 后,会有 P1-2 任务,handoff 在 `p1-2-providers/codex-handoff.md`。

---

## 1. ⛔ 代码质量铁律(用户特别强调,违反就算未完成)

**这是用户最在意的一点。**

| # | 铁律 | 反面例子 | 正面要求 |
|:-:|---|---|---|
| 1 | **中文注释,且必须有** | `// process` / 没注释 | 每个类、每个公开方法、每个关键逻辑块都有中文注释,讲**为什么**这么写 |
| 2 | **逻辑清晰严谨** | "我先这样写凑合,后面再优化" | 边界条件、null / 空集合 / 异常路径显式处理,**不靠运气** |
| 3 | **条理清楚** | 一个方法干 5 件事 | 一个方法只做一件事;一个类只有一个职责(SRP) |
| 4 | **优雅易懂** | `int a = b > 0 ? f(c, d, e) : g(h, i, j, k);` | 新人 30 秒能看懂;复杂表达式必须拆分 + 命名变量 |
| 5 | **不写屎山** | (见下方禁忌清单) | (见下方正面清单) |

### 🚫 严格禁止(写屎山的典型特征)

- ❌ **if-else 嵌套超过 3 层** → 用 early return / guard clause 扁平化
- ❌ **方法超过 50 行** → 拆!没有借口
- ❌ **类超过 300 行** → 拆!职责不清
- ❌ **变量名 `a` / `x` / `tmp` / `data` / `result`** → 取有业务含义的名字
- ❌ **复制粘贴代码** → 抽方法,DRY 原则
- ❌ **magic number 不加注释** → 提取常量 + 注释来源
- ❌ **catch (Exception e) { /* ignore */ }** → 要么处理要么往上抛,绝不静默吞掉
- ❌ **方法里突然写 `System.out.println`** → 用 Logger
- ❌ **JavaDoc 全是 `@param x x`** → 写真实信息,或者干脆不写
- ❌ **多个 boolean 参数** → 用 enum 或 builder

### ✅ 必须做到

- ✅ **每个 record / class 顶部**:中文 JavaDoc 说明"是什么 + 为什么存在 + 谁会用它"
- ✅ **每个 public 方法**:中文 JavaDoc 说明"做什么 + 参数含义 + 返回什么 + 何时抛异常"
- ✅ **每个非显然的代码块**:中文行内注释说明"为什么这么写"(不是"做什么")
- ✅ **常量**:`private static final` + 中文注释说明来源(`// JSON-RPC 2.0 规范保留错误码`)
- ✅ **异常**:抛业务异常 + 中文 message + 包含上下文(`"threadId=" + threadId + " 不存在"`)
- ✅ **方法签名优雅**:参数 ≤ 5 个,> 5 个用 builder 或 record
- ✅ **测试**:每个测试方法名描述场景(`thread_create_returns_unique_thread_id`),用 AAA(Arrange-Act-Assert)结构

### 注释示例(对照学习)

```java
/**
 * Turn 状态机 — 维护一轮对话的生命周期状态转移。
 *
 * <p>背景:每个 Turn 对应用户一次提问 + Agent 一次完整响应,
 * 内部可能跨多次模型调用 + 工具调用。状态机帮助协议层准确
 * 知道当前应该接收什么消息、能不能 cancel、何时发 turn/completed。
 * 详见 ARCHITECTURE §5.2 + 决策 D3。</p>
 *
 * <p>6 个状态:3 个非终态(CREATED / RUNNING / WAITING_APPROVAL)
 * + 3 个终态(COMPLETED / FAILED / CANCELED)。</p>
 */
public enum TurnStatus {
    /** 刚创建,还未开始处理。turn/start 调用后会立刻转为 RUNNING。 */
    CREATED,

    /** Agent Loop 主循环执行中(模型调用 / 工具调用 / 流式输出)。 */
    RUNNING,

    /** 工具调用前需要用户审批,等待 approval/respond。 */
    WAITING_APPROVAL,

    /** 正常完成。Agent Loop 自然退出,模型没再要求工具调用。 */
    COMPLETED,

    /** 执行失败。模型报错、工具异常或超出 max-iterations。 */
    FAILED,

    /** 用户主动取消(turn/cancel)或中断(turn/interrupt)。 */
    CANCELED;

    /**
     * 检查从当前状态能否合法转移到目标状态。
     *
     * @param target 目标状态
     * @return true 表示状态转移合法;false 表示该转移在状态机中未定义
     */
    public boolean canTransitionTo(TurnStatus target) {
        // 终态不能再变成任何状态,这是状态机的核心约束
        if (this.isTerminal()) return false;

        // 按 ARCHITECTURE §5.2 状态机转移表:
        // CREATED → RUNNING / CANCELED
        // RUNNING → WAITING_APPROVAL / COMPLETED / FAILED / CANCELED
        // WAITING_APPROVAL → RUNNING / CANCELED / FAILED
        return switch (this) {
            case CREATED          -> target == RUNNING || target == CANCELED;
            case RUNNING          -> target != CREATED;       // 除回到初始外都合法
            case WAITING_APPROVAL -> target == RUNNING || target == CANCELED || target == FAILED;
            default -> false;
        };
    }

    /** 终态判断:COMPLETED / FAILED / CANCELED 不再迁移。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }
}
```

—— 这是合格代码。每行存在的理由都清楚。

---

## 2. 项目背景(60 秒读完)

- **项目**:BaBiQ — 对标 OpenAI Codex 桌面端的 AI Agent 学习项目
- **架构**:Kotlin Compose Desktop + Spring Boot Backend,**内层 WebSocket + JSON-RPC,外层 A2A**(详见 ARCHITECTURE §2.1)
- **当前状态**:P1-0 Monorepo 骨架已完成(tag `p1-0-skeleton`)
- **P1-1 你要做**:**内层协议层** — WebSocket 端点 + JSON-RPC 2.0 框架 + Thread/Turn/Item 数据模型 + 7 个 method handler(2 个真实 + 5 个 mock)+ mock agentMessage(不接真模型)
- **完整架构上下文**(可选深入读):
  - `docs/ARCHITECTURE.md`(2754 行,24 章)
  - `docs/superpowers/plans/2026-05-21-p1-master.md`(34 条全局决策)

---

## 3. 硬约束

| 约束 | 值 | 备注 |
|---|---|---|
| **JDK** | Java 21 LTS | P1-0 已锁定 |
| **Spring Boot** | 3.5.14 | 不要升 4.0 |
| **后端端口** | 8080 | 协议依赖 |
| **shell** | PowerShell | Windows 11 |
| **commit message** | **中文** | 用户 2026-05-21 起的规则;prefix(feat/chore/refactor/test/docs)保持英文 |
| **package** | `com.wzx.babiq.server.*` | 不要改 |
| **本阶段不引入** | `spring-ai-alibaba-*` / Spring AI 任何依赖 | 那是 P1-2 的事 |

### P1-1 必须遵守的全局决策

- **D1**:内层协议**自己写** WebSocket + JSON-RPC 2.0(P1-1 核心)
- **D2**:用 `spring-boot-starter-websocket` 原生
- **D3 + D22**:Item 用 **Java sealed interface + records + `@JsonProperty(required=true)`**
- **D17**:Logback JSON 结构化输出
- **D8** ⚠️:**已 deprecated**,审批不写 CompletableFuture 状态机(P1-3 用 `HumanInTheLoopHook`,P1-1 阶段 `approval/respond` handler 只做 mock 返回 ok)

---

## 4. P1-1 任务清单总览(详细 step 在 plan.md)

按 plan.md Task 顺序执行:

| # | Task | 关键产出 |
|:-:|---|---|
| Pre-flight | 检查 cwd / git / Java / Maven | 不修改文件 |
| 1 | 加 WebSocket 依赖到 pom | spring-boot-starter-websocket |
| 2 | WebSocket 端点 + Handler 骨架 | `/ws/agent` 能收发文本 |
| 3 | Turn 状态机(6 态)+ 全态测试 | TurnStatus.canTransitionTo() |
| 4 | 12 种 Item record(sealed)+ Jackson 多态序列化 | UserMessage / AgentMessage 等 6 个功能 + 6 个 placeholder |
| 5 | JsonRpcMessage sealed family + Dispatcher 路由 | -32601 / -32602 / -32000 错误码 |
| 6 | ThreadCreateHandler + ConversationService | 真实实现 |
| 7 | ItemEmitter 流式发射 | session 并发安全 |
| 8 | TurnStartHandler + mock agentMessage 流 | 异步 CompletableFuture |
| 9 | TurnCancel / TurnInterrupt(D23 新增) | 协议占位 |
| 10 | ApprovalRespond / Providers Mock handler | 占位返回 ok |
| 11 | 集成测试(StandardWebSocketClient) | 端到端 turn/start → turn/completed |
| 12 | wscat 人工烟测 | 验收 M1 |
| 13 | 文档同步 + 最终 commit | 中文 commit,不打 tag 不 push |

---

## 5. ⚠️ Plan 内可能撞到的小坑

### A. `awaitility` 测试依赖
plan 已经把"加 awaitility 依赖"提升为正式 **Step 8.0**,务必按顺序执行。

### B. `Thread.sleep` 命名冲突
项目里有 `com.wzx.babiq.server.conversation.Thread` record,与 `java.lang.Thread` 在 IT 测试上下文中可能冲突。plan 已经把所有 `Thread.sleep(...)` 改为显式 `java.lang.Thread.sleep(...)`,**保持原样不要"优化"**。

### C. 风险已记录的项
plan 里有"风险与待定项"表,其中明确说 `runMockStream` 用 `CompletableFuture.runAsync` 共用 ForkJoinPool,**P1-2 接真模型时必须替换**。你 P1-1 阶段**不要**替换,保持 plan 一致。

---

## 6. 工作流约定

1. **先读后做**:每个 Task 开始前,`read_file` plan.md 中该 Task 完整段落
2. **每步独立 commit**(中文 message,prefix 英文):`feat(p1-1): 实现 ThreadCreateHandler` 这种
3. **遇到失败先看 plan**:不要瞎改,plan 里有大量陷阱预警
4. **不要超出范围**:plan 没写的事不要做(例如不要顺手加 Spotlighting,那是 P1-3b)
5. **代码质量铁律(§1)一直生效**,写每一行代码都对照
6. **审批策略**:你默认 `on-request`,以下命令必须征求用户同意:
   - `git commit`(**不要 `git tag`、不要 `git push`**;由用户自行决定 push 时机)
   - `mvnw clean package` / `spring-boot:run` / `mvnw test`
   - `Remove-Item` / `mkdir`(plan 没要求的目录操作)

---

## 7. 完成后请给用户的汇报

```
## ✅ P1-1 完成报告

### Done Criteria(按 plan 末尾清单)
[逐条勾选,失败的标 ❌ 并说明]

### git 历史
[git log --oneline p1-0-skeleton..HEAD]

### 关键产物
- backend jar: backend/target/babiq-server-0.0.1-SNAPSHOT.jar(✅ 编译通过)
- 单测: N tests, 0 failures
- 集成测: M tests, 0 failures
- wscat 烟测: ✅ thread/create + turn/start + mock turn/completed 全流程过
- **未 push,未打 tag**(等用户自行决定)

### 代码质量自检(对照 §1 铁律)
- [ ] 所有 public 方法都有中文 JavaDoc
- [ ] 所有 class 都有顶部说明
- [ ] 无方法超过 50 行
- [ ] 无类超过 300 行
- [ ] 无 if-else 嵌套超 3 层
- [ ] 无变量名 a/x/tmp/data/result(除非测试 fixture)
- [ ] 无 catch + 静默吞异常
- [ ] commit message 全部中文

### 遇到的偏差或问题
[列出执行中和 plan 不一致的地方;若无写 "无"]

### 下一步建议
P1-1 完成。可让用户继续 P1-2(handoff 在 p1-2-providers/codex-handoff.md)
```

---

## 8. 应急情况

| 情况 | 处理 |
|---|---|
| 连续 3 次同一步骤失败 | **停下**,把当前状态 + 错误信息汇报给用户。**不要**破坏性回滚 |
| Jackson 多态反序列化失败 | 检查 `@JsonTypeInfo(use=NAME, include=EXISTING_PROPERTY, property="type", visible=true)` 是否完整 |
| WebSocket 测试 IllegalStateException(消息丢失) | 检查 ItemEmitter / Handler 是否对 session 加了 `synchronized` |
| 测试不稳(flaky) | 调 awaitility 超时上限,**绝不** `@Disabled` 跳过 |

---

## 9. 一个最重要的提醒

**你写的代码是用户拿来学习的**,不是为了让 plan 跑过就完事。
**质量比速度重要**。
慢一点没关系,屎山不可原谅。

如果某个步骤 plan 写得不够清楚,**宁可花 5 分钟想清楚再写**,也不要凑合塞代码。
如果有更优雅的写法,**在保持 plan 语义不变的前提下**可以采用,但要在 commit message 注明 "对 plan 的优化:XXX"。

---

**好了,开始吧。第一步:`read_file` P1-1 plan.md,然后从 Pre-flight 走起。**
