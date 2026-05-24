# P2-3 设置系统 Handoff

## 状态

- 当前状态: 已实现并通过自动化验收，等待后续阶段继续推进。
- 计划入口: `docs/superpowers/plans/p2-3-settings-system/plan.md`
- 依赖: P2-1、P2-2 已完成。

## 目标

让 Provider、API Key、沙箱权限、审批策略都能在桌面端设置页编辑，并从下一轮 turn 起真实生效。

## 关键边界

- Provider 表不能保存明文 API Key。
- SecretStore 已由 `LocalKeyStoreSecretStore` 使用 JDK `JCEKS` KeyStore 实现；SQLite 只保存 `secretRef`，JSON-RPC 响应不回显明文 API Key。
- Running turn 使用启动时快照，不被设置页中途修改影响。
- “始终允许”只做 session scope，默认不做永久全局放行；匹配条件为同一 thread、同一 tool、同一 args fingerprint。

## 已完成实现

- 新增 `settings/get`、`settings/update`、`provider/list`、`provider/create`、`provider/update`、`provider/delete`、`provider/test`、`provider/set-active`、`sandbox/policy/set`、`approval/policy`、`approval/policy/set` JSON-RPC 方法。
- `ProviderSettingsService` 负责 Provider 参数校验、API Key 写入 SecretStore、Provider 配置落库、运行期 registry 同步和 ChatClient 缓存失效。
- `AppSettingsService`、`SandboxSettingsService`、`ApprovalPolicyService` 已把 active provider、默认 cwd、sandbox mode、approval policy 统一保存到 `bq_app_settings`。
- `ApprovalRespondHandler` 已支持 `decision=always`，`ApprovalRuleService` 已持久化 session always 规则，`ReActStrategy` 会在 HITL 中断时自动匹配并续跑。
- 桌面端设置页已支持 Provider 新增、编辑、删除、测试连接、选中当前 Provider，以及沙箱/审批策略修改。
- 审批弹窗的“始终允许”按钮已从占位变为真实可用，调用 `approval/respond` 时带 `decision=always` 和 `scope=session`。
- Provider 的 `contextWindow=0` 会按 `ModelMetadata` 自动推导，避免 UI 和测试看到无业务意义的 0。

## 验收命令

```powershell
cd backend
.\mvnw.cmd "-Dtest=ProviderSettingsServiceTest,LocalKeyStoreSecretStoreTest,AppSettingsServiceTest,ProviderSettingsHandlersTest,SettingsHandlersTest,ApprovalRuleServiceTest,ApprovalRespondHandlerTest" test
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test --tests "*SettingsModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest"
.\gradlew.bat test
```

已通过:

- `cd backend; .\mvnw.cmd "-Dtest=ProviderSettingsServiceTest,LocalKeyStoreSecretStoreTest,AppSettingsServiceTest,ProviderSettingsHandlersTest,SettingsHandlersTest,ApprovalRuleServiceTest,ApprovalRespondHandlerTest" test`
- `cd backend; .\mvnw.cmd "-Dtest=AgentLoopLineCountTest,ProviderTestControllerIntegrationTest" test`
- `cd backend; .\mvnw.cmd clean verify`
- `cd desktop; .\gradlew.bat test --tests "*SettingsModelsTest" --tests "*AgentClientTest" --tests "*ChatControllerTest"`
- `cd desktop; .\gradlew.bat test`

## 手动验收

1. 新增 Provider 并测试连接。
2. 保存后 UI 不回显 API Key 明文。
3. 切换 Provider 后下一轮 turn 使用新模型。
4. 修改沙箱和审批策略后下一轮 turn 生效。
5. 审批弹窗的“始终允许”具备真实后端语义。

## 下一步

- 进入 `P2-4 持久化后的恢复语义和运行记录`。
- P2-4 需要基于 P2-1/P2-2/P2-3 的 SQLite、最近对话和审批规则能力，补齐启动恢复收束、运行详情查询和历史 turn 运行记录。
