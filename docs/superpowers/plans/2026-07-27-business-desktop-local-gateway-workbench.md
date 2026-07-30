# 业务桌面本地网关与完整工作台迁移实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Compose 只连接本地 Spring Boot，由 Spring Boot 统一持有 OA 会话并提供完整工作台 BFF，其他菜单保持占位。

**Architecture:** 本地 Spring Boot 以 finalized loopback WebSocket 为第一层信任边界，以后端 OA READY session 为第二层业务边界。后端完成登录、refresh、logout、可信 identity/catalog/context 原子安装，并以最后一个 CAS 动作发布 READY；每次安装带 server-owned `installationId`、owner、90 秒 TTL 和 generation CAS。Compose 不再注册或声明可信身份，只消费稳定 JSON-RPC DTO，并在 identity epoch 变化时丢弃旧结果。

**Tech Stack:** Java 21、Spring Boot 3.5、RestClient、WebSocket + JSON-RPC、SQLite + MyBatis-Plus + Flyway、JCEKS、Kotlin 2.x、Compose Desktop、Ktor、本地 loopback HTTP multipart。

**Design:** `docs/superpowers/specs/2026-07-27-business-desktop-local-gateway-workbench-design.md`

早期冲突草案 `2026-07-27-business-desktop-local-gateway-design.md` 已在协议复审确认没有必须保留的独有实施材料并删除；实施只能引用上述权威设计。

**Execution note:** 用户要求在 IDEA 当前仓库 `E:\huitai-work\BaBiQ` 调试，禁止再次复制到 `C:\tmp`。因此在当前 `codex/lawyer-oa-desktop` 分支实施；每次只暂存本任务 `Files` 清单中的明确文件，统一使用 `git commit --only -- <file...>`，禁止按目录 `git add`，保留 `PackagingScriptContractTest.kt` 修改和所有 `.tmp-*` 用户现场。当前 `.run/Business Backend.run.xml` 与 `.run/Business Frontend.run.xml` 已是用户 staged deletion，实施和最终提交必须保留删除状态，不恢复或重新暂存它们；IDEA 独立配置改为在 IDE 中临时建立并按设计传入配置文件参数。用户已确认会员续费支付不迁移：工作台保留会员状态和点击入口，点击显示明确占位。

**Command working-directory contract:** 为避免跨代码块依赖当前目录，所有 Maven 命令必须先执行
`cd E:\huitai-work\BaBiQ\backend`，所有 Gradle 命令必须先执行
`cd E:\huitai-work\BaBiQ\business-desktop`；跨端验证代码块也必须重新使用绝对 `cd`。下文若为简洁省略
重复的 `cd`，仍按本合同执行，不得从仓库根目录或上一个代码块的遗留目录直接运行 wrapper。

**Implementation handoff snapshot (2026-07-27，历史快照；当前证据更新至 2026-07-30):** 当前分支已经落地认证网关、READY 门禁、工作台 BFF、资源代理和 Compose 工作台代码，但本计划的 checkbox 仍代表“有新鲜证据且通过验收”，不能仅因代码存在而批量勾选。fake-OA/真 WebSocket E2E、安全 canary、fresh 全量、安装包烟测、已经完成的 IDEA 未登录分离启动，以及仍受阻的桌面测试冲突/真实账号人工烟测均以本文 Task 17/18 和 `docs/superpowers/plans/2026-07-27-business-desktop-local-gateway-handoff.md` 的最新记录为准；最终 Goal 状态必须按这些未验收项判断。

---

## 文件结构总览

后端新目录：

```text
backend/src/main/java/com/wzx/babiq/server/business/
├─ api/                       JSON-RPC handlers、固定错误映射和桌面 DTO
├─ identity/                  服务端可信 READY 安装
├─ oa/config/                 OA properties
├─ oa/client/                 OA auth/workbench ports 与 RestClient adapters
├─ oa/client/dto/             只在 adapter 内可见的 OA DTO
├─ oa/session/                状态机、JCEKS、SQLite、refresh、lease
├─ workbench/                 聚合、数据范围、分页、日程
└─ upload/                    单次票据与 loopback multipart 代理
```

Compose 新目录：

```text
business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/business/
├─ auth/                      安全认证 DTO 与 RPC client
└─ workbench/                 工作台 DTO 与 RPC client

business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/workbench/
├─ BusinessWorkbenchController.kt
├─ BusinessWorkbenchState.kt
└─ BusinessWorkbenchReducer.kt

business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/
├─ BusinessWorkbenchScreen.kt
├─ WorkbenchHeader.kt
├─ WorkbenchNavigation.kt
├─ QuickEntranceCard.kt
├─ DataStatisticsCard.kt
├─ BusinessListCard.kt
├─ WorkbenchProfileCard.kt
├─ SchedulePanel.kt
└─ ScheduleCreateDialog.kt
```

现有大文件只负责装配，不把 OA DTO、刷新或工作台业务规则继续堆进 `BusinessDesktopCompositionRoot.kt`。

---

## Chunk 1：可信连接、OA 会话与认证迁移

### Task 0：现有 JSON-RPC 泄密加固与双向 envelope 边界

**Files:**

- Modify: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationOutboundJsonRpcClient.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/config/WebSocketConfig.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcDispatcherTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcBidirectionalMessageTest.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolValidator.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcEnvelopeSizeBoundaryTest.java`

- [ ] **Step 1: 复核现有 RED/GREEN 安全切片**

未知 handler 异常只返回 `Internal server error`；畸形 JSON 日志只记录字节数/异常类型，响应固定
`Malformed JSON`，不得回显 payload 或 Jackson/OA message。

- [ ] **Step 2: 写双向 262144/262145 RED**

容器 `defaultMaxTextMessageBufferSize` 设为至少 262145，使超限一字节帧进入应用层；后端对入站
request/notification 和出站 response/notification 统一按 UTF-8 bytes 校验。恰好 262144 可通过，
262145 在解析/分发前拒绝并使用既有 `-32041 PROTOCOL_ERROR`。

- [ ] **Step 3: 运行 RED、最小实现并运行 GREEN**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=JsonRpcDispatcherTest,JsonRpcBidirectionalMessageTest,JsonRpcWebSocketHandlerIT,JsonRpcEnvelopeSizeBoundaryTest" test
```

- [ ] **Step 4: 独立规格审查、质量审查和中文提交**

```powershell
git commit --only -m "fix(协议): 收紧JSON-RPC错误与报文边界" -- backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationOutboundJsonRpcClient.java backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolValidator.java backend/src/main/java/com/wzx/babiq/server/config/WebSocketConfig.java backend/src/test/java/com/wzx/babiq/server/api/JsonRpcDispatcherTest.java backend/src/test/java/com/wzx/babiq/server/api/JsonRpcBidirectionalMessageTest.java backend/src/test/java/com/wzx/babiq/server/api/JsonRpcEnvelopeSizeBoundaryTest.java
```

提交后确认用户已暂存的 `.run` 删除仍保持 staged。

### Task 1：finalized 本地连接解析与三层 RPC 门禁

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionResolver.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionRegistry.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicy.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionRegistryTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionResolverTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicyTest.java`

- [ ] **Step 1: 写失败测试：PRE_AUTH 也必须来自 finalized connection**

测试应证明：仅传任意非空 WebSocket id 不能调用 `business/auth/session/get`；registry 中 reservation 未 finalize 也不能调用；完整属性与真实 connection 匹配后才允许。

- [ ] **Step 2: 运行 RED**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessDesktopConnectionRegistryTest,BusinessDesktopConnectionResolverTest,BusinessJsonRpcAccessPolicyTest" test
```

Expected: 新测试因缺少按 WebSocket id 查询和 resolver 而失败。

- [ ] **Step 3: 实现最小连接解析 API**

```java
public synchronized Optional<TrustedDesktopConnection> findByWebSocketSessionId(String id);

public TrustedDesktopConnection requireFinalized(WebSocketSession session);

public boolean isFinalized(String webSocketSessionId);
```

resolver 必须匹配四元组 `reservationId/desktopInstanceId/desktopSessionId/wsId`。

- [ ] **Step 4: 将 access policy 拆成 PRE_AUTH、SAFE_LOCAL、OA_READY allowlist**

此步先只实现 finalized connection 门禁；OA_READY supplier 在 Task 4 接入。删除生产 PRE_BIND 中的客户端 identity bind/update 放行。

- [ ] **Step 5: 运行 GREEN 与关联身份测试**

```powershell
.\mvnw.cmd "-Dtest=BusinessDesktopConnectionRegistryTest,BusinessDesktopConnectionResolverTest,BusinessJsonRpcAccessPolicyTest,ApplicationIdentityCatalogHandlersTest" test
```

- [ ] **Step 6: 规格审查、质量审查、中文提交**

```powershell
git commit --only -m "refactor(网关): 收紧本地连接与RPC门禁" -- backend/src/main/java/com/wzx/babiq/server/application backend/src/test/java/com/wzx/babiq/server/application
```

### Task 2：OA properties、认证 gateway 与稳定错误

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/config/BusinessOaProperties.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/client/OaAuthenticationGateway.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/client/RestClientOaAuthenticationGateway.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/client/OaPasswordEncoder.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/client/dto/OaAuthDtos.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/api/BusinessRpcErrorMapper.java`
- Modify: `backend/src/main/resources/application-business-desktop.yml`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentLaunchRequest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentProcessLauncherTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentBackendConfigPropagationTest.kt`
- Test contract: IDEA temporary `Business Backend` configuration must pass
  `SPRING_CONFIG_ADDITIONAL_LOCATION=file:$PROJECT_DIR$/business-desktop/config/business-desktop-development.properties`
  (or the equivalent absolute `--spring.config.additional-location=file:<path>`); do not restore staged `.run` deletions.
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java`（若 Task 0 泄密加固尚未提交）
- Test: `backend/src/test/java/com/wzx/babiq/server/business/oa/config/BusinessOaPropertiesTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/oa/client/RestClientOaAuthenticationGatewayTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/api/BusinessRpcErrorMapperTest.java`

- [ ] **Step 1: 写 OA 合同失败测试**

覆盖：`/law-api` 前缀、`X-Platform-Type: pc`、数字/字符串 ID、仅业务 `code=0` 成功、`code=200` 必须失败、候选空列表、MD5 双层密码、refresh token 位于 form body、logout 使用最新 access token、禁止重定向。稳定数字错误保留既有 `-32041 PROTOCOL_ERROR`，远端错误使用 `-32040/-32042/-32043`。

- [ ] **Step 2: 运行 RED**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessOaPropertiesTest,RestClientOaAuthenticationGatewayTest,BusinessRpcErrorMapperTest" test
```

- [ ] **Step 3: 实现 ports 与 adapter**

port 的公开结果只包含内部 domain 类型：

```java
List<OaTenantCandidate> findTenantCandidates(String account);
OaCredential login(OaTenantCandidate candidate, char[] encodedPassword);
OaCredential refresh(char[] refreshToken);
OaPermissionSnapshot loadPermissions(char[] accessToken);
void logout(char[] accessToken);
```

远端异常转换为固定 domain exception，禁止携带 HTTP body 进入 message。

同时让内置 Agent child command 显式传入
`--spring.config.additional-location=file:<paths.desktopConfiguration>`（规范化绝对路径）；该参数由
受控 runtime path 生成，不能来自 OA 配置值或工作目录。IDEA 分离启动后端时使用同一个仓库内
`business-desktop/config/business-desktop-development.properties`，通过临时 Run Configuration 的环境变量
`SPRING_CONFIG_ADDITIONAL_LOCATION=file:$PROJECT_DIR$/business-desktop/config/business-desktop-development.properties`
或等价 JVM/application 参数传递。缺失、不可读或不在受控路径的文件必须 fail-closed；测试须断言 child
command、IDEA 参数和错误路径。

- [ ] **Step 4: generic dispatcher fallback 保持固定 `Internal server error`**

为现有 dispatcher 增加回归测试，禁止 `exception.getMessage()` 进入 JSON-RPC。

- [ ] **Step 5: GREEN、敏感日志断言与提交**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessOaPropertiesTest,RestClientOaAuthenticationGatewayTest,BusinessRpcErrorMapperTest,JsonRpcDispatcherTest" test
git commit --only -m "feat(网关): 建立后端OA认证适配层" -- <Task2 Files中的明确文件列表>
```

### Task 3：JCEKS 凭据与 SQLite 非敏感会话索引

**Files:**

- Create: next unused migration, e.g. `backend/src/main/resources/db/migration/VNN__business_oa_sessions.sql`（实施前以 `rg --files backend/src/main/resources/db/migration` 确认编号）
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/OaSessionEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/OaSessionMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/OaSessionRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/SQLiteOaSessionRepository.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/OaSessionCredentialStore.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/OaSessionPersistenceService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/settings/SecretStore.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/settings/LocalKeyStoreSecretStore.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/oa/session/OaSessionCredentialStoreTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/oa/session/OaSessionPersistenceServiceTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/SQLiteMigrationIT.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`

- [ ] **Step 1: 写失败测试：数据库不含 Token，credential 组合替换原子化**

覆盖 write/read/delete、refresh CAS、错误版本拒绝、revoked 会话不可恢复、业务 profile 使用默认 KeyStore password 时 fail closed。

- [ ] **Step 2: 运行 RED**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=OaSessionCredentialStoreTest,OaSessionPersistenceServiceTest,SQLiteMigrationIT,SchemaCommentsCoverageTest" test
```

- [ ] **Step 3: 创建下一个未占用 migration 与中文注释**

`bq_business_oa_sessions` 只含 design 中列出的非敏感字段，明确区分 `active_credential_ref`、`staged_credential_ref`、credential version、安装/脱离/撤销时间；每表/字段同步 SQL 注释、`bq_schema_comments` 和 Entity 中文注释。启动恢复须清理 orphan staged secret，并收口卡在 INSTALLING/REVOKING 的记录。

- [ ] **Step 4: 扩展 SecretStore 为可安全替换的 char[] blob**

生产 API 不返回 String；读取方负责 finally wipe 临时数组。SQLite 事务只提交已经持久化成功的 `credentialRef`。

- [ ] **Step 5: GREEN、schema 扫描与提交**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=OaSessionCredentialStoreTest,OaSessionPersistenceServiceTest,SQLiteMigrationIT,SchemaCommentsCoverageTest" test
git commit --only -m "feat(网关): 持久化OA桌面会话索引" -- <Task3 Files中的明确文件列表>
```

### Task 4：后端会话状态机、READY lease、refresh 与请求执行器

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/BusinessOaSessionState.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/BusinessOaSessionRegistry.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/ReadyOaSessionLease.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/OaTokenRefreshCoordinator.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/OaAuthenticatedRequestExecutor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicy.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/ApplicationBridgeLifecycleCoordinator.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/oa/session/BusinessOaSessionRegistryTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/oa/session/OaTokenRefreshCoordinatorTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/oa/session/OaAuthenticatedRequestExecutorTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/ApplicationBridgeLifecycleCoordinatorTest.java`

- [ ] **Step 1: 写状态机和并发失败测试**

覆盖非法转换、READY last、generation 先撤销、同 generation singleflight、网络错误不删除会话、confirmed auth expiry、membership expiry、权限漂移、旧连接/旧 generation 迟到结果丢弃、写请求不自动重放。

- [ ] **Step 2: 运行 RED**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessOaSessionRegistryTest,OaTokenRefreshCoordinatorTest,OaAuthenticatedRequestExecutorTest,ApplicationBridgeLifecycleCoordinatorTest,BusinessJsonRpcAccessPolicyTest" test
```

- [ ] **Step 3: 实现不可变 lease 与 revalidate**

```java
ReadyOaSessionLease captureReady(TrustedDesktopConnection connection);
boolean isCurrent(ReadyOaSessionLease lease);
void revokeBeforeCleanup(TrustedDesktopConnection connection, RevocationReason reason);
```

锁内只捕获/提交状态，不做远程 IO。

- [ ] **Step 4: 接入 OA_READY access policy 和连接 close listener**

断开只 detach live lease；显式 logout 才删除 durable session。所有原 Agent post-bind 方法改要求服务端 OA READY + server-installed identity。

- [ ] **Step 5: GREEN 与提交**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessOaSessionRegistryTest,OaTokenRefreshCoordinatorTest,OaAuthenticatedRequestExecutorTest,ApplicationBridgeLifecycleCoordinatorTest,BusinessJsonRpcAccessPolicyTest" test
git commit --only -m "feat(网关): 建立OA会话代次与刷新门禁" -- <Task4 Files中的明确文件列表>
```

### Task 5：服务端可信身份安装与认证 JSON-RPC

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/business/identity/BusinessOaReadyInstaller.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/BusinessOaAuthenticationService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/api/BusinessAuthProtocolHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/api/dto/BusinessAuthDtos.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistry.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/catalog/ApplicationCatalogRegistry.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/catalog/ApplicationPageContextRegistry.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/api/ApplicationIdentityProtocolHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/api/ApplicationCatalogProtocolHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/identity/BusinessOaReadyInstallerTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/api/BusinessAuthProtocolHandlerTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistryServerInstallTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/api/ApplicationIdentityCatalogHandlersTest.java`

- [ ] **Step 1: 写失败测试：客户端 bind 不能解锁，READY 必须最后发布**

覆盖 selection ticket 与连接/账号绑定、TTL、单次使用；login user/permission/candidate 不一致；identity/catalog/context 每个服务端安装故障；READY 必须最后发布；密码/Token 不在返回；session/get 只读；DETACHED session 只允许同 desktop session attach；startup restore 与 reconnect attach 语义分离；logout 任意状态幂等。

- [ ] **Step 2: 运行 RED**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessOaReadyInstallerTest,BusinessAuthProtocolHandlerTest,ApplicationIdentityRegistryServerInstallTest,ApplicationIdentityCatalogHandlersTest,BusinessJsonRpcAccessPolicyTest" test
```

- [ ] **Step 3: 为三个 registry 增加 server-only install API**

API 接受服务端 domain projection，不接受客户端 `ApplicationIdentityMessage` 作为可信输入。安装失败提供明确 rollback。

- [ ] **Step 4: 实现 auth handler 与服务端原子 READY**

六个方法：`business/auth/session/get`、`business/auth/session/attach`、restore、tenant-candidates、login、logout。login/attach/restore 在服务端完成 OA 校验、identity/catalog/context 安装并最后发布 READY；生产协议不提供 `business/auth/ready`。密码字段进入 blanket-redacted method；所有 exception 映射固定错误。

- [ ] **Step 5: 生产关闭客户端 identity/catalog/context handler**

用 `babiq.business.legacy-client-registration-enabled=false` 条件化旧 handler；测试 profile 可显式开启。

- [ ] **Step 6: GREEN、真实 WebSocket IT 与提交**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessOaReadyInstallerTest,BusinessAuthProtocolHandlerTest,ApplicationIdentityRegistryServerInstallTest,ApplicationIdentityCatalogHandlersTest,BusinessJsonRpcAccessPolicyTest,ApplicationBridgeEndToEndIT" test
git commit --only -m "feat(网关): 由后端安装可信OA身份" -- <Task5 Files中的明确文件列表>
```

### Task 6：旧 JCEKS 定点清理与 native 启动恢复

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/session/BusinessOaSessionStartupRecovery.java`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendKeyStorePasswordVault.kt`
- Modify: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/secret/JceksSecretStore.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/LegacyOaCredentialAliasCleanup.kt`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/oa/session/BusinessOaSessionStartupRecoveryTest.java`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/security/LegacyOaCredentialAliasCleanupTest.kt`

- [ ] **Step 1: 写三 alias 原子清理与 native recovery 测试**

只允许删除 `huitai.auth.tokens.v1`、`huitai.auth.session-metadata.v1`、`huitai.login.remembered.v1`；不解码 Token/密码、不删除整个 desktop JCEKS。断言临时文件写入、重开校验、原子替换，任一步失败保留原文件且不出现 partial 删除。后端 recovery 只处理 native staged/orphan session。

- [ ] **Step 2: 运行双端 RED**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessOaSessionStartupRecoveryTest" test
cd E:\huitai-work\BaBiQ\business-desktop
.\gradlew.bat :app:test --tests "*LegacyOaCredentialAliasCleanupTest" --no-daemon --max-workers=1 --no-parallel
```

- [ ] **Step 3: 实现原子定点清理和后端 native recovery**

不复制任何旧 alias 到 backend KeyStore，不创建 reserved import alias；完成清理后要求用户明确重新登录。启动恢复只收口 native staged/orphan/REVOKING 记录，不能恢复 revoked session。

- [ ] **Step 4: GREEN、别名安全扫描与提交**

```powershell
git commit --only -m "fix(登录): 原子清理旧OA凭据别名" -- <Task6 Files中的明确文件列表>
```

---

## Chunk 2：工作台 BFF 与安全写链

### Task 7：工作台 OA adapter、稳定 DTO 与错误转换

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/client/OaWorkbenchGateway.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/client/RestClientOaWorkbenchGateway.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/oa/client/dto/OaWorkbenchDtos.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/api/dto/BusinessWorkbenchDtos.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/workbench/BusinessWorkbenchMapper.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/oa/client/RestClientOaWorkbenchGatewayTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/workbench/BusinessWorkbenchMapperTest.java`

- [ ] **Step 1: 写 fixture 失败测试**

覆盖仅 `code=0` 成功、`code=200` 失败、公告真实 `pageNo=1&pageSize=10&type=3&displayStatus=1`、其他分页 `pageNo/pageSize`、数字/字符串 ID、未知 OA 枚举 fail-safe、公告 `total > list.size()`、案件真实字段 `applicationNumber/categoriesName`。另锁定 Web 当前 `page=1` 只是因后端 `PageParam.pageNo` 默认 1 才碰巧正确，adapter 不复制该偏差。

- [ ] **Step 2: RED**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=RestClientOaWorkbenchGatewayTest,BusinessWorkbenchMapperTest" test
```

- [ ] **Step 3: 实现窄 OA DTO 和稳定桌面 DTO**

OA DTO 只在 adapter 包内；Compose DTO 不含 `CommonResult/tableName/traceId/url/component/moduleId/relatedIds/dataRoleCodes/fileIds`。

- [ ] **Step 4: GREEN 与提交**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=RestClientOaWorkbenchGatewayTest,BusinessWorkbenchMapperTest" test
git commit --only -m "feat(工作台): 建立稳定OA适配合同" -- <Task7 Files中的明确文件列表>
```

### Task 8：工作台聚合快照与读方法

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/business/workbench/BusinessWorkbenchService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/api/BusinessWorkbenchProtocolHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/workbench/BusinessWorkbenchServiceTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/api/BusinessWorkbenchProtocolHandlerTest.java`

- [ ] **Step 1: 写聚合与 lease 失败测试**

`business/workbench/get` 聚合公告、快捷入口、统计、首个 enabled 列表、用户卡、团队、当月/当日日程；`business/workbench/navigation/get` 从可信 permission/menu projection 独立返回 allowlisted 导航。非核心分区失败进入 `issues`，核心身份失败整体失败；返回前 epoch 变化则使用既有 `BUSINESS_SESSION_STALE`。

- [ ] **Step 2: RED**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessWorkbenchServiceTest,BusinessWorkbenchProtocolHandlerTest" test
```

- [ ] **Step 3: 实现有界并发聚合**

每个远程调用经 `OaAuthenticatedRequestExecutor`；避免在 session/connection 锁内阻塞；快照控制在 WebSocket envelope 限额内。

- [ ] **Step 4: 注册 navigation/get、notices/profile/shortcuts/summary 独立刷新方法**

所有 handler 通过 resolver + READY lease，不重复手写连接属性解析。

- [ ] **Step 5: GREEN 与提交**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessWorkbenchServiceTest,BusinessWorkbenchProtocolHandlerTest,BusinessJsonRpcAccessPolicyTest" test
git commit --only -m "feat(工作台): 聚合桌面首屏快照" -- <Task8 Files中的明确文件列表>
```

### Task 9：团队数据范围与四类分页

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/business/workbench/BusinessDataScopeValidator.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/business/workbench/BusinessWorkbenchService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/business/api/BusinessWorkbenchProtocolHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/workbench/BusinessDataScopeValidatorTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/workbench/BusinessWorkbenchPagesTest.java`

- [ ] **Step 1: 写安全 RED**

覆盖 typed `business/workbench/page/get`：kind/filter 未知枚举、unknown JSON field、TEAM 空/非当前 teamId、跨模块 roleCode、客户端注入 moduleId/relatedIds/dataRoleInfos/dataRoleCodes/tenantId/userId，以及 ALL/PERSONAL 携带 teamId/roleCode；拒绝时 OA 调用次数必须为 0。

- [ ] **Step 2: 写四类参数映射 RED**

案件 1007、预约 1006、顾问 1003、拜访 1004；筛选值、页码、角色与返回分页必须准确。

- [ ] **Step 3: 实现 validator 与四类 page handler**

公开 scope 只有 ALL/PERSONAL/TEAM。roleCode 在服务端从当前团队角色缓存核验；缓存绑定 READY lease。

- [ ] **Step 4: GREEN 与提交**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessDataScopeValidatorTest,BusinessWorkbenchPagesTest,BusinessWorkbenchProtocolHandlerTest" test
git commit --only -m "feat(工作台): 收紧团队范围与业务分页" -- <Task9 Files中的明确文件列表>
```

### Task 10：排序、日程读写、关联选项和附件代理

**Files:**

- Create: `backend/src/main/java/com/wzx/babiq/server/business/workbench/BusinessScheduleService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/upload/BusinessAttachmentTicketService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/upload/BusinessAttachmentUploadController.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/upload/BusinessResourceHandleRegistry.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/upload/BusinessResourceProxyController.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/upload/BusinessAttachmentRecoveryService.java`
- Create: next unused migration for durable upload ticket/batch/resource-handle state (confirm version first)
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/BusinessAttachmentTicketEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/BusinessAttachmentBatchEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/BusinessResourceHandleEntity.java`
- Create: corresponding MyBatis mappers/repositories and schema-comment coverage
- Create: `backend/src/main/java/com/wzx/babiq/server/business/upload/BusinessLoopbackHttpSecurityFilter.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/business/upload/BusinessUploadExceptionHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/business/api/BusinessWorkbenchProtocolHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/workbench/BusinessScheduleServiceTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/workbench/BusinessWorkbenchMutationTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/upload/BusinessAttachmentTicketServiceTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/upload/BusinessAttachmentUploadIT.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/upload/BusinessAttachmentRecoveryIT.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/business/upload/BusinessResourceProxyIT.java`

- [x] **Step 1: 写排序和日程权限 RED**

排序拒绝重复、缺失、多余 ID 和 stale revision；普通成员不能指派他人；负责人只能指派有效成员；服务项目 recordId 必须先在授权 options 中；日程 create 携带幂等键但不自动重放。

- [x] **Step 2: 写附件票据 RED**

覆盖 60s TTL、Origin/CSRF/loopback/Host/Bearer/finalized WS/READY 校验、connection/OA session/generation/tenant/`SCHEDULE_CREATE` operation/父资源授权绑定、退出吊销、owner-only 临时路径清理、类型/实际大小/hash/数量限制、ticket 不在 query、返回 `attachmentBatchId` 而非 fileId。ticket 与 batch 分开锁定 CAS：`ISSUED -> CLAIMED -> IN_FLIGHT -> SUCCEEDED|REJECTED|OUTCOME_UNKNOWN|EXPIRED|REVOKED`，`READY -> CONSUMING -> CONSUMED|FAILED|OUTCOME_UNKNOWN|REVOKED`；并发领取只有一个成功，partial upload 永不 READY，崩溃/断链不自动重放。ticket、batch 和 resource handle 必须落入同一受注释的 SQLite migration；启动 recovery 将 `IN_FLIGHT/CONSUMING` 收束为 `OUTCOME_UNKNOWN/REVOKED`，清理临时文件和过期 handle，不能在重启后自动上传/创建。

- [x] **Step 3: 运行 RED**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessScheduleServiceTest,BusinessWorkbenchMutationTest,BusinessAttachmentTicketServiceTest,BusinessAttachmentUploadIT" test
```

- [x] **Step 4: 实现 schedule/form/options/create 和 `business/schedule/completion/set`**

month/day 使用稳定日期格式；完成/激活为显式目标状态。服务端二次校验团队与关联项；写响应返回新 revision。

资源代理必须同时实现 `GET /business/resources/{opaqueHandle}`：handle 仅由可信 OA 响应注册，绑定
instance/session/READY generation/tenant/过期时间，logout、detach 超时和 generation 变化立即撤销；
只返回固定 MIME/长度且禁止缓存，未知、跨 lease、过期或撤销统一 `BUSINESS_RESOURCE_UNAVAILABLE`。

- [x] **Step 5: 实现 loopback multipart streaming**

HTTP controller 只绑定 loopback并经过专用 Origin/CSRF filter：只接受规范化 loopback、精确 Host/Origin、Authorization Bearer header 和 `X-Business-Upload-Ticket`，拒绝缺失/通配 Origin、Cookie/query 凭据、OPTIONS 与代理转发头；先流式写入 owner-only、禁止 symlink/reparse 的临时文件并完成真实 size/hash/MIME 校验，再固定 storage name 上传 OA。HTTP 错误/日志只含固定 businessCode/correlationId/计数，Advice 禁止默认 error body；远端文件 URL、fileIds、ticket、文件名/路径/SHA/OA body 不返回或记录。schedule/create 对 `attachmentBatchId` 做同 operation/父授权/form revision/generation 的单次 CAS 消费。

- [ ] **Step 6: GREEN 与提交**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessScheduleServiceTest,BusinessWorkbenchMutationTest,BusinessAttachmentTicketServiceTest,BusinessAttachmentUploadIT,BusinessJsonRpcAccessPolicyTest" test
git commit --only -m "feat(工作台): 接通日程写链与附件代理" -- <Task10 Files中的明确文件列表>
```

---

## Chunk 3：Compose 认证切换与完整工作台

### Task 11：typed 认证/工作台协议客户端

**Files:**

- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/business/auth/BusinessAuthModels.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/business/auth/BusinessAuthRpcClient.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/business/workbench/BusinessWorkbenchModels.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/business/workbench/BusinessWorkbenchRpcClient.kt`
- Modify: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/AgentJsonRpcClient.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/business/auth/BusinessAuthRpcClientTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/business/workbench/BusinessWorkbenchRpcClientTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/client/JsonRpcEnvelopeSizeBoundaryTest.kt`

- [ ] **Step 1: 写 fixture 与安全 RED**

覆盖数字/字符串 ID、未知枚举、稳定 business error、payload 无 accessToken/refreshToken/secretRef/OA URL、epoch 字段必填，以及 `262144/262145` UTF-8 bytes 双向边界。Kotlin 对出站 request/response 与入站 response/notification 在业务解码前校验，超限使用既有 `-32041 PROTOCOL_ERROR`。

- [ ] **Step 2: RED**

```powershell
cd business-desktop
.\gradlew.bat :agent-client-core:test --tests "*BusinessAuthRpcClientTest" --tests "*BusinessWorkbenchRpcClientTest" --no-daemon --max-workers=1 --no-parallel
```

- [ ] **Step 3: 实现窄 typed clients**

使用现有 `AgentJsonRpcClient.request(String, JsonObject)`；新增 `BusinessRpcException` 只解析白名单 error.data。

- [ ] **Step 4: GREEN 与提交**

```powershell
git commit --only -m "feat(桌面协议): 接入本地认证与工作台RPC" -- <Task11 Files中的明确文件列表>
```

### Task 12：登录控制器切换和 Compose OA 权威移除

**Files:**

- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessLoginController.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessLogoutController.kt`
- Replace: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessAuthenticationOrchestrator.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessAuthenticationLifecycle.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessIdentityRegistry.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/login/BusinessLoginScreen.kt`
- Delete after replacement: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/OaTokenRefreshAdapter.kt`
- Delete after replacement: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/ReadyAuthenticatedHuitaiClient.kt`
- Delete after replacement: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/ReadyAuthenticatedHttpGate.kt`
- Delete after migration: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/JceksAuthCredentialPersistence.kt`
- Delete after migration: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/BusinessAuthSessionMetadataStore.kt`
- Delete after migration: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/BusinessAuthRevocationMarkerStore.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/BusinessLoginCredentialStore.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/auth/BusinessLoginControllerTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessAuthenticationLifecycleIT.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRootTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/security/ComposeOaDirectAccessForbiddenTest.kt`

- [ ] **Step 1: 写登录行为和源码合同 RED**

现有登录 UX 不变；session/get、session/attach、startup restore、login/logout 只调用本地 RPC。composition root 不引用 `OaAuthenticationGatewayFactory`、`ReadyAuthenticatedHuitaiClient`、Token refresh 或 OA Authorization/tenant headers。记住密码文案/状态收口为只保存账号。

- [ ] **Step 2: RED**

```powershell
.\gradlew.bat :app:test --tests "*BusinessLoginControllerTest" --tests "*BusinessAuthenticationLifecycleIT" --tests "*BusinessDesktopCompositionRootTest" --tests "*ComposeOaDirectAccessForbiddenTest" --no-daemon --max-workers=1 --no-parallel
```

- [ ] **Step 3: 最小切换为 RPC orchestrator**

Kotlin `BusinessIdentityRegistry` 只镜像 server READY revision/epoch；不再生成 authSessionId、roles、permissions 或注册可信 identity/catalog/context。

- [ ] **Step 4: 删除已不可达直连生产代码**

只有 Task 6 的旧 alias cleanup 可在切换窗口定点删除三个已知别名；它不解码或复制 Token。`huitai-integration-core` 中 OA auth 代码在 Task 16 确认无引用后删除。

- [ ] **Step 5: GREEN 与提交**

```powershell
cd E:\huitai-work\BaBiQ\business-desktop
.\gradlew.bat :app:test --tests "*BusinessLoginControllerTest" --tests "*BusinessAuthenticationLifecycleIT" --tests "*BusinessDesktopCompositionRootTest" --tests "*ComposeOaDirectAccessForbiddenTest" --no-daemon --max-workers=1 --no-parallel
git commit --only -m "refactor(登录): 切换到后端OA会话权威" -- <Task12 Files中的明确文件列表>
```

### Task 13：工作台状态、控制器与主布局

**Files:**

- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/workbench/BusinessWorkbenchState.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/workbench/BusinessWorkbenchReducer.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/workbench/BusinessWorkbenchController.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/BusinessWorkbenchScreen.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/WorkbenchHeader.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/WorkbenchNavigation.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopDestination.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebar.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/workbench/BusinessWorkbenchReducerTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/workbench/BusinessWorkbenchControllerTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/workbench/BusinessWorkbenchScreenTest.kt`

- [ ] **Step 1: 写 reducer/controller RED**

覆盖 READY 自动加载、epoch 变化清空、旧响应丢弃、分区失败与空态区分、退出清空、普通重连按 `session/get -> session/attach` 后重载；startup restore 只用于应用启动。

- [ ] **Step 2: 写 layout contract RED**

Header 64、nav 88、背景/主色/padding/gap、左右列比例、默认 destination 工作台、其他 menu placeholder。

- [ ] **Step 3: 实现状态与主布局**

UI 组件只消费稳定 DTO；不要在 composable 中直接发 RPC。Agent 助手保留为业务桌面能力，但工作台是 READY 后默认主体。

- [ ] **Step 4: GREEN 与提交**

```powershell
.\gradlew.bat :app:test --tests "*BusinessWorkbenchReducerTest" --tests "*BusinessWorkbenchControllerTest" --tests "*BusinessWorkbenchScreenTest" --tests "*BusinessDesktopShellTest" --no-daemon --max-workers=1 --no-parallel
git commit --only -m "feat(工作台): 落地翔鸟律智桌面布局" -- <Task13 Files中的明确文件列表>
```

### Task 14：工作台八组件、分页、排序与数据范围交互

**Files:**

- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/QuickEntranceCard.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/DataStatisticsCard.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/BusinessListCard.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/WorkbenchProfileCard.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/DataScopeSelector.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/workbench/BusinessWorkbenchController.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/workbench/QuickEntranceCardTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/workbench/DataStatisticsCardTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/workbench/BusinessListCardTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/workbench/BusinessWorkbenchInteractionTest.kt`

- [x] **Step 1: 写交互 RED**

快捷入口 10 个/页循环；安全 URL 才打开；第一个 enabled 统计自动选中；切 card/scope/team/role 重置页码；团队或角色失效安全回退；只有案件行打开详情占位；排序失败回滚。

- [x] **Step 2: 实现组件与 controller intents**

四类列表共享分页壳，但每类保留真实字段。网络失败必须显示明确 retry 状态，不再静默伪装为空数据。

- [ ] **Step 3: GREEN 与提交**

```powershell
.\gradlew.bat :app:test --tests "*QuickEntranceCardTest" --tests "*DataStatisticsCardTest" --tests "*BusinessListCardTest" --tests "*BusinessWorkbenchInteractionTest" --no-daemon --max-workers=1 --no-parallel
git commit --only -m "feat(工作台): 接通统计分页与团队范围" -- <Task14 Files中的明确文件列表>
```

### Task 15：日程面板、新增表单、附件和视觉资源

**Files:**

- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/SchedulePanel.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/workbench/ScheduleCreateDialog.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/workbench/BusinessScheduleController.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/workbench/BusinessAttachmentUploadClient.kt`
- Add resources under: `business-desktop/app/src/main/resources/brand/workbench/`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/workbench/SchedulePanelTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/workbench/ScheduleCreateDialogTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/workbench/BusinessScheduleControllerTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/workbench/BusinessAttachmentUploadClientTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/workbench/WorkbenchResourceTest.kt`

- [x] **Step 1: 写日历与乐观更新 RED**

覆盖年月/今日/前后月、一周/整月、日期事件点、TEAM onlyMine、完成与取消完成、失败回滚、epoch 变化丢弃。

- [x] **Step 2: 写表单和附件 RED**

标题、类型、指派、优先级、日期时间/全天、描述、多提醒、自定义提醒、重复、客户/案件/拜访/服务项目关联、附件；普通成员指派限制；ticket header/TTL/进度/取消。

- [x] **Step 3: 迁移实际可达位图并校验 hash**

从 `E:\huitai-work\huitai-law-oa` 复制工作台实际使用资源；使用现有品牌资源测试模式固定 SHA-256。图标优先使用 Compose vector；不能复制 CSS class 名。

- [x] **Step 4: 实现日程和上传 UI**

非幂等 create/upload 不自动重放；退出或 epoch 变化取消上传并清 `attachmentBatchId`、ticket 和本地上传状态。

- [ ] **Step 5: GREEN 与提交**

```powershell
.\gradlew.bat :app:test --tests "*SchedulePanelTest" --tests "*ScheduleCreateDialogTest" --tests "*BusinessScheduleControllerTest" --tests "*BusinessAttachmentUploadClientTest" --tests "*WorkbenchResourceTest" --no-daemon --max-workers=1 --no-parallel
git commit --only -m "feat(工作台): 完整迁移日程与附件交互" -- <Task15 Files中的明确文件列表>
```

---

## Chunk 4：直连收口、端到端、安全与最终验收

### Task 16：删除 Compose OA 直连与旧模块权威

**Files:**

- Delete: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/oa/auth/*`
- Delete when unreferenced: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/HuitaiHttpClient.kt`
- Delete when unreferenced: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/auth/TokenRefreshCoordinator.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/config/BusinessOaConfiguration.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/config/BusinessOaConfigurationLoader.kt`
- Modify: `business-desktop/config/business-desktop-development.properties`
- Modify: `business-desktop/app/build.gradle.kts`
- Modify or delete affected tests under: `business-desktop/huitai-integration-core/src/test/`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/security/ComposeOaDirectAccessForbiddenTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/architecture/ModuleStructureTest.kt`

Task 16 的删除顺序以实际引用为准：先完成 Task 12 的 RPC orchestrator 和 Task 15 的 BFF/resource
消费，再运行 `rg` 确认以下生产引用全部为零，最后才删除旧实现：

- `BusinessDesktopCompositionRoot.kt` 中的 `OaAuthenticationGatewayFactory`、`OaAuthenticationGatewayBundle`、
  `AuthSessionManager`、`TokenRefreshCoordinator`、`HuitaiHttpClient`、`ReadyAuthenticatedHttpGate` 和
  `ReadyAuthenticatedHuitaiClient` 构造链。
- `BusinessAuthenticationOrchestrator.kt`、`OaTokenRefreshAdapter.kt`、`ReadyAuthenticated*.kt`、
  `JceksAuthCredentialPersistence.kt`、`BusinessAuthSessionMetadataStore.kt` 和
  `BusinessAuthRevocationMarkerStore.kt` 的生产引用；对应旧测试必须迁移到 RPC client/BFF fake 后再删除。
- `huitai-integration-core` 的 `oa/auth/*`、`http/HuitaiHttpClient.kt`、`auth/TokenRefreshCoordinator.kt`；
  仅当 `websocket/*`、`permission/*`、`tenant/*`、`identity/*` 也确认无生产引用时才删除，保留仍被 Agent
  或测试所需的通用模块，不用目录级机械删除。

删除完成的合同测试必须扫描 `business-desktop/**/src/main`，允许本地 desktop Bearer 与 JSON-RPC 代码，
但禁止 OA URL、OA token、远程 Authorization/tenant header 和 Ktor OA gateway。

- [x] **Step 1: 写 source-contract RED**

Compose main 源码不得出现远程 OA base URL、OA accessToken/refreshToken、Ktor OA gateway、远程 WS，或向远端 OA 注入 `Authorization`/`tenant-id` 的代码。不得误禁本地 WebSocket/loopback HTTP 使用的 desktop Bearer 连接认证；唯一 legacy 例外是 Task 6 受测试约束的三个 alias 常量。

- [x] **Step 2: 删除不可达代码和配置**

OA base URL 只进入后端 business profile；前端 development properties 不再包含 OA 地址。若 `huitai-integration-core` 仍有与 OA 无关能力，只删除 auth/http 子包，不机械删除模块。

- [ ] **Step 3: 运行模块和全桌面 GREEN**

```powershell
cd E:\huitai-work\BaBiQ\business-desktop
.\gradlew.bat :app:test --tests "*ComposeOaDirectAccessForbiddenTest" --tests "*ModuleStructureTest" --no-daemon --max-workers=1 --no-parallel
.\gradlew.bat test --no-daemon --max-workers=1 --no-parallel
```

- [ ] **Step 4: 中文提交**

```powershell
git commit --only -m "refactor(桌面): 移除远程OA直连权威" -- business-desktop/huitai-integration-core/src/main business-desktop/huitai-integration-core/src/test business-desktop/app/src/main business-desktop/app/src/test business-desktop/app/build.gradle.kts business-desktop/config/business-desktop-development.properties
```

### Task 17：fake OA 端到端、重连与敏感数据审计

**Files:**

- Create: `backend/src/test/java/com/wzx/babiq/server/business/BusinessOaAuthenticatedWebSocketIT.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/business/BusinessOaReconnectIT.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/business/BusinessWorkbenchEndToEndIT.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/business/BusinessOaSecretLeakAuditTest.java`
- Replace: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessAuthenticationLifecycleIT.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessWorkbenchLifecycleIT.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessAuthenticationReconnectIT.kt`

- [x] **Step 1: 写真实 local WS + fake OA E2E**

覆盖未 finalize 拒绝、候选、登录、权限安装、READY、完整工作台、401 singleflight、写不重放、退出、切身份、断开后 `session/get -> session/attach`、独立 startup restore、旧响应丢弃、auth/membership/network 三类错误。

- [x] **Step 2: 写敏感数据 canary 审计**

将唯一 password/token canary 注入 fake OA。password 允许且只能在受控 `business/auth/login` 请求 frame 出现 1 次，在 response/notification/日志/SQLite/JCEKS/context/items/tools/exceptions/HTTP/temp/report 中 0 次。OA Token 在桌面/RPC/HTTP 响应/日志/SQLite/context/items/tools/exceptions/report 中 0 次；受控 backend→OA Authorization 与 backend JCEKS 是预期 secret boundary。另扫描 multipart 临时目录、DTO `toString()`、ticket、文件名/路径/SHA、旧/reserved/staged/orphan alias 与 OA 错误正文。

- [x] **Step 3: 运行后端和桌面 E2E**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd "-Dtest=BusinessOaAuthenticatedWebSocketIT,BusinessOaReconnectIT,BusinessWorkbenchEndToEndIT,BusinessOaSecretLeakAuditTest" test
cd E:\huitai-work\BaBiQ\business-desktop
.\gradlew.bat :app:test --tests "*BusinessAuthenticationLifecycleIT" --tests "*BusinessWorkbenchLifecycleIT" --tests "*BusinessAuthenticationReconnectIT" --no-daemon --max-workers=1 --no-parallel
```

- [ ] **Step 4: 独立规格审查和代码质量审查**

所有 Critical/Important 必须修复并重新审查。

- [ ] **Step 5: 中文提交**

```powershell
git commit --only -m "test(网关): 覆盖OA会话与工作台全链路" -- <Task17 Files中的明确文件列表>
```

### Task 18：全量验证、IDEA 分离烟测、文档和最终提交

**Files:**

- Modify: `docs/superpowers/specs/2026-07-27-business-desktop-local-gateway-workbench-design.md`
- Modify: `docs/superpowers/plans/2026-07-27-business-desktop-local-gateway-workbench.md`
- Create: `docs/superpowers/plans/2026-07-27-business-desktop-local-gateway-handoff.md`
- Preserve user changes: existing staged deletions of `.run/Business Backend.run.xml` and `.run/Business Frontend.run.xml` (do not restore), the modification to `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagingScriptContractTest.kt`, and all `.tmp-*` directories (do not stage).

- [x] **Step 1: fresh 后端全量**

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd clean verify
```

Expected: exit 0，Surefire/Failsafe 0 failure/0 error。

2026-07-29 新鲜证据：固定 Java 21、离线、本地 Maven 仓库、串行执行，并为 business
profile 注入仅限本次测试进程的非默认 KeyStore password；原始 Maven exit 0。Surefire
241 suites / 1463 tests / 0 failure / 0 error / 3 skipped；Failsafe 36 suites /
161 tests / 0 failure / 0 error。

- [ ] **Step 2: fresh 桌面端全量**

```powershell
cd E:\huitai-work\BaBiQ\business-desktop
.\gradlew.bat test --rerun-tasks --no-daemon --max-workers=1 --no-parallel --no-build-cache "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
```

Expected: `BUILD SUCCESSFUL`，0 failed。

2026-07-29 最终新鲜证据：按离线、E 盘缓存、单 worker、禁并行、禁 build cache、
`--continue`、强制 rerun 执行。六个模块共 133 suites / 1024 tests，其中
1022 通过、2 failure、0 error；唯一失败
来自用户必须保留的 `PackagingScriptContractTest.kt` 直接读取同时必须保留 staged
deletion 的 `.run/Business Backend.run.xml` 与 `.run/Business Frontend.run.xml`。
未恢复 `.run`、未改用户测试，因此本 Step 仍未勾选。

2026-07-29 最终认证竞态与安装包审查修复后再次 fresh 验证通过：
31 个 task 全部重新执行，
重新生成 app image、MSI 和 EXE 后，从 MSI
行政提取的真实 Compose launcher 先完成 signed-out/login-gate/品牌/loopback
WebSocket/单次 token 删除烟测；随后使用提取包自带 runtime、classpath 与 bundled
Spring Boot，对 loopback fake OA 完成正式密码编码、登录、READY、六区工作台、
导航 allowlist 和助手 controller 烟测。两段运行均验证进程清理，并对桌面 KeyStore
密码、OA access/refresh canary 扫描临时目录、日志、SQLite、JCEKS 和报告，0 明文命中。
最终 MSI 为 235,487,951 bytes，SHA-256
`972092EAB1733058A89CC9D193570DDCBB3A96A22985BC54713037F1D95BF3CF`；
EXE 为 236,080,640 bytes，SHA-256
`101524221D3F1F1B2C241BA0F031F76C9349A9981F7A73DA30A0A7255CD23795`。

独立质量审查发现的 smoke 分区状态、环境恢复、canary 后 finally 删除、精确双 MD5、
未知路由 fail-closed 与 exited-root descendant cleanup 均已按 RED/GREEN 修复；
相关 focused 10 tests 全绿，最终复审为 Critical 0 / Important 0 / Minor 0。

- [x] **Step 3: IDEA 前后端分离烟测（未登录启动边界）**

使用当前仓库的独立 Run Configuration，不复制到 C 盘。验证：后端日志单独可见；未登录显示登录页且业务 RPC fail closed；登录后默认进入工作台；工作台真实数据、四类分页、数据范围、排序、日程、新增/附件；其他菜单占位；关闭前端不等于远端 logout；显式 logout 回登录页。

2026-07-30：在当前仓库以两个独立 Run 标签启动 `Business Backend` 和 `Business Frontend`；后端就绪于
`ws://127.0.0.1:49391/ws/agent`，两者日志独立。桌面窗口“翔鸟律智桌面端”显示登录门禁，未登录时不暴露工作台。
结束后 PID `34976`、`576`、`7128` 已退出且 49391 已关闭，`.tmp-business-desktop-idea-runtime` 保留。
本步骤仅验收分离启动和未登录门禁；登录后的真实 OA 数据/写操作仍由 Step 4 验收。

- [ ] **Step 4: 真实账号与同时登录烟测**

用用户提供或当前可用的真实账号验证正确密码、Web 与 Desktop 同时在线、refresh、重启 restore、断网恢复、logout 不退出 Web。不得自动破解滑块；如果账号或环境不可用，交接文档必须明确列为未验收，Goal 保持 active。

2026-07-30：`HUITAI_OA_USERNAME`、`HUITAI_OA_PASSWORD`、`HUITAI_OA_BASE_URL`、
`HUITAI_OA_TENANT_ID`、`HUITAI_OA_CAPTCHA_VERIFICATION`、`HUITAI_OA_CLIENT_ID` 和
`HUITAI_OA_CLIENT_SECRET` 均不存在；没有可安全自动使用的真实 OA 账号输入渠道。本 Step 未验收，
不得伪造通过。

- [ ] **Step 5: 安全与工作区审计**

```powershell
git diff --check
git status --short
rg -n "accessToken|refreshToken|Authorization|tenant-id|192\.168\.1\.20:48080" business-desktop --glob "**/src/main/**"
```

另扫描 runtime/log/SQLite/RPC canary，确认 OA secret 0 matches；确认用户 `.tmp-*` 与现有脏改动未暂存。

2026-07-29：`git diff --check` 与 `git diff --cached --check` 均 exit 0；source scan
只命中本地 loopback desktop Bearer、禁止字段黑名单和无关的本地 operation token。
后端与 fresh 安装包的 runtime/log/SQLite/RPC/temp/report canary 均通过。`.run`
staged deletion、`PackagingScriptContractTest.kt` 和全部 `.tmp-*` 均保留。旧 target
的跨 SID ACL 现场已整体保留到 `tmp/backend-stale-target-acl-20260729`，其中八个
旧测试目录仍不可由当前进程读取，因此无法声称“整个工作区每个历史字节”已完成扫描，
本 Step 保持未勾选。

- [x] **Step 6: 更新计划、handoff 和验收证据**

逐项标记本计划 checkbox，记录每条命令、测试数、exit code、烟测环境和明确未验收项。

- [ ] **Step 7: 最终独立审查与中文文档提交**

```powershell
git add docs/superpowers/specs/2026-07-27-business-desktop-local-gateway-workbench-design.md docs/superpowers/plans/2026-07-27-business-desktop-local-gateway-workbench.md docs/superpowers/plans/2026-07-27-business-desktop-local-gateway-handoff.md
git commit -m "docs(网关): 收口OA工作台迁移验收"
```

- [ ] **Step 8: 完成审计**

按纪要第 12 节和权威 design 第 15 节逐条找证据。只有代码、自动化、真实烟测、安全审计、独立审查、文档与中文提交全部满足且无剩余任务，才调用 `update_goal(status=complete)`。
