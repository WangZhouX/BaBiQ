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

/**
 * UI 只依赖这个网关接口，不直接依赖 Ktor 或 JSON-RPC 编解码。
 *
 * 这是 Kotlin/Compose 项目里常用的“端口-适配器”写法：Controller 面向接口编程，
 * 单元测试可以注入 FakeGateway，真实运行时再注入 AgentClient + KtorAgentTransport。
 */
interface AgentGateway {
	/** 后端 notification 事件流，例如 item/added、turn/completed、approval/request。 */
	val events: Flow<ServerEvent>

	/** 建立连接，并开始监听后端事件。 */
	suspend fun connect()

	/** 创建一个与 cwd 绑定的后端 Thread，后续 turn 都在这个工作目录里执行。 */
	suspend fun createThread(cwd: String): String

	/** 启动一轮对话；providerId 为空时由后端使用当前激活模型。 */
	suspend fun startTurn(threadId: String, prompt: String, providerId: String? = null): String

	/** 回传工具审批结果，editedArgs 只在“修改后批准”时携带。 */
	suspend fun respondApproval(threadId: String, turnId: String, decision: String, editedArgs: String? = null): Boolean

	/** 读取后端当前可用 Provider/Model 列表。 */
	suspend fun listProviders(): ProviderListResult

	/** 设置后端当前激活 Provider/Model，下一轮 turn 生效。 */
	suspend fun setActiveProvider(providerId: String, modelId: String? = null): Boolean
}

/**
 * AgentClient 负责把类型安全的方法调用翻译成 JSON-RPC 2.0 报文。
 *
 * pending 表保存“请求 id -> 等待中的响应”，后端返回同一个 id 时再唤醒对应协程；
 * 不带 id 的 notification 则进入 events 流，由 ChatController 交给 reducer 更新界面。
 */
class AgentClient(
	private val transport: AgentTransport,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
	private val config: DesktopConfig = DesktopConfig(),
) : AgentGateway, AutoCloseable {
	// JSON-RPC request id 单调递增，保证同一连接里的响应能和请求配对。
	private val nextId = AtomicLong(1)

	// pending 保存“已经发出去但还没收到 response”的请求。
	private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()

	// 后端 notification 没有 id，不能唤醒 pending，只能作为事件广播给状态层。
	private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 128)

	// 防止重复 connect() 时启动多个 collector，导致同一后端事件被消费多次。
	private var collecting = false

	override val events: Flow<ServerEvent> = _events

	/**
	 * 连接 WebSocket 并启动后端消息收集。
	 */
	override suspend fun connect() {
		transport.connect()
		if (!collecting) {
			collecting = true
			// 使用 UNDISPATCHED 让 collector 在 connect() 返回前就开始订阅。
			// 这能避免测试或高速后端“先发响应、后启动 collector”造成的竞态。
			scope.launch(start = CoroutineStart.UNDISPATCHED) {
				transport.incoming.collect(::handleIncoming)
			}
		}
	}

	/**
	 * 调用后端 `thread/create`，把桌面端选中的 cwd 固化到后端 Thread。
	 */
	override suspend fun createThread(cwd: String): String {
		val response = request(
			method = "thread/create",
			params = buildJsonObject { put("cwd", cwd) },
		)
		return response.requireResult().jsonObject.requiredText("threadId")
	}

	/**
	 * 调用后端 `turn/start`。
	 *
	 * 注意 providerId 是可选的：为空时后端使用当前 active provider；不为空时本轮显式指定。
	 */
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

	/**
	 * 调用后端 `approval/respond`，让 Human-in-the-loop 暂停点继续运行。
	 */
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

	/**
	 * 拉取后端模型列表，供 UI 下拉框渲染。
	 */
	override suspend fun listProviders(): ProviderListResult {
		val response = request("model/providers/list", buildJsonObject {})
		return protocolJson.decodeFromJsonElement(ProviderListResult.serializer(), response.requireResult())
	}

	/**
	 * 切换后端 active provider/model。
	 */
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

	/**
	 * 中断正在等待审批的 turn。当前 UI 还没有暴露按钮，但协议客户端先保留能力。
	 */
	suspend fun interruptTurn(turnId: String): Boolean {
		val response = request("turn/interrupt", buildJsonObject { put("turnId", turnId) })
		return response.requireResult().jsonObject["accepted"]?.jsonPrimitive?.booleanOrNull ?: true
	}

	/**
	 * 取消正在运行的 turn。当前 UI 还没有暴露按钮，但协议客户端先保留能力。
	 */
	suspend fun cancelTurn(turnId: String): Boolean {
		val response = request("turn/cancel", buildJsonObject { put("turnId", turnId) })
		return response.requireResult().jsonObject["ok"]?.jsonPrimitive?.booleanOrNull ?: true
	}

	/**
	 * 释放底层 WebSocket/HTTP client 资源。
	 */
	override fun close() {
		transport.close()
	}

	/**
	 * 发送一个 JSON-RPC request，并等待同 id 的 response。
	 */
	private suspend fun request(method: String, params: JsonElement): JsonRpcResponse {
		val id = nextId.getAndIncrement()
		val deferred = CompletableDeferred<JsonRpcResponse>()
		pending[id] = deferred
		val request = JsonRpcRequest(id = id, method = method, params = params)
		// 先注册 pending 再发送，确保响应即使立刻回来也能找到等待者。
		transport.send(protocolJson.encodeToString(request))
		val response = withTimeout(config.requestTimeout) { deferred.await() }
		// 后端返回 JSON-RPC error 时，转成普通异常，Controller 可以统一展示错误提示。
		response.error?.let { error ->
			throw AgentClientException(error.code, error.message)
		}
		return response
	}

	/**
	 * 处理后端发来的一个原始 JSON 文本帧。
	 */
	private suspend fun handleIncoming(text: String) {
		val root = protocolJson.parseToJsonElement(text).jsonObject
		val id = root["id"]?.jsonPrimitive?.content?.toLongOrNull()
		if (id != null && ("result" in root || "error" in root)) {
			// JSON-RPC response：交还给发起 request 的协程。
			val response = protocolJson.decodeFromString(JsonRpcResponse.serializer(), text)
			pending.remove(id)?.complete(response)
			return
		}

		if ("method" in root) {
			// JSON-RPC notification：没有 request/response 配对，直接作为后端事件广播。
			_events.emit(protocolJson.decodeFromString(ServerEvent.serializer(), text))
		}
	}
}

/**
 * JSON-RPC error 在桌面端的异常表示。
 */
class AgentClientException(
	val code: Int,
	override val message: String,
) : RuntimeException(message)

/**
 * 从 JSON object 中读取必填字符串字段；缺字段说明后端响应不满足协议。
 */
private fun kotlinx.serialization.json.JsonObject.requiredText(name: String): String =
	this[name]?.jsonPrimitive?.content ?: error("JSON-RPC result 缺少字段: $name")
