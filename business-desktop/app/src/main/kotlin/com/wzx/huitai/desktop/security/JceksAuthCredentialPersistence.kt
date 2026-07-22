package com.wzx.huitai.desktop.security

import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.security.secret.SecretRef
import com.wzx.huitai.security.secret.SecretStore
import java.nio.ByteBuffer
import java.util.Arrays

class JceksAuthCredentialPersistence internal constructor(
    private val secretStore: SecretStore,
    private val entryRef: SecretRef,
    private val secureEncoding: SecureJceksEncoding,
) : AuthCredentialPersistencePort {
    constructor(
        secretStore: SecretStore,
        entryRef: SecretRef = SecretRef.parse(DEFAULT_ALIAS),
    ) : this(secretStore, entryRef, SecureJceksEncoding())

    override suspend fun load(): AuthTokenSet? {
        val stored = try {
            secretStore.load(entryRef)
        } catch (_: IllegalStateException) {
            throw LocalCredentialStoreUnavailableException()
        } ?: return null
        return try {
            decode(stored)
        } catch (failure: CredentialPersistenceException) {
            throw failure
        } catch (failure: Exception) {
            throw invalidCredentials(failure)
        } finally {
            Arrays.fill(stored, '\u0000')
        }
    }

    override suspend fun replace(tokens: AuthTokenSet) {
        val encoded = encode(tokens)
        try {
            try {
                secretStore.upsert(entryRef.value, encoded)
            } catch (_: IllegalStateException) {
                throw LocalCredentialStoreUnavailableException()
            }
        } finally {
            Arrays.fill(encoded, '\u0000')
        }
    }

    override suspend fun clear() {
        try {
            secretStore.delete(entryRef)
        } catch (_: IllegalStateException) {
            throw LocalCredentialStoreUnavailableException()
        }
    }

    override fun toString(): String =
        "JceksAuthCredentialPersistence(secretStore=[REDACTED], entryRef=[REDACTED])"

    private fun encode(tokens: AuthTokenSet): CharArray {
        var accessBytes: ByteArray? = null
        var refreshBytes: ByteArray? = null
        var payload: ByteArray? = null
        try {
            accessBytes = secureEncoding.encodeUtf8(tokens.accessToken.toCharArray(), MAX_TOKEN_BYTES)
            refreshBytes = secureEncoding.encodeUtf8(tokens.refreshToken.toCharArray(), MAX_TOKEN_BYTES)
            validateTokenLength(accessBytes.size)
            validateTokenLength(refreshBytes.size)
            val payloadSize = HEADER_SIZE + accessBytes.size + refreshBytes.size
            if (payloadSize > MAX_PAYLOAD_BYTES) throw credentialsTooLarge()
            payload = ByteArray(payloadSize)
            ByteBuffer.wrap(payload)
                .putInt(MAGIC)
                .putInt(VERSION)
                .putInt(accessBytes.size)
                .put(accessBytes)
                .putInt(refreshBytes.size)
                .put(refreshBytes)
            return secureEncoding.encodeHex(payload)
        } catch (failure: SecureJceksEncoding.Failure) {
            throw if (failure.reason == SecureJceksEncoding.FailureReason.TOO_LARGE) {
                credentialsTooLarge()
            } else {
                CredentialPersistenceException("Authentication credentials are invalid")
            }
        } finally {
            accessBytes?.let(secureEncoding::wipe)
            refreshBytes?.let(secureEncoding::wipe)
            payload?.let(secureEncoding::wipe)
        }
    }

    private fun decode(stored: CharArray): AuthTokenSet {
        val payload = try {
            secureEncoding.decodeHex(stored, MAX_PAYLOAD_BYTES)
        } catch (failure: SecureJceksEncoding.Failure) {
            throw invalidCredentials()
        }
        var accessBytes: ByteArray? = null
        var refreshBytes: ByteArray? = null
        try {
            if (payload.size !in HEADER_SIZE..MAX_PAYLOAD_BYTES) throw invalidCredentials()
            val buffer = ByteBuffer.wrap(payload)
            if (buffer.int != MAGIC || buffer.int != VERSION) throw invalidCredentials()
            val accessLength = buffer.int
            validateStoredLength(accessLength, buffer.remaining(), requiresTrailingLength = true)
            accessBytes = ByteArray(accessLength).also(buffer::get)
            if (buffer.remaining() < Int.SIZE_BYTES) throw invalidCredentials()
            val refreshLength = buffer.int
            validateStoredLength(refreshLength, buffer.remaining(), requiresTrailingLength = false)
            if (buffer.remaining() != refreshLength) throw invalidCredentials()
            refreshBytes = ByteArray(refreshLength).also(buffer::get)
            val accessToken = secureEncoding.decodeUtf8(accessBytes, MAX_TOKEN_BYTES)
            val refreshToken = secureEncoding.decodeUtf8(refreshBytes, MAX_TOKEN_BYTES)
            return try {
                AuthTokenSet(accessToken, refreshToken)
            } catch (failure: IllegalArgumentException) {
                throw invalidCredentials(failure)
            }
        } catch (failure: CredentialPersistenceException) {
            throw failure
        } catch (failure: Exception) {
            throw invalidCredentials(failure)
        } finally {
            accessBytes?.let(secureEncoding::wipe)
            refreshBytes?.let(secureEncoding::wipe)
            secureEncoding.wipe(payload)
        }
    }

    private fun validateTokenLength(length: Int) {
        if (length !in 1..MAX_TOKEN_BYTES) throw credentialsTooLarge()
    }

    private fun validateStoredLength(length: Int, remaining: Int, requiresTrailingLength: Boolean) {
        if (length !in 1..MAX_TOKEN_BYTES) throw invalidCredentials()
        val required = length + if (requiresTrailingLength) Int.SIZE_BYTES else 0
        if (remaining < required) throw invalidCredentials()
    }

    private fun invalidCredentials(cause: Throwable? = null) =
        CredentialPersistenceException(INVALID_CREDENTIALS_MESSAGE, cause)

    private fun credentialsTooLarge() =
        CredentialPersistenceException("Authentication credentials exceed storage limits")

    companion object {
        const val DEFAULT_ALIAS = "huitai.auth.tokens.v1"
        private const val MAGIC = 0x48544352
        private const val VERSION = 1
        private const val HEADER_SIZE = 4 * Int.SIZE_BYTES
        private const val MAX_TOKEN_BYTES = 64 * 1024
        private const val MAX_PAYLOAD_BYTES = 128 * 1024
        private const val INVALID_CREDENTIALS_MESSAGE = "Stored authentication credentials are invalid"
    }
}

class CredentialPersistenceException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
