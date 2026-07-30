package com.wzx.huitai.agent.business.workbench

import com.wzx.huitai.agent.business.BusinessRpcException
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.AgentJsonRpcException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class BusinessScheduleRpcClient(
    private val rpc: AgentJsonRpcClient,
) : BusinessScheduleClient, BusinessAttachmentPrepareClient {
    override suspend fun month(query: BusinessScheduleQuery): BusinessScheduleMonthData {
        val result = scheduleResult("business/schedule/month/get", query)
        return BusinessScheduleMonthData(
            result.long("identityEpoch"),
            result.long("generation"),
            (result["days"] as? JsonArray)?.mapNotNull { raw ->
                val item = raw as? JsonObject ?: return@mapNotNull null
                val date = item.textOrNull("date") ?: return@mapNotNull null
                val count = item.intOrNull("count") ?: return@mapNotNull null
                BusinessScheduleMonthEntry(date, count.coerceAtLeast(0))
            }.orEmpty(),
        )
    }

    override suspend fun day(query: BusinessScheduleQuery): BusinessScheduleDayData {
        val result = scheduleResult("business/schedule/day/get", query)
        return BusinessScheduleDayData(
            result.long("identityEpoch"),
            result.long("generation"),
            (result["groups"] as? JsonArray)?.mapNotNull { rawGroup ->
                val group = rawGroup as? JsonObject ?: return@mapNotNull null
                BusinessScheduleDayGroup(
                    time = group.textOrNull("time").orEmpty(),
                    allDay = group.booleanOrNull("allDay") ?: false,
                    items = (group["items"] as? JsonArray)?.mapNotNull { rawItem ->
                        val item = rawItem as? JsonObject ?: return@mapNotNull null
                        val id = item.textOrNull("id") ?: return@mapNotNull null
                        BusinessScheduleDayItem(
                            id = id,
                            title = item.textOrNull("title") ?: "日程",
                            at = item.textOrNull("at").orEmpty(),
                            completed = item.booleanOrNull("completed") ?: false,
                            typeTitle = item.textOrNull("typeTitle"),
                            color = item.textOrNull("color"),
                            priority = item.intOrNull("priority"),
                            repetition = item.intOrNull("repetition"),
                            expiredDays = item.intOrNull("expiredDays"),
                        )
                    }.orEmpty(),
                )
            }.orEmpty(),
        )
    }

    override suspend fun setCompletion(id: String, completed: Boolean): BusinessScheduleCompletion {
        require(id.isNotBlank()) { "schedule id must not be blank" }
        val result = request("business/schedule/completion/set", buildJsonObject {
            put("scheduleId", id)
            put("completed", completed)
        })
        return BusinessScheduleCompletion(
            result.long("identityEpoch"),
            result.long("generation"),
            result.boolean("completed"),
            result.boolean("refreshRequired"),
            result.long("revision"),
        )
    }

    override suspend fun form(scope: BusinessWorkbenchScope, teamId: String?): BusinessScheduleForm {
        validateScope(scope, teamId)
        val result = request("business/schedule/form/get", buildJsonObject {
            put("scope", scope.name)
            teamId?.let { put("teamId", it) }
        })
        return BusinessScheduleForm(
            result.long("identityEpoch"),
            result.long("generation"),
            result.long("revision"),
            result.options("types"),
            result.options("members", preferUserId = true),
        )
    }

    override suspend fun relationOptions(
        type: String,
        keyword: String?,
        teamId: String?,
        parentId: String?,
    ): BusinessScheduleRelationOptions = relationRequest(
        "business/schedule/relation-options/get",
        buildJsonObject {
            put("relationType", type)
            keyword?.takeIf(String::isNotBlank)?.let { put("keyword", it) }
            teamId?.let { put("teamId", it) }
            parentId?.let { put("parentId", it) }
        },
    )

    override suspend fun serviceProjects(
        recordId: String,
        keyword: String?,
        teamId: String?,
    ): BusinessScheduleRelationOptions {
        val result = request(
            "business/schedule/service-projects/get",
            buildJsonObject {
                put("recordId", recordId)
                keyword?.takeIf(String::isNotBlank)?.let { put("keyword", it) }
                teamId?.let { put("teamId", it) }
            },
        )
        return BusinessScheduleRelationOptions(
            result.long("identityEpoch"),
            result.long("generation"),
            result.long("revision"),
            result.text("relationType"),
            result.serviceProjectOptions(),
        )
    }

    override suspend fun create(request: BusinessScheduleCreateRequest): BusinessScheduleMutation {
        val result = request("business/schedule/create", buildJsonObject {
            put("clientOperationId", request.clientOperationId)
            put("scope", request.scope.name)
            request.teamId?.let { put("teamId", it) }
            request.assigneeUserId?.let { put("assigneeUserId", it) }
            put("title", request.title)
            put("typeId", request.typeId)
            put("at", request.at)
            put("allDay", request.allDay)
            put("priority", request.priority)
            request.description?.let { put("description", it) }
            put("reminderMinutes", JsonArray(request.reminderMinutes.map(::JsonPrimitive)))
            put("relations", JsonArray(request.relations.map { relation ->
                buildJsonObject {
                    put("relationType", relation.relationType)
                    put("relationId", relation.id)
                    relation.name?.let { put("relationTitle", it) }
                    relation.parentId?.let { put("parentId", it) }
                }
            }))
            request.attachmentBatchId?.let { put("attachmentBatchId", it) }
            request.attachmentParentResourceId?.let { put("attachmentParentResourceId", it) }
            request.attachmentParentRelationType?.let { put("attachmentParentRelationType", it) }
            put("formRevision", request.formRevision)
            put("repetition", request.repetition)
        })
        return BusinessScheduleMutation(
            result.long("identityEpoch"),
            result.long("generation"),
            result.long("revision"),
            result.boolean("refreshRequired"),
        )
    }

    override suspend fun prepareAttachment(
        request: BusinessAttachmentPrepareRequest,
    ): BusinessAttachmentPrepared {
        val result = request("business/attachments/upload/prepare", buildJsonObject {
            put("operation", request.operation)
            put("clientOperationId", request.clientOperationId)
            put("scope", request.scope.name)
            request.teamId?.let { put("teamId", it) }
            put("typeId", request.typeId)
            put("parentRelationType", request.parentRelationType)
            put("parentResourceId", request.parentResourceId)
            request.parentRecordId?.let { put("parentRecordId", it) }
            put("formRevision", request.formRevision)
            put("files", JsonArray(request.files.map { file ->
                buildJsonObject {
                    put("fileName", file.fileName)
                    put("sizeBytes", file.sizeBytes)
                    put("mediaType", file.mediaType)
                    file.sha256?.let { put("sha256", it) }
                }
            }))
        })
        return BusinessAttachmentPrepared(
            result.text("attachmentBatchId"),
            result.text("ticket"),
            result.text("expiresAt"),
            result.long("identityEpoch"),
            result.long("generation"),
        )
    }

    private suspend fun scheduleResult(method: String, query: BusinessScheduleQuery): JsonObject {
        validateScope(query.scope, query.teamId)
        val result = request(method, buildJsonObject {
            put("date", query.date)
            put("scope", query.scope.name)
            query.teamId?.let { put("teamId", it) }
            put("onlyMine", query.onlyMine)
            query.typeId?.let { put("typeId", it) }
        })
        return sanitizeSchedulePayload(result) as JsonObject
    }

    private suspend fun relationRequest(
        method: String,
        params: JsonObject,
    ): BusinessScheduleRelationOptions {
        val result = request(method, params)
        return BusinessScheduleRelationOptions(
            result.long("identityEpoch"),
            result.long("generation"),
            result.long("revision"),
            result.text("relationType"),
            result.options("items"),
        )
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject = try {
        rpc.request(method, params)
    } catch (failure: AgentJsonRpcException) {
        throw BusinessRpcException.from(failure)
    }

    private fun validateScope(scope: BusinessWorkbenchScope, teamId: String?) {
        if (scope == BusinessWorkbenchScope.TEAM) require(!teamId.isNullOrBlank()) { "TEAM requires teamId" }
        else require(teamId == null) { "teamId requires TEAM scope" }
    }

    private fun sanitizeSchedulePayload(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::sanitizeSchedulePayload))
        is JsonObject -> JsonObject(
            element.entries.mapNotNull { (key, value) ->
                if (SENSITIVE_KEYS.any { key.equals(it, ignoreCase = true) }
                    || key.contains("token", ignoreCase = true)
                    || key.contains("secret", ignoreCase = true)
                    || key.contains("authorization", ignoreCase = true)
                    || key.contains("fileId", ignoreCase = true)
                ) {
                    null
                } else {
                    key to sanitizeSchedulePayload(value)
                }
            }.toMap(LinkedHashMap()),
        )
        else -> element
    }

    private companion object {
        val SENSITIVE_KEYS = setOf(
            "accessToken",
            "refreshToken",
            "fileIds",
            "tenantId",
            "userId",
            "secretRef",
        )
    }
}

private fun JsonObject.text(name: String): String =
    (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw SerializationException("Missing required field: $name")

private fun JsonObject.long(name: String): Long = text(name).toLongOrNull()
    ?: throw SerializationException("Invalid long field: $name")

private fun JsonObject.boolean(name: String): Boolean = text(name).toBooleanStrictOrNull()
    ?: throw SerializationException("Invalid boolean field: $name")

private fun JsonObject.textOrNull(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.intOrNull(name: String): Int? = textOrNull(name)?.toIntOrNull()

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    textOrNull(name)?.let { value ->
        value.toBooleanStrictOrNull() ?: value.toIntOrNull()?.let { it != 0 }
    }

private fun JsonObject.options(
    name: String,
    preferUserId: Boolean = false,
): List<BusinessScheduleOption> =
    (get(name) as? JsonArray)?.map { raw ->
        val value = raw as? JsonObject ?: throw SerializationException("Invalid option")
        val id = if (preferUserId) {
            (value["userId"] as? JsonPrimitive)?.contentOrNull
                ?: (value["id"] as? JsonPrimitive)?.contentOrNull
        } else {
            (value["id"] as? JsonPrimitive)?.contentOrNull
                ?: (value["userId"] as? JsonPrimitive)?.contentOrNull
        }
        id
            ?: throw SerializationException("Missing option id")
        val label = listOf("name", "title", "userName", "projectName")
            .firstNotNullOfOrNull { key -> (value[key] as? JsonPrimitive)?.contentOrNull }
            ?: id
        BusinessScheduleOption(
            id,
            label,
            value.mapNotNull { (key, element) ->
                (element as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }.toMap(),
        )
    }.orEmpty()

private fun JsonObject.serviceProjectOptions(): List<BusinessScheduleOption> =
    (get("items") as? JsonArray)?.flatMap { rawGroup ->
        val group = rawGroup as? JsonObject ?: throw SerializationException("Invalid service project group")
        val categoryId = group.textOrNull("categoryId")
        val categoryName = group.textOrNull("categoryName")
        (group["projects"] as? JsonArray)?.map { rawProject ->
            val project = rawProject as? JsonObject ?: throw SerializationException("Invalid service project")
            val id = project.textOrNull("id") ?: project.textOrNull("projectId")
                ?: throw SerializationException("Missing service project id")
            val projectName = project.textOrNull("projectName") ?: project.textOrNull("name") ?: id
            val values = buildMap {
                project.forEach { (key, element) ->
                    (element as? JsonPrimitive)?.contentOrNull?.let { put(key, it) }
                }
                categoryId?.let { put("categoryId", it) }
                categoryName?.let { put("categoryName", it) }
            }
            BusinessScheduleOption(
                id = id,
                name = listOfNotNull(categoryName, projectName).joinToString(" > "),
                values = values,
            )
        }.orEmpty()
    }.orEmpty()
