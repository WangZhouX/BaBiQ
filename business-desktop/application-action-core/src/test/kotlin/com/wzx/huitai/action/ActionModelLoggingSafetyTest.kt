package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionPreviewChange
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ReconciliationPolicy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionModelLoggingSafetyTest {
    @Test
    fun `logging boundary models redact identity payload and result secrets`() {
        val secretJson = buildJsonObject { put("token", SECRET) }
        val identity = ActionIdentityScope(
            desktopInstanceId = "$SECRET-desktop",
            desktopSessionId = "$SECRET-session",
            authSessionId = "$SECRET-auth",
            identityEpoch = 7,
            userId = "$SECRET-user",
            tenantId = "$SECRET-tenant",
            platformId = "$SECRET-platform",
        )
        val command = ActionCommand(
            executionId = "execution-safe",
            actionId = "action-safe",
            input = secretJson,
            origin = ActionOrigin.AGENT,
            identityScope = identity,
            pageId = "page-safe",
            contextRevision = 9,
        )
        val error = ActionError(
            code = ActionErrorCode.REMOTE_REQUEST_FAILED,
            message = "$SECRET-message",
            details = secretJson,
        )
        val change = ActionPreviewChange(
            path = "safe.path",
            before = secretJson,
            after = secretJson,
            redacted = true,
        )
        val preview = ActionPreview(
            executionId = "execution-safe",
            summary = "$SECRET-summary",
            redactedInput = secretJson,
            changes = listOf(change),
            warnings = listOf("$SECRET-warning"),
        )
        val results: List<ActionResult<JsonElement>> = listOf(
            ActionResult.Preview(preview),
            ActionResult.ApprovalRequired(
                executionId = "execution-safe",
                approvalId = "approval-safe",
                preview = preview,
                reason = "$SECRET-approval-reason",
                expiresAtEpochMillis = 1_800_000_000_000,
            ),
            ActionResult.Success(
                executionId = "execution-safe",
                output = secretJson,
                redactedOutput = secretJson,
                remoteReference = "$SECRET-remote",
            ),
            ActionResult.Failure(
                executionId = "execution-safe",
                error = error,
                remoteReference = "$SECRET-remote",
            ),
            ActionResult.Canceled("execution-safe", "$SECRET-cancel-reason"),
            ActionResult.Expired("execution-safe", "$SECRET-expire-reason"),
            ActionResult.OutcomeUnknown(
                executionId = "execution-safe",
                error = error,
                remoteReference = "$SECRET-remote",
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            ),
        )

        val values = listOf(identity, command, error, change, preview) + results
        values.forEach { value ->
            val text = value.toString()
            assertFalse(SECRET in text, "${value::class.simpleName} 泄漏了敏感值: $text")
            assertTrue(text.isNotBlank(), "${value::class.simpleName} 应保留安全日志摘要")
        }
        assertTrue("execution-safe" in command.toString())
        assertTrue("action-safe" in command.toString())
        assertTrue("page-safe" in command.toString())
        results.forEach { assertTrue("execution-safe" in it.toString()) }
    }

    private companion object {
        const val SECRET = "secret-marker"
    }
}
