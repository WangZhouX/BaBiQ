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

/**
 * ThreadItem 镜像后端的 Thread / Turn / Item 协议模型。
 *
 * sealed interface 的好处是：when 分支处理 item 时，编译器能提醒我们是否漏掉已知类型；
 * Unknown 则保留未来协议字段，避免后端新增 item 时桌面端直接崩溃。
 */
@Serializable(with = ThreadItemSerializer::class)
sealed interface ThreadItem {
	val id: String
	val type: String

	@Serializable
	/**
	 * 用户消息协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 userMessage。
	 * @property text 用户输入文本。
	 */
	data class UserMessage(
		override val id: String,
		override val type: String = "userMessage",
		val text: String,
	) : ThreadItem

	@Serializable
	/**
	 * Agent 文本协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 agentMessage。
	 * @property text 完整助手回复，通常在 turn 完成时出现。
	 * @property textDelta 流式增量文本，UI 会追加到正在展示的助手消息里。
	 */
	data class AgentMessage(
		override val id: String,
		override val type: String = "agentMessage",
		val text: String? = null,
		val textDelta: String? = null,
	) : ThreadItem

	@Serializable
	/**
	 * 推理过程协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 reasoning。
	 * @property text 后端暴露的推理/计划摘要文本。
	 */
	data class Reasoning(
		override val id: String,
		override val type: String = "reasoning",
		val text: String,
	) : ThreadItem

	@Serializable
	/**
	 * 命令执行协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 commandExecution。
	 * @property command 实际执行的 shell 命令。
	 * @property status 命令状态，例如 running/completed/failed。
	 * @property exitCode 进程退出码，仍在运行时为空。
	 * @property stdout 标准输出摘要。
	 * @property stderr 标准错误摘要。
	 * @property durationMs 命令耗时，单位毫秒。
	 */
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
	/**
	 * 文件变更协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 fileChange。
	 * @property action 文件动作，例如 read/write/patch。
	 * @property path 文件路径。
	 * @property status 动作状态。
	 * @property contentPreview 可选内容预览。
	 */
	data class FileChange(
		override val id: String,
		override val type: String = "fileChange",
		val action: String,
		val path: String,
		val status: String,
		val contentPreview: String? = null,
	) : ThreadItem

	@Serializable
	/**
	 * turn 结束摘要协议 item。
	 *
	 * @property id 后端生成的 item id。
	 * @property type 协议类型固定为 turnSummary。
	 * @property status turn 结束状态。
	 * @property model 本轮实际使用的模型名。
	 * @property promptTokens 输入 token 数。
	 * @property completionTokens 输出 token 数。
	 * @property totalTokens 输入和输出 token 总数。
	 * @property toolCalls 本轮工具调用次数。
	 * @property estimatedCostUsd 估算美元成本。
	 * @property durationMs 本轮耗时，单位毫秒。
	 */
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

	/**
	 * 未知协议 item。
	 *
	 * @property id 尽量从 raw 中读取的 item id。
	 * @property type 后端传来的未知类型。
	 * @property raw 完整原始 JSON，便于协议升级时排查。
	 */
	data class Unknown(
		override val id: String,
		override val type: String,
		val raw: JsonObject,
	) : ThreadItem
}

/**
 * 后端 item 用 type 字段区分具体形态，kotlinx.serialization 默认不会自动按这个字段分派。
 * 因此这里写一个很薄的自定义 serializer：只读一次原始 JsonObject，再根据 type 选择目标 data class。
 */
object ThreadItemSerializer : KSerializer<ThreadItem> {
	override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ThreadItem")

	override fun deserialize(decoder: Decoder): ThreadItem {
		val jsonDecoder = decoder as? JsonDecoder
			?: throw SerializationException("ThreadItem 只能从 JSON 解码")
		val raw = jsonDecoder.decodeJsonElement().jsonObject
		return when (val type = raw.requiredText("type")) {
			// 已知 P1 类型转成强类型对象，UI 和 reducer 不需要手写 JsonObject 取字段。
			"userMessage" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.UserMessage.serializer(), raw)
			"agentMessage" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.AgentMessage.serializer(), raw)
			"reasoning" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.Reasoning.serializer(), raw)
			"commandExecution" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.CommandExecution.serializer(), raw)
			"fileChange" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.FileChange.serializer(), raw)
			"turnSummary" -> jsonDecoder.json.decodeFromJsonElement(ThreadItem.TurnSummary.serializer(), raw)
			// 未知类型不丢弃，交给运行详情面板展示 raw JSON，方便后续协议扩展排查。
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
