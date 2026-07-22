package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.SecretRef
import com.wzx.huitai.security.secret.SecretStore
import java.util.Arrays

class RememberedBusinessLogin internal constructor(
    val account: String,
    password: CharArray,
) : AutoCloseable {
    private val password: CharArray
    private var cleared = false

    init {
        require(account.isNotBlank()) { "account must not be blank" }
        require(password.isNotEmpty()) { "password must not be empty" }
        this.password = password.copyOf()
    }

    @Synchronized
    fun copyPassword(): CharArray {
        check(!cleared) { "Remembered login is cleared" }
        return password.copyOf()
    }

    @Synchronized
    fun clear() {
        if (cleared) return
        Arrays.fill(password, '\u0000')
        cleared = true
    }

    override fun close() = clear()

    override fun toString(): String = "RememberedBusinessLogin(account=[REDACTED], password=[REDACTED])"
}

class BusinessLoginCredentialStore internal constructor(
    private val secretStore: SecretStore,
    private val entryRef: SecretRef,
    private val codec: VersionedJceksCodec,
) {
    constructor(
        secretStore: SecretStore,
        entryRef: SecretRef = SecretRef.parse(DEFAULT_ALIAS),
    ) : this(secretStore, entryRef, VersionedJceksCodec())

    fun load(): RememberedBusinessLogin? {
        val stored = try {
            secretStore.load(entryRef)
        } catch (failure: IllegalStateException) {
            throw LocalCredentialStoreUnavailableException()
        } ?: return null
        try {
            val fields = codec.decode(stored, MAGIC, 2)
            try {
                val account = codec.decodeUtf8(fields[0])
                val password = codec.decodeUtf8Chars(fields[1])
                try {
                    require(password.isNotEmpty()) { "stored password must not be empty" }
                    return RememberedBusinessLogin(account, password)
                } finally {
                    Arrays.fill(password, '\u0000')
                }
            } finally {
                fields.forEach { Arrays.fill(it, 0) }
            }
        } catch (failure: VersionedJceksCodec.InvalidEntryException) {
            invalidateRememberedLogin()
        } catch (failure: IllegalArgumentException) {
            invalidateRememberedLogin()
        } finally {
            Arrays.fill(stored, '\u0000')
        }
    }

    fun saveOrReplace(account: String, password: CharArray) {
        require(account.isNotBlank()) { "account must not be blank" }
        require(password.isNotEmpty()) { "password must not be empty" }
        val encoded = codec.encode(MAGIC, listOf(account.toCharArray(), password.copyOf()))
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
        const val DEFAULT_ALIAS = "huitai.login.remembered.v1"
        private const val MAGIC = 0x484c4f47
    }
}

class RememberedLoginInvalidException : IllegalStateException("Remembered login is invalid")
