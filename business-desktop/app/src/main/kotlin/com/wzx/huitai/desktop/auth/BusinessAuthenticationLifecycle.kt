package com.wzx.huitai.desktop.auth

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the one-shot startup restore job and deterministic local revocation on application close. */
class BusinessAuthenticationLifecycle(
    private val orchestrator: BusinessAuthenticationOrchestrator,
    parentScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifecycleJob)
    private val shutdownMutex = Mutex()
    private val lifecycleLock = Any()
    private var restoreJob: Job? = null

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        synchronized(lifecycleLock) {
            if (closed.get()) return
            restoreJob = scope.launch { orchestrator.restore() }
        }
    }

    suspend fun shutdown() = shutdownMutex.withLock {
        if (!closed.compareAndSet(false, true)) return@withLock
        val restoration = synchronized(lifecycleLock) {
            restoreJob.also { restoreJob = null }
        }
        restoration?.cancelAndJoin()
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
