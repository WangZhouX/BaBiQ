package com.wzx.huitai.integration.http

import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.integration.auth.AuthenticatedRequestIdentity
import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.TokenRefreshCoordinator
import com.wzx.huitai.integration.auth.TokenRefreshResult
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException

/** Adds authentication and coordinates one bounded retry or replay around the raw transport. */
class HuitaiHttpClient(
    private val transport: HuitaiTransport,
    private val decoder: CommonResultDecoder,
    private val sessionManager: AuthSessionManager,
    private val refreshCoordinator: TokenRefreshCoordinator,
) {
    suspend fun send(request: HuitaiRequest): HuitaiResponse {
        val first = sendAuthenticated(request) ?: return failure(ActionErrorCode.AUTH_EXPIRED)
        return when (val outcome = first.outcome) {
            is HuitaiTransportOutcome.ResponseReceived -> handleReceived(request, first.identity, outcome)
            HuitaiTransportOutcome.NotSent,
            HuitaiTransportOutcome.AmbiguousAfterSend,
            -> handleTransportDecision(request, first.identity, outcome)
        }
    }

    private suspend fun handleReceived(
        request: HuitaiRequest,
        requestIdentity: AuthenticatedRequestIdentity,
        outcome: HuitaiTransportOutcome.ResponseReceived,
    ): HuitaiResponse {
        val decoded = decode(outcome)
        if (decoded.isMembershipExpired()) {
            sessionManager.expireMembershipIfCurrent(
                requestIdentity.authSessionId,
                requestIdentity.identityEpoch,
            )
            return decoded
        }
        if (outcome.httpStatus !in AUTH_EXPIRED_STATUSES) return decoded

        val currentIdentity = sessionManager.requestIdentitySnapshot()
            ?: return failure(ActionErrorCode.AUTH_EXPIRED)
        if (!currentIdentity.hasSameBoundary(requestIdentity)) {
            return failure(ActionErrorCode.AUTH_EXPIRED)
        }
        if (currentIdentity.accessToken != requestIdentity.accessToken) {
            return replayAfterRefresh(request, requestIdentity, outcome)
        }

        when (val refreshResult = refreshSafely()) {
            TokenRefreshResult.AuthenticationExpired,
            TokenRefreshResult.Stale,
            -> return failure(ActionErrorCode.AUTH_EXPIRED)

            TokenRefreshResult.MembershipExpired -> return failure(ActionErrorCode.MEMBERSHIP_EXPIRED)
            is TokenRefreshResult.Refreshed -> Unit
        }
        return replayAfterRefresh(request, requestIdentity, outcome)
    }

    private suspend fun replayAfterRefresh(
        request: HuitaiRequest,
        requestIdentity: AuthenticatedRequestIdentity,
        outcome: HuitaiTransportOutcome.ResponseReceived,
    ): HuitaiResponse {
        val refreshedOutcome = outcome.withAuthenticationRefreshCompleted()
        return when (RequestReplayDecision.decide(request, refreshedOutcome)) {
            RequestReplayDecision.Replay -> sendOnceAndDecode(request, requestIdentity)
            RequestReplayDecision.AuthExpiredNoReplay,
            RequestReplayDecision.NoReplay,
            -> failure(ActionErrorCode.AUTH_EXPIRED)

            RequestReplayDecision.RetryWithoutReconciliation -> sendOnceAndDecode(request, requestIdentity)
            is RequestReplayDecision.OutcomeUnknown -> failure(ActionErrorCode.OUTCOME_UNKNOWN)
        }
    }

    private fun AuthenticatedRequestIdentity.hasSameBoundary(other: AuthenticatedRequestIdentity): Boolean =
        authSessionId == other.authSessionId && identityEpoch == other.identityEpoch

    private suspend fun refreshSafely(): TokenRefreshResult = try {
        refreshCoordinator.refreshOnce()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Error) {
        throw error
    } catch (_: Throwable) {
        TokenRefreshResult.AuthenticationExpired
    }

    private suspend fun handleTransportDecision(
        request: HuitaiRequest,
        requestIdentity: AuthenticatedRequestIdentity,
        outcome: HuitaiTransportOutcome,
    ): HuitaiResponse = when (RequestReplayDecision.decide(request, outcome)) {
        RequestReplayDecision.Replay,
        RequestReplayDecision.RetryWithoutReconciliation,
        -> sendOnceAndDecode(request, requestIdentity)

        is RequestReplayDecision.OutcomeUnknown -> failure(ActionErrorCode.OUTCOME_UNKNOWN)
        RequestReplayDecision.AuthExpiredNoReplay -> failure(ActionErrorCode.AUTH_EXPIRED)
        RequestReplayDecision.NoReplay -> failure(ActionErrorCode.PROTOCOL_ERROR)
    }

    private suspend fun sendOnceAndDecode(
        request: HuitaiRequest,
        expectedIdentity: AuthenticatedRequestIdentity,
    ): HuitaiResponse {
        val retry = sendAuthenticated(request, expectedIdentity) ?: return failure(ActionErrorCode.AUTH_EXPIRED)
        return when (val retryOutcome = retry.outcome) {
            is HuitaiTransportOutcome.ResponseReceived -> decodeTerminalResponse(retry.identity, retryOutcome)
            HuitaiTransportOutcome.AmbiguousAfterSend -> failure(ActionErrorCode.OUTCOME_UNKNOWN)
            HuitaiTransportOutcome.NotSent -> failure(ActionErrorCode.REMOTE_REQUEST_FAILED)
        }
    }

    private suspend fun decodeTerminalResponse(
        requestIdentity: AuthenticatedRequestIdentity,
        outcome: HuitaiTransportOutcome.ResponseReceived,
    ): HuitaiResponse {
        val decoded = decode(outcome)
        when {
            decoded.isMembershipExpired() -> sessionManager.expireMembershipIfCurrent(
                requestIdentity.authSessionId,
                requestIdentity.identityEpoch,
            )

            decoded.isAuthenticationExpired() -> sessionManager.expireAuthenticationIfCurrent(
                requestIdentity.authSessionId,
                requestIdentity.identityEpoch,
            )
        }
        return decoded
    }

    private suspend fun sendAuthenticated(
        request: HuitaiRequest,
        expectedIdentity: AuthenticatedRequestIdentity? = null,
    ): AuthenticatedTransportAttempt? {
        val identity = sessionManager.requestIdentitySnapshot() ?: return null
        if (expectedIdentity != null && !identity.hasSameBoundary(expectedIdentity)) return null
        val headers = LinkedHashMap(request.headers)
        removeHeaderIgnoreCase(headers, HttpHeaders.Authorization)
        removeHeaderIgnoreCase(headers, TENANT_HEADER)
        headers[HttpHeaders.Authorization] = "Bearer ${identity.accessToken}"
        headers[TENANT_HEADER] = identity.tenantId
        val outcome = try {
            transport.send(request.copyWithHeaders(headers))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Error) {
            throw error
        }
        return AuthenticatedTransportAttempt(identity, outcome)
    }

    private fun decode(outcome: HuitaiTransportOutcome.ResponseReceived): HuitaiResponse =
        decoder.decode(
            httpStatus = outcome.httpStatus,
            contentType = outcome.headers.entries
                .firstOrNull { (name) -> name.equals(HttpHeaders.ContentType, ignoreCase = true) }
                ?.value
                ?.firstOrNull(),
            body = outcome.body,
        )

    private fun HuitaiRequest.copyWithHeaders(headers: Map<String, String>) = HuitaiRequest(
        method = method,
        relativePath = relativePath,
        headers = headers,
        body = body,
        replayPolicy = replayPolicy,
        executionId = executionId,
        idempotencyHeaderName = idempotencyHeaderName,
        reconciliationPolicy = reconciliationPolicy,
    )

    private fun HuitaiTransportOutcome.ResponseReceived.withAuthenticationRefreshCompleted() =
        HuitaiTransportOutcome.ResponseReceived(
            httpStatus = httpStatus,
            headers = headers,
            body = body,
            authenticationRefreshCompleted = true,
        )

    private fun HuitaiResponse.isMembershipExpired(): Boolean =
        this is HuitaiResponse.Failure && errorCode == ActionErrorCode.MEMBERSHIP_EXPIRED

    private fun HuitaiResponse.isAuthenticationExpired(): Boolean =
        this is HuitaiResponse.Failure && errorCode == ActionErrorCode.AUTH_EXPIRED

    private fun removeHeaderIgnoreCase(headers: MutableMap<String, String>, headerName: String) {
        headers.keys.filter { it.equals(headerName, ignoreCase = true) }.forEach(headers::remove)
    }

    private fun failure(errorCode: ActionErrorCode) = HuitaiResponse.Failure(errorCode)

    override fun toString(): String = "HuitaiHttpClient(transport=[REDACTED], session=[REDACTED])"

    private data class AuthenticatedTransportAttempt(
        val identity: AuthenticatedRequestIdentity,
        val outcome: HuitaiTransportOutcome,
    )

    private companion object {
        const val TENANT_HEADER = "tenant-id"
        val AUTH_EXPIRED_STATUSES = setOf(401, 499)
    }
}
