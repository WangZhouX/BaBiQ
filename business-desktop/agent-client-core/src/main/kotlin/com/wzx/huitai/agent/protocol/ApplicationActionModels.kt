package com.wzx.huitai.agent.protocol

import com.wzx.huitai.action.model.ActionExecutionState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

typealias ApplicationActionState = ActionExecutionState

@Serializable
enum class ApplicationMethod(val wireName: String) {
    @SerialName("application/catalog/register") CATALOG_REGISTER("application/catalog/register"),
    @SerialName("application/catalog/update") CATALOG_UPDATE("application/catalog/update"),
    @SerialName("application/context/publish") CONTEXT_PUBLISH("application/context/publish"),
    @SerialName("application/identity/bind") IDENTITY_BIND("application/identity/bind"),
    @SerialName("application/identity/update") IDENTITY_UPDATE("application/identity/update"),
    @SerialName("application/action/request") ACTION_REQUEST("application/action/request"),
    @SerialName("application/action/cancel") ACTION_CANCEL("application/action/cancel"),
    @SerialName("application/action/accepted") ACTION_ACCEPTED("application/action/accepted"),
    @SerialName("application/action/previewed") ACTION_PREVIEWED("application/action/previewed"),
    @SerialName("application/action/approval-required") ACTION_APPROVAL_REQUIRED("application/action/approval-required"),
    @SerialName("application/action/running") ACTION_RUNNING("application/action/running"),
    @SerialName("application/action/completed") ACTION_COMPLETED("application/action/completed"),
    @SerialName("application/action/failed") ACTION_FAILED("application/action/failed"),
    @SerialName("application/action/rejected") ACTION_REJECTED("application/action/rejected"),
    @SerialName("application/action/canceled") ACTION_CANCELED("application/action/canceled"),
    @SerialName("application/action/expired") ACTION_EXPIRED("application/action/expired"),
    @SerialName("application/action/outcome-unknown") ACTION_OUTCOME_UNKNOWN("application/action/outcome-unknown"),
    @SerialName("application/action/status") ACTION_STATUS("application/action/status"),
    @SerialName("application/action/result/get") ACTION_RESULT_GET("application/action/result/get"),
}

@Serializable(with = ActionEnvelopeSerializer::class)
data class ActionEnvelope(
    override val common: CommonApplicationFields,
    val threadId: String,
    val turnId: String,
    val toolCallId: String,
    val executionId: String,
    val payload: JsonObject,
) : ApplicationEnvelope

internal fun ActionEnvelope.toJsonObject() = JsonObject(common.toJsonEntries().apply {
    put("threadId", JsonPrimitive(threadId))
    put("turnId", JsonPrimitive(turnId))
    put("toolCallId", JsonPrimitive(toolCallId))
    put("executionId", JsonPrimitive(executionId))
    put("payload", payload)
})

internal fun JsonObject.toActionEnvelope() = ActionEnvelope(
    common = toCommonApplicationFields(),
    threadId = string("threadId"),
    turnId = string("turnId"),
    toolCallId = string("toolCallId"),
    executionId = string("executionId"),
    payload = getValue("payload").jsonObject,
)

internal object ActionEnvelopeSerializer : JsonObjectEnvelopeSerializer<ActionEnvelope>() {
    override fun toJson(value: ActionEnvelope) = value.toJsonObject()
    override fun fromJson(value: JsonObject) = value.toActionEnvelope()
}
