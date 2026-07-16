package com.wzx.huitai.integration.websocket

import com.wzx.huitai.integration.auth.AuthSessionManager
import com.wzx.huitai.integration.auth.AuthenticatedRequestIdentity
import com.wzx.huitai.integration.auth.AuthenticationState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 将认证身份生命周期映射为一条通用业务 WebSocket 连接。
 *
 * 本类只公开连接状态和原始文本事件，不包含任何具体 OA 订阅或事件名称。
 */
class HuitaiWebSocketClient(
    private val authSessionManager: AuthSessionManager,
    private val transport: HuitaiWebSocketTransport,
    private val webSocketUrl: () -> String,
    private val scope: CoroutineScope,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    incomingCapacity: Int = 128,
) {
    init {
        require(incomingCapacity > 0) { "incomingCapacity must be positive" }
    }

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow<HuitaiWebSocketClientState>(HuitaiWebSocketClientState.SignedOut)
    private val mutableEvents = Channel<HuitaiWebSocketEvent>(
        capacity = incomingCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val controllerJobs = mutableListOf<Job>()
    private var generation: Long = 0
    private var activeIdentity: AuthenticatedRequestIdentity? = null
    private var activeConnection: HuitaiWebSocketConnection? = null
    private var activeLoop: Job? = null

    val state: StateFlow<HuitaiWebSocketClientState> = mutableState.asStateFlow()
    val events: ReceiveChannel<HuitaiWebSocketEvent> = mutableEvents

    /** 启动身份监听，并以权威认证快照补偿 SharedFlow 无 replay 的启动窗口。 */
    fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) return
        controllerJobs += scope.launch {
            authSessionManager.identityTransitions.collect { transition ->
                if (transition.toState == AuthenticationState.AUTHENTICATED) {
                    connectCurrentIdentityUnlessManual()
                }
            }
        }
        controllerJobs += scope.launch {
            authSessionManager.state.collect { authState ->
                when (authState) {
                    AuthenticationState.SIGNED_OUT -> enterTerminal(HuitaiWebSocketClientState.SignedOut)
                    AuthenticationState.EXPIRED -> enterTerminal(HuitaiWebSocketClientState.AuthenticationExpired)
                    AuthenticationState.MEMBERSHIP_EXPIRED ->
                        enterTerminal(HuitaiWebSocketClientState.MembershipExpired)
                    else -> Unit
                }
            }
        }
        controllerJobs += scope.launch { reconcileAuthoritativeState() }
    }

    /** 只允许从十次连续失败终态显式恢复，并重新读取当前原子身份快照。 */
    fun manualRetry() {
        if (!started.get() || closed.get()) return
        scope.launch {
            val identity = authSessionManager.requestIdentitySnapshot() ?: return@launch
            lifecycleMutex.withLock {
                if (mutableState.value !is HuitaiWebSocketClientState.ManualRetryRequired) return@withLock
                replaceLoopLocked(identity, clearEvents = true)
            }
        }
    }

    /** 幂等关闭；调用方注入的 scope 归调用方所有，不在此处整体取消。 */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        controllerJobs.forEach(Job::cancel)
        controllerJobs.clear()
        lifecycleMutex.withLock {
            stopActiveLocked(clearEvents = true)
            mutableState.value = HuitaiWebSocketClientState.SignedOut
        }
        mutableEvents.close()
    }

    private suspend fun reconcileAuthoritativeState() {
        when (authSessionManager.state.value) {
            AuthenticationState.AUTHENTICATED -> connectCurrentIdentityUnlessManual()
            AuthenticationState.EXPIRED -> enterTerminal(HuitaiWebSocketClientState.AuthenticationExpired)
            AuthenticationState.MEMBERSHIP_EXPIRED ->
                enterTerminal(HuitaiWebSocketClientState.MembershipExpired)
            else -> if (authSessionManager.requestIdentitySnapshot() == null) {
                enterTerminal(HuitaiWebSocketClientState.SignedOut)
            }
        }
    }

    private suspend fun connectCurrentIdentityUnlessManual() {
        val identity = authSessionManager.requestIdentitySnapshot() ?: return
        lifecycleMutex.withLock {
            if (closed.get() || mutableState.value is HuitaiWebSocketClientState.ManualRetryRequired) {
                return@withLock
            }
            val loopIsUsable = activeLoop?.isActive == true
            if (activeIdentity == identity && loopIsUsable) return@withLock
            replaceLoopLocked(identity, clearEvents = true)
        }
    }

    private suspend fun enterTerminal(terminalState: HuitaiWebSocketClientState) {
        lifecycleMutex.withLock {
            if (closed.get()) return@withLock
            stopActiveLocked(clearEvents = true)
            mutableState.value = terminalState
        }
    }

    private suspend fun replaceLoopLocked(
        identity: AuthenticatedRequestIdentity,
        clearEvents: Boolean,
    ) {
        stopActiveLocked(clearEvents)
        generation += 1
        val loopGeneration = generation
        activeIdentity = identity
        mutableState.value = HuitaiWebSocketClientState.Connecting(consecutiveFailures = 0)
        activeLoop = scope.launch { runConnectionLoop(loopGeneration, identity) }
    }

    private suspend fun stopActiveLocked(clearEvents: Boolean) {
        generation += 1
        activeLoop?.cancel()
        activeLoop = null
        val connection = activeConnection
        activeConnection = null
        activeIdentity = null
        connection?.closeSafely()
        if (clearEvents) drainEvents()
    }

    private suspend fun runConnectionLoop(
        loopGeneration: Long,
        identity: AuthenticatedRequestIdentity,
    ) {
        var consecutiveFailures = 0
        while (isCurrent(loopGeneration, identity)) {
            publishIfCurrent(
                loopGeneration,
                HuitaiWebSocketClientState.Connecting(consecutiveFailures),
            )
            var connection: HuitaiWebSocketConnection? = null
            var installed = false
            try {
                connection = transport.connect(
                    HuitaiWebSocketConnectRequest(
                        url = webSocketUrl(),
                        accessToken = identity.accessToken,
                        tenantId = identity.tenantId,
                    ),
                )
                installed = installConnection(loopGeneration, identity, connection)
                if (!installed) return

                when (val observed = connection.state.awaitHandshakeOutcome()) {
                    HuitaiWebSocketState.Connected -> {
                        consecutiveFailures = 0
                        publishIfCurrent(loopGeneration, HuitaiWebSocketClientState.Connected)
                        val terminal = collectEventsUntilTerminal(loopGeneration, connection)
                        if (!isCurrent(loopGeneration, identity)) return
                        if (terminal is HuitaiWebSocketState.Error) {
                            publishSanitizedIfCurrent(loopGeneration, connection, terminal.kind)
                        }
                        retireConnection(loopGeneration, connection)
                        if (terminal is HuitaiWebSocketState.Error &&
                            terminal.kind == HuitaiWebSocketFailureKind.AUTHENTICATION_REJECTED
                        ) {
                            publishIfCurrent(loopGeneration, HuitaiWebSocketClientState.AuthenticationRejected)
                            return
                        }
                    }
                    is HuitaiWebSocketState.Error -> {
                        publishSanitizedIfCurrent(loopGeneration, connection, observed.kind)
                        retireConnection(loopGeneration, connection)
                        if (observed.kind == HuitaiWebSocketFailureKind.AUTHENTICATION_REJECTED) {
                            publishIfCurrent(loopGeneration, HuitaiWebSocketClientState.AuthenticationRejected)
                            return
                        }
                    }
                    is HuitaiWebSocketState.Closed -> retireConnection(loopGeneration, connection)
                    HuitaiWebSocketState.Connecting -> error("unreachable handshake state")
                }
            } catch (cancelled: CancellationException) {
                if (!installed) connection?.closeSafely()
                throw cancelled
            } catch (_: Throwable) {
                if (installed && connection != null) {
                    retireConnection(loopGeneration, connection)
                } else {
                    connection?.closeSafely()
                }
            } finally {
                if (connection != null) {
                    withContext(NonCancellable) {
                        retireConnection(loopGeneration, connection)
                    }
                }
            }

            if (!isCurrent(loopGeneration, identity)) return
            consecutiveFailures += 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                publishIfCurrent(
                    loopGeneration,
                    HuitaiWebSocketClientState.ManualRetryRequired(consecutiveFailures),
                )
                return
            }
            publishIfCurrent(
                loopGeneration,
                HuitaiWebSocketClientState.Retrying(
                    consecutiveFailures = consecutiveFailures,
                    delayMillis = retryDelayMillis(consecutiveFailures),
                ),
            )
            retryDelay(retryDelayMillis(consecutiveFailures))
        }
    }

    private suspend fun StateFlow<HuitaiWebSocketState>.awaitHandshakeOutcome(): HuitaiWebSocketState {
        val current = value
        return if (current == HuitaiWebSocketState.Connecting) {
            first { it != HuitaiWebSocketState.Connecting }
        } else {
            current
        }
    }

    private suspend fun collectEventsUntilTerminal(
        loopGeneration: Long,
        connection: HuitaiWebSocketConnection,
    ): HuitaiWebSocketState = coroutineScope {
        val reader = launch {
            for (text in connection.incoming) {
                lifecycleMutex.withLock {
                    if (generation == loopGeneration && activeConnection === connection) {
                        mutableEvents.trySend(HuitaiWebSocketEvent.Raw(text))
                    }
                }
            }
        }
        try {
            connection.state.first {
                it is HuitaiWebSocketState.Closed || it is HuitaiWebSocketState.Error
            }
        } finally {
            reader.cancel()
        }
    }

    private suspend fun installConnection(
        loopGeneration: Long,
        identity: AuthenticatedRequestIdentity,
        connection: HuitaiWebSocketConnection,
    ): Boolean = lifecycleMutex.withLock {
        if (closed.get() || generation != loopGeneration || activeIdentity != identity) {
            connection.closeSafely()
            false
        } else {
            activeConnection = connection
            true
        }
    }

    private suspend fun retireConnection(
        loopGeneration: Long,
        connection: HuitaiWebSocketConnection,
    ) = lifecycleMutex.withLock {
        if (generation == loopGeneration && activeConnection === connection) {
            activeConnection = null
            connection.closeSafely()
        }
    }

    private suspend fun publishSanitizedIfCurrent(
        loopGeneration: Long,
        connection: HuitaiWebSocketConnection,
        kind: HuitaiWebSocketFailureKind,
    ) = lifecycleMutex.withLock {
        if (!closed.get() && generation == loopGeneration && activeConnection === connection) {
            mutableEvents.trySend(HuitaiWebSocketEvent.Sanitized(kind))
        }
    }

    private suspend fun publishIfCurrent(
        loopGeneration: Long,
        nextState: HuitaiWebSocketClientState,
    ) = lifecycleMutex.withLock {
        if (!closed.get() && generation == loopGeneration) mutableState.value = nextState
    }

    private suspend fun isCurrent(
        loopGeneration: Long,
        identity: AuthenticatedRequestIdentity,
    ): Boolean = lifecycleMutex.withLock {
        !closed.get() && generation == loopGeneration && activeIdentity == identity
    }

    private fun drainEvents() {
        while (mutableEvents.tryReceive().isSuccess) Unit
    }

    private suspend fun HuitaiWebSocketConnection.closeSafely() {
        try {
            close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cleanup failure must not expose remote diagnostics or cross an identity boundary.
        }
    }

    private fun retryDelayMillis(consecutiveFailures: Int): Long =
        when (consecutiveFailures) {
            1 -> 1_000L
            2 -> 2_000L
            3 -> 4_000L
            4 -> 8_000L
            else -> 10_000L
        }

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 10
    }
}

/** UI 可观察但不携带 token、tenant 或远端错误正文的连接状态。 */
sealed interface HuitaiWebSocketClientState {
    data object SignedOut : HuitaiWebSocketClientState

    class Connecting(val consecutiveFailures: Int) : HuitaiWebSocketClientState {
        override fun toString(): String = "Connecting(consecutiveFailures=$consecutiveFailures)"
    }

    data object Connected : HuitaiWebSocketClientState

    class Retrying(
        val consecutiveFailures: Int,
        val delayMillis: Long,
    ) : HuitaiWebSocketClientState

    class ManualRetryRequired(val consecutiveFailures: Int) : HuitaiWebSocketClientState

    data object AuthenticationRejected : HuitaiWebSocketClientState

    data object AuthenticationExpired : HuitaiWebSocketClientState

    data object MembershipExpired : HuitaiWebSocketClientState
}

/** 公开事件保留原始文本供上层解析，但任何普通字符串渲染都必须脱敏。 */
sealed interface HuitaiWebSocketEvent {
    class Raw(val text: String) : HuitaiWebSocketEvent {
        override fun toString(): String = "Raw(text=[REDACTED:${text.length} chars])"
    }

    class Sanitized(val kind: HuitaiWebSocketFailureKind) : HuitaiWebSocketEvent
}
