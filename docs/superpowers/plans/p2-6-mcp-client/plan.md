# P2-6 MCP Client Minimal Integration Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` if subagents are available, otherwise use `superpowers:executing-plans` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 P2 主线稳定后，验证 BaBiQ 能接入本地 stdio MCP server，并把 MCP 工具包装进 BaBiQ 的审批、沙箱、日志和 TurnSummary 链路。

**Architecture:** 后端新增 MCP client 配置和工具适配层，启动时连接本地 stdio MCP server 并拉取 tools；MCP tool 被包装成 BaBiQ `Tool` 接口实现，调用路径继续经过现有审批、沙箱、ToolObservationInterceptor 和 Spotlighting。桌面端只展示 MCP server 状态和工具列表，不做 marketplace、远程 OAuth 或 MCP Server 实现。

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring AI MCP Client or official Model Context Protocol Java SDK after compatibility check, Spring AI 1.1.x stable line, Spring AI Alibaba 1.1.2.3, Kotlin 2.3.21, Compose Multiplatform 1.11.0, JSON-RPC 2.0.

---

## 0. 当前上下文

P2-6 是可选阶段，必须等待:

- P2-3 审批和沙箱设置稳定。
- P2-4 运行记录稳定。
- P2-5 基础统计稳定。

当前已有:

- `Tool` 接口。
- `ToolRegistry`。
- 本地工具实现。
- 审批、沙箱、Spotlighting、工具观测拦截器。
- `McpToolCallItem` 协议 item 类型雏形。

P2-6 的重点是最小可用接入，不是插件平台。

## 1. 官方能力与版本检查

实现前必须重新核对官方文档和 Maven Central。

2026-05-24 初步核对:

- `org.springframework.ai:spring-ai-starter-mcp-client` Maven latest/release 指向 `2.0.0-M7`，属于 milestone，不符合本仓库“禁止 RC/Beta/EAP/Milestone”的规则。
- 同 artifact 最新稳定可用线为 `1.1.7`，但当前仓库锁定 Spring AI `1.1.6`。
- `io.modelcontextprotocol.sdk:mcp` 最新稳定可用线为 `1.1.3`，Maven latest 可能指向 milestone。

决策规则:

1. 优先使用 Spring AI 官方 MCP Client。
2. 如果 Spring AI MCP Client 需要把 `spring-ai.version` 从 `1.1.6` 升到 `1.1.7`，必须先做兼容性验证:
   - Spring AI Alibaba `1.1.2.3` 是否仍兼容。
   - `cd backend; .\mvnw.cmd clean verify` 是否通过。
3. 如果兼容性不明确，不在 P2-6 中硬升级主依赖；改用官方 MCP Java SDK 稳定版并保持薄适配。
4. 禁止使用 `2.0.0-M*`、RC、Beta、Snapshot。

## 2. MCP 范围

### 必做

- 本地 stdio MCP server 配置。
- 启动/连接 MCP server。
- 拉取工具列表。
- MCP 工具包装成 BaBiQ `Tool`。
- MCP 工具调用走审批、沙箱、日志、TurnSummary。
- 桌面端设置页展示 MCP server 状态和工具列表。

### 不做

- MCP Server 实现。
- Remote MCP。
- OAuth。
- 插件市场。
- 多租户权限。
- 自动安装第三方 MCP server。

## 3. 配置设计

### `application.yml`

```yaml
babiq:
  mcp:
    enabled: false
    servers:
      - id: local-filesystem
        display-name: 本地文件 MCP
        transport: stdio
        command: node
        args:
          - path/to/server.js
        cwd: E:\BaBiQ
        enabled: true
        approval-policy: ON_REQUEST
```

### SQLite 表

如果 P2-3 设置系统已完成，MCP server 配置应持久化到:

- `bq_mcp_servers`
- `bq_mcp_tools`

新增 migration:

- Create: `backend/src/main/resources/db/migration/V5__mcp_client.sql`

Migration 注释要求:

- 本阶段新增的 `bq_mcp_servers`、`bq_mcp_tools` 以及每个字段都必须在 SQL 中有中文 `--` 注释。
- 新增表和字段必须同步写入 `bq_schema_comments`。
- `SchemaCommentsCoverageTest` 必须继续通过，确保所有 `bq_*` 表字段都有中文说明。

字段建议:

| 表 | 字段 |
|---|---|
| `bq_mcp_servers` | `server_id`, `display_name`, `transport`, `command`, `args_json`, `cwd`, `enabled`, `status`, `last_error`, `created_at`, `updated_at` |
| `bq_mcp_tools` | `server_id`, `tool_name`, `description`, `schema_json`, `enabled`, `updated_at` |

## 4. JSON-RPC 协议

### `mcp/servers/list`

返回本地 MCP server 状态:

```json
{
  "servers": [
    {
      "serverId": "local-filesystem",
      "displayName": "本地文件 MCP",
      "transport": "stdio",
      "enabled": true,
      "status": "connected",
      "toolCount": 5,
      "lastError": null
    }
  ]
}
```

### `mcp/tools/list`

返回 MCP 工具列表:

```json
{
  "serverId": "local-filesystem",
  "tools": [
    {
      "name": "read_file",
      "description": "Read a file",
      "enabled": true
    }
  ]
}
```

### `mcp/servers/refresh`

手动重连并刷新工具列表:

```json
{
  "serverId": "local-filesystem"
}
```

## 5. 文件结构

### 后端生产代码

- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpProperties.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpServerConfig.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpClientManager.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpToolCatalog.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpToolAdapter.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpServerStatus.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/McpServersListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/McpToolsListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/McpServersRefreshHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/tool/ToolRegistry.java`
  - 支持动态注册 MCP tool 或合并静态工具和 MCP 工具。
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/items/McpToolCallItem.java`
  - 确认字段能表达 serverId、toolName、status、duration、error。
- Optional Modify: `backend/pom.xml`
  - 只添加稳定版 MCP client 依赖。

### 桌面端生产代码

- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/McpModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsPanel.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/Sidebar.kt`
  - 插件入口可从禁用占位升级为 MCP 状态页入口，但不做 marketplace。

### 测试

- Create: `backend/src/test/java/com/wzx/babiq/server/mcp/McpPropertiesTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/mcp/McpClientManagerTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/mcp/McpToolAdapterTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/McpHandlersTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/mcp/McpEndToEndIT.java`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/McpModelsTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

## 6. TDD 任务

### Task 1: 依赖和官方能力 preflight

**Files:**
- Modify: `backend/pom.xml`
- Modify: `docs/superpowers/plans/p2-6-mcp-client/codex-handoff.md`

- [ ] **Step 1: 查官方文档和 Maven Central**

记录:

- Spring AI MCP Client 最新稳定版。
- MCP Java SDK 最新稳定版。
- 与当前 Spring AI Alibaba 的兼容性判断。

- [ ] **Step 2: 做最小依赖验证**

如果使用 Spring AI MCP Client:

```powershell
cd backend
.\mvnw.cmd -DskipTests compile
```

Expected: 编译通过。

- [ ] **Step 3: 如需升级 Spring AI 小版本，先跑全量后端测试**

```powershell
cd backend
.\mvnw.cmd clean verify
```

Expected: BUILD SUCCESS。

如果失败，回滚升级，改走 MCP Java SDK 稳定版或暂停 P2-6。

### Task 2: MCP 配置和 server 状态

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/mcp/McpPropertiesTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpProperties.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpServerConfig.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpServerStatus.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- enabled=false 时不启动 MCP。
- stdio server 缺 command 报配置错误。
- args/cwd 正确绑定。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=McpPropertiesTest test
```

- [ ] **Step 3: 实现配置模型**

实现要求:

- 注释解释 stdio command/args/cwd 的安全边界。
- 不允许从 UI 输入任意命令后立即执行，P2-6 只读或需明确保存确认。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=McpPropertiesTest test
```

### Task 3: MCP client manager 和工具目录

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/mcp/McpClientManagerTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpClientManager.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpToolCatalog.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- enabled server 启动后拉取工具列表。
- 连接失败记录 lastError，不让后端启动失败。
- refresh 会重新拉取工具。
- disabled server 不连接。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=McpClientManagerTest test
```

- [ ] **Step 3: 实现 manager**

实现要求:

- MCP 连接失败不能拖垮核心聊天。
- 连接日志包含 serverId，但不输出敏感环境变量。
- 资源释放实现 `DisposableBean` 或等价生命周期接口。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=McpClientManagerTest test
```

### Task 4: MCP tool adapter 接入 ToolRegistry

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/mcp/McpToolAdapterTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/mcp/McpToolAdapter.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/tool/ToolRegistry.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/items/McpToolCallItem.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- MCP tool 名称被命名空间化，例如 `mcp.local-filesystem.read_file`。
- 调用 adapter 会调用 MCP client。
- 成功/失败都产生可观察结果。
- 工具输出仍被 Spotlighting 包裹。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=McpToolAdapterTest test
```

- [ ] **Step 3: 实现 adapter**

实现要求:

- adapter 实现 BaBiQ `Tool` 接口。
- 不绕过审批和沙箱链路。
- MCP 输出视为不可信数据，继续走 spotlighting。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=McpToolAdapterTest test
```

### Task 5: MCP JSON-RPC handlers

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/McpHandlersTest.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/McpServersListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/McpToolsListHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/McpServersRefreshHandler.java`

- [ ] **Step 1: 写失败测试**

覆盖:

- `mcp/servers/list` 返回状态。
- `mcp/tools/list` 返回工具。
- `mcp/servers/refresh` 触发刷新。
- MCP disabled 时返回空列表，不报错。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd backend
.\mvnw.cmd -Dtest=McpHandlersTest test
```

- [ ] **Step 3: 实现 handlers**

实现要求:

- handler 不直接操作 SDK client。
- 返回 DTO 只包含 UI 需要的字段。

- [ ] **Step 4: 运行测试通过**

```powershell
cd backend
.\mvnw.cmd -Dtest=McpHandlersTest test
```

### Task 6: 桌面端 MCP 状态展示

**Files:**
- Create: `desktop/src/main/kotlin/com/wzx/babiq/desktop/protocol/McpModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/client/AgentClient.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/AppState.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/UiModels.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/state/ChatController.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/settings/SettingsPanel.kt`
- Modify: `desktop/src/main/kotlin/com/wzx/babiq/desktop/ui/shell/Sidebar.kt`
- Create: `desktop/src/test/kotlin/com/wzx/babiq/desktop/protocol/McpModelsTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/client/AgentClientTest.kt`
- Modify: `desktop/src/test/kotlin/com/wzx/babiq/desktop/state/ChatControllerTest.kt`

- [ ] **Step 1: 写失败测试**

覆盖:

- 设置页打开时加载 MCP server 状态。
- 刷新按钮调用 `mcp/servers/refresh`。
- 连接失败展示 lastError。

- [ ] **Step 2: 运行测试并确认失败**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

- [ ] **Step 3: 实现 UI**

UI 要求:

- 插件入口可以改为“本地 MCP”。
- 只展示 server 状态和工具列表。
- 不做 marketplace。
- 不允许用户随意输入命令并立即执行。

- [ ] **Step 4: 运行测试通过**

```powershell
cd desktop
.\gradlew.bat test --tests "*AgentClientTest" --tests "*ChatControllerTest"
```

### Task 7: 最小端到端 MCP 验收

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/mcp/McpEndToEndIT.java`
- Modify: `docs/superpowers/plans/p2-6-mcp-client/codex-handoff.md`

- [ ] **Step 1: 准备本地测试 MCP server**

优先使用官方示例或测试 stub，不把外部 server 源码复制进业务代码。

- [ ] **Step 2: 写集成测试**

覆盖:

- server 启动。
- 拉取工具列表。
- 调用一个安全工具。
- 输出进入 BaBiQ tool result。

- [ ] **Step 3: 运行集成测试**

```powershell
cd backend
.\mvnw.cmd -Dtest=McpEndToEndIT verify
```

### Task 8: 全量验证和文档同步

**Files:**
- Modify: `docs/superpowers/plans/p2-6-mcp-client/codex-handoff.md`
- Modify: `docs/superpowers/plans/p2-task-index.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: 后端全量验证**

```powershell
cd backend
.\mvnw.cmd clean verify
```

- [ ] **Step 2: 桌面端全量验证**

```powershell
cd desktop
.\gradlew.bat test
```

- [ ] **Step 3: 手动验收**

1. 配置一个本地 stdio MCP server。
2. 启动后端后能看到 MCP connected。
3. 设置页能看到 MCP 工具列表。
4. Agent 调用 MCP 工具时仍触发审批和日志。
5. TurnSummary 工具数包含 MCP 调用。

- [ ] **Step 4: 更新文档**

- `docs/superpowers/plans/p2-6-mcp-client/codex-handoff.md`
- `docs/superpowers/plans/p2-task-index.md`
- `AGENTS.md`
- `CLAUDE.md`

- [ ] **Step 5: 中文 commit**

```powershell
git add backend desktop docs AGENTS.md CLAUDE.md
git commit -m "feat(p2-6): 接入本地 MCP 客户端"
```

不要 push。

## 7. 验收标准

- 本地 stdio MCP server 可配置。
- 后端能拉取 MCP tool list。
- MCP tool 能包装为 BaBiQ Tool。
- MCP 调用经过审批、沙箱、Spotlighting、日志和 TurnSummary。
- 桌面端设置页能展示 MCP server 和工具状态。
- 不使用 milestone/RC/Beta/Snapshot 依赖。
- `cd backend; .\mvnw.cmd clean verify` 通过。
- `cd desktop; .\gradlew.bat test` 通过。

## 8. 非目标

- 不实现 MCP Server。
- 不接远程 MCP。
- 不做 OAuth。
- 不做 marketplace。
- 不自动安装第三方 server。
