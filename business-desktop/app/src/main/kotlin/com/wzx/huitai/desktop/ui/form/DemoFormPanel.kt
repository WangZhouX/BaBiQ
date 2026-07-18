package com.wzx.huitai.desktop.ui.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.desktop.state.BusinessFieldSuggestion
import com.wzx.huitai.desktop.ui.shell.BusinessUiTags

private data class DemoFieldDefinition(
    val fieldId: String,
    val label: String,
    val multiline: Boolean = false,
)

private val demoFieldDefinitions = listOf(
    DemoFieldDefinition(DemoFormState.FIELD_NAME, "资料名称"),
    DemoFieldDefinition(DemoFormState.FIELD_TYPE, "资料类型"),
    DemoFieldDefinition(DemoFormState.FIELD_CONTACT, "联系人"),
    DemoFieldDefinition(DemoFormState.FIELD_AMOUNT, "金额"),
    DemoFieldDefinition(DemoFormState.FIELD_DATE, "日期"),
    DemoFieldDefinition(DemoFormState.FIELD_STATUS, "状态"),
    DemoFieldDefinition(DemoFormState.FIELD_DETAILS, "详细说明", multiline = true),
)

/** 返回用户编辑后仅移除目标字段建议的新映射，不修改页面已提交值。 */
fun suggestionsAfterUserEdit(
    suggestions: Map<String, BusinessFieldSuggestion>,
    editedFieldId: String,
): Map<String, BusinessFieldSuggestion> = LinkedHashMap(suggestions).apply { remove(editedFieldId) }

/** 展示通用七字段表单，并把所有编辑和接受行为上送为强类型回调。 */
@Composable
fun DemoFormPanel(
    state: DemoFormState,
    suggestions: Map<String, BusinessFieldSuggestion> = emptyMap(),
    onFieldEdited: (fieldId: String, value: String) -> Unit = { _, _ -> },
    onSuggestionsChanged: (Map<String, BusinessFieldSuggestion>) -> Unit = {},
    onAcceptSuggestion: (fieldId: String, baseRevision: Long) -> Unit = { _, _ -> },
    onAcceptAllSuggestions: (baseRevision: Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val patchRevision = state.suggestionPatch?.baseRevision ?: state.revision
    Surface(
        modifier = modifier.fillMaxSize().testTag(BusinessUiTags.FORM_PANEL),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("通用资料录入", style = MaterialTheme.typography.headlineSmall)
            Text(
                "页面版本 ${state.revision}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            demoFieldDefinitions.forEach { definition ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(definition.label, style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = state.values.valueOf(definition.fieldId),
                        onValueChange = { value ->
                            onFieldEdited(definition.fieldId, value)
                            if (definition.fieldId in suggestions) {
                                onSuggestionsChanged(suggestionsAfterUserEdit(suggestions, definition.fieldId))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (definition.multiline) 88.dp else 52.dp)
                            .semantics { contentDescription = definition.label }
                            .testTag("form-field-${definition.fieldId}"),
                        singleLine = !definition.multiline,
                        minLines = if (definition.multiline) 3 else 1,
                    )
                    suggestions[definition.fieldId]?.let { suggestion ->
                        FieldSuggestionDecoration(
                            suggestion = suggestion,
                            onAccept = { onAcceptSuggestion(definition.fieldId, patchRevision) },
                        )
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                Button(
                    onClick = { onAcceptAllSuggestions(patchRevision) },
                    modifier = Modifier.testTag("accept-all-suggestions"),
                ) {
                    Text("接受全部建议")
                }
            }
        }
    }
}
