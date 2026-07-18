package com.wzx.huitai.demo.model

import com.wzx.huitai.presentation.form.FormPatch

/** 通用演示表单的七字段不可变值。 */
data class DemoFormValues(
    /** 资料名称。 */
    val name: String = "未命名资料",
    /** 资料类型。 */
    val type: String = "通用",
    /** 联系人。 */
    val contact: String = "",
    /** 金额。 */
    val amount: String = "0",
    /** 日期。 */
    val date: String = "",
    /** 状态。 */
    val status: String = "草稿",
    /** 详细说明。 */
    val details: String = "",
) {
    /** 按稳定字段标识读取当前值。 */
    fun valueOf(fieldId: String): String = when (fieldId) {
        DemoFormState.FIELD_NAME -> name
        DemoFormState.FIELD_TYPE -> type
        DemoFormState.FIELD_CONTACT -> contact
        DemoFormState.FIELD_AMOUNT -> amount
        DemoFormState.FIELD_DATE -> date
        DemoFormState.FIELD_STATUS -> status
        DemoFormState.FIELD_DETAILS -> details
        else -> throw IllegalArgumentException("未知演示字段")
    }

    /** 返回只修改目标字段的新值对象。 */
    fun withValue(fieldId: String, value: String): DemoFormValues = when (fieldId) {
        DemoFormState.FIELD_NAME -> copy(name = value)
        DemoFormState.FIELD_TYPE -> copy(type = value)
        DemoFormState.FIELD_CONTACT -> copy(contact = value)
        DemoFormState.FIELD_AMOUNT -> copy(amount = value)
        DemoFormState.FIELD_DATE -> copy(date = value)
        DemoFormState.FIELD_STATUS -> copy(status = value)
        DemoFormState.FIELD_DETAILS -> copy(details = value)
        else -> throw IllegalArgumentException("未知演示字段")
    }
}

/** 通用演示页面的单一不可变状态。 */
data class DemoFormState(
    /** 已提交到页面状态的七字段值。 */
    val values: DemoFormValues = DemoFormValues(),
    /** 每次页面写入成功后递增的上下文版本。 */
    val revision: Long = 1,
    /** 当前演示页面路由。 */
    val route: String = DEFAULT_ROUTE,
    /** 绑定生成时版本的待处理建议补丁。 */
    val suggestionPatch: FormPatch? = null,
) {
    /** 当前建议是否已因页面版本变化而失效。 */
    val suggestionIsStale: Boolean
        get() = suggestionPatch?.baseRevision?.let { it != revision } ?: false

    companion object {
        const val PAGE_ID = "demo.form"
        const val DEFAULT_ROUTE = "/demo/form"
        const val FIELD_NAME = "material_name"
        const val FIELD_TYPE = "material_type"
        const val FIELD_CONTACT = "contact"
        const val FIELD_AMOUNT = "amount"
        const val FIELD_DATE = "date"
        const val FIELD_STATUS = "status"
        const val FIELD_DETAILS = "details"

        /** 页面允许出现的七个稳定字段标识。 */
        val FIELD_IDS: Set<String> = linkedSetOf(
            FIELD_NAME,
            FIELD_TYPE,
            FIELD_CONTACT,
            FIELD_AMOUNT,
            FIELD_DATE,
            FIELD_STATUS,
            FIELD_DETAILS,
        )
    }
}
