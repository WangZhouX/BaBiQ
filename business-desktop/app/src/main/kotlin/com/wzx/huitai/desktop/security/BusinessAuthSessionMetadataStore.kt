package com.wzx.huitai.desktop.security

import com.wzx.huitai.security.secret.SecretRef
import com.wzx.huitai.security.secret.SecretStore
import java.nio.ByteBuffer
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
    } catch (failure: IllegalStateException) {
        throw LocalCredentialStoreUnavailableException()
    }

    private fun save(ref: SecretRef, encoded: CharArray) {
        try {
            secretStore.upsert(ref.value, encoded)
        } catch (failure: IllegalStateException) {
            throw LocalCredentialStoreUnavailableException()
        }
    }

    private fun delete(ref: SecretRef) {
        try {
            secretStore.delete(ref)
        } catch (failure: IllegalStateException) {
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
    onWiped: (Any) -> Unit = {},
) {
    private val secureEncoding = SecureJceksEncoding(onWiped)

    private companion object {
        const val VERSION = 1
        const val HEADER_BYTES = Int.SIZE_BYTES * 3
        const val MAX_FIELD_BYTES = 64 * 1024
        const val MAX_PAYLOAD_BYTES = 192 * 1024
    }

    fun encode(magic: Int, values: List<CharArray>): CharArray {
        val bytes = mutableListOf<ByteArray>()
        var payload: ByteArray? = null
        try {
            values.forEach { bytes += secureEncoding.encodeUtf8(it, MAX_FIELD_BYTES) }
            val size = HEADER_BYTES + bytes.sumOf { Int.SIZE_BYTES + it.size }
            if (size > MAX_PAYLOAD_BYTES || bytes.any { it.isEmpty() || it.size > MAX_FIELD_BYTES }) {
                throw IllegalArgumentException("credential entry exceeds storage limits")
            }
            payload = ByteArray(size)
            ByteBuffer.wrap(payload).putInt(magic).putInt(VERSION).putInt(values.size).also { buffer ->
                bytes.forEach { field -> buffer.putInt(field.size).put(field) }
            }
            return secureEncoding.encodeHex(payload)
        } catch (failure: SecureJceksEncoding.Failure) {
            throw when (failure.reason) {
                SecureJceksEncoding.FailureReason.TOO_LARGE ->
                    IllegalArgumentException("credential entry exceeds storage limits")
                else -> IllegalArgumentException("credential entry contains invalid UTF-8")
            }
        } finally {
            bytes.forEach(secureEncoding::wipe)
            payload?.let(secureEncoding::wipe)
            values.forEach(secureEncoding::wipe)
        }
    }

    fun decode(stored: CharArray, expectedMagic: Int, expectedFields: Int): List<ByteArray> {
        val payload = try {
            secureEncoding.decodeHex(stored, MAX_PAYLOAD_BYTES)
        } catch (failure: SecureJceksEncoding.Failure) {
            throw InvalidEntryException()
        }
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
            fields.forEach(secureEncoding::wipe)
            throw failure
        } catch (failure: Exception) {
            fields.forEach(secureEncoding::wipe)
            throw InvalidEntryException()
        } finally {
            secureEncoding.wipe(payload)
        }
    }

    fun decodeUtf8(bytes: ByteArray): String {
        return try {
            secureEncoding.decodeUtf8(bytes, MAX_FIELD_BYTES)
                .also { require(it.isNotBlank()) { "stored field must not be blank" } }
        } catch (failure: SecureJceksEncoding.Failure) {
            throw InvalidEntryException()
        }
    }

    fun decodeUtf8Chars(bytes: ByteArray): CharArray = try {
        secureEncoding.decodeUtf8Chars(bytes, MAX_FIELD_BYTES)
    } catch (failure: SecureJceksEncoding.Failure) {
        throw InvalidEntryException()
    }

    class InvalidEntryException : IllegalArgumentException()
}
