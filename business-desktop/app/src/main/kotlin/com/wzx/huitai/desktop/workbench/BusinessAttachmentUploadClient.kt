package com.wzx.huitai.desktop.workbench

import com.wzx.huitai.agent.business.workbench.BusinessAttachmentFile
import com.wzx.huitai.agent.business.workbench.BusinessAttachmentPrepareClient
import com.wzx.huitai.agent.business.workbench.BusinessAttachmentPrepareRequest
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.net.URI
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path as KxPath
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class BusinessLoopbackEndpoint(
    val baseUrl: String,
    val identity: DesktopSessionIdentity,
) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "http" || uri.scheme == "https") { "loopback endpoint must be HTTP(S)" }
        require(uri.host == "127.0.0.1" || uri.host == "::1") { "loopback endpoint must use an IP loopback host" }
        require(uri.port in 1..65535 && uri.path.isNullOrEmpty()) { "loopback endpoint must contain an explicit port only" }
        require(identity.localOrigin == "${uri.scheme}://${uri.host}:${uri.port}") {
            "loopback endpoint must match the authenticated origin"
        }
    }

    override fun toString(): String = "BusinessLoopbackEndpoint(baseUrl=[REDACTED], identity=[REDACTED])"

    companion object {
        fun fromWebSocket(url: String, identity: DesktopSessionIdentity): BusinessLoopbackEndpoint {
            val uri = URI(url)
            require(uri.scheme == "ws" || uri.scheme == "wss") { "agent endpoint must be WS(S)" }
            require(uri.host == "127.0.0.1" || uri.host == "::1") { "agent endpoint must use an IP loopback host" }
            require(uri.port in 1..65535) { "agent endpoint must contain an explicit port" }
            val scheme = if (uri.scheme == "wss") "https" else "http"
            return BusinessLoopbackEndpoint("$scheme://${uri.host}:${uri.port}", identity)
        }
    }
}

data class BusinessAttachmentHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val paths: List<Path>,
) {
    override fun toString(): String =
        "BusinessAttachmentHttpRequest(url=[REDACTED], headers=[REDACTED], paths=${paths.size})"
}

data class BusinessAttachmentUploadReceipt(
    val attachmentBatchId: String,
    val fileCount: Int,
) {
    override fun toString(): String =
        "BusinessAttachmentUploadReceipt(attachmentBatchId=[REDACTED], fileCount=$fileCount)"
}

fun interface BusinessAttachmentUploadTransport {
    suspend fun upload(
        request: BusinessAttachmentHttpRequest,
        files: List<BusinessAttachmentFile>,
        onProgress: (Long, Long) -> Unit,
    ): BusinessAttachmentUploadReceipt
}

data class BusinessAttachmentUploadState(
    val uploading: Boolean = false,
    val progress: Float = 0f,
    val attachmentBatchId: String? = null,
    val ticket: String? = null,
    val completed: Boolean = false,
    val error: String? = null,
) {
    override fun toString(): String =
        "BusinessAttachmentUploadState(uploading=$uploading, progress=$progress, " +
            "attachmentBatchId=[REDACTED], ticket=[REDACTED], completed=$completed, error=$error)"
}

class BusinessAttachmentUploadException(
    val code: String,
) : IllegalStateException(code) {
    override fun toString(): String = "BusinessAttachmentUploadException(code=$code)"
}

class KtorBusinessAttachmentUploadTransport(
    private val http: HttpClient,
    private val timeoutMillis: Long = 30_000,
) : BusinessAttachmentUploadTransport {
    override suspend fun upload(
        request: BusinessAttachmentHttpRequest,
        files: List<BusinessAttachmentFile>,
        onProgress: (Long, Long) -> Unit,
    ): BusinessAttachmentUploadReceipt {
        require(files.size == request.paths.size && files.isNotEmpty()) { "attachment metadata mismatch" }
        val declaredFileBytes = files.sumOf { it.sizeBytes }
        return try {
            withTimeout(timeoutMillis) {
                val response = http.post(request.url) {
                    headers {
                        request.headers.forEach { (name, value) -> append(name, value) }
                    }
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                files.zip(request.paths).forEach { (file, path) ->
                                    append(
                                        "files",
                                        InputProvider(file.sizeBytes) {
                                            SystemFileSystem.source(KxPath(path.toString())).buffered()
                                        },
                                        Headers.build {
                                            append(HttpHeaders.ContentType, file.mediaType)
                                            append(
                                                HttpHeaders.ContentDisposition,
                                                """filename="${safeFileName(file.fileName)}"""",
                                            )
                                        },
                                    )
                                }
                            },
                        ),
                    )
                    onUpload { sent, _ ->
                        onProgress(sent.coerceAtMost(declaredFileBytes), declaredFileBytes)
                    }
                }
                if (!response.status.isSuccess()) {
                    throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_HTTP_${response.status.value}")
                }
                val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }
                    .getOrElse { throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_MALFORMED_RESPONSE") }
                val batchId = body["attachmentBatchId"]?.jsonPrimitive?.content
                    ?.takeIf(String::isNotBlank)
                    ?: throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_MALFORMED_RESPONSE")
                val count = body["fileCount"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_MALFORMED_RESPONSE")
                if (count != files.size) {
                    throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_FILE_COUNT_MISMATCH")
                }
                BusinessAttachmentUploadReceipt(batchId, count)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_TIMEOUT")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BusinessAttachmentUploadException) {
            throw failure
        } catch (_: Throwable) {
            throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_CONNECTION_LOST")
        }
    }

    private fun safeFileName(name: String): String =
        name.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")
}

/**
 * One-shot schedule upload coordinator. A failed transport is deliberately surfaced to the caller;
 * it is never retried because the OA side may already have accepted the multipart request.
 */
class BusinessAttachmentUploadClient(
    private val prepare: BusinessAttachmentPrepareClient,
    private val transport: BusinessAttachmentUploadTransport,
    private val endpoint: BusinessLoopbackEndpoint,
    private val clock: Clock = Clock.systemUTC(),
    private val identityVersion: () -> Pair<Long, Long>? = { null },
    private val fileInspectionDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileInputStream: (Path) -> InputStream = { Files.newInputStream(it) },
) {
    private val mutableState = MutableStateFlow(BusinessAttachmentUploadState())
    val state: StateFlow<BusinessAttachmentUploadState> = mutableState.asStateFlow()

    @Volatile
    private var activeJob: Job? = null
    @Volatile
    private var attachedIdentityVersion: Pair<Long, Long>? = null

    internal val sessionIdentity: DesktopSessionIdentity
        get() = endpoint.identity
    internal val loopbackBaseUrl: String
        get() = endpoint.baseUrl
    internal val currentIdentityVersion: Pair<Long, Long>?
        get() = attachedIdentityVersion

    suspend fun upload(
        request: BusinessAttachmentPrepareRequest,
        paths: List<Path>,
    ): BusinessAttachmentUploadReceipt {
        require(paths.isNotEmpty()) { "至少选择一个附件" }
        require(paths.size <= MAX_FILE_COUNT) { "附件数量超过限制" }
        check(activeJob == null) { "已有附件正在上传" }
        val job = currentCoroutineContext()[Job] ?: error("upload requires a coroutine job")
        activeJob = job
        mutableState.value = BusinessAttachmentUploadState(uploading = true)
        try {
            val files = withContext(fileInspectionDispatcher) {
                paths.map { inspect(it) }
            }
            require(files.sumOf { it.sizeBytes } < MAX_TOTAL_BYTES) { "附件总大小超过限制" }
            val prepared = prepare.prepareAttachment(request.copy(files = files))
            requireCurrent(prepared.identityEpoch, prepared.generation)
            if (!Instant.parse(prepared.expiresAt).isAfter(clock.instant())) {
                throw IllegalStateException("附件上传票据已过期")
            }
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(
                attachmentBatchId = prepared.attachmentBatchId,
                ticket = prepared.ticket,
            )
            val identity = endpoint.identity
            val receipt = transport.upload(
                BusinessAttachmentHttpRequest(
                    url = endpoint.baseUrl.trimEnd('/') +
                        "/business/attachments/uploads/${prepared.attachmentBatchId}",
                    headers = mapOf(
                        "X-Business-Upload-Ticket" to prepared.ticket,
                        "Authorization" to "Bearer ${identity.desktopSessionToken}",
                        "Origin" to identity.localOrigin,
                        "X-Desktop-Instance-Id" to identity.desktopInstanceId,
                        "X-Desktop-Session-Id" to identity.desktopSessionId,
                    ),
                    paths = paths.toList(),
                ),
                files,
            ) { sent, total ->
                val progress = if (total <= 0) 0f else (sent.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
                mutableState.value = mutableState.value.copy(progress = progress)
            }
            currentCoroutineContext().ensureActive()
            requireCurrent(prepared.identityEpoch, prepared.generation)
            if (receipt.attachmentBatchId != prepared.attachmentBatchId) {
                throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_BATCH_MISMATCH")
            }
            if (receipt.fileCount != files.size) {
                throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_FILE_COUNT_MISMATCH")
            }
            mutableState.value = mutableState.value.copy(
                uploading = false,
                progress = 1f,
                completed = true,
                ticket = null,
            )
            return receipt
        } catch (cancelled: CancellationException) {
            mutableState.value = BusinessAttachmentUploadState()
            throw cancelled
        } catch (failure: Throwable) {
            val safeFailure = failure as? BusinessAttachmentUploadException
                ?: BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_UPLOAD_FAILED")
            mutableState.value = mutableState.value.copy(
                uploading = false,
                ticket = null,
                error = safeFailure.code,
            )
            throw safeFailure
        } finally {
            if (activeJob === job) activeJob = null
        }
    }

    fun cancel() {
        val job = activeJob
        mutableState.value = BusinessAttachmentUploadState()
        job?.cancel(CancellationException("attachment upload cancelled"))
    }

    fun onIdentityVersionChanged(identityEpoch: Long, generation: Long) {
        require(identityEpoch > 0 && generation >= 0) { "invalid identity version" }
        val next = identityEpoch to generation
        if (attachedIdentityVersion != next) {
            attachedIdentityVersion = next
            cancel()
        }
    }

    private suspend fun inspect(path: Path): BusinessAttachmentFile {
        currentCoroutineContext().ensureActive()
        require(Files.isRegularFile(path)) { "附件不存在" }
        val size = Files.size(path)
        require(size > 0) { "附件不能为空" }
        val digest = MessageDigest.getInstance("SHA-256")
        fileInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        currentCoroutineContext().ensureActive()
        return BusinessAttachmentFile(
            fileName = path.fileName.toString(),
            sizeBytes = size,
            mediaType = Files.probeContentType(path) ?: "application/octet-stream",
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun requireCurrent(identityEpoch: Long, generation: Long) {
        val current = identityVersion() ?: attachedIdentityVersion ?: return
        if (current != identityEpoch to generation) {
            throw BusinessAttachmentUploadException("BUSINESS_ATTACHMENT_STALE_IDENTITY")
        }
    }

    private companion object {
        const val MAX_FILE_COUNT = 50
        const val MAX_TOTAL_BYTES = 500_000_000L
    }
}
