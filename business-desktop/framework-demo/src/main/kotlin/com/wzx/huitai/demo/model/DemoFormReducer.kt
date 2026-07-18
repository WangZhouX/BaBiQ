package com.wzx.huitai.demo.model

import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import com.wzx.huitai.presentation.screen.ScreenReducer
import kotlinx.serialization.json.JsonPrimitive

/** 只进行确定性状态计算、不执行任何副作用的演示表单 reducer。 */
class DemoFormReducer : ScreenReducer<DemoFormState, DemoFormEvent> {
    /** 根据当前不可变状态和强类型事件计算下一状态。 */
    override fun reduce(state: DemoFormState, event: DemoFormEvent): DemoFormState = when (event) {
        is DemoFormEvent.EditField -> editField(state, event.fieldId, event.value)
        is DemoFormEvent.SuggestPatch -> if (
            event.patch.pageId == DemoFormState.PAGE_ID && event.patch.baseRevision == state.revision
        ) {
            state.copy(suggestionPatch = event.patch)
        } else {
            state
        }
        is DemoFormEvent.AcceptSuggestion -> acceptSuggestion(state, event.fieldId)
        DemoFormEvent.AcceptAllSuggestions -> state.suggestionPatch
            ?.takeUnless { state.suggestionIsStale }
            ?.let { applyPatch(state, it) }
            ?: state
        is DemoFormEvent.ApplyPatch -> applyPatch(state, event.patch)
        is DemoFormEvent.Navigate -> state.copy(
            route = event.route,
            revision = state.revision + 1,
            suggestionPatch = null,
        )
    }

    private fun editField(state: DemoFormState, fieldId: String, value: String): DemoFormState {
        if (fieldId !in DemoFormState.FIELD_IDS) return state
        val nextRevision = state.revision + 1
        val remainingSuggestions = state.suggestionPatch
            ?.takeUnless { state.suggestionIsStale }
            ?.changes
            ?.filterNot { it.fieldId == fieldId }
            .orEmpty()
        return state.copy(
            values = state.values.withValue(fieldId, value),
            revision = nextRevision,
            suggestionPatch = remainingSuggestions.takeIf(List<FieldChange>::isNotEmpty)?.let { changes ->
                FormPatch(DemoFormState.PAGE_ID, nextRevision, changes)
            },
        )
    }

    private fun acceptSuggestion(state: DemoFormState, fieldId: String): DemoFormState {
        val suggestion = state.suggestionPatch?.takeUnless { state.suggestionIsStale } ?: return state
        val change = suggestion.changes.firstOrNull { it.fieldId == fieldId } ?: return state
        return applyPatch(
            state,
            FormPatch(
                pageId = suggestion.pageId,
                baseRevision = suggestion.baseRevision,
                changes = listOf(change),
            ),
        )
    }

    /** 原子验证并应用整个补丁，失败时返回原状态。 */
    private fun applyPatch(state: DemoFormState, patch: FormPatch): DemoFormState {
        if (patch.pageId != DemoFormState.PAGE_ID || patch.baseRevision != state.revision) return state
        if (patch.changes.map(FieldChange::fieldId).distinct().size != patch.changes.size) return state
        var nextValues = state.values
        patch.changes.forEach { change ->
            if (change.fieldId !in DemoFormState.FIELD_IDS) return state
            if (change.previousValue != JsonPrimitive(state.values.valueOf(change.fieldId))) return state
            val nextValue = (change.newValue as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?: return state
            nextValues = nextValues.withValue(change.fieldId, nextValue)
        }
        val nextRevision = state.revision + 1
        val appliedFields = patch.changes.mapTo(linkedSetOf(), FieldChange::fieldId)
        val remainingSuggestions = state.suggestionPatch
            ?.takeIf { it.pageId == patch.pageId && it.baseRevision == patch.baseRevision }
            ?.changes
            ?.filterNot { it.fieldId in appliedFields }
            .orEmpty()
        return state.copy(
            values = nextValues,
            revision = nextRevision,
            suggestionPatch = remainingSuggestions.takeIf(List<FieldChange>::isNotEmpty)?.let { changes ->
                FormPatch(DemoFormState.PAGE_ID, nextRevision, changes)
            },
        )
    }
}
