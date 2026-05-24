# P2-3 Settings System and Approval Policy Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, otherwise use `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 P1 的只读 Provider/权限展示升级为可编辑的本地设置系统，并让 Provider、沙箱、审批策略从下一轮 turn 开始真实生效。

**Architecture:** 后端通过 settings application service 管理 Provider、SecretStore、SandboxPolicy、ApprovalPolicy，JSON-RPC handler 只做参数校验和 DTO 转换。桌面端设置页从只读卡片升级为可编辑表单，但 running turn 使用启动时快照，不被中途设置变更影响。

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Validation, MyBatis-Plus 3.5.16, SQLite JDBC 3.53.1.0, Flyway 12.6.2, JDK `java.security.KeyStore` or documented local SecretStore fallback, Kotlin 2.3.21, Compose Multiplatform 1.11.0, Ktor Client 3.5.0, kotlinx.serialization 1.11.0.

---

## 0. 当前上下文

P2-3 必须在 P2-1 完成后实现；如果需要在 UI 上展示真实历史状态，最好在 P2-2 后实现。

当前限制:

- `ModelProviderRegistry` 从 `application.yml` 读取 provider，运行时只支持 `setActive`。
- `ProvidersListHandler` 只返回非敏感字段。
- `ProvidersSetActiveHandler` 只允许选择已配置 provider。
- `SandboxPolicyHandler` 只读后端当前沙箱策略。
- `ApprovalRespondHandler` 只支持 approve/deny/edit，不支持 always。
- `SettingsPanel` 只读展示 Provider 信息和工作区。

P2-3 的目标不是做云端账号体系，而是本地单用户设置。

## 1. 官方能力与版本检查

已在 2026-05-24 用 Maven Central 元数据核对:

- MyBatis-Plus Spring Boot 3 starter 最新稳定: `3.5.16`
- sqlite-jdbc 最新稳定: `3.53.1.0`
- Flyway core 最新稳定: `12.6.2`
- Spring Boot Maven metadata 已出现 4.x stable/RC 线，但当前仓库锁定 `3.5.14`，P2-3 不做 Spring Boot 大版本升级。

SecretStore 需要在实现前重新确认:

- 优先使用 JDK 官方 `java.security.KeyStore`。
- 如果 KeyStore 存储 API Key 的可用性或迁移成本不合适，允许使用 P2-1 定义的 `SecretStore` 抽象加本地文件加密实现，但必须在代码注释和 handoff 里写清安全边界。
- 无论采用哪种实现，`bq_provider_configs.secret_ref` 不能保存明文 API Key。

## 2. 数据库变更

新增 migration:

- Create: `backend/src/main/resources/db/migration/V3__settings_provider_policy.sql`

Migration 注释要求:

- 本阶段新增或修改的每张表、每个字段都必须在 SQL 中有中文 `--` 注释。
- 新增 `bq_approval_rules` 或补齐 `bq_provider_configs` / `bq_app_settings` 字段时，必须同步写入 `bq_schema_comments`。
- `SchemaCommentsCoverageTest` 必须继续通过，确保所有 `bq_*` 表字段都有中文说明。

建议新增/确认表:

### `bq_provider_configs`

P2-1 已创建时，本阶段补齐字段和约束:

- `provider_id`
- `display_name`
- `type`
- `base_url`
- `model`
- `secret_ref`
- `context_window`
- `enabled`
- `created_at`
- `updated_at`

### `bq_app_settings`

保存:

- `active_provider_id`
- `sandbox_mode`
- `approval_policy`
- `default_cwd`

### `bq_approval_rules`

用于 “始终允许”:

| 字段 | 说明 |
|---|---|
| `rule_id` | 规则业务 ID |
| `scope` | `session` 或 `workspace`，P2 默认只允许 session |
| `thread_id` | session scope 绑定的 thread，可为空 |
| `cwd` | workspace scope 预留，P2 可为空 |
| `tool_name` | 工具名 |
| `args_fingerprint` | 参数摘要，避免宽泛放行 |
| `decision` | P2 固定 `always` |
| `expires_at` | 过期时间，P2 默认随进程或会话结束 |
| `created_at` | 创建时间 |

P2-3 默认只实现 `session` scope，避免“永远不问”误伤所有未来任务。

## 3. JSON-RPC 协议

### 3.1 Settings

- `settings/get`
- `settings/update`

`settings/get` response:

```json
{
  "activeProviderId": "deepseek-v4-pro",
  "sandboxMode": "DANGER_FULL_ACCESS",
  "approvalPolicy": "ON_REQUEST",
  "defaultCwd": "E:\\BaBiQ"
}
```

### 3.2 Provider

- `provider/list`
- `provider/create`
- `provider/update`
- `provider/delete`
- `provider/test`
- `provider/set-active`

P2-3 可以保留 P1 的 `model/providers/list` 和 `model/providers/set-active` 作为兼容别名，但新设置页应优先使用 `provider/*`。

### 3.3 Sandbox

- `sandbox/policy`
- `sandbox/policy/set`

支持枚举以当前后端为准:

- `READ_ONLY`
- `WORKSPACE_WRITE`
- `DANGER_FULL_ACCESS`

### 3.4 Approval

- `approval/policy`
- `approval/policy/set`
- `approval/respond`

`approval/respond` 新增:

```json
{
  "threadId": "thr_xxx",
  "turnId": "turn_xxx",
  "decision": "always",
  "editedArgs": null,
  "scope": "session"
}
```

后端行为:

- `always` 等价于本次 approve。
- 同时写入 `bq_approval_rules`。
- 后续同一 session、同一 tool、同一 args fingerprint 命中规则时，后端自动 approve 并记录审计。

## 4. 文件结构

### 后端生产代码

- Create: `backend/src/main/java/com/wzx/babiq/server/settings/AppSettings.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/AppSettingsService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/ProviderSettingsService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/SandboxSettingsService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/ApprovalPolicyService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/LocalKeyStoreSecretStore.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/settings/SecretStore.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/model/ModelProviderRegistry.java`
  - 从 repository/settings service 读取动态 provider。
  - 支持刷新或以 service 为真相源。
- Modify: `backend/src/main/java/com/wzx/babiq/server/model/ChatClientFactory.java`
  - 从 SecretStore 解析 `secretRef`。
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ProvidersListHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ProvidersSetActiveHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ProviderCreateHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ProviderUpdateHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ProviderDeleteHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ProviderTestHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/SettingsGetHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/SettingsUpdateHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/SandboxPolicySetHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ApprovalPolicyGetHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ApprovalPolicySetHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ApprovalRespondHandler.java`
  - 支持 `always`。
- Create: `backend/src/main/java/com/wzx/babiq/server/approval/ApprovalRuleService.java`
  - 负责 always 规则匹配和写入。
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`
  - 工具审批前查询 always 规则。
- Modify: `backend/src/main/java/com/wzx/babiq/server/sandbox/SandboxPolicy.java`
  - 从 settings service 读取默认策略。

### 桌面端生产代码

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/SettingsModels.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ProviderSettingsModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ApprovalModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/SandboxModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsPanel.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/approval/ApprovalDialog.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/chat/ComposerContextBar.kt`

### 测试

- Create: `backend/src/test/java/com/wzx/babiq/server/settings/ProviderSettingsServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/settings/AppSettingsServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/settings/LocalKeyStoreSecretStoreTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/approval/ApprovalRuleServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ProviderSettingsHandlersTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/SettingsHandlersTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/ApprovalRespondHandlerTest.java`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/SettingsModelsTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

## 5. TDD 任务

### Task 1: Provider 设置服务和 SecretStore

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/settings/ProviderSettingsServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/settings/LocalKeyStoreSecretStoreTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/ProviderSettingsService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/LocalKeyStoreSecretStore.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/settings/SecretStore.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- 创建 provider 时 API Key 写入 SecretStore，provider 表只保存 `secretRef`。
- 更新 API Key 会生成或覆盖对应 secret。
- 删除 provider 默认不删除历史 turn，只禁用或删除配置。
- SecretStore 读不到 secret 时抛出明确异常。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProviderSettingsServiceTest,LocalKeyStoreSecretStoreTest test
```

- [ ] **Step 3: 实现服务**

实现要求:

- SecretStore 的类型、字段和方法必须有中文注释。
- 不在日志里输出 API Key。
- 单元测试断言数据库 provider 表不含明文 key。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProviderSettingsServiceTest,LocalKeyStoreSecretStoreTest test
```

### Task 2: AppSettings、Sandbox、ApprovalPolicy

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/settings/AppSettingsServiceTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/AppSettings.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/AppSettingsService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/SandboxSettingsService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/settings/ApprovalPolicyService.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- 默认设置从 `application.yml` 或 P2-1 默认值初始化。
- `sandboxMode` 只接受后端枚举。
- `approvalPolicy` 只接受后端枚举。
- 更新设置不影响已经启动的 turn 快照。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=AppSettingsServiceTest test
```

- [ ] **Step 3: 实现设置服务**

实现要求:

- 设置服务通过 `bq_app_settings` 持久化。
- 查询时把缺失值补成默认值。
- 写入时校验枚举和值类型。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=AppSettingsServiceTest test
```

### Task 3: Provider 和 Settings JSON-RPC handler

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ProviderSettingsHandlersTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/SettingsHandlersTest.java`
- Create/Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/*Provider*Handler.java`
- Create/Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/*Settings*Handler.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- `provider/create` 缺 displayName、type、model、apiKey 返回 `INVALID_PARAMS`。
- `provider/update` 不回显 apiKey。
- `provider/delete` 后 `provider/list` 不返回或标记 disabled。
- `provider/test` 使用 ChatClientFactory 构造一次轻量连接检查。
- `settings/get` 返回当前 sandbox 和 approval policy。
- `settings/update` 写入后再次读取能看到新值。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProviderSettingsHandlersTest,SettingsHandlersTest test
```

- [ ] **Step 3: 实现 handler**

实现要求:

- handler 只做参数解析和 DTO。
- 不记录 apiKey 原文。
- provider 测试连接失败要返回可读错误，不让异常栈穿透到 UI。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProviderSettingsHandlersTest,SettingsHandlersTest test
```

### Task 4: Always 审批语义

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/approval/ApprovalRuleServiceTest.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/api/method/ApprovalRespondHandlerTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/approval/ApprovalRuleService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ApprovalRespondHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- `decision=always` 会记录 approval rule。
- always 本次会按 approve 恢复。
- 同一 thread、同一 tool、同一 args fingerprint 命中时自动 approve。
- 不同 args fingerprint 不自动放行。
- session scope 结束后规则不再生效。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ApprovalRuleServiceTest,ApprovalRespondHandlerTest test
```

- [ ] **Step 3: 实现 always**

实现要求:

- `ApprovalDecision` 增加 `ALWAYS` 或在 handler 层规范化为 always。
- 关键注释解释 always 的 scope 和安全边界。
- 不实现全局永久 always。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ApprovalRuleServiceTest,ApprovalRespondHandlerTest test
```

### Task 5: 桌面端设置页可编辑

**Files:**
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/SettingsModels.kt`
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ProviderSettingsModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsPanel.kt`
- Create/Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/SettingsModelsTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

- [ ] **Step 1: 写失败测试**

覆盖:

- 连接成功后加载 settings 和 providers。
- 保存 Provider 后刷新列表。
- 保存 sandbox/approval policy 后更新上下文条。
- running turn 时设置控件禁用或提示“下一轮生效”。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

- [ ] **Step 3: 实现协议和状态**

实现要求:

- `ProviderEditorState` 保存表单草稿。
- API Key 输入框不从后端回填明文。
- 保存成功后显示短提示，不清空整个设置页。

- [ ] **Step 4: 实现 UI**

UI 要求:

- 设置页包含 Provider 列表、新增/编辑弹窗、测试连接按钮。
- 沙箱权限用分段控制或下拉。
- 审批策略用分段控制或下拉。
- “始终允许”按钮在审批弹窗中真实可点击；如果后端返回不支持，UI 禁用并展示原因。

- [ ] **Step 5: 运行测试通过**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

### Task 6: 全量验证和文档同步

**Files:**
- Modify: `docs/superpowers/plans/p2-3-settings-system/codex-handoff.md`
- Modify: `docs/superpowers/plans/p2-task-index.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: 后端全量验证**

```powershell
cd backend
.\mvnw.cmd clean verify
```

- [ ] **Step 2: 桌面端全量验证**

```powershell
cd desktop
.\gradlew.bat test
```

- [ ] **Step 3: 手动验收**

1. 新增 Provider。
2. 输入 API Key，保存后确认 UI 不回显明文。
3. 测试连接。
4. 切换 Provider 后发送一轮消息。
5. 切换沙箱模式，确认下一轮 turn 生效。
6. 切换审批策略，确认下一轮 turn 生效。
7. 触发审批，点击“始终允许”，同 session 同工具同参数不再重复询问。

- [ ] **Step 4: 更新文档**

- `docs/superpowers/plans/p2-3-settings-system/codex-handoff.md`
- `docs/superpowers/plans/p2-task-index.md`
- `AGENTS.md`
- `CLAUDE.md`

- [ ] **Step 5: 中文 commit**

```powershell
git add backend desktop docs AGENTS.md CLAUDE.md
git commit -m "feat(p2-3): 实现本地设置系统和审批策略"
```

不要 push。

## 6. 验收标准

- Provider 可以新增、编辑、删除、启用、禁用、测试连接。
- Provider 表不保存明文 API Key。
- Sandbox 和 ApprovalPolicy 可在 UI 修改。
- 设置默认下一轮 turn 生效，running turn 不被中途改变。
- `approval/respond` 支持 always，且语义明确。
- `cd backend; .\mvnw.cmd clean verify` 通过。
- `cd desktop; .\gradlew.bat test` 通过。

## 7. 非目标

- 不做云端账号体系。
- 不做多用户权限。
- 不做 OAuth Provider。
- 不做永久全局 always。
- 不做 MCP marketplace。
