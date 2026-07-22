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
4. 账号不存在时显示“账号不存在”；租户入驻状态为 1 或 2 时显示当前桌面版本暂不支持
   入驻流程，不继续登录。
5. 密码使用 `MD5(MD5(rawPassword + "huitaisystem"))`，桌面端只在请求前于内存中计算。
6. `POST /system/auth/login`，JSON 参数包含 `mobileOrEmail`、哈希密码、`platformId` 和
   查询得到的 `tenantId`。
7. 使用响应中的 `accessToken`、`refreshToken`、`userId`，并带
   `Authorization: Bearer <accessToken>` 与 `tenant-id` 请求
   `GET /system/auth/get-permission-info?platformId={platformId}`。
8. 权限接口成功后才提交本地认证状态并进入业务桌面。

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
```

加载优先级从高到低：

1. `HUITAI_DESKTOP_CONFIG_FILE` 指向的显式文件，供 IDEA 和测试使用。
2. `<HUITAI_DESKTOP_HOME 或 user.home>/.huitai-agent-desktop/desktop/config/business-desktop.properties`。
3. 安装包内的只读默认配置。

首次启动在用户配置不存在时写入一份可编辑的非敏感默认配置。显式配置文件不存在、URL
包含 user-info/query/fragment、前缀格式不合法或超时越界时 fail-closed，并只显示稳定的配置
错误。开发仓库提供 `business-desktop/config/business-desktop-development.properties`，IDEA
前端运行配置通过 `HUITAI_DESKTOP_CONFIG_FILE` 引用它；安装包继续使用用户目录配置。

## 5. 组件边界

### 5.1 `BusinessOaConfigurationLoader`

只负责定位、初始化、读取和校验非敏感 properties。它不创建 HTTP 客户端、不读取 Token，
可通过临时目录和资源流独立测试。

### 5.2 `OaAuthenticationGateway`

位于 `huitai-integration-core`，封装租户查询、登录、权限信息和退出请求。输入输出使用明确的
Kotlin DTO；网络层使用 Ktor，公共响应由单一解码器处理。网关不持久化状态、不操作 Compose。

### 5.3 `BusinessLoginController`

位于 `app`，拥有 `BusinessLoginState`，负责表单校验、滑动验证状态、请求串行化、错误映射、
记住密码以及把网关结果提交给 `AuthSessionManager`。控制器只暴露状态流和意图方法，Compose
不直接调用 HTTP。

### 5.4 `BusinessLoginCredentialStore`

复用桌面 JCEKS，只在用户勾选“记住密码”时保存账号和原始密码；API 只返回可擦除的临时
字符数组，使用后立即覆盖。Token 继续由现有 `JceksAuthCredentialPersistence` 保存。配置文件、
SQLite、日志和 Agent 消息中都不出现原始密码或 Token。

### 5.5 `BusinessLoginScreen`

只负责 Compose 渲染与输入事件。图片从应用资源加载，语义标签覆盖账号、密码、协议、滑动
验证、提交和错误区域，方便无障碍访问和 Compose UI 测试。

### 5.6 生产装配

`BusinessDesktopCompositionRoot` 创建配置、Ktor 登录网关、`AuthSessionManager` 和登录控制器，
并把当前 child 的 `desktopInstanceId/desktopSessionId` 交给控制器生成 `BusinessIdentity`。
`Main` 根据认证状态只渲染一个顶层页面：未认证/失效时渲染登录页，认证成功时渲染现有
`BusinessDesktopShell`。

## 6. 身份、Agent 与 WebSocket 数据流

Agent WebSocket 与 OA HTTP 是两条独立连接。启动时允许本机 Agent WebSocket 建立，但只发布
`authenticated=false` 的未登录身份；此时不渲染业务壳、不允许发消息或执行 application action。

登录成功后的原子顺序为：

1. 登录网关取得 Token 和权限快照。
2. `AuthSessionManager.login` 先安全替换 Token，再发布认证身份。
3. 控制器基于 child 会话身份创建 `BusinessIdentity`。
4. `BusinessDesktopCoordinator.onAuthenticated` 在现有 WebSocket 上依次绑定身份、注册能力目录、
   发布初始页面上下文。
5. 全部成功后 UI 才切换为业务桌面；中间失败则清理本次认证并保留登录页。

模型、Agent 后端和 WebSocket 消息永远不接收 OA Token。以后 Agent 或 MCP 发起创建、修改、
提交等业务动作时，只发送受控 action/tool 参数；桌面可信执行层通过 `HuitaiHttpClient` 自动
注入当前 `Authorization` 和 `tenant-id`。退出、身份过期或切换租户会提升身份代次、清空上下文
和待执行动作，使旧请求不能复用新身份。

## 7. 启动恢复与退出

- 启动时先从 JCEKS 读取 Token；存在 Token 时使用 refresh/权限接口验证，只有验证成功才恢复
  业务桌面，不从“文件存在”推断已登录。
- Token 不存在、刷新失败、401/499、会员失效或权限加载失败时清理认证凭据并显示登录页。
- 用户主动退出时尽力调用 OA logout；无论远端调用是否成功，本地都必须清除 Token、记住密码
  以外的认证状态、Agent 身份、页面上下文和待执行动作。
- 开发用 `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY=1` 仅保留给自动化 fixture，不再出现在正常
  IDEA 前端运行配置中。
- 安装包 smoke 使用显式 smoke 信号绕过真实网络登录，只验证窗口、品牌资源、登录门禁与本机
  Agent 未登录绑定，不能伪造生产认证状态。

## 8. 错误与并发约束

- 同一时刻最多一个登录请求；重复点击和 Enter 不会产生并发请求。
- 账号不存在、密码错误、网络不可达、超时、配置无效、响应协议错误和权限加载失败使用不同
  稳定错误码，UI 显示中文可操作提示。
- 协程取消必须向上传播，不转换成登录失败。
- Token 提交成功但 Agent 身份注册失败时执行补偿登出，避免 UI 与 Agent 身份不一致。
- 日志只记录稳定错误码和阶段，不记录账号全值、请求/响应正文、路径、密码或 Token。

## 9. 测试与验收

实施严格采用测试先行，至少覆盖：

- 配置优先级、首次写入、URL/前缀/超时校验和敏感键拒绝。
- 公共响应、租户、登录 Token、权限 DTO 解码，以及 HTTP 请求方法、路径、请求体和认证头。
- 密码双重 MD5 与输入校验。
- 登录控制器成功、账号不存在、租户入驻中、协议未勾选、滑动验证取消、重复提交、网络错误、
  权限失败补偿和记住密码清理。
- 登录前只显示登录页且 Agent action/composer 不可达；登录成功后显示业务壳；退出/过期返回登录页。
- Token、密码不出现在 DTO `toString`、异常、日志、SQLite、WebSocket payload 和 Agent 上下文中。
- 宽屏/窄屏 Compose UI、品牌图片解码、键盘提交、语义标签和错误提示。
- 现有 `business-desktop` 模块测试、真实编译、打包 smoke 合同不回退。

人工验收使用配置文件指向可用 OA 环境，验证错误密码、正确密码、重启恢复、主动退出和登录后
小律助手可用；没有可用账号时只完成自动化与可达性烟测，不声称真实登录已通过。

## 10. 完成标准

- 正常启动首先看到与 Web 端一致的翔鸟律智登录页。
- 未登录时看不到业务表单，不能打开或调用小律智能助手。
- 修改外部 properties 后无需重新编译即可切换 OA 地址。
- 有效账号登录后进入现有业务桌面，Agent 继承当前用户、租户、角色和权限，但无法读取 Token。
- 退出或认证失效后立即回到登录页，旧 Agent 请求无法执行。
- 定向测试、`business-desktop` 全量测试及相关 smoke 合同均有新鲜通过证据。

