# 翔鸟律智桌面端 OA 登录 Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` (if subagents are available) or `superpowers:executing-plans` to implement this plan task-by-task. Every production change must follow `superpowers:test-driven-development`; before declaring completion use `superpowers:verification-before-completion`.

**Goal:** 把 OA Web 的密码登录主链迁移到 Kotlin Compose 桌面端，并确保只有 OA 会话、权限和 Agent 身份注册全部成功后，业务桌面与小律智能助手才可使用。

**Architecture:** OA HTTP 与本机 Agent WebSocket 保持分离。`BusinessAuthenticationOrchestrator` 是认证状态和 Agent 身份的唯一生产权威；候选 Token 只在认证专用网关中验证，READY 后才允许业务 HTTP、composer 和 application action。配置从外部 properties 加载，Token、会话元数据和记住密码分别写入现有 JCEKS 独立别名。

**Tech Stack:** Kotlin 2.3、Compose Desktop、Ktor Client 3.5、kotlinx.serialization、Coroutines/StateFlow、JCEKS、JSON-RPC 2.0、Gradle 9.3、JUnit/Compose UI Test。

---

## Chunk 1：配置与 OA 认证协议

### Task 1：可外置的 OA 配置

**Files:**

- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/config/BusinessOaConfiguration.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/config/BusinessOaConfigurationLoader.kt`
- Create: `business-desktop/app/src/main/resources/config/business-desktop.properties`
- Create: `business-desktop/config/business-desktop-development.properties`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessDesktopRuntimePaths.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/auth/config/BusinessOaConfigurationLoaderTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessDesktopRuntimePathsTest.kt`

- [ ] 1. 先写失败测试，覆盖显式文件、用户文件、bundled default 的整文件优先级，首次原子复制，空环境变量，绝对普通文件和链接拒绝。
- [ ] 2. 写失败测试覆盖 URL user-info/query/fragment、API prefix、1～120 秒超时、HTTPS 默认要求、仅 loopback/私网可显式启用 HTTP，以及任意 `password/token/secret/api-key` 敏感键直接拒绝。
- [ ] 3. 运行 `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessOaConfigurationLoaderTest" --tests "*BusinessDesktopRuntimePathsTest"`，确认因类和新路径不存在而 RED。
- [ ] 4. 最小实现 immutable 配置、loader 和 `desktop/config/business-desktop.properties` 路径；所有异常只暴露稳定码 `CONFIG_UNAVAILABLE` / `CONFIG_INVALID`。
- [ ] 5. 写入生产默认配置与开发配置；两者均不得包含 Token、密码或 API Key。
- [ ] 6. 重跑定向测试，确认 GREEN。
- [ ] 7. 中文提交：`feat(登录): 增加可外置 OA 配置`。

### Task 2：OA 认证 DTO、密码摘要和三类网关

**Files:**

- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/oa/auth/OaAuthenticationModels.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/oa/auth/OaAuthenticationGateway.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/oa/auth/OaPasswordEncoder.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/oa/auth/KtorOaAuthenticationGateway.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/oa/auth/OaAuthenticationError.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/oa/auth/OaPasswordEncoderTest.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/oa/auth/KtorOaAuthenticationGatewayTest.kt`

- [ ] 1. 写密码 RED 测试：输入不合法时拒绝；合法密码得到 Web 同款 `MD5(MD5(raw + "huitaisystem"))`；错误和 `toString` 不包含原文。
- [ ] 2. 用 Ktor MockEngine 写协议 RED 测试，固定租户查询、login、refresh、permission、candidate logout 的 method/path/query/body/header。
- [ ] 3. 写响应 RED 测试：公共 envelope、空 data、非 2xx、业务码、非法 JSON、超时、取消传播、多租户重复项、平台/用户/租户不一致。
- [ ] 4. 写安全 RED 测试：HttpClient 禁止 redirect；pre-auth 不读取 session；candidate 权限头只使用显式候选 Token 和 tenant；异常不包含正文或 Token。
- [ ] 5. 运行 `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*OaPasswordEncoderTest" --tests "*KtorOaAuthenticationGatewayTest"`，确认 RED。
- [ ] 6. 最小实现 DTO、公共解码、错误映射和 `OaPreAuthenticationGateway` / `OaCandidateAuthenticationGateway` / `OaAuthenticatedGateway`。
- [ ] 7. 重跑定向测试，确认 GREEN。
- [ ] 8. 中文提交：`feat(登录): 接入 OA 密码认证协议`。

## Chunk 2：本地凭据与认证事务

### Task 3：JCEKS 会话元数据和记住密码

**Files:**

- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/BusinessAuthSessionMetadataStore.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/BusinessLoginCredentialStore.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/JceksAuthCredentialPersistence.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/security/BusinessAuthSessionMetadataStoreTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/security/BusinessLoginCredentialStoreTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/security/JceksAuthCredentialPersistenceTest.kt`

- [ ] 1. 写 RED 测试覆盖版本化 session metadata `userId/tenantId/platformId` 与 remembered login 独立别名、保存/读取/删除、敏感 `toString` 脱敏。
- [ ] 2. 写 RED 测试：remembered entry 内容损坏只删本别名；共享 KeyStore 无法打开时保留原文件并 fail-closed。Token/metadata 的成对一致性和清理由 Task 4 的编排器测试证明。
- [ ] 3. 运行 `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessAuthSessionMetadataStoreTest" --tests "*BusinessLoginCredentialStoreTest" --tests "*JceksAuthCredentialPersistenceTest"`，确认 RED。
- [ ] 4. 基于现有 `JceksSecretStore` 最小实现两个 store；序列化缓冲和密码 `CharArray` 在使用后覆盖，不写 SQLite/日志。
- [ ] 5. 重跑定向测试，确认 GREEN。
- [ ] 6. 中文提交：`feat(登录): 安全保存 OA 会话与记住密码`。

### Task 4：登录控制器与唯一认证编排器

**Files:**

- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessAccessGateState.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessIdentityRegistry.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessAgentRegistrationTransactionPort.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessAuthenticationLifecycle.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessLoginState.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessLoginController.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/BusinessAuthenticationOrchestrator.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/auth/BusinessLoginControllerTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/auth/BusinessAuthenticationOrchestratorTest.kt`

- [ ] 1. 写控制器 RED 测试：账号、密码、协议、slider、Enter；单租户；多租户选择/取消；入驻中候选；重复提交；错误码及记住密码。
- [ ] 2. 写编排器 RED 测试覆盖 `STARTING/RESTORING/SIGNED_OUT/VERIFYING/AUTHENTICATING/SELECTING_TENANT/REGISTERING_AGENT/READY/SIGNING_OUT` 全部合法迁移。
- [ ] 3. 先定义可伪造的 `BusinessAgentRegistrationTransactionPort`（provisional register/commit/rollback/signed-out），再写两阶段提交/补偿 RED 测试：metadata 写失败、Token 写失败、identity/catalog/context 任一步失败、候选远端 logout、READY 之前 registry 始终为空；Task 5 才提供真实 Coordinator 适配器，避免顺序循环。
- [ ] 4. 写恢复 RED 测试：无凭据进入 `SIGNED_OUT`；Token+metadata 同时存在才恢复；缺一/损坏清两者；refresh Token 轮换；权限重载与身份一致性；任一步失败清本地认证回到登录页。
- [ ] 5. 写退出/过期 RED 测试：先关 gate 与提升 generation，再撤动作/发 signed-out/清 workspace，远端 logout 超时不阻止本地退出；`onAuthenticationExpired()` 必须走同一编排器入口。
- [ ] 6. 运行 `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessLoginControllerTest" --tests "*BusinessAuthenticationOrchestratorTest"`，确认 RED。
- [ ] 7. 最小实现 StateFlow 控制器、registry、lifecycle 和 orchestrator；`BusinessAuthenticationLifecycle.start()` 触发一次恢复，`close()` 取消恢复/登录 job 并本地撤权；生产不实例化 `AuthIdentityPublisher`。
- [ ] 8. 重跑定向测试，确认 GREEN。
- [ ] 9. 中文提交：`feat(登录): 建立 OA 认证状态机`。

## Chunk 3：生产装配与 Agent 安全门禁

### Task 5：让 Coordinator 和重连只服从 READY registry

**Files:**

- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessDesktopCoordinator.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessWorkspaceController.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/CoordinatorAgentRegistrationTransactionAdapter.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/ProductionIdentityBoundaryActionAdapter.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessDesktopCoordinatorTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRootTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/security/ProductionIdentityBoundaryActionAdapterTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessAuthenticationLifecycleIT.kt`

- [ ] 1. 写 RED 测试：启动 signed-out 只发未登录 identity；成功注册按 identity→catalog→page 顺序；全部成功后才提交桌面 store 与 registry。
- [ ] 2. 写 RED 测试：重连从 registry 读同一 READY identity；非 READY 只发 signed-out；不再读取 factory 启动时的 nullable identity。
- [ ] 3. 写 RED 集成测试：composition root 构造业务 controller 但 READY 前不允许任何刷新/业务副作用；退出清空 page/thread/suggestions/provider workspace；恢复与普通登录共用同一注册事务。
- [ ] 4. 运行相关定向测试并确认 RED。
- [ ] 5. 重构 Coordinator 为 provisional 注册 + commit/rollback；实现 registration transaction adapter 和完整旧 scope 的 production identity-boundary action adapter；在 composition root 装配配置、网关、session manager、stores、registry、orchestrator、login controller 和 lifecycle。
- [ ] 6. 在 root 成功创建 UI 后调用 lifecycle `start()`，并把 lifecycle `close()` 放入 root 的 UI 关闭顺序；生产装配测试证明无凭据到 `SIGNED_OUT`、有凭据到 `READY`、关闭会取消正在恢复的 job。
- [ ] 7. 保留 `frameworkDemoIdentity` 仅供旧自动化 fixture，正常生产和开发运行不设置。
- [ ] 8. 重跑定向与集成测试，确认 GREEN。
- [ ] 9. 中文提交：`feat(登录): 将 Agent 身份注册纳入登录事务`。

### Task 6：动作、HTTP 和 composer 的登录门禁

**Files:**

- Modify: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationActionRequestHandler.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationAuthenticationGate.kt`
- Modify: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/application/ApplicationActionRequestHandlerTest.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationController.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessProviderSettingsController.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/ReadyAuthenticatedHttpGate.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/auth/ReadyAgentUsageGate.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/auth/ReadyAuthenticatedHttpGateTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/auth/ReadyAgentUsageGateTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationControllerTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessProviderSettingsControllerTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRootTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessAuthenticationExpiryIT.kt`

- [ ] 1. 写 RED 测试：gate 非 READY 时 action request 必须返回稳定 `auth_required`，不可抛出后被 reader 静默吞掉。
- [ ] 2. 写 RED 测试：退出按完整旧 `ActionIdentityScope` 取消未执行记录，`EXECUTING` 从当前 UI 分离并保留旧 scope 对账；迟到结果不得更新新身份页面。
- [ ] 3. 写 controller RED 测试：conversation 的 thread/create、turn/start、附件发送、provider refresh/settings RPC 在未登录、恢复中和退出后都在发送前拒绝；READY 后放行。每次 RPC 捕获 authSessionId/identityEpoch，返回提交前再次校验；非 READY 时丢弃 gateway 入站业务事件。覆盖“退出后迟到 turn/provider/event 不更新 store、新身份不接收旧会话数据”。Main 不再无条件 `refreshProviders()`。
- [ ] 4. 写 RED 测试：authenticated HTTP 在发送前和回写前双检 authSessionId/identityEpoch；非 READY 或旧代次不发请求。
- [ ] 5. 写生产集成 RED 测试：401/499 或 singleflight refresh 失败只回调 orchestrator 的 `onAuthenticationExpired()`，随后 gate 关闭、registry 清空、signed-out 发布、workspace/action 撤权；不得形成第二条身份发布链。
- [ ] 6. 运行定向测试确认 RED；最小实现 HTTP/Agent usage gate、controller 发送与提交双门禁、入站事件过滤、handler 拒绝路径，并在 composition root 完成这些 gate 与 refresh-failure 回调的最终生产装配。
- [ ] 7. 重跑定向测试，确认 GREEN。
- [ ] 8. 中文提交：`fix(登录): 阻断未认证 Agent 与业务动作`。

## Chunk 4：登录 UI、启动门禁与打包验收

### Task 7：迁移 Compose 登录页和品牌资源

**Files:**

- Copy: `E:/huitai-work/huitai-law-oa/src/assets/imgs/login/login_bg.png` → `business-desktop/app/src/main/resources/brand/login_bg.png`
- Copy: `E:/huitai-work/huitai-law-oa/public/logo.gif` → `business-desktop/app/src/main/resources/brand/xiangniao-law-logo.gif`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/login/BusinessLoginScreen.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/login/BusinessSliderVerification.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/login/BusinessExternalLinkOpener.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/login/BusinessLoginScreenTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/login/BusinessLoginResourceTest.kt`

- [ ] 1. 先写并运行资源缺失 RED 测试；再复制原始品牌资源，确认资源解码 GREEN。
- [ ] 2. 写宽屏/窄屏 Compose UI RED 测试：左侧 68% 插画；窄屏隐藏插画；账号、密码、记住密码、协议、slider、按钮、错误区语义标签，并单独运行确认 RED。
- [ ] 3. 写交互 RED 测试：密码显隐、Enter 与点击一致、slider 未完成不请求、多租户弹窗显式选择且禁用入驻中候选。
- [ ] 4. 写外链 RED 测试：只允许已校验的协议/隐私 HTTPS URL，真实调用 `Desktop.browse` 的可替换适配器；不可用/失败映射 `AGREEMENT_OPEN_FAILED`，不使应用退出。
- [ ] 5. 最小实现登录布局和外链适配器，标题固定为“欢迎登录 / 翔鸟律智-法律智能平台”；不渲染短信、注册、忘记密码、微信或律所入驻假入口。
- [ ] 6. 重跑 UI/外链定向测试，确认 GREEN。
- [ ] 7. 中文提交：`feat(登录): 迁移翔鸟律智登录页面`。

### Task 8：Main 登录门禁、配置入口与新版 packaged smoke

**Files:**

- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/login/BusinessBootstrapFailureScreen.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeWindowComposition.kt`
- Modify: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeProbe.kt`
- Modify: `business-desktop/scripts/smoke-packaged-distribution.ps1`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeWindowCompositionTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeProbeTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagingScriptContractTest.kt`
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`
- Create: `.run/Business Backend.run.xml`
- Create: `.run/Business Frontend.run.xml`

- [ ] 1. 写 Main/Compose RED 合同：只有 `gate == READY` 才组合 `BusinessDesktopShell`；其余状态显示恢复进度或登录页，composer/助手不存在。
- [ ] 2. 写 bootstrap RED 测试：`CONFIG_UNAVAILABLE`、`CONFIG_INVALID`、`LOCAL_KEYSTORE_UNAVAILABLE` 即使发生在 root 完成前也必须打开 fail-closed 故障窗口，显示稳定提示，不显示业务壳且不发送 OA/Agent 业务请求。
- [ ] 3. 把 smoke RED 合同从 `shellComposed` 改为 `loginGateComposed=true` 与 `businessShellHiddenWhileSignedOut=true`，保留窗口、Logo 解码、本机 Agent、signed-out identity 和安全文件断言。
- [ ] 4. 运行 smoke/壳层定向测试并确认 RED。
- [ ] 5. 最小修改 Main、bootstrap failure screen、signals、probe 和 PowerShell；登录成功后复用现有 shell，退出立即回登录页并清空密码状态。
- [ ] 6. 新增可提交的 `.run` 两个独立配置；前端配置设置 `HUITAI_DESKTOP_CONFIG_FILE` 与外置后端模式，不设置 demo identity。
- [ ] 7. 重跑 smoke/壳层定向测试，确认 GREEN。
- [ ] 8. 中文提交：`feat(登录): 登录成功后开放业务桌面`。

### Task 9：全量验证与真实桌面烟测

**Files:**

- Modify: `docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md`（记录结果）
- Create: `docs/superpowers/plans/2026-07-22-business-desktop-oa-login-handoff.md`

- [x] 1. 运行认证定向套件，确认 0 failure：配置、网关、JCEKS、controller/orchestrator、action gate、UI、smoke。
- [x] 2. 使用 ASCII Gradle cache 运行桌面端全量测试，1059 tests / 0 failure。
- [x] 3. 运行后端 `clean verify`，1154 tests / 0 failure / 0 error。
- [x] 4. 分别启动 `Business Backend` 和 `Business Frontend`，验证未登录只显示登录页、后端日志可独立观察，停止后无残留端口、进程、session 或锁占用。
- [x] 5. 无可用真实账号，已完成 OA 端点契约和未登录分离 smoke；正确密码、真实刷新、重启恢复、主动退出、登录后 Agent 可用明确保留为未人工验证。
- [x] 6. 完成金丝雀和敏感键安全验收；日志、runtime、仓库、配置与 Git 未发现真实敏感值。
- [x] 7. 完成独立代码审查；修复旧身份 action 查询/取消绕过和 refresh Token URL query 两项 Important 问题，并重跑受影响测试。
- [x] 8. 中文提交：`test(登录): 完成 OA 登录验收记录`。

### Task 9 最终收口补充（2026-07-23）

- 独立审查范围为 `92d4f34..448187d`。
- `ACTION_REQUEST`、`ACTION_CANCEL`、`ACTION_STATUS`、`ACTION_RESULT_GET` 统一要求当前 READY 身份和同一 identity scope；操作在 current permit 内完成，通知型 cancel 在非 READY 或代次不匹配时丢弃。
- OA refresh 保持服务端 `@RequestParam("refreshToken")` 兼容语义，但桌面端改用 `application/x-www-form-urlencoded` POST body，URL query 不再携带 refresh Token。
- 两项回归测试均先 RED 后 GREEN；最终证据记录在 handoff。
