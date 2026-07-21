package com.wzx.huitai.desktop.ui.shell

/** 业务桌面的唯一导航真相；响应式布局只映射视觉内容，不改写该值。 */
enum class BusinessDesktopDestination(val label: String, val compactLabel: String = label) {
    WORKBENCH("工作台", "台"),
    DATA_ENTRY("资料录入", "录入"),
    RUN_HISTORY("运行记录", "记录"),
    SETTINGS("设置"),
}
