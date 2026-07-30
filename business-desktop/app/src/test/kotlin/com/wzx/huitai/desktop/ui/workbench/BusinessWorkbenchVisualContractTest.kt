package com.wzx.huitai.desktop.ui.workbench

import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 固化 Web 工作台的关键尺寸，避免后续 Compose 调整再次退回通用 Material 卡片布局。
 */
class BusinessWorkbenchVisualContractTest {
    @Test
    fun `workbench keeps the web shell and two column proportions`() {
        assertEquals(64.dp, BusinessWorkbenchVisualSpec.topHeaderHeight)
        assertEquals(88.dp, BusinessWorkbenchVisualSpec.navigationWidth)
        assertEquals(10.dp, BusinessWorkbenchVisualSpec.pagePadding)
        assertEquals(10.dp, BusinessWorkbenchVisualSpec.columnGap)
        assertEquals(75.1f, BusinessWorkbenchVisualSpec.leftColumnWeight)
        assertEquals(23.7f, BusinessWorkbenchVisualSpec.rightColumnWeight)
    }

    @Test
    fun `workbench keeps the web fixed card heights and palette`() {
        assertEquals(180.dp, BusinessWorkbenchVisualSpec.quickEntranceHeight)
        assertEquals(180.dp, BusinessWorkbenchVisualSpec.profileHeight)
        assertEquals(Color(0xFF216DFF), BusinessWorkbenchVisualSpec.primary)
        assertEquals(Color(0xFFF7F7F7), BusinessWorkbenchVisualSpec.pageBackground)
        assertEquals(Color(0xFFE6E6E6), BusinessWorkbenchVisualSpec.border)
        assertEquals(Color(0xFF333333), BusinessWorkbenchVisualSpec.textPrimary)
        assertEquals(Color(0xFF666666), BusinessWorkbenchVisualSpec.textSecondary)
        assertEquals(Color(0xFF8C8C8C), BusinessWorkbenchVisualSpec.textTertiary)
    }
}
