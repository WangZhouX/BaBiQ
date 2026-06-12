# P7 Claude Provider 多认证模式 - Codex 交接

> 完整计划见：`docs/superpowers/plans/p7-claude-provider-multi-auth/plan.md`
> 当前实现提交：`dd765da feat(p7): 接入 Claude 双模式 Provider 与桌面预设`
> 锁定技术栈：Spring AI `1.1.6`、Spring AI Alibaba `1.1.2.3`、Java 21、SQLite + MyBatis-Plus + Flyway、Compose Desktop。

## 当前状态

- **代码已完成并通过自动化验证**：后端新增 Anthropic 官方 Provider，支持 `api_key` 与 `oauth_cli` 两种认证模式；桌面端设置页已支持 Claude 双模式、OAuth 状态/登录按钮、五类 Provider 预设和复制 Provider。
- **后端双模式链路已落地**：`ProviderType.ANTHROPIC`、`ProviderAuthMode`、`AnthropicProviderFactory`、`AnthropicOAuthCredentialSource`、`provider/oauth/status`、`provider/oauth/login` 已实现。
- **2026-06-12 缓存语义修复已落地**：OAuth Bearer 不再在 `ChatModel` build 阶段写入 `defaultOptions`；`AnthropicProviderFactory` 改为 RestClient/WebClient 每次真实请求前动态读取 CLI access token，避免 `ChatClientFactory` 按 providerId 缓存后冻结短期 token。
- **2026-06-12 自动化语义补齐**：测试已覆盖 OAuth 请求无 `x-api-key`、带动态 `Authorization: Bearer`、`anthropic-beta` 包含 `oauth-2025-04-20`、API Key 模式缺 key fail-fast、TTL 过期重取、Apache Ant 误判提示和 `provider/test` OAuth 登录状态检查。
- **持久化已落地**：V19 migration 为 `bq_provider_configs` 增加 `auth_mode`，默认 `api_key`，并同步 `bq_schema_comments` 与 Entity 中文注释。
- **桌面端已接入**：协议模型新增 `authMode` 与 OAuth DTO，`AgentClient`/`ChatController`/`SettingsPanel` 已贯通状态查询、登录启动、Provider 保存和复制。
- **真实 Anthropic 人工烟测未执行**：需要本机具备 Anthropic API Key、官方 `ant` CLI、已完成 `ant auth login` 的账号环境。

## 本次实现证据

### 后端

- `backend/pom.xml` 引入 `org.springframework.ai:spring-ai-anthropic`，版本由 Spring AI BOM `1.1.6` 管理。
- `backend/src/main/java/com/wzx/babiq/server/model/ProviderType.java` 新增 `ANTHROPIC`。
- `backend/src/main/java/com/wzx/babiq/server/model/ProviderAuthMode.java` 定义 wire 值：`api_key` / `oauth_cli`。
- `backend/src/main/java/com/wzx/babiq/server/model/ModelProviderConfig.java` 支持 `authMode`，并保留旧构造器兼容。
- `backend/src/main/java/com/wzx/babiq/server/model/provider/AnthropicProviderFactory.java` 手工构建 `AnthropicApi` + `AnthropicChatModel`：
  - API Key 模式走 Spring AI 标准 `x-api-key`。
  - OAuth CLI 模式用空 `SimpleApiKey("")` 抑制 `x-api-key`，通过 RestClient/WebClient 拦截器在每次真实请求前动态注入 `Authorization: Bearer <token>`。
  - OAuth 模式追加 `anthropic-beta: oauth-2025-04-20`。
  - 默认 `maxTokens=4096`，避免 Anthropic Messages API 缺少 `max_tokens`。
- `backend/src/main/java/com/wzx/babiq/server/settings/AnthropicOAuthCredentialSource.java` 通过官方 `ant auth print-credentials --access-token` 获取短期 access token，并只做内存 TTL 缓存；`status()` 会识别 Apache Ant 误命中并提示配置 `babiq.anthropic.oauth.cli-path`。
- `backend/src/main/java/com/wzx/babiq/server/settings/DefaultAntCliLoginLauncher.java` 异步启动 `ant auth login`，不阻塞 JSON-RPC/WebSocket 线程。
- `backend/src/main/java/com/wzx/babiq/server/api/method/ProviderOAuthStatusHandler.java` / `ProviderOAuthLoginHandler.java` 新增 OAuth 状态和登录协议。
- `backend/src/main/resources/application.yml` 新增内置 `claude-oauth` Provider，以及 `babiq.anthropic.oauth.cli-path`、timeout、token cache 配置。

### 桌面端

- `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ProviderSettingsModels.kt` 增加 `authMode`、`ProviderOAuthStatusResult`、`ProviderOAuthLoginResult`。
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt` 增加 `getProviderOAuthStatus()`、`startProviderOAuthLogin()`。
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt` 增加 OAuth 状态刷新和登录启动流程。
- `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsPanel.kt` 增加：
  - Provider 类型 `Anthropic`。
  - 认证模式 `API Key` / `Claude CLI OAuth`。
  - 五类预设：Claude 官方 API Key、Claude 官方 OAuth、DeepSeek 官方、阿里百炼、OpenAI 兼容中转。
  - Provider 复制能力，复制时不复制敏感密钥。

## 已通过验证

```powershell
cd E:\BaBiQ\.worktrees\p7-claude-provider-multi-auth\backend
.\mvnw.cmd -q "-Dtest=ProviderSettingsServiceTest,ProviderSettingsHandlersTest,ModelMetadataTest" test
.\mvnw.cmd -q "-Dtest=AnthropicOAuthCredentialSourceTest,AnthropicProviderFactoryTest,ProviderOAuthHandlersTest" test
.\mvnw.cmd -q "-Dtest=ProviderSettingsServiceTest,ProviderSettingsHandlersTest,ModelMetadataTest,ModelProviderRegistryTest,AnthropicOAuthCredentialSourceTest,AnthropicProviderFactoryTest,ProviderOAuthHandlersTest,SchemaCommentsCoverageTest,SQLiteMigrationIT" test
.\mvnw.cmd -q "-Dtest=ProviderTestControllerIntegrationTest" test
.\mvnw.cmd clean verify

cd E:\BaBiQ\backend
.\mvnw.cmd -q "-Dtest=AnthropicProviderFactoryTest,AnthropicOAuthCredentialSourceTest,ProviderSettingsServiceTest" test
.\mvnw.cmd -q "-Dtest=AnthropicProviderFactoryTest,AnthropicOAuthCredentialSourceTest,ProviderOAuthHandlersTest,ProviderSettingsServiceTest,ProviderSettingsHandlersTest,ModelMetadataTest,ModelProviderRegistryTest,ProviderTestControllerIntegrationTest,SchemaCommentsCoverageTest" test
.\mvnw.cmd clean verify

cd E:\BaBiQ\.worktrees\p7-claude-provider-multi-auth\desktop
.\gradlew.bat test --tests "*SettingsModelsTest" --tests "*AgentClientTest" --tests "*SettingsPanelTest" --tests "*ChatControllerTest"
.\gradlew.bat test --tests "*SettingsPanelTest"
.\gradlew.bat test
.\gradlew.bat test --rerun-tasks

cd E:\BaBiQ\desktop
.\gradlew.bat test
.\gradlew.bat test --rerun-tasks
```

以上命令均已成功退出。`clean verify` 覆盖 V19 migration、`SchemaCommentsCoverageTest`、JSON-RPC 注册和后端集成测试；桌面端 `--rerun-tasks` 为真执行。2026-06-12 增量验证覆盖 OAuth 动态 Bearer、头语义、TTL 过期重取、Apache Ant 误判、`provider/test` OAuth 登录检查，以及桌面全量回归。

## 尚未完成的人工烟测

需要真实环境：

- Anthropic API Key。
- 官方 `ant` CLI，且命令不是 Apache Ant。
- 已完成 `ant auth login` 的本机账号环境。

建议按此顺序补验：

1. 在桌面端后端子进程相同用户环境里执行 `ant --version`、`ant auth status`、`ant auth print-credentials --access-token`，确认 CLI 可见且 access token 命令只输出裸 token。
2. Claude API Key Provider：创建预设、测试连接、真实 turn、观察流式输出、工具调用、TurnSummary token。
3. Claude OAuth Provider：未登录状态提示、点击登录拉起浏览器、登录完成后状态变绿、测试连接、真实 turn。
4. 确认 OAuth 请求不带 `x-api-key`，带 `Authorization: Bearer` 和 `anthropic-beta: oauth-2025-04-20`。日志不得打印 token。
5. 回归 DashScope、DeepSeek V4、中转 Provider，确认 P7 未破坏既有 Provider。

## 已知边界

- 不实现 GPT/OpenAI OAuth；GPT/OpenAI 兼容线路仍只走 API Key。
- 不在 BaBiQ 内保存 Anthropic access token 或 refresh token；凭证托管给官方 `ant` CLI。
- 不升级 Spring AI / Spring AI Alibaba。
- 不承诺 Anthropic thinking 到 P5 `ReasoningItem` 的映射；本期只保证 Claude Provider 可运行。
- `provider/test` 的 OAuth 模式会触发 CLI token/status 检查；无 CLI 或未登录时应返回带 `ant auth login` / `cli-path` 指引的错误。

## 下一步

1. 在真实 Anthropic 环境补人工烟测。
2. 若 smoke 通过，更新本 handoff 和 `plan.md` 的人工烟测结果。
3. 如需继续收口，可在新的 P7 收口阶段处理真实模型失败重试、Anthropic thinking 到 P5 `ReasoningItem` 映射或更细的 CLI 诊断文案。
