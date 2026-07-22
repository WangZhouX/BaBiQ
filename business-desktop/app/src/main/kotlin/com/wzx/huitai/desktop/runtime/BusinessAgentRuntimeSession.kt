package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentConnectionSupervisor
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.client.AgentTransport
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/** composition root 用于显式人工重试的 supervisor-backed 连接扩展。 */
interface ManagedBusinessAgentConnection : AgentConnection {
    /** 保留 supervisor 的真实退避次数、等待时长、manual/auth/shutdown 状态。 */
    val supervisorState: StateFlow<AgentSupervisorState>
    suspend fun manualRetry(): Boolean
    suspend fun reconnect(expectedConnectionId: String): Boolean
}

/**
 * 一个内置 Agent child 的稳定桌面会话边界。
 *
 * WebSocket 重连始终复用 [identity] 和 [sequenceTracker]；只有 launcher 创建新的本对象时才产生
 * 新 session/token/counters。token 文件消费后，握手 token 仍只存在本对象引用的内存身份中。
 */
class BusinessAgentRuntimeSession internal constructor(
    private val process: Process,
    private val request: BusinessAgentLaunchRequest,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val connectionSession = BusinessAgentConnectionSession(request.connectRequest)
    val identity: DesktopSessionIdentity = connectionSession.identity
    val connectRequest = connectionSession.connectRequest
    val sequenceTracker = connectionSession.sequenceTracker

    /** 同一 child 的所有 reconnect 都取回同一个身份对象。 */
    fun identityForReconnect(): DesktopSessionIdentity = identity

    /** 同一 child 的所有 reconnect 都复用同一组会话/连接计数器。 */
    fun sequenceTrackerForReconnect(): ApplicationSequenceTracker = sequenceTracker

    val isAlive: Boolean
        get() = process.isAlive

    val childPid: Long
        get() = process.pid()

    val address: String
        get() = LOOPBACK_ADDRESS

    val port: Int
        get() = request.port

    /**
     * 为该 child 创建唯一重连 supervisor，并返回供 JSON-RPC 长期持有的稳定 connection facade。
     * facade 的 incoming 和 send 都经过 supervisor generation 过滤，因此 Task31 的旧连接事件不会
     * 进入 Task33 决策断线链路或本轮协议 reader。
     */
    suspend fun connect(
        transport: AgentTransport,
        scope: CoroutineScope,
        timeoutMillis: Long = 30_000L,
    ): AgentConnection = connectionSession.connect(transport, scope, timeoutMillis)

    fun awaitExit(): Int = process.waitFor()

    val exitCodeIfFinished: Int?
        get() = if (process.isAlive) null else process.exitValue()

    /** 先请求优雅退出，最多五秒；仍存活时强制终止并等待，最后清理内存密码和残留 token。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            terminateProcess(process)
        } finally {
            request.close()
        }
    }

    override fun toString(): String =
        "BusinessAgentRuntimeSession(process=[REDACTED], identity=[REDACTED], token=[REDACTED])"

    companion object {
        internal fun terminateProcess(process: Process) {
            if (!process.isAlive) return
            process.destroy()
            if (process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) return
            process.destroyForcibly()
            check(process.waitFor(FORCED_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                "business Agent did not terminate after forced shutdown"
            }
        }

        private const val GRACEFUL_SHUTDOWN_SECONDS = 5L
        private const val FORCED_SHUTDOWN_SECONDS = 5L
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
    }
}

/**
 * An authenticated desktop-to-Agent session that is independent from ownership of the backend
 * process. Embedded production mode and split development mode share the same reconnect and
 * sequence semantics without allowing the frontend process to launch a backend implicitly.
 */
class BusinessAgentConnectionSession(
    val connectRequest: com.wzx.huitai.agent.client.AgentConnectRequest,
) {
    private val connectionAttached = AtomicBoolean(false)

    val identity: DesktopSessionIdentity = connectRequest.identity
    val sequenceTracker = ApplicationSequenceTracker(identity.desktopSessionId)

    suspend fun connect(
        transport: AgentTransport,
        scope: CoroutineScope,
        timeoutMillis: Long = 30_000L,
    ): AgentConnection {
        check(connectionAttached.compareAndSet(false, true)) {
            "business Agent connection is already attached"
        }
        val supervisor = AgentConnectionSupervisor(transport, connectRequest, scope)
        try {
            supervisor.start()
            val terminal = withTimeout(timeoutMillis) {
                supervisor.state.first { state ->
                    state is AgentSupervisorState.Connected ||
                        state == AgentSupervisorState.AuthenticationFailed ||
                        state == AgentSupervisorState.ManualRetryRequired ||
                        state == AgentSupervisorState.Shutdown
                }
            }
            check(terminal is AgentSupervisorState.Connected) {
                "business Agent connection was not authenticated"
            }
            return SupervisorAgentConnection(supervisor, scope, terminal.connectionId)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { supervisor.shutdown() }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            connectionAttached.set(false)
            throw failure
        }
    }

    override fun toString(): String =
        "BusinessAgentConnectionSession(connectRequest=[REDACTED], identity=[REDACTED])"
}

/** AgentJsonRpcClient 可跨 reconnect 持有的稳定 facade，只消费 supervisor 已过滤的状态和帧。 */
private class SupervisorAgentConnection(
    private val supervisor: AgentConnectionSupervisor,
    scope: CoroutineScope,
    initialConnectionId: String,
) : ManagedBusinessAgentConnection {
    private val lastConnectionId = AtomicReference(initialConnectionId)
    private val connectedOnce = AtomicBoolean(true)
    private val stateJob = SupervisorJob(scope.coroutineContext[Job])
    private val stateScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + stateJob)

    override val connectionId: String
        get() = (supervisor.state.value as? AgentSupervisorState.Connected)?.connectionId?.also(lastConnectionId::set)
            ?: lastConnectionId.get()

    override val incoming: ReceiveChannel<String> = supervisor.incoming

    override val supervisorState: StateFlow<AgentSupervisorState> = supervisor.state

    override val state: StateFlow<AgentConnectionState> = supervisor.state.map { state ->
        when (state) {
            AgentSupervisorState.Idle,
            AgentSupervisorState.Connecting,
            is AgentSupervisorState.Reconnecting,
            -> AgentConnectionState.Connecting
            is AgentSupervisorState.Connected -> {
                lastConnectionId.set(state.connectionId)
                connectedOnce.set(true)
                AgentConnectionState.Connected
            }
            AgentSupervisorState.AuthenticationFailed -> AgentConnectionState.AuthenticationFailed
            AgentSupervisorState.ManualRetryRequired -> AgentConnectionState.TransportFailure()
            AgentSupervisorState.Shutdown -> AgentConnectionState.Closed(code = 1000, reasonPresent = false)
        }
    }.stateIn(stateScope, SharingStarted.Eagerly, AgentConnectionState.Connecting)

    override val hasConnected: Boolean
        get() = connectedOnce.get()

    override suspend fun send(text: String) = supervisor.send(text)

    override suspend fun manualRetry(): Boolean = supervisor.manualRetry()

    override suspend fun reconnect(expectedConnectionId: String): Boolean =
        supervisor.requestReconnect(expectedConnectionId)

    override suspend fun close() {
        try {
            supervisor.shutdown()
        } finally {
            stateScope.cancel()
        }
    }
}
