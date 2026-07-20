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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
                        """[{"id":"p1","displayName":"P1","type":"OPENAI_COMPATIBLE","authMode":"api_key","baseUrl":"https://relay.example.com/v1","model":"m1","contextWindow":64000,"enabled":true,"hasApiKey":true,"active":true,"apiKey":"must-not-escape","models":[{"id":"m1","label":"M1","active":true}]}]""",
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
    fun `attachment only turn sends metadata without desktop only fields or file content`() = runTest {
        val connection = FakeConnection().apply {
            responder = { request ->
                assertEquals("turn/start", request.getValue("method").jsonPrimitive.content)
                buildJsonObject { put("turnId", "turn-attachment-1") }
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAgentClient(rpc, this)
        val localPath = "C:\\private\\contracts\\contract.pdf"
        val draft = BusinessAttachmentDraft(
            id = "5e4d4e7a-7dd6-4c6e-bec4-bd6f92ec9123",
            displayId = "A-7K3M2Q",
            name = "contract.pdf",
            localPath = localPath,
            sizeBytes = 1_024,
            displayType = "PDF",
        )

        val turn = client.startTurn(
            threadId = "thread-1",
            text = "",
            attachments = listOf(draft),
            providerId = "provider-1",
        )

        assertEquals(BusinessTurn("turn-attachment-1", "thread-1"), turn)
        val input = connection.params().single().getValue("input").jsonObject
        assertEquals("", input.getValue("text").jsonPrimitive.content)
        val attachment = input.getValue("attachments").jsonArray.single().jsonObject
        assertEquals(setOf("id", "displayId", "name", "localPath"), attachment.keys)
        assertEquals(draft.id, attachment.getValue("id").jsonPrimitive.content)
        assertEquals(draft.displayId, attachment.getValue("displayId").jsonPrimitive.content)
        assertEquals(draft.name, attachment.getValue("name").jsonPrimitive.content)
        assertEquals(localPath, attachment.getValue("localPath").jsonPrimitive.content)
        val serializedRequest = connection.sentRequests().single().toString()
        assertFalse(serializedRequest.contains("sizeBytes"))
        assertFalse(serializedRequest.contains("displayType"))
        assertFalse(serializedRequest.contains("base64", ignoreCase = true))
        assertFalse(serializedRequest.contains("data:", ignoreCase = true))
        client.close()
        rpc.close()
    }

    @Test
    fun `turn rejects blank text only when attachments are empty`() = runTest {
        val connection = FakeConnection()
        val rpc = AgentJsonRpcClient(connection, this)
        val client: BusinessConversationGateway = BusinessAgentClient(rpc, this)

        val failure = assertFailsWith<IllegalArgumentException> {
            client.startTurn("thread-1", " \t", emptyList(), "provider-1")
        }

        assertEquals("turn text and attachments must not both be blank", failure.message)
        assertTrue(connection.methods().isEmpty())
        client.close()
        rpc.close()
    }

    @Test
    fun `attachment models redact local paths from string representations`() {
        val localPath = "C:\\private\\contracts\\contract.pdf"
        val draft = BusinessAttachmentDraft(
            id = "5e4d4e7a-7dd6-4c6e-bec4-bd6f92ec9123",
            displayId = "A-7K3M2Q",
            name = "contract.pdf",
            localPath = localPath,
            sizeBytes = 1_024,
            displayType = "PDF",
        )
        val message = BusinessMessageAttachment(
            id = draft.id,
            displayId = draft.displayId,
            name = draft.name,
            mediaType = "application/pdf",
            sizeBytes = draft.sizeBytes,
            sha256 = "a".repeat(64),
            source = "SELECTED_FILE",
            localPath = localPath,
        )

        assertFalse(draft.toString().contains(localPath))
        assertFalse(message.toString().contains(localPath))
        assertTrue(draft.toString().contains("[REDACTED]"))
        assertTrue(message.toString().contains("[REDACTED]"))
    }

    @Test
    fun `provider settings requests preserve the key only in create and update params`() = runTest {
        val marker = "sk-fake-sensitive-marker"
        val connection = FakeConnection()
        val rpc = AgentJsonRpcClient(connection, this)
        val client: BusinessConversationGateway = BusinessAgentClient(rpc, this)
        connection.responder = { request ->
            when (request.getValue("method").jsonPrimitive.content) {
                "provider/create", "provider/update" -> providerPayload()
                "provider/delete" -> buildJsonObject {
                    put("ok", true)
                    put("providerId", "relay")
                    put("activeProviderId", "fallback")
                }
                "provider/test" -> buildJsonObject {
                    put("ok", true)
                    put("providerId", "relay")
                    put("message", "Provider 配置可用")
                }
                "provider/oauth/status" -> buildJsonObject {
                    put("providerType", "ANTHROPIC")
                    put("authMode", "oauth_cli")
                    put("cliInstalled", true)
                    put("loggedIn", false)
                    put("message", "未登录")
                }
                "provider/oauth/login" -> buildJsonObject {
                    put("ok", true)
                    put("pid", 12345L)
                    put("message", "登录已启动")
                }
                else -> error("unexpected method")
            }
        }
        val draft = BusinessProviderDraft(
            providerId = "relay",
            displayName = "Relay",
            type = "OPENAI_COMPATIBLE",
            authMode = "api_key",
            baseUrl = "https://relay.example.com/v1",
            model = "kimi-k3",
            apiKey = marker,
            contextWindow = 131072,
            enabled = true,
        )

        val created = client.createProvider(draft)
        val updated = client.updateProvider(draft)
        val deleted = client.deleteProvider("relay")
        val tested = client.testProvider("relay")
        val oauthStatus = client.providerOAuthStatus()
        val oauthLogin = client.loginProviderOAuth()

        assertEquals(
            listOf(
                "provider/create",
                "provider/update",
                "provider/delete",
                "provider/test",
                "provider/oauth/status",
                "provider/oauth/login",
            ),
            connection.methods(),
        )
        assertEquals(marker, connection.params(0).getValue("apiKey").jsonPrimitive.content)
        assertEquals(marker, connection.params(1).getValue("apiKey").jsonPrimitive.content)
        assertEquals("Relay", connection.params(0).getValue("displayName").jsonPrimitive.content)
        assertEquals("OPENAI_COMPATIBLE", connection.params(0).getValue("type").jsonPrimitive.content)
        assertEquals("kimi-k3", connection.params(0).getValue("model").jsonPrimitive.content)
        assertEquals("kimi-k3", connection.params(1).getValue("model").jsonPrimitive.content)
        assertEquals("131072", connection.params(0).getValue("contextWindow").jsonPrimitive.content)
        assertEquals("true", connection.params(0).getValue("enabled").jsonPrimitive.content)
        connection.params().take(2).forEach { params ->
            assertEquals(
                setOf(
                    "providerId",
                    "displayName",
                    "type",
                    "authMode",
                    "baseUrl",
                    "model",
                    "apiKey",
                    "contextWindow",
                    "enabled",
                ),
                params.keys,
            )
        }
        connection.params().slice(2..3).forEach { params ->
            assertEquals(setOf("providerId"), params.keys)
        }
        connection.params().drop(4).forEach { params ->
            assertTrue(params.isEmpty())
        }
        assertEquals("relay", connection.params(2).getValue("providerId").jsonPrimitive.content)
        assertEquals("relay", connection.params(3).getValue("providerId").jsonPrimitive.content)

        assertEquals("kimi-k3", created.model)
        assertEquals("kimi-k3", updated.model)
        assertEquals("fallback", deleted.activeProviderId)
        assertEquals("Provider 配置可用", tested.message)
        assertEquals("未登录", oauthStatus.message)
        assertEquals("登录已启动", oauthLogin.message)
        assertNull(created::class.members.singleOrNull { it.name == "apiKey" })
        assertNull(updated::class.members.singleOrNull { it.name == "apiKey" })
        listOf(created, updated, deleted, tested, oauthStatus, oauthLogin).forEach { result ->
            assertNull(result::class.members.singleOrNull { it.name == "apiKey" })
            assertFalse(result.toString().contains(marker))
        }
        assertEquals(
            "BusinessProviderDraft(providerId=relay, model=kimi-k3, apiKey=[REDACTED])",
            draft.toString(),
        )
        assertFalse(draft.toString().contains(marker))
        val copiedDraft = draft.copy(apiKey = "$marker-rotated")
        assertEquals(draft.toString(), copiedDraft.toString())
        assertFalse(copiedDraft.toString().contains(marker))

        client.close()
        rpc.close()
    }

    @Test
    fun `provider drafts reject invalid request invariants without leaking the key`() = runTest {
        val marker = "sk-fake-sensitive-marker"
        val connection = FakeConnection().apply { responder = { providerPayload() } }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAgentClient(rpc, this)
        val valid = BusinessProviderDraft(
            providerId = "relay",
            displayName = "Relay",
            type = "OPENAI_COMPATIBLE",
            model = "kimi-k3",
            apiKey = marker,
        )
        val invalidDrafts = listOf(
            valid.copy(displayName = " "),
            valid.copy(type = ""),
            valid.copy(contextWindow = -1),
            valid.copy(enabled = false),
        )

        invalidDrafts.forEach { invalid ->
            val createFailure = assertFailsWith<IllegalArgumentException> {
                client.createProvider(invalid)
            }
            val updateFailure = assertFailsWith<IllegalArgumentException> {
                client.updateProvider(invalid)
            }
            assertFalse(createFailure.toString().contains(marker))
            assertFalse(updateFailure.toString().contains(marker))
        }
        assertTrue(connection.methods().isEmpty())

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
        val failure = assertFailsWith<AgentJsonRpcException> {
            client.createProvider(
                BusinessProviderDraft(
                    providerId = "relay",
                    displayName = "Relay",
                    type = "OPENAI_COMPATIBLE",
                    model = "kimi-k3",
                    apiKey = "sk-fake-sensitive-marker",
                ),
            )
        }
        assertEquals(-32044, failure.remoteCode)
        assertFalse(failure.toString().contains("sk-fake-sensitive-marker"))

        client.close()
        rpc.close()
    }

    private fun itemParams(id: String, type: String, valueName: String, value: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("item", buildJsonObject { put("id", id); put("type", type); put(valueName, value) })
    }

    private fun providerPayload() = buildJsonObject {
        put("id", "relay")
        put("displayName", "Relay")
        put("type", "OPENAI_COMPATIBLE")
        put("authMode", "api_key")
        put("baseUrl", "https://relay.example.com/v1")
        put("model", "kimi-k3")
        put("contextWindow", 131072)
        put("enabled", true)
        put("hasApiKey", true)
        put("active", false)
        put("apiKey", "must-not-escape")
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
                    JsonRpcErrorResponse(id = id, error = JsonRpcError(code, "sk-fake-sensitive-marker")),
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
        fun params() = sent.map { it.getValue("params").jsonObject }
        fun params(index: Int) = params()[index]
        fun sentRequests() = sent.toList()

        override suspend fun close() { incomingChannel.close() }
    }
}
