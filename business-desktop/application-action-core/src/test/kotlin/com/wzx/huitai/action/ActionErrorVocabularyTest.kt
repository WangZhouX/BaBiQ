package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ErrorDisposition
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionErrorVocabularyTest {
    @Test
    fun `error vocabulary has the fixed disposition for every code`() {
        val expected = mapOf(
            ActionErrorCode.ACTION_NOT_FOUND to ErrorDisposition.NON_RETRYABLE,
            ActionErrorCode.ACTION_DISABLED to ErrorDisposition.NON_RETRYABLE,
            ActionErrorCode.PERMISSION_DENIED to ErrorDisposition.NON_RETRYABLE,
            ActionErrorCode.VALIDATION_FAILED to ErrorDisposition.USER_FIXABLE,
            ActionErrorCode.CONTEXT_STALE to ErrorDisposition.USER_FIXABLE,
            ActionErrorCode.APPROVAL_DENIED to ErrorDisposition.NON_RETRYABLE,
            ActionErrorCode.APPROVAL_EXPIRED to ErrorDisposition.RETRYABLE,
            ActionErrorCode.EXECUTION_CONFLICT to ErrorDisposition.RETRYABLE,
            ActionErrorCode.EXECUTION_TIMEOUT to ErrorDisposition.RETRYABLE,
            ActionErrorCode.DESKTOP_DISCONNECTED to ErrorDisposition.RETRYABLE,
            ActionErrorCode.AGENT_DISCONNECTED to ErrorDisposition.RETRYABLE,
            ActionErrorCode.AUTH_EXPIRED to ErrorDisposition.RELOGIN_REQUIRED,
            ActionErrorCode.MEMBERSHIP_EXPIRED to ErrorDisposition.RELOGIN_REQUIRED,
            ActionErrorCode.REMOTE_REQUEST_FAILED to ErrorDisposition.RETRYABLE,
            ActionErrorCode.OUTCOME_UNKNOWN to ErrorDisposition.MANUAL_RECONCILIATION,
            ActionErrorCode.PROTOCOL_ERROR to ErrorDisposition.NON_RETRYABLE,
        )

        assertEquals(expected.keys, ActionErrorCode.entries.toSet())
        expected.forEach { (code, disposition) ->
            assertEquals(disposition, code.disposition, code.name)
        }
    }
}
