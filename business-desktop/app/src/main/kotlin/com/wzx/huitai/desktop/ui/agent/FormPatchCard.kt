package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.presentation.form.FormPatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** 在任何页面写入发生前完整展示表单补丁的旧值、新值、原因、置信度和来源。 */
@Composable
fun FormPatchCard(
    patch: FormPatch,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("form-patch-card"),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("待应用的表单变更", style = MaterialTheme.typography.titleMedium)
            Text(
                "绑定页面版本 ${patch.baseRevision} · 应用前不会修改业务数据",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            patch.changes.forEach { change ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(fieldLabel(change.fieldId), style = MaterialTheme.typography.labelLarge)
                    Text("旧值：${displayValue(change.previousValue)}")
                    Text("新值：${displayValue(change.newValue)}")
                    Text("原因：${change.reason}")
                    Text("置信度 ${(change.confidence * 100).roundToInt()}%")
                    val source = change.sourceReferences.firstOrNull()?.let {
                        it.label ?: "${it.type}/${it.id}"
                    } ?: "无"
                    Text("来源：$source")
                }
            }
        }
    }
}

private fun displayValue(value: JsonElement?): String = when (value) {
    null, JsonNull -> "空"
    is JsonPrimitive -> value.content
    else -> value.toString()
}

private fun fieldLabel(fieldId: String): String = when (fieldId) {
    DemoFormState.FIELD_NAME -> "资料名称"
    DemoFormState.FIELD_TYPE -> "资料类型"
    DemoFormState.FIELD_CONTACT -> "联系人"
    DemoFormState.FIELD_AMOUNT -> "金额"
    DemoFormState.FIELD_DATE -> "日期"
    DemoFormState.FIELD_STATUS -> "状态"
    DemoFormState.FIELD_DETAILS -> "详细说明"
    else -> "字段 $fieldId"
}
