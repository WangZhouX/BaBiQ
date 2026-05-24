# P2-3 设置系统 Handoff

## 状态

- 当前状态: 计划已编写，等待用户确认后实现。
- 计划入口: `docs/superpowers/plans/p2-3-settings-system/plan.md`
- 依赖: P2-1 必须完成；建议 P2-2 完成后再实现。

## 目标

让 Provider、API Key、沙箱权限、审批策略都能在桌面端设置页编辑，并从下一轮 turn 起真实生效。

## 关键边界

- Provider 表不能保存明文 API Key。
- SecretStore 必须明确实现和安全边界。
- Running turn 使用启动时快照，不被设置页中途修改影响。
- “始终允许”只做 session scope，默认不做永久全局放行。

## 验收命令

```powershell
cd backend
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

## 手动验收

1. 新增 Provider 并测试连接。
2. 保存后 UI 不回显 API Key 明文。
3. 切换 Provider 后下一轮 turn 使用新模型。
4. 修改沙箱和审批策略后下一轮 turn 生效。
5. 审批弹窗的“始终允许”具备真实后端语义。
