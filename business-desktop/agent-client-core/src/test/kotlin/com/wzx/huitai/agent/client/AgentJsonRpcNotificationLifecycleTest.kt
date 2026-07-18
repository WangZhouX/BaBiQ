package com.wzx.huitai.agent.client

import com.wzx.huitai.agent.conversation.BusinessAgentClient
import com.wzx.huitai.agent.conversation.BusinessAgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `notification overflow explicitly closes the connection instead of silently dropping`() = runTest {
        val connection = NotificationConnection()
        val rpc = AgentJsonRpcClient(connection, this, inboundCapacity = 1)
        connection.notify("future/one")
        connection.notify("future/two")
        runCurrent()

        val client = BusinessAgentClient(rpc, this)
        try {
            val events = withTimeout(500) { client.events.toList() }
            assertEquals(listOf("future/one"), events.map { assertIs<BusinessAgentEvent.Unknown>(it).method })
            assertTrue(connection.closeCount > 0)
            assertIs<AgentConnectionState.Closed>(connection.state.value)
        } finally {
            client.close()
            rpc.close()
        }
    }

    @Test
    fun `full typed notification path cannot leak a blocked pump after rpc close`() = runTest {
        val connection = NotificationConnection()
        val rpc = AgentJsonRpcClient(connection, this, inboundCapacity = 1)
        val clientJob = SupervisorJob()
        val clientScope = CoroutineScope(coroutineContext + clientJob)
        BusinessAgentClient(rpc, clientScope)

        try {
            repeat(67) { connection.notify("future/$it") }
            runCurrent()
            rpc.close()

            withTimeout(500) {
                while (clientJob.children.any { it.isActive }) yield()
            }
        } finally {
            rpc.close()
            clientJob.cancel()
        }
    }

    @Test
    fun `notification overload fails a pending response closed instead of timing out`() = runTest {
        val connection = NotificationConnection(burstOnRequest = 67)
        val rpc = AgentJsonRpcClient(connection, this, requestTimeoutMillis = 500, inboundCapacity = 1)
        val client = BusinessAgentClient(rpc, this)

        try {
            assertFailsWith<AgentJsonRpcClosedException> {
                rpc.request("thread/create", buildJsonObject { })
            }
            withTimeout(500) { connection.state.first { it is AgentConnectionState.Closed } }
            assertTrue(connection.closeCount > 0)
        } finally {
            client.close()
            rpc.close()
        }
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

    private class NotificationConnection(
        private val burstOnRequest: Int = 0,
    ) : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        override val connectionId: String = "notification-lifecycle"
        override val incoming = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true
        var closeCount: Int = 0
        override suspend fun send(text: String) {
            if (burstOnRequest == 0) return
            val request = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            repeat(burstOnRequest) { notify("future/burst-$it") }
            incomingChannel.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", request.getValue("id").jsonPrimitive.content.toLong())
                put("result", buildJsonObject { put("ok", true) })
            }.toString())
        }
        suspend fun notify(method: String, params: JsonObject = JsonObject(emptyMap())) {
            incomingChannel.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }.toString())
        }
        override suspend fun close() {
            closeCount += 1
            state.value = AgentConnectionState.Closed(code = null, reasonPresent = false)
            incomingChannel.close()
        }
    }
}
