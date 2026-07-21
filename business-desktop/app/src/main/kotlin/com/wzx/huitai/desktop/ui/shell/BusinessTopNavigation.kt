package com.wzx.huitai.desktop.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzx.huitai.desktop.ui.brand.BusinessBrandResources

object BusinessTopNavigationTags {
    const val ROOT = "business-top-navigation"
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

private val primaryNavigationItems = listOf(
    TopNavigationItem(BusinessDesktopDestination.WORKBENCH, BusinessTopNavigationTags.WORKBENCH),
    TopNavigationItem(BusinessDesktopDestination.DATA_ENTRY, BusinessTopNavigationTags.DATA_ENTRY),
    TopNavigationItem(BusinessDesktopDestination.RUN_HISTORY, BusinessTopNavigationTags.RUN_HISTORY),
)

/** 翔鸟律智桌面端唯一一级导航；小律助手由右下角吉祥物控制，不占导航目的地。 */
@Composable
fun BusinessTopNavigation(
    selectedDestination: BusinessDesktopDestination,
    onDestinationSelected: (BusinessDesktopDestination) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .testTag(BusinessTopNavigationTags.ROOT),
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints {
            val compact = maxWidth < 1008.dp
            val horizontalPadding = if (compact) 12.dp else 20.dp
            val brandWidth = if (compact) 184.dp else 212.dp
            val itemWidth = if (compact) 88.dp else 104.dp
            val itemGap = if (compact) 2.dp else 8.dp

            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(63.dp)
                        .padding(horizontal = horizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandBlock(compact = compact, width = brandWidth)
                    Spacer(Modifier.width(if (compact) 8.dp else 20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(itemGap)) {
                        primaryNavigationItems.forEach { item ->
                            TopNavigationButton(
                                item = item,
                                selected = selectedDestination == item.destination,
                                width = itemWidth,
                                compact = compact,
                                onClick = { onDestinationSelected(item.destination) },
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TopNavigationButton(
                        item = TopNavigationItem(
                            BusinessDesktopDestination.SETTINGS,
                            BusinessTopNavigationTags.SETTINGS,
                        ),
                        selected = selectedDestination == BusinessDesktopDestination.SETTINGS,
                        width = itemWidth,
                        compact = compact,
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
}
@Composable
private fun BrandBlock(compact: Boolean, width: Dp) {
    Row(
        modifier = Modifier
            .width(width)
            .testTag(BusinessTopNavigationTags.BRAND),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = BusinessBrandResources.logoImageBitmap(),
            contentDescription = "翔鸟律智 Logo",
            modifier = Modifier
                .size(if (compact) 34.dp else 38.dp)
                .clip(RoundedCornerShape(8.dp))
                .testTag(BusinessTopNavigationTags.LOGO),
        )
        Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
        Text(
            text = "翔鸟律智桌面端",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = if (compact) 15.sp else 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun TopNavigationButton(
    item: TopNavigationItem,
    selected: Boolean,
    width: Dp,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .widthIn(min = width, max = width)
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
            color = foreground,
            fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
