# 业务桌面动作 executionId 与失败终态修复设计

## 背景

真实执行 `execution-421514d3-04d1-4c45-b527-0e6efdfe7b8e` 调用 `form.read_state` 时，桌面数据库记录的输入指纹为 `44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a`，与规范 JSON `{}` 的 SHA-256 完全一致。该动作输入契约要求唯一字段 `executionId`，因此桌面在校验阶段以 `VALIDATION_FAILED` 结束。

同一次执行中，桌面先发布 `running`，随后把本地已经持久化为 `FAILED` 的结果发布为 `application/action/rejected`。后端只允许 `ACCEPTED -> REJECTED`，因此拒绝了来自 `EXECUTING` 状态的通知，直到 120 秒状态恢复后才收束为 `FAILED`。

## 目标

1. 模型无需生成或猜测服务端执行 ID；后端生成后把权威 `executionId` 注入动作输入。
2. 桌面端发布终态时以持久化执行记录为真相源，本地 `FAILED` 必须发布 `application/action/failed`。
3. 保持动作输入指纹、请求载荷和实际桌面执行输入一致。
4. 对输入校验失败立即结束，不再等待动作超时或状态恢复。

## 非目标

- 不放宽动作强类型输入校验。
- 不允许模型控制权威 `executionId`。
- 不修改 Provider、代理、API Key 或模型配置。
- 不修改 SQLite 表结构或业务动作风险/审批语义。

## 方案选择

采用“后端注入权威执行 ID + 桌面持久化终态优先”的方案。

- 相比删除所有动作输入中的 `executionId`，该方案不需要重构现有动作、幂等和对账接口。
- 相比只允许后端 `EXECUTING -> REJECTED`，该方案既修复空输入，也保持 `REJECTED` 与 `FAILED` 的既有语义边界。
- 相比要求模型填写 `executionId`，该方案不会暴露服务端尚未生成的值，也不会接受伪造关联 ID。

## 后端输入规范化

`executionId` 是协议保留字段。`ApplicationActionTool` 在完成目录、页面和输入对象基础校验后检查 descriptor：

- `inputSchema` 完全没有声明 `executionId` 时，动作输入保持原样；
- 声明了 `executionId`，且类型为字符串、`required` 明确包含该字段时，生成权威 ID并注入；
- 声明了 `executionId`，但它是可选字段、非字符串或 schema 结构无效时，必须 fail-closed 返回 `validation_failed`，不得让模型控制同名字段。

需要注入时，后端复制模型输入并写入服务端生成的 `executionId`：

```json
{
  "executionId": "execution-..."
}
```

如果模型输入已包含同名字段，服务端值覆盖模型值。未声明该保留字段的动作保持输入原样，避免破坏 `additionalProperties=false` 的严格 schema。规范化后的输入必须重新执行 64 KiB action input 限制校验，并同时用于：

- 请求指纹；
- 发往桌面的 action request；
- 后续执行绑定。

调用者提供的原始 `JsonNode` 不得被原地修改。注入后超出安全尺寸时，在注册 pending action 之前返回 `validation_failed`。

## 桌面终态发布

动作执行总线可能返回 `Rejected`，同时把可审计执行记录持久化为 `FAILED`。运行时完成后按以下优先级发布：

1. 如果能读取到持久化终态，发布该终态及其持久化错误，例如 `FAILED/validation_failed`。
2. 只有没有持久化终态、且动作在执行准入阶段被拒绝时，才发布 `REJECTED`。
3. 没有确定终态时继续现有对账/恢复流程。

因此校验失败的协议序列为 `accepted -> running -> failed`，而不是 `accepted -> running -> rejected`。

## 错误处理与安全

- 仍不持久化或记录原始动作输入，只保存脱敏指纹。
- `executionId` 始终由后端生成并覆盖，防止关联污染。
- `validation_failed` 使用现有安全摘要，不增加原始异常或输入回显。
- 后端状态机继续拒绝非法 `EXECUTING -> REJECTED`，不以放宽安全边界掩盖桌面发布错误。

## 测试

1. 后端工具测试：模型传入空对象时，出站 action request 的 `input.executionId` 等于生成的执行 ID。
2. 后端工具测试：模型传入伪造 `executionId` 时被服务端生成值覆盖，且原始输入对象不被修改。
3. 后端工具测试：请求指纹基于规范化输入，保持持久化绑定和真实请求一致。
4. 后端工具测试：没有声明 `executionId` 的严格 schema 不注入额外字段；把保留字段声明为可选、非字符串或无效结构时 fail-closed；接近 64 KiB 的输入在注入后超限时于注册前失败。
5. 桌面运行时测试：输入解码校验失败且本地记录为 `FAILED` 时发布 `application/action/failed`，载荷包含 `state=failed` 和 `errorCode=validation_failed`。
6. 回归测试：真正的准入拒绝仍发布 `application/action/rejected`。
7. 跨端闭环：后端以 `{}` 调用真实 `form.read_state`，桌面收到与顶层 envelope 相同的权威 ID并成功返回；构造真实解码失败时，桌面发布 `failed`，后端从 `EXECUTING` 立即收束到 `FAILED`，不经过 120 秒恢复。
8. 运行后端与 business-desktop 定向测试，再运行双方全量测试。

## 验收标准

- `form.read_state` 接收模型空输入时能够获得正确的服务端 `executionId` 并成功读取表单状态。
- 任意动作的输入校验失败在一次协议往返内显示为 `FAILED`，不再等待约 120 秒。
- 后端不再出现该场景的 `application/action/rejected` 非法参数警告。
- 现有动作审批、取消、超时、对账和真正拒绝语义不回退。
