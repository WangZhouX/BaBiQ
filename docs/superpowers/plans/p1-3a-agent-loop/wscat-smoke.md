# P1-3a wscat 烟测脚本

## 前置
1. 启动后端：
```powershell
cd backend
.\mvnw.cmd spring-boot:run
```
2. 看到应用启动完成后，另开终端：
```powershell
wscat -c ws://localhost:8080/ws/agent
```

## 场景 A: 读 README 并总结
请求：
```json
{"jsonrpc":"2.0","id":1,"method":"thread/create","params":{"cwd":"F:/wwwxxxx/BaBiQ"}}
```

继续发送：
```json
{"jsonrpc":"2.0","id":2,"method":"turn/start","params":{"threadId":"thr_xxx","input":{"type":"text","text":"读取 README.md 并总结"}}}
```

期望：
- `turn/started`
- `item/added` 用户消息
- `item/added` 工具执行项或读取项
- `item/added` agentMessage
- `turn/completed`

## 场景 B: 写文件触发审批
把 `babiq.agent.approval-policy` 设为 `ON_REQUEST`，重启后发：
```json
{"jsonrpc":"2.0","id":3,"method":"turn/start","params":{"threadId":"thr_xxx","input":{"type":"text","text":"在当前目录创建 hello.txt，内容为 hi"}}}
```

期望：
- `approval/request`
- 客户端回复：
```json
{"jsonrpc":"2.0","id":4,"method":"approval/respond","params":{"threadId":"thr_xxx","turnId":"turn_xxx","decision":"approve"}}
```
- 随后出现工具执行项、agentMessage、`turn/completed`

## 场景 C: 中断运行中的 turn
先启动一个较慢任务，再发：
```json
{"jsonrpc":"2.0","id":5,"method":"turn/interrupt","params":{"turnId":"turn_xxx"}}
```

期望：
- 同步返回 `accepted=true`
- 最终收到 `turn/completed`，状态为 `interrupted`

## 场景 D: read-only 拒写
把 `babiq.agent.sandbox-mode` 设为 `READ_ONLY`，重复场景 B。

期望：
- 立即收到 `fileChange` 或等价拒绝项
- agentMessage 提示写操作被沙箱拒绝
- 不会真的写入文件
