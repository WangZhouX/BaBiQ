package com.wzx.huitai.agent.conversation

import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.AgentJsonRpcException
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.JsonRpcError
import com.wzx.huitai.agent.protocol.JsonRpcErrorResponse
import com.wzx.huitai.agent.protocol.JsonRpcSuccessResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BusinessAgentClientTest {
    @Test
    fun `typed requests share task17 correlation and expose safe results`() = runTest {
        val connection = FakeConnection()
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAgentClient(rpc, this)
        connection.responder = { request ->
            when (request.getValue("method").jsonPrimitive.content) {
                "provider/list" -> buildJsonObject {
                    put("providers", ApplicationProtocol.JSON.parseToJsonElement(
                        """[{"id":"p1","displayName":"P1","authMode":"api_key","hasApiKey":true,"active":true,"apiKey":"must-not-escape","models":[{"id":"m1","label":"M1","active":true}]}]""",
                    ))
                }
                "provider/set-active" -> buildJsonObject { put("ok", true); put("providerId", "p1"); put("modelId", "m1") }
                "thread/create" -> buildJsonObject { put("threadId", "thread-1"); put("title", "demo"); put("cwd", "C:/demo") }
                "turn/start" -> buildJsonObject { put("turnId", "turn-1") }
                "turn/cancel" -> buildJsonObject { put("ok", true) }
                else -> error("unexpected method")
            }
        }

        val providers = async { client.listProviders() }
        val selection = async { client.setActiveProvider("p1", "m1") }
        val thread = async { client.createThread("C:/demo") }
        val turn = async { client.startTurn("thread-1", "hello", "p1") }
        val canceled = async { client.cancelTurn("turn-1") }

        assertEquals("p1", providers.await().single().id)
        assertEquals(BusinessProviderSelection("p1", "m1"), selection.await())
        assertEquals(BusinessThread("thread-1", "demo", "C:/demo"), thread.await())
        assertEquals(BusinessTurn("turn-1", "thread-1"), turn.await())
        assertTrue(canceled.await())
        assertEquals(listOf("1", "2", "3", "4", "5"), connection.requestIds())
        assertEquals(
            listOf("provider/list", "provider/set-active", "thread/create", "turn/start", "turn/cancel"),
            connection.methods(),
        )
        client.close()
        rpc.close()
    }

    @Test
    fun `stream notifications preserve thread turn and item correlation`() = runTest {
        val connection = FakeConnection()
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAgentClient(rpc, this)

        val added = async { client.events.first { it is BusinessAgentEvent.ItemAdded } }
        connection.serverNotify("item/added", itemParams("agent-1", "agentMessage", "textDelta", "A"))
        assertEquals(
            BusinessAgentEvent.ItemAdded(
                "thread-1",
                "turn-1",
                BusinessThreadItem.AgentMessage("agent-1", text = null, textDelta = "A"),
            ),
            added.await(),
        )

        val updated = async { client.events.first { it is BusinessAgentEvent.ItemUpdated } }
        connection.serverNotify("item/updated", itemParams("agent-1", "agentMessage", "text", "AB"))
        assertEquals("AB", assertIs<BusinessThreadItem.AgentMessage>(assertIs<BusinessAgentEvent.ItemUpdated>(updated.await()).item).text)

        val completed = async { client.events.first { it is BusinessAgentEvent.ItemCompleted } }
        connection.serverNotify("item/completed", itemParams("agent-1", "agentMessage", "text", "ABC"))
        assertEquals("agent-1", assertIs<BusinessAgentEvent.ItemCompleted>(completed.await()).item.id)

        val terminal = async { client.events.first { it is BusinessAgentEvent.TurnCompleted } }
        connection.serverNotify("turn/completed", buildJsonObject {
            put("threadId", "thread-1"); put("turnId", "turn-1"); put("status", "completed")
        })
        assertEquals(BusinessAgentEvent.TurnCompleted("thread-1", "turn-1", "completed"), terminal.await())

        client.close()
        rpc.close()
    }

    @Test
    fun `unknown notification drops raw parameters and json rpc errors propagate`() = runTest {
        val connection = FakeConnection()
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAgentClient(rpc, this)

        val unknown = async { client.events.first() }
        connection.serverNotify("future/event", buildJsonObject { put("apiKey", "secret") })
        val event = assertIs<BusinessAgentEvent.Unknown>(unknown.await())
        assertEquals("future/event", event.method)
        assertTrue(!event.toString().contains("secret"))

        connection.errorCode = -32044
        val failure = assertFailsWith<AgentJsonRpcException> { client.createThread("C:/demo") }
        assertEquals(-32044, failure.remoteCode)
        assertTrue(!failure.message.orEmpty().contains("remote secret"))

        client.close()
        rpc.close()
    }

    private fun itemParams(id: String, type: String, valueName: String, value: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("item", buildJsonObject { put("id", id); put("type", type); put(valueName, value) })
    }

    private class FakeConnection : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        private val sent = mutableListOf<JsonObject>()
        override val connectionId: String = "connection-1"
        override val incoming = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true
        var responder: (JsonObject) -> JsonObject = { JsonObject(emptyMap()) }
        var errorCode: Int? = null

        override suspend fun send(text: String) {
            val request = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            sent += request
            val id = request.getValue("id").jsonPrimitive.content.toLong()
            val response = errorCode?.let { code ->
                ApplicationProtocol.JSON.encodeToString(
                    JsonRpcErrorResponse.serializer(),
                    JsonRpcErrorResponse(id = id, error = JsonRpcError(code, "remote secret")),
                )
            } ?: ApplicationProtocol.JSON.encodeToString(
                JsonRpcSuccessResponse.serializer(),
                JsonRpcSuccessResponse(id = id, result = responder(request)),
            )
            incomingChannel.send(response)
        }

        suspend fun serverNotify(method: String, params: JsonObject) {
            incomingChannel.send(buildJsonObject {
                put("jsonrpc", "2.0"); put("method", method); put("params", params)
            }.toString())
        }

        fun requestIds() = sent.map { it.getValue("id").jsonPrimitive.content }
        fun methods() = sent.map { it.getValue("method").jsonPrimitive.content }

        override suspend fun close() { incomingChannel.close() }
    }
}
