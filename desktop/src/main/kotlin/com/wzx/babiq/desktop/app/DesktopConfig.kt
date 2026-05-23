package com.wzx.babiq.desktop.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 桌面端运行配置。
 *
 * 这里先使用代码默认值，是为了 P1-4 保持“本机轻客户端”简单形态；后续 P2 如果做设置持久化，
 * 可以把这个 data class 改成从配置文件或用户设置读取，而不影响 AgentClient 的调用方式。
 *
 * @property backendHost 后端监听地址，默认本机。
 * @property backendPort Spring Boot 后端端口。
 * @property backendPath WebSocket 协议入口路径。
 * @property secureWebSocket 是否使用 wss；本机开发默认 false。
 * @property requestTimeout 每个 JSON-RPC request 等待 response 的最长时间。
 */
data class DesktopConfig(
	val backendHost: String = "127.0.0.1",
	val backendPort: Int = 8080,
	val backendPath: String = "/ws/agent",
	val secureWebSocket: Boolean = false,
	val requestTimeout: Duration = 10.seconds,
)
