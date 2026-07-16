package com.wzx.huitai.agent.client

/** Agent WebSocket 单次连接的公开状态；远端诊断在进入状态前统一丢弃。 */
sealed interface AgentConnectionState {
    /** 已开始单次握手。 */
    data object Connecting : AgentConnectionState

    /** 已完成认证握手，可以收发文本帧。 */
    data object Connected : AgentConnectionState

    /** 服务端以 401 或 403 拒绝认证；上层不得自动重试。 */
    data object AuthenticationFailed : AgentConnectionState

    /**
     * 连接已经收束。
     *
     * @property code 标准 WebSocket close code；本地取消时使用正常关闭码。
     * @property reasonPresent 是否收到过远端原因文本；原因正文绝不向上暴露。
     */
    class Closed(
        val code: Int?,
        val reasonPresent: Boolean,
    ) : AgentConnectionState {
        override fun equals(other: Any?): Boolean =
            other is Closed && code == other.code && reasonPresent == other.reasonPresent

        override fun hashCode(): Int = 31 * (code ?: 0) + reasonPresent.hashCode()

        override fun toString(): String =
            "Closed(code=$code, reason=${if (reasonPresent) "[REDACTED]" else "none"})"
    }

    /** 可由上层重连策略处理的瞬时传输失败；原始异常和响应正文不会保存。 */
    class TransportFailure : AgentConnectionState {
        override fun equals(other: Any?): Boolean = other is TransportFailure

        override fun hashCode(): Int = javaClass.hashCode()

        override fun toString(): String = "TransportFailure(detail=[REDACTED])"
    }
}
