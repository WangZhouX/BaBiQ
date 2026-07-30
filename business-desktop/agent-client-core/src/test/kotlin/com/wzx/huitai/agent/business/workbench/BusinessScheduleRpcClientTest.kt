package com.wzx.huitai.agent.business.workbench

import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.JsonRpcSuccessResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BusinessScheduleRpcClientTest {
    @Test
    fun `all eight schedule and attachment methods use typed bounded params and preserve epoch generation`() = runTest {
        val connection = FakeConnection(::response)
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessScheduleRpcClient(rpc)

        assertEquals(7, client.month(BusinessScheduleQuery("2026-07", BusinessWorkbenchScope.TEAM, "team-1", true)).identityEpoch)
        assertEquals(9, client.day(BusinessScheduleQuery("2026-07-29", BusinessWorkbenchScope.TEAM, "team-1", true)).generation)
        assertEquals(7, client.setCompletion("schedule-1", true).identityEpoch)
        assertEquals(9, client.form(BusinessWorkbenchScope.TEAM, "team-1").generation)
        assertEquals(7, client.relationOptions("CASE", "合同", "team-1", null).identityEpoch)
        assertEquals(9, client.serviceProjects("service-1", "项目", "team-1").generation)
        assertEquals(
            7,
            client.create(
                BusinessScheduleCreateRequest(
                    clientOperationId = "operation-1",
                    scope = BusinessWorkbenchScope.TEAM,
                    teamId = "team-1",
                    assigneeUserId = "user-1",
                    title = "客户会议",
                    typeId = "type-1",
                    at = "2026-07-29 10:00:00",
                    allDay = false,
                    priority = 2,
                    description = "讨论案件",
                    reminderMinutes = listOf(10, 45),
                    relations = listOf(
                        BusinessScheduleRelation("SERVICE", "project-1", "合同审查", "record-1"),
                    ),
                    attachmentBatchId = "batch-1",
                    attachmentParentResourceId = "project-1",
                    attachmentParentRelationType = "SERVICE",
                    formRevision = 3,
                    repetition = 0,
                ),
            ).identityEpoch,
        )
        val prepared = client.prepareAttachment(
            BusinessAttachmentPrepareRequest.validForTest().copy(
                parentRelationType = "SERVICE",
                parentResourceId = "project-1",
                parentRecordId = "record-1",
                files = listOf(BusinessAttachmentFile("evidence.pdf", 8, "application/pdf", "abc")),
            ),
        )
        assertEquals(9, prepared.generation)

        val requests = connection.sentRequests()
        assertEquals(
            listOf(
                "business/schedule/month/get",
                "business/schedule/day/get",
                "business/schedule/completion/set",
                "business/schedule/form/get",
                "business/schedule/relation-options/get",
                "business/schedule/service-projects/get",
                "business/schedule/create",
                "business/attachments/upload/prepare",
            ),
            requests.map { it.getValue("method").jsonPrimitive.content },
        )
        requests.forEach { request ->
            val serialized = request.getValue("params").toString()
            listOf("tenantId", "accessToken", "refreshToken", "fileIds", "Authorization")
                .forEach { forbidden -> assertFalse(serialized.contains(forbidden, ignoreCase = true)) }
        }
        assertEquals(
            setOf("date", "scope", "teamId", "onlyMine"),
            requests[0].getValue("params").jsonObject.keys,
        )
        assertEquals(
            setOf("scheduleId", "completed"),
            requests[2].getValue("params").jsonObject.keys,
        )
        val createRelation = requests[6].getValue("params").jsonObject
            .getValue("relations").jsonArray.single().jsonObject
        assertEquals(setOf("relationType", "relationId", "relationTitle", "parentId"), createRelation.keys)
        assertEquals("project-1", createRelation.getValue("relationId").jsonPrimitive.content)
        assertEquals("合同审查", createRelation.getValue("relationTitle").jsonPrimitive.content)
        assertEquals("record-1", createRelation.getValue("parentId").jsonPrimitive.content)
        assertEquals(
            setOf("recordId", "keyword", "teamId"),
            requests[5].getValue("params").jsonObject.keys,
        )
        assertEquals("team-1", requests[5].getValue("params").jsonObject.getValue("teamId").jsonPrimitive.content)
        assertEquals(
            setOf(
                "operation", "clientOperationId", "scope", "typeId", "parentRelationType",
                "parentResourceId", "parentRecordId", "formRevision", "files",
            ),
            requests[7].getValue("params").jsonObject.keys,
        )
        assertEquals("files", requests[7].getValue("params").jsonObject.keys.last())
        assertEquals(1, requests[7].getValue("params").jsonObject.getValue("files").jsonArray.size)

        assertFalse(prepared.toString().contains("ticket-secret"))
        rpc.close()
    }

    @Test
    fun `schedule payload is decoded to typed groups and strips remote secret and file id fields recursively`() = runTest {
        val connection = FakeConnection {
            buildJsonObject {
                put("identityEpoch", 7)
                put("generation", 9)
                put(
                    "groups",
                    ApplicationProtocol.JSON.parseToJsonElement(
                        """[{"time":"上午","allDay":false,"items":[{"id":"schedule-1","title":"会议","at":"10:00","completed":false,"fileIds":["oa-file-secret"],"nested":{"accessToken":"token-secret"}}]}]""",
                    ),
                )
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)
        val client = BusinessScheduleRpcClient(rpc)

        val data = client.day(BusinessScheduleQuery("2026-07-29", BusinessWorkbenchScope.PERSONAL))

        assertEquals("schedule-1", data.groups.single().items.single().id)
        assertFalse(data.toString().contains("fileIds"))
        assertFalse(data.toString().contains("oa-file-secret"))
        assertFalse(data.toString().contains("token-secret"))
        assertFalse(data.toString().contains("schedule-1"))
        rpc.close()
    }

    @Test
    fun `team member option uses OA userId instead of member record id`() = runTest {
        val connection = FakeConnection {
            buildJsonObject {
                put("identityEpoch", 7)
                put("generation", 9)
                put("revision", 3)
                put("types", JsonArray(emptyList()))
                put(
                    "members",
                    ApplicationProtocol.JSON.parseToJsonElement(
                        """[{"id":"member-1","userId":"user-1","userName":"Lawyer"}]""",
                    ),
                )
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)

        val member = BusinessScheduleRpcClient(rpc)
            .form(BusinessWorkbenchScope.TEAM, "team-1")
            .members.single()

        assertEquals("user-1", member.id)
        assertEquals("member-1", member.values["id"])
        assertEquals("user-1", member.values["userId"])
        rpc.close()
    }

    @Test
    fun `service project decoder flattens real OA category groups and keeps the category path`() = runTest {
        val connection = FakeConnection {
            buildJsonObject {
                put("identityEpoch", 7)
                put("generation", 9)
                put("revision", 3)
                put("relationType", "SERVICE_PROJECT")
                put(
                    "items",
                    ApplicationProtocol.JSON.parseToJsonElement(
                        """[{"categoryId":"category-1","categoryName":"Litigation","projects":[{"id":"project-1","recordId":"record-1","projectName":"Appeal"}]}]""",
                    ),
                )
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)

        val option = BusinessScheduleRpcClient(rpc)
            .serviceProjects("record-1", null, null)
            .items.single()

        assertEquals("project-1", option.id)
        assertEquals("Litigation > Appeal", option.name)
        assertEquals("record-1", option.values["recordId"])
        assertEquals("category-1", option.values["categoryId"])
        rpc.close()
    }

    @Test
    fun `day decoder preserves timeline type color priority repetition and expiry fields`() = runTest {
        val connection = FakeConnection {
            buildJsonObject {
                put("identityEpoch", 7)
                put("generation", 9)
                put(
                    "groups",
                    ApplicationProtocol.JSON.parseToJsonElement(
                        """[{"time":"morning","allDay":false,"items":[{"id":"schedule-1","title":"meeting","at":"2026-07-29 10:00:00","completed":false,"typeTitle":"hearing","color":"#216DFF","priority":3,"repetition":2,"expiredDays":4}]}]""",
                    ),
                )
            }
        }
        val rpc = AgentJsonRpcClient(connection, this)

        val item = BusinessScheduleRpcClient(rpc)
            .day(BusinessScheduleQuery("2026-07-29", BusinessWorkbenchScope.PERSONAL))
            .groups.single().items.single()

        assertEquals("hearing", item.typeTitle)
        assertEquals("#216DFF", item.color)
        assertEquals(3, item.priority)
        assertEquals(2, item.repetition)
        assertEquals(4, item.expiredDays)
        rpc.close()
    }

    private fun response(request: JsonObject): JsonObject {
        val method = request.getValue("method").jsonPrimitive.content
        return buildJsonObject {
            put("identityEpoch", 7)
            put("generation", 9)
            when (method) {
                "business/schedule/month/get" -> put("days", JsonArray(emptyList()))
                "business/schedule/day/get" -> put("groups", JsonArray(emptyList()))
                "business/schedule/completion/set" -> {
                    put("completed", true)
                    put("refreshRequired", true)
                    put("revision", 4)
                }
                "business/schedule/form/get" -> {
                    put("revision", 3)
                    put("types", ApplicationProtocol.JSON.parseToJsonElement("""[{"id":"type-1","name":"会议"}]"""))
                    put("members", ApplicationProtocol.JSON.parseToJsonElement("""[{"userId":"user-1","userName":"当前用户"}]"""))
                }
                "business/schedule/relation-options/get" -> {
                    put("revision", 3)
                    put("relationType", "CASE")
                    put("items", JsonArray(emptyList()))
                }
                "business/schedule/service-projects/get" -> {
                    put("revision", 3)
                    put("relationType", "SERVICE_PROJECT")
                    put("items", JsonArray(emptyList()))
                }
                "business/schedule/create" -> {
                    put("revision", 4)
                    put("refreshRequired", true)
                }
                "business/attachments/upload/prepare" -> {
                    put("attachmentBatchId", "batch-1")
                    put("ticket", "ticket-secret")
                    put("expiresAt", "2026-07-29T00:05:00Z")
                }
                else -> error("unexpected method $method")
            }
        }
    }

    private class FakeConnection(
        private val responder: (JsonObject) -> JsonObject,
    ) : AgentConnection {
        private val incomingChannel = Channel<String>(Channel.UNLIMITED)
        private val sent = mutableListOf<JsonObject>()
        override val connectionId: String = "schedule-rpc-test"
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
        override suspend fun close() {
            incomingChannel.close()
        }
    }
}
