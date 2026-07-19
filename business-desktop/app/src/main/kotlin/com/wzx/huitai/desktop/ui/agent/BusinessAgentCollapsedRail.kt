package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.ui.shell.BusinessUiTags

/** 宽屏和中屏收起业务 Agent 后保留的紧凑入口，不复制任何会话状态。 */
@Composable
fun BusinessAgentCollapsedRail(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag(BusinessUiTags.AGENT_COLLAPSED_RAIL),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        ) {
            TextButton(
                onClick = onExpand,
                modifier = Modifier.semantics { contentDescription = "展开业务 Agent" },
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = "A\nG\nE\nN\nT",
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
