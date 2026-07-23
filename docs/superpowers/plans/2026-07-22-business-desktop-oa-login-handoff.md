# 翔鸟律智桌面端 OA 登录迁移交接

> 最后更新：2026-07-23（Asia/Shanghai）
>
> 交接原因：用户要求暂停当前实现，由下一个 Agent 继续。
>
> 当前结论：主要功能、运行配置和大部分验收已经完成；Goal 仍为 active，不能标记完成。

## 1. 给下一个 Agent 的一句话任务

继续完成翔鸟律智 OA 登录迁移的最后收口：先用 TDD 修复真实 OA `code=0` 成功响应与桌面网关仅接受 `"200"` 的契约不一致，再完成独立代码审查、受影响测试、最终文档和中文提交；没有真实账号时，不得声称真实登录链路人工验收通过。

## 2. 工作位置与 Git 状态

- 主仓库：`E:\huitai-work\BaBiQ`
- 当前隔离 worktree：`C:\tmp\BaBiQ-oa-login`
- 当前分支：`codex/lawyer-oa-login`
- 本次交接前代码 HEAD：`e834ac3 fix(运行): 加固前后端独立启动参数`
- 源 OA Web 项目只读参考：`E:\huitai-work\huitai-law-oa`
- 当前工作区在编写本交接文档前为干净状态。
- 当前 Goal：
  - Objective：在 BaBiQ 的 `business-desktop` 中完成翔鸟律智 OA 登录功能迁移剩余任务。
  - Status：`active`
  - 不要在完成第 8 节全部收口前调用 `update_goal(status=complete)`。

用户固定要求：

- 不再询问 Maven、Gradle 等命令执行权限；当前环境为 full access / never approval。
- 修改完成后使用中文 commit。
- IDEA 中前端和后端必须可以分别启动，后端日志独立可见。
- 不得在配置、日志、SQLite 或 Git 中保存 Token、密码、API Key。

## 3. 已完成的功能范围

### 3.1 Task 1～6：配置、认证协议、安全存储、状态机与门禁

已完成并经过多轮测试与安全修复：

- OA base URL、API prefix、platform、超时及协议链接均从外部 properties 加载。
- 已实现账号候选查询、密码登录、Token 刷新、权限加载、候选远端退出。
- Token、session metadata、记住密码分别使用 JCEKS 独立别名。
- SQLite、日志和协议响应不展示密码或 Token。
- `BusinessAuthenticationOrchestrator` 是登录、恢复、刷新、退出和过期撤权的唯一生产权威。
- 只有 `READY` 身份可执行业务 HTTP、Agent RPC、composer 和 application action。
- 已补齐并发刷新、身份代次、迟到通知隔离、动作登记线性化、退出撤权等安全语义。

相关最后一组安全提交：

- `03a7c81 fix(登录): 刷新终态由共享任务撤权`
- `612feaa fix(登录): 权限变化时关闭刷新身份`
- `719f9cb fix(登录): 按服务端身份边界隔离通知`
- `87d87ca fix(登录): 绑定HTTP身份并禁止重定向`
- `3657142 fix(登录): 按接收代次隔离业务通知`
- `5f548a6 fix(登录): 线性化动作登记与撤权`
- `7636359 fix(登录): 接通生产认证请求撤权链`

### 3.2 Task 7：Compose 登录页和品牌资源

已提交：

- `0916c10 feat(登录): 迁移翔鸟律智登录页面`
- `d46ea64 fix(登录): 脱敏租户选择语义标识`

结果：

- 原 OA `login_bg.png` 和翔鸟律智 Logo 已按 SHA-256 精确复制。
- 宽屏约 68% 左侧插画，窄屏隐藏插画。
- 仅保留账号、密码、记住密码、协议、滑块、登录按钮、错误信息和多租户选择。
- 未迁移短信登录、注册、忘记密码、微信或律所入驻假入口。
- 外链只允许经过校验的服务协议/隐私政策 HTTPS URL。
- 租户语义标识不暴露原始 tenantId。

### 3.3 Task 8：Main 登录门禁和独立运行入口

已提交：

- `a6e720e feat(登录): 登录成功后开放业务桌面`
- `af1d4c2 fix(登录): 用真实组合信号验证业务壳隐藏`
- `e834ac3 fix(运行): 加固前后端独立启动参数`

结果：

- `BusinessDesktopShell` 仅在 `BusinessAccessGateState.READY` 分支组合。
- 未登录、恢复中、退出中不会组合业务表单、业务导航、助手或确认弹窗。
- 配置/KeyStore 初始化失败时显示 fail-closed 故障窗口。
- 生产代码已移除 `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY` 绕过。
- 设置页“退出登录”通过 orchestrator 主动撤权。
- packaged smoke 使用真实 `BusinessDesktopShell.onShellComposed` 信号证明未登录业务壳未组合。
- 已提交两个分离的 IDEA Gradle Run Configuration：
  - `.run/Business Backend.run.xml`
  - `.run/Business Frontend.run.xml`
- 两个配置都包含以下稳定构建参数：

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

- 运行配置不依赖 `clean`，也不要求用户手工删除残留 build/runtime 文件。

## 4. 运行配置修复的 TDD 证据

问题复现：

- 原配置仅有 `--no-daemon --max-workers=1`。
- 在共享构建目录被并发或中断后，曾出现：

```text
:app:compileKotlin FAILED
Unresolved reference 'BusinessProviderDraft'
Unresolved reference 'BusinessAttachmentDraft'
```

TDD 过程：

1. 在 `PackagingScriptContractTest.kt` 增加 RED 断言，要求前后端配置都包含四个稳定参数。
2. 定向测试真实 RED：3 tests / 1 failed，失败原因是配置缺少 `--no-parallel` 等参数。
3. 只修改两个 `.run/*.run.xml` 的 `scriptParameters`。
4. 定向测试 GREEN：3 tests / 0 failure，`BUILD SUCCESSFUL in 2m 57s`。
5. 提交：`e834ac3 fix(运行): 加固前后端独立启动参数`。

## 5. 本次已有的真实验证证据

### 5.1 桌面端全量测试

执行：

```powershell
$env:GRADLE_USER_HOME='C:\tmp\gradle-home-ascii'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=C:\tmp\gradle-temp'
.\gradlew.bat clean test --rerun-tasks --no-daemon --max-workers=1 --no-parallel --no-build-cache "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
```

结果：

- `BUILD SUCCESSFUL in 3m 48s`
- 49/49 actionable tasks executed
- 124 suites / 1057 tests
- failures 0 / errors 0 / skipped 0

模块统计：

| 模块 | suites | tests |
|---|---:|---:|
| agent-client-core | 15 | 151 |
| app | 64 | 409 |
| application-action-core | 11 | 154 |
| framework-demo | 5 | 27 |
| huitai-integration-core | 13 | 157 |
| presentation-core | 3 | 68 |
| security-audit-core | 13 | 91 |

注意：该全量结果是在发现第 6 节 `code=0` 契约缺口之前跑出的。下一个 Agent 修复网关后必须重新运行受影响定向测试和桌面端全量测试。

### 5.2 后端全量测试

执行：

```powershell
$env:JAVA_HOME='D:\Program Files\jdk21'
.\mvnw.cmd clean verify
```

结果：

- exit code 0，耗时约 3m 4s
- 581 个生产 Java 源文件、221 个测试源文件
- Surefire：200 suites / 1087 tests / 0 failure / 0 error / 3 skipped
- Failsafe：20 suites / 65 tests / 0 failure / 0 error / 0 skipped
- 合计：220 suites / 1152 tests / 0 failure / 0 error / 3 skipped

部分 Surefire XML 的 Windows 中文用户名属性存在编码损坏；统计时应对 `<testsuite ...>` 首标签用正则读取属性，不要因 PowerShell `[xml]` 解析失败误判为测试失败。

### 5.3 真实前后端分离烟测

隔离运行目录：

```text
C:\tmp\babiq-oa-login-final-smoke-20260723-140522
```

启动脚本直接解析已提交 `.run/*.run.xml` 的 task、scriptParameters 和环境变量，没有人工追加稳定参数。

后端：

- Spring Boot 监听 `127.0.0.1:49391`。
- 23 个 migration 已应用，数据库版本 v24。
- 真实 WebSocket readiness/authentication probe 成功连接并正常关闭。
- 日志出现：

```text
Business Backend ready at ws://127.0.0.1:49391/ws/agent
```

- stderr 为空，无启动阶段 `BUILD FAILED`。

前端：

- 只出现 1 个标题为“翔鸟律智桌面端”的 Java 窗口。
- `:app:runBusinessFrontendDevelopment` 成功启动。
- 关闭前端窗口后：
  - 前端正常退出。
  - 后端继续监听。
  - `DesktopLockExists=False`。
  - 前端 Gradle：`BUILD SUCCESSFUL in 1m 29s`。

后端清理：

- 通过停止 Spring 子进程结束烟测，没有手工删除文件。
- 端口 `49391` 已释放。
- `development-session.json` 不存在。
- development lock 和 instance lock 均可独占打开。
- 后端 Gradle task 在主动杀掉 Spring 子进程后会显示 FAILED，这是人为终止长驻服务的结果，不是启动失败；以 ready 日志、端口、WebSocket 探针和锁清理为准。

### 5.4 金丝雀与敏感信息扫描

金丝雀：

```text
OA_LOGIN_SECURITY_CANARY_FINAL_20260723
```

范围：

- 上述隔离 runtime 全目录，共扫描 17 个文件。
- 包含 6 个日志、2 个 SQLite、2 个 JCEKS 及其他 runtime/lock 文件。

结果：

- `rg -a --fixed-strings` 全目录 0 matches。
- 已提交配置中金丝雀、demo identity、KeyStore 密码值 0 matches。
- session token 不存在。
- development session 文件不存在。

## 6. 当前确定未完成的问题：OA 成功响应码不一致

这是下一个 Agent 必须首先处理的问题。

### 6.1 真实数据

2026-07-23 对开发 OA 端点执行：

```text
GET http://192.168.1.20:48080/law-api/system/auth/get-users-by-mobile?mobile=__babiq_final_smoke_20260723__
```

结果：

- 网络可达。
- HTTP 200。
- JSON 业务响应 `code=0`。
- `data=[]`。

### 6.2 桌面端当前实现

文件：

```text
business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/oa/auth/KtorOaAuthenticationGateway.kt
```

`successData()` 当前只接受：

```kotlin
if (code != "200") {
    throw OaAuthenticationException(...)
}
```

因此真实开发端点的 `code=0` 会被桌面端误判为业务失败，空候选也无法继续映射为 `ACCOUNT_NOT_FOUND`。

### 6.3 源 Web 端真实行为

源项目：

```text
E:\huitai-work\huitai-law-oa\src\config\axios\config.ts
E:\huitai-work\huitai-law-oa\src\config\axios\service.ts
```

Web 配置写的是：

```ts
result_code: 200
```

但响应拦截器使用：

```ts
const code = Number(data.code || result_code)
```

JavaScript 中数字 `0` 为 falsy，所以服务端 `code=0` 会被 Web 端转换为默认 `200` 并当作成功。这解释了 Web 可以使用该接口，而桌面端会失败。

### 6.4 建议的 TDD 修复顺序

按 `superpowers:systematic-debugging` 与 `superpowers:test-driven-development` 执行：

1. 在 `KtorOaAuthenticationGatewayTest` 先增加失败测试：
   - `{"code":0,"data":[]}` 应映射为 `ACCOUNT_NOT_FOUND`。
   - `{"code":0,"data":[候选]}` 应成功解析候选。
   - 未知非成功码仍应映射到稳定业务错误，不能全部放行。
2. 单独运行网关测试，确认新测试因只接受 `"200"` 而 RED。
3. 最小修复 `successData()`。
4. 结合真实端点和既有兼容测试，建议显式只接受 `"0"` 与 `"200"`，不要复制 Web 的 `data.code || 200` 隐式 falsy 行为。
5. 重跑网关定向测试，确认 GREEN。
6. 再跑桌面端全量测试。
7. 更新设计/实施文档中“成功码只有 200”的过期描述，记录真实环境同时兼容 0/200 的依据。
8. 中文提交建议：

```text
fix(登录): 兼容 OA 成功响应码
```

## 7. 尚未完成的验收

- 最终独立代码审查未完成：
  - 审查曾以 BASE `d37a0fc08b52e8dd2bbc7f7e5ae658de4acbef8b`、HEAD `e834ac364d1ea12e4a499af0ef85343a11037c71` 启动。
  - 因用户要求暂停，审查 Agent 已被中止。
  - 下一个 Agent 应在修复 `code=0` 后重新做全范围独立审查。
- 没有可用的真实 OA 账号，因此未人工验证：
  - 正确密码登录。
  - Token 刷新。
  - 重启恢复。
  - 主动退出。
  - 登录后 Agent 可用。
- 没有绕过或自动破解滑块。
- 当前仅有端点可达性、错误账号方向、自动化协议测试、安全扫描和桌面启动证据。

严禁把上述缺少真实账号的项目写成“已通过”。

## 8. 下一个 Agent 的执行清单

1. 进入 `C:\tmp\BaBiQ-oa-login`，确认分支为 `codex/lawyer-oa-login`。
2. 读取仓库 `AGENTS.md`、设计、实施计划和本交接文档。
3. 确认没有残留 Gradle、Java 前后端进程在写 `business-desktop/**/build`。
4. 按第 6.4 节用 TDD 修复 OA `code=0` 契约。
5. 运行网关定向测试和桌面端全量测试。
6. 重新发起完整独立代码审查，重点检查：
   - READY 门禁是否存在绕过。
   - 刷新/退出/迟到数据的身份代次隔离。
   - Token、密码、API Key 是否可能进入日志、SQLite、RPC item/context。
   - `code=0/200` 兼容是否只放行明确成功码。
   - 两个 Run Configuration 是否仍保持前后端分离。
7. 修复审查发现后，重跑受影响测试。
8. 更新：
   - `docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md`
   - `docs/superpowers/specs/2026-07-22-business-desktop-oa-login-design.md`
   - 本交接文档
9. 执行最终：
   - `git diff --check`
   - `git status --short`
   - 源码敏感键/金丝雀扫描
10. 中文提交建议：

```text
test(登录): 完成 OA 登录验收记录
```

11. 只有全部自动化、独立审查和文档收口完成后，才能：
   - 把实施计划 Task 9 标记 completed。
   - 调用 `update_goal(status=complete)`。

## 9. 关键文件

- 实施计划：`docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md`
- 设计规格：`docs/superpowers/specs/2026-07-22-business-desktop-oa-login-design.md`
- 本交接：`docs/superpowers/plans/2026-07-22-business-desktop-oa-login-handoff.md`
- 登录门禁入口：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- 生产装配：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- 登录 UI：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/login/BusinessLoginScreen.kt`
- OA 网关：`business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/oa/auth/KtorOaAuthenticationGateway.kt`
- OA 网关测试：`business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/oa/auth/KtorOaAuthenticationGatewayTest.kt`
- packaged smoke：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeWindowComposition.kt`
- 后端独立 runner：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendDevelopmentRunner.kt`
- IDEA 后端配置：`.run/Business Backend.run.xml`
- IDEA 前端配置：`.run/Business Frontend.run.xml`
- 运行配置合同测试：`business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagingScriptContractTest.kt`

## 10. 操作注意事项

- 不要并行启动多个会写同一 `business-desktop/**/build` 的 Gradle。
- Windows 中文用户名环境优先使用 ASCII Gradle cache：`C:\tmp\gradle-home-ascii`。
- 独立后端需要从本机环境继承 `HUITAI_DESKTOP_KEYSTORE_PASSWORD`，不得提交真实值。
- 不得恢复 `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY` 生产绕过。
- 不得把 Token、密码、API Key 写进 Run Configuration、properties、SQLite、日志或 Git。
- 不需要删除残留数据库或锁文件；正确停止路径应释放锁并清理 development session。
- Skiko DirectX12 失败后 fallback 且窗口正常渲染，是已知图形后端降级警告，不等于启动失败。

## 11. 可直接复制给下一个 Agent 的提示词

```text
继续完成 Goal：
“在 BaBiQ 的 business-desktop 中完成翔鸟律智 OA 登录功能迁移剩余任务：实现登录页及资源、登录门禁与独立前后端运行配置，完成自动化/烟测/安全验收与中文提交”。

工作目录使用 C:\tmp\BaBiQ-oa-login，分支 codex/lawyer-oa-login。
先完整阅读 AGENTS.md，以及：
- docs/superpowers/plans/2026-07-22-business-desktop-oa-login-handoff.md
- docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md
- docs/superpowers/specs/2026-07-22-business-desktop-oa-login-design.md

不要重新做已完成的 Task 1～8。首先按交接第 6 节用 TDD 修复真实 OA code=0 与桌面网关只接受 200 的契约不一致，然后重跑受影响测试和桌面端全量测试，重新进行独立代码审查，更新最终验收文档并使用中文 commit。没有真实 OA 账号，不得声称正确密码登录、刷新、恢复、退出或登录后 Agent 已人工验收通过。全部收口后才可完成 Goal。
```
