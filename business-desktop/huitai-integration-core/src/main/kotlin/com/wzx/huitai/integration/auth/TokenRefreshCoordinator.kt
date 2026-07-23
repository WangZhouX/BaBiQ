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

    data object CredentialsAlreadyRefreshed : TokenRefreshResult
}

/** Serializes token refresh while allowing every concurrent caller to await one shared result. */
class TokenRefreshCoordinator(
    private val sessionManager: AuthSessionManager,
    private val refreshScope: CoroutineScope,
    private val refreshOperation: suspend (tenantId: String, refreshToken: String) -> TokenRefreshResult,
) {
    constructor(
        sessionManager: AuthSessionManager,
        refreshScope: CoroutineScope,
        refreshOperation: suspend (refreshToken: String) -> TokenRefreshResult,
    ) : this(sessionManager, refreshScope, { _, refreshToken -> refreshOperation(refreshToken) })

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<RefreshBoundary, Deferred<RefreshExecution>>()

    suspend fun refreshOnce(): TokenRefreshResult = coordinateRefresh(expectedIdentity = null)

    internal suspend fun refreshOnce(
        expectedIdentity: AuthenticatedRequestIdentity,
    ): TokenRefreshResult = coordinateRefresh(expectedIdentity)

    private suspend fun coordinateRefresh(
        expectedIdentity: AuthenticatedRequestIdentity?,
    ): TokenRefreshResult {
        check(currentCoroutineContext()[RefreshContext.Key]?.coordinator !== this) {
            "Recursive token refresh is not allowed"
        }

        val decision = mutex.withLock {
            val ownerIdentity = expectedIdentity
                ?: sessionManager.requestIdentitySnapshot()
                ?: return@withLock RefreshDecision.Immediate(TokenRefreshResult.AuthenticationExpired)
            val boundary = ownerIdentity.toBoundary()
            val activeRefresh = inFlight[boundary]
            if (activeRefresh != null) {
                RefreshDecision.Await(activeRefresh)
            } else {
                ownerIdentity.immediateResultOrNull()?.let(RefreshDecision::Immediate)
                    ?: createRefresh(boundary, ownerIdentity).also { created -> inFlight[boundary] = created }
                        .let(RefreshDecision::Await)
            }
        }
        return when (decision) {
            is RefreshDecision.Immediate -> decision.result
            is RefreshDecision.Await -> {
                decision.refresh.start()
                when (val execution = decision.refresh.await()) {
                    is RefreshExecution.Completed -> execution.result
                    is RefreshExecution.Failed -> throw execution.failure
                }
            }
        }
    }

    private fun createRefresh(
        boundary: RefreshBoundary,
        expectedIdentity: AuthenticatedRequestIdentity,
    ): Deferred<RefreshExecution> {
        lateinit var created: Deferred<RefreshExecution>
        created = refreshScope.async(
            start = CoroutineStart.LAZY,
        ) {
            try {
                try {
                    RefreshExecution.Completed(
                        withContext(RefreshContext(this@TokenRefreshCoordinator)) {
                            executeRefresh(expectedIdentity)
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
                        if (inFlight[boundary] === created) inFlight.remove(boundary)
                    }
                }
            }
        }
        return created
    }

    private suspend fun executeRefresh(
        expectedIdentity: AuthenticatedRequestIdentity,
    ): TokenRefreshResult {
        expectedIdentity.immediateResultOrNull()?.let { return it }
        val startIdentity = expectedIdentity
        val refreshToken = sessionManager.refreshTokenIfCurrent(startIdentity)
        if (refreshToken == null) {
            return startIdentity.immediateResultOrNull()
                ?: TokenRefreshResult.AuthenticationExpired
        }

        val result = try {
            refreshOperation(startIdentity.tenantId, refreshToken)
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
            TokenRefreshResult.CredentialsAlreadyRefreshed -> false
        }
        return if (applied) result else TokenRefreshResult.Stale
    }

    private fun AuthenticatedRequestIdentity?.immediateResultOrNull(): TokenRefreshResult? {
        if (this == null) return null
        val currentIdentity = sessionManager.requestIdentitySnapshot()
            ?: return TokenRefreshResult.Stale
        if (!currentIdentity.hasSameBoundary(this)) return TokenRefreshResult.Stale
        return if (currentIdentity.accessToken != accessToken) {
            TokenRefreshResult.CredentialsAlreadyRefreshed
        } else {
            null
        }
    }

    private fun AuthenticatedRequestIdentity.hasSameBoundary(other: AuthenticatedRequestIdentity): Boolean =
        authSessionId == other.authSessionId && identityEpoch == other.identityEpoch

    private fun AuthenticatedRequestIdentity.toBoundary() =
        RefreshBoundary(authSessionId = authSessionId, identityEpoch = identityEpoch)

    private class RefreshContext(
        val coordinator: TokenRefreshCoordinator,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RefreshContext>
    }

    private sealed interface RefreshExecution {
        data class Completed(val result: TokenRefreshResult) : RefreshExecution

        data class Failed(val failure: Throwable) : RefreshExecution
    }

    private sealed interface RefreshDecision {
        data class Immediate(val result: TokenRefreshResult) : RefreshDecision

        data class Await(val refresh: Deferred<RefreshExecution>) : RefreshDecision
    }

    private data class RefreshBoundary(
        val authSessionId: String,
        val identityEpoch: Long,
    )
}
