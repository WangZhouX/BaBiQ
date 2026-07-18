package com.wzx.huitai.desktop.ui.form

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.state.BusinessFieldSuggestion
import com.wzx.huitai.presentation.form.FieldChange
import com.wzx.huitai.presentation.form.FormPatch
import com.wzx.huitai.presentation.form.SourceReference
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DemoFormPanelTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `renders exactly seven generic fields and suggestion provenance`() {
        rule.setContent {
            DemoFormPanel(
                state = suggestedFormState(),
                suggestions = suggestions(),
            )
        }

        listOf("资料名称", "资料类型", "联系人", "金额", "日期", "状态", "详细说明").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onAllNodesWithText("来源：用户输入").assertCountEquals(2)
        rule.onNodeWithText("置信度 92%").assertExists()
    }

    @Test
    fun `accept callbacks carry field id and patch base revision`() {
        var acceptedField: Pair<String, Long>? = null
        var acceptedAllRevision: Long? = null
        rule.setContent {
            DemoFormPanel(
                state = suggestedFormState(),
                suggestions = suggestions(),
                onAcceptSuggestion = { fieldId, baseRevision -> acceptedField = fieldId to baseRevision },
                onAcceptAllSuggestions = { baseRevision -> acceptedAllRevision = baseRevision },
            )
        }

        rule.onNodeWithTag("accept-suggestion-${DemoFormState.FIELD_NAME}").performClick()
        rule.onNodeWithTag("accept-all-suggestions").performScrollTo().performClick()

        assertEquals(DemoFormState.FIELD_NAME to 7L, acceptedField)
        assertEquals(7L, acceptedAllRevision)
    }

    @Test
    fun `user edit callback identifies one field and helper removes only its suggestion`() {
        var edited: Pair<String, String>? = null
        rule.setContent {
            DemoFormPanel(
                state = suggestedFormState(),
                suggestions = suggestions(),
                onFieldEdited = { fieldId, value -> edited = fieldId to value },
            )
        }

        rule.onNodeWithTag("form-field-${DemoFormState.FIELD_NAME}").performTextReplacement("用户新名称")

        assertEquals(DemoFormState.FIELD_NAME to "用户新名称", edited)
        val remaining = suggestionsAfterUserEdit(suggestions(), DemoFormState.FIELD_NAME)
        assertEquals(setOf(DemoFormState.FIELD_CONTACT), remaining.keys)
    }

    private fun suggestedFormState(): DemoFormState = DemoFormState(
        revision = 7,
        suggestionPatch = FormPatch(
            pageId = DemoFormState.PAGE_ID,
            baseRevision = 7,
            changes = listOf(
                FieldChange(
                    fieldId = DemoFormState.FIELD_NAME,
                    previousValue = JsonPrimitive("未命名资料"),
                    newValue = JsonPrimitive("建议名称"),
                    reason = "从用户输入中识别",
                    confidence = 0.92,
                    sourceReferences = listOf(SourceReference("user-input", "message-1", "用户输入")),
                ),
            ),
        ),
    )

    private fun suggestions(): Map<String, BusinessFieldSuggestion> = linkedMapOf(
        DemoFormState.FIELD_NAME to BusinessFieldSuggestion(
            fieldId = DemoFormState.FIELD_NAME,
            value = JsonPrimitive("建议名称"),
            source = "用户输入",
            confidence = 0.92,
        ),
        DemoFormState.FIELD_CONTACT to BusinessFieldSuggestion(
            fieldId = DemoFormState.FIELD_CONTACT,
            value = JsonPrimitive("李老师"),
            source = "用户输入",
            confidence = 0.81,
        ),
    )
}
