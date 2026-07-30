# 业务桌面本地网关与工作台迁移交接文档

> 日期：2026-07-30
>
> 仓库：`E:\huitai-work\BaBiQ`
>
> 分支：`codex/lawyer-oa-desktop`
>
> 当前 HEAD：`3755065 docs(网关): 收口OA工作台迁移纪要与实施计划`
>
> 用途：给下一位 Agent 继续执行本地 Spring Boot 网关和 OA 工作台迁移；本文件不是“已验收完成”声明。

## 1. 不可改变的架构结论

本任务遵循
`docs/superpowers/specs/2026-07-27-business-desktop-local-gateway-architecture-discussion.md`
和权威设计
`docs/superpowers/specs/2026-07-27-business-desktop-local-gateway-workbench-design.md`：

- Compose 只连接本地 Spring Boot 的 finalized loopback WebSocket + JSON-RPC，不直接访问远程 OA HTTP/WebSocket。
- Spring Boot 是 OA 身份网关、业务 BFF 和协议转换层；OA 用户、租户、角色和权限仍使用翔鸟律智现有体系。
- OA Token 只由后端会话/JCEKS 持有；Compose、Agent、工具、SQLite、日志和 JSON-RPC DTO 不得看到 Token。密码只在当前登录请求使用，不能落日志、数据库、上下文或错误正文。
- 业务调用必须处于服务端 `READY`，并匹配 finalized connection、desktop session、OA session、tenant、user 和 generation；旧连接/旧租户/旧代次的迟到结果必须丢弃。
- 安装事务使用服务端生成的 `installationId`、owner、目标 generation 和 90 秒 TTL；最终 READY 发布必须是条件更新/CAS。
- 测试和 IDEA 调试都在当前仓库 `E:\huitai-work\BaBiQ` 内完成，禁止复制到 `C:\tmp`。会员续费不迁移，入口保留为明确占位。

## 2. 当前已落地的实现面

### 后端网关与认证

- 默认 OA 配置位于 `backend/src/main/resources/application.yml`，测试地址为 `http://192.168.1.20:48080`，接口前缀为 `/law-api`，`platform-id=2`，请求超时 30 秒；普通 profile 仍禁止私网 HTTP，开发 profile 才显式允许。
- 已实现 OA 登录、租户候选、session 查询/attach/restore、刷新、退出、权限安装和 READY 门禁；Compose 认证走 typed RPC client。
- 已实现 SQLite 非敏感会话索引、JCEKS secret store、启动恢复 `BusinessOaSessionRecoveryService`，并接入 `RecoveryStartupRunner`。
- 已实现 server-owned 安装租约：`installationId`、owner desktop instance/session、目标 generation、过期时间、90 秒 TTL 和 CAS 校验；登录、恢复、刷新和 READY 提交均检查 owner/generation。
- 已收紧 finalized connection 解析、PRE_AUTH/INSTALLING/READY/REVOKING access policy、固定 JSON-RPC 错误、敏感参数脱敏、未知异常固定消息和双向 262144 字节报文边界。

### 工作台 BFF 与资源链

- 已覆盖首屏快照、导航 allowlist、公告、快捷入口、统计、用户卡、团队、日程，以及四类分页、排序和数据范围校验。
- 已覆盖日程查询、表单/关联选项、创建、完成，以及 loopback 资源句柄、附件票据、上传和资源代理链路。
- 已加入基础 payload 上限：集合最多 100 项、普通文本最多 1024 字符、标识最多 256 字符、递归深度最多 8，分页总数/页码有上限。

### Compose 工作台

- 已改为只通过本地 RPC 认证和读取工作台，Compose 不再持有 OA Token 或远程 OA HTTP client。
- 已有登录页、认证状态、工作台控制器/reducer、Header、导航、公告/快捷入口/统计/列表/用户卡/日程等界面；其他菜单保持占位，设置和助手入口保留。
- 已有分页、排序、数据范围、日程乐观完成/创建和附件批次的 typed 协议模型与 UI 测试覆盖。

## 3. 当前验证证据

以下自动化证据均在当前仓库、固定 Java 21、Maven/Gradle 各自串行条件下重新执行；
IDEA 证据为 2026-07-30 的实际分离启动记录。它们都不等于真实 OA 人工验收。

| 范围 | 命令/证据 | 结果 |
| --- | --- | --- |
| 后端 fresh 全量 | `.\mvnw.cmd -o "-Dmaven.repo.local=C:\Users\王校长\.m2\repository" clean verify`；business profile 使用仅限本次测试进程的非默认 KeyStore password | 原始 Maven exit 0；Surefire 241 suites / 1463 tests / 0 failure / 0 error / 3 skipped；Failsafe 36 suites / 161 tests / 0 failure / 0 error |
| 认证竞态回归 | `BusinessOaReconnectRaceTest,BusinessOaAuthenticationServiceTerminalizationTest,BusinessOaRestoreFailureTest` | 60 tests / 0 failure / 0 error |
| fake OA + 真 Spring/WS | `BusinessOaAuthenticatedWebSocketIT`、`BusinessOaReconnectIT`、`BusinessWorkbenchEndToEndIT`、附件 loopback IT | 登录、READY、401 singleflight、工作台、重连、退出、上传与 outcome unknown 均通过 |
| 敏感 canary | `BusinessOaSecretLeakAuditTest`、`BusinessAttachmentUploadIT`、桌面 runtime scanner | RPC、日志、SQLite、context/items/tools、异常、temp/report 中 OA secret 0 matches |
| 桌面 fresh 全量 | E 盘缓存、offline、`--continue --rerun-tasks --max-workers=1 --no-parallel --no-build-cache` | 六模块 133 suites / 1024 tests；1022 通过、2 failure、0 error。唯一失败是用户测试读取同时必须保留 staged deletion 的两个 `.run` 文件 |
| fresh 安装包 | `:app:smokePackagedDistribution` 重新生成 app image/MSI/EXE；随后直接复跑提取包脚本 | 真实 Compose signed-out/login gate 通过；提取包 runtime/classpath + bundled Spring Boot + loopback fake OA 完成密码编码、登录、READY、六区工作台、导航 allowlist 和助手 controller；KeyStore/OA canary 0 明文命中 |
| 工作区与源码审计 | `git diff --check`、`git diff --cached --check`、Compose main 敏感标识扫描 | 两个 diff check 均 exit 0；命中仅为本地 desktop Bearer、禁止字段黑名单和无关 operation token |

最终 canonical 分发任务在认证竞态与烟测质量审查修复后再次 fresh 执行，
31 个 task 全部重新执行，并于 2026-07-29 22:45 exit 0。
MSI 为 235,487,951 bytes，SHA-256
`972092EAB1733058A89CC9D193570DDCBB3A96A22985BC54713037F1D95BF3CF`；
EXE 为 236,080,640 bytes，SHA-256
`101524221D3F1F1B2C241BA0F031F76C9349A9981F7A73DA30A0A7255CD23795`。

最终桌面 full test 还把真实 backend restart IT 的偶发取消收敛为确定性认证竞态：
迟到的 startup restore lifecycle observer 不得覆盖已经开始的交互式 tenant lookup。
新增 RED 后以 `beginRestoreAttempt()` 拒绝覆盖 active user attempt，随后
`BusinessRpcAuthenticationOperationsTest` 与 `BusinessProviderSettingsRestartIT`
合并 35 tests 全绿；最终 app 496 tests 中除两项受保护 `.run` 冲突外均通过。

安装包认证烟测的独立质量审查先发现并复现 3 个 Important 与 1 个 Minor：
`ERROR` 分区误算成功、OA base URL 环境未恢复、canary 命中后可能跳过 temp 删除、
fake OA 未精确校验双 MD5/未知路由未 fail-closed；随后又补查了 exited root 的
orphan backend 清理。各项均先补 RED 合同，再收口为仅 `OK/EMPTY`、环境快照恢复、
扫描与受限删除分离、`MD5(MD5(password + huitaisystem))` 精确校验、未知路由拒绝，
以及保留 exited root PID 后按所有权枚举/终止 descendants。相关 focused 10 tests
全绿，最终独立质量复审为 Critical 0 / Important 0 / Minor 0。

后端首次 fresh 清理还暴露了旧 `target/business-oa-beans-*` 的跨 SID Windows ACL。
为保留现场，整个旧 `backend/target` 被原子移动到
`tmp/backend-stale-target-acl-20260729`，未删除；`BusinessOaBeanWiringIT` 已改用
JUnit `@TempDir(cleanup = ALWAYS)`，后续 fresh `clean verify` 正常通过。

2026-07-30 IDEA 分离启动烟测已实际完成：在当前仓库通过两个独立 Run 标签启动
`Business Backend` 与 `Business Frontend`，日志互不混用。后端就绪地址为
`ws://127.0.0.1:49391/ws/agent`；桌面窗口标题为“翔鸟律智桌面端”，未登录时显示完整
登录门禁且不暴露工作台。烟测结束后前后端 PID `34976`、`576`、`7128` 均已退出，49391
端口已关闭；`.tmp-business-desktop-idea-runtime` 保留为现场证据。

## 4. 当前明确阻塞项

1. 真实 OA 账号人工烟测尚未执行：错误/正确密码、Web 与 Desktop 同时在线、
   refresh、重启 restore、断网恢复、工作台分页/范围/排序、日程创建/完成、附件和
   logout 不退出 Web 都仍需真实环境证据。2026-07-30 已检查
   `HUITAI_OA_USERNAME`、`HUITAI_OA_PASSWORD`、`HUITAI_OA_BASE_URL`、
   `HUITAI_OA_TENANT_ID`、`HUITAI_OA_CAPTCHA_VERIFICATION`、`HUITAI_OA_CLIENT_ID`
   与 `HUITAI_OA_CLIENT_SECRET`，均不存在；不得伪造或绕过认证。
2. 桌面 fresh 全量不能宣称 GREEN：用户要求保留的
   `PackagingScriptContractTest.kt` 硬读取同时要求保留 staged deletion 的
   `.run/Business Backend.run.xml` 与 `.run/Business Frontend.run.xml`，形成两项
   `FileNotFoundException`。不得恢复 `.run`，也不得擅改或暂存该用户测试。
3. 旧 target ACL 隔离目录 `tmp/backend-stale-target-acl-20260729` 当前不可由本进程读取；
   它是保留现场，不应暂存、删除或当作任务产物提交。

## 5. 下一步执行顺序

1. 在真实 OA 环境人工验证：错误/正确密码、Web 与 Desktop 同时登录、refresh、重启恢复、断网重连、工作台 section/分页/范围/排序、日程创建/完成、附件、显式退出且不退出 Web。账号或环境不可用时必须明确未验收，不能伪造通过。
2. 由用户决定如何消解 `.run` staged deletion 与其 `PackagingScriptContractTest.kt`
   的合同冲突；在未获新指示前同时保留两者，不制造虚假全量 GREEN。
3. 本次实现、自动化、安装包烟测、IDEA 未登录分离启动证据、文档和提交边界审计已可收口；提交不代表真实 OA 人工验收通过。

## 6. 工作区与提交保护

- 当前工作区已有大量任务代码、用户修改和临时目录；不要使用 `git reset --hard`、`git checkout --` 或按目录 `git add`。
- 保留 `.run/Business Backend.run.xml`、`.run/Business Frontend.run.xml` 的用户 staged deletion；不要恢复。
- 保留用户修改的 `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagingScriptContractTest.kt`，不要将其混入本任务提交。
- 保留所有 `.tmp-*` 目录，不暂存、不删除；它们是当前 IDEA/Gradle 现场。
- 最终只对审阅过的明确文件使用 `git commit --only`；不 push，除非用户另行授权。
- 2026-07-29 22:58 提交边界审计得到 386 个任务候选文件：159 个 tracked
  修改/删除和 227 个 untracked 源码、测试、migration、资源、脚本与本文档。明确排除
  用户 `PackagingScriptContractTest.kt`、两份 `.run` staged deletion、全部
  `.tmp-*`/`tmp/`，以及无关的 untracked
  `docs/superpowers/specs/2026-07-27-business-desktop-cross-page-workflow-skill-design.md`。
  当前未暂存任何任务文件；index 仍只有两份受保护 `.run` 删除。

## 7. 交接结论

本分支已经完成“Compose -> 本地 Spring Boot -> OA”的实现面、fake OA 真 Spring/WS
跨层 E2E、敏感 canary、后端 fresh 全量、安装包烟测和 IDEA 前后端分离的未登录启动烟测；
Task10/14/15/17 的规格与质量复审已完成，最终认证/安装包增量质量复审为
Critical 0 / Important 0 / Minor 0。

仍不能把“真实 OA 人工验收”写成通过：桌面 full test 有两项受保护现场冲突；当前没有可用的真实
OA 凭据，正确密码、同时登录、refresh、重启恢复、断网、真实数据/写操作与附件尚未人工验收。
提交后仍不得 push；该事实必须在后续交接中保留。
