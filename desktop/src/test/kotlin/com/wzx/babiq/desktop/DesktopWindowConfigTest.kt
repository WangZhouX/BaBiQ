package com.wzx.babiq.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopWindowConfigTest {

	@Test
	fun `桌面窗口使用项目自定义图标资源`() {
		assertEquals("icons/babiq-window-icon.png", WINDOW_ICON_RESOURCE)
		assertNotNull(
			Thread.currentThread().contextClassLoader.getResource(WINDOW_ICON_RESOURCE),
			"窗口图标资源必须随 desktop main resources 一起打包",
		)
	}
}
