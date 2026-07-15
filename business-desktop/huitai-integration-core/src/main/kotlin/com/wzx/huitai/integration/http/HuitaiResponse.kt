package com.wzx.huitai.integration.http

import com.wzx.huitai.action.model.ActionErrorCode

/** 解码后的汇泰响应，不包含 UI 或重试决策。 */
sealed interface HuitaiResponse {
    data class Success(
        val result: CommonResult,
    ) : HuitaiResponse {
        override fun toString(): String = "Success(result=$result)"
    }

    class Binary(
        val contentType: String?,
        body: ByteArray,
    ) : HuitaiResponse {
        private val bodyBytes = body.copyOf()

        val body: ByteArray
            get() = bodyBytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Binary && contentType == other.contentType && bodyBytes.contentEquals(other.bodyBytes)

        override fun hashCode(): Int = 31 * contentType.hashCode() + bodyBytes.contentHashCode()

        override fun toString(): String =
            "Binary(contentType=$contentType, body=[REDACTED:${bodyBytes.size} bytes])"
    }

    data class Failure(
        val errorCode: ActionErrorCode,
        val remoteCode: String? = null,
        val remoteMessage: String? = null,
    ) : HuitaiResponse {
        override fun toString(): String =
            "Failure(errorCode=$errorCode, remoteCode=$remoteCode, remoteMessage=[REDACTED])"
    }
}
