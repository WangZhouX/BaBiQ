package com.wzx.babiq.desktop.app

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class DesktopConfig(
	val backendHost: String = "127.0.0.1",
	val backendPort: Int = 8080,
	val backendPath: String = "/ws/agent",
	val secureWebSocket: Boolean = false,
	val requestTimeout: Duration = 10.seconds,
)
