package com.wzx.huitai.agent.application

import com.wzx.huitai.action.ActionBusResult
import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ExecutionBinding
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionTransition
import com.wzx.huitai.action.port.ExecutionTransitionResult
import com.wzx.huitai.action.port.ReconciliationClaimRequest
import com.wzx.huitai.action.port.ReconciliationClaimResult
import com.wzx.huitai.action.port.ReconciliationExecutionUpdate
import com.wzx.huitai.action.port.ReconciliationReleaseRequest
import com.wzx.huitai.action.port.ReconciliationReleaseResult
import com.wzx.huitai.action.port.ReconciliationRenewRequest
import com.wzx.huitai.action.port.ReconciliationRenewResult
import com.wzx.huitai.action.port.ReconciliationUpdateResult
import com.wzx.huitai.action.port.ScopedActionExecutionQuery
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.protocol.ActionEnvelope
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.CommonApplicationFields
import com.wzx.huitai.agent.protocol.JsonRpcRequest
import com.wzx.huitai.agent.protocol.JsonRpcNotification
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationActionRequestHandlerTest {
    @Test
    fun `status publication cache is bounded access ordered and permits evicted replay`() = runTest {
        val connection = RecordingConnection()
        val rpc = AgentJsonRpcClient(connection, backgroundScope)
        val status = ApplicationActionStatusClient(rpc, AtomicLong(1)::incrementAndGet, { NOW }, publicationCapacity = 2)
        val scopes = listOf("execution-1", "execution-2", "execution-3")

        scopes.forEach { executionId ->
            status.accepted(publication().copy(correlation = correlation().copy(executionId = executionId)), "framework.demo")
        }
        assertEquals(2, status.publishedCount)
        val sentAfterThree = connection.sent.size

        status.accepted(publication().copy(correlation = correlation().copy(executionId = "execution-3")), "framework.demo")
        assertEquals(sentAfterThree, connection.sent.size)
        status.accepted(publication().copy(correlation = correlation().copy(executionId = "execution-1")), "framework.demo")
        assertEquals(sentAfterThree + 1, connection.sent.size)
        assertEquals(2, status.publishedCount)
        rpc.close()
    }

    @Test
    fun `same execution id in a new scope does not collide with active runtime entry`() = runTest {
        val fixture = Fixture(backgroundScope)
        val releaseOld = CompletableDeferred<Unit>()
        fixture.executor.block = { command ->
            if (command.identityScope == TRUSTED_SCOPE) {
                releaseOld.await()
                ActionBusResult.InProgress(command.executionId, ActionExecutionState.EXECUTING)
            } else {
                ActionBusResult.Rejected(ActionError(ActionErrorCode.EXECUTION_CONFLICT, "safe"))
            }
        }
        fixture.serverRequest("scope-a", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        val scopeB = TRUSTED_SCOPE.copy(tenantId = "tenant-b", identityEpoch = 9)
        fixture.currentIdentity = TrustedApplicationIdentity(scopeB, setOf("case:read"))

        fixture.serverRequest("scope-b", ApplicationMethod.ACTION_REQUEST, requestEnvelope(2).copy(common = commonFor(2, scopeB)))
        runCurrent()

        assertEquals(2, fixture.executor.calls)
        assertNotNull(fixture.response("scope-b")["result"])
        assertEquals(false, releaseOld.isCompleted)
        assertEquals(
            listOf(ApplicationMethod.ACTION_ACCEPTED.wireName, ApplicationMethod.ACTION_ACCEPTED.wireName, ApplicationMethod.ACTION_REJECTED.wireName),
            fixture.connection.sent.mapNotNull(::methodOrNull),
        )
        releaseOld.complete(Unit)
        fixture.close()
    }

    @Test
    fun `same execution id in a new scope does not collide with completed tombstone`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.executor.block = { command ->
            if (command.identityScope == TRUSTED_SCOPE) {
                fixture.store.current = record(command, ActionExecutionState.SUCCEEDED)
                ActionBusResult.Completed(assertNotNull(fixture.store.current?.result))
            } else {
                ActionBusResult.Rejected(ActionError(ActionErrorCode.EXECUTION_CONFLICT, "safe"))
            }
        }
        fixture.serverRequest("complete-a", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()
        fixture.store.current = null
        val scopeB = TRUSTED_SCOPE.copy(tenantId = "tenant-b", identityEpoch = 9)
        fixture.currentIdentity = TrustedApplicationIdentity(scopeB, setOf("case:read"))

        fixture.serverRequest("retry-b", ApplicationMethod.ACTION_REQUEST, requestEnvelope(2).copy(common = commonFor(2, scopeB)))
        runCurrent()

        assertEquals(2, fixture.executor.calls)
        assertNotNull(fixture.response("retry-b")["result"])
        assertEquals(
            listOf(
                ApplicationMethod.ACTION_ACCEPTED.wireName,
                ApplicationMethod.ACTION_COMPLETED.wireName,
                ApplicationMethod.ACTION_ACCEPTED.wireName,
                ApplicationMethod.ACTION_REJECTED.wireName,
            ),
            fixture.connection.sent.mapNotNull(::methodOrNull),
        )
        fixture.close()
    }

    @Test
    fun `action request freezes one trusted identity snapshot for validation command context and publication`() = runTest {
        val fixture = Fixture(backgroundScope)
        val switched = TrustedApplicationIdentity(
            TRUSTED_SCOPE.copy(tenantId = "tenant-2", identityEpoch = 9),
            setOf("other:permission"),
        )
        var reads = 0
        fixture.identityProvider = {
            reads += 1
            if (reads == 1) fixture.currentIdentity else switched
        }

        fixture.serverRequest("frozen-identity", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()

        assertEquals(1, reads)
        assertEquals(TRUSTED_SCOPE, assertNotNull(fixture.executor.command).identityScope)
        assertEquals(TRUSTED_SCOPE, assertNotNull(fixture.executor.context).identityScope)
        assertEquals(setOf("case:read", "case:write"), assertNotNull(fixture.executor.context).permissions)
        val accepted = fixture.connection.sent.map { it.json() }.single {
            it["method"]?.jsonPrimitive?.content == ApplicationMethod.ACTION_ACCEPTED.wireName
        }.getValue("params").jsonObject
        assertEquals(TRUSTED_SCOPE.tenantId, accepted.getValue("tenantId").jsonPrimitive.content)
        assertEquals(TRUSTED_SCOPE.identityEpoch, accepted.getValue("identityEpoch").jsonPrimitive.content.toLong())
        fixture.close()
    }

    @Test
    fun `duplicate probing uses only exact scoped query and never unscoped store lookup`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.store.current = record(command(), ActionExecutionState.SUCCEEDED)
        fixture.store.failUnscopedFind = true

        fixture.serverRequest("scoped-duplicate", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        runCurrent()

        assertNotNull(fixture.response("scoped-duplicate")["result"])
        assertEquals(0, fixture.executor.calls)
        assertEquals(0, fixture.store.unscopedFindCalls)
        assertEquals(emptyList(), fixture.connection.sent.mapNotNull(::methodOrNull))
        fixture.close()
    }

    @Test
    fun `execution hidden by another scope follows normal bus path without existence disclosure`() = runTest {
        val fixture = Fixture(backgroundScope)
        val otherScope = TRUSTED_SCOPE.copy(tenantId = "other-tenant", identityEpoch = 7)
        fixture.store.current = record(command().copy(identityScope = otherScope), ActionExecutionState.SUCCEEDED)
        fixture.store.failUnscopedFind = true
        fixture.executor.block = {
            ActionBusResult.Rejected(ActionError(ActionErrorCode.EXECUTION_CONFLICT, "safe"))
        }

        fixture.serverRequest("hidden-other-scope", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()

        assertEquals(1, fixture.executor.calls)
        assertEquals(0, fixture.store.unscopedFindCalls)
        assertNotNull(fixture.response("hidden-other-scope")["result"])
        assertEquals(
            listOf(ApplicationMethod.ACTION_ACCEPTED.wireName, ApplicationMethod.ACTION_REJECTED.wireName),
            fixture.connection.sent.mapNotNull(::methodOrNull),
        )
        fixture.close()
    }

    @Test
    fun `request publishes accepted and rpc success before one trusted dispatch`() = runTest {
        val fixture = Fixture(backgroundScope)
        val release = CompletableDeferred<Unit>()
        fixture.executor.block = { release.await(); ActionBusResult.InProgress(it.executionId, ActionExecutionState.EXECUTING) }

        fixture.serverRequest("request-1", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.serverRequest("request-2", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 2))
        fixture.executor.entered.await()
        runCurrent()

        assertEquals(
            listOf(
                ApplicationMethod.ACTION_ACCEPTED.wireName,
                "response:${"request-1".testJsonRpcId()}",
                "response:${"request-2".testJsonRpcId()}",
            ),
            fixture.connection.sent.take(3).map(::messageKind),
        )
        assertEquals(1, fixture.executor.calls)
        val command = assertNotNull(fixture.executor.command)
        assertEquals("framework.demo", command.actionId)
        assertEquals(2, command.actionVersion)
        assertEquals(ActionOrigin.AGENT, command.origin)
        assertEquals("page-1", command.pageId)
        assertEquals(7, command.contextRevision)
        assertEquals("secret-value", command.input.getValue("value").jsonPrimitive.content)
        assertEquals(TRUSTED_SCOPE, command.identityScope)
        val context = assertNotNull(fixture.executor.context)
        assertEquals(setOf("case:read", "case:write"), context.permissions)

        release.complete(Unit)
        fixture.close()
    }

    @Test
    fun `identity mismatch returns stable protocol error without dispatch or sensitive values`() = runTest {
        val fixture = Fixture(backgroundScope)
        val mismatched = requestEnvelope(sequence = 1).copy(
            common = common(sequence = 1).copy(tenantId = "secret-other-tenant"),
        )

        fixture.serverRequest("bad-request", ApplicationMethod.ACTION_REQUEST, mismatched)
        runCurrent()

        val response = fixture.connection.sent.single().json()
        assertEquals("bad-request".testJsonRpcId().toString(), response.getValue("id").jsonPrimitive.content)
        assertEquals(-32041, response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content.toInt())
        assertEquals("PROTOCOL_ERROR", response.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        assertEquals(false, fixture.connection.sent.single().contains("secret-other-tenant"))
        assertEquals(0, fixture.executor.calls)
        fixture.close()
    }

    @Test
    fun `pollable store progress is emitted once and persisted terminal wins`() = runTest {
        val fixture = Fixture(backgroundScope)
        val release = CompletableDeferred<Unit>()
        fixture.executor.block = { command ->
            fixture.store.current = record(command, ActionExecutionState.RECEIVED)
            release.await()
            ActionBusResult.Completed(assertNotNull(fixture.store.current?.result))
        }

        fixture.serverRequest("lifecycle", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        listOf(
            ActionExecutionState.PREVIEWED,
            ActionExecutionState.PREVIEWED,
            ActionExecutionState.WAITING_APPROVAL,
            ActionExecutionState.EXECUTING,
        ).forEach { state ->
            fixture.store.current = record(assertNotNull(fixture.executor.command), state)
            advanceTimeBy(2)
            runCurrent()
        }
        fixture.store.current = record(assertNotNull(fixture.executor.command), ActionExecutionState.SUCCEEDED)
        release.complete(Unit)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(
            listOf(
                ApplicationMethod.ACTION_ACCEPTED.wireName,
                ApplicationMethod.ACTION_RUNNING.wireName,
                ApplicationMethod.ACTION_COMPLETED.wireName,
            ),
            fixture.connection.sent.mapNotNull(::methodOrNull),
        )
        fixture.close()
    }

    @Test
    fun `progress payloads expose only safe preview and error summaries from real action results`() = runTest {
        val fixture = Fixture(backgroundScope)
        val command = command()
        val preview = com.wzx.huitai.action.model.ActionPreview(
            executionId = command.executionId,
            summary = "将更新两个字段",
            redactedInput = buildJsonObject { put("mobile", "[REDACTED]") },
        )
        val previewRecord = record(command, ActionExecutionState.PREVIEWED)
        val approvalRecord = record(command, ActionExecutionState.WAITING_APPROVAL)
        val previewResult = ActionResult.Preview(preview)
        val approvalResult = ActionResult.ApprovalRequired(
            command.executionId, "approval-1", preview, "高风险确认", NOW.toEpochMilli() + 60_000,
        )
        val failedRecord = record(command, ActionExecutionState.FAILED).copy(
            result = ActionResult.Failure(
                command.executionId,
                ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "请求失败，请稍后重试"),
            ),
        )

        fixture.status.publish(publication(), previewRecord, projectedResult = previewResult)
        fixture.status.publish(publication(), approvalRecord, projectedResult = approvalResult)
        fixture.status.publish(publication(), failedRecord)

        val payloads = fixture.connection.sent.map { it.json().getValue("params").jsonObject.getValue("payload").jsonObject }
        assertEquals("将更新两个字段", payloads[0].getValue("previewSummary").jsonPrimitive.content)
        assertEquals("将更新两个字段", payloads[1].getValue("previewSummary").jsonPrimitive.content)
        assertEquals("请求失败，请稍后重试", payloads[2].getValue("errorSummary").jsonPrimitive.content)
        assertEquals(false, fixture.connection.sent.joinToString().contains("[REDACTED]"))
        fixture.close()
    }

    @Test
    fun `runtime forwards real executor progress projection without storing intermediate results`() = runTest {
        val fixture = Fixture(backgroundScope)
        val preview = com.wzx.huitai.action.model.ActionPreview(
            executionId = command().executionId,
            summary = "将更新两个字段",
        )
        fixture.executor.progressBlock = { command, progress ->
            fixture.store.current = record(command, ActionExecutionState.PREVIEWED)
            progress(ActionResult.Preview(preview))
            fixture.executor.progressRelease.await()
            fixture.store.current = record(command, ActionExecutionState.FAILED)
            ActionBusResult.Completed(assertNotNull(fixture.store.current?.result))
        }

        fixture.serverRequest("projected-progress", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()
        fixture.executor.progressRelease.complete(Unit)
        runCurrent()

        val previewPayload = fixture.connection.sent.map { it.json() }
            .first { it["method"]?.jsonPrimitive?.content == ApplicationMethod.ACTION_PREVIEWED.wireName }
            .getValue("params").jsonObject.getValue("payload").jsonObject
        assertEquals("将更新两个字段", previewPayload.getValue("previewSummary").jsonPrimitive.content)
        assertEquals(null, fixture.store.current?.result?.takeIf { fixture.store.current?.state == ActionExecutionState.PREVIEWED })
        fixture.close()
    }

    @Test
    fun `polling waits for rich preview and approval projections instead of publishing empty states`() = runTest {
        val fixture = Fixture(backgroundScope)
        val previewPersisted = CompletableDeferred<Unit>()
        val releasePreviewProjection = CompletableDeferred<Unit>()
        val approvalPersisted = CompletableDeferred<Unit>()
        val releaseApprovalProjection = CompletableDeferred<Unit>()
        val releaseTerminal = CompletableDeferred<Unit>()
        val preview = com.wzx.huitai.action.model.ActionPreview(
            executionId = command().executionId,
            summary = "安全预览",
        )
        fixture.executor.progressBlock = { command, progress ->
            fixture.store.current = record(command, ActionExecutionState.PREVIEWED)
            previewPersisted.complete(Unit)
            releasePreviewProjection.await()
            progress(ActionResult.Preview(preview))
            fixture.store.current = record(command, ActionExecutionState.WAITING_APPROVAL)
            approvalPersisted.complete(Unit)
            releaseApprovalProjection.await()
            progress(ActionResult.Preview(preview))
            releaseTerminal.await()
            fixture.store.current = record(command, ActionExecutionState.FAILED)
            ActionBusResult.Completed(assertNotNull(fixture.store.current?.result))
        }

        fixture.serverRequest("empty-first", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        previewPersisted.await()
        advanceTimeBy(2)
        runCurrent()
        assertEquals(
            emptyList(),
            fixture.connection.sent.mapNotNull(::methodOrNull)
                .filter { it == ApplicationMethod.ACTION_PREVIEWED.wireName },
        )

        releasePreviewProjection.complete(Unit)
        approvalPersisted.await()
        runCurrent()
        advanceTimeBy(2)
        runCurrent()
        assertEquals(
            emptyList(),
            fixture.connection.sent.mapNotNull(::methodOrNull)
                .filter { it == ApplicationMethod.ACTION_APPROVAL_REQUIRED.wireName },
        )

        releaseApprovalProjection.complete(Unit)
        runCurrent()
        val progressNotifications = fixture.connection.sent.map { it.json() }.filter {
            it["method"]?.jsonPrimitive?.content in setOf(
                ApplicationMethod.ACTION_PREVIEWED.wireName,
                ApplicationMethod.ACTION_APPROVAL_REQUIRED.wireName,
            )
        }
        assertEquals(
            listOf(
                ApplicationMethod.ACTION_PREVIEWED.wireName,
                ApplicationMethod.ACTION_APPROVAL_REQUIRED.wireName,
            ),
            progressNotifications.map { it.getValue("method").jsonPrimitive.content },
        )
        assertEquals(
            listOf("安全预览", "安全预览"),
            progressNotifications.map {
                it.getValue("params").jsonObject.getValue("payload").jsonObject
                    .getValue("previewSummary").jsonPrimitive.content
            },
        )

        releaseTerminal.complete(Unit)
        runCurrent()
        fixture.close()
    }

    @Test
    fun `same state polling cannot overwrite a richer projected progress`() = runTest {
        val fixture = Fixture(backgroundScope)
        val record = record(command(), ActionExecutionState.PREVIEWED)
        val preview = ActionResult.Preview(
            com.wzx.huitai.action.model.ActionPreview(command().executionId, "安全预览"),
        )
        val slot = RuntimePublicationSlot(backgroundScope) { fixture.status }

        slot.offerProgress(PublicationIntent.Record(publication(), record, projectedResult = preview))
        slot.offerProgress(PublicationIntent.Record(publication(), record))
        runCurrent()

        val payload = fixture.connection.sent.single().json()
            .getValue("params").jsonObject.getValue("payload").jsonObject
        assertEquals("安全预览", payload.getValue("previewSummary").jsonPrimitive.content)
        slot.close()
        fixture.close()
    }

    @Test
    fun `every persisted terminal maps exactly and rejected is not reported as failed`() = runTest {
        val cases = listOf(
            ActionExecutionState.SUCCEEDED to ApplicationMethod.ACTION_COMPLETED,
            ActionExecutionState.FAILED to ApplicationMethod.ACTION_FAILED,
            ActionExecutionState.CANCELED to ApplicationMethod.ACTION_CANCELED,
            ActionExecutionState.EXPIRED to ApplicationMethod.ACTION_EXPIRED,
            ActionExecutionState.OUTCOME_UNKNOWN to ApplicationMethod.ACTION_OUTCOME_UNKNOWN,
        )
        for ((state, method) in cases) {
            val fixture = Fixture(backgroundScope)
            val command = command()
            fixture.status.publish(publication(), record(command, state))
            fixture.status.publish(publication(), record(command, state))
            assertEquals(listOf(method.wireName), fixture.connection.sent.mapNotNull(::methodOrNull))
            fixture.close()
        }

        val rejected = Fixture(backgroundScope)
        val command = command()
        val persisted = record(command, ActionExecutionState.FAILED)
        rejected.status.publish(
            publication(),
            persisted,
            rejection = ActionError(ActionErrorCode.PERMISSION_DENIED, "secret-denial"),
        )
        assertEquals(listOf(ApplicationMethod.ACTION_REJECTED.wireName), rejected.connection.sent.mapNotNull(::methodOrNull))
        val rejectionPayload = rejected.connection.sent.single().json()
            .getValue("params").jsonObject.getValue("payload").jsonObject
        assertEquals("[REDACTED]", rejectionPayload.getValue("errorSummary").jsonPrimitive.content)
        assertEquals(false, rejected.connection.sent.single().contains("secret-denial"))
        rejected.close()
    }

    @Test
    fun `persisted failed record waits for bus rejection classification`() = runTest {
        val fixture = Fixture(backgroundScope)
        val release = CompletableDeferred<Unit>()
        fixture.executor.block = { command ->
            fixture.store.current = record(command, ActionExecutionState.FAILED)
            release.await()
            ActionBusResult.Rejected(ActionError(ActionErrorCode.PERMISSION_DENIED, "secret-denial"))
        }

        fixture.serverRequest("rejected", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(ApplicationMethod.ACTION_ACCEPTED.wireName), fixture.connection.sent.mapNotNull(::methodOrNull))

        release.complete(Unit)
        runCurrent()
        assertEquals(
            listOf(ApplicationMethod.ACTION_ACCEPTED.wireName, ApplicationMethod.ACTION_REJECTED.wireName),
            fixture.connection.sent.mapNotNull(::methodOrNull),
        )
        fixture.close()
    }

    @Test
    fun `pre-create rejection is emitted when execution store has no record`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.executor.block = {
            ActionBusResult.Rejected(ActionError(ActionErrorCode.ACTION_NOT_FOUND, "secret-missing-action"))
        }

        fixture.serverRequest("pre-create-rejected", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()

        assertNull(fixture.store.current)
        assertEquals(
            listOf(ApplicationMethod.ACTION_ACCEPTED.wireName, ApplicationMethod.ACTION_REJECTED.wireName),
            fixture.connection.sent.mapNotNull(::methodOrNull),
        )
        val rejected = fixture.connection.sent.map { it.json() }.single {
            it["method"]?.jsonPrimitive?.content == ApplicationMethod.ACTION_REJECTED.wireName
        }
        assertEquals("framework.demo", rejected.getValue("params").jsonObject.getValue("payload").jsonObject.getValue("actionId").jsonPrimitive.content)
        assertEquals("action_not_found", rejected.getValue("params").jsonObject.getValue("payload").jsonObject.getValue("errorCode").jsonPrimitive.content)
        assertEquals(false, fixture.connection.sent.joinToString().contains("secret-missing-action"))
        fixture.close()
    }

    @Test
    fun `accepted ownership survives failed rpc acknowledgement and still dispatches once`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.connection.failResponseId = "ack-fails"
        fixture.executor.block = { command ->
            fixture.store.current = record(command, ActionExecutionState.SUCCEEDED)
            ActionBusResult.Completed(assertNotNull(fixture.store.current?.result))
        }

        fixture.serverRequest("ack-fails", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()

        assertEquals(1, fixture.executor.calls)
        assertEquals(
            listOf(ApplicationMethod.ACTION_ACCEPTED.wireName, ApplicationMethod.ACTION_COMPLETED.wireName),
            fixture.connection.sent.mapNotNull(::methodOrNull),
        )
        fixture.serverRequest("duplicate-after-ack-failure", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 2))
        runCurrent()
        assertEquals(1, fixture.executor.calls)
        assertNotNull(fixture.response("duplicate-after-ack-failure")["result"])
        fixture.close()
    }

    @Test
    fun `duplicate execution requires identical frozen command binding`() = runTest {
        val variants = listOf(
            requestEnvelope(sequence = 2).copy(payload = requestEnvelope(2).payload.mutate("actionId", kotlinx.serialization.json.JsonPrimitive("other.action"))),
            requestEnvelope(sequence = 2).copy(payload = requestEnvelope(2).payload.mutate("actionVersion", kotlinx.serialization.json.JsonPrimitive(3))),
            requestEnvelope(sequence = 2).copy(payload = requestEnvelope(2).payload.mutate("input", buildJsonObject { put("value", "other-input") })),
            requestEnvelope(sequence = 2).copy(payload = requestEnvelope(2).payload.mutate("pageId", kotlinx.serialization.json.JsonPrimitive("other-page"))),
            requestEnvelope(sequence = 2).copy(payload = requestEnvelope(2).payload.mutate("contextRevision", kotlinx.serialization.json.JsonPrimitive(8))),
        )
        variants.forEachIndexed { index, variant ->
            val fixture = Fixture(backgroundScope)
            fixture.executor.block = { kotlinx.coroutines.awaitCancellation() }
            fixture.serverRequest("start-$index", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
            fixture.executor.entered.await()

            fixture.serverRequest("duplicate-$index", ApplicationMethod.ACTION_REQUEST, variant)
            runCurrent()

            assertEquals(1, fixture.executor.calls)
            assertEquals(
                "PROTOCOL_ERROR",
                fixture.response("duplicate-$index").getValue("error").jsonObject.getValue("message").jsonPrimitive.content,
            )
            fixture.close()
        }
    }

    @Test
    fun `unknown and invalid rpc requests receive protocol errors without stopping later requests`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.executor.block = { ActionBusResult.InProgress(it.executionId, ActionExecutionState.EXECUTING) }
        fixture.connection.serverSend("""{"jsonrpc":"2.0","id":${"unknown".testJsonRpcId()},"method":"application/unknown","params":{}}""")
        fixture.connection.serverSend("""{"jsonrpc":"2.0","id":${"invalid".testJsonRpcId()},"method":"application/action/request","params":{"broken":true}}""")
        fixture.serverRequest("valid-after-invalid", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()

        assertEquals("PROTOCOL_ERROR", fixture.response("unknown").getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        assertEquals("PROTOCOL_ERROR", fixture.response("invalid").getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        assertNotNull(fixture.response("valid-after-invalid")["result"])
        assertEquals(1, fixture.executor.calls)
        fixture.close()
    }

    @Test
    fun `one response or store failure does not stop later valid dispatch`() = runTest {
        val responseFailure = Fixture(backgroundScope)
        responseFailure.connection.failResponseId = "unknown-response-fails"
        responseFailure.connection.serverSend("""{"jsonrpc":"2.0","id":"unknown-response-fails","method":"application/unknown","params":{}}""")
        responseFailure.serverRequest("valid-after-response-failure", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        responseFailure.executor.entered.await()
        assertEquals(1, responseFailure.executor.calls)
        responseFailure.close()

        val storeFailure = Fixture(backgroundScope)
        storeFailure.store.failNextFind = true
        storeFailure.serverRequest("store-fails", ApplicationMethod.ACTION_STATUS, requestEnvelope(sequence = 1))
        storeFailure.serverRequest("valid-after-store-failure", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 2))
        storeFailure.executor.entered.await()
        assertEquals(1, storeFailure.executor.calls)
        storeFailure.close()
    }

    @Test
    fun `blocked best effort final observation cannot block handler close`() = runTest {
        val fixture = Fixture(backgroundScope, cleanupTimeoutMillis = 10)
        fixture.executor.block = {
            fixture.store.blockFind = true
            ActionBusResult.Rejected(ActionError(ActionErrorCode.ACTION_NOT_FOUND, "safe"))
        }
        fixture.serverRequest("blocked-cleanup", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()

        withTimeout(1_000) { fixture.handler.close() }
        fixture.rpc.close()
    }

    @Test
    fun `terminal publication keeps request identity after trusted identity changes`() = runTest {
        val fixture = Fixture(backgroundScope)
        val release = CompletableDeferred<Unit>()
        fixture.executor.block = { command ->
            release.await()
            fixture.store.current = record(command, ActionExecutionState.SUCCEEDED)
            ActionBusResult.Completed(assertNotNull(fixture.store.current?.result))
        }
        fixture.serverRequest("old-identity", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        fixture.currentIdentity = TrustedApplicationIdentity(
            TRUSTED_SCOPE.copy(tenantId = "tenant-2", identityEpoch = 9),
            setOf("case:read"),
        )
        release.complete(Unit)
        runCurrent()

        val terminal = fixture.connection.sent.map { it.json() }.single {
            it["method"]?.jsonPrimitive?.content == ApplicationMethod.ACTION_COMPLETED.wireName
        }.getValue("params").jsonObject
        assertEquals("tenant-1", terminal.getValue("tenantId").jsonPrimitive.content)
        assertEquals(8, terminal.getValue("identityEpoch").jsonPrimitive.content.toLong())
        fixture.close()
    }

    @Test
    fun `completed executions leave active map and bounded tombstones prevent redispatch`() = runTest {
        val fixture = Fixture(backgroundScope, completedCapacity = 2)
        fixture.executor.block = { command ->
            fixture.store.current = record(command, ActionExecutionState.SUCCEEDED)
            ActionBusResult.Completed(assertNotNull(fixture.store.current?.result))
        }
        fixture.serverRequest("first", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()
        assertEquals(0, fixture.handler.activeExecutionCount)
        assertEquals(1, fixture.handler.completedExecutionCount)

        fixture.serverRequest("duplicate", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 2))
        runCurrent()
        assertEquals(1, fixture.executor.calls)
        assertNotNull(fixture.response("duplicate")["result"])

        // Three distinct completions exceed capacity two; completed state remains bounded.
        listOf("execution-2", "execution-3").forEachIndexed { index, executionId ->
            fixture.store.current = null
            fixture.serverRequest(
                "start-$executionId",
                ApplicationMethod.ACTION_REQUEST,
                requestEnvelope(sequence = (3 + index).toLong()).copy(executionId = executionId),
            )
            runCurrent()
        }
        assertEquals(0, fixture.handler.activeExecutionCount)
        assertEquals(2, fixture.handler.completedExecutionCount)
        fixture.close()
    }

    @Test
    fun `persisted terminal binding overrides conflicting completed tombstone`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.executor.block = { command ->
            fixture.store.current = record(command, ActionExecutionState.SUCCEEDED)
            ActionBusResult.Completed(assertNotNull(fixture.store.current?.result))
        }
        fixture.serverRequest("complete-original", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()
        runCurrent()
        assertEquals(1, fixture.handler.completedExecutionCount)

        val conflicting = command().copy(actionId = "persisted.other")
        fixture.store.current = record(conflicting, ActionExecutionState.SUCCEEDED)
        fixture.serverRequest("retry-original", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 2))
        runCurrent()

        assertEquals("PROTOCOL_ERROR", fixture.response("retry-original").getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        assertEquals(1, fixture.executor.calls)
        fixture.close()
    }

    @Test
    fun `cancel notification validates correlation and cancels owned execution job without response`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.executor.block = {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                fixture.store.current = record(it, ActionExecutionState.CANCELED)
                fixture.executor.cancelled.complete(Unit)
            }
        }
        fixture.serverRequest("start", ApplicationMethod.ACTION_REQUEST, requestEnvelope(sequence = 1))
        fixture.executor.entered.await()

        fixture.serverNotification(ApplicationMethod.ACTION_CANCEL, requestEnvelope(sequence = 2).copy(turnId = "other-turn"))
        runCurrent()
        assertEquals(false, fixture.executor.cancelled.isCompleted)
        fixture.serverNotification(ApplicationMethod.ACTION_CANCEL, requestEnvelope(sequence = 3))
        fixture.executor.cancelled.await()
        runCurrent()

        assertEquals(false, fixture.connection.sent.map { it.json() }.any {
            it["id"]?.jsonPrimitive?.content in setOf("wrong", "cancel")
        })
        assertEquals(1, fixture.executor.calls)
        assertEquals(listOf(ApplicationMethod.ACTION_ACCEPTED.wireName, ApplicationMethod.ACTION_CANCELED.wireName),
            fixture.connection.sent.mapNotNull(::methodOrNull))
        fixture.close()
    }

    @Test
    fun `status and result responses come only from persisted redacted record`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.store.current = record(command(), ActionExecutionState.SUCCEEDED)

        fixture.serverRequest("status", ApplicationMethod.ACTION_STATUS, requestEnvelope(sequence = 1))
        fixture.serverRequest("result", ApplicationMethod.ACTION_RESULT_GET, requestEnvelope(sequence = 2))
        runCurrent()

        assertEquals("succeeded", fixture.response("status").getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
        val result = fixture.response("result").getValue("result").jsonObject
        assertEquals("public", result.getValue("output").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(false, fixture.connection.sent.joinToString().contains("private"))

        fixture.store.current = null
        fixture.serverRequest("missing", ApplicationMethod.ACTION_STATUS, requestEnvelope(sequence = 3))
        runCurrent()
        assertEquals("PROTOCOL_ERROR", fixture.response("missing").getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        fixture.close()
    }

    private class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        cleanupTimeoutMillis: Long = 2_000,
        completedCapacity: Int = 1_024,
    ) {
        val connection = RecordingConnection()
        val rpc = AgentJsonRpcClient(connection, scope, requestTimeoutMillis = 1_000)
        val store = ControllableStore()
        val executor = RecordingExecutor()
        private val sequence = AtomicLong(100)
        var currentIdentity = TrustedApplicationIdentity(TRUSTED_SCOPE, setOf("case:read", "case:write"))
        var identityProvider: () -> TrustedApplicationIdentity = { currentIdentity }
        val status = ApplicationActionStatusClient(rpc, sequence::incrementAndGet, { NOW })
        val handler = ApplicationActionRequestHandler(
            rpc = rpc,
            executor = executor,
            executionStore = store,
            scopedQuery = store,
            trustedIdentity = { identityProvider() },
            nextSequence = sequence::incrementAndGet,
            now = { NOW },
            scope = scope,
            statusClient = status,
            statusPollMillis = 1,
            cleanupTimeoutMillis = cleanupTimeoutMillis,
            completedCapacity = completedCapacity,
        )

        suspend fun serverRequest(id: String, method: ApplicationMethod, envelope: ActionEnvelope) {
            connection.serverSend(ApplicationProtocol.JSON.encodeToString(JsonRpcRequest.serializer(), JsonRpcRequest(id = id.testJsonRpcId(), method = method.wireName, params = envelope)))
        }

        suspend fun serverNotification(method: ApplicationMethod, envelope: ActionEnvelope) {
            connection.serverSend(ApplicationProtocol.JSON.encodeToString(JsonRpcNotification.serializer(), JsonRpcNotification(method = method.wireName, params = envelope)))
        }

        fun response(id: String): JsonObject = connection.sent.map { it.json() }.single { it["id"]?.jsonPrimitive?.content == id.testJsonRpcId().toString() }
        suspend fun close() { handler.close(); rpc.close() }
    }

    private class RecordingExecutor : ApplicationActionExecutor {
        var calls = 0
        var command: ActionCommand? = null
        var context: ActionContext? = null
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val progressRelease = CompletableDeferred<Unit>()
        var progressBlock: (suspend (ActionCommand, suspend (ActionResult<*>) -> Unit) -> ActionBusResult)? = null
        var block: suspend (ActionCommand) -> ActionBusResult = { ActionBusResult.InProgress(it.executionId, ActionExecutionState.EXECUTING) }
        override suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult {
            calls += 1
            this.command = command
            this.context = context
            entered.complete(Unit)
            return block(command)
        }
        override suspend fun execute(
            command: ActionCommand,
            context: ActionContext,
            progress: suspend (ActionResult<*>) -> Unit,
        ): ActionBusResult {
            progressBlock?.let { block ->
                calls += 1
                this.command = command
                this.context = context
                entered.complete(Unit)
                return block(command, progress)
            }
            return execute(command, context)
        }
    }

    private class RecordingConnection : AgentConnection {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        override val connectionId = "connection-1"
        override val incoming: ReceiveChannel<String> = inbound
        override val state: StateFlow<AgentConnectionState> = MutableStateFlow(AgentConnectionState.Connected)
        override val hasConnected = true
        val sent = mutableListOf<String>()
        var failResponseId: String? = null
        override suspend fun send(text: String) {
            val value = text.json()
            if (value["id"]?.jsonPrimitive?.content == failResponseId && "result" in value) {
                throw IllegalStateException("response send failed")
            }
            sent += text
        }
        suspend fun serverSend(text: String) { inbound.send(text) }
        override suspend fun close() { inbound.close() }
    }

    private class ControllableStore : ActionExecutionStore, ScopedActionExecutionQuery {
        @Volatile var current: ActionExecutionRecord? = null
        @Volatile var failNextFind = false
        @Volatile var blockFind = false
        @Volatile var failUnscopedFind = false
        var unscopedFindCalls = 0
        override suspend fun find(executionId: String): ActionExecutionRecord? {
            unscopedFindCalls += 1
            if (failUnscopedFind) error("unscoped find is forbidden")
            if (blockFind) kotlinx.coroutines.awaitCancellation()
            if (failNextFind) { failNextFind = false; error("store unavailable") }
            return current?.takeIf { it.command.executionId == executionId }
        }
        override suspend fun find(executionId: String, identityScope: ActionIdentityScope): ActionExecutionRecord? =
            current?.takeIf {
                it.command.executionId == executionId &&
                    it.command.identityScope == identityScope &&
                    it.binding.identityScope == identityScope
            }
        override suspend fun listNonTerminal(identityScope: ActionIdentityScope): List<ActionExecutionRecord> =
            listOfNotNull(current?.takeIf { !it.isTerminal && it.command.identityScope == identityScope })
        override suspend fun compareAndCreate(record: ActionExecutionRecord, audit: ActionAuditDraft): ExecutionCreateResult = error("unused")
        override suspend fun transition(update: ExecutionTransition): ExecutionTransitionResult = error("unused")
        override suspend fun updateReconciliation(update: ReconciliationExecutionUpdate): ReconciliationUpdateResult = error("unused")
        override suspend fun claimReconciliation(request: ReconciliationClaimRequest): ReconciliationClaimResult = error("unused")
        override suspend fun renewReconciliation(request: ReconciliationRenewRequest): ReconciliationRenewResult = error("unused")
        override suspend fun releaseReconciliation(request: ReconciliationReleaseRequest): ReconciliationReleaseResult = error("unused")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-16T10:00:00Z")
        val TRUSTED_SCOPE = ActionIdentityScope("desktop-1", "desktop-session-1", "auth-session-1", 8, "user-1", "tenant-1", "platform-1")
        fun common(sequence: Long) = CommonApplicationFields(ApplicationProtocol.PROTOCOL_VERSION, TRUSTED_SCOPE.desktopInstanceId, TRUSTED_SCOPE.desktopSessionId, TRUSTED_SCOPE.authSessionId, TRUSTED_SCOPE.identityEpoch, sequence, NOW.toString(), TRUSTED_SCOPE.userId, TRUSTED_SCOPE.tenantId, TRUSTED_SCOPE.platformId)
        fun commonFor(sequence: Long, scope: ActionIdentityScope) = CommonApplicationFields(ApplicationProtocol.PROTOCOL_VERSION, scope.desktopInstanceId, scope.desktopSessionId, scope.authSessionId, scope.identityEpoch, sequence, NOW.toString(), scope.userId, scope.tenantId, scope.platformId)
        fun requestEnvelope(sequence: Long) = ActionEnvelope(common(sequence), "thread-1", "turn-1", "tool-1", "execution-1", buildJsonObject {
            put("actionId", "framework.demo"); put("actionVersion", 2); put("input", buildJsonObject { put("value", "secret-value") }); put("pageId", "page-1"); put("contextRevision", 7)
        })
        fun command() = ActionCommand(
            "execution-1", "framework.demo", 2,
            buildJsonObject { put("value", "secret-value") },
            ActionOrigin.AGENT, TRUSTED_SCOPE, "page-1", 7,
            com.wzx.huitai.action.model.ActionCorrelation("thread-1", "turn-1", "tool-1"),
        )
        fun correlation() = ApplicationActionCorrelation("thread-1", "turn-1", "tool-1", "execution-1")
        fun publication() = ApplicationActionPublicationContext(
            correlation(),
            TrustedApplicationIdentity(TRUSTED_SCOPE, setOf("case:read", "case:write")),
        )
        fun record(command: ActionCommand, state: ActionExecutionState): ActionExecutionRecord {
            val result: ActionResult<JsonElement>? = when (state) {
                ActionExecutionState.SUCCEEDED -> ActionResult.Success(command.executionId, buildJsonObject { put("value", "private") }, buildJsonObject { put("value", "public") })
                ActionExecutionState.FAILED -> ActionResult.Failure(command.executionId, ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "secret"))
                ActionExecutionState.CANCELED -> ActionResult.Canceled(command.executionId, "secret")
                ActionExecutionState.EXPIRED -> ActionResult.Expired(command.executionId, "secret")
                ActionExecutionState.OUTCOME_UNKNOWN -> ActionResult.OutcomeUnknown(command.executionId, ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "secret"), reconciliationPolicy = com.wzx.huitai.action.model.ReconciliationPolicy.MANUAL)
                else -> null
            }
            return ActionExecutionRecord(command, ExecutionBinding(command.actionId, command.actionVersion, "fingerprint", command.origin, command.identityScope, command.pageId, command.contextRevision, command.correlation), ActionRiskLevel.READ_ONLY, state, result, NOW, startedAt = if (state == ActionExecutionState.EXECUTING || result != null) NOW else null, completedAt = if (result != null) NOW else null, updatedAt = NOW, recordVersion = 1)
        }
        fun String.json() = ApplicationProtocol.JSON.parseToJsonElement(this).jsonObject
        fun JsonObject.mutate(key: String, value: JsonElement) = JsonObject(toMutableMap().apply { put(key, value) })
        fun methodOrNull(text: String) = text.json()["method"]?.jsonPrimitive?.content
        fun messageKind(text: String): String { val json = text.json(); return json["method"]?.jsonPrimitive?.content ?: "response:${json.getValue("id").jsonPrimitive.content}" }
    }
}

private fun String.testJsonRpcId(): Long = hashCode().toLong() and 0x7fff_ffffL
