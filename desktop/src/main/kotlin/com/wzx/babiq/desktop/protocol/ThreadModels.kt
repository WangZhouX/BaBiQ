package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable(with = ThreadItemSerializer::class)
sealed interface ThreadItem {
	val id: String
	val type: String

	@Serializable
	data class UserMessage(
		override val id: String,
		override val type: String = "userMessage",
		val text: String,
	) : ThreadItem

	@Serializable
	data class AgentMessage(
		override val id: String,
		override val type: String = "agentMessage",
		val text: String? = null,
		val textDelta: String? = null,
	) : ThreadItem

	@Serializable
	data class Reasoning(
		override val id: String,
		override val type: String = "reasoning",
		val text: String,
	) : ThreadItem

	@Serializable
	data class CommandExecution(
		override val id: String,
		override val type: String = "commandExecution",
		val command: String,
		val status: String,
		val exitCode: Int? = null,
		val stdout: String? = null,
		val stderr: String? = null,
		val durationMs: Long? = null,
	) : ThreadItem

	@Serializable
	data class FileChange(
		override val id: String,
		override val type: String = "fileChange",
		val action: String,
		val path: String,
		val status: String,
		val contentPreview: String? = null,
	) : ThreadItem

	@Serializable
	data class TurnSummary(
		override val id: String,
		override val type: String = "turnSummary",
		val status: String,
		val model: String,
		val promptTokens: Long = 0,
		val completionTokens: Long = 0,
		val totalTokens: Long = 0,
		val toolCalls: Int = 0,
		val estimatedCostUsd: Double? = null,
		val durationMs: Long = 0,
	) : ThreadItem

	data class Unknown(
		override val id: String,
		override val type: String,
		val raw: JsonObject,
	) : ThreadItem
}

object ThreadItemSerializer : KSerializer<ThreadItem> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ThreadItem")

	override fun deserialize(decoder: Decoder): ThreadItem {
		val jsonDecoder = decoder as? JsonDecoder
			?: throw SerializationException("ThreadItem 只能从 JSON 解码")
		val raw = jsonDecoder.decodeJsonElement().jsonObject
		return when (val type = raw.requiredText("type")) {
			"userMessage" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.UserMessage.serializer(), raw)
			"agentMessage" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.AgentMessage.serializer(), raw)
			"reasoning" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.Reasoning.serializer(), raw)
			"commandExecution" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.CommandExecution.serializer(), raw)
			"fileChange" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.FileChange.serializer(), raw)
			"turnSummary" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.TurnSummary.serializer(), raw)
			else -> ThreadItem.Unknown(
				id = raw.optionalText("id") ?: "unknown",
				type = type,
				raw = raw,
			)
		}
	}

	override fun serialize(encoder: Encoder, value: ThreadItem) {
		throw SerializationException("桌面端当前只需要解码 ThreadItem")
	}
}
