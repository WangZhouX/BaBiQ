# P6-4 WorkUnit 编排详情修正记录（2026-06-12）

## 背景

真实模型烟测准备过程中发现，桌面端右侧“编排详情”存在两个与 P6-4 WorkUnit 心智模型不一致的问题：

1. “+ 添加节点”按钮处于禁用状态，点击没有任何效果。
2. 编排拓扑固定显示 `explorer -> analyzer/tester -> router`，没有根据 WorkUnit 目标文本或保存配置展示实际节点。例如目标里明确写了 `explorer / designer / writer / reviewer`，右侧仍然看不到 `designer`、`writer`、`reviewer`。

## 修正后的语义

- WorkUnit 后端事实源仍只负责保存目标和配置，不在创建容器时硬塞默认节点。
- 桌面端编排详情不再写死 `explorer/analyzer/tester/router`。
- 如果 WorkUnit 已有 `configJson.nodes`，右侧详情以保存配置为准，只补齐 `START` 和 `END` 终端节点。
- 如果没有保存配置，但当前目标文本显式写出 `xxx 节点` 或 `xxx node`，右侧详情按出现顺序生成这些中间节点。
- 如果没有保存配置，也没有显式节点说明，默认只显示 `START` 和 `END`，中间节点由用户手动添加。
- “+ 添加节点”会新增 `node_N` 草稿节点，插入到 `END` 前，并立即通过 `workunit/config/update` 保存到 WorkUnit 配置。
- 节点保存时写入枚举式 `mode` 值，例如 `READ_ONLY_TOOL` / `WORKSPACE_TOOL`，展示文案不再反写成配置事实。

## 验证覆盖

新增/调整 `OrchestrationSectionTest` 覆盖：

- 已保存配置时不再注入旧的硬编码节点。
- 无显式节点目标时默认仅 `start/end`。
- 显式 `explorer/designer/writer/reviewer 节点` 目标会生成对应节点。
- 新增节点插入到 `end` 前。

已通过：

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*OrchestrationSectionTest"
```
