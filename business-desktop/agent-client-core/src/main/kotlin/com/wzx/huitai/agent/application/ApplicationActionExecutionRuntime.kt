package com.wzx.huitai.agent.application

import com.wzx.huitai.action.ActionBusResult
import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ScopedActionExecutionQuery
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Desktop-process lifetime owner for application action jobs across Agent connections. */
class ApplicationActionExecutionRuntime(
    private val executor: ApplicationActionExecutor,
    @Suppress("unused") private val executionStore: ActionExecutionStore,
    private val scopedQuery: ScopedActionExecutionQuery,
    scope: CoroutineScope,
    private val statusPollMillis: Long = 50,
    private val cleanupTimeoutMillis: Long = 2_000,
    private val completedCapacity: Int = 1_024,
) {
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + ownerJob)
    private val active = ConcurrentHashMap<RuntimeExecutionKey, RuntimeOwnedExecution>()
    private val starting = ConcurrentHashMap<RuntimeExecutionKey, StartingExecution>()
    private val sinks = ConcurrentHashMap<ActionIdentityScope, ApplicationActionStatusClient>()
    private val completed = object : LinkedHashMap<RuntimeExecutionKey, RuntimeOwnedExecution>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<RuntimeExecutionKey, RuntimeOwnedExecution>?,
        ): Boolean {
            val evict = size > completedCapacity
            if (evict) eldest?.value?.close()
            return evict
        }
    }
    private val completedLock = Any()

    init {
        require(statusPollMillis > 0) { "statusPollMillis must be positive" }
        require(cleanupTimeoutMillis > 0) { "cleanupTimeoutMillis must be positive" }
        require(completedCapacity > 0) { "completedCapacity must be positive" }
    }

    internal val activeExecutionCount: Int get() = active.size
    internal val completedExecutionCount: Int get() = synchronized(completedLock) { completed.size }

    internal suspend fun start(
        candidate: RuntimeOwnedExecution,
        acceptedSink: ApplicationActionStatusClient,
    ): RuntimeStartResult {
        val key = candidate.runtimeKey()
        while (true) {
            active[key]?.let { existing ->
                if (!existing.sameRequest(candidate)) return RuntimeStartResult.Conflict
                val durableUnknown = (scopedRead(
                    candidate.command.executionId,
                    candidate.command.identityScope,
                ) as? ScopedRead.Found)?.record?.state == ActionExecutionState.OUTCOME_UNKNOWN
                if (durableUnknown) {
                    val finished = withTimeoutOrNull(cleanupTimeoutMillis) {
                        existing.job?.join()
                        true
                    } == true
                    if (finished) continue
                }
                bindSink(candidate.command.identityScope, acceptedSink)
                return RuntimeStartResult.Acknowledged
            }
            val reservation = StartingExecution(candidate)
            val existingStart = starting.putIfAbsent(key, reservation)
            if (existingStart != null) {
                if (!existingStart.candidate.sameRequest(candidate)) return RuntimeStartResult.Conflict
                existingStart.outcome.await()
                continue
            }

            try {
                var needsReconciliation = false
                when (val persisted = scopedRead(candidate.command.executionId, candidate.command.identityScope)) {
                    is ScopedRead.Found -> {
                        if (!persisted.record.matchesBinding(candidate)) {
                            reservation.outcome.complete(StartOutcome.Finished)
                            return RuntimeStartResult.Conflict
                        }
                        if (persisted.record.state == ActionExecutionState.OUTCOME_UNKNOWN) {
                            needsReconciliation = true
                            synchronized(completedLock) {
                                completed.remove(key)?.close()
                            }
                        } else {
                            if (persisted.record.isTerminal) installCompleted(candidate)
                            bindSink(candidate.command.identityScope, acceptedSink)
                            reservation.outcome.complete(StartOutcome.Finished)
                            return RuntimeStartResult.Acknowledged
                        }
                    }
                    ScopedRead.Unavailable -> error("Scoped execution query is unavailable")
                    ScopedRead.Absent -> Unit
                }

                if (!needsReconciliation) synchronized(completedLock) { completed[key] }?.let { previous ->
                    val outcome = if (!previous.sameRequest(candidate)) {
                        RuntimeStartResult.Conflict
                    } else {
                        bindSink(candidate.command.identityScope, acceptedSink)
                        RuntimeStartResult.Acknowledged
                    }
                    reservation.outcome.complete(StartOutcome.Finished)
                    return outcome
                }

                acceptedSink.accepted(candidate.publication, candidate.command.actionId)
                val installed = acceptedSink.ifAttached {
                    val lane = RuntimePublicationSlot(
                        scope = ownerScope,
                        sinkProvider = { sinks[candidate.command.identityScope] },
                    )
                    val job = ownerScope.launch(start = CoroutineStart.LAZY) { execute(candidate) }
                    candidate.publicationSlot = lane
                    candidate.job = job
                    active[key] = candidate
                    sinks[candidate.command.identityScope] = acceptedSink
                    job.start()
                    true
                } == true
                check(installed) { "Agent connection detached before action start installation" }
                reservation.outcome.complete(StartOutcome.Finished)
                return RuntimeStartResult.Accepted
            } catch (failure: Throwable) {
                reservation.outcome.complete(StartOutcome.Failed)
                throw failure
            } finally {
                starting.remove(key, reservation)
            }
        }
    }

    internal suspend fun cancel(
        key: RuntimeExecutionKey,
        correlation: ApplicationActionCorrelation,
    ): Boolean {
        val execution = active[key] ?: return false
        if (execution.publication.correlation != correlation) return false
        execution.job?.cancelAndJoin()
        return true
    }

    internal suspend fun find(executionId: String, scope: ActionIdentityScope): ActionExecutionRecord? =
        scopedQuery.find(executionId, scope)

    internal suspend fun onConnectionLost(statusClient: ApplicationActionStatusClient) {
        val affected = statusClient.detach {
            sinks.entries
                .filter { it.value === statusClient }
                .mapTo(mutableSetOf()) { it.key }
                .also { scopes -> scopes.forEach { sinks.remove(it, statusClient) } }
        } ?: return
        active.values.filter { it.command.identityScope in affected }.forEach { execution ->
            when (val read = scopedRead(execution.command.executionId, execution.command.identityScope)) {
                is ScopedRead.Found -> when (read.record.state) {
                    ActionExecutionState.EXECUTING -> Unit
                    ActionExecutionState.SUCCEEDED,
                    ActionExecutionState.FAILED,
                    ActionExecutionState.CANCELED,
                    ActionExecutionState.EXPIRED,
                    ActionExecutionState.OUTCOME_UNKNOWN,
                    -> installCompleted(execution)
                    else -> {
                        execution.recoverTerminalOnReconnect = false
                        withTimeoutOrNull(cleanupTimeoutMillis) { execution.job?.cancelAndJoin() }
                    }
                }
                ScopedRead.Absent -> {
                    execution.recoverTerminalOnReconnect = false
                    withTimeoutOrNull(cleanupTimeoutMillis) { execution.job?.cancelAndJoin() }
                }
                ScopedRead.Unavailable -> Unit
            }
        }
    }

    internal suspend fun recover(
        identityScope: ActionIdentityScope,
        statusClient: ApplicationActionStatusClient,
    ) {
        check(statusClient.ifAttached { sinks[identityScope] = statusClient } != null) {
            "Detached Agent connection cannot recover application actions"
        }
        scopedQuery.listNonTerminal(identityScope).forEach { record ->
            val execution = active[RuntimeExecutionKey(record.command.executionId, identityScope)]
            when (record.state) {
                ActionExecutionState.EXECUTING -> if (execution != null) {
                    val latest = scopedQuery.find(record.command.executionId, identityScope)
                    if (latest?.state == ActionExecutionState.EXECUTING) {
                        execution.publicationSlot?.offerProgress(PublicationIntent.Record(execution.publication, latest))
                    }
                }
                ActionExecutionState.RECEIVED,
                ActionExecutionState.VALIDATING,
                ActionExecutionState.PREVIEWED,
                ActionExecutionState.WAITING_APPROVAL,
                -> execution?.let {
                    it.recoverTerminalOnReconnect = false
                    withTimeoutOrNull(cleanupTimeoutMillis) { it.job?.cancelAndJoin() }
                }
                else -> Unit
            }
        }
        val completedForScope = synchronized(completedLock) {
            completed.filterKeys { it.identityScope == identityScope }
                .values
                .filter { it.recoverTerminalOnReconnect }
                .toList()
        }
        completedForScope.forEach { execution ->
            val terminal = scopedQuery.find(execution.command.executionId, identityScope)
            if (terminal?.isTerminal == true) {
                execution.publicationSlot?.offerTerminal(
                    PublicationIntent.Record(execution.publication, terminal),
                )
            }
            execution.publicationSlot?.onSinkChanged()
        }
    }

    suspend fun close() {
        sinks.clear()
        active.values.forEach(RuntimeOwnedExecution::close)
        synchronized(completedLock) { completed.values.forEach(RuntimeOwnedExecution::close) }
        ownerJob.cancel()
        withContext(NonCancellable) {
            withTimeoutOrNull(cleanupTimeoutMillis) { ownerJob.children.toList().joinAll() }
        }
    }

    private suspend fun execute(owned: RuntimeOwnedExecution) {
        var rejection: ActionError? = null
        var completedResult: ActionResult<*>? = null
        val observer = ownerScope.launch { observe(owned) }
        try {
            when (val outcome = executor.execute(owned.command, owned.context) { projected ->
                val current = scopedQuery.find(owned.command.executionId, owned.command.identityScope) ?: return@execute
                if (!current.isTerminal) {
                    owned.publicationSlot?.offerProgress(
                        PublicationIntent.Record(owned.publication, current, projectedResult = projected),
                    )
                }
            }) {
                is ActionBusResult.Completed -> completedResult = outcome.result
                is ActionBusResult.Rejected -> rejection = outcome.error
                is ActionBusResult.OutputEncodingFailed -> rejection = outcome.error
                else -> Unit
            }
        } catch (_: CancellationException) {
            // The action bus persists its cancellation handoff before rethrowing.
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(cleanupTimeoutMillis) { observer.cancelAndJoin() }
                val finalRead = scopedRead(owned.command.executionId, owned.command.identityScope)
                owned.closeAdmission()
                installCompleted(owned)
                when {
                    finalRead is ScopedRead.Found && finalRead.record.isTerminal -> owned.publicationSlot?.offerTerminal(
                        PublicationIntent.Record(owned.publication, finalRead.record, projectedResult = completedResult),
                    )
                    finalRead is ScopedRead.Absent && rejection != null -> owned.publicationSlot?.offerTerminal(
                        PublicationIntent.Rejected(owned.publication, owned.command.actionId, rejection),
                    )
                    else -> startTerminalResolver(owned)
                }
            }
        }
    }

    private suspend fun observe(owned: RuntimeOwnedExecution) {
        while (true) {
            val record = scopedQuery.find(owned.command.executionId, owned.command.identityScope)
            if (active[owned.runtimeKey()] === owned &&
                record != null &&
                !record.isTerminal &&
                !record.requiresTransientProjection &&
                !owned.terminalQueued
            ) {
                owned.publicationSlot?.offerProgress(PublicationIntent.Record(owned.publication, record))
            }
            delay(statusPollMillis)
        }
    }

    private fun bindSink(scope: ActionIdentityScope, client: ApplicationActionStatusClient) {
        check(client.ifAttached { sinks[scope] = client } != null) { "Detached Agent connection" }
    }

    private fun installCompleted(execution: RuntimeOwnedExecution) {
        active.remove(execution.runtimeKey(), execution)
        synchronized(completedLock) { completed[execution.runtimeKey()] = execution }
    }

    private fun startTerminalResolver(execution: RuntimeOwnedExecution) {
        if (execution.terminalResolver?.isActive == true) return
        execution.terminalResolver = ownerScope.launch {
            while (true) {
                when (val read = scopedRead(execution.command.executionId, execution.command.identityScope)) {
                    is ScopedRead.Found -> if (read.record.isTerminal) {
                        execution.publicationSlot?.offerTerminal(
                            PublicationIntent.Record(execution.publication, read.record),
                        )
                        return@launch
                    }
                    ScopedRead.Absent,
                    ScopedRead.Unavailable,
                    -> Unit
                }
                delay(TERMINAL_RESOLVER_DELAY_MILLIS)
            }
        }
    }

    private suspend fun scopedRead(
        executionId: String,
        identityScope: ActionIdentityScope,
    ): ScopedRead = try {
        val completed = withTimeoutOrNull(cleanupTimeoutMillis) {
            ScopedRead.FoundOrAbsent(scopedQuery.find(executionId, identityScope))
        }
        completed ?: ScopedRead.Unavailable
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ScopedRead.Unavailable
    }

    private companion object {
        const val TERMINAL_RESOLVER_DELAY_MILLIS = 10L
    }
}

class ApplicationReconnectRecovery(private val runtime: ApplicationActionExecutionRuntime) {
    suspend fun recover(scope: ActionIdentityScope, client: ApplicationActionStatusClient) = runtime.recover(scope, client)
}

internal data class RuntimeExecutionKey(val executionId: String, val identityScope: ActionIdentityScope)

internal data class RuntimeOwnedExecution(
    val publication: ApplicationActionPublicationContext,
    val command: ActionCommand,
    val context: ActionContext,
    @Volatile var job: Job? = null,
    @Volatile var publicationSlot: RuntimePublicationSlot? = null,
    @Volatile var terminalQueued: Boolean = false,
    @Volatile var recoverTerminalOnReconnect: Boolean = true,
    @Volatile var terminalResolver: Job? = null,
) {
    fun runtimeKey() = RuntimeExecutionKey(command.executionId, command.identityScope)
    fun sameRequest(other: RuntimeOwnedExecution) =
        publication.correlation == other.publication.correlation && command == other.command && context == other.context
    fun closeAdmission() { terminalQueued = true }
    fun close() {
        terminalResolver?.cancel()
        publicationSlot?.close()
    }
}

internal sealed interface RuntimeStartResult {
    data object Accepted : RuntimeStartResult
    data object Acknowledged : RuntimeStartResult
    data object Conflict : RuntimeStartResult
}

private data class StartingExecution(
    val candidate: RuntimeOwnedExecution,
    val outcome: CompletableDeferred<StartOutcome> = CompletableDeferred(),
)

private enum class StartOutcome { Finished, Failed }

private sealed interface ScopedRead {
    data class Found(val record: ActionExecutionRecord) : ScopedRead
    data object Absent : ScopedRead
    data object Unavailable : ScopedRead

    companion object {
        fun FoundOrAbsent(record: ActionExecutionRecord?): ScopedRead = record?.let(::Found) ?: Absent
    }
}

internal sealed interface PublicationIntent {
    suspend fun publish(client: ApplicationActionStatusClient)
    data class Record(
        val publication: ApplicationActionPublicationContext,
        val record: ActionExecutionRecord,
        val projectedResult: ActionResult<*>? = null,
    ) : PublicationIntent {
        override suspend fun publish(client: ApplicationActionStatusClient) =
            client.publish(publication, record, projectedResult)
    }
    data class Rejected(
        val publication: ApplicationActionPublicationContext,
        val actionId: String,
        val error: ActionError,
    ) : PublicationIntent {
        override suspend fun publish(client: ApplicationActionStatusClient) = client.rejected(publication, actionId, error)
    }
}

/** Bounded publication state: one replaceable progress value and one retained terminal value. */
internal class RuntimePublicationSlot(
    scope: CoroutineScope,
    private val sinkProvider: () -> ApplicationActionStatusClient?,
) {
    private val latestProgress = AtomicReference<PublicationIntent?>(null)
    private val pendingTerminal = AtomicReference<PublicationIntent?>(null)
    private val terminalDeliveredConnection = AtomicReference<String?>(null)
    private val closed = AtomicBoolean(false)
    private val signal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val worker = scope.launch {
        for (ignored in signal) {
            while (!closed.get()) {
                val terminal = pendingTerminal.get()
                val sink = sinkProvider()
                if (terminal != null) {
                    if (sink == null || terminalDeliveredConnection.get() == sink.connectionId) break
                    if (runCatching { terminal.publish(sink) }.isSuccess) {
                        terminalDeliveredConnection.set(sink.connectionId)
                    } else {
                        delay(RETRY_MILLIS)
                        signal.trySend(Unit)
                    }
                    break
                }
                val progress = latestProgress.getAndSet(null) ?: break
                if (sink == null) break
                runCatching { progress.publish(sink) }
            }
        }
    }

    fun offerProgress(intent: PublicationIntent) {
        if (closed.get() || pendingTerminal.get() != null) return
        latestProgress.updateAndGet { current -> richerProgress(current, intent) }
        if (pendingTerminal.get() == null) signal.trySend(Unit)
    }

    fun offerTerminal(intent: PublicationIntent) {
        if (closed.get()) return
        latestProgress.set(null)
        pendingTerminal.set(intent)
        terminalDeliveredConnection.set(null)
        signal.trySend(Unit)
    }

    fun onSinkChanged() {
        if (!closed.get()) signal.trySend(Unit)
    }

    fun close() {
        closed.set(true)
        signal.close()
        worker.cancel()
    }

    private companion object { const val RETRY_MILLIS = 10L }
}

private fun richerProgress(current: PublicationIntent?, incoming: PublicationIntent): PublicationIntent {
    val existing = current as? PublicationIntent.Record ?: return incoming
    val candidate = incoming as? PublicationIntent.Record ?: return incoming
    if (existing.record.command.executionId != candidate.record.command.executionId ||
        existing.record.state != candidate.record.state
    ) return incoming
    return if (existing.projectedResult != null && candidate.projectedResult == null) existing else incoming
}

private fun ActionExecutionRecord.matchesBinding(candidate: RuntimeOwnedExecution): Boolean =
    command == candidate.command &&
        binding.actionId == candidate.command.actionId &&
        binding.actionVersion == candidate.command.actionVersion &&
        binding.origin == candidate.command.origin &&
        binding.identityScope == candidate.command.identityScope &&
        binding.pageId == candidate.command.pageId &&
        binding.contextRevision == candidate.command.contextRevision &&
        candidate.context.identityScope == candidate.command.identityScope &&
        candidate.context.pageId == candidate.command.pageId &&
        candidate.context.contextRevision == candidate.command.contextRevision

private val ActionExecutionRecord.requiresTransientProjection: Boolean
    get() = state == ActionExecutionState.PREVIEWED || state == ActionExecutionState.WAITING_APPROVAL
