package com.wzx.huitai.agent.business.auth

import com.wzx.huitai.agent.client.AgentRawNotification
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class BusinessAuthStateChangeCode {
    AUTH_EXPIRED,
    MEMBERSHIP_EXPIRED,
}

data class BusinessAuthStateChanged(
    val authSessionId: String,
    val state: BusinessAuthStatus,
    val generation: Long,
    val businessCode: BusinessAuthStateChangeCode,
) {
    override fun toString(): String =
        "BusinessAuthStateChanged(authSessionId=[REDACTED], state=$state, generation=$generation, businessCode=$businessCode)"
}

object BusinessAuthStateChangedCodec {
    const val METHOD: String = "business/auth/state-changed"

    fun decode(notification: AgentRawNotification): BusinessAuthStateChanged {
        if (notification.method != METHOD) {
            throw SerializationException("Unsupported auth state notification method")
        }
        val params = notification.params
        val state = params.requiredString("state").let { raw ->
            BusinessAuthStatus.entries.firstOrNull { it.name == raw && it != BusinessAuthStatus.UNKNOWN }
                ?: throw SerializationException("Unknown auth state")
        }
        val generation = params.requiredLong("generation")
        if (generation < 0) throw SerializationException("generation must not be negative")
        val businessCode = when (params.requiredString("businessCode")) {
            "BUSINESS_AUTH_EXPIRED" -> BusinessAuthStateChangeCode.AUTH_EXPIRED
            "BUSINESS_MEMBERSHIP_EXPIRED" -> BusinessAuthStateChangeCode.MEMBERSHIP_EXPIRED
            else -> throw SerializationException("Unknown auth state change code")
        }
        return BusinessAuthStateChanged(
            authSessionId = params.requiredString("authSessionId").takeIf(String::isNotBlank)
                ?: throw SerializationException("Missing required field: authSessionId"),
            state = state,
            generation = generation,
            businessCode = businessCode,
        )
    }
}

private fun JsonObject.requiredString(name: String): String =
    (get(name) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?: throw SerializationException("Missing or invalid string field: $name")

private fun JsonObject.requiredLong(name: String): Long =
    (get(name) as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.contentOrNull
        ?.toLongOrNull()
        ?: throw SerializationException("Missing or invalid integer field: $name")
