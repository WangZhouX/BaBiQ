package com.wzx.babiq.desktop.client

import com.wzx.babiq.desktop.app.DesktopConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class KtorAgentTransport(
	private val config: DesktopConfig = DesktopConfig(),
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AgentTransport {
	private val client = HttpClient(CIO) {
		install(WebSockets) {
			pingIntervalMillis = 20_000
		}
	}
	private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 128)
	private var session: WebSocketSession? = null

	override val incoming: Flow<String> = _incoming

	override suspend fun connect() {
		session?.close()
		session = client.webSocketSession(
			method = HttpMethod.Get,
			host = config.backendHost,
			port = config.backendPort,
			path = config.backendPath,
		)
		val connectedSession = requireNotNull(session)

		scope.launch {
			for (frame in connectedSession.incoming) {
				if (frame is Frame.Text) {
					_incoming.emit(frame.readText())
				}
			}
		}
	}

	override suspend fun send(text: String) {
		val activeSession = session ?: error("尚未连接后端 WebSocket")
		activeSession.send(text)
	}

	override fun close() {
		scope.cancel()
		client.close()
	}
}
