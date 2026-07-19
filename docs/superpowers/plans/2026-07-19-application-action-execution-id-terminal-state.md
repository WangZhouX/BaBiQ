# Application Action executionId 与失败终态修复 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让需要服务端执行 ID 的业务动作自动获得权威 `executionId`，并让桌面持久化失败立即以 `FAILED` 终态返回后端。

**Architecture:** 后端在读取动作 descriptor 后按保留字段契约规范化输入：完全未声明 `executionId` 时保持原样，正确声明为 required string 时注入服务端 ID，非法声明 fail-closed。桌面运行时以持久化终态为发布真相源，只有没有持久化记录的准入拒绝才发布 `REJECTED`。

**Tech Stack:** Java 21、Spring Boot、Jackson、JUnit 5、Mockito、Kotlin、Coroutines、Kotlin Test、Compose Desktop、SQLite、JSON-RPC 2.0

---

## Chunk 1: 后端输入规范化

### Task 1: 为保留 executionId 契约编写失败测试

**Files:**
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/tool/ApplicationActionToolTest.java`
- Reference: `backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationActionTool.java:178-235`

- [ ] **Step 1: 扩展测试 descriptor helper**

给 `catalogPayload(...)` 增加可配置的 `inputSchema`，并提供 required string schema：

```java
private ObjectNode requiredExecutionIdSchema() {
    ObjectNode schema = json.createObjectNode()
            .put("type", "object")
            .put("additionalProperties", false);
    schema.putObject("properties").putObject("executionId").put("type", "string");
    schema.putArray("required").add("executionId");
    return schema;
}
```

- [ ] **Step 2: 编写空输入注入和覆盖测试**

新增测试，调用方传入 `{}` 以及伪造 ID，捕获 `sendActionRequest` 的 payload，并断言：

```java
assertThat(outbound.path("input").path("executionId").asText())
        .isEqualTo("execution-fixed");
assertThat(original.has("executionId")).isFalse();
```

同时捕获 `RegistrationMetadata.requestFingerprint()`，断言空输入和伪造 ID在规范化为相同权威输入后得到相同指纹。

- [ ] **Step 3: 编写 schema 边界测试**

覆盖以下行为：

```text
schema 未声明 executionId       -> 输入原样发送
executionId optional string     -> validation_failed
executionId required integer    -> validation_failed
properties/required 结构错误    -> validation_failed
```

所有非法 descriptor 用例都断言 `pending.register` 和 `protocol.sendActionRequest` 从未调用。

- [ ] **Step 4: 编写注入后尺寸超限测试**

构造注入前仍在 64 KiB 内、添加 `executionId` 后超过 `ApplicationProtocolValidator.MAX_ACTION_INPUT_BYTES` 的对象，断言返回 `validation_failed`，且 pending action 尚未注册。

- [ ] **Step 5: 运行测试确认 RED**

Run:

```powershell
cd backend
.\mvnw.cmd "-Dtest=ApplicationActionToolTest" test
```

Expected: 新增注入测试因 `input.executionId` 缺失而失败；非法 schema 和注入后尺寸测试因现有代码未实现保留字段契约而失败。

### Task 2: 实现 descriptor 驱动的输入规范化

**Files:**
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationActionTool.java:178-235`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/tool/ApplicationActionToolTest.java`

- [ ] **Step 1: 增加保留字段契约解析**

在 `ApplicationActionTool` 内增加最小私有类型与方法：

```java
private enum ExecutionIdContract { ABSENT, REQUIRED_STRING }

private ExecutionIdContract executionIdContract(JsonNode descriptor) {
    JsonNode schema = descriptor.get("inputSchema");
    if (schema == null || schema.isNull()) return ExecutionIdContract.ABSENT;
    if (!schema.isObject()) throw invalidExecutionIdSchema();
    JsonNode properties = schema.get("properties");
    JsonNode requiredNode = schema.get("required");
    if (properties != null && !properties.isObject()) throw invalidExecutionIdSchema();
    if (requiredNode != null && !requiredNode.isArray()) throw invalidExecutionIdSchema();
    JsonNode executionId = properties == null ? null : properties.get("executionId");
    boolean required = requiredNode != null
            && StreamSupport.stream(requiredNode.spliterator(), false)
                    .anyMatch(node -> node.isTextual() && "executionId".equals(node.textValue()));
    if (executionId == null && !required) return ExecutionIdContract.ABSENT;
    if (executionId == null
            || !executionId.isObject()
            || !"string".equals(executionId.path("type").asText())
            || !required) {
        throw invalidExecutionIdSchema();
    }
    return ExecutionIdContract.REQUIRED_STRING;
}
```

若 `required` 声明 `executionId` 但 `properties` 缺失，或 `properties` 声明该字段但 `required` 缺失，同样必须进入 `validation_failed`。`inputSchema`、`properties` 或 `required` 存在但结构类型错误时也 fail-closed，不能降级为 ABSENT。

- [ ] **Step 2: 规范化输入且不修改调用方对象**

生成 `executionId` 后：

```java
JsonNode normalizedInput = normalizeInput(descriptor, input, executionId);
```

`normalizeInput` 对 ABSENT 返回 `input.deepCopy()`；对 REQUIRED_STRING 复制成 `ObjectNode` 后覆盖：

```java
normalized.put("executionId", executionId);
```

- [ ] **Step 3: 对规范化输入重新做尺寸校验**

复用 `ApplicationProtocolValidator.validateActionInputSize(...)` 校验 `normalizedInput`，超限转为 `ActionValidation("validation_failed", ...)`。该步骤必须发生在 `pending.register(...)` 前。

- [ ] **Step 4: 统一真实请求与指纹输入**

将 `requestFingerprint(...)` 和 `requestPayload(...)` 的 input 参数都替换为 `normalizedInput`，保证指纹、发送载荷和桌面执行输入一致。

- [ ] **Step 5: 运行定向测试确认 GREEN**

Run:

```powershell
cd backend
.\mvnw.cmd "-Dtest=ApplicationActionToolTest" test
```

Expected: `ApplicationActionToolTest` 全部通过，0 failures、0 errors。

- [ ] **Step 6: 提交后端输入修复**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationActionTool.java backend/src/test/java/com/wzx/babiq/server/application/tool/ApplicationActionToolTest.java
git commit -m "fix: 注入业务动作权威执行标识"
```

---

## Chunk 2: 桌面失败终态发布

### Task 3: 用失败测试锁定持久化终态优先

**Files:**
- Modify: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/application/ApplicationActionRequestHandlerTest.kt:460-550`
- Reference: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationActionExecutionRuntime.kt:260-292`
- Reference: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationActionStatusClient.kt:70-180`

- [ ] **Step 1: 修改持久化 FAILED + bus rejection 的期望**

把现有“persisted failed record waits for bus rejection classification”回归场景改为断言：

```kotlin
assertEquals(
    listOf(
        ApplicationMethod.ACTION_ACCEPTED.wireName,
        ApplicationMethod.ACTION_FAILED.wireName,
    ),
    fixture.connection.sent.mapNotNull(::methodOrNull),
)
```

并断言 payload：

```kotlin
assertEquals("failed", payload.getValue("state").jsonPrimitive.content)
assertEquals("validation_failed", payload.getValue("errorCode").jsonPrimitive.content)
```

测试记录应使用 `ActionExecutionState.FAILED` 和 `ActionResult.Failure(ActionErrorCode.VALIDATION_FAILED, ...)`。

- [ ] **Step 2: 保留真正准入拒绝测试**

确认 `pre-create rejection is emitted when execution store has no record` 仍断言 `ACTION_REJECTED`，防止把所有拒绝错误改成 FAILED。

- [ ] **Step 3: 运行测试确认 RED**

Run:

```powershell
cd business-desktop
.\gradlew.bat :agent-client-core:test --tests "*ApplicationActionRequestHandlerTest"
```

Expected: 持久化 FAILED 场景仍收到 `ACTION_REJECTED`，新断言失败。

### Task 4: 让持久化终态覆盖瞬时 rejection 分类

**Files:**
- Modify: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationActionExecutionRuntime.kt:280-290`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/application/ApplicationActionRequestHandlerTest.kt`

- [ ] **Step 1: 修改最终发布选择**

当 `finalRead` 是终态记录时，不再把 bus 的 `rejection` 传给 `PublicationIntent.Record`：

```kotlin
finalRead is ScopedRead.Found && finalRead.record.isTerminal ->
    owned.publicationSlot?.offerTerminal(
        PublicationIntent.Record(
            owned.publication,
            finalRead.record,
            projectedResult = completedResult,
        ),
    )
```

没有持久化终态且 `rejection != null` 的分支继续使用 `PublicationIntent.Rejected`。

- [ ] **Step 2: 运行定向测试确认 GREEN**

Run:

```powershell
cd business-desktop
.\gradlew.bat :agent-client-core:test --tests "*ApplicationActionRequestHandlerTest"
```

Expected: 全部通过；持久化 FAILED 发布 `application/action/failed`，pre-create rejection 仍发布 `rejected`。

- [ ] **Step 3: 提交桌面终态修复**

```powershell
git add business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationActionExecutionRuntime.kt business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/application/ApplicationActionRequestHandlerTest.kt
git commit -m "fix: 按持久化事实发布动作失败终态"
```

---

## Chunk 3: 跨边界闭环与全量验证

### Task 5: 增加真实动作和后端状态机闭环测试

**Files:**
- Modify: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessDesktopFrameworkIT.kt:80-120`
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/ApplicationBridgeEndToEndIT.java:235-320`

- [ ] **Step 1: 让真实 form.read_state 断言权威 ID输入和真实校验失败发布**

在 `BusinessDesktopFrameworkIT` 的真实 `form.read_state` 场景继续使用真实 `DemoActionCatalog`、`ApplicationActionBus`、SQLite 和 WebSocket。明确断言请求 envelope 顶层 `executionId` 与 `input.executionId` 相同，并成功收到 `ACTION_COMPLETED`。

再新增独立 `invalid-read` 请求，直接给真实 `form.read_state` 发送 `{}`，断言真实动作总线持久化：

```text
VALIDATING, EXECUTING, FAILED
```

协议通知必须是：

```text
accepted, running, failed
```

并断言 `errorCode=validation_failed`、不存在 `application/action/rejected`。空输入到权威输入的成功转换仍由后端 IT负责；此场景负责证明真实桌面解码失败会发布正确终态。

- [ ] **Step 2: 后端桥接 IT 断言 `{}` 被注入**

给 `ApplicationBridgeEndToEndIT` 的 `case.read` catalog descriptor 增加 required string `inputSchema`。保留工具调用 JSON中的 `"input":{}`，捕获 outbound request 后断言：

```java
assertThat(action.path("payload").path("input").path("executionId").asText())
        .isEqualTo(executionId);
```

- [ ] **Step 3: 后端桥接 IT 覆盖 EXECUTING -> FAILED**

新增场景：完成 accepted、running 后发送 `application/action/failed`，payload 带 `errorCode=validation_failed`。断言工具 future 在秒级返回 FAILED，持久化事件精确包含：

```text
REQUESTED, ACCEPTED, EXECUTING, FAILED
```

且不等待 business profile 的 120 秒 execute timeout。

- [ ] **Step 4: 运行双方集成定向测试**

Run:

```powershell
cd backend
.\mvnw.cmd "-Dtest=ApplicationBridgeEndToEndIT" test
```

Expected: PASS，0 failures、0 errors。

Run:

```powershell
cd business-desktop
.\gradlew.bat :app:test --tests "*BusinessDesktopFrameworkIT"
```

Expected: PASS，真实 `form.read_state` 进入 SUCCEEDED。

- [ ] **Step 5: 提交跨边界回归测试**

```powershell
git add backend/src/test/java/com/wzx/babiq/server/application/ApplicationBridgeEndToEndIT.java business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessDesktopFrameworkIT.kt
git commit -m "test: 覆盖业务动作执行标识与失败闭环"
```

### Task 6: 全量验证与最终审查

**Files:**
- Verify: `backend/`
- Verify: `business-desktop/`
- Verify: `docs/superpowers/specs/2026-07-19-application-action-execution-id-terminal-state-design.md`

- [ ] **Step 1: 运行后端全量验证**

```powershell
cd backend
.\mvnw.cmd clean verify
```

Expected: BUILD SUCCESS，单元测试和 IT 0 failures、0 errors。

- [ ] **Step 2: 运行业务桌面全量测试**

```powershell
cd business-desktop
.\gradlew.bat test
```

Expected: BUILD SUCCESSFUL，所有模块测试通过。

- [ ] **Step 3: 检查变更范围**

```powershell
git status --short
git diff --check HEAD~3..HEAD
git log -5 --oneline
```

Expected: 只包含本计划涉及的源代码、测试和文档；用户已有 `.tmp-gradle-review/` 保持未跟踪且未修改。

- [ ] **Step 4: 独立代码审查**

核对以下验收点：

- 仅合法声明 required string `executionId` 的动作被注入；未声明动作保持原样；非法声明 fail-closed。
- 规范化输入在注册前重新接受 64 KiB 校验。
- 指纹、出站 payload 和桌面实际输入完全一致。
- 持久化 FAILED 发布 failed；无持久化记录的准入拒绝仍发布 rejected。
- `{}` 调用和 EXECUTING -> FAILED 两条闭环均有自动化证据。

- [ ] **Step 5: 如审查要求调整，重新运行受影响定向测试和双方全量测试**
