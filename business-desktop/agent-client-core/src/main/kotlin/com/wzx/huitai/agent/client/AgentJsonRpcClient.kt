package com.wzx.huitai.agent.client

import com.wzx.huitai.agent.protocol.ApplicationEnvelope
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidator
import com.wzx.huitai.agent.protocol.JsonRpcErrorResponse
import com.wzx.huitai.agent.protocol.JsonRpcError
import com.wzx.huitai.agent.protocol.JsonRpcNotification
import com.wzx.huitai.agent.protocol.JsonRpcRequest
import com.wzx.huitai.agent.protocol.JsonRpcSuccessResponse
import com.wzx.huitai.agent.protocol.toJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 通用业务 notification 的原始结构；消费层必须在进入 UI state 前做白名单映射。 */
data class AgentRawNotification(
    val method: String,
    val params: JsonObject,
    val authenticationGeneration: Long? = null,
)

/** 远端 JSON-RPC error 的脱敏本地投影；只保留数字码和白名单附件码。 */
class AgentJsonRpcException(
    val remoteCode: Int,
    val attachmentCode: String? = null,
) : IllegalStateException(
    "Agent JSON-RPC request failed (code=$remoteCode)",
)

/** 当前连接关闭时用于终止所有关联请求的稳定异常。 */
class AgentJsonRpcClosedException : IllegalStateException("Agent JSON-RPC client is closed")

/** 尚未由具体 action handler 接管的最小 inbound JSON-RPC API。 */
sealed interface AgentJsonRpcInbound {
    data class Request(val value: JsonRpcRequest) : AgentJsonRpcInbound
    data class Notification(val value: JsonRpcNotification) : AgentJsonRpcInbound
    /** 仅保留可安全关联的 request ID，不保存原始 method、params 或解析错误。 */
    data class InvalidRequest(val id: Long) : AgentJsonRpcInbound
}

/**
 * 单个已认证 [AgentConnection] 的 JSON-RPC 关联层。
 *
 * request ID 生成器和 pending map 只存在于此对象；所有成功、错误、超时和关闭路径都会清理 pending。
 */
class AgentJsonRpcClient(
    private val connection: AgentConnection,
    scope: CoroutineScope,
    private val requestTimeoutMillis: Long = 30_000,
    inboundCapacity: Int = 64,
    private val notificationAuthenticationGeneration: () -> Long? = { null },
) {
    private val requestIds = AtomicLong(0)
    private val pendingResponses = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val sendJob = SupervisorJob(scope.coroutineContext[Job])
    private val sendScope = CoroutineScope(scope.coroutineContext + sendJob)
    private val closed = AtomicBoolean(false)
    private val cleanupOwner = AtomicReference<CleanupOwner?>(null)
    private val cleanupComplete = CompletableDeferred<Unit>()
    private val mutableInbound = Channel<AgentJsonRpcInbound>(capacity = inboundCapacity)
    private val mutableRawNotifications = Channel<AgentRawNotification>(capacity = inboundCapacity)
    private val overloadResponses = Channel<Long>(capacity = OVERLOAD_RESPONSE_CAPACITY)
    private val readerEntered = AtomicBoolean(false)
    private val overloadWriter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        for (id in overloadResponses) {
            try {
                respondProtocolError(id, "inbound_overloaded")
            } catch (_: CancellationException) {
                break
            } catch (_: Exception) {
                if (!closed.get()) requestCleanupFromOverload()
                break
            }
        }
    }
    private val readerJob: Job = scope.launch(start = CoroutineStart.LAZY) {
        readerEntered.set(true)
        readIncoming()
    }

    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        require(inboundCapacity > 0) { "inboundCapacity must be positive" }
        readerJob.invokeOnCompletion {
            if (!readerEntered.get()) closeFromCancelledConstruction()
        }
        if (!readerJob.start()) closeFromCancelledConstruction()
    }

    val connectionId: String
        get() = connection.connectionId

    val incoming: ReceiveChannel<AgentJsonRpcInbound> = mutableInbound

    /** application bridge 以外的 JSON-RPC notification 单消费通道，不与双向 action request reader 竞争。 */
    val rawNotifications: ReceiveChannel<AgentRawNotification> = mutableRawNotifications

    internal val pendingRequestCount: Int
        get() = pendingResponses.size

    suspend fun request(method: ApplicationMethod, params: ApplicationEnvelope): JsonObject {
        ApplicationProtocolValidator.validate(params)
        return request(method.wireName, params.toJsonObject())
    }

    /** 通用业务方法继续复用本对象唯一的 request ID 生成器和 pending map。 */
    suspend fun request(method: String, params: JsonObject): JsonObject {
        require(method.isNotBlank()) { "JSON-RPC method must not be blank" }
        val id = requestIds.incrementAndGet()
        val response = CompletableDeferred<JsonObject>()
        try {
            val text = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString()
            ApplicationProtocolValidator.validateEnvelopeSize(text.toByteArray(Charsets.UTF_8))
            return withTimeout(requestTimeoutMillis) {
                checkOpen()
                check(pendingResponses.putIfAbsent(id, response) == null) { "Duplicate JSON-RPC request ID" }
                if (closed.get() && pendingResponses.remove(id, response)) {
                    response.completeExceptionally(AgentJsonRpcClosedException())
                }
                if (response.isCompleted) return@withTimeout response.await()
                runSend(text)
                if (closed.get()) {
                    pendingResponses.remove(id, response)
                    response.completeExceptionally(AgentJsonRpcClosedException())
                }
                response.await()
            }
        } finally {
            pendingResponses.remove(id, response)
        }
    }

    suspend fun notify(method: ApplicationMethod, params: ApplicationEnvelope) {
        ApplicationProtocolValidator.validate(params)
        notify(method.wireName, params.toJsonObject())
    }

    suspend fun notify(method: String, params: JsonObject) {
        require(method.isNotBlank()) { "JSON-RPC method must not be blank" }
        val text = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }.toString()
        ApplicationProtocolValidator.validateEnvelopeSize(text.toByteArray(Charsets.UTF_8))
        checkOpen()
        runSend(text)
    }

    /** 回复服务端发起的双向 JSON-RPC request；发送生命周期与普通 request/notification 共用。 */
    suspend fun respondSuccess(id: Long, result: JsonObject) {
        require(id >= 0) { "JSON-RPC response ID must not be negative" }
        val text = ApplicationProtocol.JSON.encodeToString(
            JsonRpcSuccessResponse.serializer(),
            JsonRpcSuccessResponse(id = id, result = result),
        )
        ApplicationProtocolValidator.validateEnvelopeSize(text.toByteArray(Charsets.UTF_8))
        checkOpen()
        runSend(text)
    }

    /** 只暴露稳定协议错误，不转发底层异常或远端载荷。 */
    suspend fun respondProtocolError(id: Long, reason: String = "invalid_request") {
        require(id >= 0) { "JSON-RPC response ID must not be negative" }
        val text = ApplicationProtocol.JSON.encodeToString(
            JsonRpcErrorResponse.serializer(),
            JsonRpcErrorResponse(
                id = id,
                error = JsonRpcError(
                    code = -32041,
                    message = "PROTOCOL_ERROR",
                    data = JsonObject(mapOf("reason" to kotlinx.serialization.json.JsonPrimitive(reason))),
                ),
            ),
        )
        ApplicationProtocolValidator.validateEnvelopeSize(text.toByteArray(Charsets.UTF_8))
        checkOpen()
        runSend(text)
    }

    suspend fun close() {
        closed.set(true)
        if (cleanupOwner.compareAndSet(null, CleanupOwner.EXPLICIT)) {
            withContext(NonCancellable) { performCleanup(cancelReader = true) }
        } else {
            withContext(NonCancellable) { cleanupComplete.await() }
        }
    }

    private suspend fun readIncoming() {
        try {
            for (text in connection.incoming) handleIncoming(text)
        } finally {
            closed.set(true)
            if (cleanupOwner.compareAndSet(null, CleanupOwner.READER)) {
                withContext(NonCancellable) { performCleanup(cancelReader = false) }
            } else if (cleanupOwner.get() == CleanupOwner.OVERLOAD) {
                withContext(NonCancellable) { performCleanup(cancelReader = false) }
            }
        }
    }

    private suspend fun handleIncoming(text: String) {
        val value = runCatching { ApplicationProtocol.JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when {
            "result" in value && "id" in value -> {
                val response = runCatching {
                    ApplicationProtocol.JSON.decodeFromJsonElement(JsonRpcSuccessResponse.serializer(), value)
                }.getOrNull() ?: return
                pendingResponses.remove(response.id)?.complete(response.result)
            }

            "error" in value -> {
                val response = runCatching {
                    ApplicationProtocol.JSON.decodeFromJsonElement(JsonRpcErrorResponse.serializer(), value)
                }.getOrNull() ?: return
                response.id?.let { pendingResponses.remove(it) }
                    ?.completeExceptionally(
                        AgentJsonRpcException(
                            response.error.code,
                            whitelistedAttachmentCode(response.error.data),
                        ),
                    )
            }

            "method" in value && "id" in value -> runCatching {
                ApplicationProtocol.JSON.decodeFromJsonElement(JsonRpcRequest.serializer(), value)
            }.fold(
                onSuccess = {
                    if (mutableInbound.trySend(AgentJsonRpcInbound.Request(it)).isFailure) enqueueOverload(it.id)
                },
                onFailure = {
                    value["id"]?.let { id ->
                        runCatching { id.jsonPrimitive.content.toLong() }.getOrNull()
                            ?.let { id ->
                                if (mutableInbound.trySend(AgentJsonRpcInbound.InvalidRequest(id)).isFailure) enqueueOverload(id)
                            }
                    }
                },
            )

            "method" in value -> {
                val method = runCatching { value.getValue("method").jsonPrimitive.content }.getOrNull() ?: return
                val applicationMethod = ApplicationMethod.entries.firstOrNull { it.wireName == method }
                if (applicationMethod == null) {
                    val params = runCatching { value.getValue("params").jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                    val authenticationGeneration = notificationAuthenticationGeneration()
                    if (
                        mutableRawNotifications.trySend(
                            AgentRawNotification(method, params, authenticationGeneration),
                        ).isFailure
                    ) {
                        requestCleanupFromOverload()
                        throw AgentJsonRpcClosedCancellation()
                    }
                } else {
                    runCatching {
                        ApplicationProtocol.JSON.decodeFromJsonElement(JsonRpcNotification.serializer(), value)
                    }.getOrNull()?.let { notification ->
                        if (mutableInbound.trySend(AgentJsonRpcInbound.Notification(notification)).isFailure) {
                            requestCleanupFromOverload()
                            throw AgentJsonRpcClosedCancellation()
                        }
                    }
                }
            }
        }
    }

    private fun checkOpen() {
        if (!closed.get() && readerJob.isCancelled && !readerEntered.get()) {
            closeFromCancelledConstruction()
        }
        if (closed.get()) throw AgentJsonRpcClosedException()
    }

    private fun closeFromCancelledConstruction() {
        if (!cleanupOwner.compareAndSet(null, CleanupOwner.READER)) return
        closed.set(true)
        overloadResponses.close()
        overloadWriter.cancel()
        mutableInbound.close()
        mutableRawNotifications.close()
        cleanupComplete.complete(Unit)
    }

    private fun failPendingRequests() {
        pendingResponses.values.forEach { it.completeExceptionally(AgentJsonRpcClosedException()) }
        pendingResponses.clear()
    }

    private fun enqueueOverload(id: Long) {
        if (overloadResponses.trySend(id).isFailure) requestCleanupFromOverload()
    }

    private fun requestCleanupFromOverload() {
        closed.set(true)
        if (cleanupOwner.compareAndSet(null, CleanupOwner.OVERLOAD)) {
            readerJob.cancel(AgentJsonRpcClosedCancellation())
        }
    }

    private suspend fun runSend(text: String) {
        val callerJob = coroutineContext[Job]
        val send = sendScope.async(start = CoroutineStart.LAZY) { connection.send(text) }
        if (closed.get()) send.cancel(AgentJsonRpcClosedCancellation())
        try {
            send.start()
            send.await()
        } catch (cancelled: CancellationException) {
            if (closed.get() || cancelled is AgentJsonRpcClosedCancellation) {
                throw AgentJsonRpcClosedException()
            }
            if (callerJob?.isCancelled == true) send.cancel(cancelled)
            throw cancelled
        }
    }

    private suspend fun performCleanup(cancelReader: Boolean) {
        try {
            failPendingRequests()
            sendJob.cancelAndJoin()
            if (cancelReader) readerJob.cancelAndJoin()
            overloadResponses.close()
            if (coroutineContext[Job] !== overloadWriter) overloadWriter.cancelAndJoin()
            mutableInbound.close()
            mutableRawNotifications.close()
            if (cleanupOwner.get() == CleanupOwner.OVERLOAD) {
                runCatching { connection.close() }
            }
        } finally {
            cleanupComplete.complete(Unit)
        }
    }

    private class AgentJsonRpcClosedCancellation : CancellationException("Agent JSON-RPC client closed")

    private enum class CleanupOwner { READER, EXPLICIT, OVERLOAD }

    private companion object {
        const val OVERLOAD_RESPONSE_CAPACITY = 8
        val ATTACHMENT_ERROR_CODES = setOf(
            "ATTACHMENT_EMPTY",
            "ATTACHMENT_LIMIT_EXCEEDED",
            "ATTACHMENT_FILE_TOO_LARGE",
            "ATTACHMENT_TOTAL_TOO_LARGE",
            "ATTACHMENT_PATH_INVALID",
            "ATTACHMENT_NOT_FOUND",
            "ATTACHMENT_NOT_REGULAR_FILE",
            "ATTACHMENT_TYPE_UNSUPPORTED",
            "ATTACHMENT_CHANGED",
            "ATTACHMENT_PARSE_FAILED",
            "ATTACHMENT_ENCRYPTED",
            "ATTACHMENT_TEXT_LIMIT_EXCEEDED",
            "ATTACHMENT_IMAGE_TOO_LARGE",
            "ATTACHMENT_MODEL_UNSUPPORTED",
            "ATTACHMENT_CLIPBOARD_FAILED",
            "ATTACHMENT_PARSE_TIMEOUT",
            "ATTACHMENT_PARSE_OVERLOADED",
            "ATTACHMENT_ARCHIVE_UNSAFE",
            "ATTACHMENT_REFERENCE_AMBIGUOUS",
        )

        fun whitelistedAttachmentCode(data: JsonElement?): String? {
            val candidate = (data as? JsonObject)?.get("attachmentCode") ?: return null
            val primitive = runCatching { candidate.jsonPrimitive }.getOrNull() ?: return null
            if (!primitive.isString) return null
            return primitive.content.takeIf(ATTACHMENT_ERROR_CODES::contains)
        }
    }
}
