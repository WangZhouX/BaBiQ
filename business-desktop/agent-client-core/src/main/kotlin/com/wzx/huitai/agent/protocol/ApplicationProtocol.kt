package com.wzx.huitai.agent.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ApplicationProtocol {
    const val PROTOCOL_VERSION: String = "1.0"

    val JSON: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = true
    }
}

@Serializable
data class CommonApplicationFields(
    val protocolVersion: String,
    val desktopInstanceId: String,
    val desktopSessionId: String,
    val authSessionId: String?,
    val identityEpoch: Long,
    val sequence: Long,
    val generatedAt: String,
    val userId: String?,
    val tenantId: String?,
    val platformId: String?,
)

@Serializable(with = ApplicationEnvelopeSerializer::class)
sealed interface ApplicationEnvelope {
    val common: CommonApplicationFields
}

internal object ApplicationEnvelopeSerializer : KSerializer<ApplicationEnvelope> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ApplicationEnvelope) {
        require(encoder is JsonEncoder)
        encoder.encodeJsonElement(value.toJsonObject())
    }

    override fun deserialize(decoder: Decoder): ApplicationEnvelope {
        require(decoder is JsonDecoder)
        val value = decoder.decodeJsonElement().jsonObject
        return when {
            "threadId" in value -> value.toActionEnvelope()
            "authenticated" in value -> value.toIdentityEnvelope()
            "catalogEpoch" in value && "contextSequence" in value ->
                throw SerializationException("Catalog and context envelopes require method-aware decoding")
            else -> throw SerializationException("Unknown application envelope shape")
        }
    }
}

internal fun ApplicationEnvelope.toJsonObject(): JsonObject = when (this) {
    is CatalogEnvelope -> toJsonObject()
    is ContextEnvelope -> toJsonObject()
    is ActionEnvelope -> toJsonObject()
    is IdentityEnvelope -> toJsonObject()
}

internal fun CommonApplicationFields.toJsonEntries(): MutableMap<String, JsonElement> = linkedMapOf(
    "protocolVersion" to JsonPrimitive(protocolVersion),
    "desktopInstanceId" to JsonPrimitive(desktopInstanceId),
    "desktopSessionId" to JsonPrimitive(desktopSessionId),
    "authSessionId" to authSessionId.toJsonElement(),
    "identityEpoch" to JsonPrimitive(identityEpoch),
    "sequence" to JsonPrimitive(sequence),
    "generatedAt" to JsonPrimitive(generatedAt),
    "userId" to userId.toJsonElement(),
    "tenantId" to tenantId.toJsonElement(),
    "platformId" to platformId.toJsonElement(),
)

internal fun JsonObject.toCommonApplicationFields() = CommonApplicationFields(
    protocolVersion = string("protocolVersion"),
    desktopInstanceId = string("desktopInstanceId"),
    desktopSessionId = string("desktopSessionId"),
    authSessionId = nullableString("authSessionId"),
    identityEpoch = long("identityEpoch"),
    sequence = long("sequence"),
    generatedAt = string("generatedAt"),
    userId = nullableString("userId"),
    tenantId = nullableString("tenantId"),
    platformId = nullableString("platformId"),
)

internal fun String?.toJsonElement(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.nullableString(name: String): String? = get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
internal fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()
internal fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.content.toInt()
internal fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean
