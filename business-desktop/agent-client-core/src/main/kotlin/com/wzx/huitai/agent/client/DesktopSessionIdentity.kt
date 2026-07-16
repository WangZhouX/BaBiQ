package com.wzx.huitai.agent.client

import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * 表示一次内置 Agent 子进程生命周期内固定不变的桌面认证身份。
 *
 * 同一个实例会跨 WebSocket 重连复用；只有启动新的子进程时才通过 [forChildLaunch] 创建
 * 新的会话 ID 和令牌。公开字符串刻意不输出任何握手头值，避免稳定安装标识也进入日志。
 *
 * @property desktopInstanceId 桌面安装实例的稳定标识，由桌面安装配置提供。
 * @property desktopSessionId 当前 Agent 子进程的会话标识，只在该子进程生命周期内复用。
 * @property desktopSessionToken 当前 Agent 子进程的一次性认证令牌，仅保存在进程内存中。
 * @property localOrigin WebSocket 握手发送的固定 Origin，不能使用通配符。
 */
class DesktopSessionIdentity(
    val desktopInstanceId: String,
    val desktopSessionId: String,
    val desktopSessionToken: String,
    val localOrigin: String,
) {
    init {
        require(desktopInstanceId.isNotBlank()) { "desktopInstanceId must not be blank" }
        require(desktopSessionId.isNotBlank()) { "desktopSessionId must not be blank" }
        require(desktopSessionToken.isNotBlank()) { "desktopSessionToken must not be blank" }
        validateOrigin(localOrigin)
    }

    override fun toString(): String =
        "DesktopSessionIdentity(desktopInstanceId=[REDACTED], desktopSessionId=[REDACTED], " +
            "desktopSessionToken=[REDACTED], localOrigin=[REDACTED])"

    companion object {
        private const val TOKEN_BYTES = 32
        private val secureRandom = SecureRandom()

        /**
         * 为一次新的内置 Agent 子进程启动创建会话身份。
         *
         * 安装标识由调用方稳定传入；会话标识和高熵令牌每次调用都会重新生成，因此不会跨
         * 子进程复用旧认证边界。
         */
        fun forChildLaunch(
            desktopInstanceId: String,
            localOrigin: String,
        ): DesktopSessionIdentity {
            val tokenBytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
            return DesktopSessionIdentity(
                desktopInstanceId = desktopInstanceId,
                desktopSessionId = UUID.randomUUID().toString(),
                desktopSessionToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes),
                localOrigin = localOrigin,
            )
        }

        /** Origin 只允许完整 HTTP(S) 源，不接受通配符、凭证或附加路径数据。 */
        private fun validateOrigin(value: String) {
            require(value.isNotBlank() && value != "*") { "localOrigin must be fixed and non-wildcard" }
            val uri = runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("localOrigin must be a valid HTTP origin", it) }
            require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
                "localOrigin must use HTTP or HTTPS"
            }
            require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "localOrigin must contain only scheme, host, and optional port"
            }
            require(uri.path.isNullOrEmpty() || uri.path == "/") {
                "localOrigin must not contain a path"
            }
        }
    }
}
