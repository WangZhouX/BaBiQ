package com.wzx.huitai.desktop.auth

/**
 * UI 可调用的最小退出端口。先清除登录页敏感输入，再委托认证编排撤销远端和本地会话；
 * 不向 Compose 暴露完整 orchestrator。
 */
class BusinessLogoutController(
    private val logout: suspend () -> Unit,
    private val clearSensitiveInput: () -> Unit,
) {
    suspend fun logout() {
        clearSensitiveInput()
        logout.invoke()
    }
}
