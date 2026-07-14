package com.wzx.huitai.presentation.form

import com.wzx.huitai.presentation.context.PageContextSnapshot

/** 表单补丁拒绝原因；错误只描述类别，不携带字段原值。 */
enum class FormPatchErrorCode {
    PAGE_MISMATCH,
    CONTEXT_STALE,
    UNKNOWN_FIELD,
    DUPLICATE_FIELD,
    MISSING_PERMISSION,
    FIELD_READ_ONLY,
    PREVIOUS_VALUE_MISMATCH,
    FIELD_TYPE_MISMATCH,
    REQUIRED_VALUE_MISSING,
    INVALID_FIELD_VALUE,
    BUSINESS_RULE_VIOLATION,
    BUSINESS_RULE_FAILURE,
}

/**
 * 脱敏后的表单补丁错误。
 *
 * @param code 稳定错误码。
 * @param fieldId 字段级错误对应的字段标识；全局错误为空。
 * @param ruleId 业务规则错误的稳定规则标识；非业务规则错误为空。
 */
data class FormPatchError(
    val code: FormPatchErrorCode,
    val fieldId: String? = null,
    val ruleId: String? = null,
) {
    /** ruleId 属于注入数据，默认诊断只保留稳定错误码和字段范围。 */
    override fun toString(): String = "FormPatchError(code=$code, fieldId=$fieldId)"
}

/** 表单补丁验证结果；拒绝分支不能携带可应用补丁。 */
sealed interface FormPatchValidationResult {
    /** 只有全部检查通过时才返回原始补丁。 */
    data class Applicable(val patch: FormPatch) : FormPatchValidationResult

    /** 所有已发现错误的脱敏集合。 */
    data class Rejected(val errors: List<FormPatchError>) : FormPatchValidationResult
}

/**
 * 在任何 UI 状态变更前验证表单补丁。
 *
 * @param definitions 由可信业务页面提供的字段定义。
 * @param businessRules 由具体表单注入的通用无副作用规则。
 */
class FormPatchValidator(
    definitions: List<FormFieldDefinition>,
    private val businessRules: List<FormBusinessRule> = emptyList(),
) {
    private val definitionsById = definitions.associateBy(FormFieldDefinition::fieldId)

    init {
        require(definitionsById.size == definitions.size) { "字段定义标识必须唯一" }
    }

    /**
     * 根据当前页面快照和冻结权限验证补丁。
     *
     * @param patch Agent 提议的补丁。
     * @param snapshot 当前不可变页面事实。
     * @param permissions 本次验证使用的冻结权限集合。
     */
    fun validate(
        patch: FormPatch,
        snapshot: PageContextSnapshot,
        permissions: Set<String>,
    ): FormPatchValidationResult {
        if (patch.pageId != snapshot.pageId) {
            return FormPatchValidationResult.Rejected(listOf(FormPatchError(FormPatchErrorCode.PAGE_MISMATCH)))
        }
        if (patch.baseRevision != snapshot.revision) {
            return FormPatchValidationResult.Rejected(listOf(FormPatchError(FormPatchErrorCode.CONTEXT_STALE)))
        }

        val errors = mutableListOf<FormPatchError>()
        val duplicateIds = patch.changes.groupingBy(FieldChange::fieldId).eachCount()
            .filterValues { it > 1 }
            .keys
        duplicateIds.forEach { fieldId ->
            errors += FormPatchError(FormPatchErrorCode.DUPLICATE_FIELD, fieldId)
        }

        val snapshotFields = snapshot.fields.associateBy { it.id }
        val structurallyValidChanges = mutableListOf<FieldChange>()
        patch.changes
            .filterNot { it.fieldId in duplicateIds }
            .forEach { change ->
                val errorCountBeforeChange = errors.size
                val definition = definitionsById[change.fieldId]
                val field = snapshotFields[change.fieldId]
                if (definition == null || field == null) {
                    errors += FormPatchError(FormPatchErrorCode.UNKNOWN_FIELD, change.fieldId)
                    return@forEach
                }
                if (!permissions.containsAll(definition.requiredPermissions)) {
                    errors += FormPatchError(FormPatchErrorCode.MISSING_PERMISSION, change.fieldId)
                }
                if (!field.editable) {
                    errors += FormPatchError(FormPatchErrorCode.FIELD_READ_ONLY, change.fieldId)
                }
                if (!change.previousValue.structurallyEquals(field.value)) {
                    errors += FormPatchError(FormPatchErrorCode.PREVIOUS_VALUE_MISMATCH, change.fieldId)
                }
                val snapshotType = FormFieldType.fromWireName(field.type)
                if (snapshotType == null || snapshotType != definition.type) {
                    errors += FormPatchError(FormPatchErrorCode.FIELD_TYPE_MISMATCH, change.fieldId)
                    return@forEach
                }
                if (field.required && change.newValue.isAbsent()) {
                    errors += FormPatchError(FormPatchErrorCode.REQUIRED_VALUE_MISSING, change.fieldId)
                } else if (!definition.type.accepts(change.newValue, definition)) {
                    errors += FormPatchError(FormPatchErrorCode.INVALID_FIELD_VALUE, change.fieldId)
                }
                if (errors.size == errorCountBeforeChange) {
                    structurallyValidChanges += change
                }
            }

        val candidateValues = snapshotFields.mapValues { (_, field) -> field.value }.toMutableMap()
        structurallyValidChanges.forEach { change -> candidateValues[change.fieldId] = change.newValue }
        val ruleContext = FormBusinessRuleContext(
            pageId = patch.pageId,
            baseRevision = patch.baseRevision,
            candidateValues = candidateValues.toMap(),
            definitions = definitionsById.toMap(),
        )
        val ruleErrors = businessRules.flatMap { rule ->
            try {
                rule.validate(ruleContext).map { violation ->
                    FormPatchError(
                        code = FormPatchErrorCode.BUSINESS_RULE_VIOLATION,
                        fieldId = violation.fieldId,
                        ruleId = violation.ruleId,
                    )
                }
            } catch (_: Exception) {
                listOf(FormPatchError(FormPatchErrorCode.BUSINESS_RULE_FAILURE))
            }
        }
        val allErrors = errors + ruleErrors
        return if (allErrors.isEmpty()) {
            FormPatchValidationResult.Applicable(patch)
        } else {
            FormPatchValidationResult.Rejected(allErrors)
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.isAbsent(): Boolean =
    this == null || this == kotlinx.serialization.json.JsonNull

private fun kotlinx.serialization.json.JsonElement?.structurallyEquals(
    other: kotlinx.serialization.json.JsonElement?,
): Boolean = (isAbsent() && other.isAbsent()) || this == other
