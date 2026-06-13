# 学习路线

这条路线分为三层：入门导览、专题深挖、项目实战。入门导览负责让初学者进门，专题深挖负责系统掌握，项目实战负责把知识变成贡献能力。

## 阶段 0: 入门导览

目标：知道每个技术在 BaBiQ 中负责什么，并能读懂桌面端主链路的轮廓。

| 课次 | 主题 | 读完后能做到 |
| --- | --- | --- |
| 0 | 认识 BaBiQ 桌面端技术栈 | 能说清 Kotlin、Compose、Ktor、协程、serialization 的分工 |
| 1 | Kotlin 最小语法包 | 能读懂最常见的 Kotlin 语法 |
| 2 | Kotlin 数据建模 | 能看懂协议模型和 UI 状态模型 |
| 3 | Gradle 和项目结构 | 能找到源码、测试、依赖和运行命令 |
| 4 | JSON 与 kotlinx.serialization | 能理解 JSON 如何变成 Kotlin 对象 |
| 5 | 协程与 StateFlow | 能理解异步任务和状态流为什么不会阻塞 UI |
| 6 | Compose Desktop 启动课 | 能看懂最外层 UI 是如何由状态驱动的 |
| 7 | Ktor WebSocket 启动课 | 能看懂桌面端如何连接后端并收发 JSON-RPC |

## 阶段 1: Compose Desktop 完整专题

目标：学完后能独立维护 BaBiQ 的桌面 UI，而不是只会写几个简单控件。

- 声明式 UI 与命令式 UI 的差异
- `@Composable`、重组和函数式 UI
- `Column`、`Row`、`Box`、`LazyColumn` 布局
- `Modifier` 系统
- Material3 组件体系
- `remember`、`mutableStateOf` 和本地状态
- 状态提升与单向数据流
- `StateFlow` 接入 Compose
- BaBiQ 的 `BaBiQDesktopApp -> AppShell -> ChatScreen`
- 聊天列表、输入框、侧边栏、右侧面板
- 弹窗、菜单、下拉框、popover
- 主题、颜色、字体、间距
- `LaunchedEffect`、`DisposableEffect`
- 复杂交互：拖拽、缩放、画布
- 桌面端特有能力：窗口、资源、剪贴板、文件选择
- UI 测试
- 重组性能和常见坑
- 打包和 native distribution
- 实战：修改一个 BaBiQ 面板
- 实战：从零做一个小型 Compose 桌面工具

## 阶段 2: Ktor WebSocket 完整专题

目标：学完后能维护 BaBiQ 的连接、重连、事件流、请求响应匹配和测试。

- Ktor Client 的位置和职责
- WebSocket 协议基础
- 为什么 BaBiQ 使用 WebSocket + JSON-RPC
- CIO engine 的作用
- WebSockets plugin
- `webSocketSession`
- 发送文本消息
- 接收服务端事件流
- session 生命周期
- ping、timeout、max frame size
- 协程里的读写模型
- `id -> CompletableDeferred` 请求响应匹配
- response 与 notification 分流
- JSON-RPC request、response、error
- 断线重连与退避策略
- 取消、超时、失败恢复
- 连接状态如何反馈给 UI
- fake transport 测试
- 精读 `KtorAgentTransport` 和 `AgentClient`
- 实战：新增一个 JSON-RPC 方法并接到桌面端

## 阶段 3: Kotlin 协程与状态专题

目标：能解释 BaBiQ 桌面端为什么选择 `StateFlow + immutable AppState + reducer`。

- `suspend fun`
- `CoroutineScope`
- `launch`、`async`、`withContext`
- `Dispatchers.Default`、`Dispatchers.IO`
- `SupervisorJob`
- 结构化并发
- `MutableStateFlow` 和 `StateFlow`
- reducer 与纯函数
- `copy(...)` 更新不可变状态
- 测试异步状态变化

## 阶段 4: BaBiQ 项目实战

目标：从读源码走向真实贡献。

- 新增一个协议字段
- 新增一个设置项
- 新增一个右侧面板区域
- 新增一个运行详情展示
- 新增一个 JSON-RPC 方法的桌面端调用
- 为新功能补测试
- 跑桌面端验证命令
- 写文档和 handoff

