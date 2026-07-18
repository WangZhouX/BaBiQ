package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** 展示受连接状态控制的消息输入框，只通过回调提交用户输入。 */
@Composable
fun AgentComposer(
    value: String,
    enabled: Boolean,
    onValueChanged: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChanged,
            enabled = enabled,
            placeholder = { Text("告诉 Agent 需要整理或修改的内容") },
            modifier = Modifier.weight(1f).testTag("agent-composer-input"),
            minLines = 2,
            maxLines = 4,
        )
        Button(
            onClick = onSend,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier.testTag("agent-composer-send"),
        ) {
            Text("发送")
        }
    }
}
