# P7 Claude 官方 Provider 接入（API Key + OAuth 双模式）+ 多 Provider 预设增强 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** BaBiQ 新增 Anthropic 官方 Provider，支持 API Key 和官方 OAuth（`ant` CLI 凭证链）两种认证模式，并补齐五类 Provider 预设与"复制 Provider"体验；GPT 维持 API Key，DashScope/DeepSeek/中转链路不变。

**Architecture:** 沿用现有 `ProviderType → ProviderFactory → ChatClientFactory` SPI，薄封装 Spring AI 官方 `spring-ai-anthropic`（BOM 1.1.6 内置版本）新增 `ANTHROPIC` 类型；OAuth 模式不自实现 OAuth client，凭证保管与刷新完全委托官方 Anthropic `ant` CLI，BaBiQ 只在请求时通过 `RestClient`/`WebClient` 拦截器动态注入 `Authorization: Bearer` 并追加 `anthropic-beta: oauth-2025-04-20`；持久层在 `bq_provider_configs` 增加 `auth_mode` 字段（V19 migration）。

**Tech Stack:** Spring AI 1.1.6（`spring-ai-anthropic` / `AnthropicApi` / `AnthropicChatModel`）、Spring AI Alibaba 1.1.2.3（不升级，`ReactAgent` 消费任意 `ChatModel`，无需改动）、SQLite + MyBatis-Plus + Flyway（V19）、JDK `ProcessBuilder`（ant CLI 桥接）、Compose Desktop（设置页扩展）。

**Implementation status (2026-06-12):** 已按本计划完成核心代码修正：后端新增 Anthropic 官方 Provider、`auth_mode` 持久化、API Key/OAuth CLI 双模式、`provider/oauth/status` 与 `provider/oauth/login`；桌面端已接入 `authMode`、Claude OAuth 状态/登录按钮、五类 Provider 预设和复制 Provider。2026-06-12 追加修复：OAuth Bearer 不再写入 build 阶段的 `defaultOptions`，改为 RestClient/WebClient 每次真实请求前动态注入；`provider/test` 对 OAuth Provider 显式检查 CLI token；Apache Ant 误命中会给出 `cli-path` 指引。真实 Anthropic API Key / `ant auth login` 人工烟测仍需在具备账号和 CLI 的本机环境执行，见 §4。
**Automated verification (2026-06-12):** 已完成后端定向测试、后端全量 `cd backend; .\mvnw.cmd clean verify`、桌面定向测试、桌面全量 `cd desktop; .\gradlew.bat test` 和 `cd desktop; .\gradlew.bat test --rerun-tasks`。本次自动化覆盖 Anthropic Provider 工厂、OAuth CLI token/status/login JSON-RPC、`auth_mode` 持久化、V19 schema 注释、Provider 列表兼容、桌面协议模型、设置页预设/复制/OAuth 状态流；追加覆盖 OAuth 头语义（无 `x-api-key`、有动态 Bearer、beta 含 `oauth-2025-04-20`）、API Key fail-fast、TTL 过期重取和 Apache Ant 误判。

---

## 0. 前置查证记录（2026-06-11，已按官方文档 + 本地 jar 复核）

本节是按 CLAUDE.md §4 查证顺序完成的官方能力核对结论，实现中如发现与此不符，停下来重新核对而不是硬改。

**已核对来源（实现者复跑时不得跳过）：**

1. Spring AI 官方参考文档（`https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html`）确认：
   - Spring AI 支持 Anthropic Messaging API 的同步和流式文本生成。
   - 自动配置 starter 是 `spring-ai-starter-model-anthropic`，但手工配置路径支持直接引入 `spring-ai-anthropic`。
   - 手工配置示例就是 `AnthropicApi` + `AnthropicChatOptions.builder().model(...).maxTokens(...)` + `AnthropicChatModel.builder().anthropicApi(...).defaultOptions(...)`。
2. Context7（`/websites/spring_io_spring-ai_reference`）同样返回上述手工配置示例，确认 Java API 路径和官方文档一致。
3. 本地 Maven 可行性已复跑：
   - `cd backend; .\mvnw.cmd -q dependency:get "-Dartifact=org.springframework.ai:spring-ai-anthropic:1.1.6"` 成功。
   - `cd backend; .\mvnw.cmd -q dependency:get "-Dartifact=org.springframework.ai:spring-ai-anthropic:1.1.6:jar:sources"` 成功。
   - 仓库当前 `backend/pom.xml` 已锁定 Spring AI `1.1.6`、Spring AI Alibaba `1.1.2.3`，无需升级。
4. Anthropic 官方 CLI 文档（`https://platform.claude.com/docs/en/cli-sdks-libraries/cli/quickstart`）确认：
   - `ant` 是 Claude API 官方 CLI。
   - `ant auth login` 会打开浏览器 OAuth 流并把凭证保存在本机。
   - `ant messages create --model claude-opus-4-8 --max-tokens 1024 ...` 是官方示例路径。
5. Anthropic 官方模型文档（`https://platform.claude.com/docs/en/about-claude/models/overview`）确认：
   - `claude-opus-4-8`、`claude-sonnet-4-6`、`claude-haiku-4-5` 是当前可用模型别名/ID。
   - Claude API 上 Opus 4.8 和 Sonnet 4.6 为 1M context，Haiku 4.5 为 200K context。
6. Anthropic extended thinking 文档（`https://platform.claude.com/docs/en/build-with-claude/extended-thinking`）确认：
   - Opus 4.8 / Opus 4.7 不接受手动 `thinking: {type: "enabled", budget_tokens: N}`，会返回 400。
   - Sonnet 4.6 虽仍支持手动 thinking，但官方建议 adaptive thinking；本期为避免 Spring AI 1.1.6 旧 thinking 形态踩坑，一律不设置 thinking。

**Spring AI 1.1.6 本地 jar / sources 已核对事实：**

1. `org.springframework.ai:spring-ai-anthropic:1.1.6` 在 Maven Central 真实存在，与仓库锁定的 Spring AI BOM `1.1.6` 同版本，**无需升级任何版本**。starter 坐标为 `spring-ai-starter-model-anthropic`，但 BaBiQ 不用 starter 自动配置（避免 `spring.ai.anthropic.*` 全局单例与多 Provider 冲突），直接依赖 `spring-ai-anthropic` 模块手工构建，与现有 `spring-ai-starter-model-openai` 的用法差异在 Task 1 中说明。
2. v1.1.6 `AnthropicApi.java` 源码关键事实（已逐行核对）：
   - `addDefaultHeadersIfMissing(...)` 只在 `apiKey.getValue()` **非空**时追加 `x-api-key` 头 → 传入 `new SimpleApiKey("")` 即可彻底抑制 `x-api-key`，给 OAuth Bearer 让路（Anthropic API 同时收到两种凭证头会直接拒绝）。
   - `Builder` 接受外部 `RestClient.Builder` / `WebClient.Builder`，构造时 `clone()` 保留我们预置的拦截器/过滤器 → 可在每次请求时动态取最新 access token。
   - `Builder.anthropicBetaFeatures(String)` 可覆盖默认 beta 头（默认值 `tools-2024-04-04,pdfs-2024-09-25,structured-outputs-2025-11-13`），OAuth 模式需在默认值后追加 `,oauth-2025-04-20`。
   - `Builder.baseUrl(String)` 使用 `Assert.hasText(...)`，空值会直接失败；`AnthropicProviderFactory.effectiveBaseUrl(...)` 必须在调用 `.baseUrl(...)` 前把空值替换为 `AnthropicApi.DEFAULT_BASE_URL`。
   - `Builder.build()` 强制 `apiKey` 非空；OAuth 模式必须传 `new SimpleApiKey("")`，不能省略 `apiKey(...)`。
   - `AnthropicChatModel` 走 `/v1/messages`，工具调用（含流式 tool-use 事件聚合 `StreamHelper`）、流式 usage 均为 1.1.6 内置能力；`ChatModel` 接口与 SAA `ReactAgent` 直接兼容。
   - `AnthropicChatModel.Builder` 的确切方法名为 `anthropicApi(...)` / `defaultOptions(...)`；`AnthropicChatOptions.Builder` 支持 `model(String)` / `maxTokens(Integer)` / `temperature(Double)` / `thinking(...)` / `httpHeaders(...)`。
3. Anthropic 官方认证链（Anthropic CLI 文档 + anthropics/skills claude-api shared notes）：`ant auth login` 浏览器登录产生 profile；`ant auth print-credentials --access-token` 输出**裸 access token 且会按需刷新**；HTTP 侧要求 `Authorization: Bearer <token>` + `anthropic-beta: oauth-2025-04-20`（`x-api-key` 与 `Authorization` 同时出现会被拒绝）。Windows 凭证目录为 `%APPDATA%\Anthropic`。
4. Spring AI 1.1.6 的 thinking 选项仍是旧形态 `ThinkingType.ENABLED + budget_tokens`；而 claude-opus-4-7/4-8 已移除 `budget_tokens`（发送即 400）→ **本期一律不设置 thinking 选项**（省略即可在全系模型上工作）。
5. Anthropic Messages API 强制要求 `max_tokens`；Spring AI 1.1.6 `AnthropicChatOptions` 的默认 maxTokens 偏小（官方参考页属性默认 500，本地 `AnthropicChatModel.DEFAULT_MAX_TOKENS` 也不是 agent 场景需要的输出预算），工厂必须显式设置（见 Task 5）。

**实现期间仍必须补充核对：**

- [ ] 用本项目真实 `BaBiQStreamingTokenUsageInterceptor` / `BaBiQTokenUsageHook` 跑一次 Anthropic fake response 或真实 smoke，确认流式最终 usage 能进入 TurnSummary；Spring AI jar 已有 usage 映射，但 BaBiQ hook 链路仍要验证。
- [ ] 用 `ant --version`、`ant auth status`、`ant auth print-credentials --access-token` 在 Windows 本机复跑，确认用户安装路径、profile 和 `%APPDATA%\Anthropic` 行为符合当前 CLI 版本。
- [ ] 确认 packaged desktop 启动的 backend 子进程能看到 `ant` PATH；如果不可见，必须在 UI 中引导填写 `babiq.anthropic.oauth.cli-path` 或写入本地设置。

**明确不做（Out of scope）：**

- GPT/OpenAI 任何形式的 OAuth 或订阅额度接入（维持 API Key；冒充 Codex 客户端的灰色路线明确排除）。
- BaBiQ 自实现 Anthropic OAuth client（无公开第三方 client 注册通道）；不自行持久化 access/refresh token（凭证保管人是官方 `ant` CLI）。
- cc-switch 式本地代理/故障转移/用量看板；Provider 分组标签。
- Spring AI / Spring AI Alibaba 版本升级；Actuator 等观测组件。
- Claude 思考块（ReasoningItem）展示承诺：P5 链路读的是 OpenAI 形态 `reasoningContent` metadata，Anthropic thinking 映射不在本期，烟测仅要求"无报错"。

---

## 1. 决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | 用 Spring AI 官方 `spring-ai-anthropic` 模块手工构建，不用 starter 自动配置 | starter 是全局单例配置（`spring.ai.anthropic.*`），与 BaBiQ 多 Provider 动态构建冲突；现有 DashScope/OpenAI 工厂同为手工构建模式 |
| D2 | OAuth 凭证全权委托官方 `ant` CLI（登录=`ant auth login`，取 token=`ant auth print-credentials --access-token`） | Anthropic 官方文档明确支持应用消费 CLI 凭证链；token 刷新由 CLI 内置；BaBiQ 不碰 refresh token，合规且免去生命周期管理 |
| D3 | OAuth 请求注入：空值 `SimpleApiKey("")` 抑制 `x-api-key` + RestClient 拦截器/WebClient 过滤器每请求动态 `setBearerAuth` + beta 头追加 `oauth-2025-04-20` | v1.1.6 源码核对结论（见 §0）；动态注入使 token 刷新后无需失效 ChatClient 缓存 |
| D4 | `bq_provider_configs` 新增 `auth_mode TEXT NOT NULL DEFAULT 'api_key'`，取值 `api_key` / `oauth_cli` | 复用既有表而非新表；默认值保证存量行为不变 |
| D5 | `ant` CLI 可执行路径可配置（`babiq.anthropic.oauth.cli-path`，默认 `ant`） | Windows 上可能与 Apache Ant 重名冲突，必须留逃生通道 |
| D6 | 新增 JSON-RPC：`provider/oauth/status`、`provider/oauth/login`；不做 logout（设置页提示用 `ant auth logout`） | YAGNI；登录是必须的 UI 动作，登出是低频终端操作 |
| D7 | 预设五类：Claude官方-APIKey / Claude官方-OAuth / DeepSeek官方 / 阿里百炼 / OpenAI兼容中转；外加"复制 Provider" | 用户确认的接入范围；复制满足"同类型多账号"诉求，不引入新的账号分组模型 |
| D8 | `ModelMetadata` 增补 `claude-opus-4-8`(1M)、`claude-sonnet-4-6`(1M)、`claude-haiku-4-5`(200K)；预设默认模型 `claude-sonnet-4-6` | 防止未知模型回退 32K 触发过早压缩（P3 预算依赖此值） |
| D9 | OAuth access token 仅内存缓存（默认 TTL 300s），不进 SecretStore、不进数据库 | token 短期有效且 CLI 可随时再取；落盘只会增加泄露面 |
| D10 | `provider/create` / `provider/update` 先解析 `authMode` 再决定是否要求 `apiKey`；`ANTHROPIC + oauth_cli` 不要求 key，`api_key` 仍要求 key | 当前 `ProviderCreateHandler` 会在 service 前强制 `apiKey`，不改会导致 OAuth Provider 无法创建 |
| D11 | `ant auth login` 不走 15s 短命令 runner；单独做 `AntCliLoginLauncher` 异步启动并允许浏览器交互 | OAuth 登录可能超过 15s，复用 token/status runner 会误杀登录流程 |
| D12 | `provider/test` 在 OAuth 模式下必须触发 `credentialSource.accessToken()` 或等价状态检查，不只构造 `ChatModel` | Spring AI Bearer token 是请求时注入；只 build model 无法发现未安装/未登录 CLI |
| D13 | Claude 官方预设默认填 `https://api.anthropic.com`；后端仍实现 `effectiveBaseUrl` 兜底，允许未来用户把 Anthropic baseUrl 留空 | 兼容当前 handler/service 的 baseUrl 必填习惯，同时不把 SDK 默认地址能力堵死 |

---

## 2. 文件结构总览

```
backend/
├── pom.xml                                                    # 修改：+spring-ai-anthropic
├── src/main/java/com/wzx/babiq/server/
│   ├── model/
│   │   ├── ProviderType.java                                  # 修改：+ANTHROPIC
│   │   ├── ProviderAuthMode.java                              # 新建：认证模式枚举
│   │   ├── ModelProviderConfig.java                           # 修改：+authMode 组件
│   │   ├── ModelMetadata.java                                 # 修改：+3 个 claude 条目
│   │   └── provider/
│   │       └── AnthropicProviderFactory.java                  # 新建：核心工厂
│   ├── settings/
│   │   ├── AntCliRunner.java                                  # 新建：CLI 执行抽象（可 mock）
│   │   ├── DefaultAntCliRunner.java                           # 新建：短命令 ProcessBuilder 实现（status/print-credentials）
│   │   ├── AntCliLoginLauncher.java                           # 新建：长交互 login 启动器，不复用 15s runner
│   │   ├── DefaultAntCliLoginLauncher.java                    # 新建：异步启动 ant auth login
│   │   ├── AnthropicOAuthCredentialSource.java                # 新建：token 获取+缓存
│   │   └── ProviderSettingsService.java                       # 修改：authMode 读写
│   ├── persistence/entity/ProviderConfigEntity.java           # 修改：+auth_mode 字段
│   ├── conversation/repository/ProviderConfigRecord.java      # 修改：+authMode
│   ├── persistence/service/ProviderPersistenceService.java    # 修改：映射 authMode
│   └── api/method/
│       ├── ProviderOAuthStatusHandler.java                    # 新建
│       └── ProviderOAuthLoginHandler.java                     # 新建
├── src/main/resources/
│   ├── db/migration/V19__provider_auth_mode.sql               # 新建
│   └── application.yml                                        # 修改：+babiq.anthropic.oauth.cli-path
└── src/test/java/com/wzx/babiq/server/
    ├── model/provider/AnthropicProviderFactoryTest.java       # 新建
    ├── settings/AnthropicOAuthCredentialSourceTest.java       # 新建
    ├── api/method/ProviderOAuthHandlersTest.java              # 新建
    └── （既有 ModelMetadataTest / ProviderSettingsServiceTest / ProvidersListHandlerTest 增量用例）

desktop/
├── src/main/kotlin/com/wzx/babiq/desktop/
│   ├── protocol/ProviderSettingsModels.kt                     # 修改：+authMode、OAuth 状态模型
│   ├── client/AgentClient.kt                                  # 修改：+2 个 OAuth 方法
│   ├── state/{ChatController,UiModels}.kt                     # 修改：OAuth 状态流
│   └── ui/settings/SettingsPanel.kt                           # 修改：类型/模式/预设/复制
└── src/test/kotlin/...                                        # 对应测试增量
```

---

## 3. 任务分解

### Task 1: 引入 spring-ai-anthropic 依赖

**Files:**
- Modify: `backend/pom.xml`（在 `spring-ai-starter-model-openai` 依赖后）

- [x] **Step 1: 添加依赖（版本由 BOM 1.1.6 管理，不写显式版本号）**

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-anthropic</artifactId>
</dependency>
```

- [x] **Step 2: 验证依赖解析与版本**

Run: `cd backend; .\mvnw.cmd dependency:tree "-Dincludes=org.springframework.ai:spring-ai-anthropic"`
Expected: 出现 `spring-ai-anthropic:jar:1.1.6:compile`，无版本冲突。

- [x] **Step 3: 编译通过即提交**

Run: `cd backend; .\mvnw.cmd -q compile`

```bash
git add backend/pom.xml
git commit -m "build(p7): 引入 spring-ai-anthropic 1.1.6 模块"
```

---

### Task 2: ProviderAuthMode 枚举 + ProviderType.ANTHROPIC + ModelProviderConfig 扩展

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/model/ProviderAuthMode.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/model/ProviderType.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/model/ModelProviderConfig.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/model/ModelProviderRegistryTest.java`（增量用例）

- [x] **Step 1: 写失败测试（authMode 默认值与兼容构造）**

```java
@Test
void anthropic_provider_config_should_default_to_api_key_auth_mode() {
    ModelProviderConfig config = new ModelProviderConfig(
            "claude-official", "Claude 官方", ProviderType.ANTHROPIC,
            "claude-sonnet-4-6", "sk-ant-xxx", null, 1_000_000);
    assertThat(config.effectiveAuthMode()).isEqualTo(ProviderAuthMode.API_KEY);
}

@Test
void oauth_cli_auth_mode_should_be_preserved() {
    ModelProviderConfig config = new ModelProviderConfig(
            "claude-oauth", "Claude OAuth", ProviderType.ANTHROPIC,
            "claude-sonnet-4-6", null, null, 1_000_000, ProviderAuthMode.OAUTH_CLI);
    assertThat(config.effectiveAuthMode()).isEqualTo(ProviderAuthMode.OAUTH_CLI);
}
```

- [x] **Step 2: 跑测试确认编译失败（ANTHROPIC / ProviderAuthMode 不存在）**

Run: `cd backend; .\mvnw.cmd -q "-Dtest=ModelProviderRegistryTest" test`

- [x] **Step 3: 最小实现**

`ProviderAuthMode.java`（全量）：

```java
package com.wzx.babiq.server.model;

/**
 * Provider 认证模式。
 *
 * <p>P7 引入：同一个 Provider 类型可以有不同的凭证形态。API Key 模式沿用
 * SecretStore + secretRef 链路；OAUTH_CLI 模式不在 BaBiQ 内保存任何 token，
 * 由官方 Anthropic `ant` CLI 托管凭证，BaBiQ 仅在请求时动态读取。</p>
 */
public enum ProviderAuthMode {

    /** 传统 API Key，密钥写入 KeyStore，数据库只存 secretRef。 */
    API_KEY,

    /** 官方 ant CLI OAuth 凭证链；BaBiQ 不持久化 token，仅运行时桥接。 */
    OAUTH_CLI
}
```

`ProviderType` 增加：

```java
    /** Anthropic 官方 Messages API，用于 claude-opus-4-8 / claude-sonnet-4-6 等模型。 */
    ANTHROPIC
```

`ModelProviderConfig`：record 末尾追加组件 `ProviderAuthMode authMode`，保留旧 7 参兼容构造器（委托传 `null`），新增：

```java
    /**
     * 返回有效认证模式。
     *
     * <p>authMode 为空代表配置来自旧版本（V19 之前的 yaml 或数据库行），
     * 一律按 API_KEY 处理，保证存量 Provider 行为不变。</p>
     */
    public ProviderAuthMode effectiveAuthMode() {
        return authMode == null ? ProviderAuthMode.API_KEY : authMode;
    }
```

同时同步 `toString()` 输出 authMode（不输出密钥），并修复所有现有构造点编译（`BaBiQProperties` yaml 绑定记录、`ProviderSettingsService`、`ProviderTestController`、相关测试——兼容构造器应使绝大多数点零改动）。

- [x] **Step 4: 跑测试通过 + 全模块编译**

Run: `cd backend; .\mvnw.cmd -q "-Dtest=ModelProviderRegistryTest,ModelMetadataTest,ChatClientFactoryTest" test`

- [x] **Step 5: Commit**

```bash
git commit -m "feat(p7): 新增 ANTHROPIC Provider 类型与认证模式枚举"
```

---

### Task 3: V19 migration + 持久层 authMode 贯通

**Files:**
- Create: `backend/src/main/resources/db/migration/V19__provider_auth_mode.sql`
- Modify: `ProviderConfigEntity.java`、`ProviderConfigRecord.java`、`ProviderPersistenceService.java`
- Test: `SchemaCommentsCoverageTest`（自动覆盖）、`RepositoryAdapterIT` 或 `ProviderSettingsServiceTest` 增量用例

- [x] **Step 1: 写失败测试（record 往返保留 authMode）**

在 `ProviderSettingsServiceTest` 增加：创建 `auth_mode=oauth_cli` 的 Provider → 重新加载 → authMode 不丢失、secretRef 允许为空。

- [x] **Step 2: 跑测试失败**

- [x] **Step 3: 写 migration（注意三件套：SQL 注释、bq_schema_comments、实体中文注释）**

```sql
-- P7：Provider 认证模式。api_key=KeyStore 密钥；oauth_cli=官方 ant CLI 托管 OAuth 凭证。
-- 默认 api_key 保证 V19 之前的存量 Provider 行为完全不变。
ALTER TABLE bq_provider_configs ADD COLUMN auth_mode TEXT NOT NULL DEFAULT 'api_key';

INSERT OR REPLACE INTO bq_schema_comments(table_name, column_name, comment) VALUES
('bq_provider_configs', 'auth_mode', 'Provider 认证模式：api_key 走 KeyStore secretRef；oauth_cli 走官方 ant CLI OAuth 凭证链，不在库内保存任何 token。');
```

实体字段（含中文注释，说明写入来源/读取方/空值语义）：

```java
    /**
     * 认证模式；由设置服务写入，ChatClientFactory 经 ProviderFactory 读取。
     * 旧数据默认 api_key；oauth_cli 行的 secret_ref 允许为空。
     */
    @TableField("auth_mode")
    private String authMode;
```

`ProviderConfigRecord` 增加 `String authMode` 组件并更新 `of(...)`；`ProviderPersistenceService` 双向映射。

- [x] **Step 4: 验证**

Run: `cd backend; .\mvnw.cmd -q "-Dtest=SchemaCommentsCoverageTest,ProviderSettingsServiceTest" test`
Expected: PASS（coverage 测试会强制 auth_mode 有非空中文说明）。

- [x] **Step 5: Commit**

```bash
git commit -m "feat(p7): V19 增加 provider auth_mode 字段并贯通持久层"
```

---

### Task 4: AntCliRunner + AntCliLoginLauncher + AnthropicOAuthCredentialSource

**Files:**
- Create: `settings/AntCliRunner.java`、`settings/DefaultAntCliRunner.java`、`settings/AntCliLoginLauncher.java`、`settings/DefaultAntCliLoginLauncher.java`、`settings/AnthropicOAuthCredentialSource.java`
- Modify: `application.yml`（`babiq.anthropic.oauth.cli-path: ant`、`token-cache-seconds: 300`）+ 对应 `BaBiQProperties` 节点
- Test: `settings/AnthropicOAuthCredentialSourceTest.java`、`settings/AntCliLoginLauncherTest.java`

- [x] **Step 1: 写失败测试（mock AntCliRunner，覆盖 4 个行为）**

```java
@Test
void should_return_trimmed_access_token_from_cli() {
    AntCliRunner runner = mock(AntCliRunner.class);
    when(runner.run("auth", "print-credentials", "--access-token"))
            .thenReturn(new AntCliResult(0, "sk-ant-oat01-abc\n", ""));
    var source = new AnthropicOAuthCredentialSource(runner, Duration.ofSeconds(60), Clock.systemUTC());
    assertThat(source.accessToken()).isEqualTo("sk-ant-oat01-abc");
}

@Test
void should_cache_token_within_ttl_and_refetch_after_expiry() { /* 用可控 Clock 验证只调一次/过期后再调 */ }

@Test
void should_throw_clear_error_when_cli_missing_or_not_logged_in() {
    // exit code 非 0 或 stdout 为空 → IllegalStateException，消息包含「ant auth login」指引
}

@Test
void status_should_report_installed_and_logged_in_flags() {
    // ant auth status 输出 → OAuthStatus(installed, loggedIn, detail 脱敏)
    // 注意：官方 shared notes 明确提示不要只把 exit code 当健康检查。
}

@Test
void login_launcher_should_not_use_short_command_timeout() {
    // ant auth login 是浏览器交互流程，不能被 DefaultAntCliRunner 的 15s 超时误杀。
    // 断言 ProviderOAuthLoginHandler 调用 AntCliLoginLauncher.startLogin()，而不是 runner.run("auth","login")。
}
```

- [x] **Step 2: 跑测试失败**

Run: `cd backend; .\mvnw.cmd -q "-Dtest=AnthropicOAuthCredentialSourceTest" test`

- [x] **Step 3: 最小实现**

`AntCliRunner`：接口 + `AntCliResult(int exitCode, String stdout, String stderr)`；`DefaultAntCliRunner` 用 `ProcessBuilder`（可配置二进制路径；Windows 下若用户装了 Apache Ant 同名命令，靠 `cli-path` 指到 Anthropic CLI 全路径），**只用于短命令**：`ant --version`、`ant auth status`、`ant auth print-credentials --access-token`，超时 15s 销毁进程。

`AntCliLoginLauncher`：单独接口，职责只有启动 `ant auth login`：

- `startLogin()` 立即返回 `AntCliLoginStartResult(started, message)`，不得阻塞 WebSocket 线程。
- 实现用独立 executor 或 `ProcessBuilder.start()` 后托管进程句柄，不套 15s timeout；登录完成状态由桌面端轮询 `provider/oauth/status` 获得。
- Windows 打包场景下，若 `cli-path=ant` 解析到 Apache Ant 或不可执行，返回明确安装/配置指引，不吞异常。

`AnthropicOAuthCredentialSource`：

- `accessToken()`：`synchronized` TTL 内存缓存（token + 过期时间戳，注释说明为什么不用 SecretStore——见 D9）；CLI 失败抛带 `ant auth login` / `cli-path` 指引的 `IllegalStateException`。
- `status()`：先执行 `ant --version` 判断 CLI 是否可用并排除 Apache Ant 误命中；再复用 `accessToken()` 作为 loggedIn 的强判定。状态文案必须截断且不得包含 token。
- `accessToken()` 必须调用 `ant auth print-credentials --access-token`，不能调用无 flag 的 `print-credentials`，因为无 flag 输出的是 credentials JSON，不能直接放进 Authorization header。
- 行级中文注释覆盖：进程超时、缓存过期语义、为什么 stdout 只取首行非空内容。

- [x] **Step 4: 跑测试通过**

- [x] **Step 5: Commit**

```bash
git commit -m "feat(p7): 新增 ant CLI 桥接与 OAuth 凭证源"
```

---

### Task 5: AnthropicProviderFactory（核心）

**Files:**
- Create: `model/provider/AnthropicProviderFactory.java`
- Test: `model/provider/AnthropicProviderFactoryTest.java`

- [x] **Step 1: 写失败测试**

```java
@Test
void api_key_mode_should_build_anthropic_chat_model() {
    var factory = new AnthropicProviderFactory(credentialSource);
    ChatModel model = factory.build(configWith(ProviderAuthMode.API_KEY, "sk-ant-xxx"));
    assertThat(model).isInstanceOf(AnthropicChatModel.class);
}

@Test
void api_key_mode_should_fail_fast_when_key_missing() {
    assertThatThrownBy(() -> factory.build(configWith(ProviderAuthMode.API_KEY, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-key");
}

@Test
void oauth_mode_should_not_require_api_key() {
    ChatModel model = factory.build(configWith(ProviderAuthMode.OAUTH_CLI, null));
    assertThat(model).isInstanceOf(AnthropicChatModel.class);
}

@Test
void oauth_interceptor_should_inject_bearer_and_skip_x_api_key() {
    // 直接测 factory 暴露的 ClientHttpRequestInterceptor：
    // 构造 MockClientHttpRequest 执行拦截器后断言
    // headers.getFirst("Authorization") == "Bearer <token>" 且不包含 x-api-key。
}

@Test
void oauth_beta_features_should_append_oauth_beta() {
    assertThat(AnthropicProviderFactory.oauthBetaFeatures())
            .startsWith(AnthropicApi.DEFAULT_ANTHROPIC_BETA_VERSION)
            .endsWith(",oauth-2025-04-20");
}
```

- [x] **Step 2: 跑测试失败**

- [x] **Step 3: 实现（骨架，builder 方法名已在 §0 通过 Spring 官方文档、Context7 和本地 1.1.6 jar 确认）**

```java
/**
 * Anthropic 官方 Provider 工厂。
 *
 * <p>API Key 模式直接走 Spring AI 默认 x-api-key 链路；OAuth 模式利用 v1.1.6
 * AnthropicApi 源码核对结论：空值 ApiKey 会被 addDefaultHeadersIfMissing 跳过，
 * 因此用空 SimpleApiKey + RestClient 拦截器/WebClient 过滤器在每次请求时注入
 * Authorization: Bearer（token 来自 ant CLI，可被刷新，所以不能静态写死），
 * 并在 anthropic-beta 默认值后追加 oauth-2025-04-20。</p>
 */
@Component
public class AnthropicProviderFactory implements ProviderFactory {

    /** Anthropic OAuth 必需的 beta 标记；与 x-api-key 互斥，详见 plan §0。 */
    static final String OAUTH_BETA = "oauth-2025-04-20";
    /** Messages API 强制 max_tokens；Spring AI 默认值过小，按 agent 输出需要显式放大。 */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final AnthropicOAuthCredentialSource credentialSource;

    @Override
    public ProviderType supports() { return ProviderType.ANTHROPIC; }

    @Override
    public ChatModel build(ModelProviderConfig config) {
        AnthropicApi api = switch (config.effectiveAuthMode()) {
            case API_KEY -> apiKeyClient(config);
            case OAUTH_CLI -> oauthClient(config);
        };
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(config.model())
                        .maxTokens(DEFAULT_MAX_TOKENS)
                        .build())
                .build();
    }

    private AnthropicApi apiKeyClient(ModelProviderConfig config) {
        requireText(config.apiKey(), "api-key", config);
        return AnthropicApi.builder()
                .baseUrl(effectiveBaseUrl(config))
                .apiKey(new SimpleApiKey(config.apiKey()))
                .build();
    }

    private AnthropicApi oauthClient(ModelProviderConfig config) {
        return AnthropicApi.builder()
                .baseUrl(effectiveBaseUrl(config))
                // 空值 ApiKey：v1.1.6 源码保证不会发出 x-api-key 头（与 Bearer 互斥）。
                .apiKey(new SimpleApiKey(""))
                .anthropicBetaFeatures(oauthBetaFeatures())
                .restClientBuilder(RestClient.builder().requestInterceptor(bearerInterceptor()))
                .webClientBuilder(WebClient.builder().filter(bearerFilter()))
                .build();
    }

    /** 每次请求动态取 token：刷新后的新 token 立即生效，无需失效 ChatClient 缓存。 */
    ClientHttpRequestInterceptor bearerInterceptor() {
        return (request, body, execution) -> {
            request.getHeaders().setBearerAuth(credentialSource.accessToken());
            return execution.execute(request, body);
        };
    }

    ExchangeFilterFunction bearerFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request ->
                Mono.fromSupplier(credentialSource::accessToken)
                        .map(token -> ClientRequest.from(request)
                                .headers(headers -> headers.setBearerAuth(token))
                                .build()));
    }

    static String oauthBetaFeatures() {
        return AnthropicApi.DEFAULT_ANTHROPIC_BETA_VERSION + "," + OAUTH_BETA;
    }
}
```

（实现时补齐 `effectiveBaseUrl`——为空用 `AnthropicApi.DEFAULT_BASE_URL`、`requireText` 与 OpenAI 工厂同款报错文案、全部中文注释。）

- [x] **Step 4: 跑测试通过**

Run: `cd backend; .\mvnw.cmd -q "-Dtest=AnthropicProviderFactoryTest" test`

- [x] **Step 5: Commit**

```bash
git commit -m "feat(p7): 实现 Anthropic 双模式 ProviderFactory"
```

---

### Task 6: ModelMetadata 增补 + 设置服务 / JSON-RPC 保存与测试连接打通

**Files:**
- Modify: `ModelMetadata.java`、`ProviderSettingsService.java`、`ProviderTestHandler.java`、`ProviderCreateHandler.java`、`ProviderUpdateHandler.java`、`ProviderPayloadMapper.java`（沿现有 Provider 设置路径，先读后改）
- Test: `ModelMetadataTest`、`ProviderSettingsServiceTest`、`ProviderSettingsHandlersTest` 增量用例

- [x] **Step 1: 失败测试**：
  - `contextWindowOf("claude-opus-4-8") == 1_000_000`、`claude-sonnet-4-6 == 1_000_000`、`claude-haiku-4-5 == 200_000`。
  - `provider/create` 请求 `type=ANTHROPIC, authMode=oauth_cli, apiKey=null` 能进入 service 并创建成功；不能被 `ProviderCreateHandler.draftFrom(..., true)` 提前拦截。
  - `provider/create` 请求 `type=ANTHROPIC, authMode=api_key, apiKey=null` 仍失败，错误消息明确要求 API Key。
  - `provider/create` 请求 `type=ANTHROPIC, authMode=oauth_cli, baseUrl=""` 可保存；持久化时 baseUrl 规范化为空字符串，运行时工厂用 `AnthropicApi.DEFAULT_BASE_URL`。
  - `provider/list` / `provider/update` 响应包含 `authMode`，且永远不包含明文 `apiKey` 或 OAuth token。
  - `provider/test` 对 API Key 模式仍复用 `ChatClientFactory.resolveChatModel`；对 OAuth 模式必须先调用 `AnthropicOAuthCredentialSource.accessToken()` 或等价强判定，未安装/未登录时返回失败，而不是只 build model 后误报成功。
- [x] **Step 2: 跑失败**
- [x] **Step 3: 实现**
  - `ModelMetadata` 加 `// Anthropic 系列（P7）` 注释块。
  - `ProviderDraft` 增加 `authMode` 字段；`ProviderCreateHandler` / `ProviderUpdateHandler` 先读取 `type` + `authMode`，再决定 `apiKey` 和 `baseUrl` 是否必填。
  - `ProviderSettingsService.validateRequired(...)` 按 `ProviderType` + `ProviderAuthMode` 分支：`ANTHROPIC + OAUTH_CLI` 不要求 apiKey；`ANTHROPIC` 允许 baseUrl 为空；其他既有 Provider 行为不变。
  - `ProviderPayloadMapper` 输出 `authMode`，旧桌面端未消费也不破坏兼容。
  - `ProviderTestHandler` / service 测试连接对 OAuth 模式触发 credential source 检查，错误文案包含 `ant auth login` 或 `cli-path` 指引。
- [x] **Step 4: 跑通过**
- [x] **Step 5: Commit** `feat(p7): 设置服务与测试连接支持 Anthropic 双模式`

---

### Task 7: provider/oauth/* JSON-RPC 方法

**Files:**
- Create: `api/method/ProviderOAuthStatusHandler.java`、`api/method/ProviderOAuthLoginHandler.java`
- Test: `api/method/ProviderOAuthHandlersTest.java`

- [x] **Step 1: 失败测试**：
  - `provider/oauth/status` 返回 `{installed, loggedIn, detail}`；detail 脱敏、截断，不包含 `sk-ant-`、`Authorization`、refresh token。
  - `provider/oauth/login` 触发 `AntCliLoginLauncher.startLogin()`，立即返回 `{started:true}`，不阻塞 WebSocket 线程，也不使用 `DefaultAntCliRunner` 的 15s timeout。
  - CLI 未安装、`cli-path` 指向 Apache Ant 或不可执行时，返回明确错误码与安装/配置指引文案。
- [x] **Step 2: 跑失败**
- [x] **Step 3: 实现**（沿现有 `JsonRpcMethodHandler` 模式注册；login 由 `AntCliLoginLauncher` 启动，浏览器由 CLI 自己拉起；中文注释说明为什么不等待登录完成——桌面端轮询 status）
- [x] **Step 4: 跑通过**
- [x] **Step 5: Commit** `feat(p7): 新增 provider/oauth 状态与登录协议方法`

---

### Task 8: 桌面端协议模型 + AgentClient

**Files:**
- Modify: `desktop/.../protocol/ProviderSettingsModels.kt`（`authMode` 字段，默认 `"api_key"` 保证旧后端兼容；`OAuthStatus` 模型）
- Modify: `desktop/.../client/AgentClient.kt`（`providerOAuthStatus()`、`providerOAuthLogin()`）
- Test: `SettingsModelsTest`、`AgentClientTest` 增量用例

- [x] **Step 1: 失败测试**（序列化往返含 authMode；缺省字段反序列化为 api_key；两个新方法的请求帧/响应解析） → **Step 2 跑失败** → **Step 3 实现** → **Step 4 跑通过**
- [x] **Step 5: Commit** `feat(p7): 桌面端 Provider 认证模式与 OAuth 协议模型`

---

### Task 9: 设置页 UI（类型/模式/预设/复制）

**Files:**
- Modify: `desktop/.../ui/settings/SettingsPanel.kt`、`state/ChatController.kt`、`state/UiModels.kt`
- Test: `SettingsPanelTest`、`ChatControllerTest` 增量用例

- [x] **Step 1: 失败测试**：
  - 类型下拉含「Claude 官方」；选中且 authMode=oauth_cli 时隐藏 API Key 输入、显示 OAuth 状态区（未安装→安装指引；未登录→“去登录”按钮触发 `provider/oauth/login` 并开始轮询 status；已登录→绿色状态 chip）。
  - 预设按钮组 5 项（Claude官方-APIKey / Claude官方-OAuth / DeepSeek官方 / 阿里百炼 / OpenAI兼容中转），点选后表单预填。两个 Claude 官方预设都填 `baseUrl=https://api.anthropic.com`、模型默认 `claude-sonnet-4-6`、contextWindow `1000000`；OAuth 预设 `apiKey=null` 且 `authMode=oauth_cli`。
  - Provider 列表项新增「复制」：复制除 secretRef 外的全部字段，名称追加「-副本」，新行处于未保存编辑态。
- [x] **Step 2 跑失败** → **Step 3 实现**（沿现有 SettingsPanel 表单模式；轮询用现有协程作用域，间隔 2s、登录成功或 60s 超时停止）→ **Step 4 跑通过**
- [x] **Step 5: Commit** `feat(p7): 设置页支持 Claude 双模式、五类预设与复制 Provider`

---

### Task 10: 全量验证 + 文档同步

- [x] **Step 1: 后端全量**

```powershell
cd backend
.\mvnw.cmd "-Dtest=AnthropicProviderFactoryTest,AnthropicOAuthCredentialSourceTest,ProviderOAuthHandlersTest,ProviderSettingsServiceTest,ProviderSettingsHandlersTest,ModelMetadataTest,ModelProviderRegistryTest,SchemaCommentsCoverageTest" test
.\mvnw.cmd "-Dtest=AntCliLoginLauncherTest,AnthropicOAuthCredentialSourceTest,ProviderOAuthHandlersTest,ProviderSettingsHandlersTest" test
.\mvnw.cmd clean verify
```

Expected: 全绿，0 失败 0 跳过；`SchemaCommentsCoverageTest` 覆盖 auth_mode。

- [x] **Step 2: 桌面端全量**

```powershell
cd desktop
.\gradlew.bat test --tests "*SettingsModelsTest" --tests "*AgentClientTest" --tests "*SettingsPanelTest" --tests "*ChatControllerTest"
.\gradlew.bat test --rerun-tasks
```

- [x] **Step 3: 同步 CLAUDE.md / AGENTS.md**（当前检查点 + P7 验收命令 + 阶段边界：GPT OAuth 明确不做）。
- [ ] **Step 4: Commit** `docs(p7): 同步 Claude Provider 双模式检查点与验收命令`

---

## 4. 人工烟测清单（自动化通过后、声称完成前必做）

需要真实环境：Anthropic API Key 一枚；已安装官方 `ant` CLI 并可 `ant auth login` 的账号。

0. **CLI 预检**：在与桌面端启动后端相同的用户环境里执行 `ant --version`、`ant auth status`、`ant auth print-credentials --access-token`；确认命中的不是 Apache Ant，且 access token 命令只输出裸 token。若桌面打包后 PATH 不可见，先配置 `babiq.anthropic.oauth.cli-path` 再继续。

1. **API Key 模式**：设置页用预设创建 Claude官方-APIKey → 测试连接通过 → 发起真实 turn「读取 README.md 并总结」→ 工具调用、审批弹窗、流式输出、TurnSummary token 数均正常。
2. **OAuth 模式**：未登录状态创建 Claude官方-OAuth → 状态区显示未登录 → 点「去登录」浏览器拉起并完成授权 → 状态翻绿 → 测试连接通过 → 真实 turn 跑通；期间后端日志确认请求无 `x-api-key`、带 `anthropic-beta: oauth-2025-04-20`（脱敏日志，不打印 token）。
3. **互斥回归**：DashScope / DeepSeek V4 / 中转 Provider 各跑一轮 turn，确认 P7 改动零回退（特别是 DeepSeek V4 thinking 适配器）。
4. **降级路径**：把 `cli-path` 指向不存在的命令 → status 返回未安装且 UI 给指引、不崩溃；`ant auth logout` 后跑 turn → 报错信息含「ant auth login」指引而非裸 401。
5. **窗口预算**：OAuth Provider 上下文 chip 显示 1M 窗口（非 32K 回退）。

## 5. 风险与回退

| 风险 | 缓解 |
|---|---|
| `ant` 与 Apache Ant 命令名冲突（Windows） | D5 可配置 cli-path；status 探测失败时 UI 明确提示 |
| Anthropic OAuth beta 头语义变化 | OAUTH_BETA 常量集中一处；烟测 #2 验真 |
| 1.1.6 `AnthropicChatModel` usage 映射缺口导致 TurnSummary 无 token | §0 补充核对要求必须覆盖 BaBiQ hook 链路；若缺失，按 DeepSeekV4 适配器先例做薄适配并在 handoff 记录 |
| record 加组件引发的广泛编译改动 | 兼容构造器兜底；Task 2 Step 4 全模块编译卡点 |
| OAuth token 过期瞬间 401 | CredentialSource TTL 300s；首版接受单次失败重试由上层 turn 重发 |
