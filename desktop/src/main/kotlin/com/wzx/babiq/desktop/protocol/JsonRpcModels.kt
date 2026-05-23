package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 桌面端发给后端的 JSON-RPC request。
 *
 * @property jsonrpc JSON-RPC 协议版本，固定为 2.0。
 * @property id 请求 id，后端响应会带回同一个 id。
 * @property method 后端方法名，例如 turn/start。
 * @property params 方法参数 JSON。
 */
@Serializable
data class JsonRpcRequest(
	val jsonrpc: String = "2.0",
	val id: Long,
	val method: String,
	val params: JsonElement = JsonObject(emptyMap()),
)

/**
 * JSON-RPC 标准 error 对象。
 *
 * @property code 标准或业务错误码。
 * @property message 给调用方展示或记录的错误消息。
 * @property data 可选错误详情，通常用于调试。
 */
@Serializable
data class JsonRpcError(
	val code: Int,
	val message: String,
	val data: JsonElement? = null,
)

/**
 * 后端对 request 的响应。
 *
 * @property jsonrpc JSON-RPC 协议版本，固定为 2.0。
 * @property id 对应请求 id；notification 不会有响应，所以这里允许为空。
 * @property result 成功响应内容。
 * @property error 失败响应内容，和 result 二选一。
 */
@Serializable
data class JsonRpcResponse(
	val jsonrpc: String = "2.0",
	val id: Long? = null,
	val result: JsonElement? = null,
	val error: JsonRpcError? = null,
) {
	/**
	 * 调用方期望成功响应时使用；如果后端返回 error，这里会给出更明确的异常。
	 */
	fun requireResult(): JsonElement =
		result ?: throw IllegalStateException(error?.message ?: "JSON-RPC 响应缺少 result")
}

/**
 * 后端主动推送给桌面端的 notification。
 *
 * 所有事件都带 method，用它和后端 wire protocol 对齐；具体子类只保留 UI 需要的字段。
 */
@Serializable(with = ServerEventSerializer::class)
sealed interface ServerEvent {
	/** JSON-RPC notification method 名称。 */
	val method: String

	/**
	 * 后端确认 turn 已开始。
	 *
	 * @property threadId 当前会话 id。
	 * @property turnId 后端新建的执行轮次 id。
	 */
	data class TurnStarted(
		val threadId: String,
		val turnId: String,
	) : ServerEvent {
		override val method: String = "turn/started"
	}

	/**
	 * 后端新增一个 ThreadItem。
	 *
	 * @property threadId item 所属会话。
	 * @property turnId item 所属执行轮次。
	 * @property item 新增的协议 item。
	 */
	data class ItemAdded(
		val threadId: String,
		val turnId: String,
		val item: ThreadItem,
	) : ServerEvent {
		override val method: String = "item/added"
	}

	/**
	 * 后端更新一个已有 ThreadItem。
	 *
	 * @property threadId item 所属会话。
	 * @property turnId item 所属执行轮次。
	 * @property item 更新后的完整协议 item。
	 */
	data class ItemUpdated(
		val threadId: String,
		val turnId: String,
		val item: ThreadItem,
	) : ServerEvent {
		override val method: String = "item/updated"
	}

	/**
	 * 后端标记一个 ThreadItem 已完成。
	 *
	 * @property threadId item 所属会话。
	 * @property turnId item 所属执行轮次。
	 * @property item 完成态协议 item。
	 */
	data class ItemCompleted(
		val threadId: String,
		val turnId: String,
		val item: ThreadItem,
	) : ServerEvent {
		override val method: String = "item/completed"
	}

	/**
	 * 当前 turn 正常结束。
	 *
	 * @property threadId turn 所属会话。
	 * @property turnId 已结束的执行轮次。
	 * @property status 后端结束状态，例如 completed。
	 */
	data class TurnCompleted(
		val threadId: String,
		val turnId: String,
		val status: String,
	) : ServerEvent {
		override val method: String = "turn/completed"
	}

	/**
	 * 当前 turn 失败。
	 *
	 * @property threadId turn 所属会话。
	 * @property turnId 失败的执行轮次。
	 * @property reason 后端给出的失败原因。
	 */
	data class TurnFailed(
		val threadId: String,
		val turnId: String,
		val reason: String,
	) : ServerEvent {
		override val method: String = "turn/failed"
	}

	/**
	 * 工具调用被 HITL 暂停，需要用户审批。
	 *
	 * @property request 审批请求详情，弹窗会直接读取它。
	 */
	data class ApprovalRequested(
		val request: ApprovalRequestPayload,
	) : ServerEvent {
		override val method: String = "approval/request"
	}

	/**
	 * 未知 notification，保留原始参数用于运行详情排查。
	 *
	 * @property method 后端传来的未知 method。
	 * @property params 原始 params JSON。
	 */
	data class Unknown(
		override val method: String,
		val params: JsonElement,
	) : ServerEvent
}

/**
 * ServerEvent 的自定义反序列化器。
 *
 * 后端 notification 都是 `{method, params}` 形态，kotlinx.serialization 无法自动根据 method 分派；
 * 这里集中处理分派逻辑，UI reducer 就能拿到强类型事件。
 */
object ServerEventSerializer : KSerializer<ServerEvent> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ServerEvent")

	/**
	 * 根据 method 字段把原始 JSON 转成具体 ServerEvent 子类。
	 */
	override fun deserialize(decoder: Decoder): ServerEvent {
		val jsonDecoder = decoder as? JsonDecoder
			?: throw SerializationException("ServerEvent 只能从 JSON 解码")
		val root = jsonDecoder.decodeJsonElement().jsonObject
		val method = root.requiredText("method")
		val params = root["params"] ?: JsonObject(emptyMap())
		val paramsObject = params.jsonObject

		return when (method) {
			"turn/started" -> ServerEvent.TurnStarted(
				threadId = paramsObject.requiredText("threadId"),
				turnId = paramsObject.requiredText("turnId"),
			)

			"item/added" -> decodeItemEvent(paramsObject, ServerEvent::ItemAdded)
			"item/updated" -> decodeItemEvent(paramsObject, ServerEvent::ItemUpdated)
			"item/completed" -> decodeItemEvent(paramsObject, ServerEvent::ItemCompleted)
			"turn/completed" -> ServerEvent.TurnCompleted(
				threadId = paramsObject.requiredText("threadId"),
				turnId = paramsObject.requiredText("turnId"),
				status = paramsObject.optionalText("status") ?: "completed",
			)

			"turn/failed" -> ServerEvent.TurnFailed(
				threadId = paramsObject.requiredText("threadId"),
				turnId = paramsObject.requiredText("turnId"),
				reason = paramsObject.optionalText("reason") ?: "unknown",
			)

			"approval/request" -> ServerEvent.ApprovalRequested(
				request = jsonDecoder.json.decodeFromJsonElement(ApprovalRequestPayload.serializer(), params),
			)

			else -> ServerEvent.Unknown(method, params)
		}
	}

	/**
	 * 桌面端目前几乎不需要把 ServerEvent 发回后端；实现这个方法主要是满足 serializer 接口。
	 */
	override fun serialize(encoder: Encoder, value: ServerEvent) {
		val jsonEncoder = encoder as? JsonEncoder
			?: throw SerializationException("ServerEvent 只能编码为 JSON")
		val payload = buildJsonObject {
			put("jsonrpc", "2.0")
			put("method", value.method)
		}
		jsonEncoder.encodeJsonElement(payload)
	}

	/**
	 * item/added、item/updated、item/completed 的结构一致，只是事件类型不同，所以共用这个小工厂。
	 */
	private fun decodeItemEvent(
		params: JsonObject,
		factory: (String, String, ThreadItem) -> ServerEvent,
	): ServerEvent {
		val itemElement = params["item"]
			?: throw SerializationException("item 事件缺少 item 字段")
		return factory(
			params.requiredText("threadId"),
			params.requiredText("turnId"),
			protocolJson.decodeFromJsonElement(ThreadItem.serializer(), itemElement),
		)
	}
}
