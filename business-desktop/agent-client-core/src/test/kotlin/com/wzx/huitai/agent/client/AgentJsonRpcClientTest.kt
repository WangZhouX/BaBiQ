package com.wzx.huitai.agent.client

import com.wzx.huitai.agent.protocol.ApplicationProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class AgentJsonRpcClientTest {
    @Test
    fun `retains only whitelisted attachment code from remote error data`() = runTest {
        val connection = ErrorConnection(
            remoteMessage = "cannot read C:\\Users\\secret\\contract.pdf",
            errorData = buildJsonObject {
                put("localPath", "C:\\Users\\secret\\contract.pdf")
                put("debug", "remote parser details")
                put("attachmentCode", "ATTACHMENT_NOT_FOUND")
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
            remoteMessage = "C:\\private\\leak.txt",
            errorData = buildJsonObject {
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

    @Test
    fun `discards nested non-string attachment code and extra error fields`() = runTest {
        val connection = ErrorConnection(
            remoteMessage = "raw C:\\private\\nested-secret.txt",
            errorData = buildJsonObject {
                put("attachmentCode", buildJsonObject {
                    put("nested", "ATTACHMENT_NOT_FOUND")
                    put("localPath", "C:\\private\\nested-secret.txt")
                })
                put("extra", buildJsonObject {
                    put("debug", "sensitive remote body")
                })
            },
        )
        val client = AgentJsonRpcClient(connection, this, requestTimeoutMillis = 500)

        val failure = assertFailsWith<AgentJsonRpcException> {
            client.request("turn/start", buildJsonObject {})
        }

        assertEquals(-32602, failure.remoteCode)
        assertNull(failure.attachmentCode)
        assertFalse(failure.toString().contains("nested-secret"))
        assertFalse(failure.toString().contains("sensitive remote body"))
        assertEquals(0, client.pendingRequestCount)
        client.close()
    }

    @Test
    fun `heterogeneous error data correlates immediately and always cleans pending requests`() = runTest {
        val cases = listOf(
            "string" to JsonPrimitive("C:\\private\\string-secret.txt"),
            "number" to JsonPrimitive(42),
            "array" to buildJsonArray {
                add(buildJsonObject {
                    put("attachmentCode", "ATTACHMENT_NOT_FOUND")
                    put("localPath", "C:\\private\\array-secret.txt")
                })
            },
            "null" to JsonNull,
        )

        cases.forEach { (label, data) ->
            val client = AgentJsonRpcClient(
                ErrorConnection(
                    remoteMessage = "raw-$label C:\\private\\message-secret.txt",
                    errorData = data,
                ),
                this,
                requestTimeoutMillis = 500,
            )
            val startedAt = currentTime

            val failure = assertFailsWith<AgentJsonRpcException>(label) {
                client.request("turn/start", buildJsonObject {})
            }

            assertEquals(-32602, failure.remoteCode, label)
            assertNull(failure.attachmentCode, label)
            assertFalse(failure.toString().contains("private"), label)
            assertEquals(0, client.pendingRequestCount, label)
            assertTrue(currentTime - startedAt < 500, "$label must correlate before timeout")
            client.close()
        }
    }

    private class ErrorConnection(
        private val remoteMessage: String,
        private val errorData: JsonElement,
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
                    put("data", errorData)
                })
            }.toString())
        }

        override suspend fun close() {
            incomingChannel.close()
        }
    }
}
