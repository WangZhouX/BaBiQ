package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single reconnect authority for the identity that has completed Agent registration. */
class BusinessIdentityRegistry {
    private val lock = Any()
    private val mutableSnapshot = MutableStateFlow(BusinessIdentityRegistrySnapshot())
    private var activeUsagePermits = 0
    private var usagePermitsDrained = CompletableDeferred(Unit)

    val snapshot: StateFlow<BusinessIdentityRegistrySnapshot> = mutableSnapshot.asStateFlow()
    val gate: StateFlow<BusinessAccessGateState> = SnapshotGateStateFlow(snapshot)

    fun currentIdentity(): BusinessIdentity? = mutableSnapshot.value.identity

    fun currentGeneration(): Long = mutableSnapshot.value.generation

    /** Runs a synchronous commit only while the exact READY publication is still current. */
    internal fun commitIfCurrent(
        expectedGeneration: Long,
        expectedIdentity: BusinessIdentity,
        commit: () -> Unit,
    ): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (
            current.gate != BusinessAccessGateState.READY ||
            current.generation != expectedGeneration ||
            current.identity != expectedIdentity
        ) {
            return@synchronized false
        }
        commit()
        true
    }

    /**
     * Runs [use] while holding a revocation-visible lease on the exact READY publication.
     *
     * Acquisition and [invalidate] are serialized by [lock]. Once invalidation wins, no new use
     * can start. If acquisition wins, revocation can close the gate immediately but must await
     * [awaitUsagePermitsDrained] before scanning pre-execution actions.
     */
    internal suspend fun <T> withCurrentUsagePermit(
        expectedGeneration: Long,
        expectedIdentity: BusinessIdentity,
        use: suspend () -> T,
    ): T? {
        val acquired = synchronized(lock) {
            val current = mutableSnapshot.value
            if (
                current.gate != BusinessAccessGateState.READY ||
                current.generation != expectedGeneration ||
                current.identity != expectedIdentity
            ) {
                false
            } else {
                if (activeUsagePermits == 0) usagePermitsDrained = CompletableDeferred()
                activeUsagePermits += 1
                true
            }
        }
        if (!acquired) return null
        return try {
            use()
        } finally {
            synchronized(lock) {
                check(activeUsagePermits > 0) { "usage permit accounting underflow" }
                activeUsagePermits -= 1
                if (activeUsagePermits == 0) usagePermitsDrained.complete(Unit)
            }
        }
    }

    /** Called only after the READY publication has been invalidated, so no new permit can enter. */
    suspend fun awaitUsagePermitsDrained() {
        val drained = synchronized(lock) { usagePermitsDrained }
        drained.await()
    }

    /** Non-ready transitions can never retain an identity. */
    fun transitionTo(targetGate: BusinessAccessGateState) = synchronized(lock) {
        require(targetGate != BusinessAccessGateState.READY) { "READY requires publishReady" }
        val current = mutableSnapshot.value
        mutableSnapshot.value = current.copy(gate = targetGate, identity = null)
    }

    /** Publishes READY and its identity through one StateFlow assignment. */
    fun publishReady(identity: BusinessIdentity, expectedGeneration: Long): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (current.generation != expectedGeneration) return@synchronized false
        mutableSnapshot.value = BusinessIdentityRegistrySnapshot(
            gate = BusinessAccessGateState.READY,
            identity = identity,
            generation = current.generation,
        )
        true
    }

    /** Atomically closes the gate, removes identity and invalidates in-flight publications. */
    fun invalidate(targetGate: BusinessAccessGateState): BusinessIdentity? = synchronized(lock) {
        require(targetGate != BusinessAccessGateState.READY) { "revocation target must not be READY" }
        val current = mutableSnapshot.value
        val previous = current.identity
        mutableSnapshot.value = BusinessIdentityRegistrySnapshot(
            gate = targetGate,
            identity = null,
            generation = current.generation + 1,
        )
        previous
    }

    override fun toString(): String =
        "BusinessIdentityRegistry(generation=${mutableSnapshot.value.generation}, identity=[REDACTED])"
}

data class BusinessIdentityRegistrySnapshot(
    val gate: BusinessAccessGateState = BusinessAccessGateState.STARTING,
    val identity: BusinessIdentity? = null,
    val generation: Long = 0,
) {
    init {
        require((gate == BusinessAccessGateState.READY) == (identity != null)) {
            "READY and identity must be published together"
        }
    }
}

private class SnapshotGateStateFlow(
    private val source: StateFlow<BusinessIdentityRegistrySnapshot>,
) : StateFlow<BusinessAccessGateState> {
    override val value: BusinessAccessGateState
        get() = source.value.gate

    override val replayCache: List<BusinessAccessGateState>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<BusinessAccessGateState>): Nothing {
        var previous: BusinessAccessGateState? = null
        source.collect { snapshot ->
            if (snapshot.gate != previous) {
                previous = snapshot.gate
                collector.emit(snapshot.gate)
            }
        }
    }
}
