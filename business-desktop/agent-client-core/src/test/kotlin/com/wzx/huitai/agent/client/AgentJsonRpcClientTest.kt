package com.wzx.huitai.agent.client

import com.wzx.huitai.agent.protocol.ApplicationProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AgentJsonRpcClientTest {
    @Test
    fun `retains only whitelisted attachment code from remote error data`() = runTest {
        val connection = ErrorConnection(
            attachmentCode = "ATTACHMENT_NOT_FOUND",
            remoteMessage = "cannot read C:\\Users\\secret\\contract.pdf",
            extraData = buildJsonObject {
                put("localPath", "C:\\Users\\secret\\contract.pdf")
                put("debug", "remote parser details")
            },
        )
        val client = AgentJsonRpcClient(connection, this, requestTimeoutMillis = 500)

        val failure = assertFailsWith<AgentJsonRpcException> {
            client.request("turn/start", buildJsonObject {})
        }

        assertEquals(-32602, failure.remoteCode)
        assertEquals("ATTACHMENT_NOT_FOUND", failure.attachmentCode)
        assertFalse(failure.message.orEmpty().contains("C:\\Users\\secret"))
        assertFalse(failure.toString().contains("remote parser details"))
        assertNull(failure.cause)
        client.close()
    }

    @Test
    fun `discards unknown attachment code and arbitrary remote message and data`() = runTest {
        val connection = ErrorConnection(
            attachmentCode = "SERVER_SUPPLIED_UNKNOWN_CODE",
            remoteMessage = "C:\\private\\leak.txt",
            extraData = buildJsonObject {
                put("attachmentCode", "SERVER_SUPPLIED_UNKNOWN_CODE")
                put("arbitrary", "sensitive remote body")
            },
        )
        val client = AgentJsonRpcClient(connection, this, requestTimeoutMillis = 500)

        val failure = assertFailsWith<AgentJsonRpcException> {
            client.request("turn/start", buildJsonObject {})
        }

        assertNull(failure.attachmentCode)
        assertFalse(failure.message.orEmpty().contains("C:\\private"))
        assertFalse(failure.toString().contains("sensitive remote body"))
        client.close()
    }

    private class ErrorConnection(
        private val attachmentCode: String,
        private val remoteMessage: String,
        private val extraData: JsonObject,
    ) : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        override val connectionId: String = "attachment-error"
        override val incoming = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true

        override suspend fun send(text: String) {
            val request = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            val id = request.getValue("id").jsonPrimitive.content.toLong()
            incomingChannel.send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject {
                    put("code", -32602)
                    put("message", remoteMessage)
                    put("data", buildJsonObject {
                        extraData.forEach { (key, value) -> put(key, value) }
                        put("attachmentCode", attachmentCode)
                    })
                })
            }.toString())
        }

        override suspend fun close() {
            incomingChannel.close()
        }
    }
}
