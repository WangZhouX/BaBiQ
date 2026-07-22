package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentTransport
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BusinessAgentConnectionSessionTest {
    @Test
    fun `external session authenticates without owning a backend process`() = runTest {
        val identity = DesktopSessionIdentity.forChildLaunch(
            desktopInstanceId = UUID.randomUUID().toString(),
            localOrigin = "http://127.0.0.1",
        )
        val request = AgentConnectRequest("ws://127.0.0.1:49391/ws/agent", identity)
        val session = BusinessAgentConnectionSession(request)
        val connection = FakeAgentConnection()

        val facade = session.connect(SingleConnectionTransport(connection), this)

        assertSame(identity, session.identity)
        session.sequenceTracker.acceptEnvelopeSequence(identity.desktopSessionId, 1)
        assertEquals(
            AgentConnectionState.Connected,
            facade.state.first { it == AgentConnectionState.Connected },
        )
        facade.close()
    }

    @Test
    fun `one session allows only one supervisor attachment`() = runTest {
        val identity = DesktopSessionIdentity.forChildLaunch(
            desktopInstanceId = UUID.randomUUID().toString(),
            localOrigin = "http://127.0.0.1",
        )
        val session = BusinessAgentConnectionSession(
            AgentConnectRequest("ws://127.0.0.1:49391/ws/agent", identity),
        )
        val first = session.connect(SingleConnectionTransport(FakeAgentConnection()), this)

        assertFailsWith<IllegalStateException> {
            session.connect(SingleConnectionTransport(FakeAgentConnection()), this)
        }

        first.close()
    }

    private class SingleConnectionTransport(
        private val connection: AgentConnection,
    ) : AgentTransport {
        override suspend fun connect(request: AgentConnectRequest): AgentConnection = connection
        override suspend fun close() = Unit
    }

    private class FakeAgentConnection : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        override val connectionId: String = "external-development-connection"
        override val incoming: ReceiveChannel<String> = incomingChannel
        override val state: StateFlow<AgentConnectionState> =
            MutableStateFlow(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true
        override suspend fun send(text: String) = Unit
        override suspend fun close() {
            incomingChannel.close()
        }
    }
}
