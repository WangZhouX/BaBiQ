package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.state.BusinessIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single reconnect authority for the identity that has completed Agent registration. */
class BusinessIdentityRegistry {
    private val lock = Any()
    private val mutableIdentity = MutableStateFlow<BusinessIdentity?>(null)
    private val mutableGeneration = MutableStateFlow(0L)

    val identity: StateFlow<BusinessIdentity?> = mutableIdentity.asStateFlow()
    val generation: StateFlow<Long> = mutableGeneration.asStateFlow()

    fun currentIdentity(): BusinessIdentity? = mutableIdentity.value

    fun currentGeneration(): Long = mutableGeneration.value

    /** Installs only when no logout/expiry has advanced the generation meanwhile. */
    fun install(identity: BusinessIdentity, expectedGeneration: Long): Boolean = synchronized(lock) {
        if (mutableGeneration.value != expectedGeneration) return@synchronized false
        mutableIdentity.value = identity
        true
    }

    /** Makes the previous identity immediately invisible and invalidates in-flight commits. */
    fun invalidate(): BusinessIdentity? = synchronized(lock) {
        val previous = mutableIdentity.value
        mutableGeneration.value = mutableGeneration.value + 1
        mutableIdentity.value = null
        previous
    }

    override fun toString(): String =
        "BusinessIdentityRegistry(generation=${mutableGeneration.value}, identity=[REDACTED])"
}
