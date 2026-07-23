package com.wzx.huitai.desktop.auth

import com.wzx.huitai.integration.http.HuitaiHttpClient
import com.wzx.huitai.integration.http.HuitaiRequest
import com.wzx.huitai.integration.http.HuitaiResponse
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** The only production-facing post-login OA HTTP client. The raw token client never escapes. */
class ReadyAuthenticatedHuitaiClient internal constructor(
    private val gate: ReadyAuthenticatedHttpGate,
    private val delegate: HuitaiHttpClient,
    private val closeDelegate: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    suspend fun send(request: HuitaiRequest): HuitaiResponse? {
        check(!closed.get()) { "authenticated HTTP client is closed" }
        return gate.execute { delegate.send(request) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeDelegate()
    }

    override fun toString(): String = "ReadyAuthenticatedHuitaiClient(delegate=[REDACTED])"
}
