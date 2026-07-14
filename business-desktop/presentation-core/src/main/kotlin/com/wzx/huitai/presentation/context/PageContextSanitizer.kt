package com.wzx.huitai.presentation.context

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/** 发布页面上下文前执行字段和动作级数据最小化。 */
class PageContextSanitizer {
    /**
     * 返回清洗后的不可变数据类视图，不修改页面持有的原始状态。本方法只复制发生清洗的结构，
     * 不承诺与调用方可变集合完全断开；完整 canonical 深冻结由 [PageContextPublisher] 的编码/解码完成。
     *
     * `SECRET` 和疑似凭证字段会被删除，普通字段结构中的凭证键会被递归剔除，
     * `SENSITIVE` 值与校验消息使用稳定掩码，禁用动作不会暴露不可调用的输入结构。
     */
    fun sanitize(snapshot: PageContextSnapshot): PageContextSnapshot = snapshot.copy(
        fields = snapshot.fields.mapNotNull(::sanitizeField),
        availableActions = snapshot.availableActions.map { action ->
            if (action.enabled) action else action.copy(inputSchema = null)
        },
    )

    private fun sanitizeField(field: FieldContext): FieldContext? {
        if (field.sensitivity == FieldSensitivity.SECRET || field.hasCredentialMetadata()) {
            return null
        }
        if (field.sensitivity != FieldSensitivity.SENSITIVE) {
            return field.copy(value = field.value?.sanitizeCredentialValues())
        }
        return field.copy(
            value = field.value?.let { JsonPrimitive(SENSITIVE_MASK) },
            validationErrors = field.validationErrors.map { SENSITIVE_MASK },
        )
    }

    private fun FieldContext.hasCredentialMetadata(): Boolean =
        id.isCredentialDescriptor() || label.isCredentialDescriptor() || type.isCredentialDescriptor()

    private fun JsonElement.sanitizeCredentialValues(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            entries.mapNotNull { (key, value) ->
                if (key.isCredentialDescriptor()) null else key to value.sanitizeCredentialValues()
            }.toMap(linkedMapOf()),
        )

        is JsonArray -> JsonArray(map { element -> element.sanitizeCredentialValues() })
        else -> this
    }

    private fun String.isCredentialDescriptor(): Boolean {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFKC)
        if (CREDENTIAL_MARKERS.any(normalized::contains)) {
            return true
        }
        val compact = normalized.lowercase().filter(Char::isLetterOrDigit)
        if (compact in COMPACT_CREDENTIAL_COMPOUNDS) {
            return true
        }
        val words = normalized
            .replace(ACRONYM_TO_WORD_BOUNDARY, "$1_$2")
            .replace(LOWER_TO_UPPER_BOUNDARY, "$1_$2")
            .lowercase()
            .split(NON_ALPHANUMERIC)
            .filter(String::isNotBlank)
        return words.any(CREDENTIAL_WORDS::contains)
    }

    companion object {
        /** 敏感值统一替换为固定文本，避免输出长度泄露原始内容。 */
        const val SENSITIVE_MASK: String = "[MASKED]"

        private val ACRONYM_TO_WORD_BOUNDARY = Regex("([A-Z]+)([A-Z][a-z])")
        private val LOWER_TO_UPPER_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val CREDENTIAL_MARKERS = setOf("密码", "口令", "令牌", "密钥", "凭证")
        private val CREDENTIAL_WORDS = setOf(
            "password",
            "passwd",
            "pwd",
            "token",
            "secret",
            "authorization",
            "bearer",
            "credential",
            "credentials",
        )
        private val COMPACT_CREDENTIAL_COMPOUNDS = setOf(
            "apitoken",
            "idtoken",
            "accesstoken",
            "refreshtoken",
            "bearertoken",
            "clientsecret",
            "apikey",
            "accesskey",
            "privatekey",
            "secretkey",
            "signingkey",
            "encryptionkey",
        )
    }
}
