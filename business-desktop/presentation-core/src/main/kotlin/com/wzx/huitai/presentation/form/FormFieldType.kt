package com.wzx.huitai.presentation.form

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 表单字段支持的通用强类型。
 *
 * @param wireName 页面上下文使用的稳定协议名称。
 */
@Serializable
enum class FormFieldType(val wireName: String) {
    @SerialName("string")
    STRING("string"),

    @SerialName("multiline")
    MULTILINE("multiline"),

    @SerialName("decimal")
    DECIMAL("decimal"),

    @SerialName("date")
    DATE("date"),

    @SerialName("enum")
    ENUM("enum");

    companion object {
        /**
         * 按协议字符串解析类型；未知类型返回 null 并由调用方拒绝。
         *
         * @param value 页面上下文提供的类型字符串。
         */
        fun fromWireName(value: String): FormFieldType? = entries.firstOrNull { it.wireName == value }
    }

    /**
     * 验证非空 JSON 值是否符合字段类型和定义约束。
     *
     * @param value 待验证的结构化字段值。
     * @param definition 当前字段的可信定义。
     */
    internal fun accepts(value: JsonElement?, definition: FormFieldDefinition): Boolean {
        if (value == null || value == JsonNull) return true
        val primitive = value as? JsonPrimitive ?: return false
        return when (this) {
            STRING, MULTILINE -> primitive.isString
            DECIMAL -> primitive.isDecimal()
            DATE -> primitive.isStrictIsoDate()
            ENUM -> primitive.isString && primitive.content in definition.enumAllowedValues
        }
    }
}

private fun JsonPrimitive.isDecimal(): Boolean {
    if (this == JsonNull || booleanOrNull != null) return false
    return runCatching { BigDecimal(content) }.isSuccess
}

private fun JsonPrimitive.isStrictIsoDate(): Boolean {
    if (!isString) return false
    return try {
        LocalDate.parse(content, DateTimeFormatter.ISO_LOCAL_DATE)
        true
    } catch (_: DateTimeParseException) {
        false
    }
}

/**
 * 仅在可信业务代码中注册的字段定义。
 *
 * @param fieldId 页面内稳定字段标识。
 * @param type 字段强类型。
 * @param requiredPermissions 修改字段必须具备的全部冻结权限。
 * @param enumAllowedValues enum 字段允许的稳定值；其他类型应保持为空。
 */
data class FormFieldDefinition(
    val fieldId: String,
    val type: FormFieldType,
    val requiredPermissions: Set<String> = emptySet(),
    val enumAllowedValues: Set<String> = emptySet(),
) {
    init {
        require(fieldId.isNotBlank()) { "字段标识不能为空" }
        require(type == FormFieldType.ENUM || enumAllowedValues.isEmpty()) {
            "只有 enum 字段可以声明允许值"
        }
        require(type != FormFieldType.ENUM || enumAllowedValues.isNotEmpty()) {
            "enum 字段必须声明至少一个允许值"
        }
    }
}

/** 冻结调用方可变权限集合和 enum 值集合。 */
internal fun FormFieldDefinition.frozenCopy(): FormFieldDefinition = FormFieldDefinition(
    fieldId = fieldId,
    type = type,
    requiredPermissions = requiredPermissions.toSet(),
    enumAllowedValues = enumAllowedValues.toSet(),
)
