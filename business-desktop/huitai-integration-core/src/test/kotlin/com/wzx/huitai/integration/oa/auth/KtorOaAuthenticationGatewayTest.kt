package com.wzx.huitai.integration.oa.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class KtorOaAuthenticationGatewayTest {
    @Test
    fun `uses exact OA auth methods paths queries bodies and candidate headers`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val gateway = gateway(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/system/auth/get-users-by-mobile" -> respondJson("""{"code":200,"msg":"ok","data":[{"userId":"u-1","tenantId":"t-1","platformId":7,"tenantName":"总部","tenantEnterStatus":1}]}""")
                "/api/system/auth/login" -> respondJson("""{"code":200,"msg":"ok","data":{"accessToken":"access-1","refreshToken":"refresh-1","userId":"u-1","expiresTime":123}}""")
                "/api/system/auth/refresh-token" -> respondJson("""{"code":200,"msg":"ok","data":{"accessToken":"access-2","refreshToken":"refresh-2","userId":"u-1","expiresTime":456}}""")
                "/api/system/auth/get-permission-info" -> respondJson("""{"code":200,"msg":"ok","data":{"permissions":["p.read"],"roles":["admin"],"user":{"id":"u-1","name":"Jane"},"menus":[]}}""")
                "/api/system/auth/logout" -> respondJson("""{"code":200,"msg":"ok","data":true}""")
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        })

        val candidates = gateway.findTenantCandidates("13800138000")
        val token = gateway.login("13800138000", "Abcdef12".toCharArray(), candidates.single().tenantId)
        val candidate = OaCandidateAccess(
            userId = "u-1",
            tenantId = "t-1",
            platformId = 7,
            accessToken = token.accessToken,
        )
        val refreshed = gateway.refresh("t-1", token.refreshToken)
        val permission = gateway.loadPermissionInfo(candidate)
        gateway.logout(candidate)

        assertEquals(5, requests.size)
        assertEquals(HttpMethod.Get, requests[0].method)
        assertEquals("/api/system/auth/get-users-by-mobile", requests[0].url.encodedPath)
        assertEquals("13800138000", requests[0].url.parameters["mobile"])
        assertEquals(HttpMethod.Post, requests[1].method)
        assertEquals("/api/system/auth/login", requests[1].url.encodedPath)
        val loginBody = (requests[1].body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals("{\"mobileOrEmail\":\"13800138000\",\"password\":\"6d93c260d711cdb51207c420279ae936\",\"platformId\":7,\"tenantId\":\"t-1\"}", loginBody)
        assertFalse(loginBody.contains("Abcdef12"))
        assertEquals(HttpMethod.Post, requests[2].method)
        assertEquals("refresh-1", requests[2].url.parameters["refreshToken"])
        assertEquals("t-1", requests[2].headers["tenant-id"])
        assertEquals(null, requests[2].headers[HttpHeaders.Authorization])
        assertEquals(HttpMethod.Get, requests[3].method)
        assertEquals("7", requests[3].url.parameters["platformId"])
        assertEquals("Bearer access-1", requests[3].headers[HttpHeaders.Authorization])
        assertEquals("t-1", requests[3].headers["tenant-id"])
        assertEquals(HttpMethod.Post, requests[4].method)
        assertEquals("Bearer access-1", requests[4].headers[HttpHeaders.Authorization])
        assertEquals("t-1", requests[4].headers["tenant-id"])
        assertEquals("access-2", refreshed.accessToken)
        assertEquals(setOf("p.read"), permission.permissions)
    }

    @Test
    fun `rejects duplicate candidates and identity mismatches without exposing remote body`() = runBlocking {
        val duplicate = gateway(MockEngine {
            respondJson("""{"code":200,"msg":"ok","data":[{"userId":"u-1","tenantId":"t-1","platformId":7,"tenantEnterStatus":1},{"userId":"u-1","tenantId":"t-1","platformId":7,"tenantEnterStatus":1}]}""")
        })
        val mismatch = gateway(MockEngine {
            respondJson("""{"code":200,"msg":"ok","data":{"permissions":[],"roles":[],"user":{"id":"other"},"menus":[]}}""")
        })

        assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, assertFailsWith<OaAuthenticationException> {
            duplicate.findTenantCandidates("13800138000")
        }.error)
        val failure = assertFailsWith<OaAuthenticationException> {
            mismatch.loadPermissionInfo(OaCandidateAccess("u-1", "t-1", 7, "access-secret"))
        }
        assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, failure.error)
        assertFalse(failure.toString().contains("other"))
        assertFalse(OaCandidateAccess("u-1", "t-1", 7, "access-secret").toString().contains("access-secret"))
    }

    @Test
    fun `does not follow redirect response`() = runBlocking {
        var calls = 0
        val gateway = gateway(MockEngine { request ->
            calls += 1
            if (calls == 1) {
                respond(
                    content = ByteReadChannel("redirect"),
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://elsewhere.example.test/secret"),
                )
            } else error("redirect was followed to ${request.url}")
        })

        val error = assertFailsWith<OaAuthenticationException> {
            gateway.findTenantCandidates("13800138000")
        }

        assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, error.error)
        assertEquals(1, calls)
    }

    private fun gateway(engine: MockEngine) = KtorOaAuthenticationGateway(
        baseUrl = "https://oa.example.test",
        apiPrefix = "/api",
        platformId = 7,
        requestTimeoutMs = 3_000,
        engine = engine,
    )

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
