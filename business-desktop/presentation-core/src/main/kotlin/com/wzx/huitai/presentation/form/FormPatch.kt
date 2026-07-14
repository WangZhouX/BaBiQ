package com.wzx.huitai.presentation.form

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Collections

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
) {
    init {
        requireSafeIdentifier(type, "来源类型格式无效")
        requireSafeIdentifier(id, "来源标识格式无效")
        require(label == null || label.isSafeDisplayText()) { "来源标签格式无效" }
    }

    /** 来源标识和标签来自不可信输入，默认诊断仅输出存在性。 */
    override fun toString(): String = "SourceReference(labelPresent=${label != null})"
}

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
class FieldChange private constructor(
    val fieldId: String,
    val previousValue: JsonElement? = null,
    val newValue: JsonElement? = null,
    val reason: String,
    val confidence: Double,
    val sourceReferences: List<SourceReference> = emptyList(),
) {
    constructor(
        fieldId: String,
        previousValue: JsonElement? = null,
        newValue: JsonElement? = null,
        reason: String,
        confidence: Double,
        sourceReferences: Collection<SourceReference> = emptyList(),
    ) : this(
        fieldId = fieldId,
        previousValue = freezeJsonElement(previousValue),
        newValue = freezeJsonElement(newValue),
        reason = reason,
        confidence = confidence,
        sourceReferences = immutableList(sourceReferences),
    )

    init {
        requireSafeIdentifier(fieldId, "字段标识格式无效")
        requireSafeReason(reason)
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "confidence 必须是 0 到 1 之间的有限值"
        }
    }

    /** 避免日志输出旧值、新值和建议原因。 */
    override fun toString(): String =
        "FieldChange(fieldId=$fieldId, confidence=$confidence, sourceCount=${sourceReferences.size})"

    override fun equals(other: Any?): Boolean = other is FieldChange &&
        fieldId == other.fieldId &&
        previousValue == other.previousValue &&
        newValue == other.newValue &&
        reason == other.reason &&
        confidence == other.confidence &&
        sourceReferences == other.sourceReferences

    override fun hashCode(): Int {
        var result = fieldId.hashCode()
        result = 31 * result + (previousValue?.hashCode() ?: 0)
        result = 31 * result + (newValue?.hashCode() ?: 0)
        result = 31 * result + reason.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + sourceReferences.hashCode()
        return result
    }
}

/**
 * 绑定页面及其基础 revision 的表单补丁。
 *
 * @param pageId 补丁目标页面标识。
 * @param baseRevision 生成补丁时的页面 revision。
 * @param changes 待验证的字段变更。
 */
@Serializable
class FormPatch private constructor(
    val pageId: String,
    val baseRevision: Long,
    val changes: List<FieldChange>,
) {
    constructor(
        pageId: String,
        baseRevision: Long,
        changes: Collection<FieldChange>,
    ) : this(
        pageId = pageId,
        baseRevision = baseRevision,
        changes = immutableList(changes),
    )

    init {
        requireSafeIdentifier(pageId, "页面标识格式无效")
        require(changes.isNotEmpty()) { "表单补丁至少包含一个字段变更" }
    }

    /** 避免日志通过集合默认实现间接输出字段原值。 */
    override fun toString(): String =
        "FormPatch(baseRevision=$baseRevision, changeCount=${changes.size})"

    override fun equals(other: Any?): Boolean = other is FormPatch &&
        pageId == other.pageId &&
        baseRevision == other.baseRevision &&
        changes == other.changes

    override fun hashCode(): Int {
        var result = pageId.hashCode()
        result = 31 * result + baseRevision.hashCode()
        result = 31 * result + changes.hashCode()
        return result
    }
}

/** 通过协议序列化生成不共享调用方集合或 JSON backing 的表单补丁。 */
internal fun canonicalizePatch(patch: FormPatch): FormPatch? = try {
    freezePatch(Json.decodeFromString<FormPatch>(Json.encodeToString(patch)))
} catch (_: Exception) {
    null
}

/** 通过同一协议边界深冻结单个字段建议。 */
internal fun canonicalizeFieldChange(change: FieldChange): FieldChange? = try {
    freezeFieldChange(Json.decodeFromString<FieldChange>(Json.encodeToString(change)))
} catch (_: Exception) {
    null
}

/** 深拷贝页面 JSON 值，避免规则接触页面快照的集合 backing。 */
internal fun canonicalizeJsonElement(value: JsonElement?): JsonElement? = try {
    value?.let { freezeJsonElement(Json.decodeFromString<JsonElement>(Json.encodeToString(it))) }
} catch (_: Exception) {
    null
}

/** 递归复制 JSON 容器，并用 JVM 不可修改集合包裹所有 backing。 */
private fun freezeJsonElement(value: JsonElement?): JsonElement? = when (value) {
    null -> null
    is JsonObject -> JsonObject(
        Collections.unmodifiableMap(
            LinkedHashMap(value.mapValues { (_, nested) -> requireNotNull(freezeJsonElement(nested)) }),
        ),
    )
    is JsonArray -> JsonArray(
        immutableList(value.map { nested -> requireNotNull(freezeJsonElement(nested)) }),
    )
    else -> value
}

private fun freezePatch(patch: FormPatch): FormPatch = FormPatch(
    pageId = patch.pageId,
    baseRevision = patch.baseRevision,
    changes = immutableList(patch.changes.map(::freezeFieldChange)),
)

private fun freezeFieldChange(change: FieldChange): FieldChange = FieldChange(
    fieldId = change.fieldId,
    previousValue = change.previousValue,
    newValue = change.newValue,
    reason = change.reason,
    confidence = change.confidence,
    sourceReferences = immutableList(change.sourceReferences),
)

/** 创建 JVM 层也拒绝写操作的列表快照。 */
internal fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
