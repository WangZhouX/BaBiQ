# Business Desktop 独立前后端开发运行设计

## 目标

在 IDEA 开发态提供两个真正独立的运行入口：

- `Business Backend`：单独启动业务 Spring Boot 后端，日志直接输出到自己的 Run 控制台。
- `Business Frontend`：只启动 Compose 前端并连接已运行的业务后端，绝不创建后端子进程。

正式安装包继续使用现有“桌面父进程 + 内置随机端口后端子进程”模式，不改变生产安全边界。

## 根因

现有 `:app:run` 启动 `MainKt` 后，由 `ProductionBusinessDesktopCompositionFactory` 创建
`BusinessAgentLaunchRequest` 和 `BusinessAgentProcessLauncher`。后端端口随机分配，认证令牌由父进程
在内存中持有，子进程 stdout/stderr 被追加到 `backend.log`。因此仅拆 IDEA XML 无法得到独立前后端：
前端仍会启动子进程；手工启动的后端也无法取得前端生成的一次性令牌和后端 JCEKS 主密码。

截图中的运行任务已经退出，当前系统中只有 Gradle daemon，没有 Compose 或 `babiq-server` 进程；
`Backend Log` 标签展示的是历史日志，所以看起来像“点击启动没有效果”。

## 方案

### 1. 开发后端托管器

在 `business-desktop/app` 增加开发后端入口。它负责：

1. 从桌面 JCEKS 读取现有后端 JCEKS 主密码，保持 Provider/API Key 数据不变。
2. 创建新的一次性业务桌面会话令牌和固定 loopback 开发端口。
3. 启动现有 `babiq-server` JAR，但把 stdout/stderr 继承到当前控制台。
4. 后端认证就绪后写入 owner-only 开发会话描述文件。
5. Run 配置停止时终止后端并删除会话描述文件。

开发后端仍使用 `business-desktop` profile、业务单实例锁、WebSocket Bearer 认证、loopback 地址和原隔离目录。

### 2. 外部后端前端模式

前端开发任务设置明确的 external-backend 模式。Composition factory 在该模式下：

1. 读取并严格校验开发会话描述文件。
2. 使用其中的 URL、desktop instance/session ID、Bearer token 和 Origin 建立连接。
3. 不解析 bundled backend JAR、不创建 `ProcessBuilder`、不拥有后端生命周期。
4. 文件缺失、损坏、非 loopback 或认证失败时直接给出明确启动错误。

默认生产模式继续走现有 embedded child launcher。

### 3. IDEA 入口

- `Business Backend` → `:app:runBusinessBackendDevelopment`
- `Business Frontend` → `:app:runBusinessFrontendDevelopment`

删除之前的 `Business Desktop（前端 + 内置后端）` 本地配置。两个入口均使用 JDK 21；后端控制台直接显示
Spring Boot 日志，前端控制台只显示 Gradle/Compose 日志。

## 安全与失败语义

- 开发会话文件只位于本机隔离目录，使用 owner-only 权限，内容和路径不进入日志。
- 会话文件只在后端存活期间存在；每次后端重启生成全新 session ID 和 256-bit token。
- external 模式必须显式开启，正式 `MainKt` 默认行为不变。
- 前端不得在 external 模式失败后回退到内置后端。
- 端口占用、业务后端单实例锁冲突、KeyStore 密码错误和会话文件缺失都必须快速失败。

## 验收

1. 两个 IDEA 配置可分别启动和停止。
2. 只启动后端时无 Compose 窗口，后端 Run 控制台可看到 Spring Boot 日志。
3. 再启动前端时出现 Compose 窗口，且系统中只有一个 `babiq-server` 进程。
4. 停止前端不停止后端；重新启动前端可再次连接。
5. 停止后端后前端进入断连状态；重新启动后端后可手动重连。
6. 原 `:app:run` 和打包模式仍使用内置后端。

