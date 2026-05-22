# P1-2 Provider 层任务交接给 OpenAI Codex(本地 CLI 版)

> **使用方式**:把本文件**整篇**粘贴给 Codex,或让 Codex `read_file` 这个文件作为入口。
> 所有上下文、约束、代码质量要求都自包含。
>
> **前置**:必须先完成 P1-1(`p1-1-protocol/codex-handoff.md`),tag `p1-1-protocol` 已打。

---

## 0. 你是谁,要做什么

你是 OpenAI Codex,在 **Windows 11 + PowerShell** 环境运行,**cwd 必须是 `F:\wwwxxxx\BaBiQ\`**。

**任务**:执行 **BaBiQ 项目 P1-2(Provider 层 + Memory + ModelMetadata)** 详细 plan。落地:
- 多 Provider 配置体系(DashScope / DeepSeek / OneAPI 中转 / Ollama)
- ChatClientFactory 双工厂 + 缓存
- ModelMetadata 内置主流模型 context window 映射
- MessageWindowChatMemory(20) + MessageChatMemoryAdvisor 自动挂
- 跨轮记忆生效(同 threadId 第 2 轮记得第 1 轮)

**详细 plan 路径**:
`F:\wwwxxxx\BaBiQ\docs\superpowers\plans\p1-2-providers\plan.md`(1900+ 行 / 10 Task / 53+ step)

**第一件事**:`read_file` plan.md,完整理解任务全貌后再动手。

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

### 🚫 严格禁止

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
- ✅ **常量**:`private static final` + 中文注释说明来源(`// D18 决策:短期记忆默认窗口大小`)
- ✅ **异常**:抛业务异常 + 中文 message + 包含上下文(`"providerId=" + providerId + " 未配置"`)
- ✅ **方法签名优雅**:参数 ≤ 5 个,> 5 个用 builder 或 record
- ✅ **测试**:每个测试方法名描述场景(`second_turn_includes_first_turn_history`),用 AAA 结构

### 注释示例(对照学习)

```java
/**
 * 模型元数据 — 集中维护各家模型的 context window 大小映射。
 *
 * <p>背景:Spring AI / Spring AI Alibaba 没有内置 "查模型上下文窗口" 的 API,
 * 业界主流是维护一份元数据表。此类按 D20 决策提供该能力,
 * 同时允许用户在 application.yml 显式 override。
 * 详见 ARCHITECTURE §14.4。</p>
 *
 * <p>使用方:Provider 解析、SummarizationHook 阈值计算(P3)、
 * 桌面端状态栏显示等所有"需要知道当前模型有多少 token 可用"的场景。</p>
 */
public final class ModelMetadata {

    /** 未知模型的默认上下文窗口,保守值 32K。 */
    public static final int DEFAULT_CONTEXT_WINDOW = 32_768;

    /**
     * 内置主流模型上下文窗口映射(2026-05 数据)。
     * 注意:模型名小写匹配,与 application.yml 配置一致。
     */
    private static final Map<String, Integer> KNOWN_CONTEXT_WINDOWS = Map.ofEntries(
        // 阿里通义系列
        entry("qwen-plus",     1_000_000),   // 2026 升级到 1M
        entry("qwen-turbo",      128_000),
        entry("qwen-max",        262_144),
        entry("qwq-plus",        131_072),   // 推理专用
        // DeepSeek
        entry("deepseek-chat",   128_000),
        entry("deepseek-v4",   1_000_000),
        // OpenAI 系列
        entry("gpt-4o",          128_000),
        entry("gpt-5",         1_000_000),
        entry("o1",              200_000),
        // Anthropic
        entry("claude-opus-4-7", 200_000),
        // 本地 Ollama
        entry("llama3:8b",         8_192),
        entry("qwen2.5-coder:7b", 32_768)
    );

    /**
     * 查询给定模型的上下文窗口大小。
     *
     * @param model 模型标识(application.yml 中 provider.model 字段)
     * @return     该模型的 context window token 数;
     *             模型未知时返回 {@link #DEFAULT_CONTEXT_WINDOW}
     */
    public static int contextWindowOf(String model) {
        if (model == null) return DEFAULT_CONTEXT_WINDOW;
        return KNOWN_CONTEXT_WINDOWS.getOrDefault(model.toLowerCase(), DEFAULT_CONTEXT_WINDOW);
    }

    private ModelMetadata() { /* 工具类,禁止实例化 */ }
}
```

—— 这是合格代码。每行存在的理由都清楚。

---

## 2. 项目背景(60 秒读完)

- **项目**:BaBiQ — 对标 OpenAI Codex 桌面端的 AI Agent 学习项目
- **当前状态**:P1-0 + P1-1 已完成
- **P1-2 你要做**:**接入 Spring AI Alibaba**,搞定多 Provider + 短期记忆 + 模型元数据
- **完整架构上下文**:`docs/ARCHITECTURE.md` 重点读 §8(多 Provider)+ §14.4(ModelMetadata)+ §14.7(P1 短期记忆)

---

## 3. 硬约束

| 约束 | 值 | 备注 |
|---|---|---|
| **JDK** | Java 21 LTS | P1-0 已锁定 |
| **Spring Boot** | 3.5.14 | 不要升 |
| **Spring AI Alibaba** | starter-dashscope **1.1.2.1** + agent-framework **1.1.2.0** | 本阶段引入 |
| **Spring AI** | 1.1.x 与 SAA 配套 | |
| **后端端口** | 8080 | |
| **shell** | PowerShell | Windows 11 |
| **commit message** | **中文**(prefix 英文) | |
| **package** | `com.wzx.babiq.server.*` | 不要改 |

### P1-2 必须遵守的全局决策

- **D4**:Provider 配置用 `@ConfigurationProperties("babiq")` + List
- **D5**:OpenAI Compatible 用 `OpenAiApi.mutate().baseUrl()` 派生
- **D18**:`MessageWindowChatMemory(20)` + `MessageChatMemoryAdvisor`,挂在每个 ChatClient
- **D20**:`ModelMetadata.contextWindowOf()` 内置主流模型映射,yml 可 override

---

## 4. P1-2 任务清单总览(详细 step 在 plan.md)

| # | Task | 关键产出 |
|:-:|---|---|
| Pre-flight | 检查 cwd / git / Java / Maven | 不修改文件 |
| 1 | pom.xml 加 SAA + Spring AI 依赖 | starter-dashscope / starter-model-openai / spring-ai-advisors-* |
| 2 | ModelProviderConfig record + ProviderType enum | `@ConfigurationProperties("babiq")` |
| 3 | ModelMetadata 静态映射 + 单测 | 19 个主流模型 |
| 4 | DashScopeProviderFactory(原生 SAA) | `.build()` 返回 ChatModel |
| 5 | OpenAiCompatibleProviderFactory(`.mutate()`)| 同上 |
| 6 | ModelProviderRegistry(启动期校验)| id 唯一 / active 存在 |
| 7 | application.yml 4 个 provider 配置 | DashScope/DeepSeek/OneAPI/Ollama |
| 8 | ChatClientFactory + 缓存 + MessageChatMemoryAdvisor 自动挂 + REST 测试端点 + 全局错误处理 | 核心 |
| 9 | 集成测试 + **CrossTurnMemoryIT 跨轮记忆自动化** | M2 关键验收 |
| 10 | 文档同步 + tag `p1-2-providers` | |

---

## 5. ⚠️ Plan 内可能撞到的关键坑(3 个)

### A. Starter auto-config 冲突
plan Step 7.1 的 application.yml **已经加了**:
```yaml
spring:
  ai:
    dashscope:
      chat:
        enabled: false
    openai:
      chat:
        enabled: false
```
**不要删!** 否则 starter 会自动创建 ChatModel Bean 与 ChatClientFactory 冲突。

### B. `MessageChatMemoryAdvisor` artifact 不确定
plan 假定它在 `spring-ai-advisors-vector-store`,执行 Step 1.2 后立刻验证:
```powershell
cd backend
.\mvnw.cmd dependency:tree | Select-String "advisors"
```
**若 `MessageChatMemoryAdvisor` 不在该 artifact**,改为 `spring-ai-client-chat` 或 `spring-ai-core`。plan 已留此风险注解。

### C. CrossTurnMemoryIT 构造签名不一致 ⚠️
plan 内有**已知不一致**:
- 实现部分(Task 6)定义 `ChatClientFactory(ModelProviderRegistry, List<ProviderFactory>, BaBiQProperties)`
- 测试 `CrossTurnMemoryIT` 用了 `ChatClientFactory(registry, Map<ProviderType, ProviderFactory>, int)`

**处理方法**:
- 实现按 Task 6 写(`List<ProviderFactory>` + `BaBiQProperties`)
- 测试 `CrossTurnMemoryIT` 撞编译错时,**改用 BaBiQProperties 包装参数**,语义不变(plan line 1802 有此免责声明)
- **30 秒改完继续**,不要纠结

### D. `DashScopeChatOptions.builder()` API 名漂移
SAA 1.1.2.x 的 API 名可能与 plan 略不同(`.withModel()` vs `.model()` 等)。**编译失败时不要硬猜,查 [java2ai.com](https://java2ai.com)**。

---

## 6. 工作流约定

1. **先读后做**:每个 Task 开始前 `read_file` 对应段落
2. **每步独立 commit**(中文 message,prefix 英文):`feat(p1-2): 实现 DashScopeProviderFactory + 单测`
3. **遇到失败先看 plan**:plan 里有大量陷阱预警
4. **不要超出范围**:不要顺手加 RAG / VectorStore / SummarizationHook(那是 P2/P3)
5. **代码质量铁律(§1)一直生效**
6. **审批策略**:你默认 `on-request`,以下命令必须征求用户同意:
   - `git commit`(**不要 `git tag`、不要 `git push`**;由用户自行决定 push 时机)
   - `mvnw clean package` / `spring-boot:run` / `mvnw test`
   - 任何 `Remove-Item` / `mkdir`(plan 没要求的)

---

## 7. 完成后请给用户的汇报

```
## ✅ P1-2 完成报告

### Done Criteria(按 plan 末尾清单)
[逐条勾选,失败的标 ❌ 并说明]

### git 历史
[git log --oneline p1-1-protocol..HEAD]

### 关键产物
- backend jar: backend/target/babiq-server-0.0.1-SNAPSHOT.jar
- 单测: N tests, 0 failures(含 ModelMetadata + ChatClientFactory 测试)
- 集成测: M tests, 0 failures(含 CrossTurnMemoryIT 跨轮记忆烟测)
- REST 烟测: ✅ GET /providers + POST /chat 真模型回复
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
[列出执行中和 plan 不一致的地方,尤其是 §5 的 4 个坑里实际撞到哪几个;若无写 "无"]

### 下一步建议
P1-2 完成。下个阶段是 P1-3a(Agent Loop 内核),plan 还未编写,需让用户先编写。
```

---

## 8. 应急情况

| 情况 | 处理 |
|---|---|
| 连续 3 次同一步骤失败 | **停下**,把状态 + 错误汇报用户。**不要**破坏性回滚 |
| SAA API 名变了 | 查 [java2ai.com](https://java2ai.com),不要硬猜 |
| `MessageChatMemoryAdvisor` 找不到 | 按 §5.B 切 artifact |
| `CrossTurnMemoryIT` 编译失败 | 按 §5.C 改构造签名,30 秒搞定 |
| auto-config 冲突 | 检查 §5.A yml 是否正确 |
| 没有 `AI_DASHSCOPE_API_KEY` 环境变量 | plan 用 `${VAR:}` 占位,启动应该不爆;真调时 `ChatClientFactory.resolve()` 报缺 key,这是预期行为 |
| 测试不稳(flaky) | 不要 `@Disabled`,汇报用户 |

---

## 9. 一个最重要的提醒

**你写的代码是用户拿来学习的**,不是为了让 plan 跑过就完事。
**质量比速度重要**。
慢一点没关系,屎山不可原谅。

如果某个步骤 plan 写得不够清楚,**宁可花 5 分钟想清楚再写**,也不要凑合塞代码。
如果有更优雅的写法,**在保持 plan 语义不变的前提下**可以采用,但要在 commit message 注明 "对 plan 的优化:XXX"。

---

**好了,开始吧。第一步:`read_file` P1-2 plan.md,然后从 Pre-flight 走起。**
