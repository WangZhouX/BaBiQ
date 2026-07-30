package com.wzx.huitai.desktop.controller

import com.wzx.huitai.agent.business.auth.BusinessAuthStateChangeCode
import com.wzx.huitai.agent.business.auth.BusinessAuthStateChanged
import com.wzx.huitai.agent.business.auth.BusinessAuthStatus
import com.wzx.huitai.agent.client.AgentJsonRpcException
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.conversation.BusinessAgentEvent
import com.wzx.huitai.agent.conversation.BusinessAgentIngressEvent
import com.wzx.huitai.agent.conversation.BusinessAgentClient
import com.wzx.huitai.agent.conversation.BusinessAttachmentDraft
import com.wzx.huitai.agent.conversation.BusinessConversationGateway
import com.wzx.huitai.agent.conversation.BusinessProvider
import com.wzx.huitai.agent.conversation.BusinessProviderSelection
import com.wzx.huitai.agent.conversation.BusinessThread
import com.wzx.huitai.agent.conversation.BusinessThreadItem
import com.wzx.huitai.agent.conversation.BusinessTurn
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.auth.BusinessIdentityRegistry
import com.wzx.huitai.desktop.auth.ReadyAgentUsageGate
import com.wzx.huitai.desktop.auth.StaleAgentUsageException
import com.wzx.huitai.desktop.state.BusinessDesktopEvent
import com.wzx.huitai.desktop.state.BusinessDesktopReducer
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class BusinessConversationControllerTest {
    @Test
    fun `start turn forwards immutable attachment metadata to the gateway`() = runTest {
        val gateway = RecordingGateway()
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        store.dispatch(BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        val controller = BusinessConversationController(gateway, store, ReadyAgentUsageGate(readyRegistry()), this)
        val attachment = attachment()

        controller.startTurn("check", listOf(attachment), "provider-1")

        assertEquals("check", gateway.text)
        assertEquals(listOf(attachment), gateway.attachments)
        assertEquals("provider-1", gateway.providerId)
        controller.close()
    }

    @Test
    fun `attachment error code maps to actionable Chinese message without remote diagnostics`() = runTest {
        val privatePath = "C:\\Users\\secret\\customer-contract.pdf"
        val gateway = RecordingGateway().apply {
            failure = AgentJsonRpcException(
                remoteCode = -32602,
                attachmentCode = "ATTACHMENT_NOT_FOUND",
            ).also { it.addSuppressed(IllegalStateException("cannot read $privatePath")) }
        }
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        store.dispatch(BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        val controller = BusinessConversationController(gateway, store, ReadyAgentUsageGate(readyRegistry()), this)

        assertFailsWith<AgentJsonRpcException> {
            controller.startTurn("check", listOf(attachment()), "provider-1")
        }

        assertEquals("TURN_START_FAILED", store.state.value.error?.code)
        assertEquals("附件已不存在，请重新选择后再发送", store.state.value.error?.message)
        check(!store.state.value.error?.message.orEmpty().contains(privatePath))
        check(!store.state.value.error?.message.orEmpty().contains("cannot read"))
        controller.close()
    }

    @Test
    fun `non ready authentication rejects turn before gateway send`() = runTest {
        val gateway = RecordingGateway()
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        store.dispatch(BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        val registry = BusinessIdentityRegistry().apply { transitionTo(BusinessAccessGateState.RESTORING) }
        val controller = BusinessConversationController(gateway, store, ReadyAgentUsageGate(registry), this)

        assertFailsWith<IllegalStateException> { controller.startTurn("must not send") }

        assertEquals(0, gateway.startTurnCalls)
        controller.close()
    }

    @Test
    fun `late turn result from logged out identity is discarded after a new identity becomes ready`() = runTest {
        val gateway = RecordingGateway()
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val oldIdentity = identity(epoch = 1, authSessionId = "old-session", userId = "old-user")
        store.dispatch(BusinessDesktopEvent.IdentityAuthenticated(oldIdentity))
        store.dispatch(BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        val registry = readyRegistry(oldIdentity)
        val controller = BusinessConversationController(gateway, store, ReadyAgentUsageGate(registry), this)
        gateway.startTurnResult = CompletableDeferred()

        val pending = async { runCatching { controller.startTurn("old request") } }
        gateway.startTurnEntered.await()
        registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        store.dispatch(BusinessDesktopEvent.SignedOut)
        val newIdentity = identity(epoch = 2, authSessionId = "new-session", userId = "new-user")
        store.dispatch(BusinessDesktopEvent.IdentityAuthenticated(newIdentity))
        check(registry.publishReady(newIdentity, 1))
        gateway.startTurnResult!!.complete(BusinessTurn("old-turn", "thread-1"))
        runCurrent()

        assertIs<StaleAgentUsageException>(pending.await().exceptionOrNull())
        assertNull(store.state.value.activeTurn)
        controller.close()
    }

    @Test
    fun `late failure from logged out identity cannot publish error into new identity store`() = runTest {
        val gateway = RecordingGateway().apply {
            failure = IllegalStateException("old request failed")
            failureRelease = CompletableDeferred()
        }
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val oldIdentity = identity(epoch = 1, authSessionId = "old-session", userId = "old-user")
        store.dispatch(BusinessDesktopEvent.IdentityAuthenticated(oldIdentity))
        store.dispatch(BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        val registry = readyRegistry(oldIdentity)
        val controller = BusinessConversationController(gateway, store, ReadyAgentUsageGate(registry), this)

        val pending = async { runCatching { controller.startTurn("old request") } }
        gateway.startTurnEntered.await()
        registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        store.dispatch(BusinessDesktopEvent.SignedOut)
        val newIdentity = identity(epoch = 2, authSessionId = "new-session", userId = "new-user")
        store.dispatch(BusinessDesktopEvent.IdentityAuthenticated(newIdentity))
        check(registry.publishReady(newIdentity, 1))
        gateway.failureRelease!!.complete(Unit)
        runCurrent()

        check(pending.await().isFailure)
        assertNull(store.state.value.error)
        controller.close()
    }

    @Test
    fun `events emitted for revoked session are not delivered to the next ready identity`() = runTest {
        val gateway = RecordingGateway()
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val registry = readyRegistry(identity(epoch = 1, authSessionId = "old-session", userId = "old-user"))
        val controller = BusinessConversationController(gateway, store, ReadyAgentUsageGate(registry), this)
        runCurrent()

        registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        runCurrent()
        gateway.mutableEvents.emit(BusinessAgentEvent.Unknown("old/session-event"))
        runCurrent()
        val newIdentity = identity(epoch = 2, authSessionId = "new-session", userId = "new-user")
        check(registry.publishReady(newIdentity, 1))
        runCurrent()
        gateway.eventAuthSessionId = "new-session"
        gateway.eventIdentityEpoch = 2
        gateway.mutableEvents.emit(BusinessAgentEvent.Unknown("new/session-event"))
        runCurrent()

        assertEquals(1, store.state.value.unknownEventCount)
        controller.close()
    }

    @Test
    fun `raw auth state notification bypasses ready and identity epoch filters`() = runTest {
        val connection = NotificationConnection()
        val rpc = AgentJsonRpcClient(connection = connection, scope = this)
        val client = BusinessAgentClient(rpc, this)
        val registry = BusinessIdentityRegistry().apply { transitionTo(BusinessAccessGateState.RESTORING) }
        val handled = CompletableDeferred<BusinessAuthStateChanged>()
        val controller = BusinessConversationController(
            gateway = client,
            store = BusinessDesktopStore(BusinessDesktopReducer()),
            usageGate = ReadyAgentUsageGate(registry),
            scope = this,
            onAuthStateChanged = { handled.complete(it) },
        )

        connection.serverNotifyAuthState(
            authSessionId = "auth-reconnecting",
            generation = 11,
            businessCode = "BUSINESS_AUTH_EXPIRED",
        )

        val change = withTimeout(1_000) { handled.await() }
        assertEquals("auth-reconnecting", change.authSessionId)
        assertEquals(11, change.generation)
        assertEquals(BusinessAuthStateChangeCode.AUTH_EXPIRED, change.businessCode)
        controller.close()
        rpc.close()
    }

    @Test
    fun `auth reconciliation failure is isolated from later conversation events`() = runTest {
        val gateway = RecordingGateway().apply {
            eventAuthSessionId = "auth-session-1"
            eventIdentityEpoch = 1
        }
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val controller = BusinessConversationController(
            gateway = gateway,
            store = store,
            usageGate = ReadyAgentUsageGate(readyRegistry()),
            scope = this,
            onAuthStateChanged = { throw IllegalStateException("session probe failed") },
        )

        runCurrent()
        gateway.mutableAuthEvents.emit(authStateChanged())
        runCurrent()
        gateway.mutableEvents.emit(BusinessAgentEvent.Unknown("future/conversation-event"))

        val state = withTimeout(1_000) { store.state.first { it.unknownEventCount == 1 } }
        assertEquals(1, state.unknownEventCount)
        controller.close()
    }

    @Test
    fun `auth reconciliation cancellation stops the ingress collector`() = runTest {
        val gateway = RecordingGateway().apply {
            eventAuthSessionId = "auth-session-1"
            eventIdentityEpoch = 1
        }
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        var authCallbackCount = 0
        val controller = BusinessConversationController(
            gateway = gateway,
            store = store,
            usageGate = ReadyAgentUsageGate(readyRegistry()),
            scope = this,
            onAuthStateChanged = {
                authCallbackCount += 1
                throw CancellationException("shutdown")
            },
        )

        runCurrent()
        gateway.mutableAuthEvents.emit(authStateChanged())
        runCurrent()
        assertEquals(1, authCallbackCount)
        gateway.mutableEvents.emit(BusinessAgentEvent.Unknown("must-not-be-consumed"))
        runCurrent()

        assertEquals(0, store.state.value.unknownEventCount)
        controller.close()
    }

    @Test
    fun `real business agent channel preserves producing identity when old notification arrives after switch`() = runTest {
        val connection = NotificationConnection()
        val oldIdentity = identity(epoch = 1, authSessionId = "old-session", userId = "old-user")
        val registry = readyRegistry(oldIdentity)
        val rpc = AgentJsonRpcClient(connection = connection, scope = this)
        val client = BusinessAgentClient(rpc, this)
        val ingress = async { client.ingressEvents.first() }
        registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        val newIdentity = identity(epoch = 2, authSessionId = "new-session", userId = "new-user")
        check(registry.publishReady(newIdentity, 1))
        connection.serverNotify("old/session-event", authSessionId = "old-session", identityEpoch = 1)

        val received = assertIs<BusinessAgentIngressEvent.Conversation>(ingress.await())
        assertEquals("old-session", received.authSessionId)
        assertEquals(1, received.identityEpoch)
        rpc.close()
    }

    @Test
    fun `real channel controller rejects delayed old notification and stores new notification after relogin`() = runTest {
        val connection = NotificationConnection()
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        val oldIdentity = identity(epoch = 1, authSessionId = "old-session", userId = "old-user")
        store.dispatch(BusinessDesktopEvent.IdentityAuthenticated(oldIdentity))
        store.dispatch(BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        val registry = readyRegistry(oldIdentity)
        val rpc = AgentJsonRpcClient(connection = connection, scope = this)
        val client = BusinessAgentClient(rpc, this)
        val controller = BusinessConversationController(client, store, ReadyAgentUsageGate(registry), this)

        registry.invalidate(BusinessAccessGateState.SIGNING_OUT)
        store.dispatch(BusinessDesktopEvent.SignedOut)
        val newIdentity = identity(epoch = 2, authSessionId = "new-session", userId = "new-user")
        check(registry.publishReady(newIdentity, 1))
        store.dispatch(BusinessDesktopEvent.IdentityAuthenticated(newIdentity))
        store.dispatch(BusinessDesktopEvent.ThreadChanged(BusinessThread("thread-1", "demo", "C:/demo")))
        connection.serverNotifyTurn("old-turn", authSessionId = "old-session", identityEpoch = 1)
        connection.serverNotifyItem("old-item", "old-turn", authSessionId = "old-session", identityEpoch = 1)
        connection.serverNotifyTurn("new-turn", authSessionId = "new-session", identityEpoch = 2)
        connection.serverNotifyItem("new-item", "new-turn", authSessionId = "new-session", identityEpoch = 2)

        val completed = withTimeout(1_000) {
            store.state.first { state -> state.messages.any { it.id == "new-item" } }
        }
        assertEquals(listOf("new-item"), completed.messages.map(BusinessThreadItem::id))
        controller.close()
        rpc.close()
    }

    @Test
    fun `unscoped provider cleanup is rejected after the ready gate closes`() = runTest {
        val gateway = RecordingGateway()
        val store = BusinessDesktopStore(BusinessDesktopReducer())
        store.dispatch(BusinessDesktopEvent.ProvidersChanged(listOf(provider())))
        val registry = BusinessIdentityRegistry().apply { transitionTo(BusinessAccessGateState.SIGNING_OUT) }
        val controller = BusinessConversationController(gateway, store, ReadyAgentUsageGate(registry), this)

        assertFailsWith<IllegalStateException> { controller.acceptProviders(emptyList()) }

        assertEquals(listOf(provider()), store.state.value.providers)
        controller.close()
    }

    private class RecordingGateway : BusinessConversationGateway {
        val mutableEvents = MutableSharedFlow<BusinessAgentEvent>(extraBufferCapacity = 8)
        val mutableAuthEvents = MutableSharedFlow<BusinessAuthStateChanged>(extraBufferCapacity = 8)
        override val events: Flow<BusinessAgentEvent> = mutableEvents
        override val ingressEvents: Flow<BusinessAgentIngressEvent> = merge(
            mutableEvents.map { event ->
                BusinessAgentIngressEvent.Conversation(
                    event,
                    authSessionId = eventAuthSessionId,
                    identityEpoch = eventIdentityEpoch,
                )
            },
            mutableAuthEvents.map { BusinessAgentIngressEvent.AuthStateChanged(it) },
        )
        var eventAuthSessionId: String = "old-session"
        var eventIdentityEpoch: Long = 1
        var text: String? = null
        var attachments: List<BusinessAttachmentDraft>? = null
        var providerId: String? = null
        var failure: Exception? = null
        var failureRelease: CompletableDeferred<Unit>? = null
        var startTurnCalls: Int = 0
        val startTurnEntered = CompletableDeferred<Unit>()
        var startTurnResult: CompletableDeferred<BusinessTurn>? = null
        override suspend fun listProviders(): List<BusinessProvider> = emptyList()
        override suspend fun setActiveProvider(providerId: String, modelId: String?): BusinessProviderSelection =
            error("unused")
        override suspend fun createThread(cwd: String): BusinessThread = error("unused")
        override suspend fun startTurn(
            threadId: String,
            text: String,
            attachments: List<BusinessAttachmentDraft>,
            providerId: String?,
        ): BusinessTurn {
            startTurnCalls += 1
            startTurnEntered.complete(Unit)
            failureRelease?.await()
            failure?.let { throw it }
            this.text = text
            this.attachments = attachments
            this.providerId = providerId
            return startTurnResult?.await() ?: BusinessTurn("turn-1", threadId)
        }
        override suspend fun cancelTurn(turnId: String): Boolean = false
        override fun close() = Unit
    }

    private class NotificationConnection : AgentConnection {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        override val connectionId: String = "connection-1"
        override val incoming = inbound
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true

        override suspend fun send(text: String) = error("unexpected outbound request")

        suspend fun serverNotify(method: String, authSessionId: String, identityEpoch: Long) {
            inbound.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", buildJsonObject {
                    put("authSessionId", authSessionId)
                    put("identityEpoch", identityEpoch)
                })
            }.toString())
        }

        suspend fun serverNotifyAuthState(
            authSessionId: String,
            generation: Long,
            businessCode: String,
        ) {
            inbound.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "business/auth/state-changed")
                put("params", buildJsonObject {
                    put("authSessionId", authSessionId)
                    put("state", "SIGNED_OUT")
                    put("generation", generation)
                    put("businessCode", businessCode)
                })
            }.toString())
        }

        suspend fun serverNotifyTurn(turnId: String, authSessionId: String, identityEpoch: Long) {
            inbound.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "turn/started")
                put("params", buildJsonObject {
                    put("threadId", "thread-1")
                    put("turnId", turnId)
                    put("authSessionId", authSessionId)
                    put("identityEpoch", identityEpoch)
                })
            }.toString())
        }

        suspend fun serverNotifyItem(
            itemId: String,
            turnId: String,
            authSessionId: String,
            identityEpoch: Long,
        ) {
            inbound.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "item/added")
                put("params", buildJsonObject {
                    put("threadId", "thread-1")
                    put("turnId", turnId)
                    put("authSessionId", authSessionId)
                    put("identityEpoch", identityEpoch)
                    put("item", buildJsonObject {
                        put("id", itemId)
                        put("type", "agentMessage")
                        put("text", itemId)
                    })
                })
            }.toString())
        }

        override suspend fun close() {
            inbound.close()
        }
    }

    private fun attachment(): BusinessAttachmentDraft = BusinessAttachmentDraft(
        id = "00000000-0000-0000-0000-000000000501",
        displayId = "A-BCDEFG",
        name = "合同.pdf",
        localPath = "C:/private/合同.pdf",
        sizeBytes = 5,
        displayType = "PDF",
    )

    private fun provider() = BusinessProvider(
        id = "provider-1",
        displayName = "Provider",
        models = emptyList(),
        authMode = "api_key",
        hasApiKey = true,
        active = true,
        type = "OPENAI_COMPATIBLE",
        model = "model-1",
    )

    private fun readyRegistry(identity: BusinessIdentity = identity()): BusinessIdentityRegistry =
        BusinessIdentityRegistry().also { check(it.publishReady(identity, 0)) }

    private fun authStateChanged() = BusinessAuthStateChanged(
        authSessionId = "auth-session-1",
        state = BusinessAuthStatus.SIGNED_OUT,
        generation = 2,
        businessCode = BusinessAuthStateChangeCode.AUTH_EXPIRED,
    )

    private fun identity(
        epoch: Long = 1,
        authSessionId: String = "auth-session-1",
        userId: String = "user-1",
    ) = BusinessIdentity(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "desktop-session-1",
        authSessionId = authSessionId,
        identityEpoch = epoch,
        userId = userId,
        tenantId = "tenant-1",
        platformId = "1",
        roles = setOf("lawyer"),
        permissions = setOf("case:read"),
    )
}
