package com.wzx.huitai.demo.model

import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoScreenModelTest {
    @Test
    fun `dispatch if revision rejects stale suggestion atomically and installs current suggestion`() {
        val screen = DemoScreenModel()
        screen.dispatch(DemoFormEvent.EditField(DemoFormState.FIELD_NAME, "user value"))

        assertFalse(screen.dispatchIfRevision(suggestion(baseRevision = 1), expectedRevision = 1))
        assertNull(screen.state.value.suggestionPatch)
        assertTrue(screen.dispatchIfRevision(suggestion(baseRevision = 2), expectedRevision = 2))
        assertTrue(screen.state.value.suggestionPatch != null)
    }

    private fun suggestion(baseRevision: Long): DemoFormEvent.SuggestPatch = DemoFormEvent.SuggestPatch(
        FormPatch(
            pageId = DemoFormState.PAGE_ID,
            baseRevision = baseRevision,
            changes = listOf(
                FieldChange(
                    fieldId = DemoFormState.FIELD_CONTACT,
                    previousValue = JsonPrimitive(""),
                    newValue = JsonPrimitive("suggested"),
                    reason = "Agent field suggestion",
                    confidence = 0.8,
                ),
            ),
        ),
    )
}
