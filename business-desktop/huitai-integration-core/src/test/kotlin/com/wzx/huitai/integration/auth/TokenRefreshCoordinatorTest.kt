package com.wzx.huitai.integration.auth

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TokenRefreshCoordinatorTest {
    @Test
    fun `refresh operation receives tenant captured from the same request identity`() = runTest {
        val manager = authenticatedManager(RefreshCredentialPersistence())
        var observedTenant: String? = null
        var observedToken: String? = null
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) { tenantId, refreshToken ->
            observedTenant = tenantId
            observedToken = refreshToken
            refreshedResult("tenant-aware")
        }

        assertIs<TokenRefreshResult.Refreshed>(coordinator.refreshOnce())
        assertEquals(TENANT_ID, observedTenant)
        assertEquals("refresh-initial", observedToken)
    }

    @Test
    fun `ten concurrent callers share one refresh and persistence replacement`() = runTest {
        val persistence = RefreshCredentialPersistence()
        val manager = authenticatedManager(persistence)
        val operationStarted = CompletableDeferred<Unit>()
        val operationRelease = CompletableDeferred<Unit>()
        val operationCalls = AtomicInteger()
        val refreshTokens = mutableListOf<String>()
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) { refreshToken ->
            operationCalls.incrementAndGet()
            refreshTokens += refreshToken
            operationStarted.complete(Unit)
            operationRelease.await()
            refreshedResult("new")
        }

        val firstResults = supervisorScope {
            val callers = List(10) {
                async(start = CoroutineStart.UNDISPATCHED) { coordinator.refreshOnce() }
            }
            operationStarted.await()
            assertEquals(1, operationCalls.get())
            operationRelease.complete(Unit)
            callers.map { it.await() }
        }

        assertTrue(firstResults.all { it == refreshedResult("new") })
        assertEquals(listOf("refresh-initial"), refreshTokens)
        assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
        assertEquals(listOf(tokenSet("initial"), tokenSet("new")), persistence.replacements)

        coordinator.refreshOnce()
        assertEquals(2, operationCalls.get(), "completed in-flight refresh must be cleared in finally")
        assertEquals(listOf("refresh-initial", "refresh-new"), refreshTokens)
    }

    @Test
    fun `late caller with old expected identity reuses completed refresh without another operation`() = runTest {
        val manager = authenticatedManager(RefreshCredentialPersistence())
        val oldIdentity = assertNotNull(manager.requestIdentitySnapshot())
        val operationCalls = AtomicInteger()
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            operationCalls.incrementAndGet()
            refreshedResult("rotated")
        }

        assertEquals(refreshedResult("rotated"), coordinator.refreshOnce(oldIdentity))
        assertSame(TokenRefreshResult.CredentialsAlreadyRefreshed, coordinator.refreshOnce(oldIdentity))
        assertEquals(1, operationCalls.get())
        assertEquals("access-rotated", manager.requestIdentitySnapshot()?.accessToken)
    }

    @Test
    fun `expected refresh rejects changed identity boundary without calling operation`() = runTest {
        val persistence = RefreshCredentialPersistence()
        val manager = authenticatedManager(persistence)
        val oldIdentity = assertNotNull(manager.requestIdentitySnapshot())
        manager.logout()
        manager.login(
            userId = "user-new",
            tenantId = "tenant-new",
            platformId = PLATFORM_ID,
            roles = ROLES,
            permissions = PERMISSIONS,
            authenticatedAt = AUTHENTICATED_AT.plusSeconds(120),
            tokens = tokenSet("new-boundary"),
        )
        val operationCalls = AtomicInteger()
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            operationCalls.incrementAndGet()
            refreshedResult("must-not-run")
        }

        assertSame(TokenRefreshResult.Stale, coordinator.refreshOnce(oldIdentity))
        assertEquals(0, operationCalls.get())
        assertEquals("tenant-new", manager.requestIdentitySnapshot()?.tenantId)
        assertEquals("access-new-boundary", manager.requestIdentitySnapshot()?.accessToken)
    }

    @Test
    fun `new session refresh starts without waiting for old session in-flight refresh`() = runTest {
        val persistence = RefreshCredentialPersistence()
        val sessionSequence = AtomicInteger()
        val manager = AuthSessionManager(
            credentialPersistence = persistence,
            authSessionIdFactory = { "auth-session-${sessionSequence.incrementAndGet()}" },
        )
        manager.login(
            userId = USER_ID,
            tenantId = TENANT_ID,
            platformId = PLATFORM_ID,
            roles = ROLES,
            permissions = PERMISSIONS,
            authenticatedAt = AUTHENTICATED_AT,
            tokens = tokenSet("session-a"),
        )
        val identityA = assertNotNull(manager.requestIdentitySnapshot())
        val operationAStarted = CompletableDeferred<Unit>()
        val releaseOperationA = CompletableDeferred<Unit>()
        val operationBStarted = CompletableDeferred<Unit>()
        val operationCalls = AtomicInteger()
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            when (operationCalls.incrementAndGet()) {
                1 -> {
                    operationAStarted.complete(Unit)
                    releaseOperationA.await()
                    refreshedResult("stale-a")
                }

                2 -> {
                    operationBStarted.complete(Unit)
                    TokenRefreshResult.Refreshed(
                        userId = "user-b",
                        tenantId = "tenant-b",
                        platformId = PLATFORM_ID,
                        roles = ROLES,
                        permissions = PERMISSIONS,
                        authenticatedAt = AUTHENTICATED_AT.plusSeconds(120),
                        tokens = tokenSet("refreshed-b"),
                    )
                }

                else -> error("unexpected refresh operation")
            }
        }
        val refreshA = async { coordinator.refreshOnce(identityA) }
        operationAStarted.await()

        manager.logout()
        manager.login(
            userId = "user-b",
            tenantId = "tenant-b",
            platformId = PLATFORM_ID,
            roles = ROLES,
            permissions = PERMISSIONS,
            authenticatedAt = AUTHENTICATED_AT.plusSeconds(60),
            tokens = tokenSet("session-b"),
        )
        val identityB = assertNotNull(manager.requestIdentitySnapshot())
        val refreshB = async { coordinator.refreshOnce(identityB) }

        withTimeout(1_000) { operationBStarted.await() }
        assertEquals(
            TokenRefreshResult.Refreshed(
                userId = "user-b",
                tenantId = "tenant-b",
                platformId = PLATFORM_ID,
                roles = ROLES,
                permissions = PERMISSIONS,
                authenticatedAt = AUTHENTICATED_AT.plusSeconds(120),
                tokens = tokenSet("refreshed-b"),
            ),
            refreshB.await(),
        )
        releaseOperationA.complete(Unit)

        assertSame(TokenRefreshResult.Stale, refreshA.await())
        assertEquals(2, operationCalls.get())
        assertEquals("user-b", manager.identity.value?.userId)
        assertEquals("tenant-b", manager.requestIdentitySnapshot()?.tenantId)
        assertEquals("access-refreshed-b", manager.requestIdentitySnapshot()?.accessToken)
    }

    @Test
    fun `shared refresh failure expires authentication and reaches every waiter`() = runTest {
        val persistence = RefreshCredentialPersistence()
        val manager = authenticatedManager(persistence)
        val operationStarted = CompletableDeferred<Unit>()
        val operationRelease = CompletableDeferred<Unit>()
        val operationCalls = AtomicInteger()
        val expectedFailure = RefreshFailure("refresh endpoint unavailable")
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            operationCalls.incrementAndGet()
            operationStarted.complete(Unit)
            operationRelease.await()
            throw expectedFailure
        }

        val failures = supervisorScope {
            val callers = List(10) {
                async(start = CoroutineStart.UNDISPATCHED) { coordinator.refreshOnce() }
            }
            operationStarted.await()
            assertEquals(1, operationCalls.get())
            operationRelease.complete(Unit)
            callers.map { caller -> runCatching { caller.await() }.exceptionOrNull() }
        }

        assertTrue(failures.all { it === expectedFailure })
        assertEquals(AuthenticationState.EXPIRED, manager.state.value)
        assertEquals(1, persistence.clearCount)
    }

    @Test
    fun `authentication expiry result clears credentials and remains distinct`() = runTest {
        val persistence = RefreshCredentialPersistence()
        val manager = authenticatedManager(persistence)
        val calls = AtomicInteger()
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            calls.incrementAndGet()
            TokenRefreshResult.AuthenticationExpired
        }

        val result = coordinator.refreshOnce()

        assertSame(TokenRefreshResult.AuthenticationExpired, result)
        assertEquals(1, calls.get())
        assertEquals(AuthenticationState.EXPIRED, manager.state.value)
        assertEquals(1, persistence.clearCount)
    }

    @Test
    fun `membership expiry bypasses retry and clears credentials into membership state`() = runTest {
        val persistence = RefreshCredentialPersistence()
        val manager = authenticatedManager(persistence)
        val calls = AtomicInteger()
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            calls.incrementAndGet()
            TokenRefreshResult.MembershipExpired
        }

        val result = coordinator.refreshOnce()

        assertSame(TokenRefreshResult.MembershipExpired, result)
        assertEquals(1, calls.get())
        assertEquals(AuthenticationState.MEMBERSHIP_EXPIRED, manager.state.value)
        assertEquals(1, persistence.clearCount)
    }

    @Test
    fun `refresh operation cannot recursively await its own in-flight refresh`() = runTest {
        val manager = authenticatedManager(RefreshCredentialPersistence())
        lateinit var coordinator: TokenRefreshCoordinator
        coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            coordinator.refreshOnce()
        }

        val failure = assertFailsWith<IllegalStateException> {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(1_000) { coordinator.refreshOnce() }
            }
        }

        assertTrue(failure.message.orEmpty().contains("recursive", ignoreCase = true))
        assertEquals(AuthenticationState.EXPIRED, manager.state.value)
    }

    @Test
    fun `old successful refresh cannot overwrite a newly logged in session`() = runTest {
        val persistence = RefreshCredentialPersistence()
        val sessionSequence = AtomicInteger()
        val manager = AuthSessionManager(
            credentialPersistence = persistence,
            authSessionIdFactory = { "auth-session-${sessionSequence.incrementAndGet()}" },
        )
        manager.login(
            userId = USER_ID,
            tenantId = TENANT_ID,
            platformId = PLATFORM_ID,
            roles = ROLES,
            permissions = PERMISSIONS,
            authenticatedAt = AUTHENTICATED_AT,
            tokens = tokenSet("old"),
        )
        val operationStarted = CompletableDeferred<Unit>()
        val operationRelease = CompletableDeferred<Unit>()
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            operationStarted.complete(Unit)
            operationRelease.await()
            refreshedResult("stale")
        }
        val refreshing = async { coordinator.refreshOnce() }
        operationStarted.await()

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
        operationRelease.complete(Unit)

        assertSame(TokenRefreshResult.Stale, refreshing.await())
        val current = assertNotNull(manager.requestIdentitySnapshot())
        assertEquals("user-new", manager.identity.value?.userId)
        assertEquals("tenant-new", current.tenantId)
        assertEquals("access-new-session", current.accessToken)
        assertEquals(tokenSet("new-session"), persistence.replacements.last())
    }

    @Test
    fun `old terminal refresh result cannot expire a newly logged in session`() = runTest {
        listOf(
            TokenRefreshResult.AuthenticationExpired,
            TokenRefreshResult.MembershipExpired,
        ).forEach { oldResult ->
            val persistence = RefreshCredentialPersistence()
            val manager = authenticatedManager(persistence)
            val operationStarted = CompletableDeferred<Unit>()
            val operationRelease = CompletableDeferred<Unit>()
            val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
                operationStarted.complete(Unit)
                operationRelease.await()
                oldResult
            }
            val refreshing = async { coordinator.refreshOnce() }
            operationStarted.await()

            manager.logout()
            manager.login(
                userId = "user-new",
                tenantId = "tenant-new",
                platformId = PLATFORM_ID,
                roles = ROLES,
                permissions = PERMISSIONS,
                authenticatedAt = AUTHENTICATED_AT.plusSeconds(120),
                tokens = tokenSet("new-${oldResult::class.simpleName}"),
            )
            operationRelease.complete(Unit)

            assertSame(TokenRefreshResult.Stale, refreshing.await())
            assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
            assertEquals("user-new", manager.identity.value?.userId)
            assertEquals("tenant-new", manager.requestIdentitySnapshot()?.tenantId)
        }
    }

    @Test
    fun `old refresh failure propagates without expiring a newly logged in session`() = runTest {
        val persistence = RefreshCredentialPersistence()
        val manager = authenticatedManager(persistence)
        val operationStarted = CompletableDeferred<Unit>()
        val operationRelease = CompletableDeferred<Unit>()
        val expectedFailure = RefreshFailure("old refresh failed")
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            operationStarted.complete(Unit)
            operationRelease.await()
            throw expectedFailure
        }
        supervisorScope {
            val refreshing = async { coordinator.refreshOnce() }
            operationStarted.await()

            manager.logout()
            manager.login(
                userId = "user-new",
                tenantId = "tenant-new",
                platformId = PLATFORM_ID,
                roles = ROLES,
                permissions = PERMISSIONS,
                authenticatedAt = AUTHENTICATED_AT.plusSeconds(120),
                tokens = tokenSet("new-after-failure"),
            )
            operationRelease.complete(Unit)

            assertSame(expectedFailure, runCatching { refreshing.await() }.exceptionOrNull())
            assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
            val current = assertNotNull(manager.requestIdentitySnapshot())
            assertEquals("tenant-new", current.tenantId)
            assertEquals("access-new-after-failure", current.accessToken)
        }
    }

    @Test
    fun `canceling one waiter does not cancel the shared refresh`() = runTest {
        val manager = authenticatedManager(RefreshCredentialPersistence())
        val operationStarted = CompletableDeferred<Unit>()
        val operationRelease = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val coordinator = TokenRefreshCoordinator(manager, backgroundScope) {
            calls.incrementAndGet()
            operationStarted.complete(Unit)
            operationRelease.await()
            refreshedResult("shared-after-cancel")
        }
        val canceledWaiter = async { coordinator.refreshOnce() }
        val survivingWaiter = async { coordinator.refreshOnce() }
        operationStarted.await()

        canceledWaiter.cancel()
        operationRelease.complete(Unit)

        assertEquals(refreshedResult("shared-after-cancel"), survivingWaiter.await())
        assertEquals(1, calls.get())
        assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
    }

    @Test
    fun `canceled only waiter cannot suppress one terminal callback from shared refresh owner`() = runTest {
        val manager = authenticatedManager(RefreshCredentialPersistence())
        val operationStarted = CompletableDeferred<Unit>()
        val operationRelease = CompletableDeferred<Unit>()
        val callbackCompleted = CompletableDeferred<TokenRefreshResult>()
        val callbackCalls = AtomicInteger()
        val coordinator = TokenRefreshCoordinator(
            sessionManager = manager,
            refreshScope = backgroundScope,
            refreshOperation = { _, _ ->
                operationStarted.complete(Unit)
                operationRelease.await()
                TokenRefreshResult.AuthenticationExpired
            },
            terminalStateApplied = { result ->
                callbackCalls.incrementAndGet()
                callbackCompleted.complete(result)
            },
        )
        val canceledWaiter = async { coordinator.refreshOnce() }
        operationStarted.await()

        canceledWaiter.cancel()
        canceledWaiter.join()
        operationRelease.complete(Unit)

        assertSame(
            TokenRefreshResult.AuthenticationExpired,
            withTimeout(1_000) { callbackCompleted.await() },
        )
        assertEquals(AuthenticationState.EXPIRED, manager.state.value)
        assertEquals(1, callbackCalls.get())
        assertSame(TokenRefreshResult.AuthenticationExpired, coordinator.refreshOnce())
        assertEquals(1, callbackCalls.get(), "terminal callback belongs only to the applied shared owner")
    }

    @Test
    fun `operation cancellation does not expire session and clears in-flight`() = runTest {
        val manager = authenticatedManager(RefreshCredentialPersistence())
        val calls = AtomicInteger()
        val coordinator = TokenRefreshCoordinator(
            sessionManager = manager,
            refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ) {
            if (calls.incrementAndGet() == 1) {
                throw kotlinx.coroutines.CancellationException("refresh canceled")
            }
            refreshedResult("after-operation-cancel")
        }

        assertIs<kotlinx.coroutines.CancellationException>(
            runCatching { coordinator.refreshOnce() }.exceptionOrNull(),
        )
        assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
        assertEquals(refreshedResult("after-operation-cancel"), coordinator.refreshOnce())
        assertEquals(2, calls.get())
    }

    @Test
    fun `canceling injected scope cancels in-flight refresh and owner cleanup completes`() = runTest {
        val manager = authenticatedManager(RefreshCredentialPersistence())
        val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val operationStarted = CompletableDeferred<Unit>()
        val coordinator = TokenRefreshCoordinator(
            sessionManager = manager,
            refreshScope = refreshScope,
        ) {
            operationStarted.complete(Unit)
            delay(Long.MAX_VALUE)
            refreshedResult("unreachable")
        }
        val caller = async { coordinator.refreshOnce() }
        operationStarted.await()

        refreshScope.cancel()

        assertIs<kotlinx.coroutines.CancellationException>(
            runCatching { caller.await() }.exceptionOrNull(),
        )
        assertEquals(AuthenticationState.AUTHENTICATED, manager.state.value)
        val replacement = TokenRefreshCoordinator(
            sessionManager = manager,
            refreshScope = CoroutineScope(SupervisorJob() + currentCoroutineContext()),
        ) { refreshedResult("replacement") }
        assertEquals(refreshedResult("replacement"), replacement.refreshOnce())
    }

    private suspend fun authenticatedManager(
        persistence: RefreshCredentialPersistence,
    ): AuthSessionManager = AuthSessionManager(
        credentialPersistence = persistence,
        authSessionIdFactory = { "auth-session-1" },
    ).also { manager ->
        manager.login(
            userId = USER_ID,
            tenantId = TENANT_ID,
            platformId = PLATFORM_ID,
            roles = ROLES,
            permissions = PERMISSIONS,
            authenticatedAt = AUTHENTICATED_AT,
            tokens = tokenSet("initial"),
        )
    }

    private fun refreshedResult(suffix: String) = TokenRefreshResult.Refreshed(
        userId = USER_ID,
        tenantId = TENANT_ID,
        platformId = PLATFORM_ID,
        roles = ROLES,
        permissions = PERMISSIONS,
        authenticatedAt = AUTHENTICATED_AT.plusSeconds(60),
        tokens = tokenSet(suffix),
    )

    private companion object {
        const val USER_ID = "user-1"
        const val TENANT_ID = "tenant-1"
        const val PLATFORM_ID = "platform-1"
        val ROLES = setOf("lawyer")
        val PERMISSIONS = setOf("case:read")
        val AUTHENTICATED_AT: Instant = Instant.parse("2026-07-15T00:00:00Z")

        fun tokenSet(suffix: String) = AuthTokenSet(
            accessToken = "access-$suffix",
            refreshToken = "refresh-$suffix",
        )
    }
}

private class RefreshCredentialPersistence : AuthCredentialPersistencePort {
    val replacements = mutableListOf<AuthTokenSet>()
    var clearCount: Int = 0

    override suspend fun load(): AuthTokenSet? = replacements.lastOrNull()

    override suspend fun replace(tokens: AuthTokenSet) {
        replacements += tokens
    }

    override suspend fun clear() {
        clearCount += 1
    }
}

private class RefreshFailure(message: String) : RuntimeException(message)
