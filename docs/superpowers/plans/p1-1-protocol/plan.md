# P1-1: 协议层(WebSocket + JSON-RPC 2.0)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已落地的 monorepo 骨架(P1-0)之上,实现 **内层协议** 全部基础设施 —— WebSocket 端点 `/ws/agent`、JSON-RPC 2.0 报文模型、方法路由器、Thread/Turn/Item 内存状态机(6 态)、12 种 Item 的 sealed interface + records、以及 7 个 JSON-RPC 方法的 handler。**本阶段不接真实 LLM**:`turn/start` 触发后服务端直接发一条 mock `agentMessage`("hello from babiq")完成一轮 turn。

**Architecture:** 严格遵守 ARCHITECTURE §2.1 的**双层协议架构 — 内层**部分。所有协议工作自己实现(D1),WebSocket 用 `spring-boot-starter-websocket` 原生(D2)、Item 用 Java sealed interface + records(D3)、Item 字段全部加 `@JsonProperty(required=true)`(D22,**仅定义 schema,P1-1 不发起 Structured Output 调用**)、配置走 yml(D12)、日志走 Logback JSON(D17,P1-1 只配基础结构)。**禁止引入 spring-ai-alibaba**(那是 P1-2 的事)。

**Tech Stack(P1-1 增量):**
- 继承 P1-0:Java 21 LTS / Spring Boot 3.5.14
- 新增依赖:`spring-boot-starter-websocket`(传递包含 `spring-websocket`)
- 已传递依赖:Jackson(由 `spring-boot-starter-web` 带入,需开启 `jackson-databind` + `jackson-annotations`,均默认存在)
- 测试栈:JUnit 5 + AssertJ + Mockito 5(由 `spring-boot-starter-test` 带入)
- 端到端 WS 测试:`spring-boot-starter-test` + 内置 `StandardWebSocketClient`(无需额外坐标)

**Master Plan Reference:** [2026-05-21-p1-master.md](../2026-05-21-p1-master.md)

**Architecture Reference:** [docs/ARCHITECTURE.md](../../../ARCHITECTURE.md) §2.1 双层协议 / §4 通信协议 / §5 状态机 / §16 Structured Output

**Milestone:** **M1**(详见 master plan §4),硬验收清单见本 plan 末尾 Done Criteria。

---

## Files Touched

### Created (生产代码)

`backend/src/main/java/com/wzx/babiq/server/`:

```
api/
├── JsonRpcWebSocketHandler.java        # WS handler 主入口
├── JsonRpcMessage.java                  # sealed interface(Request/Response/Notification/ErrorResponse)
├── JsonRpcDispatcher.java               # method → handler 路由
├── JsonRpcMethodHandler.java            # 方法 handler 接口
├── method/
│   ├── ThreadCreateHandler.java
│   ├── TurnStartHandler.java
│   ├── TurnCancelHandler.java
│   ├── TurnInterruptHandler.java
│   ├── ApprovalRespondHandler.java
│   ├── ProvidersListHandler.java
│   └── ProvidersSetActiveHandler.java
└── error/
    ├── JsonRpcErrorCode.java
    └── JsonRpcException.java

conversation/
├── ConversationService.java             # Thread/Turn 内存生命周期
├── Thread.java                          # record
├── Turn.java                            # 含 TurnStatus 状态机
├── TurnStatus.java                      # enum 6 态
├── ItemEmitter.java                     # 向 WebSocketSession 发 item/* 通知
└── items/
    ├── ThreadItem.java                  # sealed interface
    ├── UserMessageItem.java
    ├── AgentMessageItem.java
    ├── ReasoningItem.java
    ├── PlanItem.java
    ├── CommandExecutionItem.java
    ├── FileChangeItem.java
    ├── McpToolCallItem.java
    ├── CollabToolCallItem.java
    ├── WebSearchItem.java
    ├── ImageViewItem.java
    ├── ReviewModeItem.java
    └── ContextCompactionItem.java

config/
└── WebSocketConfig.java                 # 注册 /ws/agent 端点
```

### Created (测试代码)

`backend/src/test/java/com/wzx/babiq/server/`:

```
api/
├── JsonRpcMessageTest.java              # 报文序列化往返
├── JsonRpcDispatcherTest.java           # 路由 + 错误码
├── JsonRpcWebSocketHandlerIT.java       # @SpringBootTest 端到端 wscat 等价
└── method/
    ├── ThreadCreateHandlerTest.java
    ├── TurnStartHandlerTest.java
    └── TurnCancelHandlerTest.java

conversation/
├── TurnStatusMachineTest.java           # 6 态全覆盖
├── ConversationServiceTest.java
└── items/
    └── ThreadItemJsonTest.java          # 12 种 record 反/序列化
```

### Modified
- `backend/pom.xml`:新增 `spring-boot-starter-websocket` 依赖
- `backend/src/main/resources/application.yml`:加 `babiq.ws.path=/ws/agent` 等配置项(D12)
- `backend/src/main/resources/logback-spring.xml`(新建,基础 JSON pattern,D17 雏形)

### Unchanged(明确不动)
- 任何 `desktop/` 内文件(P1-4 才动)
- `backend/src/main/java/com/wzx/babiq/server/BaBiQApplication.java`(已就绪)
- 任何 `model/`、`agent/`、`tool/`、`approval/`、`sandbox/`、`hook/`、`security/`、`observability/` 包(P1-2 / P1-3 才动)

---

## Pre-flight Check

> 所有 PowerShell 命令默认在 `F:\wwwxxxx\BaBiQ` 下执行,后续步骤切到 `backend/` 时会显式 `cd`。

- [ ] **Step 0.1: 确认 P1-0 已完成 + 工作树干净**

Run:
```powershell
cd F:\wwwxxxx\BaBiQ
git status
git log --oneline -5
git tag | Select-String p1-0
```

Expected:
- `git status` 输出 `nothing to commit, working tree clean`(或确认无未跟踪生产代码)
- `git tag` 包含 `p1-0-skeleton`
- 最近 commit 包含 `chore(p1-0): finalize monorepo skeleton ...` 类记录

- [ ] **Step 0.2: 创建 feature 分支**

Run:
```powershell
git checkout -b feat/p1-1-protocol
git branch --show-current
```

Expected: `feat/p1-1-protocol`。

- [ ] **Step 0.3: 验证 backend 基线编译 + 启动**

Run:
```powershell
cd backend
.\mvnw.cmd clean compile
cd ..
```

Expected: `BUILD SUCCESS`。**P1-0 已锁 Java 21,这里不允许出现 release version 25 错误**。

- [ ] **Step 0.4: 选装 wscat(本地烟测用)**

Run:
```powershell
wscat --version
```

Expected — 任一情况:
- 输出版本号(如 `5.2.0`):OK,直接用
- 报错 "command not found":Step 后续会用 `npm install -g wscat` 安装;或改用 Java 端到端测试代替 wscat(集成测试中实现,见 Task 11)

> ⚠️ wscat 仅用于人工手动验收(M1 Done Criteria 的最后一项),自动化验证完全靠 `@SpringBootTest` 中的 `StandardWebSocketClient`。无 wscat 不阻塞 plan。

---

## Task 1: 加 WebSocket 依赖 + 基础配置

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1.1: pom.xml 加 `spring-boot-starter-websocket`**

Edit `backend/pom.xml`,定位 `<dependencies>` 区,在 `spring-boot-starter-web` 同级追加:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

> 不指定 version,由父 POM(`spring-boot-starter-parent` 3.5.14)托管。

- [ ] **Step 1.2: application.yml 加 babiq 命名空间**

Edit `backend/src/main/resources/application.yml`,在文件末尾追加:
```yaml
babiq:
  ws:
    path: /ws/agent
    allowed-origins: "*"          # P1-1 开放,P1-3b 再限
  protocol:
    mock-agent-text: "hello from babiq"
```

> `babiq.protocol.mock-agent-text` 是 P1-1 唯一的 mock 配置项,P1-2 接 LLM 后移除。`allowed-origins: "*"` 仅 P1 阶段方便本机 wscat,P3+ 收敛。

- [ ] **Step 1.3: 编译 + 测试通过(基线没回退)**

Run:
```powershell
cd backend
.\mvnw.cmd clean test
cd ..
```

Expected: `BUILD SUCCESS`,1 个原 `contextLoads` 测试通过。

- [ ] **Step 1.4: Commit**

```powershell
git add backend/pom.xml backend/src/main/resources/application.yml
git commit -m "chore(p1-1): 引入 spring-boot-starter-websocket 与基础配置"
```

---

## Task 2: JSON-RPC 报文模型(sealed interface + records)

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcMessage.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/error/JsonRpcErrorCode.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/error/JsonRpcException.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcMessageTest.java`

> 严格遵循 ARCHITECTURE §4.4 给出的报文样例。所有字段加 `@JsonProperty(required=true)` 是 D22 强约束。

- [ ] **Step 2.1: TDD — 写 JsonRpcMessageTest(先红)**

Create `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcMessageTest.java`:
```java
package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRpcMessageTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void request_should_roundtrip() throws Exception {
        var req = new JsonRpcMessage.Request(
                "2.0", 1L, "thread/create", Map.of("cwd", "."));
        String json = om.writeValueAsString(req);
        assertThat(json).contains("\"jsonrpc\":\"2.0\"")
                        .contains("\"method\":\"thread/create\"")
                        .contains("\"id\":1");
        JsonRpcMessage.Request back = om.readValue(json, JsonRpcMessage.Request.class);
        assertThat(back.id()).isEqualTo(1L);
        assertThat(back.method()).isEqualTo("thread/create");
    }

    @Test
    void notification_must_not_carry_id() throws Exception {
        var n = new JsonRpcMessage.Notification(
                "2.0", "turn/started", Map.of("threadId", "thr_1"));
        String json = om.writeValueAsString(n);
        assertThat(json).doesNotContain("\"id\":");
    }

    @Test
    void error_response_should_use_jsonrpc_codes() throws Exception {
        var err = JsonRpcMessage.ErrorResponse.of(
                42L, JsonRpcErrorCode.METHOD_NOT_FOUND, "no such method", null);
        String json = om.writeValueAsString(err);
        assertThat(json).contains("\"code\":-32601")
                        .contains("\"id\":42");
    }
}
```

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=JsonRpcMessageTest
cd ..
```

Expected: 编译失败(类未实现),这是预期的 RED。

- [ ] **Step 2.2: 实现 JsonRpcErrorCode**

Create `backend/src/main/java/com/wzx/babiq/server/api/error/JsonRpcErrorCode.java`:
```java
package com.wzx.babiq.server.api.error;

public enum JsonRpcErrorCode {
    PARSE_ERROR(-32700),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602),
    INTERNAL_ERROR(-32603),
    SERVER_ERROR(-32000);

    private final int code;
    JsonRpcErrorCode(int code) { this.code = code; }
    public int code() { return code; }
}
```

- [ ] **Step 2.3: 实现 JsonRpcException**

Create `backend/src/main/java/com/wzx/babiq/server/api/error/JsonRpcException.java`:
```java
package com.wzx.babiq.server.api.error;

public class JsonRpcException extends RuntimeException {
    private final JsonRpcErrorCode errorCode;
    private final Object data;

    public JsonRpcException(JsonRpcErrorCode code, String message) {
        this(code, message, null);
    }

    public JsonRpcException(JsonRpcErrorCode code, String message, Object data) {
        super(message);
        this.errorCode = code;
        this.data = data;
    }

    public JsonRpcErrorCode errorCode() { return errorCode; }
    public Object data() { return data; }
}
```

- [ ] **Step 2.4: 实现 JsonRpcMessage(sealed interface)**

Create `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcMessage.java`:
```java
package com.wzx.babiq.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface JsonRpcMessage {

    String jsonrpc();

    record Request(
            @JsonProperty(required = true) String jsonrpc,
            @JsonProperty(required = true) Long id,
            @JsonProperty(required = true) String method,
            Object params
    ) implements JsonRpcMessage {}

    record Response(
            @JsonProperty(required = true) String jsonrpc,
            @JsonProperty(required = true) Long id,
            @JsonProperty(required = true) Object result
    ) implements JsonRpcMessage {
        public static Response ok(Long id, Object result) {
            return new Response("2.0", id, result);
        }
    }

    record Notification(
            @JsonProperty(required = true) String jsonrpc,
            @JsonProperty(required = true) String method,
            Object params
    ) implements JsonRpcMessage {
        public static Notification of(String method, Object params) {
            return new Notification("2.0", method, params);
        }
    }

    record ErrorResponse(
            @JsonProperty(required = true) String jsonrpc,
            Long id,                              // 解析失败时允许为 null
            @JsonProperty(required = true) Error error
    ) implements JsonRpcMessage {
        public record Error(
                @JsonProperty(required = true) int code,
                @JsonProperty(required = true) String message,
                Object data
        ) {}
        public static ErrorResponse of(Long id, JsonRpcErrorCode code, String msg, Object data) {
            return new ErrorResponse("2.0", id, new Error(code.code(), msg, data));
        }
    }
}
```

- [ ] **Step 2.5: 跑测试(绿)**

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=JsonRpcMessageTest
cd ..
```

Expected: `Tests run: 3, Failures: 0`。

- [ ] **Step 2.6: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/JsonRpcMessage.java
git add backend/src/main/java/com/wzx/babiq/server/api/error/
git add backend/src/test/java/com/wzx/babiq/server/api/JsonRpcMessageTest.java
git commit -m "feat(p1-1): 实现 JSON-RPC 2.0 报文模型(sealed interface + records)"
```

---

## Task 3: Turn 状态机(6 个枚举值(3 非终态 CREATED/RUNNING/WAITING_APPROVAL + 3 终态 COMPLETED/FAILED/CANCELED))+ 单元测试

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/TurnStatus.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/Turn.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/Thread.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/conversation/TurnStatusMachineTest.java`

> ARCHITECTURE §5.2 图示 5 个状态:`CREATED → RUNNING → (WAITING_APPROVAL ↔ RUNNING) → COMPLETED | FAILED | CANCELED`。本任务实现状态机内核 + 不允许的迁移抛 `IllegalStateException`。

- [ ] **Step 3.1: TDD — 写 TurnStatusMachineTest(先红)**

Create `backend/src/test/java/com/wzx/babiq/server/conversation/TurnStatusMachineTest.java`:
```java
package com.wzx.babiq.server.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnStatusMachineTest {

    @Test
    void created_to_running_is_allowed() {
        Turn t = new Turn("turn_001", "thr_001");
        assertThat(t.status()).isEqualTo(TurnStatus.CREATED);
        t.start();
        assertThat(t.status()).isEqualTo(TurnStatus.RUNNING);
    }

    @Test
    void running_to_waiting_and_back() {
        Turn t = new Turn("turn_001", "thr_001");
        t.start();
        t.waitApproval();
        assertThat(t.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        t.resume();
        assertThat(t.status()).isEqualTo(TurnStatus.RUNNING);
    }

    @Test
    void running_to_completed() {
        Turn t = new Turn("turn_001", "thr_001");
        t.start();
        t.complete();
        assertThat(t.status()).isEqualTo(TurnStatus.COMPLETED);
    }

    @Test
    void running_to_failed() {
        Turn t = new Turn("turn_001", "thr_001");
        t.start();
        t.fail("boom");
        assertThat(t.status()).isEqualTo(TurnStatus.FAILED);
    }

    @Test
    void any_to_canceled_when_running_or_waiting() {
        Turn a = new Turn("a", "thr");
        a.start();
        a.cancel();
        assertThat(a.status()).isEqualTo(TurnStatus.CANCELED);

        Turn b = new Turn("b", "thr");
        b.start();
        b.waitApproval();
        b.cancel();
        assertThat(b.status()).isEqualTo(TurnStatus.CANCELED);
    }

    @Test
    void terminal_states_reject_further_transitions() {
        Turn t = new Turn("t", "thr");
        t.start();
        t.complete();
        assertThatThrownBy(t::cancel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(t::fail).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cannot_start_twice() {
        Turn t = new Turn("t", "thr");
        t.start();
        assertThatThrownBy(t::start).isInstanceOf(IllegalStateException.class);
    }
}
```

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=TurnStatusMachineTest
cd ..
```

Expected: 编译失败 → RED。

- [ ] **Step 3.2: 实现 TurnStatus 枚举**

Create `backend/src/main/java/com/wzx/babiq/server/conversation/TurnStatus.java`:
```java
package com.wzx.babiq.server.conversation;

public enum TurnStatus {
    CREATED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }
}
```

- [ ] **Step 3.3: 实现 Thread record**

Create `backend/src/main/java/com/wzx/babiq/server/conversation/Thread.java`:
```java
package com.wzx.babiq.server.conversation;

import java.time.Instant;

public record Thread(
        String id,
        String cwd,
        Instant createdAt
) {
    public static Thread newThread(String id, String cwd) {
        return new Thread(id, cwd, Instant.now());
    }
}
```

- [ ] **Step 3.4: 实现 Turn(可变状态对象)**

Create `backend/src/main/java/com/wzx/babiq/server/conversation/Turn.java`:
```java
package com.wzx.babiq.server.conversation;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class Turn {

    private static final Map<TurnStatus, Set<TurnStatus>> ALLOWED = Map.of(
            TurnStatus.CREATED,          EnumSet.of(TurnStatus.RUNNING, TurnStatus.CANCELED),
            TurnStatus.RUNNING,          EnumSet.of(TurnStatus.WAITING_APPROVAL, TurnStatus.COMPLETED, TurnStatus.FAILED, TurnStatus.CANCELED),
            TurnStatus.WAITING_APPROVAL, EnumSet.of(TurnStatus.RUNNING, TurnStatus.FAILED, TurnStatus.CANCELED),
            TurnStatus.COMPLETED,        EnumSet.noneOf(TurnStatus.class),
            TurnStatus.FAILED,           EnumSet.noneOf(TurnStatus.class),
            TurnStatus.CANCELED,         EnumSet.noneOf(TurnStatus.class)
    );

    private final String id;
    private final String threadId;
    private final Instant createdAt;
    private TurnStatus status;
    private String failureReason;

    public Turn(String id, String threadId) {
        this.id = id;
        this.threadId = threadId;
        this.createdAt = Instant.now();
        this.status = TurnStatus.CREATED;
    }

    public String id()          { return id; }
    public String threadId()    { return threadId; }
    public TurnStatus status()  { return status; }
    public String failureReason() { return failureReason; }
    public Instant createdAt()  { return createdAt; }

    public void start()        { transition(TurnStatus.RUNNING); }
    public void waitApproval() { transition(TurnStatus.WAITING_APPROVAL); }
    public void resume()       { transition(TurnStatus.RUNNING); }
    public void complete()     { transition(TurnStatus.COMPLETED); }
    public void cancel()       { transition(TurnStatus.CANCELED); }
    public void fail()         { fail(null); }
    public void fail(String reason) {
        transition(TurnStatus.FAILED);
        this.failureReason = reason;
    }

    private void transition(TurnStatus next) {
        Set<TurnStatus> allowed = ALLOWED.get(status);
        if (allowed == null || !allowed.contains(next)) {
            throw new IllegalStateException("Illegal transition: " + status + " → " + next);
        }
        this.status = next;
    }
}
```

- [ ] **Step 3.5: 跑测试(绿)**

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=TurnStatusMachineTest
cd ..
```

Expected: `Tests run: 7, Failures: 0`。

- [ ] **Step 3.6: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/conversation/TurnStatus.java
git add backend/src/main/java/com/wzx/babiq/server/conversation/Thread.java
git add backend/src/main/java/com/wzx/babiq/server/conversation/Turn.java
git add backend/src/test/java/com/wzx/babiq/server/conversation/TurnStatusMachineTest.java
git commit -m "feat(p1-1): Turn 状态机 6 态(含 7 个单测覆盖全部合法/非法迁移)"
```

---

## Task 4: 12 种 Item 类型(sealed interface + records,D22 字段契约)

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/items/ThreadItem.java`
- Create: 12 个 `*Item.java` records
- Create: `backend/src/test/java/com/wzx/babiq/server/conversation/items/ThreadItemJsonTest.java`

> P1-1 阶段 12 种全部定义 record schema(D22 要求),但 **P1-1 只会实例化并通过 wire 发出 UserMessageItem / AgentMessageItem** 两种;其余 10 种保留 placeholder 字段、保证类编译可用,真实使用在 P1-3 / P1-3b。

- [ ] **Step 4.1: TDD — 写 ThreadItemJsonTest(先红)**

Create `backend/src/test/java/com/wzx/babiq/server/conversation/items/ThreadItemJsonTest.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadItemJsonTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void userMessage_serializes_with_type_tag() throws Exception {
        ThreadItem item = new UserMessageItem("it_01", "userMessage", "hi");
        String json = om.writeValueAsString(item);
        assertThat(json).contains("\"type\":\"userMessage\"")
                        .contains("\"text\":\"hi\"");
    }

    @Test
    void agentMessage_supports_textDelta() throws Exception {
        ThreadItem item = new AgentMessageItem("it_02", "agentMessage", "hello", null);
        String json = om.writeValueAsString(item);
        assertThat(json).contains("\"type\":\"agentMessage\"")
                        .contains("\"text\":\"hello\"");
    }

    @Test
    void polymorphic_deserialization_by_type_tag() throws Exception {
        String json = "{\"id\":\"it_01\",\"type\":\"userMessage\",\"text\":\"hi\"}";
        ThreadItem item = om.readValue(json, ThreadItem.class);
        assertThat(item).isInstanceOf(UserMessageItem.class);
        assertThat(((UserMessageItem) item).text()).isEqualTo("hi");
    }
}
```

Expected: RED(类未实现)。

- [ ] **Step 4.2: 写 ThreadItem sealed interface(含 Jackson 多态注解)**

Create `backend/src/main/java/com/wzx/babiq/server/conversation/items/ThreadItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserMessageItem.class,        name = "userMessage"),
        @JsonSubTypes.Type(value = AgentMessageItem.class,       name = "agentMessage"),
        @JsonSubTypes.Type(value = ReasoningItem.class,          name = "reasoning"),
        @JsonSubTypes.Type(value = PlanItem.class,               name = "plan"),
        @JsonSubTypes.Type(value = CommandExecutionItem.class,   name = "commandExecution"),
        @JsonSubTypes.Type(value = FileChangeItem.class,         name = "fileChange"),
        @JsonSubTypes.Type(value = McpToolCallItem.class,        name = "mcpToolCall"),
        @JsonSubTypes.Type(value = CollabToolCallItem.class,     name = "collabToolCall"),
        @JsonSubTypes.Type(value = WebSearchItem.class,          name = "webSearch"),
        @JsonSubTypes.Type(value = ImageViewItem.class,          name = "imageView"),
        @JsonSubTypes.Type(value = ReviewModeItem.class,         name = "reviewMode"),
        @JsonSubTypes.Type(value = ContextCompactionItem.class,  name = "contextCompaction"),
})
public sealed interface ThreadItem permits
        UserMessageItem, AgentMessageItem, ReasoningItem, PlanItem,
        CommandExecutionItem, FileChangeItem, McpToolCallItem, CollabToolCallItem,
        WebSearchItem, ImageViewItem, ReviewModeItem, ContextCompactionItem {

    String id();
    String type();
}
```

- [ ] **Step 4.3: 实现 P1 主用的 6 种 Item record**

为节省篇幅,本步骤每个 record 独立一个文件,全部位于 `backend/src/main/java/com/wzx/babiq/server/conversation/items/`。**所有字段统一加 `@JsonProperty(required=true)` 以满足 D22**(可选字段不加)。

`UserMessageItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UserMessageItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,    // 固定 "userMessage"
        @JsonProperty(required = true) String text
) implements ThreadItem {
    public static UserMessageItem of(String id, String text) {
        return new UserMessageItem(id, "userMessage", text);
    }
}
```

`AgentMessageItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMessageItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,    // 固定 "agentMessage"
        String text,        // 全量文本(item/added 或 item/completed)
        String textDelta    // 增量(item/updated)
) implements ThreadItem {
    public static AgentMessageItem full(String id, String text) {
        return new AgentMessageItem(id, "agentMessage", text, null);
    }
    public static AgentMessageItem delta(String id, String delta) {
        return new AgentMessageItem(id, "agentMessage", null, delta);
    }
}
```

`ReasoningItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReasoningItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,    // "reasoning"
        @JsonProperty(required = true) String text
) implements ThreadItem {}
```

`PlanItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PlanItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,    // "plan"
        @JsonProperty(required = true) String goal,
        @JsonProperty(required = true) List<PlanStep> steps,
        @JsonProperty(required = true) String reasoning
) implements ThreadItem {
    public record PlanStep(
            @JsonProperty(required = true) int order,
            @JsonProperty(required = true) String description
    ) {}
}
```

`CommandExecutionItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandExecutionItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,    // "commandExecution"
        @JsonProperty(required = true) String command,
        @JsonProperty(required = true) String status,  // pending/approved/denied/running/completed/failed
        Integer exitCode,
        String stdout,
        String stderr,
        Long durationMs
) implements ThreadItem {}
```

`FileChangeItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileChangeItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,    // "fileChange"
        @JsonProperty(required = true) String action,  // read/write/patch/delete
        @JsonProperty(required = true) String path,
        @JsonProperty(required = true) String status,  // pending/approved/denied/completed
        String contentPreview
) implements ThreadItem {}
```

- [ ] **Step 4.4: 实现 6 种 placeholder Item record(P1 不业务使用)**

`McpToolCallItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

public record McpToolCallItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type    // "mcpToolCall"
) implements ThreadItem {}
```

`CollabToolCallItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CollabToolCallItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type   // 值固定为 "collabToolCall"
) implements ThreadItem {
    public CollabToolCallItem(String id) { this(id, "collabToolCall"); }
}
```

`WebSearchItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WebSearchItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type   // 值固定为 "webSearch"
) implements ThreadItem {
    public WebSearchItem(String id) { this(id, "webSearch"); }
}
```

`ImageViewItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImageViewItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type   // 值固定为 "imageView"
) implements ThreadItem {
    public ImageViewItem(String id) { this(id, "imageView"); }
}
```

`ReviewModeItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReviewModeItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type   // 值固定为 "reviewMode"
) implements ThreadItem {
    public ReviewModeItem(String id) { this(id, "reviewMode"); }
}
```

`ContextCompactionItem.java`:
```java
package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ContextCompactionItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type   // 值固定为 "contextCompaction"
) implements ThreadItem {
    public ContextCompactionItem(String id) { this(id, "contextCompaction"); }
}
```

> 这些 placeholder 不带业务字段是有意为之(YAGNI),P1-3 / P2+ 真用到时再扩 record component。但必须现在就声明,**否则 `ThreadItem` sealed 子类未关闭,无法编译**。

- [ ] **Step 4.5: 跑 ItemJsonTest(绿)**

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=ThreadItemJsonTest
cd ..
```

Expected: `Tests run: 3, Failures: 0`。

- [ ] **Step 4.6: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/conversation/items/
git add backend/src/test/java/com/wzx/babiq/server/conversation/items/
git commit -m "feat(p1-1): 12 种 ThreadItem(sealed interface + records,加 @JsonProperty(required=true))"
```

---

## Task 5: ConversationService + ItemEmitter

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/conversation/ConversationServiceTest.java`

> `ConversationService` = thread/turn 的内存 registry + id 生成器。`ItemEmitter` = 向单个 `WebSocketSession` 发送 `item/*` / `turn/*` notification 的薄壳。**P1-1 单连接、单 thread,所以 emitter 直接持有 session**;多连接/多 thread 是 P2+ 的事。

- [ ] **Step 5.1: TDD — 写 ConversationServiceTest(先红)**

Create `backend/src/test/java/com/wzx/babiq/server/conversation/ConversationServiceTest.java`:
```java
package com.wzx.babiq.server.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationServiceTest {

    @Test
    void create_thread_returns_unique_id() {
        var svc = new ConversationService();
        Thread a = svc.createThread(".");
        Thread b = svc.createThread(".");
        assertThat(a.id()).startsWith("thr_").isNotEqualTo(b.id());
    }

    @Test
    void start_turn_attaches_to_thread() {
        var svc = new ConversationService();
        Thread thr = svc.createThread(".");
        Turn turn = svc.startTurn(thr.id());
        assertThat(turn.threadId()).isEqualTo(thr.id());
        assertThat(turn.status()).isEqualTo(TurnStatus.CREATED);
        assertThat(turn.id()).startsWith("turn_");
    }

    @Test
    void start_turn_with_unknown_thread_throws() {
        var svc = new ConversationService();
        assertThatThrownBy(() -> svc.startTurn("thr_nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lookup_turn_works() {
        var svc = new ConversationService();
        Thread thr = svc.createThread(".");
        Turn t = svc.startTurn(thr.id());
        assertThat(svc.findTurn(t.id())).isPresent();
        assertThat(svc.findTurn("turn_nope")).isEmpty();
    }
}
```

Expected: RED。

- [ ] **Step 5.2: 实现 ConversationService**

Create `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java`:
```java
package com.wzx.babiq.server.conversation;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationService {

    private final Map<String, Thread> threads = new ConcurrentHashMap<>();
    private final Map<String, Turn>   turns   = new ConcurrentHashMap<>();

    public Thread createThread(String cwd) {
        String id = "thr_" + UUID.randomUUID().toString().substring(0, 12);
        Thread t = Thread.newThread(id, cwd);
        threads.put(id, t);
        return t;
    }

    public Optional<Thread> findThread(String id) {
        return Optional.ofNullable(threads.get(id));
    }

    public Turn startTurn(String threadId) {
        if (!threads.containsKey(threadId)) {
            throw new IllegalArgumentException("Unknown threadId: " + threadId);
        }
        String id = "turn_" + UUID.randomUUID().toString().substring(0, 12);
        Turn t = new Turn(id, threadId);
        turns.put(id, t);
        return t;
    }

    public Optional<Turn> findTurn(String id) {
        return Optional.ofNullable(turns.get(id));
    }
}
```

- [ ] **Step 5.3: 实现 ItemEmitter**

Create `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`:
```java
package com.wzx.babiq.server.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMessage;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ItemEmitter {

    private final WebSocketSession session;
    private final ObjectMapper om;
    private final String threadId;
    private final String turnId;

    public ItemEmitter(WebSocketSession session, ObjectMapper om, String threadId, String turnId) {
        this.session = session;
        this.om = om;
        this.threadId = threadId;
        this.turnId = turnId;
    }

    public void emitTurnStarted() throws IOException {
        sendNotification("turn/started", baseParams());
    }

    public void emitItemAdded(ThreadItem item) throws IOException {
        Map<String, Object> p = baseParams();
        p.put("item", item);
        sendNotification("item/added", p);
    }

    public void emitItemUpdated(ThreadItem item) throws IOException {
        Map<String, Object> p = baseParams();
        p.put("item", item);
        sendNotification("item/updated", p);
    }

    public void emitItemCompleted(ThreadItem item) throws IOException {
        Map<String, Object> p = baseParams();
        p.put("item", item);
        sendNotification("item/completed", p);
    }

    public void emitTurnCompleted(String status) throws IOException {
        Map<String, Object> p = baseParams();
        p.put("status", status);   // completed / interrupted / canceled
        sendNotification("turn/completed", p);
    }

    public void emitTurnFailed(String reason) throws IOException {
        Map<String, Object> p = baseParams();
        p.put("reason", reason);
        sendNotification("turn/failed", p);
    }

    private Map<String, Object> baseParams() {
        var m = new LinkedHashMap<String, Object>();
        m.put("threadId", threadId);
        m.put("turnId", turnId);
        return m;
    }

    private void sendNotification(String method, Object params) throws IOException {
        var n = JsonRpcMessage.Notification.of(method, params);
        synchronized (session) {
            session.sendMessage(new TextMessage(om.writeValueAsString(n)));
        }
    }
}
```

- [ ] **Step 5.4: 跑测试(绿)**

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=ConversationServiceTest
cd ..
```

Expected: `Tests run: 4, Failures: 0`。

- [ ] **Step 5.5: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java
git add backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java
git add backend/src/test/java/com/wzx/babiq/server/conversation/ConversationServiceTest.java
git commit -m "feat(p1-1): ConversationService 内存 registry + ItemEmitter 通知发射"
```

---

## Task 6: JsonRpcMethodHandler 接口 + Dispatcher 路由

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcMethodHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcDispatcherTest.java`

- [ ] **Step 6.1: TDD — 写 JsonRpcDispatcherTest(先红)**

Create `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcDispatcherTest.java`:
```java
package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JsonRpcDispatcherTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void method_not_found_returns_minus32601() throws Exception {
        var dispatcher = new JsonRpcDispatcher(List.of(), om);
        JsonRpcMessage out = dispatcher.dispatch(
                new JsonRpcMessage.Request("2.0", 1L, "no/such", Map.of()),
                mock(WebSocketSession.class));
        assertThat(out).isInstanceOf(JsonRpcMessage.ErrorResponse.class);
        assertThat(((JsonRpcMessage.ErrorResponse) out).error().code())
                .isEqualTo(JsonRpcErrorCode.METHOD_NOT_FOUND.code());
    }

    @Test
    void handler_throwing_invalid_params_returns_minus32602() throws Exception {
        JsonRpcMethodHandler h = new JsonRpcMethodHandler() {
            public String method() { return "x/y"; }
            public Object handle(JsonNode params, WebSocketSession s) {
                throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "missing field");
            }
        };
        var dispatcher = new JsonRpcDispatcher(List.of(h), om);
        JsonRpcMessage out = dispatcher.dispatch(
                new JsonRpcMessage.Request("2.0", 2L, "x/y", Map.of()),
                mock(WebSocketSession.class));
        assertThat(out).isInstanceOf(JsonRpcMessage.ErrorResponse.class);
        assertThat(((JsonRpcMessage.ErrorResponse) out).error().code())
                .isEqualTo(JsonRpcErrorCode.INVALID_PARAMS.code());
    }

    @Test
    void successful_handler_returns_response_with_same_id() throws Exception {
        JsonRpcMethodHandler h = new JsonRpcMethodHandler() {
            public String method() { return "ping" ; }
            public Object handle(JsonNode params, WebSocketSession s) {
                return Map.of("pong", true);
            }
        };
        var dispatcher = new JsonRpcDispatcher(List.of(h), om);
        JsonRpcMessage out = dispatcher.dispatch(
                new JsonRpcMessage.Request("2.0", 99L, "ping", Map.of()),
                mock(WebSocketSession.class));
        assertThat(out).isInstanceOf(JsonRpcMessage.Response.class);
        assertThat(((JsonRpcMessage.Response) out).id()).isEqualTo(99L);
    }
}
```

Expected: RED。

- [ ] **Step 6.2: 实现 JsonRpcMethodHandler 接口**

Create `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcMethodHandler.java`:
```java
package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.socket.WebSocketSession;

public interface JsonRpcMethodHandler {
    String method();
    Object handle(JsonNode params, WebSocketSession session) throws Exception;
}
```

- [ ] **Step 6.3: 实现 JsonRpcDispatcher**

Create `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java`:
```java
package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class JsonRpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcDispatcher.class);

    private final Map<String, JsonRpcMethodHandler> handlers = new HashMap<>();
    private final ObjectMapper om;

    public JsonRpcDispatcher(List<JsonRpcMethodHandler> all, ObjectMapper om) {
        this.om = om;
        for (var h : all) handlers.put(h.method(), h);
    }

    public JsonRpcMessage dispatch(JsonRpcMessage.Request req, WebSocketSession session) {
        var h = handlers.get(req.method());
        if (h == null) {
            return JsonRpcMessage.ErrorResponse.of(req.id(),
                    JsonRpcErrorCode.METHOD_NOT_FOUND,
                    "Method not found: " + req.method(), null);
        }
        try {
            JsonNode params = req.params() == null
                    ? om.nullNode()
                    : om.valueToTree(req.params());
            Object result = h.handle(params, session);
            return JsonRpcMessage.Response.ok(req.id(), result);
        } catch (JsonRpcException jre) {
            return JsonRpcMessage.ErrorResponse.of(req.id(),
                    jre.errorCode(), jre.getMessage(), jre.data());
        } catch (Exception e) {
            log.error("Method {} failed", req.method(), e);
            return JsonRpcMessage.ErrorResponse.of(req.id(),
                    JsonRpcErrorCode.SERVER_ERROR, e.getMessage(), null);
        }
    }
}
```

- [ ] **Step 6.4: 跑测试(绿)**

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=JsonRpcDispatcherTest
cd ..
```

Expected: `Tests run: 3, Failures: 0`。

- [ ] **Step 6.5: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/JsonRpcMethodHandler.java
git add backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java
git add backend/src/test/java/com/wzx/babiq/server/api/JsonRpcDispatcherTest.java
git commit -m "feat(p1-1): JsonRpcDispatcher 方法路由 + 标准错误码映射"
```

---

## Task 7: 7 个 Method Handler(真实业务 2 个 + mock 5 个)

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/*.java`(7 个)
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/ThreadCreateHandlerTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnCancelHandlerTest.java`

> 真实实现:`ThreadCreateHandler` / `TurnCancelHandler`。Mock 占位(返回硬编码 ok):`TurnInterruptHandler` / `ApprovalRespondHandler` / `ProvidersListHandler` / `ProvidersSetActiveHandler`。
> `TurnStartHandler` 比较特殊 — 真实创建 Turn,然后**异步**驱动 mock item 流(见 Task 8 与 Task 9 协作)。

- [ ] **Step 7.1: TDD — 写 ThreadCreateHandlerTest(先红)**

Create `backend/src/test/java/com/wzx/babiq/server/api/method/ThreadCreateHandlerTest.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ThreadCreateHandlerTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void creates_thread_and_returns_id() throws Exception {
        var svc = new ConversationService();
        var h   = new ThreadCreateHandler(svc);
        var result = (Map<?, ?>) h.handle(om.valueToTree(Map.of("cwd", ".")), mock(WebSocketSession.class));
        assertThat(result.get("threadId")).asString().startsWith("thr_");
    }

    @Test
    void missing_cwd_throws_invalid_params() {
        var h = new ThreadCreateHandler(new ConversationService());
        assertThatThrownBy(() -> h.handle(om.valueToTree(Map.of()), mock(WebSocketSession.class)))
                .isInstanceOf(JsonRpcException.class);
    }
}
```

- [ ] **Step 7.2: 实现 ThreadCreateHandler**

Create `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadCreateHandler.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

@Component
public class ThreadCreateHandler implements JsonRpcMethodHandler {

    private final ConversationService svc;

    public ThreadCreateHandler(ConversationService svc) { this.svc = svc; }

    @Override public String method() { return "thread/create"; }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        if (params == null || !params.hasNonNull("cwd")) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "missing field 'cwd'");
        }
        Thread t = svc.createThread(params.get("cwd").asText());
        return Map.of("threadId", t.id());
    }
}
```

- [ ] **Step 7.3: TDD + 实现 TurnCancelHandler**

Create test `backend/src/test/java/com/wzx/babiq/server/api/method/TurnCancelHandlerTest.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TurnCancelHandlerTest {
    private final ObjectMapper om = new ObjectMapper();

    @Test
    void cancels_running_turn() throws Exception {
        var svc = new ConversationService();
        Thread thr = svc.createThread(".");
        Turn t = svc.startTurn(thr.id());
        t.start();
        var h = new TurnCancelHandler(svc);
        h.handle(om.valueToTree(Map.of("turnId", t.id())), mock(WebSocketSession.class));
        assertThat(t.status()).isEqualTo(TurnStatus.CANCELED);
    }

    @Test
    void unknown_turn_throws_invalid_params() {
        var h = new TurnCancelHandler(new ConversationService());
        assertThatThrownBy(() -> h.handle(om.valueToTree(Map.of("turnId", "nope")), mock(WebSocketSession.class)))
                .isInstanceOf(JsonRpcException.class);
    }
}
```

Create `backend/src/main/java/com/wzx/babiq/server/api/method/TurnCancelHandler.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Turn;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

@Component
public class TurnCancelHandler implements JsonRpcMethodHandler {
    private final ConversationService svc;
    public TurnCancelHandler(ConversationService svc) { this.svc = svc; }
    @Override public String method() { return "turn/cancel"; }
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        if (params == null || !params.hasNonNull("turnId")) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "missing 'turnId'");
        }
        Turn t = svc.findTurn(params.get("turnId").asText())
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "unknown turnId"));
        try { t.cancel(); }
        catch (IllegalStateException ise) {
            throw new JsonRpcException(JsonRpcErrorCode.SERVER_ERROR, ise.getMessage());
        }
        return Map.of("ok", true);
    }
}
```

- [ ] **Step 7.4: 实现 4 个 mock handler(占位实现)**

Create `backend/src/main/java/com/wzx/babiq/server/api/method/TurnInterruptHandler.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

@Component
public class TurnInterruptHandler implements JsonRpcMethodHandler {
    @Override public String method() { return "turn/interrupt"; }
    @Override public Object handle(JsonNode params, WebSocketSession session) {
        // P1-1 mock:真实中断在 P1-3a 接 HumanInTheLoopHook 后实现
        return Map.of("ok", true, "mock", true);
    }
}
```

Create `backend/src/main/java/com/wzx/babiq/server/api/method/ApprovalRespondHandler.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

@Component
public class ApprovalRespondHandler implements JsonRpcMethodHandler {
    @Override public String method() { return "approval/respond"; }
    @Override public Object handle(JsonNode params, WebSocketSession session) {
        // P1-1 mock,真实路径在 P1-3a
        return Map.of("ok", true, "mock", true);
    }
}
```

Create `backend/src/main/java/com/wzx/babiq/server/api/method/ProvidersListHandler.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

@Component
public class ProvidersListHandler implements JsonRpcMethodHandler {
    @Override public String method() { return "model/providers/list"; }
    @Override public Object handle(JsonNode params, WebSocketSession session) {
        // P1-1 hardcoded mock list,P1-2 由 ModelProviderRegistry 替换
        return Map.of("providers", List.of(
                Map.of("id", "mock-provider", "label", "Mock (P1-1 placeholder)")
        ));
    }
}
```

Create `backend/src/main/java/com/wzx/babiq/server/api/method/ProvidersSetActiveHandler.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

@Component
public class ProvidersSetActiveHandler implements JsonRpcMethodHandler {
    @Override public String method() { return "model/providers/set-active"; }
    @Override public Object handle(JsonNode params, WebSocketSession session) {
        return Map.of("ok", true, "mock", true);
    }
}
```

- [ ] **Step 7.5: 跑测试(绿)**

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=ThreadCreateHandlerTest,TurnCancelHandlerTest
cd ..
```

Expected: `Tests run: 4, Failures: 0`。

- [ ] **Step 7.6: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/method/
git add backend/src/test/java/com/wzx/babiq/server/api/method/
git commit -m "feat(p1-1): 实现 7 个 JSON-RPC 方法 handler(2 个真实业务 + 5 个 P1-1 占位)"
```

---

## Task 8: TurnStartHandler + Mock Item 流(P1-1 核心)

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`

> 这是 **P1-1 唯一会真的发 item 通知的 handler**。完整流程(对应 M1 验收):
> 1. Request 解析 → 创建 Turn → `t.start()`
> 2. 同步返回 `{turnId}` 给 client
> 3. 异步线程发 `turn/started` → `item/added (userMessage)` → `item/added (agentMessage, mock 文本)` → `t.complete()` → `turn/completed (status=completed)`
> 4. 异步用 `@Async` + Spring 默认 `TaskExecutor`(D17 之外不引线程池配置,默认够用)

- [ ] **Step 8.0: 加 awaitility 测试依赖**

在 `backend/pom.xml` 的 `<dependencies>` 节,scope=test 区域追加:
```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

验证:
```powershell
cd backend
.\mvnw.cmd dependency:tree | Select-String "awaitility"
cd ..
```
Expected: 输出包含 `org.awaitility:awaitility:5.x.x:test`。

Commit:
```powershell
git add backend/pom.xml
git commit -m "chore(p1-1): 加 awaitility 测试依赖(异步流测试需要)"
```

- [ ] **Step 8.1: TDD — 写 TurnStartHandlerTest(用 mock session 抓 emit 序列)**

Create `backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TurnStartHandlerTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void emits_full_mock_item_stream() throws Exception {
        var svc = new ConversationService();
        Thread thr = svc.createThread(".");

        var session = mock(WebSocketSession.class);
        var h = new TurnStartHandler(svc, om, "hello from babiq");

        Object result = h.handle(
                om.valueToTree(Map.of(
                        "threadId", thr.id(),
                        "input", Map.of("type", "text", "text", "ping"))),
                session);
        assertThat(((Map<?, ?>) result).get("turnId")).asString().startsWith("turn_");

        // 等异步任务完成,最多 1 秒
        var captor = ArgumentCaptor.forClass(TextMessage.class);
        await().atMost(java.time.Duration.ofSeconds(1))
               .untilAsserted(() -> verify(session, atLeast(4)).sendMessage(captor.capture()));

        List<String> payloads = captor.getAllValues().stream().map(TextMessage::getPayload).toList();
        // 必须按序看到 4 个 notification
        assertThat(payloads.get(0)).contains("\"method\":\"turn/started\"");
        assertThat(payloads.get(1)).contains("\"method\":\"item/added\"")
                                   .contains("\"type\":\"userMessage\"")
                                   .contains("\"text\":\"ping\"");
        assertThat(payloads.get(2)).contains("\"method\":\"item/added\"")
                                   .contains("\"type\":\"agentMessage\"")
                                   .contains("hello from babiq");
        assertThat(payloads.get(3)).contains("\"method\":\"turn/completed\"")
                                   .contains("\"status\":\"completed\"");
    }
}
```

> ⚠️ awaitility 依赖已在 Step 8.0 正式引入。

- [ ] **Step 8.2: 实现 TurnStartHandler**

Create `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`:
```java
package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class TurnStartHandler implements JsonRpcMethodHandler {

    private static final Logger log = LoggerFactory.getLogger(TurnStartHandler.class);

    private final ConversationService svc;
    private final ObjectMapper om;
    private final String mockAgentText;

    public TurnStartHandler(ConversationService svc,
                            ObjectMapper om,
                            @Value("${babiq.protocol.mock-agent-text:hello from babiq}") String mockAgentText) {
        this.svc = svc;
        this.om = om;
        this.mockAgentText = mockAgentText;
    }

    @Override public String method() { return "turn/start"; }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        if (params == null || !params.hasNonNull("threadId") || !params.hasNonNull("input")) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "missing 'threadId' or 'input'");
        }
        String threadId = params.get("threadId").asText();
        String userText = params.get("input").path("text").asText("");

        Turn t = svc.startTurn(threadId);
        t.start();

        // 同步先返回 turnId,然后异步发 mock 流
        var emitter = new ItemEmitter(session, om, threadId, t.id());
        // TODO P1-2: 替换为专用 Executor(接真模型后 ForkJoinPool 阻塞会影响整个 JVM)
        CompletableFuture.runAsync(() -> runMockStream(emitter, t, userText));

        return Map.of("turnId", t.id());
    }

    private void runMockStream(ItemEmitter e, Turn t, String userText) {
        try {
            e.emitTurnStarted();
            e.emitItemAdded(UserMessageItem.of("it_" + shortId(), userText));
            e.emitItemAdded(AgentMessageItem.full("it_" + shortId(), mockAgentText));
            t.complete();
            e.emitTurnCompleted("completed");
        } catch (Exception ex) {
            log.error("Mock stream failed for turn {}", t.id(), ex);
            try {
                if (!t.status().isTerminal()) t.fail(ex.getMessage());
                e.emitTurnFailed(ex.getMessage());
            } catch (Exception ignored) {}
        }
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
```

- [ ] **Step 8.3: 跑测试(绿)**

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=TurnStartHandlerTest
cd ..
```

Expected: `Tests run: 1, Failures: 0`。

- [ ] **Step 8.4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java
git add backend/src/test/java/com/wzx/babiq/server/api/method/TurnStartHandlerTest.java
git commit -m "feat(p1-1): TurnStartHandler 异步发 mock item 流(P1-1 不接 LLM)"
```

---

## Task 9: WebSocket Handler + 端点注册

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/config/WebSocketConfig.java`

- [ ] **Step 9.1: 实现 JsonRpcWebSocketHandler**

Create `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java`:
```java
package com.wzx.babiq.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class JsonRpcWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcWebSocketHandler.class);

    private final JsonRpcDispatcher dispatcher;
    private final ObjectMapper om;

    public JsonRpcWebSocketHandler(JsonRpcDispatcher dispatcher, ObjectMapper om) {
        this.dispatcher = dispatcher;
        this.om = om;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WS connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage msg) {
        JsonRpcMessage out;
        Long requestId = null;
        try {
            JsonRpcMessage.Request req = om.readValue(msg.getPayload(), JsonRpcMessage.Request.class);
            requestId = req.id();
            if (!"2.0".equals(req.jsonrpc()) || req.method() == null) {
                out = JsonRpcMessage.ErrorResponse.of(requestId,
                        JsonRpcErrorCode.INVALID_REQUEST, "Invalid JSON-RPC envelope", null);
            } else {
                out = dispatcher.dispatch(req, session);
            }
        } catch (JsonProcessingException jpe) {
            out = JsonRpcMessage.ErrorResponse.of(null,
                    JsonRpcErrorCode.PARSE_ERROR, "Parse error: " + jpe.getOriginalMessage(), null);
        } catch (Exception e) {
            log.error("WS handler error", e);
            out = JsonRpcMessage.ErrorResponse.of(requestId,
                    JsonRpcErrorCode.INTERNAL_ERROR, e.getMessage(), null);
        }

        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(om.writeValueAsString(out)));
            }
        } catch (Exception sendEx) {
            log.error("WS send failed", sendEx);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WS closed: {} ({})", session.getId(), status);
    }
}
```

- [ ] **Step 9.2: 实现 WebSocketConfig**

Create `backend/src/main/java/com/wzx/babiq/server/config/WebSocketConfig.java`:
```java
package com.wzx.babiq.server.config;

import com.wzx.babiq.server.api.JsonRpcWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JsonRpcWebSocketHandler handler;

    @Value("${babiq.ws.path:/ws/agent}")
    private String path;

    @Value("${babiq.ws.allowed-origins:*}")
    private String allowedOrigins;

    public WebSocketConfig(JsonRpcWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, path).setAllowedOrigins(allowedOrigins);
    }
}
```

- [ ] **Step 9.3: 编译 + 启动验证**

Run:
```powershell
cd backend
.\mvnw.cmd clean package -DskipTests
.\mvnw.cmd spring-boot:run
```

Expected 输出:
```
Tomcat started on port 8080 (http)
Started BaBiQApplication in X.XXX seconds
```

按 Ctrl+C 停。

```powershell
cd ..
```

- [ ] **Step 9.4: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java
git add backend/src/main/java/com/wzx/babiq/server/config/WebSocketConfig.java
git commit -m "feat(p1-1): JsonRpcWebSocketHandler + /ws/agent 端点注册"
```

---

## Task 10: Logback JSON 基础配置(D17 雏形)

**Files:**
- Create: `backend/src/main/resources/logback-spring.xml`

> P1-1 只搭骨架(让所有日志走 STDOUT 的 JSON layout),真正的 turn/token 字段在 P1-3b 完善。这里只需 logstash-logback-encoder 或简化的自定义 pattern。**为避免在 P1-1 引入新依赖,本步骤用 Logback 内置 pattern 模拟 JSON 单行**,真正切到 logstash-logback-encoder 留给 P1-3b。

- [ ] **Step 10.1: 写 logback-spring.xml(pattern 化 JSON)**

Create `backend/src/main/resources/logback-spring.xml`:
```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>{"ts":"%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}","level":"%level","logger":"%logger{32}","thread":"%thread","msg":"%replace(%msg){'"','\\"'}"}%n</pattern>
        </encoder>
    </appender>

    <logger name="com.wzx.babiq.server" level="DEBUG"/>
    <logger name="org.springframework.web.socket" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

> ⚠️ `%msg` 中可能包含双引号,`%replace` 做基本转义。**生产级 JSON logging 用 logstash-logback-encoder**,在 P1-3b 替换。

- [ ] **Step 10.2: 启动看 JSON 行**

Run:
```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Expected: 控制台输出以 `{"ts":"...` 开头的每行 JSON 日志。Ctrl+C 退出。

```powershell
cd ..
```

- [ ] **Step 10.3: Commit**

```powershell
git add backend/src/main/resources/logback-spring.xml
git commit -m "chore(p1-1): Logback JSON pattern 雏形(D17 占位,P1-3b 升级)"
```

---

## Task 11: 端到端集成测试(@SpringBootTest)

**Files:**
- Create: `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandlerIT.java`

> 启动真 Spring Boot context + 真 WebSocket,通过 `StandardWebSocketClient` 完成 `thread/create` → `turn/start` → 收齐 4 个 notification。这是 M1 自动化验收的核心。

- [ ] **Step 11.1: 写集成测试**

Create `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandlerIT.java`:
```java
package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JsonRpcWebSocketHandlerIT {

    @LocalServerPort int port;
    @Autowired ObjectMapper om;

    @Test
    void full_handshake_and_mock_turn() throws Exception {
        List<String> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(5);   // 1 response + 4 notifications

        var client = new StandardWebSocketClient();
        WebSocketSession sess = client.execute(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
                received.add(msg.getPayload());
                latch.countDown();
            }
        }, "ws://localhost:" + port + "/ws/agent").get();

        // 1. thread/create
        sess.sendMessage(new TextMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"thread/create\",\"params\":{\"cwd\":\".\"}}"));

        // 等首个回应,提取 threadId
        while (received.isEmpty()) java.lang.Thread.sleep(20);
        JsonNode resp1 = om.readTree(received.get(0));
        String threadId = resp1.path("result").path("threadId").asText();
        assertThat(threadId).startsWith("thr_");

        // 2. turn/start
        sess.sendMessage(new TextMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"turn/start\",\"params\":{"
              + "\"threadId\":\"" + threadId + "\","
              + "\"input\":{\"type\":\"text\",\"text\":\"ping\"}}}"));

        assertThat(latch.await(3, TimeUnit.SECONDS))
                .as("应收到 5 条消息:1 response (turn/start) + 4 notifications").isTrue();

        sess.close();

        String all = String.join("\n", received);
        assertThat(all).contains("\"method\":\"turn/started\"")
                       .contains("\"type\":\"userMessage\"")
                       .contains("\"type\":\"agentMessage\"")
                       .contains("hello from babiq")
                       .contains("\"method\":\"turn/completed\"");
    }

    @Test
    void unknown_method_returns_minus32601() throws Exception {
        List<String> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        var client = new StandardWebSocketClient();
        WebSocketSession sess = client.execute(new TextWebSocketHandler() {
            @Override protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
                received.add(msg.getPayload());
                latch.countDown();
            }
        }, URI.create("ws://localhost:" + port + "/ws/agent")).get();

        sess.sendMessage(new TextMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"no/such\",\"params\":{}}"));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get(0)).contains("\"code\":-32601");
        sess.close();
    }
}
```

- [ ] **Step 11.2: 跑集成测试**

Run:
```powershell
cd backend
.\mvnw.cmd test -Dtest=JsonRpcWebSocketHandlerIT
cd ..
```

Expected: `Tests run: 2, Failures: 0`。

- [ ] **Step 11.3: 跑全量测试**

Run:
```powershell
cd backend
.\mvnw.cmd clean test
cd ..
```

Expected: `BUILD SUCCESS`,所有(估算 20+ 个)测试通过。

- [ ] **Step 11.4: Commit**

```powershell
git add backend/src/test/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandlerIT.java
git commit -m "test(p1-1): 端到端 WebSocket 集成测试覆盖完整握手 + 错误码 -32601"
```

---

## Task 12: wscat 人工烟测(对应 M1 硬验收)

**Files:** 无(纯命令验证)

- [ ] **Step 12.1: 启动 backend**

Open **窗口 A**:
```powershell
cd F:\wwwxxxx\BaBiQ\backend
.\mvnw.cmd spring-boot:run
```

Expected: `Started BaBiQApplication`,端口 8080。

- [ ] **Step 12.2: wscat 连接**

Open **窗口 B**(可能需先 `npm install -g wscat`):
```powershell
wscat -c ws://localhost:8080/ws/agent
```

Expected: `Connected (press CTRL+C to quit)`。

- [ ] **Step 12.3: 发 thread/create**

In wscat 窗口键入(单行):
```json
{"jsonrpc":"2.0","method":"thread/create","id":1,"params":{"cwd":"."}}
```

Expected — 立即收到:
```json
{"jsonrpc":"2.0","id":1,"result":{"threadId":"thr_xxxxxx"}}
```
记录 `threadId`(下一步用)。

- [ ] **Step 12.4: 发 turn/start(替换 thr_xxxxxx)**

In wscat:
```json
{"jsonrpc":"2.0","method":"turn/start","id":2,"params":{"threadId":"thr_xxxxxx","input":{"type":"text","text":"ping"}}}
```

Expected — 顺序收到 5 条消息:
1. `{"jsonrpc":"2.0","id":2,"result":{"turnId":"turn_xxxxxx"}}`
2. `{"jsonrpc":"2.0","method":"turn/started","params":{"threadId":"...","turnId":"..."}}`
3. `{"jsonrpc":"2.0","method":"item/added","params":{...,"item":{...,"type":"userMessage","text":"ping"}}}`
4. `{"jsonrpc":"2.0","method":"item/added","params":{...,"item":{...,"type":"agentMessage","text":"hello from babiq"}}}`
5. `{"jsonrpc":"2.0","method":"turn/completed","params":{...,"status":"completed"}}`

- [ ] **Step 12.5: 发 unknown method 验证 -32601**

In wscat:
```json
{"jsonrpc":"2.0","method":"no/such","id":3,"params":{}}
```

Expected:
```json
{"jsonrpc":"2.0","id":3,"error":{"code":-32601,"message":"Method not found: no/such"}}
```

- [ ] **Step 12.6: 发 invalid params 验证 -32602**

In wscat(thread/create 不带 cwd):
```json
{"jsonrpc":"2.0","method":"thread/create","id":4,"params":{}}
```

Expected:
```json
{"jsonrpc":"2.0","id":4,"error":{"code":-32602,"message":"missing field 'cwd'"}}
```

- [ ] **Step 12.7: 退出 wscat,关闭 backend**

In wscat: Ctrl+C。
In backend: Ctrl+C。

- [ ] **Step 12.8: 记录验收**

无 commit;此 Task 仅手动验收 M1。如有任一项不符,回到对应 Task 修复。

---

## Task 13: 收尾 — 同步 ARCHITECTURE + 打 tag

**Files:**
- Modify: `docs/ARCHITECTURE.md`(若 §3 目录有任何与本 plan 文件结构不一致处,微调)

- [ ] **Step 13.1: 校验目录结构一致性**

Run:
```powershell
Select-String -Path docs\ARCHITECTURE.md -Pattern "JsonRpcWebSocketHandler|JsonRpcDispatcher|TurnInterruptHandler"
```

Expected: 至少 `JsonRpcWebSocketHandler` 有匹配。若 §3 文档结构与本 plan 文件结构有出入,**只补漏失类名**(`TurnInterruptHandler` / `ProvidersListHandler` / `ProvidersSetActiveHandler` / `JsonRpcDispatcher` / `JsonRpcMethodHandler` / `ItemEmitter`),**不动整体结构**(避免影响后续 P1-2 / P1-3 plan)。

- [ ] **Step 13.2: 全量 verification**

Run:
```powershell
cd backend
.\mvnw.cmd clean verify
cd ..
```

Expected: `BUILD SUCCESS`,所有 unit + IT 测试通过。

- [ ] **Step 13.3: 终态 commit**

```powershell
git add -A
git status
git commit -m "docs(p1-1): 同步 ARCHITECTURE 协议层文件清单(若有 diff)" --allow-empty
git log --oneline -20
```

Expected:
- 看到 P1-1 期间约 10-12 个 commit
- **不要 push,不要打 tag**(由用户自己决定)

---

## Done Criteria(M1 整体硬验收)

逐项检查,任一项不达成都需回到对应 Task 修复。

### 自动化(必须全过)
- [ ] `cd backend && .\mvnw.cmd clean verify` 全绿
- [ ] `JsonRpcMessageTest`(3 测试)、`TurnStatusMachineTest`(7 测试)、`ConversationServiceTest`(4 测试)、`JsonRpcDispatcherTest`(3 测试)、`ThreadCreateHandlerTest`(2 测试)、`TurnCancelHandlerTest`(2 测试)、`TurnStartHandlerTest`(1 测试)、`ThreadItemJsonTest`(3 测试)、`JsonRpcWebSocketHandlerIT`(2 测试)全部通过
- [ ] `babiq-server-0.0.1-SNAPSHOT.jar` 打包成功

### 文件结构(必须存在)
- [ ] `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java`
- [ ] `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcMessage.java`(含 `sealed` 关键字 + 4 个 record)
- [ ] `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java`
- [ ] `backend/src/main/java/com/wzx/babiq/server/api/method/` 包含 7 个 handler
- [ ] `backend/src/main/java/com/wzx/babiq/server/api/error/JsonRpcErrorCode.java`(6 个枚举值)
- [ ] `backend/src/main/java/com/wzx/babiq/server/conversation/Turn.java` + `TurnStatus.java`(6 个枚举值(3 非终态 CREATED/RUNNING/WAITING_APPROVAL + 3 终态 COMPLETED/FAILED/CANCELED))
- [ ] `backend/src/main/java/com/wzx/babiq/server/conversation/items/` 含 1 个 sealed interface + 12 个 record
- [ ] `backend/src/main/java/com/wzx/babiq/server/config/WebSocketConfig.java`(`@EnableWebSocket`)
- [ ] `backend/src/main/resources/logback-spring.xml`

### 协议契约(wscat / IT 验证)
- [ ] `ws://localhost:8080/ws/agent` 可连
- [ ] `thread/create` 返回带 `thr_` 前缀的 `threadId`
- [ ] `turn/start` 返回 `turn_` 前缀的 `turnId`,异步顺序推 `turn/started` → `item/added (userMessage)` → `item/added (agentMessage, "hello from babiq")` → `turn/completed (status="completed")`
- [ ] unknown method 错误码 `-32601`
- [ ] missing required param 错误码 `-32602`
- [ ] 任意 handler 内部抛非 `JsonRpcException` 时错误码 `-32000`(`SERVER_ERROR`)

### 决策合规
- [ ] D1:WebSocket + JSON-RPC 2.0 完全自写,**未引入 jsonrpc4j / 其他 RPC 库**
- [ ] D2:用 `spring-boot-starter-websocket` 原生 API,**未引 Netty**
- [ ] D3:`ThreadItem` 是 `sealed interface`,12 个子类是 `record`
- [ ] D22:每个 Item record 的必填字段均带 `@JsonProperty(required=true)`;P1-1 **未**调用 `chatClient.entity(...)`(因为没接 LLM)
- [ ] D12:配置经由 `application.yml`(无 `.properties`)
- [ ] D17:Logback 输出 JSON 单行(雏形)
- [ ] **未引入 `spring-ai-alibaba`** 任何坐标(P1-2 才能引)
- [ ] 端口固定 `8080`

### Git
- [ ] 主分支 master 上有 10-12 个原子 commit,中文 commit message(范例:`feat(p1-1): 实现 JsonRpcWebSocketHandler 基础框架`)
- [ ] **不要 push,不要打 tag**(由用户自行决定 push 时机)
- [ ] 工作树 clean(`git status` 输出 `nothing to commit`)

---

## 风险提示

| 风险 | 严重度 | 缓解 |
|---|---|---|
| **Jackson 多态反序列化 `@JsonSubTypes` 字段冲突** | 中 | 用 `include=EXISTING_PROPERTY` + `visible=true` 把 `type` 同时作为类型 tag 和 record component(已写入 Task 4) |
| **异步 mock 流在 IT 中未到达就关连接** | 中 | IT 用 `CountDownLatch(5) + 3s timeout`,本地 wscat 用肉眼等待(总耗时 < 100ms) |
| **`@SpringBootTest` 启动慢** | 低 | 仅 1 个 `*IT` 类,共享 context 不重复加载 |
| **Logback pattern 含双引号字面量导致 XML 报错** | 低 | 已用 `'"'` escape,Step 10.2 烟测会暴露 |
| **`session.sendMessage` 跨线程并发** | 中 | `ItemEmitter` + `JsonRpcWebSocketHandler` 均对 `session` 做 `synchronized`(已写入 Task 5 / Task 9) |
| **wscat 未装** | 低 | M1 验收以 IT 为主,wscat 仅人工二次确认;`npm install -g wscat` 即可装上 |
| **runMockStream 用 ForkJoinPool** | 中 | P1-1 流量低无碍;P1-2 接真模型时**必须**替换专用 Executor |

---

## 完成后下一步

P1-1 落地后:

1. 跑 **superpowers:verification-before-completion** 跨步验收(由用户/上游驱动)
2. 让规划助手为 **P1-2(Provider 层)** 写详细 plan(`docs/superpowers/plans/p1-2-providers/plan.md`),引入 `spring-ai-alibaba-bom` 1.1.2.x + `MessageWindowChatMemory(20)` + `MessageChatMemoryAdvisor` + 双工厂(DashScope + OpenAI Compatible)
3. P1-3a / P1-3b 顺序紧跟,见 master plan §2 依赖图
