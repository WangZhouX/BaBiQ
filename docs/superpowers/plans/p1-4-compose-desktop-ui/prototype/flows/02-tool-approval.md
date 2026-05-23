# 02 工具审批流程

## 目标

当后端因为 HITL 触发 `approval/request` 时,桌面端弹出审批弹窗。用户可以 Approve、Deny、Always 或 Edit,桌面端调用 `approval/respond` 回写决策。

## 主流程

```mermaid
flowchart TD
    A["后端 ReAct 触发高风险工具"] --> B["后端发送 approval/request"]
    B --> C["桌面端锁定当前 turn 为 waitingApproval"]
    C --> D["弹出 ApprovalDialog"]
    D --> E["显示工具名、工作目录、参数、权限模式"]
    E --> F{"用户选择"}
    F -- "Approve" --> G["approval/respond: decision=approve"]
    F -- "Deny" --> H["approval/respond: decision=deny"]
    F -- "Always" --> I["approval/respond: decision=always"]
    F -- "Edit" --> J["进入参数编辑态"]
    J --> K["用户修改参数"]
    K --> L["approval/respond: decision=edit, args=修改后参数"]
    G --> M["关闭弹窗,显示工具继续执行"]
    H --> N["关闭弹窗,显示工具被拒绝"]
    I --> M
    L --> M
    M --> O["继续消费 item/added / item/updated"]
    N --> O
    O --> P["收到 turn/completed 或 turn/failed"]
```

## 弹窗内容

| 区域 | 内容 |
| --- | --- |
| 标题 | `需要审批工具调用` |
| 风险标签 | 当前权限策略,例如 `on-request` 或 `完全访问权限` |
| 上下文标签 | 项目、cwd、分支、worktree |
| 工具信息 | tool name、命令或参数 JSON |
| 操作按钮 | `Deny`、`Edit`、`Always`、`Approve` |

## 异常流程

```mermaid
flowchart TD
    A["弹窗已打开"] --> B{"WebSocket 是否断开?"}
    B -- "否" --> C["允许提交 approval/respond"]
    B -- "是" --> D["禁用审批按钮"]
    D --> E["显示连接断开提示"]
    E --> F["重连成功"]
    F --> G{"后端仍有 pending approval?"}
    G -- "是" --> H["恢复弹窗并允许继续审批"]
    G -- "否" --> I["关闭弹窗,提示审批已过期"]
```

## 关键约束

- `approval/request` 的 `itemId`、`threadId`、`turnId` 必须原样保留,供 `approval/respond` 使用。
- `Edit` 只允许编辑后端暴露的工具参数;P1 不做任意脚本编辑器。
- 如果审批提交失败,弹窗不直接消失,要显示错误并允许用户重试或取消。
