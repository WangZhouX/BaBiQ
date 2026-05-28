# P3-UI-Desktop 交接记录

## 本次完成结论

P3-UI-Desktop 已完成。Compose Desktop 已按 Figma P3 原型把 P3 上下文与记忆平台能力做成真实产品 UI，并接入现有后端 JSON-RPC：

- 输入栏上下文 chip 增加 P3 状态弹层，统一展示上下文窗口、短期压缩、长期记忆和能力装配状态。
- 运行详情从单行上下文摘要升级为上下文快照、长期记忆引用和能力搜索审计分区。
- 设置页升级长期记忆设置卡和能力中心，支持真实调用 `memory/settings/set`、`memory/consolidate`、`memory/search`、`capability/settings/set`、`capability/search`。
- 能力中心补充中文 query 示例，覆盖“读取文件 / 运行命令 / 列出目录 / 搜索关键字 / 打补丁”等 P3-5a 已支持的中文能力搜索路径。
- 聊天流中的 `contextCompaction` item 继续使用后端真实事件，但在 UI 中展示为“上下文”事件卡，不再误导为普通工具调用。

`00 交互总览-P3` 是 Figma 原型索引页，仅用于开发导航和验收说明，没有进入产品 UI。

## Figma 对齐

- 原型入口：`https://www.figma.com/proto/frTp55zgrKf4NAWxn6LdI7?node-id=35-2`
- 本次用于产品落地的关键 Frame：`97:2`、`97:86`、`97:173`、`97:237`、`97:307`、`97:379`、`97:436`、`97:500`、`97:568`、`97:617`、`97:663`
- 明确排除：`00 交互总览-P3`，它是原型索引页，不是桌面端页面。

## 后端接入边界

本次没有新增后端接口，也没有改变 Agent 核心语义。桌面端直接复用 P3 已完成的真实 JSON-RPC：

- `context/status`
- `run/turn/get`
- `memory/status`
- `memory/jobs/list`
- `memory/artifacts/list`
- `memory/settings/set`
- `memory/consolidate`
- `memory/search`
- `capability/status`
- `capability/settings/set`
- `capability/search`

设置页和运行详情展示的是后端事实源返回的数据；记忆检索和能力搜索都是只读审计/预览，不会把结果写入聊天历史，也不会绕过后端 `ContextWindowRuntime` 的下一轮上下文装配。

## 测试证据

已新增和覆盖的桌面端测试：

- `ContextStatusPopoverTest`
- `ContextSnapshotSectionTest`
- `MemorySettingsSectionTest`
- `CapabilityCenterSectionTest`
- `MessageBubbleTest`
- `ChatControllerTest`

已执行目标验证：

```powershell
cd E:\BaBiQ\desktop
.\gradlew.bat test --tests "*ContextStatusPopoverTest" --tests "*ContextSnapshotSectionTest" --tests "*MemorySettingsSectionTest" --tests "*CapabilityCenterSectionTest" --tests "*MessageBubbleTest" --tests "*ChatControllerTest"
```

结果：`BUILD SUCCESSFUL`。

## 下一步

进入 P3 总体验收复盘，核对 P3-1 到 P3-UI-Desktop 的上下文窗口、短期压缩、长期记忆、按需能力装配、Lucene 能力搜索和桌面控制是否满足阶段目标。
