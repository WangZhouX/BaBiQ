package com.wzx.huitai.agent.conversation

import com.wzx.huitai.agent.client.AgentJsonRpcClient
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * Task 17 JSON-RPC 关联层之上的业务会话薄封装。
 * 它只负责 typed request 与 notification 映射，不执行 application action。
 */
interface BusinessConversationGateway : Closeable {
    val events: Flow<BusinessAgentEvent>
    suspend fun listProviders(): List<BusinessProvider>
    suspend fun createProvider(draft: BusinessProviderDraft): BusinessProvider =
        throw UnsupportedOperationException("Provider create is not supported")
    suspend fun updateProvider(draft: BusinessProviderDraft): BusinessProvider =
        throw UnsupportedOperationException("Provider update is not supported")
    suspend fun deleteProvider(providerId: String): BusinessProviderDeleteResult =
        throw UnsupportedOperationException("Provider delete is not supported")
    suspend fun testProvider(providerId: String): BusinessProviderTestResult =
        throw UnsupportedOperationException("Provider test is not supported")
    suspend fun providerOAuthStatus(): BusinessProviderOAuthStatus =
        throw UnsupportedOperationException("Provider OAuth status is not supported")
    suspend fun loginProviderOAuth(): BusinessProviderOAuthLoginResult =
        throw UnsupportedOperationException("Provider OAuth login is not supported")
    suspend fun setActiveProvider(providerId: String, modelId: String? = null): BusinessProviderSelection
    suspend fun createThread(cwd: String): BusinessThread
    suspend fun startTurn(
        threadId: String,
        text: String,
        providerId: String? = null,
    ): BusinessTurn = startTurn(threadId, text, emptyList(), providerId)
    suspend fun startTurn(
        threadId: String,
        text: String,
        attachments: List<BusinessAttachmentDraft>,
        providerId: String? = null,
    ): BusinessTurn
    suspend fun cancelTurn(turnId: String): Boolean
}

class BusinessAgentClient(
    private val rpc: AgentJsonRpcClient,
    @Suppress("UNUSED_PARAMETER")
    scope: CoroutineScope,
) : BusinessConversationGateway {
    override val events: Flow<BusinessAgentEvent> = rpc.rawNotifications.receiveAsFlow().map { notification ->
        runCatching { BusinessAgentEventCodec.decode(notification) }
            .getOrElse { BusinessAgentEvent.Unknown(notification.method) }
    }

    override suspend fun listProviders(): List<BusinessProvider> =
        BusinessProviderCodec.decodeList(rpc.request("provider/list", buildJsonObject { }))

    override suspend fun createProvider(draft: BusinessProviderDraft): BusinessProvider =
        BusinessProviderCodec.decodeProvider(rpc.request("provider/create", draft.toRequestParams()))

    override suspend fun updateProvider(draft: BusinessProviderDraft): BusinessProvider =
        BusinessProviderCodec.decodeProvider(rpc.request("provider/update", draft.toRequestParams()))

    override suspend fun deleteProvider(providerId: String): BusinessProviderDeleteResult {
        requireProviderId(providerId)
        return BusinessProviderCodec.decodeDeleteResult(rpc.request("provider/delete", providerIdParams(providerId)))
    }

    override suspend fun testProvider(providerId: String): BusinessProviderTestResult {
        requireProviderId(providerId)
        return BusinessProviderCodec.decodeTestResult(rpc.request("provider/test", providerIdParams(providerId)))
    }

    override suspend fun providerOAuthStatus(): BusinessProviderOAuthStatus {
        return BusinessProviderCodec.decodeOAuthStatus(
            rpc.request("provider/oauth/status", buildJsonObject { }),
        )
    }

    override suspend fun loginProviderOAuth(): BusinessProviderOAuthLoginResult {
        return BusinessProviderCodec.decodeOAuthLoginResult(
            rpc.request("provider/oauth/login", buildJsonObject { }),
        )
    }

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
        attachments: List<BusinessAttachmentDraft>,
        providerId: String?,
    ): BusinessTurn {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        require(text.isNotBlank() || attachments.isNotEmpty()) {
            "turn text and attachments must not both be blank"
        }
        val result = rpc.request("turn/start", buildJsonObject {
            put("threadId", threadId)
            put("input", buildJsonObject {
                put("text", text)
                put("attachments", buildJsonArray {
                    attachments.forEach { attachment ->
                        add(buildJsonObject {
                            put("id", attachment.id)
                            put("displayId", attachment.displayId)
                            put("name", attachment.name)
                            put("localPath", attachment.localPath)
                        })
                    }
                })
            })
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
        // Event collection is owned and canceled by BusinessConversationController.
        // The shared JSON-RPC lifecycle remains owned by its connection composition.
    }

    private fun BusinessProviderDraft.toRequestParams(): JsonObject {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(type.isNotBlank()) { "type must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(contextWindow >= 0) { "contextWindow must not be negative" }
        require(enabled) { "enabled must be true" }
        return buildJsonObject {
            put("providerId", providerId)
            put("displayName", displayName)
            put("type", type)
            put("authMode", authMode)
            put("baseUrl", baseUrl)
            put("model", model)
            apiKey?.let { put("apiKey", it) }
            put("contextWindow", contextWindow)
            put("enabled", enabled)
        }
    }

    private fun providerIdParams(providerId: String): JsonObject =
        buildJsonObject { put("providerId", providerId) }

    private fun requireProviderId(providerId: String) {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
    }
}
