package com.wzx.huitai.agent.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable(with = JsonRpcRequestSerializer::class)
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: ApplicationEnvelope,
)

@Serializable(with = JsonRpcNotificationSerializer::class)
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: ApplicationEnvelope,
)

@Serializable
data class JsonRpcSuccessResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonObject,
)

@Serializable
data class JsonRpcErrorResponse(
    val jsonrpc: String = "2.0",
    val id: String?,
    val error: JsonRpcError,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonObject? = null,
)

internal object JsonRpcRequestSerializer : KSerializer<JsonRpcRequest> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: JsonRpcRequest) {
        require(encoder is JsonEncoder)
        encoder.encodeJsonElement(
            JsonObject(
                linkedMapOf(
                    "jsonrpc" to JsonPrimitive(value.jsonrpc),
                    "id" to JsonPrimitive(value.id),
                    "method" to JsonPrimitive(value.method),
                    "params" to value.params.toJsonObject(),
                ),
            ),
        )
    }

    override fun deserialize(decoder: Decoder): JsonRpcRequest {
        require(decoder is JsonDecoder)
        val value = decoder.decodeJsonElement().jsonObject
        val method = value.string("method")
        return JsonRpcRequest(
            jsonrpc = value.string("jsonrpc"),
            id = value.string("id"),
            method = method,
            params = decodeApplicationParams(method, value.getValue("params").jsonObject),
        )
    }
}

internal object JsonRpcNotificationSerializer : KSerializer<JsonRpcNotification> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: JsonRpcNotification) {
        require(encoder is JsonEncoder)
        encoder.encodeJsonElement(
            JsonObject(
                linkedMapOf(
                    "jsonrpc" to JsonPrimitive(value.jsonrpc),
                    "method" to JsonPrimitive(value.method),
                    "params" to value.params.toJsonObject(),
                ),
            ),
        )
    }

    override fun deserialize(decoder: Decoder): JsonRpcNotification {
        require(decoder is JsonDecoder)
        val value = decoder.decodeJsonElement().jsonObject
        val method = value.string("method")
        return JsonRpcNotification(
            jsonrpc = value.string("jsonrpc"),
            method = method,
            params = decodeApplicationParams(method, value.getValue("params").jsonObject),
        )
    }
}

private fun decodeApplicationParams(method: String, params: JsonObject): ApplicationEnvelope {
    val applicationMethod = ApplicationMethod.entries.firstOrNull { it.wireName == method }
        ?: throw SerializationException("Unsupported application method")
    return when (applicationMethod) {
        ApplicationMethod.CATALOG_REGISTER,
        ApplicationMethod.CATALOG_UPDATE,
        -> params.toCatalogEnvelope()

        ApplicationMethod.CONTEXT_PUBLISH -> params.toContextEnvelope()

        ApplicationMethod.IDENTITY_BIND,
        ApplicationMethod.IDENTITY_UPDATE,
        -> params.toIdentityEnvelope()

        else -> params.toActionEnvelope()
    }
}
