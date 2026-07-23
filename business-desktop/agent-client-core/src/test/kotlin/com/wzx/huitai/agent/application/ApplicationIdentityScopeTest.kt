package com.wzx.huitai.agent.application

import com.wzx.huitai.action.ActionBusResult
import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ExecutionBinding
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ReconciliationClaimRequest
import com.wzx.huitai.action.port.ReconciliationClaimResult
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationReleaseRequest
import com.wzx.huitai.action.port.ReconciliationReleaseResult
import com.wzx.huitai.action.port.ReconciliationRenewRequest
import com.wzx.huitai.action.port.ReconciliationRenewResult
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import com.wzx.huitai.action.port.ScopedActionExecutionQuery
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.protocol.ActionEnvelope
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.CommonApplicationFields
import com.wzx.huitai.agent.protocol.JsonRpcNotification
import com.wzx.huitai.agent.protocol.JsonRpcRequest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationIdentityScopeTest {
    @Test
    fun `dispatch rejects every common and optional identity mismatch before execution or lookup`() = runTest {
        val commonVariants = listOf<(CommonApplicationFields) -> CommonApplicationFields>(
            { it.copy(desktopInstanceId = "other") },
            { it.copy(desktopSessionId = "other") },
            { it.copy(authSessionId = "other") },
            { it.copy(identityEpoch = it.identityEpoch + 1) },
            { it.copy(userId = "other") },
            { it.copy(tenantId = "other") },
            { it.copy(platformId = "other") },
        )
        val optionalVariants = listOf(
            "desktopInstanceId" to JsonPrimitive("other"),
            "desktopSessionId" to JsonPrimitive("other"),
            "authSessionId" to JsonPrimitive("other"),
            "identityEpoch" to JsonPrimitive(TRUSTED_SCOPE.identityEpoch + 1),
            "userId" to JsonPrimitive("other"),
            "tenantId" to JsonPrimitive("other"),
            "platformId" to JsonPrimitive("other"),
        )
        val fixture = Fixture(backgroundScope)

        commonVariants.forEachIndexed { index, mutate ->
            fixture.request("common-$index", ApplicationMethod.ACTION_REQUEST, envelope(index.toLong() + 1).copy(common = mutate(common(index.toLong() + 1))))
        }
        optionalVariants.forEachIndexed { index, (name, value) ->
            val base = envelope(index.toLong() + 20)
            fixture.request("optional-$index", ApplicationMethod.ACTION_REQUEST, base.copy(payload = JsonObject(base.payload + (name to value))))
        }
        runCurrent()

        (commonVariants.indices.map { "common-$it" } + optionalVariants.indices.map { "optional-$it" }).forEach { id ->
            assertProtocolError(fixture.response(id))
        }
        assertEquals(0, fixture.executor.calls)
        assertEquals(0, fixture.query.findCalls)
        fixture.close()
    }

    @Test
    fun `status and result reject old or mismatched identity without reading persisted records`() = runTest {
        val oldScope = TRUSTED_SCOPE
        val running = record(command(oldScope).copy(executionId = "running-execution"), ActionExecutionState.EXECUTING)
        val terminal = record(command(oldScope), ActionExecutionState.SUCCEEDED)
        val fixture = Fixture(backgroundScope, initialRecords = listOf(running, terminal))
        fixture.currentIdentity = TrustedApplicationIdentity(oldScope.copy(tenantId = "new-tenant", identityEpoch = 9), emptySet())
        val scopes = listOf(
            oldScope.copy(desktopInstanceId = "other"),
            oldScope.copy(desktopSessionId = "other"),
            oldScope.copy(authSessionId = "other"),
            oldScope.copy(identityEpoch = oldScope.identityEpoch + 1),
            oldScope.copy(userId = "other"),
            oldScope.copy(tenantId = "other"),
            oldScope.copy(platformId = "other"),
        )

        fixture.request("missing-status", ApplicationMethod.ACTION_STATUS, envelope(1, oldScope).copy(executionId = "missing"))
        fixture.request("missing-result", ApplicationMethod.ACTION_RESULT_GET, envelope(2, oldScope).copy(executionId = "missing"))
        scopes.forEachIndexed { index, scope ->
            fixture.request("mismatch-status-$index", ApplicationMethod.ACTION_STATUS, envelope(index.toLong() + 3, scope))
            fixture.request("mismatch-result-$index", ApplicationMethod.ACTION_RESULT_GET, envelope(index.toLong() + 20, scope))
        }
        fixture.request("old-status", ApplicationMethod.ACTION_STATUS, envelope(40, oldScope).copy(executionId = "running-execution"))
        fixture.request("old-result", ApplicationMethod.ACTION_RESULT_GET, envelope(41, oldScope))
        runCurrent()

        assertProtocolError(fixture.response("missing-status"))
        assertProtocolError(fixture.response("missing-result"))
        scopes.indices.forEach {
            assertProtocolError(fixture.response("mismatch-status-$it"))
            assertProtocolError(fixture.response("mismatch-result-$it"))
        }
        assertProtocolError(fixture.response("old-status"))
        assertProtocolError(fixture.response("old-result"))
        assertEquals(0, fixture.query.findCalls)
        fixture.close()
    }

    @Test
    fun `cancel notification cannot affect old execution after current identity changes`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.executor.block = true
        fixture.request("start", ApplicationMethod.ACTION_REQUEST, envelope(1))
        fixture.executor.entered.await()
        fixture.currentIdentity = TrustedApplicationIdentity(
            TRUSTED_SCOPE.copy(tenantId = "new-tenant", identityEpoch = 9),
            emptySet(),
        )

        fixture.notification(
            ApplicationMethod.ACTION_CANCEL,
            envelope(2, TRUSTED_SCOPE.copy(desktopSessionId = "other-session")),
        )
        runCurrent()
        assertFalse(fixture.executor.cancelled.isCompleted)

        fixture.notification(ApplicationMethod.ACTION_CANCEL, envelope(3, TRUSTED_SCOPE))
        runCurrent()

        assertEquals(1, fixture.executor.calls)
        assertFalse(fixture.executor.cancelled.isCompleted)
        assertEquals(listOf("start".testJsonRpcId().toString()), fixture.connection.sent.map { it.json() }.mapNotNull { it["id"]?.jsonPrimitive?.content })
        fixture.close()
    }

    private class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        initialRecords: List<ActionExecutionRecord> = emptyList(),
    ) {
        val connection = RecordingConnection()
        val rpc = AgentJsonRpcClient(connection, scope, requestTimeoutMillis = 1_000)
        val query = RecordingScopedQuery(initialRecords)
        private val store = EmptyStore()
        val executor = RecordingExecutor()
        private val sequence = AtomicLong(100)
        var currentIdentity = TrustedApplicationIdentity(TRUSTED_SCOPE, setOf("case:read"))
        val handler = ApplicationActionRequestHandler(
            rpc = rpc,
            executor = executor,
            executionStore = store,
            scopedQuery = query,
            trustedIdentity = { currentIdentity },
            nextSequence = sequence::incrementAndGet,
            now = { NOW },
            scope = scope,
            statusPollMillis = 1,
        )

        suspend fun request(id: String, method: ApplicationMethod, value: ActionEnvelope) {
            connection.serverSend(ApplicationProtocol.JSON.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id.testJsonRpcId(), method = method.wireName, params = value)))
        }

        suspend fun notification(method: ApplicationMethod, value: ActionEnvelope) {
            connection.serverSend(ApplicationProtocol.JSON.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method.wireName, params = value)))
        }

        fun response(id: String): JsonObject = connection.sent.map { it.json() }.single { it["id"]?.jsonPrimitive?.content == id.testJsonRpcId().toString() }
        suspend fun close() { handler.close(); rpc.close() }
    }

    private class RecordingScopedQuery(records: List<ActionExecutionRecord>) : ScopedActionExecutionQuery {
        private val records = records.associateBy { it.command.executionId }
        var findCalls = 0
        override suspend fun find(executionId: String, identityScope: ActionIdentityScope): ActionExecutionRecord? {
            findCalls += 1
            return records[executionId]?.takeIf { it.command.identityScope == identityScope }
        }
        override suspend fun listNonTerminal(identityScope: ActionIdentityScope): List<ActionExecutionRecord> = emptyList()
    }

    private class RecordingExecutor : ApplicationActionExecutor {
        var calls = 0
        var block = false
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult {
            calls += 1
            entered.complete(Unit)
            if (block) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            return ActionBusResult.InProgress(command.executionId, ActionExecutionState.EXECUTING)
        }
    }

    private class RecordingConnection : AgentConnection {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        override val connectionId = "scope-connection"
        override val incoming: ReceiveChannel<String> = inbound
        override val state: StateFlow<AgentConnectionState> = MutableStateFlow(AgentConnectionState.Connected)
        override val hasConnected = true
        val sent = mutableListOf<String>()
        override suspend fun send(text: String) { sent += text }
        suspend fun serverSend(text: String) { inbound.send(text) }
        override suspend fun close() { inbound.close() }
    }

    private class EmptyStore : ActionExecutionStore {
        override suspend fun find(executionId: String): ActionExecutionRecord? = null
        override suspend fun compareAndCreate(record: ActionExecutionRecord, audit: ActionAuditDraft): ExecutionCreateResult = error("unused")
        override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult = error("unused")
        override suspend fun updateReconciliation(update: ReconciliationExecutionUpdate): ReconciliationUpdateResult = error("unused")
        override suspend fun claimReconciliation(request: ReconciliationClaimRequest): ReconciliationClaimResult = error("unused")
        override suspend fun renewReconciliation(request: ReconciliationRenewRequest): ReconciliationRenewResult = error("unused")
        override suspend fun releaseReconciliation(request: ReconciliationReleaseRequest): ReconciliationReleaseResult = error("unused")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-16T10:00:00Z")
        val TRUSTED_SCOPE = ActionIdentityScope("desktop-1", "desktop-session-1", "auth-session-1", 8, "user-1", "tenant-1", "platform-1")

        fun common(sequence: Long, scope: ActionIdentityScope = TRUSTED_SCOPE) = CommonApplicationFields(
            ApplicationProtocol.PROTOCOL_VERSION,
            scope.desktopInstanceId,
            scope.desktopSessionId,
            scope.authSessionId,
            scope.identityEpoch,
            sequence,
            NOW.toString(),
            scope.userId,
            scope.tenantId,
            scope.platformId,
        )

        fun envelope(sequence: Long, scope: ActionIdentityScope = TRUSTED_SCOPE) = ActionEnvelope(
            common(sequence, scope),
            "thread-1",
            "turn-1",
            "tool-1",
            "execution-1",
            buildJsonObject {
                put("actionId", "framework.demo")
                put("actionVersion", 1)
                put("input", buildJsonObject { put("value", "secret") })
                put("pageId", "page-1")
                put("contextRevision", 1)
            },
        )

        fun command(scope: ActionIdentityScope) = ActionCommand(
            "execution-1", "framework.demo", 1, buildJsonObject { put("value", "secret") },
            ActionOrigin.AGENT, scope, "page-1", 1,
            com.wzx.huitai.action.model.ActionCorrelation("thread-1", "turn-1", "tool-1"),
        )

        fun record(command: ActionCommand, state: ActionExecutionState): ActionExecutionRecord {
            val result = if (state == ActionExecutionState.SUCCEEDED) {
                ActionResult.Success<JsonElement>(
                    command.executionId,
                    buildJsonObject { put("value", "private") },
                    buildJsonObject { put("value", "public") },
                )
            } else null
            return ActionExecutionRecord(
                command,
                ExecutionBinding(command.actionId, command.actionVersion, "fingerprint", command.origin, command.identityScope, command.pageId, command.contextRevision, command.correlation),
                ActionRiskLevel.READ_ONLY,
                state,
                result,
                NOW,
                startedAt = if (state == ActionExecutionState.EXECUTING || result != null) NOW else null,
                completedAt = if (result != null) NOW else null,
                updatedAt = NOW,
                recordVersion = 1,
            )
        }

        fun assertProtocolError(response: JsonObject) = assertEquals(
            "PROTOCOL_ERROR",
            response.getValue("error").jsonObject.getValue("message").jsonPrimitive.content,
        )
        fun JsonObject.result() = getValue("result").jsonObject
        fun String.json() = ApplicationProtocol.JSON.parseToJsonElement(this).jsonObject
    }
}

private fun String.testJsonRpcId(): Long = hashCode().toLong() and 0x7fff_ffffL
