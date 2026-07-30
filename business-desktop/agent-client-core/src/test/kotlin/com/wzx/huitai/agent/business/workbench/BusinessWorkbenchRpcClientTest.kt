package com.wzx.huitai.agent.business.workbench

import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.JsonRpcSuccessResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BusinessWorkbenchRpcClientTest {
    @Test
    fun `page sends typed scope request without tenant or remote routing fields`() = runTest {
        val connection = FakeConnection { request ->
            assertEquals("business/workbench/page/get", request.getValue("method").jsonPrimitive.content)
            assertEquals(
                setOf("kind", "scope", "teamId", "roleCode", "pageNo", "pageSize", "filters"),
                request.getValue("params").jsonObject.keys,
            )
            buildJsonObject {
                put("identityEpoch", 11)
                put("generation", 12)
                put("total", 1)
                put("pageNo", 2)
                put("pageSize", 20)
                put("items", ApplicationProtocol.JSON.parseToJsonElement("[{\"id\":\"case-1\",\"applicationNumber\":\"A-1\",\"categoriesName\":\"民事\",\"title\":\"案件\",\"values\":{\"status\":\"OPEN\"}}]"))
                put("accessToken", "must-not-escape")
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessWorkbenchRpcClient(rpc)

        val page = client.page(
            BusinessWorkbenchPageRequest(
                kind = BusinessWorkbenchKind.CASE,
                scope = BusinessWorkbenchScope.TEAM,
                teamId = "team-1",
                roleCode = "OWNER",
                pageNo = 2,
                pageSize = 20,
                filters = mapOf("status" to "1"),
            ),
        )

        assertEquals(11, page.identityEpoch)
        assertEquals(12, page.generation)
        assertEquals("case-1", page.items.single().id)
        assertEquals("A-1", page.items.single().applicationNumber)
        assertFalse(page.toString().contains("must-not-escape"))
        client.close()
        rpc.close()
    }

    @Test
    fun `page decodes identity envelope returned by backend`() = runTest {
        val connection = FakeConnection {
            buildJsonObject {
                put("identityEpoch", 21)
                put("generation", 4)
                put("page", buildJsonObject {
                    put("total", 1)
                    put("pageNo", 1)
                    put("pageSize", 20)
                    put("items", ApplicationProtocol.JSON.parseToJsonElement("""[{"id":"case-1","title":"合同纠纷"}]"""))
                })
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessWorkbenchRpcClient(rpc)

        val page = client.page(BusinessWorkbenchPageRequest(BusinessWorkbenchKind.CASE))

        assertEquals(21, page.identityEpoch)
        assertEquals(4, page.generation)
        assertEquals("case-1", page.items.single().id)
        client.close()
        rpc.close()
    }

    @Test
    fun `page rejects unknown scope and forbidden authority fields before sending`() = runTest {
        val connection = FakeConnection { error("request must not be sent") }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessWorkbenchRpcClient(rpc)

        assertFailsWith<IllegalArgumentException> {
            client.page(BusinessWorkbenchPageRequest(BusinessWorkbenchKind.CASE, BusinessWorkbenchScope.TEAM))
        }
        assertFailsWith<IllegalArgumentException> {
            client.page(
                BusinessWorkbenchPageRequest(
                    kind = BusinessWorkbenchKind.CASE,
                    scope = BusinessWorkbenchScope.ALL,
                    filters = mapOf("tenantId" to "tenant-secret"),
                ),
            )
        }
        assertTrue(connection.sentRequests().isEmpty())
        client.close()
        rpc.close()
    }

    @Test
    fun `snapshot decodes epoch and drops non protocol secret fields from sections`() = runTest {
        val connection = FakeConnection { request ->
            assertEquals("business/workbench/get", request.getValue("method").jsonPrimitive.content)
            assertTrue(request.getValue("params").jsonObject.isEmpty())
            buildJsonObject {
                put("identityEpoch", 8)
                put("generation", 9)
                put("generation", 9)
                put("snapshot", buildJsonObject {
                    put("notices", buildJsonObject {
                        put("status", "OK")
                        put("data", buildJsonObject { put("title", "公告"); put("accessToken", "secret") })
                    })
                    put("issues", ApplicationProtocol.JSON.parseToJsonElement("[\"schedule\"]"))
                })
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessWorkbenchRpcClient(rpc)

        val snapshot = client.get()

        assertEquals(8, snapshot.identityEpoch)
        assertEquals(9, snapshot.generation)
        assertEquals(BusinessWorkbenchSectionStatus.OK, snapshot.notices.status)
        assertEquals("公告", snapshot.notices.data?.jsonObject?.get("title")?.jsonPrimitive?.content)
        assertFalse(snapshot.toString().contains("secret"))
        assertEquals(listOf("schedule"), snapshot.issues)
        client.close()
        rpc.close()
    }

    @Test
    fun `navigation keeps only fixed local allowlist targets`() = runTest {
        val connection = FakeConnection { request ->
            assertEquals("business/workbench/navigation/get", request.getValue("method").jsonPrimitive.content)
            buildJsonObject {
                put("identityEpoch", 8)
                put("generation", 9)
                put(
                    "items",
                    ApplicationProtocol.JSON.parseToJsonElement(
                        """[
                            {"kind":"WORKBENCH","path":"/index","title":"首页"},
                            {"kind":"LAW_OA","path":"/lawoa","title":"律所业务"},
                            {"kind":"CASE","path":"/case","title":"案件"},
                            {"kind":"APPOINTMENT","path":"/appointment","title":"越权预约别名"},
                            {"kind":"VISIT","path":"/visit","title":"越权拜访别名"},
                            {"kind":"SCHEDULE","path":"/schedule","title":"越权日程别名"},
                            {"kind":"REMOTE","path":"https://evil.example","title":"外链"}
                        ]""",
                    ),
                )
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessWorkbenchRpcClient(rpc)

        val navigation = client.navigation()

        assertEquals(8, navigation.identityEpoch)
        assertEquals(9, navigation.generation)
        assertEquals(listOf("/", "/lawoa", "/case"), navigation.items.map { it.path })
        assertEquals(8, client.lastIdentityEpoch)
        client.close()
        rpc.close()
    }

    @Test
    fun `team roles uses typed kind and rejects malformed envelope`() = runTest {
        val connection = FakeConnection { request ->
            assertEquals("business/workbench/team-roles/list", request.getValue("method").jsonPrimitive.content)
            assertEquals(
                mapOf("teamId" to "team-1", "kind" to "VISIT"),
                request.getValue("params").jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
            buildJsonObject {
                put("identityEpoch", 17)
                put("generation", 4)
                put("items", ApplicationProtocol.JSON.parseToJsonElement("""[{"roleCode":"OWNER","name":"负责人"}]"""))
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessWorkbenchRpcClient(rpc)

        val result = client.teamRoles("team-1", BusinessWorkbenchKind.VISIT)

        assertEquals(17, result.identityEpoch)
        assertEquals(4, result.generation)
        assertEquals("OWNER", result.items.single().roleCode)
        assertEquals("负责人", result.items.single().name)
        assertFailsWith<IllegalArgumentException> { client.teamRoles("", BusinessWorkbenchKind.CASE) }
        client.close()
        rpc.close()
    }

    @Test
    fun `sort update sends enum ids and revision then adopts canonical response`() = runTest {
        val connection = FakeConnection { request ->
            assertEquals("business/workbench/sort/update", request.getValue("method").jsonPrimitive.content)
            val params = request.getValue("params").jsonObject
            assertEquals("SUMMARY", params.getValue("kind").jsonPrimitive.content)
            assertEquals(listOf("summary-2", "summary-1"), params.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("7", params.getValue("expectedRevision").jsonPrimitive.content)
            buildJsonObject {
                put("identityEpoch", 19)
                put("generation", 5)
                put("revision", 8)
                put("refreshRequired", false)
                put("ids", ApplicationProtocol.JSON.parseToJsonElement("""["summary-1","summary-2"]"""))
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessWorkbenchRpcClient(rpc)

        val result = client.updateSort(
            BusinessWorkbenchSortRequest(
                kind = BusinessWorkbenchSortKind.SUMMARY,
                ids = listOf("summary-2", "summary-1"),
                expectedRevision = 7,
            ),
        )

        assertEquals(19, result.identityEpoch)
        assertEquals(5, result.generation)
        assertEquals(8, result.revision)
        assertEquals(listOf("summary-1", "summary-2"), result.canonicalIds)
        assertFailsWith<IllegalArgumentException> {
            client.updateSort(BusinessWorkbenchSortRequest(BusinessWorkbenchSortKind.SHORTCUT, listOf("same", "same"), 0))
        }
        client.close()
        rpc.close()
    }

    @Test
    fun `refresh required sort response never fabricates request ids as canonical ids`() = runTest {
        val connection = FakeConnection {
            buildJsonObject {
                put("identityEpoch", 19)
                put("generation", 5)
                put("revision", 8)
                put("refreshRequired", true)
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessWorkbenchRpcClient(rpc)

        val result = client.updateSort(
            BusinessWorkbenchSortRequest(
                BusinessWorkbenchSortKind.SHORTCUT,
                listOf("shortcut-2", "shortcut-1"),
                7,
            ),
        )

        assertNull(result.canonicalIds)
        client.close()
        rpc.close()
    }

    private class FakeConnection(private val responder: (JsonObject) -> JsonObject) : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        private val sent = mutableListOf<JsonObject>()
        override val connectionId: String = "business-workbench-test"
        override val incoming = incomingChannel
        override val state = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connected)
        override val hasConnected: Boolean = true

        override suspend fun send(text: String) {
            val request = ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject
            sent += request
            val id = request.getValue("id").jsonPrimitive.content.toLong()
            incomingChannel.send(
                ApplicationProtocol.JSON.encodeToString(
                    JsonRpcSuccessResponse.serializer(),
                    JsonRpcSuccessResponse(id = id, result = responder(request)),
                ),
            )
        }

        fun sentRequests(): List<JsonObject> = sent.toList()

        override suspend fun close() { incomingChannel.close() }
    }
}
