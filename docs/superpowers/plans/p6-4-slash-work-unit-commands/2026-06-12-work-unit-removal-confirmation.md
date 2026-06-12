# 2026-06-12 WorkUnit / Flow / Team 移除确认修订

## 背景

用户指出右侧“编排”“团队”详情页缺少移除入口，并要求不只手动页面可移除，对话中的 Agent 也应具备移除对应编排或团队的权限，但移除前必须二次确认。

## 结论

- 右侧 WorkUnit 列表、编排详情、团队详情都必须先弹二次确认，再执行移除动作。
- WorkUnit 容器移除仍然是软移除：只设置 `removed=1` / `removed_at`，不删除 SQLite 审计记录、运行记录或工具调用记录。
- 已完成/失败/取消的运行时编排或团队卡片可以从右侧详情隐藏，但这只是桌面端本地隐藏，不删除聊天历史、WorkUnit 或审计记录。
- 运行中的 WorkUnit 容器不显示移除入口，后端也继续拒绝移除 running 容器。
- Agent 通过 `work_unit_manage(action=remove)` 移除 WorkUnit 时，必须先获得用户二次确认；未传 `confirmed=true` 时工具返回失败并提示确认要求，不会调用 `WorkUnitService.remove(...)`。

## 已更新代码

- 后端 `work_unit_manage` 新增 `confirmed` 参数；remove 动作必须二次确认后传 `confirmed=true`。
- System prompt 新增 WorkUnit 移除规则，要求 Agent 在对话中先确认，再执行 remove。
- 桌面端新增编排/团队运行卡片本地隐藏状态，避免同一个 runtime item 后续更新后重新弹出。
- 右侧运行详情面板统一增加移除确认弹窗，覆盖 WorkUnit 列表、编排详情、团队详情。

## 验证

- `cd backend; .\mvnw.cmd "-Dtest=WorkUnitManageToolTest,SystemPromptSecurityRuleTest" test`
- `cd desktop; .\gradlew.bat test --tests "*OrchestrationSectionTest" --tests "*TeamSectionTest" --tests "*ChatReducerTest"`
