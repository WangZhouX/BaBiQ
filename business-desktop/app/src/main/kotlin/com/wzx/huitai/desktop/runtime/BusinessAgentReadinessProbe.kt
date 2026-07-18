package com.wzx.huitai.desktop.runtime

import com.wzx.huitai.agent.client.AgentConnectRequest
import kotlinx.coroutines.delay

/** 生产实现必须执行带 Bearer 和桌面身份头的真实 WebSocket 握手，而不是探测 TCP 端口。 */
fun interface AuthenticatedWebSocketProbe {
    suspend fun authenticate(request: AgentConnectRequest): Boolean
}

/**
 * 同时验证子进程存活和认证 WebSocket 就绪。
 *
 * false 只表示服务尚未就绪；进程退出或超时是确定失败。认证器异常按暂未就绪处理，但异常文本
 * 不进入公开错误，避免远端诊断或认证材料泄漏。
 */
class BusinessAgentReadinessProbe(
    private val authenticator: AuthenticatedWebSocketProbe,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val retryDelayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(timeoutMillis > 0) { "readiness timeout must be positive" }
    }

    /** 认证成功前持续有界探测；每轮首先检查 child 存活。 */
    suspend fun await(process: Process, request: AgentConnectRequest) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            check(process.isAlive) { "business Agent exited before authenticated readiness" }
            val authenticated = runCatching { authenticator.authenticate(request) }.getOrDefault(false)
            if (authenticated) {
                check(process.isAlive) { "business Agent exited during authenticated readiness" }
                return
            }
            if (System.nanoTime() >= deadline) {
                throw IllegalStateException("business Agent authenticated readiness timed out")
            }
            retryDelayMillis(RETRY_DELAY_MILLIS)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val RETRY_DELAY_MILLIS = 50L
    }
}
