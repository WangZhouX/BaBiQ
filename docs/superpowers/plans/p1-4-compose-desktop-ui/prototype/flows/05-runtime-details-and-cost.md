# 05 运行详情与成本反馈流程

## 目标

默认保持聊天界面清爽,右侧运行详情收起。用户需要查看时展开,看到当前 turn 的工具轨迹、事件流和成本明细。TurnSummary 成本反馈条始终在聊天流中可见。

## 主流程

```mermaid
flowchart TD
    A["收到 turn/started"] --> B["创建运行详情时间线"]
    B --> C["收到 item/added"]
    C --> D["根据 item type 追加事件"]
    D --> E{"用户是否展开运行详情?"}
    E -- "否" --> F["右侧只显示收起把手"]
    E -- "是" --> G["展示工具轨迹、审批记录、事件时间线"]
    D --> H{"是否收到 turnSummary?"}
    H -- "否" --> C
    H -- "是" --> I["在聊天流中渲染成本反馈条"]
    I --> J["同步更新运行详情成本区"]
```

## 运行详情内容

| 模块 | 内容 |
| --- | --- |
| 事件时间线 | `turn/started`、`item/added`、`approval/request`、`turn/completed` |
| 工具轨迹 | 工具名、状态、耗时、输出摘要 |
| 审批记录 | 决策类型、操作者动作、时间 |
| 成本信息 | tokensIn、tokensOut、costUsd、durationMs、toolCount |
| 错误信息 | `turn/failed` 的错误摘要 |

## TurnSummary 展示规则

```mermaid
flowchart TD
    A["收到 item/added"] --> B{"item.type 是否为 turnSummary?"}
    B -- "否" --> C["按普通 item 渲染"]
    B -- "是" --> D["渲染成本反馈条"]
    D --> E["显示输入 tokens"]
    D --> F["显示输出 tokens"]
    D --> G["显示 costUsd"]
    D --> H["显示 durationMs"]
    D --> I["显示 toolCount"]
```

## 关键约束

- 成本反馈条来自后端 `turnSummary`,桌面端不自行估算 tokens 或价格。
- 右侧详情默认收起,避免抢占聊天主空间。
- 错误和 interrupted turn 也要显示 summary,只要后端发送了 `turnSummary`。
