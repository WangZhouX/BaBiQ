package com.wzx.babiq.desktop

/**
 * 桌面窗口级配置。
 *
 * 图标资源路径集中在这里，Main.kt 和测试都读取同一份常量，避免后续换图标时只改了窗口入口却忘记更新资源校验。
 */
const val WINDOW_ICON_RESOURCE: String = "icons/babiq-window-icon.png"
