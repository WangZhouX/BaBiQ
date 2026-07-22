package com.wzx.huitai.desktop.auth

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Owns the one-shot startup restore job and deterministic local revocation on application close. */
class BusinessAuthenticationLifecycle(
    private val orchestrator: BusinessAuthenticationOrchestrator,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifecycleJob)

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        scope.launch { orchestrator.restore() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking { orchestrator.close() }
        scope.cancel()
    }

    override fun toString(): String = "BusinessAuthenticationLifecycle(started=${started.get()}, closed=${closed.get()})"
}
