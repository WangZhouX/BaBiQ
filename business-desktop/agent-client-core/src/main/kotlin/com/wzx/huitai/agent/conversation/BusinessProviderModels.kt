package com.wzx.huitai.agent.conversation

import kotlinx.serialization.json.JsonArray
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
    val type: String = "UNKNOWN",
    val baseUrl: String = "",
    val model: String = models.firstOrNull { it.active }?.id ?: models.firstOrNull()?.id.orEmpty(),
    val contextWindow: Int = 0,
    val enabled: Boolean = true,
)

data class BusinessProviderDraft(
    val providerId: String,
    val displayName: String,
    val type: String,
    val authMode: String = "api_key",
    val baseUrl: String = "",
    val model: String,
    val apiKey: String? = null,
    val contextWindow: Int = 0,
    val enabled: Boolean = true,
) {
    override fun toString(): String =
        "BusinessProviderDraft(providerId=$providerId, model=$model, apiKey=[REDACTED])"
}

data class BusinessProviderDeleteResult(
    val ok: Boolean,
    val providerId: String,
    val activeProviderId: String,
)

data class BusinessProviderTestResult(
    val ok: Boolean,
    val providerId: String,
    val message: String,
)

data class BusinessProviderOAuthStatus(
    val providerType: String,
    val authMode: String,
    val cliInstalled: Boolean,
    val loggedIn: Boolean,
    val message: String,
)

data class BusinessProviderOAuthLoginResult(
    val ok: Boolean,
    val pid: Long?,
    val message: String,
)

data class BusinessProviderSelection(
    val providerId: String,
    val modelId: String,
)

object BusinessProviderCodec {
    fun decodeList(result: JsonObject): List<BusinessProvider> =
        result.getValue("providers").jsonArray.map { decodeProvider(it.asObject("provider")) }

    fun decodeProvider(result: JsonObject): BusinessProvider {
        val id = result.requiredText("id")
        val configuredModel = result.requiredText("model")
        val active = result.requiredBoolean("active")
        val decodedModels = (result["models"] as? JsonArray).orEmpty().map { modelElement ->
            val rawModel = modelElement.asObject("provider model")
            val modelId = rawModel.requiredText("id")
            BusinessProviderModel(
                id = modelId,
                displayName = rawModel.optionalText("displayName") ?: rawModel.optionalText("label") ?: modelId,
                active = active && modelId == configuredModel,
            )
        }
        val models = if (decodedModels.any { it.id == configuredModel }) {
            decodedModels
        } else {
            listOf(BusinessProviderModel(configuredModel, configuredModel, active)) + decodedModels
        }
        return BusinessProvider(
            id = id,
            displayName = result.optionalText("displayName") ?: result.optionalText("label") ?: id,
            models = models,
            authMode = result.optionalText("authMode") ?: "api_key",
            hasApiKey = result.requiredBoolean("hasApiKey"),
            active = active,
            type = result.optionalText("type") ?: "UNKNOWN",
            baseUrl = result.optionalText("baseUrl") ?: "",
            model = configuredModel,
            contextWindow = result.optionalInt("contextWindow") ?: 0,
            enabled = result.requiredBoolean("enabled"),
        )
    }

    fun decodeDeleteResult(result: JsonObject): BusinessProviderDeleteResult =
        BusinessProviderDeleteResult(
            ok = result.requiredBoolean("ok"),
            providerId = result.requiredText("providerId"),
            activeProviderId = result.requiredText("activeProviderId"),
        )

    fun decodeTestResult(result: JsonObject): BusinessProviderTestResult {
        return BusinessProviderTestResult(
            ok = result.requiredBoolean("ok"),
            providerId = result.requiredText("providerId"),
            message = result.requiredText("message"),
        )
    }

    fun decodeOAuthStatus(result: JsonObject): BusinessProviderOAuthStatus {
        return BusinessProviderOAuthStatus(
            providerType = result.optionalText("providerType") ?: "ANTHROPIC",
            authMode = result.optionalText("authMode") ?: "oauth_cli",
            cliInstalled = result.requiredBoolean("cliInstalled"),
            loggedIn = result.requiredBoolean("loggedIn"),
            message = result.requiredText("message"),
        )
    }

    fun decodeOAuthLoginResult(result: JsonObject): BusinessProviderOAuthLoginResult {
        return BusinessProviderOAuthLoginResult(
            ok = result.requiredBoolean("ok"),
            pid = result.optionalLong("pid"),
            message = result.requiredText("message"),
        )
    }
}
