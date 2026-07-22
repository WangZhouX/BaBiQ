package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Immutable authority captured once for one Agent request or event subscription. */
data class ReadyAgentUsageSnapshot(
    val identity: BusinessIdentity,
    val generation: Long,
) {
    override fun toString(): String =
        "ReadyAgentUsageSnapshot(generation=$generation, identityEpoch=${identity.identityEpoch}, values=[REDACTED])"
}

class AgentAuthenticationRequiredException : IllegalStateException("auth_required")

class StaleAgentUsageException : IllegalStateException("authentication_scope_changed")

/**
 * The only application-layer gate for authenticated Agent RPC and inbound business events.
 * A permit includes the complete identity and the registry generation; both must still match at commit.
 */
class ReadyAgentUsageGate(
    private val registry: BusinessIdentityRegistry,
) {
    fun captureIfReady(): ReadyAgentUsageSnapshot? = registry.snapshot.value.toUsageSnapshotOrNull()

    fun requireReady(): ReadyAgentUsageSnapshot = captureIfReady() ?: throw AgentAuthenticationRequiredException()

    fun isCurrent(snapshot: ReadyAgentUsageSnapshot): Boolean {
        val current = registry.snapshot.value
        return current.gate == BusinessAccessGateState.READY &&
            current.generation == snapshot.generation &&
            current.identity == snapshot.identity
    }

    fun commitIfCurrent(snapshot: ReadyAgentUsageSnapshot, commit: () -> Unit): Boolean =
        registry.commitIfCurrent(snapshot.generation, snapshot.identity, commit)

    internal val readySnapshots: Flow<ReadyAgentUsageSnapshot?> = registry.snapshot
        .map(BusinessIdentityRegistrySnapshot::toUsageSnapshotOrNull)
        .distinctUntilChanged()
}

private fun BusinessIdentityRegistrySnapshot.toUsageSnapshotOrNull(): ReadyAgentUsageSnapshot? =
    identity?.takeIf { gate == BusinessAccessGateState.READY }?.let {
        ReadyAgentUsageSnapshot(identity = it, generation = generation)
    }
