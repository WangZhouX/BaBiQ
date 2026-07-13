package com.wzx.huitai.action

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionDescriptor
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionPreview
import com.wzx.huitai.action.model.ActionPreviewChange
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ActionTarget
import com.wzx.huitai.action.model.ErrorDisposition
import com.wzx.huitai.action.model.ReconciliationPolicy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonElement.Companion.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionModelSerializationTest {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `descriptor and command round trip with complete identity scope`() {
        val descriptor = ActionDescriptor(
            id = "demo.submit",
            version = 2,
            title = "提交",
            description = "提交当前通用表单",
            inputSchema = buildJsonObject { put("type", "object") },
            riskLevel = ActionRiskLevel.HIGH_RISK,
            requiredPermissions = setOf("demo:submit"),
            target = ActionTarget(pageType = "generic-form", operation = "submit"),
            replayPolicy = ActionReplayPolicy.NEVER,
            reconciliationPolicy = ReconciliationPolicy.MANUAL,
        )
        val command = ActionCommand(
            executionId = "execution-1",
            actionId = descriptor.id,
            input = buildJsonObject { put("confirmed", true) },
            origin = ActionOrigin.AGENT,
            identityScope = ActionIdentityScope(
                desktopInstanceId = "desktop-1",
                desktopSessionId = "session-1",
                authSessionId = "auth-1",
                identityEpoch = 7,
                userId = "user-1",
                tenantId = "tenant-1",
                platformId = "platform-1",
            ),
            pageId = "page-1",
            contextRevision = 9,
        )

        assertRoundTrip(ActionDescriptor.serializer(), descriptor)
        assertRoundTrip(ActionCommand.serializer(), command)
    }

    @Test
    fun `preview approval and every terminal result round trip`() {
        val preview = ActionPreview(
            executionId = "execution-1",
            summary = "将修改 1 个字段",
            redactedInput = buildJsonObject { put("secret", "***") },
            changes = listOf(
                ActionPreviewChange(
                    path = "status",
                    before = buildJsonObject { put("value", "draft") },
                    after = buildJsonObject { put("value", "submitted") },
                    redacted = false,
                ),
            ),
            warnings = listOf("提交后不可直接撤销"),
        )
        val resultSerializer = ActionResult.serializer(serializer())
        val error = ActionError(
            code = ActionErrorCode.REMOTE_REQUEST_FAILED,
            message = "远程请求失败",
            details = buildJsonObject { put("requestId", "request-1") },
        )
        val results: List<ActionResult<JsonElement>> = listOf(
            ActionResult.Preview(preview),
            ActionResult.ApprovalRequired(
                executionId = "execution-1",
                approvalId = "approval-1",
                preview = preview,
                reason = "该操作风险较高",
                expiresAtEpochMillis = 1_800_000_000_000,
            ),
            ActionResult.Success(
                executionId = "execution-1",
                output = buildJsonObject { put("saved", true) },
                redactedOutput = buildJsonObject { put("saved", true) },
                remoteReference = "remote-1",
            ),
            ActionResult.Failure("execution-1", error, remoteReference = "remote-1"),
            ActionResult.Canceled("execution-1", reason = "用户取消"),
            ActionResult.Expired("execution-1", reason = "审批已过期"),
            ActionResult.OutcomeUnknown(
                executionId = "execution-1",
                error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "远程结果未知"),
                remoteReference = "remote-1",
                reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
            ),
        )

        assertRoundTrip(ActionPreview.serializer(), preview)
        results.forEach { assertRoundTrip(resultSerializer, it) }
    }

    @Test
    fun `all wire enums use lower snake case`() {
        assertWireNames(ActionRiskLevel.serializer(), ActionRiskLevel.entries)
        assertWireNames(ActionOrigin.serializer(), ActionOrigin.entries)
        assertWireNames(ActionReplayPolicy.serializer(), ActionReplayPolicy.entries)
        assertWireNames(ReconciliationPolicy.serializer(), ReconciliationPolicy.entries)
        assertWireNames(ActionExecutionState.serializer(), ActionExecutionState.entries)
        assertWireNames(ActionErrorCode.serializer(), ActionErrorCode.entries)
        assertWireNames(ErrorDisposition.serializer(), ErrorDisposition.entries)
        val approvalJson = json.encodeToString(
            ActionResult.serializer(serializer()),
            ActionResult.ApprovalRequired(
                executionId = "execution-1",
                approvalId = "approval-1",
                preview = ActionPreview("execution-1", "预览"),
                reason = "需要确认",
                expiresAtEpochMillis = 1_800_000_000_000,
            ),
        )
        assertTrue("\"type\":\"approval_required\"" in approvalJson)
    }

    private fun <T> assertRoundTrip(serializer: KSerializer<T>, value: T) {
        val encoded = json.encodeToString(serializer, value)
        assertEquals(value, json.decodeFromString(serializer, encoded))
    }

    private fun <T : Enum<T>> assertWireNames(serializer: KSerializer<T>, values: Iterable<T>) {
        values.forEach { value ->
            assertEquals("\"${value.name.lowercase()}\"", json.encodeToString(serializer, value))
        }
    }
}
