package com.wzx.huitai.desktop.security

import java.nio.ByteBuffer

/** Bounded, wipe-aware encoding used for non-token desktop secrets. */
internal class VersionedJceksCodec(
    onWiped: (Any) -> Unit = {},
) {
    private val secureEncoding = SecureJceksEncoding(onWiped)

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
            if (buffer.int != expectedMagic || buffer.int != VERSION || buffer.int != expectedFields) {
                throw InvalidEntryException()
            }
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
        } catch (_: Exception) {
            fields.forEach(secureEncoding::wipe)
            throw InvalidEntryException()
        } finally {
            secureEncoding.wipe(payload)
        }
    }

    fun decodeUtf8(bytes: ByteArray): String = try {
        secureEncoding.decodeUtf8(bytes, MAX_FIELD_BYTES)
            .also { require(it.isNotBlank()) { "stored field must not be blank" } }
    } catch (failure: SecureJceksEncoding.Failure) {
        throw InvalidEntryException()
    }

    class InvalidEntryException : IllegalArgumentException()

    private companion object {
        const val VERSION = 1
        const val HEADER_BYTES = Int.SIZE_BYTES * 3
        const val MAX_FIELD_BYTES = 64 * 1024
        const val MAX_PAYLOAD_BYTES = 192 * 1024
    }
}

/** Stable local error used by login UI mapping; underlying key-store details stay private. */
class LocalCredentialStoreUnavailableException : IllegalStateException("Local key store is unavailable")
