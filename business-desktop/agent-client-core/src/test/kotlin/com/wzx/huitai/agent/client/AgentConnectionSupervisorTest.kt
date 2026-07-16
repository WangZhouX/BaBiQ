package com.wzx.huitai.agent.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class AgentConnectionSupervisorTest {
    @Test
    fun `reconnect policy uses capped exponential delays and stops at ten failures`() {
        val policy = AgentReconnectPolicy()

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L, 10_000L, 10_000L, 10_000L),
            (1..9).map(policy::retryDelayMillis),
        )
        assertEquals(null, policy.retryDelayMillis(10))
        assertEquals(null, policy.retryDelayMillis(11))
    }

    @Test
    fun `ten consecutive transient failures require manual retry`() = runTest {
        val connections = (1..10).map { ordinal ->
            FakeConnection("connection-$ordinal", AgentConnectionState.TransportFailure())
        }
        val transport = RecordingTransport(connections)
        val delays = mutableListOf<Long>()
        val supervisor = supervisor(transport) { delays += it }

        supervisor.start()
        runCurrent()

        assertEquals(10, transport.requests.size)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L, 10_000L, 10_000L, 10_000L),
            delays,
        )
        assertEquals(AgentSupervisorState.ManualRetryRequired, supervisor.state.value)
    }

    @Test
    fun `a connected session resets the consecutive failure counter`() = runTest {
        val first = FakeConnection("connection-1", AgentConnectionState.TransportFailure())
        val second = FakeConnection("connection-2", AgentConnectionState.Connected)
        val third = FakeConnection("connection-3", AgentConnectionState.AuthenticationFailed)
        val transport = RecordingTransport(listOf(first, second, third))
        val delays = mutableListOf<Long>()
        val supervisor = supervisor(transport) { delays += it }

        supervisor.start()
        awaitConnected(supervisor, "connection-2")
        second.emitState(AgentConnectionState.TransportFailure())
        runCurrent()

        assertEquals(listOf(1_000L, 1_000L), delays)
        assertEquals(AgentSupervisorState.AuthenticationFailed, supervisor.state.value)
    }

    @Test
    fun `a fast connected then closed session still resets the failure counter`() = runTest {
        val first = FakeConnection("connection-1", AgentConnectionState.TransportFailure())
        val second = FakeConnection(
            connectionId = "connection-2",
            initialState = AgentConnectionState.Closed(code = 1000, reasonPresent = false),
            initiallyConnected = true,
        )
        val third = FakeConnection("connection-3", AgentConnectionState.AuthenticationFailed)
        val transport = RecordingTransport(listOf(first, second, third))
        val delays = mutableListOf<Long>()
        val supervisor = supervisor(transport) { delays += it }

        supervisor.start()
        runCurrent()

        assertEquals(listOf(1_000L, 1_000L), delays)
        assertEquals(AgentSupervisorState.AuthenticationFailed, supervisor.state.value)
    }

    @Test
    fun `authentication failure never retries`() = runTest {
        val transport = RecordingTransport(
            listOf(FakeConnection("connection-1", AgentConnectionState.AuthenticationFailed)),
        )
        val delays = mutableListOf<Long>()
        val supervisor = supervisor(transport) { delays += it }

        supervisor.start()
        runCurrent()

        assertEquals(1, transport.requests.size)
        assertTrue(delays.isEmpty())
        assertEquals(AgentSupervisorState.AuthenticationFailed, supervisor.state.value)
    }

    @Test
    fun `start begins the first connection before returning`() = runTest {
        val transport = RecordingTransport(
            listOf(FakeConnection("connection-1", AgentConnectionState.Connected)),
        )
        val supervisor = supervisor(transport)

        supervisor.start()

        assertEquals(1, transport.requests.size)
        assertEquals(AgentSupervisorState.Connected("connection-1"), supervisor.state.value)
        supervisor.shutdown()
    }

    @Test
    fun `shutdown closes active connection and prevents late retry`() = runTest {
        val active = FakeConnection("connection-1", AgentConnectionState.Connected)
        val transport = RecordingTransport(listOf(active))
        val supervisor = supervisor(transport)

        supervisor.start()
        awaitConnected(supervisor, "connection-1")
        supervisor.shutdown()
        active.emitState(AgentConnectionState.TransportFailure())
        advanceUntilIdle()

        assertEquals(1, transport.requests.size)
        assertEquals(1, active.closeCount)
        assertEquals(1, transport.closeCount)
        assertEquals(AgentSupervisorState.Shutdown, supervisor.state.value)
    }

    @Test
    fun `manual retry is accepted only from manual retry state and starts immediately`() = runTest {
        val connections = (1..10).map { ordinal ->
            FakeConnection("connection-$ordinal", AgentConnectionState.TransportFailure())
        } + FakeConnection("connection-11", AgentConnectionState.AuthenticationFailed)
        val transport = RecordingTransport(connections)
        val delays = mutableListOf<Long>()
        val supervisor = supervisor(transport) { delays += it }

        assertFalse(supervisor.manualRetry())
        supervisor.start()
        runCurrent()
        assertEquals(AgentSupervisorState.ManualRetryRequired, supervisor.state.value)

        assertTrue(supervisor.manualRetry())
        assertEquals(11, transport.requests.size)
        runCurrent()

        assertEquals(9, delays.size)
        assertEquals(AgentSupervisorState.AuthenticationFailed, supervisor.state.value)
    }

    @Test
    fun `late state and event from old connection cannot override active connection`() = runTest {
        val first = FakeConnection("connection-1", AgentConnectionState.Connected)
        val second = FakeConnection("connection-2", AgentConnectionState.Connected)
        val transport = RecordingTransport(listOf(first, second))
        val retryEntered = CompletableDeferred<Unit>()
        val allowRetry = CompletableDeferred<Unit>()
        val supervisor = supervisor(transport) {
            retryEntered.complete(Unit)
            allowRetry.await()
        }

        supervisor.start()
        awaitConnected(supervisor, "connection-1")
        first.emitIncoming("current-event")
        assertEquals("current-event", withTimeout(2_000) { supervisor.incoming.receive() })
        first.emitState(AgentConnectionState.TransportFailure())
        retryEntered.await()
        allowRetry.complete(Unit)
        awaitConnected(supervisor, "connection-2")

        first.emitIncoming("stale-event")
        first.emitState(AgentConnectionState.Connected)
        second.emitIncoming("fresh-event")
        assertEquals("fresh-event", withTimeout(2_000) { supervisor.incoming.receive() })
        runCurrent()
        assertEquals(AgentSupervisorState.Connected("connection-2"), supervisor.state.value)
        supervisor.shutdown()
    }

    @Test
    fun `queued old connection events are discarded and supervisor buffering stays bounded`() = runTest {
        val first = FakeConnection("connection-1", AgentConnectionState.Connected)
        val second = FakeConnection("connection-2", AgentConnectionState.Connected)
        val transport = RecordingTransport(listOf(first, second))
        val supervisor = supervisor(transport, incomingCapacity = 2)

        supervisor.start()
        awaitConnected(supervisor, "connection-1")
        first.emitIncoming("old-1")
        first.emitIncoming("old-2")
        first.emitIncoming("old-3")
        runCurrent()

        first.emitState(AgentConnectionState.TransportFailure())
        awaitConnected(supervisor, "connection-2")
        second.emitIncoming("fresh-1")
        second.emitIncoming("fresh-2")
        second.emitIncoming("fresh-3")
        runCurrent()

        assertEquals("fresh-2", supervisor.incoming.receive())
        assertEquals("fresh-3", supervisor.incoming.receive())
        assertTrue(supervisor.incoming.tryReceive().isFailure)
        supervisor.shutdown()
    }

    @Test
    fun `reconnect reuses child identity while every connection id remains distinct`() = runTest {
        val first = FakeConnection("connection-1", AgentConnectionState.TransportFailure())
        val second = FakeConnection("connection-2", AgentConnectionState.AuthenticationFailed)
        val transport = RecordingTransport(listOf(first, second))
        val request = request()
        val supervisor = AgentConnectionSupervisor(
            transport = transport,
            request = request,
            scope = backgroundScope,
            reconnectPolicy = AgentReconnectPolicy(),
            delayMillis = {},
        )

        supervisor.start()
        runCurrent()

        assertEquals(listOf("connection-1", "connection-2"), transport.connections.map { it.connectionId })
        assertSame(request.identity, transport.requests[0].identity)
        assertSame(request.identity, transport.requests[1].identity)
    }

    private fun kotlinx.coroutines.test.TestScope.supervisor(
        transport: RecordingTransport,
        incomingCapacity: Int = 128,
        delayMillis: suspend (Long) -> Unit = {},
    ) = AgentConnectionSupervisor(
        transport = transport,
        request = request(),
        scope = backgroundScope,
        reconnectPolicy = AgentReconnectPolicy(),
        delayMillis = delayMillis,
        incomingCapacity = incomingCapacity,
    )

    private suspend fun awaitConnected(
        supervisor: AgentConnectionSupervisor,
        connectionId: String,
    ) {
        supervisor.state.first { it == AgentSupervisorState.Connected(connectionId) }
    }

    private fun request() = AgentConnectRequest(
        url = "ws://127.0.0.1:43117/ws/agent",
        identity = DesktopSessionIdentity(
            desktopInstanceId = "desktop-installation-1",
            desktopSessionId = "desktop-session-1",
            desktopSessionToken = "desktop-session-token-secret",
            localOrigin = "http://127.0.0.1:43117",
        ),
    )

    private class RecordingTransport(connections: List<FakeConnection>) : AgentTransport {
        private val remaining = ArrayDeque(connections)
        val requests = mutableListOf<AgentConnectRequest>()
        val connections = mutableListOf<FakeConnection>()
        var closeCount: Int = 0
            private set

        override suspend fun connect(request: AgentConnectRequest): AgentConnection {
            requests += request
            return remaining.removeFirst().also(connections::add)
        }

        override suspend fun close() {
            closeCount += 1
        }
    }

    private class FakeConnection(
        override val connectionId: String,
        initialState: AgentConnectionState,
        initiallyConnected: Boolean = initialState == AgentConnectionState.Connected,
    ) : AgentConnection {
        private val mutableIncoming = Channel<String>(Channel.UNLIMITED)
        private val mutableState = MutableStateFlow(initialState)
        override val incoming: ReceiveChannel<String> = mutableIncoming
        override val state: StateFlow<AgentConnectionState> = mutableState
        override var hasConnected: Boolean = initiallyConnected
            private set
        var closeCount: Int = 0
            private set

        fun emitState(state: AgentConnectionState) {
            if (state == AgentConnectionState.Connected) hasConnected = true
            mutableState.value = state
        }

        fun emitIncoming(text: String) {
            mutableIncoming.trySend(text).getOrThrow()
        }

        override suspend fun send(text: String) = Unit

        override suspend fun close() {
            closeCount += 1
            mutableIncoming.close()
        }
    }
}
