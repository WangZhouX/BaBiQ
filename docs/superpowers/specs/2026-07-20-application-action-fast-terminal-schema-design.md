# 业务动作快速终态与安全输入 Schema 修复设计

## 现场证据

真实执行 `execution-dca6b441-eed8-4644-ad61-e1ea71da2baf` 在桌面数据库中已经是 `SUCCEEDED`，但 Agent 数据库仍停在 `ACCEPTED`。后端日志显示桌面发送 `application/action/completed` 后，`ApplicationActionProtocolHandler` 返回 `INVALID_PARAMS`。

桌面运行时为降低进度噪声，允许终态覆盖尚未发送的中间 `running`。快速只读动作因此可能只发布 `accepted -> completed`；后端状态机此前只允许 `RUNNING -> COMPLETED`，造成终态被拒绝、工具 Future 和 Turn 永久等待。

同一真实对话中的 `form.preview_patch` 返回 `validation_failed`。目录实现只向模型提供动作名称、风险和描述，没有提供输入 schema；而表单动作自身也只声明 `patch: object`，没有声明 `pageId/baseRevision/changes` 等嵌套结构，模型无法可靠构造参数。

## 设计

### 快速终态

后端只在动作已经位于对应风险路径的合法执行门槛时接受省略 `running` 的 `COMPLETED` 或 `OUTCOME_UNKNOWN`：

- `READ_ONLY`: `ACCEPTED`
- `REVERSIBLE_WRITE`: `PREVIEWED`
- `HIGH_RISK`: `APPROVAL_REQUIRED`

接受终态前，后端内部补记 `RUNNING`，依次发布进度并持久化 `EXECUTING`、终态。高风险动作从 `ACCEPTED` 或 `PREVIEWED` 直接完成仍被拒绝。

### 模型可见输入结构

`ApplicationContextModelContributor` 从目录 schema 生成结构化安全投影，只保留：

- `type`
- `properties`
- `required`
- `items`
- 布尔型 `additionalProperties`
- 数字型长度、数量和范围约束

投影移除服务端权威注入的 `executionId`，不复制 `description/default/example/pattern` 等不可信文本；遇到凭据型属性名或无法识别的结构时不暴露该 schema。

### 表单 Patch Schema

演示动作完整声明 `patch.pageId`、`patch.baseRevision`、`patch.changes[]`、字段变化和来源引用结构，使模型可以基于当前页面上下文生成有效补丁。

## 验收

- 快速只读动作不发送 `running` 也能完成，Agent 审计仍为 `ACCEPTED -> EXECUTING -> COMPLETED`。
- 高风险动作不能绕过预览和审批。
- 模型上下文包含安全结构 schema，但不包含 `executionId`、schema 描述或凭据字段。
- `form.preview_patch` 和 `form.apply_patch` 目录包含完整嵌套 Patch schema。
- 后端与业务桌面全量测试通过。
