package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.auth.BusinessNavigationTarget
import com.wzx.huitai.desktop.ui.workbench.BusinessWorkbenchVisualSpec

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
 * 渲染业务桌面唯一的左侧导航。
 *
 * 工作台模式严格使用 Web 的 88dp 图标栏；其他原有桌面页面继续使用文字导航，
 * 避免本次 OA 视觉迁移扩大到资料录入和 Agent 页面。
 */
@Composable
fun BusinessSidebar(
    selected: BusinessDesktopDestination = BusinessDesktopDestination.DATA_ENTRY,
    workbenchItems: List<BusinessNavigationTarget> = emptyList(),
    selectedWorkbenchPath: String = "/",
    onSelected: (BusinessDesktopDestination) -> Unit = {},
    onWorkbenchSelected: (String) -> Unit = {},
    onComposed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SideEffect(onComposed)
    if (selected == BusinessDesktopDestination.WORKBENCH) {
        WorkbenchSidebar(
            items = workbenchItems,
            selectedPath = selectedWorkbenchPath,
            onSelected = onWorkbenchSelected,
            modifier = modifier,
        )
        return
    }
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

/** 工作台采用 Web 的 72x72 图标菜单项，只保留一层导航。 */
@Composable
private fun WorkbenchSidebar(
    items: List<BusinessNavigationTarget>,
    selectedPath: String,
    onSelected: (String) -> Unit,
    modifier: Modifier,
) {
    val visible = items.ifEmpty {
        listOf(BusinessNavigationTarget("WORKBENCH", "/", "工作台"))
    }
    Box(
        modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .then(
                if (selectedPath == "/") Modifier
                else Modifier.testTag(com.wzx.huitai.desktop.ui.workbench.WorkbenchTags.NAVIGATION),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BusinessWorkbenchVisualSpec.surface)
                .selectableGroup()
                .testTag(BusinessSidebarTags.ROOT)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BusinessWorkbenchVisualSpec.navigationItemGap),
        ) {
            visible.forEach { item ->
                WorkbenchSidebarButton(
                    item = item,
                    selected = item.path == selectedPath,
                    onSelected = onSelected,
                )
            }
        }
    }
}

/** 单个工作台菜单项用简洁线框图标和标题复刻 Web 左栏信息层级。 */
@Composable
private fun WorkbenchSidebarButton(
    item: BusinessNavigationTarget,
    selected: Boolean,
    onSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .size(BusinessWorkbenchVisualSpec.navigationItemSize)
            .clip(RoundedCornerShape(2.dp))
            .background(if (selected) BusinessWorkbenchVisualSpec.activeNavigation else Color.Transparent)
            .selectable(
                selected = selected,
                onClick = { onSelected(item.path) },
                role = Role.Tab,
            )
            .semantics { contentDescription = item.title }
            .testTag("business-workbench-nav-${item.path.replace('/', '_').ifBlank { "root" }}")
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = navigationGlyph(item),
            color = if (selected) BusinessWorkbenchVisualSpec.primary else BusinessWorkbenchVisualSpec.textSecondary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) BusinessWorkbenchVisualSpec.primary else BusinessWorkbenchVisualSpec.textPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 不引入额外图标依赖，用稳定的中文字形保持各业务入口可识别。 */
private fun navigationGlyph(item: BusinessNavigationTarget): String = when {
    item.path == "/" || item.title == "工作台" -> "▰"
    "顾问" in item.title -> "♟"
    "客户" in item.title -> "♟"
    "案件" in item.title -> "◆"
    "工具" in item.title -> "▥"
    "团队" in item.title -> "♣"
    else -> item.title.take(1)
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
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
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
