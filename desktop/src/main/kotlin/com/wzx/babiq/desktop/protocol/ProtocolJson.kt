package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 桌面端协议层统一使用的 JSON 实例。
 *
 * `ignoreUnknownKeys` 很重要：后端协议会继续演进，桌面端只消费自己认识的字段；
 * 不认识的字段应进入运行详情或被安全忽略，而不是让整个聊天界面崩掉。
 */
val protocolJson: Json = Json {
	ignoreUnknownKeys = true
	encodeDefaults = true
	explicitNulls = false
}

internal fun JsonObject.requiredText(name: String): String =
	this[name]?.jsonPrimitive?.content
		?: error("缺少必填协议字段: $name")

internal fun JsonObject.optionalText(name: String): String? =
	this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

internal fun JsonElement.asObject(): JsonObject = jsonObject
