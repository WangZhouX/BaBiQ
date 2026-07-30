package com.wzx.huitai.agent.business.auth

import com.wzx.huitai.agent.business.BusinessRpcException
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.JsonRpcError
import com.wzx.huitai.agent.protocol.JsonRpcErrorResponse
import com.wzx.huitai.agent.protocol.JsonRpcSuccessResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BusinessAuthRpcClientTest {
    @Test
    fun `login sends only account password and opaque candidate and returns safe ready projection`() = runTest {
        val connection = FakeConnection { request ->
            assertEquals("business/auth/login", request.getValue("method").jsonPrimitive.content)
            assertEquals(setOf("account", "password", "candidateId"), request.getValue("params").jsonObject.keys)
            buildJsonObject {
                put("authSessionId", "auth-session-7")
                put("status", "READY")
                put("identityEpoch", 7)
                put("generation", 3)
                put("platformId", "101")
                put("user", buildJsonObject { put("id", "user-1"); put("name", "张律师") })
                put("tenant", buildJsonObject { put("id", "tenant-1"); put("name", "律所") })
                put("roles", ApplicationProtocol.JSON.parseToJsonElement("[\"LAWYER\"]"))
                put("permissions", ApplicationProtocol.JSON.parseToJsonElement("[\"workbench:read\"]"))
                put(
                    "menus",
                    ApplicationProtocol.JSON.parseToJsonElement(
                        """[
                            {"kind":"WORKBENCH","path":"/index/unfinished","title":"工作台"},
                            {"kind":"LAW_OA","path":"/lawoa","title":"律所业务"},
                            {"kind":"APPOINTMENT","path":"/appointment","title":"禁止预约别名"},
                            {"kind":"VISIT","path":"/visit","title":"禁止拜访别名"},
                            {"kind":"SCHEDULE","path":"/schedule","title":"禁止日程别名"}
                        ]""",
                    ),
                )
                put("accessToken", "must-not-escape")
                put("refreshToken", "must-not-escape")
                put("secretRef", "must-not-escape")
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAuthRpcClient(rpc)
        val password = "P@ssw0rd!".toCharArray()

        val result = client.login("13800138000", password, "candidate-1")

        assertEquals(BusinessAuthStatus.READY, result.status)
        assertEquals(7, result.identityEpoch)
        assertEquals(3, result.generation)
        assertEquals("101", result.platformId)
        assertEquals("user-1", result.user?.id)
        assertEquals("tenant-1", result.tenant?.id)
        assertEquals(setOf("LAWYER"), result.roles)
        assertEquals(listOf("/", "/lawoa"), result.menus.map { it.path })
        assertTrue(password.all { it == '\u0000' })
        assertFalse(result.toString().contains("must-not-escape"))
        client.close()
        rpc.close()
    }

    @Test
    fun `session get and attach do not accept client identity or credential fields`() = runTest {
        val connection = FakeConnection { request ->
            when (request.getValue("method").jsonPrimitive.content) {
                "business/auth/session/get" -> {
                    assertTrue(request.getValue("params").jsonObject.isEmpty())
                    buildJsonObject { put("status", "DETACHED"); put("identityEpoch", 4); put("generation", 9); put("attachHandle", "opaque-handle") }
                }
                "business/auth/session/attach" -> {
                    assertEquals(setOf("attachHandle"), request.getValue("params").jsonObject.keys)
                    buildJsonObject { put("authSessionId", "auth-session-5"); put("status", "READY"); put("identityEpoch", 5); put("generation", 10) }
                }
                else -> error("unexpected method")
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAuthRpcClient(rpc)

        assertEquals(BusinessAuthStatus.DETACHED, client.session().status)
        assertEquals(BusinessAuthStatus.READY, client.attach("opaque-handle").status)
        assertTrue(connection.sentRequests().all { request ->
            val serialized = request.toString()
            !serialized.contains("accessToken") && !serialized.contains("refreshToken") && !serialized.contains("secretRef") &&
                !serialized.contains("tenantId") && !serialized.contains("userId") && !serialized.contains("desktopSessionId")
        })
        client.close()
        rpc.close()
    }

    @Test
    fun `restore uses the server session restore endpoint`() = runTest {
        val connection = FakeConnection { request ->
            assertEquals("business/auth/session/restore", request.getValue("method").jsonPrimitive.content)
            assertTrue(request.getValue("params").jsonObject.isEmpty())
            buildJsonObject {
                put("status", "READY")
                put("authSessionId", "auth-session-restore")
                put("identityEpoch", 6)
                put("generation", 11)
                put("platformId", "101")
                put("user", buildJsonObject { put("id", "user-restore"); put("name", "寰嬪笀") })
                put("tenant", buildJsonObject { put("id", "tenant-restore"); put("name", "寰嬫墍") })
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val result = BusinessAuthRpcClient(rpc).restore()

        assertEquals(BusinessAuthStatus.READY, result.status)
        assertEquals(6, result.identityEpoch)
        rpc.close()
    }

    @Test
    fun `decodes server flat session projection and permits signed out epoch zero`() = runTest {
        val connection = FakeConnection { request ->
            assertEquals("business/auth/session/get", request.getValue("method").jsonPrimitive.content)
            buildJsonObject {
                put("authSessionId", "auth-session-1")
                put("state", "SIGNED_OUT")
                put("identityEpoch", 0)
                put("generation", 4)
                put("userId", "user-1")
                put("userName", "律师")
                put("tenantId", "tenant-1")
                put("tenantName", "律所")
                put("platformId", "100")
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val result = BusinessAuthRpcClient(rpc).session()

        assertEquals(BusinessAuthStatus.SIGNED_OUT, result.status)
        assertEquals(0, result.identityEpoch)
        assertEquals("auth-session-1", result.authSessionId)
        assertEquals("user-1", result.user?.id)
        assertEquals("tenant-1", result.tenant?.id)
        rpc.close()
    }

    @Test
    fun `stable business error data is decoded without remote message or unknown fields`() = runTest {
        val connection = FakeConnection(
            responder = null,
            error = JsonRpcError(
                code = -32013,
                message = "remote password body must not escape",
                data = buildJsonObject {
                    put("businessCode", "BUSINESS_INVALID_CREDENTIALS")
                    put("retryable", false)
                    put("section", "auth")
                    put("currentSessionState", "SIGNED_OUT")
                    put("correlationId", "corr-1")
                    put("fieldErrors", buildJsonObject { put("account", "invalid") })
                    put("accessToken", "secret-marker")
                    put("remoteBody", "secret-marker")
                },
            ),
        )
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAuthRpcClient(rpc)

        val failure = assertFailsWith<BusinessRpcException> { client.session() }

        assertEquals(-32013, failure.remoteCode)
        assertEquals("BUSINESS_INVALID_CREDENTIALS", failure.businessCode)
        assertEquals(false, failure.retryable)
        assertEquals(mapOf("account" to "invalid"), failure.fieldErrors)
        assertEquals("auth", failure.section)
        assertEquals("SIGNED_OUT", failure.currentSessionState)
        assertEquals("corr-1", failure.correlationId)
        assertFalse(failure.toString().contains("remote password body"))
        assertFalse(failure.toString().contains("secret-marker"))
        client.close()
        rpc.close()
    }

    @Test
    fun `tenant candidates accept only account and map unknown status safely`() = runTest {
        val connection = FakeConnection { request ->
            assertEquals("business/auth/tenant-candidates", request.getValue("method").jsonPrimitive.content)
            assertEquals(setOf("account"), request.getValue("params").jsonObject.keys)
            buildJsonObject {
                put("candidates", ApplicationProtocol.JSON.parseToJsonElement("[{\"candidateId\":\"c-1\",\"name\":\"律所\",\"status\":\"FUTURE\"}]"))
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessAuthRpcClient(rpc)
        val candidates = client.tenantCandidates("13800138000")

        assertEquals(1, candidates.size)
        assertEquals("c-1", candidates.single().candidateId)
        assertEquals("律所", candidates.single().name)
        assertEquals("FUTURE", candidates.single().status)
        assertFalse(candidates.single().toString().contains("accessToken"))
        client.close()
        rpc.close()
    }

    private class FakeConnection(
        private val responder: ((JsonObject) -> JsonObject)?,
        private val error: JsonRpcError? = null,
    ) : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        private val sent = mutableListOf<JsonObject>()
        override val connectionId: String = "business-auth-test"
        override val incoming = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true

        constructor(responder: (JsonObject) -> JsonObject) : this(responder, null)

        override suspend fun send(text: String) {
            val request = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            sent += request
            val id = request.getValue("id").jsonPrimitive.content.toLong()
            val encoded = error?.let {
                ApplicationProtocol.JSON.encodeToString(JsonRpcErrorResponse.serializer(), JsonRpcErrorResponse(id = id, error = it))
            } ?: ApplicationProtocol.JSON.encodeToString(
                JsonRpcSuccessResponse.serializer(),
                JsonRpcSuccessResponse(id = id, result = requireNotNull(responder).invoke(request)),
            )
            incomingChannel.send(encoded)
        }

        fun sentRequests(): List<JsonObject> = sent.toList()

        override suspend fun close() { incomingChannel.close() }
    }
}
