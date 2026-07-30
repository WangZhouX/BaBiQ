package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.workbench.BusinessAttachmentFile
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray

class KtorBusinessAttachmentUploadTransportTest {
    @Test
    fun `real loopback transport sends two files multipart with five trusted headers once`() = runBlocking {
        val calls = AtomicInteger()
        val partNames = mutableListOf<String?>()
        val fileNames = mutableListOf<String?>()
        val bodies = mutableListOf<String>()
        var expectedOrigin = ""
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/business/attachments/uploads/batch-1") {
                    calls.incrementAndGet()
                    assertEquals("ticket-1", call.request.headers["X-Business-Upload-Ticket"])
                    assertEquals("Bearer desktop-token", call.request.headers["Authorization"])
                    assertEquals(expectedOrigin, call.request.headers["Origin"])
                    assertEquals("desktop-1", call.request.headers["X-Desktop-Instance-Id"])
                    assertEquals("session-1", call.request.headers["X-Desktop-Session-Id"])
                    call.receiveMultipart().forEachPart { part ->
                        if (part is PartData.FileItem) {
                            partNames += part.name
                            fileNames += part.originalFileName
                            bodies += part.provider().readRemaining().readByteArray().decodeToString()
                        }
                        part.release()
                    }
                    call.respondText(
                        """{"attachmentBatchId":"batch-1","fileCount":2}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().single().port
        expectedOrigin = "http://127.0.0.1:$port"
        val files = listOf(
            Files.createTempFile("upload-one-", ".txt").also { it.writeText("one") },
            Files.createTempFile("upload-two-", ".txt").also { it.writeText("two") },
        )
        val http = HttpClient(CIO)
        try {
            val progress = mutableListOf<Pair<Long, Long>>()
            val transport = KtorBusinessAttachmentUploadTransport(http, 2_000)

            val receipt = transport.upload(
                request(port, files),
                files.map { BusinessAttachmentFile(it.fileName.toString(), Files.size(it), "text/plain") },
            ) { sent, total -> progress += sent to total }

            assertEquals(1, calls.get())
            assertEquals(listOf<String?>("files", "files"), partNames)
            assertEquals(files.map { it.fileName.toString() as String? }, fileNames)
            assertEquals(listOf("one", "two"), bodies)
            assertEquals(2, receipt.fileCount)
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.all { (sent, total) -> total == 6L && sent in 0L..6L })
            assertFalse(receipt.toString().contains("batch-1"))
        } finally {
            http.close()
            server.stop(100, 1_000)
            files.forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `400 401 404 and 5xx are each attempted once and expose only stable error codes`() = runBlocking {
        val calls = AtomicInteger()
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/status/400") { calls.incrementAndGet(); call.respondText("ticket-secret", status = HttpStatusCode.BadRequest) }
                post("/status/401") { calls.incrementAndGet(); call.respondText("token-secret", status = HttpStatusCode.Unauthorized) }
                post("/status/404") { calls.incrementAndGet(); call.respondText("file-id-secret", status = HttpStatusCode.NotFound) }
                post("/status/500") { calls.incrementAndGet(); call.respondText("remote-secret", status = HttpStatusCode.InternalServerError) }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().single().port
        val path = Files.createTempFile("upload-status-", ".txt").also { it.writeText("one") }
        val file = BusinessAttachmentFile(path.fileName.toString(), Files.size(path), "text/plain")
        val http = HttpClient(CIO)
        try {
            listOf(400, 401, 404, 500).forEachIndexed { index, status ->
                val failure = assertFailsWith<BusinessAttachmentUploadException> {
                    KtorBusinessAttachmentUploadTransport(http, 2_000).upload(
                        request(port, listOf(path)).copy(url = "http://127.0.0.1:$port/status/$status"),
                        listOf(file),
                    ) { _, _ -> }
                }
                assertEquals("BUSINESS_ATTACHMENT_HTTP_$status", failure.code)
                assertFalse(failure.toString().contains("secret"))
                assertEquals(index + 1, calls.get())
            }
        } finally {
            http.close()
            server.stop(100, 1_000)
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `timeout malformed response file count mismatch and disconnected loopback never retry`() = runBlocking {
        val calls = AtomicInteger()
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                post("/timeout") {
                    calls.incrementAndGet()
                    delay(250)
                    call.respondText("""{"attachmentBatchId":"batch-1","fileCount":1}""")
                }
                post("/malformed") { calls.incrementAndGet(); call.respondText("ticket-secret") }
                post("/mismatch") {
                    calls.incrementAndGet()
                    call.respondText("""{"attachmentBatchId":"batch-1","fileCount":2}""")
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().single().port
        val path = Files.createTempFile("upload-failure-", ".txt").also { it.writeText("one") }
        val file = BusinessAttachmentFile(path.fileName.toString(), Files.size(path), "text/plain")
        val http = HttpClient(CIO)
        try {
            suspend fun failure(pathSuffix: String, timeout: Long = 2_000): BusinessAttachmentUploadException =
                assertFailsWith {
                    KtorBusinessAttachmentUploadTransport(http, timeout).upload(
                        request(port, listOf(path)).copy(url = "http://127.0.0.1:$port/$pathSuffix"),
                        listOf(file),
                    ) { _, _ -> }
                }

            assertEquals("BUSINESS_ATTACHMENT_TIMEOUT", failure("timeout", 100).code)
            assertEquals(1, calls.get())
            assertEquals("BUSINESS_ATTACHMENT_MALFORMED_RESPONSE", failure("malformed").code)
            assertEquals(2, calls.get())
            assertEquals("BUSINESS_ATTACHMENT_FILE_COUNT_MISMATCH", failure("mismatch").code)
            assertEquals(3, calls.get())

            val unusedPort = ServerSocket().use {
                it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                it.localPort
            }
            val disconnected = assertFailsWith<BusinessAttachmentUploadException> {
                KtorBusinessAttachmentUploadTransport(http, 500).upload(
                    request(unusedPort, listOf(path)),
                    listOf(file),
                ) { _, _ -> }
            }
            assertEquals("BUSINESS_ATTACHMENT_CONNECTION_LOST", disconnected.code)
        } finally {
            http.close()
            server.stop(100, 1_000)
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `websocket endpoint conversion is exact loopback only`() {
        val identity = identity(48123)
        assertEquals(
            "http://127.0.0.1:48123",
            BusinessLoopbackEndpoint.fromWebSocket("ws://127.0.0.1:48123/ws/agent", identity).baseUrl,
        )
        assertFailsWith<IllegalArgumentException> {
            BusinessLoopbackEndpoint.fromWebSocket("ws://localhost:48123/ws/agent", identity)
        }
        assertFailsWith<IllegalArgumentException> {
            BusinessLoopbackEndpoint.fromWebSocket("ws://127.0.0.1:48124/ws/agent", identity)
        }
    }

    private fun request(port: Int, paths: List<java.nio.file.Path>) = BusinessAttachmentHttpRequest(
        url = "http://127.0.0.1:$port/business/attachments/uploads/batch-1",
        headers = mapOf(
            "X-Business-Upload-Ticket" to "ticket-1",
            "Authorization" to "Bearer desktop-token",
            "Origin" to "http://127.0.0.1:$port",
            "X-Desktop-Instance-Id" to "desktop-1",
            "X-Desktop-Session-Id" to "session-1",
        ),
        paths = paths,
    )

    private fun identity(port: Int) = DesktopSessionIdentity(
        "desktop-1",
        "session-1",
        "desktop-token",
        "http://127.0.0.1:$port",
    )
}
