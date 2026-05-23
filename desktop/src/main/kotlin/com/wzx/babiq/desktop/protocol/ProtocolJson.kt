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

/**
 * 读取必填字符串字段；协议必填字段缺失时尽早失败，方便定位后端 wire shape 不一致。
 */
internal fun JsonObject.requiredText(name: String): String =
	this[name]?.jsonPrimitive?.content
		?: error("缺少必填协议字段: $name")

/**
 * 读取可选字符串字段；JSON null 会被当成 Kotlin null 处理。
 */
internal fun JsonObject.optionalText(name: String): String? =
	this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

/**
 * 小型语义化扩展：调用处写 `element.asObject()` 比直接 `.jsonObject` 更像一次协议转换。
 */
internal fun JsonElement.asObject(): JsonObject = jsonObject
