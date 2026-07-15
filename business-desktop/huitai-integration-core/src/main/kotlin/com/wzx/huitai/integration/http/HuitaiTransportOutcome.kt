package com.wzx.huitai.integration.http

import java.util.Collections

/** 精确描述请求是否已发送及是否收到响应。 */
sealed interface HuitaiTransportOutcome {
    data object NotSent : HuitaiTransportOutcome

    class ResponseReceived(
        val httpStatus: Int,
        headers: Map<String, List<String>> = emptyMap(),
        body: ByteArray = byteArrayOf(),
        val authenticationRefreshCompleted: Boolean = false,
    ) : HuitaiTransportOutcome {
        val headers: Map<String, List<String>> = Collections.unmodifiableMap(
            LinkedHashMap<String, List<String>>().apply {
                headers.forEach { (name, values) ->
                    put(name, Collections.unmodifiableList(ArrayList(values)))
                }
            },
        )

        private val bodyBytes = body.copyOf()

        val body: ByteArray
            get() = bodyBytes.copyOf()

        override fun equals(other: Any?): Boolean =
            other is ResponseReceived &&
                httpStatus == other.httpStatus &&
                headers == other.headers &&
                bodyBytes.contentEquals(other.bodyBytes) &&
                authenticationRefreshCompleted == other.authenticationRefreshCompleted

        override fun hashCode(): Int {
            var result = httpStatus
            result = 31 * result + headers.hashCode()
            result = 31 * result + bodyBytes.contentHashCode()
            result = 31 * result + authenticationRefreshCompleted.hashCode()
            return result
        }

        override fun toString(): String =
            "ResponseReceived(httpStatus=$httpStatus, headers=[REDACTED], " +
                "body=[REDACTED:${bodyBytes.size} bytes], " +
                "authenticationRefreshCompleted=$authenticationRefreshCompleted)"
    }

    data object AmbiguousAfterSend : HuitaiTransportOutcome
}
