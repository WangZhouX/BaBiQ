# Business Backend 残留开发会话恢复设计

## 问题

IDEA 停止 `Business Backend（后端）` 时可能直接终止 Gradle/Java 进程树，导致 JVM shutdown hook 没有完成 `development-session.json` 删除。旧 runner 只判断文件是否存在，因此即使后端已经退出、49391 端口已经释放，后续启动仍误报已有后端并以 exit code 1 结束。

真实故障证据来自 Gradle daemon 日志：`BusinessBackendDevelopmentRunner.kt:53` 抛出 `development backend session already exists; stop the previous Business Backend run`；同时 49391 无监听进程，但 `development-session.json` 仍存在。

## 方案比较

1. **无条件删除会话文件**：简单，但可能删除仍在运行的真实后端会话，拒绝。
2. **按文件时间或 PID 判断**：TTL 会误判长时间运行的后端；PID 会复用且需要扩展协议，拒绝。
3. **跨进程所有权锁 + 认证探测 + 端口空闲双重确认**：新 runner 首先取得操作系统文件锁；旧版无锁会话则继续用会话中的一次性身份探测真实 WebSocket，探测失败后还必须确认固定 loopback 端口可绑定，两个条件同时成立才回收会话文件。采用此方案。

## 设计

- `BusinessBackendDevelopmentRunner.start()` 在读取或回收开发会话前先获取 `development-session.json.lock` 的独占 OS 文件锁，并将锁持有到 child 后端结束。
- IDEA 强制终止进程时，操作系统会自动释放文件锁；`.lock` 文件本身可以保留，其存在不代表后端存活。
- 第二个新 runner 无法获得锁时，立即以固定的“后端已运行”错误退出，不读取或删除现有会话。
- 对升级前留下、没有持有新锁的会话继续执行存活确认：
  - 会话可被真实认证：保留文件并拒绝重复启动。
  - 会话无法认证但端口仍被占用：保留文件并报告端口占用，避免误删活跃或未知服务。
  - 会话无法认证且端口空闲：在独占锁内验证会话文件身份与有界 SHA-256 指纹未变化，再删除并继续启动。
  - 会话文件损坏但端口空闲：允许回收；符号链接、reparse point 或探测期间被替换仍快速失败。
- 新会话发布、正常关闭时的 lease 删除和异常残留回收都要求持有同一所有权锁，关闭了 runner/lease 之间“检查后被替换再删除”的竞态窗口。
- KeyStore 初始化与 child 启动也在锁获取之后，避免重复启动造成无关本机状态变更。
- 诊断只输出固定错误，不记录会话路径、token、指纹或原始认证异常。
- 安装包的 embedded 模式不走该开发会话协议，不受影响。

## 测试

- 残留有效会话、认证失败、端口空闲时自动回收并发布新会话。
- 新 runner 持有所有权锁时拒绝第二个 runner，且不调用 child launcher。
- 升级前无锁会话仍可认证时拒绝重复启动。
- 认证失败但端口占用时拒绝启动并保留会话。
- 损坏会话且端口空闲时回收；文件被替换时拒绝删除。
- 旧 lease 失去所有权后不能删除新 owner 发布的会话。
- 真实执行“启动 → 强制终止 runner/child → 保留会话和 lock 文件 → 再启动”，确认 49391 重新监听并重新发布会话。
- 运行 app runtime/composition 聚焦测试和 `business-desktop` 全量测试。
