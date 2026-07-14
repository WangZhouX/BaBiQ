package com.wzx.huitai.presentation.form

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 表单建议所引用的通用来源。
 *
 * @param type 来源类型，例如 user-input、external-record；该值不得承载原始正文。
 * @param id 来源的稳定标识。
 * @param label 可选的用户可读标签；不得承载敏感原文。
 */
@Serializable
data class SourceReference(
    val type: String,
    val id: String,
    val label: String? = null,
)

/**
 * 单个字段的建议变更。
 *
 * @param fieldId 页面内稳定字段标识。
 * @param previousValue Agent 生成建议时看到的旧值，用于防止覆盖用户新输入。
 * @param newValue 建议写入的结构化新值。
 * @param reason 建议原因。
 * @param confidence 0 到 1 之间的有限置信度。
 * @param sourceReferences 支撑建议的通用来源引用。
 */
@Serializable
data class FieldChange(
    val fieldId: String,
    val previousValue: JsonElement? = null,
    val newValue: JsonElement? = null,
    val reason: String,
    val confidence: Double,
    val sourceReferences: List<SourceReference> = emptyList(),
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "confidence 必须是 0 到 1 之间的有限值"
        }
    }

    /** 避免日志输出旧值、新值和建议原因。 */
    override fun toString(): String =
        "FieldChange(fieldId=$fieldId, confidence=$confidence, sourceCount=${sourceReferences.size})"
}

/**
 * 绑定页面及其基础 revision 的表单补丁。
 *
 * @param pageId 补丁目标页面标识。
 * @param baseRevision 生成补丁时的页面 revision。
 * @param changes 待验证的字段变更。
 */
@Serializable
data class FormPatch(
    val pageId: String,
    val baseRevision: Long,
    val changes: List<FieldChange>,
) {
    /** 避免日志通过集合默认实现间接输出字段原值。 */
    override fun toString(): String =
        "FormPatch(pageId=$pageId, baseRevision=$baseRevision, changeCount=${changes.size})"
}

/** 通过协议序列化生成不共享调用方集合或 JSON backing 的表单补丁。 */
internal fun canonicalizePatch(patch: FormPatch): FormPatch? = try {
    Json.decodeFromString<FormPatch>(Json.encodeToString(patch))
} catch (_: Exception) {
    null
}

/** 通过同一协议边界深冻结单个字段建议。 */
internal fun canonicalizeFieldChange(change: FieldChange): FieldChange? = try {
    Json.decodeFromString<FieldChange>(Json.encodeToString(change))
} catch (_: Exception) {
    null
}
