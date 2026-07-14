package com.wzx.huitai.presentation.form

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
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
@Serializable(with = FieldChangeSerializer::class)
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
@Serializable(with = FormPatchSerializer::class)
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
        validatePatchBudget(pageId, baseRevision, changes)
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
    freezeJsonElement(value)
} catch (_: Exception) {
    null
}

/** 递归复制 JSON 容器，并用 JVM 不可修改集合包裹所有 backing。 */
internal fun freezeJsonElement(value: JsonElement?): JsonElement? =
    value?.let { JsonFreezer().freeze(it, depth = 0) }

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
internal fun <T> immutableList(values: Collection<T>): List<T> {
    val snapshot = ArrayList<T>()
    values.forEach(snapshot::add)
    return Collections.unmodifiableList(snapshot)
}

private const val MAX_PATCH_CHANGES = 256
private const val MAX_PATCH_BYTES = 128 * 1024
private const val MAX_JSON_DEPTH = 32
private const val MAX_JSON_NODES = 10_000

/**
 * FormPatch 和 FieldChange 使用显式代理模型反序列化，确保协议入口也执行公开构造器的冻结与校验。
 */
internal object FieldChangeSerializer : KSerializer<FieldChange> {
    private val delegate = FieldChangeWire.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: FieldChange) {
        delegate.serialize(encoder, value.toWire())
    }

    override fun deserialize(decoder: Decoder): FieldChange = delegate.deserialize(decoder).toModel()
}

internal object FormPatchSerializer : KSerializer<FormPatch> {
    private val delegate = FormPatchWire.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: FormPatch) {
        delegate.serialize(encoder, value.toWire())
    }

    override fun deserialize(decoder: Decoder): FormPatch = delegate.deserialize(decoder).toModel()
}

@Serializable
private data class FieldChangeWire(
    val fieldId: String,
    val previousValue: JsonElement? = null,
    val newValue: JsonElement? = null,
    val reason: String,
    val confidence: Double,
    val sourceReferences: List<SourceReference> = emptyList(),
)

@Serializable
private data class FormPatchWire(
    val pageId: String,
    val baseRevision: Long,
    val changes: List<FieldChange>,
)

private fun FieldChange.toWire(): FieldChangeWire = FieldChangeWire(
    fieldId = fieldId,
    previousValue = previousValue,
    newValue = newValue,
    reason = reason,
    confidence = confidence,
    sourceReferences = sourceReferences,
)

private fun FieldChangeWire.toModel(): FieldChange = FieldChange(
    fieldId = fieldId,
    previousValue = previousValue,
    newValue = newValue,
    reason = reason,
    confidence = confidence,
    sourceReferences = sourceReferences,
)

private fun FormPatch.toWire(): FormPatchWire = FormPatchWire(
    pageId = pageId,
    baseRevision = baseRevision,
    changes = changes,
)

private fun FormPatchWire.toModel(): FormPatch = FormPatch(
    pageId = pageId,
    baseRevision = baseRevision,
    changes = changes,
)

/** 先用迭代遍历限制结构，再执行有界协议编码，避免深层 JSON 触发递归溢出。 */
private fun validatePatchBudget(
    pageId: String,
    baseRevision: Long,
    changes: List<FieldChange>,
) {
    require(changes.size <= MAX_PATCH_CHANGES) { "表单补丁字段变更数量超限" }

    var nodes = 0
    val rawBytes = CappedUtf8Counter(MAX_PATCH_BYTES)
    rawBytes.add(pageId)
    changes.forEach { change ->
        rawBytes.add(change.fieldId)
        rawBytes.add(change.reason)
        change.sourceReferences.forEach { source ->
            rawBytes.add(source.type)
            rawBytes.add(source.id)
            source.label?.let(rawBytes::add)
        }
        nodes += inspectJson(change.previousValue, rawBytes, MAX_JSON_NODES - nodes)
        nodes += inspectJson(change.newValue, rawBytes, MAX_JSON_NODES - nodes)
        require(nodes <= MAX_JSON_NODES) { "表单补丁 JSON 节点数量超限" }
    }

    val encoded = Json.encodeToString(
        FormPatchWire.serializer(),
        FormPatchWire(pageId = pageId, baseRevision = baseRevision, changes = changes),
    )
    require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATCH_BYTES) {
        "表单补丁协议大小超限"
    }
}

private fun inspectJson(
    value: JsonElement?,
    rawBytes: CappedUtf8Counter,
    remainingNodes: Int,
): Int {
    if (value == null) return 0
    require(remainingNodes > 0) { "表单补丁 JSON 节点数量超限" }

    val pending = ArrayDeque<Pair<JsonElement, Int>>()
    pending.addLast(value to 0)
    var nodes = 0
    while (pending.isNotEmpty()) {
        val (current, depth) = pending.removeLast()
        require(depth <= MAX_JSON_DEPTH) { "表单补丁 JSON 深度超限" }
        nodes += 1
        require(nodes <= remainingNodes) { "表单补丁 JSON 节点数量超限" }
        when (current) {
            is JsonObject -> current.entries.forEach { (key, nested) ->
                rawBytes.add(key)
                pending.addLast(nested to depth + 1)
            }
            is JsonArray -> current.forEach { nested -> pending.addLast(nested to depth + 1) }
            is JsonPrimitive -> rawBytes.add(current.content)
        }
    }
    return nodes
}

/** 单次遍历同时校验并冻结 JSON，避免在不可信 backing 上产生检查与复制的时间差。 */
private class JsonFreezer {
    private var nodes: Int = 0
    private val rawBytes = CappedUtf8Counter(MAX_PATCH_BYTES)

    fun freeze(value: JsonElement, depth: Int): JsonElement {
        require(depth <= MAX_JSON_DEPTH) { "表单补丁 JSON 深度超限" }
        nodes += 1
        require(nodes <= MAX_JSON_NODES) { "表单补丁 JSON 节点数量超限" }
        return when (value) {
            is JsonObject -> {
                val entries = LinkedHashMap<String, JsonElement>()
                value.forEach { (key, nested) ->
                    rawBytes.add(key)
                    entries[key] = freeze(nested, depth + 1)
                }
                JsonObject(Collections.unmodifiableMap(entries))
            }
            is JsonArray -> {
                val elements = ArrayList<JsonElement>()
                value.forEach { nested -> elements += freeze(nested, depth + 1) }
                JsonArray(Collections.unmodifiableList(elements))
            }
            is JsonPrimitive -> value.also { rawBytes.add(it.content) }
        }
    }
}

/** 只累计到上限后一字节，避免为异常大的输入分配完整 UTF-8 字节数组。 */
private class CappedUtf8Counter(
    private val limit: Int,
) {
    private var count: Int = 0

    fun add(value: String) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            count += when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                    index += 1
                    4
                }
                character.isSurrogate() -> 1
                else -> 3
            }
            require(count <= limit) { "表单补丁协议大小超限" }
            index += 1
        }
    }
}
