package com.wzx.huitai.integration.websocket

/** WebSocket 连接状态；所有远端诊断内容都在进入公开状态前丢弃。 */
sealed interface HuitaiWebSocketState {
    data object Connecting : HuitaiWebSocketState

    data object Connected : HuitaiWebSocketState

    class Closed(
        val code: Int?,
        val reasonPresent: Boolean,
    ) : HuitaiWebSocketState {
        override fun equals(other: Any?): Boolean =
            other is Closed && code == other.code && reasonPresent == other.reasonPresent

        override fun hashCode(): Int = 31 * (code ?: 0) + reasonPresent.hashCode()

        override fun toString(): String =
            "Closed(code=$code, reason=${if (reasonPresent) "[REDACTED]" else "none"})"
    }

    class Error(val kind: HuitaiWebSocketFailureKind) : HuitaiWebSocketState {
        override fun equals(other: Any?): Boolean = other is Error && kind == other.kind

        override fun hashCode(): Int = kind.hashCode()

        override fun toString(): String = "Error(kind=$kind, detail=[REDACTED])"
    }
}

/** 区分不可自动重试的认证拒绝与可退避重试的瞬时失败。 */
enum class HuitaiWebSocketFailureKind {
    AUTHENTICATION_REJECTED,
    TRANSIENT,
}
