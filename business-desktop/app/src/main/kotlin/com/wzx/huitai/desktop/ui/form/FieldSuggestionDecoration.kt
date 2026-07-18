package com.wzx.huitai.desktop.ui.form

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.state.BusinessFieldSuggestion
import kotlin.math.roundToInt

/** 在字段下方展示 Agent 建议的来源、置信度和单字段接受入口。 */
@Composable
fun FieldSuggestionDecoration(
    suggestion: BusinessFieldSuggestion,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("suggestion-${suggestion.fieldId}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("来源：${suggestion.source}", style = MaterialTheme.typography.labelSmall)
            suggestion.confidence?.let { confidence ->
                Text(
                    "置信度 ${(confidence * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        TextButton(
            onClick = onAccept,
            modifier = Modifier.testTag("accept-suggestion-${suggestion.fieldId}"),
        ) {
            Text("接受")
        }
    }
}
