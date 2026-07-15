package com.wzx.huitai.integration.http

import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy

/** 根据请求声明和精确传输结果生成重试或对账决策。 */
sealed interface RequestReplayDecision {
    data object RetryWithoutReconciliation : RequestReplayDecision

    data object Replay : RequestReplayDecision

    data class OutcomeUnknown(
        val reconciliationPolicy: ReconciliationPolicy,
    ) : RequestReplayDecision

    data object AuthExpiredNoReplay : RequestReplayDecision

    data object NoReplay : RequestReplayDecision

    companion object {
        fun decide(
            request: HuitaiRequest,
            outcome: HuitaiTransportOutcome,
        ): RequestReplayDecision = when (outcome) {
            HuitaiTransportOutcome.NotSent -> RetryWithoutReconciliation
            HuitaiTransportOutcome.AmbiguousAfterSend -> request.afterAmbiguousSend()
            is HuitaiTransportOutcome.ResponseReceived -> request.afterResponse(outcome)
        }

        private fun HuitaiRequest.afterAmbiguousSend(): RequestReplayDecision =
            if (isReplaySafe()) {
                Replay
            } else {
                OutcomeUnknown(reconciliationPolicy)
            }

        private fun HuitaiRequest.afterResponse(
            outcome: HuitaiTransportOutcome.ResponseReceived,
        ): RequestReplayDecision {
            if (outcome.httpStatus !in AUTH_EXPIRED_STATUSES) return NoReplay
            if (!outcome.authenticationRefreshCompleted) return NoReplay
            return if (isReplaySafe()) Replay else AuthExpiredNoReplay
        }

        private fun HuitaiRequest.isReplaySafe(): Boolean = when (replayPolicy) {
            ActionReplayPolicy.SAFE -> true
            ActionReplayPolicy.IDEMPOTENCY_KEY_REQUIRED -> hasAttachedIdempotencyKey()
            ActionReplayPolicy.NEVER -> false
        }

        private val AUTH_EXPIRED_STATUSES = setOf(401, 499)
    }
}
