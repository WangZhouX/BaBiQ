package com.wzx.huitai.integration.auth

/** 只在认证基础设施内部流转的访问凭据。 */
data class AuthTokenSet(
    val accessToken: String,
    val refreshToken: String,
) {
    init {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
    }

    /** 防止日志、异常和调试输出泄露真实 token。 */
    override fun toString(): String = "AuthTokenSet(accessToken=[REDACTED], refreshToken=[REDACTED])"
}

/** 将 token 读取能力限制在 integration 模块内部。 */
internal interface AuthTokenProvider {
    /** 返回当前访问 token；未登录或凭据不可用时返回 null。 */
    suspend fun accessToken(): String?

    /** 返回当前刷新 token；未登录或凭据不可用时返回 null。 */
    suspend fun refreshToken(): String?
}
