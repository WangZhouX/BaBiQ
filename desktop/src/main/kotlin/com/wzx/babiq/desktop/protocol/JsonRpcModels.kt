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

@Serializable
data class JsonRpcRequest(
	val jsonrpc: String = "2.0",
	val id: Long,
	val method: String,
	val params: JsonElement = JsonObject(emptyMap()),
)

@Serializable
data class JsonRpcError(
	val code: Int,
	val message: String,
	val data: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
	val jsonrpc: String = "2.0",
	val id: Long? = null,
	val result: JsonElement? = null,
	val error: JsonRpcError? = null,
) {
	fun requireResult(): JsonElement =
		result ?: throw IllegalStateException(error?.message ?: "JSON-RPC 响应缺少 result")
}

@Serializable(with = ServerEventSerializer::class)
sealed interface ServerEvent {
	val method: String

	data class TurnStarted(
		val threadId: String,
		val turnId: String,
	) : ServerEvent {
		override val method: String = "turn/started"
	}

	data class ItemAdded(
		val threadId: String,
		val turnId: String,
		val item: ThreadItem,
	) : ServerEvent {
		override val method: String = "item/added"
	}

	data class ItemUpdated(
		val threadId: String,
		val turnId: String,
		val item: ThreadItem,
	) : ServerEvent {
		override val method: String = "item/updated"
	}

	data class ItemCompleted(
		val threadId: String,
		val turnId: String,
		val item: ThreadItem,
	) : ServerEvent {
		override val method: String = "item/completed"
	}

	data class TurnCompleted(
		val threadId: String,
		val turnId: String,
		val status: String,
	) : ServerEvent {
		override val method: String = "turn/completed"
	}

	data class TurnFailed(
		val threadId: String,
		val turnId: String,
		val reason: String,
	) : ServerEvent {
		override val method: String = "turn/failed"
	}

	data class ApprovalRequested(
		val request: ApprovalRequestPayload,
	) : ServerEvent {
		override val method: String = "approval/request"
	}

	data class Unknown(
		override val method: String,
		val params: JsonElement,
	) : ServerEvent
}

object ServerEventSerializer : KSerializer<ServerEvent> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ServerEvent")

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

	override fun serialize(encoder: Encoder, value: ServerEvent) {
		val jsonEncoder = encoder as? JsonEncoder
			?: throw SerializationException("ServerEvent 只能编码为 JSON")
		val payload = buildJsonObject {
			put("jsonrpc", "2.0")
			put("method", value.method)
		}
		jsonEncoder.encodeJsonElement(payload)
	}

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
