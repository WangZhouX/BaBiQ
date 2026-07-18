package com.wzx.huitai.agent.conversation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

data class BusinessProviderModel(
    val id: String,
    val displayName: String,
    val active: Boolean = false,
)

data class BusinessProvider(
    val id: String,
    val displayName: String,
    val models: List<BusinessProviderModel>,
    val authMode: String,
    val hasApiKey: Boolean,
    val active: Boolean,
)

data class BusinessProviderSelection(
    val providerId: String,
    val modelId: String,
)

object BusinessProviderCodec {
    fun decodeList(result: JsonObject): List<BusinessProvider> =
        result.getValue("providers").jsonArray.map { element ->
            val raw = element.asObject("provider")
            BusinessProvider(
                id = raw.requiredText("id"),
                displayName = raw.optionalText("displayName") ?: raw.requiredText("label"),
                models = raw.getValue("models").jsonArray.map { modelElement ->
                    val model = modelElement.asObject("provider model")
                    BusinessProviderModel(
                        id = model.requiredText("id"),
                        displayName = model.optionalText("displayName") ?: model.optionalText("label")
                            ?: model.requiredText("id"),
                        active = model.optionalBoolean("active") ?: false,
                    )
                },
                authMode = raw.requiredText("authMode"),
                hasApiKey = raw.optionalBoolean("hasApiKey") ?: false,
                active = raw.optionalBoolean("active") ?: false,
            )
        }
}

internal fun JsonObject.optionalBoolean(name: String): Boolean? =
    optionalText(name)?.toBooleanStrictOrNull()
