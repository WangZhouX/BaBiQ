package com.wzx.huitai.security.audit

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * 动作审计 JSON 的递归脱敏器。
 *
 * @param sensitiveFieldIds 页面契约额外声明的敏感字段标识，按大小写不敏感精确匹配。
 * @param maxDepth 最大递归层数，到达边界后用固定占位符替代子树。
 * @param maxOutputBytes 单条脱敏载荷最大 UTF-8 字节数。
 */
class AuditRedactor(
    sensitiveFieldIds: Set<String> = emptySet(),
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {
    private val configuredSensitiveFields = sensitiveFieldIds.mapTo(mutableSetOf()) { it.lowercase() }

    init {
        require(maxDepth > 0) { "审计脱敏最大深度必须为正数" }
        require(maxOutputBytes >= MIN_OUTPUT_BYTES) { "审计脱敏输出预算过小" }
    }

    /**
     * 返回与输入无共享可变容器的脱敏快照。
     *
     * @param payload 尚未持久化的审计 JSON。
     */
    fun redact(payload: JsonObject): JsonObject {
        val redacted = redactElement(payload, depth = 0) as JsonObject
        val encoded = JSON.encodeToString(JsonElement.serializer(), redacted)
        return if (encoded.toByteArray(StandardCharsets.UTF_8).size <= maxOutputBytes) {
            redacted
        } else {
            JsonObject(Collections.unmodifiableMap(mapOf(OUTPUT_TRUNCATED_FIELD to JsonPrimitive(TRUNCATED))))
        }
    }

    /** 日志只展示安全配置边界，不回显自定义字段名称。 */
    override fun toString(): String =
        "AuditRedactor(sensitiveFields=${configuredSensitiveFields.size}, maxDepth=$maxDepth, " +
            "maxOutputBytes=$maxOutputBytes)"

    /** 在有界深度和元素数量内复制 JSON，敏感字段值直接替换。 */
    private fun redactElement(element: JsonElement, depth: Int): JsonElement {
        if (depth >= maxDepth && (element is JsonObject || element is JsonArray)) return JsonPrimitive(TRUNCATED)
        return when (element) {
            JsonNull -> JsonNull
            is JsonObject -> redactObject(element, depth)
            is JsonArray -> redactArray(element, depth)
            is JsonPrimitive -> redactPrimitive(element)
        }
    }

    /** 复制对象并对字段名实施凭据、文件内容和配置字段匹配。 */
    private fun redactObject(source: JsonObject, depth: Int): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        source.entries.take(MAX_CONTAINER_ENTRIES).forEachIndexed { index, (rawKey, value) ->
            val key = rawKey.take(MAX_FIELD_NAME_CHARS).ifBlank { "field_$index" }
            result[key] = if (isSensitiveKey(rawKey)) {
                JsonPrimitive(REDACTED)
            } else {
                redactElement(value, depth + 1)
            }
        }
        if (source.size > MAX_CONTAINER_ENTRIES) result[CONTAINER_TRUNCATED_FIELD] = JsonPrimitive(TRUNCATED)
        return JsonObject(Collections.unmodifiableMap(result))
    }

    /** 复制数组并限制单个容器元素数量。 */
    private fun redactArray(source: JsonArray, depth: Int): JsonArray {
        val values = source.take(MAX_CONTAINER_ENTRIES).map { redactElement(it, depth + 1) }.toMutableList()
        if (source.size > MAX_CONTAINER_ENTRIES) values += JsonPrimitive(TRUNCATED)
        return JsonArray(Collections.unmodifiableList(values))
    }

    /** 限制普通字符串长度，避免二次序列化形成无界内存或审计载荷。 */
    private fun redactPrimitive(source: JsonPrimitive): JsonPrimitive {
        if (!source.isString || source.content.length <= maxOutputBytes / 2) return source
        val prefixLength = (maxOutputBytes / 4).coerceAtLeast(1)
        return JsonPrimitive(source.content.take(prefixLength) + TRUNCATED)
    }

    /** 使用精确复合词匹配，避免把普通 `key` 或 `MONKEY` 误判为凭据。 */
    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        if (lower in configuredSensitiveFields) return true
        val compact = splitCompoundWords(key).joinToString("")
        return compact in CREDENTIAL_FIELDS || compact in BINARY_CONTENT_FIELDS
    }

    companion object {
        const val REDACTED = "[REDACTED]"
        const val TRUNCATED = "[TRUNCATED]"

        private const val DEFAULT_MAX_DEPTH = 16
        private const val DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024
        private const val MIN_OUTPUT_BYTES = 64
        private const val MAX_CONTAINER_ENTRIES = 256
        private const val MAX_FIELD_NAME_CHARS = 128
        private const val OUTPUT_TRUNCATED_FIELD = "_truncated"
        private const val CONTAINER_TRUNCATED_FIELD = "_remaining"

        private val JSON = Json { encodeDefaults = true }
        private val CREDENTIAL_FIELDS = setOf(
            "token", "accesstoken", "refreshtoken", "idtoken", "bearertoken",
            "password", "passcode", "secret", "clientsecret", "credential", "authorization",
            "apikey", "accesskey", "secretkey", "privatekey", "signingkey", "encryptionkey",
        )
        private val BINARY_CONTENT_FIELDS = setOf(
            "binary", "binarycontent", "filecontent", "filebytes", "contentbytes",
            "attachmentcontent", "attachmentbytes", "base64", "blob",
        )
    }
}

/** 将 camelCase、下划线、点和横线统一拆成小写复合词。 */
private fun splitCompoundWords(value: String): List<String> = value
    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .split(Regex("[^A-Za-z0-9]+"))
    .filter(String::isNotBlank)
    .map(String::lowercase)
