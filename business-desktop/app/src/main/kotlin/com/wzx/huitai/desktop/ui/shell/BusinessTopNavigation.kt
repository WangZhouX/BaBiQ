package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object BusinessTopNavigationTags {
    const val ROOT = "business-top-navigation"
    const val GROUP = "business-top-navigation-group"
    const val BRAND = "business-top-navigation-brand"
    const val LOGO = "business-top-navigation-logo"
    const val WORKBENCH = "navigation-workbench"
    const val DATA_ENTRY = "navigation-data_entry"
    const val RUN_HISTORY = "navigation-run_history"
    const val SETTINGS = "navigation-settings"
}

private data class TopNavigationItem(
    val destination: BusinessDesktopDestination,
    val tag: String,
)

/** 顶部工具栏只承载全局设置；业务导航由左侧栏负责。 */
@Composable
fun BusinessTopNavigation(
    selectedDestination: BusinessDesktopDestination,
    onDestinationSelected: (BusinessDesktopDestination) -> Unit,
    onComposed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SideEffect(onComposed)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag(BusinessTopNavigationTags.ROOT),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(51.dp)
                    .padding(horizontal = 20.dp)
                    .selectableGroup()
                    .testTag(BusinessTopNavigationTags.GROUP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TopNavigationButton(
                    item = TopNavigationItem(
                        BusinessDesktopDestination.SETTINGS,
                        BusinessTopNavigationTags.SETTINGS,
                    ),
                    selected = selectedDestination == BusinessDesktopDestination.SETTINGS,
                    minWidth = 88.dp,
                    compact = false,
                    onClick = { onDestinationSelected(BusinessDesktopDestination.SETTINGS) },
                )
            }
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun TopNavigationButton(
    item: TopNavigationItem,
    selected: Boolean,
    minWidth: Dp,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .width(IntrinsicSize.Min)
            .widthIn(min = minWidth)
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            )
            .semantics { contentDescription = "${item.destination.label}导航" }
            .testTag(item.tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.destination.label,
            modifier = Modifier.fillMaxWidth(),
            color = foreground,
            fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}
