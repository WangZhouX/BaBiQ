# 翔鸟律智桌面端 OA 登录迁移：下一位 Agent 交接

> 最后更新：2026-07-23（Asia/Shanghai）
>
> 交接原因：用户要求暂停当前实现，改由下一位 Agent 继续。
>
> 当前结论：登录页、OA 认证底座、READY 门禁、前后端独立运行配置、`code=0/200` 兼容及首次未登录身份通知修复均已提交；修复后的后端和桌面端全量测试已通过。最终前后端分离烟测按用户交接要求中止，安全扫描、独立审查和最终文档收口仍未完成，Goal 必须保持 `active`。

## 1. 下一位 Agent 的任务

不要重做已经完成的登录迁移。当前工作区已干净，首先从全新的隔离 runtime 重新执行前后端分离烟测，确认首次 signed-out 通知不再报错；随后完成敏感信息扫描、独立代码审查和最终文档收口。没有真实 OA 账号时，不得声称正确密码登录、Token 刷新、重启恢复、主动退出和登录后 Agent 的真实链路已经人工验收通过。

## 2. 工作位置、Goal 与 Git 现场

- 主仓库：`E:\huitai-work\BaBiQ`
- 当前隔离 worktree：`C:\tmp\BaBiQ-oa-login`
- 当前分支：`codex/lawyer-oa-login`
- 源 OA Web 项目（只读参考）：`E:\huitai-work\huitai-law-oa`
- 当前 Goal：
  - Objective：在 BaBiQ 的 `business-desktop` 中完成翔鸟律智 OA 登录功能迁移剩余任务：实现登录页及资源、登录门禁与独立前后端运行配置，完成自动化/烟测/安全验收与中文提交。
  - Status：`active`
  - 不得在第 9 节所有收口工作完成前调用 `update_goal(status=complete)`。
- 最新已提交功能代码：

```text
4fe4be6 fix(登录): 接受首次未登录身份通知
df36573 docs(登录): 更新下一位Agent交接文档
fda372a fix(登录): 兼容 OA 成功响应码
e834ac3 fix(运行): 加固前后端独立启动参数
```

- 本次更新交接文档前，`git status --short` 为空。
- 最终烟测的临时后端进程已经停止：端口 `49391` 已释放、`development-session.json` 已清理、相关运行进程为 0。

用户固定要求：

- 不要再询问 Maven、Gradle 等命令执行权限；当前环境为 full access / never approval。
- 每次修改完成后使用中文 commit。
- IDEA 中前端、后端必须分别启动，后端日志单独可见。
- 不得在配置、日志、SQLite、RPC、上下文或 Git 中保存 Token、密码、API Key。
- 不要通过手工删除数据库、lock 或 build 文件掩盖正常停止流程的问题。

## 3. 必读材料

按顺序完整读取：

1. 仓库根目录 `AGENTS.md`
2. `docs/superpowers/plans/2026-07-22-business-desktop-oa-login-handoff.md`
3. `docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md`
4. `docs/superpowers/specs/2026-07-22-business-desktop-oa-login-design.md`

开始修复前使用：

- `superpowers:systematic-debugging`
- `superpowers:test-driven-development`

准备声称完成前使用：

- `superpowers:requesting-code-review`
- `superpowers:verification-before-completion`

## 4. 已完成并已提交的范围

### 4.1 OA 配置、认证协议、安全存储与状态机

- OA base URL、API prefix、platform、超时和协议链接由外部 properties 加载。
- 已实现账号候选查询、密码登录、Token 刷新、权限加载、候选远端退出。
- Token、session metadata、记住密码使用 JCEKS 独立别名。
- `BusinessAuthenticationOrchestrator` 是登录、恢复、刷新、退出和过期撤权的唯一生产权威。
- SQLite、日志和协议响应不展示密码或 Token。
- 只有 `READY` 身份可执行业务 HTTP、Agent RPC、composer 和 application action。
- 已补齐并发刷新、身份代次、迟到通知隔离、动作登记线性化和退出撤权等安全语义。

相关提交：

```text
03a7c81 fix(登录): 刷新终态由共享任务撤权
612feaa fix(登录): 权限变化时关闭刷新身份
719f9cb fix(登录): 按服务端身份边界隔离通知
87d87ca fix(登录): 绑定HTTP身份并禁止重定向
3657142 fix(登录): 按接收代次隔离业务通知
5f548a6 fix(登录): 线性化动作登记与撤权
7636359 fix(登录): 接通生产认证请求撤权链
```

### 4.2 Compose 登录页和品牌资源

- 已迁移原 OA `login_bg.png` 和翔鸟律智 Logo，并按 SHA-256 校验一致。
- 宽屏约 68% 左侧插画，窄屏隐藏插画。
- 仅保留账号、密码、记住密码、协议、滑块、登录按钮、错误信息和多租户选择。
- 未迁移短信登录、注册、忘记密码、微信或律所入驻假入口。
- 外链只允许经过校验的服务协议/隐私政策 HTTPS URL。
- 租户选择语义标识不暴露原始 tenantId。

相关提交：

```text
0916c10 feat(登录): 迁移翔鸟律智登录页面
d46ea64 fix(登录): 脱敏租户选择语义标识
```

### 4.3 Main 登录门禁和前后端独立运行

- `BusinessDesktopShell` 只在 `BusinessAccessGateState.READY` 分支组合。
- 未登录、恢复中、退出中不组合业务表单、业务导航、助手或确认弹窗。
- 配置/KeyStore 初始化失败时显示 fail-closed 故障窗口。
- 已移除生产环境 `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY` 绕过。
- 设置页“退出登录”通过 orchestrator 主动撤权。
- packaged smoke 通过真实 `BusinessDesktopShell.onShellComposed` 信号验证未登录业务壳不组合。
- 已提交两个分离的 IDEA Gradle Run Configuration：
  - `.run/Business Backend.run.xml`
  - `.run/Business Frontend.run.xml`
- 两个配置都包含：

```text
--no-daemon --max-workers=1 --no-parallel --no-build-cache
-Pkotlin.incremental=false
-Pkotlin.compiler.execution.strategy=in-process
```

- 前端配置保留：

```text
HUITAI_DESKTOP_EXTERNAL_BACKEND=1
HUITAI_DESKTOP_CONFIG_FILE=$PROJECT_DIR$/business-desktop/config/business-desktop-development.properties
```

- 配置不依赖 `clean`，也不要求用户手工删除残留 build/runtime 文件。

相关提交：

```text
a6e720e feat(登录): 登录成功后开放业务桌面
af1d4c2 fix(登录): 用真实组合信号验证业务壳隐藏
e834ac3 fix(运行): 加固前后端独立启动参数
```

### 4.4 真实 OA `code=0/200` 兼容

真实开发端点：

```text
GET http://192.168.1.20:48080/law-api/system/auth/get-users-by-mobile?mobile=__babiq_contract_probe_20260723__
HTTP 200
{"code":0,"data":[],"msg":"","tableName":null,"traceId":"2080180695422148608"}
```

源 Web 的 `service.ts` 使用 `Number(data.code || result_code)`；数字 `0` 为 falsy，因此被 Web 转成默认 `200`。桌面端此前只接受 `"200"`，并在判断 code 前强制 `msg` 非空，导致真实响应失败。

TDD 证据：

1. 在 `KtorOaAuthenticationGatewayTest` 新增两个真实契约测试：
   - `code=0,msg=""` 的候选响应可成功解析。
   - `code=0,msg="",data=[]` 映射为 `ACCOUNT_NOT_FOUND`。
2. RED：14 tests / 2 failed。
3. 最小修复：成功码显式限定为 `"0"` 与 `"200"`；只有错误响应才要求非空 `msg`。
4. GREEN：14 tests / 0 failed。
5. 已提交：

```text
fda372a fix(登录): 兼容 OA 成功响应码
```

## 5. 最新自动化验证证据

以下全量结果包含 `4fe4be6` 首次 signed-out 修复，是本次重新运行的新鲜证据。

### 5.1 桌面端全量

执行：

```powershell
cd C:\tmp\BaBiQ-oa-login\business-desktop
$env:GRADLE_USER_HOME='C:\tmp\gradle-home-ascii'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=C:\tmp\gradle-temp'
$env:JAVA_HOME='D:\Program Files\jdk21'
.\gradlew.bat clean test --rerun-tasks --no-daemon --max-workers=1 --no-parallel --no-build-cache "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
```

结果：

- `BUILD SUCCESSFUL in 4m 35s`
- 49/49 actionable tasks executed
- 124 suites / 1059 tests
- failures 0 / errors 0 / skipped 0
- 仅有两个已知 opt-in warning。

### 5.2 后端全量

执行：

```powershell
cd C:\tmp\BaBiQ-oa-login\backend
$env:JAVA_HOME='D:\Program Files\jdk21'
.\mvnw.cmd clean verify
```

结果：

- exit code 0，约 3m 3s
- Surefire：200 suites / 1089 tests / 0 failure / 0 error / 3 skipped
- Failsafe：20 suites / 65 tests / 0 failure / 0 error / 0 skipped
- 合计：220 suites / 1154 tests / 0 failure / 0 error / 3 skipped

桌面端全量中的 `packageBusinessBackendJar` 也重新打包了当前后端 jar。

## 6. 最新分离烟测及其发现

隔离运行目录：

```text
C:\tmp\babiq-oa-login-final-code0-20260723-150443
```

金丝雀：

```text
OA_LOGIN_SECURITY_CANARY_CODE0_20260723
```

启动脚本直接解析已提交 `.run/*.run.xml` 的 task、scriptParameters 和环境变量，没有手工追加参数。

### 6.1 已验证正常的部分

后端：

- 监听 `127.0.0.1:49391`。
- 23 个 migration 已应用，数据库版本 v24。
- runner 的真实 WebSocket readiness probe 成功连接并正常关闭。
- 启动阶段没有 `BUILD FAILED`。
- stderr 只有 `JAVA_TOOL_OPTIONS` 提示。

前端：

- 通过独立 `:app:runBusinessFrontendDevelopment` 启动。
- 只出现一个标题为“翔鸟律智桌面端”的 Java 窗口。
- 实际画面为左侧插画和 Logo、右侧账号/密码/记住密码/协议/滑块/禁用登录按钮。
- 未出现业务导航、业务表单或“小律智能助手”。
- 关闭前端窗口后，前端 Gradle 为 `BUILD SUCCESSFUL in 2m 23s`，后端仍继续监听。
- Skiko DirectX12 fallback 后窗口正常渲染；这是已知图形后端降级警告，不等于启动失败。

停止后：

- 端口已释放。
- runner 数量为 0。
- `development-session.json` 不存在。
- development lock 与 instance lock 均可独占打开。
- 未手工删除残留文件。
- 主动停止 Spring 长驻子进程后，后端 Gradle task 显示 FAILED 是人工终止结果，不是启动失败；启动成功应以 ready 日志、端口、WebSocket 探针为准。

### 6.2 烟测暴露的真实缺陷

后端日志在前端连接和关闭时各出现一次：

```text
JSON-RPC notification 执行失败: method=application/identity/update
JsonRpcException: Invalid application identity parameters
```

这说明“未登录桌面启动正常显示”成立，但新连接首次发布 signed-out 身份时，前后端身份协议不一致，不能把本轮分离烟测写成完全通过。

## 7. 已提交修复：首次 signed-out 身份通知

### 7.1 根因

设计和实现计划要求：

- 桌面端启动时如果不是 `READY`，只发布 unauthenticated/signed-out 身份。
- 后续真实登录成功后，在同一连接上调用 authenticated `bind`。

前端 `BusinessDesktopCompositionRoot.registerActiveConnection` 正是这样执行：

```text
未 READY -> application/identity/update(signedOut)
登录成功 -> application/identity/bind(authenticated)
```

后端 `ApplicationIdentityRegistry.update()` 此前无条件调用 `requireState(connection)`。新连接尚未做 authenticated bind，没有现存状态，因此首次 signed-out update 被拒绝。

正确语义：

- 新连接的首次 signed-out update 应被校验并接受。
- 它应执行连接快照清理，保持 fail-closed。
- 它不能创建伪造的 registry state，否则会阻止后续首次 authenticated bind。
- 已绑定连接的 authenticated update 仍必须遵循既有严格 epoch 规则。
- 未 bind 的 authenticated update 仍必须拒绝。
- 已绑定连接后再 signed-out，继续走原有有状态 update 路径。

### 7.2 已完成的 RED

新增测试：

1. `ApplicationIdentityRegistryTest.initialSignedOutUpdateRunsCleanupWithoutBlockingLaterAuthenticatedBind`
2. `ApplicationIdentityCatalogHandlersTest.initialSignedOutUpdateIsAcceptedBeforeFirstAuthenticatedBind`

定向命令：

```powershell
cd C:\tmp\BaBiQ-oa-login\backend
$env:JAVA_HOME='D:\Program Files\jdk21'
.\mvnw.cmd "-Dtest=ApplicationIdentityRegistryTest,ApplicationIdentityCatalogHandlersTest" test
```

RED 结果：

- 24 tests
- 2 errors
- 22 个既有测试通过
- 两个新测试分别因 `Identity must be bound before update` 和 `Invalid application identity parameters` 失败。

### 7.3 当前 GREEN 实现

`ApplicationIdentityRegistry.update()` 在正常 stateful update 前增加 `acceptInitialSignedOut(...)`：

- 只处理 `authenticated=false`。
- 在 connection 锁内确认没有既有状态。
- 执行 `beforeCommitCleanup`。
- 再检查没有并发身份状态。
- 返回空身份，不写入伪造水位，不触发 identity change listener。
- 后续 authenticated bind 仍可正常建立 epoch 2 身份。

同一定向命令的 GREEN 结果：

- `BUILD SUCCESS`
- 24 tests / 0 failures / 0 errors / 0 skipped。

### 7.4 已完成的扩展验证与提交

更宽的身份定向测试：

```powershell
.\mvnw.cmd "-Dtest=ApplicationIdentityRegistryTest,ApplicationIdentityCatalogHandlersTest,BusinessJsonRpcAccessPolicyTest,ApplicationBridgeLifecycleCoordinatorTest" test
```

- 34 tests / 0 failures / 0 errors / 0 skipped。
- 另运行 `ApplicationIdentityRegistryTest` + `ApplicationBridgeEndToEndIT`：
  - 13 个 registry 单测通过。
  - 8 个真实 WebSocket bridge IT 通过。
- 已中文提交：

```text
4fe4be6 fix(登录): 接受首次未登录身份通知
```

## 8. 尚未完成或不能声称完成的验收

仍需完成：

- 使用全新隔离 runtime 重新进行前后端分离烟测。
- 确认新烟测日志中以下内容均为 0 次：

```text
JSON-RPC notification 执行失败: method=application/identity/update
Invalid application identity parameters
```

- 新 runtime 的金丝雀与敏感信息扫描。
- 全范围独立代码审查。
- 实施计划、设计规格和最终交接/验收记录收口。

2026-07-23 16:00 启动过一次仅后端的临时烟测，目录为：

```text
C:\tmp\babiq-oa-login-final-identity-20260723-160018
```

该后端已经到达 `Business Backend ready at ws://127.0.0.1:49391/ws/agent`，但用户随即要求交接，因此没有启动前端，也没有形成新的 signed-out 端到端证据。随后主动终止后端，造成该长驻 Gradle task 按预期显示非零退出；端口、session 和进程均已清理。下一位 Agent 必须使用另一个全新 runtime 重做完整烟测，不得把这次中止记录写成通过。

由于没有可用的真实 OA 账号，尚未人工验证：

- 正确密码登录。
- Token 刷新。
- 重启恢复。
- 主动退出。
- 登录后 Agent 可用。

没有绕过或自动破解滑块。严禁把上述项目写成“已通过”。

## 9. 下一位 Agent 的执行顺序

1. 进入 `C:\tmp\BaBiQ-oa-login`，确认分支 `codex/lawyer-oa-login`。
2. 完整读取第 3 节材料，核对 `git status --short` 为空。
3. 确认没有残留 Gradle/Java 前后端进程正在写 `business-desktop/**/build`。
4. 新建全新的隔离 runtime，严格从 `.run/Business Backend.run.xml` 与 `.run/Business Frontend.run.xml` 解析配置，重新做前后端分离烟测。
5. 在新烟测中确认：
   - 后端 ready、端口监听、WebSocket probe 成功。
   - 前端只出现登录窗口，业务壳未组合。
   - 前端正常关闭后，后端继续运行。
   - 后端日志不再出现第 6.2 节身份错误。
   - 停止后端后端口、session 和锁均正确释放。
6. 用新的随机金丝雀扫描整个新 runtime：
    - `rg -a --fixed-strings <CANARY> <runtime>` 必须为 0 matches。
    - 检查日志、SQLite、JCEKS、session、配置和 Git 中无明文密码/Token/API Key。
7. 发起独立代码审查，重点检查：
    - READY 门禁是否存在绕过。
    - 首次 signed-out、首次 bind、刷新、退出和迟到数据的身份代次隔离。
    - Token、密码、API Key 是否可能进入日志、SQLite、RPC item/context。
    - `code=0/200` 是否仅放行明确成功码。
    - 两个 Run Configuration 是否仍保持前后端分离。
8. 修复审查发现并重跑受影响测试。
9. 更新：
    - `docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md`
    - `docs/superpowers/specs/2026-07-22-business-desktop-oa-login-design.md`
    - 本交接文档或新增最终验收记录。
10. 执行：
    - `git diff --check`
    - `git status --short`
    - 最终敏感键与金丝雀扫描。
11. 使用中文 commit 收口文档，建议：

```text
test(登录): 完成 OA 登录验收记录
```

12. 只有上述自动化、分离烟测、安全扫描、独立审查和文档全部完成后，才能：
    - 把实施计划最终任务标记为 completed。
    - 调用 `update_goal(status=complete)`。

## 10. 关键文件

- 实施计划：`docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md`
- 设计规格：`docs/superpowers/specs/2026-07-22-business-desktop-oa-login-design.md`
- 本交接：`docs/superpowers/plans/2026-07-22-business-desktop-oa-login-handoff.md`
- 登录门禁入口：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- 生产装配：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- 登录 UI：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/login/BusinessLoginScreen.kt`
- OA 网关：`business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/oa/auth/KtorOaAuthenticationGateway.kt`
- OA 网关测试：`business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/oa/auth/KtorOaAuthenticationGatewayTest.kt`
- 后端身份 registry：`backend/src/main/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistry.java`
- 后端身份 registry 测试：`backend/src/test/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistryTest.java`
- 后端身份 handler 测试：`backend/src/test/java/com/wzx/babiq/server/application/api/ApplicationIdentityCatalogHandlersTest.java`
- packaged smoke：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeWindowComposition.kt`
- 后端独立 runner：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendDevelopmentRunner.kt`
- IDEA 后端配置：`.run/Business Backend.run.xml`
- IDEA 前端配置：`.run/Business Frontend.run.xml`
- 运行配置合同测试：`business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagingScriptContractTest.kt`

## 11. 操作注意事项

- 不要并行启动多个会写同一 `business-desktop/**/build` 的 Gradle。
- Windows 中文用户名环境优先使用 ASCII Gradle cache：`C:\tmp\gradle-home-ascii`。
- 独立后端从本机环境继承 `HUITAI_DESKTOP_KEYSTORE_PASSWORD`，不得提交真实值。
- 不得恢复 `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY` 生产绕过。
- 不得把 Token、密码、API Key 写进 Run Configuration、properties、SQLite、日志、RPC、上下文或 Git。
- 不需要删除残留数据库或锁文件；正确停止路径应释放锁并清理 development session。
- 部分 Surefire XML 的 Windows 中文用户名属性可能编码损坏。统计时读取 `<testsuite ...>` 首标签属性，不要因 PowerShell `[xml]` 解析失败误判测试失败。
- Skiko DirectX12 失败后 fallback 且窗口正常渲染，是图形后端降级警告，不是启动失败。

## 12. 可直接复制给下一位 Agent 的提示词

```text
继续完成当前 active Goal：
“在 BaBiQ 的 business-desktop 中完成翔鸟律智 OA 登录功能迁移剩余任务：实现登录页及资源、登录门禁与独立前后端运行配置，完成自动化/烟测/安全验收与中文提交”。

工作目录：C:\tmp\BaBiQ-oa-login
分支：codex/lawyer-oa-login

先完整读取：
- AGENTS.md
- docs/superpowers/plans/2026-07-22-business-desktop-oa-login-handoff.md
- docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md
- docs/superpowers/specs/2026-07-22-business-desktop-oa-login-design.md

不要重做已完成的 OA 登录功能。`fda372a` 已用 TDD 修复 code=0/200 兼容，`4fe4be6` 已修复并提交首次 application/identity/update(signed-out) 被拒绝的问题；后端 1154 tests 和桌面端 1059 tests 的全量结果均为 0 失败。当前工作区应为干净状态。

从一个全新的隔离 runtime 继续完整的前后端分离烟测，确认后端日志中 `application/identity/update` 与 `Invalid application identity parameters` 错误均为 0，再完成金丝雀/敏感信息扫描、独立代码审查和设计/计划/验收文档收口。不要复用或把 `C:\tmp\babiq-oa-login-final-identity-20260723-160018` 的中止记录写成通过。

没有真实 OA 账号，不得声称正确密码登录、Token 刷新、重启恢复、主动退出或登录后 Agent 已人工验收通过。全部收口后才可完成 Goal。
```
