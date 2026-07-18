package com.wzx.huitai.agent.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlin.coroutines.coroutineContext

/** Supervisor 对 UI 暴露的脱敏连接状态。 */
sealed interface AgentSupervisorState {
    /** 尚未启动连接循环。 */
    data object Idle : AgentSupervisorState

    /** 正在执行一次新的连接尝试。 */
    data object Connecting : AgentSupervisorState

    /**
     * 当前连接已认证。
     *
     * @property connectionId 仅用于本地隔离迟到事件，不包含认证凭证。
     */
    data class Connected(val connectionId: String) : AgentSupervisorState

    /** 瞬时失败后的可观测等待状态。 */
    data class Reconnecting(
        val consecutiveFailures: Int,
        val delayMillis: Long,
    ) : AgentSupervisorState

    /** 连续十次瞬时失败，只有显式人工操作可以再发起连接。 */
    data object ManualRetryRequired : AgentSupervisorState

    /** 401/403 认证拒绝，禁止自动重试。 */
    data object AuthenticationFailed : AgentSupervisorState

    /** 桌面应用已经关闭连接管理器。 */
    data object Shutdown : AgentSupervisorState
}

/**
 * 管理 Agent WebSocket 的单连接重连循环和迟到事件隔离。
 *
 * Supervisor 创建自己的 child job，不取消调用方注入的 scope。每次安装连接都会递增
 * generation；状态和文本只有仍属于 active generation 时才能发布，因此旧连接迟到事件不能
 * 覆盖新连接。认证失败和 shutdown 是不可自动重试终态。
 *
 * @param transport 单次连接传输端口；每次 connect 都复用同一个 [request] 身份对象。
 * @param request 当前 Agent 子进程固定的 loopback 地址和桌面会话身份。
 * @param scope 仅作为父作用域，Supervisor 不会主动取消它。
 * @param reconnectPolicy 连续瞬时失败到等待时长的确定性映射。
 * @param delayMillis 等待端口；生产默认真实 delay，测试可注入记录器。
 * @param incomingCapacity 单个 transport generation 的有界缓冲容量；满时对当前连接施加背压而不丢帧。
 */
class AgentConnectionSupervisor(
    private val transport: AgentTransport,
    private val request: AgentConnectRequest,
    scope: CoroutineScope,
    private val reconnectPolicy: AgentReconnectPolicy = AgentReconnectPolicy(),
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val incomingCapacity: Int = 128,
) {
    private val lifecycleMutex = Mutex()
    private val outboundMutex = Mutex()
    private val supervisorJob = SupervisorJob(scope.coroutineContext[Job])
    private val supervisorScope = CoroutineScope(scope.coroutineContext + supervisorJob)
    private val mutableState = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Idle)
    private val mutableIncoming = Channel<String>(capacity = Channel.RENDEZVOUS)
    private var loopJob: Job? = null
    private var active: ActiveConnection? = null
    private var nextGeneration: Long = 0
    private var started: Boolean = false
    private var shutdown: Boolean = false

    init {
        require(incomingCapacity > 0) { "incomingCapacity must be positive" }
    }

    /** 当前连接管理状态，不包含 token、Origin 或远端异常正文。 */
    val state: StateFlow<AgentSupervisorState> = mutableState.asStateFlow()

    /** 当前 active connection 的文本帧；旧 generation 的帧在写入前被丢弃。 */
    val incoming: ReceiveChannel<String> = mutableIncoming

    /**
     * 只向当前 generation 的已认证连接发送文本。
     *
     * outbound mutex 保证应用帧顺序；生命周期锁只用于捕获当前 generation，外部发送在锁外执行，
     * 因此背压不能阻塞重连或关闭。捕获后发生切换时旧句柄会被关闭，帧至多发送失败而不会改投新连接。
     */
    suspend fun send(text: String) = outboundMutex.withLock {
        val connection = lifecycleMutex.withLock {
            val current = active ?: error("Agent WebSocket is not connected")
            val connected = mutableState.value as? AgentSupervisorState.Connected
                ?: error("Agent WebSocket is not connected")
            check(connected.connectionId == current.connection.connectionId) {
                "Agent WebSocket generation changed"
            }
            current.connection
        }
        // 外部 I/O 绝不能持有生命周期锁；shutdown/重连可并发关闭本句柄并使发送失败。
        connection.send(text)
    }

    /** 幂等启动首次连接循环；连接行为在 Supervisor 自己的 child job 中运行。 */
    suspend fun start() {
        val shouldLaunch = lifecycleMutex.withLock {
            if (started || shutdown) return@withLock false
            started = true
            mutableState.value = AgentSupervisorState.Connecting
            true
        }
        if (shouldLaunch) launchLoop()
    }

    /**
     * 只有处于 [AgentSupervisorState.ManualRetryRequired] 时才立即启动一次新循环。
     *
     * @return 是否接受了本次人工重试请求。
     */
    suspend fun manualRetry(): Boolean {
        val accepted = lifecycleMutex.withLock {
            if (shutdown || mutableState.value != AgentSupervisorState.ManualRetryRequired) {
                return@withLock false
            }
            mutableState.value = AgentSupervisorState.Connecting
            true
        }
        if (accepted) launchLoop()
        return accepted
    }

    /** Invalidates only the active transport generation so the existing supervisor loop reconnects. */
    suspend fun requestReconnect(expectedConnectionId: String? = null): Boolean {
        val current = lifecycleMutex.withLock {
            if (shutdown) null else active?.takeIf {
                expectedConnectionId == null || it.connection.connectionId == expectedConnectionId
            }
        } ?: return false
        if (!current.reconnectRequested.complete(Unit)) return false
        runCatching { current.connection.close() }
        return true
    }

    /**
     * 幂等停止自身 jobs，关闭 active connection 与 transport，但不取消注入的父 scope。
     */
    suspend fun shutdown() {
        val resources = lifecycleMutex.withLock {
            if (shutdown) return
            shutdown = true
            nextGeneration += 1
            mutableState.value = AgentSupervisorState.Shutdown
            ShutdownResources(
                loopJob = loopJob,
                connection = active?.connection,
            ).also {
                loopJob = null
                active = null
            }
        }

        var first: Throwable? = null
        suspend fun close(block: suspend () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                first?.addSuppressed(failure) ?: run { first = failure }
            }
        }
        close { resources.loopJob?.cancelAndJoin() }
        close { resources.connection?.close() }
        close { transport.close() }
        close { mutableIncoming.close() }
        close { supervisorJob.cancel() }
        first?.let { throw it }
    }

    /** 锁外立即启动 loop，随后在 lifecycleMutex 内登记 job；人工重试从零失败计数开始。 */
    private suspend fun launchLoop() {
        val launchedJob = supervisorScope.launch(start = CoroutineStart.UNDISPATCHED) { runConnectionLoop() }
        lifecycleMutex.withLock {
            if (shutdown) {
                launchedJob.cancel()
            } else {
                loopJob = launchedJob
            }
        }
    }

    /**
     * 执行连接、观察终态并应用退避。只捕获普通 Exception 作为瞬时失败；取消和 JVM Error
     * 均保持原始传播语义。
     */
    private suspend fun runConnectionLoop() {
        var consecutiveFailures = 0
        while (coroutineContext[Job]?.isActive == true) {
            if (isShutdown()) return
            publishIfRunning(AgentSupervisorState.Connecting)

            val connection = try {
                transport.connect(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val delay = recordFailureAndDelay(++consecutiveFailures) ?: return
                delayMillis(delay)
                continue
            }

            val installed = installConnection(connection) ?: return
            val observed = try {
                observeConnection(installed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ObservedConnection(transientFailure = true, connected = false)
            } finally {
                clearIfCurrent(installed.generation)
            }

            if (observed.authenticationFailed) {
                publishIfGenerationOrRunning(installed.generation, AgentSupervisorState.AuthenticationFailed)
                return
            }
            if (observed.connected) consecutiveFailures = 0
            if (observed.transientFailure) consecutiveFailures += 1
            val retryDelay = recordFailureAndDelay(consecutiveFailures) ?: return
            delayMillis(retryDelay)
        }
    }

    /** 同时转发文本并等待连接终态；reader 在离开该 generation 时总会取消。 */
    private suspend fun observeConnection(
        installed: ActiveConnection,
    ): ObservedConnection = coroutineScope {
        val connection = installed.connection
        val generation = installed.generation
        val generationQueue = Channel<String>(capacity = incomingCapacity)
        val incomingJob = launch(start = CoroutineStart.UNDISPATCHED) {
            for (text in connection.incoming) {
                if (!isCurrent(generation, connection)) break
                generationQueue.send(text)
            }
        }
        val deliveryJob = launch(start = CoroutineStart.UNDISPATCHED) {
            for (text in generationQueue) {
                mutableIncoming.send(text)
            }
        }
        val terminalJob = async(start = CoroutineStart.UNDISPATCHED) {
            connection.state
                .onEach { observedState ->
                    if (observedState == AgentConnectionState.Connected &&
                        publishIfCurrent(generation, connection, AgentSupervisorState.Connected(connection.connectionId))
                    ) Unit
                }
                .first { observedState ->
                    observedState == AgentConnectionState.AuthenticationFailed ||
                        observedState is AgentConnectionState.TransportFailure ||
                        observedState is AgentConnectionState.Closed
                }
        }
        try {
            val terminal = select<AgentConnectionState> {
                terminalJob.onAwait { it }
                installed.reconnectRequested.onAwait { AgentConnectionState.TransportFailure() }
            }
            ObservedConnection(
                authenticationFailed = terminal == AgentConnectionState.AuthenticationFailed,
                transientFailure = terminal is AgentConnectionState.TransportFailure ||
                    terminal is AgentConnectionState.Closed,
                connected = connection.hasConnected,
            )
        } finally {
            incomingJob.cancelAndJoin()
            generationQueue.close()
            deliveryJob.cancelAndJoin()
            terminalJob.cancelAndJoin()
        }
    }

    /** 安装连接并分配新 generation；shutdown 与安装竞争时拒绝该连接并关闭它。 */
    private suspend fun installConnection(connection: AgentConnection): ActiveConnection? {
        val installed = lifecycleMutex.withLock {
            if (shutdown) return@withLock null
            nextGeneration += 1
            ActiveConnection(nextGeneration, connection).also { active = it }
        }
        if (installed == null) connection.close()
        return installed
    }

    /** 根据失败计数发布 retry/manual 状态，返回 null 时连接循环必须停止。 */
    private suspend fun recordFailureAndDelay(consecutiveFailures: Int): Long? {
        val retryDelay = reconnectPolicy.retryDelayMillis(consecutiveFailures)
        if (retryDelay == null) {
            publishIfRunning(AgentSupervisorState.ManualRetryRequired)
            return null
        }
        publishIfRunning(AgentSupervisorState.Reconnecting(consecutiveFailures, retryDelay))
        return retryDelay
    }

    /** 仅在尚未 shutdown 时发布非连接特定状态。 */
    private suspend fun publishIfRunning(newState: AgentSupervisorState) {
        lifecycleMutex.withLock {
            if (!shutdown) mutableState.value = newState
        }
    }

    /** 仅 active generation 可以发布连接特定状态。 */
    private suspend fun publishIfCurrent(
        generation: Long,
        connection: AgentConnection,
        newState: AgentSupervisorState,
    ): Boolean = lifecycleMutex.withLock {
        val current = active
        if (shutdown || current?.generation != generation || current.connection !== connection) return false
        mutableState.value = newState
        true
    }

    /** 认证终态允许在 active 清理前后发布，但 shutdown 始终拥有最高优先级。 */
    private suspend fun publishIfGenerationOrRunning(
        generation: Long,
        newState: AgentSupervisorState,
    ) {
        lifecycleMutex.withLock {
            if (!shutdown && (active == null || active?.generation == generation)) mutableState.value = newState
        }
    }

    /** 在读取 transport 帧后再次核对 generation，避免旧 reader 向其 generation relay 继续入队。 */
    private suspend fun isCurrent(
        generation: Long,
        connection: AgentConnection,
    ): Boolean = lifecycleMutex.withLock {
        val current = active
        !shutdown && current?.generation == generation && current.connection === connection
    }

    /** 连接观察结束后只清理仍属于自己的 active 引用。 */
    private suspend fun clearIfCurrent(generation: Long) {
        lifecycleMutex.withLock {
            if (active?.generation == generation) active = null
        }
    }

    /** 在循环关键点读取 shutdown 标志，避免 shutdown 后启动下一次 connect。 */
    private suspend fun isShutdown(): Boolean = lifecycleMutex.withLock { shutdown }

    /** active connection 与 generation 的原子快照。 */
    private data class ActiveConnection(
        val generation: Long,
        val connection: AgentConnection,
        val reconnectRequested: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    /** 一次连接观察的终态分类，connected 用于重置连续失败计数。 */
    private data class ObservedConnection(
        val authenticationFailed: Boolean = false,
        val transientFailure: Boolean,
        val connected: Boolean,
    )

    /** shutdown 在锁内摘取、锁外关闭的资源集合。 */
    private data class ShutdownResources(
        val loopJob: Job?,
        val connection: AgentConnection?,
    )

}
