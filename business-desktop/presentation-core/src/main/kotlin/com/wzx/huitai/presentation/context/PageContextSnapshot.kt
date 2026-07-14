package com.wzx.huitai.presentation.context

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 页面当前交互模式。 */
@Serializable
enum class PageMode {
    @SerialName("view")
    VIEW,

    @SerialName("edit")
    EDIT,

    @SerialName("create")
    CREATE,
}

/** 页面字段的 Agent 可见敏感等级。 */
@Serializable
enum class FieldSensitivity {
    @SerialName("public")
    PUBLIC,

    @SerialName("internal")
    INTERNAL,

    @SerialName("sensitive")
    SENSITIVE,

    @SerialName("secret")
    SECRET,
}

/**
 * 页面关联的业务实体引用。
 *
 * @param type 实体类型；该字符串是下游不可信数据。
 * @param id 实体稳定标识；该字符串是下游不可信数据。
 * @param displayName 可选显示名；该字符串是下游不可信数据。
 */
@Serializable
data class EntityReference(
    val type: String,
    val id: String,
    val displayName: String? = null,
)

/**
 * Agent 可见页面字段事实。
 *
 * @param id 页面内稳定字段标识。
 * @param label 用户可读标签；该字符串是下游不可信数据。
 * @param type 字段值类型。
 * @param value 当前结构化值。
 * @param editable 当前是否允许编辑。
 * @param required 当前是否必填。
 * @param validationErrors 字段校验消息；所有字符串都是下游不可信数据。
 * @param sensitivity 字段敏感等级。
 */
@Serializable
data class FieldContext(
    val id: String,
    val label: String,
    val type: String,
    val value: JsonElement? = null,
    val editable: Boolean,
    val required: Boolean,
    val validationErrors: List<String> = emptyList(),
    val sensitivity: FieldSensitivity,
)

/**
 * 当前页面可执行动作。
 *
 * @param id 稳定动作标识。
 * @param title 用户可读标题；该字符串是下游不可信数据。
 * @param description 动作说明；该字符串是下游不可信数据。
 * @param enabled 当前是否可执行。
 * @param inputSchema 动作输入结构；内部字符串均为下游不可信数据。
 */
@Serializable
data class AvailableAction(
    val id: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val inputSchema: JsonObject? = null,
)

/**
 * 页面校验汇总。
 *
 * @param valid 页面当前是否通过校验。
 * @param messages 校验消息；所有字符串都是下游不可信数据。
 */
@Serializable
data class ValidationSummary(
    val valid: Boolean,
    val messages: List<String> = emptyList(),
)

/**
 * 页面当前选择范围。
 *
 * @param kind 选择类型；该字符串是下游不可信数据。
 * @param ids 被选中对象标识；所有字符串都是下游不可信数据。
 * @param description 可选说明；该字符串是下游不可信数据。
 */
@Serializable
data class SelectionContext(
    val kind: String,
    val ids: List<String>,
    val description: String? = null,
)

/**
 * 从同一份不可变页面状态生成的 Agent 可见页面事实。
 *
 * 该模型只描述页面自身事实；可信传输身份和发布序列由 [PageContextPublisher] 构造。
 */
@Serializable
data class PageContextSnapshot(
    val snapshotId: String,
    val pageId: String,
    val pageTitle: String,
    val route: String,
    val revision: Long,
    val mode: PageMode,
    val entityReferences: List<EntityReference> = emptyList(),
    val fields: List<FieldContext> = emptyList(),
    val availableActions: List<AvailableAction> = emptyList(),
    val validationSummary: ValidationSummary,
    val selection: SelectionContext? = null,
)
