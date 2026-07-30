package com.wzx.huitai.agent.business

import com.wzx.huitai.agent.client.AgentJsonRpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/** Stable, redacted projection of a business JSON-RPC failure. */
class BusinessRpcException(
    val remoteCode: Int,
    val businessCode: String? = null,
    val retryable: Boolean? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val section: String? = null,
    val currentSessionState: String? = null,
    val correlationId: String? = null,
) : IllegalStateException("Business JSON-RPC request failed (code=$remoteCode, businessCode=${businessCode ?: "UNKNOWN"})") {
    companion object {
        fun from(error: AgentJsonRpcException): BusinessRpcException {
            val data = error.safeData
            return BusinessRpcException(
                remoteCode = error.remoteCode,
                businessCode = data.safeText("businessCode"),
                retryable = data.safeText("retryable")?.toBooleanStrictOrNull(),
                fieldErrors = (data?.get("fieldErrors") as? JsonObject)?.entries
                    ?.mapNotNull { (key, value) -> value.safeText()?.let { key to it } }
                    ?.toMap()
                    ?: emptyMap(),
                section = data.safeText("section"),
                currentSessionState = data.safeText("currentSessionState"),
                correlationId = data.safeText("correlationId"),
            )
        }

        private fun JsonObject?.safeText(name: String): String? =
            (this?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

        private fun kotlinx.serialization.json.JsonElement?.safeText(): String? =
            (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }

    override fun toString(): String =
        "BusinessRpcException(remoteCode=$remoteCode, businessCode=${businessCode ?: "UNKNOWN"}, retryable=$retryable, section=${section ?: "-"}, correlationId=${correlationId ?: "-"})"
}
