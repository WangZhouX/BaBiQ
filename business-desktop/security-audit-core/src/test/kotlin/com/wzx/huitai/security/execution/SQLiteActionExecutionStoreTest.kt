package com.wzx.huitai.security.execution

import com.wzx.huitai.action.ActionExecutionCoordinator
import com.wzx.huitai.action.ActionExecutionStart
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionCorrelation
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.model.ActionRiskLevel
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.port.ActionAuditDraft
import com.wzx.huitai.action.port.ActionClock
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
import com.wzx.huitai.security.database.BusinessDesktopDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SQLiteActionExecutionStoreTest {
    @Test
    fun `two database adapters atomically compare and create one execution`() = runTest {
        val fixture = fixture()
        fixture.database.use { database ->
            val first = store(database)
            val second = store(database)
            val record = runningRecord()

            val results = listOf(first, second).map { candidate ->
                async(Dispatchers.IO) { candidate.compareAndCreate(record, audit()) }
            }.awaitAll()

            assertEquals(1, results.count { it is ExecutionCreateResult.Created })
            assertEquals(1, results.count { it is ExecutionCreateResult.ExistingRunning })
            assertEquals(listOf(1L), first.events("execution-1").map { it.sequence })

            val conflicting = record.copy(binding = record.binding.copy(inputFingerprint = "other-fingerprint"))
            assertIs<ExecutionCreateResult.Conflict>(second.compareAndCreate(conflicting, audit()))
        }
    }

    @Test
    fun `restart reconstructs only redacted durable facts while current process retains exact values`() = runTest {
        val fixture = fixture()
        fixture.database.use { database ->
            val current = store(database)
            val running = runningRecord(inputValue = RAW_INPUT)
            assertIs<ExecutionCreateResult.Created>(current.compareAndCreate(running, audit()))
            val success: ActionResult<JsonElement> = ActionResult.Success(
                executionId = "execution-1",
                output = buildJsonObject { put("value", RAW_OUTPUT) },
                redactedOutput = buildJsonObject { put("value", "masked-output") },
                remoteReference = "remote-safe-1",
            )
            val terminal = assertIs<ExecutionTransitionResult.Updated>(
                current.transition(transition(running, ActionExecutionState.SUCCEEDED, success)),
            ).record

            assertEquals(RAW_INPUT, terminal.command.input["value"]?.jsonPrimitive?.content)
            assertEquals(RAW_OUTPUT, assertIs<ActionResult.Success<JsonElement>>(terminal.result).output.jsonObjectValue())

            val restarted = store(database)
            val persisted = restarted.find("execution-1")!!
            assertEquals(JsonObject(emptyMap()), persisted.command.input)
            val redacted = assertIs<ActionResult.Success<JsonElement>>(persisted.result)
            assertEquals("masked-output", redacted.output.jsonObjectValue())
            assertEquals(ActionReplayPolicy.SAFE.name, scalar(database, "SELECT replay_policy FROM bd_action_executions"))
            assertEquals(ReconciliationPolicy.QUERY_REMOTE.name, scalar(database, "SELECT reconciliation_policy FROM bd_action_executions"))

            val storedText = allStoredText(database)
            listOf(RAW_INPUT, RAW_OUTPUT, RAW_TOKEN, RAW_OWNER).forEach { secret ->
                assertTrue(secret !in storedText, "database must not contain $secret")
            }
        }
    }

    @Test
    fun `restart inspect hydrates matching candidate input before reconciliation and rejects mismatch`() = runTest {
        fixture().database.use { database ->
            val command = command(inputValue = RAW_INPUT)
            val firstStore = store(database)
            val firstCoordinator = ActionExecutionCoordinator(firstStore, ActionClock { NOW })
            val created = assertIs<ActionExecutionStart.New>(
                firstCoordinator.begin(command, ActionRiskLevel.REVERSIBLE_WRITE),
            ).record
            val unknownResult: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
                command.executionId,
                ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "unknown"),
                "remote-safe-hydration",
                ReconciliationPolicy.QUERY_REMOTE,
            )
            assertIs<ExecutionTransitionResult.Updated>(
                firstStore.transition(transition(created, ActionExecutionState.OUTCOME_UNKNOWN, unknownResult)),
            )

            val restartedStore = store(database)
            val restartedCoordinator = ActionExecutionCoordinator(restartedStore, ActionClock { NOW.plusSeconds(3) })
            val matching = assertIs<ActionExecutionStart.NeedsReconciliation>(
                restartedCoordinator.inspectExisting(command),
            )
            assertEquals(RAW_INPUT, matching.record.command.input["value"]?.jsonPrimitive?.content)
            val claimed = assertIs<ReconciliationClaimResult.Claimed>(
                restartedStore.claimReconciliation(
                    claimRequest(matching.record, RAW_TOKEN, RAW_OWNER, matching.record.updatedAt.plusSeconds(1)),
                ),
            ).record
            assertEquals(RAW_INPUT, claimed.command.input["value"]?.jsonPrimitive?.content)
            val renewed = assertIs<ReconciliationRenewResult.Renewed>(
                restartedStore.renewReconciliation(renewRequest(claimed, RAW_TOKEN)),
            ).record
            assertEquals(RAW_INPUT, renewed.command.input["value"]?.jsonPrimitive?.content)
            val released = assertIs<ReconciliationReleaseResult.Released>(
                restartedStore.releaseReconciliation(releaseRequest(renewed, RAW_TOKEN)),
            ).record
            assertEquals(RAW_INPUT, released.command.input["value"]?.jsonPrimitive?.content)

            val mismatch = command.copy(input = buildJsonObject { put("value", "different-input") })
            assertIs<ActionExecutionStart.Conflict>(restartedCoordinator.inspectExisting(mismatch))
            assertEquals(JsonObject(emptyMap()), store(database).find(command.executionId)!!.command.input)
        }
    }

    @Test
    fun `first terminal wins and every late terminal response appends evidence without changing version`() = runTest {
        fixture().database.use { database ->
            val store = store(database)
            val running = runningRecord()
            store.compareAndCreate(running, audit())
            val success: ActionResult<JsonElement> = ActionResult.Success(
                "execution-1",
                buildJsonObject { put("saved", true) },
                buildJsonObject { put("saved", true) },
            )
            val terminal = assertIs<ExecutionTransitionResult.Updated>(
                store.transition(transition(running, ActionExecutionState.SUCCEEDED, success)),
            ).record
            val lateFailure: ActionResult<JsonElement> = ActionResult.Failure(
                "execution-1",
                ActionError(ActionErrorCode.REMOTE_REQUEST_FAILED, "late-sensitive-message"),
            )

            repeat(2) {
                assertIs<ExecutionTransitionResult.ExistingTerminal>(
                    store.transition(transition(terminal, ActionExecutionState.FAILED, lateFailure, seconds = 2L + it)),
                )
            }

            val unchanged = store.find("execution-1")!!
            assertEquals(ActionExecutionState.SUCCEEDED, unchanged.state)
            assertEquals(terminal.recordVersion, unchanged.recordVersion)
            assertEquals(listOf("state_transition", "state_transition", "late_terminal_response", "late_terminal_response"), store.events("execution-1").map { it.type })
        }
    }

    @Test
    fun `stale version conflicts and event insert failure rolls back row update`() = runTest {
        fixture().database.use { database ->
            val store = store(database)
            val running = runningRecord()
            store.compareAndCreate(running, audit())

            val stale = transition(running.copy(recordVersion = 0), ActionExecutionState.VALIDATING, null)
            assertIs<ExecutionTransitionResult.Conflict>(store.transition(stale))

            database.write { connection ->
                connection.createStatement().use {
                    it.execute(
                        """
                        CREATE TRIGGER reject_forced_event BEFORE INSERT ON bd_action_events
                        WHEN NEW.event_type = 'forced_failure'
                        BEGIN SELECT RAISE(ABORT, 'forced event failure'); END
                        """.trimIndent(),
                    )
                }
            }
            val before = store.find("execution-1")
            val eventsBefore = store.events("execution-1")
            val update = transition(running, ActionExecutionState.VALIDATING, null)
            assertFailsWith<SQLException> { store.transition(update.copy(audit = update.audit.copy(type = "forced_failure"))) }
            assertEquals(before, store.find("execution-1"))
            assertEquals(eventsBefore, store.events("execution-1"))
        }
    }

    @Test
    fun `policy serialization failure rolls back create row and audit event`() = runTest {
        fixture().database.use { database ->
            val store = SQLiteActionExecutionStore(
                database = database,
                policyResolver = ActionExecutionPolicyResolver { error("policy-serialization-failure") },
            )

            assertFailsWith<IllegalStateException> { store.compareAndCreate(runningRecord(), audit()) }
            assertEquals("0", scalar(database, "SELECT COUNT(*) FROM bd_action_executions"))
            assertEquals("0", scalar(database, "SELECT COUNT(*) FROM bd_action_events"))
        }
    }

    @Test
    fun `scoped reads match every identity field and exclude prior desktop session`() = runTest {
        fixture().database.use { database ->
            val ownerStore = store(database)
            val query: ScopedActionExecutionQuery = ownerStore
            val scope = scope()
            val earlier = runningRecord(
                command = command("a-execution").copy(
                    correlation = ActionCorrelation("thread-earlier", "turn-earlier", "tool-earlier"),
                ),
            )
            val later = runningRecord(
                createdAt = NOW.plusSeconds(2),
                command = command("z-execution").copy(
                    correlation = ActionCorrelation("thread-later", "turn-later", "tool-later"),
                ),
            )
            val oldCommand = command("old-agent-link", scope.copy(desktopSessionId = "prior-session")).copy(
                correlation = ActionCorrelation("orphan-thread", "orphan-turn", "orphan-tool"),
            )
            val old = runningRecord(command = oldCommand)
            ownerStore.compareAndCreate(later, audit(later, payloadWithLinks("thread-later", "turn-later", "tool-later")))
            ownerStore.compareAndCreate(earlier, audit(earlier, payloadWithLinks("thread-earlier", "turn-earlier", "tool-earlier")))
            ownerStore.compareAndCreate(old, audit(old, payloadWithLinks("forged-thread", "forged-turn", "forged-tool")))

            assertEquals(listOf("a-execution", "z-execution"), query.listNonTerminal(scope).map { it.command.executionId })
            listOf(
                scope.copy(desktopInstanceId = "other"),
                scope.copy(desktopSessionId = "other"),
                scope.copy(authSessionId = "other"),
                scope.copy(identityEpoch = 2),
                scope.copy(userId = "other"),
                scope.copy(tenantId = "other"),
                scope.copy(platformId = "other"),
            ).forEach { mismatch ->
                assertNull(query.find("a-execution", mismatch))
                assertTrue(query.listNonTerminal(mismatch).isEmpty())
            }
            assertNull(query.find("old-agent-link", scope))
            assertEquals("orphan-thread", scalar(database, "SELECT thread_id FROM bd_action_executions WHERE execution_id='old-agent-link'"))
            val hydrated = assertNotNull(store(database).find("old-agent-link", oldCommand.identityScope))
            assertEquals(oldCommand.correlation, hydrated.command.correlation)
            assertEquals(oldCommand.correlation, hydrated.binding.correlation)
            assertIs<ExecutionCreateResult.Conflict>(
                ownerStore.compareAndCreate(
                    runningRecord(
                        command = oldCommand.copy(
                            correlation = ActionCorrelation("other-thread", "other-turn", "other-tool"),
                        ),
                    ),
                    audit(old, payloadWithLinks("orphan-thread", "orphan-turn", "orphan-tool")),
                ),
            )
        }
    }

    @Test
    fun `reconciliation claim survives restart fences inaccessible owner and permits expiry takeover`() = runTest {
        fixture().database.use { database ->
            val ownerStore = store(database)
            val running = runningRecord()
            ownerStore.compareAndCreate(running, audit())
            val unknown = installUnknown(ownerStore, running)
            val claimed = assertIs<ReconciliationClaimResult.Claimed>(
                ownerStore.claimReconciliation(claimRequest(unknown, RAW_TOKEN, RAW_OWNER, NOW.plusSeconds(3))),
            ).record
            assertEquals(RAW_TOKEN, claimed.reconciliationClaim?.claimToken)

            val restarted = store(database)
            val inaccessible = assertIs<ReconciliationClaimResult.ExistingClaim>(
                restarted.claimReconciliation(claimRequest(claimed, "token-2", "owner-2", NOW.plusSeconds(4))),
            ).record
            assertTrue(inaccessible.reconciliationClaim?.claimToken != RAW_TOKEN)
            assertIs<ReconciliationRenewResult.ExistingClaim>(restarted.renewReconciliation(renewRequest(inaccessible, RAW_TOKEN)))
            assertIs<ReconciliationReleaseResult.Conflict>(restarted.releaseReconciliation(releaseRequest(inaccessible, RAW_TOKEN)))
            assertIs<ReconciliationUpdateResult.Conflict>(restarted.updateReconciliation(finalUpdate(inaccessible, RAW_TOKEN)))

            val takeover = assertIs<ReconciliationClaimResult.Claimed>(
                restarted.claimReconciliation(
                    claimRequest(inaccessible, "token-2", "owner-2", inaccessible.reconciliationClaim!!.expiresAt),
                ),
            ).record
            assertEquals("token-2", takeover.reconciliationClaim?.claimToken)
            assertIs<ReconciliationRenewResult.ExistingClaim>(ownerStore.renewReconciliation(renewRequest(takeover, RAW_TOKEN)))
            val renewed = assertIs<ReconciliationRenewResult.Renewed>(
                restarted.renewReconciliation(renewRequest(takeover, "token-2")),
            ).record
            val released = assertIs<ReconciliationReleaseResult.Released>(
                restarted.releaseReconciliation(releaseRequest(renewed, "token-2")),
            ).record
            val finalClaim = assertIs<ReconciliationClaimResult.Claimed>(
                restarted.claimReconciliation(claimRequest(released, "token-3", "owner-3", released.updatedAt.plusSeconds(1))),
            ).record
            val final = assertIs<ReconciliationUpdateResult.Updated>(
                restarted.updateReconciliation(finalUpdate(finalClaim, "token-3")),
            ).record

            assertEquals(ActionExecutionState.SUCCEEDED, final.state)
            assertEquals(final.completedAt, final.reconciliation?.reconciledAt)
            assertNull(final.reconciliationClaim)
            assertEquals("SUCCEEDED", scalar(database, "SELECT reconciliation_status FROM bd_action_executions"))
            assertEquals("3", scalar(database, "SELECT reconciliation_attempts FROM bd_action_executions"))
            val storedText = allStoredText(database)
            assertTrue(RAW_TOKEN !in storedText)
            assertTrue(RAW_OWNER !in storedText)
        }
    }

    private suspend fun installUnknown(
        store: SQLiteActionExecutionStore,
        running: ActionExecutionRecord,
    ): ActionExecutionRecord {
        val result: ActionResult<JsonElement> = ActionResult.OutcomeUnknown(
            "execution-1",
            ActionError(ActionErrorCode.OUTCOME_UNKNOWN, "network response unknown"),
            "remote-safe-unknown",
            ReconciliationPolicy.QUERY_REMOTE,
        )
        return assertIs<ExecutionTransitionResult.Updated>(
            store.transition(transition(running, ActionExecutionState.OUTCOME_UNKNOWN, result)),
        ).record
    }

    private fun store(database: BusinessDesktopDatabase) = SQLiteActionExecutionStore(
        database = database,
        policyResolver = ActionExecutionPolicyResolver {
            ActionExecutionPolicies(ActionReplayPolicy.SAFE, ReconciliationPolicy.QUERY_REMOTE)
        },
    )

    private fun fixture(): Fixture {
        val path = Files.createTempDirectory("sqlite-action-store").resolve("business.db")
        return Fixture(path, BusinessDesktopDatabase(path))
    }

    private fun runningRecord(
        executionId: String = "execution-1",
        createdAt: Instant = NOW,
        inputValue: String = "safe-input",
        command: ActionCommand = command(executionId, inputValue = inputValue),
    ): ActionExecutionRecord = ActionExecutionRecord(
        command = command,
        binding = binding(command),
        riskLevel = ActionRiskLevel.REVERSIBLE_WRITE,
        state = ActionExecutionState.EXECUTING,
        result = null,
        createdAt = createdAt,
        startedAt = createdAt,
        updatedAt = createdAt,
        recordVersion = 1,
    )

    private fun command(
        executionId: String = "execution-1",
        identityScope: ActionIdentityScope = scope(),
        inputValue: String = "safe-input",
    ) = ActionCommand(
        executionId,
        "demo.save",
        1,
        buildJsonObject { put("value", inputValue) },
        ActionOrigin.AGENT,
        identityScope,
        "page-1",
        1,
    )

    private fun binding(command: ActionCommand) = ExecutionBinding(
        command.actionId,
        command.actionVersion,
        "fingerprint-${command.executionId}",
        command.origin,
        command.identityScope,
        command.pageId,
        command.contextRevision,
        command.correlation,
    )

    private fun scope() = ActionIdentityScope("desktop", "session", "auth", 1, "user", "tenant", "platform")

    private fun audit(
        record: ActionExecutionRecord = runningRecord(),
        payload: JsonObject = JsonObject(emptyMap()),
    ) = ActionAuditDraft(
        record.command.executionId,
        ActionExecutionState.RECEIVED,
        record.state,
        "state_transition",
        payload,
        null,
        record.createdAt,
    )

    private fun transition(
        record: ActionExecutionRecord,
        state: ActionExecutionState,
        result: ActionResult<JsonElement>?,
        seconds: Long = 1,
    ): ExecutionTransition {
        val at = record.updatedAt.plusSeconds(seconds)
        return ExecutionTransition(
            record.command.executionId,
            record.recordVersion,
            state,
            result,
            updatedAt = at,
            startedAt = if (state == ActionExecutionState.EXECUTING) at else null,
            completedAt = if (state.isTerminal()) at else null,
            audit = ActionAuditDraft(record.command.executionId, record.state, state, "state_transition", JsonObject(emptyMap()), null, at),
        )
    }

    private fun claimRequest(record: ActionExecutionRecord, token: String, owner: String, now: Instant) =
        ReconciliationClaimRequest(
            record.command.executionId,
            record.recordVersion,
            token,
            owner,
            now,
            Duration.ofSeconds(10),
            ActionAuditDraft(record.command.executionId, ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN, "reconciliation_attempt", JsonObject(emptyMap()), null, now),
        )

    private fun renewRequest(record: ActionExecutionRecord, token: String): ReconciliationRenewRequest {
        val now = record.updatedAt.plusSeconds(1)
        return ReconciliationRenewRequest(
            record.command.executionId,
            record.recordVersion,
            token,
            now,
            Duration.ofSeconds(10),
            ActionAuditDraft(record.command.executionId, ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN, "reconciliation_claim_renewed", JsonObject(emptyMap()), null, now),
        )
    }

    private fun releaseRequest(record: ActionExecutionRecord, token: String): ReconciliationReleaseRequest {
        val at = record.updatedAt.plusSeconds(1)
        return ReconciliationReleaseRequest(
            record.command.executionId,
            record.recordVersion,
            token,
            at,
            ActionAuditDraft(record.command.executionId, ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.OUTCOME_UNKNOWN, "reconciliation_result", JsonObject(emptyMap()), null, at),
        )
    }

    private fun finalUpdate(record: ActionExecutionRecord, token: String): ReconciliationExecutionUpdate {
        val at = record.updatedAt.plusSeconds(1)
        return ReconciliationExecutionUpdate(
            record.command.executionId,
            record.recordVersion,
            token,
            result = null,
            successFact = ExecutionSuccessFact(
                ExecutionSuccessFact.RECONCILED_REMOTE_SUCCESS,
                "remote-safe-final",
                null,
                null,
                ExecutionSuccessFact.SOURCE_RECONCILIATION,
            ),
            completedAt = at,
            audit = ActionAuditDraft(record.command.executionId, ActionExecutionState.OUTCOME_UNKNOWN, ActionExecutionState.SUCCEEDED, "reconciliation_result", JsonObject(emptyMap()), null, at),
        )
    }

    private fun payloadWithLinks(threadId: String, turnId: String, toolCallId: String) = buildJsonObject {
        put("threadId", threadId)
        put("turnId", turnId)
        put("toolCallId", toolCallId)
    }

    @Test
    fun `null and non string correlation payload values are not persisted`() = runTest {
        fixture().database.use { database ->
            val store = store(database)
            val record = runningRecord()
            val payload = buildJsonObject {
                put("threadId", JsonPrimitive(7))
                put("turnId", "")
                put("toolCallId", JsonPrimitive(false))
            }

            store.compareAndCreate(record, audit(record, payload))

            assertEquals("0", scalar(database, "SELECT COUNT(thread_id)+COUNT(turn_id)+COUNT(tool_call_id) FROM bd_action_executions"))
        }
    }

    @Test
    fun `unsafe scalar values are redacted before SQL while ordinary attribution survives`() = runTest {
        fixture().database.use { database ->
            val store = store(database)
            val running = runningRecord()
            val unsafeActor = "Bearer ${RAW_TOKEN}\nactor"
            store.compareAndCreate(
                running,
                audit(running).copy(
                    actorId = unsafeActor,
                    redactedPayload = buildJsonObject {
                        put("claimToken", RAW_TOKEN)
                        put("ownerId", RAW_OWNER)
                    },
                ),
            )
            val unsafeReference = "Bearer eyJhbGciOiJIUzI1NiJ9.${RAW_TOKEN}.signature"
            val successFact = ExecutionSuccessFact(
                kind = ExecutionSuccessFact.OUTPUT_ENCODING_FAILED,
                remoteReference = unsafeReference,
                errorCode = ActionErrorCode.PROTOCOL_ERROR,
                safeMessage = "unsafe-message-${RAW_TOKEN}",
                source = ExecutionSuccessFact.SOURCE_OUTPUT_ENCODING,
            )
            val unsafeTerminal = ExecutionTransition(
                executionId = running.command.executionId,
                expectedVersion = running.recordVersion,
                state = ActionExecutionState.SUCCEEDED,
                result = null,
                successFact = successFact,
                updatedAt = NOW.plusSeconds(1),
                completedAt = NOW.plusSeconds(1),
                audit = ActionAuditDraft(
                    running.command.executionId,
                    running.state,
                    ActionExecutionState.SUCCEEDED,
                    "state_transition",
                    buildJsonObject { put("token", RAW_TOKEN) },
                    unsafeActor,
                    NOW.plusSeconds(1),
                ),
            )
            store.transition(unsafeTerminal)

            val ordinary = runningRecord("safe-scalars", createdAt = NOW.plusSeconds(3))
            store.compareAndCreate(ordinary, audit(ordinary).copy(actorId = "user_123-safe"))
            val ordinaryResult: ActionResult<JsonElement> = ActionResult.Success(
                ordinary.command.executionId,
                buildJsonObject { put("raw", "not-persisted") },
                buildJsonObject { put("safe", true) },
                "remote-safe-1",
            )
            store.transition(transition(ordinary, ActionExecutionState.SUCCEEDED, ordinaryResult))

            val storedText = allStoredText(database)
            listOf(RAW_TOKEN, RAW_OWNER, unsafeActor, unsafeReference, successFact.safeMessage!!).forEach { secret ->
                assertTrue(secret !in storedText, "unsafe scalar must not be persisted: $secret")
            }
            assertEquals("user_123-safe", scalar(database, "SELECT actor_id FROM bd_action_events WHERE execution_id='safe-scalars' ORDER BY event_sequence LIMIT 1"))
            assertEquals("remote-safe-1", scalar(database, "SELECT remote_reference FROM bd_action_executions WHERE execution_id='safe-scalars'"))
            val restarted = store(database).find("execution-1")!!
            assertEquals("动作输出不可用", restarted.successFact?.safeMessage)
            assertTrue(restarted.successFact?.remoteReference != unsafeReference)
        }
    }

    private fun scalar(database: BusinessDesktopDatabase, sql: String): String = database.read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                check(result.next())
                result.getString(1)
            }
        }
    }

    private fun allStoredText(database: BusinessDesktopDatabase): String = database.read { connection ->
        buildString {
            listOf("bd_action_executions", "bd_action_events").forEach { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT * FROM $table").use { rows ->
                        while (rows.next()) {
                            for (index in 1..rows.metaData.columnCount) append(rows.getString(index)).append('|')
                        }
                    }
                }
            }
        }
    }

    private fun JsonElement.jsonObjectValue(): String =
        (this as JsonObject)["value"]!!.jsonPrimitive.content

    private fun ActionExecutionState.isTerminal() = this in setOf(
        ActionExecutionState.SUCCEEDED,
        ActionExecutionState.FAILED,
        ActionExecutionState.CANCELED,
        ActionExecutionState.EXPIRED,
        ActionExecutionState.OUTCOME_UNKNOWN,
    )

    private data class Fixture(val path: Path, val database: BusinessDesktopDatabase)

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
        const val RAW_INPUT = "input-super-secret-4731"
        const val RAW_OUTPUT = "output-super-secret-8842"
        const val RAW_TOKEN = "claim-token-super-secret-1198"
        const val RAW_OWNER = "owner-super-secret-5512"
    }
}
