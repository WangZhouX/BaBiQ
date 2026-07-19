# 业务桌面 Provider 设置与 Agent 面板折叠 Handoff

## 状态

- 完成日期：2026-07-19。
- 规格：`docs/superpowers/specs/2026-07-19-business-desktop-provider-settings-agent-panel-design.md`。
- 实施计划：`docs/superpowers/plans/2026-07-19-business-desktop-provider-settings-agent-panel.md`。
- 结论：代码实现、定向测试、后端全量、业务桌面强制全量、真实 jar 跨重启验收、规格审查和代码质量审查均已闭环。

## 用户可见能力

### Provider 设置页

- 左侧“设置”进入真实 Provider 管理页，支持刷新、新增、编辑、复制、删除、轻量测试和设为当前。
- Provider 类型支持 `OPENAI_COMPATIBLE`、`DASHSCOPE`、`ANTHROPIC`；认证支持 `api_key`，Anthropic 额外支持 `oauth_cli`。
- OpenAI-compatible Provider 必填 Base URL；模型 ID 为自由文本，可直接填写自定义中转站实际提供的模型，例如 `kimi-k3`。
- 新建 API Key Provider 必须提供密钥；编辑时密钥输入默认空白，留空保留现有密钥，填写则执行安全轮换。
- 保存失败时编辑器保留非敏感草稿；API Key 在请求结束后的 `finally` 中清空，成功后才关闭编辑器。
- OAuth 状态与登录只对已经持久化的 Anthropic OAuth Provider 开放；新建/复制草稿先保存，避免向后端发送空 ID 或旧 ID。

### Agent 面板展开/收起

- 宽屏展开宽度 420dp，中屏展开宽度 360dp；收起后统一保留 52dp 入口栏。
- 收起后中心表单或设置页获得释放出的宽度；点击“展开业务 Agent”恢复完整对话。
- `agentPanelExpanded` 由 `Main` 持有，只在当前桌面进程内存在，不写数据库或设置文件。
- 消息、未发送输入、当前 Provider、连接和 turn 状态均由上层状态持有，折叠/展开不会丢失。
- compact 布局不显示 52dp 窄栏，仍通过“Agent”页签进入完整对话页。

## 后端持久化与安全语义

- SQLite 是 Provider 非敏感配置真相源；JCEKS 保存 API Key，数据库和 JSON-RPC 响应只暴露 `secretRef`/`hasApiKey` 语义，不回显密钥。
- create 为 create-if-absent，重复 ID 不覆盖；update 只更新已存在且启用的 Provider。
- Provider 变更由机器级 mutation coordinator 串行化；SQLite 提交、SecretStore、运行时 registry 和 ChatClient 缓存失败均有补偿路径。
- 启动时以 SQLite 恢复运行时 Provider 目录；YAML 只补齐从未持久化的初始项，已软删除项不会复活。
- 删除采用软禁用；删除 active Provider 时按 Provider ID 确定性选择 fallback 并同事务持久化；最后一个启用 Provider 不允许删除。
- `provider/*` 以及等价旧写入口均要求 business identity bind；Provider 测试、OAuth 和错误响应只返回安全摘要。

## IDEA 启动

只需一个 Gradle 运行配置：

```text
Gradle project: E:\huitai-work\BaBiQ\business-desktop
Tasks: :app:run
```

开发演示环境至少配置：

```text
HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY=1
HUITAI_DESKTOP_KEYSTORE_PASSWORD=<与当前 HUITAI_DESKTOP_HOME 内已有 JCEKS 一致的固定密码>
```

`:app:run` 的任务依赖已包含 `:app:packageBusinessBackendJar`、`:app:prepareBundledBusinessBackend` 和 `:app:prepareAppResources`，因此不需要在 IDEA 中单独启动 backend。若更换 `HUITAI_DESKTOP_KEYSTORE_PASSWORD` 却继续复用旧 home，JCEKS 会拒绝解密并导致桌面初始化失败。

Provider 已经通过设置页持久化后，不再要求每次启动都设置 `ONEAPI_BASE_URL`、`ONEAPI_KEY` 或 `ONEAPI_MODEL`；这些变量只适合全新 home 的首次引导。

## 新鲜验证证据

### 定向验证

- Backend Provider/访问策略：64 tests，0 failures/errors。
- `agent-client-core` Provider 协议：11 tests，0 failures/errors。
- 设置控制器、设置页、布局、Shell、Agent 面板：36 tests，0 failures/errors。
- 折叠功能 TDD 定向集：20 tests，0 failures/errors。

### 全量验证

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd -q clean verify
```

结果：917 个 unit tests + 63 个 integration tests，共 980 tests，0 failures/errors。

```powershell
cd E:\huitai-work\BaBiQ\business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat test --rerun-tasks --max-workers=1 --no-parallel --no-build-cache `
  "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process"
```

结果：7 个模块共 725 tests，0 failures/errors；42 个 Gradle tasks 全部实际执行。

模块明细：

- `agent-client-core`: 133
- `application-action-core`: 154
- `app`: 128
- `framework-demo`: 26
- `huitai-integration-core`: 131
- `presentation-core`: 68
- `security-audit-core`: 85

### 真实后端 jar 跨重启

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd -q -DskipTests package

cd E:\huitai-work\BaBiQ\business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat :app:test --tests "*BusinessProviderSettingsRestartIT" `
  --no-daemon --max-workers=1 --no-parallel --no-build-cache `
  "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process"
```

结果：1 test，0 failures/errors；测试真实启动、停止并复用同一 home 重启 backend，覆盖创建、更新、API Key、active、fallback 和软删除，且协议响应与日志不包含测试密钥。

## 审查与已知非阻断项

- 设置页规格审查：Spec compliant；代码质量：Ready，无 Critical/Important。
- Agent 折叠规格审查：Spec compliant；代码质量：Ready，无 Critical/Important。
- 折叠会移除完整 Agent composition，因此滚动位置、Reasoning 展开状态等组件局部展示状态会重置；消息、输入和 Provider 等业务状态保持。本阶段需求未要求持久化这些局部展示状态。
- 折叠按钮当前使用带明确 content description 的箭头 `TextButton`；无障碍语义已覆盖，后续可按视觉系统替换为标准图标资源。

## 工作区与提交边界

- `.tmp-gradle-review/` 是用户原有未跟踪目录，未读取、未修改、未提交。
- 未提交真实 API Key、JCEKS、SQLite、运行目录或后端日志。
- 本阶段主要提交从 `ce938fc` 到 `ae23826`；最终差异检查以仓库本地配置 `codex.business-provider-settings-base` 记录的实施基线为准。
