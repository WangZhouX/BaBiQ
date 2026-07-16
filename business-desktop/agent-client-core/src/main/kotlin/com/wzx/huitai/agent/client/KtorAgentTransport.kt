package com.wzx.huitai.agent.client

import com.wzx.huitai.agent.protocol.ApplicationProtocolValidator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.UUID
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ktor 3.5 Agent WebSocket 传输实现，只负责安全握手、文本帧和连接资源边界。
 *
 * 非 101 握手通过 `prepareRequest.execute` 读取结构化 HTTP 状态，避免从异常字符串推断
 * 401/403。transport 本身只执行一次连接，不在认证失败或瞬时失败后偷偷重试。
 *
 * @param httpClient 已安装 Ktor WebSockets 插件的客户端，生命周期由调用方管理。
 * @param scope reader 协程作用域；作用域取消会主动关闭已建立的 session。
 * @param incomingCapacity 文本输入通道容量，满时丢弃最旧帧保护网络 reader。
 * @param connectionIdFactory 每次连接尝试生成唯一 ID 的工厂，测试可注入确定值。
 */
class KtorAgentTransport(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val incomingCapacity: Int = 128,
    private val connectionIdFactory: () -> String = { UUID.randomUUID().toString() },
) : AgentTransport {
    private val lifecycleMutex = Mutex()
    private val activeConnection = AtomicReference<KtorConnection?>(null)
    private val closed = AtomicBoolean(false)

    init {
        require(incomingCapacity > 0) { "incomingCapacity must be positive" }
    }

    /** 在开始新握手前关闭旧 reader，确保同一 transport 只有一个活跃连接。 */
    override suspend fun connect(request: AgentConnectRequest): AgentConnection = lifecycleMutex.withLock {
        check(!closed.get()) { "Agent transport is closed" }
        activeConnection.getAndSet(null)?.close()
        KtorConnection(
            connectionId = connectionIdFactory().also { require(it.isNotBlank()) { "connectionId must not be blank" } },
            request = request,
        ).also(activeConnection::set)
    }

    /** 幂等关闭当前连接，但不越权关闭由上层注入的共享 HttpClient 或 scope。 */
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycleMutex.withLock {
            activeConnection.getAndSet(null)?.close()
        }
    }

    /** 单次连接对象拥有自己的 session、reader 和有界输入通道。 */
    private inner class KtorConnection(
        override val connectionId: String,
        private val request: AgentConnectRequest,
    ) : AgentConnection {
        private val mutableIncoming = Channel<String>(
            capacity = incomingCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        private val mutableState = MutableStateFlow<AgentConnectionState>(AgentConnectionState.Connecting)
        private val session = AtomicReference<DefaultClientWebSocketSession?>(null)
        private val explicitlyClosed = AtomicBoolean(false)
        private val connectedOnce = AtomicBoolean(false)
        private val readerJob: Job = scope.launch { runConnection() }

        override val incoming: ReceiveChannel<String> = mutableIncoming
        override val state: StateFlow<AgentConnectionState> = mutableState.asStateFlow()
        override val hasConnected: Boolean
            get() = connectedOnce.get()

        /** 只允许在已认证 session 上发送文本帧，错误信息不携带 payload 或身份。 */
        override suspend fun send(text: String) {
            ApplicationProtocolValidator.validateEnvelopeSize(text.toByteArray(Charsets.UTF_8))
            val activeSession = session.get() ?: error("Agent WebSocket is not connected")
            activeSession.send(Frame.Text(text))
        }

        /** 关闭 session 后取消 reader，并统一发布不含本地关闭诊断的终态。 */
        override suspend fun close() {
            if (!explicitlyClosed.compareAndSet(false, true)) return
            session.getAndSet(null)?.close(CloseReason(CloseReason.Codes.NORMAL, "client close"))
            readerJob.cancelAndJoin()
            mutableState.value = AgentConnectionState.Closed(
                code = CloseReason.Codes.NORMAL.code.toInt(),
                reasonPresent = false,
            )
            mutableIncoming.close()
        }

        /** 执行一次结构化握手并持续读取文本帧；取消与 JVM Error 均保持原有传播语义。 */
        private suspend fun runConnection() {
            try {
                httpClient.prepareRequest(request.url) {
                    header(HttpHeaders.Authorization, "Bearer ${request.identity.desktopSessionToken}")
                    header("X-Desktop-Instance-Id", request.identity.desktopInstanceId)
                    header("X-Desktop-Session-Id", request.identity.desktopSessionId)
                    header(HttpHeaders.Origin, request.identity.localOrigin)
                }.execute { response ->
                    if (response.status == HttpStatusCode.Unauthorized ||
                        response.status == HttpStatusCode.Forbidden
                    ) {
                        mutableState.value = AgentConnectionState.AuthenticationFailed
                        return@execute
                    }

                    val connected = response.body<DefaultClientWebSocketSession>()
                    if (explicitlyClosed.get()) {
                        connected.close(CloseReason(CloseReason.Codes.NORMAL, "client close"))
                        return@execute
                    }
                    session.set(connected)
                    connectedOnce.set(true)
                    mutableState.value = AgentConnectionState.Connected
                    for (frame in connected.incoming) {
                        if (frame is Frame.Text) mutableIncoming.trySend(frame.readText())
                    }
                    val closeReason = connected.closeReason.await()
                    mutableState.value = AgentConnectionState.Closed(
                        code = closeReason?.code?.toInt(),
                        reasonPresent = !closeReason?.message.isNullOrEmpty(),
                    )
                }
            } catch (cancelled: CancellationException) {
                if (!explicitlyClosed.get()) closeAfterScopeCancellation()
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = AgentConnectionState.TransportFailure()
            } finally {
                session.getAndSet(null)
                mutableIncoming.close()
            }
        }

        /** NonCancellable 收尾保证注入 scope 取消后仍能关闭底层 session 并公开收束状态。 */
        private suspend fun closeAfterScopeCancellation() = withContext(NonCancellable) {
            session.getAndSet(null)?.close(
                CloseReason(CloseReason.Codes.NORMAL, "transport scope cancelled"),
            )
            mutableState.value = AgentConnectionState.Closed(
                code = CloseReason.Codes.NORMAL.code.toInt(),
                reasonPresent = false,
            )
            mutableIncoming.close()
        }
    }
}
