package com.wzx.huitai.desktop.integration

import com.wzx.huitai.action.ActionExecutionContextValidator
import com.wzx.huitai.action.ApplicationActionBus
import com.wzx.huitai.action.ActionResolution
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.agent.application.ApplicationActionExecutionRuntime
import com.wzx.huitai.agent.application.ApplicationActionRequestHandler
import com.wzx.huitai.agent.application.ApplicationActionStatusClient
import com.wzx.huitai.agent.application.ApplicationReconnectRecovery
import com.wzx.huitai.agent.application.DirectApplicationActionExecutor
import com.wzx.huitai.agent.application.TrustedApplicationIdentity
import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.agent.client.KtorAgentTransport
import com.wzx.huitai.agent.protocol.ActionEnvelope
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.CommonApplicationFields
import com.wzx.huitai.agent.protocol.JsonRpcRequest
import com.wzx.huitai.desktop.decision.ActionDecisionPhase
import com.wzx.huitai.desktop.decision.ComposeActionDecisionCoordinator
import com.wzx.huitai.desktop.decision.ComposeApprovalPort
import com.wzx.huitai.desktop.decision.ComposeConfirmationPort
import com.wzx.huitai.demo.action.DemoActionCatalog
import com.wzx.huitai.demo.gateway.FakeGatewayMode
import com.wzx.huitai.demo.gateway.FakeHuitaiGateway
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import com.wzx.huitai.security.database.BusinessDesktopDatabase
import com.wzx.huitai.security.execution.ActionExecutionPolicies
import com.wzx.huitai.security.execution.ActionExecutionPolicyResolver
import com.wzx.huitai.security.execution.SQLiteActionExecutionStore
import com.wzx.huitai.security.risk.DefaultActionRiskPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class BusinessDesktopFrameworkIT {
    @Test
    fun `真实协议动作总线决策远端和SQLite闭环读取写入审批拒绝与重复执行`() = runBlocking {
        val fixture = FrameworkFixture.create()
        try {
            val read = fixture.request("read-1", "form.read_state")
            assertTrue(read.getValue("result").jsonObject["accepted"]?.jsonPrimitive?.content == "true")
            fixture.awaitNotification(ApplicationMethod.ACTION_COMPLETED, "read-1")
            fixture.assertCorrelation("read-1")
            assertEquals(
                listOf(
                    ActionExecutionState.VALIDATING,
                    ActionExecutionState.EXECUTING,
                    ActionExecutionState.SUCCEEDED,
                ),
                fixture.auditStates("read-1"),
            )

            val invalidRead = "read-invalid"
            val invalidReadResponse = fixture.request(invalidRead, "form.read_state", buildJsonObject {})
            assertTrue(
                invalidReadResponse.getValue("result").jsonObject["accepted"]?.jsonPrimitive?.content == "true",
            )
            val failed = fixture.awaitNotification(ApplicationMethod.ACTION_FAILED, invalidRead)
            assertEquals(
                "validation_failed",
                failed.getValue("params").jsonObject
                    .getValue("payload").jsonObject
                    .getValue("errorCode").jsonPrimitive.content,
            )
            assertEquals(
                listOf(
                    ActionExecutionState.VALIDATING,
                    ActionExecutionState.EXECUTING,
                    ActionExecutionState.FAILED,
                ),
                fixture.auditStates(invalidRead),
            )
            assertEquals(
                listOf(
                    ApplicationMethod.ACTION_ACCEPTED.wireName,
                    ApplicationMethod.ACTION_RUNNING.wireName,
                    ApplicationMethod.ACTION_FAILED.wireName,
                ),
                fixture.methodsFor(invalidRead),
            )
            assertFalse(ApplicationMethod.ACTION_REJECTED.wireName in fixture.methodsFor(invalidRead))

            val patchExecution = "patch-1"
            val patchRequest = async { fixture.request(patchExecution, "form.apply_patch", fixture.patchInput(patchExecution)) }
            fixture.awaitDecision(patchExecution, ActionDecisionPhase.CONFIRMATION)
            fixture.awaitNotification(ApplicationMethod.ACTION_PREVIEWED, patchExecution)
            assertTrue(fixture.decisions.accept(patchExecution))
            patchRequest.await()
            fixture.awaitNotification(ApplicationMethod.ACTION_COMPLETED, patchExecution)
            assertEquals("Agent写入", fixture.screen.state.value.values.name)
            fixture.assertCorrelation(patchExecution)

            val approved = "submit-approved"
            val approvedRequest = async { fixture.request(approved, "demo.submit") }
            fixture.awaitDecision(approved, ActionDecisionPhase.CONFIRMATION)
            assertTrue(fixture.decisions.accept(approved))
            fixture.awaitDecision(approved, ActionDecisionPhase.HIGH_RISK_APPROVAL)
            assertTrue(fixture.decisions.approve(approved))
            approvedRequest.await()
            fixture.awaitNotification(ApplicationMethod.ACTION_COMPLETED, approved)

            val denied = "submit-denied"
            val deniedRequest = async { fixture.request(denied, "demo.submit") }
            fixture.awaitDecision(denied, ActionDecisionPhase.CONFIRMATION)
            assertTrue(fixture.decisions.accept(denied))
            fixture.awaitDecision(denied, ActionDecisionPhase.HIGH_RISK_APPROVAL)
            assertTrue(fixture.decisions.deny(denied))
            deniedRequest.await()
            fixture.awaitNotification(ApplicationMethod.ACTION_CANCELED, denied)
            assertFalse(ApplicationMethod.ACTION_RUNNING.wireName in fixture.methodsFor(denied))

            val duplicate = "draft-duplicate"
            val first = async { fixture.request(duplicate, "demo.save_draft") }
            val second = async { fixture.request(duplicate, "demo.save_draft") }
            fixture.awaitDecision(duplicate, ActionDecisionPhase.CONFIRMATION)
            assertEquals(1, fixture.decisions.state.value.dialogs.count { it.executionId == duplicate })
            assertTrue(fixture.decisions.accept(duplicate))
            first.await()
            second.await()
            fixture.awaitNotification(ApplicationMethod.ACTION_COMPLETED, duplicate)
            assertEquals(1, fixture.gateway.draftRequestCount)
            assertEquals(1, fixture.gateway.draftWriteCount)
            assertEquals(1, fixture.methodsFor(duplicate).count { it == ApplicationMethod.ACTION_COMPLETED.wireName })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `取消竞争和执行前断线都只留下一个安全终态`() = runBlocking {
        val fixture = FrameworkFixture.create()
        try {
            val raced = "patch-cancel-race"
            val request = async { fixture.request(raced, "form.apply_patch", fixture.patchInput(raced)) }
            fixture.awaitDecision(raced, ActionDecisionPhase.CONFIRMATION)
            val cancel = async { fixture.cancel(raced) }
            fixture.decisions.accept(raced)
            request.await()
            runCatching { cancel.await() }
            val racedRecord = fixture.awaitTerminal(raced)
            assertTrue(racedRecord.state in setOf(
                ActionExecutionState.SUCCEEDED,
                ActionExecutionState.CANCELED,
                ActionExecutionState.OUTCOME_UNKNOWN,
            ))
            assertEquals(1, fixture.auditStates(raced).count { it == racedRecord.state })

            val disconnected = "patch-disconnect-before"
            val revisionBeforeDisconnect = fixture.screen.state.value.revision
            async { fixture.request(disconnected, "form.apply_patch", fixture.patchInput(disconnected)) }
            fixture.awaitDecision(disconnected, ActionDecisionPhase.CONFIRMATION)
            fixture.decisions.onAgentDisconnected()
            fixture.server.closeCurrentSession()
            assertEquals(ActionExecutionState.CANCELED, fixture.awaitTerminal(disconnected).state)
            assertEquals(revisionBeforeDisconnect, fixture.screen.state.value.revision)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `响应丢失和执行中断线经同一execution只查询远端并完成对账`() = runBlocking {
        val submitEntered = CountDownLatch(1)
        val releaseSubmit = CountDownLatch(1)
        val fixture = FrameworkFixture.create(
            submitMode = FakeGatewayMode.RESPONSE_LOST_AFTER_WRITE,
            beforeSubmit = {
                submitEntered.countDown()
                check(releaseSubmit.await(10, TimeUnit.SECONDS)) { "submit barrier timed out" }
            },
        )
        try {
            val executionId = "submit-response-lost"
            val first = async { fixture.request(executionId, "demo.submit") }
            fixture.awaitDecision(executionId, ActionDecisionPhase.CONFIRMATION)
            assertTrue(fixture.decisions.accept(executionId))
            fixture.awaitDecision(executionId, ActionDecisionPhase.HIGH_RISK_APPROVAL)
            assertTrue(fixture.decisions.approve(executionId))
            first.await()
            assertTrue(submitEntered.await(10, TimeUnit.SECONDS))
            fixture.awaitNotification(ApplicationMethod.ACTION_RUNNING, executionId)
            fixture.server.closeCurrentSession()
            releaseSubmit.countDown()
            assertEquals(ActionExecutionState.OUTCOME_UNKNOWN, fixture.awaitTerminal(executionId).state)
            assertEquals(1, fixture.gateway.submissionWriteCount)

            fixture.reconnect()
            fixture.recover()
            fixture.awaitNotification(ApplicationMethod.ACTION_OUTCOME_UNKNOWN, executionId, fixture.reconnectMarker)
            val retryResponse = fixture.request(executionId, "demo.submit")
            assertTrue("result" in retryResponse, retryResponse.toString())
            fixture.awaitNotification(ApplicationMethod.ACTION_COMPLETED, executionId, fixture.reconnectMarker)

            assertEquals(ActionExecutionState.SUCCEEDED, fixture.awaitTerminal(executionId).state)
            assertEquals(1, fixture.gateway.submissionRequestCount)
            assertEquals(1, fixture.gateway.submissionWriteCount)
            assertEquals(1, fixture.gateway.submissionQueryCount)
            assertTrue("reconciliation_attempt" in fixture.auditTypes(executionId))
            assertTrue("reconciliation_result" in fixture.auditTypes(executionId))
            fixture.assertCorrelation(executionId, fixture.reconnectMarker)
        } finally {
            releaseSubmit.countDown()
            fixture.close()
        }
    }
}

private class FrameworkFixture private constructor(
    val server: LoopbackAgentServer,
    val screen: DemoScreenModel,
    val gateway: FakeHuitaiGateway,
    val decisions: ComposeActionDecisionCoordinator,
    private val database: BusinessDesktopDatabase,
    private val store: SQLiteActionExecutionStore,
    private val runtime: ApplicationActionExecutionRuntime,
    private val scope: CoroutineScope,
) {
    private val sequence = AtomicLong(0)
    private val identity = TrustedApplicationIdentity(
        scope = ActionIdentityScope(
            desktopInstanceId = "desktop-framework-it",
            desktopSessionId = "desktop-session-framework-it",
            authSessionId = "auth-framework-it",
            identityEpoch = 1,
            userId = "user-framework-it",
            tenantId = "tenant-framework-it",
            platformId = "platform-framework-it",
        ),
        permissions = setOf("demo.write", "demo.submit"),
    )
    private var client = HttpClient(CIO) { install(ClientWebSockets) }
    private var transport = KtorAgentTransport(client, scope)
    private lateinit var connection: AgentConnection
    private lateinit var rpc: AgentJsonRpcClient
    private lateinit var statusClient: ApplicationActionStatusClient
    private lateinit var handler: ApplicationActionRequestHandler
    var reconnectMarker: Int = 0
        private set

    suspend fun start() {
        connect()
    }

    suspend fun request(
        executionId: String,
        actionId: String,
        input: JsonObject = buildJsonObject { put("executionId", executionId) },
    ): JsonObject = server.request(actionEnvelope(executionId, actionId, input))

    suspend fun cancel(executionId: String): JsonObject = server.request(
        actionEnvelope(executionId, "cancel", buildJsonObject { put("state", "cancel_requested") }),
        ApplicationMethod.ACTION_CANCEL,
    )

    fun patchInput(executionId: String): JsonObject {
        val state = screen.state.value
        val patch = FormPatch(
            pageId = DemoFormState.PAGE_ID,
            baseRevision = state.revision,
            changes = listOf(
                FieldChange(
                    fieldId = DemoFormState.FIELD_NAME,
                    previousValue = JsonPrimitive(state.values.name),
                    newValue = JsonPrimitive("Agent写入"),
                    reason = "框架集成验证",
                    confidence = 1.0,
                ),
            ),
        )
        return buildJsonObject {
            put("executionId", executionId)
            put("patch", ApplicationProtocol.JSON.encodeToJsonElement(FormPatch.serializer(), patch).jsonObject)
        }
    }

    suspend fun awaitDecision(executionId: String, phase: ActionDecisionPhase) = withTimeout(TIMEOUT) {
        decisions.state.first { state ->
            state.dialogs.any { it.executionId == executionId && it.phase == phase }
        }
    }

    suspend fun awaitNotification(method: ApplicationMethod, executionId: String, after: Int = 0): JsonObject =
        server.awaitMessage(after) { message ->
            message["method"]?.jsonPrimitive?.content == method.wireName &&
                message["params"]?.jsonObject?.get("executionId")?.jsonPrimitive?.content == executionId
        }

    fun methodsFor(executionId: String, after: Int = 0): List<String> = server.messages
        .drop(after)
        .filter { it["params"]?.jsonObject?.get("executionId")?.jsonPrimitive?.content == executionId }
        .mapNotNull { it["method"]?.jsonPrimitive?.content }

    suspend fun awaitTerminal(executionId: String): com.wzx.huitai.action.port.ActionExecutionRecord =
        withTimeout(TIMEOUT) {
            while (true) {
                val current = store.find(executionId, identity.scope)
                if (current?.isTerminal == true) return@withTimeout current
                delay(10)
            }
            error("unreachable")
        }

    suspend fun auditStates(executionId: String): List<ActionExecutionState> =
        store.events(executionId).map { it.toState }

    suspend fun auditTypes(executionId: String): List<String> =
        store.events(executionId).map { it.type }

    suspend fun assertCorrelation(executionId: String, after: Int = 0) {
        val correlated = server.messages.drop(after).filter {
            it["params"]?.jsonObject?.get("executionId")?.jsonPrimitive?.content == executionId
        }
        assertTrue(correlated.isNotEmpty())
        correlated.forEach { message ->
            val params = message.getValue("params").jsonObject
            assertEquals("thread-$executionId", params.getValue("threadId").jsonPrimitive.content)
            assertEquals("turn-$executionId", params.getValue("turnId").jsonPrimitive.content)
            assertEquals("tool-$executionId", params.getValue("toolCallId").jsonPrimitive.content)
            assertEquals(executionId, params.getValue("executionId").jsonPrimitive.content)
            assertEquals(identity.scope.desktopInstanceId, params.getValue("desktopInstanceId").jsonPrimitive.content)
            assertEquals(identity.scope.desktopSessionId, params.getValue("desktopSessionId").jsonPrimitive.content)
            assertEquals(identity.scope.authSessionId, params.getValue("authSessionId").jsonPrimitive.content)
            assertEquals(identity.scope.identityEpoch.toString(), params.getValue("identityEpoch").jsonPrimitive.content)
        }
        val durable = database.read { connection ->
            connection.prepareStatement(
                """
                SELECT execution_id, thread_id, turn_id, tool_call_id,
                       desktop_instance_id, desktop_session_id, auth_session_id, identity_epoch,
                       user_id, tenant_id, platform_id
                FROM bd_action_executions
                WHERE execution_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, executionId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    listOf(
                        rows.getString("execution_id"),
                        rows.getString("thread_id"),
                        rows.getString("turn_id"),
                        rows.getString("tool_call_id"),
                        rows.getString("desktop_instance_id"),
                        rows.getString("desktop_session_id"),
                        rows.getString("auth_session_id"),
                        rows.getLong("identity_epoch").toString(),
                        rows.getString("user_id"),
                        rows.getString("tenant_id"),
                        rows.getString("platform_id"),
                    )
                }
            }
        }
        assertEquals(
            listOf(
                executionId,
                "thread-$executionId",
                "turn-$executionId",
                "tool-$executionId",
                identity.scope.desktopInstanceId,
                identity.scope.desktopSessionId,
                identity.scope.authSessionId,
                identity.scope.identityEpoch.toString(),
                identity.scope.userId,
                identity.scope.tenantId,
                identity.scope.platformId,
            ),
            durable,
        )
        store.events(executionId).forEach { event ->
            val payload = event.redactedPayload
            assertEquals("thread-$executionId", payload.getValue("threadId").jsonPrimitive.content)
            assertEquals("turn-$executionId", payload.getValue("turnId").jsonPrimitive.content)
            assertEquals("tool-$executionId", payload.getValue("toolCallId").jsonPrimitive.content)
            assertEquals(executionId, payload.getValue("executionId").jsonPrimitive.content)
            assertEquals(identity.scope.desktopInstanceId, payload.getValue("desktopInstanceId").jsonPrimitive.content)
            assertEquals(identity.scope.desktopSessionId, payload.getValue("desktopSessionId").jsonPrimitive.content)
            assertEquals(identity.scope.authSessionId, payload.getValue("authSessionId").jsonPrimitive.content)
            assertEquals(identity.scope.identityEpoch, payload.getValue("identityEpoch").jsonPrimitive.long)
        }
    }

    suspend fun reconnect() {
        handler.closeConnection()
        rpc.close()
        connection.close()
        transport.close()
        client.close()
        server.awaitDisconnected()
        reconnectMarker = server.messages.size
        client = HttpClient(CIO) { install(ClientWebSockets) }
        transport = KtorAgentTransport(client, scope)
        connect()
        decisions.onAgentConnected()
    }

    suspend fun recover() {
        ApplicationReconnectRecovery(runtime).recover(identity.scope, statusClient)
    }

    suspend fun close() {
        decisions.shutdown()
        runCatching { handler.closeConnection() }
        runCatching { rpc.close() }
        runCatching { connection.close() }
        runCatching { transport.close() }
        client.close()
        runtime.close()
        scope.cancel()
        server.close()
        database.close()
    }

    private suspend fun connect() {
        val desktopIdentity = DesktopSessionIdentity(
            desktopInstanceId = identity.scope.desktopInstanceId,
            desktopSessionId = identity.scope.desktopSessionId,
            desktopSessionToken = "framework-it-token",
            localOrigin = "http://127.0.0.1",
        )
        connection = transport.connect(AgentConnectRequest(server.url, desktopIdentity))
        withTimeout(TIMEOUT) { connection.state.first { it == AgentConnectionState.Connected } }
        server.awaitConnected()
        rpc = AgentJsonRpcClient(connection, scope, requestTimeoutMillis = TIMEOUT)
        statusClient = ApplicationActionStatusClient(rpc, sequence::incrementAndGet, Instant::now)
        handler = ApplicationActionRequestHandler(
            rpc = rpc,
            runtime = runtime,
            trustedIdentity = { identity },
            nextSequence = sequence::incrementAndGet,
            now = Instant::now,
            scope = scope,
            statusClient = statusClient,
        )
    }

    private fun actionEnvelope(executionId: String, actionId: String, input: JsonObject) = ActionEnvelope(
        common = CommonApplicationFields(
            protocolVersion = ApplicationProtocol.PROTOCOL_VERSION,
            desktopInstanceId = identity.scope.desktopInstanceId,
            desktopSessionId = identity.scope.desktopSessionId,
            authSessionId = identity.scope.authSessionId,
            identityEpoch = identity.scope.identityEpoch,
            sequence = sequence.incrementAndGet(),
            generatedAt = Instant.now().toString(),
            userId = identity.scope.userId,
            tenantId = identity.scope.tenantId,
            platformId = identity.scope.platformId,
        ),
        threadId = "thread-$executionId",
        turnId = "turn-$executionId",
        toolCallId = "tool-$executionId",
        executionId = executionId,
        payload = buildJsonObject {
            put("actionId", actionId)
            put("actionVersion", 1)
            put("input", input)
            put("pageId", DemoFormState.PAGE_ID)
            put("contextRevision", screen.state.value.revision)
        },
    )

    companion object {
        suspend fun create(
            submitMode: FakeGatewayMode = FakeGatewayMode.CONFIRMED,
            beforeSubmit: () -> Unit = {},
        ): FrameworkFixture {
            val root = Files.createTempDirectory("business-desktop-framework-it")
            val database = BusinessDesktopDatabase(root.resolve("framework.db"))
            val screen = DemoScreenModel()
            val gateway = FakeHuitaiGateway(submitMode = submitMode, beforeSubmit = beforeSubmit)
            val catalog = DemoActionCatalog(screen, gateway)
            val registry = catalog.createRegistry()
            val store = SQLiteActionExecutionStore(
                database,
                policyResolver = ActionExecutionPolicyResolver { record ->
                    when (val resolution = registry.resolve(record.command.actionId, record.command.actionVersion)) {
                        is ActionResolution.Found -> ActionExecutionPolicies(
                            resolution.action.descriptor.replayPolicy,
                            resolution.action.descriptor.reconciliationPolicy,
                        )
                        is ActionResolution.NotFound -> ActionExecutionPolicies(
                            ActionReplayPolicy.NEVER,
                            ReconciliationPolicy.MANUAL,
                        )
                    }
                },
            )
            val decisions = ComposeActionDecisionCoordinator()
            val bus = ApplicationActionBus(
                registry = registry,
                riskPolicy = DefaultActionRiskPolicy(),
                confirmationPort = ComposeConfirmationPort(decisions),
                approvalPort = ComposeApprovalPort(decisions),
                executionStore = store,
                clock = ActionClock(Instant::now),
                contextValidator = ActionExecutionContextValidator(),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val runtime = ApplicationActionExecutionRuntime(
                DirectApplicationActionExecutor(bus),
                store,
                store,
                scope,
                statusPollMillis = 10,
            )
            return FrameworkFixture(
                server = LoopbackAgentServer.start(),
                screen = screen,
                gateway = gateway,
                decisions = decisions,
                database = database,
                store = store,
                runtime = runtime,
                scope = scope,
            ).also { it.start() }
        }
    }
}

private class LoopbackAgentServer private constructor(
    private val server: EmbeddedServer<*, *>,
    val url: String,
) {
    val messages = CopyOnWriteArrayList<JsonObject>()
    private val session = AtomicReference<DefaultWebSocketServerSession?>(null)
    private val requestIds = AtomicLong(10_000)

    suspend fun request(
        envelope: ActionEnvelope,
        method: ApplicationMethod = ApplicationMethod.ACTION_REQUEST,
    ): JsonObject {
        val id = requestIds.incrementAndGet()
        val marker = messages.size
        val text = ApplicationProtocol.JSON.encodeToString(
            JsonRpcRequest.serializer(),
            JsonRpcRequest(id = id, method = method.wireName, params = envelope),
        )
        requireNotNull(session.get()).send(Frame.Text(text))
        return awaitMessage(marker) { message ->
            message["id"]?.jsonPrimitive?.content == id.toString() &&
                ("result" in message || "error" in message)
        }
    }

    suspend fun awaitMessage(after: Int = 0, predicate: (JsonObject) -> Boolean): JsonObject = withTimeout(TIMEOUT) {
        while (true) {
            messages.drop(after).firstOrNull(predicate)?.let { return@withTimeout it }
            delay(10)
        }
        error("unreachable")
    }

    suspend fun awaitConnected() = withTimeout(TIMEOUT) {
        while (session.get() == null) delay(10)
    }

    suspend fun awaitDisconnected() = withTimeout(TIMEOUT) {
        while (session.get() != null) delay(10)
    }

    suspend fun closeCurrentSession() {
        session.get()?.close(CloseReason(CloseReason.Codes.NORMAL, "framework-it disconnect"))
        awaitDisconnected()
    }

    fun close() {
        server.stop(100, 1_000)
    }

    companion object {
        suspend fun start(): LoopbackAgentServer {
            val holder = AtomicReference<LoopbackAgentServer?>()
            val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
                install(ServerWebSockets)
                routing {
                    webSocket("/ws/agent") {
                        val fixture = requireNotNull(holder.get())
                        fixture.session.set(this)
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    fixture.messages += ApplicationProtocol.JSON.parseToJsonElement(frame.readText()).jsonObject
                                }
                            }
                        } finally {
                            fixture.session.compareAndSet(this, null)
                        }
                    }
                }
            }.start(wait = false)
            val port = server.engine.resolvedConnectors().single().port
            return LoopbackAgentServer(server, "ws://127.0.0.1:$port/ws/agent").also(holder::set)
        }
    }
}

private const val TIMEOUT = 10_000L
