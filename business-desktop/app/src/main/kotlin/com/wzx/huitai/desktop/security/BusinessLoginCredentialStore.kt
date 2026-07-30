package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.SecretRef
import com.wzx.huitai.security.secret.SecretStore
import java.util.Arrays

/** Account-only remembered login state. The OA password is never persisted on the desktop. */
class RememberedBusinessLogin internal constructor(
    val account: String,
) : AutoCloseable {
    init {
        require(account.isNotBlank()) { "account must not be blank" }
    }

    override fun close() = Unit

    override fun toString(): String = "RememberedBusinessLogin(account=[REDACTED])"
}

class BusinessLoginCredentialStore internal constructor(
    private val secretStore: SecretStore,
    private val codec: VersionedJceksCodec,
) {
    constructor(secretStore: SecretStore) : this(secretStore, VersionedJceksCodec())

    private val entryRef = SecretRef.parse(DEFAULT_ALIAS)

    fun load(): RememberedBusinessLogin? {
        val stored = try {
            secretStore.load(entryRef)
        } catch (failure: IllegalStateException) {
            throw LocalCredentialStoreUnavailableException()
        } ?: return null
        try {
            val fields = codec.decode(stored, MAGIC, 1)
            try {
                return RememberedBusinessLogin(codec.decodeUtf8(fields[0]))
            } finally {
                fields.forEach { Arrays.fill(it, 0) }
            }
        } catch (_: VersionedJceksCodec.InvalidEntryException) {
            invalidateRememberedLogin()
        } catch (_: IllegalArgumentException) {
            invalidateRememberedLogin()
        } finally {
            Arrays.fill(stored, '\u0000')
        }
    }

    fun saveOrReplace(account: String) {
        require(account.isNotBlank()) { "account must not be blank" }
        val encoded = codec.encode(MAGIC, listOf(account.toCharArray()))
        try {
            try {
                secretStore.upsert(entryRef.value, encoded)
            } catch (failure: IllegalStateException) {
                throw LocalCredentialStoreUnavailableException()
            }
        } finally {
            Arrays.fill(encoded, '\u0000')
        }
    }

    fun clear() {
        try {
            secretStore.delete(entryRef)
        } catch (failure: IllegalStateException) {
            throw LocalCredentialStoreUnavailableException()
        }
    }

    override fun toString(): String =
        "BusinessLoginCredentialStore(secretStore=[REDACTED], entryRef=[REDACTED])"

    private fun invalidateRememberedLogin(): Nothing {
        try {
            secretStore.delete(entryRef)
        } catch (failure: IllegalStateException) {
            throw LocalCredentialStoreUnavailableException()
        }
        throw RememberedLoginInvalidException()
    }

    companion object {
        const val DEFAULT_ALIAS = "huitai.login.account.v2"
        private const val MAGIC = 0x484c4f47
    }
}

class RememberedLoginInvalidException : IllegalStateException("Remembered login is invalid")
