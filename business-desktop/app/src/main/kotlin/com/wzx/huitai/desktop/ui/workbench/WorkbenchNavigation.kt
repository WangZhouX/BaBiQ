package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.business.auth.BusinessNavigationTarget

@Composable
fun WorkbenchNavigation(
    items: List<BusinessNavigationTarget>,
    selectedPath: String = "/",
    onSelected: (BusinessNavigationTarget) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(88.dp).fillMaxHeight().testTag(WorkbenchTags.NAVIGATION),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val visible = if (items.isEmpty()) listOf(BusinessNavigationTarget("WORKBENCH", "/", "工作台")) else items
        visible.forEach { item ->
            TextButton(
                onClick = { onSelected(item) },
                modifier = Modifier
                    .testTag(WorkbenchTags.navItem(item.path))
                    .semantics { selected = item.path == selectedPath },
            ) {
                Text(
                    text = item.title,
                    color = if (item.path == selectedPath) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
