package com.wzx.huitai.agent.client

import com.wzx.huitai.agent.conversation.BusinessAgentClient
import com.wzx.huitai.agent.conversation.BusinessAgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class AgentJsonRpcNotificationLifecycleTest {
    @Test
    fun `business notification arriving before typed client is buffered`() = runTest {
        val connection = NotificationConnection()
        val rpc = AgentJsonRpcClient(connection, this, inboundCapacity = 1)
        connection.notify("future/first")
        runCurrent()

        val client = BusinessAgentClient(rpc, this)
        val events = async { withTimeout(500) { client.events.take(1).toList() } }
        assertEquals("future/first", assertIs<BusinessAgentEvent.Unknown>(events.await().single()).method)
        rpc.close()
    }

    @Test
    fun `bounded notification path applies backpressure and preserves order without dropping`() = runTest {
        val connection = NotificationConnection()
        val rpc = AgentJsonRpcClient(connection, this, inboundCapacity = 1)
        connection.notify("future/one")
        connection.notify("future/two")
        runCurrent()

        val client = BusinessAgentClient(rpc, this)
        val events = withTimeout(500) { client.events.take(2).toList() }
        assertEquals(
            listOf("future/one", "future/two"),
            events.map { assertIs<BusinessAgentEvent.Unknown>(it).method },
        )
        rpc.close()
    }

    @Test
    fun `rpc close completes typed business event flow`() = runTest {
        val connection = NotificationConnection()
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAgentClient(rpc, this)
        val completion = async { client.events.toList() }

        rpc.close()

        assertEquals(emptyList(), withTimeout(500) { completion.await() })
    }

    private class NotificationConnection : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        override val connectionId: String = "notification-lifecycle"
        override val incoming = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true
        override suspend fun send(text: String) = Unit
        suspend fun notify(method: String, params: JsonObject = JsonObject(emptyMap())) {
            incomingChannel.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }.toString())
        }
        override suspend fun close() { incomingChannel.close() }
    }
}
