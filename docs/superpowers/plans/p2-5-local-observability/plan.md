# P2-5 Local Observability Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, otherwise use `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在本地学习项目边界内补齐可观测统计，让用户能看清 provider/model、tokens、成本、工具调用和失败分布。

**Architecture:** P2-5 以 P2-4 的持久化运行记录为数据源，构建本地统计服务和 JSON-RPC 查询接口；P1 内存 `BaBiQMetrics` 保留为实时短期指标，但 UI 和统计快照默认读 SQLite 聚合结果。Actuator/Micrometer 只作为可选本地出口，不引入 Prometheus 部署、Langfuse 或 OpenTelemetry UI。

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Boot Actuator managed by current Boot BOM if enabled, Micrometer managed by Spring Boot, MyBatis-Plus 3.5.16, SQLite JDBC 3.53.1.0, Kotlin 2.3.21, Compose Multiplatform 1.11.0, JSON-RPC 2.0.

---

## 0. 当前上下文

P2-5 必须等待 P2-4 完成，因为统计必须来自持久化运行记录。

现有能力:

- `BaBiQMetrics` 是 P1 内存计数器。
- `TurnSummaryEmitter` 会发出 `turnSummary` 并记录 P1 内存指标。
- `StructuredTurnLogger` 输出单行 JSON turn 摘要。
- P2-4 会引入 `RunRecordService`、tool call 表和历史运行详情。

P2-5 的目标是“看清本地运行情况”，不是建设完整 APM 平台。

## 1. 版本与依赖边界

2026-05-24 Maven Central 元数据观察:

- `spring-boot-starter-actuator` Maven latest/release 已可能指向 4.x/RC 线。
- 当前仓库锁定 Spring Boot `3.5.14`，P2-5 不单独做 Spring Boot 大版本升级。
- 如果启用 Actuator，直接使用当前 Spring Boot BOM 管理的版本，不手写 Actuator/Micrometer 版本号。
- 如果不启用 Actuator，P2-5 仍必须完成 JSON-RPC 本地统计接口。

## 2. JSON-RPC 协议

### `observability/snapshot`

Request:

```json
{
  "range": "7d",
  "cwd": "E:\\BaBiQ"
}
```

Response:

```json
{
  "range": "7d",
  "totals": {
    "turns": 34,
    "failedTurns": 2,
    "promptTokens": 26308,
    "completionTokens": 8120,
    "estimatedCostUsd": 0.1352
  },
  "byProvider": [],
  "byModel": [],
  "byTool": [],
  "byStatus": []
}
```

### `observability/tools`

按工具聚合:

```json
{
  "range": "30d",
  "tools": [
    {
      "toolName": "read_file",
      "calls": 120,
      "failures": 3,
      "avgDurationMs": 32
    }
  ]
}
```

### `observability/costs`

按 provider/model 聚合成本:

```json
{
  "range": "30d",
  "models": [
    {
      "providerId": "deepseek",
      "model": "deepseek-v4-pro",
      "turns": 18,
      "promptTokens": 12000,
      "completionTokens": 4500,
      "estimatedCostUsd": 0.081
    }
  ]
}
```

## 3. 文件结构

### 后端生产代码

- Create: `backend/src/main/java/com/wzx/babiq/server/observability/LocalObservabilityService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/observability/LocalObservabilitySnapshot.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/observability/ToolStats.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/observability/ModelCostStats.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ObservabilitySnapshotHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ObservabilityToolsHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ObservabilityCostsHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/TurnMapper.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/TurnSummaryMapper.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ToolCallMapper.java`
- Optional Modify: `backend/pom.xml`
  - 可选增加 `spring-boot-starter-actuator`，不指定版本。
- Optional Create: `backend/src/main/java/com/wzx/babiq/server/observability/BaBiQMetricsEndpoint.java`
  - 如果启用 Actuator，则暴露最小自定义 endpoint。

### 桌面端生产代码

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ObservabilityModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`
- Optional Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/ObservabilitySummary.kt`

### 测试

- Create: `backend/src/test/java/com/wzx/babiq/server/observability/LocalObservabilityServiceTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ObservabilityHandlersTest.java`
- Optional Create: `backend/src/test/java/com/wzx/babiq/server/observability/BaBiQMetricsEndpointTest.java`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ObservabilityModelsTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

## 4. TDD 任务

### Task 1: 本地统计服务

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/observability/LocalObservabilityServiceTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/observability/LocalObservabilityService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/observability/LocalObservabilitySnapshot.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/observability/ToolStats.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/observability/ModelCostStats.java`

- [x] **Step 1: 写失败测试**

覆盖:

- 按 7d/30d/all 过滤 turn。
- 聚合 turn 总数、失败数、tokens、成本。
- 按 provider/model 聚合。
- 按工具聚合 calls/failures/avgDurationMs。
- 空数据库返回 0 和空数组。

- [x] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=LocalObservabilityServiceTest test
```

- [x] **Step 3: 实现服务**

实现要求:

- 统计只读持久化数据，不读 P1 内存计数器。
- SQL 聚合放 mapper 或 persistence service，不在 Java 中加载全量数据后统计。
- 注释解释统计窗口和空值处理。

- [x] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=LocalObservabilityServiceTest test
```

### Task 2: JSON-RPC 统计接口

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ObservabilityHandlersTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ObservabilitySnapshotHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ObservabilityToolsHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/ObservabilityCostsHandler.java`

- [x] **Step 1: 写失败测试**

覆盖:

- `observability/snapshot` 默认 range 为 `7d`。
- 非法 range 返回 `INVALID_PARAMS`。
- handler 委托 service，不直接访问 mapper。

- [x] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=ObservabilityHandlersTest test
```

- [x] **Step 3: 实现 handlers**

实现要求:

- 返回 DTO 字段稳定。
- 不把内部表字段名直接暴露为协议字段。

- [x] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=ObservabilityHandlersTest test
```

### Task 3: 可选 Actuator/Micrometer 出口

**Files:**
- Optional Modify: `backend/pom.xml`
- Optional Create: `backend/src/main/java/com/wzx/babiq/server/observability/BaBiQMetricsEndpoint.java`
- Optional Create: `backend/src/test/java/com/wzx/babiq/server/observability/BaBiQMetricsEndpointTest.java`

- [x] **Step 1: 决策是否启用 Actuator**

如果启用:

- 使用 `spring-boot-starter-actuator`，不显式写版本。
- 不引入 Prometheus registry。
- 暴露本地 endpoint 只用于开发。

如果不启用:

- 在 handoff 中说明 P2-5 只完成 JSON-RPC 本地统计接口。

- [x] **Step 2: 写测试**

P2-5 决策为不启用 Actuator，因此不创建 `BaBiQMetricsEndpointTest`；相关边界写入 handoff。

- [x] **Step 3: 实现可选 endpoint**

未启用可选 endpoint；JSON-RPC 统计接口已调用 `LocalObservabilityService`，没有重复统计逻辑。

- [x] **Step 4: 运行测试通过**

不适用；已通过 `ObservabilityHandlersTest` 和后端 `clean verify` 覆盖 JSON-RPC 本地统计路径。

### Task 4: 桌面端本地统计展示

**Files:**
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/ObservabilityModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/RuntimeDetailsPanel.kt`
- Optional Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/runtime/ObservabilitySummary.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/ObservabilityModelsTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

- [x] **Step 1: 写失败测试**

覆盖:

- 打开运行详情时可刷新统计。
- range 切换调用后端并更新状态。
- 后端统计接口失败时展示错误，不影响聊天。

- [x] **Step 2: 运行测试并确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

- [x] **Step 3: 实现 UI**

UI 要求:

- 只做紧凑统计，不做复杂图表。
- 显示 totals、provider/model、tool 三组信息。
- 文本不能溢出运行详情面板。

- [x] **Step 4: 运行测试通过**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

### Task 5: 全量验证和文档同步

**Files:**
- Modify: `docs/superpowers/plans/p2-5-local-observability/codex-handoff.md`
- Modify: `docs/superpowers/plans/p2-task-index.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

- [x] **Step 1: 后端全量验证**

```powershell
cd backend
.\mvnw.cmd clean verify
```

- [x] **Step 2: 桌面端全量验证**

```powershell
cd desktop
.\gradlew.bat test
```

- [ ] **Step 3: 手动验收**

1. 连续跑几轮任务。
2. 打开运行详情统计。
3. 确认 tokens、成本、失败、工具统计来自历史记录。
4. 重启后端后统计仍存在。

说明: 自动化验证已完成；真实 Provider 多轮人工验收留到 P2 总体验收时执行。

- [x] **Step 4: 更新文档**

- `docs/superpowers/plans/p2-5-local-observability/codex-handoff.md`
- `docs/superpowers/plans/p2-task-index.md`
- `AGENTS.md`
- `CLAUDE.md`

- [ ] **Step 5: 中文 commit**

```powershell
git add backend desktop docs AGENTS.md CLAUDE.md
git commit -m "feat(p2-5): 增强本地运行统计"
```

不要 push。

## 5. 验收标准

- 可以通过 JSON-RPC 查询本地统计快照。
- 统计来自 SQLite 持久化运行记录。
- provider/model、tool、status、cost 都可聚合。
- 后端 JSON 日志仍保留 thread、turn、provider、cwd、sandbox、approval、tool 关键信息。
- 可选 Actuator 若启用，必须有测试和边界说明。
- `cd backend; .\mvnw.cmd clean verify` 通过。
- `cd desktop; .\gradlew.bat test` 通过。

## 6. 非目标

- 不接 Langfuse。
- 不接 OpenTelemetry UI。
- 不部署 Prometheus/Grafana。
- 不做复杂图表和报表导出。
