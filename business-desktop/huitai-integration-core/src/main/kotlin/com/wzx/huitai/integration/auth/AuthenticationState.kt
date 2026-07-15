package com.wzx.huitai.integration.auth

/** 汇泰业务会话在客户端中的认证状态。 */
enum class AuthenticationState {
    SIGNED_OUT,
    SIGNING_IN,
    AUTHENTICATED,
    REFRESHING,
    SWITCHING_TENANT,
    EXPIRED,
    MEMBERSHIP_EXPIRED,
}
