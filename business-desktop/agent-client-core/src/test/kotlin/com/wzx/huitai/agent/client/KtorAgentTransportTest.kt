package com.wzx.huitai.agent.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import com.wzx.huitai.agent.protocol.ApplicationProtocolLimits
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class KtorAgentTransportTest {
    @Test
    fun `child launches create fresh session credentials while reconnects reuse one identity`() {
        val first = DesktopSessionIdentity.forChildLaunch(
            desktopInstanceId = "desktop-installation-1",
            localOrigin = "http://127.0.0.1:43117",
        )
        val second = DesktopSessionIdentity.forChildLaunch(
            desktopInstanceId = "desktop-installation-1",
            localOrigin = "http://127.0.0.1:43117",
        )

        assertEquals("desktop-installation-1", first.desktopInstanceId)
        assertEquals("desktop-installation-1", second.desktopInstanceId)
        assertNotEquals(first.desktopSessionId, second.desktopSessionId)
        assertNotEquals(first.desktopSessionToken, second.desktopSessionToken)
        assertEquals(first.desktopSessionId, first.desktopSessionId)
        assertEquals(first.desktopSessionToken, first.desktopSessionToken)
    }

    @Test
    fun `request accepts only loopback websocket URLs without ambient URL credentials`() {
        val identity = identity()

        listOf(
            "ws://127.0.0.1:43117/ws/agent",
            "ws://localhost:43117/ws/agent",
            "ws://[::1]:43117/ws/agent",
        ).forEach { AgentConnectRequest(it, identity) }

        listOf(
            "ws://192.168.1.20:43117/ws/agent",
            "wss://agent.example.test/ws/agent",
            "ws://0.0.0.0:43117/ws/agent",
            "ws://[::]:43117/ws/agent",
            "ws://user:password@127.0.0.1:43117/ws/agent",
            "ws://127.0.0.1:43117/ws/agent#secret-fragment",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                AgentConnectRequest(invalid, identity)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            DesktopSessionIdentity.forChildLaunch("desktop-installation-1", "*")
        }
    }

    @Test
    fun `connect sends authenticated desktop headers receives text and sends text frames`() = runBlocking {
        val observedHandshake = CompletableDeferred<Handshake>()
        val observedClientText = CompletableDeferred<String>()
        val fixture = serverFixture {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    observedHandshake.complete(
                        Handshake(
                            authorization = call.request.headers[HttpHeaders.Authorization],
                            desktopInstanceId = call.request.headers["X-Desktop-Instance-Id"],
                            desktopSessionId = call.request.headers["X-Desktop-Session-Id"],
                            origin = call.request.headers[HttpHeaders.Origin],
                        ),
                    )
                    send(Frame.Text("server-text"))
                    observedClientText.complete((incoming.receive() as Frame.Text).readText())
                    close(CloseReason(CloseReason.Codes.NORMAL, "finished"))
                }
            }
        }
        val connectionIds = Channel<String>(2).apply {
            trySend("connection-1")
            trySend("connection-2")
        }
        val transport = fixture.transport(connectionIdFactory = { connectionIds.tryReceive().getOrThrow() })
        try {
            val request = AgentConnectRequest(fixture.wsUrl("/ws/agent"), identity())
            val first = transport.connect(request)
            assertEquals("connection-1", first.connectionId)
            awaitConnected(first)

            assertEquals("server-text", withTimeout(TIMEOUT) { first.incoming.receive() })
            first.send("client-text")
            assertEquals("client-text", withTimeout(TIMEOUT) { observedClientText.await() })
            assertEquals(
                Handshake(
                    authorization = "Bearer desktop-session-token-secret",
                    desktopInstanceId = "desktop-installation-1",
                    desktopSessionId = "desktop-session-1",
                    origin = "http://127.0.0.1:43117",
                ),
                withTimeout(TIMEOUT) { observedHandshake.await() },
            )

            val second = transport.connect(request)
            assertEquals("connection-2", second.connectionId)
            assertNotEquals(first.connectionId, second.connectionId)
            second.close()
        } finally {
            transport.close()
            fixture.close()
        }
    }

    @Test
    fun `incoming buffer is bounded and drops oldest text frames`() = runBlocking {
        val fixture = serverFixture {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    send(Frame.Text("one"))
                    send(Frame.Text("two"))
                    send(Frame.Text("three"))
                    close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                }
            }
        }
        val transport = fixture.transport(incomingCapacity = 2)
        try {
            val connection = transport.connect(AgentConnectRequest(fixture.wsUrl("/ws/agent"), identity()))
            withTimeout(TIMEOUT) { connection.state.first { it is AgentConnectionState.Closed } }

            assertTrue(connection.hasConnected)
            assertEquals("two", connection.incoming.receive())
            assertEquals("three", connection.incoming.receive())
            assertTrue(connection.incoming.tryReceive().isFailure)
        } finally {
            transport.close()
            fixture.close()
        }
    }

    @Test
    fun `oversized UTF-8 envelope is rejected before the server receives a frame`() = runBlocking {
        val received = CompletableDeferred<String>()
        val fixture = serverFixture {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) received.complete(frame.readText())
                }
            }
        }
        val transport = fixture.transport()
        try {
            val connection = transport.connect(AgentConnectRequest(fixture.wsUrl("/ws/agent"), identity()))
            awaitConnected(connection)

            assertFailsWith<ApplicationProtocolValidationException> {
                connection.send("界".repeat(ApplicationProtocolLimits.MAX_ENVELOPE_BYTES / 3 + 1))
            }
            assertFalse(received.isCompleted)
        } finally {
            transport.close()
            fixture.close()
        }
    }

    @Test
    fun `reconnect shuts down prior reader and close is idempotent`() = runBlocking {
        val connectedCount = AtomicInteger()
        val firstClosed = CompletableDeferred<Unit>()
        val fixture = serverFixture {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    val ordinal = connectedCount.incrementAndGet()
                    try {
                        for (frame in incoming) if (frame is Frame.Text) Unit
                    } finally {
                        if (ordinal == 1) firstClosed.complete(Unit)
                    }
                }
            }
        }
        val transport = fixture.transport()
        try {
            val request = AgentConnectRequest(fixture.wsUrl("/ws/agent"), identity())
            val first = transport.connect(request)
            awaitConnected(first)

            val second = transport.connect(request)
            awaitConnected(second)
            withTimeout(TIMEOUT) { firstClosed.await() }
            withTimeout(TIMEOUT) { first.state.first { it is AgentConnectionState.Closed } }
            assertTrue(first.incoming.receiveCatching().isClosed)

            second.close()
            second.close()
            transport.close()
            transport.close()
        } finally {
            transport.close()
            fixture.close()
        }
    }

    @Test
    fun `cancelling injected scope closes an established connection`() = runBlocking {
        val fixture = serverFixture {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    for (frame in incoming) if (frame is Frame.Text) Unit
                }
            }
        }
        val transport = fixture.transport()
        try {
            val connection = transport.connect(AgentConnectRequest(fixture.wsUrl("/ws/agent"), identity()))
            awaitConnected(connection)

            fixture.scope.cancel()

            withTimeout(TIMEOUT) { connection.state.first { it is AgentConnectionState.Closed } }
            assertTrue(connection.incoming.receiveCatching().isClosed)
        } finally {
            transport.close()
            fixture.close()
        }
    }

    @Test
    fun `real unauthorized and forbidden handshakes map to authentication failure without retry`() = runBlocking {
        listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden).forEach { status ->
            val requests = AtomicInteger()
            val fixture = serverFixture {
                routing {
                    get("/ws/agent") {
                        requests.incrementAndGet()
                        call.respondText("desktop-session-token-secret rejected", status = status)
                    }
                }
            }
            val transport = fixture.transport()
            try {
                val connection = transport.connect(AgentConnectRequest(fixture.wsUrl("/ws/agent"), identity()))

                withTimeout(TIMEOUT) {
                    connection.state.first { it == AgentConnectionState.AuthenticationFailed }
                }
                assertFalse(connection.hasConnected)
                assertEquals(1, requests.get())
            } finally {
                transport.close()
                fixture.close()
            }
        }
    }

    @Test
    fun `request identity close and transport error rendering never exposes handshake values`() = runBlocking {
        val secretError = "desktop-session-token-secret desktop-installation-1 desktop-session-1 origin-secret"
        val fixture = serverFixture {
            routing {
                get("/ws/agent") {
                    call.respondText(secretError, status = HttpStatusCode.InternalServerError)
                }
            }
        }
        val identity = identity(localOrigin = "http://origin-secret:43117")
        val request = AgentConnectRequest(fixture.wsUrl("/ws/agent"), identity)
        val transport = fixture.transport()
        try {
            val connection = transport.connect(request)
            val failed = withTimeout(TIMEOUT) {
                connection.state.first { it is AgentConnectionState.TransportFailure }
            }
            val rendered = listOf(identity, request, failed).joinToString()

            listOf(
                "desktop-session-token-secret",
                "desktop-installation-1",
                "desktop-session-1",
                "http://origin-secret:43117",
                secretError,
            ).forEach { secret -> assertFalse(secret in rendered, rendered) }
            assertTrue("REDACTED" in rendered, rendered)
        } finally {
            transport.close()
            fixture.close()
        }
    }

    @Test
    fun `server close diagnostics are redacted from public state`() = runBlocking {
        val fixture = serverFixture {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "desktop-session-token-secret desktop-installation-1 desktop-session-1",
                        ),
                    )
                }
            }
        }
        val transport = fixture.transport()
        try {
            val connection = transport.connect(AgentConnectRequest(fixture.wsUrl("/ws/agent"), identity()))
            val closed = withTimeout(TIMEOUT) {
                connection.state.first { it is AgentConnectionState.Closed }
            }
            val rendered = closed.toString()

            assertFalse("desktop-session-token-secret" in rendered, rendered)
            assertFalse("desktop-installation-1" in rendered, rendered)
            assertFalse("desktop-session-1" in rendered, rendered)
            assertTrue("REDACTED" in rendered, rendered)
        } finally {
            transport.close()
            fixture.close()
        }
    }

    private suspend fun awaitConnected(connection: AgentConnection) {
        withTimeout(TIMEOUT) { connection.state.first { it == AgentConnectionState.Connected } }
    }

    private fun identity(localOrigin: String = "http://127.0.0.1:43117") = DesktopSessionIdentity(
        desktopInstanceId = "desktop-installation-1",
        desktopSessionId = "desktop-session-1",
        desktopSessionToken = "desktop-session-token-secret",
        localOrigin = localOrigin,
    )

    private suspend fun serverFixture(module: io.ktor.server.application.Application.() -> Unit): ServerFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = embeddedServer(ServerCIO, port = 0, module = module).start()
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        val port = server.engine.resolvedConnectors().single().port
        return ServerFixture(server, client, scope, port)
    }

    private data class Handshake(
        val authorization: String?,
        val desktopInstanceId: String?,
        val desktopSessionId: String?,
        val origin: String?,
    )

    private class ServerFixture(
        private val server: io.ktor.server.engine.EmbeddedServer<*, *>,
        private val client: HttpClient,
        val scope: CoroutineScope,
        private val port: Int,
    ) {
        fun wsUrl(path: String): String = "ws://127.0.0.1:$port$path"

        fun transport(
            incomingCapacity: Int = 128,
            connectionIdFactory: () -> String = { "connection-${System.nanoTime()}" },
        ): KtorAgentTransport = KtorAgentTransport(
            httpClient = client,
            scope = scope,
            incomingCapacity = incomingCapacity,
            connectionIdFactory = connectionIdFactory,
        )

        fun close() {
            scope.cancel()
            client.close()
            server.stop()
        }
    }

    private companion object {
        const val TIMEOUT = 3_000L
    }
}
