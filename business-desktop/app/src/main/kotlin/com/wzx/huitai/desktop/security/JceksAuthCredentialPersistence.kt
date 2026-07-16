package com.wzx.huitai.desktop.security

import com.wzx.huitai.integration.auth.AuthCredentialPersistencePort
import com.wzx.huitai.integration.auth.AuthTokenSet
import com.wzx.huitai.security.secret.SecretRef
import com.wzx.huitai.security.secret.SecretStore
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays

class JceksAuthCredentialPersistence(
    private val secretStore: SecretStore,
    private val entryRef: SecretRef = SecretRef.parse(DEFAULT_ALIAS),
) : AuthCredentialPersistencePort {
    override suspend fun load(): AuthTokenSet? {
        val stored = secretStore.load(entryRef) ?: return null
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
            secretStore.upsert(entryRef.value, encoded)
        } finally {
            Arrays.fill(encoded, '\u0000')
        }
    }

    override suspend fun clear() {
        secretStore.delete(entryRef)
    }

    override fun toString(): String =
        "JceksAuthCredentialPersistence(secretStore=[REDACTED], entryRef=[REDACTED])"

    private fun encode(tokens: AuthTokenSet): CharArray {
        val accessBytes = tokens.accessToken.toByteArray(StandardCharsets.UTF_8)
        val refreshBytes = tokens.refreshToken.toByteArray(StandardCharsets.UTF_8)
        var payload: ByteArray? = null
        try {
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
            return encodeHex(payload)
        } finally {
            Arrays.fill(accessBytes, 0)
            Arrays.fill(refreshBytes, 0)
            payload?.let { Arrays.fill(it, 0) }
        }
    }

    private fun decode(stored: CharArray): AuthTokenSet {
        val payload = decodeHex(stored)
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
            val accessToken = decodeUtf8(accessBytes)
            val refreshToken = decodeUtf8(refreshBytes)
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
            accessBytes?.let { Arrays.fill(it, 0) }
            refreshBytes?.let { Arrays.fill(it, 0) }
            Arrays.fill(payload, 0)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        var chars: CharBuffer? = null
        try {
            chars = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            return chars.toString()
        } finally {
            chars?.let {
                if (it.hasArray()) Arrays.fill(it.array(), '\u0000')
                it.clear()
            }
        }
    }

    private fun encodeHex(payload: ByteArray): CharArray = CharArray(payload.size * 2) { index ->
        val value = payload[index / 2].toInt() and 0xff
        HEX_DIGITS[if (index % 2 == 0) value ushr 4 else value and 0x0f]
    }

    private fun decodeHex(stored: CharArray): ByteArray {
        if (stored.size % 2 != 0 || stored.size > MAX_PAYLOAD_BYTES * 2) throw invalidCredentials()
        return ByteArray(stored.size / 2) { index ->
            val high = hexValue(stored[index * 2])
            val low = hexValue(stored[index * 2 + 1])
            ((high shl 4) or low).toByte()
        }
    }

    private fun hexValue(value: Char): Int = when (value) {
        in '0'..'9' -> value - '0'
        in 'a'..'f' -> value - 'a' + 10
        in 'A'..'F' -> value - 'A' + 10
        else -> throw invalidCredentials()
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
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}

class CredentialPersistenceException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
