package com.wzx.huitai.desktop.ui.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 业务桌面在当前窗口宽度下采用的稳定布局模式。 */
enum class BusinessDesktopLayoutMode {
    WIDE,
    MEDIUM,
    COMPACT,
}

/**
 * 三栏布局计算结果。
 *
 * 三个宽度之和永不超过传入可用宽度，因而调用方可以直接按顺序放置，避免表单与 Agent 面板重叠。
 */
data class BusinessDesktopLayout(
    val mode: BusinessDesktopLayoutMode,
    val navigationWidth: Dp,
    val formWidth: Dp,
    val agentWidth: Dp,
)

/** 把窗口宽度确定性映射为宽屏、中屏或单页签紧凑布局。 */
object BusinessDesktopLayoutPolicy {
    val wideThreshold: Dp = 1280.dp
    val mediumThreshold: Dp = 1024.dp
    val wideNavigationWidth: Dp = 210.dp
    val mediumNavigationWidth: Dp = 72.dp
    val wideAgentWidth: Dp = 420.dp
    val mediumAgentWidth: Dp = 360.dp
    val minimumFormWidth: Dp = 560.dp

    /**
     * 计算互不重叠的栏位宽度。
     *
     * 非法负宽度先收敛为零；阈值保证宽屏和中屏天然保留至少 560dp 表单区。
     */
    fun resolve(
        availableWidth: Dp,
    ): BusinessDesktopLayout {
        val width = availableWidth.coerceAtLeast(0.dp)
        return when {
            width >= wideThreshold -> BusinessDesktopLayout(
                mode = BusinessDesktopLayoutMode.WIDE,
                navigationWidth = wideNavigationWidth,
                formWidth = width - wideNavigationWidth - wideAgentWidth,
                agentWidth = wideAgentWidth,
            )
            width >= mediumThreshold -> BusinessDesktopLayout(
                mode = BusinessDesktopLayoutMode.MEDIUM,
                navigationWidth = mediumNavigationWidth,
                formWidth = width - mediumNavigationWidth - mediumAgentWidth,
                agentWidth = mediumAgentWidth,
            )
            else -> BusinessDesktopLayout(
                mode = BusinessDesktopLayoutMode.COMPACT,
                navigationWidth = 0.dp,
                formWidth = width,
                agentWidth = 0.dp,
            )
        }
    }
}
