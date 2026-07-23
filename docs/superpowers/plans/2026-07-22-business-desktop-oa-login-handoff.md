# 翔鸟律智桌面端 OA 登录迁移交接

> 更新时间：2026-07-23 13:45（Asia/Shanghai）  
> 当前状态：Task 1～8 已完成并提交；Task 9 已完成大部分验证，但发现 Run Configuration 缓存稳定性问题，尚未修复、尚未最终收口。  
> 本文用于下一个 Agent 直接续做。不要把本文当前状态误判为 Goal 已完成。

## 1. 工作位置与当前分支

- 主仓库：`E:\huitai-work\BaBiQ`
- 当前隔离 worktree：`C:\tmp\BaBiQ-oa-login`
- 当前分支：`codex/lawyer-oa-login`
- 交接前功能代码 HEAD：`af1d4c2 fix(登录): 用真实组合信号验证业务壳隐藏`（本文提交会位于其后）
- 当前工作区：干净，无未提交文件。
- 源 OA 项目只读参考：`E:\huitai-work\huitai-law-oa`
- 当前 Goal 仍为 active，Task 9 尚未完成，不要调用 `update_goal(complete)`。

用户要求：

- 不再询问 Maven、Gradle 等命令执行权限；当前环境为 full access / never approval。
- 修改完成后使用中文 commit。
- 前端与后端必须能在 IDEA 中分别启动，后端日志必须独立可见。

## 2. 已完成范围

### Task 1～6：配置、认证协议、安全存储、认证状态机与 Agent 门禁

已完成并经过多轮安全审查，主要结果包括：

- OA base URL、API prefix、platform、超时和协议链接均从外部 properties 加载。
- 实现 OA 账号候选查询、密码登录、刷新、权限加载、候选退出协议。
- Token、session metadata、记住密码分别使用 JCEKS 独立别名，不写 SQLite 或日志。
- `BusinessAuthenticationOrchestrator` 是登录、恢复、刷新、退出、过期撤权的唯一生产权威。
- 只有 `READY` 身份可以进行业务 HTTP、Agent RPC、composer 与 application action。
- 已补齐并发刷新、身份代次、通知隔离、动作登记线性化、退出撤权等安全语义。

最近的 Task 6 安全提交：

- `03a7c81 fix(登录): 刷新终态由共享任务撤权`
- 此前 93 个登录/安全定向测试全部通过，两路独立审查结论为 READY。

### Task 7：OA 登录页面迁移

已提交：

- `0916c10 feat(登录): 迁移翔鸟律智登录页面`
- `d46ea64 fix(登录): 脱敏租户选择语义标识`

结果：

- 原 OA `login_bg.png` 和翔鸟律智 Logo 已按 SHA-256 精确复制。
- 宽屏显示约 68% 左侧插画，窄屏隐藏插画。
- 只保留账号、密码、记住密码、协议、滑块、登录按钮、错误信息和多租户选择。
- 没有迁移短信登录、注册、忘记密码、微信或律所入驻假入口。
- 外链只允许经过校验的服务协议/隐私政策 HTTPS URL。
- 租户 testTag 不暴露原始 tenantId；短 ID 完全掩码。
- Task 7 最终定向测试 10 tests / 0 failure，独立审查 APPROVED。

### Task 8：Main 登录门禁、退出登录、失败闭锁与独立运行配置

已提交：

- `a6e720e feat(登录): 登录成功后开放业务桌面`
- `af1d4c2 fix(登录): 用真实组合信号验证业务壳隐藏`

结果：

- `BusinessDesktopShell` 仅在 `BusinessAccessGateState.READY` 分支组合。
- 未登录、恢复中、退出中不会组合业务表单、业务导航、助手或确认弹窗。
- `CONFIG_UNAVAILABLE`、`CONFIG_INVALID`、`LOCAL_KEYSTORE_UNAVAILABLE` 显示 fail-closed 故障窗口。
- 生产代码已移除 `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY` 绕过。
- 设置页已有“退出登录”，先清除密码输入，再走 orchestrator 退出。
- 已提交 `.run/Business Backend.run.xml` 和 `.run/Business Frontend.run.xml`，任务本身前后端分离。
- packaged smoke 不再由登录分支自行设置 hidden=true，而是通过真实 `BusinessDesktopShell.onShellComposed` 信号，以 `loginGateComposed && !shellComposed` 推导未登录业务壳隐藏。
- 两路独立复审结论：READY / APPROVED。
- smoke 定向 12 tests / 0 failure。

## 3. 本次新鲜验证证据

### 桌面端全量

在没有其他 Gradle 进程时执行：

```powershell
$env:GRADLE_USER_HOME='C:\tmp\gradle-home-ascii'
$env:JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=C:\tmp\gradle-temp'
$env:JAVA_HOME='C:\Users\王校长\scoop\apps\openjdk21\current'
.\gradlew.bat clean test --rerun-tasks --no-daemon --max-workers=1 --no-parallel --no-build-cache "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" --console=plain
```

结果：

- `BUILD SUCCESSFUL in 3m 38s`
- 49 actionable tasks，49 executed
- 124 suites / 1057 tests
- failures 0 / errors 0 / skipped 0

模块计数：

| 模块 | suites | tests |
|---|---:|---:|
| agent-client-core | 15 | 151 |
| app | 64 | 409 |
| application-action-core | 11 | 154 |
| framework-demo | 5 | 27 |
| huitai-integration-core | 13 | 157 |
| presentation-core | 3 | 68 |
| security-audit-core | 13 | 91 |

### 后端全量

执行：

```powershell
$env:JAVA_HOME='D:\Program Files\jdk21'
.\mvnw.cmd clean verify
```

结果：

- exit code 0，耗时 3m 6s
- 581 个生产 Java 源文件、221 个测试源文件重新编译
- Surefire：200 suites / 1087 tests / 0 failure / 0 error / 3 skipped
- Failsafe：20 suites / 65 tests / 0 failure / 0 error / 0 skipped
- 合计：220 suites / 1152 tests / 0 failure / 0 error / 3 skipped

注意：部分 Surefire XML 的 Windows 中文用户名属性存在编码损坏，PowerShell `[xml]` 解析会失败；统计时应从 `<testsuite ...>` 首标签用正则读取 `tests/failures/errors/skipped/time`，不要误判测试失败。

### 真实前后端分离烟测

隔离运行目录：

- 成功烟测：`C:\tmp\babiq-oa-login-smoke-safe-20260723-133914`
- 默认参数失败复现：`C:\tmp\babiq-oa-login-smoke-20260723-133646`

成功烟测使用的安全参数：

```text
--no-daemon --max-workers=1 --no-parallel --no-build-cache
-Pkotlin.incremental=false
-Pkotlin.compiler.execution.strategy=in-process
```

后端结果：

- Spring Boot 监听 `127.0.0.1:49391`。
- 23 个后端 migration 全部应用。
- 真实 WebSocket 鉴权探针成功连接并正常关闭。
- 控制台打印 `Business Backend ready at ws://127.0.0.1:49391/ws/agent`。
- 后端输出独立保存在 `orchestration/backend-gradle.stdout.log`，应用日志位于隔离 runtime 下的 agent logs。

前端结果：

- 使用 `HUITAI_DESKTOP_EXTERNAL_BACKEND=1` 独立启动，只连接已运行后端。
- 真实 Compose 窗口标题为“翔鸟律智桌面端”。
- 视觉检查确认：左侧为 OA 原登录插画和翔鸟律智 Logo；右侧只有账号、密码、记住密码、协议、滑块和登录按钮。
- 未登录时没有业务导航、业务表单或“小律智能助手”。
- 协议/滑块未完成时登录按钮禁用。
- Skiko DirectX12 失败后自动 fallback，窗口仍正常渲染；这是已知图形后端降级警告，不是启动失败。

OA 开发端点结果：

- 配置：`http://192.168.1.20:48080/law-api`
- `GET /system/auth/get-users-by-mobile?mobile=__babiq_smoke_20260723__`
- HTTP 200，响应 `data: []`，证明端点可达且错误账号进入 account-not-found 路径。
- 没有真实 OA 账号，因此未完成正确密码登录、Token 刷新、重启恢复、主动退出和登录后 Agent 可用的人工验证。
- 未绕过或自动破解滑块。

烟测结束后已停止临时进程；`127.0.0.1:49391` 当前不再监听。

## 4. 当前唯一已知阻塞：Run Configuration 编译稳定性

### 复现

按当前已提交 `.run/Business Backend.run.xml` 的默认参数启动：

```text
--no-daemon --max-workers=1
```

在刚完成全量 clean test 后，仍复现：

```text
:app:compileKotlin FAILED
Unresolved reference 'BusinessProviderDraft'
Unresolved reference 'BusinessAttachmentDraft'
```

源码与全量测试本身正常。原因是默认 Run Configuration 仍允许 Kotlin 增量缓存、build cache 和外部 Kotlin daemon；共享构建目录曾有并发/中断后，它会读取不完整缓存。这与用户此前反复遇到“启动没效果、需要手工删残留”的问题一致。

同一代码、同一任务加上以下参数后成功 ready：

```text
--no-daemon --max-workers=1 --no-parallel --no-build-cache
-Pkotlin.incremental=false
-Pkotlin.compiler.execution.strategy=in-process
```

### 下一个 Agent 必须先做的 TDD 修复

1. 在 `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagingScriptContractTest.kt` 先增加 RED 断言：
   - `Business Backend.run.xml` 和 `Business Frontend.run.xml` 都包含 `--no-parallel`、`--no-build-cache`、`-Pkotlin.incremental=false`、`-Pkotlin.compiler.execution.strategy=in-process`。
   - 两个配置仍只启动各自任务，不能合并启动。
2. 单独运行 `PackagingScriptContractTest`，确认因当前 XML 缺少参数而 RED。
3. 修改两个 `.run/*.run.xml` 的 `scriptParameters`，加入上述安全参数；不要让任务依赖 `clean`，不要要求用户手工删除 build 或 runtime 文件。
4. 重跑合同测试确认 GREEN。
5. 重新分别启动 Backend 和 Frontend，确认默认 IDEA 配置本身不再需要手工追加参数。
6. 建议中文提交：`fix(运行): 加固前后端独立启动参数`。

## 5. Task 9 剩余工作

1. 完成上述 Run Configuration TDD 修复并真实重启验证。
2. 对成功烟测目录执行金丝雀扫描。烟测金丝雀为 `OA_LOGIN_SECURITY_CANARY_20260723`，用于临时 desktop keystore password：
   - 日志、SQLite、WebSocket payload、Agent item/context 不得出现该明文。
   - 可以用 `rg -a -n --fixed-strings` 扫隔离目录；若扫描 keystore 二进制，应确认不会把加密/格式字节误判为明文泄漏。
3. 执行源代码敏感键扫描、`git diff --check`、`git status --short`。
4. 把最终结果追加到实现计划 `docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md`，并把本文更新为最终 handoff。
5. 做一次最终独立代码审查，修复后重跑受影响测试。
6. 最终中文提交建议：`test(登录): 完成 OA 登录验收记录`。
7. 只有全部收口后，才把 Task 9 标为 completed，并调用 `update_goal(status=complete)`。

## 6. 重要操作注意事项

- 不要同时启动多个会写同一 `business-desktop/**/build` 的 Gradle。此前两路审查与全量测试重叠，曾造成测试编译时本模块生产类暂时不可见；等所有 Gradle 退出后再跑全量。
- Windows/中文用户路径下优先使用 ASCII Gradle cache：`C:\tmp\gradle-home-ascii`。
- 启动独立后端需要环境变量 `HUITAI_DESKTOP_KEYSTORE_PASSWORD`；只能从本机环境继承，不得提交真实密码。
- 前端独立配置必须保留：
  - `HUITAI_DESKTOP_EXTERNAL_BACKEND=1`
  - `HUITAI_DESKTOP_CONFIG_FILE=$PROJECT_DIR$/business-desktop/config/business-desktop-development.properties`
- 不得恢复 `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY` 生产绕过。
- 不得把 Token、密码、API Key 写入 Run Configuration、properties、SQLite、日志或 Git。
- 真实成功登录人工烟测需要用户提供/操作有效 OA 账号；没有账号时应明确记录“未完成真实成功登录”，不能伪造通过。

## 7. 关键文件

- 实现计划：`docs/superpowers/plans/2026-07-22-business-desktop-oa-login.md`
- 设计规格：`docs/superpowers/specs/2026-07-22-business-desktop-oa-login-design.md`
- 登录门禁入口：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- 生产装配：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- 登录 UI：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/login/BusinessLoginScreen.kt`
- packaged smoke：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeWindowComposition.kt`
- 后端独立 runner：`business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessBackendDevelopmentRunner.kt`
- IDEA 后端配置：`.run/Business Backend.run.xml`
- IDEA 前端配置：`.run/Business Frontend.run.xml`
- 运行配置合同测试：`business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagingScriptContractTest.kt`
