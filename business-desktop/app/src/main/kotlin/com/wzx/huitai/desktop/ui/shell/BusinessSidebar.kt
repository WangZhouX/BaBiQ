package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 左侧业务导航的稳定测试标记，不与顶部设置入口共用。 */
object BusinessSidebarTags {
    const val ROOT = "business-sidebar"
    const val WORKBENCH = "business-sidebar-navigation-workbench"
    const val DATA_ENTRY = "business-sidebar-navigation-data-entry"
    const val RUN_HISTORY = "business-sidebar-navigation-run-history"
    const val SETTINGS = "business-sidebar-navigation-settings"
}

private val sidebarTags = mapOf(
    BusinessDesktopDestination.WORKBENCH to BusinessSidebarTags.WORKBENCH,
    BusinessDesktopDestination.DATA_ENTRY to BusinessSidebarTags.DATA_ENTRY,
    BusinessDesktopDestination.RUN_HISTORY to BusinessSidebarTags.RUN_HISTORY,
    BusinessDesktopDestination.SETTINGS to BusinessSidebarTags.SETTINGS,
)

/**
 * 渲染由父 Shell 分配宽度的左侧业务导航。
 *
 * 组件只占满父容器，不内置宽度或 compact 分支；点击直接回传唯一的 canonical destination。
 */
@Composable
fun BusinessSidebar(
    selected: BusinessDesktopDestination = BusinessDesktopDestination.DATA_ENTRY,
    onSelected: (BusinessDesktopDestination) -> Unit = {},
    onComposed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SideEffect(onComposed)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .selectableGroup()
            .testTag(BusinessSidebarTags.ROOT)
            .padding(horizontal = 12.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "业务导航",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        businessSidebarDestinations.forEach { destination ->
            SidebarDestinationButton(
                destination = destination,
                selected = destination == selected,
                onSelected = onSelected,
            )
        }
        Spacer(Modifier.weight(1f))
        SidebarDestinationButton(
            destination = BusinessDesktopDestination.SETTINGS,
            selected = selected == BusinessDesktopDestination.SETTINGS,
            onSelected = onSelected,
        )
    }
}

@Composable
private fun SidebarDestinationButton(
    destination: BusinessDesktopDestination,
    selected: Boolean,
    onSelected: (BusinessDesktopDestination) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = destination.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .selectable(
                selected = selected,
                onClick = { onSelected(destination) },
                role = Role.Tab,
            )
            .semantics { contentDescription = destination.label }
            .testTag(sidebarTags.getValue(destination))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    )
}
