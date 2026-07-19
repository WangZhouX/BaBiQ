# 汇泰业务桌面 Agent 框架 Codex Handoff

## 当前状态

- Task 29–37：全部完成。
- 框架验收：通过。
- 最终代码提交：`893e169 fix: 闭环业务桌面最终人工烟测`。
- 具体 OA 业务迁移：尚未开始，符合阶段边界。

## 最终实现范围

- 纯 Kotlin Compose Desktop Shell、响应式布局和通用七字段演示。
- 页面上下文、表单建议、来源/置信度、单字段/全部接受。
- 统一 `ApplicationActionBus`、预览、审批、幂等、终态与 reconciliation。
- Ktor WebSocket JSON-RPC Agent 客户端、Thread/Turn/Item、Provider 选择和重连。
- 汇泰认证、租户、权限、HTTP/WebSocket 集成底座。
- JCEKS、SQLite 审计、脱敏、单实例、隔离运行目录和子进程生命周期。
- 内置 business profile Agent、固定工具 allowlist、长期记忆关闭。
- MSI/EXE 打包、canonical 安装包 smoke、真实模型人工烟测。

## Task 37 最终补丁

`893e169` 处理了人工验收中发现的全部问题：

1. 首次 Thread 前允许输入并在发送时确定性创建 Thread。
2. 窗口关闭回调先同步 `root.shutdown()`，再退出 Compose。
3. 仅向 Agent 子进程转发显式 Provider 引导变量和必要运行环境。
4. OpenAI-compatible `/v1` Base URL 归一化。
5. 新 Turn 和成功终态清除旧错误。
6. 安装包 smoke 固定使用 canonical MSI。
7. 删除默认 API Key，并加资源级防回归测试。
8. SQLite 终态采纳测试使用符合真实持久化抖动的时限。

## 验证命令

```powershell
cd E:\huitai-work\BaBiQ\backend
.\mvnw.cmd clean verify

cd E:\huitai-work\BaBiQ\desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat test --rerun-tasks --max-workers=1 --no-parallel --no-build-cache

cd E:\huitai-work\BaBiQ\business-desktop
$env:GRADLE_USER_HOME='E:\gradle-home-ascii'
.\gradlew.bat test --rerun-tasks --max-workers=1 --no-parallel --no-build-cache `
  "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process"
.\gradlew.bat :app:packageDistributionForCurrentOS :app:smokePackagedDistribution `
  --rerun-tasks --max-workers=1 --no-parallel --no-build-cache `
  "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process"
```

结果：Backend 933、旧 Desktop 337、新 Business Desktop 694 项测试全部通过；正式安装包 smoke 通过。

## Provider 引导说明

业务桌面子进程只继承以下显式 Provider 配置：

- `AI_DASHSCOPE_API_KEY`
- `DEEPSEEK_API_KEY`
- `ONEAPI_BASE_URL`
- `ONEAPI_KEY`
- `ONEAPI_MODEL`
- `ANTHROPIC_CLI_PATH`
- `PATH`

其他父进程 Token、数据库密码和 `HUITAI_DESKTOP_KEYSTORE_PASSWORD` 不会继承。API Key 不进入桌面 State、上下文、审计或日志。

## 下一阶段入口

若继续迁移真实 OA 业务，应先基于本框架单独创建计划，至少明确：

1. 第一批领域边界和页面，不要一次性迁移全部客户/案件/文书。
2. 汇泰真实 API、认证、租户、权限和错误码契约。
3. 每个动作的风险、审批、幂等、reconciliation 和审计字段。
4. 真实敏感字段脱敏规则。
5. 领域级自动化、人工烟测和数据回滚方案。

## 工作区注意事项

- `.tmp-gradle-review/` 是用户原有未跟踪目录，未读取、未修改、未提交。
- `business-desktop/app/build/manual-smoke-*` 和临时模型网关均属于忽略的验收产物，不进入 Git。
- 进入下一阶段前先阅读 `framework-acceptance.md` 和 `manual-smoke.md`。

## 2026-07-19 Provider 设置页与 Agent 面板折叠补充

- 左侧“设置”现为真实 Provider 管理页，支持新增、编辑、复制、删除、测试、设为当前，以及已保存 Anthropic OAuth Provider 的状态检查和登录。
- OpenAI-compatible 中转站可自由填写 Base URL 和模型 ID，例如 `kimi-k3`；保存后进入 SQLite/JCEKS 真相源，下一轮对话使用新配置。
- API Key 不回显、不进入桌面全局 State、日志或协议响应；编辑时留空代表保留现有密钥，保存请求结束后输入框会清空。
- Provider 创建/更新/删除、active fallback、启动恢复、密钥轮换和失败补偿已收紧；最后一个启用 Provider 不允许删除，business Provider 方法要求完成 identity bind。
- 宽屏/中屏右侧业务 Agent 可从 420dp/360dp 收起为 52dp 窄栏，中心工作区随之扩宽；展开状态仅在当前进程内保留。compact 模式继续通过 Agent 页签显示完整对话页。
- IDEA 开发态只启动 `business-desktop` 的 Gradle task `:app:run`，该 task 会先构建并准备内置 backend jar，无需另启 backend 服务。环境变量至少设置：
  - `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY=1`（当前演示身份）；
  - `HUITAI_DESKTOP_KEYSTORE_PASSWORD=<与现有业务桌面 home 中 JCEKS 一致的固定密码>`。
- 详细实现、测试证据和安全语义见 `docs/superpowers/plans/business-desktop-provider-settings-agent-panel/codex-handoff.md`。
