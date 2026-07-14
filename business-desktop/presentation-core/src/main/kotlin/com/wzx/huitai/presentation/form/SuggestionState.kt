package com.wzx.huitai.presentation.form

/**
 * 绑定单一页面 revision 的待处理字段建议。
 *
 * @param pageId 建议所属页面。
 * @param baseRevision 建议生成时的页面 revision。
 * @param pendingChanges 按字段唯一保存的待处理建议。
 */
class SuggestionState(
    val pageId: String,
    val baseRevision: Long,
    pendingChanges: List<FieldChange> = emptyList(),
) {
    val pendingChanges: List<FieldChange> = pendingChanges.map { change ->
        canonicalizeFieldChange(change) ?: throw IllegalArgumentException("字段建议无法安全冻结")
    }

    init {
        require(pendingChanges.map(FieldChange::fieldId).distinct().size == pendingChanges.size) {
            "待处理建议的字段标识必须唯一"
        }
    }

    /**
     * 添加建议；同字段建议在原位置被新建议替换。
     *
     * @param change 新的字段建议。
     */
    fun withSuggestion(change: FieldChange): SuggestionState {
        val canonicalChange = canonicalizeFieldChange(change)
            ?: throw IllegalArgumentException("字段建议无法安全冻结")
        val index = pendingChanges.indexOfFirst { it.fieldId == canonicalChange.fieldId }
        val nextChanges = if (index < 0) {
            pendingChanges + canonicalChange
        } else {
            pendingChanges.toMutableList().also { it[index] = canonicalChange }
        }
        return copyWith(nextChanges)
    }

    /**
     * 用户编辑字段或拒绝建议后，移除该字段建议。
     *
     * @param fieldId 需要移除建议的字段标识。
     */
    fun removeSuggestion(fieldId: String): SuggestionState =
        copyWith(pendingChanges.filterNot { it.fieldId == fieldId })

    /**
     * 生成单字段补丁；字段没有待处理建议时返回 null。
     *
     * @param fieldId 需要生成补丁的字段标识。
     */
    fun patchFor(fieldId: String): FormPatch? = pendingChanges
        .firstOrNull { it.fieldId == fieldId }
        ?.let { change -> FormPatch(pageId, baseRevision, listOf(change)) }

    /** 生成全部待处理建议补丁；没有建议时返回 null。 */
    fun patchForAll(): FormPatch? = pendingChanges
        .takeIf(List<FieldChange>::isNotEmpty)
        ?.let { changes -> FormPatch(pageId, baseRevision, changes) }

    /** 避免日志递归展开建议内容。 */
    override fun toString(): String =
        "SuggestionState(pageId=$pageId, baseRevision=$baseRevision, pendingCount=${pendingChanges.size})"

    override fun equals(other: Any?): Boolean = other is SuggestionState &&
        pageId == other.pageId &&
        baseRevision == other.baseRevision &&
        pendingChanges == other.pendingChanges

    override fun hashCode(): Int {
        var result = pageId.hashCode()
        result = 31 * result + baseRevision.hashCode()
        result = 31 * result + pendingChanges.hashCode()
        return result
    }

    private fun copyWith(changes: List<FieldChange>): SuggestionState =
        SuggestionState(pageId = pageId, baseRevision = baseRevision, pendingChanges = changes)
}
