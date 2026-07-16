package com.wzx.huitai.agent.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable(with = CatalogEnvelopeSerializer::class)
data class CatalogEnvelope(
    override val common: CommonApplicationFields,
    val catalogEpoch: Long,
    val contextSequence: Long,
    val payloadSize: Int,
    val payload: JsonObject,
) : ApplicationEnvelope

@Serializable(with = ContextEnvelopeSerializer::class)
data class ContextEnvelope(
    override val common: CommonApplicationFields,
    val catalogEpoch: Long,
    val contextSequence: Long,
    val payloadSize: Int,
    val payload: JsonObject,
) : ApplicationEnvelope

internal fun CatalogEnvelope.toJsonObject() = JsonObject(common.toJsonEntries().apply {
    put("catalogEpoch", JsonPrimitive(catalogEpoch))
    put("contextSequence", JsonPrimitive(contextSequence))
    put("payloadSize", JsonPrimitive(payloadSize))
    put("payload", payload)
})

internal fun ContextEnvelope.toJsonObject() = JsonObject(common.toJsonEntries().apply {
    put("catalogEpoch", JsonPrimitive(catalogEpoch))
    put("contextSequence", JsonPrimitive(contextSequence))
    put("payloadSize", JsonPrimitive(payloadSize))
    put("payload", payload)
})

internal fun JsonObject.toCatalogEnvelope() = CatalogEnvelope(
    common = toCommonApplicationFields(),
    catalogEpoch = long("catalogEpoch"),
    contextSequence = long("contextSequence"),
    payloadSize = int("payloadSize"),
    payload = getValue("payload").jsonObject,
)

internal fun JsonObject.toContextEnvelope() = ContextEnvelope(
    common = toCommonApplicationFields(),
    catalogEpoch = long("catalogEpoch"),
    contextSequence = long("contextSequence"),
    payloadSize = int("payloadSize"),
    payload = getValue("payload").jsonObject,
)

internal object CatalogEnvelopeSerializer : JsonObjectEnvelopeSerializer<CatalogEnvelope>() {
    override fun toJson(value: CatalogEnvelope) = value.toJsonObject()
    override fun fromJson(value: JsonObject) = value.toCatalogEnvelope()
}

internal object ContextEnvelopeSerializer : JsonObjectEnvelopeSerializer<ContextEnvelope>() {
    override fun toJson(value: ContextEnvelope) = value.toJsonObject()
    override fun fromJson(value: JsonObject) = value.toContextEnvelope()
}

internal abstract class JsonObjectEnvelopeSerializer<T> : KSerializer<T> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    abstract fun toJson(value: T): JsonObject
    abstract fun fromJson(value: JsonObject): T

    override fun serialize(encoder: Encoder, value: T) {
        require(encoder is JsonEncoder)
        encoder.encodeJsonElement(toJson(value))
    }

    override fun deserialize(decoder: Decoder): T {
        require(decoder is JsonDecoder)
        return fromJson(decoder.decodeJsonElement().jsonObject)
    }
}
