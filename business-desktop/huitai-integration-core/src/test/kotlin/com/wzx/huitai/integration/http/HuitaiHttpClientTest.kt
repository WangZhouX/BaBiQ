package com.wzx.huitai.integration.http

import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.integration.auth.AuthenticationState
import com.wzx.huitai.integration.auth.TokenRefreshCoordinator
import com.wzx.huitai.integration.auth.TokenRefreshResult
import io.ktor.http.HttpHeaders
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HuitaiHttpClientTest {
    @Test
    fun `attaches auth and tenant headers without leaking tokens and decodes success`() = runTest {
        val fixture = fixture(
            refreshScope = backgroundScope,
            outcomes = listOf(jsonResponse(SUCCESS_JSON)),
        )

        val response = fixture.client.send(request(ActionReplayPolicy.SAFE))

        val sent = fixture.transport.requests.single()
        assertEquals("Bearer access-initial", sent.headers[HttpHeaders.Authorization])
        assertEquals(TENANT_ID, sent.headers["tenant-id"])
        val success = assertIs<HuitaiResponse.Success>(response)
        assertEquals("case-1", success.result.data?.jsonObject?.get("caseId")?.jsonPrimitive?.content)
        listOf(sent.toString(), response.toString(), fixture.client.toString()).forEach { rendered ->
            assertFalse("access-initial" in rendered, rendered)
            assertFalse("refresh-initial" in rendered, rendered)
        }
    }

    @Test
    fun `decodes structured failure and binary response without changing bytes`() = runTest {
        val binary = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0xff.toByte())
        val fixture = fixture(
            refreshScope = backgroundScope,
            outcomes = listOf(
                jsonResponse("""{"code":500123,"msg":"validation failed","data":null}"""),
                HuitaiTransportOutcome.ResponseReceived(
                    httpStatus = 200,
                    headers = mapOf(HttpHeaders.ContentType to listOf("application/octet-stream")),
                    body = binary,
                ),
            ),
        )

        val failure = assertIs<HuitaiResponse.Failure>(
            fixture.client.send(request(ActionReplayPolicy.SAFE)),
        )
        assertEquals(ActionErrorCode.REMOTE_REQUEST_FAILED, failure.errorCode)
        assertEquals("500123", failure.remoteCode)
        assertContentEquals(
            binary,
            assertIs<HuitaiResponse.Binary>(
                fixture.client.send(request(ActionReplayPolicy.SAFE)),
            ).body,
        )
    }

    @Test
    fun `401 refreshes once and replays SAFE request with replaced bearer`() = runTest {
        val refreshCalls = AtomicInteger()
        val fixture = fixture(
            refreshScope = backgroundScope,
            outcomes = listOf(authExpiredResponse(401), jsonResponse(SUCCESS_JSON)),
            refreshCalls = refreshCalls,
        )

        val response = fixture.client.send(request(ActionReplayPolicy.SAFE))

        assertIs<HuitaiResponse.Success>(response)
        assertEquals(1, refreshCalls.get())
        assertEquals(2, fixture.transport.requests.size)
        assertEquals("Bearer access-initial", fixture.transport.requests[0].headers[HttpHeaders.Authorization])
        assertEquals("Bearer access-refreshed", fixture.transport.requests[1].headers[HttpHeaders.Authorization])
        assertEquals(tokenSet("refreshed"), fixture.persistence.replacements.last())
    }

    @Test
    fun `staggered old-token 401 responses reuse one completed refresh`() = runTest {
        val persistence = ClientCredentialPersistence()
        val manager = AuthSessionManager(
            credentialPersistence = persistence,
            authSessionIdFactory = { "auth-session-1" },
        )
        manager.login(
            userId = USER_ID,
            tenantId = TENANT_ID,
            platformId = PLATFORM_ID,
            roles = setOf("lawyer"),
            permissions = setOf("case:read"),
            authenticatedAt = AUTHENTICATED_AT,
            tokens = tokenSet("initial"),
        )
        val oldRequestsStarted = CompletableDeferred<Unit>()
        val releaseLateOldResponses = CompletableDeferred<Unit>()
        val firstReplayObserved = CompletableDeferred<Unit>()
        val transport = StaggeredAuthExpiryTransport(
            requestCount = 10,
            oldRequestsStarted = oldRequestsStarted,
            releaseLateOldResponses = releaseLateOldResponses,
            firstReplayObserved = firstReplayObserved,
        )
        val refreshCalls = AtomicInteger()
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            refreshCalls.incrementAndGet()
            TokenRefreshResult.Refreshed(
                userId = USER_ID,
                tenantId = TENANT_ID,
                platformId = PLATFORM_ID,
                roles = setOf("lawyer"),
                permissions = setOf("case:read"),
                authenticatedAt = AUTHENTICATED_AT.plusSeconds(60),
                tokens = tokenSet("refreshed"),
            )
        }
        val client = HuitaiHttpClient(
            transport = transport,
            decoder = CommonResultDecoder(),
            sessionManager = manager,
            refreshCoordinator = coordinator,
        )
        val responses = List(10) {
            async { client.send(request(ActionReplayPolicy.SAFE)) }
        }
        oldRequestsStarted.await()

        firstReplayObserved.await()
        releaseLateOldResponses.complete(Unit)
        val completed = responses.awaitAll()

        assertTrue(completed.all { it is HuitaiResponse.Success })
        assertEquals(1, refreshCalls.get(), "late 401 responses for the old token must reuse the completed refresh")
        assertEquals(10, transport.oldAuthorizationHeaders.size)
        assertTrue(transport.oldAuthorizationHeaders.all { it == "Bearer access-initial" })
        assertEquals(10, transport.replayAuthorizationHeaders.size)
        assertTrue(transport.replayAuthorizationHeaders.all { it == "Bearer access-refreshed" })
    }

    @Test
    fun `late old-session 401 cannot refresh or replay under a newly logged in identity`() = runTest {
        val persistence = ClientCredentialPersistence()
        val sessionSequence = AtomicInteger()
        val manager = AuthSessionManager(
            credentialPersistence = persistence,
            authSessionIdFactory = { "auth-session-${sessionSequence.incrementAndGet()}" },
        )
        manager.login(
            userId = USER_ID,
            tenantId = TENANT_ID,
            platformId = PLATFORM_ID,
            roles = setOf("lawyer"),
            permissions = setOf("case:read"),
            authenticatedAt = AUTHENTICATED_AT,
            tokens = tokenSet("old-session"),
        )
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val transport = DelayedAuthExpiryTransport(requestStarted, releaseResponse)
        val refreshCalls = AtomicInteger()
        val client = HuitaiHttpClient(
            transport = transport,
            decoder = CommonResultDecoder(),
            sessionManager = manager,
            refreshCoordinator = TokenRefreshCoordinator(manager, backgroundScope) {
                refreshCalls.incrementAndGet()
                TokenRefreshResult.AuthenticationExpired
            },
        )
        val response = async { client.send(request(ActionReplayPolicy.SAFE)) }
        requestStarted.await()

        manager.logout()
        manager.login(
            userId = "user-new",
            tenantId = "tenant-new",
            platformId = PLATFORM_ID,
            roles = setOf("partner"),
            permissions = setOf("case:write"),
            authenticatedAt = AUTHENTICATED_AT.plusSeconds(120),
            tokens = tokenSet("new-session"),
        )
        releaseResponse.complete(Unit)

        assertEquals(
            ActionErrorCode.AUTH_EXPIRED,
            assertIs<HuitaiResponse.Failure>(response.await()).errorCode,
        )
        assertEquals(0, refreshCalls.get())
        assertEquals(1, transport.callCount.get())
        assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
        assertEquals("user-new", manager.identity.value?.userId)
        assertEquals("tenant-new", manager.requestIdentitySnapshot()?.tenantId)
    }

    @Test
    fun `499 replays only correctly keyed request after refresh`() = runTest {
        val request = request(
            replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
            headers = mapOf(IDEMPOTENCY_HEADER to EXECUTION_ID),
            executionId = EXECUTION_ID,
            idempotencyHeaderName = IDEMPOTENCY_HEADER,
        )
        val fixture = fixture(
            refreshScope = backgroundScope,
            outcomes = listOf(authExpiredResponse(499), jsonResponse(SUCCESS_JSON)),
        )

        assertIs<HuitaiResponse.Success>(fixture.client.send(request))
        assertEquals(2, fixture.transport.requests.size)
        assertEquals(EXECUTION_ID, fixture.transport.requests.last().headers[IDEMPOTENCY_HEADER])
    }

    @Test
    fun `unsafe and invalid keyed requests never replay after received auth expiry`() = runTest {
        val requests = listOf(
            request(ActionReplayPolicy.NEVER),
            request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                executionId = EXECUTION_ID,
                idempotencyHeaderName = IDEMPOTENCY_HEADER,
            ),
        )

        requests.forEach { request ->
            val refreshCalls = AtomicInteger()
            val fixture = fixture(
                refreshScope = backgroundScope,
                outcomes = listOf(authExpiredResponse(401)),
                refreshCalls = refreshCalls,
            )

            val failure = assertIs<HuitaiResponse.Failure>(fixture.client.send(request))

            assertEquals(ActionErrorCode.AUTH_EXPIRED, failure.errorCode)
            assertEquals(1, refreshCalls.get())
            assertEquals(1, fixture.transport.requests.size)
        }
    }

    @Test
    fun `membership expiry bypasses refresh and remains distinct`() = runTest {
        val refreshCalls = AtomicInteger()
        val fixture = fixture(
            refreshScope = backgroundScope,
            outcomes = listOf(jsonResponse(MEMBERSHIP_EXPIRED_JSON)),
            refreshCalls = refreshCalls,
        )

        val failure = assertIs<HuitaiResponse.Failure>(
            fixture.client.send(request(ActionReplayPolicy.SAFE)),
        )

        assertEquals(ActionErrorCode.MEMBERSHIP_EXPIRED, failure.errorCode)
        assertEquals(0, refreshCalls.get())
        assertEquals(1, fixture.transport.requests.size)
    }

    @Test
    fun `ambiguous NEVER becomes outcome unknown while replay safe requests run once more`() = runTest {
        val neverFixture = fixture(
            refreshScope = backgroundScope,
            outcomes = listOf(HuitaiTransportOutcome.AmbiguousAfterSend),
        )
        val unknown = assertIs<HuitaiResponse.Failure>(
            neverFixture.client.send(request(ActionReplayPolicy.NEVER)),
        )
        assertEquals(ActionErrorCode.OUTCOME_UNKNOWN, unknown.errorCode)
        assertEquals(1, neverFixture.transport.requests.size)

        listOf(
            request(ActionReplayPolicy.SAFE),
            request(
                replayPolicy = ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED,
                headers = mapOf(IDEMPOTENCY_HEADER to EXECUTION_ID),
                executionId = EXECUTION_ID,
                idempotencyHeaderName = IDEMPOTENCY_HEADER,
            ),
        ).forEach { replaySafe ->
            val fixture = fixture(
                refreshScope = backgroundScope,
                outcomes = listOf(HuitaiTransportOutcome.AmbiguousAfterSend, jsonResponse(SUCCESS_JSON)),
            )
            assertIs<HuitaiResponse.Success>(fixture.client.send(replaySafe))
            assertEquals(2, fixture.transport.requests.size)
        }
    }

    @Test
    fun `generic transport IO ambiguity never replays unsafe request`() = runTest {
        val fixture = fixture(
            refreshScope = backgroundScope,
            outcomes = listOf(HuitaiTransportOutcome.AmbiguousAfterSend),
        )

        val failure = assertIs<HuitaiResponse.Failure>(
            fixture.client.send(request(ActionReplayPolicy.NEVER)),
        )

        assertEquals(ActionErrorCode.OUTCOME_UNKNOWN, failure.errorCode)
        assertEquals(1, fixture.transport.requests.size)
    }

    @Test
    fun `not sent retries once without reconciliation`() = runTest {
        val fixture = fixture(
            refreshScope = backgroundScope,
            outcomes = listOf(HuitaiTransportOutcome.NotSent, jsonResponse(SUCCESS_JSON)),
        )

        assertIs<HuitaiResponse.Success>(fixture.client.send(request(ActionReplayPolicy.NEVER)))
        assertEquals(2, fixture.transport.requests.size)
    }

    @Test
    fun `tenant switching transition fails closed instead of sending a mixed identity`() = runTest {
        val persistence = ClientCredentialPersistence()
        val manager = AuthSessionManager(
            credentialPersistence = persistence,
            authSessionIdFactory = { "auth-session" },
        )
        manager.login(
            userId = USER_ID,
            tenantId = TENANT_ID,
            platformId = PLATFORM_ID,
            roles = setOf("lawyer"),
            permissions = setOf("case:read"),
            authenticatedAt = AUTHENTICATED_AT,
            tokens = tokenSet("old"),
        )
        val replaceStarted = CompletableDeferred<Unit>()
        val replaceRelease = CompletableDeferred<Unit>()
        persistence.replaceStarted = replaceStarted
        persistence.replaceRelease = replaceRelease
        val switching = async {
            manager.refresh(
                userId = USER_ID,
                tenantId = "tenant-new",
                platformId = PLATFORM_ID,
                roles = setOf("lawyer"),
                permissions = setOf("case:read"),
                authenticatedAt = AUTHENTICATED_AT.plusSeconds(60),
                tokens = tokenSet("new"),
            )
        }
        replaceStarted.await()
        assertEquals(AuthenticationState.SWITCHING_TENANT, manager.state.value)
        val transport = RecordingTransport(listOf(jsonResponse(SUCCESS_JSON)))
        val client = HuitaiHttpClient(
            transport = transport,
            decoder = CommonResultDecoder(),
            sessionManager = manager,
            refreshCoordinator = TokenRefreshCoordinator(manager, backgroundScope) {
                TokenRefreshResult.AuthenticationExpired
            },
        )

        val response = client.send(request(ActionReplayPolicy.SAFE))
        replaceRelease.complete(Unit)
        switching.await()

        assertEquals(ActionErrorCode.AUTH_EXPIRED, assertIs<HuitaiResponse.Failure>(response).errorCode)
        assertTrue(transport.requests.isEmpty(), "transitioning identity must not reach transport")
    }

    @Test
    fun `signed out request fails closed without reaching transport`() = runTest {
        val manager = AuthSessionManager(ClientCredentialPersistence())
        val transport = RecordingTransport(listOf(jsonResponse(SUCCESS_JSON)))
        val client = HuitaiHttpClient(
            transport = transport,
            decoder = CommonResultDecoder(),
            sessionManager = manager,
            refreshCoordinator = TokenRefreshCoordinator(manager, backgroundScope) {
                TokenRefreshResult.AuthenticationExpired
            },
        )

        val failure = assertIs<HuitaiResponse.Failure>(
            client.send(request(ActionReplayPolicy.SAFE)),
        )

        assertEquals(ActionErrorCode.AUTH_EXPIRED, failure.errorCode)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `refresh terminal results retain distinct client errors without replay`() = runTest {
        listOf(
            TokenRefreshResult.AuthenticationExpired to ActionErrorCode.AUTH_EXPIRED,
            TokenRefreshResult.MembershipExpired to ActionErrorCode.MEMBERSHIP_EXPIRED,
        ).forEach { (refreshResult, expectedError) ->
            val fixture = fixture(
                refreshScope = backgroundScope,
                outcomes = listOf(authExpiredResponse(401)),
                refreshResult = { refreshResult },
            )

            val failure = assertIs<HuitaiResponse.Failure>(
                fixture.client.send(request(ActionReplayPolicy.SAFE)),
            )

            assertEquals(expectedError, failure.errorCode)
            assertEquals(1, fixture.transport.requests.size)
            assertEquals(
                if (expectedError == ActionErrorCode.MEMBERSHIP_EXPIRED) {
                    AuthenticationState.MEMBERSHIP_EXPIRED
                } else {
                    AuthenticationState.EXPIRED
                },
                fixture.manager.state.value,
            )
        }
    }

    @Test
    fun `refresh operation failure becomes structured auth expired`() = runTest {
        val fixture = fixture(
            refreshScope = backgroundScope,
            outcomes = listOf(authExpiredResponse(401)),
            refreshResult = { throw IllegalStateException("secret refresh failure") },
        )

        val failure = assertIs<HuitaiResponse.Failure>(
            fixture.client.send(request(ActionReplayPolicy.SAFE)),
        )

        assertEquals(ActionErrorCode.AUTH_EXPIRED, failure.errorCode)
        assertEquals(AuthenticationState.EXPIRED, fixture.manager.state.value)
        assertFalse("secret refresh failure" in failure.toString())
    }

    @Test
    fun `terminal replay response updates session without a third transport call`() = runTest {
        listOf(
            jsonResponse(MEMBERSHIP_EXPIRED_JSON) to ActionErrorCode.MEMBERSHIP_EXPIRED,
            authExpiredResponse(401) to ActionErrorCode.AUTH_EXPIRED,
            authExpiredResponse(499) to ActionErrorCode.AUTH_EXPIRED,
        ).forEach { (terminalResponse, expectedError) ->
            val fixture = fixture(
                refreshScope = backgroundScope,
                outcomes = listOf(authExpiredResponse(401), terminalResponse),
            )

            val failure = assertIs<HuitaiResponse.Failure>(
                fixture.client.send(request(ActionReplayPolicy.SAFE)),
            )

            assertEquals(expectedError, failure.errorCode)
            assertEquals(2, fixture.transport.requests.size)
            assertEquals(
                if (expectedError == ActionErrorCode.MEMBERSHIP_EXPIRED) {
                    AuthenticationState.MEMBERSHIP_EXPIRED
                } else {
                    AuthenticationState.EXPIRED
                },
                fixture.manager.state.value,
            )
        }
    }

    private suspend fun fixture(
        refreshScope: CoroutineScope,
        outcomes: List<HuitaiTransportOutcome>,
        refreshCalls: AtomicInteger = AtomicInteger(),
        refreshResult: suspend () -> TokenRefreshResult = {
            TokenRefreshResult.Refreshed(
                userId = USER_ID,
                tenantId = TENANT_ID,
                platformId = PLATFORM_ID,
                roles = setOf("lawyer"),
                permissions = setOf("case:read"),
                authenticatedAt = AUTHENTICATED_AT.plusSeconds(60),
                tokens = tokenSet("refreshed"),
            )
        },
    ): ClientFixture {
        val persistence = ClientCredentialPersistence()
        val manager = AuthSessionManager(
            credentialPersistence = persistence,
            authSessionIdFactory = { "auth-session-1" },
        )
        manager.login(
            userId = USER_ID,
            tenantId = TENANT_ID,
            platformId = PLATFORM_ID,
            roles = setOf("lawyer"),
            permissions = setOf("case:read"),
            authenticatedAt = AUTHENTICATED_AT,
            tokens = tokenSet("initial"),
        )
        val transport = RecordingTransport(outcomes)
        val coordinator = TokenRefreshCoordinator(manager, refreshScope) {
            refreshCalls.incrementAndGet()
            refreshResult()
        }
        return ClientFixture(
            client = HuitaiHttpClient(
                transport = transport,
                decoder = CommonResultDecoder(),
                sessionManager = manager,
                refreshCoordinator = coordinator,
            ),
            transport = transport,
            persistence = persistence,
            manager = manager,
        )
    }

    private fun request(
        replayPolicy: ActionReplayPolicy,
        headers: Map<String, String> = emptyMap(),
        executionId: String? = null,
        idempotencyHeaderName: String? = null,
    ) = HuitaiRequest(
        method = "POST",
        relativePath = "/framework/example",
        headers = headers,
        body = "{}".encodeToByteArray(),
        replayPolicy = replayPolicy,
        executionId = executionId,
        idempotencyHeaderName = idempotencyHeaderName,
        reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
    )

    private fun jsonResponse(body: String) = HuitaiTransportOutcome.ResponseReceived(
        httpStatus = 200,
        headers = mapOf(HttpHeaders.ContentType to listOf("application/json")),
        body = body.encodeToByteArray(),
    )

    private fun authExpiredResponse(status: Int) = HuitaiTransportOutcome.ResponseReceived(
        httpStatus = status,
        headers = mapOf(HttpHeaders.ContentType to listOf("application/json")),
        body = """{"code":$status,"msg":"authentication expired","data":null}""".encodeToByteArray(),
    )

    private companion object {
        const val USER_ID = "user-1"
        const val TENANT_ID = "tenant-1"
        const val PLATFORM_ID = "platform-1"
        const val EXECUTION_ID = "execution-1"
        const val IDEMPOTENCY_HEADER = "X-Execution-Id"
        const val SUCCESS_JSON = """{"code":200,"msg":"success","data":{"caseId":"case-1"}}"""
        const val MEMBERSHIP_EXPIRED_JSON =
            """{"code":1002010000,"msg":"membership expired","data":null}"""
        val AUTHENTICATED_AT: Instant = Instant.parse("2026-07-15T00:00:00Z")

        fun tokenSet(suffix: String) = AuthTokenSet(
            accessToken = "access-$suffix",
            refreshToken = "refresh-$suffix",
        )
    }
}

private data class ClientFixture(
    val client: HuitaiHttpClient,
    val transport: RecordingTransport,
    val persistence: ClientCredentialPersistence,
    val manager: AuthSessionManager,
)

private class RecordingTransport(
    outcomes: List<HuitaiTransportOutcome>,
) : HuitaiTransport {
    private val outcomes = ArrayDeque(outcomes)
    val requests = mutableListOf<HuitaiRequest>()

    override suspend fun send(request: HuitaiRequest): HuitaiTransportOutcome {
        requests += request
        return outcomes.removeFirst()
    }
}

private class StaggeredAuthExpiryTransport(
    private val requestCount: Int,
    private val oldRequestsStarted: CompletableDeferred<Unit>,
    private val releaseLateOldResponses: CompletableDeferred<Unit>,
    private val firstReplayObserved: CompletableDeferred<Unit>,
) : HuitaiTransport {
    private val oldRequestOrdinal = AtomicInteger()
    private val oldRequestCount = AtomicInteger()
    val oldAuthorizationHeaders = ConcurrentLinkedQueue<String?>()
    val replayAuthorizationHeaders = ConcurrentLinkedQueue<String?>()

    override suspend fun send(request: HuitaiRequest): HuitaiTransportOutcome {
        val authorization = request.headers[HttpHeaders.Authorization]
        return if (authorization == "Bearer access-initial") {
            oldAuthorizationHeaders += authorization
            val ordinal = oldRequestOrdinal.incrementAndGet()
            if (oldRequestCount.incrementAndGet() == requestCount) oldRequestsStarted.complete(Unit)
            if (ordinal > 1) releaseLateOldResponses.await()
            authExpiredResponse()
        } else {
            replayAuthorizationHeaders += authorization
            firstReplayObserved.complete(Unit)
            successResponse()
        }
    }

    private fun authExpiredResponse() = HuitaiTransportOutcome.ResponseReceived(
        httpStatus = 401,
        headers = mapOf(HttpHeaders.ContentType to listOf("application/json")),
        body = """{"code":401,"msg":"authentication expired","data":null}""".encodeToByteArray(),
    )

    private fun successResponse() = HuitaiTransportOutcome.ResponseReceived(
        httpStatus = 200,
        headers = mapOf(HttpHeaders.ContentType to listOf("application/json")),
        body = """{"code":200,"msg":"success","data":{"caseId":"case-1"}}""".encodeToByteArray(),
    )
}

private class DelayedAuthExpiryTransport(
    private val requestStarted: CompletableDeferred<Unit>,
    private val releaseResponse: CompletableDeferred<Unit>,
) : HuitaiTransport {
    val callCount = AtomicInteger()

    override suspend fun send(request: HuitaiRequest): HuitaiTransportOutcome {
        callCount.incrementAndGet()
        requestStarted.complete(Unit)
        releaseResponse.await()
        return HuitaiTransportOutcome.ResponseReceived(
            httpStatus = 401,
            headers = mapOf(HttpHeaders.ContentType to listOf("application/json")),
            body = """{"code":401,"msg":"authentication expired","data":null}""".encodeToByteArray(),
        )
    }
}

private class ClientCredentialPersistence : AuthCredentialPersistencePort {
    val replacements = mutableListOf<AuthTokenSet>()
    var replaceStarted: CompletableDeferred<Unit>? = null
    var replaceRelease: CompletableDeferred<Unit>? = null

    override suspend fun load(): AuthTokenSet? = replacements.lastOrNull()

    override suspend fun replace(tokens: AuthTokenSet) {
        replaceStarted?.complete(Unit)
        replaceRelease?.await()
        replacements += tokens
    }

    override suspend fun clear() = Unit
}
