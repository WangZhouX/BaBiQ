package com.wzx.huitai.presentation.form

import com.wzx.huitai.presentation.context.PageContextSnapshot
import java.util.Collections
import java.util.concurrent.CancellationException

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
    INVALID_PATCH_ENCODING,
    INVALID_PAGE_CONTEXT,
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
    override fun toString(): String =
        "FormPatchError(code=$code, hasField=${fieldId != null}, hasRule=${ruleId != null})"
}

/** 表单补丁验证结果；拒绝分支不能携带可应用补丁。 */
sealed interface FormPatchValidationResult {
    /** 只暴露验证通过事实；具体实现由 validator 私有持有。 */
    sealed interface Applicable : FormPatchValidationResult {
        val patch: FormPatch
    }

    /** 所有已发现错误的脱敏集合。 */
    class Rejected(errors: Collection<FormPatchError>) : FormPatchValidationResult {
        val errors: List<FormPatchError> = immutableList(errors)

        override fun equals(other: Any?): Boolean = other is Rejected && errors == other.errors

        override fun hashCode(): Int = errors.hashCode()

        override fun toString(): String = "Rejected(errorCount=${errors.size})"
    }
}

/**
 * 在任何 UI 状态变更前验证表单补丁。
 *
 * @param definitions 由可信业务页面提供的字段定义。
 * @param businessRules 由具体表单注入的通用无副作用规则。
 */
class FormPatchValidator(
    definitions: List<FormFieldDefinition>,
    businessRules: List<FormBusinessRule> = emptyList(),
) {
    private val frozenDefinitions = immutableList(definitions.map(FormFieldDefinition::frozenCopy))
    private val definitionsById = immutableMap(frozenDefinitions.associateBy(FormFieldDefinition::fieldId))
    private val businessRules = immutableList(businessRules)

    init {
        require(definitionsById.size == frozenDefinitions.size) { "字段定义标识必须唯一" }
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
        val canonicalPatch = canonicalizePatch(patch)
            ?: return FormPatchValidationResult.Rejected(
                listOf(FormPatchError(FormPatchErrorCode.INVALID_PATCH_ENCODING)),
            )
        if (canonicalPatch.pageId != snapshot.pageId) {
            return FormPatchValidationResult.Rejected(listOf(FormPatchError(FormPatchErrorCode.PAGE_MISMATCH)))
        }
        if (canonicalPatch.baseRevision != snapshot.revision) {
            return FormPatchValidationResult.Rejected(listOf(FormPatchError(FormPatchErrorCode.CONTEXT_STALE)))
        }
        if (!snapshot.hasValidFieldIdentity()) {
            return FormPatchValidationResult.Rejected(
                listOf(FormPatchError(FormPatchErrorCode.INVALID_PAGE_CONTEXT)),
            )
        }

        val errors = mutableListOf<FormPatchError>()
        val duplicateIds = canonicalPatch.changes.groupingBy(FieldChange::fieldId).eachCount()
            .filterValues { it > 1 }
            .keys
        duplicateIds.forEach { fieldId ->
            errors += FormPatchError(FormPatchErrorCode.DUPLICATE_FIELD, fieldId)
        }

        val snapshotFields = snapshot.fields.associateBy { it.id }
        val structurallyValidChanges = mutableListOf<FieldChange>()
        canonicalPatch.changes
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

        val candidateValues = snapshotFields.mapValues { (_, field) -> canonicalizeJsonElement(field.value) }.toMutableMap()
        structurallyValidChanges.forEach { change -> candidateValues[change.fieldId] = change.newValue }
        val ruleContext = FormBusinessRuleContext(
            pageId = canonicalPatch.pageId,
            baseRevision = canonicalPatch.baseRevision,
            candidateValues = immutableMap(candidateValues),
            definitions = definitionsById,
        )
        val ruleErrors = businessRules.flatMap { rule ->
            try {
                val violations = rule.validate(ruleContext)
                if (violations.any { violation ->
                        !violation.ruleId.isSafeStableIdentifier() ||
                            (violation.fieldId != null && violation.fieldId !in definitionsById)
                    }
                ) {
                    listOf(FormPatchError(FormPatchErrorCode.BUSINESS_RULE_FAILURE))
                } else {
                    violations.map { violation ->
                        FormPatchError(
                            code = FormPatchErrorCode.BUSINESS_RULE_VIOLATION,
                            fieldId = violation.fieldId,
                            ruleId = violation.ruleId,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                listOf(FormPatchError(FormPatchErrorCode.BUSINESS_RULE_FAILURE))
            }
        }
        val allErrors = errors + ruleErrors
        return if (allErrors.isEmpty()) {
            ApplicableResult(canonicalPatch)
        } else {
            FormPatchValidationResult.Rejected(allErrors)
        }
    }

    /** 防止模块内其他调用方绕过 validator 伪造 Applicable。 */
    private class ApplicableResult(
        override val patch: FormPatch,
    ) : FormPatchValidationResult.Applicable {
        override fun equals(other: Any?): Boolean =
            other is FormPatchValidationResult.Applicable && patch == other.patch

        override fun hashCode(): Int = patch.hashCode()

        override fun toString(): String = "Applicable(changeCount=${patch.changes.size})"
    }
}

private fun kotlinx.serialization.json.JsonElement?.isAbsent(): Boolean =
    this == null || this == kotlinx.serialization.json.JsonNull

private fun kotlinx.serialization.json.JsonElement?.structurallyEquals(
    other: kotlinx.serialization.json.JsonElement?,
): Boolean = (isAbsent() && other.isAbsent()) || this == other

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun PageContextSnapshot.hasValidFieldIdentity(): Boolean {
    val ids = fields.map { it.id }
    return ids.all(String::isSafeStableIdentifier) && ids.distinct().size == ids.size
}
