package com.wzx.huitai.agent.client

import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AgentJsonRpcNumericCompatibilityTest {
    @Test
    fun `outgoing request id is numeric and numeric success and error correlate`() = runTest {
        val connection = NumericBackendConnection()
        val client = AgentJsonRpcClient(connection, this, requestTimeoutMillis = 500)

        connection.nextResult = buildJsonObject { put("ok", true) }
        assertEquals(true, client.request("thread/create", buildJsonObject { })["ok"]?.jsonPrimitive?.content?.toBoolean())
        assertFalse(connection.sent.single().getValue("id").jsonPrimitive.isString)

        connection.errorCode = -32044
        assertEquals(-32044, assertFailsWith<AgentJsonRpcException> {
            client.request("thread/create", buildJsonObject { })
        }.remoteCode)
        client.close()
    }

    @Test
    fun `numeric inbound application request and response preserve the same numeric id`() = runTest {
        val connection = NumericBackendConnection(autoRespond = false)
        val client = AgentJsonRpcClient(connection, this)
        connection.serverSend(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 41)
            put("method", ApplicationMethod.ACTION_REQUEST.wireName)
            put("params", actionEnvelope())
        }.toString())

        val inbound = assertIs<AgentJsonRpcInbound.Request>(client.incoming.receive())
        assertEquals(41L, inbound.value.id)
        client.respondSuccess(inbound.value.id, buildJsonObject { put("accepted", true) })
        assertFalse(connection.sent.single().getValue("id").jsonPrimitive.isString)
        assertEquals(41L, connection.sent.single().getValue("id").jsonPrimitive.content.toLong())
        client.close()
    }

    private fun actionEnvelope() = buildJsonObject {
        put("protocolVersion", "1.0")
        put("desktopInstanceId", "desktop-1")
        put("desktopSessionId", "desktop-session-1")
        put("authSessionId", "auth-1")
        put("identityEpoch", 1)
        put("sequence", 1)
        put("generatedAt", "2026-07-18T00:00:00Z")
        put("userId", "user-1")
        put("tenantId", "tenant-1")
        put("platformId", "platform-1")
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("toolCallId", "tool-1")
        put("executionId", "execution-1")
        put("payload", buildJsonObject { })
    }

    private class NumericBackendConnection(
        private val autoRespond: Boolean = true,
    ) : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        override val connectionId: String = "numeric-backend"
        override val incoming = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true
        val sent = mutableListOf<JsonObject>()
        var nextResult: JsonObject = JsonObject(emptyMap())
        var errorCode: Int? = null

        override suspend fun send(text: String) {
            val request = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            sent += request
            if (!autoRespond || "id" !in request) return
            val id = request.getValue("id").jsonPrimitive.content.toLong()
            val response = errorCode?.let { code ->
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("error", buildJsonObject { put("code", code); put("message", "remote secret") })
                }
            } ?: buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", nextResult)
            }
            incomingChannel.send(response.toString())
        }

        suspend fun serverSend(text: String) { incomingChannel.send(text) }
        override suspend fun close() { incomingChannel.close() }
    }
}
