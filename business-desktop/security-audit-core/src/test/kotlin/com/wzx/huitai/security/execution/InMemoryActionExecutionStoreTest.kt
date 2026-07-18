package com.wzx.huitai.security.execution

import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ExecutionBinding
import com.wzx.huitai.action.port.ExecutionCreateResult
import com.wzx.huitai.action.port.ExecutionSuccessFact
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryActionExecutionStoreTest {
    @Test
    fun `create rejects command binding inconsistencies before records or audit are written`() = runTest {
        val original = runningRecord()
        val mismatches = listOf(
            original.binding.copy(actionId = "other.action"),
            original.binding.copy(actionVersion = original.binding.actionVersion + 1),
            original.binding.copy(origin = ActionOrigin.USER),
            original.binding.copy(identityScope = original.command.identityScope.copy(tenantId = "other")),
            original.binding.copy(pageId = "other-page"),
            original.binding.copy(contextRevision = original.binding.contextRevision + 1),
        )

        mismatches.forEach { mismatchedBinding ->
            val store = InMemoryActionExecutionStore()

            assertFailsWith<IllegalArgumentException> {
                store.compareAndCreate(original.copy(binding = mismatchedBinding), audit())
            }
            assertNull(store.find(original.command.executionId))
            assertEquals(emptyList(), store.events(original.command.executionId))
        }
    }

    @Test
    fun `scoped query matches all seven identity fields and missing is indistinguishable`() = runTest {
        val store = InMemoryActionExecutionStore()
        val record = runningRecord()
        store.compareAndCreate(record, audit())
        val query: ScopedActionExecutionQuery = store
        val scope = record.command.identityScope
        val mismatches = listOf(
            scope.copy(desktopInstanceId = "other"),
            scope.copy(desktopSessionId = "other"),
            scope.copy(authSessionId = "other"),
            scope.copy(identityEpoch = 2),
            scope.copy(userId = "other"),
            scope.copy(tenantId = "other"),
            scope.copy(platformId = "other"),
        )

        assertEquals(record, query.find(record.command.executionId, scope))
        mismatches.forEach { assertNull(query.find(record.command.executionId, it)) }
        assertNull(query.find("missing", scope))
    }

    @Test
    fun `scoped nonterminal query filters exact session sorts stably and returns defensive snapshots`() = runTest {
        val store = InMemoryActionExecutionStore()
        val scope = command().identityScope
        val later = runningRecord().copy(
            command = command().copy(executionId = "z-execution"),
            binding = binding(command().copy(executionId = "z-execution")),
            createdAt = NOW.plusSeconds(2),
            startedAt = NOW.plusSeconds(2),
            updatedAt = NOW.plusSeconds(2),
        )
        val earlier = runningRecord().copy(
            command = command().copy(executionId = "a-execution"),
            binding = binding(command().copy(executionId = "a-execution")),
        )
        val priorSessionCommand = command().copy(
            executionId = "prior-session",
            identityScope = scope.copy(desktopSessionId = "prior-session"),
        )
        val priorSession = runningRecord().copy(
            command = priorSessionCommand,
            binding = binding(priorSessionCommand),
        )
        store.compareAndCreate(later, audit().copy(executionId = later.command.executionId, occurredAt = later.createdAt))
        store.compareAndCreate(earlier, audit().copy(executionId = earlier.command.executionId))
        store.compareAndCreate(priorSession, audit().copy(executionId = priorSession.command.executionId))
        val query: ScopedActionExecutionQuery = store

        val first = query.listNonTerminal(scope)
        val second = query.listNonTerminal(scope)

        assertEquals(listOf("a-execution", "z-execution"), first.map { it.command.executionId })
        assertNotSame(first, second)
        assertNotSame(first.first(), second.first())
        assertNotSame(first.first().command.input, second.first().command.input)
    }

    @Test
    fun `Created和find返回独立深不可变命令快照且攻击不改变存储事实`() = runTest {
        val input = buildJsonObject {
            put("nested", buildJsonObject { put("value", "original") })
            put("items", buildJsonArray { add(buildJsonObject { put("value", "original") }) })
        }
        val original = runningRecord()
        val record = original.copy(command = original.command.copy(input = input))
        val store = InMemoryActionExecutionStore()

        val created = assertIs<ExecutionCreateResult.Created>(store.compareAndCreate(record, audit())).record
        assertObjectRemovalBlocked(assertIs<JsonObject>(created.command.input["nested"]))
        assertArrayRemovalBlocked(assertIs<JsonArray>(created.command.input["items"]))

        val found = store.find("execution-1")!!
        assertNotSame(created, found)
        assertNotSame(created.command.input, found.command.input)
        assertObjectRemovalBlocked(assertIs<JsonObject>(found.command.input["nested"]))
        assertArrayRemovalBlocked(assertIs<JsonArray>(found.command.input["items"]))

        val persisted = store.find("execution-1")!!
        assertEquals(input, persisted.command.input)
        assertNotSame(found, persisted)
        assertNotSame(found.command.input, persisted.command.input)
    }

    @Test
    fun `Updated和ExistingTerminal返回独立深不可变终态快照且攻击不改变存储事实`() = runTest {
        val successStore = InMemoryActionExecutionStore()
        val running = runningRecord()
        successStore.compareAndCreate(running, audit())
        val output = buildJsonObject {
            put("nested", buildJsonObject { put("saved", true) })
            put("items", buildJsonArray { add(buildJsonObject { put("saved", true) }) })
        }
        val redactedOutput = buildJsonObject { put("nested", buildJsonObject { put("saved", "masked") }) }
        val success: ActionResult<JsonElement> = ActionResult.Success(
            executionId = "execution-1",
            output = output,
            redactedOutput = redactedOutput,
        )

        val updated = assertIs<ExecutionTransitionResult.Updated>(
            successStore.transition(transition(running, ActionExecutionState.SUCCEEDED, success)),
        ).record
        val updatedSuccess = assertIs<ActionResult.Success<JsonElement>>(updated.result)
        assertObjectRemovalBlocked(assertIs<JsonObject>(updatedSuccess.output)["nested"] as JsonObject)
        assertArrayRemovalBlocked(assertIs<JsonObject>(updatedSuccess.output)["items"] as JsonArray)
        assertObjectRemovalBlocked(assertIs<JsonObject>(updatedSuccess.redactedOutput)["nested"] as JsonObject)

        val replay = assertIs<ExecutionCreateResult.ExistingTerminal>(
            successStore.compareAndCreate(running, audit()),
        ).record
        assertNotSame(updated, replay)
        assertNotSame(updated.result, replay.result)
        val replaySuccess = assertIs<ActionResult.Success<JsonElement>>(replay.result)
        assertObjectRemovalBlocked(assertIs<JsonObject>(replaySuccess.output)["nested"] as JsonObject)
        assertEquals(success, successStore.find("execution-1")!!.result)

        val failureStore = InMemoryActionExecutionStore()
        failureStore.compareAndCreate(running, audit())
        val details = buildJsonObject { put("nested", buildJsonObject { put("reason", "original") }) }
        val failure: ActionResult<JsonElement> = ActionResult.Failure(
            "execution-1",
            ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "failed", details),
        )
        val failureRecord = assertIs<ExecutionTransitionResult.Updated>(
            failureStore.transition(transition(running, ActionExecutionState.FAILED, failure)),
        ).record
        val failureReplay = assertIs<ExecutionCreateResult.ExistingTerminal>(
            failureStore.compareAndCreate(running, audit()),
        ).record
        assertNotSame(failureRecord, failureReplay)
        val replayFailure = assertIs<ActionResult.Failure>(failureReplay.result)
        assertObjectRemovalBlocked(replayFailure.error.details!!["nested"] as JsonObject)
        assertEquals(failure, failureStore.find("execution-1")!!.result)
    }

    @Test
    fun `创建和普通迁移拒绝审计状态时间错配且不改变记录事件`() = runTest {
        val validCreateAudit = audit()
        listOf(
            validCreateAudit.copy(fromState = null),
            validCreateAudit.copy(toState = ActionExecutionState.VALIDATING),
            validCreateAudit.copy(occurredAt = NOW.plusSeconds(1)),
        ).forEach { mismatchedAudit ->
            val store = InMemoryActionExecutionStore()

            assertStoreMutationRejected {
                store.compareAndCreate(runningRecord(), mismatchedAudit)
            }
            assertNull(store.find("execution-1"))
            assertEquals(emptyList(), store.events("execution-1"))
        }

        val store = InMemoryActionExecutionStore()
        val running = runningRecord()
        store.compareAndCreate(running, audit())
        val recordBefore = store.find("execution-1")
        val eventsBefore = store.events("execution-1")
        val success: ActionResult<JsonElement> = ActionResult.Success(
            "execution-1",
            buildJsonObject { put("saved", true) },
        )
        val valid = transition(running, ActionExecutionState.SUCCEEDED, success)
        val mismatchedTransitions: List<suspend () -> Any?> = listOf(
            { store.transition(valid.copy(audit = valid.audit.copy(toState = ActionExecutionState.FAILED))) },
            { store.transition(valid.copy(audit = valid.audit.copy(occurredAt = valid.updatedAt.plusSeconds(1)))) },
            { store.transition(valid.copy(completedAt = valid.updatedAt.plusSeconds(1))) },
        )
        mismatchedTransitions.forEach { mutate ->
            assertStoreMutationRejected(mutate)
            assertEquals(recordBefore, store.find("execution-1"))
            assertEquals(eventsBefore, store.events("execution-1"))
        }
    }

    @Test
    fun `对账更新与claim续租释放拒绝审计业务时间错配且不改变记录事件`() = runTest {
        suspend fun assertRollback(
            prepare: suspend (InMemoryActionExecutionStore) -> ActionExecutionRecord,
            mutate: suspend (InMemoryActionExecutionStore, ActionExecutionRecord) -> Any?,
        ) {
            val store = InMemoryActionExecutionStore()
            val current = prepare(store)
            val recordBefore = store.find("execution-1")
            val eventsBefore = store.events("execution-1")

            assertStoreMutationRejected { mutate(store, current) }
            assertEquals(recordBefore, store.find("execution-1"))
            assertEquals(eventsBefore, store.events("execution-1"))
        }

        assertRollback(
            prepare = { installUnknown(it) },
            mutate = { store, unknown ->
                val request = claimRequest(unknown, "token-1", NOW.plusSeconds(3))
                store.claimReconciliation(
                    request.copy(audit = request.audit.copy(occurredAt = request.now.plusSeconds(1))),
                )
            },
        )
        listOf("renew", "release", "final").forEach { operation ->
            assertRollback(
                prepare = { store ->
                    val unknown = installUnknown(store)
                    assertIs<ReconciliationClaimResult.Claimed>(
                        store.claimReconciliation(claimRequest(unknown, "token-1", NOW.plusSeconds(3))),
                    ).record
                },
                mutate = { store, claimed ->
                    when (operation) {
                        "renew" -> {
                            val request = renewRequest(claimed, "token-1")
                            store.renewReconciliation(
                                request.copy(audit = request.audit.copy(occurredAt = request.now.plusSeconds(1))),
                            )
                        }
                        "release" -> {
                            val request = releaseRequest(claimed, "token-1")
                            store.releaseReconciliation(
                                request.copy(audit = request.audit.copy(occurredAt = request.releasedAt.plusSeconds(1))),
                            )
                        }
                        else -> {
                            val update = finalUpdate(claimed, "token-1")
                            store.updateReconciliation(
                                update.copy(audit = update.audit.copy(occurredAt = update.completedAt.plusSeconds(1))),
                            )
                        }
                    }
                },
            )
        }
    }

    @Test
    fun `并发compareAndCreate仅创建一次其余返回同一运行记录`() = runTest {
        val store = InMemoryActionExecutionStore()
        val record = runningRecord()

        val results = (1..32).map { async { store.compareAndCreate(record, audit()) } }.awaitAll()

        assertEquals(1, results.count { it is ExecutionCreateResult.Created })
        assertEquals(31, results.count { it is ExecutionCreateResult.ExistingRunning })
        assertEquals(record, store.find("execution-1"))
        assertEquals(listOf(1L), store.events("execution-1").map { it.sequence })
    }

    @Test
    fun `相同execution的动作和指纹冲突且不改变原记录`() = runTest {
        val store = InMemoryActionExecutionStore()
        val record = runningRecord()
        store.compareAndCreate(record, audit())

        val conflictingCommand = record.command.copy(actionId = "other")
        val conflict = store.compareAndCreate(
            record.copy(
                command = conflictingCommand,
                binding = record.binding.copy(actionId = "other", inputFingerprint = "other"),
            ),
            audit(),
        )

        assertEquals(ActionErrorCode.EXECUTION_CONFLICT, assertIs<ExecutionCreateResult.Conflict>(conflict).error.code)
        assertEquals(record, store.find("execution-1"))
    }

    @Test
    fun `首个终态原样重放且迟到终态不能覆盖`() = runTest {
        val store = InMemoryActionExecutionStore()
        val record = runningRecord()
        store.compareAndCreate(record, audit())
        val success: ActionResult<JsonElement> = ActionResult.Success(
            executionId = "execution-1",
            output = buildJsonObject { put("saved", true) },
        )
        val terminal = assertIs<ExecutionTransitionResult.Updated>(
            store.transition(transition(record, ActionExecutionState.SUCCEEDED, success)),
        ).record

        val late = store.transition(
            transition(
                terminal,
                ActionExecutionState.FAILED,
                ActionResult.Failure("execution-1", ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "late")),
            ),
        )

        val replayed = assertIs<ExecutionTransitionResult.ExistingTerminal>(late).record.result
        assertEquals(terminal.result, replayed)
        assertNotSame(terminal.result, replayed)
        assertEquals(terminal, store.find("execution-1"))
        assertIs<ExecutionCreateResult.ExistingTerminal>(store.compareAndCreate(record, audit()))
    }

    @Test
    fun `OUTCOME_UNKNOWN持久化后只能由有效租约token收束`() = runTest {
        val store = InMemoryActionExecutionStore()
        val running = runningRecord()
        store.compareAndCreate(running, audit())
        val unknownResult: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "unknown"),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        val unknown = assertIs<ExecutionTransitionResult.Updated>(
            store.transition(transition(running, ActionExecutionState.OUTCOME_UNKNOWN, unknownResult)),
        ).record

        assertIs<ExecutionCreateResult.ExistingTerminal>(store.compareAndCreate(running, audit()))
        val claimed = assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(claimRequest(unknown, "token-1", NOW.plusSeconds(3))),
        ).record
        assertIs<ReconciliationUpdateResult.Conflict>(
            store.updateReconciliation(finalUpdate(claimed, "wrong-token")),
        )
        val final = assertIs<ReconciliationUpdateResult.Updated>(
            store.updateReconciliation(finalUpdate(claimed, "token-1")),
        ).record

        assertEquals(ActionExecutionState.SUCCEEDED, final.state)
        assertNull(final.reconciliationClaim)
        assertEquals(claimed.recordVersion, final.reconciliation?.sourceRecordVersion)
    }

    @Test
    fun `claim可在到期边界接管旧token不能续租或释放`() = runTest {
        val store = InMemoryActionExecutionStore()
        val unknown = installUnknown(store)
        val first = assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(claimRequest(unknown, "token-1", NOW.plusSeconds(3))),
        ).record
        assertIs<ReconciliationClaimResult.ExistingClaim>(
            store.claimReconciliation(claimRequest(first, "token-2", NOW.plusSeconds(10))),
        )
        val takeover = assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(claimRequest(first, "token-2", first.reconciliationClaim!!.expiresAt)),
        ).record

        assertEquals("token-2", takeover.reconciliationClaim?.claimToken)
        assertIs<ReconciliationRenewResult.ExistingClaim>(
            store.renewReconciliation(renewRequest(takeover, "token-1")),
        )
        assertIs<ReconciliationReleaseResult.Conflict>(
            store.releaseReconciliation(releaseRequest(takeover, "token-1")),
        )
        val renewed = assertIs<ReconciliationRenewResult.Renewed>(
            store.renewReconciliation(renewRequest(takeover, "token-2")),
        ).record
        assertIs<ReconciliationReleaseResult.Released>(
            store.releaseReconciliation(releaseRequest(renewed, "token-2")),
        )
    }

    @Test
    fun `存储边界重新脱敏审计载荷且快照不可修改`() = runTest {
        val store = InMemoryActionExecutionStore()
        val rawToken = "raw-token-must-not-survive"
        val draft = audit().copy(
            redactedPayload = buildJsonObject { put("accessToken", rawToken) },
        )

        store.compareAndCreate(runningRecord(), draft)

        val events = store.events("execution-1")
        assertEquals("[REDACTED]", events.single().redactedPayload["accessToken"].toString().trim('"'))
        kotlin.test.assertFalse(rawToken in events.toString())
        kotlin.test.assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (events as MutableList<com.wzx.huitai.action.port.ActionAuditEvent>).clear()
        }
    }

    @Test
    fun `输入JsonObject的外部可变map不能改变已存储记录`() = runTest {
        val values = mutableMapOf<String, JsonElement>("value" to JsonPrimitive("before"))
        val original = runningRecord()
        val command = original.command.copy(input = JsonObject(values))
        val record = original.copy(command = command, binding = original.binding)
        val store = InMemoryActionExecutionStore()

        store.compareAndCreate(record, audit())
        values["value"] = JsonPrimitive("after")

        assertEquals("before", store.find("execution-1")!!.command.input["value"].toString().trim('"'))
    }

    @Test
    fun `create和普通终态审计构造失败时记录与事件均不提交`() = runTest {
        val createStore = InMemoryActionExecutionStore()
        assertFailsWith<IllegalStateException> {
            createStore.compareAndCreate(runningRecord(), poisonAudit())
        }
        assertNull(createStore.find("execution-1"))
        assertEquals(emptyList(), createStore.events("execution-1"))

        val transitionStore = InMemoryActionExecutionStore()
        val running = runningRecord()
        transitionStore.compareAndCreate(running, audit())
        val eventsBefore = transitionStore.events("execution-1")
        val update = transition(
            running,
            ActionExecutionState.SUCCEEDED,
            ActionResult.Success("execution-1", buildJsonObject { put("saved", true) }),
        ).let { it.copy(audit = poisonAudit(running.state, ActionExecutionState.SUCCEEDED, at = it.updatedAt)) }

        assertFailsWith<IllegalStateException> { transitionStore.transition(update) }
        assertEquals(running, transitionStore.find("execution-1"))
        assertEquals(eventsBefore, transitionStore.events("execution-1"))
    }

    @Test
    fun `claim续租释放审计构造失败时租约版本与事件均不提交`() = runTest {
        suspend fun assertUnknownMutationRollback(
            prepare: suspend (InMemoryActionExecutionStore, ActionExecutionRecord) -> ActionExecutionRecord,
            mutate: suspend (InMemoryActionExecutionStore, ActionExecutionRecord) -> Unit,
        ) {
            val store = InMemoryActionExecutionStore()
            val current = prepare(store, installUnknown(store))
            val eventsBefore = store.events("execution-1")

            assertFailsWith<IllegalStateException> { mutate(store, current) }
            assertEquals(current, store.find("execution-1"))
            assertEquals(eventsBefore, store.events("execution-1"))
        }

        assertUnknownMutationRollback(
            prepare = { _, unknown -> unknown },
            mutate = { store, unknown ->
                val request = claimRequest(unknown, "token-1", NOW.plusSeconds(3))
                store.claimReconciliation(
                    request.copy(
                        audit = poisonAudit(
                            ActionExecutionState.OUTCOME_UNKNOWN,
                            ActionExecutionState.OUTCOME_UNKNOWN,
                            "reconciliation_attempt",
                            request.now,
                        ),
                    ),
                )
            },
        )
        listOf("renew", "release").forEach { operation ->
            assertUnknownMutationRollback(
                prepare = { store, unknown ->
                    assertIs<ReconciliationClaimResult.Claimed>(
                        store.claimReconciliation(claimRequest(unknown, "token-1", NOW.plusSeconds(3))),
                    ).record
                },
                mutate = { store, claimed ->
                    if (operation == "renew") {
                        val request = renewRequest(claimed, "token-1")
                        store.renewReconciliation(
                            request.copy(
                                audit = poisonAudit(
                                    ActionExecutionState.OUTCOME_UNKNOWN,
                                    ActionExecutionState.OUTCOME_UNKNOWN,
                                    "reconciliation_claim_renewed",
                                    request.now,
                                ),
                            ),
                        )
                    } else {
                        val request = releaseRequest(claimed, "token-1")
                        store.releaseReconciliation(
                            request.copy(
                                audit = poisonAudit(
                                    ActionExecutionState.OUTCOME_UNKNOWN,
                                    ActionExecutionState.OUTCOME_UNKNOWN,
                                    "reconciliation_result",
                                    request.releasedAt,
                                ),
                            ),
                        )
                    }
                },
            )
        }
    }

    @Test
    fun `最终对账审计构造失败时UNKNOWN事实与claim均不提交`() = runTest {
        val store = InMemoryActionExecutionStore()
        val unknown = installUnknown(store)
        val claimed = assertIs<ReconciliationClaimResult.Claimed>(
            store.claimReconciliation(claimRequest(unknown, "token-1", NOW.plusSeconds(3))),
        ).record
        val eventsBefore = store.events("execution-1")
        val validUpdate = finalUpdate(claimed, "token-1")
        val update = validUpdate.copy(
            audit = poisonAudit(
                ActionExecutionState.OUTCOME_UNKNOWN,
                ActionExecutionState.SUCCEEDED,
                "reconciliation_result",
                validUpdate.completedAt,
            ),
        )

        assertFailsWith<IllegalStateException> { store.updateReconciliation(update) }
        assertEquals(claimed, store.find("execution-1"))
        assertEquals(eventsBefore, store.events("execution-1"))
    }

    private suspend fun installUnknown(store: InMemoryActionExecutionStore): ActionExecutionRecord {
        val running = runningRecord()
        store.compareAndCreate(running, audit())
        val result: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            executionId = "execution-1",
            error = ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "unknown"),
            reconciliationPolicy = ReconciliationPolicy.QUERY_REMOTE,
        )
        return assertIs<ExecutionTransitionResult.Updated>(
            store.transition(transition(running, ActionExecutionState.OUTCOME_UNKNOWN, result)),
        ).record
    }

    private fun runningRecord(): ActionExecutionRecord {
        val command = command()
        return ActionExecutionRecord(
            command = command,
            binding = binding(command),
            riskLevel = com.wzx.huitai.action.model.ActionRiskLevel.REVERSIBLE_WRITE,
            state = ActionExecutionState.EXECUTING,
            result = null,
            createdAt = NOW,
            startedAt = NOW,
            updatedAt = NOW,
            recordVersion = 1,
        )
    }

    private fun transition(
        record: ActionExecutionRecord,
        state: ActionExecutionState,
        result: ActionResult<JsonElement>,
    ) = ExecutionTransition(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        state = state,
        result = result,
        updatedAt = record.updatedAt.plusSeconds(1),
        completedAt = record.updatedAt.plusSeconds(1),
        audit = audit(record.state, state, record.updatedAt.plusSeconds(1)),
    )

    private fun claimRequest(record: ActionExecutionRecord, token: String, now: Instant) = ReconciliationClaimRequest(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        claimToken = token,
        ownerId = "owner-$token",
        now = now,
        leaseDuration = Duration.ofSeconds(60),
        audit = audit(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN, now)
            .copy(type = "reconciliation_attempt"),
    )

    private fun renewRequest(record: ActionExecutionRecord, token: String) = ReconciliationRenewRequest(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        claimToken = token,
        now = record.updatedAt.plusSeconds(1),
        leaseDuration = Duration.ofSeconds(60),
        audit = audit(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN, record.updatedAt.plusSeconds(1))
            .copy(type = "reconciliation_claim_renewed"),
    )

    private fun releaseRequest(record: ActionExecutionRecord, token: String) = ReconciliationReleaseRequest(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        claimToken = token,
        releasedAt = record.updatedAt.plusSeconds(1),
        audit = audit(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN, record.updatedAt.plusSeconds(1))
            .copy(type = "reconciliation_result"),
    )

    private fun finalUpdate(record: ActionExecutionRecord, token: String) = ReconciliationExecutionUpdate(
        executionId = "execution-1",
        expectedVersion = record.recordVersion,
        claimToken = token,
        result = null,
        successFact = ExecutionSuccessFact(
            kind = ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS,
            errorCode = null,
            safeMessage = null,
            source = ExecutionSuccessFact.SOURCE_RECONCILIATION,
        ),
        completedAt = record.updatedAt.plusSeconds(1),
        audit = audit(ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED, record.updatedAt.plusSeconds(1))
            .copy(type = "reconciliation_result"),
    )

    private fun command() = ActionCommand(
        executionId = "execution-1",
        actionId = "demo.save",
        actionVersion = 1,
        input = buildJsonObject { put("value", "secret") },
        origin = ActionOrigin.AGENT,
        identityScope = ActionIdentityScope("desktop", "session", "auth", 1, "user", "tenant", "platform"),
        pageId = "page-1",
        contextRevision = 1,
    )

    private fun binding(command: ActionCommand) = ExecutionBinding(
        actionId = command.actionId,
        actionVersion = command.actionVersion,
        inputFingerprint = "fingerprint",
        origin = command.origin,
        identityScope = command.identityScope,
        pageId = command.pageId,
        contextRevision = command.contextRevision,
        correlation = command.correlation,
    )

    private fun audit(
        from: ActionExecutionState? = ActionExecutionState.RECEIVED,
        to: ActionExecutionState = ActionExecutionState.EXECUTING,
        at: Instant = NOW,
    ) = ActionAuditDraft(
        executionId = "execution-1",
        fromState = from,
        toState = to,
        type = "state_transition",
        redactedPayload = buildJsonObject { },
        actorId = null,
        occurredAt = at,
    )

    private fun poisonAudit(
        from: ActionExecutionState? = ActionExecutionState.RECEIVED,
        to: ActionExecutionState = ActionExecutionState.EXECUTING,
        type: String = "state_transition",
        at: Instant = NOW,
    ) = audit(from, to, at).copy(
        type = type,
        redactedPayload = JsonObject(object : AbstractMap<String, JsonElement>() {
            override val entries: Set<Map.Entry<String, JsonElement>>
                get() = error("audit-redaction-failure")
        }),
    )

    private fun assertObjectRemovalBlocked(value: JsonObject) {
        assertFailsWith<UnsupportedOperationException> {
            val iterator = value.entries.iterator()
            iterator.next()
            @Suppress("UNCHECKED_CAST")
            (iterator as MutableIterator<Map.Entry<String, JsonElement>>).remove()
        }
    }

    private fun assertArrayRemovalBlocked(value: JsonArray) {
        assertFailsWith<UnsupportedOperationException> {
            val iterator = value.iterator()
            iterator.next()
            @Suppress("UNCHECKED_CAST")
            (iterator as MutableIterator<JsonElement>).remove()
        }
    }

    private suspend fun assertStoreMutationRejected(mutation: suspend () -> Any?) {
        val rejected = try {
            when (mutation()) {
                is ExecutionCreateResult.Conflict,
                is ExecutionTransitionResult.Conflict,
                is ReconciliationUpdateResult.Conflict,
                is ReconciliationClaimResult.Conflict,
                is ReconciliationRenewResult.Conflict,
                is ReconciliationReleaseResult.Conflict,
                -> true
                else -> false
            }
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(rejected, "审计状态或时间错配必须被拒绝")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
