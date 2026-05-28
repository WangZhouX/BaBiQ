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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * AgentTransport 的 Ktor 实现，只负责 WebSocket 字符串收发。
 *
 * 它不理解 JSON-RPC，也不关心 Thread/Turn/Item；这些协议语义留给 AgentClient。
 * 分开后，如果以后要换成测试内存传输、Unix socket 或远程 relay，只需要替换这一层。
 *
 * @param config 后端地址、WebSocket 路径和请求超时等桌面端配置。
 * @param scope 读取 WebSocket 帧的后台协程作用域，默认使用 IO dispatcher。
 */
class KtorAgentTransport(
	private val config: DesktopConfig = DesktopConfig(),
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AgentTransport {
	// Ktor HttpClient 是重量级对象，放在 transport 生命周期里复用。
	private val client = HttpClient(CIO) {
		install(WebSockets) {
			// P1 桌面端需要能感知断线，ping 可以让长时间空闲连接更早暴露问题。
			pingIntervalMillis = 20_000
		}
	}

	// extraBufferCapacity 允许网络协程短时间快于 UI 消费，避免直接挂起在 WebSocket 读取循环里。
	private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 128)

	// 当前活跃 WebSocket session；重连时会先关闭旧 session 再创建新 session。
	private var session: WebSocketSession? = null
	private var readerJob: Job? = null

	override val incoming: Flow<String> = _incoming

	/**
	 * 建立 WebSocket 连接，并启动一个后台协程持续读取文本帧。
	 */
	override suspend fun connect() {
		readerJob?.cancel()
		session?.close()
		val connectedSession = client.webSocketSession(
			method = HttpMethod.Get,
			host = config.backendHost,
			port = config.backendPort,
			path = config.backendPath,
		)
		session = connectedSession

		readerJob = scope.launch {
			// 后端所有 JSON-RPC response/notification 都是文本帧，二进制帧在 P1 协议里没有意义。
			try {
				for (frame in connectedSession.incoming) {
					if (frame is Frame.Text) {
						_incoming.emit(frame.readText())
					}
				}
			} finally {
				if (session === connectedSession) {
					session = null
				}
			}
		}
	}

	/**
	 * 发送一个 JSON-RPC 文本帧；如果还没连接，直接抛错交给 Controller 展示。
	 */
	override suspend fun send(text: String) {
		val activeSession = session ?: error("尚未连接后端 WebSocket")
		activeSession.send(text)
	}

	/**
	 * 关闭 transport，释放协程和 Ktor client。
	 */
	override fun close() {
		readerJob?.cancel()
		session = null
		scope.cancel()
		client.close()
	}
}
