package com.wzx.huitai.agent.application

import com.wzx.huitai.action.ActionBusResult
import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
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
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.protocol.ActionEnvelope
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.CatalogEnvelope
import com.wzx.huitai.agent.protocol.CommonApplicationFields
import com.wzx.huitai.agent.protocol.ContextEnvelope
import com.wzx.huitai.agent.protocol.IdentityEnvelope
import com.wzx.huitai.agent.protocol.JsonRpcRequest
import com.wzx.huitai.agent.protocol.JsonRpcSuccessResponse
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationReconnectRecoveryTest {
    @Test
    fun `unavailable disconnect query conservatively keeps executing job alive`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1, cleanupTimeoutMillis = 10)
        val first = ConnectionFixture(backgroundScope, runtime, "unavailable-old")
        first.request("start")
        executor.entered.await()
        store.failFindCount = 1

        first.handler.closeConnection()
        first.rpc.close()
        assertFalse(executor.cancelled.isCompleted)
        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        val second = ConnectionFixture(backgroundScope, runtime, "unavailable-new")
        ApplicationReconnectRecovery(runtime).recover(SCOPE, second.status)
        advanceTimeBy(20)
        runCurrent()

        assertEquals(ApplicationMethod.ACTION_COMPLETED.wireName, second.connection.methods().last())
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `terminal resolver retries unavailable final query and publishes terminal once without reconnect`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1, cleanupTimeoutMillis = 10)
        val fixture = ConnectionFixture(backgroundScope, runtime, "resolver")
        fixture.request("start")
        executor.entered.await()
        store.failFindAfterTerminalCount = 3

        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, fixture.connection.methods().count { it == ApplicationMethod.ACTION_COMPLETED.wireName })
        fixture.closeConnection()
        runtime.close()
    }

    @Test
    fun `terminal publication retries after the first send failure on the same connection`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val fixture = ConnectionFixture(backgroundScope, runtime, "retry-terminal")
        fixture.connection.failMethodOnce = ApplicationMethod.ACTION_COMPLETED.wireName
        fixture.request("start")
        executor.entered.await()
        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        advanceTimeBy(20)
        runCurrent()

        assertEquals(ApplicationMethod.ACTION_COMPLETED.wireName, fixture.connection.methods().last())
        assertEquals(1, fixture.connection.methods().count { it == ApplicationMethod.ACTION_COMPLETED.wireName })
        fixture.closeConnection()
        runtime.close()
    }

    @Test
    fun `detached client cannot rebind recovery`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope)
        val fixture = ConnectionFixture(backgroundScope, runtime, "detached")
        fixture.handler.closeConnection()

        kotlin.test.assertFailsWith<IllegalStateException> {
            ApplicationReconnectRecovery(runtime).recover(SCOPE, fixture.status)
        }
        fixture.rpc.close()
        runtime.close()
    }

    @Test
    fun `many progress updates cannot displace the retained terminal`() = runTest {
        val connection = RecordingConnection("slot")
        val rpc = AgentJsonRpcClient(connection, backgroundScope, requestTimeoutMillis = 1_000)
        val status = ApplicationActionStatusClient(rpc, AtomicLong(1)::incrementAndGet, { NOW })
        val slot = RuntimePublicationSlot(backgroundScope) { status }
        val executing = record(command(), ActionExecutionState.EXECUTING)
        repeat(100) { slot.offerProgress(PublicationIntent.Record(publication(), executing)) }
        val terminal = record(command(), ActionExecutionState.SUCCEEDED)

        slot.offerTerminal(PublicationIntent.Record(publication(), terminal))
        runCurrent()

        assertEquals(ApplicationMethod.ACTION_COMPLETED.wireName, connection.methods().last())
        assertEquals(1, connection.methods().count { it == ApplicationMethod.ACTION_COMPLETED.wireName })
        slot.close()
        rpc.close()
    }

    @Test
    fun `connection loss during blocked accepted cannot start execution afterwards`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val fixture = ConnectionFixture(backgroundScope, runtime, "blocked-accepted")
        fixture.connection.blockMethod = ApplicationMethod.ACTION_ACCEPTED.wireName
        fixture.request("start")
        fixture.connection.blockEntered.await()

        fixture.handler.closeConnection()
        fixture.rpc.close()
        fixture.connection.blockRelease.complete(Unit)
        runCurrent()

        assertEquals(0, executor.calls)
        runtime.close()
    }

    @Test
    fun `terminal completed without a sink is published after reconnect recovery`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "terminal-old")
        first.request("start")
        executor.entered.await()
        runCurrent()
        first.handler.closeConnection()
        first.rpc.close()
        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        runCurrent()

        val second = ConnectionFixture(backgroundScope, runtime, "terminal-new")
        ApplicationReconnectRecovery(runtime).recover(SCOPE, second.status)
        runCurrent()

        assertEquals(listOf(ApplicationMethod.ACTION_COMPLETED.wireName), second.connection.methods())
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `blocked running publication completes before terminal and never after it`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "lane-old")
        first.request("start")
        executor.entered.await()
        runCurrent()
        first.handler.closeConnection()
        first.rpc.close()
        val second = ConnectionFixture(backgroundScope, runtime, "lane-new")
        second.connection.blockMethod = ApplicationMethod.ACTION_RUNNING.wireName

        val recovery = backgroundScope.async { ApplicationReconnectRecovery(runtime).recover(SCOPE, second.status) }
        second.connection.blockEntered.await()
        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        runCurrent()
        second.connection.blockRelease.complete(Unit)
        recovery.await()
        runCurrent()

        assertEquals(
            listOf(ApplicationMethod.ACTION_RUNNING.wireName, ApplicationMethod.ACTION_COMPLETED.wireName),
            second.connection.methods(),
        )
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `duplicate request waits until the first accepted send succeeds before acknowledging`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "first")
        first.connection.blockMethod = ApplicationMethod.ACTION_ACCEPTED.wireName
        first.request("request-a")
        first.connection.blockEntered.await()
        val second = ConnectionFixture(backgroundScope, runtime, "second")
        second.request("request-b")
        runCurrent()

        assertFalse(second.connection.hasResponse("request-b"))
        first.connection.blockRelease.complete(Unit)
        executor.entered.await()
        runCurrent()

        assertTrue(second.connection.hasResponse("request-b"))
        assertEquals(1, executor.calls)
        executor.release.complete(Unit)
        first.closeConnection()
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `duplicate request never acknowledges a failed first accepted send without dispatch`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "first")
        first.connection.blockMethod = ApplicationMethod.ACTION_ACCEPTED.wireName
        first.connection.failMethod = ApplicationMethod.ACTION_ACCEPTED.wireName
        first.request("request-a")
        first.connection.blockEntered.await()
        val second = ConnectionFixture(backgroundScope, runtime, "second")
        second.request("request-b")
        runCurrent()

        assertFalse(second.connection.hasResponse("request-b"))
        first.connection.blockRelease.complete(Unit)
        runCurrent()

        val acknowledged = second.connection.sent.map { it.json() }.any {
            it["id"]?.jsonPrimitive?.content == "request-b" && "result" in it
        }
        assertFalse(acknowledged && executor.calls == 0)
        if (executor.calls == 1) executor.release.complete(Unit)
        first.closeConnection()
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `recovery does not publish stale running after terminal is already published`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "stale-first")
        first.request("start")
        executor.entered.await()
        runCurrent()
        first.handler.closeConnection()
        first.rpc.close()

        val second = ConnectionFixture(backgroundScope, runtime, "stale-second")
        store.blockListReturn = CompletableDeferred<Unit>()
        val recovery = backgroundScope.async { ApplicationReconnectRecovery(runtime).recover(SCOPE, second.status) }
        store.listCaptured.await()
        val terminal = record(requireNotNull(store.current).command, ActionExecutionState.SUCCEEDED)
        store.current = terminal
        second.status.publish(publication(), terminal)
        store.blockListReturn?.complete(Unit)
        recovery.await()
        runCurrent()

        assertEquals(listOf(ApplicationMethod.ACTION_COMPLETED.wireName), second.connection.methods())
        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `executing action survives connection close and rebinds running and terminal to new sink`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "connection-1")
        first.request("start")
        executor.entered.await()
        runCurrent()

        first.handler.closeConnection()
        first.rpc.close()
        assertFalse(executor.cancelled.isCompleted)

        val second = ConnectionFixture(backgroundScope, runtime, "connection-2")
        ApplicationReconnectRecovery(runtime).recover(SCOPE, second.status)
        runCurrent()
        assertEquals(listOf(ApplicationMethod.ACTION_RUNNING.wireName), second.connection.methods())

        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        runCurrent()
        assertEquals(
            listOf(ApplicationMethod.ACTION_RUNNING.wireName, ApplicationMethod.ACTION_COMPLETED.wireName),
            second.connection.methods(),
        )
        assertEquals(1, executor.calls)
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `connection loss cancels unconfirmed preview and recovery never replays preview or approval`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store, previewOnly = true)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "preview-1")
        first.request("preview")
        executor.entered.await()
        runCurrent()

        first.handler.closeConnection()
        first.rpc.close()
        withTimeout(1_000) { executor.cancelled.await() }
        assertEquals(ActionExecutionState.CANCELED, store.current?.state)

        val second = ConnectionFixture(backgroundScope, runtime, "preview-2")
        ApplicationReconnectRecovery(runtime).recover(SCOPE, second.status)
        runCurrent()
        assertEquals(emptyList(), second.connection.methods())
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `recovery never attaches an execution from a prior desktop session`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "session-a")
        first.request("start")
        executor.entered.await()
        runCurrent()
        first.handler.closeConnection()
        first.rpc.close()

        val priorSessionScope = SCOPE.copy(desktopSessionId = "new-desktop-session")
        val second = ConnectionFixture(backgroundScope, runtime, "session-b", priorSessionScope)
        ApplicationReconnectRecovery(runtime).recover(priorSessionScope, second.status)
        runCurrent()
        assertEquals(emptyList(), second.connection.methods())
        assertFalse(executor.cancelled.isCompleted)
        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        runCurrent()
        assertEquals(emptyList(), second.connection.methods())
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `authenticated registration invokes recovery only after context publication`() = runTest {
        val connection = RecordingConnection("registration")
        val rpc = AgentJsonRpcClient(connection, backgroundScope, requestTimeoutMillis = 1_000)
        val tracker = ApplicationSequenceTracker(DESKTOP_SESSION_ID)
        val catalogClient = ApplicationCatalogClient(rpc, tracker)
        val contextClient = ApplicationContextClient(rpc, tracker)
        val identityClient = ApplicationIdentityClient(rpc, tracker, catalogClient, contextClient)
        val order = mutableListOf<String>()
        connection.onMethod = { order += it }

        identityClient.registerAuthenticatedConnection(
            identity = identity(),
            catalog = catalog(),
            context = context(),
            afterRegistration = { order += "recover" },
        )

        assertEquals(
            listOf(
                ApplicationMethod.IDENTITY_BIND.wireName,
                ApplicationMethod.CATALOG_REGISTER.wireName,
                ApplicationMethod.CONTEXT_PUBLISH.wireName,
                "recover",
            ),
            order,
        )
        rpc.close()
    }

    @Test
    fun `conflicting request cannot replace the sink of a running execution`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "owner")
        first.request("start")
        executor.entered.await()
        runCurrent()
        val second = ConnectionFixture(backgroundScope, runtime, "conflict")

        second.request("conflict", payload = buildJsonObject {
            put("actionId", "other.action")
            put("actionVersion", 1)
            put("input", buildJsonObject { })
            put("pageId", "page-1")
            put("contextRevision", 1)
        })
        runCurrent()
        assertEquals("PROTOCOL_ERROR", second.connection.response("conflict").getValue("error").jsonObject.getValue("message").jsonPrimitive.content)

        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        runCurrent()
        assertTrue(ApplicationMethod.ACTION_COMPLETED.wireName in first.connection.methods())
        assertFalse(ApplicationMethod.ACTION_COMPLETED.wireName in second.connection.methods())
        first.closeConnection()
        second.closeConnection()
        runtime.close()
    }

    @Test
    fun `accepted send failure cannot replace the sink of another running execution`() = runTest {
        val store = RuntimeStore()
        val executor = RuntimeExecutor(store)
        val runtime = ApplicationActionExecutionRuntime(executor, store, store, backgroundScope, statusPollMillis = 1)
        val first = ConnectionFixture(backgroundScope, runtime, "owner")
        first.request("start")
        executor.entered.await()
        runCurrent()
        val second = ConnectionFixture(backgroundScope, runtime, "failed-accepted")
        second.connection.failMethod = ApplicationMethod.ACTION_ACCEPTED.wireName

        second.request("new-execution", executionId = "execution-2")
        runCurrent()
        executor.release.complete(Unit)
        withTimeout(1_000) { executor.completed.await() }
        runCurrent()

        assertTrue(ApplicationMethod.ACTION_COMPLETED.wireName in first.connection.methods())
        assertFalse(ApplicationMethod.ACTION_COMPLETED.wireName in second.connection.methods())
        first.closeConnection()
        second.closeConnection()
        runtime.close()
    }

    private class ConnectionFixture(
        scope: kotlinx.coroutines.CoroutineScope,
        runtime: ApplicationActionExecutionRuntime,
        connectionId: String,
        private val identityScope: ActionIdentityScope = SCOPE,
    ) {
        val connection = RecordingConnection(connectionId)
        val rpc = AgentJsonRpcClient(connection, scope, requestTimeoutMillis = 1_000)
        private val sequence = AtomicLong(100)
        val status = ApplicationActionStatusClient(rpc, sequence::incrementAndGet, { NOW })
        val handler = ApplicationActionRequestHandler(
            rpc = rpc,
            runtime = runtime,
            trustedIdentity = { TrustedApplicationIdentity(identityScope, setOf("case:read")) },
            nextSequence = sequence::incrementAndGet,
            now = { NOW },
            scope = scope,
            statusClient = status,
        )

        suspend fun request(
            id: String,
            executionId: String = "execution-1",
            payload: JsonObject = requestPayload(),
        ) {
            connection.serverSend(ApplicationProtocol.JSON.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id, method = ApplicationMethod.ACTION_REQUEST.wireName, params = requestEnvelope(identityScope).copy(executionId = executionId, payload = payload))))
        }

        suspend fun closeConnection() {
            handler.closeConnection()
            rpc.close()
        }
    }

    private class RuntimeExecutor(
        private val store: RuntimeStore,
        private val previewOnly: Boolean = false,
    ) : ApplicationActionExecutor {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()

        override suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult {
            calls += 1
            store.current = record(command, if (previewOnly) ActionExecutionState.PREVIEWED else ActionExecutionState.EXECUTING)
            entered.complete(Unit)
            try {
                if (previewOnly) awaitCancellation() else release.await()
            } catch (cancelledFailure: kotlinx.coroutines.CancellationException) {
                store.current = record(command, ActionExecutionState.CANCELED)
                cancelled.complete(Unit)
                throw cancelledFailure
            }
            store.current = record(command, ActionExecutionState.SUCCEEDED)
            completed.complete(Unit)
            return ActionBusResult.Completed(requireNotNull(store.current?.result))
        }
    }

    private class RuntimeStore : ActionExecutionStore, ScopedActionExecutionQuery {
        @Volatile var current: ActionExecutionRecord? = null
        @Volatile var blockListReturn: CompletableDeferred<Unit>? = null
        @Volatile var failFindCount: Int = 0
        @Volatile var failFindAfterTerminalCount: Int = 0
        val listCaptured = CompletableDeferred<Unit>()
        override suspend fun find(executionId: String): ActionExecutionRecord? = current?.takeIf { it.command.executionId == executionId }
        override suspend fun find(executionId: String, identityScope: ActionIdentityScope): ActionExecutionRecord? {
            if (failFindCount > 0) { failFindCount -= 1; error("query unavailable") }
            if (current?.isTerminal == true && failFindAfterTerminalCount > 0) {
                failFindAfterTerminalCount -= 1
                error("terminal query unavailable")
            }
            return current?.takeIf { it.command.executionId == executionId && it.command.identityScope == identityScope && it.binding.identityScope == identityScope }
        }
        override suspend fun listNonTerminal(identityScope: ActionIdentityScope): List<ActionExecutionRecord> {
            val snapshot = listOfNotNull(current?.takeIf { !it.isTerminal && it.command.identityScope == identityScope && it.binding.identityScope == identityScope })
            listCaptured.complete(Unit)
            blockListReturn?.await()
            return snapshot
        }
        override suspend fun compareAndCreate(record: ActionExecutionRecord, audit: ActionAuditDraft): ExecutionCreateResult = error("unused")
        override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult = error("unused")
        override suspend fun updateReconciliation(update: ReconciliationExecutionUpdate): ReconciliationUpdateResult = error("unused")
        override suspend fun claimReconciliation(request: ReconciliationClaimRequest): ReconciliationClaimResult = error("unused")
        override suspend fun renewReconciliation(request: ReconciliationRenewRequest): ReconciliationRenewResult = error("unused")
        override suspend fun releaseReconciliation(request: ReconciliationReleaseRequest): ReconciliationReleaseResult = error("unused")
    }

    private class RecordingConnection(override val connectionId: String) : AgentConnection {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<String> = inbound
        override val state: StateFlow<AgentConnectionState> = MutableStateFlow(AgentConnectionState.Connected)
        override val hasConnected = true
        val sent = mutableListOf<String>()
        var onMethod: (String) -> Unit = {}
        var failMethod: String? = null
        var failMethodOnce: String? = null
        var blockMethod: String? = null
        val blockEntered = CompletableDeferred<Unit>()
        val blockRelease = CompletableDeferred<Unit>()
        override suspend fun send(text: String) {
            val value = text.json()
            val method = value["method"]?.jsonPrimitive?.content
            if (blockMethod != null && method == blockMethod) {
                blockEntered.complete(Unit)
                blockRelease.await()
            }
            if (failMethod != null && method == failMethod) error("send failed")
            if (failMethodOnce != null && method == failMethodOnce) {
                failMethodOnce = null
                error("send failed once")
            }
            sent += text
            method ?: return
            onMethod(method)
            val id = value["id"]?.jsonPrimitive?.content ?: return
            inbound.send(ApplicationProtocol.JSON.encodeToString(JsonRpcSuccessResponse.serializer(), JsonRpcSuccessResponse(id = id, result = JsonObject(emptyMap()))))
        }
        fun methods() = sent.mapNotNull { it.json()["method"]?.jsonPrimitive?.content }
        fun response(id: String) = sent.map { it.json() }.single { it["id"]?.jsonPrimitive?.content == id }
        fun hasResponse(id: String) = sent.map { it.json() }.any { it["id"]?.jsonPrimitive?.content == id }
        suspend fun serverSend(text: String) { inbound.send(text) }
        override suspend fun close() { inbound.close() }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-16T10:00:00Z")
        const val DESKTOP_SESSION_ID = "desktop-session-1"
        val SCOPE = ActionIdentityScope("desktop-1", DESKTOP_SESSION_ID, "auth-1", 8, "user-1", "tenant-1", "platform-1")

        fun common(sequence: Long, scope: ActionIdentityScope = SCOPE) = CommonApplicationFields(ApplicationProtocol.PROTOCOL_VERSION, scope.desktopInstanceId, scope.desktopSessionId, scope.authSessionId, scope.identityEpoch, sequence, NOW.toString(), scope.userId, scope.tenantId, scope.platformId)
        fun requestPayload() = buildJsonObject { put("actionId", "framework.demo"); put("actionVersion", 1); put("input", buildJsonObject { }); put("pageId", "page-1"); put("contextRevision", 1) }
        fun requestEnvelope(scope: ActionIdentityScope = SCOPE) = ActionEnvelope(common(1, scope), "thread-1", "turn-1", "tool-1", "execution-1", requestPayload())
        fun publication() = ApplicationActionPublicationContext(
            ApplicationActionCorrelation("thread-1", "turn-1", "tool-1", "execution-1"),
            TrustedApplicationIdentity(SCOPE, setOf("case:read")),
        )
        fun command() = ActionCommand(
            "execution-1", "framework.demo", 1, buildJsonObject { }, ActionOrigin.AGENT,
            SCOPE, "page-1", 1,
        )
        fun identity() = IdentityEnvelope(common(1), true, setOf("lawyer"), setOf("case:read"))
        fun catalog(): CatalogEnvelope { val payload = buildJsonObject { put("actions", true) }; return CatalogEnvelope(common(2), 1, 1, payload.toString().toByteArray().size, payload) }
        fun context(): ContextEnvelope { val payload = buildJsonObject { put("page", "demo") }; return ContextEnvelope(common(3), 1, 1, payload.toString().toByteArray().size, payload) }
        fun record(command: ActionCommand, state: ActionExecutionState): ActionExecutionRecord {
            val result: ActionResult<JsonElement>? = when (state) {
                ActionExecutionState.SUCCEEDED -> ActionResult.Success(command.executionId, buildJsonObject { put("private", true) }, buildJsonObject { put("ok", true) })
                ActionExecutionState.CANCELED -> ActionResult.Canceled(command.executionId, "connection_lost")
                else -> null
            }
            return ActionExecutionRecord(command, ExecutionBinding(command.actionId, command.actionVersion, "fingerprint", ActionOrigin.AGENT, command.identityScope, command.pageId, command.contextRevision), ActionRiskLevel.READ_ONLY, state, result, NOW, startedAt = if (state == ActionExecutionState.EXECUTING || result != null) NOW else null, completedAt = if (result != null) NOW else null, updatedAt = NOW, recordVersion = 1)
        }
        fun String.json() = ApplicationProtocol.JSON.parseToJsonElement(this).jsonObject
    }
}
