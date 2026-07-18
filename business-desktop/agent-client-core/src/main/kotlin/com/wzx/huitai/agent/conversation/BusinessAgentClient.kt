package com.wzx.huitai.agent.conversation

import com.wzx.huitai.agent.client.AgentJsonRpcClient
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Task 17 JSON-RPC 关联层之上的业务会话薄封装。
 * 它只负责 typed request 与 notification 映射，不执行 application action。
 */
interface BusinessConversationGateway : Closeable {
    val events: Flow<BusinessAgentEvent>
    suspend fun listProviders(): List<BusinessProvider>
    suspend fun setActiveProvider(providerId: String, modelId: String? = null): BusinessProviderSelection
    suspend fun createThread(cwd: String): BusinessThread
    suspend fun startTurn(threadId: String, text: String, providerId: String? = null): BusinessTurn
    suspend fun cancelTurn(turnId: String): Boolean
}

class BusinessAgentClient(
    private val rpc: AgentJsonRpcClient,
    scope: CoroutineScope,
) : BusinessConversationGateway {
    private val mutableEvents = Channel<BusinessAgentEvent>(capacity = 64)
    private val collector: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            for (notification in rpc.rawNotifications) {
                runCatching { BusinessAgentEventCodec.decode(notification) }
                    .getOrElse { BusinessAgentEvent.Unknown(notification.method) }
                    .let { mutableEvents.send(it) }
            }
        } finally {
            mutableEvents.close()
        }
    }

    override val events: Flow<BusinessAgentEvent> = mutableEvents.receiveAsFlow()

    override suspend fun listProviders(): List<BusinessProvider> =
        BusinessProviderCodec.decodeList(rpc.request("provider/list", buildJsonObject { }))

    override suspend fun setActiveProvider(providerId: String, modelId: String?): BusinessProviderSelection {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        val result = rpc.request("provider/set-active", buildJsonObject {
            put("providerId", providerId)
            modelId?.takeIf(String::isNotBlank)?.let { put("modelId", it) }
        })
        if (result["ok"]?.jsonPrimitive?.content != "true") {
            throw SerializationException("Provider selection was not accepted")
        }
        return BusinessProviderSelection(
            providerId = result.requiredText("providerId"),
            modelId = result.requiredText("modelId"),
        )
    }

    override suspend fun createThread(cwd: String): BusinessThread {
        require(cwd.isNotBlank()) { "cwd must not be blank" }
        val result = rpc.request("thread/create", buildJsonObject { put("cwd", cwd) })
        return BusinessThread(
            id = result.requiredText("threadId"),
            title = result.requiredText("title"),
            cwd = result.requiredText("cwd"),
        )
    }

    override suspend fun startTurn(
        threadId: String,
        text: String,
        providerId: String?,
    ): BusinessTurn {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        require(text.isNotBlank()) { "turn text must not be blank" }
        val result = rpc.request("turn/start", buildJsonObject {
            put("threadId", threadId)
            put("input", buildJsonObject { put("text", text) })
            providerId?.takeIf(String::isNotBlank)?.let { put("providerId", it) }
        })
        return BusinessTurn(result.requiredText("turnId"), threadId)
    }

    override suspend fun cancelTurn(turnId: String): Boolean {
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        val result = rpc.request("turn/cancel", buildJsonObject { put("turnId", turnId) })
        return result["ok"]?.jsonPrimitive?.content == "true"
    }

    override fun close() {
        collector.cancel()
    }
}
