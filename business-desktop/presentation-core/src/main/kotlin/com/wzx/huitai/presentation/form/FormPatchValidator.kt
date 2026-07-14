package com.wzx.huitai.presentation.form

import com.wzx.huitai.presentation.context.PageContextSnapshot
import com.wzx.huitai.presentation.context.AvailableAction
import com.wzx.huitai.presentation.context.FieldContext
import com.wzx.huitai.presentation.context.SelectionContext
import com.wzx.huitai.presentation.context.ValidationSummary
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
    /**
     * 仅证明补丁对传入的页面、权限、定义和规则事实通过校验，不是授权能力。
     * 下游执行前仍必须按当前认证身份和页面 revision 重新校验。
     */
    class Applicable private constructor(
        val patch: FormPatch,
    ) : FormPatchValidationResult {
        companion object {
            /** 同模块调用也必须提交全部验证事实；唯一入口自身执行完整算法。 */
            @JvmSynthetic
            internal fun validate(
                patch: FormPatch,
                snapshot: PageContextSnapshot,
                permissions: Set<String>,
                definitions: List<FormFieldDefinition>,
                businessRules: List<FormBusinessRule>,
            ): FormPatchValidationResult {
                val frozenDefinitions = immutableList(definitions.map(FormFieldDefinition::frozenCopy))
                val definitionsById = immutableMap(frozenDefinitions.associateBy(FormFieldDefinition::fieldId))
                require(definitionsById.size == frozenDefinitions.size) { "字段定义标识必须唯一" }
                return validateFormPatch(
                    patch = patch,
                    snapshot = snapshot,
                    permissions = permissions,
                    definitionsById = definitionsById,
                    businessRules = immutableList(businessRules),
                    onApplicable = ::Applicable,
                )
            }
        }

        override fun toString(): String = "Applicable(changeCount=${patch.changes.size})"
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
    private val businessRules = immutableList(businessRules)

    init {
        require(frozenDefinitions.map(FormFieldDefinition::fieldId).distinct().size == frozenDefinitions.size) {
            "字段定义标识必须唯一"
        }
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
    ): FormPatchValidationResult = FormPatchValidationResult.Applicable.validate(
        patch = patch,
        snapshot = snapshot,
        permissions = permissions,
        definitions = frozenDefinitions,
        businessRules = businessRules,
    )
}

/** 完整验证算法是 Applicable 私有构造器的唯一调用链。 */
private fun validateFormPatch(
    patch: FormPatch,
    snapshot: PageContextSnapshot,
    permissions: Set<String>,
    definitionsById: Map<String, FormFieldDefinition>,
    businessRules: List<FormBusinessRule>,
    onApplicable: (FormPatch) -> FormPatchValidationResult.Applicable,
): FormPatchValidationResult {
    val canonicalPatch = canonicalizePatch(patch)
        ?: return FormPatchValidationResult.Rejected(
            listOf(FormPatchError(FormPatchErrorCode.INVALID_PATCH_ENCODING)),
        )
    val frozenPermissions = try {
        immutableSet(permissions)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return FormPatchValidationResult.Rejected(
            listOf(FormPatchError(FormPatchErrorCode.INVALID_PAGE_CONTEXT)),
        )
    }
    val frozenSnapshot = try {
        snapshot.frozenCopy()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return FormPatchValidationResult.Rejected(
            listOf(FormPatchError(FormPatchErrorCode.INVALID_PAGE_CONTEXT)),
        )
    }
    /*
     * 两个外部集合无法形成真正原子快照。授权事实必须优先冻结：页面迭代器即使回写调用方权限，
     * 也不能提升本轮 frozenPermissions；后续所有阶段只读取彼此独立的只读副本。
     */
    if (canonicalPatch.pageId != frozenSnapshot.pageId) {
        return FormPatchValidationResult.Rejected(listOf(FormPatchError(FormPatchErrorCode.PAGE_MISMATCH)))
    }
    if (canonicalPatch.baseRevision != frozenSnapshot.revision) {
        return FormPatchValidationResult.Rejected(listOf(FormPatchError(FormPatchErrorCode.CONTEXT_STALE)))
    }
    if (!frozenSnapshot.hasValidFieldIdentity()) {
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

    val snapshotFields = frozenSnapshot.fields.associateBy { it.id }
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
            if (!frozenPermissions.containsAll(definition.requiredPermissions)) {
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
    val ruleErrors = mutableListOf<FormPatchError>()
    businessRules.forEachIndexed { ruleIndex, rule ->
        if (ruleErrors.size >= MAX_RULE_VIOLATIONS_TOTAL) return@forEachIndexed
        try {
            val violations = snapshotRuleViolations(rule.validate(ruleContext))
            if (violations.any { violation ->
                    !violation.ruleId.isSafeStableIdentifier() ||
                        (violation.fieldId != null &&
                            (violation.fieldId !in definitionsById || violation.fieldId !in snapshotFields))
                }
            ) {
                ruleErrors += FormPatchError(FormPatchErrorCode.BUSINESS_RULE_FAILURE)
            } else {
                val remaining = MAX_RULE_VIOLATIONS_TOTAL - ruleErrors.size
                if (violations.size > remaining) {
                    if (remaining > 0) {
                        ruleErrors += violations.take(remaining - 1).map { violation ->
                            FormPatchError(
                                code = FormPatchErrorCode.BUSINESS_RULE_VIOLATION,
                                fieldId = violation.fieldId,
                                ruleId = violation.ruleId,
                            )
                        }
                        ruleErrors += FormPatchError(FormPatchErrorCode.BUSINESS_RULE_FAILURE)
                    }
                } else {
                    if (violations.size == remaining && remaining > 0 && ruleIndex < businessRules.lastIndex) {
                        ruleErrors += violations.take(remaining - 1).map { violation ->
                            FormPatchError(
                                code = FormPatchErrorCode.BUSINESS_RULE_VIOLATION,
                                fieldId = violation.fieldId,
                                ruleId = violation.ruleId,
                            )
                        }
                        ruleErrors += FormPatchError(FormPatchErrorCode.BUSINESS_RULE_FAILURE)
                    } else {
                        ruleErrors += violations.map { violation -> FormPatchError(
                            code = FormPatchErrorCode.BUSINESS_RULE_VIOLATION,
                            fieldId = violation.fieldId,
                            ruleId = violation.ruleId,
                        ) }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ruleErrors += FormPatchError(FormPatchErrorCode.BUSINESS_RULE_FAILURE)
        }
    }
    val allErrors = errors + ruleErrors
    return if (allErrors.isEmpty()) {
        onApplicable(canonicalPatch)
    } else {
        FormPatchValidationResult.Rejected(allErrors)
    }
}

private const val MAX_RULE_VIOLATIONS_PER_RULE = 64
private const val MAX_RULE_VIOLATIONS_TOTAL = 256

/** 在复制前检查声明数量，并用单次有界遍历冻结规则输出。 */
private fun snapshotRuleViolations(
    violations: List<FormBusinessRuleViolation>,
): List<FormBusinessRuleViolation> {
    val declaredSize = violations.size
    require(declaredSize <= MAX_RULE_VIOLATIONS_PER_RULE) { "业务规则结果数量超限" }
    val snapshot = ArrayList<FormBusinessRuleViolation>(declaredSize)
    val iterator = violations.iterator()
    while (iterator.hasNext()) {
        require(snapshot.size < MAX_RULE_VIOLATIONS_PER_RULE) { "业务规则结果数量超限" }
        snapshot += iterator.next()
    }
    require(snapshot.size == declaredSize) { "业务规则结果快照不一致" }
    return immutableList(snapshot)
}

private fun kotlinx.serialization.json.JsonElement?.isAbsent(): Boolean =
    this == null || this == kotlinx.serialization.json.JsonNull

private fun kotlinx.serialization.json.JsonElement?.structurallyEquals(
    other: kotlinx.serialization.json.JsonElement?,
): Boolean = (isAbsent() && other.isAbsent()) || this == other

private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))

private fun <T> immutableSet(values: Set<T>): Set<T> {
    val snapshot = LinkedHashSet<T>()
    values.forEach(snapshot::add)
    return Collections.unmodifiableSet(snapshot)
}

/** 校验入口一次性冻结完整页面事实，后续阶段不再读取调用方持有的集合或 JSON backing。 */
private fun PageContextSnapshot.frozenCopy(): PageContextSnapshot = PageContextSnapshot(
    snapshotId = snapshotId,
    pageId = pageId,
    pageTitle = pageTitle,
    route = route,
    revision = revision,
    mode = mode,
    entityReferences = immutableList(entityReferences.map { reference -> reference.copy() }),
    fields = immutableList(fields.map(FieldContext::frozenCopy)),
    availableActions = immutableList(availableActions.map(AvailableAction::frozenCopy)),
    validationSummary = validationSummary.frozenCopy(),
    selection = selection?.frozenCopy(),
)

private fun FieldContext.frozenCopy(): FieldContext = copy(
    value = freezeJsonElement(value),
    validationErrors = immutableList(validationErrors),
)

private fun AvailableAction.frozenCopy(): AvailableAction = copy(
    inputSchema = inputSchema?.let { schema -> freezeJsonElement(schema) as kotlinx.serialization.json.JsonObject },
)

private fun ValidationSummary.frozenCopy(): ValidationSummary = copy(
    messages = immutableList(messages),
)

private fun SelectionContext.frozenCopy(): SelectionContext = copy(
    ids = immutableList(ids),
)

private fun PageContextSnapshot.hasValidFieldIdentity(): Boolean {
    val ids = fields.map { it.id }
    return ids.all(String::isSafeStableIdentifier) && ids.distinct().size == ids.size
}
