package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.ui.brand.BusinessBrandResources

object BusinessAssistantChromeTags {
    const val MASCOT: String = "business-assistant-mascot"
    const val MASCOT_SLOT: String = "business-assistant-mascot-slot"
    const val MESSAGES: String = "business-agent-messages"
    const val COMPOSER: String = "agent-composer-root"
    const val ATTACHMENTS_CONTAINER: String = "agent-attachments-container"
    const val RESIZE_HANDLE: String = "business-assistant-resize-handle"
    const val RESIZE_RAIL: String = "business-assistant-resize-rail"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BusinessAssistantMascotButton(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionLabel = if (expanded) "收回小律智能助手" else "打开小律智能助手"
    val stateLabel = if (expanded) "小律智能助手已打开" else "小律智能助手已收回"

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        TooltipArea(
            tooltip = {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(
                        text = actionLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
        ) {
            Box(
                modifier = Modifier
                .requiredSize(112.dp)
                .testTag(BusinessAssistantChromeTags.MASCOT)
                .semantics {
                    contentDescription = actionLabel
                    stateDescription = stateLabel
                    role = Role.Button
                }
                .clickable(
                    role = Role.Button,
                    onClickLabel = actionLabel,
                    onClick = onToggle,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = BusinessBrandResources.mascotImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            }
        }
    }
}
