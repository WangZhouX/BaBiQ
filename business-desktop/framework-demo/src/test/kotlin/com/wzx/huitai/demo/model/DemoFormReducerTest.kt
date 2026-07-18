package com.wzx.huitai.demo.model

import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DemoFormReducerTest {
    private val reducer = DemoFormReducer()

    @Test
    fun `用户编辑字段后只更新目标字段并递增版本`() {
        val initial = DemoFormState()
        assertEquals(1, initial.revision)

        val updated = reducer.reduce(
            initial,
            DemoFormEvent.EditField(DemoFormState.FIELD_NAME, "季度资料"),
        )

        assertEquals("季度资料", updated.values.name)
        assertEquals(initial.values.type, updated.values.type)
        assertEquals(2, updated.revision)
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
        assertEquals(listOf(DemoFormState.FIELD_STATUS), accepted.suggestionPatch?.changes?.map(FieldChange::fieldId))
        assertEquals(accepted.revision, accepted.suggestionPatch?.baseRevision)
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
    fun `用户编辑一个建议字段只移除该字段并把其余建议重基到新版本`() {
        val initial = DemoFormState()
        val suggested = reducer.reduce(
            initial,
            DemoFormEvent.SuggestPatch(
                patch(
                    initial,
                    DemoFormState.FIELD_NAME to "旧建议",
                    DemoFormState.FIELD_STATUS to "保留建议",
                ),
            ),
        )

        val edited = reducer.reduce(
            suggested,
            DemoFormEvent.EditField(DemoFormState.FIELD_NAME, "用户新输入"),
        )
        val accepted = reducer.reduce(edited, DemoFormEvent.AcceptAllSuggestions)

        assertFalse(edited.suggestionIsStale)
        assertEquals("用户新输入", edited.values.name)
        assertEquals(listOf(DemoFormState.FIELD_STATUS), edited.suggestionPatch?.changes?.map(FieldChange::fieldId))
        assertEquals(edited.revision, edited.suggestionPatch?.baseRevision)
        assertEquals("用户新输入", accepted.values.name)
        assertEquals("保留建议", accepted.values.status)
        assertEquals(edited.revision + 1, accepted.revision)
        assertEquals(null, accepted.suggestionPatch)
    }

    @Test
    fun `错误页面或基础版本的建议不会安装到状态`() {
        val initial = DemoFormState()
        val wrongPage = FormPatch(
            pageId = "other.page",
            baseRevision = initial.revision,
            changes = patch(initial, DemoFormState.FIELD_NAME to "建议值").changes,
        )
        val wrongRevision = FormPatch(
            pageId = DemoFormState.PAGE_ID,
            baseRevision = initial.revision + 1,
            changes = patch(initial, DemoFormState.FIELD_NAME to "建议值").changes,
        )

        assertEquals(initial, reducer.reduce(initial, DemoFormEvent.SuggestPatch(wrongPage)))
        assertEquals(initial, reducer.reduce(initial, DemoFormEvent.SuggestPatch(wrongRevision)))
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
