# 业务桌面左右壳层纠偏 QA 记录

日期：2026-07-22
分支：`codex/lawyer-oa-desktop`

## 验收范围

- Windows 原生标题栏作为唯一品牌展示位置。
- Compose 顶部工具栏只保留“设置”。
- 左侧固定业务导航只包含工作台、资料录入、运行记录。
- 中间业务区与右侧助手采用相邻左右布局，不使用覆盖层。
- 助手收起时保留 124dp 右侧控制列；吉祥物不再创建整行底部安全区。
- 助手展开、拖动宽度、再次收起以及设置页返回业务页均可用。

## 自动化证据

### 聚焦验证

- Shell / 顶部栏 / 左侧栏 / 布局策略：35 tests，0 failures，0 errors，0 skipped。
- 品牌与 packaged smoke 契约：22 tests，0 failures，0 errors，0 skipped。
- Shell 四组加附件工作流：36 tests，0 failures，0 errors，0 skipped。
- 附件测试视口回归：在 `Density(0.75f)` 下真实命中发送按钮右边界越出 1024px 根视口 4px；改为 `Density(0.7f)` 后，附件和发送按钮四边均位于根视口内。

### 桌面全量测试

两次全量均执行 376 tests：

1. 第一次为 375/376，通过后只剩 `BusinessAgentAttachmentWorkflowIT` 使用旧展开视口；该问题已按上述红绿证据修复。
2. 第二次仍为 375/376，唯一失败变为与本次 UI 文件无重叠的 `BusinessDesktopFrameworkIT`：快速 `EXECUTING -> FAILED` 时偶发缺少 `application/action/running`。

对第二项做了独立诊断：

- 本次提交未修改 `BusinessDesktopFrameworkIT`、`application-action-core` 或 action runtime。
- 该 IT 串行聚焦运行 3 次均通过。
- 根因是既有 runtime 依赖 10ms observer 轮询 `EXECUTING`，而终态发布会清空尚未消费的 progress；这是生产事件漏发竞态，不是 WebSocket 乱序或测试读取竞态。
- 本任务没有通过延时、重试或放宽断言掩盖该问题，也没有越过左右壳层范围修改协议内核。

## 真实窗口验收

使用独立临时 Home，分别启动：

- `:app:runBusinessBackendDevelopment`
- `:app:runBusinessFrontendDevelopment`

后端明确输出：

```text
Business Backend ready at ws://127.0.0.1:49391/ws/agent
```

最大化窗口捕获尺寸为 1920 x 1032，实际检查结果：

- 原生标题栏显示一次 Logo 和“翔鸟律智桌面端”；Compose 内容区没有重复品牌。
- Compose 顶部只有右侧“设置”，没有工作台、资料录入和运行记录。
- 左侧栏显示工作台、资料录入、运行记录；点击顶部设置后，可从左侧资料录入返回业务页。
- 收起态吉祥物位于右侧窄控制列底部；页面底部没有整行空白条，保存草稿和提交资料按钮完整可见。
- 点击吉祥物后，“小律智能助手”在右侧相邻展开，左侧导航仍存在，业务表单只是按宽度缩小，不被覆盖。
- 将分隔条从约 x=1456 拖到 x=1376 后，助手面板实际增宽约 80px；拖动有效。
- 再次点击助手内吉祥物后恢复 124dp 收起控制列，业务区恢复。

隔离 Home 没有配置 Provider，因此助手内容区显示“请求失败，请检查后重试”；这不影响壳层布局验收。前端出现 Skiko DirectX12 创建失败后的备用 API 回退警告，但窗口正常渲染并完成全部交互，前端任务最终 `BUILD SUCCESSFUL`。

## 清理结果

- 临时前端窗口正常关闭。
- 后端启动树 8 个进程全部停止。
- 49391 端口无监听残留。
- 未删除或覆盖用户现有 Home、Provider、数据库和未提交改动。
