package com.wzx.huitai.integration.http

/** 精确描述请求是否已发送及是否收到响应。 */
sealed interface HuitaiTransportOutcome {
    data object NotSent : HuitaiTransportOutcome

    data class ResponseReceived(
        val httpStatus: Int,
        val authenticationRefreshCompleted: Boolean = false,
    ) : HuitaiTransportOutcome

    data object AmbiguousAfterSend : HuitaiTransportOutcome
}
