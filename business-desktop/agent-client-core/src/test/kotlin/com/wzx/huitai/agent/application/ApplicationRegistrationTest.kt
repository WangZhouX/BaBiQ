package com.wzx.huitai.agent.application

import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.AgentJsonRpcClosedException
import com.wzx.huitai.agent.client.AgentJsonRpcException
import com.wzx.huitai.agent.client.ApplicationSequenceException
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.ApplicationProtocolLimits
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidationException
import com.wzx.huitai.agent.protocol.CatalogEnvelope
import com.wzx.huitai.agent.protocol.CommonApplicationFields
import com.wzx.huitai.agent.protocol.ContextEnvelope
import com.wzx.huitai.agent.protocol.IdentityEnvelope
import com.wzx.huitai.agent.protocol.JsonRpcError
import com.wzx.huitai.agent.protocol.JsonRpcErrorResponse
import com.wzx.huitai.agent.protocol.JsonRpcSuccessResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationRegistrationTest {
    @Test
    fun `every authenticated connection binds identity before catalog and context`() = runTest {
        val tracker = ApplicationSequenceTracker(DESKTOP_SESSION_ID)
        val first = registrationClients(RecordingConnection("connection-1"), tracker)

        first.identity.registerAuthenticatedConnection(
            identity = identity(sequence = 1, identityEpoch = 8),
            catalog = catalog(sequence = 2, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9),
            context = context(sequence = 3, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9),
        )

        assertEquals(
            listOf(
                ApplicationMethod.IDENTITY_BIND.wireName,
                ApplicationMethod.CATALOG_REGISTER.wireName,
                ApplicationMethod.CONTEXT_PUBLISH.wireName,
            ),
            first.connection.methods,
        )
        first.rpc.close()

        val reconnect = registrationClients(RecordingConnection("connection-2"), tracker)
        reconnect.identity.registerAuthenticatedConnection(
            identity = identity(sequence = 4, identityEpoch = 8),
            catalog = catalog(sequence = 5, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9),
            context = context(sequence = 6, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9),
        )

        assertEquals(
            listOf(
                ApplicationMethod.IDENTITY_BIND.wireName,
                ApplicationMethod.CATALOG_REGISTER.wireName,
                ApplicationMethod.CONTEXT_PUBLISH.wireName,
            ),
            reconnect.connection.methods,
        )
        reconnect.rpc.close()
    }

    @Test
    fun `same connection requires increasing registration epochs`() = runTest {
        val clients = registrationClients(
            RecordingConnection("connection-1"),
            ApplicationSequenceTracker(DESKTOP_SESSION_ID),
        )
        clients.identity.registerAuthenticatedConnection(
            identity = identity(sequence = 1, identityEpoch = 8),
            catalog = catalog(sequence = 2, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9),
            context = context(sequence = 3, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9),
        )

        assertFailsWith<ApplicationSequenceException> {
            clients.identity.update(identity(sequence = 4, identityEpoch = 8))
        }
        assertFailsWith<ApplicationSequenceException> {
            clients.catalog.update(
                catalog(sequence = 5, identityEpoch = 9, catalogEpoch = 3, contextSequence = 10),
            )
        }
        assertFailsWith<ApplicationSequenceException> {
            clients.context.publish(
                context(sequence = 6, identityEpoch = 9, catalogEpoch = 4, contextSequence = 9),
            )
        }
        clients.rpc.close()
    }

    @Test
    fun `ambiguous send failure consumes local watermarks but reconnect may republish business epochs`() = runTest {
        val tracker = ApplicationSequenceTracker(DESKTOP_SESSION_ID)
        val failedConnection = RecordingConnection("connection-1").apply { failSends = true }
        val failed = registrationClients(failedConnection, tracker)

        assertFailsWith<IllegalStateException> {
            failed.identity.bind(identity(sequence = 1, identityEpoch = 8))
        }
        assertFailsWith<ApplicationSequenceException> {
            failed.identity.bind(identity(sequence = 1, identityEpoch = 8))
        }
        failed.rpc.close()

        val reconnect = registrationClients(RecordingConnection("connection-2"), tracker)
        reconnect.identity.bind(identity(sequence = 2, identityEpoch = 8))
        assertEquals(listOf(ApplicationMethod.IDENTITY_BIND.wireName), reconnect.connection.methods)
        reconnect.rpc.close()
    }

    @Test
    fun `classification rejection does not consume envelope sequence for corrected retry`() = runTest {
        val clients = registrationClients(
            RecordingConnection("atomic-sequences"),
            ApplicationSequenceTracker(DESKTOP_SESSION_ID),
        )
        clients.identity.bind(identity(sequence = 1, identityEpoch = 8))

        assertFailsWith<ApplicationSequenceException> {
            clients.identity.update(identity(sequence = 2, identityEpoch = 8))
        }
        clients.identity.update(identity(sequence = 2, identityEpoch = 9))

        clients.catalog.register(catalog(sequence = 3, identityEpoch = 9, catalogEpoch = 3, contextSequence = 9))
        assertFailsWith<ApplicationSequenceException> {
            clients.catalog.update(catalog(sequence = 4, identityEpoch = 9, catalogEpoch = 3, contextSequence = 10))
        }
        clients.catalog.update(catalog(sequence = 4, identityEpoch = 9, catalogEpoch = 4, contextSequence = 10))

        clients.context.publish(context(sequence = 5, identityEpoch = 9, catalogEpoch = 4, contextSequence = 9))
        assertFailsWith<ApplicationSequenceException> {
            clients.context.publish(context(sequence = 6, identityEpoch = 9, catalogEpoch = 4, contextSequence = 9))
        }
        clients.context.publish(context(sequence = 6, identityEpoch = 9, catalogEpoch = 4, contextSequence = 10))
        assertEquals(6, clients.connection.sendCount)
        clients.rpc.close()
    }

    @Test
    fun `logout publishes signed out identity before local business cleanup without catalog rpc`() = runTest {
        val events = mutableListOf<String>()
        val clients = registrationClients(
            RecordingConnection("connection-1").apply { onSend = { events += it } },
            ApplicationSequenceTracker(DESKTOP_SESSION_ID),
        )
        clients.identity.registerAuthenticatedConnection(
            identity = identity(sequence = 1, identityEpoch = 8),
            catalog = catalog(sequence = 2, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9),
            context = context(sequence = 3, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9),
        )

        clients.identity.signOut(
            identity = signedOutIdentity(sequence = 4, identityEpoch = 9),
            afterPublished = { events += "local-cleanup" },
        )

        assertEquals(
            listOf(
                ApplicationMethod.IDENTITY_BIND.wireName,
                ApplicationMethod.CATALOG_REGISTER.wireName,
                ApplicationMethod.CONTEXT_PUBLISH.wireName,
                ApplicationMethod.IDENTITY_UPDATE.wireName,
            ),
            clients.connection.methods,
        )
        assertEquals(ApplicationMethod.IDENTITY_UPDATE.wireName, events[3])
        assertEquals("local-cleanup", events[4])
        clients.rpc.close()
    }

    @Test
    fun `registration validates complete shared scope and catalog context versions before any send`() = runTest {
        val identity = identity(sequence = 1, identityEpoch = 8)
        val catalog = catalog(sequence = 2, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9)
        val context = context(sequence = 3, identityEpoch = 8, catalogEpoch = 3, contextSequence = 9)
        val mismatches = listOf<Pair<String, (CatalogEnvelope, ContextEnvelope) -> Pair<CatalogEnvelope, ContextEnvelope>>>(
            "protocolVersion" to { c, x -> c.copy(common = c.common.copy(protocolVersion = "2.0")) to x },
            "desktopInstanceId" to { c, x -> c.copy(common = c.common.copy(desktopInstanceId = "other")) to x },
            "desktopSessionId" to { c, x -> c.copy(common = c.common.copy(desktopSessionId = "other")) to x },
            "authSessionId" to { c, x -> c.copy(common = c.common.copy(authSessionId = "other")) to x },
            "identityEpoch" to { c, x -> c.copy(common = c.common.copy(identityEpoch = 9)) to x },
            "userId" to { c, x -> c.copy(common = c.common.copy(userId = "other")) to x },
            "tenantId" to { c, x -> c.copy(common = c.common.copy(tenantId = "other")) to x },
            "platformId" to { c, x -> c.copy(common = c.common.copy(platformId = "other")) to x },
            "context desktopInstanceId" to { c, x -> c to x.copy(common = x.common.copy(desktopInstanceId = "other")) },
            "catalogEpoch" to { c, x -> c to x.copy(catalogEpoch = c.catalogEpoch + 1) },
            "contextSequence" to { c, x -> c to x.copy(contextSequence = c.contextSequence + 1) },
        )

        mismatches.forEachIndexed { index, (name, mismatch) ->
            val connection = RecordingConnection("scope-$index")
            val clients = registrationClients(connection, ApplicationSequenceTracker(DESKTOP_SESSION_ID))
            val (mismatchedCatalog, mismatchedContext) = mismatch(catalog, context)
            assertFailsWith<ApplicationProtocolValidationException>(name) {
                clients.identity.registerAuthenticatedConnection(identity, mismatchedCatalog, mismatchedContext)
            }
            assertEquals(0, connection.sendCount, name)
            clients.rpc.close()
        }
    }

    @Test
    fun `json rpc correlation removes pending entries on success error timeout and close`() = runTest {
        val successConnection = RecordingConnection("success")
        val successClient = AgentJsonRpcClient(successConnection, backgroundScope, requestTimeoutMillis = 1_000)
        successClient.request(ApplicationMethod.IDENTITY_BIND, identity(sequence = 1, identityEpoch = 1))
        assertEquals(0, successClient.pendingRequestCount)
        successClient.close()

        val errorConnection = RecordingConnection("error").apply { failRequests = true }
        val errorClient = AgentJsonRpcClient(errorConnection, backgroundScope, requestTimeoutMillis = 1_000)
        assertFailsWith<AgentJsonRpcException> {
            errorClient.request(ApplicationMethod.IDENTITY_BIND, identity(sequence = 2, identityEpoch = 2))
        }
        assertEquals(0, errorClient.pendingRequestCount)
        errorClient.close()

        val timeoutConnection = RecordingConnection("timeout").apply { respondToRequests = false }
        val timeoutClient = AgentJsonRpcClient(timeoutConnection, backgroundScope, requestTimeoutMillis = 1)
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            timeoutClient.request(ApplicationMethod.IDENTITY_BIND, identity(sequence = 3, identityEpoch = 3))
        }
        assertEquals(0, timeoutClient.pendingRequestCount)
        timeoutClient.close()

        val closeConnection = RecordingConnection("close").apply { respondToRequests = false }
        val closeClient = AgentJsonRpcClient(closeConnection, backgroundScope, requestTimeoutMillis = 10_000)
        val pending = async {
            assertFailsWith<AgentJsonRpcClosedException> {
                closeClient.request(ApplicationMethod.IDENTITY_BIND, identity(sequence = 4, identityEpoch = 4))
            }
        }
        runCurrent()
        assertEquals(1, closeClient.pendingRequestCount)
        closeClient.close()
        pending.await()
        assertEquals(0, closeClient.pendingRequestCount)
    }

    @Test
    fun `catalog and context reject forged payload size before transport send`() = runTest {
        val clients = registrationClients(
            RecordingConnection("payload-size"),
            ApplicationSequenceTracker(DESKTOP_SESSION_ID),
        )
        val validCatalog = catalog(sequence = 1, identityEpoch = 1, catalogEpoch = 1, contextSequence = 1)
        val validContext = context(sequence = 2, identityEpoch = 1, catalogEpoch = 1, contextSequence = 1)

        assertFailsWith<ApplicationProtocolValidationException> {
            clients.catalog.register(validCatalog.copy(payloadSize = validCatalog.payloadSize + 1))
        }
        assertFailsWith<ApplicationProtocolValidationException> {
            clients.context.publish(validContext.copy(payloadSize = validContext.payloadSize + 1))
        }

        assertEquals(0, clients.connection.sendCount)
        clients.rpc.close()
    }

    @Test
    fun `final json rpc envelope exceeding transport limit fails before send`() = runTest {
        val connection = RecordingConnection("oversized-envelope")
        val rpc = AgentJsonRpcClient(connection, backgroundScope, requestTimeoutMillis = 1_000)
        val oversizedRole = "r".repeat(ApplicationProtocolLimits.MAX_ENVELOPE_BYTES)
        val oversizedIdentity = identity(sequence = 1, identityEpoch = 1).copy(roles = setOf(oversizedRole))

        assertFailsWith<ApplicationProtocolValidationException> {
            rpc.notify(ApplicationMethod.IDENTITY_UPDATE, oversizedIdentity)
        }

        assertEquals(0, connection.sendCount)
        rpc.close()
    }

    @Test
    fun `full inbound queue never blocks response correlation`() = runTest {
        val connection = RecordingConnection("inbound-pressure").apply { respondToRequests = false }
        val rpc = AgentJsonRpcClient(
            connection = connection,
            scope = backgroundScope,
            requestTimeoutMillis = 1_000,
            inboundCapacity = 1,
        )
        val pending = async {
            rpc.request(ApplicationMethod.IDENTITY_BIND, identity(sequence = 1, identityEpoch = 1))
        }
        runCurrent()
        val requestId = connection.lastRequestId

        connection.serverSend(
            ApplicationProtocol.JSON.encodeToString(
                com.wzx.huitai.agent.protocol.JsonRpcNotification.serializer(),
                com.wzx.huitai.agent.protocol.JsonRpcNotification(
                    method = ApplicationMethod.IDENTITY_UPDATE.wireName,
                    params = identity(sequence = 2, identityEpoch = 2),
                ),
            ),
        )
        connection.serverSend(
            ApplicationProtocol.JSON.encodeToString(
                JsonRpcSuccessResponse.serializer(),
                JsonRpcSuccessResponse(id = requestId, result = buildJsonObject { put("ok", true) }),
            ),
        )

        assertTrue(withTimeout(1_000) { pending.await() }.getValue("ok").jsonPrimitive.content.toBoolean())
        rpc.close()
    }

    @Test
    fun `overloaded inbound requests receive protocol errors instead of silent eviction`() = runTest {
        val connection = RecordingConnection("inbound-overload")
        val rpc = AgentJsonRpcClient(connection, backgroundScope, requestTimeoutMillis = 1_000, inboundCapacity = 1)
        connection.serverSend(ApplicationProtocol.JSON.encodeToString(
            com.wzx.huitai.agent.protocol.JsonRpcNotification.serializer(),
                com.wzx.huitai.agent.protocol.JsonRpcNotification(method = ApplicationMethod.IDENTITY_UPDATE.wireName, params = identity(1, 1)),
        ))
        listOf(1L, 2L).forEachIndexed { index, id ->
            connection.serverSend(ApplicationProtocol.JSON.encodeToString(
                com.wzx.huitai.agent.protocol.JsonRpcRequest.serializer(),
                com.wzx.huitai.agent.protocol.JsonRpcRequest(id = id, method = ApplicationMethod.IDENTITY_BIND.wireName, params = identity((index + 2).toLong(), (index + 2).toLong())),
            ))
        }
        runCurrent()

        val inboundIds = buildSet {
            while (true) {
                val item = rpc.incoming.tryReceive().getOrNull() ?: break
                if (item is com.wzx.huitai.agent.client.AgentJsonRpcInbound.Request) add(item.value.id)
            }
        }
        val responseIds = connection.sentMessages().mapNotNull { it["id"]?.jsonPrimitive?.content?.toLongOrNull() }.toSet()
        assertTrue(setOf(1L, 2L).all { it in inboundIds || it in responseIds })
        rpc.close()
    }

    @Test
    fun `close cancels blocked request and notification sends without external release`() = runTest {
        val requestEntered = CompletableDeferred<Unit>()
        val requestSendCancelled = CompletableDeferred<Unit>()
        val requestConnection = RecordingConnection("request-close-race").apply {
            respondToRequests = false
            beforeSend = {
                requestEntered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    requestSendCancelled.complete(Unit)
                }
            }
        }
        val requestClient = AgentJsonRpcClient(requestConnection, backgroundScope, requestTimeoutMillis = 10_000)
        val request = async {
            assertFailsWith<AgentJsonRpcClosedException> {
                requestClient.request(ApplicationMethod.IDENTITY_BIND, identity(sequence = 1, identityEpoch = 1))
            }
        }
        requestEntered.await()
        val requestClose = async { requestClient.close() }
        runCurrent()
        withTimeout(1_000) { requestClose.await() }
        withTimeout(1_000) { request.await() }
        withTimeout(1_000) { requestSendCancelled.await() }
        assertEquals(0, requestClient.pendingRequestCount)

        val notificationEntered = CompletableDeferred<Unit>()
        val notificationSendCancelled = CompletableDeferred<Unit>()
        val notificationConnection = RecordingConnection("notification-close-race").apply {
            beforeSend = {
                notificationEntered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    notificationSendCancelled.complete(Unit)
                }
            }
        }
        val notificationClient = AgentJsonRpcClient(notificationConnection, backgroundScope)
        val notification = async {
            assertFailsWith<AgentJsonRpcClosedException> {
                notificationClient.notify(ApplicationMethod.IDENTITY_UPDATE, identity(sequence = 2, identityEpoch = 2))
            }
        }
        notificationEntered.await()
        val notificationClose = async { notificationClient.close() }
        runCurrent()
        withTimeout(1_000) { notificationClose.await() }
        withTimeout(1_000) { notification.await() }
        withTimeout(1_000) { notificationSendCancelled.await() }
    }

    @Test
    fun `request timeout budget includes blocked transport send`() = runTest {
        val sendCancelled = CompletableDeferred<Unit>()
        val connection = RecordingConnection("send-timeout").apply {
            beforeSend = {
                try {
                    awaitCancellation()
                } finally {
                    sendCancelled.complete(Unit)
                }
            }
        }
        val rpc = AgentJsonRpcClient(connection, backgroundScope, requestTimeoutMillis = 1)

        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            rpc.request(ApplicationMethod.IDENTITY_BIND, identity(sequence = 1, identityEpoch = 1))
        }
        withTimeout(1_000) { sendCancelled.await() }
        assertEquals(0, rpc.pendingRequestCount)
        rpc.close()
    }

    @Test
    fun `remote incoming close cancels blocked sends and explicit close awaits shared cleanup`() = runTest {
        val requestEntered = CompletableDeferred<Unit>()
        val requestSendCancelled = CompletableDeferred<Unit>()
        val requestConnection = RecordingConnection("remote-request-close").apply {
            respondToRequests = false
            beforeSend = {
                requestEntered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    requestSendCancelled.complete(Unit)
                }
            }
        }
        val requestClient = AgentJsonRpcClient(requestConnection, backgroundScope, requestTimeoutMillis = 10_000)
        val request = async {
            assertFailsWith<AgentJsonRpcClosedException> {
                requestClient.request(ApplicationMethod.IDENTITY_BIND, identity(sequence = 1, identityEpoch = 1))
            }
        }
        requestEntered.await()
        requestConnection.closeRemoteIncoming()

        withTimeout(1_000) { request.await() }
        withTimeout(1_000) { requestSendCancelled.await() }
        assertEquals(0, requestClient.pendingRequestCount)
        withTimeout(1_000) { requestClient.close() }

        val notificationEntered = CompletableDeferred<Unit>()
        val notificationSendCancelled = CompletableDeferred<Unit>()
        val notificationConnection = RecordingConnection("remote-notification-close").apply {
            beforeSend = {
                notificationEntered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    notificationSendCancelled.complete(Unit)
                }
            }
        }
        val notificationClient = AgentJsonRpcClient(notificationConnection, backgroundScope)
        val notification = async {
            assertFailsWith<AgentJsonRpcClosedException> {
                notificationClient.notify(ApplicationMethod.IDENTITY_UPDATE, identity(sequence = 2, identityEpoch = 2))
            }
        }
        notificationEntered.await()
        notificationConnection.closeRemoteIncoming()

        withTimeout(1_000) { notification.await() }
        withTimeout(1_000) { notificationSendCancelled.await() }
        withTimeout(1_000) { notificationClient.close() }
    }

    @Test
    fun `cancelled construction scope closes reader and rejects later operations`() = runTest {
        val cancelledJob = Job().apply { cancel() }
        val cancelledScope = CoroutineScope(coroutineContext + cancelledJob)
        val connection = RecordingConnection("cancelled-scope")
        val rpc = AgentJsonRpcClient(connection, cancelledScope)
        runCurrent()

        assertTrue(rpc.incoming.tryReceive().isClosed)
        assertFailsWith<AgentJsonRpcClosedException> {
            rpc.request(ApplicationMethod.IDENTITY_BIND, identity(sequence = 1, identityEpoch = 1))
        }
        assertFailsWith<AgentJsonRpcClosedException> {
            rpc.notify(ApplicationMethod.IDENTITY_UPDATE, identity(sequence = 2, identityEpoch = 2))
        }
        assertEquals(0, connection.sendCount)
    }

    private fun kotlinx.coroutines.CoroutineScope.registrationClients(
        connection: RecordingConnection,
        tracker: ApplicationSequenceTracker,
    ): RegistrationClients {
        val rpc = AgentJsonRpcClient(connection, this, requestTimeoutMillis = 1_000)
        val catalog = ApplicationCatalogClient(rpc, tracker)
        val context = ApplicationContextClient(rpc, tracker)
        val identity = ApplicationIdentityClient(rpc, tracker, catalog, context)
        return RegistrationClients(connection, rpc, identity, catalog, context)
    }

    private data class RegistrationClients(
        val connection: RecordingConnection,
        val rpc: AgentJsonRpcClient,
        val identity: ApplicationIdentityClient,
        val catalog: ApplicationCatalogClient,
        val context: ApplicationContextClient,
    )

    private class RecordingConnection(
        override val connectionId: String,
    ) : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        override val incoming: ReceiveChannel<String> = incomingChannel
        override val state: StateFlow<AgentConnectionState> = MutableStateFlow(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true
        val methods = mutableListOf<String>()
        var sendCount = 0
            private set
        var lastRequestId: Long = 0
            private set
        var respondToRequests = true
        var failRequests = false
        var failSends = false
        var beforeSend: suspend () -> Unit = {}
        var onSend: (String) -> Unit = {}
        private val sentTexts = mutableListOf<String>()

        override suspend fun send(text: String) {
            sentTexts += text
            beforeSend()
            sendCount += 1
            val message = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            message["method"]?.jsonPrimitive?.content?.let {
                methods += it
                onSend(it)
            }
            if (failSends) error("send outcome unknown")
            val id = message["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
            lastRequestId = id
            if (!respondToRequests) return
            val response = if (failRequests) {
                ApplicationProtocol.JSON.encodeToString(
                    JsonRpcErrorResponse.serializer(),
                    JsonRpcErrorResponse(id = id, error = JsonRpcError(code = -32000, message = "failed")),
                )
            } else {
                ApplicationProtocol.JSON.encodeToString(
                    JsonRpcSuccessResponse.serializer(),
                    JsonRpcSuccessResponse(id = id, result = JsonObject(emptyMap())),
                )
            }
            incomingChannel.send(response)
        }

        fun sentMessages() = sentTexts.map { ApplicationProtocol.JSON.parseToJsonElement(it).jsonObject }

        suspend fun serverSend(text: String) {
            incomingChannel.send(text)
        }

        fun closeRemoteIncoming() {
            incomingChannel.close()
        }

        override suspend fun close() {
            incomingChannel.close()
        }
    }

    private companion object {
        const val DESKTOP_SESSION_ID = "desktop-session-1"

        fun common(sequence: Long, identityEpoch: Long, authenticated: Boolean = true) = CommonApplicationFields(
            protocolVersion = ApplicationProtocol.PROTOCOL_VERSION,
            desktopInstanceId = "desktop-1",
            desktopSessionId = DESKTOP_SESSION_ID,
            authSessionId = if (authenticated) "auth-session-1" else null,
            identityEpoch = identityEpoch,
            sequence = sequence,
            generatedAt = "2026-07-16T10:00:00Z",
            userId = if (authenticated) "user-1" else null,
            tenantId = if (authenticated) "tenant-1" else null,
            platformId = if (authenticated) "platform-1" else null,
        )

        fun identity(sequence: Long, identityEpoch: Long) = IdentityEnvelope(
            common = common(sequence, identityEpoch),
            authenticated = true,
            roles = setOf("lawyer"),
            permissions = setOf("framework:read"),
        )

        fun signedOutIdentity(sequence: Long, identityEpoch: Long) = IdentityEnvelope(
            common = common(sequence, identityEpoch, authenticated = false),
            authenticated = false,
            roles = emptySet(),
            permissions = emptySet(),
        )

        fun catalog(
            sequence: Long,
            identityEpoch: Long,
            catalogEpoch: Long,
            contextSequence: Long,
            payload: JsonObject = buildJsonObject { put("actions", buildJsonObject { put("demo", true) }) },
        ) = CatalogEnvelope(
            common = common(sequence, identityEpoch),
            catalogEpoch = catalogEpoch,
            contextSequence = contextSequence,
            payloadSize = payload.toString().toByteArray().size,
            payload = payload,
        )

        fun context(
            sequence: Long,
            identityEpoch: Long,
            catalogEpoch: Long,
            contextSequence: Long,
        ): ContextEnvelope {
            val payload = buildJsonObject { put("pageType", "framework-demo") }
            return ContextEnvelope(
                common = common(sequence, identityEpoch),
                catalogEpoch = catalogEpoch,
                contextSequence = contextSequence,
                payloadSize = payload.toString().toByteArray().size,
                payload = payload,
            )
        }
    }
}
