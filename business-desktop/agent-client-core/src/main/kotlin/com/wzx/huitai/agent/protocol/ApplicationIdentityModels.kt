package com.wzx.huitai.agent.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = IdentityEnvelopeSerializer::class)
data class IdentityEnvelope(
    override val common: CommonApplicationFields,
    val authenticated: Boolean,
    val roles: Set<String>,
    val permissions: Set<String>,
) : ApplicationEnvelope

internal fun IdentityEnvelope.toJsonObject() = JsonObject(common.toJsonEntries().apply {
    put("authenticated", JsonPrimitive(authenticated))
    put("roles", JsonArray(roles.map(::JsonPrimitive)))
    put("permissions", JsonArray(permissions.map(::JsonPrimitive)))
})

internal fun JsonObject.toIdentityEnvelope() = IdentityEnvelope(
    common = toCommonApplicationFields(),
    authenticated = boolean("authenticated"),
    roles = getValue("roles").jsonArray.mapTo(linkedSetOf()) { it.jsonPrimitive.content },
    permissions = getValue("permissions").jsonArray.mapTo(linkedSetOf()) { it.jsonPrimitive.content },
)

internal object IdentityEnvelopeSerializer : JsonObjectEnvelopeSerializer<IdentityEnvelope>() {
    override fun toJson(value: IdentityEnvelope) = value.toJsonObject()
    override fun fromJson(value: JsonObject) = value.toIdentityEnvelope()
}
