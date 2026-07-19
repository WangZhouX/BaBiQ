# Business Desktop Provider 设置与 Agent 面板折叠设计

## 背景

`business-desktop` 已能列出和切换 Provider，但当前 UI 只提供只读下拉框。后端其实已经实现并在 business profile 中放行 `provider/create`、`provider/update`、`provider/delete`、`provider/test`、`provider/set-active`、`provider/oauth/status` 和 `provider/oauth/login`。因此用户无法在业务桌面内修改中转站地址、API Key 或任意模型 ID，也无法修正首次启动后已经持久化的旧 Provider 配置。

同时，宽屏和中屏布局把右侧 Agent 面板固定为 420dp/360dp，用户无法临时释放业务表单或设置页的横向空间。

## 目标

1. 让左侧“设置”成为真实页面，并在业务桌面内完成 Provider 的新增、编辑、复制、删除、轻量测试和设为当前。
2. 允许用户自由输入模型 ID，例如 `kimi-k3`，保存后立即进入 SQLite/JCEKS 真相源并在下一轮对话生效。
3. API Key 在客户端只存在于一次保存请求和组件局部输入状态，不进入 `BusinessDesktopState`、日志、审计、聊天上下文或后端响应。后端运行时为构造 ChatModel 可以在受控 registry/SecretStore 解析链路中持有密钥，但不得记录或回显。
4. 让宽屏和中屏右侧 Agent 面板可收起和展开；收起后中心工作区自动扩宽。
5. 保持 compact 布局可访问资料录入、设置和 Agent 三个内容页。

## 非目标

- 不新增 Provider 数据库表或迁移。
- 不把旧 `desktop` 工程作为运行时依赖。
- 不把 API Key 写入 Compose 全局 Store 或本地明文文件。
- 不改变后端 `provider/test` 的既有语义；它仍是构造 ChatModel 的轻量配置检查，不发起计费聊天。
- 不持久化 Agent 面板展开状态；状态只在当前桌面进程内保留。

## 方案选择

采用“业务桌面原生设置页 + 复用现有 JSON-RPC”的方案。

- 相比继续依赖环境变量，它支持运行中编辑和持久化。
- 相比复用旧桌面 UI 模块，它保持两个桌面工程的构建与产品边界独立。
- 相比直接修改 SQLite，它继续经过 `ProviderSettingsService`、SecretStore、运行时 registry 和 ChatClient 缓存失效链路。

## 架构

### 后端 Provider 语义加固

设置页会把现有 `provider/*` 写接口变成用户可直接触达的生产入口，因此先收紧既有语义：

- `provider/create` 改为 create-if-absent；重复 Provider ID 返回 `INVALID_PARAMS`，绝不再通过底层 upsert 静默覆盖。复制草稿默认生成 `<id>-copy`，若仍冲突则要求用户修改 ID。
- `provider/update` 只允许更新已存在记录。`api_key` 更新留空只在旧记录已经存在 secretRef 时表示沿用；从 `oauth_cli`/无密钥配置切换到 `api_key` 时必须提供新 Key。
- SecretStore 使用版本化 alias 保存替换密钥。创建或更新的 SQLite 步骤失败时删除刚写入的新 alias；更新成功后删除旧 alias；切换到 OAuth 或删除 Provider 后删除不再引用的旧 alias。
- 删除前要求至少保留一个启用 Provider。删除当前 Provider 时确定性选择下一个启用 Provider，把 fallback 同时写入 `bq_app_settings` 和运行时 registry，再返回新的 `activeProviderId`。
- 启动 bootstrap 以 SQLite 为真相源：已软删除的 YAML Provider 必须从运行时 registry 移除；持久化 active Provider 必须在 registry 恢复完成后重新应用；失效 active 值自动修复为首个启用 Provider并持久化。
- Provider 写入和 active/fallback 持久化使用明确事务边界；事务外 SecretStore 操作必须有补偿，运行时 registry 只安装已提交配置。

### Provider 协议层

扩展 `business-desktop/agent-client-core` 的 Provider 模型，使列表项包含：

- `id`
- `displayName`
- `type`
- `authMode`
- `baseUrl`
- `model`
- `contextWindow`
- `enabled`
- `hasApiKey`
- `active`

新增不记录秘密的 Provider 保存草稿、删除结果、测试结果和 OAuth 状态模型。`BusinessAgentClient` 继续使用同一条认证 WebSocket，调用现有 `provider/*` 方法。所有响应模型都不包含明文 API Key。

业务桌面模式下，Provider 的 list/create/update/delete/test/set-active/OAuth status/login 全部要求已经完成 business identity bind 和 finalized connection。它们从 `PRE_BIND_METHODS` 移到 `POST_BIND_METHODS`；只有身份握手方法可以在 bind 前调用。Provider 仍是本机 Agent 数据目录下的机器级设置，不按业务用户或租户复制保存，但任何写入必须来自已认证的业务桌面会话。

### Provider 控制层

新增专用 `BusinessProviderSettingsController`，负责：

- 刷新 Provider 列表；
- 创建、更新、复制和删除 Provider；
- 测试配置；
- 切换当前 Provider；
- 查询和启动 Anthropic CLI OAuth；
- 把安全的成功/失败提示投影到设置 UI 状态。

Provider API Key 不进入 controller 的 `StateFlow`。设置表单用组件局部状态持有 API Key；点击保存后立即清空。编辑已有 Provider 时 API Key 字段永远为空，留空表示沿用后端现有 secretRef。

### 设置页 UI

新增 `BusinessProviderSettingsPanel`，位于中心工作区：

- 顶部标题、说明和“新增 Provider”入口；
- Provider 列表展示名称、ID、类型、认证模式、模型、上下文窗口、密钥状态和当前状态；
- 操作包括“设为当前”“编辑”“复制”“测试”“删除”；
- 编辑表单支持 Provider ID、名称、类型、认证模式、Base URL、模型自由文本、Context Window 和密码输入框；
- Provider ID 在编辑模式只读；复制时生成 `-copy` 草稿且不复制 API Key；
- 删除使用确认对话框，后端语义仍为软禁用；
- Anthropic `oauth_cli` 模式隐藏 API Key，显示 CLI 状态检查和登录入口；
- 表单做非空字段和非负 Context Window 校验；远端错误只显示安全摘要。

旧桌面设置页可以作为交互参考，但业务桌面重新实现精简 Provider 区域，不引入跨 Gradle 工程依赖。

### 导航与响应式布局

`BusinessDesktopShell` 接收真实的 `BusinessNavigationItem` 状态与回调：

- `DATA_ENTRY` 显示现有资料录入页；
- `SETTINGS` 显示 Provider 设置页；
- `WORKBENCH` 和 `RUN_HISTORY` 保留明确的通用占位内容，不再表现为点击无效；
- compact 模式使用“资料录入 / 设置 / Agent”三个标签页。

### Agent 面板折叠

宽屏和中屏：

- 展开时保持现有 420dp/360dp；
- 面板标题栏增加“收起业务 Agent”按钮；
- 收起后右侧保留 52dp 窄栏，包含纵向/紧凑标题和“展开业务 Agent”按钮；
- 中心区域获得释放出的宽度；
- 消息、输入内容、当前 Provider、连接状态和 turn 状态不因折叠而重置。

compact 模式本来没有固定右栏，因此不套用折叠窄栏，继续通过 Agent 标签进入完整对话页。

## 数据流

1. 桌面启动后 `provider/list` 加载非敏感 Provider 真相源。
2. 用户进入设置页，选择已有 Provider 或创建草稿。
3. 保存时 UI 构造一次性 Provider draft；API Key 只存在于该调用参数。
4. 后端 `ProviderSettingsService` 把 API Key 写入 JCEKS，把非敏感字段写入 SQLite，并刷新运行时 registry/ChatClient 缓存。
5. 后端提交 SQLite 后安装运行时 registry，并对 SecretStore alias 做成功清理或失败补偿。
6. 客户端清空 API Key 局部输入并重新调用 `provider/list`。
7. 用户设为当前后，右侧 Agent 下拉框同步更新；下一轮 turn 使用新 Provider/模型。

## 错误与安全处理

- API Key 不参与 `toString()`，不进入 reducer/store，不写日志。
- 保存失败后显示通用或服务端安全校验信息；不显示远端响应正文中的秘密。
- `provider/test` 不再透出 `exception.getMessage()`；失败统一返回固定安全文案。OAuth status/login 也只返回由布尔状态映射出的受控文案，不透出 CLI 路径、命令输出、token 或远端正文。
- 后端日志只允许记录 Provider ID、操作类型和异常类名；禁止记录 draft、API Key、SecretStore 内容或原始异常 message。测试使用假 secret 注入异常，证明 JSON-RPC、UI 状态和日志均不包含该 secret。
- 删除当前 Provider 后刷新列表并采用删除响应中的 `activeProviderId`；禁止删除最后一个启用 Provider。
- 网络断开时设置操作禁用，保留非敏感草稿；API Key 不跨页面/重连持久保存。
- OAuth 登录只调用既有后端入口，不在桌面直接启动 shell。

## 测试

1. 后端 service：create 重复 ID 拒绝；api_key 条件校验；SecretStore 新旧 alias 补偿/清理；最后 Provider 删除拒绝；active fallback 持久化；disabled YAML Provider 启动不复活；错误文案不泄密。
2. 后端 access policy：逐方法验证 Provider 读写/test/OAuth 在 bind 前拒绝、finalized identity 后允许。
3. `agent-client-core`：Provider 完整字段解码、CRUD/test/OAuth JSON-RPC 请求、API Key 不出现在响应模型和字符串表示中。
4. Controller：保存后刷新、选择当前、错误投影、删除刷新、API Key 不进入状态。
5. Compose 设置页：自由模型输入、编辑回填但 API Key 为空、复制清密钥、重复 ID 错误、删除确认、OAuth 模式、按钮回调。
6. Shell：设置导航可达、compact 显式三态标签、宽/中屏收起后 52dp 窄栏、展开恢复、中心区域扩宽；`agentPanelExpanded` 提升到 `Main`/Shell owner，不能保存在被移除的 AgentPanel 内。
7. 真实重启 IT：使用临时 SQLite/JCEKS 和业务 profile 启动真实 backend jar，经过认证 WebSocket 创建/更新 Provider、设置 active、停止进程、用相同运行目录重启，再验证 baseUrl/model/contextWindow/hasApiKey/active/soft-delete；全程断言密钥不出现在协议和日志。
8. 回归：现有资料录入、Agent 对话、Provider 下拉和业务动作测试保持通过。

## 验收标准

- 用户无需修改 YAML、SQLite 或重建运行目录，即可把 `oneapi-relay` 的模型从 `gpt-4o` 改成任意模型 ID并保存。
- 保存的 Base URL、模型、Context Window 和 API Key 在重启后仍有效，API Key 不回显。
- Provider 可新增、编辑、复制、测试、设为当前和删除。
- 重复 ID 不覆盖旧 Provider；删除当前 Provider 后 fallback 在重启后保持；最后一个 Provider 不可删除。
- 密钥轮换、OAuth 切换、失败回滚和软删除不会留下仍可引用的孤儿 SecretStore alias。
- 未完成 business identity bind 的连接不能读取或修改 Provider 设置。
- 宽屏/中屏 Agent 面板可以收起和展开，折叠不丢失对话状态。
- business-desktop 定向和全量测试通过；涉及后端访问策略或 Provider 行为的回归测试通过。
