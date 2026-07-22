package com.wzx.huitai.integration.oa.auth

/** 可安全展示或上报的 OA 认证失败分类；绝不附带远端响应正文或凭据。 */
enum class OaAuthenticationError {
    INVALID_PASSWORD_FORMAT,
    INVALID_CREDENTIALS,
    ACCOUNT_NOT_FOUND,
    REMOTE_PROTOCOL_ERROR,
    REMOTE_UNAVAILABLE,
    REMOTE_TIMEOUT,
}

class OaAuthenticationException(
    val error: OaAuthenticationError,
) : IllegalStateException(error.name) {
    override fun toString(): String = "OaAuthenticationException(error=${error.name})"
}
