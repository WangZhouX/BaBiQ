package com.wzx.huitai.integration.oa.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 独立于 READY 会话的 OA 认证协议客户端。
 *
 * 构造参数由 app 层已经校验的配置拆开传入，避免 integration-core 依赖 app 模块。
 */
internal class KtorOaAuthenticationGateway(
    baseUrl: String,
    apiPrefix: String,
    private val platformId: Int,
    requestTimeoutMs: Long,
    engine: HttpClientEngine = CIO.create(),
) : OaPreAuthenticationGateway, OaCandidateAuthenticationGateway, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val ownedEngine = engine
    private val endpointBase = endpointBase(baseUrl, apiPrefix)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = requestTimeoutMs }
        install(ContentNegotiation) { json(json) }
        defaultRequest {
            header(PLATFORM_HEADER, PLATFORM_PC)
        }
    }

    init {
        require(platformId > 0) { "platformId must be positive" }
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be positive" }
    }

    override suspend fun findTenantCandidates(mobile: String): List<OaTenantCandidate> = request {
        val data = successData(
            httpClient.get("$endpointBase/system/auth/get-users-by-mobile") {
                parameter("mobile", mobile)
            },
        )
        val candidates = (data as? JsonArray)?.map(::candidateFrom) ?: protocolError()
        if (candidates.isEmpty()) {
            throw OaAuthenticationException(OaAuthenticationError.ACCOUNT_NOT_FOUND)
        }
        if (candidates.map { it.userId to it.tenantId }.toSet().size != candidates.size) {
            protocolError()
        }
        candidates
    }

    override suspend fun login(mobileOrEmail: String, password: CharArray, tenantId: String): OaTokenBundle {
        val passwordDigest = OaPasswordEncoder.encode(password)
        return request {
            val body = buildJsonObject {
                put("mobileOrEmail", mobileOrEmail)
                put("password", passwordDigest)
                put("platformId", platformId)
                put("tenantId", tenantId)
            }.toString()
            tokenFrom(successData(httpClient.post("$endpointBase/system/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }))
        }
    }

    override suspend fun refresh(tenantId: String, refreshToken: String): OaTokenBundle = request {
        tokenFrom(successData(httpClient.post("$endpointBase/system/auth/refresh-token") {
            header(TENANT_HEADER, tenantId)
            setBody(FormDataContent(Parameters.build {
                append("refreshToken", refreshToken)
            }))
        }))
    }

    override suspend fun loadPermissionInfo(candidate: OaCandidateAccess): OaPermissionInfo = request {
        if (candidate.platformId != platformId) protocolError()
        val data = successData(httpClient.get("$endpointBase/system/auth/get-permission-info") {
            parameter("platformId", candidate.platformId)
            header(HttpHeaders.Authorization, "Bearer ${candidate.accessToken}")
            header(TENANT_HEADER, candidate.tenantId)
        })
        permissionFrom(data, candidate.userId)
    }

    override suspend fun logout(candidate: OaCandidateAccess) {
        request {
            if (candidate.platformId != platformId) protocolError()
            successData(httpClient.post("$endpointBase/system/auth/logout") {
                header(HttpHeaders.Authorization, "Bearer ${candidate.accessToken}")
                header(TENANT_HEADER, candidate.tenantId)
            })
            Unit
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            httpClient.close()
            ownedEngine.close()
        }
    }

    private suspend fun successData(response: io.ktor.client.statement.HttpResponse): JsonElement {
        if (response.status.value !in 200..299) protocolError()
        val root = try {
            json.parseToJsonElement(response.bodyAsText()) as? JsonObject ?: protocolError()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OaAuthenticationException) {
            throw OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR)
        } catch (_: Throwable) {
            protocolError()
        }
        val code = root["code"].scalar() ?: protocolError()
        if (code !in SUCCESS_CODES) {
            val message = root.requiredString("msg")
            throw OaAuthenticationException(businessError(code, message))
        }
        return root["data"]?.takeUnless { it is JsonNull } ?: protocolError()
    }

    private fun candidateFrom(value: JsonElement): OaTenantCandidate {
        val source = value as? JsonObject ?: protocolError()
        val candidate = OaTenantCandidate(
            userId = source.requiredIdentifier("userId"),
            tenantId = source.requiredIdentifier("tenantId"),
            platformId = source.requiredInt("platformId"),
            tenantName = source.optionalString("tenantName"),
            tenantEnterStatus = source.requiredInt("tenantEnterStatus"),
            tenantEnterId = source.optionalIdentifier("tenantEnterId"),
        )
        if (candidate.platformId != platformId) protocolError()
        return candidate
    }

    private fun tokenFrom(value: JsonElement): OaTokenBundle {
        val source = value as? JsonObject ?: protocolError()
        return OaTokenBundle(
            accessToken = source.requiredString("accessToken"),
            refreshToken = source.requiredString("refreshToken"),
            userId = source.requiredIdentifier("userId"),
            expiresTime = source.requiredLong("expiresTime"),
        )
    }

    private fun permissionFrom(value: JsonElement, expectedUserId: String): OaPermissionInfo {
        val source = value as? JsonObject ?: protocolError()
        val user = source["user"] as? JsonObject ?: protocolError()
        val id = user.requiredIdentifier("id")
        if (id != expectedUserId) protocolError()
        val permissions = source.requiredStringSetIgnoringBlank("permissions")
        val roles = source.requiredStringSet("roles")
        val menus = (source["menus"] as? JsonArray)?.toList() ?: protocolError()
        return OaPermissionInfo(permissions, roles, OaPermissionUser(id, user.optionalString("name")), menus)
    }

    private suspend fun <T> request(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: OaAuthenticationException) {
        throw known
    } catch (_: HttpRequestTimeoutException) {
        throw OaAuthenticationException(OaAuthenticationError.REMOTE_TIMEOUT)
    } catch (_: SocketTimeoutException) {
        throw OaAuthenticationException(OaAuthenticationError.REMOTE_TIMEOUT)
    } catch (_: ConnectException) {
        throw OaAuthenticationException(OaAuthenticationError.REMOTE_UNAVAILABLE)
    } catch (_: UnresolvedAddressException) {
        throw OaAuthenticationException(OaAuthenticationError.REMOTE_UNAVAILABLE)
    } catch (_: IOException) {
        throw OaAuthenticationException(OaAuthenticationError.REMOTE_UNAVAILABLE)
    } catch (error: Error) {
        throw error
    } catch (_: Throwable) {
        throw OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR)
    }

    private fun JsonObject.requiredString(name: String): String =
        (this[name] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: protocolError()

    /**
     * OA serializes identity IDs as either JSON strings or integer JSON numbers.
     * Accept both representations while rejecting booleans, decimals, and nulls.
     */
    private fun JsonObject.requiredIdentifier(name: String): String {
        val value = this[name] as? JsonPrimitive ?: protocolError()
        val content = value.contentOrNull?.takeIf(String::isNotBlank) ?: protocolError()
        if (value.isString || INTEGER_IDENTIFIER_PATTERN.matches(content)) return content
        protocolError()
    }

    private fun JsonObject.optionalIdentifier(name: String): String? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: protocolError()
        val content = primitive.contentOrNull?.takeIf(String::isNotBlank) ?: protocolError()
        if (primitive.isString || INTEGER_IDENTIFIER_PATTERN.matches(content)) return content
        protocolError()
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: protocolError()
    }

    private fun JsonObject.requiredInt(name: String): Int = this[name].scalar()?.toIntOrNull() ?: protocolError()

    private fun JsonObject.requiredLong(name: String): Long = this[name].scalar()?.toLongOrNull() ?: protocolError()

    private fun JsonObject.requiredStringSet(name: String): Set<String> =
        (this[name] as? JsonArray)?.map {
            (it as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: protocolError()
        }?.toSet() ?: protocolError()

    private fun JsonObject.requiredStringSetIgnoringBlank(name: String): Set<String> =
        (this[name] as? JsonArray)?.map {
            (it as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?: protocolError()
        }?.filter(String::isNotBlank)?.toSet() ?: protocolError()

    private fun JsonElement?.scalar(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun businessError(code: String, message: String): OaAuthenticationError {
        val marker = "$code $message".lowercase()
        return when {
            marker.contains("password") || marker.contains("密码") || marker.contains("credential") -> OaAuthenticationError.INVALID_CREDENTIALS
            marker.contains("account") || marker.contains("user") || marker.contains("mobile") || marker.contains("用户") || marker.contains("账号") -> OaAuthenticationError.ACCOUNT_NOT_FOUND
            else -> OaAuthenticationError.REMOTE_PROTOCOL_ERROR
        }
    }

    private fun protocolError(): Nothing = throw OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR)

    private fun endpointBase(baseUrl: String, apiPrefix: String): String {
        val normalizedBase = baseUrl.trimEnd('/')
        require(normalizedBase.startsWith("http://") || normalizedBase.startsWith("https://")) { "baseUrl must use HTTP or HTTPS" }
        require(!normalizedBase.contains('?') && !normalizedBase.contains('#')) { "baseUrl must not contain query or fragment" }
        require(apiPrefix.startsWith('/') && !apiPrefix.contains('?') && !apiPrefix.contains('#') && !apiPrefix.contains("..")) {
            "apiPrefix must be an absolute path"
        }
        return normalizedBase + apiPrefix.trimEnd('/')
    }

    private companion object {
        val SUCCESS_CODES = setOf("0", "200")
        const val PLATFORM_HEADER = "X-Platform-Type"
        const val PLATFORM_PC = "pc"
        const val TENANT_HEADER = "tenant-id"
        val INTEGER_IDENTIFIER_PATTERN = Regex("-?\\d+")
    }
}
