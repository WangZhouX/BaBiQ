# P3-6 技能层薄封装官方 SkillRegistry（含 token/memory 官方化评估结论）

> 状态：**草案，待用户确认**。
> 触发：用户要求用 Context7 + 本地 jar 核对 P2/P3 是否重复造轮子，并对 3 个候选
> （技能注册表、token 估算器、长期记忆存储层）判断"现有实现能否用 Spring AI /
> Spring AI Alibaba 官方能力优化，可行就出计划"。
> 锁定版本：Spring AI `1.1.6`、Spring AI Alibaba `1.1.2.3`（不升级，遵循 CLAUDE.md §1/§4）。

---

## 0. 一句话结论

经过 **javap 逐签名核对**（不是只看文档），三块里只有 **技能层值得做官方化优化**：

| 候选 | 官方能力 | 结论 | 理由（证据） |
| --- | --- | --- | --- |
| **技能注册/扫描/正文读取** | `SkillRegistry` / `FileSystemSkillRegistry` / `SkillMetadata` | **✅ 做（本计划）** | 官方完全覆盖 BaBiQ 手写的扫描 + front-matter 解析 + 正文读取，且更健壮（多 `allowedTools`、`search`、`disable`、`reload`） |
| **token 估算器** | `agent.hook.TokenCounter` | **❌ 不做** | 官方 `approximateMsgCounter()` 本身也是 `字符/N` 粗估，**精度零提升**，只是 API 对齐；不值得单独重构 |
| **长期记忆存储层** | `store.Store` / `store.stores.DatabaseStore` | **❌ 不做（自研合理）** | 官方是通用 namespace/key/value(Map) KV + 独立表；BaBiQ 记忆是关系型 2 阶段流水线 + 脱敏 + 审计 + schema 注释纪律，塞进通用 KV 会丢结构、碎片化持久化 |

本计划的**实质工作只针对技能层**，是一次**行为不变的内部替换（adapter 重构）**，不是新功能。

---

## 1. 核查证据（javap 实测，1.1.2.3 jar）

### 1.1 官方技能 API（graph-core）

```text
interface SkillRegistry {
  Optional<SkillMetadata> get(String);
  Optional<SkillMetadata> getByPath(String);
  List<SkillMetadata>     listAll();
  boolean                 contains(String);
  List<SkillMetadata>     search(String);          // 官方自带词法搜索
  int                     size();
  boolean                 disable(String);          // 官方自带启用/禁用
  boolean                 isDisabled(String);
  void                    reload();
  String                  readSkillContent(String) throws IOException;  // 按需读正文
  String                  getSkillLoadInstructions();
  String                  getRegistryType();
  SystemPromptTemplate    getSystemPromptTemplate();
}

class FileSystemSkillRegistry extends AbstractSkillRegistry {
  static Builder builder();
  String readSkillContent(String) throws IOException;
  // Builder: userSkillsDirectory(String|Resource) / projectSkillsDirectory(String|Resource)
  //          / autoLoad(boolean) / systemPromptTemplate(...) / build()
}

class SkillMetadata {
  String getName(); String getDescription(); String getSkillPath(); String getSource();
  String getFullContent(); List<String> getAllowedTools();   // ★ BaBiQ 当前没有的字段
  String loadFullContent() throws IOException;               // 按需读正文
}
```

### 1.2 BaBiQ 现状（master）

- `skill/LocalSkillRegistry`：`Files.walk(root, 3)` 找 `SKILL.md` → **手写 front-matter 行解析**（`frontMatter()` 第 90-105 行，只认 `key:` 前缀，不是真 YAML）→ 手算 sha256 → 拼 `SkillDescriptor`。
- `skill/SkillContentLoader`：`Files.readString` 读正文 → 按 `maxContentChars` 截断。
- `skill/SkillDescriptor`：`id / namespace / name / description / sourceDirectory / skillFile / contentHash` 7 字段。
- `skill/SkillProperties`：`enabled` + `directories: List<Path>`（默认 `~/.codex/skills`、`~/.codex/superpowers/skills`）+ `maxContentChars=16000`。
- **消费方（必须行为不变）**：
  - `SkillCatalogService.list()/get()` → `skills/list`、`skills/get`（`SkillsListHandler`/`SkillsGetHandler`）。
  - `capability/CapabilityCatalogSyncService.skillCapabilities()` → 把每个 `SkillDescriptor` 映射成 `skill.<id>` 写进 `bq_capabilities`（→ Lucene → deferred exposure → `tool_search`）。**依赖 `id`、`namespace`、`contentHash` 的稳定性**。

### 1.3 GO/NO-GO 关键点（已解）

- **官方 `FileSystemSkillRegistry` 只有 `user`/`project` 两个固定目录槽**，而 BaBiQ 现状是任意多目录 `List<Path>`（且默认借 `~/.codex` 目录）。
  → **不阻塞，反而更顺**：BaBiQ 改用自有的"用户级 + 项目级"两条路径（§3.0），**直接对上官方两槽**，单个 `FileSystemSkillRegistry` 即可承载；只有用户额外配置自定义目录时才退回"每目录一个 registry 聚合"。
- **绝不接 `SkillPromptAugmentAdvisor` / `SpringAiSkillAdvisor`**：这两个是**常驻 system prompt 注入**（把所有 skill 摘要塞进每轮 prompt），会**绕过 BaBiQ 的 deferred exposure（`tool_search` + `CapabilityExposurePlanner` + Lucene 门控）和 Spotlighting**。BaBiQ 的按需装配语义必须保留 → 只薄封装**注册/扫描/正文读取**这一层。

### 1.4 Codex 源码核对（`E:\wzx\codex\codex-rs`）

为给路径与未来安装设计做"参考实现"交叉印证，已核对 Codex skill 实现：

- **目录模型**：4 scope + 优先级去重（Repo>User>System>Admin）。常量 `AGENTS_DIR_NAME=".agents"`。用户级新位置 = `$HOME/.agents/skills`，`$CODEX_HOME/skills`（即 `~/.codex/skills`）**已标 deprecated 仅兼容**；项目级 `<repo>/.agents/skills`（项目根↔cwd 间扫描）；内置 bundled 在 `$CODEX_HOME/skills/.system`；admin `/etc/codex/skills`。cwd 感知 + `set_extra_roots` 清缓存。→ 印证 BaBiQ 采用 `.agents/skills`（D8）、cwd reload（Step 2b）、`additional-directories`（≈ extra_roots）。
- **SKILL.md**：YAML front-matter（`name` + 长句 `description`）+ markdown 正文；富字段 `interface`/`dependencies`/`policy`；技能可带 `scripts/` 可执行。
- **分发**：远程 "hazelnuts" API（`GET /hazelnuts/{id}/export` → zip，PK 魔数校验 + `safe_join` 路径穿越防护 + 解压到 skills 目录，需 ChatGPT 后端鉴权，注释称"尚未接入产品入口"）+ 插件/市场（`marketplace_cmd.rs`）。→ §10 安装阶段可复用其安全模板。
- **喂模型**：按 token 预算注入 system prompt（`include_instructions` + `SkillMetadataBudget`）+ 隐式调用检测，属**常驻注入**；BaBiQ 刻意走 **deferred exposure**（`tool_search` 门控，D1），为差异化设计。

### 1.5 Codex 功能 ↔ 官方 SAA / Java 能力对照（诚实评估，回答"绝对可以吗"）

> 官方 skill 能力已用 **jar/javap（权威）+ Context7（官方散文文档）双验证**；Codex 功能来自 §1.4 源码核对。
> **不承诺 1:1 全复刻。** 诚实结论：核心能力**绝对可以**（官方薄封装）；富 metadata **可选**用 Java YAML 自解析；远程安装/市场属**未来阶段**；常驻注入/隐式调用/自动跑脚本是 BaBiQ **刻意分歧**，不照搬。

| Codex skill 功能 | 官方 SAA / Java 能力（已验证） | 结论 |
| --- | --- | --- |
| SKILL.md 发现 + 递归扫描 | `FileSystemSkillRegistry` + `SkillScanner` | ✅ 官方薄封装 |
| front-matter `name`/`description` | `SkillMetadata.getName()/getDescription()` | ✅ 官方薄封装 |
| 按需读正文 + 截断 | `readSkillContent()` / `loadFullContent()`（截断在 BaBiQ 层） | ✅ 官方薄封装 |
| 用户 + 项目目录 + 优先级 | 官方 user/project 两槽（project 覆盖 user，Context7 确认） | ✅ 官方薄封装（Codex 4 scope 取其 2） |
| `.agents/skills` 中立约定 | 由 BaBiQ 配置路径值 | ✅ 配置（D8） |
| cwd 感知 + reload | `SkillRegistry.reload()` + 重建项目槽 | ✅ 官方薄封装（Step 2b） |
| 内置 bundled 技能 | `ClasspathSkillRegistry.builder().classpathPath("skills")`（打进 JAR） | ✅ 官方薄封装 |
| admin / 额外 root | `additional-directories` 聚合 | ✅ 配置（可选） |
| `allowedTools`（工具名列表） | `SkillMetadata.getAllowedTools()` | ✅ 官方免费携带 → 纳入核心（后端透出 `SkillInfo`） |
| 技能搜索 | BaBiQ 既有 Lucene/BM25（或官方 `search()`） | ✅ 已有 |
| 启用/禁用单个技能 | 官方 `disable()/isDisabled()` | ✅ 官方薄封装（可选） |
| 富工具依赖（MCP transport/command/url） | 官方无（仅工具名） | ⚠️ Java YAML（SnakeYAML，Spring Boot 自带）自解析；当前非必需 |
| `interface`（display_name/icon/brand_color/default_prompt） | 官方无 | ⚠️ 可选 UI 增强，后续再做 |
| `policy`（implicit invocation / products） | 官方无 | ❌ 不做：products 是 Codex 产品门控（与 BaBiQ 无关）；implicit 用 deferred 取代 |
| `plugin_id` / 市场来源 | 官方无 | ⏭️ 未来阶段（§10 市场） |
| 远程下载（zip export） | 官方无；Java：`java.util.zip` + Spring `RestClient` + 路径穿越防护 | ⏭️ 未来阶段（§10，抄 Codex `remote.rs` 模板） |
| 常驻 prompt 注入（token budget） | 官方 `SkillPromptAugmentAdvisor`/`SkillsAgentHook` | 🚫 刻意分歧：BaBiQ 用 deferred exposure（tool_search），不常驻注入（D1） |
| 隐式调用检测 | Codex 自实现 | 🚫 刻意分歧：用 `tool_search` 取代 |
| 脚本执行 + 单技能 FS + 审批 | Codex 沙箱 executor | 🚫 刻意分歧（D10 不自动执行）；如需可另起未来阶段 |

**一句话回答"你绝对可以吗"**：P3-6 范围内（✅ 行）= **绝对可以**，纯官方薄封装 + 既有能力，已 jar+Context7 验证；⚠️ 行 = Java 能做但当前非必需，列为可选；⏭️ 行 = Java 能做但属独立未来阶段；🚫 行 = 我**主动选择不照搬**（设计/安全分歧，非"做不到"）。

---

## 2. 目标与非目标

### 2.1 目标

1. 用官方 `FileSystemSkillRegistry`（每目录一个，聚合）替换 `LocalSkillRegistry` 的**扫描 + front-matter 解析**内部实现。
2. 用官方 `readSkillContent` / `SkillMetadata.loadFullContent()` 替换 `SkillContentLoader` 的正文读取。
3. **`LocalSkillRegistry` / `SkillContentLoader` 的对外方法签名与 `SkillDescriptor` 字段完全不变**，消费方（`SkillCatalogService`、两个 handler、`CapabilityCatalogSyncService`）**零改动**。
4. 删除 BaBiQ 自写的脆弱 front-matter 行解析、`Files.walk` 扫描逻辑。
5. **采用工具中立的 `.agents/skills` 技能路径**（`~/.agents/skills` + `<cwd>/.agents/skills`，见 §3.0），对齐 agent 生态、开源 skill 零搬迁；`~/.codex` 等遗留目录降级为可选配置。
6. 把官方免费携带的 `allowedTools` 透出到 `SkillDescriptor` → `SkillInfo`（**后端核心，官方 `getAllowedTools()` 零成本**）；桌面端展示仍列为可选后续项。

### 2.2 非目标（明确不做）

- ❌ 不接 `SkillPromptAugmentAdvisor` / `SpringAiSkillAdvisor`（破坏 deferred exposure）。
- ❌ 不改 `tool_search` / `CapabilityExposurePlanner` / Lucene / `bq_capabilities` 任何门控逻辑。
- ❌ 不改 token 估算器（见 §5.1）。
- ❌ 不改长期记忆存储层（见 §5.2）。
- ❌ 不新增数据库表 / migration（技能 metadata 不落 `bq_*` 业务表，只进 `bq_capabilities` 摘要，且字段不变）。
- ❌ 不动桌面端（`allowedTools` 仅后端透出到 `SkillInfo`，桌面展示列为后续项）。

---

## 3. 设计：adapter 内部替换

保留 `LocalSkillRegistry` 这个 BaBiQ 类作为**防腐层（ACL）**，内部从"手写扫描"改为"持有官方 `FileSystemSkillRegistry`"。

### 3.0 Skill 存放路径约定（D8/D9）

BaBiQ 采用**工具中立的 `.agents/skills` 约定**（对齐 Codex 新位置与 agent 生态），不再借 Codex 的 `~/.codex/skills`（那已是 Codex 自己标记 deprecated 的旧位置）：

| 层级 | 路径 | 官方槽位 | 语义 |
| --- | --- | --- | --- |
| 用户级（全局） | `~/.agents/skills/` | `userSkillsDirectory` | 跟随当前用户/机器，所有工作区共享；工具中立目录，Codex/Claude 生态开源 skill 通用 |
| 项目级（随工作区） | `<cwd>/.agents/skills/` | `projectSkillsDirectory` | 放在当前工作目录下，可随仓库提交、跟项目走；与文件工具/沙箱的 cwd 同根 |

- 默认即这两条 → **直接对上官方两槽，单个 `FileSystemSkillRegistry` 即可承载**；§1.3 的"N 目录聚合"降级为仅在用户额外配置 `babiq.skills.additional-directories` 时启用。
- `~/.codex/skills` / superpowers 等遗留目录**不写死默认**；想继续读 Codex/superpowers 技能的人，把目录加进可选 `additional-directories` 即可（按目录各建一个 registry 聚合）。
- 项目级目录依赖 cwd，**切换工作目录时必须 `registry.reload()` 重建项目槽**（官方 `SkillRegistry.reload()` 现成）——见 §4 Step 2b。
- "下载/使用别人开源的 skill"最朴素的形态由此免费附带：`git clone <repo> ~/.agents/skills/<name>` 或把 skill 文件夹拷进去即可（生态 skill 零搬迁）；受管安装/市场见 §10（独立阶段）。

### 3.1 字段映射 `SkillMetadata` → `SkillDescriptor`

| `SkillDescriptor` | 来源 | 说明 |
| --- | --- | --- |
| `name` | `SkillMetadata.getName()` | 直接取 |
| `description` | `SkillMetadata.getDescription()` | 直接取 |
| `skillFile` | `SkillMetadata.getSkillPath()` | 官方给的 SKILL.md 路径 |
| `sourceDirectory` | 该 registry 对应的根目录（用户级 `~/.agents/skills` / 项目级 `<cwd>/.agents/skills` / 额外目录） | adapter 持有"目录 → registry"映射时一并记住 |
| `namespace` | **沿用 BaBiQ 现算法**：根目录相对路径首段，嵌套则取首段，否则 `local` | **保证 id 稳定**，不用官方 `getSource()`（那是 user/project，会变 id） |
| `id` | **沿用 BaBiQ 现算法**：`(namespace + "." + name).replaceAll("[^A-Za-z0-9_.-]","_")` | 必须逐字符一致，否则 `bq_capabilities` 里的 `skill.<id>` 行会孤立 |
| `contentHash` | 见 §3.2 决策 | 影响 capability 变更检测 |
| `allowedTools`（新增字段） | `SkillMetadata.getAllowedTools()` | 官方免费携带，后端透出 `SkillInfo`；为空时表示该 skill 未声明工具限制 |

### 3.2 contentHash 策略（决策点 D3）

官方 `SkillMetadata` **不带内容 hash**。两个选项：

- **(A) 保内容 sha256（推荐，行为完全一致）**：adapter 在 `listSkills()` 时对每个 skill 取 `getFullContent()`（官方扫描后通常已缓存正文；否则 `loadFullContent()`）再 sha256。BaBiQ 现状本来就在 `listSkills()` 里读全文算 hash，**无新增性能回归**。capability 变更检测语义 100% 不变。
- (B) 改为 hash 元数据（name+description+path）：更轻，但**只能检测 front-matter 变更，检测不到正文改动** → 改变 `bq_capabilities` 的 `contentHash` 语义 → 不推荐。

**本计划采用 (A)**。

### 3.3 正文读取

`SkillContentLoader.load(id)`：`registry.findById(id)` → 对应官方 registry `readSkillContent(name)`（或 `SkillMetadata.loadFullContent()`）→ 按 `maxContentChars` 截断 → 返回 `SkillContent(descriptor, clipped, truncated)`。截断逻辑保留在 BaBiQ 层（官方不做预算截断）。

### 3.4 依赖确认

技能类在 `spring-ai-alibaba-graph-core` 包内，BaBiQ 已经（经 P6 的 `ReactAgent.asNode`/`StateGraph`）在编译期依赖 graph-core。**Step 1 先验证 `com.alibaba.cloud.ai.graph.skills.*` 在编译 classpath 可见**；若仅运行期传递可见，则在 `backend/pom.xml` 显式声明 graph-core（已锁版本，不引新版本）。

---

## 4. 分步实现（TDD，每步先测后改）

> 遵循 CLAUDE.md §0/§5：先 `superpowers:test-driven-development` 心智；禁止 `@Disabled`；改前读代码。

- **Step 0 — 行为基线（golden master）**：先写/补 `LocalSkillRegistryTest` + `SkillContentLoaderTest`，用临时目录放若干 `SKILL.md`（含 front-matter、嵌套 namespace、超长正文、缺失 front-matter 回退），断言当前 `listSkills()` 的 id/namespace/description/contentHash 和 `load()` 的截断行为。**这是重构安全网**——重构后必须全绿且输出逐字段一致。
- **Step 1 — 依赖与可见性确认**：确认 graph-core skills 包编译可见（见 §3.4），必要时显式声明依赖（不升级版本）。
- **Step 1b — 路径与配置**：`SkillProperties` 默认改为 `~/.agents/skills`（用户级）+ `<cwd>/.agents/skills`（项目级），新增可选 `additional-directories`（默认空），去掉 `~/.codex` 写死默认。补 `SkillPropertiesTest` 断言默认值与可选目录。
- **Step 2 — adapter 化 `LocalSkillRegistry`**：用户槽 `~/.agents/skills` + 项目槽 `<cwd>/.agents/skills` 建一个官方 `FileSystemSkillRegistry`（`autoLoad(true)`），`additional-directories` 每个再建一个聚合；`listSkills()` 聚合各 `listAll()` → 映射 `SkillDescriptor`（§3.1/§3.2）→ 按 id 排序去重；`findById()` 走聚合结果。**对外签名不变**。删除手写 `scanDirectory`/`readDescriptor`/`frontMatter`/`Files.walk`。
- **Step 2b — cwd 切换重建**：在工作目录切换处（与现有文件工具/沙箱 cwd 来源一致）`reload()` 或重建项目槽 registry，保证项目级技能随当前工作区变化。补 `LocalSkillRegistryCwdReloadTest` 断言切 cwd 后 `<cwd>/.agents/skills` 的技能随之进出。
- **Step 3 — `SkillContentLoader` 走官方读正文**：改为 `registry.readSkillContent(name)`（或 `loadFullContent()`）+ 截断；对外签名不变。
- **Step 4 — 跑基线**：`LocalSkillRegistryTest`、`SkillContentLoaderTest` 全绿且字段逐一对齐（Step 0 的网兜住行为）。
- **Step 5 — 消费方回归**：`SkillHandlersTest`（`skills/list`、`skills/get`）+ `CapabilityCatalogSyncServiceTest`（`skill.<id>` 映射、searchText、contentHash 变更检测）全绿，证明 deferred exposure / `bq_capabilities` 链路不变。
- **Step 6 — 全量验证**：后端 `clean verify`（含 IT）+ 桌面 `gradlew test`（应无影响）。
- **Step 7 — 文档同步**：更新 `CLAUDE.md` / `AGENTS.md` 检查点 + `p3-task-index.md`，记录"技能层已薄封装官方 registry，token/memory 评估后维持自研"。

---

## 5. 其他两块的评估结论（不改，记录在案，避免后续重复争论）

### 5.1 token 估算器 — 维持自研

- 官方 `TokenCounter.approximateMsgCounter()` 实测就是 `字符 / DEFAULT_CHARS_PER_TOKEN` 的粗估，签名是 `countTokens(List<Message>)`。
- BaBiQ `ApproximateContextTokenEstimator` 是 `字符 / 3` 的粗估，签名是 `estimate(String)`。
- **两者同为字符近似，官方不带真 tokenizer，换过去精度零提升**，还要把 `String` 包成 `Message`。
- 唯一潜在价值：若将来 BaBiQ 引入官方 `SummarizationHook`（其内部用 `TokenCounter` 做预算），那时为避免"两套估算口径"再对齐。
- **结论**：现在不动；接口注释里"后续可接真实 tokenizer"的预留点继续保留（真 tokenizer 需 provider-specific 依赖，官方也没给）。

### 5.2 长期记忆存储层 — 维持自研（自研合理）

- 官方 `Store` 是通用 `namespace/key/value(Map)` KV：`putItem/getItem/deleteItem/searchItems(filter+sort+offset+limit)/listNamespaces/clear/size`；`DatabaseStore(DataSource[, tableName])` 落**自己的一张表**。
- BaBiQ 记忆是**关系型 2 阶段流水线**：`bq_memory_jobs`（Phase1/2 任务队列）、`bq_memory_candidates`（抽取候选 + `SECRET_RISK` 隔离）、`bq_memory_artifacts`（归并产物 + Markdown 镜像）、`bq_memory_references`（read-path 引用），且受 schema 中文注释 + `bq_schema_comments` + 覆盖测试纪律约束。
- 把这套塞进通用 KV(Map blob)：**丢关系结构、丢 job 队列、丢脱敏/审计列、破坏 schema-comments 纪律、与 `bq_*` 持久化模型碎片化**。
- 官方 `Store` 适合"跨会话简单 KV 记忆（如用户画像）"，**不适合 BaBiQ 的抽取流水线**。
- **结论**：维持自研。若将来要做"跨会话轻量 KV 记忆"这种新场景，再单独评估 `DatabaseStore`。

---

## 6. 决策记录

- **D1**：只薄封装注册/扫描/正文读取，不接 `SkillPromptAugmentAdvisor`/`SpringAiSkillAdvisor`（保 deferred exposure）。
- **D2**：保 `LocalSkillRegistry`/`SkillContentLoader`/`SkillDescriptor` 对外契约不变（adapter 内部替换），消费方零改动。
- **D3**：`contentHash` 用方案 A（内容 sha256），保 `bq_capabilities` 变更检测语义不变。
- **D4**：`namespace`/`id` 沿用 BaBiQ 现算法（不用官方 `getSource()`），保 capability id 稳定。
- **D5**：多目录用"每目录一个 `FileSystemSkillRegistry` 聚合"承载官方两槽限制。
- **D6**：token 估算器、长期记忆存储层**评估后不改**（§5），理由入档。
- **D7**：`allowedTools` 透出列为可选后续项，不混入本计划核心。
- **D8**：技能路径采用工具中立的 `.agents/skills` 约定 = `~/.agents/skills`（用户级 → 官方 `userSkillsDirectory`）+ `<cwd>/.agents/skills`（项目级，随工作区 → 官方 `projectSkillsDirectory`）。对齐 Codex 新位置与 agent 生态，开源 skill 零搬迁。
- **D9**：`~/.codex/skills`（Codex 已 deprecated 的旧位置）、superpowers 等遗留目录不再是默认；保留为可选 `babiq.skills.additional-directories`（默认空），按目录聚合。
- **D10**：维持只读指令语义，**不自动执行 skill 捆绑脚本**；"第三方 skill 受管安装/市场"为独立未来阶段（§10），不在 P3-6。

---

## 7. 风险与回滚

- **风险 1：官方扫描的 namespace/id 与现状不一致** → 由 Step 0 golden master 兜底，字段逐一比对；不一致就在 adapter 里用 BaBiQ 算法纠正。
- **风险 2：官方 front-matter 解析比手写宽松/严格，导致 skill 数量变化** → Step 5 `CapabilityCatalogSyncServiceTest` + 基线测试会暴露；按官方语义为准（更健壮是收益，但需在 handoff 里写清行为差异）。
- **风险 3：graph-core 仅运行期传递依赖** → Step 1 显式声明（锁版本）。
- **回滚**：纯内部重构，`git revert` 单个提交即可恢复手写实现；消费方未动，回滚无连锁。

---

## 8. 验收命令

```powershell
cd backend
.\mvnw.cmd "-Dtest=LocalSkillRegistryTest,SkillContentLoaderTest,SkillHandlersTest,CapabilityCatalogSyncServiceTest,SchemaCommentsCoverageTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

- 通过标准：基线 + 消费方测试全绿且字段逐一对齐；`clean verify` 全绿（含 IT）；桌面无回归；`bq_capabilities` 里 `skill.<id>` 行无孤立/无漂移。
- 真实烟测（可选）：放一个含 front-matter 的 `SKILL.md` 到配置目录，`skills/list` 能列出、`skills/get` 能读正文且按 16000 字符截断、`tool_search` 中文 query 能命中该 skill。

---

## 9. 确认状态

- ✅ 范围分层见 §1.5：核心官方薄封装 / 富 metadata 可选 Java / 远程安装未来阶段 / 注入·隐式·脚本刻意分歧。
- ✅ 范围：只做技能层薄封装，token/memory 维持自研（已确认）。
- ✅ 路径：工具中立 `~/.agents/skills` + `<cwd>/.agents/skills`，遗留目录降级为可选 `additional-directories`（已确认对齐生态）。
- ✅ `contentHash`：方案 A（内容 sha256，行为完全一致）（已确认）。
- ✅ `allowedTools`：官方 `getAllowedTools()` 免费携带 → 纳入 P3-6 后端核心（透出 `SkillInfo`，桌面展示后续项）。
- ✅ 一键安装/市场：独立未来阶段（§10），P3-6 不做（已确认）。
- ⬜ 实现：交 Codex（出 `codex-handoff.md`）or 本会话直接实现？

---

## 10. 未来独立阶段（不在 P3-6）：第三方 Skill 受管安装

P3-6 落地后，"手动 / `git clone` 进 `~/.agents/skills`"已可使用别人开源的 skill。"受管安装/市场"是更大的独立功能，单独成阶段：

- **能力**：从 git URL / 市场源添加、更新、卸载 skill；桌面端来源管理 UI + 列表。
- **兼容**：skill 是标准"含 `SKILL.md` 的目录"，为 Claude Code / Codex 写的开源 skill 可直接落盘使用（官方 registry 解析标准 front-matter，含 `allowed-tools`）。
- **安全模型（关键，必须先设计）**：
  - BaBiQ 维持**只读指令语义**：surfacing `SKILL.md` 文本，**不自动执行**捆绑脚本（沿用 D10）。
  - 第三方 `SKILL.md` 是**供应链 / 间接 prompt 注入**面 → 安装时需**评审/确认步骤**、来源标注、可信目录隔离；纳入上下文仍走 Spotlighting + deferred exposure 门控。
  - 与 CLAUDE.md「插件市场属未来阶段」一致，不得混入 P3-6 或当前阶段收口。
- **可复用 Codex 安装安全模板**（已核对 `core-skills/remote.rs`）：下载 zip → 校验 PK 魔数 → **路径穿越防护**（只允许 Normal 路径组件，挡 `..`）→ 解压到 `~/.agents/skills/<id>`；远程来源需鉴权/信任标注；可选 bundled 自带技能（Codex 放 `$CODEX_HOME/skills/.system`）。
- **触发条件**：P3-6 完成后，若用户要做"一键安装/市场"，再写该阶段详细 plan。
