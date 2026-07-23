package com.wzx.huitai.desktop.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.auth.BusinessSliderState

@Composable
fun BusinessSliderVerification(
    state: BusinessSliderState,
    onCompleted: () -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(BusinessLoginTags.SLIDER),
    ) {
        Text(
            text = if (state == BusinessSliderState.REQUESTED) {
                "请拖动滑块完成验证"
            } else {
                "登录前需完成滑动验证"
            },
        )
    }

    if (state == BusinessSliderState.REQUESTED) {
        var progress by remember { mutableFloatStateOf(0f) }
        var completed by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismissed,
            title = { Text("安全验证") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("拖动滑块到最右侧后继续登录")
                    Slider(
                        value = progress,
                        onValueChange = { value ->
                            progress = value
                            if (value >= COMPLETION_THRESHOLD && !completed) {
                                completed = true
                                onCompleted()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "滑动验证" }
                            .testTag(BusinessLoginTags.SLIDER_CONTROL),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                Row(
                    modifier = Modifier.padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismissed,
                        modifier = Modifier.testTag(BusinessLoginTags.SLIDER_CANCEL),
                    ) {
                        Text("取消")
                    }
                }
            },
            modifier = Modifier.testTag(BusinessLoginTags.SLIDER_DIALOG),
        )
    }
}

private const val COMPLETION_THRESHOLD = 0.98f
