package com.wzx.huitai.agent.conversation

import com.wzx.huitai.agent.client.AgentRawNotification
import kotlinx.serialization.SerializationException

sealed interface BusinessAgentEvent {
    val threadId: String?
    val turnId: String?

    data class TurnStarted(
        override val threadId: String,
        override val turnId: String,
    ) : BusinessAgentEvent

    data class ItemAdded(
        override val threadId: String,
        override val turnId: String,
        val item: BusinessThreadItem,
    ) : BusinessAgentEvent

    data class ItemUpdated(
        override val threadId: String,
        override val turnId: String,
        val item: BusinessThreadItem,
    ) : BusinessAgentEvent

    data class ItemCompleted(
        override val threadId: String,
        override val turnId: String,
        val item: BusinessThreadItem,
    ) : BusinessAgentEvent

    data class TurnCompleted(
        override val threadId: String,
        override val turnId: String,
        val status: String,
    ) : BusinessAgentEvent

    data class TurnFailed(
        override val threadId: String,
        override val turnId: String,
        val reason: String,
    ) : BusinessAgentEvent

    /** 未知 notification 不保留 params，防止未审计的新字段进入桌面状态。 */
    data class Unknown(val method: String) : BusinessAgentEvent {
        override val threadId: String? = null
        override val turnId: String? = null
    }
}

internal object BusinessAgentEventCodec {
    fun decode(notification: AgentRawNotification): BusinessAgentEvent {
        val params = notification.params
        return when (notification.method) {
            "turn/started" -> BusinessAgentEvent.TurnStarted(
                params.requiredText("threadId"),
                params.requiredText("turnId"),
            )
            "item/added" -> item(params, BusinessAgentEvent::ItemAdded)
            "item/updated" -> item(params, BusinessAgentEvent::ItemUpdated)
            "item/completed" -> item(params, BusinessAgentEvent::ItemCompleted)
            "turn/completed" -> BusinessAgentEvent.TurnCompleted(
                params.requiredText("threadId"),
                params.requiredText("turnId"),
                params.optionalText("status") ?: "completed",
            )
            "turn/failed" -> BusinessAgentEvent.TurnFailed(
                params.requiredText("threadId"),
                params.requiredText("turnId"),
                safeReason(params.optionalText("reason")),
            )
            else -> BusinessAgentEvent.Unknown(notification.method)
        }
    }

    private fun item(
        params: kotlinx.serialization.json.JsonObject,
        factory: (String, String, BusinessThreadItem) -> BusinessAgentEvent,
    ): BusinessAgentEvent = factory(
        params.requiredText("threadId"),
        params.requiredText("turnId"),
        BusinessThreadItemCodec.decode(params["item"] ?: throw SerializationException("Missing required field: item")),
    )

    private fun safeReason(reason: String?): String = when (reason?.lowercase()) {
        "canceled", "cancelled" -> "canceled"
        "interrupted" -> "interrupted"
        "expired" -> "expired"
        else -> "turn_failed"
    }
}
