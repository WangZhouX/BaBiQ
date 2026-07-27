# 业务桌面本地网关架构讨论纪要

> 日期：2026-07-27
> 性质：用户与 Codex 的架构讨论结果，供后续主任务 Agent 查阅和转化为正式设计/实施计划。
> 状态：目标架构已达成共识，但尚未据此修改代码。文中“目标”不代表当前实现已经完成。

## 1. 讨论背景

翔鸟律智 Web 端和业务桌面端使用同一套 OA 用户、租户、角色与权限体系。业务桌面当前由 Kotlin Compose 前端和本地 Spring Boot Agent Server 组成，两者通过 WebSocket + JSON-RPC 通信。

本次讨论集中解决以下问题：

1. 桌面端是否应直接调用远程 OA HTTP 接口。
2. 远程 OA 后端如果也提供 WebSocket，应由谁建立和维护连接。
3. OA 登录是否需要另建桌面用户体系。
4. 本地 Spring Boot 在整个业务桌面架构中的准确职责。

## 2. 已确认的核心决策

### 2.1 保持同一套 OA 用户体系

桌面端不新增用户表、注册体系、账号或密码。Web 端和桌面端继续使用同一套 OA：

- 用户 `userId`。
- 租户 `tenantId`。
- 角色、权限和数据范围。
- 账号密码校验规则。
- Token 签发、刷新和失效机制。

用户已明确确认：同一 OA 用户允许 Web 端和桌面端同时登录。

两端应创建彼此独立的客户端会话和 Token：

- Web 端登录取得 Web Token。
- 桌面端经本地 Spring Boot 登录取得 Desktop Token。
- 退出桌面端不应退出 Web 端。
- Web Token 过期不应直接使桌面端退出。
- 两端身份和权限一致，但不共享同一枚 Token。

### 2.2 Compose 不直接连接远程 OA

目标架构下，Compose 桌面前端不直接调用远程 OA HTTP，也不直接连接远程 OA WebSocket。

Compose 只维护到本地 Spring Boot 的一条受认证 WebSocket 连接，并使用桌面自有 JSON-RPC 协议发送请求、接收响应和订阅通知。

### 2.3 本地 Spring Boot 是业务桌面的本地 BFF/应用网关

本地 Spring Boot 不是透明的字节转发代理，而是同时承担以下职责：

- 对 Compose：WebSocket + JSON-RPC 服务端。
- 对 OA：HTTP 客户端，以及 OA 存在实时通道时的 WebSocket 客户端。
- 身份网关：执行 OA 登录、刷新、退出、租户切换和权限加载。
- 协议网关：将 OA DTO、错误和推送转换为稳定的桌面协议。
- 业务聚合：聚合工作台等页面需要的多个 OA 接口结果。
- 安全边界：集中保存 Token，实施登录门禁、租户隔离、脱敏和审计。
- Agent 后端：继续承载现有 Agent、工具、审批、沙箱和本地持久化能力。

目标拓扑如下：

```text
Kotlin Compose 桌面前端
        |
        | 本机 loopback WebSocket + JSON-RPC
        v
BaBiQ 本地 Spring Boot（BFF / 身份网关 / 协议网关 / Agent Server）
        |
        | OA HTTPS API / OA WebSocket
        v
远程翔鸟律智 OA 后端
        |
        v
现有用户、租户、角色、权限和业务数据
```

## 3. 两层认证必须区分

桌面端存在两层不同目的的认证，但这不等于两套用户体系。

### 3.1 本地进程连接认证

Compose 连接本地 Spring Boot 时使用现有一次性本地会话身份，用于证明连接者是本次启动的合法桌面进程。

该身份：

- 不是 OA 用户账号。
- 用户无需输入或感知。
- 只保护本机 WebSocket 和进程边界。
- 不能替代 OA 业务登录。

### 3.2 OA 业务用户登录

用户仍输入原有 OA 账号密码。Compose 通过本地 WebSocket 把一次登录请求交给 Spring Boot，由 Spring Boot 使用 HTTPS 调用现有 OA 登录接口。

成功后，Spring Boot 保存 OA Token 和完整业务会话，只向 Compose 返回渲染界面所需的安全身份 DTO。

## 4. 推荐登录数据流

```text
1. Compose 与本地 Spring Boot 建立受本地身份保护的 WebSocket。
2. Compose 发送 business/auth/login。
3. Spring Boot 调用 OA 租户查询、密码登录和权限接口。
4. Spring Boot 校验 userId、tenantId、platformId 和权限身份一致性。
5. Spring Boot 安装 OA access/refresh Token、当前租户和权限快照。
6. Spring Boot 完成桌面身份、能力目录和页面上下文注册。
7. 业务门禁进入 READY。
8. Spring Boot 只向 Compose 返回用户、租户、权限摘要和 READY 状态。
```

桌面请求示例：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "business/auth/login",
  "params": {
    "account": "13800138000",
    "password": "******",
    "tenantId": "2"
  }
}
```

返回给 Compose 的结果示例：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "status": "READY",
    "user": {
      "id": "123",
      "name": "张律师"
    },
    "tenant": {
      "id": "2",
      "name": "个人律师端"
    }
  }
}
```

禁止在该结果或任何其他桌面协议消息中返回 OA `accessToken`、`refreshToken`、KeyStore 密码或远程请求正文。

## 5. 登录后的业务调用

登录成功后，Compose 继续只调用桌面协议，不携带 OA Token。

以工作台为例：

```text
Compose
  -> business/workbench/get
本地 Spring Boot
  -> 使用当前 Desktop Token 调用一个或多个 OA HTTP 接口
  -> 校验租户与权限
  -> 聚合并转换为 BusinessWorkbenchSnapshot
  -> JSON-RPC response 返回 Compose
Compose
  -> 渲染工作台
```

Spring Boot 应返回桌面自己的稳定 DTO，不应原样透传 OA 公共响应。这样 OA 接口字段或组合方式变化时，可以只调整网关适配层，而不迫使 Compose UI 同步理解所有 OA 内部契约。

## 6. OA WebSocket 的处理方式

如果远程 OA 后端存在工作台、待办、消息或案件状态的 WebSocket 推送，由本地 Spring Boot 建立和维护该连接。

此时 Spring Boot 同时具有两个角色：

- 面向 Compose 的 WebSocket 服务端。
- 面向远程 OA 的 WebSocket 客户端。

两条连接的端点、协议和生命周期彼此独立，不会发生技术冲突。

推荐事件流：

```text
OA WebSocket 推送
  -> 本地 Spring Boot 校验当前用户、租户和会话代次
  -> 转换为 business/workbench/updated 等桌面通知
  -> 通过现有本地 WebSocket 推送 Compose
  -> Compose 增量更新，或按通知重新拉取工作台快照
```

示例桌面通知：

```json
{
  "jsonrpc": "2.0",
  "method": "business/workbench/updated",
  "params": {
    "type": "todo_changed"
  }
}
```

用户退出、认证失效或切换租户时，必须关闭旧 OA WebSocket。切换租户后使用新租户身份重新建立订阅，禁止复用旧连接或处理旧会话迟到事件。

## 7. 生命周期与安全约束

### 7.1 Token 与密码

- OA Token 只由本地 Spring Boot 的认证和远程适配层使用。
- Compose、Agent、MCP、工具参数和页面上下文都不得看到 OA Token。
- 用户密码只用于当前登录调用，不写入日志、SQLite、协议错误或 Agent 上下文。
- 远程 OA 登录必须使用 HTTPS；本机进程间通信只绑定 loopback。
- Token 应通过本地受保护存储持久化，并继续遵守现有 JCEKS 和撤权约束。

### 7.2 READY 门禁

- 本地 WebSocket 可以在 OA 未登录时建立，但只能处于 signed-out/pre-auth 状态。
- 工作台及其他业务请求必须要求当前业务会话为 `READY`。
- 登录、权限安装或桌面身份注册任一步失败，都不能进入 `READY`。
- 退出、Token 失效、用户变化或租户变化时，应先提升会话代次并撤销旧请求，再清理 Token、缓存和远程连接。

### 7.3 重连与错误转换

- Compose 只负责本地 WebSocket 重连，不感知 OA HTTP 重试或 OA WebSocket 重连细节。
- Spring Boot 负责 OA Token 单飞刷新、远程 WebSocket 心跳和退避重连。
- OA 错误应转换为稳定桌面错误码，禁止原样返回远程响应正文。
- 旧身份、旧租户和旧会话代次的迟到响应必须丢弃。

## 8. 文件上传下载例外

普通页面数据、命令和实时事件统一走本地 WebSocket + JSON-RPC。

未来遇到大附件上传、下载或需要流式传输的内容，可以由本地 Spring Boot 提供受认证的 loopback HTTP 流式通道；Compose 仍不直接访问远程 OA。该例外应单独设计，不能把普通业务 API 重新散落到 Compose。

## 9. 与当前代码和既有设计的差异

主任务 Agent 必须注意：本次讨论结果是后续目标架构，不是对当前实现状态的描述。

现有 `2026-07-22-business-desktop-oa-login-design.md` 明确把 OA 登录网关放在 Kotlin `huitai-integration-core`，并描述了桌面端 OA HTTP 与 Agent WebSocket 两条独立连接。当前代码也已经存在 Kotlin `KtorOaAuthenticationGateway` 等直接访问 OA 的实现。

因此，落实本纪要需要一次明确的架构迁移，而不是只修改工作台 UI：

- 把 OA 登录、刷新、退出和权限查询迁移到本地 Spring Boot。
- 在 Spring Boot 中建立 OA 会话、Token 和租户上下文。
- 新增稳定的桌面认证与业务 JSON-RPC 方法。
- 把工作台等业务 HTTP 调用迁入 Spring Boot 适配层。
- 如果 OA 存在 WebSocket，再增加远程连接管理与桌面事件转换。
- 最后删除或收缩 Compose 进程中的 OA 直连网关。

不得同时保留“登录由 Compose 直连、业务由 Spring Boot 代理”的长期混合方案。该方案要求在两个进程之间同步 Token、刷新、退出和租户状态，实际比统一网关更复杂，也更容易出现状态不一致和凭据暴露。

## 10. 建议实施顺序

后续主任务 Agent 应先调查代码和接口，再为该架构迁移单独编写正式设计与实施计划。建议分阶段推进：

1. **协议与会话底座**：定义 Spring Boot 内部 OA 会话端口、桌面认证 DTO、错误码和 JSON-RPC handler。
2. **认证迁移**：把租户查询、登录、权限、refresh 和 logout 从 Kotlin 直连迁到 Spring Boot，保持现有 UI 行为不变。
3. **工作台迁移**：Spring Boot 聚合工作台真实 OA 接口，Compose 只消费桌面工作台快照。
4. **实时事件迁移**：仅在代码确认 OA 确有 WebSocket 后，实现远程连接、身份绑定和事件转换。
5. **直连收口**：移除 Compose 对 OA 地址、Token 和业务 HTTP 客户端的依赖。
6. **安全与烟测**：验证登录恢复、同时登录、刷新、退出、租户切换、网络中断和旧会话迟到事件。

每个阶段必须遵循 TDD，并保持当前 `READY` 门禁、身份代次、Application Action 权限边界和敏感数据不外泄。

## 11. 主任务 Agent 需要从代码继续核实的事项

下列事实不能仅凭本次讨论假设，实施前必须以现有前后端代码和真实契约为准：

- OA 登录、refresh、logout、权限和租户接口的最终参数与响应字段。
- OA 是否对桌面客户端区分平台请求头、设备或客户端类型。
- OA Token 的并发会话、刷新轮换和单端退出语义。
- 工作台实际调用的全部 HTTP 接口及权限条件。
- OA 是否确实存在工作台相关 WebSocket、SSE 或其他推送通道。
- 推送事件是否携带用户、租户和数据范围标识。
- 本地 Spring Boot 当前协议注册、认证门禁和持久化中最合适的扩展点。
- 迁移期间如何兼容已有本地凭据，避免要求用户无故重复登录或损坏 KeyStore。

其中“同一 OA 用户允许 Web 和桌面同时登录”已经由用户在本次讨论中明确确认，可作为需求前提；具体 Token 行为仍应由代码和真实环境验证。

## 12. 完成标准

当后续架构迁移完成时，应满足：

- Compose 源码不再直接访问远程 OA HTTP/WebSocket 地址。
- Compose 不保存、不接收、不输出 OA Token。
- OA 登录仍使用原有用户、租户、角色和权限体系。
- Web 与桌面可同时登录，且两端 Token 与退出生命周期独立。
- Spring Boot 统一完成 OA 登录、刷新、退出、业务 HTTP 和远程 WebSocket 管理。
- 工作台等页面只消费稳定的桌面协议 DTO。
- 未达到 `READY` 时无法调用工作台或其他业务能力。
- 退出、过期和租户切换后，旧请求及旧推送无法影响新会话。
- 日志、SQLite、WebSocket payload、Agent 上下文和工具调用中不存在密码或 OA Token。
- 自动化测试和真实环境烟测覆盖登录、恢复、工作台、刷新、退出、同时登录及断网重连。

## 13. 一句话结论

业务桌面的最终通信边界应为：**Compose 只负责 UI 并只连接本地 Spring Boot；本地 Spring Boot 作为 BFF、身份网关和协议网关，代表同一个 OA 用户连接远程 OA HTTP/WebSocket；Web 与桌面共享用户体系，但使用彼此独立的客户端会话。**
