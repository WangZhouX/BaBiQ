package com.wzx.huitai.agent.conversation

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface BusinessThreadItem {
    val id: String
    val type: String

    data class UserMessage(
        override val id: String,
        val text: String,
        override val type: String = "userMessage",
    ) : BusinessThreadItem

    data class AgentMessage(
        override val id: String,
        val text: String? = null,
        val textDelta: String? = null,
        override val type: String = "agentMessage",
    ) : BusinessThreadItem

    data class Reasoning(
        override val id: String,
        val text: String,
        override val type: String = "reasoning",
    ) : BusinessThreadItem

    data class Plan(
        override val id: String,
        val goal: String? = null,
        val steps: List<BusinessPlanStep> = emptyList(),
        val reasoning: String? = null,
        override val type: String = "plan",
    ) : BusinessThreadItem

    data class ApplicationAction(
        override val id: String,
        val executionId: String,
        val actionId: String,
        val title: String,
        val risk: String,
        val status: String,
        val previewSummary: String? = null,
        val errorCode: String? = null,
        val errorSummary: String? = null,
        val durationMs: Long? = null,
        override val type: String = "applicationAction",
    ) : BusinessThreadItem

    data class TurnSummary(
        override val id: String,
        val status: String,
        val model: String,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val totalTokens: Long = 0,
        val toolCalls: Int = 0,
        val durationMs: Long = 0,
        override val type: String = "turnSummary",
    ) : BusinessThreadItem

    /** 未知 item 只保留稳定定位字段，避免未来 payload 中的凭据进入 UI state 或异常文本。 */
    data class Unknown(
        override val id: String,
        override val type: String,
    ) : BusinessThreadItem
}

data class BusinessPlanStep(
    val order: Int,
    val description: String,
    val status: String,
    val activeForm: String? = null,
)

data class BusinessThread(
    val id: String,
    val title: String,
    val cwd: String,
)

data class BusinessTurn(
    val id: String,
    val threadId: String,
)

object BusinessThreadItemCodec {
    fun decode(value: JsonElement): BusinessThreadItem {
        val raw = value.asObject("thread item")
        val type = raw.requiredText("type")
        val id = raw.optionalText("id")
        return when (type) {
            "userMessage" -> BusinessThreadItem.UserMessage(requireNotNull(id) { "Missing required field: id" }, raw.requiredText("text"))
            "agentMessage" -> BusinessThreadItem.AgentMessage(
                id = requireNotNull(id) { "Missing required field: id" },
                text = raw.optionalText("text"),
                textDelta = raw.optionalText("textDelta"),
            )
            "reasoning" -> BusinessThreadItem.Reasoning(requireNotNull(id) { "Missing required field: id" }, raw.requiredText("text"))
            "plan" -> BusinessThreadItem.Plan(
                id = requireNotNull(id) { "Missing required field: id" },
                goal = raw.optionalText("goal"),
                steps = raw["steps"]?.jsonArray?.map { step ->
                    step.asObject("plan step").let {
                        BusinessPlanStep(
                            order = it.requiredInt("order"),
                            description = it.requiredText("description"),
                            status = it.requiredText("status"),
                            activeForm = it.optionalText("activeForm"),
                        )
                    }
                }.orEmpty(),
                reasoning = raw.optionalText("reasoning"),
            )
            "applicationAction" -> BusinessThreadItem.ApplicationAction(
                id = requireNotNull(id) { "Missing required field: id" },
                executionId = raw.requiredText("executionId"),
                actionId = raw.requiredText("actionId"),
                title = raw.requiredText("title"),
                risk = raw.requiredText("risk"),
                status = raw.requiredText("status"),
                previewSummary = raw.optionalText("previewSummary"),
                errorCode = raw.optionalText("errorCode"),
                errorSummary = raw.optionalText("errorSummary"),
                durationMs = raw.optionalLong("durationMs"),
            )
            "turnSummary" -> BusinessThreadItem.TurnSummary(
                id = requireNotNull(id) { "Missing required field: id" },
                status = raw.requiredText("status"),
                model = raw.requiredText("model"),
                promptTokens = raw.optionalLong("promptTokens") ?: 0,
                completionTokens = raw.optionalLong("completionTokens") ?: 0,
                totalTokens = raw.optionalLong("totalTokens") ?: 0,
                toolCalls = raw.optionalInt("toolCalls") ?: 0,
                durationMs = raw.optionalLong("durationMs") ?: 0,
            )
            else -> BusinessThreadItem.Unknown(id ?: "unknown:$type", type)
        }
    }
}

internal fun JsonElement.asObject(label: String): JsonObject =
    runCatching { jsonObject }.getOrElse { throw SerializationException("Invalid $label") }

internal fun JsonObject.requiredText(name: String): String = optionalText(name)
    ?.takeIf(String::isNotBlank)
    ?: throw SerializationException("Missing required field: $name")

internal fun JsonObject.optionalText(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.requiredInt(name: String): Int = optionalInt(name)
    ?: throw SerializationException("Missing required field: $name")

internal fun JsonObject.requiredBoolean(name: String): Boolean {
    val value = get(name) as? JsonPrimitive
    if (value == null || value.isString) {
        throw SerializationException("Missing or invalid boolean field: $name")
    }
    return when (value.content) {
        "true" -> true
        "false" -> false
        else -> throw SerializationException("Missing or invalid boolean field: $name")
    }
}

internal fun JsonObject.optionalInt(name: String): Int? =
    (get(name) as? JsonPrimitive)?.intOrNull

internal fun JsonObject.optionalLong(name: String): Long? =
    (get(name) as? JsonPrimitive)?.longOrNull
