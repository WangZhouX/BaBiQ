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

2026-07-21 复审补强后，后端 IT 还会保存完整 `Prompt`，逐项检查 instructions text、message metadata、
`UserMessage` media metadata/数据描述和 options 的可文本化表面；第一轮与第二轮 snapshot 都会检查 raw、
JSON escaped、双层 escaped、forward-slash 和 file URI 路径变体。全部 WebSocket frame 会结构化解析并
递归收集敏感文本 JSON Pointer，精确白名单仅允许显式附件请求和 `userMessage` 本机元数据字段。
第三个真实 missing-file 请求确认 `ATTACHMENT_NOT_FOUND`，错误节点与日志不回显缺失路径，且不会调用模型。
这些增强断言在现有生产实现上直接通过，因此本次复审没有伪造 RED，也不需要额外生产代码修复。

桌面 IT 明确证明：Shell → Panel callback → SendCoordinator → ConversationController → fake gateway 的
真实路径工作；chooser/imageSource 仅作为可注入 OS seam，覆盖选择、移除、纯附件发送、发送失败保留、
成功精确清理、历史消息 chip 和 Ctrl+V 截图，测试期间不打开 JFileChooser 或系统剪贴板。
复审补强会对选择文件和截图的 raw、forward-slash、file URI 三类路径逐一做 UI 不可见断言，并在失败
重发前显式等待发送按钮恢复 enabled，不依赖 `startCalls` 或 Compose 隐式 idle。注：该 IT 在测试组合中
接入 chooser/imageSource OS seam，验证的是 Shell/Panel 到 Controller 的回调事务；它不会捕获 `Main` 自身
接线漂移，`Main` composition root 由现有 `BusinessDesktopCompositionRootTest` 等测试另行覆盖。

WebSocket 连接 future 现在有 8 秒硬超时。每次测试生成的 UUID runtime 仅在确认位于本模块 `target` 且
具有专用前缀后做 best-effort 递归清理；SQLite 尚未关闭时登记 `deleteOnExit`，不强关共享 Spring context。
复审运行生成的 runtime 已清除，历史运行遗留目录未被测试代码越权批量删除。

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
- Task 12 已完成计划中的 focused suites、后端 `clean verify`、业务桌面强制全量测试与前后端分离烟测；
  新鲜证据见下一节。

## 8. Task 12 最终验证证据

### 8.1 自动化验证

全部命令均在 2026-07-21 使用 JDK 21 新鲜执行；业务桌面同时固定
`GRADLE_USER_HOME=E:\huitai-work\BaBiQ\.tmp-gradle-review`。

- 后端附件聚焦套件在计划清单上额外包含
  `AttachmentPublicationCleanupRaceTest`、`AttachmentReservationRegistryTest`、
  `WindowsSafeAttachmentDeletionStrategyTest`、`ConversationEventRecorderTest` 和 `TurnExecutorTest`：
  `Tests run: 174, Failures: 0, Errors: 0, Skipped: 3`，`BUILD SUCCESS`，63.361 秒。
- 后端 `.\mvnw.cmd clean verify`：Surefire 200 个报告、1085 个测试，Failsafe 20 个 IT 报告、
  65 个测试；合计 1150 个测试，0 失败、0 错误、3 跳过，`BUILD SUCCESS`，约 248.8 秒。
- 业务桌面 `:agent-client-core:test` 聚焦验证：18 个测试，0 失败、0 错误、0 跳过，5.517 秒。
- 业务桌面 `:app:test` 聚焦验证（含 `BusinessAgentAttachmentWorkflowIT`）：11 个测试类、
  86 个测试，0 失败、0 错误、0 跳过，28.575 秒。
- 业务桌面 `.\gradlew.bat test --rerun-tasks`：42/42 actionable tasks 实际执行；7 个模块、
  92 个测试报告、797 个测试，0 失败、0 错误、0 跳过，`BUILD SUCCESSFUL`，119.790 秒。

### 8.2 前后端分离烟测

烟测只使用 `backend/target/smoke` 下的隔离 HOME、临时数据库、密钥、日志与测试附件，没有访问用户
真实业务数据。先单独运行 `:app:runBusinessBackendDevelopment`，再单独运行
`:app:runBusinessFrontendDevelopment`，没有使用组合启动任务。后端在 11.324 秒启动并监听
`127.0.0.1:49391`；前端窗口标题为“汇泰业务桌面 Agent”，窗口响应正常且与后端保持已建立连接。

独立命令行启动前端时，除了任务自身注入的 `HUITAI_DESKTOP_EXTERNAL_BACKEND=1`，隔离演示环境还必须
显式设置 `HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY=1`；IDEA 的 `Business Frontend（前端）` 配置已经包含
该变量。正确启动后，`application/identity/bind`、`application/catalog/register` 和 `provider/list`
均真实成功。Skiko 的 DirectX 12 初始化失败后自动回退到下一渲染 API，窗口仍正常显示和响应，不是启动失败。

人工操作结果：

- 选择 279 B 的 TXT 成功，chip 为 `A-MPPDFS`，只显示稳定 ID、文本类型和大小；移除成功。
- Ctrl+V 粘贴真实截图成功，生成 `A-UA6SYY`，PNG 原子落入隔离受控 clipboard 目录。
- 选择 1.1 MiB PNG 成功，chip 为 `A-QZFE2U`，不显示原始路径。
- 仅附件发送被后端接受：`turn/start` 准备 2 个附件、总计 2,514,479 B；历史用户消息显示同一组
  `A-UA6SYY` / `A-QZFE2U` chip。隔离 HOME 没有真实 Provider 凭据，因此模型阶段以安全提示失败、
  token 为 0；文档正文装配由 `BusinessAttachmentEndToEndIT` 的确定性捕获模型桩覆盖，图片 media
  装配由 `AgentLoopAttachmentTest` 覆盖，本次人工烟测不把“无凭据”误报为模型理解成功。
- 文本加 TXT 发送时，`A-8F2RFE` 被准备为 1 个附件、279 B。下一轮只发送文本引用 `A-8F2RFE`，
  入站 `attachments=0`，历史解析仍准备出 1 个附件、279 B、`[A-8F2RFE]`。
- 把原 TXT 安全改名后再次引用，UI 显示“附件已不存在，请重新选择后再发送”，草稿保留；后端返回
  `ATTACHMENT_NOT_FOUND`，并在调用模型前结束。

### 8.3 日志隐私与清理

停止进程后扫描烟测产生的 8 个日志文件：原 TXT 路径、改名后 TXT 路径、原 PNG 路径和受控截图路径的
反斜杠、正斜杠、file URI、JSON 转义和 URL 编码变体均为 0 命中；`data:image`、`base64,` 和连续
512 字符以上 Base64-like 串均为 0；`SQLITE_BUSY` / `database is locked` 均为 0。诊断中的本机路径
使用 `<local-path-redacted>`，共命中 10 次。

烟测后前端窗口已关闭，后端及 Gradle 启动链已退出，49391 端口不再监听，相关进程计数为 0；删除前将
目标解析为 `E:\huitai-work\BaBiQ\backend\target\smoke` 并确认严格位于
`E:\huitai-work\BaBiQ\backend\target` 下，随后清理整个隔离目录并确认不存在。
