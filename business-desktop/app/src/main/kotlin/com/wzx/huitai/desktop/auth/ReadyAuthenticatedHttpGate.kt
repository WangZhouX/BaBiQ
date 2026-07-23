package com.wzx.huitai.desktop.auth

import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.integration.http.HuitaiResponse

/**
 * READY-only boundary for post-login OA HTTP calls.
 *
 * The complete identity is captured once, checked immediately before the send and checked again
 * before a response can escape to business state. Authentication failures are routed only to the
 * authentication orchestrator callback supplied by the composition root.
 */
class ReadyAuthenticatedHttpGate(
    private val usageGate: ReadyAgentUsageGate,
    private val onAuthenticationExpired: suspend () -> Unit,
    private val onMembershipExpired: suspend () -> Unit,
) {
    suspend fun execute(send: suspend (BusinessIdentity) -> HuitaiResponse): HuitaiResponse? {
        val authentication = usageGate.requireReady()
        if (!usageGate.isCurrent(authentication)) throw StaleAgentUsageException()

        val response = send(authentication.identity)
        if (!usageGate.isCurrent(authentication)) return null

        if (response is HuitaiResponse.Failure) {
            when (response.errorCode) {
                ActionErrorCode.AUTH_EXPIRED -> onAuthenticationExpired()
                ActionErrorCode.MEMBERSHIP_EXPIRED -> onMembershipExpired()
                else -> return commitResponse(authentication, response)
            }
            return null
        }
        return commitResponse(authentication, response)
    }

    private fun commitResponse(
        authentication: ReadyAgentUsageSnapshot,
        response: HuitaiResponse,
    ): HuitaiResponse? {
        var committed: HuitaiResponse? = null
        usageGate.commitIfCurrent(authentication) { committed = response }
        return committed
    }
}
