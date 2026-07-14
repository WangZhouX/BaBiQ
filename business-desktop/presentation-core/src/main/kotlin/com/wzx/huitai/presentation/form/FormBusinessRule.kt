package com.wzx.huitai.presentation.form

import kotlinx.serialization.json.JsonElement

/**
 * 业务规则读取的不可变候选表单事实。
 *
 * @param pageId 当前页面标识。
 * @param baseRevision 补丁绑定的基础 revision。
 * @param candidateValues 当前字段值与已验证建议合并后的完整候选值。
 * @param definitions 当前页面可信字段定义。
 */
data class FormBusinessRuleContext(
    val pageId: String,
    val baseRevision: Long,
    val candidateValues: Map<String, JsonElement?>,
    val definitions: Map<String, FormFieldDefinition>,
) {
    /** 避免日志展开候选字段原值。 */
    override fun toString(): String =
        "FormBusinessRuleContext(pageId=$pageId, baseRevision=$baseRevision, candidateCount=${candidateValues.size})"
}

/**
 * 通用业务规则违规结果。
 *
 * @param ruleId 规则的稳定标识，不得包含字段原始内容。
 * @param fieldId 可选的关联字段；为空表示页面级违规。
 */
data class FormBusinessRuleViolation(
    val ruleId: String,
    val fieldId: String? = null,
) {
    init {
        require(ruleId.isNotBlank()) { "业务规则标识不能为空" }
    }

    /** 规则标识来自注入代码，默认诊断仅输出关联范围。 */
    override fun toString(): String = "FormBusinessRuleViolation(fieldId=$fieldId)"
}

/** 由具体业务表单注入的无副作用校验规则。 */
fun interface FormBusinessRule {
    /**
     * 根据完整候选事实返回零个或多个脱敏违规结果。
     *
     * @param context 当前页面的完整候选事实。
     */
    fun validate(context: FormBusinessRuleContext): List<FormBusinessRuleViolation>
}
