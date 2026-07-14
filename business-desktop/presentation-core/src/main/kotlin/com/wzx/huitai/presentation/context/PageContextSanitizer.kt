package com.wzx.huitai.presentation.context

import kotlinx.serialization.json.JsonPrimitive

/** 发布页面上下文前执行字段和动作级数据最小化。 */
class PageContextSanitizer {
    /**
     * 返回独立的清洗后快照，不修改页面持有的原始不可变状态。
     *
     * `SECRET` 和疑似凭证字段会被删除，`SENSITIVE` 值与校验消息使用稳定掩码，
     * 禁用动作不会暴露不可调用的输入结构。
     */
    fun sanitize(snapshot: PageContextSnapshot): PageContextSnapshot = snapshot.copy(
        fields = snapshot.fields.mapNotNull(::sanitizeField),
        availableActions = snapshot.availableActions.map { action ->
            if (action.enabled) action else action.copy(inputSchema = null)
        },
    )

    private fun sanitizeField(field: FieldContext): FieldContext? {
        if (field.sensitivity == FieldSensitivity.SECRET || field.id.isCredentialFieldId()) {
            return null
        }
        if (field.sensitivity != FieldSensitivity.SENSITIVE) {
            return field
        }
        return field.copy(
            value = field.value?.let { JsonPrimitive(SENSITIVE_MASK) },
            validationErrors = field.validationErrors.map { SENSITIVE_MASK },
        )
    }

    private fun String.isCredentialFieldId(): Boolean {
        if (CREDENTIAL_MARKERS.any(::contains)) {
            return true
        }
        val words = replace(LOWER_TO_UPPER_BOUNDARY, "$1_$2")
            .lowercase()
            .split(NON_ALPHANUMERIC)
            .filter(String::isNotBlank)
        return words.any(CREDENTIAL_WORDS::contains) ||
            words.windowed(size = 2).any { parts -> parts.joinToString(separator = "") in CREDENTIAL_WORDS }
    }

    companion object {
        /** 敏感值统一替换为固定文本，避免输出长度泄露原始内容。 */
        const val SENSITIVE_MASK: String = "[MASKED]"

        private val LOWER_TO_UPPER_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val CREDENTIAL_MARKERS = setOf("密码", "口令", "令牌", "密钥", "凭证")
        private val CREDENTIAL_WORDS = setOf(
            "password",
            "passwd",
            "pwd",
            "token",
            "secret",
            "apikey",
            "accesskey",
            "credential",
            "credentials",
            "privatekey",
        )
    }
}
