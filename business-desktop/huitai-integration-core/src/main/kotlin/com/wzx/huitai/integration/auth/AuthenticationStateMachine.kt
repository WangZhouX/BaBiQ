package com.wzx.huitai.integration.auth

/**
 * 认证状态的纯迁移规则。
 *
 * 状态机不持有可变状态，调用方必须显式传入起点，避免会话协调逻辑绕过迁移矩阵。
 */
class AuthenticationStateMachine {
    /** 校验并返回目标状态；未列入规格的迁移一律拒绝。 */
    fun transition(
        from: AuthenticationState,
        to: AuthenticationState,
    ): AuthenticationState {
        require(from to to in allowedTransitions) {
            "Authentication transition is not allowed: $from -> $to"
        }
        return to
    }

    private companion object {
        /** 集中维护唯一允许的状态边，防止条件分支意外放宽认证生命周期。 */
        val allowedTransitions = setOf(
            AuthenticationState.SIGNED_OUT to AuthenticationState.SIGNING_IN,
            AuthenticationState.SIGNING_IN to AuthenticationState.AUTHENTICATED,
            AuthenticationState.SIGNING_IN to AuthenticationState.EXPIRED,
            AuthenticationState.SIGNING_IN to AuthenticationState.MEMBERSHIP_EXPIRED,
            AuthenticationState.SIGNING_IN to AuthenticationState.SIGNED_OUT,
            AuthenticationState.AUTHENTICATED to AuthenticationState.REFRESHING,
            AuthenticationState.AUTHENTICATED to AuthenticationState.SWITCHING_TENANT,
            AuthenticationState.AUTHENTICATED to AuthenticationState.SIGNED_OUT,
            AuthenticationState.REFRESHING to AuthenticationState.AUTHENTICATED,
            AuthenticationState.REFRESHING to AuthenticationState.EXPIRED,
            AuthenticationState.REFRESHING to AuthenticationState.MEMBERSHIP_EXPIRED,
            AuthenticationState.REFRESHING to AuthenticationState.SIGNED_OUT,
            AuthenticationState.SWITCHING_TENANT to AuthenticationState.AUTHENTICATED,
            AuthenticationState.SWITCHING_TENANT to AuthenticationState.EXPIRED,
            AuthenticationState.SWITCHING_TENANT to AuthenticationState.MEMBERSHIP_EXPIRED,
            AuthenticationState.SWITCHING_TENANT to AuthenticationState.SIGNED_OUT,
            AuthenticationState.EXPIRED to AuthenticationState.SIGNED_OUT,
            AuthenticationState.MEMBERSHIP_EXPIRED to AuthenticationState.SIGNED_OUT,
        )
    }
}
