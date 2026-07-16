package com.wzx.huitai.integration.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class KtorHuitaiWebSocketTransportTest {
    @Test
    fun `connects to runtime URL with bearer and tenant headers and receives text`() = runBlocking {
        val observedHandshake = CompletableDeferred<Handshake>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/runtime/events") {
                    observedHandshake.complete(
                        Handshake(
                            authorization = call.request.headers[HttpHeaders.Authorization],
                            tenantId = call.request.headers["tenant-id"],
                        ),
                    )
                    send(Frame.Text("event-one"))
                    close(CloseReason(CloseReason.Codes.NORMAL, "finished"))
                }
            }
        }.start()
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        var connection: HuitaiWebSocketConnection? = null
        try {
            val port = server.engine.resolvedConnectors().single().port
            connection = KtorHuitaiWebSocketTransport(
                httpClient = client,
                scope = scope,
            ).connect(
                HuitaiWebSocketConnectRequest(
                    url = "ws://127.0.0.1:$port/runtime/events",
                    accessToken = "access-secret",
                    tenantId = "tenant-secret",
                ),
            )

            assertEquals("event-one", withTimeout(2_000) { connection.incoming.receive() })
            assertEquals(
                Handshake("Bearer access-secret", "tenant-secret"),
                withTimeout(2_000) { observedHandshake.await() },
            )
            assertIs<HuitaiWebSocketState.Closed>(
                withTimeout(2_000) {
                    connection.state.first { it is HuitaiWebSocketState.Closed }
                },
            )
            Unit
        } finally {
            connection?.close()
            scope.cancel()
            client.close()
            server.stop()
        }
    }

    @Test
    fun `incoming buffer is bounded and drops oldest text frames`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/events") {
                    send(Frame.Text("one"))
                    send(Frame.Text("two"))
                    send(Frame.Text("three"))
                    close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                }
            }
        }.start()
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        var connection: HuitaiWebSocketConnection? = null
        try {
            val port = server.engine.resolvedConnectors().single().port
            connection = KtorHuitaiWebSocketTransport(
                httpClient = client,
                scope = scope,
                incomingCapacity = 2,
            ).connect(connectRequest("ws://127.0.0.1:$port/events"))

            withTimeout(2_000) {
                connection.state.first { it is HuitaiWebSocketState.Closed }
            }

            assertEquals("two", connection.incoming.receive())
            assertEquals("three", connection.incoming.receive())
            assertTrue(connection.incoming.tryReceive().isFailure)
        } finally {
            connection?.close()
            scope.cancel()
            client.close()
            server.stop()
        }
    }

    @Test
    fun `close and error states redact server diagnostics and request secrets`() = runBlocking {
        val closeSecret = "close-access-secret tenant-secret"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/events") {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, closeSecret))
                }
            }
        }.start()
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        var connection: HuitaiWebSocketConnection? = null
        try {
            val port = server.engine.resolvedConnectors().single().port
            val request = HuitaiWebSocketConnectRequest(
                url = "ws://127.0.0.1:$port/events",
                accessToken = "access-secret",
                tenantId = "tenant-secret",
            )
            connection = KtorHuitaiWebSocketTransport(client, scope).connect(request)

            val closed = withTimeout(2_000) {
                connection.state.first { it is HuitaiWebSocketState.Closed }
            }
            val rendered = listOf(request.toString(), closed.toString()).joinToString()

            assertFalse("access-secret" in rendered, rendered)
            assertFalse("tenant-secret" in rendered, rendered)
            assertFalse(closeSecret in rendered, rendered)
            assertTrue("REDACTED" in rendered, rendered)
        } finally {
            connection?.close()
            scope.cancel()
            client.close()
            server.stop()
        }
    }

    @Test
    fun `connection errors expose only a sanitized category`() = runBlocking {
        val rawError = "access-secret tenant-secret raw transport failure"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = HttpClient(
            MockEngine {
                throw IOException(rawError)
            },
        ) {
            install(ClientWebSockets)
        }
        var connection: HuitaiWebSocketConnection? = null
        try {
            val request = HuitaiWebSocketConnectRequest(
                url = "ws://runtime.example.test/events",
                accessToken = "access-secret",
                tenantId = "tenant-secret",
            )
            connection = KtorHuitaiWebSocketTransport(client, scope).connect(request)

            val failed = withTimeout(2_000) {
                connection.state.first { it is HuitaiWebSocketState.Error }
            }
            val rendered = listOf(request.toString(), failed.toString()).joinToString()

            assertFalse("access-secret" in rendered, rendered)
            assertFalse("tenant-secret" in rendered, rendered)
            assertFalse(rawError in rendered, rendered)
            assertTrue("REDACTED" in rendered, rendered)
        } finally {
            connection?.close()
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun `real unauthorized handshake is classified without exposing response diagnostics`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = embeddedServer(ServerCIO, port = 0) {
            routing {
                get("/events") {
                    call.respondText(
                        text = "access-secret tenant-secret rejected",
                        status = io.ktor.http.HttpStatusCode.Unauthorized,
                    )
                }
            }
        }.start()
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        var connection: HuitaiWebSocketConnection? = null
        try {
            val port = server.engine.resolvedConnectors().single().port
            connection = KtorHuitaiWebSocketTransport(client, scope).connect(
                HuitaiWebSocketConnectRequest(
                    url = "ws://127.0.0.1:$port/events",
                    accessToken = "access-secret",
                    tenantId = "tenant-secret",
                ),
            )

            val failed = assertIs<HuitaiWebSocketState.Error>(
                withTimeout(2_000) {
                    connection.state.first { it is HuitaiWebSocketState.Error }
                },
            )

            assertEquals(HuitaiWebSocketFailureKind.AUTHENTICATION_REJECTED, failed.kind)
            assertFalse("access-secret" in failed.toString(), failed.toString())
            assertFalse("tenant-secret" in failed.toString(), failed.toString())
        } finally {
            connection?.close()
            scope.cancel()
            client.close()
            server.stop()
        }
    }

    @Test
    fun `cancelling injected transport scope closes an established connection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = embeddedServer(ServerCIO, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/events") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) Unit
                    }
                }
            }
        }.start()
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        var connection: HuitaiWebSocketConnection? = null
        try {
            val port = server.engine.resolvedConnectors().single().port
            connection = KtorHuitaiWebSocketTransport(client, scope).connect(
                connectRequest("ws://127.0.0.1:$port/events"),
            )
            withTimeout(2_000) {
                connection.state.first { it == HuitaiWebSocketState.Connected }
            }

            scope.cancel()

            val closed = assertIs<HuitaiWebSocketState.Closed>(
                withTimeout(2_000) {
                    connection.state.first { it is HuitaiWebSocketState.Closed }
                },
            )
            assertFalse("access-token" in closed.toString(), closed.toString())
            assertFalse("tenant-1" in closed.toString(), closed.toString())
        } finally {
            connection?.close()
            scope.cancel()
            client.close()
            server.stop()
        }
    }

    private fun connectRequest(url: String) = HuitaiWebSocketConnectRequest(
        url = url,
        accessToken = "access-token",
        tenantId = "tenant-1",
    )

    private data class Handshake(val authorization: String?, val tenantId: String?)
}
