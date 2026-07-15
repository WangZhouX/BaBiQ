package com.wzx.huitai.integration.http

fun interface HuitaiTransport {
    suspend fun send(request: HuitaiRequest): HuitaiTransportOutcome
}
