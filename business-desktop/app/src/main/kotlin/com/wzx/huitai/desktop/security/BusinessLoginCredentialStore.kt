package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.SecretRef
import com.wzx.huitai.security.secret.SecretStore
import com.wzx.huitai.security.secret.SecretStoreException
import java.util.Arrays

class RememberedBusinessLogin(
    val account: String,
    password: CharArray,
) {
    val password: CharArray = password
    init {
        require(account.isNotBlank()) { "account must not be blank" }
        require(password.isNotEmpty()) { "password must not be empty" }
    }

    override fun toString(): String = "RememberedBusinessLogin(account=[REDACTED], password=[REDACTED])"
}

class BusinessLoginCredentialStore(
    private val secretStore: SecretStore,
    private val entryRef: SecretRef = SecretRef.parse(DEFAULT_ALIAS),
) {
    fun load(): RememberedBusinessLogin? {
        val stored = try {
            secretStore.load(entryRef)
        } catch (failure: SecretStoreException) {
            throw LocalCredentialStoreUnavailableException()
        } ?: return null
        try {
            val fields = VersionedJceksCodec.decode(stored, MAGIC, 2)
            try {
                val account = VersionedJceksCodec.decodeUtf8(fields[0])
                val password = VersionedJceksCodec.decodeUtf8Chars(fields[1])
                try {
                    require(password.isNotEmpty()) { "stored password must not be empty" }
                    return RememberedBusinessLogin(account, password.copyOf())
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
        val encoded = VersionedJceksCodec.encode(MAGIC, listOf(account.toCharArray(), password.copyOf()))
        try {
            try {
                secretStore.upsert(entryRef.value, encoded)
            } catch (failure: SecretStoreException) {
                throw LocalCredentialStoreUnavailableException()
            }
        } finally {
            Arrays.fill(encoded, '\u0000')
        }
    }

    fun clear() {
        try {
            secretStore.delete(entryRef)
        } catch (failure: SecretStoreException) {
            throw LocalCredentialStoreUnavailableException()
        }
    }

    override fun toString(): String =
        "BusinessLoginCredentialStore(secretStore=[REDACTED], entryRef=[REDACTED])"

    private fun invalidateRememberedLogin(): Nothing {
        try {
            secretStore.delete(entryRef)
        } catch (failure: SecretStoreException) {
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
