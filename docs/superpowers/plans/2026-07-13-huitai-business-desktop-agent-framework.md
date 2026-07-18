# Huitai Business Desktop Agent Framework Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify an independent pure Kotlin Compose business desktop framework that lets users and the bundled BaBiQ Agent safely share one application-action pipeline, without implementing concrete OA business domains.

**Architecture:** Add a separate `business-desktop/` Gradle multi-project beside the existing `desktop/`. Keep the application-action domain pure Kotlin, adapt it to Compose, Ktor, SQLite, and the existing Java Agent backend through explicit ports, and run the backend in a locked-down `business-desktop` profile with isolated data and an allowlisted `application_action` tool.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.11.0, Ktor 3.5.0, kotlinx.serialization 1.11.0, kotlinx.coroutines 1.11.0, SQLite JDBC 3.53.1.0, Flyway 12.6.2, Java 21 desktop, Spring Boot 3.5.14, Spring AI 1.1.6, Spring AI Alibaba 1.1.2.3, JUnit 5, kotlin.test.

**Design Spec:** `docs/superpowers/specs/2026-07-13-huitai-business-desktop-agent-framework-design.md`

---

## File Map

### New Kotlin multi-project

```text
business-desktop/
├── settings.gradle.kts                         # module registry and repositories
├── build.gradle.kts                            # shared plugin versions and test defaults
├── gradle.properties                           # UTF-8, caching, JVM args without machine paths
├── gradlew / gradlew.bat / gradle/wrapper/*    # independent wrapper copied from desktop
├── application-action-core/                    # pure action domain, state machine, ports
├── presentation-core/                          # page state, context and Compose contracts
├── agent-client-core/                          # JSON-RPC and desktop action protocol client
├── huitai-integration-core/                    # auth, tenant, Ktor HTTP, replay/reconciliation
├── security-audit-core/                        # risk, approval, JCEKS, SQLite audit adapters
├── framework-demo/                             # generic form demo and demo actions
└── app/                                        # executable Compose app and bundled backend lifecycle
```

### New backend application bridge

```text
backend/src/main/java/com/wzx/babiq/server/application/
├── auth/                                       # local handshake and trusted identity binding
├── catalog/                                    # action catalog and page-context registry
├── action/                                     # pending action state and protocol models
└── tool/                                       # application_action Spring AI tool
```

### Existing backend anchors to modify

- `backend/src/main/java/com/wzx/babiq/server/config/WebSocketConfig.java`
- `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java`
- `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`
- `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`
- `backend/src/main/java/com/wzx/babiq/server/capability/CapabilityExposurePlanner.java`
- `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`
- `backend/src/main/java/com/wzx/babiq/server/recovery/TurnRecoveryService.java`
- `backend/src/main/resources/application-business-desktop.yml`
- `backend/src/main/resources/db/migration/V23__business_desktop_identity_scope.sql`

## Implementation Rules

- Run each behavior change through `superpowers:test-driven-development`: failing test, observed RED, minimal GREEN, regression, commit.
- New business framework code uses Chinese class/method/important-field comments, matching repository rules.
- Do not modify `huitai_cloud` or `huitai-law-oa` during this framework plan.
- Do not introduce `customer`, `case`, `document`, `lawyer` or other OA domain production models under `business-desktop/`.
- Keep `application-action-core` free of Compose, Ktor, SQLite, Spring, and file-system imports.
- Keep existing `desktop/` behavior and tests green.
- Stage and commit only files named by the current task.

---

## Chunk 1: Pure Kotlin Framework Core

### Task 1: Bootstrap the independent Gradle multi-project

This is build scaffolding rather than production behavior, so it uses command verification instead of a synthetic failing unit test.

**Files:**
- Create: `business-desktop/settings.gradle.kts`
- Create: `business-desktop/build.gradle.kts`
- Create: `business-desktop/gradle.properties`
- Create: `business-desktop/gradlew`
- Create: `business-desktop/gradlew.bat`
- Create: `business-desktop/gradle/wrapper/gradle-wrapper.jar`
- Create: `business-desktop/gradle/wrapper/gradle-wrapper.properties`
- Create: `business-desktop/application-action-core/build.gradle.kts`
- Create: `business-desktop/presentation-core/build.gradle.kts`
- Create: `business-desktop/agent-client-core/build.gradle.kts`
- Create: `business-desktop/huitai-integration-core/build.gradle.kts`
- Create: `business-desktop/security-audit-core/build.gradle.kts`
- Create: `business-desktop/framework-demo/build.gradle.kts`
- Create: `business-desktop/app/build.gradle.kts`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/architecture/ModuleStructureTest.kt`
- Modify: `.gitignore`

- [ ] **Step 1: Copy the verified Gradle wrapper**

Run:

```powershell
New-Item -ItemType Directory -Force business-desktop\gradle\wrapper
Copy-Item desktop\gradlew,desktop\gradlew.bat business-desktop\
Copy-Item desktop\gradle\wrapper\gradle-wrapper.jar business-desktop\gradle\wrapper\
Copy-Item desktop\gradle\wrapper\gradle-wrapper.properties business-desktop\gradle\wrapper\
```

Expected: `business-desktop\gradlew.bat --version` reports Gradle 9.3.0.

- [ ] **Step 2: Create the exact settings file**

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "huitai-business-desktop"
include(
    ":app",
    ":presentation-core",
    ":application-action-core",
    ":agent-client-core",
    ":huitai-integration-core",
    ":security-audit-core",
    ":framework-demo",
)
```

- [ ] **Step 3: Create the exact root build defaults**

```kotlin
plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    id("org.jetbrains.compose") version "1.11.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

allprojects {
    group = "com.wzx.huitai.desktop"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
```

Set `business-desktop/gradle.properties` to:

```properties
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
```

- [ ] **Step 4: Create the pure Kotlin module build files**

For `application-action-core`:

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin { jvmToolchain(21) }
```

Create `agent-client-core/build.gradle.kts` exactly as:

```kotlin
plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies {
    implementation(project(":application-action-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-cio:3.5.0")
    implementation("io.ktor:ktor-client-websockets:3.5.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
kotlin { jvmToolchain(21) }
```

Create `huitai-integration-core/build.gradle.kts` with the same content but replace the project dependency with the same `application-action-core` dependency and add `implementation("io.ktor:ktor-client-content-negotiation:3.5.0")` plus `implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")`.

Create `security-audit-core/build.gradle.kts` exactly as:

```kotlin
plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies {
    implementation(project(":application-action-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.53.1.0")
    implementation("org.flywaydb:flyway-core:12.6.2")
    implementation("org.flywaydb:flyway-database-nc-sqlite:12.6.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
kotlin { jvmToolchain(21) }
```

- [ ] **Step 5: Create the Compose module build files**

Create `presentation-core/build.gradle.kts` exactly as:

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation(project(":application-action-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
kotlin { jvmToolchain(21) }
```

Create `framework-demo/build.gradle.kts` from that exact file and replace its project dependency block with:

```kotlin
implementation(project(":presentation-core"))
implementation(project(":application-action-core"))
```

Create `app/build.gradle.kts` from the presentation file, add project dependencies on all six library modules, and append:

```kotlin
compose.desktop {
    application {
        mainClass = "com.wzx.huitai.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "HuitaiBusinessDesktop"
            packageVersion = "0.1.0"
            includeAllModules = true
        }
    }
}
```

Add `import org.jetbrains.compose.desktop.application.dsl.TargetFormat` at the top.

- [ ] **Step 6: Verify the project graph**

Run: `cd business-desktop; .\gradlew.bat projects`

Expected: exactly seven subprojects are printed and configuration succeeds.

- [ ] **Step 7: Add the exact-module architecture test**

Use this test body:

```kotlin
@Test
fun `settings contains exactly the approved modules`() {
    val settings = Path.of("..", "settings.gradle.kts").toFile().readText()
    val actual = Regex("\\\":([a-z-]+)\\\"")
        .findAll(settings)
        .map { it.groupValues[1] }
        .toSet()
    val expected = setOf(
        "app", "presentation-core", "application-action-core",
        "agent-client-core", "huitai-integration-core",
        "security-audit-core", "framework-demo",
    )
    assertEquals(expected, actual)
}
```

- [ ] **Step 8: Run the architecture test**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*ModuleStructureTest"`

Expected: one passing test.

- [ ] **Step 9: Update ignore rules and commit**

Append:

```gitignore
business-desktop/build/
business-desktop/.gradle/
business-desktop/.kotlin/
business-desktop/**/build/
business-desktop/gradle.properties.local
```

Then run:

```powershell
git add .gitignore business-desktop
git commit -m "build: 建立业务桌面多模块工程"
```

### Task 2: Define action models and the complete lifecycle matrix

**Files:**
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/model/ActionDescriptor.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/model/ActionCommand.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/model/ActionResult.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/model/ActionExecutionState.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/model/ActionError.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/ActionStateMachine.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/ActionStateMachineTest.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/ActionErrorVocabularyTest.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/ActionModelSerializationTest.kt`

- [ ] **Step 1: Write the failing lifecycle matrix test**

Define the allowed matrix in the test:

```kotlin
val common = setOf(
    RECEIVED to VALIDATING,
    RECEIVED to CANCELED,
    RECEIVED to EXPIRED,
    VALIDATING to FAILED,
    VALIDATING to CANCELED,
    VALIDATING to EXPIRED,
    PREVIEWED to FAILED,
    PREVIEWED to CANCELED,
    PREVIEWED to EXPIRED,
    WAITING_APPROVAL to FAILED,
    WAITING_APPROVAL to CANCELED,
    WAITING_APPROVAL to EXPIRED,
    EXECUTING to SUCCEEDED,
    EXECUTING to FAILED,
    EXECUTING to CANCELED,
    EXECUTING to EXPIRED,
    EXECUTING to OUTCOME_UNKNOWN,
    OUTCOME_UNKNOWN to SUCCEEDED,
    OUTCOME_UNKNOWN to FAILED,
)
```

Add risk-specific pairs:

```text
READ_ONLY:        VALIDATING -> EXECUTING
REVERSIBLE_WRITE: VALIDATING -> PREVIEWED -> EXECUTING
HIGH_RISK:        VALIDATING -> PREVIEWED -> WAITING_APPROVAL -> EXECUTING
```

Assert every other pair is rejected. This explicitly covers preview rejection (`PREVIEWED -> CANCELED`), approval denial (`WAITING_APPROVAL -> CANCELED`), failure, cancellation, expiry, and reconciliation.

- [ ] **Step 2: Run RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionStateMachineTest"`

Expected: compilation fails because the model and state machine do not exist.

- [ ] **Step 3: Implement the enums and transition result**

```kotlin
enum class ActionRiskLevel { READ_ONLY, REVERSIBLE_WRITE, HIGH_RISK }
enum class ActionOrigin { USER, AGENT }
enum class ActionReplayPolicy { SAFE, IDEMPOTENCY_KEY_REQUIRED, NEVER }
enum class ReconciliationPolicy { NONE, QUERY_REMOTE, MANUAL }
enum class ActionExecutionState {
    RECEIVED, VALIDATING, PREVIEWED, WAITING_APPROVAL, EXECUTING,
    SUCCEEDED, FAILED, CANCELED, EXPIRED, OUTCOME_UNKNOWN,
}
```

`ActionTransitionResult` is `Allowed` or `Rejected(ActionError(PROTOCOL_ERROR, ...))`.

- [ ] **Step 4: Write the failing complete error-vocabulary test**

Add table-driven assertions for every required code:

```text
ACTION_NOT_FOUND, ACTION_DISABLED, PERMISSION_DENIED, VALIDATION_FAILED,
CONTEXT_STALE, APPROVAL_DENIED, APPROVAL_EXPIRED, EXECUTION_CONFLICT,
EXECUTION_TIMEOUT, DESKTOP_DISCONNECTED, AGENT_DISCONNECTED, AUTH_EXPIRED,
MEMBERSHIP_EXPIRED, REMOTE_REQUEST_FAILED, OUTCOME_UNKNOWN, PROTOCOL_ERROR
```

Each `ActionErrorCode` has a fixed `ErrorDisposition`:

```text
USER_FIXABLE | RELOGIN_REQUIRED | RETRYABLE | NON_RETRYABLE | MANUAL_RECONCILIATION
```

- [ ] **Step 5: Run error-vocabulary RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionErrorVocabularyTest"`

Expected: compilation fails until the vocabulary and disposition mapping exist.

- [ ] **Step 6: Implement the vocabulary and only the tested lifecycle matrix**

`ActionStateMachine.transition(from, to, riskLevel)` is a pure function. Terminal states reject every transition. Do not add hidden fallback transitions.

- [ ] **Step 7: Run focused GREEN**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionStateMachineTest" --tests "*ActionErrorVocabularyTest"`

Expected: all matrix cases pass.

- [ ] **Step 8: Write failing serialization tests**

Round-trip descriptor, command, preview, approval-required result, success, failure, canceled, expired, and outcome-unknown. Assert wire enum values use lower snake case.

- [ ] **Step 9: Run serialization RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionModelSerializationTest"`

Expected: FAIL because serializable descriptor/command/result models are incomplete.

- [ ] **Step 10: Implement serializable immutable models**

`ActionDescriptor` includes ID, version, title, description, input schema, risk, permissions, target, replay policy, and reconciliation policy. `ActionCommand` includes execution ID, origin, identity scope, page/context revision, and JSON input.

- [ ] **Step 11: Run the complete task suite**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test`

Expected: lifecycle and serialization tests pass.

- [ ] **Step 12: Commit**

```powershell
git add business-desktop/application-action-core
git commit -m "feat: 定义桌面动作模型与完整状态机"
```

### Task 3: Implement typed ActionRegistry and input codecs

**Files:**
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/ApplicationAction.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/ActionInputCodec.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/RegisteredAction.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/ActionRegistry.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/ActionRegistryTest.kt`

- [ ] **Step 1: Write failing registry tests**

Assert:

- registering the same `actionId + version` twice throws a clear startup error;
- two versions of the same action ID may coexist;
- resolving without a version returns the highest registered version;
- resolving an unknown ID returns `ACTION_NOT_FOUND`;
- invalid JSON input returns `VALIDATION_FAILED` without invoking action code;
- descriptor and codec remain paired in one `RegisteredAction` object.

- [ ] **Step 2: Run RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionRegistryTest"`

Expected: compilation fails because the registry does not exist.

- [ ] **Step 3: Implement `ApplicationAction` and codec**

```kotlin
interface ApplicationAction<I : Any, O : Any> {
    val descriptor: ActionDescriptor
    suspend fun preview(input: I, context: ActionContext): ActionPreview
    suspend fun execute(input: I, context: ActionContext): ActionResult<O>
    suspend fun reconcile(input: I, context: ActionContext, remoteReference: String?): ReconciliationResult =
        ReconciliationResult.Unsupported
}

interface ActionInputCodec<I : Any> {
    fun decode(input: JsonObject): ActionInputDecodeResult<I>
}
```

- [ ] **Step 4: Implement `ActionRegistry` with no unchecked public API**

Confine the single internal cast to `RegisteredAction.invoke*`; callers receive JSON-safe previews/results. Add `@Suppress("UNCHECKED_CAST")` only on that private bridge with a Chinese explanation.

- [ ] **Step 5: Run GREEN**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionRegistryTest"`

Expected: registry tests pass.

- [ ] **Step 6: Commit**

```powershell
git add business-desktop/application-action-core
git commit -m "feat: 建立强类型动作注册表"
```

### Task 4: Define confirmation, approval, execution-store, audit, and policy ports

**Files:**
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/port/ActionConfirmationPort.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/port/ActionApprovalPort.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/port/ActionExecutionStore.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/port/ActionAuditPort.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/port/ActionRiskPolicy.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/port/ActionClock.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/port/ActionPortContractTest.kt`

- [ ] **Step 1: Write failing port contract tests with in-test fakes**

The tests compile fake ports and prove the result vocabularies are distinct:

```text
confirmation: ACCEPTED | REJECTED | EXPIRED
approval:     APPROVED | DENIED | EXPIRED
execution:    absent | running | exact terminal record
audit:        append-only transition event
```

Assert neither confirmation nor approval has an `always` or session-wide result.

- [ ] **Step 2: Run RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionPortContractTest"`

Expected: compilation fails because ports do not exist.

- [ ] **Step 3: Implement small single-purpose port files**

`ActionConfirmationPort` accepts a preview; `ActionApprovalPort` accepts an already-confirmed high-risk command; `ActionExecutionStore` exposes compare-and-create plus terminal update; `ActionAuditPort` only appends immutable events; `ActionRiskPolicy` returns a risk that cannot be below descriptor risk.

- [ ] **Step 4: Run GREEN**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionPortContractTest"`

Expected: port contract tests pass.

- [ ] **Step 5: Commit**

```powershell
git add business-desktop/application-action-core
git commit -m "feat: 定义动作确认审批与审计端口"
```

### Task 5: Implement ActionBus preview confirmation and high-risk approval

**Files:**
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/ApplicationActionBus.kt`
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/ActionExecutionContextValidator.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/ApplicationActionBusReadOnlyTest.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/ApplicationActionBusWriteTest.kt`

- [ ] **Step 1: Write failing read-only tests**

Assert read-only USER and AGENT commands resolve the same registered implementation, skip preview/confirmation/approval, execute once, and audit `RECEIVED -> VALIDATING -> EXECUTING -> SUCCEEDED`.

- [ ] **Step 2: Run RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ApplicationActionBusReadOnlyTest"`

Expected: compilation fails because the bus does not exist.

- [ ] **Step 3: Implement the read-only path only**

Resolve, decode, validate identity/context, evaluate risk, transition, execute, persist, and audit. Do not implement write branches yet.

- [ ] **Step 4: Run read-only GREEN**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ApplicationActionBusReadOnlyTest"`

Expected: read-only tests pass.

- [ ] **Step 5: Write failing reversible-write tests**

Assert preview is generated without side effects, `ActionConfirmationPort` is always called, acceptance executes once, rejection returns `CANCELED`, expiry returns `EXPIRED`, and neither rejection nor expiry invokes execute.

- [ ] **Step 6: Run reversible-write RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ApplicationActionBusWriteTest.reversible*"`

Expected: FAIL because the bus does not call confirmation and currently lacks write branching.

- [ ] **Step 7: Implement reversible-write confirmation**

After preview, persist/audit `PREVIEWED`; call confirmation; only `ACCEPTED` may transition to `EXECUTING`.

- [ ] **Step 8: Run reversible-write GREEN**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ApplicationActionBusWriteTest.reversible*"`

Expected: reversible-write tests pass.

- [ ] **Step 9: Write failing high-risk tests**

Assert high-risk actions first require preview confirmation and then a separate per-execution approval. Denied approval returns `CANCELED`; expired approval returns `EXPIRED`; no session-wide approval API exists.

- [ ] **Step 10: Run high-risk RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ApplicationActionBusWriteTest.high*risk*"`

Expected: FAIL because approval is not yet requested after confirmation.

- [ ] **Step 11: Implement high-risk approval**

Call `ActionApprovalPort` only after confirmation acceptance. Audit approval identity and result before execute.

- [ ] **Step 12: Run write-path GREEN and regression**

Run:

```powershell
cd business-desktop
.\gradlew.bat :application-action-core:test --tests "*ApplicationActionBus*Test"
```

Expected: read-only, reversible-write, and high-risk tests pass.

- [ ] **Step 13: Commit**

```powershell
git add business-desktop/application-action-core
git commit -m "feat: 实现动作预览确认与高风险审批"
```

### Task 6: Implement idempotency, terminal replay, and reconciliation

**Files:**
- Create: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/ActionExecutionCoordinator.kt`
- Modify: `business-desktop/application-action-core/src/main/kotlin/com/wzx/huitai/action/ApplicationActionBus.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/ActionIdempotencyTest.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/ActionReconciliationTest.kt`

- [ ] **Step 1: Write the complete failing idempotency table**

Cover:

```text
same executionId + same action/fingerprint + RUNNING -> no second execute
same executionId + different actionId              -> EXECUTION_CONFLICT
same executionId + different fingerprint           -> EXECUTION_CONFLICT
stored SUCCEEDED|FAILED|CANCELED|EXPIRED            -> return exact stored terminal
stored OUTCOME_UNKNOWN                              -> reconcile only, never execute
```

- [ ] **Step 2: Run RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionIdempotencyTest"`

Expected: tests fail because the bus currently re-enters execution.

- [ ] **Step 3: Implement compare-and-create coordination**

`ActionExecutionCoordinator.begin(command)` returns `New`, `ExistingRunning`, `ExistingTerminal`, `NeedsReconciliation`, or `Conflict`. The bus branches on this result before decoding or executing.

- [ ] **Step 4: Run idempotency GREEN**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionIdempotencyTest"`

Expected: all table rows pass.

- [ ] **Step 5: Write failing reconciliation tests**

Assert `OUTCOME_UNKNOWN + QUERY_REMOTE` calls reconcile once and stores success/failure; `MANUAL` remains outcome unknown; `NONE` returns a configuration error; a second call returns the stored reconciliation terminal.

- [ ] **Step 6: Run reconciliation RED**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionReconciliationTest"`

Expected: FAIL because outcome-unknown records are not reconciled.

- [ ] **Step 7: Implement reconciliation branching**

Do not call `execute()` from reconciliation code. Audit the original uncertain result and the reconciliation result as separate events.

- [ ] **Step 8: Run reconciliation GREEN**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionReconciliationTest"`

Expected: reconciliation tests pass.

- [ ] **Step 9: Run the complete module suite**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test`

Expected: all action-core tests pass.

- [ ] **Step 10: Commit**

```powershell
git add business-desktop/application-action-core
git commit -m "feat: 完成动作幂等与结果对账"
```

### Task 7: Define business screen, Agent-aware context, and pure reducer contracts

**Files:**
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/screen/BusinessScreenContract.kt`
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/screen/AgentAwareScreen.kt`
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/screen/ScreenReducer.kt`
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/context/PageContextSnapshot.kt`
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/context/PageContextPublisher.kt`
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/context/PageContextSanitizer.kt`
- Test: `business-desktop/presentation-core/src/test/kotlin/com/wzx/huitai/presentation/screen/BusinessScreenContractTest.kt`
- Test: `business-desktop/presentation-core/src/test/kotlin/com/wzx/huitai/presentation/context/PageContextSanitizerTest.kt`

- [ ] **Step 1: Write failing screen contract tests**

Use a tiny test screen to prove state is exposed as `StateFlow`, events pass through a pure reducer, and `pageContext()` is derived from the same immutable state revision.

- [ ] **Step 2: Run RED**

Run: `cd business-desktop; .\gradlew.bat :presentation-core:test --tests "*BusinessScreenContractTest"`

Expected: compilation fails because contracts do not exist.

- [ ] **Step 3: Implement the three small contracts**

```kotlin
interface BusinessScreenContract<S : Any, E : Any> {
    val state: StateFlow<S>
    fun dispatch(event: E)
}

fun interface ScreenReducer<S : Any, E : Any> {
    fun reduce(state: S, event: E): S
}

interface AgentAwareScreen {
    fun pageContext(): PageContextSnapshot
}
```

- [ ] **Step 4: Run screen GREEN**

Run: `cd business-desktop; .\gradlew.bat :presentation-core:test --tests "*BusinessScreenContractTest"`

Expected: screen contract tests pass.

- [ ] **Step 5: Write failing context sanitizer tests**

Assert `SECRET` omission, `SENSITIVE` masking, disabled action schema removal, trusted identity fields, monotonic catalog/context sequence, and payload-size rejection.

- [ ] **Step 6: Run sanitizer RED**

Run: `cd business-desktop; .\gradlew.bat :presentation-core:test --tests "*PageContextSanitizerTest"`

Expected: FAIL because the snapshot, publisher, and sanitizer are missing.

- [ ] **Step 7: Implement snapshot, publisher, and sanitizer**

Include protocol version, desktop instance, auth session, identity epoch, catalog epoch, context sequence, generated time, fields, selection, validation summary, and actions. Treat every display string as untrusted downstream data.

- [ ] **Step 8: Run context GREEN**

Run: `cd business-desktop; .\gradlew.bat :presentation-core:test`

Expected: screen and context tests pass.

- [ ] **Step 9: Commit**

```powershell
git add business-desktop/presentation-core
git commit -m "feat: 建立页面状态与 Agent 上下文契约"
```

### Task 8: Implement FormPatch permission, type, business-rule, and revision validation

**Files:**
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/form/FormPatch.kt`
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/form/FormFieldType.kt`
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/form/FormBusinessRule.kt`
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/form/FormPatchValidator.kt`
- Create: `business-desktop/presentation-core/src/main/kotlin/com/wzx/huitai/presentation/form/SuggestionState.kt`
- Test: `business-desktop/presentation-core/src/test/kotlin/com/wzx/huitai/presentation/form/FormPatchValidatorTest.kt`

- [ ] **Step 1: Write failing revision and permission tests**

Page mismatch, revision mismatch, missing required permission, read-only field, and previous-value mismatch must all reject without returning an applicable patch.

- [ ] **Step 2: Run RED**

Run: `cd business-desktop; .\gradlew.bat :presentation-core:test --tests "*FormPatchValidatorTest"`

Expected: compilation fails because form types do not exist.

- [ ] **Step 3: Implement identity, page, permission, and overwrite checks**

Return field-level errors without logging raw values.

- [ ] **Step 4: Write failing type and business-rule tests**

Cover string, decimal, date, enum, and multiline values; invalid enum, invalid decimal, and a custom cross-field rule must fail. A valid patch keeps reason, confidence, and source references.

- [ ] **Step 5: Run type/rule RED**

Run: `cd business-desktop; .\gradlew.bat :presentation-core:test --tests "*FormPatchValidatorTest.type*" --tests "*FormPatchValidatorTest.business*rule*"`

Expected: FAIL because type codecs and business-rule aggregation do not exist.

- [ ] **Step 6: Implement type codecs and `FormBusinessRule` aggregation**

Rules are injected per form; no OA-specific rule enters this module.

- [ ] **Step 7: Run GREEN**

Run: `cd business-desktop; .\gradlew.bat :presentation-core:test`

Expected: all patch and context tests pass.

- [ ] **Step 8: Commit**

```powershell
git add business-desktop/presentation-core
git commit -m "feat: 完成表单补丁完整校验"
```

### Task 9: Implement tested in-memory security and audit adapters

**Files:**
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/risk/DefaultActionRiskPolicy.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/approval/InMemoryConfirmationPort.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/approval/InMemoryApprovalPort.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/execution/InMemoryActionExecutionStore.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/audit/AuditRedactor.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/audit/InMemoryActionAuditPort.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/risk/DefaultActionRiskPolicyTest.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/approval/ApprovalAdapterTest.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/execution/InMemoryActionExecutionStoreTest.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/audit/InMemoryActionAuditPortTest.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/audit/AuditRedactorTest.kt`
- Test: `business-desktop/application-action-core/src/test/kotlin/com/wzx/huitai/action/ActionCoreDependencyGuardTest.kt`

- [ ] **Step 1: Write failing risk tests**

Unknown defaults to deny; risk can be raised but not lowered; sensitive writes are at least reversible; submit/send/delete wording is high risk.

- [ ] **Step 2: Run risk RED**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*DefaultActionRiskPolicyTest"`

Expected: compilation fails because the risk adapter does not exist.

- [ ] **Step 3: Implement the risk policy**

Keep the adapter stateless and depend only on action-core models.

- [ ] **Step 4: Run risk GREEN**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*DefaultActionRiskPolicyTest"`

Expected: risk tests pass.

- [ ] **Step 5: Write failing confirmation and approval tests**

Prove each execution receives a separate decision, denial and expiry are terminal, and no API can grant session-wide approval.

- [ ] **Step 6: Run approval RED**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*ApprovalAdapterTest"`

Expected: FAIL because no adapter can supply per-execution decisions.

- [ ] **Step 7: Implement confirmation and approval adapters**

The adapters are deterministic queues for tests/demo; Compose dialogs replace them in Chunk 4.

- [ ] **Step 8: Run approval GREEN**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*ApprovalAdapterTest"`

Expected: approval tests pass.

- [ ] **Step 9: Write failing execution-store tests**

Cover atomic create, running lookup, exact terminal replay, action/fingerprint conflict, and persisted outcome unknown.

- [ ] **Step 10: Run execution-store RED**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*InMemoryActionExecutionStoreTest"`

Expected: FAIL because atomic idempotency storage is missing.

- [ ] **Step 11: Implement the in-memory execution store**

Use `Mutex` around compare-and-create; never expose mutable maps.

- [ ] **Step 12: Run execution-store GREEN**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*InMemoryActionExecutionStoreTest"`

Expected: execution-store tests pass.

- [ ] **Step 13: Write failing audit tests**

Require every transition event to contain executionId, actionId/version, origin, thread/turn/toolCall when present, user/tenant/platform/auth session, desktop instance, page/revision, risk, approval identity/result, timestamps, remote reference, and terminal/error. Verify append-only order.

- [ ] **Step 14: Run audit RED**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*InMemoryActionAuditPortTest" --tests "*AuditRedactorTest"`

Expected: FAIL because audit append/redaction is missing.

- [ ] **Step 15: Implement audit port and redactor**

Redact token, refresh token, password, secret, key, binary/file content, and configured sensitive field IDs.

- [ ] **Step 16: Run audit GREEN**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*InMemoryActionAuditPortTest" --tests "*AuditRedactorTest"`

Expected: audit tests pass.

- [ ] **Step 17: Add the failing dependency guard test**

Reject imports in `application-action-core` matching:

```text
androidx.compose
io.ktor
java.sql
org.sqlite
org.springframework
com.wzx.huitai.security
```

- [ ] **Step 18: Run dependency-guard RED, then remove any forbidden imports**

Run: `cd business-desktop; .\gradlew.bat :application-action-core:test --tests "*ActionCoreDependencyGuardTest"`

Expected before cleanup: FAIL if any forbidden dependency leaked in; otherwise record PASS as evidence that the boundary already holds. Remove only actual forbidden imports, not the test.

- [ ] **Step 19: Run Chunk 1 verification**

Run:

```powershell
cd business-desktop
.\gradlew.bat :application-action-core:test :presentation-core:test :security-audit-core:test
```

Expected: all Chunk 1 tests pass; no forbidden imports.

- [ ] **Step 20: Commit**

```powershell
git add business-desktop/security-audit-core business-desktop/application-action-core/src/test
git commit -m "feat: 完成动作风险审批幂等与审计适配"
```

---

## Chunk 2: Desktop Agent Client, Huitai Integration, and Durable Audit

### Task 10: Implement authentication identity, credential lifecycle, and state transitions

**Files:**
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/auth/AuthIdentitySnapshot.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/auth/AuthenticationState.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/auth/AuthenticationStateMachine.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/auth/AuthSessionManager.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/auth/AuthTokenSet.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/auth/AuthCredentialPersistencePort.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/auth/AuthenticationStateMachineTest.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/auth/AuthSessionManagerTest.kt`

- [ ] **Step 1: Write the failing authentication transition matrix**

Cover exactly:

```text
SIGNED_OUT -> SIGNING_IN -> AUTHENTICATED|EXPIRED|MEMBERSHIP_EXPIRED
AUTHENTICATED -> REFRESHING -> AUTHENTICATED|EXPIRED|MEMBERSHIP_EXPIRED
AUTHENTICATED -> SWITCHING_TENANT -> AUTHENTICATED|EXPIRED|MEMBERSHIP_EXPIRED
any non-terminal -> SIGNED_OUT
EXPIRED|MEMBERSHIP_EXPIRED -> SIGNED_OUT -> SIGNING_IN
all unlisted transitions rejected
```

- [ ] **Step 2: Run authentication RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*AuthenticationStateMachineTest"`

Expected: compilation fails because authentication models do not exist.

- [ ] **Step 3: Implement immutable auth models and the pure state machine**

`AuthIdentitySnapshot` contains authSessionId, identityEpoch, userId, tenantId, platformId, roles, permissions, and authenticatedAt. Tokens exist only in `AuthTokenSet`; `toString()` for token-bearing types is redacted.

- [ ] **Step 4: Run authentication GREEN**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*AuthenticationStateMachineTest"`

Expected: every listed transition passes and every unlisted transition fails.

- [ ] **Step 5: Write failing session and credential-lifecycle tests**

Assert login creates epoch 1 and a new authSessionId; refresh with unchanged identity keeps both values and atomically replaces persisted credentials; tenant or user change creates a higher epoch and a new authSessionId; logout creates a higher signed-out epoch, publishes the transition before clearing identity, and clears credentials; restore loads credentials through the port without exposing tokens through state or logs; auth expiry and membership expiry clear credentials but remain distinct states.

- [ ] **Step 6: Run session-manager RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*AuthSessionManagerTest"`

Expected: FAIL because session coordination and credential persistence are absent.

- [ ] **Step 7: Implement `AuthSessionManager` with StateFlow and a persistence port**

Expose state, identity, and identity-transition flows. `AuthCredentialPersistencePort` has `load()`, `replace(AuthTokenSet)`, and `clear()`; production JCEKS wiring is implemented in Task 18. Token access remains behind internal `AuthTokenProvider` methods.

- [ ] **Step 8: Run task GREEN and commit**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*Authentication*Test" --tests "*AuthSessionManagerTest"`

Expected: authentication and credential lifecycle tests pass.

```powershell
git add business-desktop/huitai-integration-core
git commit -m "feat: 建立汇泰认证身份与凭据生命周期"
```

### Task 11: Implement CommonResult decoding and an explicit transport outcome model

**Files:**
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/CommonResult.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/CommonResultDecoder.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/HuitaiRequest.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/HuitaiResponse.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/HuitaiTransportOutcome.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/RequestReplayDecision.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/http/CommonResultDecoderTest.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/http/RequestReplayDecisionTest.kt`

- [ ] **Step 1: Write failing envelope tests**

Decode success code 200, structured business failure, empty response, JSON error under a blob content type, and a true binary response. Assert `1002010000` maps to `MEMBERSHIP_EXPIRED`, HTTP/envelope 401 and 499 map to `AUTH_EXPIRED`, and other business errors retain code/msg.

- [ ] **Step 2: Run envelope RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*CommonResultDecoderTest"`

Expected: compilation fails because envelope models do not exist.

- [ ] **Step 3: Implement decoder and sealed response types**

Return success, binary, and structured failure values. This layer never displays UI notifications and never logs response bodies containing secrets.

- [ ] **Step 4: Run envelope GREEN**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*CommonResultDecoderTest"`

Expected: decoder tests pass.

- [ ] **Step 5: Write failing send-outcome and replay tests**

Use the exact transport outcomes `NotSent`, `ResponseReceived`, and `AmbiguousAfterSend`. Assert: `NotSent` may retry without reconciliation; `SAFE` may replay after an ambiguous send; `IDEMPOTENCY_KEY_REQUIRED` may replay only when executionId was attached under the declared idempotency header; `NEVER + AmbiguousAfterSend` becomes `OUTCOME_UNKNOWN`; a received 401/499 is not itself ambiguous but only replay-safe requests run again after refresh.

- [ ] **Step 6: Run replay RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*RequestReplayDecisionTest"`

Expected: FAIL because transport outcomes and replay decisions are missing.

- [ ] **Step 7: Implement exact request metadata and replay decisions**

`HuitaiRequest` carries method, relative path, headers, body, replayPolicy, executionId, idempotencyHeaderName, and reconciliationPolicy. It contains no real OA endpoint constants. `HuitaiTransportOutcome` is the only input used to distinguish safe retry from mandatory reconciliation.

- [ ] **Step 8: Run task GREEN and commit**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*CommonResultDecoderTest" --tests "*RequestReplayDecisionTest"`

Expected: decoder and every replay/outcome table row pass.

```powershell
git add business-desktop/huitai-integration-core
git commit -m "feat: 定义汇泰响应与发送结果语义"
```

### Task 12: Implement the real Ktor transport, single-flight refresh, and Huitai HTTP client

**Files:**
- Modify: `business-desktop/huitai-integration-core/build.gradle.kts`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/auth/TokenRefreshCoordinator.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/HuitaiTransport.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/KtorHuitaiTransport.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/http/HuitaiHttpClient.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/auth/TokenRefreshCoordinatorTest.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/http/KtorHuitaiTransportTest.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/http/HuitaiHttpClientTest.kt`

- [ ] **Step 1: Add the Ktor test dependency and write failing real-transport tests**

Add `testImplementation("io.ktor:ktor-client-mock:3.5.0")`. With `MockEngine`, assert injected base URL plus relative path, method, query, body, Bearer header, tenant-id, response status/headers/body, binary bytes, and JSON error bytes are mapped exactly. Simulate connect failure as `NotSent` and a post-send I/O failure as `AmbiguousAfterSend`.

- [ ] **Step 2: Run transport RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*KtorHuitaiTransportTest"`

Expected: compilation fails because the transport does not exist.

- [ ] **Step 3: Implement `KtorHuitaiTransport` only**

The constructor requires a base URL and `HttpClient`; it maps requests and returns `HuitaiTransportOutcome` without refresh, business decoding, endpoint constants, or UI behavior.

- [ ] **Step 4: Run transport GREEN**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*KtorHuitaiTransportTest"`

Expected: all real transport mapping and outcome-classification cases pass.

- [ ] **Step 5: Write failing single-flight tests**

Launch ten concurrent 401 responses and assert one refresh request; replay-safe requests resume after success; refresh failure expires the session; membership expiry bypasses refresh; the refresh request never recursively refreshes; the in-flight Deferred is cleared in `finally`.

- [ ] **Step 6: Run refresh RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*TokenRefreshCoordinatorTest"`

Expected: FAIL because the coordinator is absent.

- [ ] **Step 7: Implement the coordinator with Mutex and shared Deferred, then run GREEN**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*TokenRefreshCoordinatorTest"`

Expected: one refresh is observed for every concurrent burst.

- [ ] **Step 8: Write failing client orchestration tests**

With a recording transport, assert auth headers are attached without leaking tokens; SAFE and correctly keyed requests replay after refresh; NEVER requests return `AUTH_EXPIRED` after a received 401/499 without automatic replay; ambiguous NEVER writes return `OUTCOME_UNKNOWN`; binary responses bypass JSON decoding; refresh replacement is persisted through Task 10's port.

- [ ] **Step 9: Run client RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*HuitaiHttpClientTest"`

Expected: FAIL because client orchestration is missing.

- [ ] **Step 10: Implement client orchestration and run task GREEN**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test`

Expected: transport, refresh, decoding, and replay tests pass.

- [ ] **Step 11: Commit**

```powershell
git add business-desktop/huitai-integration-core
git commit -m "feat: 完成汇泰 Ktor HTTP 与单飞刷新"
```

### Task 13: Implement the generic Huitai business WebSocket client

**Files:**
- Modify: `business-desktop/huitai-integration-core/build.gradle.kts`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/websocket/HuitaiWebSocketClient.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/websocket/HuitaiWebSocketTransport.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/websocket/KtorHuitaiWebSocketTransport.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/websocket/HuitaiWebSocketState.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/websocket/KtorHuitaiWebSocketTransportTest.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/websocket/HuitaiWebSocketClientTest.kt`

- [ ] **Step 1: Add test-server dependencies and write failing transport tests**

Add Ktor 3.5.0 test implementations for `ktor-server-test-host`, `ktor-server-core`, `ktor-server-cio`, and `ktor-server-websockets`. The local server asserts an injected WebSocket URL, Bearer and tenant-id headers, text-frame delivery, bounded incoming buffering, and redacted close/error states.

- [ ] **Step 2: Run WebSocket transport RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*KtorHuitaiWebSocketTransportTest"`

Expected: compilation fails because the transport is absent.

- [ ] **Step 3: Implement the Ktor WebSocket transport and run GREEN**

The transport accepts a runtime URL and contains no real OA event names or endpoint constants.

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*KtorHuitaiWebSocketTransportTest"`

Expected: handshake and frame tests pass.

- [ ] **Step 4: Write failing client lifecycle tests**

Assert authenticated connect, token-refresh reconnect with the same business identity, tenant-switch reconnect with the new tenant only, logout close, membership-expired terminal close, delay sequence `1s,2s,4s,8s,10s`, and transition to `ManualRetryRequired` after ten consecutive failures. Authentication rejection never loops.

- [ ] **Step 5: Run client RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*HuitaiWebSocketClientTest"`

Expected: FAIL because lifecycle orchestration is absent.

- [ ] **Step 6: Implement the generic client and run GREEN**

Expose only connection state and raw/sanitized events; concrete OA subscriptions remain out of scope.

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*HuitaiWebSocket*Test"`

Expected: transport and lifecycle tests pass.

- [ ] **Step 7: Commit**

```powershell
git add business-desktop/huitai-integration-core
git commit -m "feat: 建立汇泰业务 WebSocket 底座"
```

### Task 14: Implement tenant switching and old-identity action containment

**Files:**
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/tenant/TenantContextManager.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/permission/PermissionSnapshotProvider.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/identity/AuthIdentityPublisher.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/identity/IdentityBindingPort.kt`
- Create: `business-desktop/huitai-integration-core/src/main/kotlin/com/wzx/huitai/integration/identity/IdentityBoundaryActionPort.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/tenant/TenantContextManagerTest.kt`
- Test: `business-desktop/huitai-integration-core/src/test/kotlin/com/wzx/huitai/integration/identity/AuthIdentityPublisherTest.kt`

- [ ] **Step 1: Write failing tenant-boundary tests**

Assert a successful tenant switch rotates authSessionId, increments identityEpoch, replaces permissions, clears old page context and unapplied patches, cancels RECEIVED/VALIDATING/PREVIEWED/WAITING_APPROVAL work, and detaches EXECUTING remote writes into old-scope reconciliation. Executing results remain queryable only with the old complete identity scope and never enter the new tenant context.

- [ ] **Step 2: Run tenant RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*TenantContextManagerTest"`

Expected: compilation fails because tenant and action-boundary coordination are missing.

- [ ] **Step 3: Implement tenant manager and boundary ports**

Cleanup/action hooks are suspend ports registered by presentation, action-store, and Agent-client adapters; this module imports none of those implementations. Execute hooks before publishing the new identity.

- [ ] **Step 4: Run tenant GREEN**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*TenantContextManagerTest"`

Expected: old-scope cancellation, reconciliation, and permission isolation pass.

- [ ] **Step 5: Write failing identity-publisher tests**

Assert login sends bind; tenant/user/logout sends update with new authSessionId and increasing epoch; logout sends a signed-out update before business actions disappear; unchanged refresh sends nothing; stale publication is rejected locally.

- [ ] **Step 6: Run identity RED**

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test --tests "*AuthIdentityPublisherTest"`

Expected: FAIL because no publication port exists.

- [ ] **Step 7: Implement publisher against the sibling-safe `IdentityBindingPort` and run GREEN**

The port is owned by `huitai-integration-core`; tests use a recording fake. Neither sibling core module depends on the other. Task 17 adds an app-level adapter that delegates this port to `ApplicationIdentityClient`.

Run: `cd business-desktop; .\gradlew.bat :huitai-integration-core:test`

Expected: tenant, identity, HTTP, and business WebSocket tests pass.

- [ ] **Step 8: Commit**

```powershell
git add business-desktop/huitai-integration-core
git commit -m "feat: 隔离租户切换与旧身份动作"
```

### Task 15: Define the complete Kotlin application protocol and canonical fixtures

**Files:**
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/protocol/ApplicationProtocol.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/protocol/ApplicationProtocolLimits.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/protocol/ApplicationProtocolValidator.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/protocol/ApplicationCatalogModels.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/protocol/ApplicationIdentityModels.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/protocol/ApplicationActionModels.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/protocol/JsonRpcModels.kt`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/catalog-register.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/catalog-update.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/context-publish.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/identity-bind.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/identity-update.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-request.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-cancel.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-accepted.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-previewed.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-approval-required.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-running.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-completed.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-failed.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-rejected.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-canceled.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-expired.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-outcome-unknown.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-status.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-result-get.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-status-result.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/action-result-get-result.json`
- Create: `docs/superpowers/contracts/huitai-business-desktop-agent/protocol-error.json`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/protocol/ApplicationProtocolSerializationTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/protocol/ApplicationProtocolFixtureTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/protocol/ApplicationProtocolValidationTest.kt`

- [ ] **Step 1: Write failing round-trip tests for all nineteen methods plus query responses and protocol errors**

Every envelope contains protocolVersion, desktopInstanceId, desktopSessionId, authSessionId, identityEpoch, sequence, generatedAt, userId, tenantId, and platformId. Catalog/context envelopes additionally contain catalogEpoch, contextSequence, and payloadSize. Action envelopes additionally contain threadId, turnId, toolCallId, and executionId. Signed-out identity updates allow null business IDs only when `authenticated=false`.

- [ ] **Step 2: Run protocol RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationProtocolSerializationTest"`

Expected: compilation fails because protocol models do not exist.

- [ ] **Step 3: Implement versioned serializable models**

Use protocol version `1.0`; accept unknown JSON fields; use lower-snake-case wire enums. Do not put request correlation, validation, or limits in the model files.

- [ ] **Step 4: Run serialization GREEN**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationProtocolSerializationTest"`

Expected: nineteen round trips and unknown-field compatibility pass.

- [ ] **Step 5: Write the failing fixture loader test**

The test enumerates the exact twenty-two paths above: nineteen method requests/notifications, two canonical query success responses, and one canonical `PROTOCOL_ERROR` JSON-RPC response. It requires fixed IDs/timestamps and compares canonical JSON trees. Do not create the fixture files until after observing RED.

- [ ] **Step 6: Run fixture RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationProtocolFixtureTest"`

Expected: FAIL listing the missing canonical fixture files.

- [ ] **Step 7: Add all twenty-two canonical fixtures and run fixture GREEN**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationProtocolFixtureTest"`

Expected: all request, notification, query-response, and error fixture cases pass.

- [ ] **Step 8: Write failing validation and vocabulary-parity tests**

Set limits to envelope 256 KiB, catalog 128 KiB, context 128 KiB, and action input/result 64 KiB. Test exact-boundary acceptance and one-byte-over rejection; reject unsupported protocol versions and nonpositive sequence/epoch values; assert protocol terminal names and all sixteen error codes equal the action-core vocabulary.

- [ ] **Step 9: Run validation RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationProtocolValidationTest"`

Expected: FAIL because limits and validation are absent.

- [ ] **Step 10: Implement validation, run GREEN, and commit**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test`

Expected: serialization, fixture, forward-compatibility, size, version, terminal, and error parity tests pass.

```powershell
git add business-desktop/agent-client-core docs/superpowers/contracts/huitai-business-desktop-agent
git commit -m "feat: 定义桌面应用动作 JSON-RPC 协议"
```

### Task 16: Implement authenticated Agent transport, desktop sessions, and reconnect control

**Files:**
- Modify: `business-desktop/agent-client-core/build.gradle.kts`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/DesktopSessionIdentity.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/AgentTransport.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/KtorAgentTransport.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/AgentConnectionState.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/AgentReconnectPolicy.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/AgentConnectionSupervisor.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/ApplicationSequenceTracker.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/client/KtorAgentTransportTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/client/AgentConnectionSupervisorTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/client/ApplicationSequenceTrackerTest.kt`

- [ ] **Step 1: Add Ktor test-server dependencies and write failing handshake tests**

Add Ktor 3.5.0 test implementations for `ktor-server-test-host`, `ktor-server-core`, `ktor-server-cio`, and `ktor-server-websockets`. Assert `Authorization: Bearer <desktopSessionToken>`, `X-Desktop-Instance-Id`, `X-Desktop-Session-Id`, the configured non-wildcard `Origin`, loopback URL, text frames, bounded incoming buffering, prior-reader shutdown, and that tokens never appear in state/errors. Unauthorized close maps to `AuthenticationFailed`.

- [ ] **Step 2: Run transport RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*KtorAgentTransportTest"`

Expected: compilation fails because transport is absent.

- [ ] **Step 3: Implement transport and desktop session identity**

`desktopInstanceId` is stable for one installation. `desktopSessionId` and desktopSessionToken are generated once per bundled Agent child-process launch and stay constant across WebSocket reconnects to that child. `DesktopSessionIdentity` also owns the fixed local Origin value sent in the Ktor handshake. Each connection attempt has a new connection ID; a new child process creates a new desktopSessionId and fresh counters.

- [ ] **Step 4: Run transport GREEN**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*KtorAgentTransportTest"`

Expected: authenticated transport tests pass.

- [ ] **Step 5: Write failing reconnect and sequence tests**

Reconnect delays are `1s,2s,4s,8s,10s` capped at 10s; after ten consecutive failures enter `ManualRetryRequired`; authentication failure and app shutdown never retry. On each new connection, the first positive identityEpoch/catalogEpoch/contextSequence is accepted even if it equals the value republished on the prior connection; later values on that connection must be strictly greater. Envelope `sequence` remains increasing for the desktopSessionId; only a new desktopSessionId resets all counters. Oversized payloads fail before send.

- [ ] **Step 6: Run reconnect/sequence RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*AgentConnectionSupervisorTest" --tests "*ApplicationSequenceTrackerTest"`

Expected: FAIL because reconnect and connection-local sequencing are absent.

- [ ] **Step 7: Implement supervisor and tracker, then run task GREEN**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*KtorAgentTransportTest" --tests "*AgentConnectionSupervisorTest" --tests "*ApplicationSequenceTrackerTest"`

Expected: handshake, retry termination, reconnect republish, and reset semantics pass.

- [ ] **Step 8: Commit**

```powershell
git add business-desktop/agent-client-core
git commit -m "feat: 实现认证 Agent 连接与重连控制"
```

### Task 17: Implement registration, action dispatch, complete-scope lookup, and reconnect synchronization

**Files:**
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/client/AgentJsonRpcClient.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationCatalogClient.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationContextClient.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationIdentityClient.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationActionRequestHandler.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/application/ApplicationActionStatusClient.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/agent/ApplicationIdentityBindingAdapter.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/application/ApplicationRegistrationTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/application/ApplicationActionRequestHandlerTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/application/ApplicationReconnectRecoveryTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/application/ApplicationIdentityScopeTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/agent/ApplicationIdentityBindingAdapterTest.kt`

- [ ] **Step 1: Write failing registration tests**

On every authenticated connection send identity bind before catalog/context; initial republish may use the current epochs as the first values for that connection; later updates increase them. Logout sends the higher signed-out identity update before removing business actions. JSON-RPC uses one request-ID generator and one pending-response map; timeout cleanup occurs in `finally`.

- [ ] **Step 2: Run registration RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationRegistrationTest"`

Expected: compilation fails because orchestration clients do not exist.

- [ ] **Step 3: Implement correlation and registration clients, then run registration GREEN**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationRegistrationTest"`

Expected: initial and reconnect registration ordering pass.

- [ ] **Step 4: Write the failing app-level identity bridge test**

Prove bind and update payloads are forwarded to a recording `ApplicationIdentityClient` delegate, and add source-import assertions proving neither sibling core module imports the other.

- [ ] **Step 5: Run identity bridge RED**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*ApplicationIdentityBindingAdapterTest"`

Expected: compilation fails because the adapter is absent.

- [ ] **Step 6: Implement the app-level identity bridge and run GREEN**

`ApplicationIdentityBindingAdapter` is the only cross-module bridge: it implements `IdentityBindingPort` from `huitai-integration-core` and delegates to `ApplicationIdentityClient` from `agent-client-core`.

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*ApplicationIdentityBindingAdapterTest"`

Expected: sibling-safe identity forwarding passes.

- [ ] **Step 7: Write failing action-handler tests**

Assert accepted is sent first; one executionId dispatches once to `ApplicationActionBus`; progress maps to previewed/approval-required/running; every terminal maps to the exact protocol method; cancel reaches the bus; persisted execution state is the source for status/result; payload identity mismatch is rejected before dispatch.

- [ ] **Step 8: Run action-handler RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationActionRequestHandlerTest"`

Expected: FAIL because action dispatch is missing.

- [ ] **Step 9: Implement action request handling without Compose and run GREEN**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationActionRequestHandlerTest"`

Expected: all lifecycle and cancel mappings pass.

- [ ] **Step 10: Write failing complete identity-scope tests**

For dispatch, status, and result/get, vary one field at a time across userId, tenantId, platformId, authSessionId, desktopInstanceId, and identityEpoch. Every mismatch must return `PROTOCOL_ERROR` and must not reveal whether the execution exists. Include reconnect and tenant-switch cases; old EXECUTING results are available only under the exact old scope.

- [ ] **Step 11: Run identity-scope RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationIdentityScopeTest"`

Expected: FAIL until all lookup paths enforce the complete scope.

- [ ] **Step 12: Implement scope guards and run identity-scope GREEN**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationIdentityScopeTest"`

Expected: every field mismatch is rejected without existence disclosure, and exact old-scope queries pass.

- [ ] **Step 13: Write failing reconnect recovery tests**

On reconnect bind identity, register catalog/context, query scoped nonterminal actions, return stored terminal results, keep running actions running, never replay an unconfirmed preview, and never attach executions from a prior desktopSessionId automatically.

- [ ] **Step 14: Run reconnect RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*ApplicationReconnectRecoveryTest"`

Expected: FAIL because synchronization is absent.

- [ ] **Step 15: Implement status/result synchronization and run task GREEN**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test`

Expected: registration, lifecycle, identity-scope, and reconnect tests pass.

- [ ] **Step 16: Commit**

```powershell
git add business-desktop/agent-client-core business-desktop/huitai-integration-core business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/agent/ApplicationIdentityBindingAdapter.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/agent/ApplicationIdentityBindingAdapterTest.kt
git commit -m "feat: 打通桌面动作请求与重连对齐"
```

### Task 18: Implement JCEKS credential persistence and process-instance locking

**Files:**
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/secret/SecretStore.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/secret/JceksSecretStore.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/instance/ProcessInstanceLock.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/security/JceksAuthCredentialPersistence.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/secret/JceksSecretStoreTest.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/instance/ProcessInstanceLockTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/security/JceksAuthCredentialPersistenceTest.kt`

- [ ] **Step 1: Write failing generic secret-store tests**

Save/load/replace/delete named secrets; assert the file contains none of the known plaintext values, wrong password fails clearly, parent directories are created, references and `toString()` never expose secrets, and file permissions are best-effort restricted on the current OS.

- [ ] **Step 2: Run secret RED**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*JceksSecretStoreTest"`

Expected: compilation fails because the store is absent.

- [ ] **Step 3: Implement JCEKS behind `SecretStore` and run GREEN**

Use the same threat boundary as backend `LocalKeyStoreSecretStore`; never hard-code credentials or print KeyStore contents.

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*JceksSecretStoreTest"`

Expected: secret storage tests pass.

- [ ] **Step 4: Write failing auth-credential adapter tests**

The app-level adapter implements Task 10's port using one JCEKS entry. Assert restore, atomic replacement after refresh, tenant-switch replacement, logout/auth-expiry deletion, corrupt entry rejection, and absence of access/refresh tokens in ordinary files, states, logs, and persisted references.

- [ ] **Step 5: Run credential adapter RED**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*JceksAuthCredentialPersistenceTest"`

Expected: FAIL because the cross-module adapter is missing.

- [ ] **Step 6: Implement the adapter and run credential GREEN**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*JceksAuthCredentialPersistenceTest"`

Expected: complete credential lifecycle passes.

- [ ] **Step 7: Write failing instance-lock tests**

First lock succeeds, a second lock on the same file fails, closing releases it, and separate desktop and Agent lock files do not conflict.

- [ ] **Step 8: Run lock RED**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*ProcessInstanceLockTest"`

Expected: FAIL because NIO lock acquisition is absent.

- [ ] **Step 9: Implement NIO FileChannel locking**

Keep separate lock paths caller-provided; close channel and lock in all failure paths.

- [ ] **Step 10: Run lock GREEN**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*ProcessInstanceLockTest"`

Expected: acquisition, collision, release, and separate-file cases pass.

- [ ] **Step 11: Commit**

```powershell
git add business-desktop/security-audit-core business-desktop/app
git commit -m "feat: 实现业务桌面凭据存储与单实例锁"
```

### Task 19: Implement exact durable SQLite action, approval, and audit storage

**Files:**
- Create: `business-desktop/security-audit-core/src/main/resources/db/migration/V1__business_desktop_action_audit.sql`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/database/BusinessDesktopDatabase.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/execution/SQLiteActionExecutionStore.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/audit/SQLiteActionAuditPort.kt`
- Create: `business-desktop/security-audit-core/src/main/kotlin/com/wzx/huitai/security/approval/SQLiteApprovalRecordStore.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/database/BusinessDesktopMigrationTest.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/database/BusinessDesktopSchemaCommentsCoverageTest.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/execution/SQLiteActionExecutionStoreTest.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/audit/SQLiteActionAuditPortTest.kt`
- Test: `business-desktop/security-audit-core/src/test/kotlin/com/wzx/huitai/security/approval/SQLiteApprovalRecordStoreTest.kt`

- [ ] **Step 1: Write failing migration and schema-coverage tests**

Require `bd_action_executions`, `bd_action_events`, `bd_action_approvals`, and `bd_schema_comments`. Every table and column has a Chinese comment row. Require these exact execution columns:

```text
execution_id, action_id, action_version, input_fingerprint, origin,
desktop_instance_id, desktop_session_id, auth_session_id, identity_epoch, user_id, tenant_id, platform_id,
page_id, context_revision, thread_id, turn_id, tool_call_id,
risk_level, replay_policy, reconciliation_policy, status, remote_reference,
result_json_redacted, error_code, error_message_redacted,
reconciliation_status, reconciliation_attempts, last_reconciled_at,
created_at, started_at, completed_at, updated_at, record_version
```

Require exact event columns `event_id, execution_id, event_sequence, from_status, to_status, event_type, payload_json_redacted, actor_id, occurred_at`; exact approval columns `approval_id, execution_id, desktop_instance_id, auth_session_id, identity_epoch, user_id, tenant_id, platform_id, requested_at, expires_at, decided_at, decision, decided_by, reason_redacted`; primary/foreign keys; enum CHECK constraints; `UNIQUE(execution_id,event_sequence)`; one approval per execution; a partial unique index on non-null tool_call_id; identity-scope/status and correlation indexes. Require `bd_action_events_reject_update` and `bd_action_events_reject_delete` SQLite triggers that abort every UPDATE or DELETE.

- [ ] **Step 2: Run migration RED**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*BusinessDesktopMigrationTest" --tests "*BusinessDesktopSchemaCommentsCoverageTest"`

Expected: FAIL because migration and bootstrap are missing.

- [ ] **Step 3: Create migration and database bootstrap**

Use caller-provided paths, WAL, foreign keys, and busy timeout. Create the two rejection triggers so `bd_action_events` is database-enforced append-only. JSON columns are explicitly redacted. No table stores tokens, passwords, full files, or model reasoning.

- [ ] **Step 4: Run migration GREEN**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*BusinessDesktopMigrationTest" --tests "*BusinessDesktopSchemaCommentsCoverageTest"`

Expected: exact columns, constraints, indexes, pragmas, and Chinese coverage pass.

- [ ] **Step 5: Write failing execution-store tests**

Prove transactional compare-and-create under concurrency, same fingerprint replay, conflict, restart persistence, first-terminal-wins, optimistic record_version updates, scoped status/result lookup, desktop-session ownership, prior-session orphaning after child-process restart, orphaned Agent links, OUTCOME_UNKNOWN, reconciliation attempts/results, and late terminal responses recorded without overwriting the first terminal.

- [ ] **Step 6: Run execution-store RED**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*SQLiteActionExecutionStoreTest"`

Expected: FAIL because the repository is absent.

- [ ] **Step 7: Implement execution storage and run GREEN**

Use prepared statements and explicit transactions. Complete identity scope is part of every query predicate.

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*SQLiteActionExecutionStoreTest"`

Expected: concurrency, restart, scope, and terminal protection pass.

- [ ] **Step 8: Write failing audit and approval tests**

Audit tests require immutable ordered events for accepted, previewed, rejected, approval, running, terminal, timeout, cancel race, late response, outcome unknown, and reconciliation. Use direct SQL to attempt UPDATE and DELETE and assert both abort through the named triggers. Approval tests require per-execution isolation, complete identity, approved/denied/expired decisions, first-decision-wins, restart persistence, and no session-wide approval. Raw-database assertions search for known token and sensitive fixture values and find none.

- [ ] **Step 9: Run audit/approval RED**

Run: `cd business-desktop; .\gradlew.bat :security-audit-core:test --tests "*SQLiteActionAuditPortTest" --tests "*SQLiteApprovalRecordStoreTest"`

Expected: FAIL because adapters are missing.

- [ ] **Step 10: Implement adapters and run Chunk 2 verification**

Run:

```powershell
cd business-desktop
.\gradlew.bat :agent-client-core:test :huitai-integration-core:test :security-audit-core:test
.\gradlew.bat :app:test --tests "*JceksAuthCredentialPersistenceTest" --tests "*ApplicationIdentityBindingAdapterTest"
```

Expected: all Chunk 2 suites pass, including real Ktor transports, business WebSocket lifecycle, complete-scope lookup, credential lifecycle, concurrent SQLite idempotency, restart persistence, approvals, and redaction.

- [ ] **Step 11: Commit**

```powershell
git add business-desktop/security-audit-core
git commit -m "feat: 持久化桌面动作审批与审计"
```

## Chunk 3: Business-profile Agent Server and Trusted Application Bridge

### Task 20: Establish the isolated `business-desktop` backend profile

**Files:**
- Create: `backend/src/main/resources/application-business-desktop.yml`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/config/BusinessDesktopModeProperties.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/config/BusinessDesktopRuntimePaths.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/config/BusinessBackendInstanceLock.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/config/BusinessDesktopProfilePropertiesTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/config/BusinessDesktopProfileIsolationIT.java`

- [ ] **Step 1: Write failing profile-property tests**

Load only the `business-desktop` profile with a temporary `${babiq.business.runtime-dir}` and assert: `server.address=127.0.0.1`; WebSocket origins do not contain `*`; database, backend KeyStore, logs, memory, teams, and locks all resolve below that runtime directory; long-term memory generation/read/retrieval are false; existing MCP and Skill switches are false; business handshake authentication is required and cannot be disabled. Flow, Team, WorkUnit and sub-Agent blocking is enforced by the explicit JSON-RPC and model-tool allowlists in Tasks 23 and 27, not assumed from profile flags that do not exist.

- [ ] **Step 2: Run profile RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessDesktopProfilePropertiesTest" test`

Expected: compilation fails because the business mode properties and profile file are absent.

- [ ] **Step 3: Implement the exact profile and validated path model**

`BusinessDesktopModeProperties` contains `enabled`, `runtimeDir`, `databasePath`, `keyStorePath`, `logPath`, `memoryRoot`, `teamRoot`, `backendLockPath`, `sessionTokenFile`, and payload/timeout settings. Its constructor rejects enabled mode when any resolved path escapes `runtimeDir`, authentication is false, address is non-loopback, or allowed origins contain `*`. `BusinessDesktopRuntimePaths` creates directories but never creates the token file.

- [ ] **Step 4: Run profile GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessDesktopProfilePropertiesTest" test`

Expected: all isolation and fail-fast configuration cases pass.

- [ ] **Step 5: Write failing backend-lock and parallel-profile tests**

Assert one backend lock per business runtime, release on close, and no collision with the common `${user.home}/.babiq` database/KeyStore/memory paths. Start a profile context on temporary paths and assert Flyway, logging, memory and recovery point only to the business tree.

- [ ] **Step 6: Run isolation RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessDesktopProfileIsolationIT" test`

Expected: FAIL because runtime-path creation and the backend process lock are absent.

- [ ] **Step 7: Implement NIO locking and run task GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessDesktopProfilePropertiesTest,BusinessDesktopProfileIsolationIT" test`

Expected: profile fail-fast, directory isolation, and instance-lock tests pass.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/resources/application-business-desktop.yml backend/src/main/java/com/wzx/babiq/server/application/config/BusinessDesktopModeProperties.java backend/src/main/java/com/wzx/babiq/server/application/config/BusinessDesktopRuntimePaths.java backend/src/main/java/com/wzx/babiq/server/application/config/BusinessBackendInstanceLock.java backend/src/test/java/com/wzx/babiq/server/application/config/BusinessDesktopProfilePropertiesTest.java backend/src/test/java/com/wzx/babiq/server/application/config/BusinessDesktopProfileIsolationIT.java
git commit -m "feat: 隔离业务桌面 Agent 运行配置"
```

### Task 21: Authenticate the loopback WebSocket and track trusted desktop connections

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/application/auth/DesktopSessionTokenProvider.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/auth/BusinessDesktopHandshakeInterceptor.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/auth/TrustedDesktopConnection.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionRegistry.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/config/WebSocketConfig.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/auth/DesktopSessionTokenProviderTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/auth/BusinessDesktopHandshakeInterceptorTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionRegistryTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/auth/BusinessDesktopAuthenticatedWebSocketIT.java`

- [ ] **Step 1: Write failing token-file tests**

Create a permission-restricted temporary token file and assert one successful read, immediate deletion, in-memory redaction, rejection of empty/oversized/symlink token files, and fail-fast startup when business mode has no readable token file.

- [ ] **Step 2: Run token RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=DesktopSessionTokenProviderTest" test`

Expected: compilation fails because the provider is absent.

- [ ] **Step 3: Implement the one-shot token provider and run GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=DesktopSessionTokenProviderTest" test`

Expected: file deletion and all fail-fast cases pass without token text in assertion messages or logs.

- [ ] **Step 4: Write failing handshake and registry tests**

Require `Authorization: Bearer`, `X-Desktop-Instance-Id`, `X-Desktop-Session-Id`, loopback remote address, and a permitted Origin. Reject absent/wrong tokens, malformed IDs, non-loopback connections, duplicate active desktopSessionId, and reservation drift. On connection close release only the matching WebSocket session; a stale close cannot evict a newer reservation.

- [ ] **Step 5: Run handshake RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessDesktopHandshakeInterceptorTest,BusinessDesktopConnectionRegistryTest" test`

Expected: FAIL because authenticated reservation and trusted connection state are absent.

- [ ] **Step 6: Implement handshake reservation and session binding**

The interceptor stores only trusted IDs and an opaque reservation ID in WebSocket attributes. `JsonRpcWebSocketHandler.afterConnectionEstablished` finalizes the reservation with the real session ID; close releases it. Common profile behavior remains unchanged.

- [ ] **Step 7: Run unit GREEN, then write the failing endpoint IT**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessDesktopHandshakeInterceptorTest,BusinessDesktopConnectionRegistryTest" test`

Expected: handshake and stale-close tests pass.

The IT attempts missing token, wrong token, duplicate session, valid authenticated JSON-RPC, and reconnect with the same desktopSessionId after the first session closes.

- [ ] **Step 8: Run endpoint RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessDesktopAuthenticatedWebSocketIT" test`

Expected: FAIL until `WebSocketConfig` installs the interceptor only for business mode and the handler manages registry lifecycle.

- [ ] **Step 9: Wire the endpoint and run task GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=DesktopSessionTokenProviderTest,BusinessDesktopHandshakeInterceptorTest,BusinessDesktopConnectionRegistryTest,BusinessDesktopAuthenticatedWebSocketIT,JsonRpcWebSocketHandlerIT" test`

Expected: authenticated business mode and unchanged common WebSocket behavior both pass.

- [ ] **Step 10: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/application/auth/DesktopSessionTokenProvider.java backend/src/main/java/com/wzx/babiq/server/application/auth/BusinessDesktopHandshakeInterceptor.java backend/src/main/java/com/wzx/babiq/server/application/auth/TrustedDesktopConnection.java backend/src/main/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionRegistry.java backend/src/main/java/com/wzx/babiq/server/config/WebSocketConfig.java backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java backend/src/test/java/com/wzx/babiq/server/application/auth/DesktopSessionTokenProviderTest.java backend/src/test/java/com/wzx/babiq/server/application/auth/BusinessDesktopHandshakeInterceptorTest.java backend/src/test/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionRegistryTest.java backend/src/test/java/com/wzx/babiq/server/application/auth/BusinessDesktopAuthenticatedWebSocketIT.java
git commit -m "feat: 认证业务桌面本机会话"
```

### Task 22: Define the Java application protocol from the canonical fixtures

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationProtocol.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationEnvelope.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationIdentityMessage.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationCatalogMessage.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationActionMessage.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolValidator.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolFixtureTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolValidatorTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolVocabularyTest.java`

- [ ] **Step 1: Write the failing twenty-two-fixture Java contract test**

Enumerate the exact files under `docs/superpowers/contracts/huitai-business-desktop-agent/` from Task 15. Deserialize each into its Java record, serialize back to a canonical JSON tree, accept one injected unknown field, and compare method, IDs, timestamps, identity fields, terminal payloads, query responses, and `PROTOCOL_ERROR` response.

- [ ] **Step 2: Run fixture RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationProtocolFixtureTest" test`

Expected: compilation fails because Java protocol records do not exist.

- [ ] **Step 3: Implement the focused records and fixture mapping**

Use protocol version `1.0` and Jackson forward-compatible records. Keep identity, catalog/context, and action payloads in separate files; no handler or persistence behavior enters these records.

- [ ] **Step 4: Run fixture GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationProtocolFixtureTest" test`

Expected: all twenty-two Kotlin-authored fixtures round-trip through Java.

- [ ] **Step 5: Write failing validator and vocabulary tests**

Reuse the exact 256/128/128/64 KiB limits; test boundary and one-byte-over payloads, unsupported versions, nonpositive sequence/epoch, signed-out nullable fields, all terminal names, and all sixteen action error codes.

- [ ] **Step 6: Run validation RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationProtocolValidatorTest,ApplicationProtocolVocabularyTest" test`

Expected: FAIL because Java validation and vocabulary constants are absent.

- [ ] **Step 7: Implement validation and run task GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationProtocolFixtureTest,ApplicationProtocolValidatorTest,ApplicationProtocolVocabularyTest" test`

Expected: Java/Kotlin schema, limits, terminals, and errors match exactly.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationProtocol.java backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationEnvelope.java backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationIdentityMessage.java backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationCatalogMessage.java backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationActionMessage.java backend/src/main/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolValidator.java backend/src/test/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolFixtureTest.java backend/src/test/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolValidatorTest.java backend/src/test/java/com/wzx/babiq/server/application/protocol/ApplicationProtocolVocabularyTest.java
git commit -m "feat: 对齐业务桌面应用协议"
```

### Task 23: Bind trusted business identity and register the current catalog/context

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcMultiMethodHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/auth/TrustedBusinessIdentity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistry.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/catalog/ApplicationCatalogRegistry.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/catalog/ApplicationPageContextRegistry.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/api/ApplicationIdentityProtocolHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/api/ApplicationCatalogProtocolHandler.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicy.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcMultiMethodHandlerTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistryTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/catalog/ApplicationCatalogRegistryTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/api/ApplicationIdentityCatalogHandlersTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicyTest.java`

- [ ] **Step 1: Write failing multi-method dispatch tests**

Prove one handler may own a declared method set, duplicate aliases fail at startup, and the dispatcher passes the matched method while preserving every existing single-method handler and error code.

- [ ] **Step 2: Run dispatch RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=JsonRpcMultiMethodHandlerTest,JsonRpcDispatcherTest" test`

Expected: compilation fails because the multi-method extension is absent.

- [ ] **Step 3: Implement the dispatcher extension and run GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=JsonRpcMultiMethodHandlerTest,JsonRpcDispatcherTest" test`

Expected: alias routing and all legacy dispatcher tests pass.

- [ ] **Step 4: Write failing trusted identity/catalog/context tests**

Assert bind requires the authenticated WebSocket reservation. Every update requires a strictly higher identityEpoch and installs that epoch even when user/tenant/platform/authSession fields are unchanged; equal or older epochs are rejected. A normal Token refresh sends no identity update. Logout clears catalog/context; identity changes expire old pre-execution work through a callback; catalogEpoch/contextSequence are connection-local monotonic; catalog actions are filtered by permissions; payload identity must match the registry; stale, oversized, or disabled-action data is rejected; values/descriptions are stored as untrusted data.

- [ ] **Step 5: Run registry RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationIdentityRegistryTest,ApplicationCatalogRegistryTest" test`

Expected: FAIL because the trusted registries are absent.

- [ ] **Step 6: Implement registries and write failing handler/access-policy tests**

The identity handler owns `application/identity/bind|update`; the catalog handler owns `application/catalog/register|update` and `application/context/publish`. `BusinessJsonRpcAccessPolicy` is default-deny for the whole connection lifetime. Before identity bind it permits only `application/identity/bind|update`, `provider/list|create|update|delete|test|set-active`, `provider/oauth/status|login`, `model/providers/list|set-active`, `settings/get|update`, `sandbox/policy|set`, and `approval/policy|set`. After bind it additionally permits only `thread/create|list|load|archive`, `turn/start|cancel|interrupt`, `run/turns/list|turn/get`, `context/status|snapshot/get`, the five application catalog/context/identity methods, and these inbound desktop action methods: `application/action/accepted|previewed|approval-required|running|completed|failed|rejected|canceled|expired|outcome-unknown|status|result/get`. The server-only `application/action/request` and `application/action/cancel` are always denied if received from the client. `run/recovery/status` remains denied because its current report is process-global. Every other registered method returns the same business-mode method-denied error before handler invocation.

- [ ] **Step 7: Run handler RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationIdentityCatalogHandlersTest,BusinessJsonRpcAccessPolicyTest" test`

Expected: FAIL until handlers validate the trusted connection and the dispatcher enforces the policy before method invocation.

- [ ] **Step 8: Implement handlers/policy and run task GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=JsonRpcMultiMethodHandlerTest,ApplicationIdentityRegistryTest,ApplicationCatalogRegistryTest,ApplicationIdentityCatalogHandlersTest,BusinessJsonRpcAccessPolicyTest,JsonRpcDispatcherTest" test`

Expected: identity, catalog, context, both pre-bind/post-bind exact allowlists, default denial of every other registered method, and legacy common-mode dispatch all pass.

- [ ] **Step 9: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/api/JsonRpcMultiMethodHandler.java backend/src/main/java/com/wzx/babiq/server/api/JsonRpcDispatcher.java backend/src/main/java/com/wzx/babiq/server/application/auth/TrustedBusinessIdentity.java backend/src/main/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistry.java backend/src/main/java/com/wzx/babiq/server/application/catalog/ApplicationCatalogRegistry.java backend/src/main/java/com/wzx/babiq/server/application/catalog/ApplicationPageContextRegistry.java backend/src/main/java/com/wzx/babiq/server/application/api/ApplicationIdentityProtocolHandler.java backend/src/main/java/com/wzx/babiq/server/application/api/ApplicationCatalogProtocolHandler.java backend/src/main/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicy.java backend/src/test/java/com/wzx/babiq/server/api/JsonRpcMultiMethodHandlerTest.java backend/src/test/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistryTest.java backend/src/test/java/com/wzx/babiq/server/application/catalog/ApplicationCatalogRegistryTest.java backend/src/test/java/com/wzx/babiq/server/application/api/ApplicationIdentityCatalogHandlersTest.java backend/src/test/java/com/wzx/babiq/server/application/api/BusinessJsonRpcAccessPolicyTest.java
git commit -m "feat: 建立可信身份与页面动作目录"
```

### Task 24: Implement server-initiated requests and the pending application-action state machine

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationOutboundRequestTracker.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationOutboundJsonRpcClient.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationActionTimeoutProperties.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/action/PendingApplicationAction.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/action/PendingApplicationActions.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationActionTerminalStore.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/api/ApplicationActionProtocolHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnCancelHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnInterruptHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/action/ApplicationOutboundRequestTrackerTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/action/PendingApplicationActionsTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/api/ApplicationActionProtocolHandlerTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/api/JsonRpcBidirectionalMessageTest.java`

- [ ] **Step 1: Write failing bidirectional JSON-RPC and request-tracker tests**

Assert the handler distinguishes client Request, Notification, Response and ErrorResponse. Requests enter the dispatcher and receive one response; Notifications route to declared multi-method handlers and produce no JSON-RPC response; Response/ErrorResponse complete outbound correlation without invoking the dispatcher. Correlate one outbound request ID, remove pending entries on success/error/timeout/close, reject duplicate responses, and never log full action payloads.

- [ ] **Step 2: Run bidirectional RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationOutboundRequestTrackerTest,JsonRpcBidirectionalMessageTest" test`

Expected: FAIL because outbound correlation and response routing are absent.

- [ ] **Step 3: Implement the outbound client/tracker and run GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationOutboundRequestTrackerTest,JsonRpcBidirectionalMessageTest" test`

Expected: request, response, error, timeout, close, and redaction cases pass.

- [ ] **Step 4: Write the failing complete pending-action matrix**

Cover `REQUESTED -> ACCEPTED -> PREVIEWED -> APPROVAL_REQUIRED -> RUNNING -> terminal`, with read-only paths allowed to skip preview/approval. Every terminal is consumed once; cancel/terminal races use first-terminal-wins; duplicate executionId with different correlation conflicts; late results go only to `ApplicationActionTerminalStore`. Accept, preview, and approval timeout safely expire before side effects. Execute timeout sends `application/action/status`, adopts a stored terminal, waits a bounded grace period for RUNNING, otherwise returns `OUTCOME_UNKNOWN`; it never emits EXPIRED after execution may have started. WebSocket close cancels REQUESTED/ACCEPTED/PREVIEWED/APPROVAL_REQUIRED as disconnected-before-execute, while RUNNING becomes `OUTCOME_UNKNOWN` and is queued for reconciliation.

- [ ] **Step 5: Run pending-action RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=PendingApplicationActionsTest" test`

Expected: compilation fails because the pending state machine is absent.

- [ ] **Step 6: Implement pending state, timeouts, status query, and terminal storage**

Use `CompletableFuture` plus injected scheduler/clock. Do not use the generic approval registry or Spring AI HITL. `ApplicationActionTerminalStore` is a persistence port; Task 24 tests use an in-memory fake and Task 28 provides the SQLite implementation.

- [ ] **Step 7: Run pending GREEN and write failing protocol-handler tests**

Run: `cd backend; .\mvnw.cmd "-Dtest=PendingApplicationActionsTest" test`

Expected: every lifecycle, timeout, race, and late-result row passes.

The protocol handler owns accepted, previewed, approval-required, running, the six exact terminal notifications `completed|failed|rejected|canceled|expired|outcome-unknown`, cancel, status, and result/get. It verifies trusted connection identity before mutating or returning state. `TurnCancelHandler` and `TurnInterruptHandler` call a small pending-action cancellation method before closing the Turn: each live execution sends `application/action/cancel`; already RUNNING desktop work remains outcome-unknown/reconciliation if cancellation cannot be confirmed.

- [ ] **Step 8: Run protocol-handler RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationActionProtocolHandlerTest" test`

Expected: FAIL until every method maps to the pending/terminal stores and complete identity scope.

- [ ] **Step 9: Implement the handler and run task GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationOutboundRequestTrackerTest,PendingApplicationActionsTest,ApplicationActionProtocolHandlerTest,JsonRpcBidirectionalMessageTest" test`

Expected: outbound correlation and the complete application action protocol pass.

- [ ] **Step 10: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationOutboundRequestTracker.java backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationOutboundJsonRpcClient.java backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationActionTimeoutProperties.java backend/src/main/java/com/wzx/babiq/server/application/action/PendingApplicationAction.java backend/src/main/java/com/wzx/babiq/server/application/action/PendingApplicationActions.java backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationActionTerminalStore.java backend/src/main/java/com/wzx/babiq/server/application/api/ApplicationActionProtocolHandler.java backend/src/main/java/com/wzx/babiq/server/api/JsonRpcWebSocketHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/TurnCancelHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/TurnInterruptHandler.java backend/src/test/java/com/wzx/babiq/server/application/action/ApplicationOutboundRequestTrackerTest.java backend/src/test/java/com/wzx/babiq/server/application/action/PendingApplicationActionsTest.java backend/src/test/java/com/wzx/babiq/server/application/api/ApplicationActionProtocolHandlerTest.java backend/src/test/java/com/wzx/babiq/server/api/JsonRpcBidirectionalMessageTest.java
git commit -m "feat: 实现后端桌面动作等待模型"
```

### Task 25: Bind immutable business identity to Thread, Turn, context, and async execution

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/application/scope/BusinessIdentityScope.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/scope/BusinessIdentityScopeService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/Thread.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/Turn.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/TurnExecutor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeInput.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/observability/TurnObservationContext.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadCreateHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/scope/BusinessIdentityScopeServiceTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/scope/BusinessTurnScopePropagationTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopLineCountTest.java`

- [ ] **Step 1: Write failing trusted-scope resolution tests**

Resolve the immutable scope from the authenticated connection plus current bound identity. Require desktopInstanceId, desktopSessionId, authSessionId, identityEpoch, userId, tenantId, and platformId. Common mode returns an explicit unscoped marker; missing/stale business identity is rejected. Scope objects are value records with redacted `toString()`.

- [ ] **Step 2: Run scope RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessIdentityScopeServiceTest" test`

Expected: compilation fails because the scope model/service are absent.

- [ ] **Step 3: Implement scope resolution and run GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessIdentityScopeServiceTest" test`

Expected: trusted, stale, missing, and common-mode cases pass.

- [ ] **Step 4: Write failing immutable propagation tests**

Create a thread and turn under tenant A, switch the live connection to tenant B, then assert the existing thread, turn, `TurnExecutor` worker, `TurnObservationContext`, `ReActStrategy` ToolContext metadata, and `ContextWindowRuntimeInput` still carry tenant A's immutable scope. A tenant-B `turn/start` request using tenant A's threadId is rejected with the same response as a nonexistent thread and creates no Turn. Direct thread/turn construction used by legacy tests remains unscoped. No ThreadLocal or global-current-identity lookup is allowed after turn creation.

- [ ] **Step 5: Run propagation RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessTurnScopePropagationTest" test`

Expected: FAIL because Thread/Turn and async inputs do not carry scope.

- [ ] **Step 6: Implement explicit scope propagation**

`ThreadCreateHandler` resolves scope once; `ConversationService` copies it into Thread and every Turn. Before `TurnStartHandler` calls `startTurn`, it resolves current connection scope and requires exact equality with the stored Thread scope; mismatch uses the same not-found path. `AgentLoop` adds only the explicit `turn.businessIdentityScope()` arguments when creating `TurnObservationContext` and `ContextWindowRuntimeInput`; `ReActStrategy` reads that scope from the observation context for ToolContext metadata. Do not use ThreadLocal/global identity and keep the file within the 100-line guard.

- [ ] **Step 7: Run task GREEN and line guard**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessIdentityScopeServiceTest,BusinessTurnScopePropagationTest,TurnStartHandlerTest,AgentLoopLineCountTest" test`

Expected: immutable identity survives connection changes and AgentLoop remains at most 100 lines.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/application/scope/BusinessIdentityScope.java backend/src/main/java/com/wzx/babiq/server/application/scope/BusinessIdentityScopeService.java backend/src/main/java/com/wzx/babiq/server/conversation/Thread.java backend/src/main/java/com/wzx/babiq/server/conversation/Turn.java backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java backend/src/main/java/com/wzx/babiq/server/agent/TurnExecutor.java backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeInput.java backend/src/main/java/com/wzx/babiq/server/observability/TurnObservationContext.java backend/src/main/java/com/wzx/babiq/server/api/method/ThreadCreateHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/TurnStartHandler.java backend/src/test/java/com/wzx/babiq/server/application/scope/BusinessIdentityScopeServiceTest.java backend/src/test/java/com/wzx/babiq/server/application/scope/BusinessTurnScopePropagationTest.java backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopLineCountTest.java
git commit -m "feat: 绑定业务 Turn 不可变身份"
```

### Task 26: Implement the `application_action` tool and progress item

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationToolInvocationContext.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationActionTool.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationActionToolResult.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/conversation/items/ApplicationActionItem.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/items/ThreadItem.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/interceptor/ToolObservationInterceptor.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/tool/ApplicationToolInvocationContextTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/tool/ApplicationActionToolTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/conversation/items/ApplicationActionItemTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/conversation/items/ThreadItemJsonTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/interceptor/ToolObservationInterceptorTest.java`

- [ ] **Step 1: Write failing tool-call correlation tests**

For `application_action`, assert `ToolObservationInterceptor` installs toolCallId/threadId/turnId and the immutable scope from `TurnObservationContext` during the downstream call and clears it in `finally`; nested or concurrent calls do not leak. Other tools are unchanged.

- [ ] **Step 2: Run correlation RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationToolInvocationContextTest" test`

Expected: compilation fails because the scoped correlation context is absent.

- [ ] **Step 3: Implement correlation scope and run GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationToolInvocationContextTest" test`

Expected: correlation, identity, and cleanup pass under success, failure, nesting, and concurrency.

- [ ] **Step 4: Write failing `application_action` tests**

Assert the tool validates the immutable Turn scope, action ID/version, permission-filtered catalog entry, current contextRevision, and JSON input size; generates one executionId; sends `application/action/request`; waits through `PendingApplicationActions`; returns a short structured terminal result; never reads secrets; never invokes generic HITL; and maps every desktop terminal/error code without retrying `OUTCOME_UNKNOWN`.

- [ ] **Step 5: Run tool RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationActionToolTest" test`

Expected: FAIL because the tool is absent.

- [ ] **Step 6: Implement the tool and progress emission**

Create one `applicationAction` item at request time and update it for accepted, previewed, approval-required, running, and terminal states. The item contains only IDs, title, risk, status, redacted preview/error summaries, and duration; it is display-only and never enters recent-history context.

- [ ] **Step 7: Run item/tool GREEN and regression**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationActionToolTest,ApplicationActionItemTest,ThreadItemJsonTest,ToolObservationInterceptorTest" test`

Expected: tool behavior, item round-trip, progress ordering, immutable identity, and existing tool observation pass.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationToolInvocationContext.java backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationActionTool.java backend/src/main/java/com/wzx/babiq/server/application/tool/ApplicationActionToolResult.java backend/src/main/java/com/wzx/babiq/server/conversation/items/ApplicationActionItem.java backend/src/main/java/com/wzx/babiq/server/conversation/items/ThreadItem.java backend/src/main/java/com/wzx/babiq/server/conversation/ItemEmitter.java backend/src/main/java/com/wzx/babiq/server/interceptor/ToolObservationInterceptor.java backend/src/test/java/com/wzx/babiq/server/application/tool/ApplicationToolInvocationContextTest.java backend/src/test/java/com/wzx/babiq/server/application/tool/ApplicationActionToolTest.java backend/src/test/java/com/wzx/babiq/server/conversation/items/ApplicationActionItemTest.java backend/src/test/java/com/wzx/babiq/server/conversation/items/ThreadItemJsonTest.java backend/src/test/java/com/wzx/babiq/server/interceptor/ToolObservationInterceptorTest.java
git commit -m "feat: 接通 application_action 工具"
```

### Task 27: Enforce the business tool allowlist, HITL boundary, and page-context injection

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/application/policy/BusinessAgentModePolicy.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/context/ApplicationContextModelContributor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/capability/CapabilityExposurePlanner.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/security/SystemPromptSecurityRule.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/policy/BusinessAgentModePolicyTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/context/ApplicationContextModelContributorTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/agent/ReActStrategyTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/capability/CapabilityExposurePlannerTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/security/SystemPromptSecurityRuleTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopLineCountTest.java`

- [ ] **Step 1: Write failing business-policy tests**

In business mode the exact model-visible tools are `application_action` and `update_plan`; every other local/MCP/Skill/Flow/Team/WorkUnit/sub-Agent tool is absent even if registered. Common mode retains current exposure behavior. `application_action` is excluded from generic HITL for `NEVER`, `ON_REQUEST`, and `ALWAYS`.

- [ ] **Step 2: Run policy RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessAgentModePolicyTest,CapabilityExposurePlannerTest,ReActStrategyTest" test`

Expected: FAIL because fixed business exposure and HITL exclusion are absent.

- [ ] **Step 3: Implement the mode policy and run GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessAgentModePolicyTest,CapabilityExposurePlannerTest,ReActStrategyTest" test`

Expected: strict business allowlist and unchanged common mode pass.

- [ ] **Step 4: Write failing immutable-scope context tests**

Use `ContextWindowRuntimeInput.businessIdentityScope`, never current connection identity. Contribute only that scope's latest permission-filtered catalog summaries and sanitized page context under `<untrusted-data source="business_application">`; exclude secrets, disabled actions, over-budget schemas, stale context, and `ApplicationActionItem`; long-term memory references remain empty.

- [ ] **Step 5: Run context RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationContextModelContributorTest,ContextWindowRuntimeTest,SystemPromptSecurityRuleTest" test`

Expected: FAIL because the contributor and business prompt rules are absent.

- [ ] **Step 6: Implement contribution and prompt rules**

`ContextWindowRuntime` adds the bounded untrusted reference to workspace facts before snapshot/rendering. The prompt requires `application_action`, forbids claims of direct UI mutation, and waits for desktop terminal results.

- [ ] **Step 7: Run task GREEN and line guard**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessAgentModePolicyTest,ApplicationContextModelContributorTest,CapabilityExposurePlannerTest,ReActStrategyTest,ContextWindowRuntimeTest,SystemPromptSecurityRuleTest,AgentLoopLineCountTest" test`

Expected: policy/context tests pass and AgentLoop stays at most 100 lines.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/application/policy/BusinessAgentModePolicy.java backend/src/main/java/com/wzx/babiq/server/application/context/ApplicationContextModelContributor.java backend/src/main/java/com/wzx/babiq/server/agent/ReActStrategy.java backend/src/main/java/com/wzx/babiq/server/capability/CapabilityExposurePlanner.java backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java backend/src/main/java/com/wzx/babiq/server/security/SystemPromptSecurityRule.java backend/src/test/java/com/wzx/babiq/server/application/policy/BusinessAgentModePolicyTest.java backend/src/test/java/com/wzx/babiq/server/application/context/ApplicationContextModelContributorTest.java backend/src/test/java/com/wzx/babiq/server/agent/ReActStrategyTest.java backend/src/test/java/com/wzx/babiq/server/capability/CapabilityExposurePlannerTest.java backend/src/test/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntimeTest.java backend/src/test/java/com/wzx/babiq/server/security/SystemPromptSecurityRuleTest.java backend/src/test/java/com/wzx/babiq/server/agent/AgentLoopLineCountTest.java
git commit -m "feat: 收紧业务 Agent 能力与上下文"
```

### Task 28: Persist identity scope and backend application-action event audit with V23

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__business_desktop_identity_scope.sql`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ThreadEntity.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/TurnEntity.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextWindowEntity.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextSnapshotEntity.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ToolCallEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ApplicationActionEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/entity/ApplicationActionEventEntity.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ApplicationActionMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ApplicationActionEventMapper.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/action/SQLiteApplicationActionTerminalStore.java`
- Create: `backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationActionRecoveryService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationApplicationService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/repository/ConversationRepository.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/repository/TurnRecord.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteConversationRepository.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/TurnPersistenceService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/ToolCallPersistenceService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/interceptor/ToolObservationInterceptor.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/ContextStatusService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextWindowRecord.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSnapshotRecord.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextWindowRepository.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSnapshotRepository.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextWindowRepository.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextSnapshotRepository.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/observability/RunRecordService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryStartupRunner.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadListHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadLoadHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ThreadArchiveHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnCancelHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/TurnInterruptHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/RunTurnsListHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/RunTurnGetHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ContextStatusHandler.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/api/method/ContextSnapshotGetHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/persistence/BusinessIdentityScopeMigrationTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/action/SQLiteApplicationActionTerminalStoreTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/action/ApplicationActionRecoveryServiceTest.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/application/scope/BusinessScopedHandlersIT.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java`

- [ ] **Step 1: Write failing V23 migration and comment tests**

Add nullable legacy-compatible identity columns to threads, turns, context windows/snapshots and tool calls, plus execution_id on tool calls. Create `bq_application_actions` with exact columns `execution_id, action_id, action_version, request_fingerprint, thread_id, turn_id, tool_call_id, desktop_instance_id, desktop_session_id, auth_session_id, identity_epoch, user_id, tenant_id, platform_id, status, result_summary_redacted, error_code, error_message_redacted, created_at, updated_at, terminal_at`; create `bq_application_action_events` with `event_id, execution_id, event_sequence, event_type, from_status, to_status, payload_summary_redacted, late_result, occurred_at`. Require PK/FK/CHECK constraints, `UNIQUE(execution_id,event_sequence)`, a unique non-null toolCall correlation, scope/status indexes, and UPDATE/DELETE rejection triggers on the event table. Every field has Chinese comments.

- [ ] **Step 2: Run migration RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessIdentityScopeMigrationTest,SchemaCommentsCoverageTest" test`

Expected: FAIL because V23 and mappings are absent.

- [ ] **Step 3: Implement migration/entities/mappers and run GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessIdentityScopeMigrationTest,SchemaCommentsCoverageTest" test`

Expected: current-state table, append-only event table/triggers, indexes, entities, and Chinese comments pass.

- [ ] **Step 4: Write failing action-store/recovery tests**

Persist every transition as an immutable event; current row uses first-terminal-wins; late results append `late_result=1` without changing terminal; exact identity/session queries only. Startup expires pre-execution actions, converts EXECUTING to OUTCOME_UNKNOWN/orphaned, appends recovery events, and never resumes old model Turns.

- [ ] **Step 5: Run action persistence RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=SQLiteApplicationActionTerminalStoreTest,ApplicationActionRecoveryServiceTest" test`

Expected: FAIL because store/recovery are absent.

- [ ] **Step 6: Implement store/recovery and run GREEN**

Run: `cd backend; .\mvnw.cmd "-Dtest=SQLiteApplicationActionTerminalStoreTest,ApplicationActionRecoveryServiceTest,TurnRecoveryServiceTest" test`

Expected: event ordering, trigger enforcement, late results, recovery, and common Turn recovery pass.

- [ ] **Step 7: Write failing scoped handler/repository tests**

Scope every permitted post-bind method that touches business data: thread list/load/archive, turn cancel/interrupt, run list/get, context status/snapshot, and all application action queries. Assert thread creation and turn persistence copy the Task 25 immutable scope into `bq_threads` and `bq_turns`. After switching the live connection to tenant B, execute a tenant-A tool and assert `ToolObservationInterceptor -> ToolCallPersistenceService` writes tenant A's scope into `bq_tool_calls`. Confirm denied methods never reach repositories. Two users/tenants cannot observe each other; mismatch and nonexistent targets return indistinguishable responses.

- [ ] **Step 8: Run scope RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessScopedHandlersIT" test`

Expected: FAIL because permitted query/mutation paths lack scoped repository overloads.

- [ ] **Step 9: Implement scoped services/repositories/handlers**

Add scope fields to `TurnRecord`, `ContextWindowRecord`, and `ContextSnapshotRecord`; `ConversationService.persistThreadIfEnabled` and `persistTurnStarted` copy the Thread/Turn scope; `ContextWindowRuntime` copies `ContextWindowRuntimeInput.businessIdentityScope` into both context records. Pass scope explicitly through conversation, turn persistence, run aggregation, context status and context repositories. `ToolObservationInterceptor` passes `TurnObservationContext.businessIdentityScope()` into a scoped `ToolCallPersistenceService.recordStarted` overload. Do not add context/compact, runtime item mutation, observability, memory, MCP, Skill, WorkUnit or Team scope because Task 23 rejects those methods before invocation.

- [ ] **Step 10: Run task verification**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessScopedHandlersIT,SQLiteApplicationActionTerminalStoreTest,ApplicationActionRecoveryServiceTest,SchemaCommentsCoverageTest,AgentLoopLineCountTest" test`

Expected: all permitted paths are scoped, all denied paths stay denied, action event audit/recovery passes, and line guard passes.

- [ ] **Step 11: Commit**

```powershell
git add backend/src/main/resources/db/migration/V23__business_desktop_identity_scope.sql backend/src/main/java/com/wzx/babiq/server/persistence/entity/ThreadEntity.java backend/src/main/java/com/wzx/babiq/server/persistence/entity/TurnEntity.java backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextWindowEntity.java backend/src/main/java/com/wzx/babiq/server/persistence/entity/ContextSnapshotEntity.java backend/src/main/java/com/wzx/babiq/server/persistence/entity/ToolCallEntity.java backend/src/main/java/com/wzx/babiq/server/persistence/entity/ApplicationActionEntity.java backend/src/main/java/com/wzx/babiq/server/persistence/entity/ApplicationActionEventEntity.java backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ApplicationActionMapper.java backend/src/main/java/com/wzx/babiq/server/persistence/mapper/ApplicationActionEventMapper.java backend/src/main/java/com/wzx/babiq/server/application/action/SQLiteApplicationActionTerminalStore.java backend/src/main/java/com/wzx/babiq/server/application/action/ApplicationActionRecoveryService.java backend/src/main/java/com/wzx/babiq/server/conversation/ConversationApplicationService.java backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java backend/src/main/java/com/wzx/babiq/server/conversation/repository/ConversationRepository.java backend/src/main/java/com/wzx/babiq/server/conversation/repository/TurnRecord.java backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteConversationRepository.java backend/src/main/java/com/wzx/babiq/server/persistence/service/TurnPersistenceService.java backend/src/main/java/com/wzx/babiq/server/persistence/service/ToolCallPersistenceService.java backend/src/main/java/com/wzx/babiq/server/interceptor/ToolObservationInterceptor.java backend/src/main/java/com/wzx/babiq/server/context/ContextStatusService.java backend/src/main/java/com/wzx/babiq/server/context/runtime/ContextWindowRuntime.java backend/src/main/java/com/wzx/babiq/server/context/repository/ContextWindowRecord.java backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSnapshotRecord.java backend/src/main/java/com/wzx/babiq/server/context/repository/ContextWindowRepository.java backend/src/main/java/com/wzx/babiq/server/context/repository/ContextSnapshotRepository.java backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextWindowRepository.java backend/src/main/java/com/wzx/babiq/server/persistence/service/SQLiteContextSnapshotRepository.java backend/src/main/java/com/wzx/babiq/server/observability/RunRecordService.java backend/src/main/java/com/wzx/babiq/server/recovery/RecoveryStartupRunner.java backend/src/main/java/com/wzx/babiq/server/api/method/ThreadListHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/ThreadLoadHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/ThreadArchiveHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/TurnCancelHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/TurnInterruptHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/RunTurnsListHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/RunTurnGetHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/ContextStatusHandler.java backend/src/main/java/com/wzx/babiq/server/api/method/ContextSnapshotGetHandler.java backend/src/test/java/com/wzx/babiq/server/persistence/BusinessIdentityScopeMigrationTest.java backend/src/test/java/com/wzx/babiq/server/application/action/SQLiteApplicationActionTerminalStoreTest.java backend/src/test/java/com/wzx/babiq/server/application/action/ApplicationActionRecoveryServiceTest.java backend/src/test/java/com/wzx/babiq/server/application/scope/BusinessScopedHandlersIT.java backend/src/test/java/com/wzx/babiq/server/persistence/SchemaCommentsCoverageTest.java
git commit -m "feat: 持久化业务身份与动作事件审计"
```

### Task 29: Verify the complete backend application bridge end to end

**Files:**
- Create: `backend/src/main/java/com/wzx/babiq/server/application/ApplicationBridgeLifecycleCoordinator.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionRegistry.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistry.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/application/action/PendingApplicationActions.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/conversation/TurnStatus.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/persistence/service/TurnPersistenceService.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/PendingApprovals.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java`
- Modify: `backend/src/main/java/com/wzx/babiq/server/agent/AgentLoopOutputHandler.java`
- Test: `backend/src/test/java/com/wzx/babiq/server/conversation/TurnStatusTest.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/application/ApplicationBridgeEndToEndIT.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/application/BusinessToolAllowlistIT.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/application/BusinessProfileParallelIsolationIT.java`
- Create: `backend/src/test/java/com/wzx/babiq/server/application/ApplicationProtocolReconnectIT.java`

- [ ] **Step 1: Write the failing bridge E2E test**

Start the real business profile with temporary runtime paths and authenticated WebSocket. Bind identity, register a generic catalog/context, create thread/turn, invoke `application_action` through the real ToolCallback, receive request, send preview/running/completed, and assert the tool returns the desktop terminal, the progress item sequence is persisted, and toolCallId maps to one executionId. Repeat high-risk denial, cancellation race, duplicate executionId, and OUTCOME_UNKNOWN reconciliation.

- [ ] **Step 2: Run bridge E2E RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationBridgeEndToEndIT" test`

Expected: FAIL because connection close and identity-change callbacks are not yet coordinated across pending actions, catalog/context invalidation, Turn expiration, and terminal persistence.

- [ ] **Step 3: Implement the lifecycle coordinator and run bridge GREEN**

`ApplicationBridgeLifecycleCoordinator` registers explicit listeners with the connection and identity registries. Connection close clears outbound request correlation, invalidates connection-local catalog/context, and applies Task 24 disconnect semantics to pending actions. Identity update calls a scoped `ConversationService.expirePreExecutionTurns(oldScope, reason)`: add the tested `CREATED -> EXPIRED` Turn transition, expire WAITING_APPROVAL, and leave old-scope RUNNING Turns untouched. `PendingApprovals.remove(threadId)` clears approval metadata; add `AgentLoop.forgetPaused(threadId)` delegating to `AgentLoopOutputHandler.forgetPaused` so the paused ReactAgent cache is also removed. Only application actions already in EXECUTING remain in old-scope reconciliation. Tests prove old CREATED/WAITING Turns cannot start or resume under the new identity. The coordinator contains no tool, protocol parsing, or SQL.

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationBridgeEndToEndIT" test`

Expected: read-only, preview confirmation, high-risk denial, cancel race, idempotency, and uncertain outcome flows pass.

- [ ] **Step 4: Write failing security/reconnect/isolation ITs**

Prove model-visible tools are exactly two; attempts to expose Shell/file/MCP/Skill/Flow/Team/sub-Agent fail; generic HITL is not emitted for application_action; reconnect republishes identity/catalog/context and queries persisted action state; new desktopSessionId cannot attach old actions; common and business profiles can run in parallel without sharing database, KeyStore, logs, memory, locks, recovery, or sessions.

- [ ] **Step 5: Run security/reconnect RED**

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessToolAllowlistIT,ApplicationProtocolReconnectIT,BusinessProfileParallelIsolationIT" test`

Expected: FAIL if any fixed allowlist, default-deny JSON-RPC policy, reconnect scope, or runtime-path isolation from Tasks 20-28 is not actually wired in the Spring context.

- [ ] **Step 6: Run focused GREEN without new production changes**

If this command fails, return the defect to the owning Task 20-28 and complete that task's RED/GREEN cycle before rerunning this verification task. Do not patch policy, protocol, scope, or profile production files inside Task 29.

Run: `cd backend; .\mvnw.cmd "-Dtest=ApplicationBridgeEndToEndIT,BusinessToolAllowlistIT,ApplicationProtocolReconnectIT,BusinessProfileParallelIsolationIT" test`

Expected: bridge, allowlist, reconnect, and parallel isolation ITs pass.

- [ ] **Step 7: Run the full backend regression**

Run: `cd backend; .\mvnw.cmd clean verify`

Expected: all unit tests and every `*IT` pass with BUILD SUCCESS; existing common desktop protocol behavior remains green.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/wzx/babiq/server/application/ApplicationBridgeLifecycleCoordinator.java backend/src/main/java/com/wzx/babiq/server/application/auth/BusinessDesktopConnectionRegistry.java backend/src/main/java/com/wzx/babiq/server/application/auth/ApplicationIdentityRegistry.java backend/src/main/java/com/wzx/babiq/server/application/action/PendingApplicationActions.java backend/src/main/java/com/wzx/babiq/server/conversation/ConversationService.java backend/src/main/java/com/wzx/babiq/server/conversation/TurnStatus.java backend/src/main/java/com/wzx/babiq/server/persistence/service/TurnPersistenceService.java backend/src/main/java/com/wzx/babiq/server/agent/PendingApprovals.java backend/src/main/java/com/wzx/babiq/server/agent/AgentLoop.java backend/src/main/java/com/wzx/babiq/server/agent/AgentLoopOutputHandler.java backend/src/test/java/com/wzx/babiq/server/conversation/TurnStatusTest.java backend/src/test/java/com/wzx/babiq/server/application/ApplicationBridgeEndToEndIT.java backend/src/test/java/com/wzx/babiq/server/application/BusinessToolAllowlistIT.java backend/src/test/java/com/wzx/babiq/server/application/BusinessProfileParallelIsolationIT.java backend/src/test/java/com/wzx/babiq/server/application/ApplicationProtocolReconnectIT.java
git commit -m "test: 验证业务桌面 Agent 后端桥接"
```

## Chunk 4: Framework Demo, Compose Product, Packaging, and Acceptance

### Task 30: Build the generic demo domain, seven actions, and uncertain remote outcome

**Files:**
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/model/DemoFormState.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/model/DemoFormEvent.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/model/DemoFormReducer.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/model/DemoScreenModel.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/gateway/FakeHuitaiGateway.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/DemoActionCatalog.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/page/PageNavigateAction.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/page/PageReadContextAction.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/form/FormReadStateAction.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/form/FormPreviewPatchAction.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/form/FormApplyPatchAction.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/remote/DemoSaveDraftAction.kt`
- Create: `business-desktop/framework-demo/src/main/kotlin/com/wzx/huitai/demo/action/remote/DemoSubmitAction.kt`
- Test: `business-desktop/framework-demo/src/test/kotlin/com/wzx/huitai/demo/model/DemoFormReducerTest.kt`
- Test: `business-desktop/framework-demo/src/test/kotlin/com/wzx/huitai/demo/gateway/FakeHuitaiGatewayTest.kt`
- Test: `business-desktop/framework-demo/src/test/kotlin/com/wzx/huitai/demo/action/DemoActionCatalogTest.kt`
- Test: `business-desktop/framework-demo/src/test/kotlin/com/wzx/huitai/demo/action/DemoActionBusIntegrationTest.kt`

- [ ] **Step 1: Write failing generic-form reducer and fake-gateway tests**

Use only the seven approved fields: `资料名称`, `资料类型`, `联系人`, `金额`, `日期`, `状态`, and `详细说明`. Prove edits increment revision, suggestions do not mutate committed values, accepting one suggestion applies only that field, accepting all uses one revision-checked patch, and a user edit after suggestion creation makes the old patch stale. The fake gateway stores draft/submission records by executionId, supports remote idempotency lookup, and has a deterministic mode that commits the record and then returns `SentButResponseLost`.

- [ ] **Step 2: Run domain RED**

Run: `cd business-desktop; .\gradlew.bat :framework-demo:test --tests "*DemoFormReducerTest" --tests "*FakeHuitaiGatewayTest"`

Expected: compilation fails because the demo state, reducer, and gateway are absent.

- [ ] **Step 3: Implement the generic domain and gateway, then run GREEN**

`DemoScreenModel` implements the Task 7 screen contracts and derives `PageContextSnapshot` from the same immutable revision. Use generic demo identity and references only; no customer, case, document, lawyer, OA endpoint, or external repository dependency is permitted.

Run: `cd business-desktop; .\gradlew.bat :framework-demo:test --tests "*DemoFormReducerTest" --tests "*FakeHuitaiGatewayTest"`

Expected: revision, suggestion, stale-patch, idempotent storage, and response-loss cases pass.

- [ ] **Step 4: Write the failing exact-action-catalog test**

Require exactly these descriptors and risks:

```text
page.navigate       REVERSIBLE_WRITE  SAFE
page.read_context   READ_ONLY          SAFE
form.read_state     READ_ONLY          SAFE
form.preview_patch  READ_ONLY          SAFE
form.apply_patch    REVERSIBLE_WRITE  SAFE
demo.save_draft     REVERSIBLE_WRITE  IDEMPOTENCY_KEY_REQUIRED
demo.submit         HIGH_RISK         NEVER
```

Both remote actions use `QUERY_REMOTE`; every input codec rejects unknown fields and invalid types. `preview()` is side-effect free for all seven actions.

- [ ] **Step 5: Run action RED**

Run: `cd business-desktop; .\gradlew.bat :framework-demo:test --tests "*DemoActionCatalogTest"`

Expected: FAIL because the actions and catalog are absent.

- [ ] **Step 6: Implement the seven focused actions and run catalog GREEN**

Keep one action per file. Page and form writes dispatch typed `DemoFormEvent`; remote actions call only `FakeHuitaiGateway`. `DemoSubmitAction` never retries execute after an ambiguous send and reconciles by executionId.

Run: `cd business-desktop; .\gradlew.bat :framework-demo:test --tests "*DemoActionCatalogTest"`

Expected: exact descriptors, pure previews, codecs, and action registration pass.

- [ ] **Step 7: Write and pass the common-ActionBus integration test**

Send the same `form.apply_patch` once as `origin=USER` and once as `origin=AGENT`; assert both traverse validation, preview, confirmation, execution, revision update, and audit through one `ApplicationActionBus`. Then run `demo.submit` in response-loss mode: assert one remote write, `OUTCOME_UNKNOWN`, no automatic execute retry, one reconciliation query, and terminal success after the fake gateway confirms the committed record.

Run: `cd business-desktop; .\gradlew.bat :framework-demo:test --tests "*DemoActionBusIntegrationTest"`

Expected: shared-bus, high-risk approval, idempotency, uncertain outcome, and reconciliation pass.

- [ ] **Step 8: Commit**

```powershell
git add business-desktop/framework-demo
git commit -m "feat: 实现通用业务框架演示动作"
```

### Task 31: Implement business Agent conversation state and desktop coordination

**Files:**
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessThreadModels.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessAgentClient.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessAgentEvents.kt`
- Create: `business-desktop/agent-client-core/src/main/kotlin/com/wzx/huitai/agent/conversation/BusinessProviderModels.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/state/BusinessDesktopState.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/state/BusinessDesktopReducer.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessConversationController.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessWorkspaceController.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller/BusinessDesktopCoordinator.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/conversation/BusinessThreadModelsTest.kt`
- Test: `business-desktop/agent-client-core/src/test/kotlin/com/wzx/huitai/agent/conversation/BusinessAgentClientTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/state/BusinessDesktopReducerTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller/BusinessDesktopCoordinatorTest.kt`

- [ ] **Step 1: Write failing conversation protocol tests**

Decode `userMessage`, `agentMessage`, `reasoning`, `plan`, `applicationAction`, and `turnSummary` items plus unknown forward-compatible items. Test `provider/list`, `provider/set-active`, `thread/create`, `turn/start`, `turn/cancel`, event correlation, streaming item updates, and JSON-RPC error propagation through the Task 17 client; do not copy the existing `desktop/` client wholesale. Provider models expose only IDs, display names, models, auth mode, and `hasApiKey`; API keys never enter desktop state.

- [ ] **Step 2: Run conversation RED**

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*BusinessThreadModelsTest" --tests "*BusinessAgentClientTest"`

Expected: compilation fails because business conversation models and client are absent.

- [ ] **Step 3: Implement the focused conversation client and run GREEN**

One request correlation map remains owned by `AgentJsonRpcClient`. `BusinessAgentClient` only adds typed business thread/turn methods and maps notifications to `BusinessAgentEvent`; it never dispatches application actions directly.

Run: `cd business-desktop; .\gradlew.bat :agent-client-core:test --tests "*BusinessThreadModelsTest" --tests "*BusinessAgentClientTest"`

Expected: typed items, streaming updates, cancellation, errors, and unknown items pass.

- [ ] **Step 4: Write failing reducer/coordinator tests**

Cover initial disconnected state, authenticated registration order, current page revision, form suggestions and sources, chat messages, plan replacement, application-action progress, turn summary, ordinary error, membership/auth expiry, Agent disconnect, reconnect, manual retry, and shutdown. Assert page revisions publish context after identity and catalog; duplicate revisions are coalesced; identity switch clears old suggestions and pre-execution actions; late old-scope results remain audit-only.

- [ ] **Step 5: Run coordinator RED**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopReducerTest" --tests "*BusinessDesktopCoordinatorTest"`

Expected: FAIL because the state and three focused controllers are absent.

- [ ] **Step 6: Implement state, reducers, and coordination**

`BusinessConversationController` owns chat turns; `BusinessWorkspaceController` owns screen/context publication and calls the shared ActionBus for user actions; `BusinessDesktopCoordinator` wires lifecycle and identity without absorbing either controller's logic. All externally visible state is immutable `StateFlow` data.

- [ ] **Step 7: Run task GREEN and commit**

Run:

```powershell
cd business-desktop
.\gradlew.bat :agent-client-core:test
.\gradlew.bat :app:test --tests "*BusinessDesktopReducerTest" --tests "*BusinessDesktopCoordinatorTest"
```

Expected: conversation, page publication, action progress, identity boundary, errors, and reconnect state pass.

```powershell
git add business-desktop/agent-client-core business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/state business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/controller business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/state business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/controller
git commit -m "feat: 建立业务桌面 Agent 会话状态"
```

### Task 32: Build the responsive three-column Compose desktop experience

**Files:**
- Modify: `business-desktop/app/build.gradle.kts`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/theme/HuitaiBusinessTheme.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicy.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShell.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessSidebar.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/form/DemoFormPanel.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/form/FieldSuggestionDecoration.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanel.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/FormPatchCard.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/AgentPlanCard.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/ApplicationActionProgressCard.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/AgentComposer.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessProviderSelector.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/common/ConnectionBanner.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/layout/BusinessDesktopLayoutPolicyTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/shell/BusinessDesktopShellTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/form/DemoFormPanelTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/agent/BusinessAgentPanelTest.kt`

- [ ] **Step 1: Add Compose UI test support and write failing layout tests**

Add `testImplementation(compose.uiTestJUnit4)`. Lock three deterministic modes: `WIDE >= 1280` uses 210px navigation and 420px Agent panel; `MEDIUM 1024..1279` uses 72px navigation and 360px Agent panel; `COMPACT < 1024` shows one content tab at a time. Assert the middle form never overlaps the Agent panel and retains at least 560px in wide/medium modes.

- [ ] **Step 2: Run layout RED**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopLayoutPolicyTest"`

Expected: compilation fails because the layout policy is absent.

- [ ] **Step 3: Implement theme, layout policy, and shell skeleton**

Use the confirmed prototype's blue-gray visual language but keep every production label generic. The left rail contains only `工作台`, `资料录入`, `运行记录`, and `设置`; no customer/case/document navigation is allowed in the framework build.

- [ ] **Step 4: Write failing form and Agent panel UI tests**

Use stable test tags. Assert all seven fields render; suggested fields show source/confidence; one-field and accept-all callbacks carry the correct field IDs and base revision; user edits remove only that field's suggestion. The Agent panel renders messages, collapsed reasoning, current plan, action lifecycle, provider/model selector, error/disconnect state, composer disabled state, reconnect action, and TurnSummary without price/cost. Provider changes apply only to the next turn and never reveal API keys.

- [ ] **Step 5: Run UI RED**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopShellTest" --tests "*DemoFormPanelTest" --tests "*BusinessAgentPanelTest"`

Expected: FAIL because the panels and tagged controls are absent.

- [ ] **Step 6: Implement the form and Agent panels**

`FormPatchCard` displays old/new value, reason, confidence, and source before any mutation. `ApplicationActionProgressCard` renders received/preview/approval/running/terminal states and `OUTCOME_UNKNOWN` reconciliation guidance. Unknown protocol items render a safe diagnostic row, not raw secret-bearing JSON.

- [ ] **Step 7: Run all Compose tests and commit**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopLayoutPolicyTest" --tests "*BusinessDesktopShellTest" --tests "*DemoFormPanelTest" --tests "*BusinessAgentPanelTest"`

Expected: wide, medium, compact, field-suggestion, chat, plan, action-progress, and disconnected UI tests pass.

```powershell
git add business-desktop/app/build.gradle.kts business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui
git commit -m "feat: 实现业务桌面三栏 Compose 界面"
```

### Task 33: Connect Compose preview confirmation and per-execution high-risk approval

**Files:**
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/decision/ActionDecisionState.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/decision/ComposeActionDecisionCoordinator.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/decision/ComposeConfirmationPort.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/decision/ComposeApprovalPort.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/action/ActionPreviewDialog.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/action/HighRiskApprovalDialog.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/decision/ComposeActionDecisionCoordinatorTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/action/ActionDecisionDialogTest.kt`

- [ ] **Step 1: Write failing decision-coordinator tests**

Prove one executionId owns one deferred decision, duplicate registration conflicts, ordinary confirmation and high-risk approval are distinct phases, decision consumes once, timeout maps to the exact expired result, Agent disconnect before execute cancels, shutdown completes every waiter, and late clicks are ignored. No decision can be shared across executionIds or stored as session-wide allow.

- [ ] **Step 2: Run decision RED**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*ComposeActionDecisionCoordinatorTest"`

Expected: compilation fails because the Compose decision adapters are absent.

- [ ] **Step 3: Implement decision state and port adapters**

The coordinator exposes immutable dialog state to Compose. The two port adapters only translate ActionBus requests into coordinator waits; they contain no action execution, persistence, or protocol code.

- [ ] **Step 4: Write failing dialog tests**

Preview dialog shows action title, origin, structured differences, and confirm/cancel. High-risk dialog additionally shows risk reason, tenant/user summary, remote side-effect warning, and an explicit approval checkbox; it has no `始终允许` control. Both redact registered sensitive values and close on expiry/disconnect.

- [ ] **Step 5: Run dialog RED, implement, and run task GREEN**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*ComposeActionDecisionCoordinatorTest" --tests "*ActionDecisionDialogTest"`

Expected after implementation: concurrency, timeout, disconnect, single-use decisions, preview rendering, high-risk rendering, and redaction pass.

- [ ] **Step 6: Commit**

```powershell
git add business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/decision business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/ui/action business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/decision business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/ui/action
git commit -m "feat: 接入桌面动作确认与高风险审批"
```

### Task 34: Assemble the executable app and bundled business Agent lifecycle

**Files:**
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessDesktopRuntimePaths.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/DesktopInstallationIdentityStore.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/DesktopSessionTokenFile.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentLaunchRequest.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentProcessLauncher.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentRuntimeSession.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentReadinessProbe.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/logging/DesktopLoggingBootstrap.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRoot.kt`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessDesktopRuntimePathsTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/DesktopSessionTokenFileTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime/BusinessAgentProcessLauncherTest.kt`
- Test: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app/BusinessDesktopCompositionRootTest.kt`

- [ ] **Step 1: Write failing path, installation-ID, and one-shot-token tests**

Under a supplied home, require the exact isolated `agent/` and `desktop/` trees from the design. The installation ID is atomic and stable; desktopSessionId and a 256-bit token are new for each child launch. The token file uses create-new, rejects symlinks, applies best-effort owner-only permissions, is absent after backend consumption or startup failure, and token/password text never appears in object strings.

- [ ] **Step 2: Run runtime-path RED**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopRuntimePathsTest" --tests "*DesktopSessionTokenFileTest"`

Expected: compilation fails because runtime paths and token-file lifecycle are absent.

- [ ] **Step 3: Implement paths, IDs, logging bootstrap, and token file**

Configure desktop SLF4J output before logger creation to `desktop/logs/desktop.log`. Acquire `desktop/instance.lock` before database, KeyStore, Agent, or Compose initialization; Task 20's child process independently acquires `agent/instance.lock`.

- [ ] **Step 4: Write failing child-process launch tests**

Require a dynamic loopback port and bundled `backend/babiq-server.jar`. The ProcessBuilder argument list includes `business-desktop` profile, loopback address, runtime dir, database, backend KeyStore, backend log redirection, memory/team roots, backend lock, and token-file path. Pass the persisted backend KeyStore password only through the child environment. Assert no secret is present in command arguments/loggable request text. Readiness requires the process to remain alive and an authenticated WebSocket to succeed; close terminates the child within 5 seconds, then forces termination.

- [ ] **Step 5: Run launcher RED**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessAgentProcessLauncherTest"`

Expected: FAIL because the launch request, process handle, and readiness probe are absent.

- [ ] **Step 6: Implement the bundled-Agent lifecycle and run GREEN**

Use argument-list ProcessBuilder, not a shell string. On any failure close the child, delete an unconsumed token file, release locks, and close opened stores. Reconnects reuse one desktopSessionId/token only while the same child remains alive; a restarted child gets a new session and counters.

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessAgentProcessLauncherTest"`

Expected: exact profile/path isolation, authenticated readiness, failure cleanup, and child termination pass.

- [ ] **Step 7: Write the failing composition-root test**

Assemble real SQLite/JCEKS adapters, demo gateway/actions, risk policy, Compose decision ports, one `ApplicationActionBus`, Agent transport/clients, controllers, and UI state. Assert user clicks and `ApplicationActionRequestHandler` receive the same bus instance; startup order is lock -> storage -> child -> authenticated connection -> identity -> catalog -> context -> UI; close order is controllers -> connection -> child -> stores -> lock.

- [ ] **Step 8: Implement composition and executable Main, then run task GREEN**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopCompositionRootTest" --tests "*BusinessAgentProcessLauncherTest"`

Expected: complete real-adapter wiring, one-bus invariant, startup rollback, and reverse shutdown pass.

- [ ] **Step 9: Commit**

```powershell
git add business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/runtime business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/logging business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/app business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/Main.kt business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/runtime business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/app
git commit -m "feat: 装配业务桌面与内置 Agent 生命周期"
```

### Task 35: Bundle the backend jar and automate packaged-distribution smoke testing

**Files:**
- Modify: `business-desktop/app/build.gradle.kts`
- Create: `business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeProbe.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke/PackagedSmokeProbeTest.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/build/DistributionBuildLogicTest.kt`
- Create: `business-desktop/scripts/smoke-packaged-distribution.ps1`

- [ ] **Step 1: Write failing build-logic and smoke-probe tests**

Require Gradle tasks `packageBusinessBackendJar`, `prepareBundledBusinessBackend`, and `smokePackagedDistribution`; `prepareAppResources` and all native package tasks depend on the bundled jar. The runtime image must retain `java.exe`. The probe reports profile, dynamic port, loopback address, isolated paths, token-file deletion, unauthorized-handshake rejection, authenticated connection, and child PID, without secrets.

- [ ] **Step 2: Run packaging RED**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*DistributionBuildLogicTest" --tests "*PackagedSmokeProbeTest"`

Expected: FAIL because distribution wiring and the smoke probe are absent.

- [ ] **Step 3: Implement Gradle bundling and smoke mode**

Build `backend/target/babiq-server-0.0.1-SNAPSHOT.jar` with `backend\mvnw.cmd -DskipTests package`, copy it as `common/backend/babiq-server.jar`, and configure `appResourcesRootDir`. `HUITAI_DESKTOP_SMOKE_REPORT` activates a one-run probe after the child is ready and the real composition root has completed authenticated WebSocket connection plus framework-level signed-out identity bind; it writes a redacted JSON report and requests orderly app shutdown without starting a model Turn or requiring real Huitai credentials.

- [ ] **Step 4: Implement the Windows package smoke harness**

The PowerShell script locates the generated MSI, performs an administrative extraction with `msiexec /a` into a temporary directory, starts `HuitaiBusinessDesktop.exe` hidden with a temporary home and smoke-report path, waits at most 120 seconds, validates every report field, waits for app exit, and proves the reported child PID exited. It also asserts the installer exists, the bundled jar exists, runtime data stays under the temporary `.huitai-agent-desktop`, the token file is gone, and no secret marker appears in logs. Cleanup uses only the verified temporary directory.

- [ ] **Step 5: Run unit GREEN and create the distributable**

Run:

```powershell
cd business-desktop
.\gradlew.bat :app:test --tests "*DistributionBuildLogicTest" --tests "*PackagedSmokeProbeTest"
.\gradlew.bat :app:packageDistributionForCurrentOS
```

Expected: build-logic/probe tests pass and MSI/EXE packages are produced with the backend jar.

- [ ] **Step 6: Run the automatic packaged smoke**

Run: `cd business-desktop; .\gradlew.bat :app:smokePackagedDistribution`

Expected: extraction, app launch, business-profile child startup, unauthorized rejection, authenticated connection, independent paths, token deletion, and child shutdown all pass.

- [ ] **Step 7: Commit**

```powershell
git add business-desktop/app/build.gradle.kts business-desktop/app/src/main/kotlin/com/wzx/huitai/desktop/smoke business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/smoke business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/build business-desktop/scripts/smoke-packaged-distribution.ps1
git commit -m "build: 打包并烟测内置业务 Agent"
```

### Task 36: Verify the complete desktop framework and Java/Kotlin bridge

**Files:**
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessDesktopFrameworkIT.kt`
- Create: `business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration/BusinessDesktopBackendCompatibilityIT.kt`
- Create: `backend/src/test/java/com/wzx/babiq/server/application/BusinessDesktopContractSeamIT.java`
- Modify: `backend/src/test/java/com/wzx/babiq/server/application/ApplicationBridgeEndToEndIT.java`

- [ ] **Step 1: Write the failing Kotlin framework IT**

Use real SQLite stores, real ActionBus, real Ktor bidirectional transport, real application request handler, Compose decision coordinator under test control, and `FakeHuitaiGateway`. A loopback server sends application/action/request and captures every progress/terminal notification. Cover read, reversible patch, high-risk approval, denial, duplicate execution, cancel race, response loss, OUTCOME_UNKNOWN, reconciliation, disconnect before execute, disconnect during execute, and exact audit correlation.

- [ ] **Step 2: Run Kotlin bridge RED, implement only missing assembly, and run GREEN**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopFrameworkIT"`

Expected after missing assembly is completed: the entire Kotlin-side protocol -> ActionBus -> fake remote -> persistence -> protocol path passes without UI shortcuts.

- [ ] **Step 3: Write the real backend-process compatibility IT**

Build and launch the actual bundled backend jar with a temporary business runtime and one-shot token. Through the production Ktor client prove unauthorized rejection, identity bind, catalog/context registration, scoped thread creation, reconnect republish, old-session rejection, path isolation, and clean process shutdown. This IT does not require a real model or Provider key.

- [ ] **Step 4: Run backend-process compatibility GREEN**

Run: `cd business-desktop; .\gradlew.bat :app:test --tests "*BusinessDesktopBackendCompatibilityIT"`

Expected: real Java process and Kotlin client agree on authentication, JSON-RPC, canonical fixtures, scope, reconnect, and shutdown.

- [ ] **Step 5: Strengthen the Java contract seam and rerun backend E2E**

`BusinessDesktopContractSeamIT` loads every Task 15 canonical JSON file and replays the same lifecycle rows asserted by `BusinessDesktopFrameworkIT`; `ApplicationBridgeEndToEndIT` additionally asserts applicationAction item ordering and toolCallId/executionId equality for success, rejection, cancellation race, and reconciliation. No test-only JSON-RPC method or production bypass is added.

Run: `cd backend; .\mvnw.cmd "-Dtest=BusinessDesktopContractSeamIT,ApplicationBridgeEndToEndIT,ApplicationProtocolReconnectIT" test`

Expected: the Java server half and Kotlin desktop half meet on the same fixtures, lifecycle vocabulary, scope, and correlation IDs.

- [ ] **Step 6: Run all new-framework suites together**

Run:

```powershell
cd business-desktop
.\gradlew.bat clean test --rerun-tasks
cd ..\backend
.\mvnw.cmd "-Dtest=BusinessDesktopContractSeamIT,ApplicationBridgeEndToEndIT,BusinessToolAllowlistIT,BusinessProfileParallelIsolationIT,ApplicationProtocolReconnectIT" test
```

Expected: every module and bridge suite passes with no ignored integration tests.

- [ ] **Step 7: Commit**

```powershell
git add business-desktop/app/src/test/kotlin/com/wzx/huitai/desktop/integration backend/src/test/java/com/wzx/babiq/server/application/BusinessDesktopContractSeamIT.java backend/src/test/java/com/wzx/babiq/server/application/ApplicationBridgeEndToEndIT.java
git commit -m "test: 验证业务桌面完整框架桥接"
```

### Task 37: Run full regressions, manual demonstration, and framework acceptance

**Files:**
- Create: `docs/superpowers/plans/huitai-business-desktop-agent-framework/manual-smoke.md`
- Create: `docs/superpowers/plans/huitai-business-desktop-agent-framework/framework-acceptance.md`
- Create: `docs/superpowers/plans/huitai-business-desktop-agent-framework/codex-handoff.md`

- [x] **Step 1: Run source and scope guards**

Run:

```powershell
rg -n "TODO|FIXME|TBD|placeholder|待实现" business-desktop backend/src/main/java/com/wzx/babiq/server/application
rg -n "customer|case|document|lawyer|客户|案件|文书|律师" business-desktop -g "*.kt" -g "*.sql"
git diff --check
```

Expected: no unfinished implementation marker; any forbidden-domain hit is limited to negative architecture tests or documentation assertions, not production models/actions/UI; diff check is clean.

- [x] **Step 2: Run the three full product regressions**

Run:

```powershell
cd backend
.\mvnw.cmd clean verify
cd ..\desktop
.\gradlew.bat test --rerun-tasks
cd ..\business-desktop
.\gradlew.bat clean test --rerun-tasks
.\gradlew.bat :app:packageDistributionForCurrentOS :app:smokePackagedDistribution
```

Expected: backend unit/IT, existing desktop, new business desktop, package creation, and packaged smoke all succeed from fresh outputs.

- [x] **Step 3: Write and execute the manual smoke checklist**

Document exact build/package path and record timestamped PASS/FAIL evidence for: first launch and single-instance rejection; three responsive window widths; seven-field generic form; user edit; Agent page context; unstructured-input chat using an available configured Provider; field sources/confidence; one-field and accept-all; stale revision; ordinary save preview; high-risk submit approval and denial; forced response-loss reconciliation; Agent disconnect/reconnect; auth/membership error presentation; audit rows; desktop close and child exit. Do not mark Provider-dependent rows passed without an actual model response.

- [x] **Step 4: Create the acceptance report from fresh evidence**

Map all seventeen design acceptance conditions to exact automated commands, test names, package-smoke evidence, and manual-smoke rows. Record package paths and remaining external prerequisites. The conclusion is `通过` only when every mandatory automated and manual row passed; otherwise it is `未通过` with the exact blocker and no claim that OA migration may start.

- [x] **Step 5: Create the handoff and commit verification evidence**

The handoff records architecture boundaries, runtime paths, commands, package locations, known constraints, and the hard rule that real OA migration begins only after `framework-acceptance.md` says `通过`.

```powershell
git add docs/superpowers/plans/huitai-business-desktop-agent-framework
git commit -m "docs: 完成业务桌面框架验收记录"
```
