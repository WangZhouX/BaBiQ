package com.wzx.babiq.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.wzx.babiq.desktop.app.BaBiQDesktopApp

internal val DefaultWindowSize = DpSize(1920.dp, 860.dp)

/**
 * 桌面端进程入口。
 *
 * `application + Window` 让窗口图标在 Compose 上下文中加载；真正的业务组合仍放在 `BaBiQDesktopApp()`，
 * 入口只负责窗口标题、图标和初始尺寸。
 */
fun main() = application {
	Window(
		title = "BaBiQ",
		icon = painterResource(WINDOW_ICON_RESOURCE),
		state = WindowState(size = DefaultWindowSize),
		onCloseRequest = ::exitApplication,
	) {
		// 把业务 UI 作为窗口内容挂进去，后续所有状态和网络连接都从这个组合根节点往下传。
		BaBiQDesktopApp()
	}
}
