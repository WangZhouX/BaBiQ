package com.wzx.huitai.integration.oa.auth

import java.security.MessageDigest

/** OA 登录协议的双 MD5 摘要器。输入仅接受 ASCII 字母和数字，便于无 String 明文编码。 */
object OaPasswordEncoder {
    fun encode(password: CharArray): String {
        try {
            if (password.size !in 8..16 || password.none { it.isAsciiLetter() } || password.none { it.isDigit() }) {
                throw OaAuthenticationException(OaAuthenticationError.INVALID_PASSWORD_FORMAT)
            }
            val passwordBytes = password.toUtf8Bytes()
            val salt = SALT.toCharArray()
            val saltBytes = salt.toUtf8Bytes()
            val firstInput = ByteArray(passwordBytes.size + saltBytes.size)
            val firstDigest: ByteArray
            try {
                passwordBytes.copyInto(firstInput)
                saltBytes.copyInto(firstInput, destinationOffset = passwordBytes.size)
                firstDigest = MessageDigest.getInstance("MD5").digest(firstInput)
            } finally {
                passwordBytes.fill(0)
                saltBytes.fill(0)
                firstInput.fill(0)
                salt.fill('\u0000')
            }
            val firstHex = firstDigest.toLowerHexChars()
            firstDigest.fill(0)
            return try {
                val secondInput = ByteArray(firstHex.size)
                try {
                    firstHex.forEachIndexed { index, value -> secondInput[index] = value.code.toByte() }
                    MessageDigest.getInstance("MD5").digest(secondInput).toLowerHexString()
                } finally {
                    secondInput.fill(0)
                }
            } finally {
                firstHex.fill('\u0000')
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun ByteArray.toLowerHexChars(): CharArray {
        val result = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = HEX[value ushr 4]
            result[index * 2 + 1] = HEX[value and 0x0f]
        }
        return result
    }

    private fun CharArray.toUtf8Bytes(): ByteArray {
        val scratch = ByteArray(size * 3)
        var offset = 0
        var index = 0
        while (index < size) {
            var codePoint = this[index++].code
            if (codePoint in 0xd800..0xdbff && index < size && this[index].code in 0xdc00..0xdfff) {
                codePoint = Character.toCodePoint(codePoint.toChar(), this[index++])
            } else if (codePoint in 0xd800..0xdfff) {
                codePoint = 0xfffd
            }
            when {
                codePoint <= 0x7f -> scratch[offset++] = codePoint.toByte()
                codePoint <= 0x7ff -> {
                    scratch[offset++] = (0xc0 or (codePoint ushr 6)).toByte()
                    scratch[offset++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
                codePoint <= 0xffff -> {
                    scratch[offset++] = (0xe0 or (codePoint ushr 12)).toByte()
                    scratch[offset++] = (0x80 or ((codePoint ushr 6) and 0x3f)).toByte()
                    scratch[offset++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
                else -> {
                    scratch[offset++] = (0xf0 or (codePoint ushr 18)).toByte()
                    scratch[offset++] = (0x80 or ((codePoint ushr 12) and 0x3f)).toByte()
                    scratch[offset++] = (0x80 or ((codePoint ushr 6) and 0x3f)).toByte()
                    scratch[offset++] = (0x80 or (codePoint and 0x3f)).toByte()
                }
            }
        }
        return try {
            scratch.copyOf(offset)
        } finally {
            scratch.fill(0)
        }
    }

    private fun ByteArray.toLowerHexString(): String = concatToString(toLowerHexChars())

    private fun concatToString(chars: CharArray): String = try {
        chars.concatToString()
    } finally {
        chars.fill('\u0000')
    }

    private const val SALT = "huitaisystem"
    private const val HEX = "0123456789abcdef"
}
