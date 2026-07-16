package com.wzx.huitai.agent.client

import java.net.URI
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * 单次 Agent WebSocket 握手请求。
 *
 * @property url 子进程启动后返回的动态 loopback WebSocket 地址。
 * @property identity 当前子进程生命周期内固定复用的桌面会话身份。
 */
class AgentConnectRequest(
    val url: String,
    val identity: DesktopSessionIdentity,
) {
    init {
        validateLoopbackWebSocketUrl(url)
    }

    override fun toString(): String =
        "AgentConnectRequest(url=[REDACTED], identity=[REDACTED])"

    companion object {
        /** 防止动态端口配置被替换成局域网、外网、通配监听或带凭证的地址。 */
        private fun validateLoopbackWebSocketUrl(value: String) {
            val uri = runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("url must be a valid loopback WebSocket URL", it) }
            require(uri.scheme.equals("ws", ignoreCase = true) || uri.scheme.equals("wss", ignoreCase = true)) {
                "url must use WS or WSS"
            }
            require(uri.userInfo == null) { "url must not contain user info" }
            require(uri.fragment == null) { "url must not contain a fragment" }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
            require(host == "localhost" || host == "::1" || isIpv4Loopback(host)) {
                "url must target loopback"
            }
        }

        /** 仅接受 127/8 数字地址，避免 DNS 解析和 wildcard 地址混入。 */
        private fun isIpv4Loopback(host: String?): Boolean {
            if (host == null) return false
            val octets = host.split('.')
            return octets.size == 4 && octets.first() == "127" && octets.all { octet ->
                octet.toIntOrNull()?.let { it in 0..255 } == true
            }
        }
    }
}

/** 一次 WebSocket 连接尝试提供的文本帧、状态和关闭句柄。 */
interface AgentConnection {
    /** 每次连接尝试的唯一标识，用于隔离旧连接迟到事件。 */
    val connectionId: String

    /** 已经过有界缓冲的服务端文本帧；慢消费者会丢弃最旧帧。 */
    val incoming: ReceiveChannel<String>

    /** 不包含令牌、握手头值或远端诊断正文的公开状态。 */
    val state: StateFlow<AgentConnectionState>

    /** 该连接是否曾完成认证；终态覆盖 StateFlow 中间值后仍保持为 true。 */
    val hasConnected: Boolean

    /** 向当前已认证 session 发送单个文本帧。 */
    suspend fun send(text: String)

    /** 幂等关闭当前 session、reader 和输入通道。 */
    suspend fun close()
}

/** 只负责认证握手和文本帧收发，不包含 JSON-RPC 或自动重试语义的传输端口。 */
interface AgentTransport {
    /** 先收束该 transport 的旧连接，再开始一次新的连接尝试。 */
    suspend fun connect(request: AgentConnectRequest): AgentConnection

    /** 幂等关闭 transport 当前持有的连接；注入的 HttpClient 生命周期仍归调用方。 */
    suspend fun close()
}
