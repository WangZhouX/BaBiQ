package com.wzx.huitai.agent.application

import com.wzx.huitai.action.ActionBusResult
import com.wzx.huitai.action.ActionContext
import com.wzx.huitai.action.ApplicationActionBus
import com.wzx.huitai.action.model.ActionCommand
import com.wzx.huitai.action.model.ActionErrorCode
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.action.port.ActionExecutionStore
import com.wzx.huitai.action.port.ScopedActionExecutionQuery
import com.wzx.huitai.agent.client.AgentJsonRpcInbound
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.protocol.ActionEnvelope
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidator
import com.wzx.huitai.agent.protocol.JsonRpcRequest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** 可替换的最小执行端口，测试无需代理 final 的 [ApplicationActionBus]。 */
fun interface ApplicationActionExecutor {
    suspend fun execute(command: ActionCommand, context: ActionContext): ActionBusResult

    suspend fun execute(
        command: ActionCommand,
        context: ActionContext,
        progress: suspend (ActionResult<*>) -> Unit,
    ): ActionBusResult = execute(command, context)
}

class DirectApplicationActionExecutor(private val bus: ApplicationActionBus) : ApplicationActionExecutor {
    override suspend fun execute(command: ActionCommand, context: ActionContext) = bus.execute(command, context)
    override suspend fun execute(
        command: ActionCommand,
        context: ActionContext,
        progress: suspend (ActionResult<*>) -> Unit,
    ) = bus.execute(command, context, progress)
}

/** 接管服务端动作 request/cancel/status/result 请求，并拥有其启动的执行 Job。 */
class ApplicationActionRequestHandler(
    private val rpc: AgentJsonRpcClient,
    private val runtime: ApplicationActionExecutionRuntime,
    private val trustedIdentity: () -> TrustedApplicationIdentity,
    private val nextSequence: () -> Long,
    private val now: () -> Instant,
    scope: CoroutineScope,
    private val statusClient: ApplicationActionStatusClient = ApplicationActionStatusClient(
        rpc,
        nextSequence,
        now,
    ),
    private val ownsRuntime: Boolean = false,
) {
    private val connectionJob = SupervisorJob(scope.coroutineContext[Job])
    private val connectionScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + connectionJob)
    private val reader = connectionScope.launch { consumeIncoming() }

    constructor(
        rpc: AgentJsonRpcClient,
        executor: ApplicationActionExecutor,
        executionStore: ActionExecutionStore,
        scopedQuery: ScopedActionExecutionQuery,
        trustedIdentity: () -> TrustedApplicationIdentity,
        nextSequence: () -> Long,
        now: () -> Instant,
        scope: CoroutineScope,
        statusClient: ApplicationActionStatusClient = ApplicationActionStatusClient(rpc, nextSequence, now),
        statusPollMillis: Long = 50,
        cleanupTimeoutMillis: Long = 2_000,
        completedCapacity: Int = 1_024,
    ) : this(
        rpc = rpc,
        runtime = ApplicationActionExecutionRuntime(
            executor,
            executionStore,
            scopedQuery,
            scope,
            statusPollMillis,
            cleanupTimeoutMillis,
            completedCapacity,
        ),
        trustedIdentity = trustedIdentity,
        nextSequence = nextSequence,
        now = now,
        scope = scope,
        statusClient = statusClient,
        ownsRuntime = true,
    )

    internal val activeExecutionCount: Int get() = runtime.activeExecutionCount
    internal val completedExecutionCount: Int get() = runtime.completedExecutionCount

    suspend fun close() {
        closeConnection()
        if (ownsRuntime) runtime.close()
    }

    suspend fun closeConnection() {
        connectionJob.cancel()
        withContext(NonCancellable) { runtime.onConnectionLost(statusClient) }
    }

    private suspend fun consumeIncoming() {
        try {
            for (incoming in rpc.incoming) {
                try {
                    when (incoming) {
                        is AgentJsonRpcInbound.Request -> handle(incoming.value)
                        is AgentJsonRpcInbound.Notification -> handle(incoming.value)
                        is AgentJsonRpcInbound.InvalidRequest -> rpc.respondProtocolError(incoming.id)
                    }
                } catch (cancellation: CancellationException) {
                    if (connectionJob.isCancelled) throw cancellation
                } catch (_: Exception) {
                    // 单条协议或存储失败不得终止连接 reader 或取消其他 execution。
                }
            }
        } finally {
            withContext(NonCancellable) { runtime.onConnectionLost(statusClient) }
        }
    }

    private suspend fun handle(request: JsonRpcRequest) {
        val envelope = request.params as? ActionEnvelope
        if (envelope == null) {
            rpc.respondProtocolError(request.id)
            return
        }
        when (request.method) {
            ApplicationMethod.ACTION_REQUEST.wireName -> {
                val identity = trustedIdentity()
                if (!isTrusted(envelope, identity.scope)) rpc.respondProtocolError(request.id, PROTOCOL_ERROR_REASON)
                else handleStart(request.id, envelope, identity)
            }
            ApplicationMethod.ACTION_CANCEL.wireName -> handleCancel(request.id, envelope)
            ApplicationMethod.ACTION_STATUS.wireName -> handleStatus(request.id, envelope)
            ApplicationMethod.ACTION_RESULT_GET.wireName -> handleResult(request.id, envelope)
            else -> rpc.respondProtocolError(request.id)
        }
    }

    private suspend fun handle(notification: com.wzx.huitai.agent.protocol.JsonRpcNotification) {
        val envelope = notification.params as? ActionEnvelope ?: return
        if (notification.method != ApplicationMethod.ACTION_CANCEL.wireName) return
        handleCancel(envelope)
    }

    private suspend fun handleStart(
        requestId: Long,
        envelope: ActionEnvelope,
        identity: TrustedApplicationIdentity,
    ) {
        val decoded = runCatching { decode(envelope, identity) }.getOrNull()
        if (decoded == null) {
            rpc.respondProtocolError(requestId)
            return
        }
        val correlation = envelope.correlation()
        val publication = ApplicationActionPublicationContext(
            correlation,
            TrustedApplicationIdentity(decoded.first.identityScope, decoded.second.permissions),
        )
        val candidate = RuntimeOwnedExecution(publication, decoded.first, decoded.second)
        when (runtime.start(candidate, statusClient)) {
            RuntimeStartResult.Conflict -> {
                rpc.respondProtocolError(requestId)
                return
            }
            RuntimeStartResult.Accepted,
            RuntimeStartResult.Acknowledged,
            -> Unit
        }
        try {
            rpc.respondSuccess(requestId, ack(envelope.executionId))
        } catch (cancellation: CancellationException) {
            if (!connectionJob.isCancelled) throw cancellation
        } catch (_: Exception) {
            // accepted 已转移业务责任；response 丢失不能撤销执行或终止唯一 reader。
        }
    }

    private suspend fun handleCancel(requestId: Long, envelope: ActionEnvelope) {
        val requestedScope = requestedScopeOrNull(envelope) ?: run {
            rpc.respondProtocolError(requestId, PROTOCOL_ERROR_REASON)
            return
        }
        if (!runtime.cancel(RuntimeExecutionKey(envelope.executionId, requestedScope), envelope.correlation())) {
            rpc.respondProtocolError(requestId, PROTOCOL_ERROR_REASON)
            return
        }
        rpc.respondSuccess(requestId, ack(envelope.executionId))
    }

    private suspend fun handleCancel(envelope: ActionEnvelope) {
        val requestedScope = requestedScopeOrNull(envelope) ?: return
        runtime.cancel(RuntimeExecutionKey(envelope.executionId, requestedScope), envelope.correlation())
    }

    private suspend fun handleStatus(requestId: Long, envelope: ActionEnvelope) {
        val requestedScope = requestedScopeOrNull(envelope)
        val record = requestedScope?.let { runtime.find(envelope.executionId, it) }
        if (record == null) {
            rpc.respondProtocolError(requestId, PROTOCOL_ERROR_REASON)
            return
        }
        rpc.respondSuccess(requestId, buildJsonObject {
            put("executionId", record.command.executionId)
            put("state", record.state.wireName())
        })
    }

    private suspend fun handleResult(requestId: Long, envelope: ActionEnvelope) {
        val requestedScope = requestedScopeOrNull(envelope)
        val record = requestedScope?.let { runtime.find(envelope.executionId, it) }
        if (record == null || !record.isTerminal) {
            rpc.respondProtocolError(requestId, PROTOCOL_ERROR_REASON)
            return
        }
        val result = record.toQueryResult()
        ApplicationProtocolValidator.validateActionResultSize(
            ApplicationProtocol.JSON.encodeToString(JsonObject.serializer(), result).toByteArray(Charsets.UTF_8),
        )
        rpc.respondSuccess(requestId, result)
    }

    private fun decode(
        envelope: ActionEnvelope,
        identity: TrustedApplicationIdentity,
    ): Pair<ActionCommand, ActionContext> {
        ApplicationProtocolValidator.validate(envelope)
        val payload = envelope.payload
        val input = payload.getValue("input").jsonObject
        ApplicationProtocolValidator.validateActionInputSize(
            ApplicationProtocol.JSON.encodeToString(JsonObject.serializer(), input).toByteArray(Charsets.UTF_8),
        )
        validateOptionalPayloadIdentity(payload, identity.scope)
        val command = ActionCommand(
            executionId = envelope.executionId,
            actionId = payload.getValue("actionId").jsonPrimitive.content,
            actionVersion = payload.getValue("actionVersion").jsonPrimitive.int,
            input = input,
            origin = ActionOrigin.AGENT,
            identityScope = identity.scope,
            pageId = payload.getValue("pageId").jsonPrimitive.content,
            contextRevision = payload.getValue("contextRevision").jsonPrimitive.long,
        )
        return command to ActionContext(identity.scope, command.pageId, command.contextRevision, identity.permissions.toSet())
    }

    private fun isTrusted(envelope: ActionEnvelope, trusted: ActionIdentityScope): Boolean {
        val common = envelope.common
        return common.protocolVersion == ApplicationProtocol.PROTOCOL_VERSION &&
            common.desktopInstanceId == trusted.desktopInstanceId &&
            common.desktopSessionId == trusted.desktopSessionId &&
            common.authSessionId == trusted.authSessionId &&
            common.identityEpoch == trusted.identityEpoch &&
            common.userId == trusted.userId && common.tenantId == trusted.tenantId && common.platformId == trusted.platformId
    }

    private fun requestedScopeOrNull(envelope: ActionEnvelope): ActionIdentityScope? = runCatching {
        ApplicationProtocolValidator.validate(envelope)
        val common = envelope.common
        ActionIdentityScope(
            desktopInstanceId = common.desktopInstanceId,
            desktopSessionId = common.desktopSessionId,
            authSessionId = requireNotNull(common.authSessionId),
            identityEpoch = common.identityEpoch,
            userId = requireNotNull(common.userId),
            tenantId = requireNotNull(common.tenantId),
            platformId = requireNotNull(common.platformId),
        )
    }.getOrNull()

    private fun validateOptionalPayloadIdentity(payload: JsonObject, trusted: ActionIdentityScope) {
        val expected = mapOf(
            "desktopInstanceId" to trusted.desktopInstanceId,
            "desktopSessionId" to trusted.desktopSessionId,
            "authSessionId" to trusted.authSessionId,
            "userId" to trusted.userId,
            "tenantId" to trusted.tenantId,
            "platformId" to trusted.platformId,
        )
        if (expected.any { (key, value) -> payload[key]?.jsonPrimitive?.content?.let { it != value } == true } ||
            payload["identityEpoch"]?.jsonPrimitive?.long?.let { it != trusted.identityEpoch } == true
        ) throw SerializationException("Untrusted payload identity")
    }

    private companion object {
        const val PROTOCOL_ERROR_REASON = "identity_scope_invalid"
    }
}

private fun ActionEnvelope.correlation() = ApplicationActionCorrelation(threadId, turnId, toolCallId, executionId)
private fun ack(executionId: String) = buildJsonObject { put("executionId", executionId); put("accepted", true) }

private fun ActionExecutionRecord.toQueryResult(): JsonObject {
    val payload = toProtocolPayload()
    return buildJsonObject {
        put("executionId", command.executionId)
        put("state", state.wireName())
        payload["output"]?.let { put("output", it) }
        payload["errorCode"]?.let { put("errorCode", it) }
    }
}
