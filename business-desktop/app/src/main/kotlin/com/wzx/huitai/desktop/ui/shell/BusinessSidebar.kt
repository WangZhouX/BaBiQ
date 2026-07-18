package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 框架构建允许出现的四个通用导航目的地。 */
enum class BusinessNavigationItem(val label: String, val compactLabel: String) {
    WORKBENCH("工作台", "台"),
    DATA_ENTRY("资料录入", "录入"),
    RUN_HISTORY("运行记录", "记录"),
    SETTINGS("设置", "设置"),
}

/** 渲染不包含任何具体 OA 业务名词的通用左侧导航。 */
@Composable
fun BusinessSidebar(
    selected: BusinessNavigationItem = BusinessNavigationItem.DATA_ENTRY,
    compact: Boolean = false,
    onSelected: (BusinessNavigationItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(BusinessUiTags.SIDEBAR)
            .padding(horizontal = if (compact) 6.dp else 12.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (compact) "导航" else "业务导航",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        BusinessNavigationItem.entries.forEach { item ->
            val active = item == selected
            Text(
                text = if (compact) item.compactLabel else item.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelected(item) }
                    .padding(horizontal = if (compact) 4.dp else 12.dp, vertical = 12.dp)
                    .semantics { contentDescription = item.label }
                    .testTag("navigation-${item.name.lowercase()}"),
            )
        }
    }
}
