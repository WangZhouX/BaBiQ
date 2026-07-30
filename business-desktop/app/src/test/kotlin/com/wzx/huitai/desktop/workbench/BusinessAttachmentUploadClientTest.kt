package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.workbench.BusinessAttachmentFile
import com.wzx.huitai.agent.business.workbench.BusinessAttachmentPrepareRequest
import com.wzx.huitai.agent.business.workbench.BusinessAttachmentPrepared
import com.wzx.huitai.agent.business.workbench.BusinessAttachmentPrepareClient
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import java.io.InputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class BusinessAttachmentUploadClientTest {
    @Test
    fun `prepare ticket is sent once in dedicated header and progress reaches completion`() = runTest {
        val file = Files.createTempFile("schedule-upload-", ".txt").also { it.writeText("evidence") }
        val rpc = FakePrepareClient()
        val transport = FakeUploadTransport()
        val client = BusinessAttachmentUploadClient(
            prepare = rpc,
            transport = transport,
            endpoint = BusinessLoopbackEndpoint("http://127.0.0.1:48123", identity()),
            clock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
        )

        val result = client.upload(BusinessAttachmentPrepareRequest.validForTest(), listOf(file))

        assertEquals("batch-1", result.attachmentBatchId)
        assertEquals(1, rpc.calls)
        assertEquals(1, transport.calls)
        assertEquals("ticket-1", transport.request?.headers?.get("X-Business-Upload-Ticket"))
        assertEquals("Bearer desktop-token", transport.request?.headers?.get("Authorization"))
        assertEquals("http://127.0.0.1:48123", transport.request?.headers?.get("Origin"))
        assertEquals("desktop-1", transport.request?.headers?.get("X-Desktop-Instance-Id"))
        assertEquals("session-1", transport.request?.headers?.get("X-Desktop-Session-Id"))
        assertEquals(1f, client.state.value.progress)
        assertTrue(client.state.value.completed)
        Files.deleteIfExists(file)
    }

    @Test
    fun `expired ticket never uploads and cancellation clears batch ticket and local state`() = runTest {
        val file = Files.createTempFile("schedule-upload-cancel-", ".txt").also { it.writeText("evidence") }
        val rpc = FakePrepareClient(expiresAt = "2026-07-28T23:59:59Z")
        val transport = FakeUploadTransport()
        val client = BusinessAttachmentUploadClient(
            rpc, transport, BusinessLoopbackEndpoint("http://127.0.0.1:48123", identity()),
            Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
        )
        assertFailsWith<IllegalStateException> {
            client.upload(BusinessAttachmentPrepareRequest.validForTest(), listOf(file))
        }
        assertEquals(0, transport.calls)

        rpc.expiresAt = "2026-07-29T00:05:00Z"
        transport.gate = CompletableDeferred()
        client.onIdentityVersionChanged(7, 9)
        val upload = async { client.upload(BusinessAttachmentPrepareRequest.validForTest(), listOf(file)) }
        kotlinx.coroutines.yield()
        client.onIdentityVersionChanged(8, 10)
        assertFailsWith<kotlinx.coroutines.CancellationException> { upload.await() }
        assertEquals(8L to 10L, client.currentIdentityVersion)
        assertNull(client.state.value.attachmentBatchId)
        assertNull(client.state.value.ticket)
        assertFalse(client.state.value.uploading)
        Files.deleteIfExists(file)
    }

    @Test
    fun `file count failure transport failure and stale identity are each surfaced after one attempt only`() = runTest {
        val file = Files.createTempFile("schedule-upload-once-", ".txt").also { it.writeText("evidence") }
        val rpc = FakePrepareClient()
        val transport = FakeUploadTransport()
        var version = 7L to 9L
        val client = BusinessAttachmentUploadClient(
            rpc,
            transport,
            BusinessLoopbackEndpoint("http://127.0.0.1:48123", identity()),
            Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
            identityVersion = { version },
        )

        transport.receiptFileCount = 2
        val countFailure = assertFailsWith<BusinessAttachmentUploadException> {
            client.upload(BusinessAttachmentPrepareRequest.validForTest(), listOf(file))
        }
        assertEquals("BUSINESS_ATTACHMENT_FILE_COUNT_MISMATCH", countFailure.code)
        assertEquals(1, rpc.calls)
        assertEquals(1, transport.calls)

        transport.receiptFileCount = 1
        transport.failure = IllegalStateException("ticket-secret")
        val transportFailure = assertFailsWith<BusinessAttachmentUploadException> {
            client.upload(BusinessAttachmentPrepareRequest.validForTest(), listOf(file))
        }
        assertEquals("BUSINESS_ATTACHMENT_UPLOAD_FAILED", transportFailure.code)
        assertEquals(2, rpc.calls)
        assertEquals(2, transport.calls)
        assertFalse(client.state.value.toString().contains("ticket-1"))
        assertFalse(client.state.value.toString().contains("ticket-secret"))

        transport.failure = null
        transport.afterUpload = { version = 8L to 10L }
        val stale = assertFailsWith<BusinessAttachmentUploadException> {
            client.upload(BusinessAttachmentPrepareRequest.validForTest(), listOf(file))
        }
        assertEquals("BUSINESS_ATTACHMENT_STALE_IDENTITY", stale.code)
        assertEquals(3, rpc.calls)
        assertEquals(3, transport.calls)
        Files.deleteIfExists(file)
    }

    @Test
    fun `file metadata and hashing are dispatched off caller and cancellation closes an open file`() = runTest {
        val file = Files.createTempFile("schedule-upload-inspection-", ".txt")
            .also { it.writeText("evidence") }
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var dispatchCount = 0
        var inputClosed = false
        var byteServed = false
        val blockingInput = object : InputStream() {
            override fun read(): Int = error("bulk reads only")

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.complete(Unit)
                release.await()
                if (byteServed) return -1
                byteServed = true
                bytes[offset] = 1
                return 1
            }

            override fun close() {
                inputClosed = true
            }
        }
        val inspectionDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(
                context: kotlin.coroutines.CoroutineContext,
                block: Runnable,
            ) {
                dispatchCount++
                Dispatchers.IO.dispatch(context, block)
            }
        }
        val rpc = FakePrepareClient()
        val transport = FakeUploadTransport()
        val client = BusinessAttachmentUploadClient(
            prepare = rpc,
            transport = transport,
            endpoint = BusinessLoopbackEndpoint("http://127.0.0.1:48123", identity()),
            clock = Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC),
            fileInspectionDispatcher = inspectionDispatcher,
            fileInputStream = { blockingInput },
        )

        val upload = async {
            client.upload(BusinessAttachmentPrepareRequest.validForTest(), listOf(file))
        }
        entered.await()
        upload.cancel()
        release.countDown()
        upload.cancelAndJoin()

        assertTrue(dispatchCount > 0)
        assertTrue(inputClosed)
        assertEquals(0, rpc.calls)
        assertEquals(0, transport.calls)
        assertFalse(client.state.value.uploading)
        Files.deleteIfExists(file)
    }

    private class FakePrepareClient(var expiresAt: String = "2026-07-29T00:05:00Z") : BusinessAttachmentPrepareClient {
        var calls = 0
        override suspend fun prepareAttachment(request: BusinessAttachmentPrepareRequest): BusinessAttachmentPrepared {
            calls++
            return BusinessAttachmentPrepared("batch-1", "ticket-1", expiresAt, 7, 9)
        }
    }

    private class FakeUploadTransport : BusinessAttachmentUploadTransport {
        var calls = 0
        var request: BusinessAttachmentHttpRequest? = null
        var gate: CompletableDeferred<Unit>? = null
        var receiptFileCount: Int? = null
        var failure: Throwable? = null
        var afterUpload: (() -> Unit)? = null
        override suspend fun upload(
            request: BusinessAttachmentHttpRequest,
            files: List<BusinessAttachmentFile>,
            onProgress: (Long, Long) -> Unit,
        ): BusinessAttachmentUploadReceipt {
            calls++
            this.request = request
            onProgress(4, 8)
            gate?.await()
            failure?.let { throw it }
            onProgress(8, 8)
            afterUpload?.invoke()
            return BusinessAttachmentUploadReceipt("batch-1", receiptFileCount ?: files.size)
        }
    }

    private fun identity() = DesktopSessionIdentity(
        desktopInstanceId = "desktop-1",
        desktopSessionId = "session-1",
        desktopSessionToken = "desktop-token",
        localOrigin = "http://127.0.0.1:48123",
    )
}
