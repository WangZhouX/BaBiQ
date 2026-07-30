package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Web 工作台到 Compose 的视觉契约。
 *
 * 这些常量直接对应 Web 源码中的固定尺寸和色值，集中维护可以避免各卡片再次使用
 * Material 默认主题而产生紫色、圆角过大或间距漂移。
 */
object BusinessWorkbenchVisualSpec {
    val topHeaderHeight = 64.dp
    val navigationWidth = 88.dp
    val navigationItemSize = 72.dp
    val navigationItemGap = 8.dp
    val pagePadding = 10.dp
    val columnGap = 10.dp
    val quickEntranceHeight = 180.dp
    val profileHeight = 180.dp
    const val leftColumnWeight = 75.1f
    const val rightColumnWeight = 23.7f

    val primary = Color(0xFF216DFF)
    val pageBackground = Color(0xFFF7F7F7)
    val surface = Color.White
    val border = Color(0xFFE6E6E6)
    val divider = Color(0xFFF2F2F2)
    val textPrimary = Color(0xFF333333)
    val textSecondary = Color(0xFF666666)
    val textTertiary = Color(0xFF8C8C8C)
    val activeNavigation = Color(0xFFEDF3FF)
    val success = Color(0xFF40BC5B)
    val warning = Color(0xFFFF8100)
    val danger = Color(0xFFFF343A)
}
