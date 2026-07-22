package com.wzx.huitai.integration.oa.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertNull(requests[0].headers[HttpHeaders.Authorization])
        assertEquals(HttpMethod.Post, requests[1].method)
        assertEquals("/api/system/auth/login", requests[1].url.encodedPath)
        assertNull(requests[1].headers[HttpHeaders.Authorization])
        val loginContent = requests[1].body as OutgoingContent.ByteArrayContent
        assertEquals("application/json", loginContent.contentType?.withoutParameters()?.toString())
        val loginBody = loginContent.bytes().decodeToString()
        assertEquals("{\"mobileOrEmail\":\"13800138000\",\"password\":\"6d93c260d711cdb51207c420279ae936\",\"platformId\":7,\"tenantId\":\"t-1\"}", loginBody)
        assertFalse(loginBody.contains("Abcdef12"))
        assertEquals(HttpMethod.Post, requests[2].method)
        assertEquals("/api/system/auth/refresh-token", requests[2].url.encodedPath)
        assertEquals("refresh-1", requests[2].url.parameters["refreshToken"])
        assertEquals("t-1", requests[2].headers["tenant-id"])
        assertEquals(null, requests[2].headers[HttpHeaders.Authorization])
        assertEquals(HttpMethod.Get, requests[3].method)
        assertEquals("/api/system/auth/get-permission-info", requests[3].url.encodedPath)
        assertEquals("7", requests[3].url.parameters["platformId"])
        assertEquals("Bearer access-1", requests[3].headers[HttpHeaders.Authorization])
        assertEquals("t-1", requests[3].headers["tenant-id"])
        assertEquals(HttpMethod.Post, requests[4].method)
        assertEquals("/api/system/auth/logout", requests[4].url.encodedPath)
        assertEquals("Bearer access-1", requests[4].headers[HttpHeaders.Authorization])
        assertEquals("t-1", requests[4].headers["tenant-id"])
        assertEquals("access-2", refreshed.accessToken)
        assertEquals(setOf("p.read"), permission.permissions)
        assertFalse(token.toString().contains("access-1"))
        assertFalse(token.toString().contains("refresh-1"))
        assertFalse(refreshed.toString().contains("access-2"))
        assertFalse(gateway.toString().contains("access-1"))
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

    @Test
    fun `maps empty tenant list to account not found but duplicate candidates to protocol error`() = runBlocking {
        val empty = gateway(MockEngine { respondJson("""{"code":200,"msg":"ok","data":[]}""") })
        val duplicate = gateway(MockEngine {
            respondJson("""{"code":200,"msg":"ok","data":[{"userId":"u-1","tenantId":"t-1","platformId":7,"tenantEnterStatus":1},{"userId":"u-1","tenantId":"t-1","platformId":7,"tenantEnterStatus":1}]}""")
        })

        assertEquals(OaAuthenticationError.ACCOUNT_NOT_FOUND, authFailure { empty.findTenantCandidates("13800138000") }.error)
        assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, authFailure { duplicate.findTenantCandidates("13800138000") }.error)
    }

    @Test
    fun `maps null data missing fields illegal JSON and wrong top level types to protocol error`() = runBlocking {
        val bodies = listOf(
            """{"code":200,"msg":"ok","data":null}""",
            """{"code":200,"msg":"ok","data":[{"tenantId":"t-1","platformId":7,"tenantEnterStatus":1}]}""",
            "{not-json",
            "[]",
        )

        bodies.forEach { body ->
            val failure = authFailure { gateway(MockEngine { respondJson(body) }).findTenantCandidates("13800138000") }
            assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, failure.error)
            assertFalse(failure.toString().contains(body))
        }
    }

    @Test
    fun `maps non success HTTP and unknown business code to protocol error without remote body`() = runBlocking {
        val body = "remote-body-secret"
        val httpFailure = gateway(MockEngine {
            respond(ByteReadChannel(body), HttpStatusCode.InternalServerError)
        })
        val businessFailure = gateway(MockEngine {
            respondJson("""{"code":500999,"msg":"$body","data":false}""")
        })

        listOf(httpFailure, businessFailure).forEach { target ->
            val failure = authFailure { target.findTenantCandidates("13800138000") }
            assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, failure.error)
            assertFalse(failure.toString().contains(body))
        }
    }

    @Test
    fun `keeps invalid credentials and account not found business failures recognizable`() = runBlocking {
        val invalidCredentials = gateway(MockEngine {
            respondJson("""{"code":401001,"msg":"invalid password","data":false}""")
        })
        val accountNotFound = gateway(MockEngine {
            respondJson("""{"code":404001,"msg":"account not found","data":false}""")
        })

        assertEquals(OaAuthenticationError.INVALID_CREDENTIALS, authFailure {
            invalidCredentials.login("13800138000", "Abcdef12".toCharArray(), "t-1")
        }.error)
        assertEquals(OaAuthenticationError.ACCOUNT_NOT_FOUND, authFailure {
            accountNotFound.findTenantCandidates("13800138000")
        }.error)
    }

    @Test
    fun `maps timeout and unavailable transport and propagates cancellation`() = runBlocking {
        val timeout = gateway(MockEngine {
            delay(200)
            respondJson("""{"code":200,"msg":"ok","data":[]}""")
        }, requestTimeoutMs = 25)
        val unavailable = gateway(MockEngine { throw ConnectException("remote endpoint secret") })
        val cancelled = gateway(MockEngine { throw CancellationException("caller cancelled") })

        assertEquals(OaAuthenticationError.REMOTE_TIMEOUT, authFailure { timeout.findTenantCandidates("13800138000") }.error)
        assertEquals(OaAuthenticationError.REMOTE_UNAVAILABLE, authFailure { unavailable.findTenantCandidates("13800138000") }.error)
        assertFailsWith<CancellationException> { cancelled.findTenantCandidates("13800138000") }
    }

    @Test
    fun `rejects candidate platform mismatch before making a request`() = runBlocking {
        var calls = 0
        val gateway = gateway(MockEngine {
            calls += 1
            respondJson("""{"code":200,"msg":"ok","data":true}""")
        })
        val candidate = OaCandidateAccess("u-1", "t-1", 8, "candidate-token")

        val permissionFailure = authFailure { gateway.loadPermissionInfo(candidate) }
        val logoutFailure = authFailure { gateway.logout(candidate) }

        assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, permissionFailure.error)
        assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, logoutFailure.error)
        assertEquals(0, calls)
        assertFalse(permissionFailure.toString().contains("candidate-token"))
    }

    @Test
    fun `maps permission user mismatch and permission JSON type errors to protocol error`() = runBlocking {
        val bodies = listOf(
            """{"code":200,"msg":"ok","data":{"permissions":[],"roles":[],"user":{"id":"other"},"menus":[]}}""",
            """{"code":200,"msg":"ok","data":{"permissions":{},"roles":[],"user":{"id":"u-1"},"menus":[]}}""",
            """{"code":200,"msg":"ok","data":{"permissions":[],"roles":[],"user":[],"menus":[]}}""",
            """{"code":200,"msg":"ok","data":{"permissions":[],"roles":[],"user":{"id":"u-1"},"menus":{}}}""",
        )
        val candidate = OaCandidateAccess("u-1", "t-1", 7, "candidate-token")

        bodies.forEach { body ->
            val failure = authFailure {
                gateway(MockEngine { respondJson(body) }).loadPermissionInfo(candidate)
            }
            assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, failure.error)
            assertFalse(failure.toString().contains("candidate-token"))
            assertFalse(failure.toString().contains(body))
        }
    }

    @Test
    fun `redacts malformed refresh response and token bundle string forms`() = runBlocking {
        val remoteSecret = "remote-refresh-secret"
        val gateway = gateway(MockEngine {
            respondJson("""{"code":200,"msg":"$remoteSecret","data":{"accessToken":"access-only"}}""")
        })

        val failure = authFailure { gateway.refresh("t-1", "refresh-secret") }
        val bundle = OaTokenBundle("access-secret", "refresh-secret", "u-1", 123)

        assertEquals(OaAuthenticationError.REMOTE_PROTOCOL_ERROR, failure.error)
        assertFalse(failure.toString().contains(remoteSecret))
        assertFalse(failure.toString().contains("refresh-secret"))
        assertFalse(bundle.toString().contains("access-secret"))
        assertFalse(bundle.toString().contains("refresh-secret"))
    }

    @Test
    fun `public bundle owns one client exposes isolated ports and closes idempotently`() {
        val engine = CloseCountingEngine(MockEngine { respondJson("""{"code":200,"msg":"ok","data":true}""") })
        val bundle: OaAuthenticationGatewayBundle = OaAuthenticationGatewayFactory.create(
            "https://oa.example.test", "/api", 7, 3_000, engine,
        )
        val pre: OaPreAuthenticationGateway = bundle.preAuthentication
        val candidate: OaCandidateAuthenticationGateway = bundle.candidateAuthentication

        assertFalse(pre is OaCandidateAuthenticationGateway)
        assertFalse(candidate is OaPreAuthenticationGateway)
        assertFalse(pre.javaClass.name.contains("KtorOaAuthenticationGateway"))
        assertFalse(candidate.javaClass.name.contains("KtorOaAuthenticationGateway"))
        val publicFactoryMethods = OaAuthenticationGatewayFactory::class.java.methods
            .filter { it.declaringClass == OaAuthenticationGatewayFactory::class.java }
        assertTrue(publicFactoryMethods.none { it.returnType == KtorOaAuthenticationGateway::class.java })

        bundle.close()
        bundle.close()

        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun `encodes plus slash equals spaces and Unicode in auth query parameters`() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val gateway = gateway(MockEngine { request ->
            requests += request
            when (request.url.encodedPath) {
                "/api/system/auth/get-users-by-mobile" -> respondJson(
                    """{"code":200,"msg":"ok","data":[{"userId":"u-1","tenantId":"t-1","platformId":7,"tenantEnterStatus":1}]}""",
                )
                else -> respondJson(
                    """{"code":200,"msg":"ok","data":{"accessToken":"access-2","refreshToken":"refresh-2","userId":"u-1","expiresTime":456}}""",
                )
            }
        })
        val mobile = "+8613800138000"
        val refreshToken = "refresh+ / =空格雪"

        gateway.findTenantCandidates(mobile)
        gateway.refresh("t-1", refreshToken)

        assertEquals(mobile, requests[0].url.parameters["mobile"])
        assertEquals("mobile=%2B8613800138000", requests[0].url.encodedQuery)
        assertEquals(refreshToken, requests[1].url.parameters["refreshToken"])
        assertEquals(
            "refreshToken=refresh%2B+%2F+%3D%E7%A9%BA%E6%A0%BC%E9%9B%AA",
            requests[1].url.encodedQuery,
        )
    }

    private fun gateway(engine: MockEngine, requestTimeoutMs: Long = 3_000) = KtorOaAuthenticationGateway(
        baseUrl = "https://oa.example.test",
        apiPrefix = "/api",
        platformId = 7,
        requestTimeoutMs = requestTimeoutMs,
        engine = engine,
    )

    private fun MockRequestHandleScope.respondJson(body: String) = respond(
        content = ByteReadChannel(body),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private suspend fun authFailure(block: suspend () -> Any?): OaAuthenticationException =
        assertFailsWith { block() }

    private class CloseCountingEngine(
        private val delegate: HttpClientEngine,
    ) : HttpClientEngine by delegate {
        var closeCalls: Int = 0
            private set

        override fun close() {
            closeCalls += 1
            delegate.close()
        }
    }
}
