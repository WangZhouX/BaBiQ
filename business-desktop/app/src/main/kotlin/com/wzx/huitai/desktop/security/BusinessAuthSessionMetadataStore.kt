package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.SecretRef
import com.wzx.huitai.security.secret.SecretStore
import com.wzx.huitai.security.secret.SecretStoreException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays

/** 与 token 分开保存、且不携带权限、角色或 token 的会话身份元数据。 */
data class BusinessAuthSessionMetadata(
    val userId: String,
    val tenantId: String,
    val platformId: String,
) {
    init {
        require(userId.isNotBlank()) { "userId must not be blank" }
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(platformId.isNotBlank()) { "platformId must not be blank" }
    }

    override fun toString(): String =
        "BusinessAuthSessionMetadata(userId=[REDACTED], tenantId=[REDACTED], platformId=[REDACTED])"
}

class BusinessAuthSessionMetadataStore(
    private val secretStore: SecretStore,
    private val entryRef: SecretRef = SecretRef.parse(DEFAULT_ALIAS),
) {
    fun load(): BusinessAuthSessionMetadata? = loadStored()?.let { stored ->
        try {
            val fields = VersionedJceksCodec.decode(stored, MAGIC, 3)
            try {
                BusinessAuthSessionMetadata(
                    VersionedJceksCodec.decodeUtf8(fields[0]),
                    VersionedJceksCodec.decodeUtf8(fields[1]),
                    VersionedJceksCodec.decodeUtf8(fields[2]),
                )
            } finally {
                fields.forEach { Arrays.fill(it, 0) }
            }
        } catch (failure: VersionedJceksCodec.InvalidEntryException) {
            throw SessionMetadataPersistenceException(INVALID_METADATA_MESSAGE)
        } catch (failure: IllegalArgumentException) {
            throw SessionMetadataPersistenceException(INVALID_METADATA_MESSAGE)
        } finally {
            Arrays.fill(stored, '\u0000')
        }
    }

    fun saveOrReplace(metadata: BusinessAuthSessionMetadata) {
        val encoded = VersionedJceksCodec.encode(
            MAGIC,
            listOf(metadata.userId.toCharArray(), metadata.tenantId.toCharArray(), metadata.platformId.toCharArray()),
        )
        try {
            save(entryRef, encoded)
        } finally {
            Arrays.fill(encoded, '\u0000')
        }
    }

    fun clear() = delete(entryRef)

    override fun toString(): String =
        "BusinessAuthSessionMetadataStore(secretStore=[REDACTED], entryRef=[REDACTED])"

    private fun loadStored(): CharArray? = try {
        secretStore.load(entryRef)
    } catch (failure: SecretStoreException) {
        throw LocalCredentialStoreUnavailableException()
    }

    private fun save(ref: SecretRef, encoded: CharArray) {
        try {
            secretStore.upsert(ref.value, encoded)
        } catch (failure: SecretStoreException) {
            throw LocalCredentialStoreUnavailableException()
        }
    }

    private fun delete(ref: SecretRef) {
        try {
            secretStore.delete(ref)
        } catch (failure: SecretStoreException) {
            throw LocalCredentialStoreUnavailableException()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "huitai.auth.session-metadata.v1"
        private const val MAGIC = 0x48534d44
        const val INVALID_METADATA_MESSAGE = "Stored authentication session metadata is invalid"
    }
}

class SessionMetadataPersistenceException(message: String) : IllegalStateException(message)

/** 可由 UI 映射为 LOCAL_KEYSTORE_UNAVAILABLE，且不会保留底层异常中的敏感上下文。 */
class LocalCredentialStoreUnavailableException : IllegalStateException("Local key store is unavailable")

internal object VersionedJceksCodec {
    private const val VERSION = 1
    private const val HEADER_BYTES = Int.SIZE_BYTES * 3
    private const val MAX_FIELD_BYTES = 64 * 1024
    private const val MAX_PAYLOAD_BYTES = 192 * 1024
    private const val HEX_DIGITS = "0123456789abcdef"

    fun encode(magic: Int, values: List<CharArray>): CharArray {
        val bytes = values.map { encodeUtf8(it) }
        var payload: ByteArray? = null
        try {
            val size = HEADER_BYTES + bytes.sumOf { Int.SIZE_BYTES + it.size }
            if (size > MAX_PAYLOAD_BYTES || bytes.any { it.isEmpty() || it.size > MAX_FIELD_BYTES }) {
                throw IllegalArgumentException("credential entry exceeds storage limits")
            }
            payload = ByteArray(size)
            ByteBuffer.wrap(payload).putInt(magic).putInt(VERSION).putInt(values.size).also { buffer ->
                bytes.forEach { field -> buffer.putInt(field.size).put(field) }
            }
            return hex(payload)
        } finally {
            bytes.forEach { Arrays.fill(it, 0) }
            payload?.let { Arrays.fill(it, 0) }
            values.forEach { Arrays.fill(it, '\u0000') }
        }
    }

    fun decode(stored: CharArray, expectedMagic: Int, expectedFields: Int): List<ByteArray> {
        val payload = unhex(stored)
        val fields = mutableListOf<ByteArray>()
        try {
            if (payload.size !in HEADER_BYTES..MAX_PAYLOAD_BYTES) throw InvalidEntryException()
            val buffer = ByteBuffer.wrap(payload)
            if (buffer.int != expectedMagic || buffer.int != VERSION || buffer.int != expectedFields) throw InvalidEntryException()
            repeat(expectedFields) {
                if (buffer.remaining() < Int.SIZE_BYTES) throw InvalidEntryException()
                val length = buffer.int
                if (length !in 1..MAX_FIELD_BYTES || length > buffer.remaining()) throw InvalidEntryException()
                fields += ByteArray(length).also(buffer::get)
            }
            if (buffer.hasRemaining()) throw InvalidEntryException()
            return fields
        } catch (failure: InvalidEntryException) {
            fields.forEach { Arrays.fill(it, 0) }
            throw failure
        } catch (failure: Exception) {
            fields.forEach { Arrays.fill(it, 0) }
            throw InvalidEntryException()
        } finally {
            Arrays.fill(payload, 0)
        }
    }

    fun decodeUtf8(bytes: ByteArray): String {
        val chars = decodeUtf8Chars(bytes)
        try {
            return chars.concatToString().also { require(it.isNotBlank()) { "stored field must not be blank" } }
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    fun decodeUtf8Chars(bytes: ByteArray): CharArray {
        var chars: CharBuffer? = null
        try {
            chars = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            if (!chars.hasRemaining()) throw InvalidEntryException()
            return CharArray(chars.remaining()).also(chars::get)
        } catch (failure: Exception) {
            throw InvalidEntryException()
        } finally {
            chars?.let {
                if (it.hasArray()) Arrays.fill(it.array(), '\u0000')
                it.clear()
            }
        }
    }

    private fun encodeUtf8(value: CharArray): ByteArray {
        val copied = value.copyOf()
        val chars = CharBuffer.wrap(copied)
        var encoded: ByteBuffer? = null
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(chars)
            return ByteArray(encoded.remaining()).also(encoded::get)
        } finally {
            encoded?.let { if (it.hasArray()) Arrays.fill(it.array(), 0) }
            Arrays.fill(copied, '\u0000')
            chars.clear()
        }
    }

    private fun hex(payload: ByteArray): CharArray = CharArray(payload.size * 2) { index ->
        val value = payload[index / 2].toInt() and 0xff
        HEX_DIGITS[if (index % 2 == 0) value ushr 4 else value and 0x0f]
    }

    private fun unhex(stored: CharArray): ByteArray {
        if (stored.size % 2 != 0 || stored.size > MAX_PAYLOAD_BYTES * 2) throw InvalidEntryException()
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
        else -> throw InvalidEntryException()
    }

    class InvalidEntryException : IllegalArgumentException()
}
