# P2-3 Provider / API Key / 沙箱 / 审批设置系统任务卡

## 目标

把 P1 的只读 Provider 和权限展示升级为可编辑设置系统，并让设置影响下一轮 turn。

## 依赖

- 必须等待 P2-1 完成。
- Provider 配置和 app setting 必须走 SQLite。
- API Key 不允许明文保存到 provider 表。

## 必做能力

- Provider 新增、编辑、删除、启用、禁用。
- Provider 测试连接。
- SecretStore 真实实现或明确安全边界。
- 沙箱模式 UI 可选择: `READ_ONLY`、`WORKSPACE_WRITE`、`DANGER_FULL_ACCESS`。
- 审批策略 UI 可选择: `NEVER`、`ON_REQUEST`、`ON_FAILURE` 或实际后端枚举。
- 补齐 `approve / deny / edit / always` 语义。
- 设置修改默认影响下一轮 turn，不改变 running turn。

## 验收

- UI 能新增和切换 Provider。
- Provider 表不保存明文 API Key。
- 权限和审批设置在下一轮 turn 生效。
- “始终允许”按钮要么真实可用，要么禁用并说明原因，不能假可用。

## 下一步

在实现前写 `docs/superpowers/plans/p2-3-settings-system/plan.md` 并等待用户确认。
