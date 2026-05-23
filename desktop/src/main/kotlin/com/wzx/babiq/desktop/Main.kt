package com.wzx.babiq.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import com.wzx.babiq.desktop.app.BaBiQDesktopApp

/**
 * 桌面端进程入口。
 *
 * `singleWindowApplication` 是 Compose Desktop 提供的最小窗口启动方式；真正的业务组合
 * 都放在 `BaBiQDesktopApp()`，让入口只负责窗口标题和初始尺寸。
 */
fun main() = singleWindowApplication(
	title = "BaBiQ",
	state = WindowState(size = DpSize(1180.dp, 780.dp)),
) {
	// 把业务 UI 作为窗口内容挂进去，后续所有状态和网络连接都从这个组合根节点往下传。
	BaBiQDesktopApp()
}
