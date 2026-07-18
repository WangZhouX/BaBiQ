package com.wzx.huitai.demo.model

import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoFormReducerTest {
    private val reducer = DemoFormReducer()

    @Test
    fun `用户编辑字段后只更新目标字段并递增版本`() {
        val initial = DemoFormState()

        val updated = reducer.reduce(
            initial,
            DemoFormEvent.EditField(DemoFormState.FIELD_NAME, "季度资料"),
        )

        assertEquals("季度资料", updated.values.name)
        assertEquals(initial.values.type, updated.values.type)
        assertEquals(1, updated.revision)
    }

    @Test
    fun `生成建议不修改已提交值且接受单字段只应用该字段`() {
        val initial = DemoFormState()
        val patch = patch(
            initial,
            DemoFormState.FIELD_NAME to "建议名称",
            DemoFormState.FIELD_STATUS to "待复核",
        )

        val suggested = reducer.reduce(initial, DemoFormEvent.SuggestPatch(patch))
        val accepted = reducer.reduce(
            suggested,
            DemoFormEvent.AcceptSuggestion(DemoFormState.FIELD_NAME),
        )

        assertEquals(initial.values, suggested.values)
        assertEquals("建议名称", accepted.values.name)
        assertEquals(initial.values.status, accepted.values.status)
        assertEquals(initial.revision + 1, accepted.revision)
    }

    @Test
    fun `接受全部建议用同一基础版本补丁一次性提交`() {
        val initial = DemoFormState()
        val patch = patch(
            initial,
            DemoFormState.FIELD_NAME to "建议名称",
            DemoFormState.FIELD_STATUS to "已复核",
            DemoFormState.FIELD_DETAILS to "统一应用",
        )
        val suggested = reducer.reduce(initial, DemoFormEvent.SuggestPatch(patch))

        val accepted = reducer.reduce(suggested, DemoFormEvent.AcceptAllSuggestions)

        assertEquals("建议名称", accepted.values.name)
        assertEquals("已复核", accepted.values.status)
        assertEquals("统一应用", accepted.values.details)
        assertEquals(initial.revision + 1, accepted.revision)
        assertFalse(accepted.suggestionIsStale)
    }

    @Test
    fun `建议生成后用户编辑会使旧补丁失效且不能覆盖新输入`() {
        val initial = DemoFormState()
        val suggested = reducer.reduce(
            initial,
            DemoFormEvent.SuggestPatch(
                patch(initial, DemoFormState.FIELD_NAME to "旧建议"),
            ),
        )

        val edited = reducer.reduce(
            suggested,
            DemoFormEvent.EditField(DemoFormState.FIELD_NAME, "用户新输入"),
        )
        val rejected = reducer.reduce(edited, DemoFormEvent.AcceptAllSuggestions)

        assertTrue(edited.suggestionIsStale)
        assertEquals("用户新输入", rejected.values.name)
        assertEquals(edited.revision, rejected.revision)
    }

    private fun patch(
        state: DemoFormState,
        vararg replacements: Pair<String, String>,
    ): FormPatch = FormPatch(
        pageId = DemoFormState.PAGE_ID,
        baseRevision = state.revision,
        changes = replacements.map { (fieldId, value) ->
            FieldChange(
                fieldId = fieldId,
                previousValue = JsonPrimitive(state.values.valueOf(fieldId)),
                newValue = JsonPrimitive(value),
                reason = "演示建议",
                confidence = 0.9,
            )
        },
    )
}
