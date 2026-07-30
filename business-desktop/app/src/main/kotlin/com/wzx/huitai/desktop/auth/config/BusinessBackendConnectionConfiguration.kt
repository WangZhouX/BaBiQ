package com.wzx.huitai.desktop.auth.config

/**
 * 前端连接独立业务后端所需的非敏感配置。
 *
 * 会话文件仍只负责提供短期身份；连接地址和本地 Origin 由同一份桌面配置文件决定。
 */
data class BusinessBackendConnectionConfiguration(
    val websocketUrl: String,
    val localOrigin: String,
)
