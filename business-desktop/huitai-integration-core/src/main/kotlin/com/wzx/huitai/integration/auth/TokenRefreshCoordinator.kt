package com.wzx.huitai.integration.auth

import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface TokenRefreshResult {
    data class Refreshed(
        val userId: String,
        val tenantId: String,
        val platformId: String,
        val roles: Set<String>,
        val permissions: Set<String>,
        val authenticatedAt: Instant,
        val tokens: AuthTokenSet,
    ) : TokenRefreshResult

    data object AuthenticationExpired : TokenRefreshResult

    data object MembershipExpired : TokenRefreshResult

    data object Stale : TokenRefreshResult
}

/** Serializes token refresh while allowing every concurrent caller to await one shared result. */
class TokenRefreshCoordinator(
    private val sessionManager: AuthSessionManager,
    private val refreshScope: CoroutineScope,
    private val refreshOperation: suspend (refreshToken: String) -> TokenRefreshResult,
) {
    private val mutex = Mutex()
    private var inFlight: Deferred<RefreshExecution>? = null

    suspend fun refreshOnce(): TokenRefreshResult {
        check(currentCoroutineContext()[RefreshContext.Key]?.coordinator !== this) {
            "Recursive token refresh is not allowed"
        }

        val refresh = mutex.withLock {
            inFlight ?: createRefresh().also { created -> inFlight = created }
        }
        refresh.start()
        return when (val execution = refresh.await()) {
            is RefreshExecution.Completed -> execution.result
            is RefreshExecution.Failed -> throw execution.failure
        }
    }

    private fun createRefresh(): Deferred<RefreshExecution> {
        lateinit var created: Deferred<RefreshExecution>
        created = refreshScope.async(
            start = CoroutineStart.LAZY,
        ) {
            try {
                try {
                    RefreshExecution.Completed(
                        withContext(RefreshContext(this@TokenRefreshCoordinator)) {
                            executeRefresh()
                        },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Error) {
                    throw error
                } catch (failure: Throwable) {
                    RefreshExecution.Failed(failure)
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlight === created) inFlight = null
                    }
                }
            }
        }
        return created
    }

    private suspend fun executeRefresh(): TokenRefreshResult {
        val startIdentity = sessionManager.requestIdentitySnapshot()
            ?: return TokenRefreshResult.AuthenticationExpired
        val refreshToken = (sessionManager as AuthTokenProvider).refreshToken()
        if (refreshToken == null) {
            sessionManager.expireAuthenticationIfCurrent(
                startIdentity.authSessionId,
                startIdentity.identityEpoch,
            )
            return TokenRefreshResult.AuthenticationExpired
        }

        val result = try {
            refreshOperation(refreshToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Error) {
            throw error
        } catch (failure: Throwable) {
            try {
                sessionManager.expireAuthenticationIfCurrent(
                    startIdentity.authSessionId,
                    startIdentity.identityEpoch,
                )
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }

        val applied = when (result) {
            is TokenRefreshResult.Refreshed -> sessionManager.refreshIfCurrent(
                expectedAuthSessionId = startIdentity.authSessionId,
                expectedIdentityEpoch = startIdentity.identityEpoch,
                userId = result.userId,
                tenantId = result.tenantId,
                platformId = result.platformId,
                roles = result.roles,
                permissions = result.permissions,
                authenticatedAt = result.authenticatedAt,
                tokens = result.tokens,
            )

            TokenRefreshResult.AuthenticationExpired -> sessionManager.expireAuthenticationIfCurrent(
                startIdentity.authSessionId,
                startIdentity.identityEpoch,
            )

            TokenRefreshResult.MembershipExpired -> sessionManager.expireMembershipIfCurrent(
                startIdentity.authSessionId,
                startIdentity.identityEpoch,
            )

            TokenRefreshResult.Stale -> false
        }
        return if (applied) result else TokenRefreshResult.Stale
    }

    private class RefreshContext(
        val coordinator: TokenRefreshCoordinator,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RefreshContext>
    }

    private sealed interface RefreshExecution {
        data class Completed(val result: TokenRefreshResult) : RefreshExecution

        data class Failed(val failure: Throwable) : RefreshExecution
    }
}
