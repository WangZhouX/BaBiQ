package com.wzx.huitai.agent.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
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
 * @param incomingCapacity Supervisor 对 UI/协议层暴露的文本缓冲容量，满时丢弃最旧帧。
 */
class AgentConnectionSupervisor(
    private val transport: AgentTransport,
    private val request: AgentConnectRequest,
    scope: CoroutineScope,
    private val reconnectPolicy: AgentReconnectPolicy = AgentReconnectPolicy(),
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    incomingCapacity: Int = 128,
) {
    private val lifecycleMutex = Mutex()
    private val supervisorJob = SupervisorJob(scope.coroutineContext[Job])
    private val supervisorScope = CoroutineScope(scope.coroutineContext + supervisorJob)
    private val mutableState = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Idle)
    private val mutableIncoming = Channel<String>(
        capacity = incomingCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
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

        resources.loopJob?.cancelAndJoin()
        resources.connection?.close()
        transport.close()
        mutableIncoming.close()
        supervisorJob.cancel()
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

            val generation = installConnection(connection) ?: return
            val observed = try {
                observeConnection(connection, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ObservedConnection(transientFailure = true, connected = false)
            } finally {
                clearIfCurrent(generation)
            }

            if (observed.authenticationFailed) {
                publishIfGenerationOrRunning(generation, AgentSupervisorState.AuthenticationFailed)
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
        connection: AgentConnection,
        generation: Long,
    ): ObservedConnection = coroutineScope {
        val incomingJob = launch(start = CoroutineStart.UNDISPATCHED) {
            for (text in connection.incoming) {
                forwardIfCurrent(generation, connection, text)
            }
        }
        try {
            val terminal = connection.state
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
            ObservedConnection(
                authenticationFailed = terminal == AgentConnectionState.AuthenticationFailed,
                transientFailure = terminal is AgentConnectionState.TransportFailure ||
                    terminal is AgentConnectionState.Closed,
                connected = connection.hasConnected,
            )
        } finally {
            incomingJob.cancelAndJoin()
        }
    }

    /** 安装连接并分配新 generation；shutdown 与安装竞争时拒绝该连接并关闭它。 */
    private suspend fun installConnection(connection: AgentConnection): Long? {
        val generation = lifecycleMutex.withLock {
            if (shutdown) return@withLock null
            nextGeneration += 1
            nextGeneration.also {
                active = ActiveConnection(it, connection)
                clearQueuedIncomingLocked()
            }
        }
        if (generation == null) connection.close()
        return generation
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

    /** generation 核对和有界入队在同一锁内完成，避免重连清队列后旧 reader 再写入。 */
    private suspend fun forwardIfCurrent(
        generation: Long,
        connection: AgentConnection,
        text: String,
    ) = lifecycleMutex.withLock {
        val current = active
        if (!shutdown && current?.generation == generation && current.connection === connection) {
            mutableIncoming.trySend(text)
        }
    }

    /** 新连接安装时丢弃仍排队的旧 generation 文本。 */
    private fun clearQueuedIncomingLocked() {
        while (mutableIncoming.tryReceive().isSuccess) Unit
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
