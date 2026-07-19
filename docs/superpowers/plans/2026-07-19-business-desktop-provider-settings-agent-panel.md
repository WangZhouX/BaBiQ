# Business Desktop Provider Settings and Agent Panel Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `business-desktop` 中提供可持久化、可安全编辑自定义模型的 Provider 设置页，并让宽屏/中屏右侧业务 Agent 面板可展开和收起。

**Architecture:** 继续复用后端现有 `provider/*` JSON-RPC 与 SQLite/JCEKS 真相源，先收紧 Provider 事务、密钥生命周期、active fallback 和 business identity 访问边界，再扩展独立的业务客户端协议与 `BusinessProviderSettingsController`。Compose 中央工作区按真实导航切换资料录入/设置/占位页，右侧 Agent 折叠状态由 `Main` 提升持有，compact 模式使用“资料录入 / 设置 / Agent”三个完整页签。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、SQLite、JCEKS、JSON-RPC 2.0、Kotlin、Kotlin Coroutines/StateFlow、Compose Desktop、JUnit 5、Mockito、kotlin.test、Compose UI Test、Gradle、Maven。

---

## 实施前提与文件职责

- 规格真相源：`docs/superpowers/specs/2026-07-19-business-desktop-provider-settings-agent-panel-design.md`。
- 当前分支已经是独立功能分支 `codex/lawyer-oa-desktop`，不再创建额外 worktree。
- 不新增数据库表或 migration；复用 `bq_provider_configs`、`bq_app_settings` 与 JCEKS。
- `.tmp-gradle-review/` 是用户未跟踪目录，所有提交必须显式列文件，禁止纳入。
- 计划文档提交后、Task 1 开始前执行 `git config --local codex.business-provider-settings-base (git rev-parse HEAD)`，把基线持久化到当前仓库本地 Git 配置；任何 shell/子 Agent 都通过 `git config --local --get codex.business-provider-settings-base` 重新读取。最终所有差异检查都使用该 base 到 `HEAD`，不得只看最后一笔提交。
- `ProviderSettingsService.java`：Provider 校验、事务、密钥补偿、bootstrap、运行时 registry 同步。
- `AppSettingsService.java`：active provider 等设置必须先提交数据库，再更新 registry。
- `BusinessProviderModels.kt` / `BusinessAgentClient.kt`：只承载非敏感 Provider 元数据和 JSON-RPC 调用。
- `BusinessProviderSettingsController.kt`：设置页状态与命令，不保存 API Key，不接管连接或事件流生命周期。
- `BusinessProviderSettingsPanel.kt`：表单局部持有 API Key，保存请求结束立即清空。
- `BusinessDesktopShell.kt` / `BusinessDesktopLayoutPolicy.kt`：真实导航、compact 三页签、Agent rail 宽度与中央区扩展。

## Chunk 1: 后端 Provider 真相源与安全边界

### Task 1: 用失败测试锁定 Provider 创建、更新和密钥生命周期

**Files:**
- Modify: `backend/src/test/java/com/wzx/babiq/server/settings/ProviderSettingsServiceTest.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/ProviderPersistenceService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/settings/ProviderSettingsService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/settings/AppSettingsService.java`

- [ ] **Step 1: 写 create-if-absent 与 API Key 模式切换失败测试**

在 `ProviderSettingsServiceTest` 增加：

```java
@Test
void duplicate_create_must_not_overwrite_existing_provider() {
    providerSettingsService.create(apiKeyDraft("relay", "sk-first"));
    assertThatThrownBy(() -> providerSettingsService.create(apiKeyDraft("relay", "sk-second")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("已存在");
    assertThat(secretStore.load(providerPersistenceService.findProvider("relay").orElseThrow().secretRef()))
            .contains("sk-first");
}

@Test
void switching_from_oauth_to_api_key_requires_new_key() {
    providerSettingsService.create(oauthDraft("claude"));
    assertThatThrownBy(() -> providerSettingsService.update(apiKeyDraft("claude", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("apiKey");
}
```

- [ ] **Step 2: 写 SecretStore 补偿和轮换测试**

使用可记录 `save/delete/load` 的测试 SecretStore 或 Mockito spy，覆盖：

```java
@Test
void failed_database_write_deletes_new_secret_alias() { /* mapper/save 抛错；新 alias 被 delete */ }

@Test
void successful_key_rotation_deletes_old_alias_after_commit() { /* 新 ref 生效；旧 ref 不可再 load */ }

@Test
void switching_to_oauth_deletes_old_api_key_alias() { /* secretRef 变 null；旧 ref 被删 */ }
```

任何断言消息、对象 `toString()` 和日志捕获都不得包含测试密钥 `sk-fake-sensitive-marker`。

同时在 `AppSettingsServiceTest` 先写 active 原子性 RED：

```java
@Test
void failed_setting_persistence_must_not_change_runtime_active_provider() {
    doThrow(new IllegalStateException("db-failed"))
            .when(appSettingPersistenceService).save(any());
    assertThatThrownBy(() -> service.update(activeProvider("provider-b")));
    assertThat(providerRegistry.active().id()).isEqualTo("provider-a");
}

@Test
void successful_setting_update_changes_registry_only_after_persistence_returns() {
    doAnswer(invocation -> {
        assertThat(providerRegistry.active().id()).isEqualTo("provider-a");
        return null;
    }).when(appSettingPersistenceService).save(any());
    service.update(activeProvider("provider-b"));
    assertThat(providerRegistry.active().id()).isEqualTo("provider-b");
}
```

在 `ProviderSettingsServiceTest` 额外断言 SQLite insert/update 抛错时 registry 内容和 active 均不变，`ChatClientFactory.invalidate` 从未调用。

- [ ] **Step 3: 运行测试确认 RED**

Run:

```powershell
cd backend
.\mvnw.cmd "-Dtest=ProviderSettingsServiceTest,AppSettingsServiceTest" test
```

Expected: FAIL，至少暴露重复 create 被 upsert、OAuth→API Key 空 key 被接受、旧 alias 未清理、DB 失败时 registry 提前切换中的一项。

- [ ] **Step 4: 为持久层增加明确的 insert/update 语义**

在 `ProviderPersistenceService` 增加并使用：

```java
public void insertProvider(ProviderConfigRecord record) { providerConfigMapper.insert(toEntity(record)); }

public void updateProvider(ProviderConfigRecord record) {
    ProviderConfigEntity existing = requireExisting(record.providerId());
    ProviderConfigEntity entity = toEntity(record);
    entity.setId(existing.getId());
    providerConfigMapper.updateById(entity);
}
```

保留 `saveProvider` 仅给兼容 bootstrap/旧调用，设置页 create/update 不再走 upsert。

- [ ] **Step 5: 在 ProviderSettingsService 实现事务外密钥 staging 与提交后运行时更新**

`ProviderSettingsService` 使用 `TransactionTemplate` 只包 SQLite 读写；实现顺序固定为：

```text
validate -> save new secret alias (if any)
-> SQLite transaction insert/update
-> transaction commit
-> registry register/disable + ChatClient invalidate
-> delete replaced secret alias
```

数据库事务失败时删除本次新 alias，且 registry、active 和 ChatClient 缓存完全不变；创建前检查重复 ID；更新只允许已存在记录；`api_key` 留空只有旧 `secretRef` 存在时才表示沿用。不得把 draft 或异常原始 message 写日志。

- [ ] **Step 6: 调整 AppSettingsService 为“数据库先提交、registry 后生效”**

把 active provider 校验放在事务前，`bq_app_settings` 保存放入明确 `TransactionTemplate`，事务返回后再调用 `providerRegistry.setActive(activeProviderId)`。保留现有 settings 字段行为和 handler API。

- [ ] **Step 7: 运行定向测试确认 GREEN**

Run:

```powershell
cd backend
.\mvnw.cmd "-Dtest=ProviderSettingsServiceTest,AppSettingsServiceTest" test
```

Expected: PASS，密钥 marker 不出现在 surefire 输出。

- [ ] **Step 8: 提交后端创建/更新原子性**

```powershell
git add -- backend/src/main/java/com/wzx/babiq/server/persistence/service/ProviderPersistenceService.java backend/src/main/java/com/wzx/babiq/server/settings/ProviderSettingsService.java backend/src/main/java/com/wzx/babiq/server/settings/AppSettingsService.java backend/src/test/java/com/wzx/babiq/server/settings/ProviderSettingsServiceTest.java backend/src/test/java/com/wzx/babiq/server/settings/AppSettingsServiceTest.java
git commit -m "fix: 收紧 Provider 持久化与密钥轮换"
```

### Task 2: 删除 fallback、启动恢复、错误脱敏与 business bind

**Files:**
- Modify: `backend/src/test/java/com/wzx/babiq/server/settings/ProviderSettingsServiceTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/ProviderSettingsHandlersTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/ProviderOAuthHandlersTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicyTest.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/settings/ProviderSettingsService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ProviderDeleteHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ProviderTestHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ProviderOAuthStatusHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ProviderOAuthLoginHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicy.java`

- [ ] **Step 1: 写删除与 bootstrap RED 测试**

覆盖以下行为：

```java
@Test void deleting_last_enabled_provider_is_rejected() { }
@Test void deleting_active_provider_persists_deterministic_fallback() { }
@Test void bootstrap_does_not_resurrect_persisted_disabled_yaml_provider() { }
@Test void bootstrap_restores_persisted_active_provider() { }
@Test void bootstrap_repairs_invalid_active_provider_and_persists_repair() { }
```

fallback 按启用 Provider 的 `providerId` 升序选择，避免更新时间改变选择结果。删除成功返回 `ProviderDeleteResult(providerId, activeProviderId)`。

- [ ] **Step 2: 写 handler 脱敏与访问策略 RED 测试**

断言：

- `provider/delete` 响应包含新的 `activeProviderId`。
- 删除最后一个启用 Provider、删除不存在 Provider 等 service 校验失败由 `ProviderDeleteHandler` 映射为 `INVALID_PARAMS`，且响应不含原始异常链。
- `provider/test` 内部异常包含 `sk-fake-sensitive-marker` 时，JSON-RPC 响应只返回固定文案“Provider 配置检查失败”，不包含 marker。
- OAuth status/login 内部异常或 CLI 输出包含 marker 时只返回受控状态文案。
- 未 bind 的 business WebSocket 对所有 `provider/list/create/update/delete/test/set-active/oauth/status/oauth/login` 均拒绝。
- finalized identity 后这些方法允许；连接 release/identity drift 后再次拒绝。

- [ ] **Step 3: 运行测试确认 RED**

Run:

```powershell
cd backend
.\mvnw.cmd "-Dtest=ProviderSettingsServiceTest,ProviderSettingsHandlersTest,ProviderOAuthHandlersTest,BusinessJsonRpcAccessPolicyTest" test
```

Expected: FAIL，当前删除无 fallback、测试连接泄露 `exception.getMessage()`、Provider 方法仍在 PRE_BIND。

- [ ] **Step 4: 实现确定性删除和 SQLite-first bootstrap**

`ProviderSettingsService.delete` 在同一个 SQLite 事务中：确认目标存在、确认不是最后一个启用 Provider、软删除目标；若目标 active，则把排序后的 fallback 写入 `AppSettingPersistenceService`。提交后再更新 registry、invalidate client、删除旧 secret alias。`ProviderDeleteHandler` 捕获业务校验异常并映射为安全 `INVALID_PARAMS`。

`bootstrap` 使用一次明确事务生成不可变快照：插入仅首次出现的 YAML Provider、读取全部持久化记录、计算/修复 persisted active；提交后先清空/禁用启动 registry 的旧条目，再仅注册 enabled 记录并应用 active。

- [ ] **Step 5: 实现统一安全错误映射**

`ProviderSettingsService.testConnection` 失败固定返回：

```java
new ProviderTestResult(false, providerId, "Provider 配置检查失败")
```

OAuth handler 仅按“已登录/未登录/登录已启动/登录失败”映射，不回传 CLI 路径、命令输出、token 或原始异常消息。日志只允许 `providerId`、操作名和异常类名。

- [ ] **Step 6: 移动 Provider 方法到 POST_BIND allowlist**

`BusinessJsonRpcAccessPolicy` 把本规格列出的 `provider/list/create/update/delete/test/set-active/oauth/status/oauth/login` 从 PRE_BIND 移到 POST_BIND；其他既有 settings/sandbox/approval 方法保持原策略，避免扩大本任务范围。Provider 仍是本机配置，但写入和读取都必须来自 finalized business identity。

- [ ] **Step 7: 运行定向测试确认 GREEN**

Run:

```powershell
cd backend
.\mvnw.cmd "-Dtest=ProviderSettingsServiceTest,ProviderSettingsHandlersTest,ProviderOAuthHandlersTest,BusinessJsonRpcAccessPolicyTest" test
```

Expected: PASS，所有响应与测试输出不包含 fake secret。

- [ ] **Step 8: 提交删除、恢复与访问边界**

```powershell
git add -- backend/src/main/java/com/wzx/babiq/server/settings/ProviderSettingsService.java backend/src/main/java/com/wzx/babiq/server/api/method/ProviderDeleteHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/ProviderTestHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/ProviderOAuthStatusHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/ProviderOAuthLoginHandler.java backend/src/main/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicy.java backend/src/test/java/com/wzx/babiq/server/settings/ProviderSettingsServiceTest.java backend/src/test/java/com/wzx/babiq/server/api/method/ProviderSettingsHandlersTest.java backend/src/test/java/com/wzx/babiq/server/api/method/ProviderOAuthHandlersTest.java backend/src/test/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicyTest.java
git commit -m "fix: 恢复持久化 Provider 状态并收紧访问"
```

## Chunk 2: 业务客户端协议与设置控制器

### Task 3: 扩展非敏感 Provider 模型和 JSON-RPC 客户端

**Files:**
- Modify: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/conversation/BusinessThreadModelsTest.kt`
- Modify: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/conversation/BusinessAgentClientTest.kt`
- Modify: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessProviderModels.kt`
- Modify: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessAgentClient.kt`

- [ ] **Step 1: 写完整 Provider 解码与敏感字段缺席测试**

`BusinessProvider` 增加 `type/baseUrl/model/contextWindow/enabled`，同时保留供 Agent 下拉使用的 `models`。测试 JSON 包含自定义 `model = "kimi-k3"`，断言响应 DTO 和序列化均不存在 `apiKey` 字段。

`BusinessProviderDraft` 虽然必须临时携带 create/update 请求所需 API Key，但必须显式覆盖：

```kotlin
override fun toString(): String =
    "BusinessProviderDraft(providerId=$providerId, model=$model, apiKey=[REDACTED])"
```

测试 `draft.toString()`、异常文本和所有 result `toString()` 均不包含 `sk-fake-sensitive-marker`；只有 fake RPC 捕获的 `provider/create` / `provider/update` params 可以包含该 marker。

- [ ] **Step 2: 写 CRUD/test/OAuth 请求 RED 测试**

为 fake `AgentJsonRpcClient` 记录 method/params，断言：

```kotlin
gateway.createProvider(draft)      // provider/create
gateway.updateProvider(draft)      // provider/update
gateway.deleteProvider("relay")   // provider/delete
gateway.testProvider("relay")     // provider/test
gateway.providerOAuthStatus("id") // provider/oauth/status
gateway.loginProviderOAuth("id")  // provider/oauth/login
```

只有 create/update 请求 params 可包含调用参数中的 API Key；draft 字符串表示、所有响应/结果模型和异常均不能包含 API Key。

- [ ] **Step 3: 运行 agent-client-core 测试确认 RED**

Run:

```powershell
cd business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat :agent-client-core:test --tests "*BusinessThreadModelsTest" --tests "*BusinessAgentClientTest"
```

Expected: FAIL，缺少字段和 gateway 方法。

- [ ] **Step 4: 实现模型与客户端最小 API**

新增非敏感 DTO：`BusinessProviderDraft`、`BusinessProviderDeleteResult`、`BusinessProviderTestResult`、`BusinessProviderOAuthStatus`、`BusinessProviderOAuthLoginResult`。`BusinessConversationGateway` 增加方法，`BusinessAgentClient` 用现有 `rpc.request` 调用，错误继续由统一 JSON-RPC 异常路径处理。

- [ ] **Step 5: 运行测试确认 GREEN**

Expected: 两个测试类 PASS，fake key 仅出现在请求捕获断言中，不出现在结果对象。

- [ ] **Step 6: 提交业务 Provider 协议**

```powershell
git add -- business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessProviderModels.kt business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessAgentClient.kt business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/conversation/BusinessThreadModelsTest.kt business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/conversation/BusinessAgentClientTest.kt
git commit -m "feat: 接通业务桌面 Provider 设置协议"
```

### Task 4: 真实后端重启验收 Provider 持久化

**Files:**
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessProviderSettingsRestartIT.kt`

- [ ] **Step 1: 基于现有 compatibility IT 写跨进程 acceptance test**

复用 `BusinessAgentProcessLauncher`、认证 Ktor WebSocket 和临时 home，测试流程：

```text
启动真实 backend jar
-> 完成 identity bind
-> provider/create(custom relay, custom model, contextWindow, fake key)
-> provider/set-active
-> provider/update(新 model/baseUrl/contextWindow/key)
-> 创建第二个启用 Provider 并设为 active
-> 删除当前 active Provider，记录响应中的 deterministic fallback activeProviderId
-> 关闭进程
-> 用同一 home/runtime 重新启动
-> provider/list 校验自定义非敏感字段、被删 active 的 soft-delete、hasApiKey 和 fallback active 跨重启保持
```

读取 launcher 生成的 backend 日志文件并记录全部 JSON-RPC 响应 payload，断言均不含 fake key；不把密钥写入测试失败消息。

- [ ] **Step 2: 运行 acceptance test 确认 GREEN**

Run:

```powershell
cd backend
.\mvnw.cmd -DskipTests package
cd ..\business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat :app:test --tests "*BusinessProviderSettingsRestartIT"
```

Expected: PASS。该测试在 Chunk 1 后端行为和 Task 3 客户端 RPC 已完成后加入，职责是证明真实 jar 跨进程组合成立，不伪造额外 RED 阶段；若失败，按 `@superpowers:systematic-debugging` 修复对应生产缺口后重跑。

- [ ] **Step 3: 补齐测试夹具和 Gradle 后端 jar 输入**

沿用 `BusinessDesktopBackendCompatibilityIT` 的启动/关闭边界，不复制生产密钥；确保重启确实使用相同 `agentDatabase`、`business-agent.jceks` 与 runtime root。

- [ ] **Step 4: 重跑重启 IT 并保存验收证据**

Expected: PASS，模型、Base URL、上下文窗口、hasApiKey、active 删除后的 deterministic fallback 和 soft-delete 均跨进程保持，fake key 无泄露。

- [ ] **Step 5: 提交重启验收**

```powershell
git add -- business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessProviderSettingsRestartIT.kt
git commit -m "test: 覆盖业务 Provider 跨重启持久化"
```

### Task 5: 新增不保存 API Key 的设置控制器

**Files:**
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessProviderSettingsController.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessProviderSettingsControllerTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationController.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRootTest.kt`

- [ ] **Step 1: 写控制器 RED 测试**

状态只允许：

```kotlin
data class BusinessProviderSettingsState(
    val providers: List<BusinessProvider> = emptyList(),
    val loading: Boolean = false,
    val busyProviderId: String? = null,
    val notice: BusinessProviderSettingsNotice? = null,
    val oauthStatus: Map<String, BusinessProviderOAuthStatus> = emptyMap(),
    val operationsEnabled: Boolean = false,
    val connectionGeneration: Long = 0,
)
```

测试保存成功刷新列表并调用 `conversationController.refreshProviders()`；删除采用响应 `activeProviderId`；测试/设为当前/OAuth 投影安全提示；失败保留非敏感草稿语义。用反射或 `state.toString()` 断言 state 类型没有 `apiKey` 属性且不包含 fake key。

控制器同时观察 `RegisteredAgentConnectionLifecycle.state` 与 `BusinessDesktopState`：只有 lifecycle 已发布 `Connected(connectionId)`（表示 identity/catalog/context 已在该连接完成注册）、认证状态为 `AUTHENTICATED` 且 identity 非空时 `operationsEnabled=true`。每次 finalized `connectionId` 变化递增 `connectionGeneration`。测试断线/重连期间所有 CRUD/test/OAuth 命令均不调用 gateway，重连完成后才恢复。

- [ ] **Step 2: 运行控制器测试确认 RED**

Run:

```powershell
cd business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat :app:test --tests "*BusinessProviderSettingsControllerTest"
```

Expected: FAIL，控制器不存在。

- [ ] **Step 3: 实现独立控制器与安全 notice 映射**

控制器接收 `BusinessConversationGateway`、`StateFlow<AgentSupervisorState>`（必须是 registered lifecycle）、`StateFlow<BusinessDesktopState>`、`CoroutineScope`、`onProvidersChanged: suspend () -> Unit`。它拥有一个只观察可用性的 Job，并实现 `close()` 只取消该 Job，不关闭共享 gateway。`save(draft)` 只把 draft 作为 suspend 调用栈参数，不写入 `MutableStateFlow`、日志或错误文本；每个命令首先检查 `operationsEnabled`，finally 清 busy 状态。公开 `refresh/create/update/delete/test/setActive/oauthStatus/oauthLogin`。

- [ ] **Step 4: 接入 composition root 生命周期**

`ProductionUiComponents` 增加 `providerSettingsController`。在 `RegisteredAgentConnectionLifecycle` 创建后把其 state 传给控制器，启动时仅在 `operationsEnabled` 后刷新；UI stage 关闭时取消设置控制器观察 Job，但不重复关闭共享客户端。设置变更回调刷新会话 Provider 下拉。

- [ ] **Step 5: 运行控制器和 composition 测试确认 GREEN**

Run:

```powershell
.\gradlew.bat :app:test --tests "*BusinessProviderSettingsControllerTest" --tests "*BusinessDesktopCompositionRootTest"
```

Expected: PASS，资源关闭顺序和唯一 ActionBus 不变量不变。

- [ ] **Step 6: 提交设置控制器**

```powershell
git add -- business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessProviderSettingsController.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationController.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessProviderSettingsControllerTest.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRootTest.kt
git commit -m "feat: 新增业务 Provider 设置控制器"
```

## Chunk 3: Compose 设置页、导航与 Agent 折叠

### Task 6: 实现 Provider 设置页和真实导航

**Files:**
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/settings/BusinessProviderSettingsPanel.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/settings/BusinessProviderSettingsPanelTest.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopDestination.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`

- [ ] **Step 1: 写设置表单 RED 测试**

覆盖：Provider 列表字段；新增与编辑；编辑回填但 API Key 永远空；复制生成 `<id>-copy` 且不复制 key；模型文本框接受 `kimi-k3`；ID 编辑模式只读；OAuth 模式隐藏 key 并显示状态/登录；删除确认；保存/测试/active/复制/delete 回调。

额外覆盖 `operationsEnabled=false` 时保存、删除、测试、设为当前和 OAuth 全禁用；`connectionGeneration` 变化或 operations 从 true 变 false 时，组件局部 API Key 立即清空，且不会回写 controller state。

为关键节点使用稳定 test tag：`provider-settings-panel`、`provider-model-input`、`provider-api-key-input`、`provider-save-action`、`provider-delete-confirm`。

- [ ] **Step 2: 写 shell 导航与 compact 三页签 RED 测试**

使用单一 canonical `BusinessDesktopDestination`（`WORKBENCH/DATA_ENTRY/RUN_HISTORY/SETTINGS/AGENT`），禁止同时持有 `selectedNavigation` 与 `compactContentTab` 两个真相源。宽/中屏点击 `设置` 后只显示设置中央页，右侧 Agent 仍存在；点击资料录入恢复表单；工作台/运行记录显示明确通用占位。compact 依次验证 `DATA_ENTRY/SETTINGS/AGENT` 一次只显示一个内容页。

增加可变窗口宽度测试：WIDE `SETTINGS` 缩到 COMPACT 后仍是 SETTINGS；COMPACT `AGENT` 扩到 WIDE 时中央区确定性显示 DATA_ENTRY fallback、右侧 Agent 保持存在，随后再缩回 COMPACT 仍回到 AGENT。WORKBENCH/RUN_HISTORY 缩到 compact 同样只做 DATA_ENTRY 视觉 fallback，不修改 canonical destination。

- [ ] **Step 3: 运行 UI 测试确认 RED**

Run:

```powershell
cd business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat :app:test --tests "*BusinessProviderSettingsPanelTest" --tests "*BusinessDesktopShellTest"
```

Expected: FAIL，设置页和 SETTINGS compact tab 尚不存在。

- [ ] **Step 4: 实现纯 Compose 设置面板**

面板参数只接收 `BusinessProviderSettingsState` 和动作回调。API Key 使用组件局部 `remember(editorIdentity) { mutableStateOf("") }`，不得进入 saveable state；用 `LaunchedEffect(state.connectionGeneration, state.operationsEnabled)` 在断线或 finalized reconnect 时清空，保存请求结束后也清空。所有远端错误只显示 controller 安全摘要，全部操作按钮受 `operationsEnabled` 控制。

- [ ] **Step 5: 接通 Main 与 Shell 导航**

`Main` 只提升一个 `selectedDestination: BusinessDesktopDestination`，collect `providerSettingsController.state` 并把 suspend 命令包装到 `uiScope.launch`。`BusinessDesktopShell` 在非 compact 时把 destination 映射为导航中央页（`AGENT` 使用 DATA_ENTRY fallback），在 compact 时把 WORKBENCH/RUN_HISTORY 映射为 DATA_ENTRY 视觉页但不改 canonical destination；compact tabs 固定三项“资料录入 / 设置 / Agent”。

- [ ] **Step 6: 运行 UI 测试确认 GREEN**

Expected: 设置页测试和 shell 测试 PASS；Agent 对话原有测试不回退。

- [ ] **Step 7: 提交设置页与导航**

```powershell
git add -- business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/settings/BusinessProviderSettingsPanel.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopDestination.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/settings/BusinessProviderSettingsPanelTest.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt
git commit -m "feat: 补齐业务桌面 Provider 设置页"
```

### Task 7: 实现宽/中屏 Agent 面板展开与收起

**Files:**
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicy.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicyTest.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentCollapsedRail.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanel.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanelTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`

- [ ] **Step 1: 写布局策略 RED 测试**

```kotlin
val expanded = resolve(1280.dp, agentPanelExpanded = true)
val collapsed = resolve(1280.dp, agentPanelExpanded = false)
assertEquals(420.dp, expanded.agentWidth)
assertEquals(52.dp, collapsed.agentWidth)
assertEquals(expanded.formWidth + 368.dp, collapsed.formWidth)
```

medium 同样从 360dp 收到 52dp；compact 不受 `agentPanelExpanded` 影响。

- [ ] **Step 2: 写 Agent header/rail 与状态保持 RED 测试**

展开面板存在 content description“收起业务 Agent”；点击后出现 52dp `business-agent-collapsed-rail` 与“展开业务 Agent”；再次点击恢复。折叠前写入的 `composerText`、消息、active Provider 在展开后仍显示。

- [ ] **Step 3: 运行测试确认 RED**

Run:

```powershell
cd business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat :app:test --tests "*BusinessDesktopLayoutPolicyTest" --tests "*BusinessDesktopShellTest" --tests "*BusinessAgentPanelTest"
```

Expected: FAIL，缺少 expanded 参数、折叠 rail 和按钮。

- [ ] **Step 4: 实现 52dp rail 和布局宽度计算**

`BusinessDesktopLayoutPolicy.resolve` 增加 `agentPanelExpanded`；非 compact 用 expanded width 或 `collapsedAgentWidth = 52.dp`。`BusinessAgentPanel` 标题行增加 IconButton；`BusinessAgentCollapsedRail` 仅包含紧凑 Agent 标识与展开按钮，不复制消息或 provider 状态。

- [ ] **Step 5: 把折叠状态提升到 Main**

`Main` 用 session-only `remember { mutableStateOf(true) }` 持有 `agentPanelExpanded`。Shell 只消费状态和 `onAgentPanelExpandedChange`；不能在被移除的 Agent panel 内自己保存折叠状态。compact 模式始终显示完整 Agent 页。

- [ ] **Step 6: 运行测试确认 GREEN**

Expected: 三个测试类 PASS，中央区宽度释放、展开恢复且会话状态未重置。

- [ ] **Step 7: 提交 Agent 折叠功能**

```powershell
git add -- business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicy.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicyTest.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentCollapsedRail.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanel.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanelTest.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt
git commit -m "feat: 支持业务 Agent 面板展开与收起"
```

## Chunk 4: 集成、回归、交接与完成证明

### Task 8: 全链路验证、文档与完成审查

**Files:**
- Modify: `docs/superpowers/plans/huitai-business-desktop-agent-framework/codex-handoff.md`
- Create: `docs/superpowers/plans/business-desktop-provider-settings-agent-panel/codex-handoff.md`
- Modify if needed: all files changed in Tasks 1-7

- [ ] **Step 1: 运行后端定向测试**

```powershell
cd backend
.\mvnw.cmd "-Dtest=ProviderSettingsServiceTest,AppSettingsServiceTest,ProviderSettingsHandlersTest,ProviderOAuthHandlersTest,BusinessJsonRpcAccessPolicyTest" test
```

Expected: BUILD SUCCESS，0 failures/errors。

- [ ] **Step 2: 运行 business-desktop 定向测试**

```powershell
cd business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat :agent-client-core:test --tests "*BusinessAgentClientTest" --tests "*BusinessThreadModelsTest"
.\gradlew.bat :app:test --tests "*BusinessProviderSettingsControllerTest" --tests "*BusinessProviderSettingsPanelTest" --tests "*BusinessDesktopLayoutPolicyTest" --tests "*BusinessDesktopShellTest" --tests "*BusinessAgentPanelTest"
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 使用 @superpowers:verification-before-completion 运行后端全量**

```powershell
cd backend
.\mvnw.cmd clean verify
```

Expected: BUILD SUCCESS，全部 unit + IT 通过。

- [ ] **Step 4: 运行 business-desktop 强制全量**

```powershell
cd business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat test --rerun-tasks --max-workers=1 --no-parallel --no-build-cache "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process"
```

Expected: BUILD SUCCESSFUL，所有模块测试真实执行。

- [ ] **Step 5: 运行真实 jar 重启 IT**

```powershell
cd backend
.\mvnw.cmd -DskipTests package
cd ..\business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat :app:test --tests "*BusinessProviderSettingsRestartIT"
```

Expected: PASS，跨重启设置与 active 生效。fake secret 只允许出现在测试发出的 `provider/create` / `provider/update` request params；所有 response、notification、backend log 和测试失败消息均不得包含。

- [ ] **Step 6: 更新交接文档**

记录：用户可自由填写模型 ID 和中转站；API Key 仅写 JCEKS；删除与 active fallback 语义；Provider 方法必须 identity bind；设置页入口；Agent 折叠行为；所有验证命令和结果；IDEA 启动仍只需业务桌面 `:app:run`，内置 backend 自动拉起。

- [ ] **Step 7: 检查差异与用户目录隔离**

```powershell
$IMPLEMENTATION_BASE = git config --local --get codex.business-provider-settings-base
git status --short
git diff --check "$IMPLEMENTATION_BASE..HEAD"
git diff --check
git diff --stat "$IMPLEMENTATION_BASE..HEAD"
git diff --name-only "$IMPLEMENTATION_BASE..HEAD"
```

Expected: 当前待审差异无 whitespace error；`.tmp-gradle-review/` 仍未跟踪且未暂存；无真实 API Key、数据库、JCEKS、runtime 文件进入 Git。最终提交后还必须在 Step 10 重跑同一检查。

- [ ] **Step 8: 进行规格符合性与代码质量双审查**

按 `@superpowers:requesting-code-review` 分别检查规格覆盖和实现质量；修复 Important/Critical 后重跑受影响定向测试，直到两轮均通过。

- [ ] **Step 9: 提交交接与最终修正**

```powershell
git add -- docs/superpowers/plans/huitai-business-desktop-agent-framework/codex-handoff.md docs/superpowers/plans/business-desktop-provider-settings-agent-panel/codex-handoff.md
git commit -m "docs: 记录业务桌面设置页与面板折叠验收"
```

- [ ] **Step 10: 最终提交后重跑全范围与工作树检查**

```powershell
$IMPLEMENTATION_BASE = git config --local --get codex.business-provider-settings-base
git diff --check "$IMPLEMENTATION_BASE..HEAD"
git diff --check
git diff --stat "$IMPLEMENTATION_BASE..HEAD"
git diff --name-only "$IMPLEMENTATION_BASE..HEAD"
git status --short
```

Expected: Tasks 1-8、审查修正和交接提交全部进入检查范围；工作树除用户原有 `.tmp-gradle-review/` 外干净。记录证据后执行 `git config --local --unset codex.business-provider-settings-base` 清理临时本地配置。

- [ ] **Step 11: 完成 goal**

确认无必要工作剩余后调用 goal 更新为 complete，并向用户报告新鲜验证证据与关键文件。
