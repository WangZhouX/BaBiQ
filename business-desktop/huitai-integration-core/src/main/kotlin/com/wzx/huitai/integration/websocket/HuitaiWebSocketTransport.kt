package com.wzx.huitai.integration.websocket

import io.ktor.http.Url
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/** 单次 WebSocket 握手所需的运行时参数。 */
class HuitaiWebSocketConnectRequest(
    val url: String,
    val accessToken: String,
    val tenantId: String,
) {
    init {
        val parsed = Url(url)
        require(parsed.protocol.name == "ws" || parsed.protocol.name == "wss") {
            "url must use WS or WSS"
        }
        require(parsed.user == null && parsed.password == null) { "url must not contain user info" }
        require(parsed.fragment.isEmpty()) { "url must not contain a fragment" }
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
    }

    override fun toString(): String =
        "HuitaiWebSocketConnectRequest(url=[REDACTED], accessToken=[REDACTED], tenantId=[REDACTED])"
}

/** 一次连接尝试的文本事件、状态和关闭句柄。 */
interface HuitaiWebSocketConnection {
    val incoming: ReceiveChannel<String>
    val state: StateFlow<HuitaiWebSocketState>

    suspend fun close()
}

/** 不包含任何具体 OA endpoint 或业务订阅语义的 WebSocket 传输端口。 */
fun interface HuitaiWebSocketTransport {
    fun connect(request: HuitaiWebSocketConnectRequest): HuitaiWebSocketConnection
}
