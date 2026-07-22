package com.wzx.huitai.desktop.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays
import kotlin.math.ceil

/** JCEKS payloads share this bounded, wipe-aware binary/text encoding primitive. */
internal class SecureJceksEncoding(
    private val onWiped: (Any) -> Unit = {},
) {
    fun encodeUtf8(value: CharArray, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        var copied: CharArray? = null
        var input: CharBuffer? = null
        var staging: ByteBuffer? = null
        try {
            copied = value.copyOf()
            input = CharBuffer.wrap(copied)
            val encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            staging = ByteBuffer.allocate(boundedCapacity(copied.size, encoder.maxBytesPerChar(), maxBytes))
            val encoded = encoder.encode(input, staging, true)
            when {
                encoded.isOverflow -> throw Failure(FailureReason.TOO_LARGE)
                encoded.isError || !encoded.isUnderflow -> throw Failure(FailureReason.INVALID_TEXT)
            }
            val flushed = encoder.flush(staging)
            when {
                flushed.isOverflow -> throw Failure(FailureReason.TOO_LARGE)
                flushed.isError || !flushed.isUnderflow -> throw Failure(FailureReason.INVALID_TEXT)
            }
            staging.flip()
            return ByteArray(staging.remaining()).also(staging::get)
        } finally {
            staging?.let {
                wipe(it.array())
                it.clear()
            }
            copied?.let(::wipe)
            input?.clear()
            wipe(value)
        }
    }

    fun decodeUtf8(bytes: ByteArray, maxBytes: Int): String {
        val chars = decodeUtf8Chars(bytes, maxBytes)
        try {
            return chars.concatToString()
        } finally {
            wipe(chars)
        }
    }

    fun decodeUtf8Chars(bytes: ByteArray, maxBytes: Int): CharArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (bytes.isEmpty() || bytes.size > maxBytes) throw Failure(FailureReason.INVALID_BINARY)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val staging = CharBuffer.allocate(boundedCapacity(bytes.size, decoder.maxCharsPerByte(), maxBytes))
        try {
            val decoded = decoder.decode(ByteBuffer.wrap(bytes), staging, true)
            if (decoded.isError || decoded.isOverflow || !decoded.isUnderflow) throw Failure(FailureReason.INVALID_BINARY)
            val flushed = decoder.flush(staging)
            if (flushed.isError || flushed.isOverflow || !flushed.isUnderflow) throw Failure(FailureReason.INVALID_BINARY)
            staging.flip()
            if (!staging.hasRemaining()) throw Failure(FailureReason.INVALID_BINARY)
            return CharArray(staging.remaining()).also(staging::get)
        } finally {
            wipe(staging.array())
            staging.clear()
        }
    }

    fun encodeHex(payload: ByteArray): CharArray = CharArray(payload.size * 2) { index ->
        val value = payload[index / 2].toInt() and 0xff
        HEX_DIGITS[if (index % 2 == 0) value ushr 4 else value and 0x0f]
    }

    fun decodeHex(stored: CharArray, maxPayloadBytes: Int): ByteArray {
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
        if (stored.size % 2 != 0 || stored.size.toLong() > maxPayloadBytes.toLong() * 2L) {
            throw Failure(FailureReason.INVALID_BINARY)
        }
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

    fun wipe(value: ByteArray) {
        Arrays.fill(value, 0)
        onWiped(value)
    }

    fun wipe(value: CharArray) {
        Arrays.fill(value, '\u0000')
        onWiped(value)
    }

    private fun boundedCapacity(inputSize: Int, expansion: Float, limit: Int): Int {
        val safeUpperBound = ceil(inputSize.toDouble() * expansion.toDouble())
        if (!safeUpperBound.isFinite() || safeUpperBound > Long.MAX_VALUE.toDouble()) {
            throw Failure(FailureReason.TOO_LARGE)
        }
        return safeUpperBound.coerceAtMost(limit.toDouble()).toInt().coerceAtLeast(1)
    }

    private fun hexValue(value: Char): Int = when (value) {
        in '0'..'9' -> value - '0'
        in 'a'..'f' -> value - 'a' + 10
        in 'A'..'F' -> value - 'A' + 10
        else -> throw Failure(FailureReason.INVALID_BINARY)
    }

    internal class Failure(val reason: FailureReason) : IllegalArgumentException("secure credential encoding failed")

    internal enum class FailureReason {
        INVALID_TEXT,
        INVALID_BINARY,
        TOO_LARGE,
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
