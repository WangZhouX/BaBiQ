package com.wzx.huitai.integration.websocket

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ktor 实现只负责握手和文本帧，不理解 OA 事件或订阅协议。 */
class KtorHuitaiWebSocketTransport(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val incomingCapacity: Int = 128,
) : HuitaiWebSocketTransport {
    init {
        require(incomingCapacity > 0) { "incomingCapacity must be positive" }
    }

    override fun connect(request: HuitaiWebSocketConnectRequest): HuitaiWebSocketConnection =
        KtorConnection(request)

    private inner class KtorConnection(
        private val request: HuitaiWebSocketConnectRequest,
    ) : HuitaiWebSocketConnection {
        private val mutableIncoming = Channel<String>(
            capacity = incomingCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        private val mutableState = MutableStateFlow<HuitaiWebSocketState>(HuitaiWebSocketState.Connecting)
        private val session = AtomicReference<DefaultClientWebSocketSession?>(null)
        private val explicitlyClosed = AtomicBoolean(false)
        private val readerJob: Job = scope.launch { runConnection() }

        override val incoming: ReceiveChannel<String> = mutableIncoming
        override val state: StateFlow<HuitaiWebSocketState> = mutableState.asStateFlow()

        override suspend fun close() {
            if (!explicitlyClosed.compareAndSet(false, true)) return
            session.getAndSet(null)?.close(CloseReason(CloseReason.Codes.NORMAL, "client close"))
            readerJob.cancelAndJoin()
            mutableState.value = HuitaiWebSocketState.Closed(
                code = CloseReason.Codes.NORMAL.code.toInt(),
                reasonPresent = false,
            )
            mutableIncoming.close()
        }

        private suspend fun runConnection() {
            try {
                httpClient.prepareRequest(request.url) {
                    header(HttpHeaders.Authorization, "Bearer ${request.accessToken}")
                    header("tenant-id", request.tenantId)
                }.execute { response ->
                    if (response.status == HttpStatusCode.Unauthorized ||
                        response.status == HttpStatusCode.Forbidden
                    ) {
                        mutableState.value = HuitaiWebSocketState.Error(
                            HuitaiWebSocketFailureKind.AUTHENTICATION_REJECTED,
                        )
                        return@execute
                    }

                    val connected = response.body<DefaultClientWebSocketSession>()
                    if (explicitlyClosed.get()) {
                        connected.close(CloseReason(CloseReason.Codes.NORMAL, "client close"))
                        return@execute
                    }
                    session.set(connected)
                    mutableState.value = HuitaiWebSocketState.Connected
                    for (frame in connected.incoming) {
                        if (frame is Frame.Text) mutableIncoming.trySend(frame.readText())
                    }
                    val closeReason = connected.closeReason.await()
                    mutableState.value = HuitaiWebSocketState.Closed(
                        code = closeReason?.code?.toInt(),
                        reasonPresent = !closeReason?.message.isNullOrEmpty(),
                    )
                }
            } catch (cancelled: CancellationException) {
                if (!explicitlyClosed.get()) {
                    withContext(NonCancellable) {
                        session.getAndSet(null)?.close(
                            CloseReason(CloseReason.Codes.NORMAL, "transport scope cancelled"),
                        )
                        mutableState.value = HuitaiWebSocketState.Closed(
                            code = CloseReason.Codes.NORMAL.code.toInt(),
                            reasonPresent = false,
                        )
                        mutableIncoming.close()
                    }
                    throw cancelled
                }
            } catch (error: Throwable) {
                mutableState.value = HuitaiWebSocketState.Error(error.failureKind())
            } finally {
                session.getAndSet(null)
                mutableIncoming.close()
            }
        }
    }
}

private fun Throwable.failureKind(): HuitaiWebSocketFailureKind {
    var current: Throwable? = this
    while (current != null) {
        if (current is ResponseException &&
            (current.response.status == HttpStatusCode.Unauthorized ||
                current.response.status == HttpStatusCode.Forbidden)
        ) {
            return HuitaiWebSocketFailureKind.AUTHENTICATION_REJECTED
        }
        current = current.cause
    }
    return HuitaiWebSocketFailureKind.TRANSIENT
}
