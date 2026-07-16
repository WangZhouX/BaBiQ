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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 远端 JSON-RPC error 的脱敏本地投影，不保留远端 message 或 data。 */
class AgentJsonRpcException(val remoteCode: Int) : IllegalStateException(
    "Agent JSON-RPC request failed (code=$remoteCode)",
)

/** 当前连接关闭时用于终止所有关联请求的稳定异常。 */
class AgentJsonRpcClosedException : IllegalStateException("Agent JSON-RPC client is closed")

/** 尚未由具体 action handler 接管的最小 inbound JSON-RPC API。 */
sealed interface AgentJsonRpcInbound {
    data class Request(val value: JsonRpcRequest) : AgentJsonRpcInbound
    data class Notification(val value: JsonRpcNotification) : AgentJsonRpcInbound
    /** 仅保留可安全关联的 request ID，不保存原始 method、params 或解析错误。 */
    data class InvalidRequest(val id: String) : AgentJsonRpcInbound
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
) {
    private val requestIds = AtomicLong(0)
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val sendJob = SupervisorJob(scope.coroutineContext[Job])
    private val sendScope = CoroutineScope(scope.coroutineContext + sendJob)
    private val closed = AtomicBoolean(false)
    private val cleanupOwner = AtomicReference<CleanupOwner?>(null)
    private val cleanupComplete = CompletableDeferred<Unit>()
    private val mutableInbound = Channel<AgentJsonRpcInbound>(capacity = inboundCapacity)
    private val overloadResponses = Channel<String>(capacity = OVERLOAD_RESPONSE_CAPACITY)
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
    private val readerJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { readIncoming() }

    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        require(inboundCapacity > 0) { "inboundCapacity must be positive" }
    }

    val connectionId: String
        get() = connection.connectionId

    val incoming: ReceiveChannel<AgentJsonRpcInbound> = mutableInbound

    internal val pendingRequestCount: Int
        get() = pendingResponses.size

    suspend fun request(method: ApplicationMethod, params: ApplicationEnvelope): JsonObject {
        ApplicationProtocolValidator.validate(params)
        val id = requestIds.incrementAndGet().toString()
        val response = CompletableDeferred<JsonObject>()
        try {
            val text = ApplicationProtocol.JSON.encodeToString(
                JsonRpcRequest.serializer(),
                JsonRpcRequest(id = id, method = method.wireName, params = params),
            )
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
        val text = ApplicationProtocol.JSON.encodeToString(
            JsonRpcNotification.serializer(),
            JsonRpcNotification(method = method.wireName, params = params),
        )
        ApplicationProtocolValidator.validateEnvelopeSize(text.toByteArray(Charsets.UTF_8))
        checkOpen()
        runSend(text)
    }

    /** 回复服务端发起的双向 JSON-RPC request；发送生命周期与普通 request/notification 共用。 */
    suspend fun respondSuccess(id: String, result: JsonObject) {
        require(id.isNotBlank()) { "JSON-RPC response ID must not be blank" }
        val text = ApplicationProtocol.JSON.encodeToString(
            JsonRpcSuccessResponse.serializer(),
            JsonRpcSuccessResponse(id = id, result = result),
        )
        ApplicationProtocolValidator.validateEnvelopeSize(text.toByteArray(Charsets.UTF_8))
        checkOpen()
        runSend(text)
    }

    /** 只暴露稳定协议错误，不转发底层异常或远端载荷。 */
    suspend fun respondProtocolError(id: String, reason: String = "invalid_request") {
        require(id.isNotBlank()) { "JSON-RPC response ID must not be blank" }
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
                    ?.completeExceptionally(AgentJsonRpcException(response.error.code))
            }

            "method" in value && "id" in value -> runCatching {
                ApplicationProtocol.JSON.decodeFromJsonElement(JsonRpcRequest.serializer(), value)
            }.fold(
                onSuccess = {
                    if (mutableInbound.trySend(AgentJsonRpcInbound.Request(it)).isFailure) enqueueOverload(it.id)
                },
                onFailure = {
                    value["id"]?.let { id ->
                        runCatching { id.jsonPrimitive.content }.getOrNull()?.takeIf(String::isNotBlank)
                            ?.let { id ->
                                if (mutableInbound.trySend(AgentJsonRpcInbound.InvalidRequest(id)).isFailure) enqueueOverload(id)
                            }
                    }
                },
            )

            "method" in value -> runCatching {
                ApplicationProtocol.JSON.decodeFromJsonElement(JsonRpcNotification.serializer(), value)
            }.getOrNull()?.let { mutableInbound.trySend(AgentJsonRpcInbound.Notification(it)) }
        }
    }

    private fun checkOpen() {
        if (closed.get()) throw AgentJsonRpcClosedException()
    }

    private fun failPendingRequests() {
        pendingResponses.values.forEach { it.completeExceptionally(AgentJsonRpcClosedException()) }
        pendingResponses.clear()
    }

    private fun enqueueOverload(id: String) {
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
        } finally {
            cleanupComplete.complete(Unit)
        }
    }

    private class AgentJsonRpcClosedCancellation : CancellationException("Agent JSON-RPC client closed")

    private enum class CleanupOwner { READER, EXPLICIT, OVERLOAD }

    private companion object {
        const val OVERLOAD_RESPONSE_CAPACITY = 8
    }
}
