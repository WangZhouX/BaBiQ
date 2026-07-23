package com.wzx.huitai.desktop.app

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class BusinessHttpTransportClientTest {
    @Test
    fun `production business HTTP client never follows redirects`() = runBlocking {
        val targetCalls = AtomicInteger()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/redirect") { call.respondRedirect("/target") }
                get("/target") {
                    targetCalls.incrementAndGet()
                    call.respondText("must-not-be-reached")
                }
            }
        }.start(wait = false)
        val client = createBusinessHttpTransportClient(requestTimeoutMillis = 2_000)
        try {
            val port = server.engine.resolvedConnectors().single().port

            val response = client.get("http://127.0.0.1:$port/redirect")

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals(0, targetCalls.get())
        } finally {
            client.close()
            server.stop(100, 1_000)
        }
    }
}
