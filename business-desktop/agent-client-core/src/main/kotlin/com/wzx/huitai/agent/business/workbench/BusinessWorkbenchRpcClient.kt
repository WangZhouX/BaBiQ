package com.wzx.huitai.agent.business.workbench

import com.wzx.huitai.agent.business.BusinessRpcException
import com.wzx.huitai.agent.business.auth.BusinessNavigationTarget
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.AgentJsonRpcException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface BusinessWorkbenchClient {
    suspend fun get(month: String? = null, day: String? = null): BusinessWorkbenchSnapshot
    suspend fun navigation(): BusinessWorkbenchNavigation
    suspend fun homeInfo(): BusinessWorkbenchSection
    suspend fun page(request: BusinessWorkbenchPageRequest): BusinessWorkbenchPage
    suspend fun teamRoles(teamId: String, kind: BusinessWorkbenchKind): BusinessWorkbenchTeamRoles
    suspend fun updateSort(request: BusinessWorkbenchSortRequest): BusinessWorkbenchSortMutation
}

class BusinessWorkbenchRpcClient(private val rpc: AgentJsonRpcClient) : BusinessWorkbenchClient {
    @Volatile
    var lastIdentityEpoch: Long? = null
        private set

    override suspend fun get(month: String?, day: String?): BusinessWorkbenchSnapshot {
        validateDate(month, day)
        val params = buildJsonObject {
            month?.let { put("month", it) }
            day?.let { put("day", it) }
        }
        val result = request("business/workbench/get", params)
        return decodeSnapshot(result)
    }

    override suspend fun navigation(): BusinessWorkbenchNavigation {
        val result = request("business/workbench/navigation/get", buildJsonObject { })
        val epoch = result.requiredLong("identityEpoch")
        lastIdentityEpoch = epoch
        val items = (result["items"] as? JsonArray)?.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val kind = item.optionalText("kind") ?: return@mapNotNull null
            val path = item.optionalText("path") ?: return@mapNotNull null
            val title = item.optionalText("title") ?: return@mapNotNull null
            if (kind !in ALLOWED_NAVIGATION_KINDS || path !in ALLOWED_NAVIGATION_PATHS) return@mapNotNull null
            BusinessNavigationTarget(kind, normalizeNavigationPath(path), title)
        }.orEmpty()
        return BusinessWorkbenchNavigation(epoch, result.requiredLong("generation"), items)
    }

    override suspend fun homeInfo(): BusinessWorkbenchSection {
        val result = request("business/workbench/home-info/get", buildJsonObject { })
        lastIdentityEpoch = result.requiredLong("identityEpoch")
        return decodeSection(result["section"] ?: result["profile"])
    }

    override suspend fun page(request: BusinessWorkbenchPageRequest): BusinessWorkbenchPage {
        validatePageRequest(request)
        val result = request("business/workbench/page/get", request.toJson())
        val pageValue = (result["page"] as? JsonObject) ?: result
        val page = BusinessWorkbenchPage(
            identityEpoch = result.requiredLong("identityEpoch"),
            generation = result.optionalLong("generation") ?: pageValue.optionalLong("generation") ?: 0,
            total = pageValue.optionalLong("total") ?: 0,
            pageNo = pageValue.optionalLong("pageNo")?.toInt() ?: request.pageNo,
            pageSize = pageValue.optionalLong("pageSize")?.toInt() ?: request.pageSize,
            items = (pageValue["items"] as? JsonArray)?.mapNotNull { decodePageItem(it) }.orEmpty(),
        )
        lastIdentityEpoch = page.identityEpoch
        return page
    }

    override suspend fun teamRoles(
        teamId: String,
        kind: BusinessWorkbenchKind,
    ): BusinessWorkbenchTeamRoles {
        require(teamId.isNotBlank() && teamId.length <= 256) { "invalid teamId" }
        val result = request(
            "business/workbench/team-roles/list",
            buildJsonObject {
                put("teamId", teamId)
                put("kind", kind.name)
            },
        )
        val roles = BusinessWorkbenchTeamRoles(
            identityEpoch = result.requiredLong("identityEpoch"),
            generation = result.requiredLong("generation"),
            items = (result["items"] as? JsonArray)?.map { raw ->
                val value = raw as? JsonObject ?: throw SerializationException("Invalid team role")
                BusinessWorkbenchTeamRole(
                    roleCode = value.requiredText("roleCode"),
                    name = value.optionalText("name") ?: value.requiredText("roleCode"),
                )
            }.orEmpty(),
        )
        lastIdentityEpoch = roles.identityEpoch
        return roles
    }

    override suspend fun updateSort(request: BusinessWorkbenchSortRequest): BusinessWorkbenchSortMutation {
        val result = request(
            "business/workbench/sort/update",
            buildJsonObject {
                put("kind", request.kind.name)
                put("ids", JsonArray(request.ids.map(::JsonPrimitive)))
                put("expectedRevision", request.expectedRevision)
            },
        )
        val mutation = BusinessWorkbenchSortMutation(
            identityEpoch = result.requiredLong("identityEpoch"),
            generation = result.requiredLong("generation"),
            revision = result.requiredLong("revision"),
            refreshRequired = result.optionalText("refreshRequired")?.toBooleanStrictOrNull()
                ?: throw SerializationException("Missing required field: refreshRequired"),
            canonicalIds = (result["ids"] as? JsonArray)?.map { raw ->
                (raw as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: throw SerializationException("Invalid canonical sort id")
            },
        )
        lastIdentityEpoch = mutation.identityEpoch
        return mutation
    }

    fun close() {
        // AgentJsonRpcClient owns the transport lifecycle.
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject = try {
        rpc.request(method, params)
    } catch (failure: AgentJsonRpcException) {
        throw BusinessRpcException.from(failure)
    }

    private fun decodeSnapshot(result: JsonObject): BusinessWorkbenchSnapshot {
        val identityEpoch = result.requiredLong("identityEpoch")
        val snapshot = (result["snapshot"] as? JsonObject) ?: result
        val value = BusinessWorkbenchSnapshot(
            identityEpoch = identityEpoch,
            generation = result.optionalLong("generation") ?: snapshot.optionalLong("generation") ?: 0,
            notices = decodeSection(snapshot["notices"]),
            shortcuts = decodeSection(snapshot["shortcuts"]),
            summary = decodeSection(snapshot["summary"]),
            profile = decodeSection(snapshot["profile"] ?: snapshot["homeInfo"]),
            teams = decodeSection(snapshot["teams"]),
            schedule = decodeSection(snapshot["schedule"]),
            issues = (snapshot["issues"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        )
        lastIdentityEpoch = value.identityEpoch
        return value
    }

    private fun decodeSection(raw: JsonElement?): BusinessWorkbenchSection {
        val objectValue = raw as? JsonObject
        val status = objectValue?.optionalText("status")?.let { rawStatus ->
            BusinessWorkbenchSectionStatus.entries.firstOrNull { it.name == rawStatus } ?: BusinessWorkbenchSectionStatus.UNKNOWN
        } ?: if (raw == null || raw is JsonNull) BusinessWorkbenchSectionStatus.EMPTY else BusinessWorkbenchSectionStatus.OK
        val data = objectValue?.get("data")?.let(::sanitize) ?: raw?.takeUnless { it is JsonNull }?.let(::sanitize)
        return BusinessWorkbenchSection(status, data)
    }

    private fun decodePageItem(raw: JsonElement): BusinessWorkbenchPageItem? {
        val value = raw as? JsonObject ?: return null
        val id = value.optionalText("id") ?: return null
        return BusinessWorkbenchPageItem(
            id = id,
            applicationNumber = value.optionalText("applicationNumber"),
            categoriesName = value.optionalText("categoriesName"),
            scheduleName = value.optionalText("scheduleName"),
            title = value.optionalText("title"),
            values = (value["values"] as? JsonObject)?.let(::sanitizeObject) ?: JsonObject(emptyMap()),
        )
    }

    private fun BusinessWorkbenchPageRequest.toJson(): JsonObject = buildJsonObject {
        put("kind", kind.name)
        put("scope", scope.name)
        teamId?.let { put("teamId", it) }
        roleCode?.let { put("roleCode", it) }
        put("pageNo", pageNo)
        put("pageSize", pageSize)
        put("filters", buildJsonObject {
            filters.forEach { (key, value) -> put(key, value.toJsonElement()) }
        })
    }

    private fun validatePageRequest(request: BusinessWorkbenchPageRequest) {
        require(request.pageNo > 0) { "pageNo must be positive" }
        require(request.pageSize in 1..100) { "pageSize must be between 1 and 100" }
        when (request.scope) {
            BusinessWorkbenchScope.TEAM -> require(!request.teamId.isNullOrBlank()) { "TEAM scope requires teamId" }
            BusinessWorkbenchScope.ALL, BusinessWorkbenchScope.PERSONAL -> {
                require(request.teamId == null) { "${request.scope} scope must not include teamId" }
                require(request.roleCode == null) { "${request.scope} scope must not include roleCode" }
            }
        }
        val allowed = FILTERS[request.kind].orEmpty()
        request.filters.keys.forEach { key ->
            require(key in allowed) { "Unknown filter: $key" }
            require(key !in FORBIDDEN_FIELDS) { "Forbidden field: $key" }
        }
    }

    private fun validateDate(month: String?, day: String?) {
        month?.let { runCatching { YearMonth.parse(it) }.getOrElse { throw IllegalArgumentException("month must be YYYY-MM") } }
        day?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("day must be YYYY-MM-DD") } }
        if (month != null && day != null) require(day.startsWith("$month-")) { "day must belong to month" }
    }

    private fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> sanitizeObject(element)
        is JsonArray -> JsonArray(element.map(::sanitize))
        else -> element
    }

    private fun sanitizeObject(element: JsonObject): JsonObject = JsonObject(
        element.entries.mapNotNull { (key, value) ->
            if (FORBIDDEN_FIELDS.any { key.equals(it, ignoreCase = true) } || key.contains("token", true) || key.contains("secret", true) || key.contains("authorization", true) || key.contains("url", true)) null
            else key to sanitize(value)
        }.toMap(LinkedHashMap()),
    )

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    private companion object {
        val FILTERS = mapOf(
            BusinessWorkbenchKind.CASE to setOf("status"),
            BusinessWorkbenchKind.APPOINTMENT to setOf("consultMode"),
            BusinessWorkbenchKind.COUNSELOR_SERVICE to setOf("serviceStatus"),
            BusinessWorkbenchKind.VISIT to setOf("visitObj"),
        )
        val FORBIDDEN_FIELDS = setOf("moduleId", "relatedIds", "dataRoleInfos", "dataRoleCodes", "tenantId", "userId", "accessToken", "refreshToken", "secretRef")
        val ALLOWED_NAVIGATION_KINDS = setOf(
            "WORKBENCH", "LAW_OA", "BPM", "APPROVAL", "CASE", "ADMINISTRATION", "MANAGEMENT",
            "CUSTOMER", "COST", "CONSULTANT", "LAWYER_ADMIN", "TOOLS", "TEAM",
        )
        val ALLOWED_NAVIGATION_PATHS = setOf(
            "/", "/index", "/index/unfinished", "/lawoa", "/bpm", "/approval", "/case",
            "/administration", "/management", "/customer", "/cost", "/consultant", "/lawyer-admin",
            "/tools", "/team",
        )
    }
}

private fun JsonObject.optionalText(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.requiredText(name: String): String = optionalText(name)?.takeIf(String::isNotBlank)
    ?: throw SerializationException("Missing required field: $name")
private fun JsonObject.requiredLong(name: String): Long = optionalText(name)?.toLongOrNull()
    ?: throw SerializationException("Missing required field: $name")
private fun JsonObject.optionalLong(name: String): Long? = optionalText(name)?.toLongOrNull()
private fun normalizeNavigationPath(path: String): String =
    if (path == "/index" || path == "/index/unfinished") "/" else path
