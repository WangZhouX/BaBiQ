# P3-6 技能层薄封装官方 SkillRegistry — Codex 交接

> 完整计划见：`E:\BaBiQ\docs\superpowers\plans\p3-6-skill-official-registry\plan.md`
> 参考实现核对：`E:\wzx\codex\codex-rs`（core-skills / config / cli marketplace）
> 锁定版本：Spring AI `1.1.6`、Spring AI Alibaba `1.1.2.3`（**不升级**）。

## 当前状态

- **计划已就绪并经用户确认范围；代码尚未实现**（待 Codex 按本交接 TDD 落地）。
- 这是一次**行为不变的内部 adapter 重构 + 路径归属**，不是新功能：把 `LocalSkillRegistry`/`SkillContentLoader` 的"手写扫描 + 手写 front-matter 解析"换成官方 `FileSystemSkillRegistry`/`ClasspathSkillRegistry`，对外契约与消费方零改动。
- 官方能力已 **jar/javap（权威）+ Context7（官方散文文档）双验证**（见下）。

## 一句话目标

把 BaBiQ 技能层从"手写 `Files.walk` + 脆弱 front-matter 行解析"改为**薄封装官方 `FileSystemSkillRegistry`/`ClasspathSkillRegistry`/`SkillRegistry`/`SkillMetadata`**，并改用**工具中立的 `~/.agents/skills` + `<cwd>/.agents/skills`** 技能路径（开源 skill 零搬迁），消费方（`skills/list`、`skills/get`、`bq_capabilities` 同步）行为完全不变。

## 必读入口

1. `E:\BaBiQ\CLAUDE.md`（§3 边界、§4 先查官方/薄封装、§4.1 工具命名/searchText、§5 验收、§7 Git、§8 汇报）。
2. `p3-6-skill-official-registry/plan.md`（**完整计划：§1.3 GO/NO-GO、§1.4 Codex 核对、§1.5 功能对照、§3 设计、§4 TDD、§6 决策 D1–D10、§8 验收**）。
3. BaBiQ 现状（先读再改）：
   - `backend/src/main/java/com/wzx/babiq/server/skill/{LocalSkillRegistry,SkillContentLoader,SkillDescriptor,SkillContent,SkillCatalogService,SkillProperties}.java`
   - 消费方（**必须保持行为不变**）：`api/method/{SkillsListHandler,SkillsGetHandler}.java`、`api/dto/{SkillInfo,SkillGetResult,SkillListResult}.java`、`capability/CapabilityCatalogSyncService.java`（`skillCapabilities()` → `bq_capabilities` 的 `skill.<id>`）。
4. 参考源（**概念借鉴，不照搬**）：Codex `core-skills/src/{loader,manager,model,remote}.rs`、`config/src/skills_config.rs`。

## 已验证的官方机制（jar + Context7，直接用）

```java
// 文件系统技能（用户 + 项目两槽，project 覆盖 user）
SkillRegistry reg = FileSystemSkillRegistry.builder()
    .userSkillsDirectory(userDir)        // ~/.agents/skills
    .projectSkillsDirectory(projectDir)  // <cwd>/.agents/skills
    .autoLoad(true)
    .build();

// 内置 bundled 技能（打进 BaBiQ JAR 的 classpath 资源；可选）
SkillRegistry bundled = ClasspathSkillRegistry.builder().classpathPath("skills").build();

// SkillRegistry 接口（AbstractSkillRegistry 实现）
List<SkillMetadata> all = reg.listAll();
Optional<SkillMetadata> one = reg.get(name);
String body = reg.readSkillContent(name);   // 按需读正文（抛 IOException）
reg.reload();                                // 重新扫描
// 还有 search(String) / disable(String) / isDisabled(String)

// SkillMetadata（POJO）
md.getName(); md.getDescription(); md.getSkillPath(); md.getSource();
md.getFullContent(); md.getAllowedTools();   // List<String>，官方免费携带
md.loadFullContent();                        // 按需读全文
```

- **官方只有 user/project 两个固定槽**（Codex 4 scope 取其 2）。BaBiQ 多目录用：用户槽 `~/.agents/skills` + 项目槽 `<cwd>/.agents/skills` 一个 `FileSystemSkillRegistry`；可选 `additional-directories` 每个再建一个聚合；bundled 用 `ClasspathSkillRegistry`。
- **`SkillMetadata` 不带** Codex 的 `interface`/`policy`/富工具依赖（§1.5）；本阶段**不**自解析这些（可选/未来）。

## 范围（先钉死）

**做**：
- adapter 化 `LocalSkillRegistry`（内部官方 registry 聚合）+ `SkillContentLoader`（官方 `readSkillContent`/`loadFullContent` + BaBiQ 层截断）。
- 路径改 `~/.agents/skills`（用户）+ `<cwd>/.agents/skills`（项目）+ 可选 `additional-directories`；去掉 `~/.codex` 写死默认。
- cwd 切换重建项目槽 registry（见执行规则 5）。
- `contentHash` 方案 A（内容 sha256，行为完全一致，§3.2）。
- `allowedTools` 作为 `SkillDescriptor`/`SkillInfo` **新增字段**（官方 `getAllowedTools()`，后端透出；桌面展示后续项）。

**不做（明确推迟/分歧，不得混入）**：
- 🚫 **不接 `SkillPromptAugmentAdvisor` / `SpringAiSkillAdvisor` / `SkillsAgentHook`**（常驻 prompt 注入，会绕过 BaBiQ 的 deferred exposure `tool_search` 门控 + Spotlighting）。BaBiQ 技能仍只走能力目录 + `tool_search`。
- 🚫 不自动执行技能脚本（D10）；不实现隐式调用检测。
- ⏭️ 远程下载 / 一键安装 / 市场 / `plugin_id` → 未来独立阶段（plan §10）。
- ⚠️ 富 metadata（`interface`/icon/brand_color、富工具依赖、`policy`）→ 可选后续，本阶段不做。
- ❌ 不新增数据库表 / migration（技能 metadata 不落 `bq_*` 业务表，只进 `bq_capabilities` 摘要，且字段不变）。
- ❌ 不改 `tool_search`/`CapabilityExposurePlanner`/Lucene/`bq_capabilities` 门控；不改桌面端（除 `allowedTools` 后端透出）；不升级版本。

## 已定决策（plan §6，D1–D10）

- **D1** 只薄封装注册/扫描/正文读取，不接任何 skill advisor/hook（保 deferred exposure）。
- **D2** 保 `LocalSkillRegistry`/`SkillContentLoader`/`SkillDescriptor` 对外契约不变，消费方零改动。
- **D3** `contentHash` 方案 A（内容 sha256），保 `bq_capabilities` 变更检测语义不变。
- **D4** `namespace`/`id` **沿用 BaBiQ 现算法**（不用官方 `getSource()`），保 `skill.<id>` capability 行不孤立。
- **D5** 多目录 = user/project 两槽 + `additional-directories` 聚合。
- **D6** token/memory 评估后不改（与本阶段无关，见 plan §5）。
- **D7** `allowedTools` 纳入后端核心（官方免费携带）。
- **D8** 路径 = `~/.agents/skills` + `<cwd>/.agents/skills`（工具中立，对齐 agent 生态）。
- **D9** `~/.codex/skills`（Codex 已 deprecated）/ superpowers 等遗留目录 → 可选 `babiq.skills.additional-directories`（默认空）。
- **D10** 维持只读指令语义，不自动执行脚本；安装/市场属未来阶段。

## TDD 任务顺序（plan §4，先红后绿）

1. **Step 0 行为基线（golden master，先写）**：`LocalSkillRegistryTest` + `SkillContentLoaderTest`，临时目录放若干 `SKILL.md`（含 front-matter、嵌套 namespace、超长正文截断、缺 front-matter 回退），断言重构前 `listSkills()` 的 `id/namespace/name/description/contentHash` 与 `load()` 截断行为。**这是重构安全网**。
2. **Step 1 依赖可见性**：确认 `com.alibaba.cloud.ai.graph.skills.*` 编译期可见；若仅运行期传递，则在 `backend/pom.xml` 显式声明 graph-core（**锁版本 1.1.2.3，不升级**）。
3. **Step 1b 路径与配置**：`SkillProperties` 默认改 `~/.agents/skills` + `<cwd>/.agents/skills`，新增可选 `additional-directories`，去 `~/.codex` 默认；补 `SkillPropertiesTest`。
4. **Step 2 adapter 化 `LocalSkillRegistry`**：内部官方 registry 聚合 → 映射 `SkillMetadata`→`SkillDescriptor`（§3.1 表，含新 `allowedTools`）→ 按 id 排序去重；删除手写 `scanDirectory/readDescriptor/frontMatter/Files.walk`。**对外签名不变**。
5. **Step 2b cwd 切换重建**：`LocalSkillRegistryCwdReloadTest` 断言切 cwd 后 `<cwd>/.agents/skills` 技能随之进出。
6. **Step 3 `SkillContentLoader`**：官方 `readSkillContent`/`loadFullContent` + `maxContentChars` 截断；对外签名不变。
7. **Step 4 跑基线**：Step 0 测试全绿且字段逐一对齐。
8. **Step 5 消费方回归**：`SkillHandlersTest` + `CapabilityCatalogSyncServiceTest` 全绿（`skill.<id>` 映射、searchText、contentHash 变更检测不变）。
9. **Step 6 全量**：后端 `clean verify`（含 IT）+ 桌面 `gradlew test`（应无影响）。

## 执行规则

1. 严格按 plan §4 Step 0→6 TDD；改生产代码补**中文教学注释**（CLAUDE.md §4）：类/方法/字段说清"负责什么、谁写入、谁读取、空值语义"。
2. **行为不变是硬约束**：Step 0 golden master 必须先存在且重构后逐字段对齐；`bq_capabilities` 的 `skill.<id>` 不得漂移/孤立（id/namespace 沿用现算法）。
3. **绝不引入任何 skill advisor/hook**（`SkillPromptAugmentAdvisor`/`SpringAiSkillAdvisor`/`SkillsAgentHook`）——会绕过 deferred exposure。
4. 工具/能力 `name`/`capability_id` 必须 ASCII；中文检索靠 displayName/description/searchText（§4.1，skill 这条已由 `CapabilityCatalogSyncService` 别名字典富化，不要回退）。
5. **cwd 切换**：项目槽目录是 `<cwd>/.agents/skills`，cwd 变化时**重建项目槽 registry**（用户槽 `~/.agents/skills` 稳定）；可按 cwd 缓存（借鉴 Codex `cache_by_cwd`）。用户级文件工具/沙箱的 cwd 来源已存在，复用同一来源。
6. 薄封装官方 registry，**不自研 scanner / front-matter 解析器**；借鉴 Codex 概念不照搬实现。
7. 不新增 `bq_*` 表/字段；`SchemaCommentsCoverageTest` 必须仍通过（无新字段即可）。
8. 每步中文 conventional commit（`test(p3-6): ...` / `refactor(p3-6): ...` / `feat(p3-6): ...`）。**不主动 push**。
9. 完成后更新 `CLAUDE.md` 检查点、`AGENTS.md`、`p3-task-index.md`（记录"技能层已薄封装官方 registry + `.agents/skills` 路径，token/memory 维持自研"）。
10. 没有新鲜证据不得声称完成（CLAUDE.md §8）；**禁止 `@Disabled` 占位**。

## 验收（plan §8）

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=LocalSkillRegistryTest,SkillContentLoaderTest,SkillPropertiesTest,SkillHandlersTest,CapabilityCatalogSyncServiceTest,SchemaCommentsCoverageTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

- 通过标准：基线 + 消费方测试全绿且字段逐一对齐；`clean verify` 全绿（含 IT）；桌面无回归；`bq_capabilities` 里 `skill.<id>` 行无孤立/无漂移；`SchemaCommentsCoverageTest` 过。
- 真实烟测（可选）：放含 front-matter 的 `SKILL.md` 到 `~/.agents/skills` 与 `<cwd>/.agents/skills`，`skills/list` 列出、`skills/get` 读正文且按 16000 字符截断、`tool_search` 中文 query 命中、切 cwd 后项目技能随之变化、`git clone` 一个生态开源 skill 进 `~/.agents/skills` 可直接被列出。

## 完成报告必须包含

- Step 0–6 逐条 ✅/❌ + 跑过的命令与**实际输出**（非预期）。
- Step 0 golden master 与重构后字段逐一对齐的证据（id/namespace/description/contentHash/截断/`allowedTools`）。
- 「未接任何 skill advisor/hook」「不新增 `bq_*` 表」「id/namespace 算法未变、`skill.<id>` 未漂移」的证据。
- graph-core 编译可见性结论（是否需在 pom 显式声明、版本是否仍 1.1.2.3）。
- cwd 切换重建项目槽的测试证据。
- 中文 conventional commit 列表；明确未 push。

## 与 Codex 的区别（已核对源码，定位准确）

- **相同（借鉴对）**：用户 + 项目目录、`.agents/skills` 中立约定、cwd 感知 + reload、bundled 内置技能、按需读正文、YAML front-matter `name/description`。
- **不同（我们的选择）**：① 薄封装官方 SAA registry，不自研 scanner；② 4 scope 取 user/project 两层（admin/额外走 `additional-directories`）；③ **deferred exposure（`tool_search`）取代 Codex 常驻注入**；④ **不自动执行脚本**（D10），Codex 的沙箱 executor + 审批留未来阶段；⑤ 远程下载/市场（Codex `remote.rs` hazelnuts + `marketplace_cmd.rs`）= BaBiQ 未来独立阶段（plan §10，可复用其 zip 魔数校验 + 路径穿越防护模板）。

## 2026-06-02 独立审查修订（**优先于上文对应条目**）

用户独立审查通过方向，但要求先修订 5 处边界（详见 plan §11）。落地时**以下为准**：

1. **allowedTools 范围（统一）**：**仅元数据透出后端 `SkillInfo`**，**不参与工具授权**（授权仍归 `ToolRegistry`/审批/沙箱）、**不做桌面展示**。上文凡与"可选后续项 / 桌面展示"冲突的，以此为准（plan D7）。
2. **迁移策略（非"行为不变"）**：默认目录切到 `.agents/skills` 是**有意行为变更**；"行为不变"只指代码契约 + `bq_capabilities` 映射。**默认非破坏式**：`additional-directories` **默认携带遗留 `~/.codex/skills` + `~/.codex/superpowers/skills`**（deprecated、可移除），补"默认同时扫 `.agents/skills`+遗留""遗留可移除"测试（plan D9）。彻底 breaking 是 §9 待用户确认的备选。
3. **缓存/刷新语义**：`listSkills()`/`load()` 必须**反映磁盘最新**（保持现状即时性）；官方 `AbstractSkillRegistry` 有缓存 → **读取前 `reload()`/重建**；补"改 `SKILL.md` 后再 `list`/`get` 看到新 `contentHash`/新正文"测试。文件监听 + `skills/changed` 推送（Codex `skills_watcher`）**不在 P3-6**（plan D12）。
4. **桌面 JSON 兼容**：桌面 `protocol/ProtocolJson.kt` 已 `ignoreUnknownKeys=true`（已核对，加字段安全）；仍补桌面 `SkillModelsTest` 反序列化"含 `allowedTools` 的 `skills/list` 载荷"成功不崩（plan D11）。
5. **front-matter 鲁棒性**：Step 0 必须额外覆盖**无 front-matter / 非法 YAML / `description` 含冒号 / 多行 `description`**；官方 `SkillScanner` 比现状手写更严格/正确，**官方语义为准**并记录差异（plan D13）。

**增补验收命令（替换上文验收段）**：

```powershell
cd E:\BaBiQ\backend
.\mvnw.cmd "-Dtest=LocalSkillRegistryTest,SkillContentLoaderTest,SkillPropertiesTest,SkillHandlersTest,CapabilityCatalogSyncServiceTest,SchemaCommentsCoverageTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*SkillModelsTest"
.\gradlew.bat test
```

完成报告额外需附：迁移测试（默认扫 `.agents/skills`+遗留、遗留可移除）、刷新测试（改 `SKILL.md` 即时生效）、front-matter 边界用例、桌面 `SkillModelsTest`（含 `allowedTools`）的实际输出。

## 下一步

- 先解决 §9 唯一待确认项（迁移取向：非破坏式 vs 彻底 breaking），再按 plan §4 + 本修订 TDD 落地。
- 实现并通过 plan §8 验收后：评估是否做可选 ⚠️ 项（富 metadata 的 Java YAML 自解析），或进入 ⏭️ "第三方 skill 受管安装/市场"未来阶段（需先写该阶段详细 plan）。
