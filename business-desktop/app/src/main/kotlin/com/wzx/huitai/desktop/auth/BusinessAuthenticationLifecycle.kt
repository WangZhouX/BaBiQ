package com.wzx.huitai.desktop.auth

import com.wzx.huitai.agent.client.AgentSupervisorState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns startup restore, reconnect attach, and deterministic local revocation on application close. */
class BusinessAuthenticationLifecycle(
    private val orchestrator: BusinessAuthenticationLifecycleOperations,
    private val supervisorState: StateFlow<AgentSupervisorState>,
    parentScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifecycleJob)
    private val shutdownMutex = Mutex()
    private val lifecycleLock = Any()
    private var observerJob: Job? = null

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        synchronized(lifecycleLock) {
            if (closed.get()) return
            observerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { observeConnections() }
        }
    }

    private suspend fun observeConnections() {
        var firstConnection = true
        var lastConnectionId: String? = null
        var recoveringLostConnection = false
        supervisorState
            .onEach { state ->
                val connected = state as? AgentSupervisorState.Connected
                if (
                    lastConnectionId != null &&
                    (connected == null || connected.connectionId != lastConnectionId) &&
                    !recoveringLostConnection
                ) {
                    recoveringLostConnection = true
                    try {
                        orchestrator.onConnectionUnavailable()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // The gate is already fail-closed. Keep observing so a replacement
                        // connection can retry the server-owned attach transaction.
                    }
                }
            }
            .collectLatest { state ->
                val connected = state as? AgentSupervisorState.Connected ?: return@collectLatest
                if (connected.connectionId == lastConnectionId) return@collectLatest
                val startup = firstConnection
                firstConnection = false
                lastConnectionId = connected.connectionId
                recoveringLostConnection = false
                try {
                    if (startup) orchestrator.restore() else orchestrator.attachAfterReconnect()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The authentication adapter has already projected a stable local error and closed the gate.
                }
            }
    }

    suspend fun shutdown() = shutdownMutex.withLock {
        if (!closed.compareAndSet(false, true)) return@withLock
        val observer = synchronized(lifecycleLock) {
            observerJob.also { observerJob = null }
        }
        observer?.cancelAndJoin()
        withContext(NonCancellable) {
            try {
                orchestrator.close()
            } finally {
                scope.cancel()
            }
        }
    }

    override fun toString(): String = "BusinessAuthenticationLifecycle(started=${started.get()}, closed=${closed.get()})"
}
