# 翔鸟律智桌面端 OA 登录迁移设计

## 1. 目标与范围

本阶段把 `E:\huitai-work\huitai-law-oa` 的密码登录主链路迁移到纯 Kotlin Compose 的
`business-desktop`，并把“登录成功”设为业务桌面和小律智能助手的统一准入条件。

本阶段交付：

- 复用 Web 端 `login_bg.png` 与品牌 Logo，迁移左右分栏登录页面。
- 支持手机号或邮箱 + 密码登录、密码显隐、记住密码、服务协议/隐私政策确认、滑动验证、
  加载态和明确错误提示。
- 接通真实租户查询、密码登录、权限信息查询和退出登录接口。
- OA 基础地址、API 前缀、平台编号、超时和协议地址全部通过 properties 配置。
- Token、租户与权限进入桌面端既有认证生命周期；登录前业务页面和 Agent 均不可使用。
- 登录成功后复用当前 WebSocket 连接发布身份、能力目录和页面上下文，不把 Token 发送给 Agent。
- 支持安全的登录凭据持久化、启动恢复、Token 刷新失败回到登录页。

实现按四个连续、可独立验收的增量完成：登录 UI/配置与 pre-auth 网关；本地认证及恢复；
Agent/WebSocket 门禁与撤权；打包 smoke 合同迁移。四个增量共同构成一个登录功能，不在本阶段
夹带短信、注册或真实业务创建接口。

本阶段不迁移短信登录、免费注册、忘记密码、微信扫码登录和律所入驻。这些入口不显示为可用
按钮，避免形成虚假功能。后续分别按独立规格迁移。

## 2. 已核对的 Web 行为与接口契约

源页面为：

- `huitai-law-oa/src/views/Login/Login.vue`
- `huitai-law-oa/src/views/Login/components/LoginForm.vue`
- `huitai-law-oa/src/api/login/index.ts`
- `huitai-law-oa/src/config/axios/config.ts`

密码登录顺序保持一致：

1. 校验手机号/邮箱、8～16 位数字与字母组合密码、协议勾选状态。
2. 完成本地滑动验证，阻止误触和自动重复提交。
3. `GET /system/auth/get-users-by-mobile?mobile={mobileOrEmail}` 查询账号对应租户。
4. 账号不存在时显示“账号不存在”。只有一个可用候选时自动选择；存在多个可用候选时必须由
   用户在租户选择弹窗中明确选择，取消选择不发送登录请求。`tenantEnterStatus` 为 1 或 2 的候选
   以禁用状态展示“入驻流程暂不支持”，不能被选择；没有可用候选时不继续登录。
5. 密码使用 `MD5(MD5(rawPassword + "huitaisystem"))`，桌面端只在请求前于内存中计算。
6. `POST /system/auth/login`，JSON 参数包含 `mobileOrEmail`、哈希密码、`platformId` 和
   查询得到的 `tenantId`。
7. 使用响应中的 `accessToken`、`refreshToken`、`userId`，并带
   `Authorization: Bearer <accessToken>` 与 `tenant-id` 请求
   `GET /system/auth/get-permission-info?platformId={platformId}`。
8. 权限接口成功后才提交本地认证状态并进入业务桌面。

用于启动恢复的接口为：

- `POST /system/auth/refresh-token?refreshToken={refreshToken}`，携带已保存的 `tenant-id`，不携带
  过期 access token；成功响应与登录 Token DTO 相同。
- 刷新得到新 Token 后重新调用权限接口，并校验刷新响应 `userId`、候选 `tenantId`、配置
  `platformId` 与权限响应 `user.id` 一致，之后才能替换持久 Token。

退出接口为 `POST /system/auth/logout`，使用当前 access token 与 `tenant-id`。远端退出是尽力
执行，失败或超时不能阻止本地撤权。

租户候选 DTO 明确读取 `userId`、`tenantId`、`platformId`、可选 `tenantName`、
`tenantEnterStatus`。候选 `platformId` 必须与配置一致；响应缺字段、重复 tenant/user 组合或
身份不一致按协议错误处理，不采用 Web 端“静默取第一条”的行为。

所有 OA 接口使用 `{ "code": 200, "msg": "...", "data": ... }` 公共响应格式。HTTP 非
2xx、业务 `code != 200`、空数据、JSON 不合法、超时和网络不可达都映射为稳定的桌面错误，
远端响应正文、Token 和密码不得写入日志。

## 3. 页面与交互

登录窗口保持桌面端当前全屏/最大化能力：

- 宽屏时左侧为约 68% 品牌插画区，背景使用源项目 `login_bg.png` 的 cover/center 效果；
  左上显示 Logo 与“翔鸟律智”，底部显示备案文案。
- 右侧约 32%，最小宽度 462dp，表单垂直居中；标题为“欢迎登录”，副标题为
  “翔鸟律智-法律智能平台”。
- 窄屏时隐藏左侧插画，表单在整窗居中，保证字段、错误与按钮不被裁剪。
- 首批只显示“密码登录”，不展示不可用的短信、微信、注册、忘记密码和律所入驻入口。
- 登录按钮仅在表单完整、协议已勾选且当前不在提交时可用。按 Enter 与点击按钮行为一致。
- 滑动验证成功后立即提交；关闭或失败不会发送登录请求。
- 多租户弹窗显示租户名称；名称缺失时只显示脱敏后的租户标识。入驻中的候选显示原因但不可选。
- “记住密码”默认开启。关闭后只保留非敏感界面偏好并删除已保存账号密码。
- 登录失败留在登录页并保留账号；密码错误不在错误信息中回显输入内容。

## 4. 配置设计

新增不可包含任何密钥的 `BusinessOaConfiguration`，配置项为：

```properties
huitai.oa.base-url=https://cloud.huitaikeji.cn
huitai.oa.api-prefix=/law-api
huitai.oa.platform-id=2
huitai.oa.request-timeout-ms=30000
huitai.oa.service-agreement-url=https://huitaikeji.cn/agreement.html
huitai.oa.privacy-policy-url=https://huitaikeji.cn/privacy.html
huitai.oa.allow-insecure-http=false
```

加载优先级从高到低：

1. `HUITAI_DESKTOP_CONFIG_FILE` 指向的显式文件，供 IDEA 和测试使用。
2. `<HUITAI_DESKTOP_HOME 或 user.home>/.huitai-agent-desktop/desktop/config/business-desktop.properties`。
3. 安装包内的只读默认配置。

三层配置采用“整文件选择”，不逐键合并：选中的文件必须包含全部必填项。空白环境变量视为未
设置；显式路径必须是绝对普通文件且不能是符号链接/reparse point。首次启动在用户配置不存在
时以原子写入方式复制一份可编辑的非敏感默认配置；复制失败进入 `CONFIG_UNAVAILABLE`，不启动
登录请求。

URL 包含 user-info/query/fragment、前缀格式不合法或超时不在 1～120 秒时 fail-closed。生产
默认只允许 HTTPS，Ktor 禁止自动重定向；开发态 HTTP 必须同时满足
`huitai.oa.allow-insecure-http=true` 且目标为 loopback 或私网地址。开发仓库提供
`business-desktop/config/business-desktop-development.properties`，IDEA 前端运行配置通过
`HUITAI_DESKTOP_CONFIG_FILE` 引用它；安装包继续使用用户目录配置。配置修改在下次启动生效，
“无需重新编译”不表示运行时热加载。

## 5. 组件边界

### 5.1 `BusinessOaConfigurationLoader`

只负责定位、初始化、读取和校验非敏感 properties。它不创建 HTTP 客户端、不读取 Token，
可通过临时目录和资源流独立测试。

### 5.2 `OaPreAuthenticationGateway`、`OaCandidateAuthenticationGateway` 与
`OaAuthenticatedGateway`

位于 `huitai-integration-core`。pre-auth 客户端只允许租户查询、密码登录和启动恢复 refresh，
使用显式参数提供候选 tenant/refresh token，不能读取 `AuthSessionManager`。candidate-auth 客户端
只对认证编排器可见，只允许使用内存候选 access token + tenant 请求权限和执行本次候选会话的
远端 logout；它不会把候选写入 session manager，也不能被业务 action/MCP 取得。

authenticated 客户端复用现有 `HuitaiHttpClient`，只允许 READY 会话的退出和后续业务 API，自动
注入当前 access token 与 `tenant-id`。其外层 `ReadyAuthenticatedHttpGate` 要求 gate=`READY`、
registry 中 authSessionId/identityEpoch 与请求捕获 scope 一致；`SIGNING_OUT`、恢复、注册或旧代次
一律在发送前拒绝。refresh、候选权限验证和退出补偿只走编排器专用 pre-auth/candidate 通道，不受
普通业务 READY 门禁影响。

输入输出使用明确 Kotlin DTO，公共响应由单一解码器处理。三类客户端都禁用重定向，网关不
持久化状态、不操作 Compose。

### 5.3 `BusinessLoginController`

位于 `app`，拥有 `BusinessLoginState`，负责表单校验、滑动验证状态、请求串行化、错误映射、
记住密码以及把完整登录候选交给认证编排器。控制器只暴露状态流和意图方法，Compose 不直接
调用 HTTP，也不直接修改 `AuthSessionManager`、桌面 store 或 WebSocket 身份。

### 5.4 `BusinessLoginCredentialStore`

复用桌面 JCEKS 和现有环境变量 `HUITAI_DESKTOP_KEYSTORE_PASSWORD`，使用独立别名
`huitai.login.remembered.v1`，只在用户勾选“记住密码”时保存账号和密码。JCEKS 已提供进程锁、
临时文件、原子替换和 owner-only 权限。仅当 KeyStore 可正常打开、但 remembered entry 的版本/
内容解码失败时，才删除该别名并显示 `REMEMBERED_LOGIN_INVALID`，不删除其他别名。

若共享 JCEKS 本身无法加载或完整性校验失败，不能承诺别名级恢复：应用以
`LOCAL_KEYSTORE_UNAVAILABLE` fail-closed，不发送登录/业务请求，也不自动覆盖、删除或重建文件；
保留原文件供人工备份恢复，Provider/API Key、Token 和 remembered login 全部视为暂不可用。
Token 继续由 `JceksAuthCredentialPersistence` 保存。

密码请求和 JCEKS 编解码内部使用 `CharArray` 并尽快覆盖；Compose 输入框不可避免持有短生命周期
`String`，因此安全承诺是成功、退出或窗口销毁时清空状态且不持久化/记录，不宣称能保证 JVM
堆中的 String 立即擦除。配置文件、SQLite、日志和 Agent 消息中都不出现原始密码或 Token。

### 5.5 `BusinessLoginScreen`

只负责 Compose 渲染与输入事件。图片从应用资源加载，语义标签覆盖账号、密码、协议、滑动
验证、提交和错误区域，方便无障碍访问和 Compose UI 测试。

### 5.6 生产装配

`BusinessDesktopCompositionRoot` 创建配置、两类 Ktor 网关、`AuthSessionManager`、认证编排器和
登录控制器，并把当前 child 的 `desktopInstanceId/desktopSessionId` 交给编排器生成
`BusinessIdentity`。`Main` 只依据编排器的 `BusinessAccessGateState.READY` 渲染现有
`BusinessDesktopShell`；其他状态渲染登录页或恢复进度，不能依据
`AuthSessionManager.AUTHENTICATED` 提前进入业务壳。

### 5.7 `BusinessAuthenticationOrchestrator`

它是生产认证事务、UI 门禁、回滚和重连身份的唯一权威入口。`BusinessLoginController`、
`AuthSessionManager` 和 `BusinessDesktopCoordinator` 都是被调用组件，不可互相订阅后形成第二条
发布链。生产装配不实例化现有 `AuthIdentityPublisher`；后者继续保留给独立集成场景和测试。

编排器维护 `BusinessIdentityRegistry`，成功注册后安装当前身份，重连注册只能从该 registry 读取；
退出/过期先提升 generation 并清空 registry，使重连和迟到请求观察不到旧身份。

### 5.8 `BusinessAuthSessionMetadataStore` 与动作边界

JCEKS 独立别名 `huitai.auth.session-metadata.v1` 保存版本化的 `userId/tenantId/platformId`，用于
启动 refresh；它不包含角色权限。Token 和 metadata 分别原子写入但不能跨别名形成文件事务，
因此恢复时要求两者同时存在且一致，任一缺失/损坏都清除两者并回到登录页。

新增生产 `IdentityBoundaryActionPort` 适配器，按完整旧 `ActionIdentityScope` 取消
`RECEIVED/VALIDATING/PREVIEWED/WAITING_APPROVAL`，把 `EXECUTING` 从当前 UI 分离后仅供原 scope
对账。`ApplicationActionRequestHandler` 在 gate 非 READY 时对 action request 返回稳定
`auth_required` 协议错误，不能通过抛异常后静默吞掉请求。

## 6. 身份、Agent 与 WebSocket 数据流

Agent WebSocket 与 OA HTTP 是两条独立连接。启动时允许本机 Agent WebSocket 建立，但只发布
`authenticated=false` 的未登录身份；此时不渲染业务壳、不允许发消息或执行 application action。

顶层门禁状态机为：

```text
STARTING -> RESTORING -------------------------> REGISTERING_AGENT -> READY
                 \-> SIGNED_OUT -> VERIFYING -> AUTHENTICATING ----/
                                      \-> SELECTING_TENANT --------/
READY -> SIGNING_OUT -> SIGNED_OUT
任意非终止阶段失败 -> SIGNED_OUT + 稳定错误
```

`AuthSessionManager.AUTHENTICATED` 只表示本地 Token 与身份快照已安全安装，不等于业务可用；
只有 `BusinessAccessGateState.READY` 表示身份、能力目录、首个页面上下文都已在当前 WebSocket
连接完成注册。

登录成功后的两阶段提交顺序为：

1. pre-auth 登录/refresh 网关取得候选 Token；编排器通过 candidate-auth 网关用该内存 Token 与
   tenant 获取权限快照，普通业务代码无法访问候选通道。
2. 编排器校验 user/tenant/platform 一致性，写 session metadata，然后调用
   `AuthSessionManager.login` 安全替换 Token；门禁仍为 `AUTHENTICATING`。
3. 编排器基于 child 会话身份创建 provisional `BusinessIdentity`，门禁进入
   `REGISTERING_AGENT`。
4. `BusinessDesktopCoordinator` 在注册互斥区中依次绑定身份、注册能力目录、发布初始页面上下文；
   成功后才提交桌面 `IdentityAuthenticated/PageChanged`。
5. 编排器把同一身份安装到 `BusinessIdentityRegistry`，最后把 gate 提交为 `READY`。
6. 任一持久化、身份、目录或上下文步骤失败，都按补偿矩阵撤销已经完成的步骤并回到登录页。

重连时 `registerActiveConnection` 读取 `BusinessIdentityRegistry` 的同一不可变快照：READY 时重新
发布身份、目录和最新页面；非 READY 时只发布 signed-out。禁止继续读取 factory 启动时的
`identity=null` 临时字段。

模型、Agent 后端和 WebSocket 消息永远不接收 OA Token。以后 Agent 或 MCP 发起创建、修改、
提交等业务动作时，只发送受控 action/tool 参数；桌面可信执行层通过 `HuitaiHttpClient` 自动
注入当前 `Authorization` 和 `tenant-id`。退出、身份过期或切换租户会提升身份代次、清空上下文
和待执行动作，使旧请求不能复用新身份。

## 7. 启动恢复与退出

- 启动时同时读取 Token 与 session metadata；缺一、损坏或不一致时清除两者并进入登录页。
- 恢复流程使用 pre-auth refresh 调用：metadata 提供 tenant/platform/user，refresh token 作为
  query 参数，响应的新 access/refresh token 只作为候选保存在内存；随后携带新 access token 与
  metadata tenant 请求权限，校验身份一致后才调用与普通登录相同的两阶段提交。
- refresh 成功而权限或 Agent 注册失败时不替换为 READY；清除本地 Token/metadata，要求重新登录。
- Token 不存在、刷新失败、401/499、会员失效或权限加载失败时清理认证凭据并显示登录页。
- 用户主动退出或认证过期按确定顺序执行：gate 进入 `SIGNING_OUT`，同时使普通 authenticated
  HTTP gate 关闭并阻止新 turn/action；提升
  generation 并从 registry 移除身份；按旧完整 scope 取消未执行动作并分离执行中动作；在当前
  WebSocket 发布 signed-out；清 workspace/store；尽力调用远端 logout；最后清 Token 与 metadata，
  gate 进入 `SIGNED_OUT`。远端 logout 失败不回滚本地撤权。
- “记住密码”只由用户勾选项控制，主动退出默认保留，取消勾选或用户明确清除时删除；它从不被
  当作已认证证据。
- 开发用 `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY=1` 仅保留给自动化 fixture，不再出现在正常
  IDEA 前端运行配置中。
- 安装包 smoke 不伪造登录。现有 `shellComposed` 合同迁移为 `loginGateComposed`，并保留窗口、
  品牌图片解码、本机 Agent 连接和 signed-out identity 断言；移除“未登录也必须组合业务壳/侧栏/
  助手”的断言。新增 `businessShellHiddenWhileSignedOut=true`，smoke 只有在登录页真实组合且业务壳
  未组合时才通过。

## 8. 错误与并发约束

- 同一时刻最多一个登录请求；重复点击和 Enter 不会产生并发请求。
- 运行中业务 HTTP 遇到 401/499 时继续使用现有 `TokenRefreshCoordinator` 单飞刷新；同一
  authSessionId/identityEpoch 的并发请求等待同一个 refresh。刷新期间不启动第二次刷新；成功后
  仅按现有幂等/对账策略回放请求，失败则调用编排器执行认证过期撤权。
- 所有业务请求捕获开始时的 authSessionId/identityEpoch；响应回写前再次比较 registry，旧代次
  结果只能进入原 scope 审计，不能更新当前页面、对话或审批。
- 协程取消必须向上传播，不转换成登录失败。
- 应用关闭时取消登录/恢复 job，先执行本地撤权再关闭 HTTP、WebSocket、JCEKS 和数据库。
- 日志只记录稳定错误码和阶段，不记录账号全值、请求/响应正文、路径、密码或 Token。

稳定错误码至少包含：

| 错误码 | 触发条件 | UI 行为 |
| --- | --- | --- |
| `CONFIG_UNAVAILABLE` / `CONFIG_INVALID` | 配置复制/读取/校验失败 | 阻止登录，提示检查配置文件 |
| `ACCOUNT_NOT_FOUND` | 租户查询为空 | 保留账号，提示确认或注册 |
| `TENANT_SELECTION_CANCELLED` | 用户取消多租户选择 | 返回表单，不作为红色失败 |
| `TENANT_UNAVAILABLE` | 只有入驻中/平台不匹配候选 | 提示候选不可用 |
| `INVALID_CREDENTIALS` | OA 返回明确账号或密码错误业务码/消息 | 清空密码，保留账号 |
| `REMOTE_UNAVAILABLE` / `REMOTE_TIMEOUT` | 网络不可达/超时 | 保留表单，可重试 |
| `REMOTE_PROTOCOL_ERROR` | HTTP、公共响应或身份字段不合法 | 不回显正文，要求联系管理员 |
| `PERMISSION_LOAD_FAILED` | 权限响应失败或身份不一致 | 回滚候选认证，返回登录页 |
| `LOCAL_CREDENTIAL_STORE_FAILED` | 可用 JCEKS 的条目写入失败 | 回滚候选，不进入 Agent 注册 |
| `LOCAL_KEYSTORE_UNAVAILABLE` | 共享 JCEKS 无法打开/校验 | fail-closed，保留文件，禁止认证与业务请求 |
| `AGENT_REGISTRATION_FAILED` | identity/catalog/context 任一步失败 | 撤权并返回登录页 |
| `AUTH_EXPIRED` / `MEMBERSHIP_EXPIRED` | 401/499 或会员失效码 | 撤权并返回登录页 |
| `AGREEMENT_OPEN_FAILED` | 系统浏览器无法打开协议链接 | 留在登录页并显示提示 |

补偿矩阵：

| 最后成功阶段 | 后续失败时补偿 |
| --- | --- |
| 未持久化 | 丢弃内存候选，gate 回到 SIGNED_OUT |
| metadata 已写、Token 未写 | 清 metadata |
| Token 已写、本地身份已发布 | `AuthSessionManager.logout` + 清 metadata |
| identity 已发、catalog/context 未完成 | registry 保持空、发送 signed-out、清 workspace/store、清本地认证 |
| READY 后刷新/业务请求失效 | 提升 generation、动作撤权、发送 signed-out、清认证 |

## 9. 测试与验收

实施严格采用测试先行，至少覆盖：

- 配置整文件优先级、首次原子写入、空环境变量、绝对路径、链接拒绝、URL/前缀/超时、HTTPS/
  开发 HTTP 规则、重定向禁止和敏感键拒绝。
- 公共响应、租户、登录 Token、权限 DTO 解码，以及 HTTP 请求方法、路径、请求体和认证头。
- 密码双重 MD5 与输入校验。
- 登录控制器成功、账号不存在、单租户、多租户选择/取消、租户入驻中、平台/身份不一致、协议
  未勾选、滑动验证取消、重复提交、网络错误、权限失败补偿和记住密码损坏/清理。
- `BusinessAuthenticationOrchestrator` 的所有门禁迁移、每一行补偿矩阵、Token/metadata 缺一恢复、
  refresh Token 轮换、权限重载、应用关闭取消与远端 logout 超时。
- candidate-auth 权限请求使用未安装 Token 且不暴露给业务代码；普通 authenticated HTTP 在非 READY
  或 scope/generation 不匹配时发送前拒绝。
- remembered entry 解码损坏只删本别名；共享 JCEKS 整体损坏时保留原文件并 fail-closed。
- 登录前只显示登录页且 composer/action 明确返回 auth-required；登录成功后显示业务壳；退出/过期
  返回登录页并按完整旧 scope 撤权；重连从 registry 恢复 READY 身份而不是发布 signed-out。
- 401/499 单飞刷新、等待者共享结果、请求代次校验和迟到结果只进入旧 scope 审计。
- Token、密码不出现在 DTO `toString`、异常、日志、SQLite、WebSocket payload 和 Agent 上下文中。
- 宽屏/窄屏 Compose UI、品牌图片解码、键盘提交、语义标签和错误提示。
- 新 packaged-smoke 报告只接受登录页真实组合、signed-out Agent 绑定和业务壳隐藏；现有窗口、图标、
  后端连接、安全文件合同不回退。

人工验收使用配置文件指向可用 OA 环境，验证错误密码、正确密码、重启恢复、主动退出和登录后
小律助手可用；没有可用账号时只完成自动化与可达性烟测，不声称真实登录已通过。

### 9.1 最终安全审查补强

- Application Action 的 start、cancel、status、result-get 都必须捕获当前 READY 身份，校验请求 scope 与当前身份一致，并在同一 current permit 内访问运行时；退出或换身份后的旧代次请求不得读取结果或取消执行。
- notification 形式的 cancel 在非 READY、当前身份已变化或 scope 不匹配时静默丢弃，不产生跨身份副作用。
- OA refresh Token 不得出现在 URL query。为兼容 OA 后端的 `@RequestParam` 合约，客户端使用 `application/x-www-form-urlencoded` POST body 传递 `refreshToken`。
- 最终验收包含上述回归测试、全量自动化、安全扫描和独立代码审查。

## 10. 完成标准

- 正常启动首先看到与 Web 端一致的翔鸟律智登录页。
- 未登录时看不到业务表单，不能打开或调用小律智能助手。
- 修改外部 properties 后无需重新编译即可切换 OA 地址。
- 多租户账号必须明确选择租户，不会静默使用第一条记录。
- 有效账号登录后进入现有业务桌面，Agent 继承当前用户、租户、角色和权限，但无法读取 Token。
- 退出或认证失效后立即回到登录页，旧 Agent 请求无法执行。
- 定向测试、`business-desktop` 全量测试及相关 smoke 合同均有新鲜通过证据。
