package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single reconnect authority for the identity that has completed Agent registration. */
class BusinessIdentityRegistry {
    private val lock = Any()
    private val mutableSnapshot = MutableStateFlow(BusinessIdentityRegistrySnapshot())

    val snapshot: StateFlow<BusinessIdentityRegistrySnapshot> = mutableSnapshot.asStateFlow()

    fun currentIdentity(): BusinessIdentity? = mutableSnapshot.value.identity

    fun currentGeneration(): Long = mutableSnapshot.value.generation

    /** Installs only when no logout/expiry has advanced the generation meanwhile. */
    fun install(identity: BusinessIdentity, expectedGeneration: Long): Boolean = synchronized(lock) {
        val current = mutableSnapshot.value
        if (current.generation != expectedGeneration) return@synchronized false
        mutableSnapshot.value = current.copy(identity = identity)
        true
    }

    /** Makes the previous identity immediately invisible and invalidates in-flight commits. */
    fun invalidate(): BusinessIdentity? = synchronized(lock) {
        val current = mutableSnapshot.value
        val previous = current.identity
        mutableSnapshot.value = BusinessIdentityRegistrySnapshot(null, current.generation + 1)
        previous
    }

    override fun toString(): String =
        "BusinessIdentityRegistry(generation=${mutableSnapshot.value.generation}, identity=[REDACTED])"
}

data class BusinessIdentityRegistrySnapshot(
    val identity: BusinessIdentity? = null,
    val generation: Long = 0,
)
