# 业务 Agent 本机附件设计

> 日期：2026-07-20
> 状态：已获用户确认，待实现
> 目标仓库：`E:\huitai-work\BaBiQ`

## 1. 背景

业务桌面右侧 Agent 输入区目前只能发送非空文本，无法选择本机资料，也无法使用 `Ctrl+V` 粘贴截图。业务资料场景需要同时支持：

- 图片；
- 文本和常见代码文件；
- PDF；
- Word；
- Excel；
- PowerPoint。

现有 `turn/start` 通过最大 256 KiB 的 WebSocket JSON-RPC envelope 发送 `input.text`。把文件或截图 Base64 直接塞进该 envelope 会迅速超过限制，也会增加协议日志、内存和重试成本。

本设计参考 Codex 的本机输入语义：桌面和本机 Agent 之间传递文件路径；剪贴板图片先落成临时 PNG；图片只在模型请求序列化阶段转换为多模态内容。选中的现有文件不做无条件副本，保持“直接读取本机文件”的产品语义。

## 2. 目标

1. 右侧输入区提供多文件选择入口。
2. 输入区聚焦时，`Ctrl+V` 可以粘贴剪贴板截图。
3. 发送前和发送后都显示稳定、可引用的附件标识、文件名、类型和大小。
4. 允许只发送附件，也允许文本与附件一起发送。
5. 现有本机文件由后端按绝对路径直接读取；桌面端到本机后端的 WebSocket 不传 Base64。
6. 剪贴板截图保存到业务桌面受控附件目录，再按本机路径读取。
7. 图片以 Spring AI 多模态 `Media` 进入模型；PDF、Office、文本和代码在本机提取成有界文本。
8. 附件元数据随用户消息持久化，后续轮次可以用附件标识再次引用。
9. 文件缺失、变化、类型不支持、超限、解析失败和模型不支持图片时提供清晰且不泄密的错误。

## 3. 非目标

- 不把任意文件上传到公网文件服务或第三方对象存储。用户选定的图片字节和文档提取文本会作为模型输入发送给当前配置的远程 Provider；这是附件功能的必要数据披露边界。
- 不在首版实现 OCR；图片理解依赖所选模型的视觉能力。
- 不执行 Office 宏、脚本、嵌入对象或压缩包内的内容。
- 不编辑用户原文件。
- 不实现拖拽上传；首版入口为文件选择和 `Ctrl+V`。
- 不把完整附件内容写入 SQLite、普通日志、审计字段或聊天消息 JSON。
- 不为 Provider 建立人工维护的“支持视觉”白名单；实际能力以模型请求结果为准。
- 不在本阶段为业务桌面新增最近会话、thread 列表或重启后的历史恢复 UI；附件元数据遵循现有后端 item 持久化，业务桌面只保证当前打开 thread 内的展示和引用。

## 4. 方案选择

### 4.1 采用：Codex 风格本机路径混合方案

- 用户选择的现有文件：桌面端提交绝对路径和最小描述，后端直接读取。
- 剪贴板截图：桌面端编码为 PNG，写入业务桌面受控目录，再提交该路径。
- 本地协议：桌面端到本机后端只携带附件描述，不携带文件字节或 Base64。
- 模型请求：图片在后端转换为 Spring AI `Media`；文档内容由本地解析器提取后加入本轮上下文。

该方案符合本机桌面的运行方式，并避开 256 KiB WebSocket envelope 限制。

### 4.1.1 数据披露边界

附件始终保留在本机文件系统，除非进入当前用户明确选择的模型 Provider 请求：

- 本机 WebSocket 只传路径和元数据；
- 图片在后端读取后，由 Spring AI 在远程模型 HTTP 请求中编码为模型支持的媒体表示；
- 文档、表格、演示和文本文件在本机解析，只有有界提取文本进入远程模型请求；
- 绝对路径、SHA-256、文件系统权限和 Tika 元数据不进入模型输入；
- 若用户选择本地模型 Provider，则相同模型输入仍留在本机。

因此“WebSocket 不传 Base64”仅指业务桌面到本机后端链路，不表示远程视觉模型无需接收图片内容。

### 4.2 未采用：WebSocket 内联 Base64

实现直观，但截图和 Office 文件会超过现有 envelope 限制，并放大日志、内存、重试和脱敏风险。

### 4.3 未采用：所有文件先复制进附件仓库

可以获得最稳定的历史快照，但会重复占用空间，并偏离用户明确要求的“直接读取本机文件”。首版只为没有原始路径的剪贴板截图生成受控文件。

## 5. 总体架构

附件能力拆分为五个边界清晰的单元：

1. **桌面草稿附件层**：文件选择、剪贴板读取、附件标签、移除和发送状态。
2. **附件协议层**：`turn/start.input.attachments` 的 Kotlin/Java 契约及安全序列化。
3. **后端附件准备层**：路径校验、类型识别、大小限制、指纹计算、历史引用解析。
4. **模型输入装配层**：图片转换为 `Media`，文档转换为有界不可信文本段。
5. **消息与历史层**：在 `userMessage` 中持久化和回放附件元数据，不持久化附件正文或 Base64。

每个单元通过不可变模型传递数据，避免 Compose UI、JSON-RPC handler、文件解析和 AgentLoop 相互耦合。

## 6. 桌面交互

### 6.1 输入区

`AgentComposer` 从单行布局升级为：

- 上方附件标签区；
- 下方附件按钮、文本输入框和发送按钮。

附件按钮打开支持多选的本机文件选择器。选择器过滤首版支持的扩展名，但后端仍做权威校验。

发送按钮在以下任一条件满足时启用：

- 文本非空；
- 至少有一个附件。

### 6.2 `Ctrl+V` 截图

输入框获得焦点后监听粘贴事件：

1. 剪贴板包含图片时，优先读取图片。
2. 图片统一编码为 PNG。
3. 文件名使用 `截图-YYYYMMDD-HHmmss-<短ID>.png`。
4. 文件写入业务桌面运行目录下的 `attachments/clipboard/`。
5. 草稿中加入普通附件标签。
6. 剪贴板不含图片时，保持 Compose 文本框原有文本粘贴行为。

截图写入失败时显示安全错误，不吞掉异常，也不清空当前草稿。

受控目录固定为 `BusinessDesktopRuntimePaths.runtimeDir/attachments/clipboard/`。业务桌面和内置后端必须共享同一个 runtime root；开发模式通过现有 development session 文件传递同一绝对 runtime root。截图使用同目录临时文件写入，关闭输出流后再以 `ATOMIC_MOVE` 重命名为最终 PNG；文件权限在 POSIX 上为 owner-only，在 Windows 上继承当前用户 profile/runtime 目录 ACL。

### 6.3 附件标识

每个草稿附件由桌面端生成 UUID，并派生稳定的短显示标识：

```text
附件 A-7K3M2Q · 合同.pdf · 2.3 MB
附件 A-91CD4F · 截图-20260720-143012-91CD4F.png · 486 KB
```

完整 UUID 用于协议和持久化；短标识用于 UI 和用户后续引用。短标识在同一 thread 内必须唯一。

附件标签支持单独移除。发送成功后，用户消息卡继续显示相同标识；发送失败时草稿附件和文本都保留。

### 6.4 发送一致性

桌面端不能在点击发送时立即清空草稿。只有 `turn/start` 返回成功后才同时清空文本和附件。若创建 thread 或启动 turn 失败，保留原草稿供用户重试。

## 7. 协议

`turn/start` 扩展为：

```json
{
  "threadId": "thread-id",
  "input": {
    "text": "请核对这份合同",
    "attachments": [
      {
        "id": "5e4d4e7a-...",
        "displayId": "A-7K3M2Q",
        "name": "合同.pdf",
        "localPath": "D:\\客户资料\\合同.pdf"
      }
    ]
  },
  "providerId": "oneapi-relay"
}
```

约束：

- `text` 可以为空，但 `text` 和 `attachments` 不能同时为空。
- `id` 必须是 UUID。
- `displayId` 必须满足受控格式，并在当前 thread 历史中唯一。
- `name` 仅用于显示；后端以真实路径文件名和内容检测结果为准。
- `localPath` 必须是绝对路径。
- 请求不接受字节、Base64、Data URI 或远程 URL。
- 附件描述总大小继续受现有 JSON-RPC envelope 限制。
- `localPath` 最大 4,096 个字符，`name` 最大 255 个 Unicode 字符。
- `displayId` 固定为 `A-` 加 6 位大写 Base32 字符，不使用易混淆的 `0/O/1/I`。

`turn/start` 仍返回 `turnId`。附件 ID 由客户端预先确定，因此无须增加单独的上传握手。

## 8. 文件支持与限制

### 8.1 首版支持

- 图片：PNG、JPEG、WebP、GIF；
- 文本：TXT、Markdown、CSV、JSON、XML、YAML、日志；
- 常见代码：Java、Kotlin、JavaScript、TypeScript、Python、SQL、Shell、PowerShell、HTML、CSS 及其他可安全识别为文本的文件；
- PDF；
- Word：DOC、DOCX；
- Excel：XLS、XLSX；
- PowerPoint：PPT、PPTX。

### 8.2 默认限制

- 单轮最多 8 个附件；
- 单文件最多 20 MiB；
- 单轮附件总大小最多 50 MiB；
- 单文档最多提取 100,000 个字符；
- 单轮文档提取总量最多 250,000 个字符；
- 文件名和显示标识使用固定长度上限；
- 图片单边最大 16,384 像素、总像素最大 50,000,000，防止小体积超大画布造成内存压力。

这些限制集中配置，并在桌面端做友好预检、后端做权威校验。

## 9. 后端安全准备

后端在创建 Turn 并提交 `TurnExecutor` 前同步完成附件准备：

1. 解析并规范化绝对路径。
2. 拒绝不存在、非普通文件和符号链接。
3. 读取真实文件大小并执行单文件、数量和总量限制。
4. 通过文件内容和扩展名共同识别媒体类型。
5. 计算 SHA-256 指纹。
6. 生成不可变 `PreparedAttachment` 元数据。
7. 检查当前 thread 中 `id` 和 `displayId` 是否冲突。

后端不信任客户端提供的文件名、类型和大小。日志只记录 threadId、turnId、附件 ID、类型和大小，不记录绝对路径、文件正文或哈希全文。

异步执行前再次确认文件存在；读取后核对大小和 SHA-256。如果从提交到读取期间文件发生变化，本轮以 `ATTACHMENT_CHANGED` 失败，不静默处理变化后的内容。

所有 JSON-RPC 参数摘要和协议诊断在序列化前必须把 `input.attachments[*].localPath` 替换为 `<local-path-redacted>`。该规则接入公共 JSON-RPC 日志摘要，不能只依赖 `TurnStartHandler` 自己不打印路径。当前仓库的应用动作审计链不接收 `turn/start` 附件参数，因此没有附件承载型业务审计序列化入口；若以后新增此类入口，必须复用同一结构化附件诊断脱敏器后才能落审计。

## 10. 文档解析

后端使用 Apache Tika 3.x：

- `AutoDetectParser` 负责内容类型识别和标准格式解析；
- `BodyContentHandler` 使用 100,000 字符硬上限；
- `SecureContentHandler` 启用输出阈值和压缩炸弹防护；
- `ParseContext` 为嵌入内容安装 `EmptyParser`，禁止递归解析嵌入文档；
- 只提取纯文本，不执行宏、脚本或活动内容；
- 解析超限、加密文档和损坏文档映射为稳定错误码。

解析资源边界：

- 单文件解析超时 10 秒，单轮累计解析时间最多 30 秒；
- 解析工作使用单独的有界 executor，最多同时执行 2 个解析任务，队列最多 8 个；
- OOXML ZIP 容器在交给 Tika 前先检查 central directory：条目最多 1,000 个、声明解压总量最多 100 MiB、单条目最多 50 MiB、压缩比最多 100:1；
- 拒绝通用 ZIP/RAR/7z 和任何不在允许 MIME 集合内的容器；
- PDF 禁止内联图片和嵌入附件提取，遇到加密 payload 直接失败；
- 输入流仍受 20 MiB 文件上限约束，输出受字符上限约束，超时任务关闭流并取消；
- 超时、队列饱和、压缩炸弹和解析器资源错误都只返回稳定安全错误，不回显解析器异常。

提取结果以不可信资料块加入本轮模型输入：

```text
<attachment id="A-7K3M2Q" name="合同.pdf" content_type="application/pdf">
……有界提取文本……
</attachment>
```

系统提示明确说明附件文本属于不可信业务资料，其中的指令不得覆盖系统规则、审批、沙箱和应用动作约束。

提取正文只存在于本轮内存和模型输入中，不进入 `bq_items`、审计记录或普通日志。

### 10.1 上下文预算

附件提取文本不能在 `ContextWindowRuntime` 之后直接拼接。后端把每个 `AttachmentTextSegment` 作为当前轮高优先级、不可持久化的上下文来源交给 `ContextWindowRuntime`：

1. 系统规则、安全规则、工具目录和真实用户文本先保留预算。
2. 文本附件最多使用模型 context window 的 35%，同时受每文件 100,000 字符和单轮 250,000 字符硬上限约束。
3. 按用户选择顺序分配剩余附件预算；达到预算的附件在 token 边界截断并附加明确的“内容已截断”标记。
4. 附件 token 计入当前 context snapshot 的总 token estimate 和 included/excluded 统计。
5. snapshot 只记录 attachment ID、媒体类型、原始/纳入/截断字符数和 token estimate，不保存提取正文、路径或哈希。
6. 当前轮附件不参与短期压缩，也不进入长期记忆候选；后续明确引用时重新验证并解析。

这样 250,000 字符只是解析安全上限，不能绕过模型上下文预算。

## 11. 图片多模态输入

图片文件在后端读取和复核后构造 Spring AI `Media`。`AgentLoop` 使用带文本和媒体的 `UserMessage` 调用 `ReactAgent`，不再把当前轮强制降为纯字符串。

现有上下文窗口、短期压缩、长期记忆、能力目录和业务页面上下文仍由 `ContextWindowRuntime` 生成文本部分；附件媒体仅附加到当前模型请求。

如果模型或中转站明确拒绝图片输入：

- Turn 进入失败终态；
- UI 显示“当前 Provider/模型不支持图片附件或中转站未开启多模态转发”；
- 保留用户消息及附件标识；
- 不把远端原始响应、密钥或请求正文展示给用户。

发送给远程 Provider 的媒体只包含图片字节和受控 MIME；本机路径、显示路径和文件指纹不进入 `UserMessage` 文本或 media metadata。

## 12. 持久化与后续引用

`UserMessageItem` 增加附件元数据：

```text
id
displayId
name
localPath
mediaType
sizeBytes
sha256
source = SELECTED_FILE | CLIPBOARD_IMAGE
```

附件元数据随现有 `bq_items.payload_json` 保存，不新增二进制列，也不保存提取正文或 Base64。`localPath` 只保存在本机 SQLite，不注入模型文本和普通日志。

当前轮新附件始终参与模型输入。历史引用只识别大小写不敏感的独立 token，语法为 `A-XXXXXX`，正则边界为 `(?<![A-Z0-9])A-[A-HJ-NP-Z2-9]{6}(?![A-Z0-9])`。后端把 token 规范化为大写，并只在当前 thread 的 `UserMessageItem.attachments` 中做精确匹配。相同 token 在同一输入中去重；未知 token 不猜测文件名，也不扫描其他 thread。

重新引用时：

- 文件存在且 SHA-256 一致：正常使用；
- 文件缺失：显示“附件 A-7K3M2Q 已不可用”；
- 文件发生变化：显示“附件 A-7K3M2Q 自发送后已变化，请重新选择”；
- 其他 thread 的附件 ID：按不存在处理，防止跨 thread 引用。

附件可用性不是持久化状态。后端只持久化“发送时已验证”的不可变元数据；只有用户明确引用时才重新计算存在性和指纹，避免历史加载触发任意文件读取。业务桌面当前打开 thread 的消息使用 item 事件直接渲染附件标签。本阶段不新增业务桌面 thread 列表或重启恢复入口；现有后端 `thread/load` 对扩展后的 `UserMessageItem` 保持 JSON round-trip 兼容，为以后接入业务历史页保留数据。

### 12.1 剪贴板文件保留

- 未成功发送或已从草稿移除的截图属于 orphan；启动时和每 6 小时清理超过 24 小时的 orphan。
- 已被 `UserMessageItem` 引用的截图在 thread 未归档期间保留。
- thread 归档 30 天后可以删除对应截图，附件元数据继续保留，后续引用返回 `ATTACHMENT_NOT_FOUND`。
- 受控截图目录默认总上限 1 GiB；达到上限时先删除过期 orphan 和已超过保留期的归档截图，仍不足则拒绝新截图并显示安全错误。
- 清理器只删除规范化后仍位于 `runtimeDir/attachments/clipboard/` 内、且名称符合本应用格式的普通文件。

## 13. 错误模型

附件错误使用稳定代码和安全中文说明：

```text
ATTACHMENT_EMPTY
ATTACHMENT_LIMIT_EXCEEDED
ATTACHMENT_FILE_TOO_LARGE
ATTACHMENT_TOTAL_TOO_LARGE
ATTACHMENT_PATH_INVALID
ATTACHMENT_NOT_FOUND
ATTACHMENT_NOT_REGULAR_FILE
ATTACHMENT_TYPE_UNSUPPORTED
ATTACHMENT_CHANGED
ATTACHMENT_PARSE_FAILED
ATTACHMENT_ENCRYPTED
ATTACHMENT_TEXT_LIMIT_EXCEEDED
ATTACHMENT_IMAGE_TOO_LARGE
ATTACHMENT_MODEL_UNSUPPORTED
ATTACHMENT_CLIPBOARD_FAILED
ATTACHMENT_PARSE_TIMEOUT
ATTACHMENT_PARSE_OVERLOADED
ATTACHMENT_ARCHIVE_UNSAFE
ATTACHMENT_REFERENCE_AMBIGUOUS
```

Turn 创建前发现的参数和文件问题返回 JSON-RPC `INVALID_PARAMS` 加稳定附件错误摘要。模型调用阶段的问题通过现有 Turn 失败事件进入 UI。

错误信息不得包含：

- 完整绝对路径；
- 文档正文；
- Base64；
- API Key、Token 或远端响应正文；
- Java/Kotlin 异常堆栈。

## 14. 主要代码边界

### 14.1 业务桌面

- `agent-client-core`：附件请求模型、用户消息附件模型和 JSON codec。
- `app/runtime`：受控剪贴板附件路径、PNG 原子写入和 runtime root 配置。
- `app/ui/agent`：文件选择、附件标签、粘贴处理和消息展示。
- `BusinessConversationController`：携带不可变附件列表启动 Turn。
- `Main`：持有文本和附件草稿，成功后原子清空。

### 14.2 后端

- `attachment` 领域包：请求模型、验证策略、指纹、Tika 解析、历史引用、context-budget segment、解析 executor、压缩容器预检和截图保留策略。
- `TurnStartHandler`：解析附件、执行业务 identity/thread 校验、同步准备附件后提交执行器。
- `TurnExecutor`：传递不可变 `PreparedTurnInput`，避免参数继续膨胀。
- `AgentLoop`：发射带附件元数据的用户消息，并构造文本加媒体的模型输入。
- `ContextAssembler`：历史中只加入附件标识和文件名，不加入路径、哈希或正文。
- `ContextWindowRuntime`：把当前轮附件 segment 纳入 token 预算和 snapshot 统计，但不持久化附件正文。
- `thread/load`：保持扩展 `UserMessageItem` 的元数据 round-trip；不在本阶段新增业务桌面历史导航。

## 15. 测试策略

### 15.1 桌面测试

- 文件选择结果转换为草稿附件；
- 多选、数量限制、类型过滤和重复文件处理；
- `Ctrl+V` 图片生成唯一 PNG；
- 非图片剪贴板保持文本粘贴；
- 附件标签显示 ID、名称和大小；
- 单个附件移除；
- 只有附件时发送按钮可用；
- `turn/start` 成功后清空、失败后保留草稿；
- 当前 thread 的 `userMessage` item 事件显示附件标签；
- JSON-RPC 请求不含 Base64 和文件字节。

### 15.2 后端测试

- `turn/start` 接受文本、附件或两者，拒绝两者都空；
- 路径缺失、相对路径、目录、符号链接、超限和不支持类型；
- 客户端伪造名称、类型和大小不影响权威检测；
- SHA-256 指纹与发送后变化检测；
- thread 内 ID 冲突和跨 thread 引用隔离；
- Tika 提取 TXT、PDF、DOCX、XLSX、PPTX 的有界文本；
- 加密、损坏、嵌入对象、危险压缩比、解析超时、队列饱和和提取上限；
- 图片构造 Spring AI `Media`，不进入 WebSocket Base64；
- `UserMessageItem` 元数据 round-trip 和历史加载；
- 附件文本受模型 35% 预算限制，snapshot 只有计数和 token estimate；
- ContextAssembler 不泄露本机路径、哈希和附件正文；
- JSON-RPC 摘要、日志与错误响应不泄露路径或内容。

### 15.3 集成和回归

- 真实业务 profile 下，通过认证 WebSocket 发送本机文档并收到 Agent 回答；
- 发送截图给视觉模型；
- 使用不支持图片的 Provider 得到清晰失败；
- 文件删除和变化后的后续引用；
- 精确 `A-XXXXXX` 引用、未知 ID 和跨 thread 隔离；
- orphan 截图、归档保留和目录容量清理；
- 现有纯文本对话、Provider 设置、业务动作、审批、运行记录和身份隔离保持通过。

## 16. 验收标准

1. 用户可以从右侧输入框选择一个或多个受支持文件。
2. 用户可以用 `Ctrl+V` 粘贴截图，并看到附件标签。
3. 每个附件在草稿和用户消息中都有稳定可引用的显示 ID。
4. 文本为空时仍可发送附件。
5. WebSocket 请求中不存在文件 Base64 或原始字节。
6. 后端直接读取选中的本机文件；剪贴板截图读取受控 PNG。
7. 图片进入真实多模态模型输入，PDF/Office/文本进入有界本地解析输入。
8. 用户可以在当前打开 thread 的后续轮次通过精确显示 ID 引用附件；本阶段不新增业务桌面重启历史页。
9. 文件删除、变化、超限、解析失败和模型不支持图片都有明确错误。
10. SQLite 和日志不保存附件正文或 Base64；普通日志和 JSON-RPC 摘要不记录绝对路径。
11. 相关定向测试、后端 `clean verify` 和业务桌面全量测试通过。
