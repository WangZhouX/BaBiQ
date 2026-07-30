# 业务桌面本地网关与律师工作台迁移设计

> 日期：2026-07-27
> 状态：已根据用户确认的本地网关讨论纪要形成正式设计，实施尚未开始。
> 适用范围：`business-desktop` Compose 客户端、本地 Spring Boot Agent Server、桌面协议以及律师 OA 工作台。

## 1. 文档优先级与结论

本设计把《业务桌面本地网关架构讨论纪要》转化为可实施方案。用户已经明确确认按该纪要继续，
因此本设计在进程边界、Token 所有权和业务调用路径上优先于
`2026-07-22-business-desktop-oa-login-design.md`。

同日生成的 `2026-07-27-business-desktop-local-gateway-design.md` 是早期补充草案，其中客户端
registration、`business/auth/status` 和旧 Token 导入等内容已被本设计明确替代，不作为实施依据。
任何仍有价值的组件边界、数据范围或验收材料应先合并进本文件，实施与最终验收只引用本权威设计。

旧登录设计中以下内容仍然有效：登录页视觉与字段、滑动验证、多租户选择、错误文案、品牌资源、
READY 前隐藏业务壳、身份代次、退出撤权和安全验收要求。以下内容被本设计替代：

- OA 登录、refresh、logout、权限查询不再位于 Kotlin `huitai-integration-core`。
- Compose 不再保存、读取或自动注入 OA Token。
- Compose 不再直接调用 OA HTTP/WebSocket 地址。
- 本地 Spring Boot 成为 OA 身份网关、业务 BFF、协议网关和远程连接唯一所有者。
- 工作台只消费桌面稳定协议，不理解 OA 公共响应或远程接口路径。

最终通信边界为：

```text
Kotlin Compose
    |
    | loopback WebSocket + JSON-RPC
    | 二进制例外：受认证 loopback HTTP stream
    v
本地 Spring Boot
    |
    | OA HTTPS API
    | OA WebSocket（仅在真实代码确认存在后）
    v
远程翔鸟律智 OA
```

## 2. 目标与非目标

### 2.1 目标

1. Web 和桌面继续使用同一套 OA 用户、租户、角色、权限和数据范围。
2. Web 与桌面取得彼此独立的 Token；退出桌面不退出 Web。
3. 本地 Spring Boot 独占 OA Token、refresh、租户上下文和远程请求。
4. 登录或恢复只有在可信身份、能力目录和初始页面上下文全部安装后才能进入 READY。
5. 完整迁移律师 Web 首页的公告、快捷入口、四类统计及分页、用户卡和日程面板。
6. 完整迁移当前工作台可达交互：排序、范围/团队/角色、分页、日历、完成回滚和新增日程。
7. 其他业务菜单、工作台外详情和外围功能保留入口，但内容只显示明确占位。
8. 保留业务桌面右侧小律助手，并让其继续服从当前 OA 身份和 READY 门禁。
9. 自动化和人工烟测覆盖认证、恢复、刷新、退出、工作台、断网和敏感信息不外泄。

### 2.2 非目标

- 不新增桌面用户表、注册体系或独立账号密码。
- 不迁移全部 Vue 动态路由和其他 OA 业务页面。
- 不新增当前 Web 工作台不可达的日程详情、编辑或删除。
- 不迁移会员支付、消息中心、帮助中心、公告详情、案件详情等外围业务。
- 不因猜测而实现 OA WebSocket；当前已核对的工作台链路只有 HTTP。
- 不让 Agent、MCP、工具参数或页面上下文看到 OA Token、密码或远程响应正文。
- 不长期保留“登录由 Compose 直连、业务由 Spring Boot 代理”的混合架构。

## 3. 已核对的当前实现差距

当前代码尚未实现本设计：

- `KtorOaAuthenticationGateway` 在 Kotlin 进程内直接请求租户、登录、refresh、权限和 logout。
- `BusinessDesktopCompositionRoot` 在 Compose 进程创建 `AuthSessionManager`、Token refresh 和
  `HuitaiHttpClient`。
- `ReadyAuthenticatedHuitaiClient` 允许 READY 后由 Compose 直接请求远程 OA。
- 后端 `BusinessJsonRpcAccessPolicy` 只有 pre-bind/post-bind 两档；post-bind 仅检查
  `ApplicationIdentityRegistry` 和活动连接，没有服务端 OA READY 状态。
- `ApplicationIdentityProtocolHandler` 接受 Compose 声明的 user/tenant/roles/permissions；在目标
  架构中这不能继续作为可信 OA 身份来源。
- 后端没有 OA session、Token、远程适配器或工作台 BFF。
- 当前工作区已有尚未提交的 JSON-RPC 泄密加固切片；正式实施前仍需独立复测、审查并补齐双向
  262144-byte envelope 边界后再提交。

因此不能先在 Kotlin 新增工作台 Gateway 再补后端；必须先完成服务端认证会话底座。

## 4. 方案比较与选择

### 4.1 选择：本地 Spring Boot 统一网关

Compose 只负责表单、状态和渲染。本地 Spring Boot 负责：

- OA 配置校验、密码摘要、租户候选、登录、权限、refresh 和 logout。
- Token 安全持久化、服务端会话状态机、身份代次和恢复。
- 可信 `ApplicationIdentityRegistry` 安装以及能力目录/页面上下文事务。
- 工作台远程调用、聚合、DTO 转换、数据范围校验和稳定错误转换。
- 二进制资源代理与日程附件流式上传。

该方案与讨论纪要一致，Token 边界单一，退出/刷新/租户切换不会跨进程同步两份状态。

### 4.2 不选：Compose 继续直连 OA

该方案虽然复用现有 Kotlin 代码最快，但会继续把 Token、远程地址和协议细节放在 UI 进程，直接
违反已确认的目标架构。

### 4.3 不选：登录直连、业务走后端

该方案需要两个进程同步 Token、refresh、logout、租户和身份代次，故障面比任一单边方案都大，
只能作为同一提交序列中的短暂不可发布中间态，不能作为可交付检查点。

## 5. 本地连接认证与 OA 认证

### 5.1 两层认证

本地进程连接认证继续使用现有一次性 desktop session Token、实例 ID、会话 ID、Origin 和 loopback
校验。它只证明连接者是本次启动的合法 Compose 进程，不代表 OA 用户已登录。

OA 认证由本地 Spring Boot 执行。Compose 在受本地身份保护的 WebSocket 上发送账号密码；后端
计算 OA 所需摘要、访问远程 HTTPS，并只返回安全身份视图。密码字段所在的整个
`business/auth/*` 请求日志必须固定脱敏。

### 5.2 服务端状态机

服务端新增唯一权威状态：

```text
SIGNED_OUT
   -> AUTHENTICATING -> INSTALLING -> READY
   -> RESTORING      -> INSTALLING -> READY
READY -> REVOKING -> SIGNED_OUT
任一候选或安装失败 -> 撤销候选/提升 generation -> SIGNED_OUT
```

- `AUTHENTICATING/RESTORING`：只有候选远程会话，不允许业务请求。
- `INSTALLING`：Token、可信身份、目录和上下文处于事务安装阶段，仍不允许业务请求。
- `READY`：精确连接、desktop session、OA session 和 generation 全部匹配后才允许业务能力。
- `REVOKING`：先关门并提升 generation，再清远程连接、上下文、Token 和持久化状态。

状态以服务端为权威；Compose 保留现有 `BusinessAccessGateState` 作为 UI 投影，不再自行决定 OA
是否可用。

### 5.3 登录和恢复由服务端原子提交 READY

纪要第 4 节已经锁定：Spring Boot 完成 Token、可信身份、能力目录和初始页面上下文安装后才进入
`READY`。因此生产协议不提供 `business/auth/ready`，Compose 也不参与可信 registration。

`business/auth/login` 与 `business/auth/restore` 在服务端依次执行：

1. 校验活动的可信本地连接。
2. 查询租户/登录或读取 backend JCEKS 中的 native refresh credential。
3. 请求权限信息并校验 userId、tenantId、platformId 一致。
4. 保存 staged credential，并保持 session=`INSTALLING`、业务门禁关闭。
5. 后端根据 OA 结果生成可信 identity、稳定导航/能力目录和净化的初始页面上下文。
6. 通过 server-only API 原子安装 identity → catalog → context，并持久化非敏感索引。
7. CAS 提交 active credentialRef 与 generation，最后一个动作才发布 `READY`。
8. 只向 Compose 返回安全 `BusinessSessionView`；客户端把它当作服务端状态投影。

任一步失败都回滚 provisional identity/catalog/context、删除 staged credential、提升 generation 并保持
fail-closed。`application/identity/bind|update`、`application/catalog/register|update` 和
`application/context/publish` 在 production business profile 中全部关闭；若为旧测试保留 handler，
只能由显式 legacy/test property 开启，不能构成生产提权旁路。

每次 login/attach/restore 都由服务端生成不可预测的 `installationId`，并在 SQLite 中记录
`ownerDesktopInstanceId`、`ownerDesktopSessionId`、开始/过期时间和目标 generation。安装事务只允许
该 owner 在一个活动事务内推进；identity、catalog、context 的临时安装和 credential staged ref 都必须
带同一个 `installationId`，最后使用条件更新（`installationId + owner + expectedGeneration + phase=INSTALLING`）
一次性提交 `READY`。安装 TTL 默认 90 秒，超时、进程崩溃或 owner 断开都由启动恢复收束为
`SIGNED_OUT`/`DETACHED`，删除 staged secret 和 provisional projection；迟到结果命中 CAS 失败后只能丢弃，
不能再次发布 READY。启动恢复还要扫描没有对应活动安装记录的 orphan staged secret 并安全删除，删除失败时
保持 fail-closed 并记录固定安全错误码。

### 5.4 重连

OA session 绑定稳定的 `desktopInstanceId` 和当前 lease 形式的 `desktopSessionId`，而不是某一个瞬时
WebSocket 对象。`desktopInstanceId` 在同一桌面安装生命周期内持久保存；每次 Agent child 进程启动时生成
新的 `desktopSessionId`，它只代表当前 WebSocket lease。WebSocket 断开后，durable session 进入 `DETACHED`，
旧 live lease 失效，但 credential 和非敏感索引仍可恢复。

- `business/auth/session/get` 只读返回当前连接投影：`SIGNED_OUT/DETACHED/INSTALLING/READY/...`、
  generation、是否存在可附着 session 和安全身份摘要；它不改变状态，也不访问远程 OA。
- 新 WebSocket 先由握手中的本地连接凭据绑定 `desktopInstanceId` 和新生成的 `desktopSessionId`；
  Compose 不得在 RPC params 中任意提交这两个值。`session/get` 不接受身份覆盖字段；如果存在可附着的
  `DETACHED` durable session，服务端只在当前 finalized connection 上返回一次性、默认 60 秒有效的 opaque
  `attachHandle`。该 handle 内部绑定 reservation、`wsId`、owner instance、当前 lease、目标 durable session
  id、当前 generation 和过期时间，不含 token、secretRef 或 OA id，不能复制到另一个 WebSocket。
  `session/attach` 只接受该 handle；服务端再次核验 finalized connection、owner instance、未过期状态和
  generation，成功后以 CAS 原子替换 live lease 并进入 `RESTORING -> INSTALLING`。同一 handle 在同一活动
  lease 上重试只返回当前安全投影，不重新执行远程登录；其他 lease、重复消费、过期 handle、generation
  不匹配或 CAS 竞争统一返回 `BUSINESS_SESSION_NOT_ATTACHABLE` / `BUSINESS_SESSION_STALE`，不能跨 desktop
  instance 抢占。
- `business/auth/restore` 用于应用启动时从 backend native credential 恢复 durable session；它不是普通
  WebSocket 重连的别名，也不读取或导入旧 Kotlin Token。

attach/restore 的远程 I/O 都在锁外执行，返回后以 reservation + generation CAS 提交；被 logout、
第二次 attach 或更新 generation 抢先时，迟到结果丢弃且不得重新发布 READY。

WebSocket 断开只把当前连接从 OA session 上 detach，并让所有桌面业务调用 fail-closed；它不等于
用户主动退出，不能立即调用远程 OA logout、删除 refresh Token 或破坏可恢复会话。只有显式 logout、
认证终态失效或服务端恢复判定不可继续时，才进入完整撤权链。

旧 WebSocket、旧 reservation、旧 generation 的响应和通知一律丢弃。

## 6. Token、记住密码与持久化

### 6.1 后端唯一 Secret Store

复用 backend business profile 已有 JCEKS 和 `BABIQ_SECRETS_KEYSTORE_PASSWORD`，不新增明文密钥
文件。business profile 在打开或创建 KeyStore 前必须验证该环境变量存在、非空且不是仓库默认值；
不满足时启动 fail-closed，不能偷偷创建使用固定默认密码的 KeyStore。新增 OA credential repository：

- access/refresh Token 与身份元数据编码为一个版本化 secret envelope。
- 新 Token 先保存为 staged secret，SQLite 事务再切换 active `secretRef` 和 generation。
- 提交成功后删除旧 secret；中途失败保留旧 READY 或进入 fail-closed recovery，不能半安装。
- logout 先持久化 `REVOKING/REVOKED` 并撤销 READY，再删除 secret；删除失败不能恢复旧 READY。

实施时使用仓库下一个未占用 migration 编号（不得硬编码假设 V25 仍空闲）：

- `bq_business_oa_sessions`：desktop instance/session、user/tenant/platform、phase、generation、
  active_credential_ref、staged_credential_ref、credential_version、install_started_at、
  installed_at、detached_at、revoked_at 和 updated_at。
- `bq_business_auth_events`：仅记录事件类型、稳定结果码、identity generation 和时间，不记录账号、
  密码、Token、远程正文。

所有新增表和字段按仓库规则提供 SQL 中文注释、`bq_schema_comments` 和覆盖测试。

### 6.2 只记住账号，不记住密码

纪要规定密码只用于当前登录调用，因此新架构不把密码写入 Compose 偏好、JCEKS 或 SQLite。
登录页原“记住密码”交互收口为“记住账号”：只保存非敏感账号文本（可在日志中掩码），
`business/auth/session/get` 最多返回 `rememberedAccount`，不提供
`hasRememberedCredential/useRememberedCredential` 或任何密码恢复能力。

### 6.3 旧 Kotlin 凭据迁移

不把旧 Token 通过 WebSocket 传给后端，也不把 desktop JCEKS 主密码长期交给后端。升级到新架构
后执行一次有明确原因的重新登录；不跨 KeyStore 导入旧 access/refresh Token：

1. 确认后端新认证协议可用。
2. 在 desktop JCEKS 上执行一个受测试约束的原子定点清理事务，只删除
   `huitai.auth.tokens.v1`、`huitai.auth.session-metadata.v1`、
   `huitai.login.remembered.v1`，不删除其他条目或整个 KeyStore。
3. 后端无有效新 session 时显示登录页。
4. 用户重新登录后，Token 只写 backend JCEKS；账号按非敏感 UI 偏好保存，密码仍不落盘。

清理必须以临时 KeyStore 文件写入、重新打开校验三个 alias 均不存在、再原子替换原文件；任何失败都
保留原文件、记录固定安全错误并 fail-closed，不能出现只删一部分或同时启用两套凭据。后端启动恢复
只处理自己已经创建的 native staged/orphan session，不读取旧 Kotlin alias。

## 7. OA 配置与远程适配器

### 7.1 配置归属

现有外置配置键保持兼容：

```properties
huitai.oa.base-url=http://192.168.1.20:48080
huitai.oa.api-prefix=/law-api
huitai.oa.platform-id=2
huitai.oa.request-timeout-ms=30000
huitai.oa.allow-insecure-http=true
```

校验和加载迁移到 Spring Boot `@ConfigurationProperties`。Compose 只负责把外部配置文件位置传给
child process，不读取或解析远程地址。内置 Agent 启动参数必须显式追加
`--spring.config.additional-location=file:<desktopConfiguration>`，其中 `<desktopConfiguration>` 是
当前受控 runtime 路径的规范化绝对路径；该参数不能由用户输入或当前工作目录推导。IDEA 独立启动后端时
使用同一文件设置 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:$PROJECT_DIR$/business-desktop/config/business-desktop-development.properties`
（Windows IDEA 宏在启动前展开），或等价的 `--spring.config.additional-location=file:<absolute-path>`。
Run Configuration 必须同时固定 `HUITAI_DESKTOP_HOME`/runtime 路径和该配置位置；不依赖工作目录或 C 盘复制。
启动合同测试必须断言 child command 和独立 IDEA 配置都确实传递该参数，并断言缺失/不可读文件时后端
fail-closed。

HTTPS、私网开发 HTTP、禁止重定向、URL user-info/query/fragment、超时范围等旧规则全部保留并在
后端测试。

### 7.2 远程请求约束

后端 OA client 统一：

- 发送 `X-Platform-Type: pc`。
- READY 请求自动注入当前 `Authorization: Bearer ...` 与 `tenant-id`。
- 所有业务请求从服务端 session 捕获 generation，响应返回后再次校验。
- OA 公共响应只接受真实合同成功码 `code=0`；HTTP 200 只是传输状态，不是业务成功码，
  `code=200` 和其他数字/字符串码均视为失败。数据结构不合法转稳定协议错误。
- refresh Token 使用 form body，不进入 URL。
- GET 可在明确 401/499 且单飞 refresh 成功后重放一次。
- 非幂等新增日程、重复日程完成、附件上传遇到发送后断链时不盲目重放，返回
  `OUTCOME_UNKNOWN` 并要求重新查询/人工确认。
- 远程 response body、URL query、Token 和底层 exception message 不进入日志或桌面错误。

session/refresh 锁只保护 phase、generation 和 secretRef 切换。实现必须在锁内取得不可变 session
lease，在锁外执行 OA I/O，响应后再以 CAS/generation 校验提交结果；禁止持有 connection/session
锁等待远程网络，避免 logout、断连和 refresh 被长请求阻塞。

当前工作台未发现真实 OA WebSocket；本阶段不创建远程 WebSocket。以后确认存在时，由后端建立并
转成桌面 notification。

## 8. 桌面 JSON-RPC 协议

### 8.1 认证方法

| 方法 | 可用阶段 | 用途 |
| --- | --- | --- |
| `business/auth/session/get` | 任意可信本地连接 | 获取安全状态、remembered account 和是否可恢复/附着 |
| `business/auth/session/attach` | 当前连接存在同 desktop session 的 `DETACHED` durable session | 重新校验并由服务端原子恢复 READY |
| `business/auth/tenant-candidates` | `SIGNED_OUT` | 按账号查询可选租户，不接收密码 |
| `business/auth/login` | `SIGNED_OUT` | 密码登录并由服务端完成原子 READY 安装 |
| `business/auth/restore` | 应用启动且存在 backend native credential | 启动恢复并由服务端完成原子 READY 安装 |
| `business/auth/logout` | `READY/REVOKING` | 先本地撤权，再尽力远程 logout |
| `business/auth/state-changed` | server notification | 过期、会员失效、退出和恢复结果通知 |

`business/auth/tenant-candidates` 返回绑定当前 desktop session、账号查询代次且短 TTL 的不透明
`candidateId`；`business/auth/login` 只接受该 candidateId，不接受 Compose 自由提交的 tenantId、userId
或 platformId。候选过期、重复使用或与当前账号/连接不匹配时 fail-closed。

认证结果只返回：状态、identity epoch、用户摘要、租户摘要、角色/权限摘要、稳定菜单、
`rememberedAccount` 和安全错误码。禁止返回 accessToken、refreshToken、secretRef、KeyStore 路径/密码
和远程正文。

首屏日期参数使用后端本地配置的唯一 `zoneId`（不得由客户端覆盖）：`month` 为 `YYYY-MM`、`day` 为
`YYYY-MM-DD`；缺省时按该 zone 的当前日期生成，`day` 必须属于 `month`，非法或空字符串固定返回
`BUSINESS_VALIDATION_FAILED`。跨午夜请求以服务端收到请求时的 zone 重新计算，不接受客户端时间戳代替日期。

### 8.2 工作台方法

| 方法 | 用途 |
| --- | --- |
| `business/workbench/get` | 聚合公告、快捷入口、统计、用户卡、团队及选定月/日的日程摘要 |
| `business/workbench/navigation/get` | 从当前可信权限/菜单投影读取 allowlisted 一级导航与占位目标 |
| `business/workbench/home-info/get` | 会员状态点击刷新用户卡 |
| `business/workbench/page/get` | 获取案件、预约、顾问服务或未来拜访分页 |
| `business/workbench/team-roles/list` | 按业务 kind 和 teamId 获取当前用户可选数据角色 |
| `business/workbench/sort/update` | 更新快捷入口或统计卡排序 |
| `business/schedule/month/get` | 获取月份事件点 |
| `business/schedule/day/get` | 获取当日日程分组 |
| `business/schedule/completion/set` | 完成或取消完成，返回是否需刷新 |
| `business/schedule/form/get` | 获取日程类型、团队权限和可指派成员 |
| `business/schedule/relation-options/get` | 按关系类型加载客户、案件、拜访或服务记录 |
| `business/schedule/service-projects/get` | 获取服务记录项目 |
| `business/schedule/create` | 创建日程并消费服务端附件 batch handle |
| `business/attachments/upload/prepare` | 为一批已校验文件生成短时、单次 loopback 上传票据 |

所有方法要求服务端 session=`READY` 且当前 WebSocket、desktop session 和 generation 精确匹配。
协议不暴露任意 OA path、base URL、tenantId 覆盖、relatedIds 或原始响应 JSON。

JSON-RPC 的双向边界统一为 `262144` UTF-8 bytes：WebSocket 容器 buffer 必须配置为至少 `262145`
bytes（仅让超限一字节帧进入应用层，以便返回稳定协议错误），后端再校验所有入站 request/notification 以及出站
response/notification，Kotlin 校验所有出站 request/response 以及入站 response/notification。恰好
`262144` bytes 可接受，`262145` bytes 必须在解析/分发前拒绝；超限使用既有 `-32041 PROTOCOL_ERROR`，
不能被业务错误码复用。首屏 snapshot、分页和表单选项必须设置服务端数量/文本长度上限，附件字节
绝不进入 JSON-RPC。登录/恢复调用若在桌面侧
超时，后端可能已经成功提交 READY；Compose 必须先调用 `business/auth/session/get` 对账，确认仍为
`SIGNED_OUT` 后才允许再次发起登录，不能因本地超时立即重复创建远程会话。

### 8.3 稳定错误码

在 JSON-RPC server-specific 范围新增可由 Kotlin 数字 code 直接识别的错误：

| JSON-RPC code | businessCode |
| ---: | --- |
| `-32010` | `BUSINESS_AUTH_REQUIRED` |
| `-32011` | `BUSINESS_ACCOUNT_NOT_FOUND` |
| `-32012` | `BUSINESS_TENANT_UNAVAILABLE` |
| `-32013` | `BUSINESS_INVALID_CREDENTIALS` |
| `-32014` | `BUSINESS_AUTH_EXPIRED` |
| `-32015` | `BUSINESS_MEMBERSHIP_EXPIRED` |
| `-32016` | `BUSINESS_SESSION_STALE` |
| `-32017` | `BUSINESS_LOCAL_SECRET_STORE_FAILED` |
| `-32018` | `BUSINESS_CONFIG_INVALID` |
| `-32019` | `BUSINESS_SESSION_NOT_ATTACHABLE` |
| `-32020` | `BUSINESS_PERMISSION_DENIED` |
| `-32021` | `BUSINESS_SCOPE_INVALID` |
| `-32022` | `BUSINESS_SCOPE_CHANGED` |
| `-32030` | `BUSINESS_VALIDATION_FAILED` |
| `-32031` | `BUSINESS_CONFLICT` |
| `-32032` | `BUSINESS_OUTCOME_UNKNOWN` |
| `-32040` | `BUSINESS_REMOTE_UNAVAILABLE` |
| `-32041` | `PROTOCOL_ERROR`（既有协议码，保留） |
| `-32042` | `BUSINESS_REMOTE_TIMEOUT` |
| `-32043` | `BUSINESS_REMOTE_PROTOCOL_ERROR` |
| `-32050` | `BUSINESS_UPLOAD_REJECTED` |
| `-32051` | `BUSINESS_RESOURCE_UNAVAILABLE` |

参数结构错误继续使用 `INVALID_PARAMS`。`JsonRpcDispatcher` 的未预期异常统一返回固定
`Internal server error`，不返回 `exception.getMessage()`。

`error.data` 仅允许 `businessCode`、`retryable`、`fieldErrors`、`section`、
`currentSessionState` 和本地 `correlationId`；不得包含 OA code/msg/traceId、HTTP body、URL 或异常。
Kotlin 新增 `BusinessRpcException` 按数字 code 和安全 data 解码，不能复用当前会丢弃普通
`error.data` 的 `AgentJsonRpcException`。

## 9. 工作台 BFF 契约

### 9.1 首屏聚合

`business/workbench/get` 在后端并发调用，单个 OA 请求建议 10--15 秒超时，聚合总预算控制在
20--25 秒以内（外层 JSON-RPC 默认 30 秒）：

- `GET /system/notice-push/page?pageNo=1&pageSize=10&type=3&displayStatus=1`
- `GET /lawyer/home-config/list-shortcut`
- `GET /lawyer/home-config/summary`
- `GET /system/user/home-info`
- `GET /system/team/list?status=1&type=5`
- `GET /lawyer/law-schedule/list-count`
- `GET /lawyer/law-schedule/list-day`

四类分页单独通过 `business/workbench/page/get` 加载，首屏依据 summary 中后端排序后的第一个
enabled 项选择 kind，避免在大聚合里串行等待分页。

OA 后端 `LawNoticePageReqVO` 继承 `PageParam`，真实字段是 `pageNo/pageSize`。当前实际挂载的律师
工作台 `Layout.vue` 通过 `getWorkbenchNoticeList` 调用同一路由，但仍发送旧字段 `page=1`；该字段被
Spring 忽略后依赖 `PageParam.pageNo` 默认值 1，才碰巧取得第一页。桌面 BFF 必须使用真实
`pageNo=1` 合同，不能复制这个前端偏差。仓库中未挂载的旧 `noticeView.vue` 另有
`POST /system/notice/list` 调用，不属于本次工作台迁移入口。

首屏各 section 独立返回 `OK/EMPTY/ERROR` 和稳定 errorCode；公告失败不应让用户卡和日程一起
失败。认证/会员失效属于全局致命错误，必须撤销 READY，不作为普通 section error 吞掉。

### 9.2 快捷入口和统计

- 快捷入口只展示 `enabled != false`，每页 10 个并循环翻页。
- 统计只展示 enabled 项，首个 enabled 项决定下方列表。
- 排序协议使用 `SHORTCUT/SUMMARY` 枚举，后端映射 OA `configType=1/2`，客户端不能传任意数字。
- 排序失败时桌面回滚或重拉 canonical 顺序并显示错误，不保留仅本地成功假象。
- 快捷入口返回安全 `DesktopNavigationTarget`；内部业务路由进入占位页，不执行任意远程 URL。

### 9.3 四类分页

`business/workbench/page/get` 使用严格 typed request：

- `kind` 仅允许 `CASE/APPOINTMENT/COUNSELOR_SERVICE/VISIT`。
- 每个 kind 的筛选字段只接受对应枚举，未知枚举或未知 JSON 字段直接 `INVALID_PARAMS`。
- `ALL`、`PERSONAL` 必须拒绝 `teamId/roleCode`；`TEAM` 必须提供当前身份已授权的 `teamId`。
- 公开 DTO 不声明也不容忍 `moduleId`、`relatedIds`、`dataRoleInfos`、`dataRoleCodes`、`userId` 或
  `tenantId`；反序列化启用 unknown-field rejection，远程调用次数必须保持 0。

kind 与远程接口固定映射：

| kind | moduleId | OA 接口 | 专属筛选 |
| --- | ---: | --- | --- |
| `CASE` | 1007 | `/lawyer/home-config/summary/case-handling-page` | `status=1/2` |
| `APPOINTMENT` | 1006 | `/lawyer/home-config/summary/appointment-page` | `consultMode=0/1/2` |
| `COUNSELOR_SERVICE` | 1003 | `/counselor/home-config/summary/counselor-service-page` | `serviceStatus=0/1` |
| `VISIT` | 1004 | `/counselor/home-config/summary/visiting-page` | `visitObj=1/2` |

公共范围使用桌面枚举：

- `ALL`：远程省略 `dataType`，保留 OA 的个人加已授权团队语义。
- `PERSONAL`：远程 `dataType=0`。
- `TEAM`：远程 `dataType=1`，必须同时存在有效 `teamId`。

服务端必须拒绝 `TEAM + teamId=null`，因为现有 OA `TeamDataPermissionAspect` 会跳过过滤并可能退化为
租户范围。moduleId 由 kind 派生；`relatedIds` 永不接受客户端输入；role codes 必须是当前团队和
module 返回集合的子集。

案件 DTO 包含共享案件卡真实读取的别名字段：`applicationNumber`、`categoriesName`、
`lawFirmRelationStatus`、`logo`、`tenant`、`teamDatas` 等。拜访 DTO 明确包含后端实际填充的
`scheduleName`。

案件点击进入占位详情；预约、顾问服务、拜访保持当前 Web 无点击行为。

### 9.4 用户卡

安全 DTO 展示 nickname、avatar handle、会员状态、最新律所和最新团队。续费支付不迁移；临期/过期
点击先调用 `business/workbench/home-info/get` 刷新，仍异常则显示“续费功能迁移中”占位。

律所和团队“查看”保留入口，目标为桌面占位页。

### 9.5 日程

迁移当前可达行为：

- 个人/团队、团队选择、仅看自己。
- 年月选择、前后月、今日、周/月展开和日期事件点。
- 当日时间线、全天、类型颜色、优先级、重复、过期天数。
- 完成/取消完成的乐观更新；失败回滚并提示。
- 顶部加号和空态进入完整新增日程表单。

新增日程表单包含：标题、类型、团队指派成员、优先级、时间/全天、描述、多个提醒、自定义提醒、
重复规则、客户/案件/拜访/服务项目关联和附件。团队负责人/管理员可选择正常成员；普通成员只能
指派自己。后端仍是最终授权边界。

表单和关联选项同样执行 fail-closed 范围校验：团队成员接口只允许当前 READY 身份的有效 teamId；
查询服务项目之前，`serviceRecordId` 必须先出现在本次身份与个人/团队范围取得的服务记录选项中，
不能把 OA 仅按 recordId 查询的接口直接暴露给 Compose。创建请求使用桌面生成的
`clientOperationId` 映射 OA 幂等机制；客户端 tenantId、moduleId、relatedIds、dataRoleInfos 和 OA
fileIds 均不属于公开 DTO。

不新增工作台当前不可达的已有日程详情、编辑和删除。完成重复日程可能创建下一期，网络结果不确定
时不能自动重放。

## 10. 二进制资源和附件

Compose 不直接下载 OA/CDN 图片，也不直接上传 OA 文件。Spring Boot 在同一 loopback 端口新增
受认证流式通道：

```text
GET  /business/resources/{opaqueHandle}
POST /business/attachments/uploads/{batchId}
```

两类请求都要求：

- loopback 来源。
- 本地 desktop Bearer、instanceId、sessionId 正确。
- 活动 WebSocket 连接与服务端 READY generation 匹配。
- `Origin` 必须精确命中 business desktop 固定 allowlist，并通过独立 loopback HTTP CSRF filter；
  不能因为地址是 localhost 就跳过 Origin/CSRF 校验。

远程图片 URL 只从可信 OA 响应注册成随机 opaque handle；客户端不能提交任意 URL。BFF DTO 只返回
`resourceHandle`、安全 MIME 类别、像素/字节上限和过期秒数，不返回原始 URL。后端维护短期 resource registry：
handle 绑定 `desktopInstanceId`、当前 `desktopSessionId`、OA session、tenant、identity generation、
创建/过期时间和读取策略；logout、generation 变化、detach 超时和过期立即撤销。`GET /business/resources/{opaqueHandle}`
只允许 registry 命中的 handle，响应固定 `Content-Type`/`Content-Length`，禁止共享代理缓存；缺失、跨 lease、
过期或撤销统一返回无正文的 `BUSINESS_RESOURCE_UNAVAILABLE`。后端执行协议、DNS/private-address、重定向、
内容类型和大小校验，按 session/generation 做短期缓存。

附件上传先调用 `business/attachments/upload/prepare`，提交净化文件名、字节数、MIME 和可选
SHA-256；后端返回约 60 秒有效、单次使用且绑定 desktop session、OA session、identity generation、
租户、目标 operation 和父资源授权上下文的 ticket/`attachmentBatchId`。Compose 再向上述 HTTP
路径发送 ticket header 和 multipart 文件；
header 固定为 `X-Business-Upload-Ticket`，ticket 不进入 query string，路径也不接受任意 OA URL。

服务端按 Web 当前真实合同同时校验：单文件严格小于 20MB、单次最多 50 个、总大小严格小于
500MB，扩展名与 MIME 必须同时命中 png/jpg/jpeg/gif/pdf/doc/docx/mp4/avi/mov/mkv/webm 白名单，
并移除文件名路径。`prepare` 的声明只是上限和摘要；upload 必须让文件数、净化文件名集合、实际字节数、
sniff MIME 与声明逐项匹配，声明了 SHA-256 时必须与真实 hash 相等，缺少或多出文件均拒绝。20MB/500MB
均按严格小于比较（`limit-1` 可接受，`limit` 拒绝），无 `Content-Length` 的 chunked 请求仍按流式累计并
在超限时立即中止。通过后才从 owner-only、禁止跟随符号链接/reparse point 的受控 runtime 临时文件上传到
`/infra/file/upload-return-ids`；固定服务端注入 `fileStorageName=ht-law-file-management`，finally 删除临时文件，
启动恢复只清理残留，不自动重放。

ticket 与 batch 是两个独立状态机，且一个 ticket 只对应一个 batch：

```text
ticket: ISSUED -> CLAIMED -> IN_FLIGHT -> SUCCEEDED|REJECTED|OUTCOME_UNKNOWN|EXPIRED|REVOKED
batch:  READY -> CONSUMING -> CONSUMED|FAILED|OUTCOME_UNKNOWN|REVOKED
```

- 领取上传使用 CAS，收到 HTTP 后立即把 ticket 从 `ISSUED` 变为 `CLAIMED/IN_FLIGHT`；只有一个请求能继续，
  ticket 不可重复消费。
- `prepare` 只允许固定 `SCHEDULE_CREATE` operation，并绑定服务端生成的 `clientOperationId`、当前 READY
  lease/generation、tenant、actor、form/options revision 和经服务端核验的父资源 relation/parent id；客户端
  不能提交可扩权的 module、tenant、user 或任意 relatedIds。`schedule/create` 仅能消费同 operation、同 form
  revision、同 lease/generation 的 batch，并再次核验当前团队/关系授权；Agent/tool 不能复用。
- 所有声明文件均完成实际字节数、MIME/扩展名和可选 hash 校验，且 OA 返回完整 fileId 集合后 ticket 才为
  `SUCCEEDED`、batch 才能进入 `READY`；部分上传永不成为 READY。
- 远端已发送任意字节但响应不确定、HTTP 断链、进程崩溃、退出或 generation 变化时，ticket/batch 进入
  `OUTCOME_UNKNOWN`，不自动重放，也不能被 schedule/create 消费。父授权变化、TTL 到期和显式 logout 进入
  `REVOKED/EXPIRED`；所有终态都清理受控临时文件。启动恢复扫描 `IN_FLIGHT/CONSUMING` 和残留临时文件并
  收束为 `OUTCOME_UNKNOWN/REVOKED`，绝不自动上传或创建日程。
- `business/schedule/create` 对 `attachmentBatchId` 做同 operation/父授权/generation 的单次 CAS 消费；
  Compose 永远看不到 OA fileId。并发 prepare/upload/create、WS close 和 recovery 均以同一 CAS 规则拒绝重复消费。

HTTP 只接受 loopback remote address（含 IPv4-mapped IPv6 的规范化检查）、精确 `Host=127.0.0.1:<port>`
或 `localhost:<port>` 和固定 `Origin=http://127.0.0.1:<port>`/`http://localhost:<port>`；缺失、`null`、通配、
其他端口、跨域预检 `OPTIONS`、Cookie/query 凭据、代理转发头和任意 `X-Forwarded-*` 一律拒绝。凭据只允许
`Authorization: Bearer <desktop-lease-token>`，instance/session 由握手绑定而非 query；ticket 只允许
`X-Business-Upload-Ticket` header。专用 filter 先匹配 finalized connection 四元组和 READY generation，再交给
controller；`@RestControllerAdvice` 对 multipart parser/size/IO/OA 异常只返回 `{businessCode, correlationId}`，
禁止 Spring 默认 error body、access/header/request dump 和 DTO/ticket `toString()` 泄密。日志只记录 correlationId、
固定状态和计数。

## 11. Compose 架构与界面

### 11.1 客户端边界

`agent-client-core` 新增安全协议模型和客户端：

- `BusinessAuthClient`
- `BusinessWorkbenchClient`
- `BusinessScheduleClient`
- `BusinessLocalResourceClient`（只访问 loopback stream）

`BusinessLoginController` 保留表单状态职责，但改为调用 `BusinessAuthClient`。新增独立
`BusinessWorkbenchController/State`，负责 section loading、分页、筛选、请求 generation、排序、
乐观日程和新增表单。退出或 identity epoch 变化时立即清空；旧请求结果不得回写。

最终移除 Compose 对 `huitai-integration-core`、`AuthSessionManager`、
`ReadyAuthenticatedHuitaiClient`、`KtorOaAuthenticationGateway` 和远程 OA 配置值的依赖。迁移完成后
删除或收缩整个 Kotlin 直连模块及其旧测试，把仍有价值的合同测试迁到后端。

### 11.2 外壳布局

迁移 Web 登录后 OA 壳，同时保留业务桌面助手能力：

```text
64dp Header：Logo / 当前身份与律所 / 消息占位 / 帮助占位 / 用户菜单与退出
主体
├─ 88dp 左侧 OA 菜单：服务端菜单顺序，首页真实，其余占位，设置固定底部
├─ 中央灰底工作区：公告 + 工作台
│  ├─ 左栏约 76%：快捷入口 180dp + 统计和四类分页
│  └─ 右栏约 24%：用户卡 180dp + 日程
└─ 右侧小律助手：收起时零占位悬浮入口，展开时沿用现有面板
```

主要视觉基准：Header 64dp、菜单 88dp、工作区 `#F7F7F7`、gap 10dp、卡片 padding 16dp、主色
`#216DFF`、边框 `#E6E6E6`。忠实迁移保持 Web 的两栏结构和 75.1/23.7 比例；桌面设置合理的
最小窗口宽度，助手展开时采用内部滚动或压缩间距，不把右栏改成 Web 未实现的单栏业务布局。

迁移 Web 的品牌/工作台位图到 app resources 并增加存在、解码、尺寸和 hash 合同。动态头像、团队
Logo、律所 Logo 和快捷图标使用本地资源代理 handle；不让 Compose 访问远程 URL。Iconfont glyph
使用 Compose Vector/Material 图标做语义等价替换，避免复制 CSS 类名。

### 11.3 菜单与占位

权限接口的 menu tree 在后端转换为稳定 DTO，仅允许当前 Web 左栏 allowlist 的可见一级路径，未知路径、
子菜单和无权限项全部过滤；首页始终保留并固定排在第一项，设置固定在底部。allowlist 必须与当前
`LawyerLeftMenu` 的实际一级过滤合同一致：`/`、`/index`、`/index/unfinished` 归一为首页，以及
`/lawoa`、`/bpm`、`/approval`、`/case`、`/administration`、`/management`、`/customer`、`/cost`、
`/consultant`、`/lawyer-admin`、`/tools`、`/team`。后端只返回这些稳定 kind/path 与标题，实际不可达页面
仍显示占位；不得凭业务名称自行新增 `/appointment`、`/visit` 或 `/schedule` 等未在当前左栏合同中的路径。
其他菜单和以下入口只展示占位：

- 案件详情、公告列表/详情。
- 律所、团队、完整日程页。
- 消息、帮助、会员续费和账户外围页面。
- 快捷入口指向的其他业务模块。

设置页继续保持真实 Provider/沙箱/审批能力，不降级为占位。目的地切换继续发布安全页面上下文，
但不把工作台全量数据、远程正文或凭据注入 Agent。

## 12. 门禁、并发和安全

### 12.1 JSON-RPC 门禁

`BusinessJsonRpcAccessPolicy` 从两档升级为会话感知策略：

- `PRE_AUTH`：本地连接认证、`business/auth/session/get`、attach、租户、登录、启动 restore 和明确允许
  的本地设置读取。
- `INSTALLING`：只允许当前 auth 事务内部完成安装，不开放工作台、Agent turn 或 action。
- `READY`：工作台、Agent、Provider、context、application action 等方法均要求精确 READY。
- `REVOKING`：只允许状态查询和幂等 logout 收口。

不能仅凭 `ApplicationIdentityRegistry` 有记录就放行业务。服务端 OA session phase、active connection、
desktop session 和 generation 必须同时匹配。

`application/identity/bind|update`、`application/catalog/register|update`、
`application/context/publish` 在 production business profile 中始终关闭；服务端内部通过非 RPC API
维护可信 identity/catalog/context，不能留下 Compose 自报身份或目录/上下文的旁路。

### 12.2 请求代次

每个远程请求捕获 `oaSessionId + generation + tenantId + userId`。写响应、section cache、上传 batch 或
通知前重新比较。退出、过期、租户/用户变化和恢复都先提升 generation；旧响应只能被审计为 stale，
不能改变当前页面或新身份。

Compose controller 另有 UI request sequence，解决同一身份内快速切 tab/page 的迟到覆盖；服务端
generation 不能替代 UI sequence，二者都要保留。

### 12.3 撤权顺序

```text
phase=REVOKING + generation++
-> access policy 立即关门
-> 取消/隔离待处理 action、远程请求和上传 batch
-> 清 identity/catalog/context 和工作台 cache
-> 关闭未来 OA realtime connection（如有）
-> 尽力 remote logout
-> 删除 active/staged secretRef
-> phase=SIGNED_OUT
-> 通知 Compose 清 UI
```

远程 logout 失败不回滚本地撤权。

### 12.4 日志与错误

- `business/auth/*` params 整段固定脱敏；工作台 payload 只记 method、类型和耗时。
- 账号最多使用掩码，不记录全值。
- password、Token、secretRef、Authorization、tenant header、远程 body 不进日志。
- 未预期异常固定消息，不回显 Java/OA message。
- JSON 解析失败只记录本地 correlationId、报文字节数和固定原因，不记录或返回 Jackson 原始输入片段。
- JSON-RPC、SQLite、Agent context、工具参数和 smoke 报告做敏感 marker 扫描。

敏感 canary 的允许边界必须精确，不以“全局简单 0 次”制造误报：

- password canary 允许且只能在受控的 Compose → Spring Boot `business/auth/login` 请求 frame 中出现
  1 次；在 response、notification、日志、SQLite/JCEKS、context、item、tool、异常、HTTP、临时文件
  和烟测报告中均为 0 次。
- OA Token canary 在桌面/RPC/HTTP 响应、日志、SQLite、context、item、tool、异常和报告中均为 0 次；
  受控 Spring Boot → OA `Authorization` 请求边界以及 backend JCEKS 是预期 secret boundary，但测试
  仍须证明不会从这些边界外泄。
- 上传审计额外扫描 multipart 临时目录、DTO `toString()`、ticket、净化前文件名/路径、SHA、
  staged/orphan alias 和 OA 错误正文；清理后不得残留。

## 13. 分阶段实施与可发布检查点

### 阶段 A：协议与服务端会话底座

- 配置、稳定错误码、服务端 phase/generation、JCEKS+SQLite repository。
- auth handler 和 application identity 内部安装端口。
- default-deny 改为 READY 感知。
- 此阶段以 fake OA 自动化验证，不发布混合架构。

### 阶段 B：认证迁移

- 后端 OA auth/refresh/logout adapter。
- Kotlin 登录控制器改走 `BusinessAuthClient`。
- startup restore、session attach、remembered account、重连和通知。
- 清理 legacy desktop OA aliases。
- 完成后 Compose 不再持有 OA Token。

### 阶段 C：工作台 BFF

- 首屏 section 聚合、四类分页、范围/角色、排序。
- 用户卡、日程查询/完成/创建和选项。
- 二进制资源与附件 loopback stream。

### 阶段 D：Compose 工作台

- OA Header/菜单/公告/两栏工作台和占位目的地。
- 真实分页、排序、筛选、日历、乐观更新和完整新增日程表单。
- 保留设置和小律助手。

### 阶段 E：直连收口与验收

- 删除 Kotlin OA HTTP/WebSocket/Token 实现和依赖。
- 全量自动化、安全审查、打包烟测与真实 OA 人工烟测。
- 更新设计、实施计划、handoff 和中文提交记录。

## 14. 测试策略

严格测试先行。

### 14.1 后端

- Properties：配置文件位置、HTTPS/开发 HTTP、重定向、URL 和超时。
- OA adapter：请求方法、路径、query/body/header、仅 `code=0` 成功、错误转换和 refresh。
- Session：所有 phase、generation、登录/恢复安装、半失败补偿、重连、撤权和启动恢复。
- Secret：staged/active 切换、SQLite/JCEKS 任一步失败、orphan 清理和 legacy alias 收口。
- Access policy：PRE_AUTH/INSTALLING/READY/REVOKING 精确 allowlist，旧 identity bind 提权路径关闭。
- Workbench：七个首屏 section、四类分页、排序、部分失败、fatal auth、团队范围 fail-closed。
- Schedule：月/日、完成回滚语义、重复日程 outcome unknown、表单选项、成员权限和创建 payload。
- Binary：loopback/local token/READY、SSRF、重定向、大小、MIME、batch identity binding。
- JSON-RPC：稳定数字错误、unknown exception 固定消息、auth params 和远程正文不泄漏。
- Schema：新增 migration 的所有业务字段中文注释覆盖。

远程合同测试优先使用 JDK loopback `HttpServer`，不依赖真实账号。

### 14.2 Kotlin/Compose

- Auth client 序列化、稳定错误映射、重连和 state notification。
- Login controller 单请求、多租户、remembered account、取消和错误恢复。
- Workbench controller section loading、选择首个统计、分页重置、团队/角色失效、旧响应丢弃。
- 排序成功/失败 canonical 回滚。
- 日程日期、周/月、团队、onlyMine、乐观完成与失败回滚。
- 新增日程字段校验、关联选项和 attachment batch。
- Header、88dp 菜单、两栏/窄屏布局、所有占位和设置/助手保留。
- 品牌/工作台资源存在、解码、尺寸/hash 与语义标签。

### 14.3 集成与烟测

1. fake OA + 真 Spring Boot + 真 WebSocket 完成登录、权限、READY、工作台、refresh、logout。
2. 协议 traffic audit 证明 password 仅出现在 auth request 内且日志已脱敏，OA Token 从未进入桌面
   payload。
3. 安装包 signed-out smoke、fake OA authenticated smoke、业务壳和助手 smoke。
4. 真实环境人工验证：错误密码、正确密码、Web/桌面同时登录、重启恢复、工作台各 section、团队
   数据、日程新增/完成、附件、主动退出和断网重连。
5. 没有可用账号或数据时如实标记真实业务烟测未完成，不能用 fake 测试冒充。

## 15. 完成标准

- Compose 源码与运行时不再访问远程 OA HTTP/WebSocket。
- Compose 不保存、接收、输出或注入 OA Token。
- 后端统一完成 OA 登录、refresh、logout、权限和业务 HTTP。
- 服务端只有在 identity/catalog/context 全部安装后进入 READY。
- 未 READY、旧连接、旧用户、旧租户和旧 generation 无法调用工作台、Agent 或 action。
- Web 与桌面同时登录，Token 和退出互不影响。
- 登录后默认进入完整律师工作台；其他业务菜单只显示占位。
- 七个首屏 section、四类分页、排序、范围、日程和新增附件均使用真实 BFF 协议。
- `TEAM` 缺 teamId、非法 role、客户端 tenantId/relatedIds 等扩权输入被服务端拒绝。
- 密码和 OA Token 不出现在日志、SQLite、JSON-RPC、Agent、工具、异常或 smoke 报告中。
- Kotlin 直连网关和旧 Token 持久化已删除或完全失去生产可达性。
- 后端、business-desktop 定向/全量测试、安全扫描、安装包烟测和可执行的真实人工验收均有新鲜证据。

## 16. 当前实现状态与交接边界（2026-07-30）

本设计仍是本次迁移的权威架构约束；以下状态只描述当前实现和验收证据，不降低第 15 节的完成标准：

- 已落地：Compose 仅通过本地 Spring Boot WebSocket + JSON-RPC 工作；OA 登录、租户候选、session attach/restore、刷新、退出、权限安装和 READY 门禁已迁入后端；OA Token 由后端 JCEKS 持有，Compose 不保存 Token，密码只用于当前登录请求并清理。
- 已落地：认证 terminalization 严格执行关闭门禁/清 projection、best-effort remote logout、删除 secret/持久化 `SIGNED_OUT`、通知；refresh/restore 拒绝 userId 漂移；abort persistence 只有 CAS winner 可删除 live lease。remembered-account 发布在 map 临界区外校验 installation，并在写入时重新校验 READY lease，避免终态与迟到登录死锁或重插入。
- 已落地：工作台 BFF 已覆盖首屏快照、公告、快捷入口、统计、用户/团队/日程、四类分页、排序与数据范围校验；日程查询、选项、创建/完成、资源句柄、SQLite durable 附件票据/批次、真实 OA multipart 上传和 loopback 资源代理已有实现。
- 已落地：Compose 工作台已覆盖 typed teamRoles/updateSort、四类列表、TEAM team/role 回退、optimistic/canonical rollback、快捷入口循环、严格导航 allowlist、月/周日程、完整新增表单、关联项和附件上传/取消/epoch 丢弃；迁移位图由 SHA-256 合同固定。
- 已落地：finalized connection 门禁、固定 JSON-RPC 错误和敏感信息脱敏、双向报文 262144 字节边界、启动恢复，以及带 `installationId`/owner/generation/90 秒 TTL 的安装租约已接入。production business profile 默认拒绝客户端 `identity/catalog/context` projection；旧 application IT 只能显式启用默认 false 的 legacy-test property。
- 已验证：fake OA + 真 Spring Boot + 真 WebSocket 覆盖登录、READY、工作台、401 singleflight、重连、退出和旧代次丢弃；password/OA Token canary 覆盖 RPC、日志、SQLite、context/items/tools、异常、临时文件和报告。2026-07-29 fresh 后端为 Surefire 1463 tests 与 Failsafe 161 tests，0 failure/0 error。
- 已验证：fresh 安装包从 MSI 行政提取后完成真实 Compose signed-out/login-gate 烟测，并以提取包自带 runtime、classpath 和 bundled Spring Boot 对 loopback fake OA 完成正式密码编码、登录、READY、六区工作台、导航 allowlist 与助手 controller 烟测；分区证据只接受 `OK/EMPTY`，fake OA 精确校验 `MD5(MD5(password + huitaisystem))` 且未知路由 fail-closed；环境、进程树和受限临时目录均在 finally 收口，桌面 KeyStore 密码和 OA access/refresh canary 在运行现场 0 明文命中。
- 已修复：迟到的 startup restore lifecycle observer 不得覆盖已经开始的交互式 tenant lookup；确定性 RED 与真实 backend restart IT 合并 35 tests 通过。
- 部分验证：business-desktop fresh 六模块共 133 suites / 1024 tests，只有用户必须保留的 `PackagingScriptContractTest.kt` 两项失败；失败原因是该用户测试直接读取同时必须保留 staged deletion 的两个 `.run` 文件，其余 1022 tests 通过。
- 已验证：2026-07-30 在当前 IDEA 仓库通过独立 `Business Backend`、`Business Frontend` Run 标签完成前后端分离启动；后端就绪 `ws://127.0.0.1:49391/ws/agent`，桌面“翔鸟律智桌面端”未登录时显示登录门禁且不暴露工作台。进程停止后 PID `34976`、`576`、`7128` 已退出，49391 端口关闭，`.tmp-business-desktop-idea-runtime` 作为现场保留。
- 未完成：真实 OA 账号的正确密码、同时登录、refresh、重启 restore、断网重连、登录后真实工作台写操作与附件人工验收。2026-07-30 检查的 `HUITAI_OA_USERNAME`、`HUITAI_OA_PASSWORD`、`HUITAI_OA_BASE_URL`、`HUITAI_OA_TENANT_ID`、`HUITAI_OA_CAPTCHA_VERIFICATION`、`HUITAI_OA_CLIENT_ID`、`HUITAI_OA_CLIENT_SECRET` 均不存在；不得伪造验收结果。

因此，在上述项目和第 15 节完成标准全部有证据前，不得把迁移标记为完成，也不得调用 `update_goal(status=complete)`。详细命令、测试结果、工作区保护规则和下一步顺序见
`docs/superpowers/plans/2026-07-27-business-desktop-local-gateway-handoff.md`。
