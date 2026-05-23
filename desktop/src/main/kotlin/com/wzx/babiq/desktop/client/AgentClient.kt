package com.wzx.babiq.desktop.client

import com.wzx.babiq.desktop.app.DesktopConfig
import com.wzx.babiq.desktop.protocol.ApprovalRespondParams
import com.wzx.babiq.desktop.protocol.JsonRpcRequest
import com.wzx.babiq.desktop.protocol.JsonRpcResponse
import com.wzx.babiq.desktop.protocol.ProviderListResult
import com.wzx.babiq.desktop.protocol.ServerEvent
import com.wzx.babiq.desktop.protocol.SetActiveProviderParams
import com.wzx.babiq.desktop.protocol.protocolJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface AgentGateway {
	val events: Flow<ServerEvent>

	suspend fun connect()
	suspend fun createThread(cwd: String): String
	suspend fun startTurn(threadId: String, prompt: String, providerId: String? = null): String
	suspend fun respondApproval(threadId: String, turnId: String, decision: String, editedArgs: String? = null): Boolean
	suspend fun listProviders(): ProviderListResult
	suspend fun setActiveProvider(providerId: String, modelId: String? = null): Boolean
}

class AgentClient(
	private val transport: AgentTransport,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
	private val config: DesktopConfig = DesktopConfig(),
) : AgentGateway, AutoCloseable {
	private val nextId = AtomicLong(1)
	private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()
	private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 128)
	private var collecting = false

	override val events: Flow<ServerEvent> = _events

	override suspend fun connect() {
		transport.connect()
		if (!collecting) {
			collecting = true
			scope.launch(start = CoroutineStart.UNDISPATCHED) {
				transport.incoming.collect(::handleIncoming)
			}
		}
	}

	override suspend fun createThread(cwd: String): String {
		val response = request(
			method = "thread/create",
			params = buildJsonObject { put("cwd", cwd) },
		)
		return response.requireResult().jsonObject.requiredText("threadId")
	}

	override suspend fun startTurn(threadId: String, prompt: String, providerId: String?): String {
		val params = buildJsonObject {
			put("threadId", threadId)
			put("input", buildJsonObject { put("text", prompt) })
			if (!providerId.isNullOrBlank()) {
				put("providerId", providerId)
			}
		}
		val response = request("turn/start", params)
		return response.requireResult().jsonObject.requiredText("turnId")
	}

	override suspend fun respondApproval(
		threadId: String,
		turnId: String,
		decision: String,
		editedArgs: String?,
	): Boolean {
		val response = request(
			method = "approval/respond",
			params = protocolJson.encodeToJsonElement(
				ApprovalRespondParams.serializer(),
				ApprovalRespondParams(threadId, turnId, decision, editedArgs),
			),
		)
		val result = response.requireResult().jsonObject
		return result["delivered"]?.jsonPrimitive?.booleanOrNull
			?: result["ok"]?.jsonPrimitive?.booleanOrNull
			?: true
	}

	override suspend fun listProviders(): ProviderListResult {
		val response = request("model/providers/list", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(ProviderListResult.serializer(), response.requireResult())
	}

	override suspend fun setActiveProvider(providerId: String, modelId: String?): Boolean {
		val response = request(
			method = "model/providers/set-active",
			params = protocolJson.encodeToJsonElement(
				SetActiveProviderParams.serializer(),
				SetActiveProviderParams(providerId, modelId),
			),
		)
		return response.requireResult().jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: true
	}

	suspend fun interruptTurn(turnId: String): Boolean {
		val response = request("turn/interrupt", buildJsonObject { put("turnId", turnId) })
		return response.requireResult().jsonObject["accepted"]?.jsonPrimitive?.booleanOrNull ?: true
	}

	suspend fun cancelTurn(turnId: String): Boolean {
		val response = request("turn/cancel", buildJsonObject { put("turnId", turnId) })
		return response.requireResult().jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: true
	}

	override fun close() {
		transport.close()
	}

	private suspend fun request(method: String, params: JsonElement): JsonRpcResponse {
		val id = nextId.getAndIncrement()
		val deferred = CompletableDeferred<JsonRpcResponse>()
		pending[id] = deferred
		val request = JsonRpcRequest(id = id, method = method, params = params)
		transport.send(protocolJson.encodeToString(request))
		val response = withTimeout(config.requestTimeout) { deferred.await() }
		response.error?.let { error ->
			throw AgentClientException(error.code, error.message)
		}
		return response
	}

	private suspend fun handleIncoming(text: String) {
		val root = protocolJson.parseToJsonElement(text).jsonObject
		val id = root["id"]?.jsonPrimitive?.content?.toLongOrNull()
		if (id != null && ("result" in root || "error" in root)) {
			val response = protocolJson.decodeFromString(JsonRpcResponse.serializer(), text)
			pending.remove(id)?.complete(response)
			return
		}

		if ("method" in root) {
			_events.emit(protocolJson.decodeFromString(ServerEvent.serializer(), text))
		}
	}
}

class AgentClientException(
	val code: Int,
	override val message: String,
) : RuntimeException(message)

private fun kotlinx.serialization.json.JsonObject.requiredText(name: String): String =
	this[name]?.jsonPrimitive?.content ?: error("JSON-RPC result 缺少字段: $name")
