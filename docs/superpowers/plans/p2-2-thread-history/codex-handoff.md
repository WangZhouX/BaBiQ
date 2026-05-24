# P2-2 多会话历史和桌面端最近对话 Handoff

## 状态

- 当前状态: 计划已编写，等待用户确认后实现。
- 计划入口: `docs/superpowers/plans/p2-2-thread-history/plan.md`
- 依赖: P2-1 SQLite + MyBatis-Plus 持久化底座必须先完成。

## 目标

把 P1/P2-1 的持久化基础接入真实业务路径，让会话历史能在后端重启后恢复，并让桌面端 Sidebar 的最近对话来自后端 `thread/list`。

## 关键边界

- P2-2 只做会话历史和最近对话。
- 不做 Provider 编辑、API Key 存储、运行详情历史查询、MCP 或搜索。
- JSON-RPC handler 不允许直接访问 MyBatis Mapper。
- 桌面端不直接访问数据库，只通过 JSON-RPC 读取历史。

## 验收命令

```powershell
cd backend
.\mvnw.cmd clean verify

cd ..\desktop
.\gradlew.bat test
```

## 手动验收

1. 创建会话并发送一轮消息。
2. 关闭后端。
3. 重启后端和桌面端。
4. 左侧最近对话仍能看到刚才的会话。
5. 点击历史会话能恢复消息和 TurnSummary。
6. 归档后默认最近列表隐藏该会话。
