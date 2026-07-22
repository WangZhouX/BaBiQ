package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.SecretRef
import com.wzx.huitai.security.secret.SecretStore
import com.wzx.huitai.security.secret.SecretStoreException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays
import kotlin.math.ceil

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

class BusinessAuthSessionMetadataStore internal constructor(
    private val secretStore: SecretStore,
    private val entryRef: SecretRef,
    private val codec: VersionedJceksCodec,
) {
    constructor(
        secretStore: SecretStore,
        entryRef: SecretRef = SecretRef.parse(DEFAULT_ALIAS),
    ) : this(secretStore, entryRef, VersionedJceksCodec())

    fun load(): BusinessAuthSessionMetadata? = loadStored()?.let { stored ->
        try {
            val fields = codec.decode(stored, MAGIC, 3)
            try {
                BusinessAuthSessionMetadata(
                    codec.decodeUtf8(fields[0]),
                    codec.decodeUtf8(fields[1]),
                    codec.decodeUtf8(fields[2]),
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
        val encoded = codec.encode(
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

internal class VersionedJceksCodec(
    private val onWiped: (Any) -> Unit = {},
) {
    private companion object {
        const val VERSION = 1
        const val HEADER_BYTES = Int.SIZE_BYTES * 3
        const val MAX_FIELD_BYTES = 64 * 1024
        const val MAX_PAYLOAD_BYTES = 192 * 1024
        const val HEX_DIGITS = "0123456789abcdef"
    }

    fun encode(magic: Int, values: List<CharArray>): CharArray {
        val bytes = mutableListOf<ByteArray>()
        var payload: ByteArray? = null
        try {
            values.forEach { bytes += encodeUtf8(it) }
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
            bytes.forEach(::wipe)
            payload?.let(::wipe)
            values.forEach(::wipe)
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
            fields.forEach(::wipe)
            throw failure
        } catch (failure: Exception) {
            fields.forEach(::wipe)
            throw InvalidEntryException()
        } finally {
            wipe(payload)
        }
    }

    fun decodeUtf8(bytes: ByteArray): String {
        val chars = decodeUtf8Chars(bytes)
        try {
            return chars.concatToString().also { require(it.isNotBlank()) { "stored field must not be blank" } }
        } finally {
            wipe(chars)
        }
    }

    fun decodeUtf8Chars(bytes: ByteArray): CharArray {
        if (bytes.isEmpty() || bytes.size > MAX_FIELD_BYTES) throw InvalidEntryException()
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val stagingCapacity = boundedCapacity(bytes.size, decoder.maxCharsPerByte(), MAX_FIELD_BYTES)
        val staging = CharBuffer.allocate(stagingCapacity)
        try {
            val decoded = decoder.decode(ByteBuffer.wrap(bytes), staging, true)
            if (decoded.isError || decoded.isOverflow || !decoded.isUnderflow) throw InvalidEntryException()
            val flushed = decoder.flush(staging)
            if (flushed.isError || flushed.isOverflow || !flushed.isUnderflow) throw InvalidEntryException()
            staging.flip()
            if (!staging.hasRemaining()) throw InvalidEntryException()
            return CharArray(staging.remaining()).also(staging::get)
        } finally {
            wipe(staging.array())
            staging.clear()
        }
    }

    private fun encodeUtf8(value: CharArray): ByteArray {
        val copied = value.copyOf()
        val chars = CharBuffer.wrap(copied)
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val stagingCapacity = boundedCapacity(copied.size, encoder.maxBytesPerChar(), MAX_FIELD_BYTES)
        val staging = ByteBuffer.allocate(stagingCapacity)
        try {
            val encoded = encoder.encode(chars, staging, true)
            when {
                encoded.isOverflow -> throw IllegalArgumentException("credential entry exceeds storage limits")
                encoded.isError || !encoded.isUnderflow ->
                    throw IllegalArgumentException("credential entry contains invalid UTF-8")
            }
            val flushed = encoder.flush(staging)
            when {
                flushed.isOverflow -> throw IllegalArgumentException("credential entry exceeds storage limits")
                flushed.isError || !flushed.isUnderflow ->
                    throw IllegalArgumentException("credential entry contains invalid UTF-8")
            }
            staging.flip()
            return ByteArray(staging.remaining()).also(staging::get)
        } finally {
            wipe(staging.array())
            staging.clear()
            wipe(copied)
            chars.clear()
        }
    }

    private fun boundedCapacity(inputSize: Int, expansion: Float, limit: Int): Int {
        val safeUpperBound = ceil(inputSize.toDouble() * expansion.toDouble())
        if (!safeUpperBound.isFinite() || safeUpperBound > Long.MAX_VALUE.toDouble()) {
            throw IllegalArgumentException("credential entry exceeds storage limits")
        }
        return safeUpperBound.coerceAtMost(limit.toDouble()).toInt().coerceAtLeast(1)
    }

    private fun hex(payload: ByteArray): CharArray = CharArray(payload.size * 2) { index ->
        val value = payload[index / 2].toInt() and 0xff
        HEX_DIGITS[if (index % 2 == 0) value ushr 4 else value and 0x0f]
    }

    private fun unhex(stored: CharArray): ByteArray {
        if (stored.size % 2 != 0 || stored.size > MAX_PAYLOAD_BYTES * 2) throw InvalidEntryException()
        val payload = ByteArray(stored.size / 2)
        try {
            payload.indices.forEach { index ->
                val high = hexValue(stored[index * 2])
                val low = hexValue(stored[index * 2 + 1])
                payload[index] = ((high shl 4) or low).toByte()
            }
            return payload
        } catch (failure: Exception) {
            wipe(payload)
            throw failure
        }
    }

    private fun hexValue(value: Char): Int = when (value) {
        in '0'..'9' -> value - '0'
        in 'a'..'f' -> value - 'a' + 10
        in 'A'..'F' -> value - 'A' + 10
        else -> throw InvalidEntryException()
    }

    private fun wipe(value: ByteArray) {
        Arrays.fill(value, 0)
        onWiped(value)
    }

    private fun wipe(value: CharArray) {
        Arrays.fill(value, '\u0000')
        onWiped(value)
    }

    class InvalidEntryException : IllegalArgumentException()
}
