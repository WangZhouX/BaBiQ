package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single reconnect authority for the identity that has completed Agent registration. */
class BusinessIdentityRegistry {
    private val lock = Any()
    private val mutableSnapshot = MutableStateFlow(BusinessIdentityRegistrySnapshot())

    val snapshot: StateFlow<BusinessIdentityRegistrySnapshot> = mutableSnapshot.asStateFlow()
    val gate: StateFlow<BusinessAccessGateState> = SnapshotGateStateFlow(snapshot)

    fun currentIdentity(): BusinessIdentity? = mutableSnapshot.value.identity

    fun currentGeneration(): Long = mutableSnapshot.value.generation

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
