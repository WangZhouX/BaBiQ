package com.wzx.huitai.integration.http

import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class KtorHuitaiTransportTest {
    @Test
    fun `maps base URL path method query body and auth headers exactly`() = runTest {
        val requestBody = """{"caseId":"case-1"}""".encodeToByteArray()
        lateinit var captured: HttpRequestData
        val client = HttpClient(
            MockEngine { request ->
                captured = request
                respond(
                    content = ByteReadChannel("ok".encodeToByteArray()),
                    status = HttpStatusCode.Accepted,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("text/plain"),
                        "X-Trace-Id" to listOf("trace-1"),
                    ),
                )
            },
        )

        val outcome = KtorHuitaiTransport(
            baseUrl = "https://api.example.test/gateway",
            httpClient = client,
        ).send(
            request(
                method = "POST",
                relativePath = "/framework/example?include=summary&tag=first&tag=second",
                headers = linkedMapOf(
                    HttpHeaders.Authorization to "Bearer access-token",
                    "tenant-id" to "tenant-1",
                    "X-Correlation-Id" to "correlation-1",
                ),
                body = requestBody,
            ),
        )

        assertEquals("https://api.example.test/gateway/framework/example?include=summary&tag=first&tag=second", captured.url.toString())
        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("summary", captured.url.parameters["include"])
        assertEquals(listOf("first", "second"), captured.url.parameters.getAll("tag"))
        assertContentEquals(requestBody, (captured.body as OutgoingContent.ByteArrayContent).bytes())
        assertEquals("Bearer access-token", captured.headers[HttpHeaders.Authorization])
        assertEquals("tenant-1", captured.headers["tenant-id"])
        assertEquals("correlation-1", captured.headers["X-Correlation-Id"])

        val received = assertIs<HuitaiTransportOutcome.ResponseReceived>(outcome)
        assertEquals(202, received.httpStatus)
        assertEquals(listOf("text/plain"), received.headers[HttpHeaders.ContentType])
        assertEquals(listOf("trace-1"), received.headers["X-Trace-Id"])
        assertContentEquals("ok".encodeToByteArray(), received.body)
    }

    @Test
    fun `preserves binary response bytes without decoding`() = runTest {
        val binary = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0xff.toByte())
        val transport = transportResponding(
            body = binary,
            contentType = "application/octet-stream",
        )

        val received = assertIs<HuitaiTransportOutcome.ResponseReceived>(
            transport.send(request(method = "GET")),
        )

        assertEquals(200, received.httpStatus)
        assertEquals(listOf("application/octet-stream"), received.headers[HttpHeaders.ContentType])
        assertContentEquals(binary, received.body)
    }

    @Test
    fun `preserves JSON error bytes for later business decoding`() = runTest {
        val jsonError = """{"code":500456,"msg":"export failed","data":null}""".encodeToByteArray()
        val transport = transportResponding(
            body = jsonError,
            contentType = "application/octet-stream",
            status = HttpStatusCode.BadRequest,
        )

        val received = assertIs<HuitaiTransportOutcome.ResponseReceived>(
            transport.send(request(method = "GET")),
        )

        assertEquals(400, received.httpStatus)
        assertEquals(listOf("application/octet-stream"), received.headers[HttpHeaders.ContentType])
        assertContentEquals(jsonError, received.body)
    }

    @Test
    fun `response owns headers and body and redacts payload in toString`() {
        val mutableHeaderValues = mutableListOf("top-secret-header", "second-secret-header")
        val mutableHeaders = linkedMapOf("X-Secret" to mutableHeaderValues)
        val mutableBody = "top-secret-body".encodeToByteArray()
        val received = HuitaiTransportOutcome.ResponseReceived(
            httpStatus = 200,
            headers = mutableHeaders,
            body = mutableBody,
        )

        mutableHeaders["X-Secret"] = mutableListOf("changed")
        mutableHeaderValues[0] = "changed-again"
        mutableBody[0] = 0x00
        received.body[1] = 0x00

        assertEquals(listOf("top-secret-header", "second-secret-header"), received.headers["X-Secret"])
        assertContentEquals("top-secret-body".encodeToByteArray(), received.body)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (received.headers as MutableMap<String, List<String>>)["X-Secret"] = listOf("attacker")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (received.headers.getValue("X-Secret") as MutableList<String>)[0] = "attacker"
        }
        val rendered = received.toString()
        assertFalse("top-secret-header" in rendered, rendered)
        assertFalse("top-secret-body" in rendered, rendered)
    }

    @Test
    fun `connect failure is classified as not sent`() = runTest {
        val client = HttpClient(
            MockEngine {
                throw ConnectException("connection refused")
            },
        )

        val outcome = KtorHuitaiTransport(
            baseUrl = "https://api.example.test",
            httpClient = client,
        ).send(request(method = "GET"))

        assertEquals(HuitaiTransportOutcome.NotSent, outcome)
    }

    @Test
    fun `generic IO failure without proof of connection phase is ambiguous`() = runTest {
        val client = HttpClient(
            MockEngine {
                throw IOException("connection reset after request write")
            },
        )

        val outcome = KtorHuitaiTransport(
            baseUrl = "https://api.example.test",
            httpClient = client,
        ).send(request(method = "POST"))

        assertEquals(HuitaiTransportOutcome.AmbiguousAfterSend, outcome)
    }

    @Test
    fun `preserves repeated response headers without comma joining`() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel("ok"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("text/plain"),
                        HttpHeaders.SetCookie to listOf("session=one; Path=/", "preference=two; Path=/"),
                    ),
                )
            },
        )

        val received = assertIs<HuitaiTransportOutcome.ResponseReceived>(
            KtorHuitaiTransport(
                baseUrl = "https://api.example.test",
                httpClient = client,
            ).send(request(method = "GET")),
        )

        assertEquals(listOf("text/plain"), received.headers[HttpHeaders.ContentType])
        assertEquals(
            listOf("session=one; Path=/", "preference=two; Path=/"),
            received.headers[HttpHeaders.SetCookie],
        )
    }

    @Test
    fun `response body IO failure after headers is ambiguous after send`() = runTest {
        val client = HttpClient(
            MockEngine {
                val responseBody = ByteChannel(autoFlush = true)
                responseBody.writeFully("partial".encodeToByteArray())
                responseBody.close(IOException("response stream failed"))
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val outcome = KtorHuitaiTransport(
            baseUrl = "https://api.example.test",
            httpClient = client,
        ).send(request(method = "POST"))

        assertEquals(HuitaiTransportOutcome.AmbiguousAfterSend, outcome)
    }

    private fun transportResponding(
        body: ByteArray,
        contentType: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): KtorHuitaiTransport = KtorHuitaiTransport(
        baseUrl = "https://api.example.test",
        httpClient = HttpClient(
            MockEngine {
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, contentType),
                )
            },
        ),
    )

    private fun request(
        method: String,
        relativePath: String = "/framework/example",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = byteArrayOf(),
    ) = HuitaiRequest(
        method = method,
        relativePath = relativePath,
        headers = headers,
        body = body,
        replayPolicy = ActionReplayPolicy.NEVER,
        executionId = null,
        idempotencyHeaderName = null,
        reconciliationPolicy = ReconciliationPolicy.MANUAL,
    )
}
