package com.wzx.huitai.integration.websocket

import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.integration.auth.AuthenticationState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class HuitaiWebSocketClientTest {
    @Test
    fun `authenticated start connects with current atomic request identity`() = runTest {
        val manager = authenticatedManager("initial", tenantId = "tenant-1")
        val transport = FakeWebSocketTransport()
        val client = client(manager, transport)

        client.start()
        runCurrent()

        val request = transport.requests.single()
        assertEquals("wss://runtime.example.test/events", request.url)
        assertEquals("access-initial", request.accessToken)
        assertEquals("tenant-1", request.tenantId)
        assertIs<HuitaiWebSocketClientState.Connected>(client.state.value)
        client.close()
    }

    @Test
    fun `same business identity token refresh closes old connection and reconnects with new token`() = runTest {
        val manager = authenticatedManager("old", tenantId = "tenant-1")
        val transport = FakeWebSocketTransport()
        val client = client(manager, transport)
        client.start()
        runCurrent()
        val oldIdentity = requireNotNull(manager.identity.value)
        val oldConnection = transport.connections.single()

        refresh(manager, "new", tenantId = "tenant-1")
        runCurrent()

        val refreshedIdentity = requireNotNull(manager.identity.value)
        assertEquals(oldIdentity.authSessionId, refreshedIdentity.authSessionId)
        assertEquals(oldIdentity.identityEpoch, refreshedIdentity.identityEpoch)
        assertEquals(1, oldConnection.closeCount)
        assertEquals(listOf("access-old", "access-new"), transport.requests.map { it.accessToken })
        client.close()
    }

    @Test
    fun `tenant switch closes old connection and only reconnects under new tenant`() = runTest {
        val manager = authenticatedManager("old", tenantId = "tenant-old")
        val transport = FakeWebSocketTransport()
        val client = client(manager, transport)
        client.start()
        runCurrent()
        val oldConnection = transport.connections.single()

        refresh(manager, "new", tenantId = "tenant-new")
        runCurrent()

        assertEquals(1, oldConnection.closeCount)
        assertEquals(listOf("tenant-old", "tenant-new"), transport.requests.map { it.tenantId })
        assertEquals("access-new", transport.requests.last().accessToken)
        client.close()
    }

    @Test
    fun `logout closes active connection without reconnecting`() = runTest {
        val manager = authenticatedManager("initial")
        val transport = FakeWebSocketTransport()
        val client = client(manager, transport)
        client.start()
        runCurrent()
        val connection = transport.connections.single()

        manager.logout()
        runCurrent()

        assertEquals(1, connection.closeCount)
        assertEquals(1, transport.requests.size)
        assertIs<HuitaiWebSocketClientState.SignedOut>(client.state.value)
        client.close()
    }

    @Test
    fun `membership expired is terminal and never reconnects`() = runTest {
        val manager = authenticatedManager("initial")
        val transport = FakeWebSocketTransport()
        val client = client(manager, transport)
        client.start()
        runCurrent()
        val connection = transport.connections.single()

        manager.expireMembership()
        runCurrent()

        assertEquals(AuthenticationState.MEMBERSHIP_EXPIRED, manager.state.value)
        assertEquals(1, connection.closeCount)
        assertEquals(1, transport.requests.size)
        assertIs<HuitaiWebSocketClientState.MembershipExpired>(client.state.value)
        client.close()
    }

    @Test
    fun `transient failures use capped backoff and require manual retry after ten failures`() = runTest {
        val manager = authenticatedManager("initial")
        val transport = FakeWebSocketTransport(defaultFailure = HuitaiWebSocketFailureKind.TRANSIENT)
        val delays = mutableListOf<Long>()
        val client = client(manager, transport, retryDelay = { delays += it })

        client.start()
        runCurrent()

        assertEquals(10, transport.requests.size)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L), delays.take(5))
        assertEquals(List(5) { 10_000L }, delays.drop(4))
        val manual = assertIs<HuitaiWebSocketClientState.ManualRetryRequired>(client.state.value)
        assertEquals(10, manual.consecutiveFailures)
        client.close()
    }

    @Test
    fun `authentication rejection is terminal for the attempt and never loops`() = runTest {
        val manager = authenticatedManager("initial")
        val transport = FakeWebSocketTransport(
            defaultFailure = HuitaiWebSocketFailureKind.AUTHENTICATION_REJECTED,
        )
        val delays = mutableListOf<Long>()
        val client = client(manager, transport, retryDelay = { delays += it })

        client.start()
        runCurrent()

        assertEquals(1, transport.requests.size)
        assertTrue(delays.isEmpty())
        assertIs<HuitaiWebSocketClientState.AuthenticationRejected>(client.state.value)
        client.close()
    }

    @Test
    fun `manual retry resets failures and rereads latest authenticated identity`() = runTest {
        val manager = authenticatedManager("old", tenantId = "tenant-1")
        val transport = FakeWebSocketTransport(
            failures = MutableList(10) { HuitaiWebSocketFailureKind.TRANSIENT },
        )
        val client = client(manager, transport, retryDelay = {})
        client.start()
        runCurrent()
        assertIs<HuitaiWebSocketClientState.ManualRetryRequired>(client.state.value)

        refresh(manager, "new", tenantId = "tenant-1")
        runCurrent()
        assertEquals(10, transport.requests.size)

        client.manualRetry()
        runCurrent()

        assertEquals(11, transport.requests.size)
        assertEquals("access-new", transport.requests.last().accessToken)
        assertIs<HuitaiWebSocketClientState.Connected>(client.state.value)
        client.close()
    }

    @Test
    fun `late events from an old connection never enter the new identity stream`() = runTest {
        val manager = authenticatedManager("old", tenantId = "tenant-old")
        val transport = FakeWebSocketTransport()
        val client = client(manager, transport)
        client.start()
        runCurrent()
        val oldConnection = transport.connections.single()

        refresh(manager, "new", tenantId = "tenant-new")
        runCurrent()
        val newConnection = transport.connections.last()
        oldConnection.emit("old-identity-event")
        newConnection.emit("new-identity-event")
        runCurrent()

        val event = assertIs<HuitaiWebSocketEvent.Raw>(client.events.receive())
        assertEquals("new-identity-event", event.text)
        assertTrue(client.events.tryReceive().isFailure)
        client.close()
    }

    @Test
    fun `raw event buffer drops oldest and event rendering redacts payload`() = runTest {
        val manager = authenticatedManager("initial")
        val transport = FakeWebSocketTransport()
        val client = client(manager, transport, incomingCapacity = 2)
        client.start()
        runCurrent()
        val connection = transport.connections.single()

        connection.emit("secret-one")
        connection.emit("secret-two")
        connection.emit("secret-three")
        runCurrent()

        val second = assertIs<HuitaiWebSocketEvent.Raw>(client.events.receive())
        val third = assertIs<HuitaiWebSocketEvent.Raw>(client.events.receive())
        assertEquals("secret-two", second.text)
        assertEquals("secret-three", third.text)
        listOf(second.toString(), third.toString()).forEach { rendered ->
            assertFalse("secret" in rendered, rendered)
            assertTrue("REDACTED" in rendered, rendered)
        }
        client.close()
    }

    @Test
    fun `authentication rejection emits only a sanitized event`() = runTest {
        val manager = authenticatedManager("initial")
        val transport = FakeWebSocketTransport(
            defaultFailure = HuitaiWebSocketFailureKind.AUTHENTICATION_REJECTED,
        )
        val client = client(manager, transport)

        client.start()
        runCurrent()

        val event = assertIs<HuitaiWebSocketEvent.Sanitized>(
            withTimeout(1_000) { client.events.receive() },
        )
        assertEquals(HuitaiWebSocketFailureKind.AUTHENTICATION_REJECTED, event.kind)
        assertFalse("access-initial" in event.toString(), event.toString())
        client.close()
    }

    @Test
    fun `cancelling injected scope closes the active connection`() = runTest {
        val manager = authenticatedManager("initial")
        val transport = FakeWebSocketTransport()
        val injectedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val client = HuitaiWebSocketClient(
            authSessionManager = manager,
            transport = transport,
            webSocketUrl = { "wss://runtime.example.test/events" },
            scope = injectedScope,
        )
        client.start()
        runCurrent()
        val connection = transport.connections.single()

        injectedScope.cancel()
        runCurrent()

        assertEquals(1, connection.closeCount)
    }

    @Test
    fun `close is idempotent and closes the active connection once`() = runTest {
        val manager = authenticatedManager("initial")
        val transport = FakeWebSocketTransport()
        val client = client(manager, transport)
        client.start()
        runCurrent()
        val connection = transport.connections.single()

        client.close()
        client.close()

        assertEquals(1, connection.closeCount)
    }

    private fun TestScope.client(
        manager: AuthSessionManager,
        transport: FakeWebSocketTransport,
        retryDelay: suspend (Long) -> Unit = {},
        incomingCapacity: Int = 16,
    ) = HuitaiWebSocketClient(
        authSessionManager = manager,
        transport = transport,
        webSocketUrl = { "wss://runtime.example.test/events" },
        scope = backgroundScope,
        retryDelay = retryDelay,
        incomingCapacity = incomingCapacity,
    )
}

private class FakeWebSocketTransport(
    failures: List<HuitaiWebSocketFailureKind> = emptyList(),
    private val defaultFailure: HuitaiWebSocketFailureKind? = null,
) : HuitaiWebSocketTransport {
    private val failures = ArrayDeque(failures)
    val requests = mutableListOf<HuitaiWebSocketConnectRequest>()
    val connections = mutableListOf<FakeWebSocketConnection>()

    override fun connect(request: HuitaiWebSocketConnectRequest): HuitaiWebSocketConnection {
        requests += request
        val failure = failures.removeFirstOrNull() ?: defaultFailure
        val connection = FakeWebSocketConnection(
            initialState = failure?.let(HuitaiWebSocketState::Error) ?: HuitaiWebSocketState.Connected,
        )
        connections += connection
        return connection
    }
}

private class FakeWebSocketConnection(
    initialState: HuitaiWebSocketState,
) : HuitaiWebSocketConnection {
    private val mutableIncoming = Channel<String>(
        capacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutableState = MutableStateFlow(initialState)
    override val incoming: ReceiveChannel<String> = mutableIncoming
    override val state: StateFlow<HuitaiWebSocketState> = mutableState.asStateFlow()
    var closeCount: Int = 0

    fun emit(text: String) {
        mutableIncoming.trySend(text)
    }

    override suspend fun close() {
        closeCount += 1
        mutableState.value = HuitaiWebSocketState.Closed(code = 1000, reasonPresent = false)
    }
}

private suspend fun authenticatedManager(
    tokenSuffix: String,
    tenantId: String = "tenant-1",
): AuthSessionManager = AuthSessionManager(InMemoryCredentialPersistence()).also { manager ->
    manager.login(
        userId = "user-1",
        tenantId = tenantId,
        platformId = "platform-1",
        roles = setOf("lawyer"),
        permissions = setOf("case:read"),
        authenticatedAt = Instant.parse("2026-07-16T00:00:00Z"),
        tokens = tokens(tokenSuffix),
    )
}

private suspend fun refresh(
    manager: AuthSessionManager,
    tokenSuffix: String,
    tenantId: String,
) = manager.refresh(
    userId = "user-1",
    tenantId = tenantId,
    platformId = "platform-1",
    roles = setOf("lawyer"),
    permissions = setOf("case:read"),
    authenticatedAt = Instant.parse("2026-07-16T00:01:00Z"),
    tokens = tokens(tokenSuffix),
)

private fun tokens(suffix: String) = AuthTokenSet(
    accessToken = "access-$suffix",
    refreshToken = "refresh-$suffix",
)

private class InMemoryCredentialPersistence : AuthCredentialPersistencePort {
    private var tokens: AuthTokenSet? = null

    override suspend fun load(): AuthTokenSet? = tokens

    override suspend fun replace(tokens: AuthTokenSet) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}
