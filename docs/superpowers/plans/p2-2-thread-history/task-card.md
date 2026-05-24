# P2-2 多会话历史和桌面端最近对话任务卡

## 目标

把 P1 的单内存会话升级为可恢复的多会话历史，让桌面端左侧最近对话来自 SQLite。

## 依赖

- 必须等待 P2-1 完成。
- 必须使用 P2-1 提供的 repository adapter，不允许桌面端或 JSON-RPC handler 直接访问 mapper。

## 必做能力

- `thread/create` 写入数据库。
- `turn/start`、`item/added`、`turn/completed` 同步写入数据库。
- 新增 `thread/list`、`thread/load`、`thread/archive` JSON-RPC method。
- 桌面端最近对话读取真实 `thread/list`。
- 点击历史会话可恢复 item 流。
- 归档会话从默认列表隐藏。

## 验收

- 创建会话、发送消息、关闭后端、重启后端后，桌面端仍能看到并加载历史会话。
- `cd backend; .\mvnw.cmd clean verify` 通过。
- `cd desktop; .\gradlew.bat test` 通过。

## 下一步

在实现前写 `docs/superpowers/plans/p2-2-thread-history/plan.md` 并等待用户确认。
