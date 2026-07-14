package com.wzx.huitai.presentation.form

internal const val MAX_STABLE_IDENTIFIER_LENGTH = 256
internal const val MAX_REASON_LENGTH = 4_000

/** 稳定标识允许常见 UUID、路径和命名空间字符，但禁止空白边界及控制字符。 */
internal fun String.isSafeStableIdentifier(): Boolean =
    isNotBlank() &&
        length <= MAX_STABLE_IDENTIFIER_LENGTH &&
        trim() == this &&
        none { it.isISOControl() || it.isWhitespace() }

/** 用户可读说明允许换行和制表符，但拒绝其他控制字符及无界长度。 */
internal fun String.isSafeReason(): Boolean =
    isNotBlank() &&
        length <= MAX_REASON_LENGTH &&
        none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }

/** 可选展示文本允许为空，但仍禁止无界长度和不可见控制字符。 */
internal fun String.isSafeDisplayText(): Boolean =
    length <= MAX_REASON_LENGTH &&
        none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }

internal fun requireSafeIdentifier(value: String, message: String) {
    require(value.isSafeStableIdentifier()) { message }
}

internal fun requireSafeReason(value: String) {
    require(value.isSafeReason()) { "字段建议原因格式无效" }
}
