package com.wzx.huitai.desktop.ui.layout

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 小律助手采用停靠式推挤布局时的纯宽度计算结果。 */
data class BusinessDesktopDockLayout(
    val availableWidth: Dp,
    val businessWidth: Dp,
    val dividerWidth: Dp,
    val assistantWidth: Dp,
    val canExpand: Boolean,
    val assistantExpanded: Boolean,
)

/** 业务区与小律助手之间的停靠布局策略，所有输入和输出单位均为 dp。 */
object BusinessDesktopLayoutPolicy {
    val minimumBusinessWidth: Dp = 640.dp
    val minimumAssistantWidth: Dp = 360.dp
    val maximumAssistantWidth: Dp = 720.dp
    val dividerWidth: Dp = 8.dp
    val defaultAssistantWidth: Dp = 460.dp
    val expandThreshold: Dp = minimumBusinessWidth + dividerWidth + minimumAssistantWidth

    /**
     * 策略支持的最大归一化宽度：2^20 dp，远大于现实多屏桌面范围。
     *
     * 在该量级 Float 的 ULP 仍可表达 8dp 分隔条和 360..720dp 助手；更大的有限输入
     * 会失去真实布局分辨率，因此统一钳制到此上限。
     */
    val maximumSupportedWidth: Dp = 1_048_576.dp

    /**
     * 计算不重叠的业务区与助手停靠区宽度。
     *
     * 窗口不足 [expandThreshold] 时，即使请求展开，也会安全回退为收起状态。
     */
    fun resolveDocked(
        availableWidth: Dp,
        assistantExpanded: Boolean = false,
        requestedAssistantWidth: Dp = defaultAssistantWidth,
    ): BusinessDesktopDockLayout {
        val width = availableWidth.normalizedAvailableWidth()
        val canExpand = width >= expandThreshold
        if (!assistantExpanded || !canExpand) {
            return collapsedLayout(width, canExpand)
        }

        val dynamicMaximum = minOf(
            maximumAssistantWidth,
            width - minimumBusinessWidth - dividerWidth,
        )
        val requestedWidth = requestedAssistantWidth
            .normalizedRequestedAssistantWidth()
            .coerceIn(minimumAssistantWidth, dynamicMaximum)
        val businessWidth = width - dividerWidth - requestedWidth
        val rowPrefix = businessWidth + dividerWidth
        val assistantWidth = exactAssistantWidth(
            availableWidth = width,
            rowPrefix = rowPrefix,
            requestedWidth = requestedWidth,
            dynamicMaximum = dynamicMaximum,
        ) ?: return collapsedLayout(width, canExpand = true)

        return BusinessDesktopDockLayout(
            availableWidth = width,
            businessWidth = businessWidth,
            dividerWidth = dividerWidth,
            assistantWidth = assistantWidth,
            canExpand = true,
            assistantExpanded = true,
        )
    }

    /**
     * 根据分隔条的水平拖动增量调整助手宽度，参数单位均为 dp。
     *
     * 分隔条位于助手左侧，因此向左拖（负 [dragDeltaX]）会增加助手宽度。
     */
    fun resizeAssistantWidth(
        current: Dp,
        dragDeltaX: Dp,
        availableWidth: Dp,
    ): Dp {
        val width = availableWidth.normalizedAvailableWidth()
        if (width < expandThreshold) return 0.dp

        val dynamicMaximum = minOf(
            maximumAssistantWidth,
            width - minimumBusinessWidth - dividerWidth,
        )
        val safeCurrent = current.normalizedRequestedAssistantWidth()
        val safeDelta = dragDeltaX.takeIf { it.value.isFinite() } ?: 0.dp
        val requested = (safeCurrent - safeDelta).normalizedRequestedAssistantWidth()
        return requested.coerceIn(minimumAssistantWidth, dynamicMaximum)
    }

    private fun Dp.normalizedAvailableWidth(): Dp =
        if (value.isFinite() && this >= 0.dp) coerceAtMost(maximumSupportedWidth) else 0.dp

    private fun Dp.normalizedRequestedAssistantWidth(): Dp =
        if (value.isFinite()) coerceAtLeast(0.dp) else defaultAssistantWidth

    private fun collapsedLayout(
        availableWidth: Dp,
        canExpand: Boolean,
    ): BusinessDesktopDockLayout = BusinessDesktopDockLayout(
        availableWidth = availableWidth,
        businessWidth = availableWidth,
        dividerWidth = 0.dp,
        assistantWidth = 0.dp,
        canExpand = canExpand,
        assistantExpanded = false,
    )

    /** 只检查固定数量的相邻 Float 候选，不做不受控的 ULP 搜索。 */
    private fun exactAssistantWidth(
        availableWidth: Dp,
        rowPrefix: Dp,
        requestedWidth: Dp,
        dynamicMaximum: Dp,
    ): Dp? {
        val residualWidth = availableWidth - rowPrefix
        val candidates = listOf(
            requestedWidth,
            residualWidth,
            Math.nextDown(residualWidth.value).dp,
            Math.nextUp(residualWidth.value).dp,
        )
        return candidates.firstOrNull { candidate ->
            candidate.value.isFinite() &&
                candidate in minimumAssistantWidth..dynamicMaximum &&
                rowPrefix + candidate == availableWidth
        }
    }

    // Task 6 重写 BusinessDesktopShell 后删除以下旧三栏布局兼容桥。
    @Deprecated("仅供旧 BusinessDesktopShell 过渡；请使用 resolveDocked")
    val wideThreshold: Dp = 1280.dp

    @Deprecated("仅供旧 BusinessDesktopShell 过渡；请使用 resolveDocked")
    val mediumThreshold: Dp = 1024.dp

    @Deprecated("仅供旧 BusinessDesktopShell 过渡；请使用 resolveDocked")
    val wideNavigationWidth: Dp = 210.dp

    @Deprecated("仅供旧 BusinessDesktopShell 过渡；请使用 resolveDocked")
    val mediumNavigationWidth: Dp = 72.dp

    @Deprecated("仅供旧 BusinessDesktopShell 过渡；请使用 resolveDocked")
    val wideAgentWidth: Dp = 420.dp

    @Deprecated("仅供旧 BusinessDesktopShell 过渡；请使用 resolveDocked")
    val mediumAgentWidth: Dp = 360.dp

    @Deprecated("仅供旧 BusinessDesktopShell 过渡；请使用 resolveDocked")
    val collapsedAgentWidth: Dp = 52.dp

    @Deprecated("仅供旧 BusinessDesktopShell 过渡；请使用 resolveDocked")
    val minimumFormWidth: Dp = 560.dp

    @Deprecated("仅供旧 BusinessDesktopShell 过渡；请使用 resolveDocked")
    fun resolve(
        availableWidth: Dp,
        agentPanelExpanded: Boolean = true,
    ): BusinessDesktopLayout {
        val width = if (availableWidth.value.isFinite()) availableWidth.coerceAtLeast(0.dp) else 0.dp
        return when {
            width >= wideThreshold -> legacyFixedRailLayout(
                width = width,
                mode = BusinessDesktopLayoutMode.WIDE,
                navigationWidth = wideNavigationWidth,
                expandedAgentWidth = wideAgentWidth,
                agentPanelExpanded = agentPanelExpanded,
            )
            width >= mediumThreshold -> legacyFixedRailLayout(
                width = width,
                mode = BusinessDesktopLayoutMode.MEDIUM,
                navigationWidth = mediumNavigationWidth,
                expandedAgentWidth = mediumAgentWidth,
                agentPanelExpanded = agentPanelExpanded,
            )
            else -> BusinessDesktopLayout(
                mode = BusinessDesktopLayoutMode.COMPACT,
                navigationWidth = 0.dp,
                formWidth = width,
                agentWidth = 0.dp,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyFixedRailLayout(
        width: Dp,
        mode: BusinessDesktopLayoutMode,
        navigationWidth: Dp,
        expandedAgentWidth: Dp,
        agentPanelExpanded: Boolean,
    ): BusinessDesktopLayout {
        val agentWidth = if (agentPanelExpanded) expandedAgentWidth else collapsedAgentWidth
        return BusinessDesktopLayout(
            mode = mode,
            navigationWidth = navigationWidth,
            formWidth = width - navigationWidth - agentWidth,
            agentWidth = agentWidth,
        )
    }
}

/** Task 6 删除：旧三栏 Shell 的临时编译兼容类型。 */
@Deprecated("仅供旧 BusinessDesktopShell 过渡")
enum class BusinessDesktopLayoutMode {
    WIDE,
    MEDIUM,
    COMPACT,
}

/** Task 6 删除：旧三栏 Shell 的临时编译兼容类型。 */
@Deprecated("仅供旧 BusinessDesktopShell 过渡")
data class BusinessDesktopLayout(
    val mode: BusinessDesktopLayoutMode,
    val navigationWidth: Dp,
    val formWidth: Dp,
    val agentWidth: Dp,
)
