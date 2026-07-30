package com.wzx.huitai.agent.business.auth

import com.wzx.huitai.agent.business.BusinessRpcException
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.AgentJsonRpcException
import java.util.Arrays
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

interface BusinessAuthClient {
    suspend fun session(): BusinessSessionView
    suspend fun attach(attachHandle: String): BusinessSessionView
    suspend fun tenantCandidates(account: String): List<BusinessTenantCandidate>
    suspend fun login(account: String, password: CharArray, candidateId: String): BusinessSessionView
    suspend fun restore(): BusinessSessionView
    suspend fun logout(): BusinessSessionView
}

class BusinessAuthRpcClient(private val rpc: AgentJsonRpcClient) : BusinessAuthClient {
    override suspend fun session(): BusinessSessionView = call("business/auth/session/get", buildJsonObject { })

    override suspend fun attach(attachHandle: String): BusinessSessionView {
        require(attachHandle.isNotBlank()) { "attachHandle must not be blank" }
        return call("business/auth/session/attach", buildJsonObject { put("attachHandle", attachHandle) })
    }

    override suspend fun tenantCandidates(account: String): List<BusinessTenantCandidate> {
        require(account.isNotBlank()) { "account must not be blank" }
        val result = request("business/auth/tenant-candidates", buildJsonObject { put("account", account) })
        return result["candidates"]?.asArray("candidates")?.map { raw ->
            val value = raw.asObject("tenant candidate")
            BusinessTenantCandidate(
                candidateId = value.requiredText("candidateId"),
                name = value.optionalText("name") ?: value.optionalText("tenantName") ?: "",
                status = value.optionalText("status")
                    ?: value.optionalLong("tenantEnterStatus")?.toString()
                    ?: "UNKNOWN",
                platformId = value.optionalLong("platformId")?.toInt() ?: 0,
                tenantEnterStatus = value.optionalLong("tenantEnterStatus")?.toInt() ?: 0,
            )
        }.orEmpty()
    }

    override suspend fun login(account: String, password: CharArray, candidateId: String): BusinessSessionView {
        require(account.isNotBlank()) { "account must not be blank" }
        require(password.isNotEmpty()) { "password must not be blank" }
        require(candidateId.isNotBlank()) { "candidateId must not be blank" }
        return try {
            call("business/auth/login", buildJsonObject {
                put("account", account)
                put("password", password.concatToString())
                put("candidateId", candidateId)
            })
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    override suspend fun restore(): BusinessSessionView = call("business/auth/session/restore", buildJsonObject { })

    override suspend fun logout(): BusinessSessionView = call("business/auth/logout", buildJsonObject { })

    fun close() {
        // AgentJsonRpcClient owns the transport lifecycle.
    }

    private suspend fun call(method: String, params: JsonObject): BusinessSessionView =
        decodeSession(request(method, params))

    private suspend fun request(method: String, params: JsonObject): JsonObject = try {
        rpc.request(method, params)
    } catch (failure: AgentJsonRpcException) {
        throw BusinessRpcException.from(failure)
    }

    private fun decodeSession(result: JsonObject): BusinessSessionView {
        val status = (result.optionalText("status") ?: result.optionalText("state"))?.let { raw ->
            BusinessAuthStatus.entries.firstOrNull { it.name == raw } ?: BusinessAuthStatus.UNKNOWN
        } ?: throw SerializationException("Missing required field: status")
        val user = (result["user"] as? JsonObject)?.let { value ->
            BusinessUserSummary(value.requiredText("id"), value.optionalText("name") ?: value.optionalText("nickname") ?: "", value.optionalText("avatar"))
        } ?: result.optionalText("userId")?.let { id ->
            BusinessUserSummary(id, result.optionalText("userName") ?: "", null)
        }
        val tenant = (result["tenant"] as? JsonObject)?.let { value ->
            BusinessTenantSummary(value.requiredText("id"), value.optionalText("name") ?: "")
        } ?: result.optionalText("tenantId")?.let { id ->
            BusinessTenantSummary(id, result.optionalText("tenantName") ?: "")
        }
        return BusinessSessionView(
            status = status,
            authSessionId = result.optionalText("authSessionId"),
            identityEpoch = result.requiredLong("identityEpoch"),
            generation = result.optionalLong("generation") ?: 0,
            platformId = result.optionalText("platformId"),
            user = user,
            tenant = tenant,
            roles = result.stringSet("roles"),
            permissions = result.stringSet("permissions"),
            menus = (result["menus"] as? JsonArray)?.mapNotNull { raw ->
                val value = raw as? JsonObject ?: return@mapNotNull null
                val path = value.optionalText("path") ?: return@mapNotNull null
                val kind = value.optionalText("kind") ?: return@mapNotNull null
                val title = value.optionalText("title") ?: return@mapNotNull null
                if (kind !in ALLOWED_NAVIGATION_KINDS || path !in ALLOWED_NAVIGATION_PATHS) return@mapNotNull null
                BusinessNavigationTarget(kind, normalizeNavigationPath(path), title)
            }.orEmpty(),
            rememberedAccount = result.optionalText("rememberedAccount"),
            attachHandle = result.optionalText("attachHandle"),
        )
    }
}

private val ALLOWED_NAVIGATION_KINDS = setOf(
    "WORKBENCH", "LAW_OA", "BPM", "APPROVAL", "CASE", "ADMINISTRATION", "MANAGEMENT",
    "CUSTOMER", "COST", "CONSULTANT", "LAWYER_ADMIN", "TOOLS", "TEAM",
)
private val ALLOWED_NAVIGATION_PATHS = setOf(
    "/", "/index", "/index/unfinished", "/lawoa", "/bpm", "/approval", "/case",
    "/administration", "/management", "/customer", "/cost", "/consultant", "/lawyer-admin",
    "/tools", "/team",
)

private fun normalizeNavigationPath(path: String): String =
    if (path == "/index" || path == "/index/unfinished") "/" else path

private fun JsonElement.asObject(label: String): JsonObject =
    this as? JsonObject ?: throw SerializationException("Invalid $label")

private fun JsonElement.asArray(label: String): JsonArray =
    this as? JsonArray ?: throw SerializationException("Invalid $label")

private fun JsonObject.requiredText(name: String): String = optionalText(name)?.takeIf(String::isNotBlank)
    ?: throw SerializationException("Missing required field: $name")

private fun JsonObject.optionalText(name: String): String? = get(name)?.let { value ->
    (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

private fun JsonObject.requiredLong(name: String): Long = optionalText(name)?.toLongOrNull()
    ?: throw SerializationException("Missing required field: $name")

private fun JsonObject.optionalLong(name: String): Long? = optionalText(name)?.toLongOrNull()

private fun JsonObject.stringSet(name: String): Set<String> =
    (this[name] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
