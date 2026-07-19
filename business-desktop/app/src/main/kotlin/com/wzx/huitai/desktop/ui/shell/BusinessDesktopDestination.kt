package com.wzx.huitai.desktop.ui.shell

/** 业务桌面的唯一导航真相；响应式布局只映射视觉内容，不改写该值。 */
enum class BusinessDesktopDestination(val label: String, val compactLabel: String = label) {
    WORKBENCH("工作台", "台"),
    DATA_ENTRY("资料录入", "录入"),
    RUN_HISTORY("运行记录", "记录"),
    SETTINGS("设置"),
    AGENT("Agent"),
}

internal val businessSidebarDestinations = listOf(
    BusinessDesktopDestination.WORKBENCH,
    BusinessDesktopDestination.DATA_ENTRY,
    BusinessDesktopDestination.RUN_HISTORY,
    BusinessDesktopDestination.SETTINGS,
)

/** compact 只支持三种视觉页；通用占位缩小时回退资料录入，但 canonical 值保持不变。 */
internal fun BusinessDesktopDestination.compactVisualDestination(): BusinessDesktopDestination = when (this) {
    BusinessDesktopDestination.SETTINGS -> BusinessDesktopDestination.SETTINGS
    BusinessDesktopDestination.AGENT -> BusinessDesktopDestination.AGENT
    BusinessDesktopDestination.WORKBENCH,
    BusinessDesktopDestination.DATA_ENTRY,
    BusinessDesktopDestination.RUN_HISTORY,
    -> BusinessDesktopDestination.DATA_ENTRY
}

/** 非 compact 的 Agent 目的地由固定右栏承载，中心确定性回退资料录入。 */
internal fun BusinessDesktopDestination.wideCenterDestination(): BusinessDesktopDestination = when (this) {
    BusinessDesktopDestination.AGENT -> BusinessDesktopDestination.DATA_ENTRY
    else -> this
}
