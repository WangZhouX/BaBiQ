# 业务 Agent 本机附件交接说明

日期：2026-07-21
对应设计：`docs/superpowers/specs/2026-07-20-business-agent-local-attachments-design.md`
对应计划：`docs/superpowers/plans/2026-07-20-business-agent-local-attachments.md`

## 1. 交付结论

业务桌面右侧 Agent 输入区已经形成 Codex 风格本机附件链路：文件选择与 Ctrl+V 截图只把
受控本机路径和稳定附件标识送入 WebSocket；后端在可信业务身份、当前 thread 和本机文件边界内
重新校验、提取文档或构造图片多模态输入。附件正文与图片字节不进入 WebSocket、SQLite 普通
item、context snapshot 或普通日志；历史轮次可以用 `A-XXXXXX` 在同一 thread 内精确引用。

## 2. 文件地图

### 2.1 业务桌面

- `business-desktop/agent-client-core/.../BusinessAttachmentModels.kt`：草稿/已发送附件模型与安全 `toString`。
- `business-desktop/agent-client-core/.../BusinessAgentClient.kt`：`turn/start.input.attachments` 路径元数据协议。
- `business-desktop/app/.../runtime/BusinessAttachmentIdFactory.kt`：UUID 与稳定显示 ID。
- `business-desktop/app/.../ui/agent/BusinessAttachmentPicker.kt`：多选文件、类型/大小预检与 chooser seam。
- `business-desktop/app/.../runtime/ClipboardImageAttachmentStore.kt`：Ctrl+V PNG 原子写入受控目录。
- `business-desktop/app/.../controller/BusinessComposerSendCoordinator.kt`：发送单飞、失败保留和成功精确清理。
- `business-desktop/app/.../ui/agent/BusinessAgentPanel.kt`：草稿及历史消息附件 chip、移除与粘贴入口。
- `business-desktop/app/.../ui/shell/BusinessDesktopShell.kt`、`Main.kt`：Shell 和真实组合根接线。
- `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessAgentAttachmentWorkflowIT.kt`：无原生对话框的完整 UI 回归。

### 2.2 后端

- `backend/src/main/java/com/wzx/babiq/server/attachment/`：请求模型、文件校验、历史解析、内容加载、
  Tika/OOXML 防护、错误分类、并发身份/路径 reservation 与剪贴板保留清理。
- `backend/src/main/java/com/wzx/babiq/server/context/attachment/`：附件 prompt 边界和 35% context 预算。
- `TurnStartHandler` / `TurnExecutor` / `AgentLoop`：同步准备、不可变输入调度、文档/多模态模型输入。
- `UserMessageItem` 与 context persistence：只持久化附件元数据和 snapshot 计数，不持久化正文/Base64。
- `backend/src/main/resources/application-business-desktop.yml`：业务 Profile 把 persistence mapper 固定为
  `INFO`，阻断 MyBatis DEBUG bind 参数泄漏本机附件路径。
- `backend/src/test/java/com/wzx/babiq/server/application/BusinessAttachmentEndToEndIT.java`：真实认证
  WebSocket、业务身份、dispatcher、TurnExecutor、AgentLoop、两轮稳定引用及数据泄漏回归。

## 3. 协议与安全边界

`turn/start` 允许 `input.text` 为空，但文本与附件不能同时为空。每个新附件只发送：
`id`、`displayId`、`name`、`localPath`；不发送 MIME、大小、SHA、正文、Base64 或 Data URI。
后端不信任客户端名称，重新读取真实文件名、类型、大小与 SHA-256，并把权威元数据放入
`userMessage.attachments`。历史引用只匹配当前业务身份和当前 thread 的 `A-XXXXXX`，再次读取前
重新核对存在性和指纹。

本机路径只允许存在于显式本机附件请求、`UserMessageItem` 本机元数据和本机 SQLite payload。
JSON-RPC 诊断、错误、context snapshot、Provider 文本/media metadata 与普通日志均不得包含路径。
Task 11 的真实 profile RED 发现 MyBatis DEBUG 参数日志会旁路输出 payload；生产配置现已在 mapper
包级别提升至 INFO，而没有降低其余业务诊断级别。

## 4. 稳定错误码

- 输入/限制：`ATTACHMENT_EMPTY`、`ATTACHMENT_LIMIT_EXCEEDED`、
  `ATTACHMENT_FILE_TOO_LARGE`、`ATTACHMENT_TOTAL_TOO_LARGE`。
- 路径/内容：`ATTACHMENT_PATH_INVALID`、`ATTACHMENT_NOT_FOUND`、
  `ATTACHMENT_NOT_REGULAR_FILE`、`ATTACHMENT_TYPE_UNSUPPORTED`、`ATTACHMENT_CHANGED`。
- 解析：`ATTACHMENT_PARSE_FAILED`、`ATTACHMENT_ENCRYPTED`、
  `ATTACHMENT_TEXT_LIMIT_EXCEEDED`、`ATTACHMENT_PARSE_TIMEOUT`、
  `ATTACHMENT_PARSE_OVERLOADED`、`ATTACHMENT_ARCHIVE_UNSAFE`。
- 图片/模型/桌面：`ATTACHMENT_IMAGE_TOO_LARGE`、`ATTACHMENT_MODEL_UNSUPPORTED`、
  `ATTACHMENT_CLIPBOARD_FAILED`。
- 引用：`ATTACHMENT_REFERENCE_AMBIGUOUS`。

协议在 Turn 创建前把附件问题映射为 JSON-RPC `INVALID_PARAMS` + `attachmentCode`；模型阶段失败
沿现有 `turn/failed` 通知返回稳定中文说明，不回显远程正文或异常栈。

## 5. Task 11 TDD 证据

### RED

- 后端 IT 在 business profile 真实执行两轮附件后失败：`Tests run: 1, Failures: 1`。失败原因是
  `ItemMapper.insert` 的 DEBUG bind 参数包含转义后的附件路径，证明日志安全断言能够捕获真实旁路。
- 桌面 IT 首次运行失败：`Tests: 1, Failures: 1`。Compose Shell 被测试夹具强制到超出
  1024×768 根边界，真实点击无法命中；移除错误的强制宽度后使用 Shell 实际响应式布局。
- 最终并行复验又捕获到测试夹具时序：草稿清理先于 fake gateway 延迟发布的历史消息事件，直接断言
  历史 chip 会偶发失败；测试现分别等待草稿清理与历史事件到达，覆盖真实异步顺序且消除假红。

### GREEN

```powershell
cd backend
$env:JAVA_HOME='D:\Program Files\jdk21'
.\mvnw.cmd -Dtest=BusinessAttachmentEndToEndIT test
# Tests run: 1, Failures: 0, Errors: 0, Skipped: 0

cd ..\business-desktop
$env:JAVA_HOME='D:\Program Files\jdk21'
$env:GRADLE_USER_HOME='E:\huitai-work\BaBiQ\.tmp-gradle-review'
.\gradlew.bat :app:test --tests "*BusinessAgentAttachmentWorkflowIT"
# 1 test, BUILD SUCCESSFUL
```

后端 IT 明确证明：one-shot token 被消费、可信 desktop headers 生效、identity bind 先于业务方法、
真实 dispatcher/TurnExecutor/AgentLoop 执行、模型两轮都收到提取正文、第二轮稳定 ID 引用成功；
WebSocket 无 Base64/正文，模型无路径，context snapshot 无路径/正文，日志无 raw/JSON-escaped/URI 路径。

桌面 IT 明确证明：Shell → Panel callback → SendCoordinator → ConversationController → fake gateway 的
真实路径工作；chooser/imageSource 仅作为可注入 OS seam，覆盖选择、移除、纯附件发送、发送失败保留、
成功精确清理、历史消息 chip 和 Ctrl+V 截图，测试期间不打开 JFileChooser 或系统剪贴板。

## 6. 手工烟测

1. 在 IDEA 单独启动 `Business Backend（后端）`，确认控制台出现 WebSocket 监听且无数据库锁错误。
2. 再启动 `Business Frontend（前端）`，登录后展开右侧业务 Agent。
3. 点击附件按钮，选择 TXT/PDF/DOCX/XLSX/PPTX 和受支持图片；确认 chip 只展示
   `A-XXXXXX`、名称、类型和大小，不展示绝对路径。
4. 移除一个附件；其余附件仍在。清空文本，仅保留一个附件并发送；发送成功后草稿清空，
   用户消息继续显示同一稳定 ID。
5. 在输入框按 Ctrl+V 粘贴截图；确认生成单个 PNG chip，发送时 WebSocket envelope 仍小于限制。
6. 下一轮输入“继续分析 A-XXXXXX”；确认后端重新读取并回答。随后修改或删除原文件，再引用应分别
   得到 `ATTACHMENT_CHANGED` 或 `ATTACHMENT_NOT_FOUND` 的安全提示。
7. 检查后端控制台/日志：不应出现附件绝对路径、正文、Base64、SHA 全文或 Provider 原始错误正文。

## 7. 后续注意事项

- 不要把 `application-business-desktop.yml` 的 mapper 日志恢复为 DEBUG；需要 SQL 调试时使用无真实
  业务附件的隔离开发数据。
- 业务桌面当前只恢复当前打开 thread 的附件元数据；没有在本阶段新增独立历史会话页面。
- Office/PDF 只提取文本，不执行宏、脚本或嵌入对象；图片仅作为当前轮瞬时多模态输入。
- Task 12 仍需重新执行计划中的 focused suites、后端 `clean verify`、业务桌面全量测试与前后端分离烟测。
