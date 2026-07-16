package com.wzx.huitai.agent.application

import com.wzx.huitai.action.model.ActionError
import com.wzx.huitai.action.model.ActionExecutionState
import com.wzx.huitai.action.model.ActionIdentityScope
import com.wzx.huitai.action.model.ActionResult
import com.wzx.huitai.action.port.ActionExecutionRecord
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.protocol.ActionEnvelope
import com.wzx.huitai.agent.protocol.ApplicationMethod
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.ApplicationProtocolValidator
import com.wzx.huitai.agent.protocol.CommonApplicationFields
import java.time.Instant
import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 当前认证身份的可信本地投影；权限从认证状态注入，绝不从动作 payload 读取。 */
data class TrustedApplicationIdentity(
    val scope: ActionIdentityScope,
    val permissions: Set<String>,
)

/** 动作协议关联字段在整个 execution 生命周期中保持不变。 */
data class ApplicationActionCorrelation(
    val threadId: String,
    val turnId: String,
    val toolCallId: String,
    val executionId: String,
)

/** 单次 request 接收时冻结的关联与身份，后续身份切换不得改写旧 execution 的出站范围。 */
data class ApplicationActionPublicationContext(
    val correlation: ApplicationActionCorrelation,
    val identity: TrustedApplicationIdentity,
)

/** 从持久化执行事实投影动作进度和终态通知。 */
class ApplicationActionStatusClient(
    private val rpc: AgentJsonRpcClient,
    private val nextSequence: () -> Long,
    private val now: () -> Instant,
    private val publicationCapacity: Int = 4_096,
) {
    internal val connectionId: String get() = rpc.connectionId
    private val publicationLock = Any()
    private val published = object : LinkedHashMap<PublicationKey, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PublicationKey, Unit>?): Boolean =
            size > publicationCapacity
    }
    private val lifecycleMonitor = Any()
    private var attached = true

    init {
        require(publicationCapacity > 0) { "publicationCapacity must be positive" }
    }

    internal val publishedCount: Int get() = synchronized(publicationLock) { published.size }

    internal fun <T> ifAttached(block: () -> T): T? = synchronized(lifecycleMonitor) {
        if (attached) block() else null
    }

    internal fun <T> detach(block: () -> T): T? = synchronized(lifecycleMonitor) {
        if (!attached) return@synchronized null
        attached = false
        block()
    }

    suspend fun accepted(context: ApplicationActionPublicationContext, actionId: String) {
        publishOnce(context, ApplicationMethod.ACTION_ACCEPTED, buildJsonObject {
            put("actionId", actionId)
            put("state", "accepted")
        })
    }

    suspend fun publish(
        context: ApplicationActionPublicationContext,
        record: ActionExecutionRecord,
        rejection: ActionError? = null,
    ) {
        val method = rejection?.let { ApplicationMethod.ACTION_REJECTED } ?: record.state.toMethodOrNull() ?: return
        val payload = record.toProtocolPayload(rejection)
        publishOnce(context, method, payload)
    }

    suspend fun rejected(context: ApplicationActionPublicationContext, actionId: String, error: ActionError) {
        publishOnce(context, ApplicationMethod.ACTION_REJECTED, buildJsonObject {
            put("actionId", actionId)
            put("state", "rejected")
            put("errorCode", error.code.name.lowercase())
        })
    }

    private suspend fun publishOnce(
        context: ApplicationActionPublicationContext,
        method: ApplicationMethod,
        payload: JsonObject,
    ) {
        val key = PublicationKey(
            executionId = context.correlation.executionId,
            identityScope = context.identity.scope,
            method = method,
        )
        val inserted = synchronized(publicationLock) {
            if (published.containsKey(key)) {
                published[key]
                false
            } else {
                published[key] = Unit
                true
            }
        }
        if (!inserted) return
        try {
            rpc.notify(method, envelope(context, payload))
        } catch (failure: Throwable) {
            synchronized(publicationLock) { published.remove(key) }
            throw failure
        }
    }

    private fun envelope(context: ApplicationActionPublicationContext, payload: JsonObject): ActionEnvelope {
        val identity = context.identity.scope
        val correlation = context.correlation
        return ActionEnvelope(
            common = CommonApplicationFields(
                protocolVersion = ApplicationProtocol.PROTOCOL_VERSION,
                desktopInstanceId = identity.desktopInstanceId,
                desktopSessionId = identity.desktopSessionId,
                authSessionId = identity.authSessionId,
                identityEpoch = identity.identityEpoch,
                sequence = nextSequence(),
                generatedAt = now().toString(),
                userId = identity.userId,
                tenantId = identity.tenantId,
                platformId = identity.platformId,
            ),
            threadId = correlation.threadId,
            turnId = correlation.turnId,
            toolCallId = correlation.toolCallId,
            executionId = correlation.executionId,
            payload = payload,
        )
    }

    private data class PublicationKey(
        val executionId: String,
        val identityScope: ActionIdentityScope,
        val method: ApplicationMethod,
    )
}

internal fun ActionExecutionRecord.toProtocolPayload(rejection: ActionError? = null): JsonObject = buildJsonObject {
    put("actionId", command.actionId)
    put("state", if (rejection != null) "rejected" else state.wireName())
    val terminal = result
    when {
        rejection != null -> put("errorCode", rejection.code.name.lowercase())
        terminal is ActionResult.Success -> terminal.redactedOutput?.let { put("output", it) }
        terminal is ActionResult.Failure -> put("errorCode", terminal.error.code.name.lowercase())
        terminal is ActionResult.OutcomeUnknown -> put("errorCode", terminal.error.code.name.lowercase())
    }
}

internal fun ActionExecutionState.wireName(): String = when (this) {
    ActionExecutionState.RECEIVED -> "received"
    ActionExecutionState.VALIDATING -> "validating"
    ActionExecutionState.PREVIEWED -> "previewed"
    ActionExecutionState.WAITING_APPROVAL -> "waiting_approval"
    ActionExecutionState.EXECUTING -> "executing"
    ActionExecutionState.SUCCEEDED -> "succeeded"
    ActionExecutionState.FAILED -> "failed"
    ActionExecutionState.CANCELED -> "canceled"
    ActionExecutionState.EXPIRED -> "expired"
    ActionExecutionState.OUTCOME_UNKNOWN -> "outcome_unknown"
}

private fun ActionExecutionState.toMethodOrNull(): ApplicationMethod? = when (this) {
    ActionExecutionState.PREVIEWED -> ApplicationMethod.ACTION_PREVIEWED
    ActionExecutionState.WAITING_APPROVAL -> ApplicationMethod.ACTION_APPROVAL_REQUIRED
    ActionExecutionState.EXECUTING -> ApplicationMethod.ACTION_RUNNING
    ActionExecutionState.SUCCEEDED -> ApplicationMethod.ACTION_COMPLETED
    ActionExecutionState.FAILED -> ApplicationMethod.ACTION_FAILED
    ActionExecutionState.CANCELED -> ApplicationMethod.ACTION_CANCELED
    ActionExecutionState.EXPIRED -> ApplicationMethod.ACTION_EXPIRED
    ActionExecutionState.OUTCOME_UNKNOWN -> ApplicationMethod.ACTION_OUTCOME_UNKNOWN
    ActionExecutionState.RECEIVED,
    ActionExecutionState.VALIDATING,
    -> null
}
